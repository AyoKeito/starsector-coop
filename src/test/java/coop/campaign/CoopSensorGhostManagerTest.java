package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ghosts.BaseSensorGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhostManager;
import com.fs.starfarer.api.impl.campaign.ghosts.types.NoGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.NoGhostCreator;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.util.TimeoutTracker;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import coop.presence.CoopPresenceRegistry;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoopSensorGhostManagerTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;
    private Scene scene;
    private String version;
    private List<SensorGhostCreator> originalCreators;

    @BeforeEach void setup() {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        originalCreators = new ArrayList<>(SensorGhostManager.CREATORS);
        version = "Starsector " + CoopPresenceRegistry.PINNED_VERSION;
        Global.setSettings(proxy(SettingsAPI.class, (name, arguments) -> switch (name) {
            case "getVersionString" -> version;
            case "getColor" -> Color.WHITE;
            default -> null;
        }));
        scene = new Scene();
        Global.setSector(scene.sector);
    }

    @AfterEach void cleanup() {
        SensorGhostManager.CREATORS.clear();
        SensorGhostManager.CREATORS.addAll(originalCreators);
        Global.setSector(previousSector);
        Global.setSettings(previousSettings);
    }

    @Test void guestReplacesNativeManagerAndDeletesItsStaleGhostEntities() {
        SensorGhostManager nativeManager = new SensorGhostManager();
        scene.persistent.add(nativeManager);
        scene.values.put("$ghostManager", nativeManager);
        CustomCampaignEntityAPI staleGhost = scene.entity(Entities.SENSOR_GHOST);
        CustomCampaignEntityAPI unrelated = scene.entity("abyssal_light");
        EveryFrameScript unrelatedScript = new NoOpScript();
        scene.persistent.add(unrelatedScript);

        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);

        assertNotNull(installed);
        assertEquals(List.of(unrelatedScript), scene.persistent);
        assertEquals(List.of(installed), scene.transientScripts);
        assertSame(installed, scene.values.get("$ghostManager"));
        assertTrue(CoopSensorGhostManager.ownsCachedHandle(scene.sector));
        assertEquals(List.of(staleGhost), scene.removedEntities);
        assertEquals(List.of(unrelated), scene.entities);
        assertEquals(originalCreators, SensorGhostManager.CREATORS);
    }

    @Test void hostAndUnconfiguredLoadsDoNotChangeNativeState() {
        SensorGhostManager nativeManager = new SensorGhostManager();
        scene.persistent.add(nativeManager);
        scene.values.put("$ghostManager", nativeManager);
        CustomCampaignEntityAPI ghost = scene.entity(Entities.SENSOR_GHOST);

        assertNull(CoopSensorGhostManager.install(scene.sector, false));

        assertEquals(List.of(nativeManager), scene.persistent);
        assertTrue(scene.transientScripts.isEmpty());
        assertSame(nativeManager, scene.values.get("$ghostManager"));
        assertEquals(List.of(ghost), scene.entities);
        assertEquals(originalCreators, SensorGhostManager.CREATORS);
    }

    @Test void sameSectorInstallIsIdempotentAndKeepsExistingCosmetics() {
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);
        NoGhost ghost = new NoGhost(installed);
        installed.getGhosts().add(ghost);
        SensorGhostManager lateNative = new SensorGhostManager();
        scene.persistent.add(lateNative);

        assertSame(installed, CoopSensorGhostManager.install(scene.sector, true));

        assertEquals(List.of(installed), scene.transientScripts);
        assertEquals(List.of(ghost), installed.getGhosts());
        assertFalse(scene.persistent.contains(lateNative));
    }

    @Test void hostOrUnconfiguredLoadRemovesOnlyOurSerializedLocalGhostEntities() {
        SensorGhostManager nativeManager = new SensorGhostManager();
        scene.persistent.add(nativeManager);
        CustomCampaignEntityAPI owned = scene.entity(Entities.SENSOR_GHOST);
        owned.addTag("coop_cosmetic_ghost");
        CustomCampaignEntityAPI nativeGhost = scene.entity(Entities.SENSOR_GHOST);
        CustomCampaignEntityAPI unrelated = scene.entity("abyssal_light");
        unrelated.addTag("coop_cosmetic_ghost");

        assertNull(CoopSensorGhostManager.install(scene.sector, false));

        assertEquals(List.of(owned), scene.removedEntities);
        assertEquals(List.of(nativeGhost, unrelated), scene.entities);
        assertEquals(List.of(nativeManager), scene.persistent);
    }

    @Test void versionMismatchRemovesUnsafeNativeManagerButInstallsNothing() {
        scene.persistent.add(new SensorGhostManager());
        scene.values.put("$ghostManager", scene.persistent.get(0));
        scene.entity(Entities.SENSOR_GHOST);
        version = "Starsector 0.99a";

        assertNull(CoopSensorGhostManager.install(scene.sector, true));

        assertTrue(scene.persistent.isEmpty());
        assertTrue(scene.transientScripts.isEmpty());
        assertFalse(scene.values.containsKey("$ghostManager"));
        assertTrue(scene.entities.isEmpty());
    }

    @Test void saveDetachKeepsManagerOutOfActualBundledXstreamMemoryGraph() {
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);
        installed.getGhosts().add(new NoGhost(installed));
        scene.values.put("ordinarySavedFlag", true);

        CoopSensorGhostManager.beforeSave(scene.sector);
        String xml = new XStream(new DomDriver()).toXML(scene.values);

        assertFalse(scene.values.containsKey("$ghostManager"));
        assertTrue(xml.contains("ordinarySavedFlag"));
        assertFalse(xml.contains(CoopSensorGhostManager.class.getName()));
        assertFalse(xml.contains("NoGhost"));
        assertEquals(List.of(installed), scene.transientScripts);
        CoopSensorGhostManager.afterSave(scene.sector);
        assertSame(installed, scene.values.get("$ghostManager"));
    }

    @Test void detachAlsoRemovesAnUnregisteredOrForeignOwnedStaleCachedManager() {
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);
        scene.transientScripts.clear();
        assertFalse(CoopSensorGhostManager.ownsCachedHandle(scene.sector));

        CoopSensorGhostManager.beforeSave(scene.sector);
        assertFalse(scene.values.containsKey("$ghostManager"));
        CoopSensorGhostManager.afterSave(scene.sector);
        assertFalse(scene.values.containsKey("$ghostManager"));
        scene.values.put("$ghostManager", installed);
        Scene another = new Scene();
        another.values.put("$ghostManager", installed);
        CoopSensorGhostManager.beforeSave(another.sector);
        assertFalse(another.values.containsKey("$ghostManager"));
    }

    @Test void ordinaryNativeCachedHandleIsNeverDetachedByCosmeticSaveHook() {
        SensorGhostManager nativeManager = new SensorGhostManager();
        scene.values.put("$ghostManager", nativeManager);

        CoopSensorGhostManager.beforeSave(scene.sector);
        CoopSensorGhostManager.afterSave(scene.sector);

        assertSame(nativeManager, scene.values.get("$ghostManager"));
    }

    @Test void nextAdvanceRestoresCacheEvenWhenSaveFailureSkippedAfterSave() {
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);
        CoopSensorGhostManager.beforeSave(scene.sector);

        installed.advance(0f);

        assertSame(installed, scene.values.get("$ghostManager"));
    }

    @Test void sectorReplacementOrUnregistrationCannotRebindAnOldManager() {
        CoopSensorGhostManager old = CoopSensorGhostManager.install(scene.sector, true);
        Scene replacement = new Scene();
        Global.setSector(replacement.sector);
        CoopSensorGhostManager current = CoopSensorGhostManager.install(replacement.sector, true);

        assertTrue(old.isDone());
        old.advance(0f);
        assertSame(current, replacement.values.get("$ghostManager"));
        replacement.transientScripts.remove(current);
        assertTrue(current.isDone());
        replacement.values.remove("$ghostManager");
        current.advance(0f);
        assertFalse(replacement.values.containsKey("$ghostManager"));
    }

    @Test void exactCosmeticListDoesNotAdmitGlobalModCreatorsOrSubclasses() {
        SensorGhostCreator dangerous = new BaseSensorGhostCreator() {
            @Override public List<SensorGhost> createGhost(SensorGhostManager manager) {
                fail("Unapproved global creator executed"); return null;
            }
        };
        SensorGhostManager.CREATORS.add(dangerous);
        List<SensorGhostCreator> global = new ArrayList<>(SensorGhostManager.CREATORS);
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);

        assertEquals(List.of("ChargerGhostCreator", "NoGhostCreator", "ZigguratGhostCreator",
                "AbyssalDrifterGhostCreator"), CoopSensorGhostManager.cosmeticCreatorNames());
        assertThrows(IllegalArgumentException.class, () -> installed.createCosmeticGhost(dangerous));
        assertThrows(IllegalArgumentException.class, () -> installed.createCosmeticGhost(new NoGhostCreator() {}));
        assertEquals(global, SensorGhostManager.CREATORS);
    }

    @Test void externallyInjectedUnsafeGhostIsRemovedBeforeAnyNativeAdvance() {
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);
        CustomCampaignEntityAPI entity = scene.entity(Entities.SENSOR_GHOST);
        SensorGhost dangerous = proxy(SensorGhost.class, (name, arguments) -> {
            if (name.equals("advance")) fail("Injected shared-state ghost advanced");
            return name.equals("getEntity") ? entity : null;
        });
        NoGhost cosmetic = new NoGhost(installed);
        installed.getGhosts().addAll(List.of(dangerous, cosmetic));

        installed.advance(0f);

        assertEquals(List.of(cosmetic), installed.getGhosts());
        assertEquals(List.of(entity), scene.removedEntities);
    }

    @Test void noGhostWeightUsesAllowedWeightAndNativeCoreAndBurstRules() {
        assertEquals(15f, CoopSensorGhostManager.noGhostWeight(false, 0f, 30f));
        assertEquals(165f, CoopSensorGhostManager.noGhostWeight(false, 1f, 30f));
        assertEquals(5f, CoopSensorGhostManager.noGhostWeight(false, 0f, 0f));
        assertEquals(0f, CoopSensorGhostManager.noGhostWeight(true, 1f, 300f));
    }

    @Test void successfulNativeNoGhostUsesNativeCooldownLifecycle() throws Exception {
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);

        installed.createCosmeticGhost(new NoGhostCreator());

        assertEquals(1, installed.getGhosts().size());
        assertEquals(NoGhost.class, installed.getGhosts().get(0).getClass());
        float timeout = (float) field("timeoutRemaining").get(installed);
        assertTrue(timeout >= 0f && (timeout <= 0.2f || timeout >= 10f && timeout <= 40f));
        assertEquals(0f, timeouts(installed).getRemaining("NoGhostCreator"));
        assertTrue(scene.entities.isEmpty(), "NoGhost must not create a world entity");
    }

    @Test void failedNativeCreatorReceivesBoundedPerCreatorRetryTimeout() throws Exception {
        CoopSensorGhostManager installed = CoopSensorGhostManager.install(scene.sector, true);
        scene.inHyperspace = false;

        installed.createCosmeticGhost(new NoGhostCreator());

        assertTrue(installed.getGhosts().isEmpty());
        float remaining = timeouts(installed).getRemaining("NoGhostCreator");
        assertTrue(remaining >= 0.8f && remaining <= 1.2f);
    }

    @SuppressWarnings("unchecked")
    private static TimeoutTracker<String> timeouts(SensorGhostManager manager) throws Exception {
        return (TimeoutTracker<String>) field("perCreatorTimeouts").get(manager);
    }

    private static Field field(String name) throws Exception {
        Field field = SensorGhostManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private interface Answers { Object answer(String name, Object[] arguments); }

    private static <T> T proxy(Class<T> type, Answers answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName() + " test proxy";
            Object value = answers.answer(method.getName(), args);
            return value == null ? ProxyDefaults.defaultValue(method.getReturnType()) : value;
        }));
    }

    private static final class Scene {
        final Map<String, Object> values = new HashMap<>();
        final List<EveryFrameScript> persistent = new ArrayList<>();
        final List<EveryFrameScript> transientScripts = new ArrayList<>();
        final List<CustomCampaignEntityAPI> entities = new ArrayList<>();
        final List<CustomCampaignEntityAPI> removedEntities = new ArrayList<>();
        boolean inHyperspace = true;
        final MemoryAPI memory = proxy(MemoryAPI.class, (name, args) -> switch (name) {
            case "get" -> values.get(args[0]);
            case "set" -> { values.put((String) args[0], args[1]); yield null; }
            case "unset" -> { values.remove(args[0]); yield null; }
            case "contains" -> values.containsKey(args[0]);
            default -> null;
        });
        final LocationAPI hyper = proxy(LocationAPI.class, (name, args) -> switch (name) {
            case "isHyperspace" -> inHyperspace;
            case "removeEntity" -> {
                CustomCampaignEntityAPI entity = (CustomCampaignEntityAPI) args[0];
                entities.remove(entity); removedEntities.add(entity); yield null;
            }
            default -> null;
        });
        final SectorAPI sector = proxy(SectorAPI.class, (name, args) -> switch (name) {
            case "getMemoryWithoutUpdate" -> memory;
            case "getScripts" -> persistent;
            case "getTransientScripts" -> transientScripts;
            case "getHyperspace", "getCurrentLocation" -> hyper;
            case "getEntitiesWithTag" -> entities.stream().filter(entity -> entity.hasTag((String) args[0])).toList();
            case "removeScript" -> { persistent.remove(args[0]); yield null; }
            case "removeTransientScript" -> { transientScripts.remove(args[0]); yield null; }
            case "addTransientScript" -> { transientScripts.add((EveryFrameScript) args[0]); yield null; }
            default -> null;
        });

        CustomCampaignEntityAPI entity(String type) {
            Set<String> tags = new HashSet<>(Set.of("ghost"));
            CustomCampaignEntityAPI entity = proxy(CustomCampaignEntityAPI.class, (name, args) -> switch (name) {
                case "getCustomEntityType" -> type;
                case "getContainingLocation" -> hyper;
                case "hasTag" -> tags.contains(args[0]);
                case "addTag" -> { tags.add((String) args[0]); yield null; }
                default -> null;
            });
            entities.add(entity);
            return entity;
        }
    }

    private static final class NoOpScript implements EveryFrameScript {
        @Override public void advance(float amount) {}
        @Override public boolean isDone() { return false; }
        @Override public boolean runWhilePaused() { return false; }
    }
}
