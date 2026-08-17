package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyDecivListener;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.GateEntityPlugin;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Engine-glue coverage for the Phase 13 skeleton mutations: the host's poll and deciv listener, and
 * the guest's three appliers. The engine is stood up as interface proxies (no mocking framework in
 * this build), which is enough because every real decision lives in
 * {@link CoopSkeletonMutationWatcher} and is tested there.
 */
class CoopSkeletonMutationReplicatorTest {

    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
    }

    // ---- Host capture --------------------------------------------------------------------------

    @Test
    void hostPollSeedsSilentlyThenReportsEveryObjectiveFlipIncludingFlipsBack() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), clock);

        replicator.tickWorldDeltas();
        assertTrue(service.sent.isEmpty(), "the seeding poll reports nothing");

        relay.factionId = "pirates";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        relay.factionId = "hegemony";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        relay.factionId = "pirates";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertEquals(List.of("pirates", "hegemony", "pirates"), ownershipPayloads(service.sent));
    }

    @Test
    void hostPollIsThrottled() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), clock);

        replicator.tickWorldDeltas();
        relay.factionId = "pirates";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS - 1);
        replicator.tickWorldDeltas();

        assertTrue(service.sent.isEmpty());
    }

    @Test
    void hostReportsAGateThatIsAlreadyScannedWhenTheSessionStarts() {
        FakeSector sector = new FakeSector();
        FakeEntity gate = sector.addGate("gate-galatia");
        gate.memory.set(GateEntityPlugin.GATE_SCANNED, true);
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), new MutableClock(1_000_000L));

        replicator.tickWorldDeltas();

        assertEquals(1, service.sent.size());
        assertEquals("GATE_ACTIVATED", CoopMessages.requiredPayloadString(service.sent.get(0), "kind"));
        assertEquals("gate-galatia",
                CoopMessages.requiredPayloadString(service.sent.get(0), "entityId"));
        assertEquals(CoopSkeletonMutationWatcher.encodeGateState(true, false, false),
                CoopMessages.requiredPayloadString(service.sent.get(0), "newStateJson"));
    }

    @Test
    void guestNeverCaptures() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), clock);

        replicator.tickWorldDeltas();
        relay.factionId = "pirates";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertTrue(service.sent.isEmpty(), "the host owns the war sim; the guest must stay quiet");
    }

    @Test
    void hostDecivListenerReportsTheMarketOnce() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), new MutableClock(1_000_000L));
        replicator.registerOn(sector.proxy());

        ColonyDecivListener capture = sector.listenerOfType(ColonyDecivListener.class);
        capture.reportColonyDecivilized(market("market_yama", false, true), false);
        // Vanilla can fire more than once across a session; the ledger keeps the wire clean.
        capture.reportColonyDecivilized(market("market_yama", false, true), false);

        assertEquals(1, service.sent.size());
        assertEquals("DECIV", CoopMessages.requiredPayloadString(service.sent.get(0), "kind"));
        assertEquals("market_yama", CoopMessages.requiredPayloadString(service.sent.get(0), "entityId"));
        assertEquals("false", CoopMessages.requiredPayloadString(service.sent.get(0), "newStateJson"));
    }

    // ---- Guest apply ---------------------------------------------------------------------------

    @Test
    void guestAppliesObjectiveOwnershipAndIsIdempotent() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        replicator.handle(ownershipMessage("relay-1", "pirates"));
        assertEquals("pirates", relay.factionId);
        assertEquals(1, relay.setFactionCalls);

        // Ledger-blocked re-apply.
        replicator.handle(ownershipMessage("relay-1", "pirates"));
        assertEquals(1, relay.setFactionCalls);

        // And the applier itself is idempotent even with the ledger out of the way.
        replicator.worldLedger().clear();
        replicator.handle(ownershipMessage("relay-1", "pirates"));
        assertEquals(1, relay.setFactionCalls);

        // A genuine flip back still lands.
        replicator.handle(ownershipMessage("relay-1", "hegemony"));
        assertEquals("hegemony", relay.factionId);
        assertEquals(2, relay.setFactionCalls);
    }

    @Test
    void guestToleratesObjectiveOwnershipForAnUnknownEntity() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        assertDoesNotThrow(() -> replicator.handle(ownershipMessage("no-such-relay", "pirates")));
        assertTrue(service.sent.isEmpty());
    }

    @Test
    void guestAppliesGateStateAndIsIdempotent() {
        FakeSector sector = new FakeSector();
        FakeEntity gate = sector.addGate("gate-1");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        replicator.handle(gateMessage("gate-1", true, true, true));

        assertTrue(gate.memory.getBoolean(GateEntityPlugin.GATE_SCANNED));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.GATES_ACTIVE));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES));

        int writes = sector.memory.writes + gate.memory.writes;
        replicator.worldLedger().clear();
        replicator.handle(gateMessage("gate-1", true, true, true));
        assertEquals(writes, sector.memory.writes + gate.memory.writes,
                "re-applying an already-applied gate state must write nothing");
    }

    @Test
    void guestToleratesGateActivationForAnUnknownGateAndStillTakesTheGlobalFlags() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        assertDoesNotThrow(() -> replicator.handle(gateMessage("no-such-gate", true, true, true)));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.GATES_ACTIVE));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES));
    }

    @Test
    void guestToleratesDecivForAnUnknownMarket() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        assertDoesNotThrow(() -> replicator.handle(CoopMessages.worldDelta("session-a", 1L, 0L,
                "no-such-market", "DECIV", false, "false", "host")));
        // Recorded either way, so a later echo cannot re-trigger it.
        assertNull(replicator.worldLedger().latestState(CoopWorldDelta.Kind.DECIV, "no-such-market"));
        assertTrue(service.sent.isEmpty());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static List<String> ownershipPayloads(List<CoopMessages.Message> sent) {
        List<String> payloads = new ArrayList<>();
        for (CoopMessages.Message message : sent) {
            if ("OBJECTIVE_OWNERSHIP".equals(CoopMessages.requiredPayloadString(message, "kind"))) {
                payloads.add(CoopMessages.requiredPayloadString(message, "newStateJson"));
            }
        }
        return payloads;
    }

    private static CoopMessages.Message ownershipMessage(String entityId, String factionId) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, entityId, "OBJECTIVE_OWNERSHIP",
                false, factionId, "host");
    }

    private static CoopMessages.Message gateMessage(String entityId, boolean scanned,
                                                    boolean gatesActive, boolean canUseGates) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, entityId, "GATE_ACTIVATED", false,
                CoopSkeletonMutationWatcher.encodeGateState(scanned, gatesActive, canUseGates), "host");
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

    // ---- Engine fakes --------------------------------------------------------------------------

    /** Minimal in-memory {@code MemoryAPI} that also counts writes, for the idempotency assertions. */
    private static final class FakeMemory {
        private final Map<String, Object> values = new HashMap<>();
        private int writes;

        void set(String key, Object value) {
            values.put(key, value);
            writes++;
        }

        boolean getBoolean(String key) {
            return Boolean.TRUE.equals(values.get(key));
        }

        MemoryAPI proxy() {
            return (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(),
                    new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            set((String) args[0], args[1]);
                            yield null;
                        }
                        case "unset" -> {
                            values.remove((String) args[0]);
                            yield null;
                        }
                        case "get" -> values.get((String) args[0]);
                        case "contains" -> values.containsKey((String) args[0]);
                        case "getBoolean" -> getBoolean((String) args[0]);
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
        private String factionId;
        private int setFactionCalls;

        private FakeEntity(String id, String factionId) {
            this.id = id;
            this.factionId = factionId;
        }

        SectorEntityToken proxy() {
            MemoryAPI memoryProxy = memory.proxy();
            return (SectorEntityToken) Proxy.newProxyInstance(
                    SectorEntityToken.class.getClassLoader(),
                    new Class<?>[]{SectorEntityToken.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getFaction" -> factionId == null ? null : faction(factionId);
                        case "setFaction" -> {
                            factionId = (String) args[0];
                            setFactionCalls++;
                            yield null;
                        }
                        case "getMemoryWithoutUpdate", "getMemory" -> memoryProxy;
                        case "toString" -> "Entity[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /** One location holding tagged objectives and gates, plus sector memory and a listener manager. */
    private static final class FakeSector {
        private final Map<String, FakeEntity> objectives = new LinkedHashMap<>();
        private final Map<String, FakeEntity> gates = new LinkedHashMap<>();
        private final FakeMemory memory = new FakeMemory();
        private final List<Object> listeners = new ArrayList<>();
        private SectorAPI cached;

        FakeEntity addObjective(String id, String factionId) {
            FakeEntity entity = new FakeEntity(id, factionId);
            objectives.put(id, entity);
            return entity;
        }

        FakeEntity addGate(String id) {
            FakeEntity entity = new FakeEntity(id, null);
            gates.put(id, entity);
            return entity;
        }

        @SuppressWarnings("unchecked")
        <T> T listenerOfType(Class<T> type) {
            for (Object listener : listeners) {
                if (type.isInstance(listener)) {
                    return (T) listener;
                }
            }
            return null;
        }

        private List<SectorEntityToken> tagged(String tag) {
            Map<String, FakeEntity> source = switch (tag) {
                case "objective" -> objectives;
                case "gate" -> gates;
                default -> Map.of();
            };
            List<SectorEntityToken> tokens = new ArrayList<>();
            for (FakeEntity entity : source.values()) {
                tokens.add(entity.proxy());
            }
            return tokens;
        }

        private SectorEntityToken byId(String id) {
            FakeEntity entity = objectives.get(id);
            if (entity == null) {
                entity = gates.get(id);
            }
            return entity == null ? null : entity.proxy();
        }

        SectorAPI proxy() {
            if (cached != null) {
                return cached;
            }
            LocationAPI location = (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(),
                    new Class<?>[]{LocationAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> "loc-1";
                        case "getEntitiesWithTag" -> tagged((String) args[0]);
                        case "getAllEntities" -> List.<SectorEntityToken>of();
                        case "toString" -> "Location";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            ListenerManagerAPI listenerManager = (ListenerManagerAPI) Proxy.newProxyInstance(
                    ListenerManagerAPI.class.getClassLoader(),
                    new Class<?>[]{ListenerManagerAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addListener" -> {
                            listeners.add(args[0]);
                            yield null;
                        }
                        case "removeListener" -> {
                            listeners.remove(args[0]);
                            yield null;
                        }
                        case "toString" -> "ListenerManager";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> null;
                        case "toString" -> "Economy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            MemoryAPI memoryProxy = memory.proxy();
            cached = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAllLocations" -> List.of(location);
                        case "getHyperspace" -> location;
                        case "getEntityById" -> byId((String) args[0]);
                        case "getMemoryWithoutUpdate" -> memoryProxy;
                        case "getListenerManager" -> listenerManager;
                        case "getEconomy" -> economy;
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    private static FactionAPI faction(String id) {
        return (FactionAPI) Proxy.newProxyInstance(
                FactionAPI.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "toString" -> "Faction[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static com.fs.starfarer.api.campaign.econ.MarketAPI market(
            String id, boolean decivilized, boolean hasPrimary) {
        return (com.fs.starfarer.api.campaign.econ.MarketAPI) Proxy.newProxyInstance(
                com.fs.starfarer.api.campaign.econ.MarketAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.campaign.econ.MarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "hasCondition" -> decivilized;
                    case "getPrimaryEntity" -> hasPrimary ? new FakeEntity(id + "_p", null).proxy() : null;
                    case "toString" -> "Market[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
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
        if (type == char.class) {
            return '\0';
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
