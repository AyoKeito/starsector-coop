package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.presence.CoopPresenceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CoopGuestPresenceTest {

    @AfterEach
    void clearRegistry() {
        CoopPresenceRegistry.clear();
        CoopGuestMirrorHandle.clear();
        CoopGuestPresence.frameBoundary();
        CoopGuestPresence.frameBoundary();
    }

    // ---- The registry slot ------------------------------------------------------------------------

    @Test
    void registryDefaultsToNullSoForksStayVanilla() {
        assertNull(CoopPresenceRegistry.get());
    }

    /**
     * The guard every presence fork calls. With an empty slot it must answer null without touching the
     * game at all -- that is what makes "no session -> exactly vanilla" hold in a solo game, where the
     * forked classes are loaded but no presence is ever registered.
     */
    @Test
    void forkAccessorIsNullAndInertWhenNothingIsRegistered() {
        assertNull(CoopPresenceRegistry.getForFork("test"));
    }

    @Test
    void pinnedVersionMatchesTheBuildTheForksWereTakenFrom() {
        assertEquals("0.98a-RC8", CoopPresenceRegistry.PINNED_VERSION);
    }

    @Test
    void registrySetAndClearRoundTrip() {
        SectorEntityToken entity = FakeFleet.withMemory(Map.of()).proxy();
        CoopPresenceRegistry.set(entity);
        assertSame(entity, CoopPresenceRegistry.get());
        CoopPresenceRegistry.clear();
        assertNull(CoopPresenceRegistry.get());
    }

    // ---- Registration lifecycle ---------------------------------------------------------------------

    /**
     * The pass is the mod's one authoritative mirror scan: what it finds becomes the handle every
     * per-frame consumer reads (see {@link CoopGuestMirrorHandle}), which is what bounds how long a
     * stale handle can survive to one interval.
     */
    @Test
    void tickRepublishesTheMirrorHandle() {
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeLocation askonia = new FakeLocation(mirror);
        FakeSector sector = new FakeSector(List.of(askonia), null);
        CoopGuestMirrorHandle.publish(null);

        new CoopGuestPresence().tick(sector.proxy(), 0L);

        assertSame(askonia.proxyFor(mirror), CoopGuestMirrorHandle.current());
    }

    @Test
    void tickRegistersTheMirrorAsThePresenceEntity() {
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeLocation askonia = new FakeLocation(mirror);
        FakeSector sector = new FakeSector(List.of(askonia), null);
        CoopGuestPresence presence = new CoopGuestPresence();

        presence.tick(sector.proxy(), 0L);

        assertSame(askonia.proxyFor(mirror), CoopPresenceRegistry.get());
        assertEquals("fake-location", presence.armedSystemId());
    }

    @Test
    void aVanishedMirrorReleasesThePresenceSlot() {
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeSector withMirror = new FakeSector(List.of(new FakeLocation(mirror)), null);
        FakeSector without = new FakeSector(List.of(new FakeLocation(FakeFleet.withMemory(Map.of()))), null);
        CoopGuestPresence presence = new CoopGuestPresence();

        presence.tick(withMirror.proxy(), 0L);
        presence.tick(without.proxy(), CoopGuestPresence.INTERVAL_MILLIS);

        assertNull(CoopPresenceRegistry.get());
        assertEquals("", presence.armedSystemId());
    }

    @Test
    void aFrameWithoutATickReleasesThePresenceSlot() {
        // Session end: the pump stops calling tick(), and nothing else would notice. The always-on
        // frame hook is what keeps a dead mirror out of the forked RouteManager.
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeSector sector = new FakeSector(List.of(new FakeLocation(mirror)), null);
        CoopGuestPresence presence = new CoopGuestPresence();

        presence.tick(sector.proxy(), 0L);
        CoopGuestPresence.frameBoundary();      // tick ran this frame: keep it
        assertNotNull(CoopPresenceRegistry.get());
        CoopGuestPresence.frameBoundary();      // no tick since: release

        assertNull(CoopPresenceRegistry.get());
    }

    @Test
    void aReleasedSlotIsRepublishedByTheNextTickInsteadOfTwoSecondsLater() {
        // One pump frame that does not tick - a caught exception earlier in the frame, a tick-ordering
        // skip - released the slot, and the 2 s pass interval then left it null for ~120 frames. With
        // no presence entity the forked RouteManager measures distance to the host alone and despawns
        // every route fleet around a guest parked far from them.
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeSector sector = new FakeSector(List.of(new FakeLocation(mirror)), null);
        CoopGuestPresence presence = new CoopGuestPresence();

        presence.tick(sector.proxy(), 0L);
        CoopGuestPresence.frameBoundary();      // ticked
        CoopGuestPresence.frameBoundary();      // missed frame: released
        assertNull(CoopPresenceRegistry.get());

        presence.tick(sector.proxy(), 16L);     // the very next frame, far inside the pass interval

        assertNotNull(CoopPresenceRegistry.get());
    }

    @Test
    void resetReleasesThePresenceSlot() {
        FakeFleet mirror = FakeFleet.withMemory(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        FakeSector sector = new FakeSector(List.of(new FakeLocation(mirror)), null);
        CoopGuestPresence presence = new CoopGuestPresence();

        presence.tick(sector.proxy(), 0L);
        presence.reset();

        assertNull(CoopPresenceRegistry.get());
        assertEquals("", presence.armedSystemId());
    }

    @Test
    void tickOnANullSectorIsSafe() {
        CoopGuestPresence presence = new CoopGuestPresence();
        presence.tick(null, 0L);
        assertEquals("", presence.armedSystemId());
        assertNull(CoopPresenceRegistry.get());
    }

    // ---- Fakes --------------------------------------------------------------------------------------

    private static final class FakeFleet {
        private final Map<String, Object> memory;
        private FakeLocation location;

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
                        case "isAlive" -> Boolean.TRUE;
                        case "getContainingLocation" -> location == null ? null : location.proxy();
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
        private LocationAPI proxy;

        private FakeLocation(FakeFleet... fleets) {
            this.fleets.addAll(List.of(fleets));
            for (FakeFleet fleet : fleets) {
                fleet.location = this;
            }
        }

        /** Stable proxy per fleet, so identity assertions across calls are honest. */
        private CampaignFleetAPI proxyFor(FakeFleet fleet) {
            return fleetProxies.computeIfAbsent(fleet, FakeFleet::proxy);
        }

        private LocationAPI proxy() {
            if (proxy == null) {
                proxy = (LocationAPI) Proxy.newProxyInstance(
                        LocationAPI.class.getClassLoader(),
                        new Class<?>[]{LocationAPI.class},
                        (p, method, args) -> switch (method.getName()) {
                            case "toString" -> "FakeLocation";
                            case "hashCode" -> System.identityHashCode(this);
                            case "equals" -> p == args[0];
                            case "getId" -> "fake-location";
                            case "getName" -> "Fake Location";
                            case "isHyperspace" -> Boolean.FALSE;
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
            return proxy;
        }
    }

    private static final class FakeSector {
        private final List<FakeLocation> locations;
        private final FakeLocation hyperspace;

        private FakeSector(List<FakeLocation> locations, FakeLocation hyperspace) {
            this.locations = locations;
            this.hyperspace = hyperspace;
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
                        // Hyperspace is in the engine's own getAllLocations() list (0.98a bytecode),
                        // so the fake puts it there too — the walk has no separate hyperspace pass.
                        case "getAllLocations" -> {
                            List<LocationAPI> out = new ArrayList<>();
                            for (FakeLocation location : locations) {
                                out.add(location.proxy());
                            }
                            if (hyperspace != null && !out.contains(hyperspace.proxy())) {
                                out.add(hyperspace.proxy());
                            }
                            yield out;
                        }
                        case "getHyperspace" -> hyperspace == null ? null : hyperspace.proxy();
                        default -> null;
                    });
        }
    }
}
