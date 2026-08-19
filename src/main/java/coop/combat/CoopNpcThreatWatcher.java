package coop.combat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.StrategicModulePlugin;
import com.fs.starfarer.api.campaign.ai.TacticalModulePlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopDebug;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Host-side bridge between vanilla's own pursuit AI and the guest's local combat (Phase 14, rebuilt by
 * Phase 14b).
 *
 * <h2>What changed in 14b, and why</h2>
 * Phase 14 concluded that "vanilla never retasks to hunt the mirror" and made this class the
 * <em>initiator</em>: it injected {@code addAssignmentAtStart(INTERCEPT, mirror, ...)} to fake a chase
 * and fired {@code ENGAGE_GUEST} at a flat 500 su whenever a hostile's {@code pickEncounterOption}
 * said ENGAGE. Both halves were wrong.
 *
 * <ul>
 *   <li><b>The spike watched the wrong signal.</b> Vanilla pursuit is not an assignment change at all —
 *       {@code TacticalModule} never touches assignments; it steers with
 *       {@code fleet.setMoveDestination(...)} ({@code nb/com/fs/starfarer/campaign/ai/TacticalModule
 *       .java:673-676}) after storing the quarry in {@code this.target} (:1049-1058). Watching
 *       assignments could never have seen a native chase.</li>
 *   <li><b>Nothing excludes the mirror as a hunt candidate.</b> {@code canBeEngaged()} and the
 *       {@code noEngaging} fader — the per-frame shield the mirror carries — appear <em>nowhere</em> in
 *       {@code TacticalModule} or {@code StrategicModule}; they are read only by {@code BaseLocation}'s
 *       battle-initiation code. The one flag that does exclude a target is
 *       {@code $cfai_ignoredByOtherFleets} ({@code StrategicModule.java:505}), which Phase 14 removed
 *       from mirror creation. The mirror also gets <em>less</em> protection than the real player fleet:
 *       {@code Misc.isPlayerOrCombinedPlayerPrimary} is false for it, so the "doesn't know who you are"
 *       early-out in {@code isHostileTo} (:1110-1112) and the {@code $cfai_recentlyDefeatedByPlayer}
 *       check (:321-323, guarded by {@code isPlayerFleet()} at :282) both skip it.</li>
 *   <li><b>The injected INTERCEPT was a permanent engage licence.</b> {@code isAllowedToEngage} clears
 *       every ignore flag when the fleet's current assignment already targets the entity
 *       ({@code StrategicModule.java:510-512}) and short-circuits the whole give-up heuristic for an
 *       INTERCEPT pointed at its own target (:551-553, :638-640). The playtest showed the consequence:
 *       {@code Coop injected INTERCEPT->guest mirror on Raiders (pirates)} repeating on the dot every
 *       30 s for ten minutes — a siege vanilla would have abandoned after a day and a half.</li>
 * </ul>
 *
 * <h2>The model now: read vanilla's decision, don't override it</h2>
 * The primary signal is {@code ai.getTacticalModule().getTarget() == mirror}. By the time that holds,
 * every vanilla gate has already run: visibility ({@code TacticalModule.java:271-273} —
 * {@code getVisibilityLevelTo != NONE}, which is the only range gate in the engine; there is no
 * distance constant), mutual hostility (:275-279, :335), the strength/personality comparison and
 * gang-up override (:341-359), and {@code isOkToPursue} → {@code StrategicModule.isAllowedToEngage}
 * (:364/:370/:376), which carries the do-not-attack tracker, the ignore flags, the pursuit-patience
 * timer and the per-assignment vetoes. The watcher adds only what the engine cannot do: hand the fight
 * to the machine that owns the fleet, once the chase has actually closed to contact.
 *
 * <p>Visible pursuit is now vanilla's own {@code setMoveDestination}. Nothing is injected.
 *
 * <h2>The fallback, behind a flag</h2>
 * It is possible that some gate not visible in the decompile keeps a mirror out of the candidate loop
 * in practice. {@code -Dcoop.pursuit.synthesized=true} (or the sector memory flag
 * {@code $coopSynthesizedPursuit}) switches to a synthesized model that re-implements the same gates
 * through public API — visibility, {@code isAllowedToEngage}, the pursuit-patience budget, the
 * all-civilian veto — and steers with {@link TacticalModulePlugin#setPriorityTarget} (:1085-1089),
 * which overrides target selection (:461-466) <em>without</em> touching assignments, so
 * {@code isAllowedToEngage} keeps applying instead of being short-circuited the way the old INTERCEPT
 * injection short-circuited it. Default off; the smoke test decides which model is live.
 *
 * <h2>Post-defeat protection</h2>
 * A fleet the guest just beat used to re-fire {@code ENGAGE_GUEST} as soon as its cooldown lapsed.
 * Vanilla's own answer is {@code $cfai_recentlyDefeatedByPlayer}, which is useless here (player-fleet
 * only). The mechanism that works against an arbitrary target is the do-not-attack tracker:
 * {@code StrategicModule.isAllowedToEngage} consults it <em>first</em>, unconditionally, above the
 * assignment-target override that defeats the ignore flags ({@code StrategicModule.java:500-502}).
 * {@link #noteBattleConcluded} queues it and the next scan applies it, following vanilla's own idiom
 * (see {@code TutorialLeashAssignmentAI:56-57}): {@code ai.doNotAttack(mirror, days)} paired with
 * {@code getTacticalModule().setTarget(null)}, because {@code isAllowedToEngage} is only re-checked for
 * a held target on the next frame (:179-181).
 *
 * <h2>The one load-bearing protection (unchanged)</h2>
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
 * guest resolves locally against its own cargo ({@link CoopCustomsDialogStaging}). 14b adds the
 * visibility gate this always needed: no hail unless the patrol can actually see the mirror. With the
 * mirror now carrying the guest's real sensor identity ({@code coop.fleet.CoopSensorSync}), a guest
 * running dark at range stops being hailed — which is the whole point of running dark.
 *
 * <p>The trigger logic is a pure function ({@link #decide}) over a {@link FleetView}, so every gate is
 * unit-tested without an engine, in the same shape as {@code CoopGuestRouteMaterializer.RouteView}.
 */
public final class CoopNpcThreatWatcher {

    /**
     * Slack added to the two fleets' radii to get the hand-off distance.
     *
     * <p><b>Contact, not a design number.</b> The engine measures fleet-to-fleet distance edge to edge
     * — {@code getVisibilityLevelTo} and {@code isVisibleToSensorsOf} both subtract
     * {@code this.getRadius() + other.getRadius()} ({@code BaseCampaignEntity.java:1118-1120, 1195-1197})
     * and {@code StrategicModule}'s "close enough to keep engaging" test is literally
     * {@code dist <= combinedRadius} (:519-538). So the handoff fires at
     *
     * <pre>contact = chaser.getRadius() + mirror.getRadius() + CONTACT_MARGIN_SU</pre>
     *
     * <p>The margin is one scan interval at the fastest closing speed the Phase 14 spike measured:
     * {@code 340 su/s} (a burn-17 patrol) x {@code SCAN_INTERVAL_MILLIS} (0.25 s) = 85 su, rounded up
     * to 100 for message-processing slack. That is the smallest margin that guarantees the watcher
     * cannot step over the contact band between two scans. It deliberately does <em>not</em> include a
     * "looks close enough" allowance: the user decision on 2026-08-19 is that outrunning a chaser must
     * work exactly as it does in vanilla, so the guest is dropped into the encounter at contact and not
     * before.
     *
     * <p><b>Phase 20 obligation:</b> re-derive as {@code 2 x p95 RTT x closing speed + processing
     * margin} once the latency audit has real WAN numbers. It is loopback-blind today.
     */
    static final float CONTACT_MARGIN_SU = 100f;

    /**
     * Fallback model only: how close a hostile must be before the synthesized pursuit gives it a
     * priority target. Vanilla's own acquisition has no distance constant (it is purely sensor
     * visibility), so this exists solely to bound the work the fallback does per scan.
     */
    static final float PURSUIT_ACQUIRE_SU = 2000f;

    /** Patrols only hassle at close range; well inside the range at which the spike's stop worked (34 su). */
    static final float CUSTOMS_TRIGGER_SU = 600f;

    /** Duration of a fallback-model priority target, in campaign days. */
    static final float PURSUIT_PRIORITY_DAYS = 2f;

    /**
     * Vanilla's pursuit patience, re-implemented for the fallback model from
     * {@code StrategicModule.java:554-588}: a chaser gives up after {@code 1.5} days, or {@code 3.0} if
     * it is a patrol, plus {@code 0.1} per point of burn level. (Vanilla also subtracts
     * {@code 1.5 x |recentlyStoppedPursuing|}, which has no public accessor; the fallback is therefore
     * slightly more persistent than vanilla, never less.)
     */
    static final float PURSUIT_BUDGET_DAYS = 1.5f;
    static final float PURSUIT_BUDGET_DAYS_PATROL = 3f;
    static final float PURSUIT_BUDGET_PER_BURN = 0.1f;

    /**
     * Per-fleet handoff cooldown, tuned to vanilla re-engagement pacing (revised 2026-08-19 from
     * 120 s after user review): vanilla grants only ~3 s of post-encounter unengageability
     * ({@code FleetEncounterContext.setNoEngaging(3f)}) and everything else is physics. 15 s covers the
     * handoff round trip (dialog-free frame, autosave, dialog) without adding un-vanilla safety. It is
     * transport-latency insurance, not the pacing mechanism — the real pacing is now vanilla's own
     * {@link #postDefeatGraceDays} do-not-attack window plus its pursuit-patience timer.
     */
    static final long ENGAGE_COOLDOWN_MILLIS = 15000L;
    /** Fallback model: a priority target lasts days of campaign time; re-asserting sooner just churns. */
    static final long PURSUIT_COOLDOWN_MILLIS = 30000L;
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
     * The engine reads behind a {@link FleetView} are not free over a busy system, and the decisions
     * they feed move on a scale of seconds. The per-frame work is the battle-eject recovery only.
     */
    static final long SCAN_INTERVAL_MILLIS = 250L;

    /** Rate limit for the (dormant) per-hostile pursuit dump. */
    static final long DIAGNOSTIC_INTERVAL_MILLIS = 5000L;

    /**
     * How long a queued post-battle do-not-attack stays queued before it is dropped. The fleet has to
     * be in the mirror's location for the next scan to reach it; if the guest jumped out first there is
     * nothing left to protect.
     */
    static final long PENDING_GRACE_TTL_MILLIS = 30000L;

    /** Vanilla's own post-encounter forget window is {@code 0.5 + random()} days (TacticalModule:176). */
    static final float POST_DEFEAT_GRACE_DAYS_BASE = 0.5f;
    static final float POST_DEFEAT_GRACE_DAYS_JITTER = 1.0f;

    /** Opt-in switch for the synthesized-pursuit fallback model. */
    public static final String SYNTHESIZED_PURSUIT_PROPERTY = "coop.pursuit.synthesized";

    private static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    private static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    /** What the watcher should do about one nearby fleet this scan. */
    public enum Action {
        NONE,
        /** Hand the fight to the guest: {@code ENGAGE_GUEST}. */
        ENGAGE_GUEST,
        /** Fallback model only: point the chaser at the mirror via {@code setPriorityTarget}. */
        STEER_PURSUIT,
        /** Host-synthesized patrol stop: {@code DIALOG_BEGIN}. */
        CUSTOMS_DIALOG
    }

    /**
     * Everything {@link #decide} needs about one nearby fleet, read off the engine once per scan.
     *
     * @param engagePick     the engine's own side-effect-free verdict
     *                       ({@code pickEncounterOption(null, mirror, true) == ENGAGE})
     * @param visible        {@code mirror.getVisibilityLevelTo(fleet) != NONE} — the engine's only range
     *                       gate, and the one that makes stealth work
     * @param huntingMirror  {@code getTacticalModule().getTarget() == mirror}: vanilla has decided to
     *                       hunt, with every gate already applied. The primary model's whole trigger.
     * @param allowedToEngage {@code getStrategicModule().isAllowedToEngage(mirror)} — fallback model only
     * @param pursuitDays    {@code getTacticalModule().getPursuitDays()} — fallback model only
     * @param pursuitBudgetDays vanilla's patience for this chaser — fallback model only
     * @param contactDistance the chaser's radius + the mirror's radius + {@link #CONTACT_MARGIN_SU}
     */
    public record FleetView(String coopFleetId, String fleetName, String factionId,
                            boolean hostile, boolean engagePick, boolean patrol,
                            boolean combatCapable, boolean visible, boolean huntingMirror,
                            boolean allowedToEngage, float pursuitDays, float pursuitBudgetDays,
                            float distance, float contactDistance) {
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
    private final BooleanSupplier synthesizedPursuit;
    private final Cooldowns cooldowns = new Cooldowns();
    /** coopFleetId -> deadline for applying the post-battle do-not-attack window. */
    private final Map<String, Long> pendingPostDefeatGrace = new HashMap<>();
    private final Map<String, Long> lastDiagnosticAtMillis = new HashMap<>();
    private long nextScanAtMillis;
    private long lastHandoffAtMillis = Long.MIN_VALUE;
    private int ejectCount;
    private int graceAppliedCount;

    public CoopNpcThreatWatcher(CoopNetService service, CoopSessionState session, LongSupplier clock) {
        this(service, session, clock, CoopNpcThreatWatcher::synthesizedPursuitConfigured);
    }

    CoopNpcThreatWatcher(CoopNetService service, CoopSessionState session, LongSupplier clock,
                         BooleanSupplier synthesizedPursuit) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.synthesizedPursuit = Objects.requireNonNull(synthesizedPursuit, "synthesizedPursuit");
    }

    // ---- pure decision core ----------------------------------------------------------------------

    /**
     * The trigger predicate. {@code coopBattleActive} covers both "someone is already fighting" and
     * "the shared clock is already held by a combat intent" — the watcher never stacks engagements.
     *
     * @param synthesizedPursuit true for the fallback model (the watcher decides pursuit itself);
     *                           false for the primary model (vanilla's own target is the signal)
     */
    public static Action decide(FleetView view, boolean synthesizedPursuit, boolean mirrorTransponderOn,
                                boolean coopBattleActive, boolean engageReady, boolean pursuitReady,
                                boolean customsReady) {
        if (view == null || !view.combatCapable() || view.distance() < 0f) {
            return Action.NONE;
        }
        if (view.hostile()) {
            return decideHostile(view, synthesizedPursuit, coopBattleActive, engageReady, pursuitReady);
        }
        // Non-hostile posture: the only synthesis left is the patrol stop against a dark guest. The
        // visibility gate is what makes running dark actually work — an undetected mirror is not hailed.
        if (coopBattleActive || mirrorTransponderOn || !view.patrol() || view.engagePick()
                || !view.visible()) {
            return Action.NONE;
        }
        if (view.distance() <= CUSTOMS_TRIGGER_SU && customsReady) {
            return Action.CUSTOMS_DIALOG;
        }
        return Action.NONE;
    }

    private static Action decideHostile(FleetView view, boolean synthesizedPursuit,
                                        boolean coopBattleActive, boolean engageReady,
                                        boolean pursuitReady) {
        // The engine itself says this fleet would not take the fight (DISENGAGE / HOLD): do not
        // synthesize one it would have refused. In the primary model this also separates a genuine
        // hunt from TacticalModule's maintain-contact and evade branches, which set the same target.
        if (!view.engagePick()) {
            return Action.NONE;
        }
        if (!synthesizedPursuit) {
            // Primary: vanilla decided to hunt this mirror, having already applied visibility,
            // hostility, strength and isAllowedToEngage. All that is left is "has it caught up".
            if (!view.huntingMirror()) {
                return Action.NONE;
            }
            return contactReached(view, coopBattleActive, engageReady) ? Action.ENGAGE_GUEST : Action.NONE;
        }
        // Fallback: re-implement vanilla's gates over the public API before steering anything.
        if (!view.visible() || !view.allowedToEngage() || view.pursuitDays() > view.pursuitBudgetDays()) {
            return Action.NONE;
        }
        if (contactReached(view, coopBattleActive, engageReady)) {
            return Action.ENGAGE_GUEST;
        }
        if (view.distance() <= PURSUIT_ACQUIRE_SU && pursuitReady) {
            return Action.STEER_PURSUIT;
        }
        return Action.NONE;
    }

    private static boolean contactReached(FleetView view, boolean coopBattleActive, boolean engageReady) {
        return !coopBattleActive && engageReady && view.distance() <= view.contactDistance();
    }

    /** The handoff distance for one chaser: edge-to-edge contact plus one scan of closing speed. */
    public static float contactDistance(float chaserRadius, float mirrorRadius) {
        return Math.max(0f, chaserRadius) + Math.max(0f, mirrorRadius) + CONTACT_MARGIN_SU;
    }

    /** Vanilla's pursuit patience for one chaser ({@code StrategicModule.java:554-588}). */
    public static float pursuitBudgetDays(boolean patrol, float burnLevel) {
        float base = patrol ? PURSUIT_BUDGET_DAYS_PATROL : PURSUIT_BUDGET_DAYS;
        return base + Math.max(0f, burnLevel) * PURSUIT_BUDGET_PER_BURN;
    }

    /**
     * The post-defeat do-not-attack window, in campaign days. Vanilla uses
     * {@code 0.5f + 1.0f * Math.random()}; the jitter here is derived from the fleet id instead so the
     * host's world stays a pure function of its inputs (the coop RNG rules only bind seeded generation,
     * but a free {@code Math.random()} in a replicated simulation is a habit worth not forming).
     */
    public static float postDefeatGraceDays(String coopFleetId) {
        int hash = coopFleetId == null ? 0 : coopFleetId.hashCode();
        float fraction = (hash & 0xFF) / 255f;
        return POST_DEFEAT_GRACE_DAYS_BASE + POST_DEFEAT_GRACE_DAYS_JITTER * fraction;
    }

    /** Cooldown key so the same fleet's engage/pursuit/customs throttles stay independent. */
    public static String cooldownKey(String coopFleetId, Action action) {
        return action.name() + ":" + (coopFleetId == null ? "" : coopFleetId);
    }

    /** True when the synthesized-pursuit fallback is switched on for this client. */
    public static boolean synthesizedPursuitConfigured() {
        if (Boolean.getBoolean(SYNTHESIZED_PURSUIT_PROPERTY)) {
            return true;
        }
        try {
            SectorAPI sector = com.fs.starfarer.api.Global.getSector();
            MemoryAPI memory = sector == null ? null : sector.getMemoryWithoutUpdate();
            return memory != null && memory.getBoolean("$coopSynthesizedPursuit");
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ---- engine driving --------------------------------------------------------------------------

    /** Session (re)start: forget every throttle so a fresh session behaves like a fresh world. */
    public void reset() {
        cooldowns.clear();
        pendingPostDefeatGrace.clear();
        lastDiagnosticAtMillis.clear();
        nextScanAtMillis = 0L;
        lastHandoffAtMillis = Long.MIN_VALUE;
        ejectCount = 0;
        graceAppliedCount = 0;
    }

    public int ejectCount() {
        return ejectCount;
    }

    /** How many post-battle do-not-attack windows have actually been stamped onto a chaser. */
    public int graceAppliedCount() {
        return graceAppliedCount;
    }

    /**
     * Battle end for one host fleet: restart its {@code ENGAGE_GUEST} cooldown and queue vanilla's
     * post-encounter forget window against the guest mirror.
     *
     * <p>The cooldown restart is Phase 15's fix and is unchanged: {@link #ENGAGE_COOLDOWN_MILLIS} stays
     * 15 s, but it is stamped at battle <em>end</em> rather than at handoff, so a fight longer than the
     * cooldown does not leave the fleet re-armed the instant the guest comes back — before the
     * reconciliation that would tell the watcher the fleet is now a wreck has landed.
     *
     * <p>Phase 14b adds the real fix for the 27-second rematch. Fifteen seconds of transport insurance
     * is not a gameplay pacing rule; vanilla's is {@code getDoNotAttack()}, which
     * {@code StrategicModule.isAllowedToEngage} consults first and unconditionally, for any target
     * (:500-502) — unlike the {@code $cfai_*} ignore flags, which an assignment aimed at the target
     * silently cancels (:510-512), and unlike {@code $cfai_recentlyDefeatedByPlayer}, which
     * {@code TacticalModule} only reads for the real player fleet (:282, :321-323). Applying it needs
     * the sector, so it is queued here and stamped by the next {@link #tick} — a frame later at most.
     *
     * <p>Called for both directions: the host's own battles (via the reconciler) and the guest's (via
     * the {@code BATTLE_END} the bridge receives, which carries the battle's {@code coopFleetId}s).
     */
    public void noteBattleConcluded(String coopFleetId, long nowMillis) {
        if (coopFleetId == null || coopFleetId.isEmpty()) {
            return;
        }
        cooldowns.mark(cooldownKey(coopFleetId, Action.ENGAGE_GUEST), nowMillis);
        pendingPostDefeatGrace.put(coopFleetId, nowMillis + PENDING_GRACE_TTL_MILLIS);
        CoopLog.debug(CoopNpcThreatWatcher.class, "Coop restarted the ENGAGE_GUEST cooldown and queued a"
                + " post-defeat do-not-attack window at battle end coopFleetId=" + coopFleetId);
    }

    /** True when this fleet may be handed off again (test seam for {@link #noteBattleConcluded}). */
    public boolean isEngageReady(String coopFleetId, long nowMillis) {
        return cooldowns.isReady(cooldownKey(coopFleetId, Action.ENGAGE_GUEST), nowMillis,
                ENGAGE_COOLDOWN_MILLIS);
    }

    /** Fleet ids still waiting for their post-battle do-not-attack window (test seam). */
    public boolean isPostDefeatGracePending(String coopFleetId) {
        return pendingPostDefeatGrace.containsKey(coopFleetId);
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
            expirePendingGrace(nowMillis);
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

    private void expirePendingGrace(long nowMillis) {
        Iterator<Map.Entry<String, Long>> it = pendingPostDefeatGrace.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMillis >= it.next().getValue()) {
                it.remove();
            }
        }
    }

    private void scan(SectorAPI sector, CampaignFleetAPI mirror, long nowMillis, boolean coopBattleActive) {
        LocationAPI location = mirror.getContainingLocation();
        if (location == null) {
            return;
        }
        boolean synthesized = synthesizedPursuitEnabled();
        boolean transponderOn = transponderOn(mirror);
        boolean diagnostics = CoopDebug.diagnosticsEnabled();
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
            applyPendingGraceIfQueued(fleet, mirror, view);
            if (diagnostics) {
                dumpPursuitState(view, synthesized, nowMillis);
            }
            Action action = decide(view, synthesized, transponderOn, coopBattleActive || handedOff,
                    cooldowns.isReady(cooldownKey(view.coopFleetId(), Action.ENGAGE_GUEST),
                            nowMillis, ENGAGE_COOLDOWN_MILLIS),
                    cooldowns.isReady(cooldownKey(view.coopFleetId(), Action.STEER_PURSUIT),
                            nowMillis, PURSUIT_COOLDOWN_MILLIS),
                    cooldowns.isReady(cooldownKey(view.coopFleetId(), Action.CUSTOMS_DIALOG),
                            nowMillis, CUSTOMS_COOLDOWN_MILLIS));
            switch (action) {
                case ENGAGE_GUEST -> {
                    fireEngageGuest(view, nowMillis);
                    handedOff = true;
                }
                case STEER_PURSUIT -> steerPursuit(fleet, mirror, view, nowMillis);
                case CUSTOMS_DIALOG -> fireCustomsDialog(view, nowMillis);
                case NONE -> {
                    // nothing to synthesize for this fleet this scan
                }
            }
        }
    }

    private boolean synthesizedPursuitEnabled() {
        try {
            return synthesizedPursuit.getAsBoolean();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private void fireEngageGuest(FleetView view, long nowMillis) {
        cooldowns.mark(cooldownKey(view.coopFleetId(), Action.ENGAGE_GUEST), nowMillis);
        lastHandoffAtMillis = nowMillis;
        service.send(CoopMessages.engageGuest(session.sessionId(), service.nextSeq(), nowMillis,
                view.coopFleetId(), view.fleetName(), view.factionId()));
        CoopLog.info(CoopNpcThreatWatcher.class, "Coop ENGAGE_GUEST sent coopFleetId=" + view.coopFleetId()
                + " fleet=" + view.fleetName() + " faction=" + view.factionId()
                + " dist=" + String.format("%.1f", view.distance())
                + " contact=" + String.format("%.1f", view.contactDistance())
                + " vanillaHunting=" + view.huntingMirror());
    }

    private void fireCustomsDialog(FleetView view, long nowMillis) {
        cooldowns.mark(cooldownKey(view.coopFleetId(), Action.CUSTOMS_DIALOG), nowMillis);
        service.send(CoopMessages.dialogBegin(session.sessionId(), service.nextSeq(), nowMillis,
                view.coopFleetId(), view.factionId(), CoopMessages.DialogKind.CUSTOMS));
        CoopLog.info(CoopNpcThreatWatcher.class, "Coop DIALOG_BEGIN sent (customs, transponder-off guest)"
                + " coopFleetId=" + view.coopFleetId() + " fleet=" + view.fleetName()
                + " faction=" + view.factionId() + " dist=" + String.format("%.1f", view.distance()));
    }

    /**
     * Fallback model only. {@code setPriorityTarget} overrides {@code TacticalModule}'s candidate
     * selection (:461-466) without adding an assignment, so {@code isAllowedToEngage} keeps deciding
     * every frame — unlike the injected {@code INTERCEPT} this replaces, which cancelled the ignore
     * flags and the give-up timer outright ({@code StrategicModule.java:510-512, 551-553}).
     */
    private void steerPursuit(CampaignFleetAPI chaser, CampaignFleetAPI mirror, FleetView view,
                              long nowMillis) {
        cooldowns.mark(cooldownKey(view.coopFleetId(), Action.STEER_PURSUIT), nowMillis);
        TacticalModulePlugin tactical = tacticalModule(chaser);
        if (tactical == null) {
            return;
        }
        try {
            tactical.setPriorityTarget(mirror, PURSUIT_PRIORITY_DAYS, false);
            CoopLog.info(CoopNpcThreatWatcher.class, "Coop steered synthesized pursuit onto the guest"
                    + " mirror: " + view.fleetName() + " (" + view.factionId() + ") dist="
                    + String.format("%.1f", view.distance()));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop failed to set a pursuit priority target", ex);
        }
    }

    /**
     * Stamps the queued post-battle do-not-attack window onto a chaser, vanilla's own idiom: the
     * tracker entry plus {@code setTarget(null)} to interrupt the pursuit already in progress (see
     * {@code TutorialLeashAssignmentAI:54-58}). {@code CampaignFleetAIAPI.doNotAttack} is a max-merge
     * so it never shortens an entry vanilla itself set.
     */
    private void applyPendingGraceIfQueued(CampaignFleetAPI fleet, CampaignFleetAPI mirror, FleetView view) {
        if (!pendingPostDefeatGrace.containsKey(view.coopFleetId())) {
            return;
        }
        pendingPostDefeatGrace.remove(view.coopFleetId());
        float days = postDefeatGraceDays(view.coopFleetId());
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            if (ai == null) {
                return;
            }
            ai.doNotAttack(mirror, days);
            TacticalModulePlugin tactical = tacticalModule(fleet);
            if (tactical != null && tactical.getTarget() == mirror) {
                tactical.setTarget(null);
            }
            graceAppliedCount++;
            CoopLog.info(CoopNpcThreatWatcher.class, "Coop post-defeat grace applied coopFleetId="
                    + view.coopFleetId() + " fleet=" + view.fleetName() + " days="
                    + String.format("%.2f", days));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop failed to apply the post-defeat do-not-attack"
                    + " window for coopFleetId=" + view.coopFleetId(), ex);
        }
    }

    /**
     * Dormant diagnostic (CoopDebug). This is how a smoke test tells the primary model from the
     * fallback: {@code hunting=true} anywhere in the dump means vanilla is targeting the mirror on its
     * own and the primary model is live.
     */
    private void dumpPursuitState(FleetView view, boolean synthesized, long nowMillis) {
        Long last = lastDiagnosticAtMillis.get(view.coopFleetId());
        if (last != null && nowMillis - last < DIAGNOSTIC_INTERVAL_MILLIS) {
            return;
        }
        lastDiagnosticAtMillis.put(view.coopFleetId(), nowMillis);
        CoopLog.info(CoopNpcThreatWatcher.class, "Coop pursuit probe model="
                + (synthesized ? "synthesized" : "vanilla")
                + " fleet=" + view.fleetName() + " (" + view.factionId() + ")"
                + " hostile=" + view.hostile() + " visible=" + view.visible()
                + " engagePick=" + view.engagePick() + " hunting=" + view.huntingMirror()
                + " allowedToEngage=" + view.allowedToEngage()
                + " pursuitDays=" + String.format("%.2f", view.pursuitDays())
                + "/" + String.format("%.2f", view.pursuitBudgetDays())
                + " dist=" + String.format("%.1f", view.distance())
                + " contact=" + String.format("%.1f", view.contactDistance()));
    }

    // ---- engine reads (all best-effort) ----------------------------------------------------------

    private static FleetView viewOf(CampaignFleetAPI fleet, CampaignFleetAPI mirror) {
        TacticalModulePlugin tactical = tacticalModule(fleet);
        StrategicModulePlugin strategic = strategicModule(fleet);
        boolean patrol = isPatrol(fleet);
        return new FleetView(
                safeId(fleet),
                safeName(fleet),
                factionOf(fleet),
                isHostileTo(fleet, mirror),
                picksEngage(fleet, mirror),
                patrol,
                isCombatCapable(fleet),
                canSee(fleet, mirror),
                tactical != null && tactical.getTarget() == mirror,
                isAllowedToEngage(strategic, mirror),
                pursuitDays(tactical),
                pursuitBudgetDays(patrol, burnLevel(fleet)),
                distance(fleet, mirror),
                contactDistance(radius(fleet), radius(mirror)));
    }

    /**
     * The engine's only range gate on pursuit ({@code TacticalModule.java:271-273}). Direction matters:
     * {@code target.getVisibilityLevelTo(observer)} answers "how well does the observer see the
     * target", so the mirror is the receiver and the chaser the argument.
     */
    private static boolean canSee(CampaignFleetAPI observer, CampaignFleetAPI mirror) {
        try {
            return mirror.getVisibilityLevelTo(observer) != SectorEntityToken.VisibilityLevel.NONE;
        } catch (RuntimeException | LinkageError ex) {
            // Unknown visibility must not manufacture a sighting: stealth fails safe toward the guest.
            return false;
        }
    }

    private static TacticalModulePlugin tacticalModule(CampaignFleetAPI fleet) {
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            return ai instanceof ModularFleetAIAPI modular ? modular.getTacticalModule() : null;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static StrategicModulePlugin strategicModule(CampaignFleetAPI fleet) {
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            return ai instanceof ModularFleetAIAPI modular ? modular.getStrategicModule() : null;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static boolean isAllowedToEngage(StrategicModulePlugin strategic, CampaignFleetAPI mirror) {
        if (strategic == null) {
            return false;
        }
        try {
            return strategic.isAllowedToEngage(mirror);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static float pursuitDays(TacticalModulePlugin tactical) {
        if (tactical == null) {
            return 0f;
        }
        try {
            return tactical.getPursuitDays();
        } catch (RuntimeException | LinkageError ex) {
            return 0f;
        }
    }

    private static float burnLevel(CampaignFleetAPI fleet) {
        try {
            return fleet.getCurrBurnLevel();
        } catch (RuntimeException | LinkageError ex) {
            return 0f;
        }
    }

    private static float radius(CampaignFleetAPI fleet) {
        try {
            return fleet.getRadius();
        } catch (RuntimeException | LinkageError ex) {
            return 0f;
        }
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
