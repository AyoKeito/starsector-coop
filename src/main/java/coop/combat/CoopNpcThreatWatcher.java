package coop.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <h2>Customs: a synthesized inspection chase</h2>
 * A patrol that detects a transponder-off guest mirror now closes on it and pushes
 * {@code DIALOG_BEGIN} on contact; the guest resolves the stop locally against its own cargo
 * ({@link CoopCustomsDialogStaging}).
 *
 * <p><b>Why this has to be synthesized (added 2026-08-19 after the 14b smoke).</b> Vanilla's
 * inspection pursuit is player-only, in three separate places: {@code StrategicModule}'s
 * {@code ORBIT_PASSIVE}/{@code FOLLOW} clause allows an engage only when the target
 * <em>is the player fleet</em> and the chaser is {@code $isPatrol} (:617-625); the whole
 * transponder-suspicion block in {@code TacticalModule.advance} sits behind
 * {@code campaignFleet2.isPlayerFleet()} (:282-314) and is what sets {@code $cfai_makeAggressive}
 * and {@code $sawPlayerTransponderOff}; and {@code Misc.isPlayerOrCombinedPlayerPrimary} is false for
 * a mirror. No native patrol will ever hunt it. The first 14b build only hailed inside a flat 600 su,
 * which the smoke session reported as patrols ignoring a dark guest until it nearly collided with them.
 *
 * <p>The start gate is detection and nothing else — from the moment
 * {@code mirror.getVisibilityLevelTo(patrol) != NONE} the patrol closes, so it chases from wherever it
 * spotted the guest, and going dark or outrunning it works. Every exit (patience spent, contact lost,
 * transponder back on, the stop fired, turn to hostility) runs through the one method that removes the
 * assignment and stamps the cooldown, so the chase cannot outlive its reason.
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

    /**
     * How long one issued inspection-chase {@code INTERCEPT} lasts before the engine drops it, in
     * campaign days. Deliberately far shorter than the patience budget it serves (3 days) and
     * re-issued while the chase is live, so the worst case if the watcher ever loses track of a patrol
     * — it leaves the mirror's location mid-chase and is never scanned again — is half a day of
     * orphaned assignment rather than the ten-minute siege the deleted hostile-chase injection produced.
     */
    static final float CUSTOMS_PURSUIT_ASSIGNMENT_DAYS = 0.5f;

    /**
     * How many consecutive scans a patrol may fail to see the mirror before it gives up. Twelve scans
     * is 3 s at {@link #SCAN_INTERVAL_MILLIS}, which is long enough to ride out a fleet clipping the
     * edge of a nebula and short enough that going dark shakes a tail promptly.
     */
    static final int CUSTOMS_UNSEEN_SCAN_LIMIT = 12;

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
        /** Start or sustain a patrol's synthesized inspection chase of the dark mirror. */
        CUSTOMS_PURSUE,
        /** Host-synthesized patrol stop: {@code DIALOG_BEGIN}. Ends the chase. */
        CUSTOMS_DIALOG,
        /** Abandon an inspection chase and stand the patrol down. */
        CUSTOMS_GIVE_UP
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
     * @param pursuitBudgetDays vanilla's patience for this chaser: the fallback model's give-up timer
     *                          and the inspection chase's give-up timer both read it
     * @param contactDistance the chaser's radius + the mirror's radius + {@link #CONTACT_MARGIN_SU}
     * @param customsPursuing whether this patrol is already running a synthesized inspection chase
     * @param customsPursuitDays campaign days elapsed since that chase started
     * @param customsUnseenScans consecutive scans of that chase in which the mirror was undetectable
     */
    public record FleetView(String coopFleetId, String fleetName, String factionId,
                            boolean hostile, boolean engagePick, boolean patrol,
                            boolean combatCapable, boolean visible, boolean huntingMirror,
                            boolean allowedToEngage, float pursuitDays, float pursuitBudgetDays,
                            float distance, float contactDistance,
                            boolean customsPursuing, float customsPursuitDays, int customsUnseenScans) {
    }

    /** Live state of one patrol's synthesized inspection chase. Mutable; owned by the watcher. */
    private static final class CustomsPursuit {
        private final long startedAtTimestamp;
        private int unseenScans;

        private CustomsPursuit(long startedAtTimestamp) {
            this.startedAtTimestamp = startedAtTimestamp;
        }
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
    /** coopFleetId -> live synthesized inspection chase of the dark guest mirror. */
    private final Map<String, CustomsPursuit> customsPursuits = new HashMap<>();
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
        if (view == null) {
            return Action.NONE;
        }
        if (!view.combatCapable() || view.distance() < 0f) {
            // A chase whose quarry or chaser has become unreadable still has to be cleaned up.
            return view.customsPursuing() ? Action.CUSTOMS_GIVE_UP : Action.NONE;
        }
        // An inspection chase in flight outranks everything, including a turn to hostility: it owns an
        // assignment on the patrol, so its exit has to run before any other branch can claim the fleet.
        if (view.customsPursuing()) {
            return decideCustomsPursuit(view, mirrorTransponderOn, coopBattleActive);
        }
        if (view.hostile()) {
            return decideHostile(view, synthesizedPursuit, coopBattleActive, engageReady, pursuitReady);
        }
        return decideCustomsStart(view, mirrorTransponderOn, coopBattleActive, customsReady);
    }

    /**
     * Should this patrol start hunting the dark mirror? The gate is detection and nothing else: from
     * the moment a patrol can see a transponder-off guest it starts closing, exactly as vanilla's
     * inspection pursuit does against the real player fleet. There is deliberately no distance constant
     * here — the flat 600 su trigger this replaced was what made patrols read as "hails you only if you
     * bump into them" (in-game report, 2026-08-19).
     */
    private static Action decideCustomsStart(FleetView view, boolean mirrorTransponderOn,
                                             boolean coopBattleActive, boolean customsReady) {
        if (coopBattleActive || mirrorTransponderOn || !view.patrol() || view.engagePick()
                || !view.visible() || !customsReady) {
            return Action.NONE;
        }
        // Already on top of the guest: skip straight to the stop rather than issuing a chase to a
        // destination the patrol is standing on.
        return view.distance() <= view.contactDistance() ? Action.CUSTOMS_DIALOG : Action.CUSTOMS_PURSUE;
    }

    /**
     * Every exit from an inspection chase. Vanilla's patrols stand down when the suspicion goes away
     * (transponder back on), when they lose the contact, and when their patience runs out; the budget
     * is vanilla's own — 3 days for a {@code $isPatrol} fleet plus 0.1 per burn level
     * ({@code StrategicModule.java:554-588}), already computed into {@code pursuitBudgetDays}.
     */
    private static Action decideCustomsPursuit(FleetView view, boolean mirrorTransponderOn,
                                               boolean coopBattleActive) {
        if (coopBattleActive || mirrorTransponderOn || view.hostile() || !view.patrol()
                || view.engagePick()
                || view.customsPursuitDays() > view.pursuitBudgetDays()
                || view.customsUnseenScans() > CUSTOMS_UNSEEN_SCAN_LIMIT) {
            return Action.CUSTOMS_GIVE_UP;
        }
        if (view.distance() <= view.contactDistance()) {
            return Action.CUSTOMS_DIALOG;
        }
        return Action.CUSTOMS_PURSUE;
    }

    /**
     * Why an inspection chase gave up, for the log line the smoke test greps. Pure, so the reason a
     * test asserts on is the reason the log prints.
     */
    public static String customsGiveUpReason(FleetView view, boolean mirrorTransponderOn,
                                             boolean coopBattleActive) {
        if (view == null) {
            return "gone";
        }
        if (!view.combatCapable() || view.distance() < 0f) {
            return "gone";
        }
        if (coopBattleActive) {
            return "coopBattle";
        }
        if (mirrorTransponderOn) {
            return "transponderOn";
        }
        if (view.hostile() || view.engagePick()) {
            return "turnedHostile";
        }
        if (!view.patrol()) {
            return "noLongerPatrol";
        }
        if (view.customsUnseenScans() > CUSTOMS_UNSEEN_SCAN_LIMIT) {
            return "lostContact";
        }
        if (view.customsPursuitDays() > view.pursuitBudgetDays()) {
            return "outOfPatience";
        }
        return "other";
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
            SectorAPI sector = Global.getSector();
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
        customsPursuits.clear();
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

    /** True while this patrol is running a synthesized inspection chase (test seam). */
    public boolean isCustomsPursuing(String coopFleetId) {
        return customsPursuits.containsKey(coopFleetId);
    }

    /** How many patrols are currently chasing the dark guest mirror (test seam). */
    public int customsPursuitCount() {
        return customsPursuits.size();
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
        // Always mutable: the Set.of() "optimization" this used to have made the unconditional
        // add() below throw UnsupportedOperationException on the first fleet of every scan whenever
        // no chase was live — which is every scan from session start, killing the whole watcher
        // (found in-game 2026-08-19: every fleet ignored the guest).
        Set<String> seenThisScan = new HashSet<>();
        for (CampaignFleetAPI fleet : fleetsIn(location)) {
            if (fleet == null || fleet == mirror || isMirror(fleet) || isPlayerFleet(sector, fleet)) {
                continue;
            }
            String coopFleetId = safeId(fleet);
            seenThisScan.add(coopFleetId);
            boolean visible = canSee(fleet, mirror);
            CustomsPursuit pursuit = trackCustomsVisibility(coopFleetId, visible);
            FleetView view = viewOf(fleet, mirror, coopFleetId, visible, pursuit, elapsedDaysSince(pursuit));
            applyPendingGraceIfQueued(fleet, mirror, view);
            boolean battleBusy = coopBattleActive || handedOff;
            if (diagnostics) {
                dumpPursuitState(view, synthesized, nowMillis);
            }
            Action action = decide(view, synthesized, transponderOn, battleBusy,
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
                case CUSTOMS_PURSUE -> pursueForInspection(fleet, mirror, view);
                case CUSTOMS_DIALOG -> {
                    endCustomsPursuit(fleet, mirror, view, nowMillis, "caught");
                    fireCustomsDialog(view, nowMillis);
                }
                case CUSTOMS_GIVE_UP -> endCustomsPursuit(fleet, mirror, view, nowMillis,
                        customsGiveUpReason(view, transponderOn, battleBusy));
                case NONE -> {
                    // nothing to synthesize for this fleet this scan
                }
            }
        }
        forgetUnscannedCustomsPursuits(seenThisScan);
    }

    /**
     * Advances (or seeds) the lost-contact counter for a live inspection chase. Runs before
     * {@link #decide} so the count the decision reads is this scan's, not the previous one's.
     */
    private CustomsPursuit trackCustomsVisibility(String coopFleetId, boolean visible) {
        CustomsPursuit pursuit = customsPursuits.get(coopFleetId);
        if (pursuit == null) {
            return null;
        }
        if (visible) {
            pursuit.unseenScans = 0;
        } else {
            pursuit.unseenScans++;
        }
        return pursuit;
    }

    /**
     * Drops chase state for a patrol that was not in the mirror's location this scan — it jumped out,
     * despawned, or the guest left. There is no fleet handle left to cancel the assignment through, so
     * this relies on {@link #CUSTOMS_PURSUIT_ASSIGNMENT_DAYS} bounding it instead.
     */
    private void forgetUnscannedCustomsPursuits(Set<String> seenThisScan) {
        if (customsPursuits.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, CustomsPursuit>> it = customsPursuits.entrySet().iterator();
        while (it.hasNext()) {
            String id = it.next().getKey();
            if (!seenThisScan.contains(id)) {
                it.remove();
                CoopLog.info(CoopNpcThreatWatcher.class, "Coop customs pursuit gaveUp reason=outOfScan"
                        + " coopFleetId=" + id + " (the assignment expires on its own within "
                        + CUSTOMS_PURSUIT_ASSIGNMENT_DAYS + " campaign days)");
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

    /**
     * Starts or sustains a patrol's inspection chase of the dark mirror.
     *
     * <h3>Why an assignment and not {@code setPriorityTarget}</h3>
     * {@code setPriorityTarget} is the right tool for a <em>hostile</em> chaser and the wrong one here,
     * and the decompile is unambiguous about why. The priority target is only promoted to
     * {@code this.target} <em>after</em> the candidate loop ({@code TacticalModule.java:461-466}); on
     * the next tactical interval that same loop walks the mirror again, and because a patrol is not
     * hostile to it the mirror falls through to the loop's tail, which reads
     * {@code if (campaignFleet2 != this.target) continue; this.setTarget(null); this.priorityTarget =
     * null;} ({@code :485-487}). A non-hostile priority target therefore erases itself within one
     * interval (0.05–0.1 days, {@code :207-209}) and no chase happens.
     *
     * <p>An {@code INTERCEPT} assignment steers through the assignment/navigation modules instead,
     * which the Phase 14 spike proved works against a mirror (closed 389 &rarr; 17 su at 151 su/s).
     * This is the same call the Phase 14b hostile path deleted, and it is safe <em>here</em> for two
     * reasons the hostile case did not have: a non-hostile patrol cannot turn the
     * {@code isAllowedToEngage} short-circuit ({@code StrategicModule.java:551-553}) into an
     * engagement licence, because no battle can form against the mirror and the stop is our own
     * synthesized {@code DIALOG_BEGIN}; and every exit path in {@link #decideCustomsPursuit} routes
     * through {@link #endCustomsPursuit}, which removes the assignment. The ten-minute siege came from
     * a chase with no exit, not from the assignment.
     *
     * <p>Idempotent: the assignment is only issued when the patrol is not already running ours.
     */
    private void pursueForInspection(CampaignFleetAPI patrol, CampaignFleetAPI mirror, FleetView view) {
        boolean starting = !customsPursuits.containsKey(view.coopFleetId());
        if (starting) {
            customsPursuits.put(view.coopFleetId(), new CustomsPursuit(clockTimestamp()));
            CoopLog.info(CoopNpcThreatWatcher.class, "Coop customs pursuit starting coopFleetId="
                    + view.coopFleetId() + " fleet=" + view.fleetName() + " (" + view.factionId() + ")"
                    + " dist=" + String.format("%.1f", view.distance())
                    + " patienceDays=" + String.format("%.1f", view.pursuitBudgetDays()));
        }
        if (hasOurInterceptAssignment(patrol, mirror)) {
            return;
        }
        try {
            CampaignFleetAIAPI ai = patrol.getAI();
            if (ai == null) {
                return;
            }
            ai.addAssignmentAtStart(FleetAssignment.INTERCEPT, mirror,
                    CUSTOMS_PURSUIT_ASSIGNMENT_DAYS, null);
            CoopLog.info(CoopNpcThreatWatcher.class, "Coop customs pursuit chasing coopFleetId="
                    + view.coopFleetId() + " fleet=" + view.fleetName()
                    + " dist=" + String.format("%.1f", view.distance())
                    + " elapsedDays=" + String.format("%.2f", view.customsPursuitDays()));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop failed to issue an inspection chase for"
                    + " coopFleetId=" + view.coopFleetId(), ex);
        }
    }

    /**
     * Ends an inspection chase: drops the state, removes every {@code INTERCEPT} we pointed at the
     * mirror, and stamps the customs cooldown so the patrol does not immediately re-acquire. The
     * cooldown is stamped on <em>every</em> exit, the stop included, which is what keeps a patrol that
     * just hailed the guest from turning round and hailing again.
     */
    private void endCustomsPursuit(CampaignFleetAPI patrol, CampaignFleetAPI mirror, FleetView view,
                                   long nowMillis, String reason) {
        boolean wasPursuing = customsPursuits.remove(view.coopFleetId()) != null;
        cooldowns.mark(cooldownKey(view.coopFleetId(), Action.CUSTOMS_DIALOG), nowMillis);
        int removed = removeOurInterceptAssignments(patrol, mirror);
        if (!wasPursuing && removed == 0) {
            return;
        }
        CoopLog.info(CoopNpcThreatWatcher.class, "Coop customs pursuit " + reason + " coopFleetId="
                + view.coopFleetId() + " fleet=" + view.fleetName()
                + " dist=" + String.format("%.1f", view.distance())
                + " elapsedDays=" + String.format("%.2f", view.customsPursuitDays())
                + " unseenScans=" + view.customsUnseenScans()
                + " assignmentsCleared=" + removed);
    }

    private static boolean hasOurInterceptAssignment(CampaignFleetAPI patrol, CampaignFleetAPI mirror) {
        try {
            CampaignFleetAIAPI ai = patrol.getAI();
            if (ai == null) {
                return false;
            }
            FleetAssignmentDataAPI current = ai.getCurrentAssignment();
            return current != null && current.getAssignment() == FleetAssignment.INTERCEPT
                    && current.getTarget() == mirror;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Removes every {@code INTERCEPT} on this fleet that points at the mirror. Scans the whole queue
     * rather than only the current assignment: ours is added at the start, so a chase that gets
     * re-issued while an earlier one is still queued would otherwise leave a second copy behind and
     * the patrol would resume chasing the moment the first expired.
     */
    private static int removeOurInterceptAssignments(CampaignFleetAPI patrol, CampaignFleetAPI mirror) {
        try {
            CampaignFleetAIAPI ai = patrol.getAI();
            if (ai == null) {
                return 0;
            }
            int removed = 0;
            for (FleetAssignmentDataAPI assignment : ai.getAssignmentsCopy()) {
                if (assignment == null || assignment.getAssignment() != FleetAssignment.INTERCEPT
                        || assignment.getTarget() != mirror) {
                    continue;
                }
                ai.removeAssignment(assignment);
                removed++;
            }
            return removed;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcThreatWatcher.class, "Coop failed to clear an inspection chase"
                    + " assignment", ex);
            return 0;
        }
    }

    /** Campaign days a chase has been running, from the engine clock. Zero when nothing is running. */
    private static float elapsedDaysSince(CustomsPursuit pursuit) {
        if (pursuit == null) {
            return 0f;
        }
        try {
            CampaignClockAPI clock = Global.getSector() == null ? null : Global.getSector().getClock();
            return clock == null ? 0f : clock.getElapsedDaysSince(pursuit.startedAtTimestamp);
        } catch (RuntimeException | LinkageError ex) {
            return 0f;
        }
    }

    private static long clockTimestamp() {
        try {
            CampaignClockAPI clock = Global.getSector() == null ? null : Global.getSector().getClock();
            return clock == null ? 0L : clock.getTimestamp();
        } catch (RuntimeException | LinkageError ex) {
            return 0L;
        }
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
                + " contact=" + String.format("%.1f", view.contactDistance())
                + " patrol=" + view.patrol()
                + " customsPursuit=" + (view.customsPursuing() ? "chasing" : "idle")
                + " customsDays=" + String.format("%.2f", view.customsPursuitDays())
                + " customsUnseen=" + view.customsUnseenScans());
    }

    // ---- engine reads (all best-effort) ----------------------------------------------------------

    private static FleetView viewOf(CampaignFleetAPI fleet, CampaignFleetAPI mirror, String coopFleetId,
                                    boolean visible, CustomsPursuit pursuit, float customsPursuitDays) {
        TacticalModulePlugin tactical = tacticalModule(fleet);
        StrategicModulePlugin strategic = strategicModule(fleet);
        boolean patrol = isPatrol(fleet);
        return new FleetView(
                coopFleetId,
                safeName(fleet),
                factionOf(fleet),
                isHostileTo(fleet, mirror),
                picksEngage(fleet, mirror),
                patrol,
                isCombatCapable(fleet),
                visible,
                tactical != null && tactical.getTarget() == mirror,
                isAllowedToEngage(strategic, mirror),
                pursuitDays(tactical),
                pursuitBudgetDays(patrol, burnLevel(fleet)),
                distance(fleet, mirror),
                contactDistance(radius(fleet), radius(mirror)),
                pursuit != null,
                customsPursuitDays,
                pursuit == null ? 0 : pursuit.unseenScans);
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
