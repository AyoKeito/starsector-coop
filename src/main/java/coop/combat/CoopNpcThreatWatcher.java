package coop.combat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Host-side synthesis of everything the engine will not do to a mirror fleet (Phase 14).
 *
 * <h2>Why the watcher is the initiator, not a supervisor</h2>
 * The spikes (2026-08-19, {@code docs/PHASE14_SPIKE_NOTES.md}) disproved the original design's
 * premise. Vanilla hostile AI <em>sees</em> the guest mirror and <em>judges</em> it normally
 * ({@code pickEncounterOption(null, mirror, true)} returns ENGAGE/HOLD/DISENGAGE tracking fleet
 * strength) but never retasks to hunt it, and <b>no engine battle can form against it at all</b>:
 * NPC-vs-NPC initiation lives only in {@code BaseLocation.advance}'s pair loop, whose first gate is
 * {@code CampaignFleet.canBeEngaged()} — false while the {@code noCombat} fader the mirror re-asserts
 * every frame is live. An ENGAGE-picking hostile crossed the mirror at 10 su and an injected
 * {@code INTERCEPT} chase ran to contact; neither produced a battle or a single autoresolve round.
 *
 * <p>So there is no handoff race to win. This class simply <em>decides</em> when a hostile should
 * have caught the guest and pushes {@code ENGAGE_GUEST}; the guest then pilots that battle locally
 * against its own mirror of the same fleet. Visible pursuit is synthesized the same way — by
 * injecting {@code addAssignmentAtStart(INTERCEPT, mirror, ...)}, which the spike proved makes a
 * vanilla hostile genuinely chase (closed 389 &rarr; 17 su at 151 su/s) and complete harmlessly.
 *
 * <h2>The one load-bearing protection</h2>
 * {@code mirror.getBattle() != null} &rarr; {@code battle.leave(mirror, false)} is a
 * <b>recovery path, not an assertion</b>. The battle <em>pull-in</em> path bypasses
 * {@code canBeEngaged()} entirely: {@code FleetInteractionDialogPluginImpl.pullInNearbyFleets} runs
 * whenever the host opens any vanilla fleet dialog, honours only
 * {@link MemFlags#FLEET_IGNORES_OTHER_FLEETS}, and grants player-faction fleets (the mirror) a 700 su
 * join radius. The mirror carries that flag, but the eject stays because pull-in is reachable in
 * ordinary host play and a silently autoresolved mirror corrupts a fleet whose owner was never in a
 * battle. It runs every frame; everything else runs on {@link #SCAN_INTERVAL_MILLIS}.
 *
 * <h2>Customs</h2>
 * A non-hostile patrol next to a transponder-off guest mirror pushes {@code DIALOG_BEGIN}, which the
 * guest resolves locally against its own cargo ({@link CoopCustomsDialogStaging}). This is the
 * committed fix for the Phase 9 gap where faction fleets stopped reacting to the guest running dark
 * (memory {@code guest-transponder-reactions-gone}).
 *
 * <p>The trigger logic is a pure function ({@link #decide}) over a {@link FleetView}, so the
 * hostility / ENGAGE-pick / threshold / cooldown / not-during-a-battle rules are unit-tested without
 * an engine, in the same shape as {@code CoopGuestRouteMaterializer.RouteView}.
 */
public final class CoopNpcThreatWatcher {

    /**
     * Distance at which a hostile that has picked ENGAGE is handed to the guest.
     *
     * <p><b>Not a race threshold — a design choice.</b> No engine contact range exists against a
     * mirror (nothing can form a battle with it), so this is purely "how close should it look before
     * the guest is dropped into the fight". Sized from the spike's observed closing speeds: 57-193
     * su/s for pirate chasers, up to 340 su/s for a burn-17 patrol, i.e. roughly 1.5-2 s of closing at
     * the fast end.
     *
     * <p><b>Phase 20 obligation:</b> re-derive this as {@code 2 x p95 RTT x closing speed +
     * processing margin} once the latency audit has real WAN numbers. It is loopback-blind today.
     */
    static final float ENGAGE_TRIGGER_SU = 500f;

    /** Range within which an ENGAGE-picking hostile gets an injected INTERCEPT so the chase is visible. */
    static final float CHASE_INJECT_SU = 2000f;

    /** Patrols only hassle at close range; well inside the range at which the spike's stop worked (34 su). */
    static final float CUSTOMS_TRIGGER_SU = 600f;

    /** Duration of an injected INTERCEPT assignment, in campaign days. */
    static final float CHASE_ASSIGNMENT_DAYS = 2f;

    /** One handoff per fleet per two minutes: the guest must not be re-dropped into the same fight. */
    static final long ENGAGE_COOLDOWN_MILLIS = 120000L;
    /** A chase assignment lasts days of campaign time; re-injecting sooner just churns the AI. */
    static final long CHASE_COOLDOWN_MILLIS = 30000L;
    /** Customs is effectively once per encounter; vanilla's own latch ({@code $tOff_didAlready}) agrees. */
    static final long CUSTOMS_COOLDOWN_MILLIS = 600000L;

    /**
     * Global suppression after any handoff, on top of the per-fleet cooldown. {@code ENGAGE_GUEST} is
     * a round trip: the guest has to reach a dialog-free frame, autosave, and start the battle before
     * its {@code BATTLE_BEGIN} comes back and {@code coopBattleActive} goes true. Without this window
     * a second, <em>different</em> hostile in the same system could be handed off inside that gap and
     * the guest would be queued into two fights.
     */
    static final long HANDOFF_GRACE_MILLIS = 15000L;

    /**
     * {@code pickEncounterOption} over every fleet in a busy system is not free, and the decisions it
     * feeds move on a scale of seconds. The per-frame work is the battle-eject recovery only.
     */
    static final long SCAN_INTERVAL_MILLIS = 250L;

    private static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    private static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    /** What the watcher should do about one nearby fleet this scan. */
    public enum Action {
        NONE,
        /** Hand the fight to the guest: {@code ENGAGE_GUEST}. */
        ENGAGE_GUEST,
        /** Make the chase visible: inject {@code INTERCEPT} toward the mirror. */
        INJECT_CHASE,
        /** Host-synthesized patrol stop: {@code DIALOG_BEGIN}. */
        CUSTOMS_DIALOG
    }

    /**
     * Everything {@link #decide} needs about one nearby fleet, read off the engine once per scan.
     * {@code engagePick} is the engine's own side-effect-free answer
     * ({@code pickEncounterOption(null, mirror, true) == ENGAGE}).
     */
    public record FleetView(String coopFleetId, String fleetName, String factionId,
                            boolean hostile, boolean engagePick, boolean patrol,
                            boolean combatCapable, float distance) {
    }

    /** Cooldown state per (fleet, action). Split out so the throttle is unit-testable. */
    public static final class Cooldowns {
        private final Map<String, Long> lastFiredAtMillis = new HashMap<>();

        public boolean isReady(String key, long nowMillis, long cooldownMillis) {
            Long last = lastFiredAtMillis.get(key);
            return last == null || nowMillis - last >= cooldownMillis;
        }

        public void mark(String key, long nowMillis) {
            lastFiredAtMillis.put(key, nowMillis);
        }

        public void clear() {
            lastFiredAtMillis.clear();
        }

        public int size() {
            return lastFiredAtMillis.size();
        }
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final Cooldowns cooldowns = new Cooldowns();
    private long nextScanAtMillis;
    private long lastHandoffAtMillis = Long.MIN_VALUE;
    private int ejectCount;

    public CoopNpcThreatWatcher(CoopNetService service, CoopSessionState session, LongSupplier clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- pure decision core ----------------------------------------------------------------------

    /**
     * The trigger predicate. {@code coopBattleActive} covers both "someone is already fighting" and
     * "the shared clock is already held by a combat intent" — the watcher never stacks engagements.
     */
    public static Action decide(FleetView view, boolean mirrorTransponderOn, boolean coopBattleActive,
                                boolean engageReady, boolean chaseReady, boolean customsReady) {
        if (view == null || !view.combatCapable() || view.distance() < 0f) {
            return Action.NONE;
        }
        if (view.hostile()) {
            if (!view.engagePick()) {
                // The engine itself says this fleet would not take the fight (DISENGAGE / HOLD):
                // do not synthesize one it would have refused.
                return Action.NONE;
            }
            if (!coopBattleActive && view.distance() <= ENGAGE_TRIGGER_SU && engageReady) {
                return Action.ENGAGE_GUEST;
            }
            if (view.distance() <= CHASE_INJECT_SU && chaseReady) {
                return Action.INJECT_CHASE;
            }
            return Action.NONE;
        }
        // Non-hostile posture: the only synthesis left is the patrol stop against a dark guest.
        if (coopBattleActive || mirrorTransponderOn || !view.patrol() || view.engagePick()) {
            return Action.NONE;
        }
        if (view.distance() <= CUSTOMS_TRIGGER_SU && customsReady) {
            return Action.CUSTOMS_DIALOG;
        }
        return Action.NONE;
    }

    /** Cooldown key so the same fleet's engage/chase/customs throttles stay independent. */
    public static String cooldownKey(String coopFleetId, Action action) {
        return action.name() + ":" + (coopFleetId == null ? "" : coopFleetId);
    }

    // ---- engine driving --------------------------------------------------------------------------

    /** Session (re)start: forget every throttle so a fresh session behaves like a fresh world. */
    public void reset() {
        cooldowns.clear();
        nextScanAtMillis = 0L;
        lastHandoffAtMillis = Long.MIN_VALUE;
        ejectCount = 0;
    }

    public int ejectCount() {
        return ejectCount;
    }

    /** Host-only, once per pump frame while the session is streaming. Never throws. */
    public void tick(SectorAPI sector, long nowMillis, boolean coopBattleActive) {
        if (sector == null) {
            return;
        }
        try {
            CampaignFleetAPI mirror = findGuestMirror(sector);
            if (mirror == null) {
                return;
            }
            ejectFromBattleIfNeeded(mirror);
            if (nowMillis < nextScanAtMillis) {
                return;
            }
            nextScanAtMillis = nowMillis + SCAN_INTERVAL_MILLIS;
            scan(sector, mirror, nowMillis, coopBattleActive);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop NPC threat watcher pass failed", ex);
        }
    }

    /**
     * Load-bearing recovery, every frame. The dialog pull-in path can drag the mirror into a real
     * host battle without ever consulting {@code canBeEngaged()}; leaving it there means silent
     * autoresolve rounds against a fleet whose owner is not in a battle.
     */
    private void ejectFromBattleIfNeeded(CampaignFleetAPI mirror) {
        BattleAPI battle;
        try {
            battle = mirror.getBattle();
        } catch (RuntimeException | LinkageError ex) {
            return;
        }
        if (battle == null) {
            return;
        }
        ejectCount++;
        try {
            battle.leave(mirror, false);
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop EJECTED the guest mirror from a battle it was"
                    + " pulled into (eject #" + ejectCount + ", stillInBattle="
                    + (safeBattle(mirror) != null) + "). This is the pull-in path"
                    + " (FleetInteractionDialogPluginImpl.pullInNearbyFleets), not a shield failure.");
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop FAILED to eject the guest mirror from a battle"
                    + " — autoresolve may damage a fleet whose owner is not fighting", ex);
        }
    }

    private void scan(SectorAPI sector, CampaignFleetAPI mirror, long nowMillis, boolean coopBattleActive) {
        LocationAPI location = mirror.getContainingLocation();
        if (location == null) {
            return;
        }
        boolean transponderOn = transponderOn(mirror);
        // The handoff round trip has not closed yet: treat it as "a coop battle is starting".
        // The never-fired sentinel must be checked explicitly: nowMillis - Long.MIN_VALUE overflows
        // negative, which read as "inside the grace window" forever and muzzled every ENGAGE_GUEST
        // and DIALOG_BEGIN this class exists to send (found in-game 2026-08-19).
        boolean handedOff = lastHandoffAtMillis != Long.MIN_VALUE
                && nowMillis - lastHandoffAtMillis < HANDOFF_GRACE_MILLIS;
        for (CampaignFleetAPI fleet : fleetsIn(location)) {
            if (fleet == null || fleet == mirror || isMirror(fleet) || isPlayerFleet(sector, fleet)) {
                continue;
            }
            FleetView view = viewOf(fleet, mirror);
            Action action = decide(view, transponderOn, coopBattleActive || handedOff,
                    cooldowns.isReady(cooldownKey(view.coopFleetId(), Action.ENGAGE_GUEST),
                            nowMillis, ENGAGE_COOLDOWN_MILLIS),
                    cooldowns.isReady(cooldownKey(view.coopFleetId(), Action.INJECT_CHASE),
                            nowMillis, CHASE_COOLDOWN_MILLIS),
                    cooldowns.isReady(cooldownKey(view.coopFleetId(), Action.CUSTOMS_DIALOG),
                            nowMillis, CUSTOMS_COOLDOWN_MILLIS));
            switch (action) {
                case ENGAGE_GUEST -> {
                    fireEngageGuest(view, nowMillis);
                    handedOff = true;
                }
                case INJECT_CHASE -> injectChase(fleet, mirror, view, nowMillis);
                case CUSTOMS_DIALOG -> fireCustomsDialog(view, nowMillis);
                case NONE -> {
                    // nothing to synthesize for this fleet this scan
                }
            }
        }
    }

    private void fireEngageGuest(FleetView view, long nowMillis) {
        cooldowns.mark(cooldownKey(view.coopFleetId(), Action.ENGAGE_GUEST), nowMillis);
        lastHandoffAtMillis = nowMillis;
        service.send(CoopMessages.engageGuest(session.sessionId(), service.nextSeq(), nowMillis,
                view.coopFleetId(), view.fleetName(), view.factionId()));
        CoopLog.info(CoopNpcThreatWatcher.class, "Coop ENGAGE_GUEST sent coopFleetId=" + view.coopFleetId()
                + " fleet=" + view.fleetName() + " faction=" + view.factionId()
                + " dist=" + String.format("%.1f", view.distance()));
    }

    private void fireCustomsDialog(FleetView view, long nowMillis) {
        cooldowns.mark(cooldownKey(view.coopFleetId(), Action.CUSTOMS_DIALOG), nowMillis);
        service.send(CoopMessages.dialogBegin(session.sessionId(), service.nextSeq(), nowMillis,
                view.coopFleetId(), view.factionId(), CoopMessages.DialogKind.CUSTOMS));
        CoopLog.info(CoopNpcThreatWatcher.class, "Coop DIALOG_BEGIN sent (customs, transponder-off guest)"
                + " coopFleetId=" + view.coopFleetId() + " fleet=" + view.fleetName()
                + " faction=" + view.factionId() + " dist=" + String.format("%.1f", view.distance()));
    }

    private void injectChase(CampaignFleetAPI chaser, CampaignFleetAPI mirror, FleetView view, long nowMillis) {
        cooldowns.mark(cooldownKey(view.coopFleetId(), Action.INJECT_CHASE), nowMillis);
        try {
            CampaignFleetAIAPI ai = chaser.getAI();
            if (ai == null) {
                return;
            }
            ai.addAssignmentAtStart(FleetAssignment.INTERCEPT, mirror, CHASE_ASSIGNMENT_DAYS, null);
            CoopLog.info(CoopNpcThreatWatcher.class, "Coop injected INTERCEPT->guest mirror on "
                    + view.fleetName() + " (" + view.factionId() + ") dist="
                    + String.format("%.1f", view.distance()));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop failed to inject a chase assignment", ex);
        }
    }

    // ---- engine reads (all best-effort) ----------------------------------------------------------

    private static FleetView viewOf(CampaignFleetAPI fleet, CampaignFleetAPI mirror) {
        return new FleetView(
                safeId(fleet),
                safeName(fleet),
                factionOf(fleet),
                isHostileTo(fleet, mirror),
                picksEngage(fleet, mirror),
                isPatrol(fleet),
                isCombatCapable(fleet),
                distance(fleet, mirror));
    }

    private static boolean picksEngage(CampaignFleetAPI fleet, CampaignFleetAPI mirror) {
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            if (ai == null) {
                return false;
            }
            // pureCheck overload: the engine's real engage-or-not decision, with no side effects.
            return ai.pickEncounterOption(null, mirror, true) == CampaignFleetAIAPI.EncounterOption.ENGAGE;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isHostileTo(CampaignFleetAPI fleet, CampaignFleetAPI mirror) {
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            if (ai != null) {
                return ai.isHostileTo(mirror);
            }
            return fleet.isHostileTo(mirror);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isPatrol(CampaignFleetAPI fleet) {
        try {
            MemoryAPI memory = fleet.getMemoryWithoutUpdate();
            return memory != null && memory.getBoolean(MemFlags.MEMORY_KEY_PATROL_FLEET);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isCombatCapable(CampaignFleetAPI fleet) {
        try {
            return !fleet.isStationMode() && fleet.getFleetPoints() > 0f;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean transponderOn(CampaignFleetAPI fleet) {
        try {
            return fleet.isTransponderOn();
        } catch (RuntimeException | LinkageError ex) {
            // Unknown transponder state must not synthesize a running-dark stop.
            return true;
        }
    }

    private static CampaignFleetAPI findGuestMirror(SectorAPI sector) {
        for (LocationAPI location : sector.getAllLocations()) {
            if (location == null) {
                continue;
            }
            for (CampaignFleetAPI fleet : fleetsIn(location)) {
                if (fleet != null && isPlayerMirror(fleet)) {
                    return fleet;
                }
            }
        }
        return null;
    }

    private static List<CampaignFleetAPI> fleetsIn(LocationAPI location) {
        try {
            List<CampaignFleetAPI> fleets = location.getFleets();
            return fleets == null ? List.of() : fleets;
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
    }

    private static boolean isPlayerMirror(CampaignFleetAPI fleet) {
        try {
            MemoryAPI memory = fleet.getMemoryWithoutUpdate();
            return memory != null && memory.getBoolean(PLAYER_MIRROR_TAG);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isMirror(CampaignFleetAPI fleet) {
        try {
            MemoryAPI memory = fleet.getMemoryWithoutUpdate();
            return memory != null
                    && (memory.getBoolean(PLAYER_MIRROR_TAG) || memory.contains(NPC_MIRROR_TAG));
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isPlayerFleet(SectorAPI sector, CampaignFleetAPI fleet) {
        try {
            return fleet == sector.getPlayerFleet();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static BattleAPI safeBattle(CampaignFleetAPI fleet) {
        try {
            return fleet.getBattle();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String safeId(CampaignFleetAPI fleet) {
        try {
            String id = fleet.getId();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static String safeName(CampaignFleetAPI fleet) {
        try {
            String name = fleet.getName();
            return name == null ? "" : name;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static String factionOf(CampaignFleetAPI fleet) {
        try {
            return fleet.getFaction() == null || fleet.getFaction().getId() == null
                    ? "" : fleet.getFaction().getId();
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static float distance(CampaignFleetAPI a, CampaignFleetAPI b) {
        try {
            Vector2f pa = a.getLocation();
            Vector2f pb = b.getLocation();
            if (pa == null || pb == null) {
                return -1f;
            }
            float dx = pa.x - pb.x;
            float dy = pa.y - pb.y;
            return (float) Math.sqrt(dx * dx + dy * dy);
        } catch (RuntimeException | LinkageError ex) {
            return -1f;
        }
    }
}
