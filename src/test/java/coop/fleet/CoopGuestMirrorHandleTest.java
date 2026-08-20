package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The handle three per-frame host paths read instead of scanning the sector for the guest mirror.
 * What has to hold: a published handle is returned as-is, a handle whose fleet has left the world is
 * dropped rather than served, and the 0.5 Hz re-resolve heals both directions.
 */
class CoopGuestMirrorHandleTest {

    @AfterEach
    void clearHandle() {
        CoopGuestMirrorHandle.clear();
        CoopLocations.invalidate();
    }

    // ---- The published handle ---------------------------------------------------------------------

    @Test
    void aPublishedLiveMirrorIsServedWithoutAScan() {
        FakeFleet mirror = FakeFleet.mirror();
        FakeLocation askonia = new FakeLocation("askonia", mirror);
        CampaignFleetAPI published = askonia.proxyFor(mirror);

        CoopGuestMirrorHandle.publish(published);

        assertSame(published, CoopGuestMirrorHandle.current());
        assertEquals(0, askonia.fleetListReads, "current() must not walk any fleet list");
    }

    @Test
    void anEmptySlotIsNullRatherThanAScan() {
        assertNull(CoopGuestMirrorHandle.current());
    }

    @Test
    void aDespawnedMirrorIsDroppedInsteadOfServed() {
        // isAlive() false is how the engine reports a fleet that has been removed; serving it would
        // hand the threat watcher and the system driver a fleet that is not in the world any more.
        FakeFleet mirror = FakeFleet.mirror();
        FakeLocation askonia = new FakeLocation("askonia", mirror);
        CoopGuestMirrorHandle.publish(askonia.proxyFor(mirror));
        mirror.alive = false;

        assertNull(CoopGuestMirrorHandle.current());
        assertNull(CoopGuestMirrorHandle.current(), "the stale handle is dropped, not re-tested");
    }

    @Test
    void aMirrorWithNoContainingLocationIsDropped() {
        FakeFleet mirror = FakeFleet.mirror();
        FakeLocation askonia = new FakeLocation("askonia", mirror);
        CoopGuestMirrorHandle.publish(askonia.proxyFor(mirror));
        mirror.location = null;

        assertNull(CoopGuestMirrorHandle.current());
    }

    @Test
    void onlyTheFleetThatPublishedTheHandleCanClearIt() {
        // Every CoopFleetMirror dispose() calls clearIfSame, including the NPC mirrors' — which must
        // not be able to drop the player mirror's slot.
        FakeFleet mirror = FakeFleet.mirror();
        FakeLocation askonia = new FakeLocation("askonia", mirror);
        CampaignFleetAPI published = askonia.proxyFor(mirror);
        CoopGuestMirrorHandle.publish(published);

        CoopGuestMirrorHandle.clearIfSame(FakeFleet.mirror().proxy());
        assertSame(published, CoopGuestMirrorHandle.current());

        CoopGuestMirrorHandle.clearIfSame(published);
        assertNull(CoopGuestMirrorHandle.current());
    }

    // ---- The 0.5 Hz authoritative re-resolve -------------------------------------------------------

    @Test
    void reresolveFindsTheFleetTaggedAsThePlayerMirror() {
        FakeFleet npc = FakeFleet.plain();
        FakeFleet mirror = FakeFleet.mirror();
        FakeLocation askonia = new FakeLocation("askonia", npc, mirror);
        FakeSector sector = new FakeSector(askonia);

        CampaignFleetAPI found = CoopGuestMirrorHandle.reresolve(sector.proxy());

        assertSame(askonia.proxyFor(mirror), found);
        assertSame(found, CoopGuestMirrorHandle.current(), "the scan result becomes the handle");
    }

    @Test
    void reresolveFindsAMirrorParkedInHyperspace() {
        // getAllLocations() includes hyperspace (0.98a engine bytecode), which is why the walk needs
        // no separate hyperspace pass.
        FakeFleet mirror = FakeFleet.mirror();
        FakeLocation hyperspace = new FakeLocation("hyperspace", mirror);
        FakeSector sector = new FakeSector(hyperspace);

        assertSame(hyperspace.proxyFor(mirror), CoopGuestMirrorHandle.reresolve(sector.proxy()));
    }

    @Test
    void reresolveHealsAHandleThatPointsAtTheWrongFleet() {
        FakeFleet stale = FakeFleet.mirror();
        FakeLocation elsewhere = new FakeLocation("elsewhere", stale);
        CoopGuestMirrorHandle.publish(elsewhere.proxyFor(stale));

        FakeFleet real = FakeFleet.mirror();
        FakeLocation askonia = new FakeLocation("askonia", real);
        FakeSector sector = new FakeSector(askonia);

        assertSame(askonia.proxyFor(real), CoopGuestMirrorHandle.reresolve(sector.proxy()));
        assertSame(askonia.proxyFor(real), CoopGuestMirrorHandle.current());
    }

    @Test
    void reresolveWithNoMirrorClearsTheHandle() {
        FakeFleet stale = FakeFleet.mirror();
        FakeLocation elsewhere = new FakeLocation("elsewhere", stale);
        CoopGuestMirrorHandle.publish(elsewhere.proxyFor(stale));
        FakeSector empty = new FakeSector(new FakeLocation("corvus", FakeFleet.plain()));

        assertNull(CoopGuestMirrorHandle.reresolve(empty.proxy()));
        assertNull(CoopGuestMirrorHandle.current());
        assertNull(CoopGuestMirrorHandle.reresolve(null));
    }

    // ---- Fakes ------------------------------------------------------------------------------------

    private static final class FakeFleet {
        private final Map<String, Object> memory;
        private boolean alive = true;
        private FakeLocation location;

        private FakeFleet(Map<String, Object> memory) {
            this.memory = new LinkedHashMap<>(memory);
        }

        private static FakeFleet mirror() {
            return new FakeFleet(Map.of(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true));
        }

        private static FakeFleet plain() {
            return new FakeFleet(Map.of());
        }

        private CampaignFleetAPI proxy() {
            return (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeFleet" + memory;
                        case "hashCode" -> System.identityHashCode(this);
                        case "equals" -> proxy == args[0];
                        case "isAlive" -> alive;
                        case "getContainingLocation" -> location == null ? null : location.proxy();
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
        private final String id;
        private final List<FakeFleet> fleets = new ArrayList<>();
        private final Map<FakeFleet, CampaignFleetAPI> fleetProxies = new LinkedHashMap<>();
        private LocationAPI proxy;
        private int fleetListReads;

        private FakeLocation(String id, FakeFleet... fleets) {
            this.id = id;
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
                            case "toString" -> "FakeLocation:" + id;
                            case "hashCode" -> System.identityHashCode(this);
                            case "equals" -> p == args[0];
                            case "getId" -> id;
                            case "getName" -> id;
                            case "getFleets" -> {
                                fleetListReads++;
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

        private FakeSector(FakeLocation... locations) {
            this.locations = List.of(locations);
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
                                out.add(location.proxy());
                            }
                            yield out;
                        }
                        default -> null;
                    });
        }
    }
}
