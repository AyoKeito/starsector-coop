package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.abilities.GenerateSlipsurgeAbility;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamManager;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import coop.ambient.CoopSlipstreamFadeBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CoopSlipstreamWorldTest {
    private SettingsAPI previousSettings;

    @BeforeEach
    void pinnedNativeHooks() {
        previousSettings = Global.getSettings();
        Global.setSettings(proxy(SettingsAPI.class, (self, method, args) -> switch (method.getName()) {
            case "getVersionString" -> "Starsector 0.98a-RC8";
            default -> coop.testing.ProxyDefaults.defaultValue(method.getReturnType());
        }));
    }

    @AfterEach
    void clearSector() {
        CoopSlipstreamWorld.releaseControlled();
        Global.setSettings(previousSettings);
        Global.setSector(null);
    }

    @Test
    void hostCaptureClassifiesSeasonalStreamsAndSkipsDynamicAndDeadTerrain() {
        Fixture f = new Fixture();
        FakeTerrain seasonal = f.nativeStream("seasonal");
        seasonal.plugin.setFade(fade());
        FakeTerrain surge = f.nativeStream("surge");
        surge.scripts.add(new GenerateSlipsurgeAbility.SlipsurgeFadeInScript(surge.plugin));
        FakeTerrain dynamic = f.nativeStream("dynamic");
        dynamic.plugin.setDynamic(true);
        FakeTerrain dead = f.nativeStream("dead");
        dead.alive = false;
        TestManager manager = new TestManager();
        manager.include(seasonal.plugin);
        manager.include(dead.plugin);
        f.scripts.add(manager);

        List<CoopSlipstreamRecord> records = f.world.captureHost();

        assertEquals(List.of("seasonal", "surge"), records.stream().map(CoopSlipstreamRecord::hostId).toList());
        assertEquals(CoopSlipstreamRecord.Kind.SEASONAL, records.get(0).kind());
        assertEquals(fade(), records.get(0).fade());
        assertEquals(CoopSlipstreamRecord.Kind.OTHER, records.get(1).kind());
        assertTrue(records.get(1).unfinishedSurgeFadeIn());
    }

    @Test
    void upsertRestoresEngineStateAndPreservesTerrainIdentityAndDiscovery() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord source = record("host", false, 0);
        f.world.upsert(source);
        FakeTerrain replica = f.lastCreated;
        replica.plugin.getSegments().get(1).discovered = true;

        f.world.upsert(source);

        assertEquals(1, f.terrains.size());
        assertSame(replica.terrain, f.terrains.get(0));
        assertTrue(replica.plugin.getSegments().get(1).discovered);
        assertEquals(Set.of("host"), f.world.taggedIds());
        CoopSlipstreamWorld.TableRow row = f.world.captureTable().get(0);
        assertEquals(123L, row.campaignTimestamp());
        assertRecipe(source, row.stream());
        assertEquals(List.of(0f, 1000f, 1200f), row.brightness().stream()
                .map(CoopSlipstreamWorld.BrightnessSample::distance).toList());
        assertTrue(row.brightness().stream().allMatch(sample -> sample.brightness() == 0.375f),
                "bridge brightness uses the native result without multiplying bMult again");
        assertEquals(2, replica.plugin.recomputes);
        assertEquals(2, replica.plugin.encounterRecomputes);
        assertEquals(0.4f, replica.plugin.getSegments().get(0).fader.getBrightness());
        assertEquals(0.5f, replica.plugin.getSegments().get(0).bMult);
    }

    @Test
    void geometryChangeReplacesOwnedTerrainAndLeavesUntaggedGuestSurges() {
        Fixture f = new Fixture();
        FakeTerrain local = f.nativeStream("host");
        f.world.upsert(record("host", false, 0));
        FakeTerrain original = f.lastCreated;

        f.world.upsert(record("host", false, 50));

        assertEquals(2, f.terrains.size());
        assertTrue(f.terrains.contains(local.terrain));
        assertFalse(f.terrains.contains(original.terrain));
        assertNotSame(original.terrain, f.lastCreated.terrain);
        f.world.remove("host");
        f.world.remove("missing");
        assertEquals(List.of(local.terrain), f.terrains);
    }

    @Test
    void repeatedSurgeUpsertsDoNotRestartOrDuplicateTheNativeFadeInScript() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord source = record("surge", true, 0);
        f.world.upsert(source);
        FakeTerrain replica = f.lastCreated;
        assertEquals(1, replica.scripts.size());
        EveryFrameScript script = replica.scripts.get(0);
        assertInstanceOf(GenerateSlipsurgeAbility.SlipsurgeFadeInScript.class, script);
        assertEquals(0.4f, replica.plugin.getSegments().get(0).fader.getBrightness());

        f.world.upsert(source);

        assertEquals(List.of(script), replica.scripts);
        assertEquals(0.4f, replica.plugin.getSegments().get(0).fader.getBrightness(),
                "an upsert restores the received fader rather than rerunning the script constructor");
        CoopSlipstreamFadeBridge.unregister(replica.plugin);
        script.advance(1f); // Released native scripts resume normal solo advancement.
        assertTrue(script.isDone());
        replica.scripts.clear();
        f.world.upsert(source);
        assertEquals(1, replica.scripts.size(), "an explicit unfinished checkpoint restores its native sweep cursor");
    }

    @Test
    void unfinishedSurgeRestoresPartialFadersAndIntensityAfterNativeScriptConstruction() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord base = record("partway", true, 0);
        CoopSlipstreamRecord source = new CoopSlipstreamRecord(base.hostId(), base.kind(),
                base.params(), base.terrainTags(), List.of(
                new CoopSlipstreamRecord.Segment(0f, 0f, 500f, 0.23f,
                        new CoopSlipstreamRecord.Fader(0.25f, 0.2f, 3f,
                                CoopSlipstreamRecord.FaderState.IN, false, false)),
                new CoopSlipstreamRecord.Segment(600f, 0f, 550f, 0.57f,
                        new CoopSlipstreamRecord.Fader(0.67f, 0.4f, 2f,
                                CoopSlipstreamRecord.FaderState.OUT, true, false)),
                new CoopSlipstreamRecord.Segment(1200f, 0f, 600f, 0.91f,
                        new CoopSlipstreamRecord.Fader(0.9f, 0.8f, 1f,
                                CoopSlipstreamRecord.FaderState.IDLE, false, true))), base.fade(), true);

        f.world.upsert(source);

        assertRecipe(source, f.world.captureTable().get(0).stream());
        assertEquals(1, f.lastCreated.scripts.size());
        EveryFrameScript script = f.lastCreated.scripts.get(0);
        f.world.upsert(source);
        assertEquals(List.of(script), f.lastCreated.scripts);
        assertRecipe(source, f.world.captureTable().get(0).stream());
    }

    @Test
    void despawnRestoresOnlyDespawnStateAndMissingIdsAreNoOps() {
        Fixture f = new Fixture();
        f.world.upsert(record("host", false, 0));
        CoopSlipstreamRecord.Despawn despawn = new CoopSlipstreamRecord.Despawn(
                "host", new float[]{0.3f, 0.9f}, -0.4f, 20f, 5f);

        f.world.despawn(despawn);
        f.world.despawn(new CoopSlipstreamRecord.Despawn("missing", null, 0f, 0f, 0f));

        CoopSlipstreamRecord.Fade actual = f.world.captureTable().get(0).stream().fade();
        assertArrayEquals(fade().spawnNoise(), actual.spawnNoise());
        assertEquals(fade().spawnDays(), actual.spawnDays());
        assertEquals(fade().spawnElapsed(), actual.spawnElapsed());
        assertArrayEquals(despawn.noise(), actual.despawnNoise());
        assertEquals(despawn.delay(), actual.despawnDelay());
        assertEquals(despawn.days(), actual.despawnDays());
        assertEquals(despawn.elapsed(), actual.despawnElapsed());
        assertEquals(1, f.terrains.size());
    }

    @Test
    void seasonalSweepUsesManagerMembershipAndKeepsTaggedReplicasAndGuestSurges() {
        Fixture f = new Fixture();
        FakeTerrain seasonal = f.nativeStream("local-seasonal");
        FakeTerrain surge = f.nativeStream("local-surge");
        f.world.upsert(record("replica", false, 0));
        FakeTerrain replica = f.lastCreated;
        TestManager manager = new TestManager();
        manager.include(seasonal.plugin);
        manager.include(replica.plugin);
        f.scripts.add(manager);
        f.world.captureHost(); // Cache membership before the suppressor removes the manager.
        f.scripts.clear();

        f.world.sweepSeasonal();
        f.world.sweepSeasonal();

        assertEquals(Set.of(surge.terrain, replica.terrain), new LinkedHashSet<>(f.terrains));
    }

    @Test
    void savedTagsRebuildLookupAndChangingCampaignClearsSeasonalCache() {
        Fixture first = new Fixture();
        first.world.upsert(record("saved", false, 0));
        CampaignTerrainAPI saved = first.lastCreated.terrain;
        CoopSlipstreamWorld reloaded = new CoopSlipstreamWorld(() -> first.sector);
        reloaded.upsert(record("saved", false, 0));
        assertEquals(List.of(saved), first.terrains);
        assertEquals(Set.of("saved"), reloaded.taggedIds());

        FakeTerrain seasonal = first.nativeStream("seasonal");
        TestManager manager = new TestManager();
        manager.include(seasonal.plugin);
        first.scripts.add(manager);
        AtomicReference<SectorAPI> selected = new AtomicReference<>(first.sector);
        CoopSlipstreamWorld changing = new CoopSlipstreamWorld(selected::get);
        changing.captureHost();
        Fixture second = new Fixture();
        // Reusing a terrain object makes a stale membership cache observable in this fixture.
        second.terrains.add(seasonal.terrain);
        selected.set(second.sector);
        changing.sweepSeasonal();
        assertEquals(List.of(seasonal.terrain), second.terrains);
        assertTrue(changing.taggedIds().isEmpty());
    }

    @Test
    void unavailableEngineStateThrowsInsteadOfPublishingAnEmptyManifest() {
        assertThrows(IllegalStateException.class, () -> new CoopSlipstreamWorld(() -> null).captureHost());
        Fixture f = new Fixture();
        TestManager manager = new TestManager();
        manager.breakMembership();
        f.scripts.add(manager);
        assertThrows(IllegalStateException.class, f.world::captureHost);
    }

    @Test
    void defaultSeasonalTriggerReportsAnAbsentOrExhaustedConfigurationWithoutClaimingCreation() {
        Fixture f = new Fixture();
        TestManager manager = new TestManager();
        f.scripts.add(manager);

        CoopSlipstreamWorld.SeasonalAddResult absent = f.world.addSeasonal(false);
        assertTrue(absent.invoked());
        assertFalse(absent.created());
        assertFalse(absent.configPresent());
        assertEquals("config_absent", absent.reason());
        assertTrue(absent.newIds().isEmpty());

        manager.configure(route("A", true));
        CoopSlipstreamWorld.SeasonalAddResult exhausted = f.world.addSeasonal(false);
        assertFalse(exhausted.created());
        assertTrue(exhausted.configPresent());
        assertEquals(1, exhausted.configuredRoutes());
        assertEquals(0, exhausted.unusedBefore());
        assertEquals("routes_exhausted", exhausted.reason());
        assertFalse(f.world.addSeasonal(), "the compatibility return value means terrain was actually created");
        assertEquals(3, manager.addCalls);
    }

    @Test
    void defaultSeasonalTriggerDetectsOnlyGenuinelyNewLiveTerrainAndReportsRouteConsumption() {
        Fixture f = new Fixture();
        FakeTerrain existing = f.nativeStream("existing");
        TestManager manager = new TestManager();
        manager.include(existing.plugin);
        SlipstreamManager.StreamData unused = route("A", false);
        manager.configure(unused);
        f.scripts.add(manager);
        manager.onAdd = () -> {
            assertFalse(unused.wasUsed);
            manager.include(f.nativeStream("new-native").plugin);
            unused.wasUsed = true;
        };

        CoopSlipstreamWorld.SeasonalAddResult result = f.world.addSeasonal(false);

        assertTrue(result.created());
        assertFalse(result.rearmedUsedRoute());
        assertEquals(1, result.unusedBefore());
        assertEquals(0, result.unusedAfter());
        assertEquals(List.of("new-native"), result.newIds());
        assertEquals("created", result.reason());
        assertEquals(3, manager.lastMonth);
        assertThrows(UnsupportedOperationException.class, () -> result.newIds().clear());
    }

    @Test
    void defaultTriggerCanDistinguishANativeBuilderDiscardFromExhaustedRoutes() {
        Fixture f = new Fixture();
        TestManager manager = new TestManager();
        manager.configure(route("A", false));
        f.scripts.add(manager);

        CoopSlipstreamWorld.SeasonalAddResult result = f.world.addSeasonal(false);

        assertFalse(result.created());
        assertEquals(1, result.unusedBefore());
        assertEquals(1, result.unusedAfter());
        assertEquals("native_builder_returned_without_live_stream", result.reason());
    }

    @Test
    void rearmUsesExactlyOneDeterministicRouteAndRestoresItsFlagAfterSuccess() {
        Fixture f = new Fixture();
        f.nativeStream("host-surge"); // Non-seasonal terrain does not prohibit the explicit setup.
        TestManager manager = new TestManager();
        SlipstreamManager.StreamData z = route("Z", true);
        SlipstreamManager.StreamData a = route("A", true);
        manager.configure(z, a);
        manager.onAdd = () -> {
            assertTrue(z.wasUsed, "no unrelated route flag may change");
            assertFalse(a.wasUsed, "the lexically first used route is the sole candidate");
            manager.include(f.nativeStream("rearmed-native").plugin);
            // Deliberately leave this false to prove the wrapper's finally restores it.
        };
        f.scripts.add(manager);

        CoopSlipstreamWorld.SeasonalAddResult result = f.world.addSeasonal(true);

        assertTrue(result.created());
        assertTrue(result.rearmedUsedRoute());
        assertEquals("A", result.routeId());
        assertEquals(2, result.configuredRoutes());
        assertEquals(0, result.unusedBefore());
        assertEquals(0, result.unusedAfter());
        assertEquals(List.of("rearmed-native"), result.newIds());
        assertTrue(a.wasUsed);
        assertTrue(z.wasUsed);
        assertEquals(1, manager.addCalls);
    }

    @Test
    void rearmRefusesAnyRemainingLiveSeasonalStreamBeforeTouchingRouteFlags() {
        Fixture f = new Fixture();
        TestManager manager = new TestManager();
        SlipstreamManager.StreamData used = route("A", true);
        manager.configure(used);
        manager.include(f.nativeStream("old-seasonal").plugin);
        f.scripts.add(manager);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> f.world.addSeasonal(true));

        assertTrue(failure.getMessage().contains("live seasonal streams remain"));
        assertTrue(used.wasUsed);
        assertEquals(0, manager.addCalls);
    }

    @Test
    void rearmRefusesMissingEmptyAndUnexhaustedConfigurations() {
        Fixture f = new Fixture();
        TestManager manager = new TestManager();
        f.scripts.add(manager);
        assertThrows(IllegalStateException.class, () -> f.world.addSeasonal(true));
        manager.configure();
        assertThrows(IllegalStateException.class, () -> f.world.addSeasonal(true));
        SlipstreamManager.StreamData used = route("A", true);
        SlipstreamManager.StreamData unused = route("B", false);
        manager.configure(used, unused);
        assertThrows(IllegalStateException.class, () -> f.world.addSeasonal(true));
        assertTrue(used.wasUsed);
        assertFalse(unused.wasUsed);
        assertEquals(0, manager.addCalls);
    }

    @Test
    void rearmRestoresTheRouteWhenNativeCodeThrowsAnExceptionOrAnError() {
        for (boolean throwError : List.of(false, true)) {
            Fixture f = new Fixture();
            TestManager manager = new TestManager();
            SlipstreamManager.StreamData route = route("A", true);
            manager.configure(route);
            manager.onAdd = () -> {
                assertFalse(route.wasUsed);
                if (throwError) throw new AssertionError("native error");
                throw new IllegalStateException("native exception");
            };
            f.scripts.add(manager);
            if (throwError) assertThrows(AssertionError.class, () -> f.world.addSeasonal(true));
            else assertThrows(IllegalStateException.class, () -> f.world.addSeasonal(true));
            assertTrue(route.wasUsed);
            assertEquals(1, manager.addCalls);
        }
    }

    @Test
    void rearmRestoresTheRouteAndFailsLoudlyIfNativeGenerationProducesNothing() {
        Fixture f = new Fixture();
        TestManager manager = new TestManager();
        SlipstreamManager.StreamData route = route("A", true);
        manager.configure(route);
        f.scripts.add(manager);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> f.world.addSeasonal(true));

        assertTrue(failure.getMessage().contains("produced no new live slipstream"));
        assertTrue(route.wasUsed);
        assertEquals(1, manager.addCalls);
    }

    @Test
    void rearmIgnoresDeadMembershipButRequiresANewIdOnNewLiveNondynamicTerrain() {
        for (String kind : List.of("dead", "dynamic", "duplicate", "valid")) {
            Fixture f = new Fixture();
            FakeTerrain old = f.nativeStream("old");
            old.alive = false;
            TestManager manager = new TestManager();
            manager.include(old.plugin);
            SlipstreamManager.StreamData route = route("A", true);
            manager.configure(route);
            manager.onAdd = () -> {
                FakeTerrain created = f.nativeStream("duplicate".equals(kind) ? "old" : "new");
                if ("dead".equals(kind)) created.alive = false;
                if ("dynamic".equals(kind)) created.plugin.setDynamic(true);
                manager.include(created.plugin);
            };
            f.scripts.add(manager);
            if ("valid".equals(kind)) assertEquals(List.of("new"), f.world.addSeasonal(true).newIds());
            else assertThrows(IllegalStateException.class, () -> f.world.addSeasonal(true));
            assertTrue(route.wasUsed);
        }
    }

    @Test
    void debugSeasonalTriggerFailsClearlyWhenTheNativeManagerIsMissing() {
        Fixture f = new Fixture();
        assertThrows(IllegalStateException.class, () -> f.world.addSeasonal(false));
        assertFalse(f.world.addSeasonal());
    }

    @Test
    void hostCanonicalTicksAdoptGenuineDespawnCommandsWithoutChangingTheGridOrigin() {
        Fixture f = new Fixture();
        FakeTerrain stream = f.nativeStream("canonical-host");
        CoopSlipstreamWorld.tickControlled(f.sector, true, false);
        long origin = (long) stream.saved.get(CoopSlipstreamWorld.FADE_ORIGIN);
        f.timestamp.addAndGet(2 * CoopSlipstreamFadeDriver.QUANTUM_CAMPAIGN_MILLIS);
        CoopSlipstreamWorld.tickControlled(f.sector, true, false);
        assertEquals(2L, stream.saved.get(CoopSlipstreamWorld.FADE_TICK));

        stream.plugin.despawn(0f, 10f, new Random(7));
        CoopSlipstreamWorld.tickControlled(f.sector, true, false);
        assertEquals(0f, f.world.captureTable().get(0).stream().fade().despawnElapsed());
        f.timestamp.addAndGet(CoopSlipstreamFadeDriver.QUANTUM_CAMPAIGN_MILLIS);
        CoopSlipstreamWorld.tickControlled(f.sector, true, false);
        CoopSlipstreamRecord captured = f.world.captureTable().get(0).stream();
        assertEquals(origin, captured.timeline().originCampaignMillis());
        assertEquals(3L, captured.timeline().committedTick());
        assertTrue(captured.fade().despawnElapsed() > 0f);
        assertTrue(stream.saved.values().stream().allMatch(v -> v instanceof String
                || v instanceof Number || v instanceof Boolean), "saved control metadata is primitive only");
    }

    @Test
    void aFutureRunningCheckpointIsDeferredUntilItsCommittedCampaignTick() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord base = record("future", false, 0);
        long future = f.timestamp.get() + 2 * CoopSlipstreamFadeDriver.QUANTUM_CAMPAIGN_MILLIS;
        CoopSlipstreamRecord source = new CoopSlipstreamRecord(base.hostId(), base.kind(), base.params(),
                base.terrainTags(), base.segments(), base.fade(),
                CoopSlipstreamRecord.Timeline.atOrigin(future, false, true), base.surgeWave());
        f.world.upsert(source);
        assertTrue(f.terrains.isEmpty());
        assertEquals(Set.of("future"), f.world.taggedIds());
        f.timestamp.set(future);
        CoopSlipstreamWorld.tickControlled(f.sector, false, false);
        assertEquals(1, f.terrains.size());
        assertEquals(source.fade(), f.world.captureTable().get(0).stream().fade());
        assertTrue(CoopSlipstreamFadeBridge.isControlled(f.lastCreated.plugin));
    }

    @Test
    void aPausedAuthoritativeCheckpointSurvivesCalendarCatchupAndReconnectGrace() {
        Fixture f = new Fixture();
        f.paused = true;
        CoopSlipstreamRecord base = record("paused", false, 0);
        long future = f.timestamp.get() + 2 * CoopSlipstreamFadeDriver.QUANTUM_CAMPAIGN_MILLIS;
        CoopSlipstreamRecord source = new CoopSlipstreamRecord(base.hostId(), base.kind(), base.params(),
                base.terrainTags(), base.segments(), base.fade(),
                CoopSlipstreamRecord.Timeline.atOrigin(future, true, true), base.surgeWave());
        f.world.upsert(source);
        FakeTerrain replica = f.lastCreated;
        f.timestamp.set(future + 4_320_000L);
        CoopSlipstreamWorld.tickControlled(f.sector, false, false);
        assertEquals(source.fade(), f.world.captureTable().get(0).stream().fade());
        assertEquals(0L, replica.saved.get(CoopSlipstreamWorld.FADE_TICK));
        CoopSlipstreamWorld.tickControlled(f.sector, false, true);
        assertTrue(CoopSlipstreamFadeBridge.isControlled(replica.plugin));
        assertEquals(source.segments(), f.world.captureTable().get(0).stream().segments());
    }

    @Test
    void loadedGridStaysRegisteredThroughThePreHandshakeLobbyAndSoloTeardownReleasesIt() {
        Fixture f = new Fixture();
        f.world.upsert(record("saved-grid", false, 0));
        FakeTerrain replica = f.lastCreated;
        f.timestamp.set(2 * CoopSlipstreamFadeDriver.QUANTUM_CAMPAIGN_MILLIS);
        CoopSlipstreamWorld.tickControlled(f.sector, false, false);
        Map<String, Object> savedMetadata = new HashMap<>(replica.saved);
        CoopSlipstreamWorld.releaseControlled();
        replica.saved.putAll(savedMetadata); // What deserialization restores on the native object.

        CoopSlipstreamWorld.prepareLoad(f.sector);
        assertTrue(CoopSlipstreamFadeBridge.isControlled(replica.plugin));
        CoopSlipstreamWorld.holdLoadedControlled(f.sector);
        assertEquals(savedMetadata.get(CoopSlipstreamWorld.FADE_ORIGIN),
                replica.saved.get(CoopSlipstreamWorld.FADE_ORIGIN));
        assertEquals(savedMetadata.get(CoopSlipstreamWorld.FADE_TICK),
                replica.saved.get(CoopSlipstreamWorld.FADE_TICK));

        CoopSlipstreamWorld.releaseControlled();
        assertFalse(CoopSlipstreamFadeBridge.isControlled(replica.plugin));
        assertFalse(replica.saved.containsKey(CoopSlipstreamWorld.FADE_ORIGIN));
        assertEquals("saved-grid", replica.saved.get(CoopSlipstreamWorld.STREAM_ID));
    }

    private static SlipstreamManager.StreamData route(String id, boolean used) {
        SlipstreamManager.StreamData route = new SlipstreamManager.StreamData(id);
        route.wasUsed = used;
        return route;
    }

    private static void assertRecipe(CoopSlipstreamRecord expected, CoopSlipstreamRecord actual) {
        assertEquals(expected, new CoopSlipstreamRecord(actual.version(), actual.hostId(), actual.kind(),
                actual.params(), actual.terrainTags(), actual.segments(), actual.fade(),
                expected.timeline(), actual.surgeWave()));
        assertEquals(expected.timeline().originCampaignMillis(), actual.timeline().originCampaignMillis());
        assertEquals(expected.timeline().committedTick(), actual.timeline().committedTick());
    }

    private static CoopSlipstreamRecord record(String id, boolean surge, float offset) {
        CoopSlipstreamRecord.Params params = new CoopSlipstreamRecord.Params("enter", 1.5f, true,
                "slipstream0", "slipstream1", "slipstream2", "slipstream_edge", 700f, 600f, 120f,
                9000f, 1400, 100f, 400f, 1.5f, 4f, 0.5f, 0.3f, 35, 40, false,
                0.8f, 1.6f, 1.2f, "Surge", 0.2f, -0.5f, 0.7f);
        CoopSlipstreamRecord.Fader fader = new CoopSlipstreamRecord.Fader(0.4f, 2f, 3f,
                CoopSlipstreamRecord.FaderState.OUT, true, false);
        return new CoopSlipstreamRecord(id, CoopSlipstreamRecord.Kind.OTHER, params,
                List.of("captured-tag"), List.of(
                new CoopSlipstreamRecord.Segment(offset, 0f, 500f, 0.5f, fader),
                new CoopSlipstreamRecord.Segment(offset + 600f, 0f, 550f, 0.8f, fader),
                new CoopSlipstreamRecord.Segment(offset + 1200f, 0f, 600f, 1f, fader)), fade(), surge);
    }

    private static CoopSlipstreamRecord.Fade fade() {
        return new CoopSlipstreamRecord.Fade(new float[]{0.1f, 0.7f}, 3f, 1.2f,
                new float[]{0.2f, 0.8f}, -0.3f, 10f, 2.5f);
    }

    private static final class Fixture {
        final List<CampaignTerrainAPI> terrains = new ArrayList<>();
        final List<EveryFrameScript> scripts = new ArrayList<>();
        final List<EveryFrameScript> locationScripts = new ArrayList<>();
        final AtomicLong timestamp = new AtomicLong(123L);
        boolean paused;
        final SectorAPI sector;
        final CoopSlipstreamWorld world;
        FakeTerrain lastCreated;

        Fixture() {
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getTimestamp" -> timestamp.get();
                case "getMonth" -> 3;
                case "convertToDays" -> (float) args[0] / 10f;
                case "convertToSeconds" -> (float) args[0] * 10f;
                default -> throw unexpected("clock", method.getName());
            });
            LocationAPI hyper = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getTerrainCopy" -> new ArrayList<>(terrains);
                case "getScripts" -> new ArrayList<>(locationScripts);
                case "addScript" -> { locationScripts.add((EveryFrameScript) args[0]); yield null; }
                case "removeScript" -> { locationScripts.remove(args[0]); yield null; }
                case "addTerrain" -> {
                    lastCreated = new FakeTerrain("guest_" + terrains.size(),
                            (SlipstreamTerrainPlugin2.SlipstreamParams2) args[1]);
                    terrains.add(lastCreated.terrain);
                    yield lastCreated.terrain;
                }
                case "removeEntity" -> {
                    terrains.remove(args[0]);
                    yield null;
                }
                default -> throw unexpected("hyperspace", method.getName());
            });
            sector = proxy(SectorAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getClock" -> clock;
                case "getHyperspace" -> hyper;
                case "getCurrentLocation" -> hyper;
                case "isPaused" -> paused;
                case "getScripts" -> new ArrayList<>(scripts);
                default -> throw unexpected("sector", method.getName());
            });
            Global.setSector(sector);
            world = new CoopSlipstreamWorld(() -> sector);
        }

        FakeTerrain nativeStream(String id) {
            FakeTerrain terrain = new FakeTerrain(id, new SlipstreamTerrainPlugin2.SlipstreamParams2());
            for (int i = 0; i < 3; i++) terrain.plugin.addSegment(new Vector2f(i * 600f, 0f), 600f);
            terrain.plugin.recompute();
            terrains.add(terrain.terrain);
            return terrain;
        }
    }

    private static final class FakeTerrain {
        final Map<String, Object> saved = new HashMap<>();
        final Set<String> tags = new LinkedHashSet<>();
        final List<EveryFrameScript> scripts = new ArrayList<>();
        final TestPlugin plugin = new TestPlugin();
        final CampaignTerrainAPI terrain;
        boolean alive = true;

        FakeTerrain(String id, SlipstreamTerrainPlugin2.SlipstreamParams2 params) {
            MemoryAPI memory = proxy(MemoryAPI.class, (self, method, args) -> switch (method.getName()) {
                case "get" -> saved.get(args[0]);
                case "set" -> {
                    saved.put((String) args[0], args[1]);
                    yield null;
                }
                case "unset" -> { saved.remove(args[0]); yield null; }
                default -> throw unexpected("memory", method.getName());
            });
            terrain = proxy(CampaignTerrainAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> id;
                case "getPlugin" -> plugin;
                case "isAlive" -> alive;
                case "getMemoryWithoutUpdate" -> memory;
                case "getTags" -> new ArrayList<>(tags);
                case "getScripts" -> new ArrayList<>(scripts);
                case "isInCurrentLocation" -> false;
                case "hasTag" -> tags.contains(args[0]);
                case "addTag" -> {
                    tags.add((String) args[0]);
                    yield null;
                }
                case "removeTag" -> {
                    tags.remove(args[0]);
                    yield null;
                }
                case "addScript" -> {
                    scripts.add((EveryFrameScript) args[0]);
                    yield null;
                }
                case "removeScript" -> { scripts.remove(args[0]); yield null; }
                default -> throw unexpected("terrain", method.getName());
            });
            plugin.init("slipstream", terrain, params);
        }
    }

    /** Bypasses render/settings work; the adapter still reads and writes the real protected fields. */
    private static final class TestPlugin extends SlipstreamTerrainPlugin2 {
        int recomputes;
        int encounterRecomputes;

        @Override
        public void recompute() {
            recomputes++;
            totalLength = 0;
            for (int i = 0; i < segments.size(); i++) {
                SlipstreamSegment segment = segments.get(i);
                segment.index = i;
                segment.totalLength = totalLength;
                segment.lengthToNext = i + 1 < segments.size()
                        ? Vector2f.sub(segments.get(i + 1).loc, segment.loc, null).length() : 0;
                totalLength += segment.lengthToNext;
            }
        }

        @Override public void recomputeEncounterPoints() {
            encounterRecomputes++;
        }

        @Override public float getFaderBrightness(float distance) {
            return 0.375f;
        }

        void setFade(CoopSlipstreamRecord.Fade fade) {
            spawnNoise = fade.spawnNoise();
            spawnDays = fade.spawnDays();
            spawnElapsed = fade.spawnElapsed();
            despawnNoise = fade.despawnNoise();
            despawnDelay = fade.despawnDelay();
            despawnDays = fade.despawnDays();
            despawnElapsed = fade.despawnElapsed();
        }
    }

    private static final class TestManager extends SlipstreamManager {
        int addCalls;
        int lastMonth;
        Runnable onAdd;

        @Override public void addStream(int month) {
            addCalls++;
            lastMonth = month;
            if (onAdd != null) onAdd.run();
        }

        void configure(StreamData... routes) {
            config = new StreamConfig("", new Random(1L));
            config.streams.addAll(List.of(routes));
        }

        void include(TestPlugin plugin) {
            active.add(new AddedStream(plugin));
        }

        void breakMembership() {
            active = null;
        }
    }

    private static AssertionError unexpected(String surface, String method) {
        return new AssertionError("Unexpected " + surface + " call: " + method);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> self == args[0];
                            case "hashCode" -> System.identityHashCode(self);
                            case "toString" -> type.getSimpleName() + " test proxy";
                            default -> throw unexpected("Object", method.getName());
                        };
                    }
                    return handler.invoke(self, method, args);
                }));
    }
}
