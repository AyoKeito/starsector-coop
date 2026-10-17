package coop.debug;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.campaign.CoopAbyssalDespawner;
import coop.campaign.CoopEncounterRecord;
import coop.campaign.CoopEncounterWorld;
import coop.fleet.CoopEntityIds;
import coop.fleet.CoopLocations;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CoopAgentEncounterLifetimeTest {
    private static final long DAY = 86_400_000L;
    private SettingsAPI oldSettings;
    private SectorAPI oldSector;

    @BeforeEach void setup() {
        oldSettings = Global.getSettings(); oldSector = Global.getSector(); CoopLocations.invalidate();
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getFloat" -> "unitsPerLightYear".equals(a[0]) ? 2000f : 0f;
            case "getColor" -> Color.WHITE;
            default -> ProxyDefaults.defaultValue(m.getReturnType());
        }));
    }
    @AfterEach void cleanup() {
        Global.setSector(oldSector); Global.setSettings(oldSettings); CoopLocations.invalidate();
        coop.ui.CoopSessionIntelFeed.uninstall();
    }

    @Test void defaultAndFalseQueryNeverReadLifetimeStateAndInvalidArgumentFailsBeforeReadingIt() throws Exception {
        Fixture f = new Fixture();
        JSONObject ordinary = f.row(new JSONObject(), CoopConnectionRole.HOST);
        JSONObject explicitFalse = f.row(new JSONObject().put("lifetime", false), CoopConnectionRole.HOST);
        assertFalse(ordinary.has("lifetime")); assertEquals(ordinary.toString(), explicitFalse.toString());
        assertEquals(0, f.scriptReads); assertEquals(0, f.hostAgeReads);
        assertThrows(IllegalArgumentException.class, () -> f.row(new JSONObject().put("lifetime", "maybe"), CoopConnectionRole.HOST));
        assertThrows(IllegalArgumentException.class, () -> f.row(new JSONObject().put("lifetime", new JSONObject()), CoopConnectionRole.HOST));
        assertEquals(0, f.scriptReads); assertEquals(0, f.hostAgeReads);
    }

    @Test void hostQueryReportsActualPredicateInputsAndNeverRefreshesVisitsOrAdvancesEligibleScript() throws Exception {
        Fixture f = new Fixture(); f.script.updateGuestPresence(f.guest, false);
        long observed = f.now; f.now += 10 * DAY;
        Map<String, Object> before = state(f.script);
        JSONObject life = f.life(CoopConnectionRole.HOST);
        assertTrue(life.getBoolean("available")); assertTrue(life.getBoolean("runtimeReady"));
        assertEquals(1, life.getInt("customScriptCount")); assertEquals(1, life.getInt("matchingScriptCount"));
        assertEquals(0, life.getInt("vanillaScriptCount"));
        assertTrue(life.getBoolean("guestObserved")); assertFalse(life.getBoolean("lastGuestInside"));
        assertEquals(Long.toString(observed), life.getString("guestLastVisitTimestamp"));
        assertEquals(10, life.getDouble("elapsedGuestDays")); assertEquals(20, life.getDouble("hostDaysSinceVisit"));
        assertEquals(Long.toString(f.hostLastVisit), life.getString("hostLastVisitTimestamp"));
        assertEquals(0, life.getDouble("systemX")); assertEquals(0, life.getDouble("systemY"));
        assertEquals(20000, life.getDouble("hostHyperX")); assertEquals(12000, life.getDouble("guestHyperX"));
        assertEquals(10, life.getDouble("hostDistanceLY")); assertEquals(6, life.getDouble("guestDistanceLY"));
        assertEquals(2000, life.getDouble("unitsPerLightYear")); assertFalse(life.getBoolean("hostInside"));
        assertTrue(life.getBoolean("canDespawn")); assertEquals(f.script.canDespawnNow(f.sector), life.getBoolean("canDespawn"));
        f.life(CoopConnectionRole.HOST);
        assertEquals(before, state(f.script)); assertSame(f.system, f.script.getSystem());
        assertEquals(1, f.scripts.size()); assertEquals(1, f.systems.size());
        assertEquals(observed + 10 * DAY, f.now); assertEquals(f.recipe.encode(), f.memory.get(CoopEncounterWorld.RECIPE_KEY));
    }

    @Test void unobservedGuestIsNullRatherThanInventedAndInsideGraceAgeDistanceRemainActualGuards() throws Exception {
        Fixture f = new Fixture(); JSONObject unready = f.life(CoopConnectionRole.HOST);
        assertFalse(unready.getBoolean("runtimeReady")); assertFalse(unready.getBoolean("guestObserved"));
        for (String key : List.of("guestLastVisitTimestamp", "elapsedGuestDays", "guestHyperX", "guestHyperY", "guestDistanceLY"))
            assertTrue(unready.isNull(key), key);
        assertFalse(unready.getBoolean("canDespawn"));
        f.script.updateGuestPresence(f.guest, false); f.now += 10 * DAY;
        f.hostDays = 9.99f; assertFalse(f.life(CoopConnectionRole.HOST).getBoolean("canDespawn"));
        f.hostDays = 20; f.guestPosition.x = 9999; f.script.updateGuestPresence(f.guest, false);
        assertFalse(f.life(CoopConnectionRole.HOST).getBoolean("canDespawn"));
        f.guestPosition.x = 10000; f.script.updateGuestPresence(f.guest, false);
        assertTrue(f.life(CoopConnectionRole.HOST).getBoolean("canDespawn"));
        f.script.updateGuestPresence(null, true);
        JSONObject grace = f.life(CoopConnectionRole.HOST); assertTrue(grace.getBoolean("reconnectHold")); assertFalse(grace.getBoolean("canDespawn"));
        f.guestLocation = f.system; f.script.updateGuestPresence(f.guest, false); long insideVisit = f.now;
        f.now += 11 * DAY; Map<String, Object> before = state(f.script);
        JSONObject inside = f.life(CoopConnectionRole.HOST);
        assertTrue(inside.getBoolean("lastGuestInside")); assertFalse(inside.getBoolean("canDespawn"));
        assertEquals(Long.toString(insideVisit), inside.getString("guestLastVisitTimestamp"));
        assertEquals(11, inside.getDouble("elapsedGuestDays")); assertEquals(before, state(f.script));
    }

    @Test void guestAndMissingOrDuplicateHostScriptDiagnosticsAreExplicitlyUnavailable() throws Exception {
        Fixture f = new Fixture(); f.script.updateGuestPresence(f.guest, false);
        JSONObject guest = f.life(CoopConnectionRole.GUEST);
        assertFalse(guest.getBoolean("available"));
        assertEquals("host_owned_lifetime_unavailable_on_guest", guest.getString("reason"));
        assertFalse(guest.has("hostDaysSinceVisit")); assertFalse(guest.has("canDespawn"));
        assertEquals(0, f.hostAgeReads);
        f.scripts.clear(); JSONObject missing = f.life(CoopConnectionRole.HOST);
        assertFalse(missing.getBoolean("available")); assertEquals("host_despawner_missing", missing.getString("reason"));
        f.scripts.add(f.script); f.scripts.add(new CoopAbyssalDespawner(f.system));
        JSONObject duplicate = f.life(CoopConnectionRole.HOST);
        assertFalse(duplicate.getBoolean("available")); assertEquals("multiple_host_despawners", duplicate.getString("reason"));
    }

    @Test void nativeVisitedStringAndMirroredBooleanHaveIdenticalCanonicalReadbackWithoutMutatingRawFlags() throws Exception {
        Fixture f = new Fixture(); f.entityMemory.put("$visited", " TrUe ");
        JSONObject visitor = f.flags(); assertEquals(Boolean.TRUE, visitor.get("$visited"));
        assertEquals(" TrUe ", f.entityMemory.get("$visited"));
        f.entityMemory.put("$visited", true); assertEquals(visitor.toString(), f.flags().toString());
        f.entityMemory.put("$visited", " FALSE "); assertEquals(Boolean.FALSE, f.flags().get("$visited"));
        f.entityMemory.put("$visited", "unexpected"); assertEquals("unexpected", f.flags().get("$visited"));
        f.entityMemory.put("$abyssalBlackHoleReadings", "true");
        assertEquals("true", f.flags().get("$abyssalBlackHoleReadings"), "only the native visited variation is normalized");
    }

    @Test void diagnosticSourceBoundaryCannotAdvanceOrRewriteClockHistoryPresenceOrRandomness() throws Exception {
        String despawner = Files.readString(Path.of("src/main/java/coop/campaign/CoopAbyssalDespawner.java"));
        String reader = despawner.substring(despawner.indexOf("public LifetimeDiagnostics lifetimeDiagnostics("),
                despawner.indexOf("    @Override", despawner.indexOf("public LifetimeDiagnostics lifetimeDiagnostics(")));
        String commands = Files.readString(Path.of("src/main/java/coop/debug/CoopAgentCommands.java"));
        String query = commands.substring(commands.indexOf("static JSONObject encounters("), commands.indexOf("private static void putEncounterExtra("));
        for (String source : List.of(reader, query)) for (String forbidden : List.of(".advance(", "updateGuestPresence(", "setTimestamp(",
                "setPaused(", "setLastPlayerVisit", "removeSystemAndLinks(", "new Random(", "Math.random("))
            assertFalse(source.contains(forbidden), forbidden);
    }

    private static Map<String, Object> state(CoopAbyssalDespawner script) throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        for (Field field : CoopAbyssalDespawner.class.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true); state.put(field.getName(), field.get(script));
        }
        return state;
    }

    private static final class Fixture {
        long now = -55_000_000_000_000L, hostLastVisit = now - 20 * DAY;
        float hostDays = 20;
        int scriptReads, hostAgeReads;
        final Vector2f systemPosition = new Vector2f(), hostPosition = new Vector2f(20000, 0), guestPosition = new Vector2f(12000, 0);
        final Map<String, Object> memory = new HashMap<>(), entityMemory = new HashMap<>();
        final List<EveryFrameScript> scripts = new ArrayList<>();
        final List<SectorEntityToken> entities = new ArrayList<>();
        final List<StarSystemAPI> systems = new ArrayList<>();
        final LocationAPI hyper;
        final StarSystemAPI system;
        final SectorAPI sector;
        final SectorEntityToken guest, entity;
        final CampaignFleetAPI host;
        final CoopAbyssalDespawner script;
        final CoopEncounterRecord recipe;
        LocationAPI guestLocation;
        Fixture() {
            recipe = new CoopEncounterRecord("alpha", 1, "ep", 0, 0, 1, true, CoopEncounterRecord.Creator.PARENT, false,
                    List.of(new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.SYSTEM, 0, "token")), List.of(), CoopEncounterRecord.Overlay.empty());
            memory.put(CoopEncounterWorld.RECIPE_KEY, recipe.encode());
            MemoryAPI systemMemory = memory(memory), nativeEntityMemory = memory(entityMemory);
            hyper = proxy(LocationAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getId" -> "hyperspace";
                case "getAllEntities" -> List.of();
                default -> safe(p, m.getName(), m.getReturnType(), a);
            });
            system = proxy(StarSystemAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getId" -> "deep space";
                case "getOptionalUniqueId" -> "alpha";
                case "hasTag" -> Tags.TEMPORARY_LOCATION.equals(a[0]) || Tags.SYSTEM_ABYSSAL.equals(a[0]);
                case "getLocation" -> systemPosition;
                case "getMemoryWithoutUpdate" -> systemMemory;
                case "getAllEntities" -> entities;
                case "getScripts" -> { scriptReads++; yield scripts; }
                case "getDaysSinceLastPlayerVisit" -> { hostAgeReads++; yield hostDays; }
                case "getLastPlayerVisitTimestamp" -> hostLastVisit;
                case "getEntityById" -> entities.stream().filter(e -> e.getId().equals(a[0])).findFirst().orElse(null);
                default -> safe(p, m.getName(), m.getReturnType(), a);
            });
            guestLocation = hyper;
            host = proxy(CampaignFleetAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getContainingLocation" -> hyper;
                case "getLocationInHyperspace" -> hostPosition;
                default -> safe(p, m.getName(), m.getReturnType(), a);
            });
            guest = proxy(SectorEntityToken.class, (p, m, a) -> switch (m.getName()) {
                case "getContainingLocation" -> guestLocation;
                case "getLocationInHyperspace" -> guestPosition;
                default -> safe(p, m.getName(), m.getReturnType(), a);
            });
            entityMemory.put(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, "coopenc_alpha_system0");
            entityMemory.put(CoopEncounterWorld.ENCOUNTER_ID_KEY, "alpha");
            entity = proxy(SectorEntityToken.class, (p, m, a) -> switch (m.getName()) {
                case "getId" -> "native-entity";
                case "getMemoryWithoutUpdate" -> nativeEntityMemory;
                case "getContainingLocation", "getStarSystem" -> system;
                case "getLocation" -> systemPosition;
                default -> safe(p, m.getName(), m.getReturnType(), a);
            }); entities.add(entity); systems.add(system);
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getTimestamp" -> now;
                case "getElapsedDaysSince" -> (now - (Long) a[0]) / (float) DAY;
                default -> safe(p, m.getName(), m.getReturnType(), a);
            });
            sector = proxy(SectorAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getStarSystems" -> systems;
                case "getAllLocations" -> List.of(system, hyper);
                case "getHyperspace" -> hyper;
                case "getPlayerFleet" -> host;
                case "getClock" -> clock;
                case "getEntityById" -> "native-entity".equals(a[0]) ? entity : null;
                default -> safe(p, m.getName(), m.getReturnType(), a);
            });
            Global.setSector(sector); script = new CoopAbyssalDespawner(system); scripts.add(script);
        }
        JSONObject row(JSONObject args, CoopConnectionRole role) throws Exception {
            CoopNetPump pump = new CoopNetPump(new RecordingNetService(role), () -> 0L);
            CoopAgentCommands.Context context = new CoopAgentCommands.Context() {
                public SectorAPI sector() { return sector; }
                public CoopNetPump pump() { return pump; }
            };
            return CoopAgentCommands.encounters(args, context).getJSONObject("encounters").getJSONObject("alpha");
        }
        JSONObject life(CoopConnectionRole role) throws Exception { return row(new JSONObject().put("lifetime", true), role).getJSONObject("lifetime"); }
        JSONObject flags() throws Exception { return row(new JSONObject(), CoopConnectionRole.HOST).getJSONObject("entities")
                .getJSONObject("coopenc_alpha_system0").getJSONObject("hintFlags"); }
    }

    private static MemoryAPI memory(Map<String, Object> values) {
        return proxy(MemoryAPI.class, (p, m, a) -> switch (m.getName()) {
            case "get" -> values.get(a[0]);
            case "contains" -> values.containsKey(a[0]);
            case "getBoolean" -> Boolean.TRUE.equals(values.get(a[0]));
            default -> safe(p, m.getName(), m.getReturnType(), a);
        });
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(CoopAgentEncounterLifetimeTest.class.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object safe(Object proxy, String method, Class<?> type, Object[] args) {
        if (method.startsWith("set") || method.startsWith("add") || method.startsWith("remove") || method.equals("advance"))
            throw new AssertionError("Read-only query attempted engine mutation: " + method);
        return switch (method) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> ProxyDefaults.defaultValue(type);
        };
    }
}
