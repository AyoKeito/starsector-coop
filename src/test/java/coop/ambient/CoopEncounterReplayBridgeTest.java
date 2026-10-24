package coop.ambient;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectDireHintsEPEC;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectEPEC;
import com.fs.starfarer.api.impl.campaign.enc.EncounterPoint;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceAbyssPluginImpl.AbyssalEPData;
import coop.rng.CoopRandom;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual parent fork up to its first content choice, without a renderer or game. */
class CoopEncounterReplayBridgeTest {
    private static final String PITY_KEY = "$threatSpawnsFailedToRoll";
    private SettingsAPI oldSettings;
    private SectorAPI oldSector;
    private String oldSeed;
    private final List<AbyssalRogueStellarObjectEPEC.RogueStellarObjectType> objectTypes = new ArrayList<>();
    private final List<Float> objectWeights = new ArrayList<>();

    @BeforeEach void setup() {
        oldSettings = Global.getSettings(); oldSector = Global.getSector();
        oldSeed = System.getProperty(CoopRandom.NEW_GAME_SEED_PROPERTY);
        System.clearProperty(CoopRandom.NEW_GAME_SEED_PROPERTY);
        Global.setSettings(ApiProxies.whiteSettings());
        CoopEncounterReplayBridge.clear();
        var picker = AbyssalRogueStellarObjectEPEC.STELLAR_OBJECT_TYPES;
        objectTypes.addAll(picker.getItems());
        for (int i = 0; i < picker.getItems().size(); i++) objectWeights.add(picker.getWeight(i));
        // Native pick then returns null before expensive star/planet generation. Everything before
        // that boundary, including seed capture and threat handling, is the actual pinned fork.
        picker.clear();
    }

    @AfterEach void cleanup() {
        var picker = AbyssalRogueStellarObjectEPEC.STELLAR_OBJECT_TYPES;
        picker.clear(); for (int i = 0; i < objectTypes.size(); i++) picker.add(objectTypes.get(i), objectWeights.get(i));
        CoopEncounterReplayBridge.clear(); Global.setSettings(oldSettings); Global.setSector(oldSector);
        if (oldSeed == null) System.clearProperty(CoopRandom.NEW_GAME_SEED_PROPERTY);
        else System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, oldSeed);
    }

    @Test void replaySlotHasAnExplicitLifetimeAndRejectsNestedReplacement() {
        assertFalse(CoopEncounterReplayBridge.isReplaying());
        assertThrows(IllegalStateException.class, CoopEncounterReplayBridge::replaySeed);
        CoopEncounterReplayBridge.beginReplay(Long.MIN_VALUE, true, false);
        assertTrue(CoopEncounterReplayBridge.isReplaying()); assertEquals(Long.MIN_VALUE, CoopEncounterReplayBridge.replaySeed());
        assertTrue(CoopEncounterReplayBridge.replayThreatRollRan()); assertFalse(CoopEncounterReplayBridge.replayThreatResolved());
        assertThrows(IllegalStateException.class, () -> CoopEncounterReplayBridge.beginReplay(2, false, true));
        assertEquals(Long.MIN_VALUE, CoopEncounterReplayBridge.replaySeed());
        CoopEncounterReplayBridge.endReplay(); assertFalse(CoopEncounterReplayBridge.isReplaying());
        CoopEncounterReplayBridge.beginReplay(3, false, true); CoopEncounterReplayBridge.clear();
        assertFalse(CoopEncounterReplayBridge.isReplaying());
    }

    @Test void replayThreatAdvancesExactlyTheHostOptionalDraw() {
        for (boolean rollRan : List.of(false, true)) {
            for (boolean resolved : List.of(false, true)) {
                Random actual = new Random(1234), expected = new Random(1234);
                CoopEncounterReplayBridge.beginReplay(1234, rollRan, resolved);
                assertEquals(resolved, CoopEncounterReplayBridge.applyReplayThreat(actual));
                if (rollRan) expected.nextFloat();
                assertEquals(expected.nextLong(), actual.nextLong());
                CoopEncounterReplayBridge.endReplay();
            }
        }
    }

    @Test void qaSeedSlotRejectsNestedAndReplayCollisionsAndClearsBetweenCampaigns() {
        assertFalse(CoopEncounterReplayBridge.hasQaSeed());
        assertThrows(IllegalStateException.class, CoopEncounterReplayBridge::qaSeed);
        CoopEncounterReplayBridge.beginQaSeed(Long.MIN_VALUE);
        assertTrue(CoopEncounterReplayBridge.hasQaSeed());
        assertEquals(Long.MIN_VALUE, CoopEncounterReplayBridge.qaSeed());
        assertFalse(CoopEncounterReplayBridge.isReplaying());
        assertThrows(IllegalStateException.class, () -> CoopEncounterReplayBridge.beginQaSeed(2));
        assertThrows(IllegalStateException.class, () -> CoopEncounterReplayBridge.beginReplay(2, true, true));
        assertEquals(Long.MIN_VALUE, CoopEncounterReplayBridge.qaSeed());
        CoopEncounterReplayBridge.endQaSeed();
        CoopEncounterReplayBridge.beginReplay(3, false, false);
        assertThrows(IllegalStateException.class, () -> CoopEncounterReplayBridge.beginQaSeed(4));
        CoopEncounterReplayBridge.clear();
        CoopEncounterReplayBridge.beginQaSeed(5); CoopEncounterReplayBridge.clear();
        assertFalse(CoopEncounterReplayBridge.hasQaSeed()); assertFalse(CoopEncounterReplayBridge.isReplaying());
    }

    @Test void qaSeedOverridesTheLaunchSeedOnBothNativeCreatorsAndCapturesNormalInputs() {
        System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, "MN-test-encounter");
        for (AbyssalRogueStellarObjectEPEC creator : List.of(new AbyssalRogueStellarObjectEPEC(), new AbyssalRogueStellarObjectDireHintsEPEC())) {
            Scene scene = new Scene(); Global.setSector(scene.sector);
            SeedRandom original = new SeedRandom(999); EncounterPoint point = point(original, 2, true);
            CoopEncounterReplayBridge.beginQaSeed(Long.MAX_VALUE);
            try { creator.createEncounter(null, point); }
            finally { CoopEncounterReplayBridge.endQaSeed(); }
            assertEquals(0, original.longDraws);
            assertEquals(Long.toString(Long.MAX_VALUE), scene.systemMemory.get(CoopEncounterReplayBridge.SEED_KEY));
            assertEquals("false", scene.systemMemory.get(CoopEncounterReplayBridge.THREAT_ROLL_RAN_KEY));
            assertEquals(creator.getClass().getName(), scene.systemMemory.get(CoopEncounterReplayBridge.CREATOR_KEY));
            assertEquals(new Random(Long.MAX_VALUE).nextLong(), ((AbyssalEPData) point.custom).random.nextLong());
            assertFalse(CoopEncounterReplayBridge.hasQaSeed());
        }
    }

    @Test void ordinaryGenerationRecordsExactInputsWithoutALaunchSeed() {
        Scene scene = new Scene(); scene.sectorMemory.put(PITY_KEY, 9); Global.setSector(scene.sector);
        SeedRandom original = new SeedRandom(-9223372036854770000L);
        EncounterPoint point = point(original, 4.125f, false);
        new AbyssalRogueStellarObjectEPEC().createEncounter(null, point);
        assertEquals(1, original.longDraws);
        assertEquals(Long.toString(original.seed), scene.systemMemory.get(CoopEncounterReplayBridge.SEED_KEY));
        assertEquals(point.id, scene.systemMemory.get(CoopEncounterReplayBridge.POINT_ID_KEY));
        assertEquals(Float.toString(point.loc.x), scene.systemMemory.get(CoopEncounterReplayBridge.POINT_X_KEY));
        assertEquals(Float.toString(point.loc.y), scene.systemMemory.get(CoopEncounterReplayBridge.POINT_Y_KEY));
        assertEquals("4.125", scene.systemMemory.get(CoopEncounterReplayBridge.DEPTH_KEY));
        assertEquals("true", scene.systemMemory.get(CoopEncounterReplayBridge.THREAT_ROLL_RAN_KEY));
        assertEquals(CoopEncounterReplayBridge.PARENT_CREATOR, scene.systemMemory.get(CoopEncounterReplayBridge.CREATOR_KEY));
        assertTrue(scene.systemMemory.values().stream().allMatch(String.class::isInstance));
        assertTrue(scene.tags.contains(Tags.SYSTEM_CAN_SPAWN_THREAT)); assertEquals(0, scene.sectorMemory.get(PITY_KEY));
        Random expected = new Random(original.seed); expected.nextFloat();
        assertEquals(expected.nextLong(), ((AbyssalEPData) point.custom).random.nextLong());
    }

    @Test void seededDireHintsGenerationRecordsTheEffectiveSeedRatherThanTheLaunchSeed() {
        System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, "MN-test-encounter");
        Scene scene = new Scene(); Global.setSector(scene.sector);
        SeedRandom original = new SeedRandom(999); EncounterPoint point = point(original, 2, true);
        long effective = CoopRandom.of("AbyssalRogueStellarObjectEPEC", point.id,
                Math.round(point.loc.x), Math.round(point.loc.y)).nextLong();
        new AbyssalRogueStellarObjectDireHintsEPEC().createEncounter(null, point);
        assertEquals(0, original.longDraws);
        assertEquals(Long.toString(effective), scene.systemMemory.get(CoopEncounterReplayBridge.SEED_KEY));
        assertEquals("false", scene.systemMemory.get(CoopEncounterReplayBridge.THREAT_ROLL_RAN_KEY));
        assertEquals(CoopEncounterReplayBridge.DIRE_HINTS_CREATOR, scene.systemMemory.get(CoopEncounterReplayBridge.CREATOR_KEY));
        assertEquals(new Random(effective).nextLong(), ((AbyssalEPData) point.custom).random.nextLong());
        assertEquals(0, scene.pityReads); assertEquals(0, scene.pityWrites);
    }

    @Test void replayUsesHostThreatStateDespiteDifferentNearestAndPityHistory() {
        for (boolean rollRan : List.of(false, true)) {
            for (boolean resolved : List.of(false, true)) {
                Scene scene = new Scene(); scene.rejectPityAccess = true; Global.setSector(scene.sector);
                SeedRandom original = new SeedRandom(999);
                EncounterPoint point = point(original, .5f, rollRan); // opposite local nearest decision
                CoopEncounterReplayBridge.beginReplay(55123, rollRan, resolved);
                try { new AbyssalRogueStellarObjectEPEC().createEncounter(null, point); }
                finally { CoopEncounterReplayBridge.endReplay(); }
                assertEquals(0, original.longDraws);
                assertTrue(scene.systemMemory.isEmpty(), "replay must not advertise itself as a new host capture");
                assertEquals(resolved, scene.tags.contains(Tags.SYSTEM_CAN_SPAWN_THREAT));
                assertEquals(0, scene.pityReads); assertEquals(0, scene.pityWrites);
                Random expected = new Random(55123); if (rollRan) expected.nextFloat();
                assertEquals(expected.nextLong(), ((AbyssalEPData) point.custom).random.nextLong());
            }
        }
    }

    @Test void unsupportedSubclassesKeepNativeRandomAndDoNotCreateRecipes() {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        SeedRandom original = new SeedRandom(91); EncounterPoint point = point(original, 2, true);
        AbyssalRogueStellarObjectEPEC custom = new AbyssalRogueStellarObjectEPEC() { };
        custom.createEncounter(null, point);
        assertSame(original, ((AbyssalEPData) point.custom).random);
        assertEquals(0, original.longDraws); assertTrue(scene.systemMemory.isEmpty());
        assertFalse(CoopEncounterReplayBridge.supportsCreator(custom.getClass().getName()));
    }

    @Test void nativeForkAddsNoSavedInstanceFieldsAndSpecialsRemainTheFinalGenerationCall() throws Exception {
        assertTrue(java.util.Arrays.stream(AbyssalRogueStellarObjectEPEC.class.getDeclaredFields())
                .allMatch(f -> Modifier.isStatic(f.getModifiers())), "saved native instances gain no fields");
        String source = Files.readString(Path.of("forks/com/fs/starfarer/api/impl/campaign/enc/AbyssalRogueStellarObjectEPEC.java"));
        String method = source.substring(source.indexOf("public void createEncounter("), source.indexOf("protected void addSpecials("));
        int specials = method.lastIndexOf("addSpecials(system, manager, point, data);");
        assertTrue(specials > 0); assertEquals(method.lastIndexOf(';'), method.indexOf(';', specials));
        String tail = method.substring(method.lastIndexOf("if (!coopReplay)"));
        assertTrue(tail.matches("(?s)if \\(!coopReplay\\)\\s*\\{\\s*system\\.addScript\\(new AbyssalLocationDespawner\\(system\\)\\);\\s*"
                + "addSpecials\\(system, manager, point, data\\);\\s*}\\s*}\\s*"));
    }

    private static EncounterPoint point(Random random, float depth, boolean nearest) {
        AbyssalEPData data = new AbyssalEPData(); data.random = random; data.depth = depth;
        data.nearest = nearest ? proxy(StarSystemAPI.class, Map.of()) : null;
        EncounterPoint point = new EncounterPoint("ep-exact-input", null, new Vector2f(-12345.375f, 23456.625f), "abyssal");
        point.custom = data; return point;
    }

    private static final class SeedRandom extends Random {
        final long seed; int longDraws;
        SeedRandom(long seed) { this.seed = seed; }
        @Override public long nextLong() { longDraws++; return seed; }
    }

    private static final class Scene {
        final Map<String, Object> sectorMemory = new HashMap<>(), systemMemory = new HashMap<>();
        final Set<String> tags = new HashSet<>(); final Vector2f location = new Vector2f();
        boolean rejectPityAccess; int pityReads, pityWrites;
        final MemoryAPI sectorMemoryAPI = (MemoryAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MemoryAPI.class},
                (p, m, a) -> {
                    if (a != null && a.length > 0 && PITY_KEY.equals(a[0])) {
                        if (rejectPityAccess) throw new AssertionError("replay accessed local pity counter");
                        if (m.getName().equals("getInt")) pityReads++;
                        if (m.getName().equals("set")) pityWrites++;
                    }
                    return switch (m.getName()) {
                        case "getInt" -> sectorMemory.get(a[0]) instanceof Number n ? n.intValue() : 0;
                        case "set" -> { sectorMemory.put((String) a[0], a[1]); yield null; }
                        default -> ProxyDefaults.defaultValue(m.getReturnType());
                    };
                });
        final SectorEntityToken center = proxy(SectorEntityToken.class, Map.of());
        final StarSystemAPI system = (StarSystemAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{StarSystemAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getMemoryWithoutUpdate" -> ApiProxies.memory(systemMemory);
                    case "addTag" -> { tags.add((String) a[0]); yield null; }
                    case "hasTag" -> tags.contains(a[0]); case "getLocation" -> location;
                    case "initNonStarCenter" -> center;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        final SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SectorAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "createStarSystem" -> system; case "getMemoryWithoutUpdate" -> sectorMemoryAPI;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (p, m, a) -> answers.containsKey(m.getName()) ? answers.get(m.getName()) : ProxyDefaults.defaultValue(m.getReturnType()));
    }
}
