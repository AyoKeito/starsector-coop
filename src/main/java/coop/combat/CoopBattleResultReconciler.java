package coop.combat;

import com.fs.starfarer.api.campaign.CampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.fleet.CoopFleetSnapshot;
import coop.fleet.CoopFleetSnapshotFactory;
import coop.util.CoopLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Host-side integration of a {@link CoopBattleResult} into the authoritative Phase 9 NPC fleet set
 * (Phase 15).
 *
 * <h2>One reconciler, two callers, never a message to yourself</h2>
 * <ul>
 *   <li><b>Guest fought</b> — the guest sends {@code BATTLE_RESULT} over reliable TCP and the pump
 *       hands the decoded record straight here.</li>
 *   <li><b>Host fought</b> — the pump calls {@link #apply} <em>directly</em> from the battle bridge's
 *       result sink. The host never sends itself a message. On that path vanilla has already applied
 *       everything to the host's own world, so the reconcile is effectively "restart the engage
 *       cooldowns and force a set rebroadcast", which is exactly what the guest's mirrors need.</li>
 * </ul>
 *
 * <h2>What it touches, and what it must never touch</h2>
 * Destroyed fleets are despawned through vanilla's own {@code CampaignFleetAPI.despawn(reason, param)}
 * so the managers that own them ({@code SourceBasedFleetManager}, base intels, the route manager)
 * hear {@code reportFleetDespawned} and release their handles instead of leaking a dead id; the
 * reason is {@code DESTROYED_BY_BATTLE} because that is what actually happened and it is what the
 * respawn timers key off. The {@code param} is null rather than a {@code BattleAPI} — the battle
 * happened in another process — and every vanilla reader of it guards with
 * {@code param instanceof BattleAPI} first (checked in the decompiled impl).
 *
 * <p>It applies <b>no reputation</b> and <b>moves no spoils</b>: see {@link CoopBattleResult} for the
 * evidence that guest battle rep already reaches the host on the Phase 12 {@code GUEST_REP_DELTA}
 * path, and for the v1 rule that the solo fighter keeps 100% of its own XP/salvage/credits/recoveries.
 * There is no credit, XP or cargo surface on {@link AuthoritativeFleets} on purpose.
 *
 * <h2>Idempotency</h2>
 * Keyed by {@code battleId} in a bounded insertion-ordered set ({@link #SEEN_BATTLE_CAPACITY}), so a
 * re-delivered or replayed result applies exactly once and the set can never grow without bound over
 * a long session.
 *
 * <p><b>Only a battle that actually landed stays in that set.</b> Every mutation the engine refuses
 * (a despawn that throws, a roster edit that throws) drops the {@code battleId} back out of the
 * ledger, so a resent {@code BATTLE_RESULT} is re-attempted instead of being waved through as
 * "already applied" — the failure that used to leave a destroyed fleet alive on the host until some
 * unrelated set change happened to remove it, while the pump counted the battle as a success.
 *
 * <p>Re-applying a result is safe by construction: the despawn pass skips any fleet that no longer
 * {@link AuthoritativeFleets#exists}, and the survivor pass recomputes the removal set against the
 * host's <em>current</em> roster, so ships the first attempt already deleted are simply not deleted
 * again. A retry is therefore never worse than the first attempt.
 */
public final class CoopBattleResultReconciler {

    /** How many recent {@code battleId}s the idempotency guard remembers. Oldest are evicted. */
    static final int SEEN_BATTLE_CAPACITY = 64;

    /** The host's authoritative fleet population, as narrow as the reconciler needs it. */
    public interface AuthoritativeFleets {

        /** True when a real engine fleet with this {@code coopFleetId} still exists. */
        boolean exists(String coopFleetId);

        /**
         * Vanilla-clean removal of a fleet the engaging client destroyed.
         *
         * @return false when the removal did not happen because the engine threw. An implementation
         *         must never report success for work it swallowed: the caller drops the battle from
         *         the applied ledger on false so a resend can retry. A fleet that is already gone is
         *         success — there is nothing left to remove.
         */
        boolean despawn(String coopFleetId);

        /**
         * Reduces the fleet's roster to the reported post-battle survivors and paints their CR/hull.
         * Called only for fleets {@link #exists} confirmed.
         *
         * @return false when the roster was not updated because the engine threw, on the same
         *         contract as {@link #despawn}.
         */
        boolean applySurvivingRoster(String coopFleetId, List<CoopFleetSnapshot.Member> survivors);

        /** Forces the next Phase 9 tick to rebroadcast the full {@code NPC_FLEET_SET}. */
        void rebroadcastSet();

        /** Restarts this fleet's {@code ENGAGE_GUEST} cooldown clock (Phase 14 threat watcher). */
        void restartEngageCooldown(String coopFleetId);
    }

    private final AuthoritativeFleets fleets;
    private final LinkedHashSet<String> seenBattleIds = new LinkedHashSet<>();

    public CoopBattleResultReconciler(AuthoritativeFleets fleets) {
        this.fleets = Objects.requireNonNull(fleets, "fleets");
    }

    /**
     * Integrates one battle result. Returns false (and does nothing) when this {@code battleId} was
     * already applied. Never throws: a reconcile failure must not take down the pump frame.
     *
     * <p>Also returns false when a mutation the result asked for did not happen. The {@code battleId}
     * then comes back out of the applied-battle ledger, so a resend of the same result is re-attempted
     * rather than dismissed as a duplicate; the class Javadoc records why replaying one is safe. Only
     * a true return means the host's world matches the result, which is what the pump's battle stats
     * count.
     *
     * <p>The set rebroadcast goes out on every path, success or failure: it costs one message and it
     * is what releases the guest's frozen mirrors, so withholding it on failure would strand them.
     */
    public boolean apply(CoopBattleResult result) {
        Objects.requireNonNull(result, "result");
        if (!remember(seenBattleIds, result.battleId())) {
            CoopLog.debug(CoopBattleResultReconciler.class,
                    "Coop BATTLE_RESULT ignored (already applied) battleId=" + result.battleId());
            return false;
        }
        int[] counts = new int[2];
        String failedStep;
        try {
            failedStep = mutate(result, counts);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleResultReconciler.class,
                    "Coop BATTLE_RESULT reconcile threw battleId=" + result.battleId(), ex);
            failedStep = "an unexpected error during the reconcile";
        }
        try {
            fleets.rebroadcastSet();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleResultReconciler.class, "Coop failed to force an NPC_FLEET_SET"
                    + " rebroadcast after battleId=" + result.battleId(), ex);
        }
        if (failedStep != null) {
            // Out of the ledger again: the world does not match the result, so a resend has to be let
            // through instead of being answered with "already applied".
            seenBattleIds.remove(result.battleId());
            CoopLog.warn(CoopBattleResultReconciler.class, "Coop BATTLE_RESULT reconcile failed"
                    + " battleId=" + result.battleId() + " step=" + failedStep
                    + "; the battle left the applied ledger so a resend can retry (despawned="
                    + counts[0] + " rosterUpdated=" + counts[1] + ")");
            return false;
        }
        CoopLog.info(CoopBattleResultReconciler.class, "Coop BATTLE_RESULT applied battleId="
                + result.battleId() + " by=" + result.engagingPlayerId()
                + " outcome=" + result.outcome() + " despawned=" + counts[0]
                + " rosterUpdated=" + counts[1]);
        return true;
    }

    /**
     * The mutation pass, split out of {@link #apply} so the first refusal can abandon the rest with a
     * plain return rather than a labelled break. Whatever already landed stays landed: the battle
     * leaves the ledger and the resend redoes the whole thing against the world as it is then.
     *
     * @param counts out-parameter, {@code [despawned, rosterUpdated]}
     * @return null when everything the result asked for happened, otherwise the step that refused
     */
    private String mutate(CoopBattleResult result, int[] counts) {
        for (String coopFleetId : result.involvedFleetIds()) {
            // Restart the cooldown even for a fleet that no longer exists: the id may still be
            // sitting in the watcher's throttle map, and a stale entry costs nothing to refresh.
            fleets.restartEngageCooldown(coopFleetId);
        }
        for (String coopFleetId : result.destroyedFleetIds()) {
            if (coopFleetId.isEmpty() || !fleets.exists(coopFleetId)) {
                continue;
            }
            if (!fleets.despawn(coopFleetId)) {
                return "despawn of destroyed coopFleetId=" + coopFleetId;
            }
            counts[0]++;
        }
        for (CoopBattleResult.SurvivingFleet survivor : result.survivingFleets()) {
            String coopFleetId = survivor.coopFleetId();
            if (coopFleetId.isEmpty() || !fleets.exists(coopFleetId)) {
                continue;
            }
            if (survivor.members().isEmpty()) {
                // Reported alive but crewless: the engaging client saw an empty hull of a fleet.
                // Treat it as destroyed rather than leaving a zero-ship fleet in the set.
                if (!fleets.despawn(coopFleetId)) {
                    return "despawn of crewless coopFleetId=" + coopFleetId;
                }
                counts[0]++;
                continue;
            }
            if (!fleets.applySurvivingRoster(coopFleetId, survivor.members())) {
                return "surviving roster of coopFleetId=" + coopFleetId;
            }
            counts[1]++;
        }
        return null;
    }

    /** Session (re)start: forget the applied-battle history along with the rest of the session. */
    public void reset() {
        seenBattleIds.clear();
    }

    public int seenBattleCount() {
        return seenBattleIds.size();
    }

    // ---- pure helpers (unit-tested) ---------------------------------------------------------------

    /**
     * Records {@code battleId} as applied, evicting the oldest entries past
     * {@link #SEEN_BATTLE_CAPACITY}. Returns false when it was already present.
     */
    static boolean remember(LinkedHashSet<String> seen, String battleId) {
        if (battleId == null || battleId.isEmpty()) {
            // A result with no id cannot be deduplicated; applying it is still better than dropping
            // a real kill, and the per-fleet work below is itself idempotent.
            return true;
        }
        if (!seen.add(battleId)) {
            return false;
        }
        while (seen.size() > SEEN_BATTLE_CAPACITY) {
            Iterator<String> oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /**
     * The roster diff: which positions in the host's current roster to remove so that what is left
     * matches the reported survivors.
     *
     * <p>Matching is a <b>multiset over {@link #memberKey}</b> (variant id, or hull id when the
     * variant is unknown), not over member ids: the guest fought a mirror whose {@code FleetMemberAPI}s
     * were minted locally, so its ids are meaningless here. Which particular Wolf died does not
     * matter to anything in the campaign; how many are left does.
     *
     * <p><b>Never removes more than the reported loss count.</b> If the keys do not line up at all
     * (a custom variant that did not round-trip through the mirror, say), the fall-through would
     * otherwise wipe a fleet that merely lost two ships. Removal is therefore capped at
     * {@code hostKeys.size() - survivorKeys.size()} — worst case the right number of the wrong ships
     * die, which is a cosmetic error rather than a destroyed fleet.
     *
     * @return indices into {@code hostKeys}, ascending
     */
    static List<Integer> membersToRemove(List<String> hostKeys, List<String> survivorKeys) {
        List<Integer> remove = new ArrayList<>();
        if (hostKeys == null || hostKeys.isEmpty()) {
            return remove;
        }
        List<String> wanted = survivorKeys == null ? List.of() : survivorKeys;
        Map<String, Integer> remaining = new HashMap<>();
        for (String key : wanted) {
            remaining.merge(key, 1, Integer::sum);
        }
        for (int i = 0; i < hostKeys.size(); i++) {
            Integer left = remaining.get(hostKeys.get(i));
            if (left != null && left > 0) {
                remaining.put(hostKeys.get(i), left - 1);
            } else {
                remove.add(i);
            }
        }
        int maxRemovable = Math.max(0, hostKeys.size() - wanted.size());
        return remove.size() <= maxRemovable ? remove : new ArrayList<>(remove.subList(0, maxRemovable));
    }

    /** Roster-matching key: the variant id, falling back to the hull id, falling back to empty. */
    static String memberKey(String hullId, String variantId) {
        if (variantId != null && !variantId.isEmpty()) {
            return "v:" + variantId;
        }
        return "h:" + (hullId == null ? "" : hullId);
    }

    // ---- engine implementation --------------------------------------------------------------------

    /**
     * The real host-side {@link AuthoritativeFleets}: looks fleets up by engine id across every
     * location and edits them in place. Split out as a nested class so the reconciler's logic can be
     * unit-tested against a fake without any engine at all.
     *
     * <p>Every engine read is best-effort — a fleet that throws while being inspected is treated as
     * absent rather than aborting the whole reconcile.
     */
    public static final class EngineFleets implements AuthoritativeFleets {

        private final Supplier<SectorAPI> sectorSupplier;
        private final Runnable setRebroadcast;
        private final Consumer<String> engageCooldownRestart;
        /**
         * Last fleet {@link #find(String)} resolved. {@code exists()} is always followed immediately by
         * {@code despawn()} or {@code applySurvivingRoster()} for the same id, and each of those ran its
         * own sector-wide scan — ~10 scans in the single frame the player returns from combat (perf
         * audit #17). The hit is revalidated ({@link #stillResolves}) rather than trusted, so a memo
         * that outlives its fleet — including across battles, since this object lives as long as the
         * pump — degrades to the scan instead of returning a corpse.
         */
        private String memoFleetId;
        private CampaignFleetAPI memoFleet;

        public EngineFleets(Supplier<SectorAPI> sectorSupplier, Runnable setRebroadcast,
                            Consumer<String> engageCooldownRestart) {
            this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
            this.setRebroadcast = Objects.requireNonNull(setRebroadcast, "setRebroadcast");
            this.engageCooldownRestart =
                    Objects.requireNonNull(engageCooldownRestart, "engageCooldownRestart");
        }

        @Override
        public boolean exists(String coopFleetId) {
            return find(coopFleetId) != null;
        }

        @Override
        public boolean despawn(String coopFleetId) {
            CampaignFleetAPI fleet = find(coopFleetId);
            if (fleet == null) {
                // Already gone: a Phase 9 sweep, a raid, or an earlier attempt at this same result.
                // "Nothing left to remove" is the outcome the caller asked for, so it is success.
                return true;
            }
            String name = safeName(fleet);
            // This id is about to stop resolving; do not leave it memoised for the survivor pass.
            memoFleetId = null;
            memoFleet = null;
            try {
                fleet.despawn(CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE, null);
                CoopLog.info(CoopBattleResultReconciler.class, "Coop despawned NPC fleet destroyed in"
                        + " the partner's battle coopFleetId=" + coopFleetId + " name=" + name);
                return true;
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBattleResultReconciler.class, "Coop failed to despawn destroyed NPC"
                        + " fleet coopFleetId=" + coopFleetId + " name=" + name, ex);
                // Reported rather than swallowed: the fleet is still in the world, so the battle must
                // not be recorded as applied or the resend would be dismissed and the ghost would stay.
                return false;
            }
        }

        @Override
        public boolean applySurvivingRoster(String coopFleetId,
                                           List<CoopFleetSnapshot.Member> survivors) {
            CampaignFleetAPI fleet = find(coopFleetId);
            if (fleet == null) {
                // Gone between the exists() check and here: there is no roster left to reduce, and
                // whatever removed it has already produced the state the result describes.
                return true;
            }
            try {
                List<FleetMemberAPI> current = fleet.getFleetData().getMembersListCopy();
                List<String> hostKeys = new ArrayList<>(current.size());
                for (FleetMemberAPI member : current) {
                    hostKeys.add(memberKey(hullIdOf(member), variantIdOf(member)));
                }
                List<String> survivorKeys = new ArrayList<>(survivors.size());
                for (CoopFleetSnapshot.Member member : survivors) {
                    survivorKeys.add(memberKey(member.hullId(), member.variantId()));
                }
                List<Integer> remove = membersToRemove(hostKeys, survivorKeys);
                for (int index : remove) {
                    fleet.getFleetData().removeFleetMember(current.get(index));
                }
                paintDamage(current, remove, survivors);
                fleet.getFleetData().setSyncNeeded();
                CoopLog.info(CoopBattleResultReconciler.class, "Coop applied post-battle roster to NPC"
                        + " fleet coopFleetId=" + coopFleetId + " name=" + safeName(fleet)
                        + " lost=" + remove.size() + " remaining=" + (current.size() - remove.size()));
                return true;
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBattleResultReconciler.class, "Coop failed to apply the post-battle"
                        + " roster to coopFleetId=" + coopFleetId, ex);
                // The roster may be half-edited. Reporting the failure is still right: the retry
                // recomputes the diff against whatever is there then, so it cannot over-remove.
                return false;
            }
        }

        /**
         * Paints the survivors' CR and hull onto the members that were kept, pairing them by key in
         * roster order. Display/pacing state only, so a mismatch is swallowed per ship.
         */
        private static void paintDamage(List<FleetMemberAPI> current, List<Integer> removed,
                                        List<CoopFleetSnapshot.Member> survivors) {
            Map<String, Deque<CoopFleetSnapshot.Member>> byKey = new HashMap<>();
            for (CoopFleetSnapshot.Member member : survivors) {
                byKey.computeIfAbsent(memberKey(member.hullId(), member.variantId()),
                        key -> new ArrayDeque<>()).add(member);
            }
            Set<Integer> removedIndices = new HashSet<>(removed);
            for (int i = 0; i < current.size(); i++) {
                if (removedIndices.contains(i)) {
                    continue;
                }
                FleetMemberAPI member = current.get(i);
                Deque<CoopFleetSnapshot.Member> queue =
                        byKey.get(memberKey(hullIdOf(member), variantIdOf(member)));
                CoopFleetSnapshot.Member reported = queue == null ? null : queue.poll();
                if (reported == null) {
                    continue;
                }
                try {
                    member.getRepairTracker().setCR(reported.cr());
                    member.getStatus().setHullFraction(reported.hullFraction());
                } catch (RuntimeException | LinkageError ignored) {
                    // battle damage on a survivor is cosmetic; never abort the reconcile over it
                }
            }
        }

        @Override
        public void rebroadcastSet() {
            setRebroadcast.run();
        }

        @Override
        public void restartEngageCooldown(String coopFleetId) {
            engageCooldownRestart.accept(coopFleetId);
        }

        private CampaignFleetAPI find(String coopFleetId) {
            if (coopFleetId == null || coopFleetId.isEmpty()) {
                return null;
            }
            if (coopFleetId.equals(memoFleetId) && stillResolves(memoFleet, coopFleetId)) {
                return memoFleet;
            }
            memoFleetId = null;
            memoFleet = null;
            CampaignFleetAPI found = scan(coopFleetId);
            if (found != null) {
                memoFleetId = coopFleetId;
                memoFleet = found;
            }
            return found;
        }

        /**
         * O(1) revalidation of a memoised fleet: still alive, still in a location, still that id. A
         * fleet that passes is exactly the one {@link #scan(String)} would return — ids are unique and
         * the scan walks locations — and a fleet that fails is one the scan would no longer find.
         */
        private static boolean stillResolves(CampaignFleetAPI fleet, String coopFleetId) {
            if (fleet == null) {
                return false;
            }
            try {
                return fleet.isAlive()
                        && fleet.getContainingLocation() != null
                        && coopFleetId.equals(safeId(fleet));
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }

        private CampaignFleetAPI scan(String coopFleetId) {
            SectorAPI sector;
            try {
                sector = sectorSupplier.get();
            } catch (RuntimeException | LinkageError ex) {
                return null;
            }
            if (sector == null) {
                return null;
            }
            try {
                for (LocationAPI location : sector.getAllLocations()) {
                    if (location == null) {
                        continue;
                    }
                    for (CampaignFleetAPI fleet : location.getFleets()) {
                        if (fleet != null && coopFleetId.equals(safeId(fleet))) {
                            return fleet;
                        }
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBattleResultReconciler.class,
                        "Coop fleet lookup failed for coopFleetId=" + coopFleetId, ex);
            }
            return null;
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

        private static String hullIdOf(FleetMemberAPI member) {
            try {
                return member.getHullId() == null ? "" : member.getHullId();
            } catch (RuntimeException | LinkageError ex) {
                return "";
            }
        }

        /**
         * Must mirror {@code CoopFleetSnapshotFactory.captureMember} exactly — the survivors on the
         * wire were produced by that method, and the multiset match only works if both sides key the
         * same way. It therefore delegates to the same function rather than re-implementing it.
         *
         * <p><b>Why the raw {@code getHullVariantId()} it used to read was wrong.</b> The engine
         * <em>inflates</em> an NPC fleet the host player has been near: every member gets a brand-new
         * variant id built from the fleet id ({@code "905d_3"}, {@code DefaultFleetInflater}:476) that
         * exists in no spec store, while the wire side skips it — {@code streamableVariantId} prefers
         * {@code getOriginalVariant()} and validates each candidate against the local spec store. The
         * two key sets were then disjoint for exactly the fleets that matter, so the multiset match
         * degraded to "remove the first N" (the wrong ships died) and {@code paintDamage} matched
         * nothing.
         */
        static String variantIdOf(FleetMemberAPI member) {
            return variantIdOf(member, CoopFleetSnapshotFactory::variantExists);
        }

        /** Predicate-injectable form: the spec store is engine-only, the key logic is not. */
        static String variantIdOf(FleetMemberAPI member, Predicate<String> variantExists) {
            ShipVariantAPI variant;
            try {
                variant = member.getVariant();
            } catch (RuntimeException | LinkageError ignored) {
                variant = null;
            }
            return CoopFleetSnapshotFactory.streamableVariantId(
                    originalVariantIdOf(variant), hullVariantIdOf(variant), specIdOf(member),
                    variantExists);
        }

        private static String originalVariantIdOf(ShipVariantAPI variant) {
            try {
                return variant == null || variant.getOriginalVariant() == null
                        ? "" : variant.getOriginalVariant();
            } catch (RuntimeException | LinkageError ex) {
                return "";
            }
        }

        private static String hullVariantIdOf(ShipVariantAPI variant) {
            try {
                return variant == null || variant.getHullVariantId() == null
                        ? "" : variant.getHullVariantId();
            } catch (RuntimeException | LinkageError ex) {
                return "";
            }
        }

        private static String specIdOf(FleetMemberAPI member) {
            try {
                return member.getSpecId() == null ? "" : member.getSpecId();
            } catch (RuntimeException | LinkageError ex) {
                return "";
            }
        }
    }
}
