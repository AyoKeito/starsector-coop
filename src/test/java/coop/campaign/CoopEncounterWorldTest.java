package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalLocationDespawner;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.ambient.CoopEncounterReplayBridge;
import coop.fleet.CoopEntityIds;
import coop.fleet.CoopLocations;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static coop.campaign.CoopEncounterRecord.*;
import static org.junit.jupiter.api.Assertions.*;

class CoopEncounterWorldTest {
    private SectorAPI previousSector;
    private SettingsAPI previousSettings;
    private Scene scene;

    @BeforeEach void setup() {
        previousSector = Global.getSector(); previousSettings = Global.getSettings();
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getColor" -> Color.WHITE;
            case "getFloat" -> "unitsPerLightYear".equals(a[0]) ? 1000f : 0f;
            default -> fallback(p, m, a);
        }));
        scene = new Scene(); Global.setSector(scene.api); CoopLocations.invalidate();
    }

    @AfterEach void cleanup() {
        Global.setSector(previousSector); Global.setSettings(previousSettings); CoopLocations.invalidate();
    }

    @Test void capturesOriginalOrderHyperMembershipAndOnlyPrimitiveSavedRecipe() {
        CoopEncounterWorld world = scene.world();
        FakeLocation system = scene.generated("alpha");
        FakeEntity first = system.token("first");
        FakeEntity npc = system.entity(CampaignFleetAPI.class, "npc", null);
        FakeEntity second = system.planet("second");
        system.links(first);
        system.scripts.add(new AbyssalLocationDespawner(system.system()));

        CoopEncounterRecord record = only(world.captureHost());
        assertEquals(123456789L, record.effectiveSeed());
        assertEquals("native-point", record.encounterPointId());
        assertEquals(List.of(Role.SYSTEM, Role.SYSTEM, Role.ANCHOR, Role.JUMP_POINT, Role.WELL),
                record.entities().stream().map(Entity::role).toList());
        assertEquals("first", first.id); assertEquals("second", second.id);
        assertEquals(id("alpha", Role.SYSTEM, 0), CoopEntityIds.keyOf(first.api));
        assertEquals(id("alpha", Role.SYSTEM, 1), CoopEntityIds.keyOf(second.api));
        assertEquals("npc", npc.id);
        assertFalse(npc.memory.values.containsKey(CoopEncounterWorld.ENCOUNTER_ID_KEY));
        assertEquals("native-anchor", system.anchor.id);
        assertEquals(id("alpha", Role.ANCHOR, 0), CoopEntityIds.keyOf(system.anchor.api));
        assertTrue(scene.entities.values().stream().allMatch(e -> e.setIdCalls == 0));
        assertEquals(Set.of("alpha"), world.taggedIds());
        Object saved = system.memory.values.get(CoopEncounterWorld.RECIPE_KEY);
        assertInstanceOf(String.class, saved);
        assertEquals(record, CoopEncounterRecord.decode((String) saved));
        world.captureHost();
        assertEquals(1, system.scripts.size());
        assertInstanceOf(CoopAbyssalDespawner.class, system.scripts.get(0));
    }

    @Test void persistedOriginalMembershipNeverRenumbersAfterConsumptionOrReload() {
        CoopEncounterWorld world = scene.world(); FakeLocation system = scene.generated("alpha");
        FakeEntity first = system.token("first"); FakeEntity second = system.token("second");
        CoopEncounterRecord original = only(world.captureHost());
        system.remove(first.api);
        CoopEncounterRecord later = only(world.captureHost());
        assertEquals(original.entities(), later.entities());
        assertEquals(List.of(CoopEntityIds.keyOf(first.api)), later.overlay().consumedIds());
        assertEquals("second", second.id);
        assertEquals(id("alpha", Role.SYSTEM, 1), CoopEntityIds.keyOf(second.api));
        CoopEncounterRecord restored = only(scene.world().captureHost());
        assertEquals(later, restored);
    }

    @Test void metadataOnlySystemsAlreadyPresentAtInitializationRemainLegacy() {
        FakeLocation legacy = scene.generated("old"); FakeEntity old = legacy.token("keep-original");
        FakeLocation unsupported = scene.generated("custom"); unsupported.token("custom-original");
        unsupported.memory.set(CoopEncounterReplayBridge.CREATOR_KEY, "custom.EPEC");
        CoopEncounterWorld world = scene.world();
        assertTrue(world.captureHost().isEmpty());
        assertEquals("keep-original", old.id);
        assertFalse(legacy.memory.values.containsKey(CoopEncounterWorld.RECIPE_KEY));
        assertTrue(world.taggedIds().isEmpty());
    }

    @Test void compositeKeysDisambiguateRepeatedNativeCenterAndAnchorIdsWithoutGlobalLookup() {
        CoopEncounterWorld world = scene.world();
        FakeLocation a = scene.generated("alpha"); FakeEntity centerA = a.token("native-center"); a.links(centerA);
        FakeLocation b = scene.generated("beta"); FakeEntity centerB = b.token("native-center"); b.links(centerB);
        assertEquals(a.system().getId(), b.system().getId());
        List<CoopEncounterRecord> records = world.captureHost();
        assertEquals(List.of("alpha", "beta"), records.stream().map(CoopEncounterRecord::instanceId).toList());
        assertEquals(centerA.id, centerB.id); assertEquals(a.anchor.id, b.anchor.id);
        assertNotEquals(CoopEntityIds.keyOf(centerA.api), CoopEntityIds.keyOf(centerB.api));
        assertNotEquals(CoopEntityIds.keyOf(a.anchor.api), CoopEntityIds.keyOf(b.anchor.api));
        scene.rejectGlobalLookup = true;
        assertSame(centerA.api, CoopEntityIds.byId(scene.api, id("alpha", Role.SYSTEM, 0)));
        assertSame(centerB.api, CoopEntityIds.byId(scene.api, id("beta", Role.SYSTEM, 0)));
        assertSame(a.anchor.api, CoopEntityIds.byId(scene.api, id("alpha", Role.ANCHOR, 0)));
        assertSame(b.anchor.api, CoopEntityIds.byId(scene.api, id("beta", Role.ANCHOR, 0)));
        assertTrue(scene.entities.values().stream().allMatch(e -> e.setIdCalls == 0));
    }

    @Test void replayPreservesExistingEntityAndLocalDiscoveryWithoutRerunningGenerator() {
        AtomicInteger replays = new AtomicInteger();
        CoopEncounterWorld world = scene.world((sector, record) -> {
            replays.incrementAndGet(); FakeLocation s = scene.generated("random-native-id");
            s.token("base"); s.scripts.add(new AbyssalLocationDespawner(s.system())); return s.system();
        });
        CoopEncounterRecord record = simple("alpha", 1);
        world.upsert(record); FakeLocation system = scene.systems.get(0);
        SectorEntityToken token = system.entities.get(0); FakeEntity entity = scene.entity(token);
        entity.discovered = true;
        world.upsert(record);
        assertEquals(1, replays.get()); assertSame(token, system.entities.get(0));
        assertTrue(entity.discovered); assertTrue(system.scripts.isEmpty());
        assertEquals("alpha", system.uniqueId);
    }

    @Test void staleHostSetCannotRecreateLocallyConsumedMemberAndReloadDerivesPendingConsume() {
        CoopEncounterWorld world = scene.world((sector, record) -> {
            FakeLocation s = scene.generated("native"); s.token("a"); s.token("b"); return s.system();
        });
        CoopEncounterRecord original = simple("alpha", 2); world.upsert(original);
        FakeLocation system = scene.systems.get(0); SectorEntityToken consumed = system.entities.get(0);
        system.remove(consumed); world.upsert(original);
        assertEquals(1, system.entities.size());
        String consumedKey = CoopEntityIds.keyOf(consumed);
        assertEquals(Set.of(consumedKey), world.guestConsumedIds());
        CoopEncounterWorld restored = scene.world();
        assertEquals(Set.of(consumedKey), restored.guestConsumedIds());
        assertEquals(original.overlay().entities(), restored.guestBaselineStates());
        CoopEncounterRecord ack = original.withOverlay(new Overlay(List.of(consumedKey),
                original.overlay().entities().stream().filter(s -> !s.entityId().equals(consumedKey)).toList()));
        restored.upsert(ack);
        assertTrue(restored.guestConsumedIds().isEmpty());
    }

    @Test void capturesAndReplaysResolvedVambraceOrbitAndPlanetFlagsAfterBaseMembers() {
        CoopEncounterWorld host = scene.world(); FakeLocation nativeSystem = scene.generated("alpha");
        FakeEntity planet = nativeSystem.planet("planet");
        planet.memory.set("$abyssalPlanetoidMiningOp", true);
        FakeEntity vambrace = nativeSystem.entity(CustomCampaignEntityAPI.class, "vambrace", "derelict_vambrace");
        vambrace.focus = planet.api; vambrace.angle = 45; vambrace.radius = 321; vambrace.period = 17;
        CoopEncounterRecord record = only(host.captureHost());
        assertEquals(2, record.specials().size());
        scene = new Scene(); Global.setSector(scene.api);
        CoopEncounterWorld guest = scene.world((sector, recipe) -> {
            FakeLocation base = scene.generated("native"); base.planet("planet"); return base.system();
        });
        guest.upsert(record); FakeLocation replay = scene.systems.get(0);
        assertEquals(2, replay.entities.size());
        FakeEntity created = scene.entity(replay.entities.get(1));
        assertEquals("derelict_vambrace", created.customType);
        assertTrue(created.id.startsWith("native-minted-"));
        assertEquals(id("alpha", Role.SYSTEM, 1), CoopEntityIds.keyOf(created.api));
        assertEquals(0, created.setIdCalls);
        assertSame(replay.entities.get(0), created.focus);
        assertEquals(45, created.angle); assertEquals(321, created.radius); assertEquals(17, created.period);
        assertTrue(replay.entities.get(0).getMemoryWithoutUpdate().getBoolean("$abyssalPlanetoidMiningOp"));
    }

    @Test void ghostReplayRetainsNativeFinalPoseAtFocusAfterInstallingItsDifferentOrbitPoint() {
        FakeLocation system = scene.generated("alpha"); FakeEntity planet = system.planet("planet");
        FakeEntity ghost = system.entity(CustomCampaignEntityAPI.class, "ghost", "wreck");
        String focusKey = id("alpha", Role.SYSTEM, 0), ghostKey = id("alpha", Role.SYSTEM, 1);
        CoopWreckRecord captured = CoopWreckFixtures.sample(CoopEncounterRecord.wreckId(ghostKey));
        planet.position.set(captured.x(), captured.y()); planet.velocity.set(captured.vx(), captured.vy());
        CoopEncounterRecord.Orbit orbit = new CoopEncounterRecord.Orbit(focusKey, 90, 352, 29.6f);
        Special special = new Special(SpecialType.GHOST_WRECK, ghostKey, orbit, captured, List.of());

        CoopEncounterWorld.applySpecialOrbitAndPose(ghost.api, special, Map.of(focusKey, planet.api));

        assertSame(planet.api, ghost.focus);
        assertEquals(352, ghost.radius); assertEquals(90, ghost.angle); assertEquals(29.6f, ghost.period);
        assertEquals(captured.x(), ghost.position.x); assertEquals(captured.y(), ghost.position.y);
        assertEquals(captured.vx(), ghost.velocity.x); assertEquals(captured.vy(), ghost.velocity.y);
        assertEquals(planet.position.x, ghost.position.x); assertEquals(planet.position.y, ghost.position.y);
        assertNotEquals(planet.position.y + orbit.radius(), ghost.position.y,
                "native creation overrides the orbit-computed point until the first orbit update");

        FakeEntity vambrace = system.entity(CustomCampaignEntityAPI.class, "vambrace", "derelict_vambrace");
        CoopEncounterWorld.applySpecialOrbitAndPose(vambrace.api,
                new Special(SpecialType.VAMBRACE, ghostKey, orbit, null, List.of()), Map.of(focusKey, planet.api));
        assertEquals(planet.position.y + orbit.radius(), vambrace.position.y);
    }

    @Test void savedPendingGuestVisitSurvivesStaleSetAndMonotoneHostAckCanAdvanceSurvey() {
        CoopEncounterRecord original = planetRecipe("alpha");
        CoopEncounterWorld world = scene.world((sector, record) -> {
            FakeLocation s = scene.generated("native"); s.planet("planet"); return s.system();
        });
        world.upsert(original); FakeEntity planet = scene.entity(scene.systems.get(0).entities.get(0));
        planet.memory.set("$visited", true);
        world = scene.world(); // reconstruct pending edits from actual entity versus saved Host baseline
        world.upsert(original);
        assertTrue(planet.memory.api.getBoolean("$visited"));
        assertEquals(List.of(), only(world.guestBaselineStates()).memory());
        String entityId = CoopEntityIds.keyOf(planet.api);
        CoopEncounterRecord ack = original.withOverlay(new Overlay(List.of(), List.of(
                new EntityState(entityId, "FULL", true, List.of(flag("$visited", true))))));
        world.upsert(ack);
        assertEquals(MarketAPI.SurveyLevel.FULL, planet.market.survey);
        assertTrue(planet.market.conditionSurveyed);
        assertTrue(planet.market.memory.api.getBoolean("$ruinsExplored"));
        world.upsert(original);
        assertEquals(MarketAPI.SurveyLevel.FULL, planet.market.survey);
        assertTrue(planet.memory.api.getBoolean("$visited"));
        assertTrue(planet.market.memory.api.getBoolean("$ruinsExplored"));
    }

    @Test void entityStateAcceptsOnlyRecordedIdentityAndSurveyAndRuinsAreMonotone() {
        CoopEncounterWorld world = scene.world(); FakeLocation system = scene.generated("alpha");
        FakeEntity planet = system.planet("native"); only(world.captureHost());
        String key = CoopEntityIds.keyOf(planet.api);
        EntityState full = new EntityState(key, "FULL", true, List.of(flag("$visited", true)));
        CoopEncounterWorld.applyEntityState(scene.api, key, full);
        CoopEncounterWorld.applyEntityState(scene.api, key, new EntityState(key, "NONE", false, List.of()));
        assertEquals(MarketAPI.SurveyLevel.FULL, planet.market.survey);
        assertTrue(planet.market.conditionSurveyed);
        assertTrue(planet.market.memory.api.getBoolean("$ruinsExplored"));
        assertTrue(planet.memory.api.getBoolean("$visited"));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterWorld.applyEntityState(scene.api,
                id("beta", Role.SYSTEM, 0), full));
        EntityState unknown = new EntityState(id("alpha", Role.SYSTEM, 55), null, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterWorld.applyEntityState(scene.api, unknown.entityId(), unknown));
        assertTrue(scene.sectorMemory.values.isEmpty(), "shared state must not leak into sector memory");
    }

    @Test void oldSavedNamespacedEngineIdsStillResolveWithoutAnotherRename() {
        CoopEncounterWorld world = scene.world((sector, record) -> {
            FakeLocation system = scene.generated("native"); system.token("native-center"); return system.system();
        });
        CoopEncounterRecord record = simple("alpha", 1); world.upsert(record);
        FakeEntity old = scene.entity(scene.systems.get(0).entities.get(0));
        old.id = id("alpha", Role.SYSTEM, 0); // persisted state from the pre-fallback build
        old.memory.values.remove(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY);
        scene.rejectGlobalLookup = true;
        world = scene.world(); world.upsert(record);
        assertEquals(record.overlay().entities(), world.guestStates());
        assertTrue(world.guestConsumedIds().isEmpty());
        assertSame(old.api, CoopEntityIds.byId(scene.api, old.id));
        assertEquals(0, old.setIdCalls);
    }

    @Test void failedReplayCleansEveryNewSystemAndHyperLinkBeforeRetry() {
        AtomicInteger attempts = new AtomicInteger();
        CoopEncounterWorld world = scene.world((sector, record) -> {
            FakeLocation s = scene.generated("native"); FakeEntity token = s.token("a"); s.links(token);
            if (attempts.incrementAndGet() == 1) s.token("unexpected-member");
            return s.system();
        });
        CoopEncounterRecord record = withLinks(simple("alpha", 1));
        assertThrows(IllegalStateException.class, () -> world.upsert(record));
        assertTrue(scene.systems.isEmpty()); assertTrue(scene.hyper.entities.isEmpty());
        world.upsert(record);
        assertEquals(1, scene.systems.size()); assertEquals(3, scene.hyper.entities.size());
        assertEquals(Set.of("alpha"), world.taggedIds());
    }

    @Test void removingOccupiedGuestReplicaMovesPlayerToAnchorBeforeRemovingSystemAndLinks() {
        CoopEncounterWorld world = scene.world(); FakeLocation system = scene.generated("alpha");
        FakeEntity target = system.token("planet"); system.links(target); only(world.captureHost());
        system.anchor.position.set(222, -333);
        scene.player = system.entity(CampaignFleetAPI.class, "player", null); scene.current = system.api;
        world.remove("alpha");
        assertSame(scene.hyper.api, scene.player.location.api); assertSame(scene.hyper.api, scene.current);
        assertEquals(222, scene.player.position.x); assertEquals(-333, scene.player.position.y);
        assertTrue(scene.systems.isEmpty());
        assertEquals(List.of(scene.player.api), scene.hyper.entities);
    }

    @Test void nativeInteractionOrJumpDefersWholeSystemRemovalWithoutMovingPlayer() {
        CoopEncounterWorld world = scene.world(); FakeLocation system = scene.generated("alpha");
        FakeEntity target = system.token("planet"); only(world.captureHost());
        scene.player = system.entity(CampaignFleetAPI.class, "player", null);
        scene.dialogTarget = target.api;
        assertThrows(IllegalStateException.class, () -> world.remove("alpha"));
        assertSame(system.api, scene.player.location.api); assertEquals(1, scene.systems.size());
        scene.dialogTarget = null; scene.player.inTransition = true;
        assertThrows(IllegalStateException.class, () -> world.remove("alpha"));
        assertEquals(1, scene.systems.size());
        scene.player.inTransition = false; world.remove("alpha"); assertTrue(scene.systems.isEmpty());
    }

    @Test void consumedOverlayPreflightsAllTargetsBeforeAnyDeletion() {
        CoopEncounterWorld world = scene.world((sector, record) -> {
            FakeLocation s = scene.generated("native"); s.token("first"); s.token("second"); return s.system();
        });
        CoopEncounterRecord original = simple("alpha", 2); world.upsert(original);
        FakeLocation system = scene.systems.get(0); scene.dialogTarget = system.entities.get(1);
        CoopEncounterRecord consumed = original.withOverlay(new Overlay(
                original.entities().stream().map(original::entityId).toList(), List.of()));
        assertThrows(IllegalStateException.class, () -> world.upsert(consumed));
        assertEquals(2, system.entities.size());
        assertEquals(original, CoopEncounterRecord.decode((String) system.memory.values.get(CoopEncounterWorld.RECIPE_KEY)));
        scene.dialogTarget = null; world.upsert(consumed); assertTrue(system.entities.isEmpty());
    }

    @Test void bundledCurrentOrbitsCorrectProgressedOffscreenReplayWhilePausedAndExcludeNewDynamicEntities() {
        CoopEncounterWorld host = scene.world(); FakeLocation original = scene.generated("alpha");
        FakeEntity center = original.token("native-center"), planet = original.planet("native-planet");
        planet.api.setCircularOrbit(center.api, 42, 1500, 50);
        FakeEntity moon = original.planet("native-moon"); moon.api.setCircularOrbit(planet.api, 65, 100, 10);
        List<CoopEncounterRecord> recipes = host.captureHost();
        planet.api.setCircularOrbitAngle(145);
        moon.api.setCircularOrbitAngle(230);
        original.token("later-dynamic").api.setCircularOrbit(center.api, 20, 100, 10);
        Map<String, String> current = host.captureCurrentOrbits(recipes);
        List<CoopOrbitSync.OrbitEntry> phases = CoopOrbitSync.decode(current.get("alpha"));
        assertEquals(2, phases.size()); assertEquals(145, phases.get(0).angle());
        float expectedX = planet.position.x, expectedY = planet.position.y;
        float moonX = moon.position.x, moonY = moon.position.y;
        List<CoopOrbitSync.OrbitEntry> reversed = new ArrayList<>(phases); Collections.reverse(reversed);
        current = Map.of("alpha", CoopOrbitSync.encode(reversed));

        scene = new Scene(); Global.setSector(scene.api);
        CoopEncounterWorld guest = scene.world((sector, recipe) -> {
            FakeLocation replay = scene.generated("native"); FakeEntity focus = replay.token("different-center");
            FakeEntity parent = replay.planet("different-planet"); parent.api.setCircularOrbit(focus.api, 42, 1500, 50);
            replay.planet("different-moon").api.setCircularOrbit(parent.api, 65, 100, 10); return replay.system();
        });
        CoopEncounterAuthority authority = new CoopEncounterAuthority(new RecordingNetService(CoopConnectionRole.GUEST),
                TestSessions.activeGuestSession(), () -> 0, guest);
        authority.applySet(CoopEncounterCodec.encodeSet(recipes), CoopEncounterOrbitSetCodec.encode(current, recipes));
        FakeEntity replayedPlanet = scene.entity(scene.systems.get(0).entities.get(1));
        assertNull(scene.player, "no occupied-system orbit stream is available in this fixture");
        assertEquals(145, replayedPlanet.angle); assertEquals(expectedX, replayedPlanet.position.x);
        assertEquals(expectedY, replayedPlanet.position.y);
        FakeEntity replayedMoon = scene.entity(scene.systems.get(0).entities.get(2));
        assertEquals(230, replayedMoon.angle); assertEquals(moonX, replayedMoon.position.x); assertEquals(moonY, replayedMoon.position.y);
        assertEquals("different-planet", replayedPlanet.id);
        assertTrue(scene.api.isPaused());
    }

    private static CoopEncounterRecord simple(String instance, int count) {
        List<Entity> members = new ArrayList<>(); List<EntityState> states = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            members.add(new Entity(Role.SYSTEM, i, "token"));
            states.add(new EntityState(id(instance, Role.SYSTEM, i), null, null, List.of()));
        }
        return new CoopEncounterRecord(instance, 7, "native-point", 1200, -400, 0.8f, true,
                Creator.PARENT, false, members, List.of(), new Overlay(List.of(), states));
    }
    private static CoopEncounterRecord planetRecipe(String instance) {
        return new CoopEncounterRecord(instance, 7, "native-point", 1200, -400, 0.8f, true,
                Creator.PARENT, false, List.of(new Entity(Role.SYSTEM, 0, "planet:barren")), List.of(),
                new Overlay(List.of(), List.of(new EntityState(id(instance, Role.SYSTEM, 0), "NONE", false, List.of()))));
    }
    private static CoopEncounterRecord withLinks(CoopEncounterRecord base) {
        List<Entity> members = new ArrayList<>(base.entities());
        members.add(new Entity(Role.ANCHOR, 0, "token"));
        members.add(new Entity(Role.JUMP_POINT, 0, "jump_point"));
        members.add(new Entity(Role.WELL, 0, "well"));
        return new CoopEncounterRecord(base.instanceId(), base.effectiveSeed(), base.encounterPointId(), base.x(), base.y(),
                base.depth(), base.threatRollRan(), base.creator(), base.threatTag(), members, base.specials(), base.overlay());
    }
    private static String id(String instance, Role role, int ordinal) { return CoopEncounterRecord.entityId(instance, role, ordinal); }
    private static CoopWreckRecord.MemoryValue flag(String key, boolean value) {
        return new CoopWreckRecord.MemoryValue(key, "BOOLEAN", Boolean.toString(value), -1);
    }
    private static <T> T only(List<T> values) { assertEquals(1, values.size()); return values.get(0); }

    private static final class Scene {
        final List<FakeLocation> systems = new ArrayList<>();
        final IdentityHashMap<SectorEntityToken, FakeEntity> entities = new IdentityHashMap<>();
        final FakeLocation hyper = new FakeLocation(this, null);
        final Mem sectorMemory = new Mem();
        final SectorAPI api;
        FakeEntity player;
        SectorEntityToken dialogTarget;
        LocationAPI current = hyper.api;
        boolean rejectGlobalLookup;
        int mintedId;
        Scene() {
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getTimestamp" -> 86_400_000L;
                case "getElapsedDaysSince" -> 0f;
                default -> fallback(p, m, a);
            });
            InteractionDialogAPI dialog = proxy(InteractionDialogAPI.class, (p, m, a) ->
                    m.getName().equals("getInteractionTarget") ? dialogTarget : fallback(p, m, a));
            CampaignUIAPI ui = proxy(CampaignUIAPI.class, (p, m, a) ->
                    m.getName().equals("getCurrentInteractionDialog") ? (dialogTarget == null ? null : dialog) : fallback(p, m, a));
            api = proxy(SectorAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getStarSystems" -> systems.stream().map(FakeLocation::system).toList();
                case "getAllLocations" -> { List<LocationAPI> all = new ArrayList<>(); all.add(hyper.api); systems.forEach(s -> all.add(s.api)); yield all; }
                case "getHyperspace" -> hyper.api;
                case "getClock" -> clock;
                case "getCampaignUI" -> ui;
                case "getPlayerFleet" -> player == null ? null : player.api;
                case "getMemoryWithoutUpdate" -> sectorMemory.api;
                case "getCurrentLocation" -> current;
                case "isPaused" -> true;
                case "setCurrentLocation" -> { current = (LocationAPI) a[0]; yield null; }
                case "removeStarSystemNextFrame" -> { systems.removeIf(s -> s.api == a[0]); yield null; }
                case "getEntityById" -> {
                    if (rejectGlobalLookup) throw new ClassCastException("LocationToken cannot be cast to BaseCampaignEntity");
                    yield entities.values().stream().filter(e -> e.location != null && e.id.equals(a[0])).map(e -> e.api).findFirst().orElse(null);
                }
                default -> fallback(p, m, a);
            });
        }
        CoopEncounterWorld world() { return new CoopEncounterWorld(() -> api); }
        CoopEncounterWorld world(CoopEncounterWorld.ReplayGenerator generator) { return new CoopEncounterWorld(() -> api, generator); }
        FakeLocation generated(String id) {
            FakeLocation system = new FakeLocation(this, id); systems.add(system);
            system.memory.set(CoopEncounterReplayBridge.SEED_KEY, "123456789");
            system.memory.set(CoopEncounterReplayBridge.POINT_ID_KEY, "native-point");
            system.memory.set(CoopEncounterReplayBridge.POINT_X_KEY, "1200.0");
            system.memory.set(CoopEncounterReplayBridge.POINT_Y_KEY, "-400.0");
            system.memory.set(CoopEncounterReplayBridge.DEPTH_KEY, "0.8");
            system.memory.set(CoopEncounterReplayBridge.THREAT_ROLL_RAN_KEY, "true");
            system.memory.set(CoopEncounterReplayBridge.CREATOR_KEY, Creator.PARENT.className());
            return system;
        }
        FakeEntity entity(SectorEntityToken api) { return Objects.requireNonNull(entities.get(api)); }
    }

    private static final class FakeLocation {
        final Scene scene;
        final LocationAPI api;
        final Mem memory = new Mem();
        final List<SectorEntityToken> entities = new ArrayList<>();
        final List<EveryFrameScript> scripts = new ArrayList<>();
        final List<JumpPointAPI> jumps = new ArrayList<>();
        final List<NascentGravityWellAPI> wells = new ArrayList<>();
        final Vector2f position = new Vector2f(1200, -400);
        final Set<String> tags = new HashSet<>();
        String uniqueId;
        FakeEntity anchor;
        FakeLocation(Scene scene, String uniqueId) {
            this.scene = scene; this.uniqueId = uniqueId;
            if (uniqueId != null) tags.addAll(List.of(Tags.TEMPORARY_LOCATION, Tags.SYSTEM_ABYSSAL));
            Class<? extends LocationAPI> type = uniqueId == null ? LocationAPI.class : StarSystemAPI.class;
            api = proxy(type, (p, m, a) -> switch (m.getName()) {
                case "getId" -> "deep space";
                case "getOptionalUniqueId" -> this.uniqueId;
                case "setOptionalUniqueId" -> { this.uniqueId = (String) a[0]; yield null; }
                case "getMemoryWithoutUpdate" -> memory.api;
                case "getLocation" -> position;
                case "hasTag" -> tags.contains(a[0]);
                case "addTag" -> { tags.add((String) a[0]); yield null; }
                case "getAllEntities" -> entities;
                case "getScripts" -> scripts;
                case "addScript" -> { scripts.add((EveryFrameScript) a[0]); yield null; }
                case "removeScript" -> { scripts.remove(a[0]); yield null; }
                case "removeScriptsOfClass" -> { scripts.removeIf(((Class<?>) a[0])::isInstance); yield null; }
                case "getHyperspaceAnchor" -> anchor == null ? null : anchor.api;
                case "getAutogeneratedJumpPointsInHyper" -> jumps;
                case "getAutogeneratedNascentWellsInHyper" -> wells;
                case "getJumpPoints" -> entities.stream().filter(JumpPointAPI.class::isInstance).toList();
                case "getGravityWells" -> entities.stream().filter(NascentGravityWellAPI.class::isInstance).map(NascentGravityWellAPI.class::cast).toList();
                case "getDaysSinceLastPlayerVisit" -> 20f;
                case "removeEntity" -> { remove((SectorEntityToken) a[0]); yield null; }
                case "addEntity" -> { add(scene.entity((SectorEntityToken) a[0])); yield null; }
                case "addCustomEntity" -> entity(CustomCampaignEntityAPI.class, (String) a[0], (String) a[2]).api;
                default -> fallback(p, m, a);
            });
        }
        StarSystemAPI system() { return (StarSystemAPI) api; }
        FakeEntity token(String id) { return entity(SectorEntityToken.class, id, null); }
        FakeEntity planet(String id) { return entity(PlanetAPI.class, id, null); }
        FakeEntity entity(Class<? extends SectorEntityToken> type, String id, String customType) {
            FakeEntity e = new FakeEntity(scene, type, id == null ? "native-minted-" + ++scene.mintedId : id, customType); add(e); return e;
        }
        void add(FakeEntity e) {
            if (e.location != null) e.location.entities.remove(e.api);
            entities.add(e.api); e.location = this;
        }
        void remove(SectorEntityToken api) { entities.remove(api); scene.entity(api).location = null; }
        void links(FakeEntity destination) {
            anchor = scene.hyper.token("native-anchor");
            FakeEntity jump = scene.hyper.entity(JumpPointAPI.class, "native-jump", null);
            jump.destinations.add(new JumpPointAPI.JumpDestination(destination.api, "")); jumps.add((JumpPointAPI) jump.api);
            FakeEntity well = scene.hyper.entity(NascentGravityWellAPI.class, "native-well", null);
            well.target = destination.api; wells.add((NascentGravityWellAPI) well.api);
        }
    }

    private static final class FakeEntity {
        final SectorEntityToken api;
        final Mem memory = new Mem();
        final Vector2f position = new Vector2f();
        final Vector2f velocity = new Vector2f();
        final List<JumpPointAPI.JumpDestination> destinations = new ArrayList<>();
        final FakeMarket market;
        final String customType;
        String id;
        FakeLocation location;
        SectorEntityToken focus;
        SectorEntityToken target;
        float angle, radius, period;
        boolean discovered, inTransition;
        int setIdCalls;
        FakeEntity(Scene scene, Class<? extends SectorEntityToken> type, String id, String customType) {
            this.id = id; this.customType = customType; market = type == PlanetAPI.class ? new FakeMarket() : null;
            PlanetSpecAPI planetSpec = proxy(PlanetSpecAPI.class, (p, m, a) -> m.getName().equals("getPlanetType") ? "barren" : fallback(p, m, a));
            OrbitAPI nativeOrbit = proxy(OrbitAPI.class, (p, m, a) -> fallback(p, m, a));
            api = proxy(type, (p, m, a) -> switch (m.getName()) {
                case "getId" -> this.id;
                case "setId" -> { setIdCalls++; this.id = (String) a[0]; yield null; }
                case "getMemoryWithoutUpdate" -> memory.api;
                case "getContainingLocation" -> location == null ? null : location.api;
                case "getStarSystem" -> location != null && location.api instanceof StarSystemAPI ? location.api : null;
                case "getLocation" -> position;
                case "getVelocity" -> velocity;
                case "getLocationInHyperspace" -> location != null && location.api instanceof StarSystemAPI ? location.position : position;
                case "setLocation" -> { position.set((Float) a[0], (Float) a[1]); yield null; }
                case "getCustomEntityType" -> customType;
                case "getSpec" -> planetSpec;
                case "getMarket" -> market == null ? null : market.api;
                case "isAlive" -> true;
                case "isExpired" -> false;
                case "isDiscovered" -> discovered;
                case "isInHyperspaceTransition" -> inTransition;
                case "getDestinations" -> destinations;
                case "getTarget" -> target;
                case "getOrbitFocus" -> focus;
                case "getOrbit" -> focus == null ? null : nativeOrbit;
                case "getCircularOrbitAngle" -> angle;
                case "getCircularOrbitRadius" -> radius;
                case "getCircularOrbitPeriod" -> period;
                case "setOrbit" -> { focus = null; yield null; }
                case "setCircularOrbit" -> {
                    focus = (SectorEntityToken) a[0]; angle = (Float) a[1]; radius = (Float) a[2]; period = (Float) a[3];
                    position.set(focus.getLocation().x + (float) Math.cos(Math.toRadians(angle)) * radius,
                            focus.getLocation().y + (float) Math.sin(Math.toRadians(angle)) * radius);
                    yield null;
                }
                case "setCircularOrbitAngle" -> {
                    angle = (Float) a[0];
                    position.set(focus.getLocation().x + (float) Math.cos(Math.toRadians(angle)) * radius,
                            focus.getLocation().y + (float) Math.sin(Math.toRadians(angle)) * radius);
                    yield null;
                }
                default -> fallback(p, m, a);
            });
            scene.entities.put(api, this);
        }
    }

    private static final class FakeMarket {
        final Mem memory = new Mem();
        final MarketAPI api;
        MarketAPI.SurveyLevel survey = MarketAPI.SurveyLevel.NONE;
        boolean conditionSurveyed;
        FakeMarket() {
            MarketConditionAPI condition = proxy(MarketConditionAPI.class, (p, m, a) -> switch (m.getName()) {
                case "setSurveyed" -> { conditionSurveyed = (Boolean) a[0]; yield null; }
                case "isSurveyed" -> conditionSurveyed;
                default -> fallback(p, m, a);
            });
            api = proxy(MarketAPI.class, (p, m, a) -> switch (m.getName()) {
                case "getSurveyLevel" -> survey;
                case "setSurveyLevel" -> { survey = (MarketAPI.SurveyLevel) a[0]; yield null; }
                case "getMemoryWithoutUpdate" -> memory.api;
                case "hasCondition" -> Conditions.RUINS_SCATTERED.equals(a[0]);
                case "getConditions" -> List.of(condition);
                default -> fallback(p, m, a);
            });
        }
    }

    private static final class Mem {
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Float> expiry = new HashMap<>();
        final MemoryAPI api = proxy(MemoryAPI.class, (p, m, a) -> switch (m.getName()) {
            case "get" -> values.get(a[0]);
            case "set" -> { values.put((String) a[0], a[1]); if (a.length > 2) expiry.put((String) a[0], (Float) a[2]); yield null; }
            case "unset" -> { values.remove(a[0]); expiry.remove(a[0]); yield null; }
            case "contains" -> values.containsKey(a[0]);
            case "getBoolean" -> Boolean.TRUE.equals(values.get(a[0]));
            case "getExpire" -> expiry.getOrDefault(a[0], -1f);
            default -> fallback(p, m, a);
        });
        void set(String key, Object value) { values.put(key, value); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(CoopEncounterWorldTest.class.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object fallback(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + "@" + System.identityHashCode(proxy);
            default -> ProxyDefaults.defaultValue(method.getReturnType());
        };
    }
}
