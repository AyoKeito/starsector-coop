package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.abilities.GenerateSlipsurgeAbility;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamManager;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CoopSlipstreamWorldTest {
    @AfterEach
    void clearSector() {
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
        assertEquals(source, row.stream());
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
        script.advance(1f); // Outside the current location the native script completes in one tick.
        assertTrue(script.isDone());
        replica.scripts.clear();
        f.world.upsert(source);
        assertTrue(replica.scripts.isEmpty(), "a finished sweep must not restart on a repeated add");
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

        assertEquals(source, f.world.captureTable().get(0).stream(),
                "the constructor must not overwrite any transmitted live fader or bMult");
        assertEquals(1, f.lastCreated.scripts.size());
        EveryFrameScript script = f.lastCreated.scripts.get(0);
        f.world.upsert(source);
        assertEquals(List.of(script), f.lastCreated.scripts);
        assertEquals(source, f.world.captureTable().get(0).stream());
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
        final SectorAPI sector;
        final CoopSlipstreamWorld world;
        FakeTerrain lastCreated;

        Fixture() {
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getTimestamp" -> 123L;
                case "getMonth" -> 3;
                default -> throw unexpected("clock", method.getName());
            });
            LocationAPI hyper = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getTerrainCopy" -> new ArrayList<>(terrains);
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
