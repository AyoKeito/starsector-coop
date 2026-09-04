package coop.campaign;

import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivityIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import coop.campaign.CoopBaseAuthority.Action;
import coop.campaign.CoopBaseAuthority.ActionType;
import coop.campaign.CoopBaseAuthority.BaseWorld;
import coop.campaign.CoopBaseAuthority.Summary;
import coop.campaign.CoopBaseRecord.Kind;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopBaseAuthorityTest {

    // ---- Wire format --------------------------------------------------------------------------

    @Test
    void recordRoundTripsThroughEncoding() {
        CoopBaseRecord record = CoopBaseRecord.pirate("corvus", "pirates", "TIER_3_2MODULE");
        assertEquals(record, CoopBaseRecord.decode(record.encode()));
    }

    @Test
    void recordRoundTripsDelimiterHostileIds() {
        CoopBaseRecord record = new CoopBaseRecord(Kind.PATHER,
                "sys|with\\pipes\nand\r\nbreaks", "fac|tion\\x", CoopBaseRecord.ATTR_LARGE);
        CoopBaseRecord decoded = CoopBaseRecord.decode(record.encode());
        assertEquals(record, decoded);
        assertEquals("sys|with\\pipes\nand\r\nbreaks", decoded.systemId());
        assertEquals("fac|tion\\x", decoded.factionId());
        // The encoded record must stay on one line so the newline-joined set encoding stays parseable.
        assertFalse(record.encode().contains("\n"));
        assertFalse(record.encode().contains("\r"));
    }

    @Test
    void setRoundTripsIncludingDelimiterHostileIds() {
        List<CoopBaseRecord> records = List.of(
                CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE"),
                CoopBaseRecord.pather("hybra\nsa|", "luddic_path", true),
                CoopBaseRecord.pather("askonia", "luddic_path", false));
        List<CoopBaseRecord> decoded = CoopBaseRecord.decodeSet(CoopBaseRecord.encodeSet(records));
        assertEquals(new HashSet<>(records), new HashSet<>(decoded));
        assertEquals(records.size(), decoded.size());
    }

    @Test
    void emptySetRoundTrips() {
        assertEquals("", CoopBaseRecord.encodeSet(List.of()));
        assertTrue(CoopBaseRecord.decodeSet("").isEmpty());
    }

    @Test
    void decodeRejectsWrongFieldCountAndUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> CoopBaseRecord.decode("PIRATE|corvus|pirates"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopBaseRecord.decode("REMNANT|corvus|pirates|TIER_1_1MODULE"));
    }

    // ---- Set hash -----------------------------------------------------------------------------

    @Test
    void setHashIsOrderIndependent() {
        CoopBaseRecord a = CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE");
        CoopBaseRecord b = CoopBaseRecord.pather("askonia", "luddic_path", true);
        CoopBaseRecord c = CoopBaseRecord.pirate("magec", "pirates", "TIER_5_3MODULE");
        assertEquals(CoopBaseRecord.setHash(List.of(a, b, c)), CoopBaseRecord.setHash(List.of(c, a, b)));
    }

    @Test
    void setHashFlipsOnTierUpgrade() {
        assertNotEquals(
                CoopBaseRecord.setHash(List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE"))),
                CoopBaseRecord.setHash(List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_2_1MODULE"))));
    }

    @Test
    void setHashFlipsOnIsLargeChangeAndOnSpawn() {
        assertNotEquals(
                CoopBaseRecord.setHash(List.of(CoopBaseRecord.pather("askonia", "luddic_path", false))),
                CoopBaseRecord.setHash(List.of(CoopBaseRecord.pather("askonia", "luddic_path", true))));
        assertNotEquals(
                CoopBaseRecord.setHash(List.of(CoopBaseRecord.pather("askonia", "luddic_path", true))),
                CoopBaseRecord.setHash(List.of(
                        CoopBaseRecord.pather("askonia", "luddic_path", true),
                        CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE"))));
    }

    @Test
    void identityIgnoresFactionAndAttrButNotKind() {
        CoopBaseRecord tier1 = CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE");
        CoopBaseRecord tier5 = CoopBaseRecord.pirate("corvus", "cabal", "TIER_5_3MODULE");
        assertEquals(tier1.identityKey(), tier5.identityKey());
        assertNotEquals(tier1.identityKey(),
                CoopBaseRecord.pather("corvus", "luddic_path", true).identityKey());
    }

    // ---- Reconcile decision table -------------------------------------------------------------

    @Test
    void planAddsMissingBases() {
        List<Action> plan = CoopBaseAuthority.plan(
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE")),
                List.of());
        assertEquals(1, plan.size());
        assertEquals(ActionType.ADD, plan.get(0).type());
        assertEquals("corvus", plan.get(0).record().systemId());
    }

    @Test
    void planRemovesBasesAbsentFromHostSet() {
        List<Action> plan = CoopBaseAuthority.plan(
                List.of(),
                List.of(CoopBaseRecord.pather("askonia", "luddic_path", true)));
        assertEquals(1, plan.size());
        assertEquals(ActionType.REMOVE, plan.get(0).type());
        assertEquals(Kind.PATHER, plan.get(0).record().kind());
    }

    @Test
    void planUpdatesAttrInPlaceForTierUpgrade() {
        List<Action> plan = CoopBaseAuthority.plan(
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_4_3MODULE")),
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_2_1MODULE")));
        assertEquals(1, plan.size());
        assertEquals(ActionType.UPDATE_ATTR, plan.get(0).type());
        assertEquals("TIER_4_3MODULE", plan.get(0).record().attr());
    }

    @Test
    void planUpdatesAttrInPlaceForIsLargeMismatch() {
        List<Action> plan = CoopBaseAuthority.plan(
                List.of(CoopBaseRecord.pather("askonia", "luddic_path", true)),
                List.of(CoopBaseRecord.pather("askonia", "luddic_path", false)));
        assertEquals(List.of(new Action(ActionType.UPDATE_ATTR,
                CoopBaseRecord.pather("askonia", "luddic_path", true))), plan);
    }

    @Test
    void planRecreatesOnFactionMismatch() {
        List<Action> plan = CoopBaseAuthority.plan(
                List.of(CoopBaseRecord.pirate("corvus", "cabal", "TIER_1_1MODULE")),
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE")));
        assertEquals(1, plan.size());
        assertEquals(ActionType.RECREATE, plan.get(0).type());
        assertEquals("cabal", plan.get(0).record().factionId());
    }

    @Test
    void planIsEmptyWhenSetsMatch() {
        List<CoopBaseRecord> both = List.of(
                CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE"),
                CoopBaseRecord.pather("askonia", "luddic_path", true));
        assertTrue(CoopBaseAuthority.plan(both, both).isEmpty());
        // Iteration order must not matter.
        assertTrue(CoopBaseAuthority.plan(both, List.of(both.get(1), both.get(0))).isEmpty());
    }

    @Test
    void planKeysOnKindAndSystemOnly() {
        // Same system, different kind: two separate identities, so neither collides with the other.
        List<Action> plan = CoopBaseAuthority.plan(
                List.of(CoopBaseRecord.pather("corvus", "luddic_path", true)),
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE")));
        assertEquals(List.of(
                new Action(ActionType.REMOVE, CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE")),
                new Action(ActionType.ADD, CoopBaseRecord.pather("corvus", "luddic_path", true))), plan);
    }

    @Test
    void planOrdersRemovalsBeforeAdditions() {
        List<Action> plan = CoopBaseAuthority.plan(
                List.of(CoopBaseRecord.pirate("magec", "pirates", "TIER_1_1MODULE")),
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE")));
        assertEquals(ActionType.REMOVE, plan.get(0).type());
        assertEquals(ActionType.ADD, plan.get(1).type());
    }

    // ---- Reconcile execution ------------------------------------------------------------------

    @Test
    void applyCreatesRemovesAndAlwaysEndsDerivedActivityIntel() {
        FakeWorld world = new FakeWorld(CoopBaseRecord.pather("askonia", "luddic_path", true));
        Summary summary = CoopBaseAuthority.apply(world,
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE")), new HashSet<>());

        assertEquals(new Summary(1, 1, 0, 0, 0), summary);
        assertEquals(List.of("remove:PATHER/askonia", "create:PIRATE/corvus"), world.calls);
        assertEquals(1, world.activitySweeps);
    }

    @Test
    void applyIsIdempotentAcrossRepeatedIdenticalSets() {
        FakeWorld world = new FakeWorld();
        List<CoopBaseRecord> desired = List.of(
                CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE"),
                CoopBaseRecord.pather("askonia", "luddic_path", false));
        Set<String> failures = new HashSet<>();

        assertEquals(new Summary(2, 0, 0, 0, 0), CoopBaseAuthority.apply(world, desired, failures));
        world.calls.clear();
        assertEquals(new Summary(0, 0, 0, 0, 0), CoopBaseAuthority.apply(world, desired, failures));
        assertEquals(new Summary(0, 0, 0, 0, 0), CoopBaseAuthority.apply(world, desired, failures));
        assertTrue(world.calls.isEmpty(), "repeat sets must not touch the world: " + world.calls);
        assertEquals(2, world.bases.size());
        // The activity-intel sweep still runs on every pass — the mirrored base regrows it.
        assertEquals(3, world.activitySweeps);
    }

    @Test
    void applyUpdatesAttrInPlaceWithoutRebuilding() {
        FakeWorld world = new FakeWorld(CoopBaseRecord.pirate("corvus", "pirates", "TIER_2_1MODULE"));
        Summary summary = CoopBaseAuthority.apply(world,
                List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_4_3MODULE")), new HashSet<>());

        assertEquals(new Summary(0, 0, 1, 0, 0), summary);
        assertEquals(List.of("updateAttr:PIRATE/corvus"), world.calls);
        assertEquals("TIER_4_3MODULE", world.bases.get(0).attr());
    }

    @Test
    void applyFallsBackToRebuildWhenInPlaceUpdateFails() {
        FakeWorld world = new FakeWorld(CoopBaseRecord.pather("askonia", "luddic_path", false));
        world.updateAttrSucceeds = false;
        Summary summary = CoopBaseAuthority.apply(world,
                List.of(CoopBaseRecord.pather("askonia", "luddic_path", true)), new HashSet<>());

        assertEquals(new Summary(0, 0, 0, 1, 0), summary);
        assertEquals(List.of("updateAttr:PATHER/askonia", "remove:PATHER/askonia", "create:PATHER/askonia"),
                world.calls);
        assertTrue(world.bases.get(0).isLarge());
    }

    @Test
    void applyRecreatesOnFactionMismatch() {
        FakeWorld world = new FakeWorld(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE"));
        Summary summary = CoopBaseAuthority.apply(world,
                List.of(CoopBaseRecord.pirate("corvus", "cabal", "TIER_1_1MODULE")), new HashSet<>());

        assertEquals(new Summary(0, 0, 0, 1, 0), summary);
        assertEquals(List.of("remove:PIRATE/corvus", "create:PIRATE/corvus"), world.calls);
        assertEquals("cabal", world.bases.get(0).factionId());
    }

    @Test
    void failedConstructionIsNotRetriedEveryPass() {
        FakeWorld world = new FakeWorld();
        world.createSucceeds = false;
        List<CoopBaseRecord> desired = List.of(CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_1MODULE"));
        Set<String> failures = new HashSet<>();

        assertEquals(new Summary(0, 0, 0, 0, 1), CoopBaseAuthority.apply(world, desired, failures));
        assertEquals(1, failures.size());
        world.calls.clear();
        assertEquals(new Summary(0, 0, 0, 0, 0), CoopBaseAuthority.apply(world, desired, failures));
        assertTrue(world.calls.isEmpty(), "a failed construction must not be retried: " + world.calls);
    }

    // ---- Role gating --------------------------------------------------------------------------

    @Test
    void onlyTheGuestReconciles() {
        assertTrue(CoopBaseAuthority.reconcilesForRole(CoopConnectionRole.GUEST));
        assertFalse(CoopBaseAuthority.reconcilesForRole(CoopConnectionRole.HOST));
        assertFalse(CoopBaseAuthority.reconcilesForRole(CoopConnectionRole.NONE));
    }

    // ---- Host capture (Phase 24 M2: intel scan, not manager poll) -------------------------------

    /**
     * The whole point of the M2 switch: {@code PlayerRelatedPirateBaseManager} keeps its bases in a
     * private list with no {@code getActive()}, so the old manager poll could not see them. Anything
     * the intel scan yields is captured, whichever manager created it.
     */
    @Test
    void hostCaptureTakesEveryBaseTheIntelScanYields() {
        CoopBaseRecord managerBase = CoopBaseRecord.pirate("corvus", "pirates", "TIER_1_MINOR");
        CoopBaseRecord playerRelatedBase = CoopBaseRecord.pirate("yma", "pirates", "TIER_3_2MODULE");
        CoopBaseRecord patherBase = CoopBaseRecord.pather("askonia", "luddic_path", true);

        List<CoopBaseRecord> captured = CoopBaseAuthority.captureHostBases(type ->
                type == PirateBaseIntel.class
                        ? List.of(managerBase, playerRelatedBase)
                        : List.of(patherBase));

        assertEquals(List.of(managerBase, playerRelatedBase, patherBase), captured);
    }

    /**
     * Load-bearing null-vs-empty distinction: {@code null} means "no reading this poll" and keeps the
     * host silent, {@code empty} means "no bases" and tells the guest to drop every mirror. Getting
     * these the wrong way round wipes the guest's bases whenever a scan fails.
     */
    @Test
    void hostCapturePreservesTheNullVersusEmptyDistinction() {
        assertTrue(CoopBaseAuthority.captureHostBases(type -> List.of()).isEmpty(),
                "both scans readable and empty means 'no bases', not 'no reading'");
        assertNull(CoopBaseAuthority.captureHostBases(type -> null),
                "no scan readable must stay null so the host says nothing");
        assertNull(CoopBaseAuthority.captureHostBases(type -> {
            throw new IllegalStateException("intel manager exploded");
        }), "a throwing scan is a failed reading, not an empty world");
    }

    /** One broken scan must not blank the other half of the set. */
    @Test
    void hostCaptureKeepsTheReadableHalfWhenTheOtherScanFails() {
        CoopBaseRecord patherBase = CoopBaseRecord.pather("askonia", "luddic_path", false);

        List<CoopBaseRecord> captured = CoopBaseAuthority.captureHostBases(type -> {
            if (type == PirateBaseIntel.class) {
                throw new IllegalStateException("pirate intel scan failed");
            }
            return List.of(patherBase);
        });

        assertEquals(List.of(patherBase), captured);
    }

    /** No sector / no intel manager is the "no reading" case, and must not throw. */
    @Test
    void liveIntelOnANullManagerIsEmptyRatherThanAThrow() {
        assertTrue(CoopBaseAuthority.liveIntel(null, PirateBaseIntel.class).isEmpty());
    }

    // ---- Seam fake ----------------------------------------------------------------------------

    // ---- Intel sweep --------------------------------------------------------------------------

    /**
     * {@code PirateActivityIntel}'s constructor <em>queues</em> itself instead of adding whenever its
     * target system holds no player market -- the common case for a base the guest mirrors -- while
     * still calling {@code Global.getSector().addScript(this)}. {@code getIntel(Class)} reads only the
     * added list, so the guest's sweep walked straight past a script that goes on stamping
     * {@code PIRATE_ACTIVITY} onto its markets forever.
     */
    @Test
    void theIntelSweepSeesTheCommQueueAsWellAsTheAddedList() {
        IntelInfoPlugin added = intel();
        IntelInfoPlugin queued = intel();
        IntelManagerAPI manager = intelManager(List.of(added), List.of(queued));

        assertEquals(List.of(added), CoopBaseAuthority.liveIntel(manager, PirateActivityIntel.class));
        assertEquals(List.of(added, queued),
                CoopBaseAuthority.liveIntelIncludingQueued(manager, PirateActivityIntel.class));
    }

    @Test
    void intelThatIsBothAddedAndQueuedIsOnlySweptOnce() {
        IntelInfoPlugin both = intel();
        IntelManagerAPI manager = intelManager(List.of(both), List.of(both));

        assertEquals(List.of(both),
                CoopBaseAuthority.liveIntelIncludingQueued(manager, PirateActivityIntel.class));
    }

    @Test
    void anUnreadableCommQueueStillYieldsTheAddedList() {
        IntelInfoPlugin added = intel();
        IntelManagerAPI manager = (IntelManagerAPI) Proxy.newProxyInstance(
                IntelManagerAPI.class.getClassLoader(), new Class<?>[]{IntelManagerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getIntel" -> List.of(added);
                    case "getCommQueue" -> throw new IllegalStateException("no comm queue");
                    case "toString" -> "FakeIntelManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });

        assertEquals(List.of(added),
                CoopBaseAuthority.liveIntelIncludingQueued(manager, PirateActivityIntel.class));
    }

    private static IntelManagerAPI intelManager(List<IntelInfoPlugin> added,
                                                List<IntelInfoPlugin> queued) {
        return (IntelManagerAPI) Proxy.newProxyInstance(
                IntelManagerAPI.class.getClassLoader(), new Class<?>[]{IntelManagerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getIntel" -> added;
                    case "getCommQueue" -> queued;
                    case "toString" -> "FakeIntelManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static IntelInfoPlugin intel() {
        return (IntelInfoPlugin) Proxy.newProxyInstance(
                IntelInfoPlugin.class.getClassLoader(), new Class<?>[]{IntelInfoPlugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "FakeIntel";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /**
     * Stands in for the guest's intel manager. The engine base constructors cannot run in a unit
     * test (they mint markets, register intel and roll placement from {@code Misc.random}), so the
     * decision table is exercised against this instead.
     */
    private static final class FakeWorld implements BaseWorld {
        private final List<CoopBaseRecord> bases = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private int activitySweeps;
        private boolean createSucceeds = true;
        private boolean updateAttrSucceeds = true;

        private FakeWorld(CoopBaseRecord... initial) {
            bases.addAll(Arrays.asList(initial));
        }

        @Override
        public List<CoopBaseRecord> localBases() {
            return new ArrayList<>(bases);
        }

        @Override
        public boolean create(CoopBaseRecord record) {
            calls.add("create:" + label(record));
            if (!createSucceeds) {
                return false;
            }
            bases.add(record);
            return true;
        }

        @Override
        public void remove(CoopBaseRecord record) {
            calls.add("remove:" + label(record));
            bases.removeIf(base -> base.sameIdentity(record));
        }

        @Override
        public boolean updateAttr(CoopBaseRecord record) {
            calls.add("updateAttr:" + label(record));
            if (!updateAttrSucceeds) {
                return false;
            }
            replace(bases, record);
            return true;
        }

        @Override
        public void endDerivedActivityIntel() {
            activitySweeps++;
        }

        private static void replace(List<CoopBaseRecord> bases, CoopBaseRecord record) {
            for (int i = 0; i < bases.size(); i++) {
                if (bases.get(i).sameIdentity(record)) {
                    bases.set(i, record);
                    return;
                }
            }
        }

        private static String label(CoopBaseRecord record) {
            return record.kind().name() + "/" + record.systemId();
        }
    }
}
