package coop.combat;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.fleet.CoopFleetSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine-side lookup, against interface proxies (no mocking framework in this build).
 *
 * <p>What this pins is perf audit #17: {@code exists()} is always followed immediately by
 * {@code despawn()} or {@code applySurvivingRoster()} for the same id, and each of those ran its own
 * {@code getAllLocations()} walk — ~10 sector scans in the one frame the player comes back from
 * combat. The resolved fleet is now memoised for that pair, and the memo must not be able to hand out
 * a fleet that has since stopped resolving.
 */
class CoopBattleResultReconcilerEngineFleetsTest {

    @Test
    void existsThenDespawnCostsOneSectorScan() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertTrue(fleets.exists("fleet-a"));
        assertTrue(fleets.despawn("fleet-a"));

        assertEquals(1, world.scans.get(), "the resolved fleet must carry over to the despawn");
        assertEquals(List.of("fleet-a"), world.despawned);
    }

    // ---- failure reporting: the reconciler's ledger depends on it ---------------------------------

    /**
     * A despawn the engine throws out of used to be logged and swallowed, so the reconciler recorded
     * the battle as applied while the fleet was still in the world. It has to come back as false.
     */
    @Test
    void aDespawnTheEngineRefusesIsReportedAsAFailure() {
        World world = new World("fleet-a");
        world.failDespawn = true;
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertFalse(fleets.despawn("fleet-a"));

        assertTrue(world.despawned.isEmpty());
        assertTrue(fleets.exists("fleet-a"), "the fleet is still there, which is the whole point");
    }

    /** Nothing left to remove is the outcome the caller asked for, so it is success, not failure. */
    @Test
    void despawningAFleetThatIsAlreadyGoneIsASuccess() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertTrue(fleets.despawn("ghost"));
    }

    @Test
    void aRosterEditThatThrowsIsReportedAsAFailure() {
        // getFleetData() hands back null in the fixture, so the edit throws exactly where a real
        // engine failure would - inside the try block, after the fleet resolved.
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertFalse(fleets.applySurvivingRoster("fleet-a", List.of(
                new CoopFleetSnapshot.Member("m-1", "wolf", "wolf_Assault", "Ship", "Cpt", 0.7f, 1f))));
    }

    @Test
    void aRosterEditForAFleetThatIsAlreadyGoneIsASuccess() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertTrue(fleets.applySurvivingRoster("ghost", List.of()));
        assertEquals(0, world.fleetDataReads.get());
    }

    @Test
    void repeatedLookupsOfTheSameLiveFleetReuseTheResolvedHandle() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertTrue(fleets.exists("fleet-a"));
        assertTrue(fleets.exists("fleet-a"));
        assertTrue(fleets.exists("fleet-a"));

        assertEquals(1, world.scans.get());
    }

    @Test
    void aDifferentIdStillScans() {
        World world = new World("fleet-a", "fleet-b");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertTrue(fleets.exists("fleet-a"));
        assertTrue(fleets.exists("fleet-b"));

        assertEquals(2, world.scans.get());
    }

    /**
     * The id appears in both the destroyed list and the surviving list — a contradictory but perfectly
     * possible result off the wire. After the despawn the fleet must read as gone, so the roster pass
     * skips it instead of editing a corpse.
     */
    @Test
    void aDespawnedFleetIsNotHandedBackToTheSurvivorPass() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertTrue(fleets.exists("fleet-a"));
        fleets.despawn("fleet-a");

        assertFalse(fleets.exists("fleet-a"), "the memo must not survive the despawn");
        fleets.applySurvivingRoster("fleet-a", List.of(
                new CoopFleetSnapshot.Member("m-1", "wolf", "wolf_Assault", "Ship", "Cpt", 0.7f, 1f)));
        assertEquals(0, world.fleetDataReads.get(), "a despawned fleet must never be edited");
    }

    /** A fleet that leaves the world between two battles must not be resurrected by the memo. */
    @Test
    void aMemoisedFleetThatLeavesTheWorldStopsResolving() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertTrue(fleets.exists("fleet-a"));
        world.removeQuietly("fleet-a"); // despawned by anything else: Phase 9, a raid, a save reload

        assertFalse(fleets.exists("fleet-a"));
        assertEquals(2, world.scans.get(), "a failed revalidation must fall back to the scan");
    }

    @Test
    void anUnknownIdResolvesToNothingAndIsNotMemoised() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertFalse(fleets.exists("ghost"));
        assertFalse(fleets.exists("ghost"));

        assertEquals(2, world.scans.get(), "a miss has nothing to reuse");
    }

    @Test
    void anEmptyIdNeverTouchesTheSector() {
        World world = new World("fleet-a");
        CoopBattleResultReconciler.EngineFleets fleets = world.engineFleets();

        assertFalse(fleets.exists(""));
        assertFalse(fleets.exists(null));

        assertEquals(0, world.scans.get());
    }

    @Test
    void noSectorMeansNoFleetsRatherThanAThrow() {
        CoopBattleResultReconciler.EngineFleets fleets = new CoopBattleResultReconciler.EngineFleets(
                () -> null, () -> { }, id -> { });

        assertFalse(fleets.exists("fleet-a"));
    }

    // ---- roster keys: the host must key exactly the way the wire does -----------------------------

    /**
     * An inflated host fleet is the normal case for anything the player has been near:
     * {@code DefaultFleetInflater} autofits every member onto a brand-new variant whose id is built
     * from the fleet id ("905d_3") and exists in no spec store, recording the stock variant it fitted
     * from as the member's original. The survivors on the wire carry the stock id, so keying the host
     * roster on the raw hull-variant id made the two key sets disjoint: the multiset match degraded
     * to "remove the first N" (the wrong ships died) and the damage paint matched nothing.
     */
    @Test
    void anInflatedHostMemberKeysOnTheStockVariantTheWireCarries() {
        assertEquals("wolf_Assault", CoopBattleResultReconciler.EngineFleets.variantIdOf(
                member("905d_3", "wolf_Assault", "wolf_Strike"), exists("wolf_Assault")));
    }

    @Test
    void anUninflatedMemberStillKeysOnItsOwnHullVariant() {
        assertEquals("wolf_Assault", CoopBattleResultReconciler.EngineFleets.variantIdOf(
                member("wolf_Assault", "", "wolf_Strike"), exists("wolf_Assault")));
    }

    @Test
    void theSpecIdIsTheLastCandidateAndAnUnresolvableMemberKeysOnNothing() {
        assertEquals("wolf_Strike", CoopBattleResultReconciler.EngineFleets.variantIdOf(
                member("905d_3", "", "wolf_Strike"), exists("wolf_Strike")));
        // Nothing this install can name: "" rather than an id the other side could never match.
        assertEquals("", CoopBattleResultReconciler.EngineFleets.variantIdOf(
                member("905d_3", "", "wolf_Strike"), id -> false));
    }

    @Test
    void aMemberThatThrowsWhileBeingKeyedIsKeyedOnWhatCouldBeRead() {
        assertEquals("wolf_Strike", CoopBattleResultReconciler.EngineFleets.variantIdOf(
                throwingMember("wolf_Strike"), exists("wolf_Strike")));
    }

    private static java.util.function.Predicate<String> exists(String variantId) {
        return variantId::equals;
    }

    private static FleetMemberAPI member(String hullVariantId, String originalVariant, String specId) {
        Object variant = Proxy.newProxyInstance(
                ShipVariantAPI.class.getClassLoader(),
                new Class<?>[]{ShipVariantAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHullVariantId" -> hullVariantId;
                    case "getOriginalVariant" -> originalVariant;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "variantProxy";
                    default -> defaultValue(method.getReturnType());
                });
        return (FleetMemberAPI) Proxy.newProxyInstance(
                FleetMemberAPI.class.getClassLoader(),
                new Class<?>[]{FleetMemberAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getVariant" -> variant;
                    case "getSpecId" -> specId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "memberProxy";
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** A member whose variant read throws: the spec id is all that is left to key on. */
    private static FleetMemberAPI throwingMember(String specId) {
        return (FleetMemberAPI) Proxy.newProxyInstance(
                FleetMemberAPI.class.getClassLoader(),
                new Class<?>[]{FleetMemberAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSpecId" -> specId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "throwingMemberProxy";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // ---- fixture ---------------------------------------------------------------------------------

    /** One location holding fleet proxies, with the sector scan counted. */
    private static final class World {
        private final AtomicInteger scans = new AtomicInteger();
        private final AtomicInteger fleetDataReads = new AtomicInteger();
        private final List<String> despawned = new ArrayList<>();
        private final List<CampaignFleetAPI> fleets = new ArrayList<>();
        private final List<String> alive = new ArrayList<>();
        private final LocationAPI location;
        /** When set, {@code despawn()} throws the way a hostile engine state would. */
        private boolean failDespawn;

        private World(String... fleetIds) {
            location = (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(),
                    new Class<?>[]{LocationAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> "loc-1";
                        case "getFleets" -> List.copyOf(fleets);
                        case "toString" -> "Location";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            for (String id : fleetIds) {
                alive.add(id);
                fleets.add(fleet(id));
            }
        }

        private CampaignFleetAPI fleet(String id) {
            return (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getName" -> "Fleet " + id;
                        case "isAlive" -> alive.contains(id);
                        case "getContainingLocation" -> containedIn(id);
                        case "despawn" -> {
                            if (failDespawn) {
                                throw new IllegalStateException("engine refused the despawn");
                            }
                            despawned.add(id);
                            removeQuietly(id);
                            yield null;
                        }
                        case "getFleetData" -> {
                            fleetDataReads.incrementAndGet();
                            yield null;
                        }
                        case "toString" -> "Fleet[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private LocationAPI containedIn(String id) {
            for (CampaignFleetAPI fleet : fleets) {
                if (id.equals(fleet.getId())) {
                    return location;
                }
            }
            return null;
        }

        /** Removes the fleet from the world without going through {@code despawn()}. */
        private void removeQuietly(String id) {
            alive.remove(id);
            fleets.removeIf(fleet -> id.equals(fleet.getId()));
        }

        private SectorAPI sector() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAllLocations" -> {
                            scans.incrementAndGet();
                            yield List.of(location);
                        }
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private CoopBattleResultReconciler.EngineFleets engineFleets() {
            SectorAPI sector = sector();
            return new CoopBattleResultReconciler.EngineFleets(() -> sector, () -> { }, id -> { });
        }
    }
}
