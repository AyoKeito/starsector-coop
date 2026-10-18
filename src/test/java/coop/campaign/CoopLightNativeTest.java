package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.CustomEntitySpecAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin.AbyssalLightParams;
import com.fs.starfarer.api.impl.campaign.BaseCustomEntityPlugin;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalLightDwellerEPEC;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalLightEPEC;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalLightEPEC.LightSpawnType;
import com.fs.starfarer.api.impl.campaign.enc.EncounterManager;
import com.fs.starfarer.api.impl.campaign.enc.EncounterPoint;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceAbyssPluginImpl.AbyssalEPData;
import coop.ambient.CoopLightBridge;
import coop.presence.CoopPresenceRegistry;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned creator/params/advance paths, with only renderer and engine containers stubbed. */
class CoopLightNativeTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;

    @BeforeEach void setup() {
        previousSettings = Global.getSettings(); previousSector = Global.getSector();
        Global.setSettings(stub(SettingsAPI.class, (method, args) -> switch (method) {
            case "getVersionString" -> CoopPresenceRegistry.PINNED_VERSION;
            case "getColor" -> java.awt.Color.WHITE;
            default -> null;
        }));
        CoopLightBridge.clear();
    }
    @AfterEach void cleanup() {
        CoopLightBridge.clear(); Global.setSettings(previousSettings); Global.setSector(previousSector);
    }

    @Test void everyNativeSpawnShapeRecreatesAllResolvedParamsAndPositions() {
        for (LightSpawnType type : LightSpawnType.values()) {
            Scene host = new Scene(); Global.setSector(host.sector);
            new ForcedCreator(type).createEncounter(null, point());
            assertTrue(type == LightSpawnType.CLUSTER ? host.lights.size() >= 3 && host.lights.size() <= 9
                    : host.lights.size() == (type == LightSpawnType.PAIR ? 2 : 1));
            List<CoopLightRecord> recipes = host.world.captureHost();
            Scene guest = new Scene(); Global.setSector(guest.sector);
            for (CoopLightRecord recipe : recipes) guest.world.upsert(recipe);
            assertEquals(recipes, guest.world.readOnlySnapshot(), type.toString());
            for (Light light : guest.lights) {
                assertEquals(100f * (1f + (light.params.bonusMult - 1f) * 0.33f), light.radius, 0.0001f);
                assertEquals(light.params.detectedRange, light.detected.getFlatBonus("gen").getValue());
            }
            if (type == LightSpawnType.PAIR) {
                assertEquals(recipes.get(0).remainingDays(), recipes.get(1).remainingDays());
                assertNotEquals(recipes.get(0).size(), recipes.get(1).size());
            }
            if (type == LightSpawnType.LARGE) assertTrue(recipes.get(0).remainingDays() >= 1000f);
        }
    }

    @Test void nativeDwellerCreatorPreservesTagSeedAndColorThroughDirectReplication() {
        Scene host = new Scene(); Global.setSector(host.sector);
        new AbyssalLightDwellerEPEC().createEncounter(null, point());
        CoopLightRecord recipe = host.world.captureHost().get(0);
        assertTrue(recipe.dweller()); assertEquals(new java.awt.Color(225, 200, 255, 255).getRGB(), recipe.colorArgb());
        Scene guest = new Scene(); Global.setSector(guest.sector); guest.world.upsert(recipe);
        assertEquals(recipe, guest.world.readOnlySnapshot().get(0));
        assertTrue(guest.lights.get(0).tags.contains(Tags.DWELLER_LIGHT));
    }

    @Test void guestAdvanceKeepsLifetimeButStillAdvancesNativeVisualPhase() throws Exception {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        Light light = scene.add("old-light", new AbyssalLightParams());
        light.params.durationDays = -1f;
        CoopLightBridge.setGuestMode(true);
        Field phase = field(AbyssalLightEntityPlugin.class, "phase");
        field(AbyssalLightEntityPlugin.class, "untilFrequencyChange").set(light.plugin, 1000f);
        light.plugin.advance(1f);
        assertEquals(-1f, light.params.durationDays);
        assertTrue((float) phase.get(light.plugin) > 0f);
        assertFalse(light.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
    }

    @Test void hostNativeLifetimeStillExpiresAndAddsTheOriginalFadeScript() {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        Light light = scene.add("expired", new AbyssalLightParams());
        light.params.durationDays = 0.25f;
        light.plugin.advance(1f);
        assertTrue(light.plugin.isDespawning());
        assertTrue(light.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
        assertTrue(light.tags.contains(Tags.NON_CLICKABLE));
    }

    @Test void nativeLightOutsideHyperspaceKeepsVanillaExpiryAndInteractionsOnGuest() {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        Light light = scene.add("system-light", new AbyssalLightParams()); light.inHyper = false;
        light.params.durationDays = 0.25f;
        CoopLightBridge.setGuestMode(true);
        assertFalse(CoopLightBridge.holdsGuestLifetime(light.entity));
        assertFalse(CoopLightBridge.handlePulse(light.entity, scene.actor(true, false), true));
        assertTrue(CoopLightBridge.allowDwellerEncounter(light.entity, scene.actor(false, false)));
        assertTrue(CoopLightBridge.allowDwellerDialog(light.entity, scene.actor(true, false)));
        light.plugin.advance(1f);
        assertTrue(light.plugin.isDespawning());
        assertTrue(scene.world.captureHost().isEmpty());
    }

    @Test void managedNativeLightMarksTheTemporaryEncounterButLegacyLightCannot() {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        Light light = scene.add("native", new AbyssalLightParams());
        CampaignFleetAPI fleet = scene.actor(false, false);
        CoopLightBridge.markEncounterFleet(fleet, light.entity);
        assertNull(fleet.getMemoryWithoutUpdate().get(CoopLightBridge.ENCOUNTER_FLEET_KEY));
        String id = scene.world.captureHost().get(0).id();
        CoopLightBridge.markEncounterFleet(fleet, light.entity);
        assertEquals(id, fleet.getMemoryWithoutUpdate().get(CoopLightBridge.ENCOUNTER_FLEET_KEY));
    }

    @Test void dwellerClaimWaitDoesNotExpireTheNativeThreeSecondScript() {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        Light light = scene.add("dweller", new AbyssalLightParams());
        int[] gates = {0};
        CoopLightBridge.install(true, new CoopLightBridge.Handler() {
            @Override public void requestPulse(SectorEntityToken ignored, boolean sensor) { fail("No pulse expected"); }
            @Override public void requestDwellerConsumption(SectorEntityToken ignored) { fail("No consumption expected"); }
            @Override public boolean allowDwellerDialog(SectorEntityToken ignored) { gates[0]++; return false; }
        });
        var script = new AbyssalLightEntityPlugin.DwellerEncounterScript("unused", scene.actor(true, false), light.entity);
        for (int i = 0; i < 40; i++) script.advance(0.25f);
        assertFalse(script.isDone()); assertTrue(gates[0] > 1);
    }

    @Test void nativeDwellerScriptNeverKillsAPlayerMirror() {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        Light light = scene.add("dweller", new AbyssalLightParams());
        var script = new AbyssalLightEntityPlugin.DwellerEncounterScript("unused", scene.actor(false, true), light.entity);
        script.advance(2.5f);
        assertTrue(script.isDone()); assertEquals(0, scene.actorDespawnCalls);
    }

    @Test void guestNativePulseCannotGrantOrConsumeBeforeAHandlerAndManifestExist() {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        Light light = scene.add("legacy", new AbyssalLightParams());
        CoopLightBridge.setGuestMode(true);
        assertTrue(CoopLightBridge.handlePulse(light.entity, scene.actor(true, false), true));
        assertFalse(CoopLightBridge.allowNativeDespawn(light.entity));
        assertTrue(scene.saved.isEmpty());
    }

    @Test void forkAddsNoSavedPluginOrDwellerScriptFields() {
        Set<String> pluginFields = new HashSet<>();
        Arrays.stream(AbyssalLightEntityPlugin.class.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                .forEach(f -> pluginFields.add(f.getName()));
        assertEquals(Set.of("glow", "phase", "frequencyMult", "flicker", "fader", "params", "untilFrequencyChange",
                "untilSoundPlayed", "flickerDur", "abilityResponseFlickerTimeout", "despawnType", "playedDespawnSound"), pluginFields);
        Set<String> scriptFields = new HashSet<>();
        Arrays.stream(AbyssalLightEntityPlugin.DwellerEncounterScript.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).forEach(f -> scriptFields.add(f.getName()));
        assertEquals(Set.of("elapsed", "trigger", "done", "triggerDelay", "light", "fleet"), scriptFields);
    }

    private static final class ForcedCreator extends AbyssalLightEPEC {
        final LightSpawnType type;
        ForcedCreator(LightSpawnType type) { this.type = type; }
        @Override protected LightSpawnType pickSpawnType(EncounterManager manager, EncounterPoint point) { return type; }
    }

    private static EncounterPoint point() {
        EncounterPoint point = new EncounterPoint("test-light", null, new Vector2f(0, 0), "ep_type_abyssal");
        AbyssalEPData data = new AbyssalEPData(); data.random = new Random(17); point.custom = data;
        return point;
    }

    private static final class Scene {
        final List<Light> lights = new ArrayList<>();
        final Map<String, Object> saved = new HashMap<>();
        final SectorAPI sector;
        final LocationAPI hyper;
        final LocationAPI system = stub(LocationAPI.class, (method, args) -> method.equals("isHyperspace") ? false : null);
        final CoopLightWorld world;
        int actorDespawnCalls;
        Scene() {
            hyper = stub(LocationAPI.class, (method, args) -> switch (method) {
                case "isHyperspace" -> true;
                case "getAllEntities" -> new ArrayList<>(lights.stream().map(l -> (SectorEntityToken) l.entity).toList());
                case "addCustomEntity" -> add(args[0] == null ? "native_" + lights.size() : (String) args[0], (AbyssalLightParams) args[4]).entity;
                default -> null;
            });
            MemoryAPI memory = ApiProxies.memory(saved);
            CampaignClockAPI clock = stub(CampaignClockAPI.class, (method, args) -> method.equals("convertToDays") ? args[0] : null);
            CampaignUIAPI ui = stub(CampaignUIAPI.class, (method, args) -> null);
            sector = stub(SectorAPI.class, (method, args) -> switch (method) {
                case "getHyperspace" -> hyper;
                case "getMemoryWithoutUpdate" -> memory;
                case "getClock" -> clock;
                case "getCampaignUI" -> ui;
                default -> null;
            });
            world = new CoopLightWorld(() -> sector);
        }
        Light add(String id, AbyssalLightParams params) {
            Light light = new Light(this, id, params); lights.add(light); return light;
        }
        CampaignFleetAPI actor(boolean player, boolean mirror) {
            Map<String, Object> values = new HashMap<>(); if (mirror) values.put("$coopMirrorFleet", "partner");
            MemoryAPI memory = ApiProxies.memory(values);
            return stub(CampaignFleetAPI.class, (method, args) -> switch (method) {
                case "getContainingLocation" -> hyper;
                case "getLocation" -> new Vector2f();
                case "getRadius" -> 10f;
                case "isPlayerFleet" -> player;
                case "getMemoryWithoutUpdate" -> memory;
                case "despawn" -> { actorDespawnCalls++; yield null; }
                default -> null;
            });
        }
    }

    private static final class Light {
        final AbyssalLightEntityPlugin plugin = new AbyssalLightEntityPlugin();
        final AbyssalLightParams params;
        final Map<String, Object> saved = new HashMap<>();
        final Set<String> tags = new HashSet<>();
        final Vector2f position = new Vector2f();
        final StatBonus detected = new StatBonus();
        final CustomCampaignEntityAPI entity;
        float radius = 100f;
        boolean inHyper = true;
        Light(Scene scene, String id, AbyssalLightParams params) {
            this.params = params;
            MemoryAPI memory = ApiProxies.memory(saved);
            CustomEntitySpecAPI spec = stub(CustomEntitySpecAPI.class, (method, args) -> method.equals("getDefaultRadius") ? 100f : null);
            entity = stub(CustomCampaignEntityAPI.class, (method, args) -> switch (method) {
                case "getId" -> id;
                case "getCustomPlugin" -> plugin;
                case "getMemoryWithoutUpdate" -> memory;
                case "getContainingLocation" -> inHyper ? scene.hyper : scene.system;
                case "getLocation" -> position;
                case "setLocation" -> { position.set((float) args[0], (float) args[1]); yield null; }
                case "getCustomEntitySpec" -> spec;
                case "getDetectedRangeMod" -> detected;
                case "getRadius" -> radius;
                case "setRadius" -> { radius = (float) args[0]; yield null; }
                case "hasTag" -> tags.contains(args[0]);
                case "addTag" -> { tags.add((String) args[0]); yield null; }
                case "removeTag" -> { tags.remove(args[0]); yield null; }
                case "isAlive" -> true;
                default -> null;
            });
            try {
                field(BaseCustomEntityPlugin.class, "entity").set(plugin, entity);
                field(AbyssalLightEntityPlugin.class, "params").set(plugin, params);
            } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @FunctionalInterface private interface Answer { Object get(String method, Object[] args); }
    private static <T> T stub(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> self == args[0]; case "hashCode" -> System.identityHashCode(self); default -> type.getSimpleName();
            };
            Object result = answer.get(method.getName(), args);
            return result == null ? ProxyDefaults.defaultValue(method.getReturnType()) : result;
        }));
    }
}
