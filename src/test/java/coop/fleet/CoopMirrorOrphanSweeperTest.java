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
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMirrorOrphanSweeperTest {

    // ---- Pure decision function ----------------------------------------------------------------

    @Test
    void playerMirrorTagMarksAnOrphan() {
        assertTrue(CoopMirrorOrphanSweeper.isCoopMirrorMemory(true, false));
    }

    @Test
    void npcMirrorTagMarksAnOrphan() {
        assertTrue(CoopMirrorOrphanSweeper.isCoopMirrorMemory(false, true));
    }

    @Test
    void untaggedFleetIsNotAnOrphan() {
        assertFalse(CoopMirrorOrphanSweeper.isCoopMirrorMemory(false, false));
    }

    // ---- Sweep over fake locations --------------------------------------------------------------

    @Test
    void sweepRemovesOnlyTaggedMirrorFleets() {
        FakeFleet playerMirror = FakeFleet.withMemory(Map.of(CoopMirrorOrphanSweeper.PLAYER_MIRROR_TAG, true));
        FakeFleet npcMirror = FakeFleet.withMemory(Map.of(CoopMirrorOrphanSweeper.NPC_MIRROR_TAG, "abc123"));
        FakeFleet vanillaPatrol = FakeFleet.withMemory(Map.of());
        FakeFleet playerFleet = FakeFleet.withMemory(Map.of());

        FakeLocation corvus = new FakeLocation(playerMirror, npcMirror, vanillaPatrol, playerFleet);
        FakeSector sector = new FakeSector(List.of(corvus), null);

        assertEquals(2, CoopMirrorOrphanSweeper.sweep(sector.proxy()));
        assertEquals(List.of(vanillaPatrol, playerFleet), corvus.fleets,
                "untagged fleets, including the player's own, must survive the sweep");
    }

    @Test
    void sweepCoversHyperspaceWhenItIsNotInAllLocations() {
        FakeFleet strandedMirror = FakeFleet.withMemory(Map.of(CoopMirrorOrphanSweeper.PLAYER_MIRROR_TAG, true));
        FakeLocation hyperspace = new FakeLocation(strandedMirror);
        FakeSector sector = new FakeSector(List.of(), hyperspace);

        assertEquals(1, CoopMirrorOrphanSweeper.sweep(sector.proxy()));
        assertTrue(hyperspace.fleets.isEmpty());
    }

    @Test
    void sweepDoesNotDoubleVisitHyperspaceListedInAllLocations() {
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopMirrorOrphanSweeper.NPC_MIRROR_TAG, "x"));
        FakeLocation hyperspace = new FakeLocation(mirror);
        FakeSector sector = new FakeSector(List.of(hyperspace), hyperspace);

        assertEquals(1, CoopMirrorOrphanSweeper.sweep(sector.proxy()),
                "a location present in both lists must be swept once, not twice");
    }

    @Test
    void sweepOfACleanSectorRemovesNothing() {
        FakeLocation corvus = new FakeLocation(FakeFleet.withMemory(Map.of()));
        FakeSector sector = new FakeSector(List.of(corvus), null);

        assertEquals(0, CoopMirrorOrphanSweeper.sweep(sector.proxy()));
    }

    @Test
    void nullSectorIsSafe() {
        assertEquals(0, CoopMirrorOrphanSweeper.sweep(null));
    }

    // ---- Fakes -----------------------------------------------------------------------------------

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
        private final Map<CampaignFleetAPI, FakeFleet> proxies = new LinkedHashMap<>();

        private FakeLocation(FakeFleet... fleets) {
            this.fleets.addAll(List.of(fleets));
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
                                CampaignFleetAPI fleetProxy = fleet.proxy();
                                proxies.put(fleetProxy, fleet);
                                out.add(fleetProxy);
                            }
                            yield out;
                        }
                        case "removeEntity" -> {
                            fleets.remove(proxies.get(args[0]));
                            yield null;
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

        /** Stable proxy per location, so identity-based de-duplication is exercised honestly. */
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
