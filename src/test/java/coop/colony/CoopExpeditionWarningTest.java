package coop.colony;

import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
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
                "New Hope", 12, CoopExpeditionWarning.Status.INBOUND, "saturation bombardment");

        CoopExpeditionWarning decoded = CoopExpeditionWarning.decode(warning.encode());

        assertEquals(warning, decoded);
        assertEquals("saturation bombardment", decoded.goal());
    }

    /** The six-argument form is how "this threat has no stated goal" is spelled. */
    @Test
    void aWarningWithNoGoalRoundTripsAsTheEmptyString() {
        CoopExpeditionWarning warning = new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.INSPECTION, "hegemony", "market_eos", "New Hope", 4,
                CoopExpeditionWarning.Status.INBOUND);

        assertEquals("", warning.goal());
        assertEquals(warning, CoopExpeditionWarning.decode(warning.encode()));
        assertEquals("", CoopExpeditionWarning.decode(warning.encode()).goal());
    }

    @Test
    void idsAndNamesCarryingDelimiterCharactersRoundTripExactly() {
        CoopExpeditionWarning warning = new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.HOSTILE_ACTIVITY, "fac|tion", "market\neos",
                "New | Hope\nStation", 3, CoopExpeditionWarning.Status.ARRIVED,
                "raid to disrupt Heavy | Industry");

        CoopExpeditionWarning decoded = CoopExpeditionWarning.decode(warning.encode());

        assertEquals(warning, decoded);
        assertEquals("New | Hope\nStation", decoded.targetName());
        assertEquals("market\neos", decoded.targetMarketId());
        assertEquals("raid to disrupt Heavy | Industry", decoded.goal());
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
                () -> CoopExpeditionWarning.decode("NOPE|hegemony|market_eos|New Hope|5|INBOUND|raid"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopExpeditionWarning.decode("RAID|hegemony|market_eos|New Hope|five|INBOUND|raid"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopExpeditionWarning.decode("RAID|hegemony|market_eos|New Hope|5|LANDED|raid"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopExpeditionWarning.decode("RAID|hegemony|market_eos|New Hope|5|INBOUND"),
                "a record missing the goal field is malformed, not a goal-less warning");
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

    /**
     * A goal that changes mid-flight (an expedition re-picking its target industry) has to reach the
     * guest, so it is folded into the hash exactly like the ETA is.
     */
    @Test
    void aChangedGoalChangesTheHash() {
        assertNotEquals(
                CoopExpeditionWarning.setHash(List.of(withGoal("saturation bombardment"))),
                CoopExpeditionWarning.setHash(List.of(withGoal("raid to disrupt Heavy Industry"))));
        assertNotEquals(CoopExpeditionWarning.setHash(List.of(withGoal(""))),
                CoopExpeditionWarning.setHash(List.of(withGoal("raid"))));
    }

    @Test
    void identityIsKindFactionAndTargetButNotEtaStatusOrGoal() {
        CoopExpeditionWarning far = warning("hegemony", "market_eos", 9);
        CoopExpeditionWarning near = new CoopExpeditionWarning(far.kind(), far.factionId(),
                far.targetMarketId(), far.targetName(), 0, CoopExpeditionWarning.Status.ARRIVED,
                "saturation bombardment");

        assertEquals(far.identityKey(), near.identityKey());
        assertTrue(far.sameIdentity(near));
        assertFalse(far.sameIdentity(warning("pirates", "market_eos", 9)));
        assertFalse(far.sameIdentity(warning("hegemony", "market_yama", 9)));
    }

    // ---- Goal resolution -----------------------------------------------------------------------

    /** The accessor is {@code PunitiveExpeditionIntel.getGoal()}; this is the wording it maps to. */
    @Test
    void thePunitiveGoalResolvesToVanillaWording() {
        assertEquals("saturation bombardment", CoopExpeditionWarningSync.punitiveGoalText(
                PunitiveExpeditionManager.PunExGoal.BOMBARD, "Heavy Industry"));
        assertEquals("raid to disrupt Heavy Industry", CoopExpeditionWarningSync.punitiveGoalText(
                PunitiveExpeditionManager.PunExGoal.RAID_PRODUCTION, "Heavy Industry"));
        assertEquals("raid to disrupt Spaceport", CoopExpeditionWarningSync.punitiveGoalText(
                PunitiveExpeditionManager.PunExGoal.RAID_SPACEPORT, "Spaceport"));
    }

    /** A half-built expedition has no target industry yet; the goal still has to say something. */
    @Test
    void thePunitiveGoalSurvivesAMissingTargetIndustry() {
        assertEquals("raid to disrupt production", CoopExpeditionWarningSync.punitiveGoalText(
                PunitiveExpeditionManager.PunExGoal.RAID_PRODUCTION, null));
        assertEquals("raid to disrupt the spaceport", CoopExpeditionWarningSync.punitiveGoalText(
                PunitiveExpeditionManager.PunExGoal.RAID_SPACEPORT, "   "));
        assertEquals("saturation bombardment", CoopExpeditionWarningSync.punitiveGoalText(
                PunitiveExpeditionManager.PunExGoal.BOMBARD, null));
    }

    /** No goal at all ships as the empty string, and the guest omits the line rather than guessing. */
    @Test
    void anUnresolvableGoalIsTheEmptyString() {
        assertEquals("", CoopExpeditionWarningSync.punitiveGoalText(null, "Heavy Industry"));
    }

    /** {@code GenericRaidFGI.getNoun()} for the FGI hierarchy, "raid" for a plain {@code RaidIntel}. */
    @Test
    void theRaidGoalUsesTheOperationNounAndFallsBackToRaid() {
        assertEquals("expedition", CoopExpeditionWarningSync.raidGoalText("expedition"));
        assertEquals("blockade", CoopExpeditionWarningSync.raidGoalText("Blockade"));
        assertEquals("raid", CoopExpeditionWarningSync.raidGoalText(null));
        assertEquals("raid", CoopExpeditionWarningSync.raidGoalText("  "));
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

    /** The surviving record keeps its own goal, not the one it displaced. */
    @Test
    void aCollapsedDuplicateCarriesTheSurvivingThreatsGoal() {
        CoopExpeditionWarning far = new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION, "hegemony", "market_eos", "New Hope",
                9, CoopExpeditionWarning.Status.INBOUND, "raid to disrupt Heavy Industry");
        CoopExpeditionWarning near = new CoopExpeditionWarning(
                CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION, "hegemony", "market_eos", "New Hope",
                2, CoopExpeditionWarning.Status.INBOUND, "saturation bombardment");

        List<CoopExpeditionWarning> captured =
                CoopExpeditionWarningSync.captureHostWarnings(type -> List.of(far, near));

        assertEquals(1, captured.size());
        assertEquals("saturation bombardment", captured.get(0).goal());
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

    /**
     * The goal is an attribute, not part of the identity, so a threat that only changes its stated
     * goal must reconcile as one UPDATE carrying the new text — never as a remove plus an add, and
     * never as a no-op that leaves the guest showing the old goal forever.
     */
    @Test
    void aChangedGoalIsAnUpdateNotAnAdd() {
        List<CoopExpeditionWarningSync.Action> plan = CoopExpeditionWarningSync.plan(
                List.of(withGoal("saturation bombardment")),
                List.of(withGoal("raid to disrupt Heavy Industry")));

        assertEquals(1, plan.size());
        assertEquals(CoopExpeditionWarningSync.ActionType.UPDATE, plan.get(0).type());
        assertEquals("saturation bombardment", plan.get(0).record().goal());
    }

    /** The same, end to end: applying converges and the mirrored entry carries the new goal. */
    @Test
    void applyingAChangedGoalUpdatesInPlace() {
        FakeWorld world = new FakeWorld();
        CoopExpeditionWarningSync.apply(world, List.of(withGoal("raid")));

        CoopExpeditionWarningSync.Summary summary =
                CoopExpeditionWarningSync.apply(world, List.of(withGoal("saturation bombardment")));

        assertEquals(new CoopExpeditionWarningSync.Summary(0, 1, 0), summary);
        assertEquals(1, world.entries.size());
        assertEquals("saturation bombardment",
                world.entries.get(withGoal("raid").identityKey()).goal());
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

    /**
     * Only the arrival is an event. The countdown moving is not, and a host-side re-estimate that
     * puts an arrived threat back to inbound must not announce a second arrival later.
     */
    @Test
    void onlyTheArrivalOfAThreatIsAnnounced() {
        assertTrue(CoopExpeditionWarningIntel.announcesArrival(
                CoopExpeditionWarning.Status.INBOUND, CoopExpeditionWarning.Status.ARRIVED));
        assertFalse(CoopExpeditionWarningIntel.announcesArrival(
                CoopExpeditionWarning.Status.INBOUND, CoopExpeditionWarning.Status.INBOUND));
        assertFalse(CoopExpeditionWarningIntel.announcesArrival(
                CoopExpeditionWarning.Status.ARRIVED, CoopExpeditionWarning.Status.ARRIVED));
        assertFalse(CoopExpeditionWarningIntel.announcesArrival(
                CoopExpeditionWarning.Status.ARRIVED, CoopExpeditionWarning.Status.INBOUND));
    }

    // ---- Guest reconcile against a real intel manager -------------------------------------------

    /**
     * These three pin the property the reconcile depends on: the mirrored set is re-read from the
     * intel manager on every pass, so no reconcile can ever mutate an entry that is dead or gone.
     * A tracking map — the obvious alternative — would survive a save and a load pointing at exactly
     * such an object, and the update path would then write to nothing forever.
     */
    @Test
    void aLiveEntryIsUpdatedInPlaceRatherThanReAdded() {
        FakeIntelManager manager = new FakeIntelManager();
        CoopExpeditionWarningSync.SectorWarningWorld world =
                new CoopExpeditionWarningSync.SectorWarningWorld(manager.api());
        CoopExpeditionWarningSync.apply(world, List.of(warning("hegemony", "market_eos", 5)));

        CoopExpeditionWarningSync.Summary summary =
                CoopExpeditionWarningSync.apply(world, List.of(warning("hegemony", "market_eos", 4)));

        assertEquals(new CoopExpeditionWarningSync.Summary(0, 1, 0), summary);
        assertEquals(1, manager.intel.size(), "no second entry was created");
        assertEquals(4, ((CoopExpeditionWarningIntel) manager.intel.get(0)).toRecord().etaDays());
    }

    /**
     * An entry that ended itself on the staleness timer can sit in the manager for a long time: the
     * manager only sweeps ended intel from {@code advance}, which returns early while the game is
     * paused — and a coop guest spends a lot of its life paused. The reconcile must treat it as
     * absent and mint a fresh one, not update the corpse.
     */
    @Test
    void anEndedEntryStillInTheManagerIsReplacedNotUpdated() {
        FakeIntelManager manager = new FakeIntelManager();
        CoopExpeditionWarningSync.SectorWarningWorld world =
                new CoopExpeditionWarningSync.SectorWarningWorld(manager.api());
        CoopExpeditionWarningSync.apply(world, List.of(warning("hegemony", "market_eos", 5)));
        ((CoopExpeditionWarningIntel) manager.intel.get(0)).endImmediately();

        CoopExpeditionWarningSync.Summary summary =
                CoopExpeditionWarningSync.apply(world, List.of(warning("hegemony", "market_eos", 4)));

        assertEquals(new CoopExpeditionWarningSync.Summary(1, 0, 0), summary);
        assertEquals(2, manager.intel.size(), "the dead entry is left for the manager to sweep");
        assertEquals(1, manager.liveEntries().size());
        assertEquals(4, manager.liveEntries().get(0).toRecord().etaDays());
    }

    /** The same for an entry the manager no longer holds at all. */
    @Test
    void anEntryMissingFromTheManagerIsAddedBack() {
        FakeIntelManager manager = new FakeIntelManager();
        CoopExpeditionWarningSync.SectorWarningWorld world =
                new CoopExpeditionWarningSync.SectorWarningWorld(manager.api());
        CoopExpeditionWarningSync.apply(world, List.of(warning("hegemony", "market_eos", 5)));
        manager.intel.clear();

        CoopExpeditionWarningSync.Summary summary =
                CoopExpeditionWarningSync.apply(world, List.of(warning("hegemony", "market_eos", 5)));

        assertEquals(new CoopExpeditionWarningSync.Summary(1, 0, 0), summary);
        assertEquals(1, manager.liveEntries().size());
    }

    /** Session teardown drops every mirrored entry, dead ones included. */
    @Test
    void clearingRemovesEveryMirroredEntry() {
        FakeIntelManager manager = new FakeIntelManager();
        CoopExpeditionWarningSync.SectorWarningWorld world =
                new CoopExpeditionWarningSync.SectorWarningWorld(manager.api());
        CoopExpeditionWarningSync.apply(world, List.of(
                warning("hegemony", "market_eos", 5), warning("pirates", "market_yama", 2)));

        assertEquals(2, world.clearAll());
        assertTrue(manager.intel.isEmpty());
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

    /** One fixed identity whose only varying attribute is the goal. */
    private static CoopExpeditionWarning withGoal(String goal) {
        return new CoopExpeditionWarning(CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION, "hegemony",
                "market_eos", "New Hope", 5, CoopExpeditionWarning.Status.INBOUND, goal);
    }

    /**
     * The three intel-manager calls {@code SectorWarningWorld} makes, over a plain list. Proxied
     * rather than implemented because {@code IntelManagerAPI} carries two dozen methods this has no
     * opinion about.
     */
    private static final class FakeIntelManager {
        private final List<IntelInfoPlugin> intel = new ArrayList<>();

        IntelManagerAPI api() {
            return (IntelManagerAPI) Proxy.newProxyInstance(
                    IntelManagerAPI.class.getClassLoader(),
                    new Class<?>[]{IntelManagerAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getIntel" -> args == null || args.length == 0
                                ? new ArrayList<>(intel) : ofClass((Class<?>) args[0]);
                        case "addIntel" -> {
                            intel.add((IntelInfoPlugin) args[0]);
                            yield null;
                        }
                        case "removeIntel" -> {
                            intel.remove(args[0]);
                            yield null;
                        }
                        case "hasIntel" -> intel.contains(args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private List<IntelInfoPlugin> ofClass(Class<?> type) {
            List<IntelInfoPlugin> found = new ArrayList<>();
            for (IntelInfoPlugin item : intel) {
                if (type.isInstance(item)) {
                    found.add(item);
                }
            }
            return found;
        }

        List<CoopExpeditionWarningIntel> liveEntries() {
            List<CoopExpeditionWarningIntel> found = new ArrayList<>();
            for (IntelInfoPlugin item : intel) {
                if (item instanceof CoopExpeditionWarningIntel entry
                        && !entry.isEnding() && !entry.isEnded()) {
                    found.add(entry);
                }
            }
            return found;
        }
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
