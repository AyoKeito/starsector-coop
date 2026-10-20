package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import coop.ambient.CoopLightBridge;
import coop.testing.ProxyDefaults;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class CoopLightInteractionDiagnosticsTest {
    private static final String LIGHT_ID = "light:01234567-89ab-4def-8123-0123456789ab";
    private SettingsAPI oldSettings;

    @BeforeEach void setup() {
        oldSettings = Global.getSettings();
        CoopLightBridge.clear();
        Global.setSettings(stub(SettingsAPI.class, (name, args) ->
                "getVersionString".equals(name) ? "Starsector 0.98a-RC8" : null));
    }

    @AfterEach void cleanup() {
        CoopLightBridge.clear();
        Global.setSettings(oldSettings);
    }

    @Test void canonicalHolderSurvivesTheNativeLightToUnregisteredFleetTargetSwap() throws Exception {
        Fixture f = new Fixture();
        Map<String, String> holders = new HashMap<>(Map.of(LIGHT_ID, "guest-seat"));
        f.queued.set(f.light);
        JSONObject initial = read(f, f.light, holders, false);
        assertEquals(LIGHT_ID, initial.getString("targetCanonicalLightId"));
        assertEquals(LIGHT_ID, initial.getString("queuedTargetCanonicalLightId"));
        assertEquals("guest-seat", initial.getString("claimHolderPlayerId"));
        assertFalse(initial.getBoolean("targetManagedEncounterFleet"));

        CampaignFleetAPI fleet = f.createFleet("native-temporary-1");
        f.queued.set(null);
        Map<String, Object> memoryBefore = Map.copyOf(f.memories.get(fleet));
        JSONObject dialog = read(f, fleet, holders, false);
        assertEquals(LIGHT_ID, dialog.getString("targetCanonicalLightId"));
        assertEquals("native-temporary-1", dialog.getString("targetEngineId"));
        assertTrue(dialog.getBoolean("targetManagedEncounterFleet"));
        assertEquals("guest-seat", dialog.getString("claimHolderPlayerId"));
        assertTrue(dialog.isNull("queuedTargetEngineId"));
        JSONObject observation = dialog.getJSONArray("encounters").getJSONObject(0);
        assertTrue(observation.getBoolean("observedAtCreation"));
        assertTrue(observation.getBoolean("referenceAlive"));
        assertEquals(LIGHT_ID, observation.getString("markerLightId"));
        assertFalse(observation.getBoolean("containingLocationHasFleet"));
        assertFalse(observation.getBoolean("containingLocationHasEntity"));
        assertEquals(0, observation.getInt("cleanupHookCalls"));
        assertEquals(memoryBefore, f.memories.get(fleet), "observation must not write native memory");
        assertTrue(f.removals.isEmpty());
    }

    @Test void leaveTracksTheSameUnregisteredNativeObjectWithoutInventingAStateTransition() throws Exception {
        Fixture f = new Fixture();
        CampaignFleetAPI fleet = f.createFleet("native-temporary-2");
        Map<String, String> holders = new HashMap<>(Map.of(LIGHT_ID, "host-seat"));
        JSONObject before = read(f, fleet, holders, true).getJSONArray("encounters").getJSONObject(0);

        CoopLightBridge.discardEncounterFleet(fleet);
        holders.clear();
        JSONObject after = read(f, null, holders, true);
        JSONObject observed = after.getJSONArray("encounters").getJSONObject(0);
        assertEquals(before.getString("observationId"), observed.getString("observationId"));
        assertTrue(after.isNull("targetCanonicalLightId"));
        assertEquals(LIGHT_ID, after.getString("claimLightId"));
        assertTrue(after.isNull("claimHolderPlayerId"));
        assertTrue(observed.isNull("claimHolderPlayerId"));
        assertEquals(List.of(fleet), f.removals);
        assertEquals(1, observed.getInt("cleanupHookCalls"));
        assertEquals(1, observed.getInt("cleanupHookReturns"));
        assertEquals(1, observed.getInt("removeEntityCalls"));
        assertEquals(1, observed.getInt("removeEntityReturns"));
        assertEquals("hyperspace", observed.getString("containingLocationId"));
        assertTrue(observed.getBoolean("alive"));
        assertFalse(observed.getBoolean("expired"));
        assertFalse(before.getBoolean("originHasFleet"));
        assertFalse(observed.getBoolean("originHasFleet"));
        assertFalse(observed.getBoolean("originHasEntity"));
        assertTrue(observed.isNull("cleanupError"));
        assertEquals(0, observed.getJSONArray("readErrors").length());

        read(f, null, holders, true);
        assertEquals(1, f.removals.size(), "a diagnostic query must not call cleanup again");
    }

    @Test void nativeGetterErrorsStayUnknownAndDoNotCrashOrMutateTheQuery() throws Exception {
        Fixture f = new Fixture();
        CampaignFleetAPI fleet = f.createFleet("native-temporary-errors");
        f.failEntityRead = true;
        f.failExpiredRead = true;
        JSONObject out = read(f, fleet, Map.of(), true).getJSONArray("encounters").getJSONObject(0);
        assertTrue(out.isNull("expired"));
        assertTrue(out.isNull("originHasEntity"));
        assertTrue(out.isNull("containingLocationHasEntity"));
        String errors = out.getJSONArray("readErrors").toString();
        assertTrue(errors.contains("java.lang.NoClassDefFoundError"));
        assertTrue(errors.contains("java.lang.AssertionError"));
        assertEquals(0, out.getInt("removeEntityCalls"));
        assertTrue(f.removals.isEmpty());
    }

    @Test void cleanupFailureIsRecordedAndRethrownWithoutAFalseReturnCount() {
        Fixture f = new Fixture();
        CampaignFleetAPI fleet = f.createFleet("native-temporary-failure");
        f.removeFailure = new IllegalStateException("native removal failed");
        assertSame(f.removeFailure, assertThrows(IllegalStateException.class,
                () -> CoopLightBridge.discardEncounterFleet(fleet)));
        CoopLightBridge.EncounterDiagnostic row = CoopLightBridge.encounterDiagnostics().get(0);
        assertEquals(1, row.cleanupHookCalls());
        assertEquals(0, row.cleanupHookReturns());
        assertEquals(1, row.removeEntityCalls());
        assertEquals(0, row.removeEntityReturns());
        assertEquals(IllegalStateException.class.getName(), row.cleanupError());
    }

    @Test void observationHistoryIsBoundedAndClearedWithItsTransientAdapter() {
        Fixture f = new Fixture();
        List<CampaignFleetAPI> keepAlive = new ArrayList<>();
        for (int i = 0; i < 18; i++) keepAlive.add(f.createFleet("native-" + i));
        List<CoopLightBridge.EncounterDiagnostic> rows = CoopLightBridge.encounterDiagnostics();
        assertEquals(16, rows.size());
        assertEquals("native-2", rows.get(0).engineId());
        assertEquals("native-17", rows.get(15).engineId());
        assertThrows(UnsupportedOperationException.class, () -> rows.clear());
        assertEquals(18, keepAlive.size());
        CoopLightBridge.Handler first = stub(CoopLightBridge.Handler.class, (name, args) -> null);
        CoopLightBridge.Handler second = stub(CoopLightBridge.Handler.class, (name, args) -> null);
        CoopLightBridge.install(false, first);
        assertTrue(CoopLightBridge.encounterDiagnostics().isEmpty());
        f.createFleet("first-owner");
        CoopLightBridge.install(false, first);
        assertEquals(1, CoopLightBridge.encounterDiagnostics().size());
        CoopLightBridge.install(false, second);
        assertTrue(CoopLightBridge.encounterDiagnostics().isEmpty());
        f.createFleet("second-owner");
        CoopLightBridge.uninstall(first);
        assertEquals(1, CoopLightBridge.encounterDiagnostics().size());
        CoopLightBridge.uninstall(second);
        assertTrue(CoopLightBridge.encounterDiagnostics().isEmpty());
        f.createFleet("cleared-explicitly");
        CoopLightBridge.clear();
        assertTrue(CoopLightBridge.encounterDiagnostics().isEmpty());
    }

    @Test void invalidRequestedCanonicalIdIsRejectedBeforeClaimLookup() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.lightInteractionBlock(
                new JSONObject().put("lightId", "native-temporary"), f.sector, null,
                id -> { fail("an invalid ID must not reach the production ledger"); return null; }));
    }

    private static JSONObject read(Fixture f, SectorEntityToken target, Map<String, String> holders,
                                   boolean requested) throws Exception {
        JSONObject args = requested ? new JSONObject().put("lightId", LIGHT_ID) : new JSONObject();
        return CoopAgentCommands.lightInteractionBlock(args, f.sector, target, holders::get);
    }

    private static final class Fixture {
        final Map<CampaignFleetAPI, Map<String, Object>> memories = new HashMap<>();
        final List<Object> removals = new ArrayList<>();
        final AtomicReference<SectorEntityToken> queued = new AtomicReference<>();
        boolean failEntityRead;
        boolean failExpiredRead;
        IllegalStateException removeFailure;
        final LocationAPI location = stub(LocationAPI.class, (name, args) -> switch (name) {
            case "getId" -> "hyperspace";
            case "isHyperspace" -> true;
            case "getFleets" -> List.of();
            case "getAllEntities" -> {
                if (failEntityRead) throw new NoClassDefFoundError("test native getter unavailable");
                yield List.of();
            }
            case "removeEntity" -> {
                if (removeFailure != null) throw removeFailure;
                removals.add(args[0]);
                yield null;
            }
            default -> null;
        });
        final MemoryAPI lightMemory = memory(new HashMap<>(Map.of(CoopLightBridge.LIGHT_ID_KEY, LIGHT_ID)));
        final AbyssalLightEntityPlugin plugin = new AbyssalLightEntityPlugin();
        final CustomCampaignEntityAPI light = stub(CustomCampaignEntityAPI.class, (name, args) -> switch (name) {
            case "getCustomPlugin" -> plugin;
            case "getContainingLocation" -> location;
            case "getMemoryWithoutUpdate" -> lightMemory;
            case "getId" -> "native-light";
            default -> null;
        });
        final CampaignFleetAPI player = stub(CampaignFleetAPI.class, (name, args) ->
                "getInteractionTarget".equals(name) ? queued.get() : null);
        final SectorAPI sector = stub(SectorAPI.class, (name, args) ->
                "getPlayerFleet".equals(name) ? player : null);

        CampaignFleetAPI createFleet(String id) {
            Map<String, Object> values = new HashMap<>();
            MemoryAPI memory = memory(values);
            FactionAPI faction = stub(FactionAPI.class, (name, args) -> "getId".equals(name) ? Factions.DWELLER : null);
            AtomicReference<LocationAPI> containingLocation = new AtomicReference<>();
            CampaignFleetAPI fleet = stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
                case "getId" -> id;
                case "getFaction" -> faction;
                case "getMemoryWithoutUpdate" -> memory;
                case "getContainingLocation" -> containingLocation.get();
                case "isAlive" -> true;
                case "isExpired" -> {
                    if (failExpiredRead) throw new AssertionError("test getter failed");
                    yield false;
                }
                default -> null;
            });
            memories.put(fleet, values);
            CoopLightBridge.markEncounterFleet(fleet, light);
            containingLocation.set(location);
            return fleet;
        }
    }

    private static MemoryAPI memory(Map<String, Object> values) {
        return stub(MemoryAPI.class, (name, args) -> switch (name) {
            case "get" -> values.get(args[0]);
            case "contains" -> values.containsKey(args[0]);
            case "set" -> { values.put((String) args[0], args[1]); yield null; }
            default -> null;
        });
    }

    private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + "Proxy";
                    default -> {
                        Object value = answer.apply(method.getName(), args);
                        yield value == null ? ProxyDefaults.defaultValue(method.getReturnType()) : value;
                    }
                }));
    }
}
