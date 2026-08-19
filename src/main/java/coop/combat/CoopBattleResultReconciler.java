package coop.combat;

import com.fs.starfarer.api.campaign.CampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.fleet.CoopFleetSnapshot;
import coop.util.CoopLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
 */
public final class CoopBattleResultReconciler {

    /** How many recent {@code battleId}s the idempotency guard remembers. Oldest are evicted. */
    static final int SEEN_BATTLE_CAPACITY = 64;

    /** The host's authoritative fleet population, as narrow as the reconciler needs it. */
    public interface AuthoritativeFleets {

        /** True when a real engine fleet with this {@code coopFleetId} still exists. */
        boolean exists(String coopFleetId);

        /** Vanilla-clean removal of a fleet the engaging client destroyed. */
        void despawn(String coopFleetId);

        /**
         * Reduces the fleet's roster to the reported post-battle survivors and paints their CR/hull.
         * Called only for fleets {@link #exists} confirmed.
         */
        void applySurvivingRoster(String coopFleetId, List<CoopFleetSnapshot.Member> survivors);

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
     */
    public boolean apply(CoopBattleResult result) {
        Objects.requireNonNull(result, "result");
        if (!remember(seenBattleIds, result.battleId())) {
            CoopLog.debug(CoopBattleResultReconciler.class,
                    "Coop BATTLE_RESULT ignored (already applied) battleId=" + result.battleId());
            return false;
        }
        int despawned = 0;
        int updated = 0;
        try {
            for (String coopFleetId : result.involvedFleetIds()) {
                // Restart the cooldown even for a fleet that no longer exists: the id may still be
                // sitting in the watcher's throttle map, and a stale entry costs nothing to refresh.
                fleets.restartEngageCooldown(coopFleetId);
            }
            for (String coopFleetId : result.destroyedFleetIds()) {
                if (coopFleetId.isEmpty() || !fleets.exists(coopFleetId)) {
                    continue;
                }
                fleets.despawn(coopFleetId);
                despawned++;
            }
            for (CoopBattleResult.SurvivingFleet survivor : result.survivingFleets()) {
                String coopFleetId = survivor.coopFleetId();
                if (coopFleetId.isEmpty() || !fleets.exists(coopFleetId)) {
                    continue;
                }
                if (survivor.members().isEmpty()) {
                    // Reported alive but crewless: the engaging client saw an empty hull of a fleet.
                    // Treat it as destroyed rather than leaving a zero-ship fleet in the set.
                    fleets.despawn(coopFleetId);
                    despawned++;
                    continue;
                }
                fleets.applySurvivingRoster(coopFleetId, survivor.members());
                updated++;
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleResultReconciler.class,
                    "Coop BATTLE_RESULT reconcile failed battleId=" + result.battleId(), ex);
        }
        // Always rebroadcast, even for an empty result. It costs one message and it is the guest's
        // signal to release the mirrors it froze pending reconciliation (see CoopFleetMirrorRegistry).
        try {
            fleets.rebroadcastSet();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleResultReconciler.class, "Coop failed to force an NPC_FLEET_SET"
                    + " rebroadcast after battleId=" + result.battleId(), ex);
        }
        CoopLog.info(CoopBattleResultReconciler.class, "Coop BATTLE_RESULT applied battleId="
                + result.battleId() + " by=" + result.engagingPlayerId()
                + " outcome=" + result.outcome() + " despawned=" + despawned
                + " rosterUpdated=" + updated);
        return true;
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
        public void despawn(String coopFleetId) {
            CampaignFleetAPI fleet = find(coopFleetId);
            if (fleet == null) {
                return;
            }
            String name = safeName(fleet);
            try {
                fleet.despawn(CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE, null);
                CoopLog.info(CoopBattleResultReconciler.class, "Coop despawned NPC fleet destroyed in"
                        + " the partner's battle coopFleetId=" + coopFleetId + " name=" + name);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBattleResultReconciler.class, "Coop failed to despawn destroyed NPC"
                        + " fleet coopFleetId=" + coopFleetId + " name=" + name, ex);
            }
        }

        @Override
        public void applySurvivingRoster(String coopFleetId, List<CoopFleetSnapshot.Member> survivors) {
            CampaignFleetAPI fleet = find(coopFleetId);
            if (fleet == null) {
                return;
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
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBattleResultReconciler.class, "Coop failed to apply the post-battle"
                        + " roster to coopFleetId=" + coopFleetId, ex);
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
            for (int i = 0; i < current.size(); i++) {
                if (removed.contains(i)) {
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
         * Must mirror {@code CoopFleetSnapshotFactory.captureMember} exactly (hull-variant id,
         * falling back to the spec id) — the survivors on the wire were produced by that method, and
         * the multiset match only works if both sides key the same way.
         */
        private static String variantIdOf(FleetMemberAPI member) {
            String variantId = "";
            try {
                if (member.getVariant() != null) {
                    variantId = member.getVariant().getHullVariantId();
                }
            } catch (RuntimeException | LinkageError ignored) {
                variantId = "";
            }
            if (variantId != null && !variantId.isEmpty()) {
                return variantId;
            }
            try {
                String specId = member.getSpecId();
                return specId == null ? "" : specId;
            } catch (RuntimeException | LinkageError ex) {
                return "";
            }
        }
    }
}
