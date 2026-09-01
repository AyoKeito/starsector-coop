package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.intel.inspection.HegemonyInspectionIntel;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionIntel;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel;
import com.fs.starfarer.api.util.Misc;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 24 milestone 3: makes NPC threats against player colonies visible on both clients.
 *
 * <p><b>Why a mirror and not replication.</b> The threats themselves are host-simulated: the guest's
 * {@code PunitiveExpeditionManager}, {@code PlayerRelatedPirateBaseManager} and the whole
 * {@code RouteManager} are on the Phase 13 suppressor's list, so the guest has no expedition to
 * generate an intel entry from, and their fleets reach it through Phase 9 replication instead. What
 * is missing on the guest is only the <em>warning</em>, which is why this ships the warning's data
 * and lets {@link CoopExpeditionWarningIntel} present it.
 *
 * <p><b>Shape: set reconciliation, like {@code BASE_SET}.</b> The host scans its intel manager on a
 * low-rate tick, builds the record set, and broadcasts the whole set whenever the order-independent
 * hash changes; the guest reconciles its mirrored entries against the last set it received. Full-set
 * rebroadcast rather than deltas is the same self-correcting choice Phase 9 and Phase 13 made. The
 * empty set is a legitimate value that clears everything, and a session (re)start re-arms the
 * rebroadcast so a fresh connection always gets the full set.
 *
 * <h2>Scan coverage</h2>
 *
 * <p>0.98a has two disjoint hierarchies for "a faction sends fleets to hit a system", and only
 * scanning one of them was the trap here:
 *
 * <ul>
 *   <li><b>{@code RaidIntel}</b> — the legacy hierarchy. Two subclasses resolve a target market
 *       directly and are the must-haves: {@link PunitiveExpeditionIntel#getTarget()} and
 *       {@link HegemonyInspectionIntel#getTarget()}. A plain {@code RaidIntel} (what a pirate base
 *       raid uses) exposes only {@code getSystem()}, so its targets are derived as the player-owned
 *       markets in that system. In unmodded 0.98a that derivation never fires: {@code
 *       PirateBaseIntel.startRaid} refuses outright to raid a system containing a player market
 *       ("actually just no raids against the player, period - that's handled by Colony Crises"). It
 *       is covered anyway because the cost is four lines and a mod lifting that restriction should
 *       not silently lose the warning.</li>
 *   <li><b>{@code FleetGroupIntel}</b> — everything the colony-crisis system spawns
 *       ({@code GenericRaidFGI} and its subclasses: blockades, league and Diktat expeditions, the
 *       Knights of Ludd takeover, TT mercenary attacks). <b>These do not extend {@code RaidIntel} at
 *       all</b>, so a {@code RaidIntel} scan misses every colony crisis — the most common threat a
 *       modern colony faces. Targets come from
 *       {@code GenericRaidFGI.getParams().raidParams.allowedTargets}, filtered to player-owned
 *       markets, with the markets of {@code getRaidAction().getWhere()} as the fallback for the
 *       blockade shape, which targets a system rather than a market list.</li>
 * </ul>
 *
 * <p><b>Not covered:</b> a {@code FleetGroupIntel} that is not a {@code GenericRaidFGI} (in the base
 * game only {@code TestFleetGroupIntel}) has no target expression this can read, and is skipped.
 * Pather cells and other non-fleet colony pressure are not "inbound attack" intel and are out of
 * scope for a countdown.
 *
 * <p>The reconcile decision is a pure function ({@link #plan}) over records; every engine touch goes
 * through the {@link WarningWorld} seam so the decision table is unit-testable without the engine.
 */
public final class CoopExpeditionWarningSync {

    private CoopExpeditionWarningSync() {
    }

    /** Host scan cadence. Matches {@code CoopBaseAuthority}'s; the ETA only moves in whole days. */
    public static final long HOST_POLL_INTERVAL_MILLIS = 1000L;
    /**
     * Guest re-reconcile cadence. Slow on purpose: the set only changes when the host's does, and the
     * pass exists mostly to refresh each entry's staleness timer.
     */
    public static final long GUEST_RECONCILE_INTERVAL_MILLIS = 5000L;

    // ---- Host capture --------------------------------------------------------------------------

    /**
     * Seam over the host's live threat intel so {@link #captureHostWarnings(HostThreatScan)} stays
     * testable: the vanilla intel constructors are massively side-effectful and cannot run in a test.
     */
    public interface HostThreatScan {
        /**
         * Warnings from every live intel of this type, or {@code null} when the scan could not be
         * performed at all (no sector, no intel manager). An empty list means "no threats of this
         * kind".
         */
        List<CoopExpeditionWarning> scan(Class<?> type);
    }

    /** Engine-backed capture. */
    public static List<CoopExpeditionWarning> captureHostWarnings() {
        return captureHostWarnings(CoopExpeditionWarningSync::scanHostIntel);
    }

    /**
     * Pure composition half of the host capture. Defensive per type so one broken scan cannot blank
     * the other half of the set, and the null-vs-empty distinction is decided here: {@code null} only
     * when <em>neither</em> hierarchy could be read, because broadcasting the resulting empty set
     * would tell the guest to drop every warning it is showing.
     *
     * <p>Duplicates across the two scans (the same faction, kind and colony) are collapsed, keeping
     * the <em>nearest</em> threat — the one whose countdown matters.
     */
    public static List<CoopExpeditionWarning> captureHostWarnings(HostThreatScan scan) {
        Objects.requireNonNull(scan, "scan");
        Map<String, CoopExpeditionWarning> byIdentity = new LinkedHashMap<>();
        boolean anyRead = scanInto(scan, RaidIntel.class, byIdentity);
        anyRead |= scanInto(scan, FleetGroupIntel.class, byIdentity);
        return anyRead ? new ArrayList<>(byIdentity.values()) : null;
    }

    private static boolean scanInto(HostThreatScan scan, Class<?> type,
                                    Map<String, CoopExpeditionWarning> out) {
        try {
            List<CoopExpeditionWarning> found = scan.scan(type);
            if (found == null) {
                return false;
            }
            for (CoopExpeditionWarning warning : found) {
                if (warning == null) {
                    continue;
                }
                CoopExpeditionWarning existing = out.get(warning.identityKey());
                if (existing == null || nearer(warning, existing)) {
                    out.put(warning.identityKey(), warning);
                }
            }
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopExpeditionWarningSync.class,
                    "Failed to scan host threat intel of type " + type.getSimpleName(), ex);
            return false;
        }
    }

    private static boolean nearer(CoopExpeditionWarning candidate, CoopExpeditionWarning existing) {
        if (candidate.status() != existing.status()) {
            return candidate.status() == CoopExpeditionWarning.Status.ARRIVED;
        }
        return candidate.etaDays() < existing.etaDays();
    }

    /** The engine-backed {@link HostThreatScan}. */
    private static List<CoopExpeditionWarning> scanHostIntel(Class<?> type) {
        SectorAPI sector = Global.getSector();
        IntelManagerAPI intel = sector == null ? null : sector.getIntelManager();
        if (intel == null) {
            return null;
        }
        List<IntelInfoPlugin> items = intel.getIntel(type);
        if (items == null) {
            return List.of();
        }
        List<CoopExpeditionWarning> warnings = new ArrayList<>();
        for (IntelInfoPlugin item : items) {
            if (item == null) {
                continue;
            }
            if (item instanceof BaseIntelPlugin plugin && (plugin.isEnding() || plugin.isEnded())) {
                continue;
            }
            try {
                warnings.addAll(toWarnings(item));
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopExpeditionWarningSync.class,
                        "Failed to read a coop threat warning from " + item.getClass().getSimpleName(), ex);
            }
        }
        return warnings;
    }

    /** One intel entry to zero or more warnings, one per threatened player colony. */
    private static List<CoopExpeditionWarning> toWarnings(IntelInfoPlugin item) {
        if (item instanceof PunitiveExpeditionIntel expedition) {
            return one(CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION, factionId(expedition.getFaction()),
                    expedition.getTarget(), expedition.getETA(), punitiveGoal(expedition));
        }
        if (item instanceof HegemonyInspectionIntel inspection) {
            // No goal: vanilla renders no "Goal:" bullet for an inspection, and the entry's own title
            // already says what it is. Shipping "" is the documented "omit the line" value.
            return one(CoopExpeditionWarning.Kind.INSPECTION, factionId(inspection.getFaction()),
                    inspection.getTarget(), inspection.getETA(), "");
        }
        if (item instanceof GenericRaidFGI raid) {
            // Days until the force is on target, which is what the vanilla panel itself shows
            // (GenericRaidFGI.addNonUpdateBulletPoints reads getETAUntil(PAYLOAD_ACTION)).
            float eta = raid.getETAUntil(GenericRaidFGI.PAYLOAD_ACTION);
            return many(CoopExpeditionWarning.Kind.HOSTILE_ACTIVITY, factionId(raid.getFaction()),
                    raidTargets(raid), eta, raidGoalText(nounOf(raid)));
        }
        if (item instanceof RaidIntel raid) {
            return many(CoopExpeditionWarning.Kind.RAID, factionId(raid.getFaction()),
                    playerMarketsIn(raid.getSystem()), raid.getETA(), raidGoalText(null));
        }
        return List.of();
    }

    // ---- Goal resolution -----------------------------------------------------------------------

    /**
     * The punitive expedition's objective as display text. {@link PunitiveExpeditionIntel#getGoal()}
     * is the accessor; the industry name comes from {@link PunitiveExpeditionIntel#getTargetIndustry()}
     * and is only meaningful for the two raid goals.
     */
    private static String punitiveGoal(PunitiveExpeditionIntel expedition) {
        String industry = null;
        try {
            Industry target = expedition.getTargetIndustry();
            industry = target == null ? null : target.getCurrentName();
        } catch (RuntimeException | LinkageError ex) {
            // A half-built expedition with no industry picked yet is not a reason to lose the goal.
            industry = null;
        }
        return punitiveGoalText(expedition.getGoal(), industry);
    }

    /**
     * Pure half of the punitive goal, so the wording is testable without an expedition.
     *
     * <p>Wording follows vanilla: {@code PunitiveExpeditionIntel} bullets a literal
     * {@code "Goal: saturation bombardment"} for {@code BOMBARD}, and its long description describes
     * the two raid goals as disrupting a named industry. Lowercase, because these read as the tail of
     * a "Goal:" bullet — except for the industry name, which is a proper noun vanilla capitalises.
     */
    static String punitiveGoalText(PunitiveExpeditionManager.PunExGoal goal, String industryName) {
        if (goal == null) {
            return "";
        }
        String industry = industryName == null ? "" : industryName.trim();
        return switch (goal) {
            case BOMBARD -> "saturation bombardment";
            case RAID_PRODUCTION -> industry.isEmpty()
                    ? "raid to disrupt production" : "raid to disrupt " + industry;
            case RAID_SPACEPORT -> industry.isEmpty()
                    ? "raid to disrupt the spaceport" : "raid to disrupt " + industry;
        };
    }

    /**
     * Pure half of the raid-hierarchy goal. {@code GenericRaidFGI.getNoun()} is what vanilla itself
     * uses to name the operation ("raid", "attack", "expedition", "blockade"), and its subclasses
     * override it; a plain {@code RaidIntel} has no such accessor, so it falls back to "raid".
     */
    static String raidGoalText(String noun) {
        String text = noun == null ? "" : noun.trim();
        return text.isEmpty() ? "raid" : text.toLowerCase(Locale.ROOT);
    }

    private static String nounOf(GenericRaidFGI raid) {
        try {
            return raid.getNoun();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * A colony-crisis raid's targets. The explicit list is authoritative when present — the hostile
     * activity factors fill it with exactly the markets they intend to hit — and the system's player
     * colonies are the fallback for the blockade shape, which names a system and a target faction
     * rather than a market list.
     */
    private static List<MarketAPI> raidTargets(GenericRaidFGI raid) {
        List<MarketAPI> targets = new ArrayList<>();
        GenericRaidFGI.GenericRaidParams params = raid.getParams();
        if (params != null && params.raidParams != null && params.raidParams.allowedTargets != null) {
            targets.addAll(params.raidParams.allowedTargets);
        }
        if (targets.isEmpty() && raid.getRaidAction() != null) {
            targets.addAll(playerMarketsIn(raid.getRaidAction().getWhere()));
        }
        return targets;
    }

    private static List<MarketAPI> playerMarketsIn(StarSystemAPI system) {
        if (system == null) {
            return List.of();
        }
        List<MarketAPI> markets = Misc.getMarketsInLocation(system, Factions.PLAYER);
        return markets == null ? List.of() : markets;
    }

    private static List<CoopExpeditionWarning> one(CoopExpeditionWarning.Kind kind, String factionId,
                                                   MarketAPI target, float etaDays, String goal) {
        return many(kind, factionId, target == null ? List.of() : List.of(target), etaDays, goal);
    }

    private static List<CoopExpeditionWarning> many(CoopExpeditionWarning.Kind kind, String factionId,
                                                    Collection<MarketAPI> targets, float etaDays,
                                                    String goal) {
        List<CoopExpeditionWarning> warnings = new ArrayList<>();
        if (targets == null) {
            return warnings;
        }
        int eta = CoopExpeditionWarning.bucketEta(etaDays);
        CoopExpeditionWarning.Status status = eta <= 0
                ? CoopExpeditionWarning.Status.ARRIVED : CoopExpeditionWarning.Status.INBOUND;
        for (MarketAPI target : targets) {
            if (target == null || target.getId() == null || !target.isPlayerOwned()) {
                continue;
            }
            warnings.add(new CoopExpeditionWarning(kind, factionId, target.getId(),
                    target.getName(), eta, status, goal));
        }
        return warnings;
    }

    private static String factionId(com.fs.starfarer.api.campaign.FactionAPI faction) {
        return faction == null || faction.getId() == null ? "" : faction.getId();
    }

    // ---- Guest reconcile -----------------------------------------------------------------------

    /** What the guest must do to one warning identity to match the host. */
    public enum ActionType {
        /** Mirrored here, absent from the host set: end it. */
        REMOVE,
        /** Present on both but an attribute (ETA, status, target name, goal) differs: update in place. */
        UPDATE,
        /** Absent here: create the intel entry. */
        ADD
    }

    /** One reconcile step. {@link #record()} always carries the <em>desired</em> (host) values. */
    public record Action(ActionType type, CoopExpeditionWarning record) {
        public Action {
            type = Objects.requireNonNull(type, "type");
            record = Objects.requireNonNull(record, "record");
        }
    }

    /**
     * The reconcile decision table, keyed by {@link CoopExpeditionWarning#identityKey()}. Removal
     * first, so a set that swaps one threat for another never briefly shows both.
     *
     * <p>Duplicate identities in either input collapse last-wins; the capture already collapses them,
     * but a malformed payload must not make the plan quadratic or non-deterministic.
     */
    public static List<Action> plan(Collection<CoopExpeditionWarning> desired,
                                    Collection<CoopExpeditionWarning> local) {
        Map<String, CoopExpeditionWarning> want = byIdentity(desired);
        Map<String, CoopExpeditionWarning> have = byIdentity(local);

        List<Action> removes = new ArrayList<>();
        List<Action> updates = new ArrayList<>();
        List<Action> adds = new ArrayList<>();

        for (Map.Entry<String, CoopExpeditionWarning> entry : have.entrySet()) {
            if (!want.containsKey(entry.getKey())) {
                removes.add(new Action(ActionType.REMOVE, entry.getValue()));
            }
        }
        for (Map.Entry<String, CoopExpeditionWarning> entry : want.entrySet()) {
            CoopExpeditionWarning wanted = entry.getValue();
            CoopExpeditionWarning mine = have.get(entry.getKey());
            if (mine == null) {
                adds.add(new Action(ActionType.ADD, wanted));
            } else if (!mine.equals(wanted)) {
                updates.add(new Action(ActionType.UPDATE, wanted));
            }
        }

        List<Action> plan = new ArrayList<>(removes.size() + updates.size() + adds.size());
        plan.addAll(removes);
        plan.addAll(updates);
        plan.addAll(adds);
        return plan;
    }

    private static Map<String, CoopExpeditionWarning> byIdentity(
            Collection<CoopExpeditionWarning> records) {
        // LinkedHashMap: the plan's within-bucket order follows input order, so it is reproducible.
        Map<String, CoopExpeditionWarning> byKey = new LinkedHashMap<>();
        if (records != null) {
            for (CoopExpeditionWarning record : records) {
                if (record != null) {
                    byKey.put(record.identityKey(), record);
                }
            }
        }
        return byKey;
    }

    /** What one reconcile pass did. */
    public record Summary(int added, int updated, int removed) {
        public boolean isNoOp() {
            return added == 0 && updated == 0 && removed == 0;
        }

        @Override
        public String toString() {
            return "added=" + added + " updated=" + updated + " removed=" + removed;
        }
    }

    /**
     * Seam over the guest's engine state so {@link #apply} stays a pure, testable decision function.
     * The engine-typed implementation is {@link SectorWarningWorld}; tests drive a fake.
     */
    public interface WarningWorld {
        /** The warnings this client currently mirrors, as records. */
        List<CoopExpeditionWarning> localWarnings();

        void add(CoopExpeditionWarning record);

        void update(CoopExpeditionWarning record);

        void remove(CoopExpeditionWarning record);

        /**
         * Refresh every mirrored entry's staleness timer. Runs on every pass, including no-op ones:
         * the timer is what stops a warning from sitting in a save forever, so it has to be reset by
         * the mere fact that a session is still here, not only by a change.
         */
        void touchAll();
    }

    /** Executes {@link #plan}. Idempotent: applying the same desired set twice plans nothing. */
    public static Summary apply(WarningWorld world, Collection<CoopExpeditionWarning> desired) {
        Objects.requireNonNull(world, "world");
        int added = 0;
        int updated = 0;
        int removed = 0;
        for (Action action : plan(desired, world.localWarnings())) {
            switch (action.type()) {
                case REMOVE -> {
                    world.remove(action.record());
                    removed++;
                }
                case UPDATE -> {
                    world.update(action.record());
                    updated++;
                }
                case ADD -> {
                    world.add(action.record());
                    added++;
                }
            }
        }
        world.touchAll();
        return new Summary(added, updated, removed);
    }

    /** Engine-typed {@link WarningWorld}. Instantiated only on the guest. */
    public static final class SectorWarningWorld implements WarningWorld {
        private final IntelManagerAPI intel;

        public SectorWarningWorld(IntelManagerAPI intel) {
            this.intel = Objects.requireNonNull(intel, "intel");
        }

        private List<CoopExpeditionWarningIntel> entries() {
            List<CoopExpeditionWarningIntel> found = new ArrayList<>();
            List<IntelInfoPlugin> items = intel.getIntel(CoopExpeditionWarningIntel.class);
            if (items == null) {
                return found;
            }
            for (IntelInfoPlugin item : items) {
                if (item instanceof CoopExpeditionWarningIntel warning
                        && !warning.isEnding() && !warning.isEnded()) {
                    found.add(warning);
                }
            }
            return found;
        }

        @Override
        public List<CoopExpeditionWarning> localWarnings() {
            List<CoopExpeditionWarning> records = new ArrayList<>();
            for (CoopExpeditionWarningIntel entry : entries()) {
                records.add(entry.toRecord());
            }
            return records;
        }

        @Override
        public void add(CoopExpeditionWarning record) {
            CoopExpeditionWarningIntel entry = new CoopExpeditionWarningIntel(record);
            // forceNoMessage=false: an inbound attack on a shared colony is exactly the kind of thing
            // the player should be interrupted for, and it is the only notification this channel ever
            // produces (updates go through the entry's own fields, not through sendUpdate).
            intel.addIntel(entry, false);
        }

        @Override
        public void update(CoopExpeditionWarning record) {
            for (CoopExpeditionWarningIntel entry : entries()) {
                if (entry.toRecord().sameIdentity(record)) {
                    entry.update(record);
                }
            }
        }

        @Override
        public void remove(CoopExpeditionWarning record) {
            for (CoopExpeditionWarningIntel entry : entries()) {
                if (entry.toRecord().sameIdentity(record)) {
                    entry.endImmediately();
                    intel.removeIntel(entry);
                }
            }
        }

        @Override
        public void touchAll() {
            for (CoopExpeditionWarningIntel entry : entries()) {
                entry.touch();
            }
        }

        /** Session teardown: every mirrored warning goes, so none can survive into a solo load. */
        public int clearAll() {
            int cleared = 0;
            for (CoopExpeditionWarningIntel entry : entries()) {
                entry.endImmediately();
                intel.removeIntel(entry);
                cleared++;
            }
            return cleared;
        }
    }
}
