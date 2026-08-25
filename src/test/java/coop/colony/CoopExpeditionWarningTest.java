package coop.colony;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 24 milestone 3: the expedition-warning record, the set hash the host's rebroadcast is gated
 * on, the host-capture composition, and the guest's reconcile plan. All of it is pure — the vanilla
 * intel constructors are massively side-effectful and cannot run in a test, which is why
 * {@link CoopExpeditionWarningSync.HostThreatScan} and
 * {@link CoopExpeditionWarningSync.WarningWorld} exist.
 */
class CoopExpeditionWarningTest {

    // ---- Codec ---------------------------------------------------------------------------------

    @Test
    void aWarningRoundTrips() {
        CoopExpeditionWarning warning = new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION, "hegemony", "market_eos",
                "New Hope", 12, CoopExpeditionWarning.Status.INBOUND);

        assertEquals(warning, CoopExpeditionWarning.decode(warning.encode()));
    }

    @Test
    void idsAndNamesCarryingDelimiterCharactersRoundTripExactly() {
        CoopExpeditionWarning warning = new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.HOSTILE_ACTIVITY, "fac|tion", "market\neos",
                "New | Hope\nStation", 3, CoopExpeditionWarning.Status.ARRIVED);

        CoopExpeditionWarning decoded = CoopExpeditionWarning.decode(warning.encode());

        assertEquals(warning, decoded);
        assertEquals("New | Hope\nStation", decoded.targetName());
        assertEquals("market\neos", decoded.targetMarketId());
    }

    @Test
    void aWholeSetRoundTripsAndTheEmptySetIsALegitimateValue() {
        List<CoopExpeditionWarning> set = List.of(
                warning("hegemony", "market_eos", 5),
                warning("pirates", "market_yama", 2));

        assertEquals(set.size(), CoopExpeditionWarning.decodeSet(
                CoopExpeditionWarning.encodeSet(set)).size());
        assertTrue(CoopExpeditionWarning.decodeSet("").isEmpty());
        assertTrue(CoopExpeditionWarning.decodeSet(CoopExpeditionWarning.encodeSet(List.of())).isEmpty());
    }

    @Test
    void decodeRejectsMalformedRecords() {
        assertThrows(IllegalArgumentException.class, () -> CoopExpeditionWarning.decode("a|b|c"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopExpeditionWarning.decode("NOPE|hegemony|market_eos|New Hope|5|INBOUND"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopExpeditionWarning.decode("RAID|hegemony|market_eos|New Hope|five|INBOUND"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopExpeditionWarning.decode("RAID|hegemony|market_eos|New Hope|5|LANDED"));
    }

    // ---- Set hash ------------------------------------------------------------------------------

    @Test
    void theSetHashIsOrderIndependent() {
        List<CoopExpeditionWarning> a = List.of(
                warning("hegemony", "market_eos", 5), warning("pirates", "market_yama", 2));
        List<CoopExpeditionWarning> b = List.of(
                warning("pirates", "market_yama", 2), warning("hegemony", "market_eos", 5));

        assertEquals(CoopExpeditionWarning.setHash(a), CoopExpeditionWarning.setHash(b));
    }

    /** The ETA is the only field that moves on its own, so it has to move the hash. */
    @Test
    void aWholeDayOfCountdownChangesTheHash() {
        assertNotEquals(CoopExpeditionWarning.setHash(List.of(warning("hegemony", "market_eos", 5))),
                CoopExpeditionWarning.setHash(List.of(warning("hegemony", "market_eos", 4))));
    }

    /**
     * The reason the ETA is bucketed at all: the live value is a float that changes every frame, and
     * an unbucketed hash would make the host rebroadcast the set at frame rate.
     */
    @Test
    void subDayCountdownDriftDoesNotChangeTheHash() {
        String at4Point9 = hashOfBucketed(4.9f);

        assertEquals(at4Point9, hashOfBucketed(4.6f));
        assertEquals(at4Point9, hashOfBucketed(4.2f));
        assertEquals(at4Point9, hashOfBucketed(4.0001f));
        assertNotEquals(at4Point9, hashOfBucketed(3.5f));
    }

    @Test
    void theEtaBucketRoundsUpAndFloorsAtZero() {
        assertEquals(5, CoopExpeditionWarning.bucketEta(4.01f));
        assertEquals(5, CoopExpeditionWarning.bucketEta(5f));
        assertEquals(1, CoopExpeditionWarning.bucketEta(0.2f));
        assertEquals(0, CoopExpeditionWarning.bucketEta(0f));
        assertEquals(0, CoopExpeditionWarning.bucketEta(-3f));
        assertEquals(0, CoopExpeditionWarning.bucketEta(Float.NaN));
    }

    @Test
    void identityIsKindFactionAndTargetButNotEtaOrStatus() {
        CoopExpeditionWarning far = warning("hegemony", "market_eos", 9);
        CoopExpeditionWarning near = new CoopExpeditionWarning(far.kind(), far.factionId(),
                far.targetMarketId(), far.targetName(), 0, CoopExpeditionWarning.Status.ARRIVED);

        assertEquals(far.identityKey(), near.identityKey());
        assertTrue(far.sameIdentity(near));
        assertFalse(far.sameIdentity(warning("pirates", "market_eos", 9)));
        assertFalse(far.sameIdentity(warning("hegemony", "market_yama", 9)));
    }

    // ---- Host capture --------------------------------------------------------------------------

    /**
     * Both hierarchies are scanned because they are disjoint: colony crises descend from
     * {@code FleetGroupIntel} and are invisible to a {@code RaidIntel} scan.
     */
    @Test
    void bothIntelHierarchiesAreScanned() {
        List<Class<?>> scanned = new ArrayList<>();

        CoopExpeditionWarningSync.captureHostWarnings(type -> {
            scanned.add(type);
            return List.of();
        });

        assertEquals(2, scanned.size());
        assertTrue(scanned.get(0).getSimpleName().equals("RaidIntel"), scanned.toString());
        assertTrue(scanned.get(1).getSimpleName().equals("FleetGroupIntel"), scanned.toString());
    }

    @Test
    void bothScansContributeToOneSet() {
        List<CoopExpeditionWarning> captured = CoopExpeditionWarningSync.captureHostWarnings(
                type -> type.getSimpleName().equals("RaidIntel")
                        ? List.of(warning("hegemony", "market_eos", 5))
                        : List.of(warning("pirates", "market_yama", 2)));

        assertEquals(2, captured.size());
    }

    /** Two reports of the same threat collapse to the nearer one; that is the countdown that matters. */
    @Test
    void duplicateIdentitiesCollapseKeepingTheNearestThreat() {
        List<CoopExpeditionWarning> captured = CoopExpeditionWarningSync.captureHostWarnings(
                type -> List.of(warning("hegemony", "market_eos", 9),
                        warning("hegemony", "market_eos", 3)));

        assertEquals(1, captured.size());
        assertEquals(3, captured.get(0).etaDays());
    }

    @Test
    void anArrivedThreatBeatsAnInboundOneOfTheSameIdentity() {
        CoopExpeditionWarning arrived = new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.RAID, "hegemony", "market_eos", "New Hope", 0,
                CoopExpeditionWarning.Status.ARRIVED);

        List<CoopExpeditionWarning> captured = CoopExpeditionWarningSync.captureHostWarnings(
                type -> List.of(warning("hegemony", "market_eos", 4), arrived));

        assertEquals(CoopExpeditionWarning.Status.ARRIVED, captured.get(0).status());
    }

    /**
     * Null means "no reading this poll", not "no threats" — broadcasting the resulting empty set would
     * tell the guest to drop every warning it is showing.
     */
    @Test
    void aTotallyUnreadableScanReturnsNullRatherThanAnEmptySet() {
        assertEquals(null, CoopExpeditionWarningSync.captureHostWarnings(type -> null));
        assertEquals(0, CoopExpeditionWarningSync.captureHostWarnings(type ->
                type.getSimpleName().equals("RaidIntel") ? List.of() : null).size());
    }

    @Test
    void oneThrowingScanDoesNotBlankTheOtherHalfOfTheSet() {
        List<CoopExpeditionWarning> captured = CoopExpeditionWarningSync.captureHostWarnings(type -> {
            if (type.getSimpleName().equals("RaidIntel")) {
                throw new IllegalStateException("boom");
            }
            return List.of(warning("pirates", "market_yama", 2));
        });

        assertEquals(1, captured.size());
    }

    // ---- Guest reconcile -----------------------------------------------------------------------

    @Test
    void thePlanAddsUpdatesAndRemoves() {
        List<CoopExpeditionWarning> desired = List.of(
                warning("hegemony", "market_eos", 4),      // ETA moved: update
                warning("pirates", "market_yama", 2));     // new: add
        List<CoopExpeditionWarning> local = List.of(
                warning("hegemony", "market_eos", 5),
                warning("luddic_path", "market_gone", 1)); // absent from the host set: remove

        List<CoopExpeditionWarningSync.Action> plan =
                CoopExpeditionWarningSync.plan(desired, local);

        assertEquals(3, plan.size());
        assertEquals(CoopExpeditionWarningSync.ActionType.REMOVE, plan.get(0).type());
        assertEquals("market_gone", plan.get(0).record().targetMarketId());
        assertEquals(CoopExpeditionWarningSync.ActionType.UPDATE, plan.get(1).type());
        assertEquals(4, plan.get(1).record().etaDays(), "the update carries the DESIRED values");
        assertEquals(CoopExpeditionWarningSync.ActionType.ADD, plan.get(2).type());
    }

    @Test
    void anUnchangedSetPlansNothing() {
        List<CoopExpeditionWarning> set = List.of(
                warning("hegemony", "market_eos", 4), warning("pirates", "market_yama", 2));

        assertTrue(CoopExpeditionWarningSync.plan(set, set).isEmpty());
    }

    @Test
    void theEmptySetClearsEverything() {
        List<CoopExpeditionWarningSync.Action> plan = CoopExpeditionWarningSync.plan(
                List.of(), List.of(warning("hegemony", "market_eos", 4)));

        assertEquals(1, plan.size());
        assertEquals(CoopExpeditionWarningSync.ActionType.REMOVE, plan.get(0).type());
    }

    @Test
    void applyingASetIsIdempotentAndAlwaysRefreshesTheStalenessTimers() {
        FakeWorld world = new FakeWorld();
        List<CoopExpeditionWarning> desired = List.of(
                warning("hegemony", "market_eos", 4), warning("pirates", "market_yama", 2));

        CoopExpeditionWarningSync.Summary first = CoopExpeditionWarningSync.apply(world, desired);

        assertEquals(2, first.added());
        assertEquals(2, world.entries.size());
        assertEquals(1, world.touches);

        CoopExpeditionWarningSync.Summary second = CoopExpeditionWarningSync.apply(world, desired);

        assertTrue(second.isNoOp(), "a second apply of the same set writes nothing");
        assertEquals(2, world.touches, "but the staleness timers are refreshed regardless");
    }

    @Test
    void applyingConvergesOnTheHostSetInOnePass() {
        FakeWorld world = new FakeWorld();
        CoopExpeditionWarningSync.apply(world, List.of(
                warning("hegemony", "market_eos", 5), warning("luddic_path", "market_gone", 1)));

        CoopExpeditionWarningSync.Summary summary = CoopExpeditionWarningSync.apply(world, List.of(
                warning("hegemony", "market_eos", 4), warning("pirates", "market_yama", 2)));

        assertEquals(new CoopExpeditionWarningSync.Summary(1, 1, 1), summary);
        assertEquals(2, world.entries.size());
        assertEquals(4, world.entries.get(
                warning("hegemony", "market_eos", 4).identityKey()).etaDays());
    }

    // ---- Intel lifecycle -----------------------------------------------------------------------

    /**
     * The self-expire is what stops a mirrored countdown from sitting frozen in a save that is later
     * loaded solo. A live session refreshes every entry several times a minute, so this only fires
     * when there is nobody left to refresh it.
     */
    @Test
    void theIntelExpiresOnlyAfterTheStaleWindow() {
        assertFalse(CoopExpeditionWarningIntel.shouldSelfExpire(0f));
        assertFalse(CoopExpeditionWarningIntel.shouldSelfExpire(
                CoopExpeditionWarningIntel.STALE_DAYS - 0.01f));
        assertTrue(CoopExpeditionWarningIntel.shouldSelfExpire(
                CoopExpeditionWarningIntel.STALE_DAYS));
        assertTrue(CoopExpeditionWarningIntel.shouldSelfExpire(
                CoopExpeditionWarningIntel.STALE_DAYS * 10f));
    }

    /** A clock that moved backwards across a load is not a reason to end anything. */
    @Test
    void aNegativeElapsedReadingDoesNotExpireTheIntel() {
        assertFalse(CoopExpeditionWarningIntel.shouldSelfExpire(-50f));
    }

    /** Enum names are what the save stores, so an unreadable one degrades instead of throwing. */
    @Test
    void unknownPersistedEnumNamesFallBackInsteadOfThrowing() {
        assertEquals(CoopExpeditionWarning.Kind.RAID, CoopExpeditionWarningIntel.parseKind(null));
        assertEquals(CoopExpeditionWarning.Kind.RAID, CoopExpeditionWarningIntel.parseKind("NOPE"));
        assertEquals(CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION,
                CoopExpeditionWarningIntel.parseKind("punitive_expedition"));
        assertEquals(CoopExpeditionWarning.Status.INBOUND,
                CoopExpeditionWarningIntel.parseStatus(null));
        assertEquals(CoopExpeditionWarning.Status.ARRIVED,
                CoopExpeditionWarningIntel.parseStatus("arrived"));
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static String hashOfBucketed(float etaDays) {
        return CoopExpeditionWarning.setHash(List.of(new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION, "hegemony", "market_eos", "New Hope",
                CoopExpeditionWarning.bucketEta(etaDays), CoopExpeditionWarning.Status.INBOUND)));
    }

    private static CoopExpeditionWarning warning(String factionId, String marketId, int etaDays) {
        return new CoopExpeditionWarning(CoopExpeditionWarning.Kind.RAID, factionId, marketId,
                marketId, etaDays, CoopExpeditionWarning.Status.INBOUND);
    }

    private static final class FakeWorld implements CoopExpeditionWarningSync.WarningWorld {
        private final Map<String, CoopExpeditionWarning> entries = new LinkedHashMap<>();
        private int touches;

        @Override
        public List<CoopExpeditionWarning> localWarnings() {
            return new ArrayList<>(entries.values());
        }

        @Override
        public void add(CoopExpeditionWarning record) {
            entries.put(record.identityKey(), record);
        }

        @Override
        public void update(CoopExpeditionWarning record) {
            entries.put(record.identityKey(), record);
        }

        @Override
        public void remove(CoopExpeditionWarning record) {
            entries.remove(record.identityKey());
        }

        @Override
        public void touchAll() {
            touches++;
        }
    }
}
