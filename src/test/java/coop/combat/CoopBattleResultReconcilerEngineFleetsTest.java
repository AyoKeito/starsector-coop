package coop.combat;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.fleet.CoopFleetSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        fleets.despawn("fleet-a");

        assertEquals(1, world.scans.get(), "the resolved fleet must carry over to the despawn");
        assertEquals(List.of("fleet-a"), world.despawned);
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

    // ---- fixture ---------------------------------------------------------------------------------

    /** One location holding fleet proxies, with the sector scan counted. */
    private static final class World {
        private final AtomicInteger scans = new AtomicInteger();
        private final AtomicInteger fleetDataReads = new AtomicInteger();
        private final List<String> despawned = new ArrayList<>();
        private final List<CampaignFleetAPI> fleets = new ArrayList<>();
        private final List<String> alive = new ArrayList<>();
        private final LocationAPI location;

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

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}
