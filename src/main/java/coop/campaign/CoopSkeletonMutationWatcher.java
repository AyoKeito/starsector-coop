package coop.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure decision half of the Phase 13 skeleton-mutation capture: a client polls the pollable skeleton
 * mutations (campaign-objective ownership, story-gate activation, and since Phase 12c planet survey
 * levels and ruins exploration) and this class decides which of them changed since the last poll.
 * The replicator supplies the engine readings and turns the returned flips into {@code WORLD_DELTA}
 * broadcasts. Gates are polled host-side only; objectives, survey levels and ruins are polled on both
 * roles, because either player produces those and the flip has to reach the other side.
 *
 * <p><b>Why survey is a poll too.</b> {@code SurveyLevel} is one shared field per market with five
 * mutation paths — the survey dialog panel, the {@code remote_survey} ability
 * ({@code abilities/RemoteSurveyAbility.java:98}), system-entry SEEN marking
 * ({@code CoreScript.markSystemAsEntered:441-443}), salvaged survey-data caches
 * ({@code SurveyDataSpecial.java:160}, which can target a planet a couple of light-years away) and
 * story rulecmds — and only the first has any listener at all. A sector-wide poll is the only
 * capture that covers all five. Same for {@code $ruinsExplored}, which rules.csv sets directly.
 *
 * <p><b>Why a poll.</b> Objective ownership flips come out of the host's war sim
 * ({@code WarSimScript.processStarSystem} &rarr; {@code Objectives.control}, api_src
 * {@code command/WarSimScript.java:144-186} and {@code rulecmd/salvage/Objectives.java:290-322}).
 * There <em>is</em> an {@code ObjectiveEventListener.reportObjectiveChangedHands} hook, but its only
 * core implementor is {@code WarSimScript} itself — the sim the guest suppresses — so a poll over the
 * gen-time objective entities is both simpler and a superset (it also catches ownership set by
 * dialog capture or by any mod). Gates have no event at all: {@code GateEntityPlugin.advance} latches
 * its private {@code madeActive} field from {@code canUseGates() && isScanned(entity)}
 * ({@code GateEntityPlugin.java:274-284}), so the poll reads the underlying flags instead.
 *
 * <p><b>Seeding.</b> The first diff after a session starts records the baseline silently — the two
 * clients start from the same deterministic skeleton, so reporting the whole sector as "changed"
 * would be pure noise. Gates are the one exception: a gate already in a non-default state at seed
 * time <em>is</em> reported, because a gate scanned before the session began (Galatia, via the
 * academy questline) is exactly the state a joining guest is missing. Objectives have no comparable
 * "default", and there are hundreds of them, so their seed pass stays silent.
 *
 * <p>Entities that appear after the baseline is seeded are recorded silently as well: a war-sim
 * <em>built</em> objective is a brand-new entity whose engine id is minted per client and therefore
 * would never resolve on the other side. Only gen-time entities that change state are reported.
 */
public final class CoopSkeletonMutationWatcher {

    /** One reportable state change: the entity's id and its new state payload. */
    public record Flip(String entityId, String state) {
        public Flip {
            entityId = Objects.requireNonNull(entityId, "entityId");
            state = state == null ? "" : state;
        }
    }

    /** Decoded {@link #encodeGateState} payload. */
    public record GateState(boolean scanned, boolean gatesActive, boolean canUseGates) {
    }

    /** A gate nobody has scanned, in a campaign where gates are not active: nothing to report. */
    public static final String DEFAULT_GATE_STATE = encodeGateState(false, false, false);

    /**
     * Vanilla's market-memory flag for "somebody has already salvaged this planet's ruins", set by
     * the {@code salRuins_postSalvagePerform} rules.csv command and read by mission generation
     * ({@code HubMissionWithSearch.java:752}, {@code Misc.java:2905/5881}). There is no
     * {@code MemFlags} constant for it in the API — vanilla uses the raw string everywhere.
     */
    public static final String RUINS_EXPLORED_FLAG = "$ruinsExplored";

    private final Map<String, String> objectiveOwners = new LinkedHashMap<>();
    private final Map<String, String> gateStates = new LinkedHashMap<>();
    private final Map<String, String> surveyLevels = new LinkedHashMap<>();
    private final Map<String, String> ruinsExplored = new LinkedHashMap<>();
    private boolean objectivesSeeded;
    private boolean gatesSeeded;
    private boolean surveyLevelsSeeded;
    private boolean ruinsSeeded;

    /**
     * Diffs {@code entityId -> owning faction id} against the remembered map. Returns one flip per
     * objective whose owner actually changed; the seeding pass returns nothing.
     */
    public List<Flip> diffObjectiveOwners(Map<String, String> current) {
        List<Flip> flips = diff(objectiveOwners, current, objectivesSeeded, null);
        objectivesSeeded = true;
        return flips;
    }

    /**
     * Diffs {@code entityId -> } {@link #encodeGateState} against the remembered map. On the seeding
     * pass only gates already in a non-default state are reported (see the class note).
     */
    public List<Flip> diffGateStates(Map<String, String> current) {
        List<Flip> flips = diff(gateStates, current, gatesSeeded, DEFAULT_GATE_STATE);
        gatesSeeded = true;
        return flips;
    }

    /**
     * Diffs {@code planetId -> } {@code MarketAPI.SurveyLevel} name against the remembered map. Seeds
     * silently: a fresh sector has hundreds of planets, all at the level deterministic worldgen gave
     * both clients, and reporting that as "changed" would put ~800 deltas on the wire at every
     * session start. Every planet is present from the first poll (planets are gen-time), so a level
     * that rises later is still caught.
     */
    public List<Flip> diffSurveyLevels(Map<String, String> current) {
        List<Flip> flips = diff(surveyLevels, current, surveyLevelsSeeded, null);
        surveyLevelsSeeded = true;
        return flips;
    }

    /**
     * Diffs {@code planetId -> "true"/"false"} ({@link #RUINS_EXPLORED_FLAG}) against the remembered
     * map. Same silent seeding as the survey levels, and for the same reason: the caller feeds every
     * ruins-bearing planet on every pass, explored or not, so the flip is a value change on an entry
     * that has been in the baseline since the first poll.
     */
    public List<Flip> diffRuinsExplored(Map<String, String> current) {
        List<Flip> flips = diff(ruinsExplored, current, ruinsSeeded, null);
        ruinsSeeded = true;
        return flips;
    }

    /**
     * @param seedBaseline the value a not-yet-seen entity is assumed to have held, or {@code null} to
     *                     treat every not-yet-seen entity as silently new.
     */
    private static List<Flip> diff(Map<String, String> baseline, Map<String, String> current,
                                   boolean seeded, String seedBaseline) {
        Objects.requireNonNull(current, "current");
        List<Flip> flips = new ArrayList<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String entityId = entry.getKey();
            String state = entry.getValue() == null ? "" : entry.getValue();
            String previous = baseline.get(entityId);
            if (previous == null && !seeded) {
                // Seeding pass: only a state that differs from the known default is worth reporting.
                previous = seedBaseline;
            }
            if (previous != null && !previous.equals(state)) {
                flips.add(new Flip(entityId, state));
            }
        }
        // Entities that vanished (system unloaded, objective destroyed) leave the baseline without
        // reporting: their disappearance is not an ownership change.
        baseline.keySet().retainAll(current.keySet());
        baseline.putAll(current);
        return flips;
    }

    /** Forgets every baseline so the next poll re-seeds silently (session end / reconnect). */
    public void clear() {
        objectiveOwners.clear();
        gateStates.clear();
        surveyLevels.clear();
        ruinsExplored.clear();
        objectivesSeeded = false;
        gatesSeeded = false;
        surveyLevelsSeeded = false;
        ruinsSeeded = false;
    }

    /** Last owner this watcher recorded for an objective, or {@code null}. Tests + diagnostics. */
    public String objectiveOwner(String entityId) {
        return objectiveOwners.get(entityId);
    }

    /** Last gate state this watcher recorded, or {@code null}. Tests + diagnostics. */
    public String gateState(String entityId) {
        return gateStates.get(entityId);
    }

    /** Last survey level this watcher recorded for a planet, or {@code null}. Tests + diagnostics. */
    public String surveyLevel(String planetId) {
        return surveyLevels.get(planetId);
    }

    /** Last {@code $ruinsExplored} value this watcher recorded, or {@code null}. Tests + diagnostics. */
    public String ruinsExplored(String planetId) {
        return ruinsExplored.get(planetId);
    }

    // ---- Guest-apply decisions ----------------------------------------------------------------
    //
    // Pure: the replicator reads the engine, asks these what to do, and performs only the writes
    // they ask for. Every one of them is idempotent by construction — re-applying the same delta
    // resolves to a skip, which is what makes the host's echo rebroadcast harmless even if it were
    // to slip past the ledger.

    /** What a guest should do with an incoming {@link CoopWorldDelta.Kind#DECIV}. */
    public enum DecivDecision {
        DECIVILIZE,
        /**
         * No such market in the economy. Also the already-applied case: vanilla's
         * {@code decivilize()} ends with {@code getEconomy().removeMarket(market)}, so a market that
         * already went through this is gone from the lookup.
         */
        SKIP_UNKNOWN_MARKET,
        SKIP_ALREADY_DECIVILIZED,
        /** A market with no primary entity would NPE inside vanilla's routine. */
        SKIP_NO_PRIMARY_ENTITY
    }

    public static DecivDecision decideDeciv(boolean marketInEconomy, boolean alreadyDecivilized,
                                            boolean hasPrimaryEntity) {
        if (!marketInEconomy) {
            return DecivDecision.SKIP_UNKNOWN_MARKET;
        }
        if (alreadyDecivilized) {
            return DecivDecision.SKIP_ALREADY_DECIVILIZED;
        }
        if (!hasPrimaryEntity) {
            return DecivDecision.SKIP_NO_PRIMARY_ENTITY;
        }
        return DecivDecision.DECIVILIZE;
    }

    /**
     * Whether an objective's faction actually needs setting. False for a blank payload and for a
     * delta that names the faction the objective already flies — the idempotent re-apply case.
     */
    public static boolean shouldSetObjectiveFaction(String currentFactionId, String newFactionId) {
        if (newFactionId == null) {
            return false;
        }
        String target = newFactionId.trim();
        return !target.isEmpty() && !target.equals(currentFactionId);
    }

    /** The writes a {@link CoopWorldDelta.Kind#GATE_ACTIVATED} still needs, given local state. */
    public record GateApply(boolean setGatesActive, boolean setCanUseGates, boolean setScanned) {
        public boolean isNoOp() {
            return !setGatesActive && !setCanUseGates && !setScanned;
        }
    }

    /**
     * Flags are set, never unset: the host only ever reports gates becoming usable, and clearing a
     * flag the guest set from its own Janus device would take a working gate away from it.
     */
    public static GateApply decideGate(GateState desired, boolean gatesActiveNow,
                                       boolean canUseGatesNow, boolean scannedNow) {
        Objects.requireNonNull(desired, "desired");
        return new GateApply(
                desired.gatesActive() && !gatesActiveNow,
                desired.canUseGates() && !canUseGatesNow,
                desired.scanned() && !scannedNow);
    }

    // ---- Payload encodings --------------------------------------------------------------------
    //
    // The envelope parser is flat (no JSON arrays), so every payload here is a single delimited
    // string riding CoopWorldDelta.newStateJson.

    /**
     * {@code scanned|gatesActive|canUseGates}. The two globals are sector-memory flags
     * ({@code GateEntityPlugin.GATES_ACTIVE} / {@code PLAYER_CAN_USE_GATES}, api_src
     * {@code GateEntityPlugin.java:41-42}) and are repeated on every gate record so each delta is
     * self-contained — the guest can apply one packet and be correct.
     */
    public static String encodeGateState(boolean scanned, boolean gatesActive, boolean canUseGates) {
        return scanned + "|" + gatesActive + "|" + canUseGates;
    }

    public static GateState decodeGateState(String payload) {
        List<String> fields = CoopDelimited.split(CoopDelimited.normalize(payload));
        return new GateState(
                fields.size() > 0 && Boolean.parseBoolean(fields.get(0)),
                fields.size() > 1 && Boolean.parseBoolean(fields.get(1)),
                fields.size() > 2 && Boolean.parseBoolean(fields.get(2)));
    }

    /** Separates the {@code fullDestroy} flag from the occurrence stamp. */
    private static final char DECIV_SEP = '#';

    /** {@code fullDestroy} — the one parameter of {@code DecivTracker.decivilize} that changes the outcome. */
    public static String encodeDeciv(boolean fullDestroy) {
        return Boolean.toString(fullDestroy);
    }

    /**
     * As {@link #encodeDeciv(boolean)}, plus the campaign timestamp the deciv happened at.
     *
     * <p>Decivilization is an <em>event</em>, not a state, and a market id can carry more than one of
     * them in a session: vanilla re-uses the planet's gen-time market object when a colony is founded,
     * so a colony that decivilizes, is re-founded on the same planet and decivilizes again produces
     * two deltas with the same {@code (kind, entityId)}. The stamp makes the second one a distinct
     * payload, which is what the latest-wins ledger keys on.
     *
     * <p>The campaign timestamp rather than a counter, deliberately: it separates two events months
     * apart while reading <em>identical</em> for a repeat report of the same one, so the host's echo
     * rebroadcast and a same-frame double fire of the vanilla listener are both still deduped.
     */
    public static String encodeDeciv(boolean fullDestroy, long campaignTimestamp) {
        return fullDestroy + String.valueOf(DECIV_SEP) + campaignTimestamp;
    }

    /** Reads the flag out of either payload form; the occurrence stamp is ledger-only. */
    public static boolean decodeDecivFullDestroy(String payload) {
        String text = CoopDelimited.normalize(payload).trim();
        int sep = text.indexOf(DECIV_SEP);
        return Boolean.parseBoolean(sep < 0 ? text : text.substring(0, sep));
    }
}
