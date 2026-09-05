package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CharacterDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.RecordingNetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static coop.testing.TestSessions.activeGuestSession;
import static coop.testing.TestSessions.activeHostSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine glue for the two Phase 32 world-delta kinds: what the replicator does with a
 * {@code STORAGE_UNLOCK} or {@code COMMISSION} that arrives off the wire. The decisions themselves
 * live in {@link CoopStorageUnlockSync} and {@link CoopCommissionSync} and are tested there; this is
 * the wiring — the switch case, the persistent-data flag, and the host's direction check.
 */
class CoopStorageUnlockReplicatorTest {

    private final Map<String, Object> persistent = new HashMap<>();
    private final Map<String, MarketAPI> markets = new LinkedHashMap<>();
    private final Map<String, Object> characterMemory = new HashMap<>();

    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
    }

    @Test
    void aReceivedStorageUnlockOpensAMarketThisEngineKnows() {
        StoragePlugin plugin = new StoragePlugin();
        MarketAPI jangala = CoopStorageUnlockTest.marketWithStorage("market_jangala", plugin);
        markets.put("market_jangala", jangala);
        Global.setSector(sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(storageUnlock("market_jangala"));

        assertTrue(CoopStorageUnlock.flagSet(Global.getSector(), "market_jangala"));
        assertTrue(CoopStorageUnlock.pluginPaid(jangala), "the local plugin is opened too");
    }

    @Test
    void aReceivedStorageUnlockFlagsAMarketThisEngineHasNeverHeardOf() {
        // A hidden base the guest has not reconstructed, or a colony that has not replicated yet.
        // The flag has to survive the gap, because nothing resends it.
        Global.setSector(sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(storageUnlock("market_ghost"));

        assertEquals(Boolean.TRUE, persistent.get(CoopStorageUnlock.flagKey("market_ghost")));
        assertTrue(service.sent.isEmpty(), "a guest does not rebroadcast");
    }

    @Test
    void theHostAcceptsAndRebroadcastsAGuestsStorageUnlock() {
        StoragePlugin plugin = new StoragePlugin();
        markets.put("market_jangala",
                CoopStorageUnlockTest.marketWithStorage("market_jangala", plugin));
        Global.setSector(sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        replicator.handle(storageUnlock("market_jangala"));

        assertTrue(CoopStorageUnlock.flagSet(Global.getSector(), "market_jangala"));
        assertEquals(1, service.sent.size(), "the host converges both clients");
        assertEquals("STORAGE_UNLOCK",
                CoopMessages.requiredPayloadString(service.sent.get(0), "kind"));
        assertEquals("market_jangala",
                CoopMessages.requiredPayloadString(service.sent.get(0), "entityId"));
        assertTrue(CoopStorageUnlock.pluginPaid(markets.get("market_jangala")));
    }

    @Test
    void theHostRefusesAGuestSentCommission() {
        // Host-only: the commission is signed, salaried and terminated on the host, and the guest
        // mirrors one memory key. A guest-originated one is an echo or a desync either way.
        Global.setSector(sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        replicator.handle(commission("hegemony", "guest-player"));

        assertFalse(characterMemory.containsKey(MemFlags.FCM_FACTION),
                "a refused delta never reaches the engine");
        assertTrue(service.sent.isEmpty(), "and is never rebroadcast");
    }

    @Test
    void theGuestWritesTheCommissionKeyAndClearsItAgain() {
        Global.setSector(sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(commission("hegemony", "host-player"));
        assertEquals("hegemony", characterMemory.get(MemFlags.FCM_FACTION));

        replicator.handle(commission("", "host-player"));
        assertFalse(characterMemory.containsKey(MemFlags.FCM_FACTION),
                "the commission ended on the host, so the guest's access clause closes too");
    }

    // ---- Messages ------------------------------------------------------------------------------

    private static CoopMessages.Message storageUnlock(String marketId) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, marketId, "STORAGE_UNLOCK", false,
                "true", "guest-player");
    }

    private static CoopMessages.Message commission(String factionId, String actingPlayerId) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, CoopCommissionSync.ENTITY_ID,
                "COMMISSION", false, factionId, actingPlayerId);
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    /** Persistent data, an economy that answers by market id, and character memory. */
    private SectorAPI sector() {
        MemoryAPI memory = (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> {
                        characterMemory.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "unset" -> {
                        characterMemory.remove((String) args[0]);
                        yield null;
                    }
                    case "get" -> characterMemory.get((String) args[0]);
                    case "getString" -> (String) characterMemory.get((String) args[0]);
                    case "contains" -> characterMemory.containsKey((String) args[0]);
                    case "getBoolean" -> Boolean.TRUE.equals(characterMemory.get((String) args[0]));
                    case "toString" -> "Memory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        CharacterDataAPI character = (CharacterDataAPI) Proxy.newProxyInstance(
                CharacterDataAPI.class.getClassLoader(),
                new Class<?>[]{CharacterDataAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMemory", "getMemoryWithoutUpdate" -> memory;
                    case "toString" -> "CharacterData";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                EconomyAPI.class.getClassLoader(),
                new Class<?>[]{EconomyAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMarket" -> markets.get((String) args[0]);
                    case "toString" -> "Economy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentData" -> persistent;
                    case "getEconomy" -> economy;
                    case "getCharacterData" -> character;
                    case "toString" -> "Sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
