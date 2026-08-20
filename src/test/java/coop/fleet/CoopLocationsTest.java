package coop.fleet;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The shared location walk and the id&rarr;location cache that replaced four copy-pasted helpers.
 * The measurements that matter here are call counts: {@code getAllLocations()} builds two fresh
 * ArrayLists and copies ~130 systems every time it is invoked, so "how many times did we call it"
 * <em>is</em> the cost.
 */
class CoopLocationsTest {

    @BeforeEach
    @AfterEach
    void resetCache() {
        CoopLocations.invalidate();
    }

    // ---- The walk ---------------------------------------------------------------------------------

    @Test
    void theWalkCallsGetAllLocationsExactlyOnce() {
        // The old copies called it twice: once to iterate and once for a !contains(hyperspace) guard.
        FakeSector sector = new FakeSector("corvus", "askonia", "hyperspace");

        List<String> visited = new ArrayList<>();
        CoopLocations.forEach(sector.proxy(), location -> visited.add(location.getId()));

        assertEquals(List.of("corvus", "askonia", "hyperspace"), visited);
        assertEquals(1, sector.allLocationsCalls);
    }

    @Test
    void theWalkSkipsNullEntriesAndTolerantlyHandlesNothingToWalk() {
        FakeSector withHole = new FakeSector("corvus", null, "askonia");

        List<String> visited = new ArrayList<>();
        CoopLocations.forEach(withHole.proxy(), location -> visited.add(location.getId()));
        assertEquals(List.of("corvus", "askonia"), visited);

        CoopLocations.forEach(null, location -> visited.add("nope"));
        CoopLocations.forEach(new FakeSector().proxy(), location -> visited.add("nope"));
        assertEquals(List.of("corvus", "askonia"), visited);
    }

    // ---- The id cache -----------------------------------------------------------------------------

    @Test
    void repeatedLookupsCostOneWalkNotOnePerLookup() {
        FakeSector sector = new FakeSector("corvus", "askonia", "hyperspace");
        SectorAPI proxy = sector.proxy();

        assertSame(sector.proxyFor("askonia"), CoopLocations.byId(proxy, "askonia"));
        assertSame(sector.proxyFor("corvus"), CoopLocations.byId(proxy, "corvus"));
        assertSame(sector.proxyFor("hyperspace"), CoopLocations.byId(proxy, "hyperspace"));

        assertEquals(1, sector.allLocationsCalls, "the map is built once and then hit");
        assertEquals(3, CoopLocations.cachedIdCount());
    }

    @Test
    void anIdThisClientDoesNotHaveIsNullAndIsNotRebuiltOnEveryAsk() {
        // A snapshot naming an unresolvable location is a real case (the mirror skips that tick), and
        // it arrives at 10 Hz — one rebuild to be sure, then the answer stands.
        FakeSector sector = new FakeSector("corvus");
        SectorAPI proxy = sector.proxy();

        assertNull(CoopLocations.byId(proxy, "not-a-place"));
        assertNull(CoopLocations.byId(proxy, "not-a-place"));
        assertNull(CoopLocations.byId(proxy, "not-a-place"));

        assertEquals(1, sector.allLocationsCalls);
    }

    @Test
    void aDifferentSectorRebuildsTheMapWithoutAnyHook() {
        // Loading another campaign hands out the same ids for different locations. Identity on the
        // sector is the invalidation that cannot be forgotten; CoopModPlugin.onGameLoad also calls
        // invalidate() explicitly.
        FakeSector first = new FakeSector("corvus");
        FakeSector second = new FakeSector("corvus");

        LocationAPI fromFirst = CoopLocations.byId(first.proxy(), "corvus");
        LocationAPI fromSecond = CoopLocations.byId(second.proxy(), "corvus");

        assertSame(first.proxyFor("corvus"), fromFirst);
        assertSame(second.proxyFor("corvus"), fromSecond);
    }

    @Test
    void invalidateForcesTheNextLookupToWalkAgain() {
        FakeSector sector = new FakeSector("corvus");
        SectorAPI proxy = sector.proxy();

        CoopLocations.byId(proxy, "corvus");
        CoopLocations.invalidate();
        assertEquals(0, CoopLocations.cachedIdCount());
        CoopLocations.byId(proxy, "corvus");

        assertEquals(2, sector.allLocationsCalls);
    }

    @Test
    void nullAndBlankLookupsAreNull() {
        FakeSector sector = new FakeSector("corvus");
        assertNull(CoopLocations.byId(sector.proxy(), null));
        assertNull(CoopLocations.byId(sector.proxy(), ""));
        assertNull(CoopLocations.byId(null, "corvus"));
        assertEquals(0, sector.allLocationsCalls);
    }

    // ---- Fakes ------------------------------------------------------------------------------------

    private static final class FakeSector {
        private final List<String> ids;
        private final List<LocationAPI> proxies = new ArrayList<>();
        private SectorAPI proxy;
        private int allLocationsCalls;

        private FakeSector(String... ids) {
            this.ids = Arrays.asList(ids);
            for (String id : this.ids) {
                proxies.add(id == null ? null : locationProxy(id));
            }
        }

        private LocationAPI proxyFor(String id) {
            return proxies.get(ids.indexOf(id));
        }

        private static LocationAPI locationProxy(String id) {
            return (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(),
                    new Class<?>[]{LocationAPI.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeLocation:" + id;
                        case "hashCode" -> id.hashCode();
                        case "equals" -> p == args[0];
                        case "getId" -> id;
                        case "getName" -> id;
                        default -> null;
                    });
        }

        private SectorAPI proxy() {
            if (proxy == null) {
                proxy = (SectorAPI) Proxy.newProxyInstance(
                        SectorAPI.class.getClassLoader(),
                        new Class<?>[]{SectorAPI.class},
                        (p, method, args) -> switch (method.getName()) {
                            case "toString" -> "FakeSector";
                            case "hashCode" -> System.identityHashCode(this);
                            case "equals" -> p == args[0];
                            case "getAllLocations" -> {
                                allLocationsCalls++;
                                yield new ArrayList<>(proxies);
                            }
                            default -> null;
                        });
            }
            return proxy;
        }
    }
}
