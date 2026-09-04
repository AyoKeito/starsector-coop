package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.CargoPodsEntityPlugin;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-glue coverage for replicated world entities: which entity a {@code CONSUME} may remove, and
 * what stops a mirrored copy from decaying on a timer of its own.
 *
 * <p>Both are cross-client identity problems. Engine ids are only stable across the two clients for
 * things that came out of worldgen; anything the engine mints at runtime takes its id from that
 * client's own counter, so the same id names different objects on the two sides.
 */
class CoopWorldEntityReplicationTest {

    @AfterEach
    void clearSector() {
        Global.setSector(null);
    }

    // ---- CONSUME target guard -------------------------------------------------------------------

    @Test
    void aConsumeRemovesTheSalvageableItNames() {
        FakeLocation location = new FakeLocation();
        FakeEntity wreck = location.add(new FakeEntity("1c3f").salvageable());
        Global.setSector(sector(location));

        guestReplicator().handle(CoopMessages.worldDelta("session-a", 1L, 10L,
                "1c3f", "CONSUME", true, "", "host-player"));

        assertEquals(List.of(wreck), location.removed);
    }

    @Test
    void aConsumeCannotRemoveAFleetThatHappensToOwnTheSameEngineId() {
        // Phase 9 owns fleet existence, and the consume watcher excludes fleets from tracking -- but
        // nothing excluded them from removal, and a runtime-minted id from one client resolves to
        // whatever owns it on the other.
        FakeLocation location = new FakeLocation();
        location.add(new FakeEntity("1c3f").fleet());
        Global.setSector(sector(location));

        guestReplicator().handle(CoopMessages.worldDelta("session-a", 1L, 10L,
                "1c3f", "CONSUME", true, "", "host-player"));

        assertTrue(location.removed.isEmpty(), "a fleet is never a salvage consume target");
    }

    @Test
    void aConsumeCannotRemoveAPlainWorldgenBodyThatHappensToOwnTheSameEngineId() {
        FakeLocation location = new FakeLocation();
        location.add(new FakeEntity("1c3f"));
        Global.setSector(sector(location));

        guestReplicator().handle(CoopMessages.worldDelta("session-a", 1L, 10L,
                "1c3f", "CONSUME", true, "", "host-player"));

        assertTrue(location.removed.isEmpty(),
                "only what this client's own consume watcher would key under exactly this id");
    }

    @Test
    void aConsumeByCoopIdStillRemovesTheMirroredEntity() {
        FakeLocation location = new FakeLocation();
        FakeEntity pod = location.add(new FakeEntity("7ab").custom().coopId("coop-pod-1"));
        Global.setSector(sector(location));

        guestReplicator().handle(CoopMessages.worldDelta("session-a", 1L, 10L,
                "coop-pod-1", "CONSUME", true, "", "host-player"));

        assertEquals(List.of(pod), location.removed);
    }

    @Test
    void aConsumeCannotRemoveAMirrorByItsLocalEngineId() {
        // A coop-replicated mirror keys on its coop id, never its engine id. An engine-id CONSUME
        // that lands on one is therefore a collision, not the entity the sender salvaged.
        FakeLocation location = new FakeLocation();
        location.add(new FakeEntity("1c3f").custom().coopId("coop-pod-1"));
        Global.setSector(sector(location));

        guestReplicator().handle(CoopMessages.worldDelta("session-a", 1L, 10L,
                "1c3f", "CONSUME", true, "", "host-player"));

        assertTrue(location.removed.isEmpty());
    }

    // ---- Mirror decay ---------------------------------------------------------------------------

    /**
     * {@code cargo_pods} declares {@code CargoPodsEntityPlugin} as its plugin class in
     * {@code custom_entities.json}, so the mirror gets the decay plugin whether the mod wants it or
     * not and runs its own {@code elapsed} from zero -- with {@code maxDays} stuck at its 1-day
     * default whenever the receiving player is not in that location. Whichever copy expires first is
     * reported as a {@code CONSUME}, which takes the other player's still-live pod with it.
     */
    @Test
    void aMirroredCargoPodNeverExpiresOnItsOwnTimer() {
        CargoPodsEntityPlugin pods = new CargoPodsEntityPlugin();
        assertFalse(pods.isNeverExpire(), "vanilla default: the mirror would decay on its own");

        guestReplicator().pinMirrorExpiry(entityWithPlugin(pods));

        assertTrue(pods.isNeverExpire(),
                "decay stays owned by the creating client, which reports the removal as a CONSUME");
    }

    @Test
    void pinningIsANoOpForAnEntityWithoutTheDecayPlugin() {
        FakeLocation location = new FakeLocation();
        FakeEntity plain = location.add(new FakeEntity("1c3f").custom());

        guestReplicator().pinMirrorExpiry(plain.proxy());
        // Nothing to assert beyond "it did not throw"; a non-pod mirror has no expiry to pin.
        assertTrue(location.removed.isEmpty());
    }

    // ---- Fakes ----------------------------------------------------------------------------------

    private static SectorEntityToken entityWithPlugin(Object plugin) {
        return (SectorEntityToken) Proxy.newProxyInstance(
                SectorEntityToken.class.getClassLoader(), new Class<?>[]{SectorEntityToken.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCustomPlugin" -> plugin;
                    case "toString" -> "EntityWithPlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** One entity, with just enough shape for the consume watcher's tracking decision. */
    private static final class FakeEntity {
        private final String id;
        private final Map<String, Object> memory = new HashMap<>();
        private boolean salvageTagged;
        private boolean custom;
        private boolean fleet;
        private LocationAPI containing;
        private SectorEntityToken cached;

        private FakeEntity(String id) {
            this.id = id;
        }

        private FakeEntity salvageable() {
            salvageTagged = true;
            custom = true;
            return this;
        }

        private FakeEntity custom() {
            custom = true;
            return this;
        }

        private FakeEntity fleet() {
            fleet = true;
            return this;
        }

        private FakeEntity coopId(String coopId) {
            memory.put(CoopWorldEntitySpawn.COOP_ENTITY_TAG, coopId);
            custom = true;
            return this;
        }

        private SectorEntityToken proxy() {
            if (cached != null) {
                return cached;
            }
            MemoryAPI memoryProxy = (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(), new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "contains" -> memory.containsKey(String.valueOf(args[0]));
                        case "get" -> memory.get(String.valueOf(args[0]));
                        case "toString" -> "FakeMemory";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            Class<?> face = fleet ? CampaignFleetAPI.class
                    : custom ? CustomCampaignEntityAPI.class : SectorEntityToken.class;
            cached = (SectorEntityToken) Proxy.newProxyInstance(
                    face.getClassLoader(), new Class<?>[]{face},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "hasTag" -> salvageTagged && "salvageable".equals(String.valueOf(args[0]));
                        case "getMemoryWithoutUpdate", "getMemory" -> memoryProxy;
                        case "getContainingLocation" -> containing;
                        case "toString" -> "FakeEntity[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            return cached;
        }
    }

    /** One location that answers by id and records what the applier removed. */
    private static final class FakeLocation {
        private final List<FakeEntity> entities = new ArrayList<>();
        private final List<FakeEntity> removed = new ArrayList<>();
        private LocationAPI cached;

        private FakeEntity add(FakeEntity entity) {
            entities.add(entity);
            entity.containing = proxy();
            return entity;
        }

        private FakeEntity byToken(Object token) {
            for (FakeEntity entity : entities) {
                if (entity.proxy() == token) {
                    return entity;
                }
            }
            return null;
        }

        private SectorEntityToken byId(String id) {
            for (FakeEntity entity : entities) {
                if (entity.id.equals(id)) {
                    return entity.proxy();
                }
            }
            return null;
        }

        private List<SectorEntityToken> all() {
            List<SectorEntityToken> tokens = new ArrayList<>();
            for (FakeEntity entity : entities) {
                tokens.add(entity.proxy());
            }
            return tokens;
        }

        private LocationAPI proxy() {
            if (cached != null) {
                return cached;
            }
            cached = (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(), new Class<?>[]{LocationAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> "loc-1";
                        case "getAllEntities" -> all();
                        case "removeEntity" -> {
                            FakeEntity entity = byToken(args[0]);
                            if (entity != null) {
                                removed.add(entity);
                            }
                            yield null;
                        }
                        case "toString" -> "FakeLocation";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            return cached;
        }
    }

    private static SectorAPI sector(FakeLocation location) {
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(), new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHyperspace" -> location.proxy();
                    case "getAllLocations" -> List.of(location.proxy());
                    case "getEntityById" -> location.byId(String.valueOf(args[0]));
                    case "toString" -> "FakeSector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static CoopCampaignReplicator guestReplicator() {
        return new CoopCampaignReplicator(
                new RecordingNetService(CoopConnectionRole.GUEST), TestSessions.activeGuestSession(), () -> 10L);
    }
}
