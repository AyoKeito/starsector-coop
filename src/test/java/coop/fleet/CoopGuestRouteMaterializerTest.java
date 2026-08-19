package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopGuestRouteMaterializerTest {

    // ---- Which systems get presence extension ----------------------------------------------------

    @Test
    void guestInAStarSystemExtendsPresence() {
        assertTrue(CoopGuestRouteMaterializer.canExtendPresenceTo(true, false));
    }

    @Test
    void guestInHyperspaceDoesNotExtendPresence() {
        // "Routes in hyperspace" is effectively every travelling route in the sector; materialising
        // them all would spawn hundreds of fleets at once.
        assertFalse(CoopGuestRouteMaterializer.canExtendPresenceTo(true, true));
    }

    @Test
    void noGuestMirrorMeansNoPresenceExtension() {
        assertFalse(CoopGuestRouteMaterializer.canExtendPresenceTo(false, false));
    }

    // ---- Per-route spawn preconditions ------------------------------------------------------------

    @Test
    void idleRouteWithASpawnerIsForceSpawned() {
        assertTrue(CoopGuestRouteMaterializer.shouldForceSpawn(false, true, 0f));
    }

    @Test
    void routeThatAlreadyHasAFleetIsNeverRespawned() {
        // The duplicate-spawn guard: once RouteData.activeFleet is set, neither we nor vanilla spawn.
        assertFalse(CoopGuestRouteMaterializer.shouldForceSpawn(true, true, 0f));
    }

    @Test
    void delayedRouteIsLeftAlone() {
        assertFalse(CoopGuestRouteMaterializer.shouldForceSpawn(false, true, 3.5f));
    }

    @Test
    void routeWithoutASpawnerIsSkipped() {
        assertFalse(CoopGuestRouteMaterializer.shouldForceSpawn(false, false, 0f));
    }

    // ---- Selection + per-pass cap -----------------------------------------------------------------

    @Test
    void selectionPicksOnlyEligibleRoutes() {
        List<CoopGuestRouteMaterializer.RouteView> routes = List.of(
                route(true, true, 0f),    // 0: already active
                route(false, true, 0f),   // 1: eligible
                route(false, true, 12f),  // 2: delayed
                route(false, false, 0f),  // 3: no spawner
                route(false, true, 0f));  // 4: eligible

        assertEquals(List.of(1, 4), CoopGuestRouteMaterializer.selectForcedSpawns(routes, 10));
    }

    @Test
    void selectionRespectsThePerPassCap() {
        List<CoopGuestRouteMaterializer.RouteView> routes = List.of(
                route(false, true, 0f), route(false, true, 0f),
                route(false, true, 0f), route(false, true, 0f));

        assertEquals(List.of(0, 1), CoopGuestRouteMaterializer.selectForcedSpawns(routes, 2));
    }

    @Test
    void selectionToleratesNullsAndDegenerateCaps() {
        List<CoopGuestRouteMaterializer.RouteView> routes = new ArrayList<>();
        routes.add(null);
        routes.add(route(false, true, 0f));

        assertEquals(List.of(1), CoopGuestRouteMaterializer.selectForcedSpawns(routes, 10));
        assertEquals(List.of(), CoopGuestRouteMaterializer.selectForcedSpawns(routes, 0));
        assertEquals(List.of(), CoopGuestRouteMaterializer.selectForcedSpawns(null, 10));
    }

    // ---- Finding the guest mirror -----------------------------------------------------------------

    @Test
    void findsTheFleetTaggedAsThePlayerMirror() {
        FakeFleet npc = FakeFleet.withMemory(Map.of());
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeLocation askonia = new FakeLocation(npc, mirror);
        FakeSector sector = new FakeSector(List.of(askonia), null);

        assertSame(askonia.proxyFor(mirror), CoopGuestRouteMaterializer.findGuestMirror(sector.proxy()));
    }

    @Test
    void findsAMirrorParkedInHyperspaceOutsideAllLocations() {
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeLocation hyperspace = new FakeLocation(mirror);
        FakeSector sector = new FakeSector(List.of(), hyperspace);

        assertSame(hyperspace.proxyFor(mirror), CoopGuestRouteMaterializer.findGuestMirror(sector.proxy()));
    }

    @Test
    void noMirrorInTheSectorYieldsNull() {
        FakeLocation corvus = new FakeLocation(FakeFleet.withMemory(Map.of(
                CoopNpcFleetReplicator.NPC_MIRROR_TAG, "abc")));
        FakeSector sector = new FakeSector(List.of(corvus), null);

        assertNull(CoopGuestRouteMaterializer.findGuestMirror(sector.proxy()));
        assertNull(CoopGuestRouteMaterializer.findGuestMirror(null));
    }

    // ---- Tick guards --------------------------------------------------------------------------------

    @Test
    void tickOnANullSectorIsSafe() {
        CoopGuestRouteMaterializer materializer = new CoopGuestRouteMaterializer();
        materializer.tick(null, 0L);
        assertEquals("", materializer.armedSystemId());
    }

    @Test
    void tickClearsTheArmedSystemWhenTheMirrorIsGone() {
        // No mirror anywhere: the pass must fall through to "idle" without ever touching RouteManager
        // (which would need a live Global.getSector()).
        FakeSector sector = new FakeSector(List.of(new FakeLocation(FakeFleet.withMemory(Map.of()))), null);
        CoopGuestRouteMaterializer materializer = new CoopGuestRouteMaterializer();

        materializer.tick(sector.proxy(), 0L);

        assertEquals("", materializer.armedSystemId());
    }

    // ---- Fakes --------------------------------------------------------------------------------------

    private static CoopGuestRouteMaterializer.RouteView route(boolean active, boolean spawner, float delay) {
        return new CoopGuestRouteMaterializer.RouteView() {
            @Override
            public boolean hasActiveFleet() {
                return active;
            }

            @Override
            public boolean hasSpawner() {
                return spawner;
            }

            @Override
            public float delay() {
                return delay;
            }
        };
    }

    private static final class FakeFleet {
        private final Map<String, Object> memory;

        private FakeFleet(Map<String, Object> memory) {
            this.memory = new LinkedHashMap<>(memory);
        }

        private static FakeFleet withMemory(Map<String, Object> memory) {
            return new FakeFleet(memory);
        }

        private CampaignFleetAPI proxy() {
            return (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeFleet" + memory;
                        case "hashCode" -> System.identityHashCode(this);
                        case "equals" -> proxy == args[0];
                        case "getMemoryWithoutUpdate" -> memoryProxy();
                        default -> null;
                    });
        }

        private MemoryAPI memoryProxy() {
            return (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(),
                    new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeMemory" + memory;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getBoolean" -> Boolean.TRUE.equals(memory.get(String.valueOf(args[0])));
                        case "contains" -> memory.containsKey(String.valueOf(args[0]));
                        default -> null;
                    });
        }
    }

    private static final class FakeLocation {
        private final List<FakeFleet> fleets = new ArrayList<>();
        private final Map<FakeFleet, CampaignFleetAPI> fleetProxies = new LinkedHashMap<>();

        private FakeLocation(FakeFleet... fleets) {
            this.fleets.addAll(List.of(fleets));
        }

        /** Stable proxy per fleet, so identity assertions across calls are honest. */
        private CampaignFleetAPI proxyFor(FakeFleet fleet) {
            return fleetProxies.computeIfAbsent(fleet, FakeFleet::proxy);
        }

        private LocationAPI proxy() {
            return (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(),
                    new Class<?>[]{LocationAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeLocation";
                        case "hashCode" -> System.identityHashCode(this);
                        case "equals" -> proxy == args[0];
                        case "getFleets" -> {
                            List<CampaignFleetAPI> out = new ArrayList<>();
                            for (FakeFleet fleet : fleets) {
                                out.add(proxyFor(fleet));
                            }
                            yield out;
                        }
                        default -> null;
                    });
        }
    }

    private static final class FakeSector {
        private final List<FakeLocation> locations;
        private final FakeLocation hyperspace;
        private final Map<FakeLocation, LocationAPI> proxyCache = new LinkedHashMap<>();

        private FakeSector(List<FakeLocation> locations, FakeLocation hyperspace) {
            this.locations = locations;
            this.hyperspace = hyperspace;
        }

        private LocationAPI proxyFor(FakeLocation location) {
            return proxyCache.computeIfAbsent(location, FakeLocation::proxy);
        }

        private SectorAPI proxy() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeSector";
                        case "hashCode" -> System.identityHashCode(this);
                        case "equals" -> proxy == args[0];
                        case "isPaused" -> Boolean.FALSE;
                        case "getAllLocations" -> {
                            List<LocationAPI> out = new ArrayList<>();
                            for (FakeLocation location : locations) {
                                out.add(proxyFor(location));
                            }
                            yield out;
                        }
                        case "getHyperspace" -> hyperspace == null ? null : proxyFor(hyperspace);
                        default -> null;
                    });
        }
    }
}
