package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replication of the two halves of vanilla's stable-location round trip: building a makeshift comm
 * relay / nav buoy / sensor array on a stable location, and disassembling one back into a stable
 * location.
 *
 * <p>Both halves create their entity with {@code addCustomEntity(null, ...)}, so the engine mints an
 * id per client and nothing about them can be matched across the session by engine id. Before this
 * the consume watcher saw only the disappearance — the peer deleted its stable location and never got
 * the relay, losing a +1 stability condition and the removal of the "no comm relay" penalty on every
 * same-faction market in that system.
 */
class CoopConstructionReplicationTest {

    @AfterEach
    void clearSector() {
        Global.setSector(null);
    }

    // ---- Pure shape decision ---------------------------------------------------------------------

    @Test
    void onlyMakeshiftObjectivesAndStableLocationsAreConstructions() {
        assertTrue(CoopCampaignReplicator.isConstructionShape(true, true, false), "built relay");
        assertTrue(CoopCampaignReplicator.isConstructionShape(false, false, true), "stable location");
        assertFalse(CoopCampaignReplicator.isConstructionShape(true, false, false),
                "a gen-time comm relay exists identically on both clients already");
        assertFalse(CoopCampaignReplicator.isConstructionShape(false, false, false), "a cargo pod");
    }

    // ---- Capture ---------------------------------------------------------------------------------

    @Test
    void aBuiltObjectiveIsReportedOnceWithItsSpecFactionOrbitAndConsumedStableLocation() {
        Fixture f = new Fixture(CoopConnectionRole.GUEST);
        FakeEntity stable = f.location.add(new FakeEntity("stable-1")
                .custom().tag(Tags.STABLE_LOCATION));
        f.tick(); // seed the baseline with the stable location present

        FakeEntity built = f.location.add(new FakeEntity("runtime-7").custom()
                .tag(Tags.OBJECTIVE).tag(Tags.MAKESHIFT)
                .type("comm_relay_makeshift").faction("player")
                .orbit(f.focus, 1.5f, 200f, 60f)
                .memoryValue("$originalStableLocation", stable.proxy()));
        f.location.remove(stable);
        f.tick();

        CoopWorldEntitySpawn spawn = f.onlySpawn();
        assertEquals("guest-player:runtime-7", spawn.coopEntityId());
        assertEquals("comm_relay_makeshift", spawn.entityType());
        assertEquals("player", spawn.factionId());
        assertEquals("stable-1", spawn.consumedEntityId());
        assertEquals("planet-1", spawn.orbit().focusId());
        assertEquals(200f, spawn.orbit().radius(), 0.01f);
        assertEquals(60f, spawn.orbit().period(), 0.01f);
        assertEquals("guest-player:runtime-7",
                built.memory.get(CoopWorldEntitySpawn.COOP_ENTITY_TAG),
                "the originator tags its own copy so the echo is inert");
    }

    /** The watcher keys the tagged entity under its coop id, so no later pass re-reports it. */
    @Test
    void aBuiltObjectiveIsNotReportedTwiceAndProducesNoPhantomConsume() {
        Fixture f = new Fixture(CoopConnectionRole.GUEST);
        f.tick();
        f.location.add(new FakeEntity("runtime-7").custom()
                .tag(Tags.OBJECTIVE).tag(Tags.MAKESHIFT).type("comm_relay_makeshift"));
        f.tick();
        assertEquals(1, f.service.sent.size());

        f.tick();
        f.tick();

        assertEquals(1, f.service.sent.size(),
                "the coop tag changed the entity's consume key; the old key must not read as gone");
    }

    /** The peer's applied copy is already coop-tagged, which is what stops it bouncing straight back. */
    @Test
    void aPeersMaterializedConstructionIsNeverReportedBack() {
        Fixture f = new Fixture(CoopConnectionRole.HOST);
        f.tick();
        f.location.add(new FakeEntity("runtime-9").custom()
                .tag(Tags.OBJECTIVE).tag(Tags.MAKESHIFT).type("nav_buoy_makeshift")
                .memoryValue(CoopWorldEntitySpawn.COOP_ENTITY_TAG, "guest-player:runtime-7"));
        f.tick();

        assertTrue(f.spawns().isEmpty());
    }

    /** Disassembly is the same round trip backwards: vanilla puts a fresh stable location back. */
    @Test
    void aDisassembledObjectivePutsAReplicatedStableLocationBack() {
        Fixture f = new Fixture(CoopConnectionRole.GUEST);
        FakeEntity built = f.location.add(new FakeEntity("runtime-7").custom()
                .tag(Tags.OBJECTIVE).tag(Tags.MAKESHIFT).type("comm_relay_makeshift")
                .memoryValue(CoopWorldEntitySpawn.COOP_ENTITY_TAG, "guest-player:runtime-7"));
        f.tick();

        f.location.remove(built);
        f.location.add(new FakeEntity("runtime-8").custom().tag(Tags.STABLE_LOCATION)
                .type(Entities.STABLE_LOCATION).orbit(f.focus, 1.5f, 200f, 60f));
        f.tick();

        CoopWorldEntitySpawn spawn = f.onlySpawn();
        assertEquals(Entities.STABLE_LOCATION, spawn.entityType());
        assertEquals("", spawn.consumedEntityId(), "nothing was consumed; the CONSUME covers that");
        assertEquals(List.of("guest-player:runtime-7"), f.consumedEntityIds(),
                "the objective's removal still reports under its coop id");
    }

    // ---- Apply -----------------------------------------------------------------------------------

    @Test
    void applyingAConstructionCreatesItInOrbitAndRemovesTheStableLocation() {
        Fixture f = new Fixture(CoopConnectionRole.HOST);
        FakeEntity stable = f.location.add(new FakeEntity("stable-1")
                .custom().tag(Tags.STABLE_LOCATION));

        f.replicator.handle(spawnMessage(new CoopWorldEntitySpawn(
                "guest-player:runtime-7", "comm_relay_makeshift", "loc-1", 40f, 50f, 0f, 0f,
                Map.of(), "player", "stable-1",
                new CoopWorldEntitySpawn.Orbit("planet-1", 1.5f, 200f, 60f))));

        FakeEntity created = f.location.onlyCreated();
        assertEquals("comm_relay_makeshift", created.createdType);
        assertEquals("player", created.createdFaction);
        assertEquals("guest-player:runtime-7",
                created.memory.get(CoopWorldEntitySpawn.COOP_ENTITY_TAG));
        assertEquals("planet-1|1.50|200.00|60.00", created.orbitSet);
        assertEquals(List.of(stable), f.location.removed, "the stable location it replaced is gone");
        assertNull(created.sensorProfile, "spec values, not the cargo-pod tweaks");
    }

    @Test
    void applyingTheSameConstructionTwiceCreatesOneEntity() {
        Fixture f = new Fixture(CoopConnectionRole.HOST);
        CoopWorldEntitySpawn spawn = new CoopWorldEntitySpawn(
                "guest-player:runtime-7", "comm_relay_makeshift", "loc-1", 40f, 50f, 0f, 0f,
                Map.of(), "player", "", new CoopWorldEntitySpawn.Orbit("planet-1", 1.5f, 200f, 60f));

        f.replicator.handle(spawnMessage(spawn));
        f.replicator.handle(spawnMessage(spawn));

        assertEquals(1, f.location.created.size());
    }

    /** A construction whose orbit focus does not resolve still materializes, at its fixed position. */
    @Test
    void anUnresolvableOrbitFocusFallsBackToTheFixedPosition() {
        Fixture f = new Fixture(CoopConnectionRole.HOST);

        f.replicator.handle(spawnMessage(new CoopWorldEntitySpawn(
                "guest-player:runtime-7", "nav_buoy_makeshift", "loc-1", 40f, 50f, 0f, 0f,
                Map.of(), "player", "",
                new CoopWorldEntitySpawn.Orbit("planet-does-not-exist", 1.5f, 200f, 60f))));

        FakeEntity created = f.location.onlyCreated();
        assertNull(created.orbitSet);
        assertEquals(40f, created.position.x, 0.01f);
        assertEquals(50f, created.position.y, 0.01f);
    }

    /** A cargo pod keeps the tweaks Misc.addCargoPods applies; a construction must not get them. */
    @Test
    void aCargoPodStillGetsItsPodOnlyTweaks() {
        Fixture f = new Fixture(CoopConnectionRole.HOST);

        f.replicator.handle(spawnMessage(new CoopWorldEntitySpawn(
                "guest-player:pod-1", Entities.CARGO_PODS, "loc-1", 40f, 50f, 1f, 2f, Map.of())));

        FakeEntity created = f.location.onlyCreated();
        assertEquals(1f, created.sensorProfile, 0.01f);
        assertEquals("neutral", created.createdFaction);
    }

    // ---- Ownership keying ------------------------------------------------------------------------

    /**
     * A built objective's engine id is minted per client, so an ownership flip reported under it
     * could never resolve on the peer. Keying on the coop id is what makes a capture of a
     * player-built relay replicate at all.
     */
    @Test
    void anOwnershipFlipOnABuiltObjectiveIsReportedUnderItsCoopId() {
        Fixture f = new Fixture(CoopConnectionRole.HOST);
        FakeEntity relay = f.location.add(new FakeEntity("runtime-7").custom()
                .tag(Tags.OBJECTIVE).tag(Tags.MAKESHIFT).type("comm_relay_makeshift")
                .faction("player")
                .memoryValue(CoopWorldEntitySpawn.COOP_ENTITY_TAG, "guest-player:runtime-7"));
        f.tick();

        relay.factionId = "pirates";
        f.tick();

        List<CoopMessages.Message> flips = f.ofKind("OBJECTIVE_OWNERSHIP");
        assertEquals(1, flips.size());
        assertEquals("guest-player:runtime-7",
                CoopMessages.requiredPayloadString(flips.get(0), "entityId"));
    }

    // ---- Fixture ---------------------------------------------------------------------------------

    private static CoopMessages.Message spawnMessage(CoopWorldEntitySpawn spawn) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, spawn.coopEntityId(), "SPAWN", false,
                spawn.encode(), "guest-player");
    }

    private static final class Fixture {
        private final FakeLocation location = new FakeLocation();
        private final RecordingNetService service;
        private final CoopCampaignReplicator replicator;
        private final MutableClock clock = new MutableClock(1_000_000L);
        private final SectorEntityToken focus;

        private Fixture(CoopConnectionRole role) {
            focus = location.add(new FakeEntity("planet-1")).proxy();
            Global.setSector(location.sector());
            service = new RecordingNetService(role);
            replicator = new CoopCampaignReplicator(service,
                    role == CoopConnectionRole.HOST ? activeHostSession() : activeGuestSession(),
                    clock);
        }

        private void tick() {
            clock.advance(Math.max(CoopCampaignReplicator.SALVAGE_SCAN_INTERVAL_MILLIS,
                    CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS));
            replicator.tickWorldDeltas();
        }

        private List<CoopMessages.Message> ofKind(String kind) {
            List<CoopMessages.Message> out = new ArrayList<>();
            for (CoopMessages.Message message : service.sent) {
                if (kind.equals(CoopMessages.requiredPayloadString(message, "kind"))) {
                    out.add(message);
                }
            }
            return out;
        }

        private List<CoopWorldEntitySpawn> spawns() {
            List<CoopWorldEntitySpawn> out = new ArrayList<>();
            for (CoopMessages.Message message : ofKind("SPAWN")) {
                out.add(CoopWorldEntitySpawn.decode(
                        CoopMessages.requiredPayloadString(message, "newStateJson")));
            }
            return out;
        }

        private CoopWorldEntitySpawn onlySpawn() {
            List<CoopWorldEntitySpawn> all = spawns();
            assertEquals(1, all.size(), "exactly one SPAWN");
            return all.get(0);
        }

        private List<String> consumedEntityIds() {
            List<String> out = new ArrayList<>();
            for (CoopMessages.Message message : ofKind("CONSUME")) {
                out.add(CoopMessages.requiredPayloadString(message, "entityId"));
            }
            return out;
        }
    }

    /** One in-memory {@code MemoryAPI}. */
    private static final class FakeMemory {
        private final Map<String, Object> values = new HashMap<>();

        Object get(String key) {
            return values.get(key);
        }

        MemoryAPI proxy() {
            return (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(), new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            values.put((String) args[0], args[1]);
                            yield null;
                        }
                        case "unset" -> {
                            values.remove((String) args[0]);
                            yield null;
                        }
                        case "get" -> values.get((String) args[0]);
                        case "contains" -> values.containsKey((String) args[0]);
                        case "getBoolean" -> Boolean.TRUE.equals(values.get((String) args[0]));
                        case "toString" -> "Memory" + values;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static final class FakeEntity {
        private final String id;
        private final FakeMemory memory = new FakeMemory();
        private final List<String> tags = new ArrayList<>();
        private final org.lwjgl.util.vector.Vector2f position =
                new org.lwjgl.util.vector.Vector2f();
        private final org.lwjgl.util.vector.Vector2f velocity =
                new org.lwjgl.util.vector.Vector2f();
        private String factionId;
        private String customType = "";
        private SectorEntityToken orbitFocus;
        private float orbitAngle;
        private float orbitRadius;
        private float orbitPeriod;
        private boolean custom;
        private String createdType;
        private String createdFaction;
        private String orbitSet;
        private Float sensorProfile;
        private LocationAPI containing;
        private SectorEntityToken cached;

        private FakeEntity(String id) {
            this.id = id;
        }

        private FakeEntity custom() {
            custom = true;
            return this;
        }

        private FakeEntity tag(String tag) {
            tags.add(tag);
            return this;
        }

        private FakeEntity type(String customType) {
            this.customType = customType;
            return this;
        }

        private FakeEntity faction(String factionId) {
            this.factionId = factionId;
            return this;
        }

        private FakeEntity orbit(SectorEntityToken focus, float angle, float radius, float period) {
            orbitFocus = focus;
            orbitAngle = angle;
            orbitRadius = radius;
            orbitPeriod = period;
            return this;
        }

        private FakeEntity memoryValue(String key, Object value) {
            memory.values.put(key, value);
            return this;
        }

        private SectorEntityToken proxy() {
            if (cached != null) {
                return cached;
            }
            MemoryAPI memoryProxy = memory.proxy();
            Class<?> face = custom ? CustomCampaignEntityAPI.class : SectorEntityToken.class;
            cached = (SectorEntityToken) Proxy.newProxyInstance(
                    face.getClassLoader(), new Class<?>[]{face},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "hasTag" -> tags.contains(String.valueOf(args[0]));
                        case "getCustomEntityType" -> customType;
                        case "getFaction" -> factionId == null ? null : factionProxy(factionId);
                        case "setFaction" -> {
                            factionId = (String) args[0];
                            yield null;
                        }
                        case "getMemoryWithoutUpdate", "getMemory" -> memoryProxy;
                        case "getContainingLocation" -> containing;
                        case "getLocation" -> position;
                        case "getVelocity" -> velocity;
                        case "getOrbitFocus" -> orbitFocus;
                        case "getCircularOrbitAngle" -> orbitAngle;
                        case "getCircularOrbitRadius" -> orbitRadius;
                        case "getCircularOrbitPeriod" -> orbitPeriod;
                        case "setCircularOrbit" -> {
                            orbitSet = String.format(java.util.Locale.ROOT, "%s|%.2f|%.2f|%.2f",
                                    ((SectorEntityToken) args[0]).getId(), (Float) args[1],
                                    (Float) args[2], (Float) args[3]);
                            yield null;
                        }
                        case "setSensorProfile" -> {
                            sensorProfile = (Float) args[0];
                            yield null;
                        }
                        case "getCargo" -> null;
                        case "toString" -> "Entity[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    /** One star system holding the player fleet, plus the sector wrapper around it. */
    private static final class FakeLocation {
        private final List<FakeEntity> entities = new ArrayList<>();
        private final List<FakeEntity> removed = new ArrayList<>();
        private final List<FakeEntity> created = new ArrayList<>();
        private int createdCounter;
        private LocationAPI cached;
        private SectorAPI cachedSector;

        private FakeEntity add(FakeEntity entity) {
            entities.add(entity);
            entity.containing = proxy();
            return entity;
        }

        private void remove(FakeEntity entity) {
            entities.remove(entity);
        }

        private FakeEntity onlyCreated() {
            assertEquals(1, created.size(), "exactly one entity created");
            return created.get(0);
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

        private List<SectorEntityToken> tagged(String tag) {
            List<SectorEntityToken> tokens = new ArrayList<>();
            for (FakeEntity entity : entities) {
                if (entity.tags.contains(tag)) {
                    tokens.add(entity.proxy());
                }
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
                        case "isHyperspace" -> false;
                        case "getAllEntities" -> all();
                        case "getEntitiesWithTag" -> tagged(String.valueOf(args[0]));
                        case "getPlanets" -> List.of();
                        case "addCustomEntity" -> {
                            FakeEntity entity = new FakeEntity("local-" + (++createdCounter));
                            entity.custom = true;
                            entity.createdType = (String) args[2];
                            entity.createdFaction = (String) args[3];
                            entity.customType = (String) args[2];
                            add(entity);
                            created.add(entity);
                            yield entity.proxy();
                        }
                        case "removeEntity" -> {
                            FakeEntity entity = byToken(args[0]);
                            if (entity != null) {
                                removed.add(entity);
                                entities.remove(entity);
                            }
                            yield null;
                        }
                        case "toString" -> "Location";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }

        private SectorAPI sector() {
            if (cachedSector != null) {
                return cachedSector;
            }
            LocationAPI locationProxy = proxy();
            CampaignFleetAPI player = (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(), new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getContainingLocation" -> locationProxy;
                        case "toString" -> "PlayerFleet";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            cachedSector = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(), new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPlayerFleet" -> player;
                        case "getHyperspace" -> locationProxy;
                        case "getAllLocations" -> List.of(locationProxy);
                        case "getEntityById" -> byId(String.valueOf(args[0]));
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cachedSector;
        }
    }

    private static FactionAPI factionProxy(String id) {
        return (FactionAPI) Proxy.newProxyInstance(
                FactionAPI.class.getClassLoader(), new Class<?>[]{FactionAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "toString" -> "Faction[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    private static final class MutableClock implements java.util.function.LongSupplier {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public long getAsLong() {
            return millis;
        }
    }

    private static final class RecordingNetService extends CoopNetService {
        private final CoopConnectionRole role;
        private final List<CoopMessages.Message> sent = new ArrayList<>();

        private RecordingNetService(CoopConnectionRole role) {
            this.role = role;
        }

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(CoopMessages.Message message) {
            sent.add(message);
        }
    }

    private static CoopSessionState activeHostSession() {
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123L, "seed-a", "fingerprint-a");
        return session;
    }

    private static CoopSessionState activeGuestSession() {
        CoopSessionState session = new CoopSessionState(new SequencedIds("guest-player"));
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123L, "seed-a", "fingerprint-a");
        return session;
    }

    private static final class SequencedIds implements java.util.function.Supplier<String> {
        private final List<String> ids;
        private int index;

        private SequencedIds(String... ids) {
            this.ids = List.of(ids);
        }

        @Override
        public String get() {
            return ids.get(index++);
        }
    }
}
