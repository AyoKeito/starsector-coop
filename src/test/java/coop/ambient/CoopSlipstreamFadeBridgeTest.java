package coop.ambient;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.abilities.GenerateSlipsurgeAbility.SlipsurgeFadeInScript;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.fs.starfarer.api.util.FaderUtil;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against the real pinned forks, which Gradle puts before the bundled API jar. */
class CoopSlipstreamFadeBridgeTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;

    @BeforeEach
    void setup() {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        CoopSlipstreamFadeBridge.clear();
        setVersion("Starsector 0.98a-RC8");
    }

    @AfterEach
    void cleanup() {
        CoopSlipstreamFadeBridge.clear();
        Global.setSettings(previousSettings);
        Global.setSector(previousSector);
    }

    @Test
    void rawNativeAdvanceSkipsControlledFadeButStillRunsParticleWork() {
        Fixture f = new Fixture();
        f.plugin.spawn();
        CoopSlipstreamFadeBridge.register(f.plugin);

        f.plugin.advance(0.1f);

        assertEquals(0.2f, f.plugin.first().fader.getBrightness());
        assertEquals(0f, f.plugin.spawnElapsed());
        assertEquals(1, f.plugin.recomputes);
        assertEquals(1, f.plugin.particleAdds);
        assertEquals(1, f.plugin.particleAdvances);

        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.1f, true, true);

        assertEquals(0.3f, f.plugin.first().fader.getBrightness(), 0.000001f);
        assertEquals(0.01f, f.plugin.spawnElapsed(), 0.000001f);
        // Native order: fader used its old 1s duration before spawn chose its next duration.
        assertEquals(4.95f, f.plugin.first().fader.getDurationIn(), 0.000001f);
        assertEquals(1, f.plugin.particleAdvances, "canonical stepping must not repeat physics");
    }

    @Test
    void unregisterAndClearRestoreTheNativeFadePath() {
        Fixture f = new Fixture();
        f.plugin.spawn();
        CoopSlipstreamFadeBridge.register(f.plugin);
        CoopSlipstreamFadeBridge.unregister(f.plugin);
        f.plugin.advance(0.1f);
        assertEquals(0.3f, f.plugin.first().fader.getBrightness(), 0.000001f);
        assertEquals(0.01f, f.plugin.spawnElapsed(), 0.000001f);

        CoopSlipstreamFadeBridge.register(f.plugin);
        CoopSlipstreamFadeBridge.clear();
        assertFalse(CoopSlipstreamFadeBridge.isControlled(f.plugin));
        f.plugin.advance(0.1f);
        assertTrue(f.plugin.spawnElapsed() > 0.01f);
    }

    @Test
    void trailReplicaHoldsHostCheckpointWhileNativeParticlesKeepAdvancing() {
        Fixture f = new Fixture();
        f.plugin.setDynamic(true);
        f.plugin.spawn();
        CoopSlipstreamFadeBridge.registerTrailReplica(f.plugin);

        f.plugin.advance(0.1f);

        assertEquals(0.2f, f.plugin.first().fader.getBrightness());
        assertEquals(0f, f.plugin.spawnElapsed());
        assertEquals(1, f.plugin.recomputes);
        assertEquals(1, f.plugin.particleAdds);
        assertEquals(1, f.plugin.particleAdvances);
        assertFalse(CoopSlipstreamFadeBridge.isControlled(f.plugin));
        assertTrue(CoopSlipstreamFadeBridge.holdsNativeFade(f.plugin));
        CoopSlipstreamFadeBridge.unregister(f.plugin);
        f.plugin.advance(0.1f);
        assertEquals(0.3f, f.plugin.first().fader.getBrightness(), 0.000001f);
        assertTrue(f.plugin.spawnElapsed() > 0f);
    }

    @Test
    void trailReplicaWaitsForHostRemovalEvenAfterItsTerminalFade() {
        Fixture f = new Fixture();
        f.plugin.setDynamic(true);
        f.plugin.finishedDespawn();
        CoopSlipstreamFadeBridge.registerTrailReplica(f.plugin);

        f.plugin.advance(0.1f);

        assertTrue(f.scripts.isEmpty());
        assertFalse(f.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
        CoopSlipstreamFadeBridge.clear();
        f.plugin.advance(0.1f);
        assertEquals(1, f.scripts.size());
        assertTrue(f.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
    }

    @Test
    void nativeBackgroundFaderConditionUsesTheHostMode() {
        Fixture f = new Fixture();
        CoopSlipstreamFadeBridge.register(f.plugin);

        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.1f, false, false);
        assertEquals(0.2f, f.plugin.first().fader.getBrightness());
        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.1f, true, false);
        assertEquals(0.3f, f.plugin.first().fader.getBrightness(), 0.000001f);
    }

    @Test
    void controlledNativeSurgeScriptIgnoresRawAdvanceAndUsesHostLocationMode() {
        Fixture f = new Fixture();
        SlipsurgeFadeInScript wave = new SlipsurgeFadeInScript(f.plugin);
        f.scripts.add(wave);
        CoopSlipstreamFadeBridge.register(f.plugin);
        CoopSlipstreamFadeBridge.SurgeWaveState initial = CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin);

        wave.advance(1f);

        assertEquals(initial, CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin));
        // This guest is outside hyperspace; the host is in hyperspace, so keep the native wave.
        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.02f, true, false);
        CoopSlipstreamFadeBridge.SurgeWaveState advanced = CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin);
        assertFalse(advanced.done());
        assertEquals(0.02f, advanced.elapsed());

        // The native host-outside shortcut still fades all segments and finishes the wave.
        f.inCurrentLocation = true;
        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.02f, false, false);
        assertTrue(CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin).done());
        assertTrue(f.plugin.getSegments().stream().allMatch(s -> s.fader.getState() == FaderUtil.State.IN));
    }

    @Test
    void canonicalWaveRunsAfterTheFaders() {
        Fixture f = new Fixture();
        f.scripts.add(new SlipsurgeFadeInScript(f.plugin));
        f.plugin.first().fader.setBrightness(0.4f);
        f.plugin.first().fader.fadeIn();
        CoopSlipstreamFadeBridge.register(f.plugin);

        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.02f, true, false);

        assertEquals(0.5f, f.plugin.first().fader.getBrightness(), 0.000001f);
        assertEquals(0f, f.plugin.getSegments().get(1).fader.getBrightness(),
                "newly requested next-segment fade starts advancing on the next native step");
        assertEquals(FaderUtil.State.IN, f.plugin.getSegments().get(1).fader.getState());
    }

    @Test
    void unregisteredSurgeRetainsItsNativeOutsideLocationShortcut() {
        Fixture f = new Fixture();
        SlipsurgeFadeInScript wave = new SlipsurgeFadeInScript(f.plugin);

        wave.advance(1f);

        assertTrue(wave.isDone());
        assertTrue(f.plugin.getSegments().stream().allMatch(s -> s.fader.getState() == FaderUtil.State.IN));
    }

    @Test
    void restoresEntityOrHyperspaceNativeScriptCursorWithoutConstructorReset() {
        Fixture f = new Fixture();
        SlipsurgeFadeInScript wave = new SlipsurgeFadeInScript(f.plugin);
        f.hyperScripts.add(wave);
        CoopSlipstreamFadeBridge.SurgeWaveState cursor = new CoopSlipstreamFadeBridge.SurgeWaveState(
                true, false, 2, 0.875f, 8.5f);
        wave.coopRestoreFadeState(cursor);
        CoopSlipstreamFadeBridge.register(f.plugin);

        assertEquals(cursor, CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin));
        CoopSlipstreamFadeBridge.SurgeWaveState done = new CoopSlipstreamFadeBridge.SurgeWaveState(
                true, true, 3, 8.75f, 8.5f);
        CoopSlipstreamFadeBridge.restoreSurgeWave(f.plugin, done);
        f.hyperScripts.clear(); // model the engine pruning a completed native script
        assertEquals(done, CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin));

        CoopSlipstreamFadeBridge.restoreSurgeWave(f.plugin, cursor);

        assertEquals(List.of(wave), f.scripts, "rewinding an already pruned wave must reattach it");
        assertEquals(cursor, wave.coopCaptureFadeState());
    }

    @Test
    void installingMissingNativeWavePreservesEverySegmentFadeFieldAndBmult() {
        Fixture f = new Fixture();
        f.plugin.first().bMult = 0.73f;
        FaderUtil fader = f.plugin.first().fader;
        fader.setDurationIn(7f);
        fader.setDurationOut(11f);
        fader.setBounceDown(true);
        fader.setBounceUp(true);
        fader.fadeOut();
        CoopSlipstreamFadeBridge.SurgeWaveState cursor = new CoopSlipstreamFadeBridge.SurgeWaveState(
                true, false, 1, 0.4f, 6f);

        CoopSlipstreamFadeBridge.restoreSurgeWave(f.plugin, cursor);

        assertEquals(0.73f, f.plugin.first().bMult);
        assertSame(fader, f.plugin.first().fader);
        assertEquals(0.2f, fader.getBrightness());
        assertEquals(7f, fader.getDurationIn());
        assertEquals(11f, fader.getDurationOut());
        assertEquals(FaderUtil.State.OUT, fader.getState());
        assertTrue(fader.isBounceDown());
        assertTrue(fader.isBounceUp());
        assertEquals(cursor, CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin));
        assertEquals(1, f.scripts.size());
        assertInstanceOf(SlipsurgeFadeInScript.class, f.scripts.get(0));
    }

    @Test
    void absentWaveRemovesOnlyMatchingOwnerScriptsFromBothNativeLists() {
        Fixture f = new Fixture();
        Fixture other = new Fixture();
        SlipsurgeFadeInScript owned = new SlipsurgeFadeInScript(f.plugin);
        SlipsurgeFadeInScript unrelated = new SlipsurgeFadeInScript(other.plugin);
        f.scripts.add(owned);
        f.hyperScripts.add(owned);
        f.hyperScripts.add(unrelated);
        Global.setSector(f.sector);
        CoopSlipstreamFadeBridge.register(f.plugin);

        CoopSlipstreamFadeBridge.restoreSurgeWave(f.plugin, CoopSlipstreamFadeBridge.SurgeWaveState.absent());

        assertTrue(f.scripts.isEmpty());
        assertEquals(List.of(unrelated), f.hyperScripts);
        assertEquals(CoopSlipstreamFadeBridge.SurgeWaveState.absent(),
                CoopSlipstreamFadeBridge.captureSurgeWave(f.plugin));
    }

    @Test
    void hostCanonicalExpiryInstallsTheNativeExpiryScriptExactlyOnce() {
        Fixture f = new Fixture();
        f.plugin.finishedDespawn();
        CoopSlipstreamFadeBridge.register(f.plugin);

        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.01f, true, true);
        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.01f, true, true);
        f.plugin.advance(1f);

        assertEquals(1, f.scripts.size());
        assertTrue(f.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
        assertNull(f.plugin.despawnNoise());
        assertFalse(CoopSlipstreamFadeBridge.allowsNativeExpiry(f.plugin), "lifetime permission is step-local");
    }

    @Test
    void guestCanonicalExpiryClearsFadeNoiseButWaitsForHostRemoval() {
        Fixture f = new Fixture();
        f.plugin.finishedDespawn();
        CoopSlipstreamFadeBridge.register(f.plugin);

        CoopSlipstreamFadeBridge.advanceControlled(f.plugin, 0.01f, true, false);
        f.plugin.advance(1f);

        assertTrue(f.scripts.isEmpty());
        assertFalse(f.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
        assertNull(f.plugin.despawnNoise());
    }

    @Test
    void unregisteredNativeDespawnStillInstallsItsExpiryScript() {
        Fixture f = new Fixture();
        f.plugin.finishedDespawn();

        f.plugin.advance(0.01f);

        assertEquals(1, f.scripts.size());
        assertTrue(f.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
    }

    @Test
    void refusesDynamicWrongVersionAndInvalidStepOrCursorBeforeMutation() {
        Fixture f = new Fixture();
        f.plugin.setDynamic(true);
        assertThrows(IllegalArgumentException.class, () -> CoopSlipstreamFadeBridge.register(f.plugin));
        f.plugin.setDynamic(false);
        setVersion("0.99a-RC1");
        assertThrows(IllegalStateException.class, () -> CoopSlipstreamFadeBridge.register(f.plugin));
        assertFalse(CoopSlipstreamFadeBridge.isControlled(f.plugin));
        setVersion("0.98a-RC8");
        CoopSlipstreamFadeBridge.register(f.plugin);
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamFadeBridge.advanceControlled(f.plugin, Float.NaN, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamFadeBridge.advanceControlled(f.plugin, -1f, true, true));
        assertThrows(IllegalArgumentException.class, () -> CoopSlipstreamFadeBridge.restoreSurgeWave(f.plugin,
                new CoopSlipstreamFadeBridge.SurgeWaveState(true, false, 4, 0f, 4f)));
        assertThrows(IllegalArgumentException.class,
                () -> new CoopSlipstreamFadeBridge.SurgeWaveState(true, false, 0, Float.POSITIVE_INFINITY, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> new CoopSlipstreamFadeBridge.SurgeWaveState(false, false, 0, 0f, 0f));
        assertEquals(0.2f, f.plugin.first().fader.getBrightness());
        assertTrue(f.scripts.isEmpty());
    }

    @Test
    void duplicateNativeOwnerScriptsFailInsteadOfPickingAnArbitraryCursor() {
        Fixture f = new Fixture();
        f.scripts.add(new SlipsurgeFadeInScript(f.plugin));
        f.hyperScripts.add(new SlipsurgeFadeInScript(f.plugin));

        assertThrows(IllegalStateException.class, () -> CoopSlipstreamFadeBridge.register(f.plugin));
        assertFalse(CoopSlipstreamFadeBridge.isControlled(f.plugin));
    }

    @Test
    void pinnedNativeSaveFieldsContainNoBridgeState() {
        assertEquals("0.98a-RC8/canonical-fade-1", SlipstreamTerrainPlugin2.coopFadeHookVersion());
        Set<String> waveFields = new HashSet<>();
        for (var field : SlipsurgeFadeInScript.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) waveFields.add(field.getName());
        }
        assertEquals(Set.of("plugin", "done", "index", "elapsed", "maxElapsed"), waveFields);
        for (var field : SlipstreamTerrainPlugin2.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("coop."), field.toString());
            assertFalse(field.getName().startsWith("coop"), field.toString());
        }
    }

    private static void setVersion(String version) {
        // Native SlipstreamBuilder initializes Misc on its first noise interpolation. Its static
        // constants read these four settings accessors, so use the existing headless engine fixture
        // for those reads rather than poisoning Misc's class initializer for the entire worker.
        SettingsAPI nativeSettings = ApiProxies.whiteSettings();
        Global.setSettings(proxy(SettingsAPI.class, (self, method, args) -> switch (method.getName()) {
            case "getVersionString" -> version;
            case "getFloat", "getBoolean", "getColor", "getInt" -> method.invoke(nativeSettings, args);
            default -> throw new AssertionError("Unexpected settings call " + method.getName());
        }));
    }

    private static final class Fixture {
        final List<EveryFrameScript> scripts = new ArrayList<>();
        final List<EveryFrameScript> hyperScripts = new ArrayList<>();
        final Set<String> tags = new HashSet<>();
        final TestPlugin plugin = new TestPlugin();
        final SectorAPI sector;
        boolean inCurrentLocation;

        Fixture() {
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (self, method, args) -> switch (method.getName()) {
                case "convertToDays" -> ((Number) args[0]).floatValue() / 10f;
                case "convertToSeconds" -> ((Number) args[0]).floatValue() * 10f;
                default -> throw new AssertionError("Unexpected clock call " + method.getName());
            });
            LocationAPI hyper = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getScripts" -> new ArrayList<>(hyperScripts);
                case "removeScript" -> { hyperScripts.remove(args[0]); yield null; }
                case "getFleets" -> List.of();
                default -> throw new AssertionError("Unexpected location call " + method.getName());
            });
            sector = proxy(SectorAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getHyperspace" -> hyper;
                case "getClock" -> clock;
                case "getPlayerFleet" -> null;
                default -> throw new AssertionError("Unexpected sector call " + method.getName());
            });
            CampaignTerrainAPI terrain = proxy(CampaignTerrainAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getScripts" -> new ArrayList<>(scripts);
                case "addScript" -> { scripts.add((EveryFrameScript) args[0]); yield null; }
                case "removeScript" -> { scripts.remove(args[0]); yield null; }
                case "hasTag" -> tags.contains(args[0]);
                case "addTag" -> { tags.add((String) args[0]); yield null; }
                case "isInCurrentLocation" -> inCurrentLocation;
                case "getContainingLocation" -> hyper;
                default -> throw new AssertionError("Unexpected terrain call " + method.getName());
            });
            Global.setSector(sector);
            plugin.init("slipstream", terrain, new SlipstreamTerrainPlugin2.SlipstreamParams2());
            for (int i = 0; i < 3; i++) {
                SlipstreamTerrainPlugin2.SlipstreamSegment segment = new SlipstreamTerrainPlugin2.SlipstreamSegment();
                segment.index = i;
                segment.lengthToNext = 100f;
                segment.fader.setBrightness(0.2f);
                segment.fader.fadeIn();
                plugin.getSegments().add(segment);
            }
        }
    }

    private static final class TestPlugin extends SlipstreamTerrainPlugin2 {
        int recomputes;
        int particleAdds;
        int particleAdvances;

        @Override public void recomputeIfNeeded() { recomputes++; }
        @Override public void addParticles() { particleAdds++; }
        @Override public void advanceParticles(float amount) { particleAdvances++; }
        @Override protected boolean shouldCheckFleetsToApplyEffect() { return false; }

        SlipstreamSegment first() { return getSegments().get(0); }
        float spawnElapsed() { return spawnElapsed; }
        float[] despawnNoise() { return despawnNoise; }
        void spawn() { spawnNoise = new float[]{0f, 0f}; spawnDays = 1f; }
        void finishedDespawn() {
            despawnNoise = new float[]{0f, 0f};
            despawnDays = 0.1f;
            for (SlipstreamSegment segment : getSegments()) segment.fader.forceOut();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler behavior) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "test " + type.getSimpleName();
                    default -> throw new AssertionError(method.getName());
                };
            }
            return behavior.invoke(self, method, args);
        });
    }
}
