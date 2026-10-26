package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceAutomaton;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.loading.TerrainSpecAPI;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.io.xml.DomDriver;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import coop.presence.CoopPresenceRegistry;
import coop.storm.CoopStormBridge;
import coop.storm.CoopStormControl;
import coop.storm.CoopStormGrid;
import coop.storm.CoopStormSnapshot;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual pinned storm lifecycle, without the renderer or a running campaign. */
class CoopStormNativeTest {
    private SectorAPI oldSector;
    private SettingsAPI oldSettings;

    @BeforeEach void setup() {
        oldSector = Global.getSector(); oldSettings = Global.getSettings();
        TerrainSpecAPI terrainSpec = stub(TerrainSpecAPI.class, (name, args) ->
                name.equals("getCustom") ? new org.json.JSONObject() : null);
        Global.setSettings(stub(SettingsAPI.class, (name, args) -> switch (name) {
            case "getVersionString" -> CoopPresenceRegistry.PINNED_VERSION;
            case "getTerrainSpec" -> terrainSpec;
            default -> null;
        }));
        CoopStormBridge.clear(); CoopStormBridge.configureGuest(false);
    }

    @AfterEach void cleanup() {
        CoopStormBridge.clear(); CoopStormBridge.configureGuest(false);
        Global.setSector(oldSector); Global.setSettings(oldSettings);
    }

    @Test void guestAutomatonNeverEvolvesOrReseedsButHostKeepsNativeSwap() {
        HyperspaceAutomaton auto = new HyperspaceAutomaton(4, 4, 1.5f, 2.5f);
        CoopStormBridge.configureGuest(true);
        int[][] before = auto.getCells();
        auto.advance(100f);
        assertSame(before, auto.getCells()); assertEquals(0, auto.coopGeneration());
        byte[][] grid = new byte[4][4]; grid[2][1] = 2;
        auto.coopApply(grid, 7, 2f); auto.advance(100f);
        assertEquals(7, auto.coopGeneration()); assertEquals(2, auto.getCells()[2][1]);
        CoopStormBridge.configureGuest(false);
        auto.advance(3f);
        assertEquals(8, auto.coopGeneration()); assertNotSame(before, auto.getCells());
    }

    @Test void carriedSignalUsesHostTimeScaleAndAbsoluteClockCatchupOnlyOnce() {
        Scene s = new Scene(4, 4); CoopStormBridge.configureGuest(true);
        s.elapsedDays = 0.25f;
        s.terrain.coopApply(snapshot(4, 4, List.of(row(1, 1, 9, 2, 0, 3, 0, 4, 0, 5))));
        var cell = s.terrain.getActiveCells()[1][1];
        assertEquals(HyperspaceTerrainPlugin.CellState.SIGNAL, cell.state);
        assertEquals(1.75f, cell.signal);
        s.terrain.coopObserveGuest(); assertEquals(1.75f, cell.signal);
        s.elapsedDays = 0.5f; s.terrain.coopObserveGuest();
        assertEquals(0.5f, cell.signal);
        assertEquals(9, cell.coopEpoch);
        assertEquals(0, s.terrain.grid()[1][1], "A carried SIGNAL need not have a live grid cell");
    }

    @Test void guestWaningFlickerCannotRemoveCanonicalTrackerAndCompleteSetCan() {
        Scene s = new Scene(4, 4); CoopStormBridge.configureGuest(true);
        s.terrain.coopApply(snapshot(4, 4, List.of(row(1, 1, 12, 4, 0, -1, 0, 1, 2, 1))));
        s.elapsedDays = 100; s.terrain.coopObserveGuest();
        s.elapsedDays = 101; s.terrain.coopObserveGuest();
        assertNotNull(s.terrain.getActiveCells()[1][1]);
        assertEquals(HyperspaceTerrainPlugin.CellState.STORM_WANE, s.terrain.getActiveCells()[1][1].state);
        s.terrain.coopApply(snapshot(4, 4, List.of()));
        assertNull(s.terrain.getActiveCells()[1][1]);
    }

    @Test void guestSetTileStateIsSuppressedEvenBeforeHandlerInstallation() {
        Scene s = new Scene(4, 4); CoopStormBridge.configureGuest(true);
        s.terrain.coopApply(snapshot(4, 4, List.of(row(2, 2, 3, 1, 2, 1, 0, 1, 0, 1))));
        var original = s.terrain.getActiveCells()[2][2];
        s.terrain.setTileState(new Vector2f(), 1000, HyperspaceTerrainPlugin.CellState.SIGNAL, 0, 1);
        s.terrain.turnOffStorms(new Vector2f(), 1000);
        assertSame(original, s.terrain.getActiveCells()[2][2]);
        assertEquals(HyperspaceTerrainPlugin.CellState.WAIT, original.state);
    }

    @Test void separatedHostAndGuestWindowsAdvanceAndSaveWithoutDeletingPeerRegion() throws Exception {
        Scene s = new Scene(400, 4);
        s.hostPoint.x = -30_000; s.remotePoint.x = 30_000;
        installHost(s, 0, 1);
        s.terrain.put(50, 2, 10, 1); s.terrain.put(350, 2, 10, 1);
        s.terrain.step(1);
        assertEquals(9f, s.terrain.getActiveCells()[50][2].wait);
        assertEquals(9f, s.terrain.getActiveCells()[350][2].wait);
        Method save = HyperspaceTerrainPlugin.class.getDeclaredMethod("writeReplace"); save.setAccessible(true);
        assertSame(s.terrain, save.invoke(s.terrain));
        assertFalse(s.terrain.isTileVisible(350, 2), "The Guest row is outside Host's native viewport");
        assertNotNull(s.terrain.getActiveCells()[350][2], "Native save must retain the Guest window");
        assertEquals(2, s.terrain.savedRows(), "Offscreen Guest trackers must actually enter the native save list");
        CoopStormSnapshot result = s.terrain.coopCapture(100);
        assertEquals(2, result.trackers().size());
        assertTrue(result.trackers().stream().allMatch(t -> t.epoch() > 0));
    }

    @Test void overlappingWindowsAdvanceOnceAndReentryGetsANewNativeInstance() {
        Scene s = new Scene(400, 4); installHost(s, 0, 1);
        s.terrain.put(200, 2, 10, 1); s.terrain.step(1);
        var first = s.terrain.getActiveCells()[200][2];
        assertEquals(9f, first.wait);
        long epoch = first.coopEpoch;
        s.hostPoint.x = -30_000; s.remotePoint.x = -30_000; s.terrain.step(1);
        assertNull(s.terrain.getActiveCells()[200][2]);
        s.terrain.grid()[200][2] = 1; s.hostPoint.x = 0; s.remotePoint.x = 0; s.terrain.step(0);
        assertNotNull(s.terrain.getActiveCells()[200][2]);
        assertTrue(s.terrain.getActiveCells()[200][2].coopEpoch > epoch);
    }

    @Test void guestSurgeExpansionUsesFivefoldNativeTimeOutsideItsBaseWindow() {
        Scene s = new Scene(700, 4);
        s.hostPoint.x = -40_000; s.remotePoint.x = 40_000;
        installHost(s, 16_000, 5);
        s.terrain.put(650, 2, 20, 1); s.terrain.step(1);
        assertEquals(15f, s.terrain.getActiveCells()[650][2].wait);
        assertEquals(5f, s.terrain.getActiveCells()[650][2].coopTimeScale);
        assertEquals(5f, s.terrain.coopCapture(100).trackers().get(0).timeScale());
    }

    @Test void probesArePureAndUseGlobalCoordinatesAndRealRowFields() {
        Scene s = new Scene(4, 4); installHost(s, 0, 1);
        s.terrain.put(2, 2, 4, 1); s.terrain.step(0);
        CoopStormSnapshot before = s.terrain.coopCapture(100);
        Map<String, Object> first = s.terrain.coopDiagnostics("2,2", 1);
        assertEquals(first, s.terrain.coopDiagnostics("2,2", 1));
        assertEquals(before.revision(), first.get("revision"));
        assertEquals(64, ((String) first.get("gridHash")).length());
        assertThrows(IllegalArgumentException.class, () -> s.terrain.coopDiagnostics("2,2;2,2", 2));
        assertEquals(4f, s.terrain.getActiveCells()[2][2].wait);
    }

    @Test void pausedCaptureReusesGridAndSnapshotButNativeSameClockChangesStillPublish() {
        Scene s = new Scene(4, 4); installHost(s, 0, 1);
        s.terrain.put(2, 2, 4, 1); s.terrain.step(0);
        CoopStormSnapshot before = s.terrain.coopCapture(100);
        assertSame(before, s.terrain.coopCapture(100));
        s.terrain.setTileState(new Vector2f(), 1000, HyperspaceTerrainPlugin.CellState.SIGNAL, 0, 1);
        CoopStormSnapshot changed = s.terrain.coopCapture(100);
        assertEquals(before.revision() + 1, changed.revision());
        assertSame(before.encodedCells(), changed.encodedCells(), "No grid deflate while generation is unchanged");
        assertEquals(2, changed.trackers().get(0).state());
        assertSame(changed, s.terrain.coopCapture(100));
    }

    @Test void mirrorsNeverReceiveNativeStrikeBoostOrDamage() {
        Scene s = new Scene(4, 4);
        assertFalse(CoopStormBridge.allowStrikes(s.remote));
        assertTrue(CoopStormBridge.allowStrikes(s.player));
        CoopStormBridge.configureGuest(true);
        assertTrue(CoopStormBridge.allowStrikes(s.player));
        assertFalse(CoopStormBridge.allowStrikes(s.remote));
        s.terrain.strike(s.remote); // Returns before any fleet/script/roster mutation or engine access.
    }

    @Test void worldRequiresCurrentActorNativeShapeAndSourceBeforeApplyingClearing() {
        Scene s = new Scene(4, 4);
        CoopStormWorld world = new CoopStormWorld(() -> s.sector);
        world.install(new Object(), false, () -> s.remote);
        long revision = world.captureHost().revision();
        CoopStormControl window = CoopStormControl.surgeWindow(uuid(), revision, 0, 0, 16_000, 5);
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(window, ""));
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(
                CoopStormControl.surgeWindow(uuid(), revision, 10_000, 0, 16_000, 5), "Guest"));
        var off = CoopStormControl.tileOff(uuid(), revision, 0, 0, 1000, 1);
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(off, "Guest"));
        world.applyControl(window, "Guest"); assertEquals(16_000, CoopStormBridge.remoteExtraRadius());
        s.terrain.put(2, 2, 0, 1); s.terrain.getActiveCells()[2][2].state = HyperspaceTerrainPlugin.CellState.SIGNAL;
        world.applyControl(off, "Guest");
        assertEquals(HyperspaceTerrainPlugin.CellState.STORM_WANE, s.terrain.getActiveCells()[2][2].state);
        s.elapsedDays = 3f;
        assertEquals(0, CoopStormBridge.remoteExtraRadius());
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(off, "Guest"));
        world.uninstall();
    }

    @Test void guestRecurringControlsCoalesceDrainWindowFirstAndCannotMutateItsWeather() {
        Scene s = new Scene(4, 4);
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), true, () -> s.remote);
        world.applySnapshot(snapshot(4, 4, List.of(row(2, 2, 2, 2, 0, 1, 0, 1, 0, 1))));
        s.terrain.setTileState(new Vector2f(), 1000, HyperspaceTerrainPlugin.CellState.OFF, 1, -1);
        s.terrain.setTileState(new Vector2f(), 1000, HyperspaceTerrainPlugin.CellState.OFF, 0.5f, -1);
        s.terrain.setExtraDistanceAroundPlayerToAdvanceStormCells(16_000);
        s.terrain.setStormCellTimeMultOutsideBaseArea(5);
        s.terrain.coopObserveGuestWindow();
        List<CoopStormControl> controls = world.pendingControls();
        assertEquals(2, controls.size()); assertEquals(CoopStormControl.Action.SURGE_WINDOW, controls.get(0).action());
        assertEquals(0.5f, controls.get(1).waitDays()); assertTrue(world.pendingControls().isEmpty());
        assertEquals(HyperspaceTerrainPlugin.CellState.SIGNAL, s.terrain.getActiveCells()[2][2].state);
        s.terrain.setExtraDistanceAroundPlayerToAdvanceStormCells(0);
        s.terrain.setStormCellTimeMultOutsideBaseArea(0);
        s.terrain.coopObserveGuestWindow();
        List<CoopStormControl> clear = world.pendingControls();
        assertEquals(1, clear.size()); assertEquals(0f, clear.get(0).extraRadius()); assertEquals(1f, clear.get(0).timeScale());
        s.terrain.coopObserveGuestWindow(); assertTrue(world.pendingControls().isEmpty(), "One clear per native ending edge");
        world.uninstall(); assertTrue(CoopStormBridge.isGuest(), "Launch role survives adapter teardown");
    }

    @Test void surgeWindowClearDoesNotRejectRemainingLocalGeometryWeatherEffects() {
        Scene s = new Scene(4, 4);
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), false, () -> s.remote);
        long revision = world.captureHost().revision();
        world.applyControl(CoopStormControl.surgeWindow(uuid(), revision, 0, 0, 16_000, 5), "Guest");
        world.applyControl(CoopStormControl.surgeWindow(uuid(), revision, 0, 0, 0, 1), "Guest");
        assertEquals(0f, CoopStormBridge.remoteExtraRadius());
        var off = CoopStormControl.tileOff(uuid(), revision, 0, 0, 1000, 1);
        s.elapsedDays = 1.6f; assertDoesNotThrow(() -> world.applyControl(off, "Guest"));
        s.elapsedDays = 1.8f; assertThrows(IllegalArgumentException.class, () -> world.applyControl(off, "Guest"));
    }

    @Test void nativeTailSourceRestoresClearingAfterTransportResetAndOwnerReplacement() {
        Scene s = new Scene(4, 4);
        s.remotePoint.set(50, -75);
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), false, () -> s.remote);
        long revision = world.captureHost().revision();
        var source = CoopStormControl.surgeSource(uuid(), revision, 50, -75);
        var off = CoopStormControl.tileOff(uuid(), revision, 200, -100, 1000, 1);
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(off, "Guest"));
        assertAcceptedControls(world, 0, 0, 0, 0);
        world.applyControl(source, "Guest");
        s.terrain.put(2, 2, 0, 1); s.terrain.getActiveCells()[2][2].state = HyperspaceTerrainPlugin.CellState.SIGNAL;
        world.applyControl(off, "Guest");
        assertEquals(HyperspaceTerrainPlugin.CellState.STORM_WANE, s.terrain.getActiveCells()[2][2].state);
        assertEquals(0f, CoopStormBridge.remoteExtraRadius()); assertEquals(1f, CoopStormBridge.remoteTimeScale());
        assertAcceptedControls(world, 1, 0, 0, 1);
        Map<String, Object> accepted = world.diagnostics("", 1);
        assertEquals(100L, accepted.get("lastAcceptedSurgeSourceTimestamp"));
        assertEquals(50f, accepted.get("lastAcceptedSurgeSourceX")); assertEquals(-75f, accepted.get("lastAcceptedSurgeSourceY"));
        assertEquals(200f, accepted.get("lastAcceptedPrivateSurgeOffX")); assertEquals(-100f, accepted.get("lastAcceptedPrivateSurgeOffY"));
        assertEquals(1000f, accepted.get("lastAcceptedPrivateSurgeOffRadius"));
        assertEquals(true, accepted.get("surgeSourceCurrent")); assertEquals(false, accepted.get("surgeWindowCurrent"));
        assertEquals(0f, accepted.get("effectiveRemoteExtraRadius")); assertEquals(1f, accepted.get("effectiveRemoteTimeScale"));
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(
                CoopStormControl.surgeSource(uuid(), revision + 1, 50, -75), "Guest"));
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(
                CoopStormControl.tileOff(uuid(), revision, 40_000, 0, 1000, 1), "Guest"));
        assertAcceptedControls(world, 1, 0, 0, 1);
        world.resetTransport(); assertThrows(IllegalArgumentException.class, () -> world.applyControl(off, "Guest"));
        assertAcceptedControls(world, 0, 0, 0, 0);
        Map<String, Object> reset = world.diagnostics("", 1);
        assertEquals(0L, reset.get("lastAcceptedSurgeSourceTimestamp"));
        assertEquals(0f, reset.get("lastAcceptedSurgeSourceX")); assertEquals(0f, reset.get("lastAcceptedSurgeSourceY"));
        assertEquals(0f, reset.get("lastAcceptedPrivateSurgeOffX")); assertEquals(0f, reset.get("lastAcceptedPrivateSurgeOffY"));
        assertEquals(0f, reset.get("lastAcceptedPrivateSurgeOffRadius")); assertEquals(false, reset.get("surgeSourceCurrent"));
        world.applyControl(source, "Guest"); assertDoesNotThrow(() -> world.applyControl(off, "Guest"));
        assertAcceptedControls(world, 1, 0, 0, 1);
        world.install(new Object(), false, () -> s.remote);
        assertAcceptedControls(world, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(off, "Guest"));
        world.applyControl(source, "Guest"); assertDoesNotThrow(() -> world.applyControl(off, "Guest"));
        s.elapsedDays = 1.8f; assertThrows(IllegalArgumentException.class, () -> world.applyControl(off, "Guest"));
        assertAcceptedControls(world, 1, 0, 0, 1);
        assertEquals(false, world.diagnostics("", 1).get("surgeSourceCurrent"));
    }

    @Test void sourceAdvertisementNeitherChangesExistingWindowNorAdvancesWeatherAndIsActorBounded() {
        Scene s = new Scene(700, 4); s.hostPoint.x = -40_000; s.remotePoint.x = 40_000;
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), false, () -> s.remote);
        long revision = world.captureHost().revision(); s.terrain.put(650, 2, 20, 1);
        world.applyControl(CoopStormControl.surgeWindow(uuid(), revision, 40_000, 0, 16_000, 5), "Guest");
        var cell = s.terrain.getActiveCells()[650][2];
        world.applyControl(CoopStormControl.surgeSource(uuid(), revision, 40_000, 0), "Guest");
        assertEquals(16_000f, CoopStormBridge.remoteExtraRadius()); assertEquals(5f, CoopStormBridge.remoteTimeScale());
        assertAcceptedControls(world, 1, 1, 0, 0);
        Map<String, Object> active = world.diagnostics("", 1);
        assertEquals(true, active.get("surgeWindowCurrent"));
        assertEquals(16_000f, active.get("effectiveRemoteExtraRadius")); assertEquals(5f, active.get("effectiveRemoteTimeScale"));
        assertEquals(HyperspaceTerrainPlugin.CellState.WAIT, cell.state); assertEquals(20f, cell.wait);
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(CoopStormControl.surgeSource(uuid(), revision, 30_000, 0), "Guest"));
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(CoopStormControl.surgeSource(uuid(), revision, 40_000, 0), ""));
        assertAcceptedControls(world, 1, 1, 0, 0);
        world.install(new Object(), true, () -> s.remote);
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(CoopStormControl.surgeSource(uuid(), revision, 40_000, 0), "Guest"));
        assertAcceptedControls(world, 0, 0, 0, 0);
    }

    @Test void acceptedOffProbeSeparatesSharedGeometryFromPrivateSurgeClearing() {
        Scene s = new Scene(4, 4);
        SlipstreamTerrainPlugin2 stream = new SlipstreamTerrainPlugin2();
        var segment = new SlipstreamTerrainPlugin2.SlipstreamSegment();
        segment.width = 1288; segment.fader.setBrightness(1); stream.getSegments().add(segment);
        s.terrains.add(stub(CampaignTerrainAPI.class, (name, args) -> switch (name) {
            case "getPlugin" -> stream; case "isAlive" -> true; default -> null;
        }));
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), false, () -> s.remote);
        long revision = world.captureHost().revision();
        world.applyControl(CoopStormControl.tileOff(uuid(), revision, 0, 0, 1000, 1), "Guest");
        assertAcceptedControls(world, 0, 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> world.applyControl(
                CoopStormControl.tileOff(uuid(), revision, 0, 0, 3000, 1), "Guest"));
        assertAcceptedControls(world, 0, 0, 1, 0);
        assertEquals(false, world.diagnostics("", 1).get("surgeSourceCurrent"));
    }

    @Test void survivingNativeTailReadvertisesBeforeOffWithoutRestartingExpansion() throws Exception {
        Scene s = new Scene(4, 4);
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), true, () -> s.remote);
        world.applySnapshot(snapshot(4, 4, List.of(row(2, 2, 2, 2, 0, 1, 0, 1, 0, 1))));
        var nativeSource = privateNativeSurge(s, new HashMap<>(), true, true);
        for (int n = 0; n < 2; n++) {
            CoopStormBridge.observeNativeSurgeSource(nativeSource);
            s.terrain.setTileState(new Vector2f(), 1000, HyperspaceTerrainPlugin.CellState.OFF, 0.5f, -1);
        }
        List<CoopStormControl> controls = world.pendingControls();
        assertEquals(List.of(CoopStormControl.Action.SURGE_SOURCE, CoopStormControl.Action.TILE_OFF),
                controls.stream().map(CoopStormControl::action).toList());
        assertEquals(0f, controls.get(0).extraRadius()); assertEquals(1f, controls.get(0).timeScale());
        assertEquals(HyperspaceTerrainPlugin.CellState.SIGNAL, s.terrain.getActiveCells()[2][2].state);
        world.resetTransport(); world.install(new Object(), true, () -> s.remote);
        CoopStormBridge.observeNativeSurgeSource(nativeSource);
        s.terrain.setTileState(new Vector2f(), 1000, HyperspaceTerrainPlugin.CellState.OFF, 0.5f, -1);
        assertEquals(List.of(CoopStormControl.Action.SURGE_SOURCE, CoopStormControl.Action.TILE_OFF),
                world.pendingControls().stream().map(CoopStormControl::action).toList());
    }

    @Test void onlyLiveUntaggedNativePrivateSurgesCanAdvertiseTailSources() throws Exception {
        Scene s = new Scene(4, 4);
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), true, () -> s.remote);
        var tagged = privateNativeSurge(s, new HashMap<>(Map.of("$coopStreamId", "shared")), true, true);
        var other = privateNativeSurge(s, new HashMap<>(), true, true); other.getParams().enteringSlipstreamTextOverride = null;
        var dynamic = privateNativeSurge(s, new HashMap<>(), true, true); dynamic.setDynamic(true);
        var wrongProfile = privateNativeSurge(s, new HashMap<>(), true, true); wrongProfile.getParams().forceNoWindVisualEffectOnFleets = false;
        for (var source : List.of(tagged, other, dynamic, wrongProfile,
                privateNativeSurge(s, new HashMap<>(), false, true), privateNativeSurge(s, new HashMap<>(), true, false))) {
            CoopStormBridge.observeNativeSurgeSource(source); assertTrue(world.pendingControls().isEmpty());
        }
        var valid = privateNativeSurge(s, new HashMap<>(), true, true);
        world.install(new Object(), false, () -> s.remote); CoopStormBridge.observeNativeSurgeSource(valid);
        assertTrue(world.pendingControls().isEmpty());
        world.install(new Object(), true, () -> s.remote); CoopStormBridge.observeNativeSurgeSource(valid);
        assertEquals(CoopStormControl.Action.SURGE_SOURCE, world.pendingControls().get(0).action());
    }

    @Test void guestTrackerRefreshReusesSameGridButANewGenerationReplacesIt() {
        Scene s = new Scene(4, 4); CoopStormBridge.configureGuest(true);
        CoopStormSnapshot first = snapshot(4, 4, List.of(row(1, 1, 2, 2, 0, 1, 0, 1, 0, 1)));
        s.terrain.coopApply(first); int[][] grid = s.terrain.grid();
        CoopStormSnapshot refresh = new CoopStormSnapshot(2, 0, 100, 100, 2, 4, 4, first.encodedCells(),
                List.of(row(1, 1, 2, 2, 0, 0.25f, 0, 1, 0, 1)));
        s.terrain.coopApply(refresh);
        assertSame(grid, s.terrain.grid()); assertEquals(0.25f, s.terrain.getActiveCells()[1][1].signal);
        s.terrain.coopApply(new CoopStormSnapshot(3, 1, 100, 100, 2, 4, 4, first.encodedCells(), refresh.trackers()));
        assertNotSame(grid, s.terrain.grid());
    }

    @Test void pausedWindowControlCannotDrawWaneOrAdvanceAnUnrelatedHostStorm() {
        Scene s = new Scene(700, 4); s.hostPoint.x = -40_000; s.remotePoint.x = 40_000;
        s.terrain.put(150, 2, 0, 0);
        var cell = s.terrain.getActiveCells()[150][2]; cell.state = HyperspaceTerrainPlugin.CellState.STORM;
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), false, () -> s.remote);
        long revision = world.captureHost().revision();
        world.applyControl(CoopStormControl.surgeWindow(uuid(), revision, 40_000, 0, 16_000, 5), "Guest");
        world.applyControl(CoopStormControl.surgeWindow(uuid(), revision, 40_000, 0, 0, 1), "Guest");
        assertEquals(HyperspaceTerrainPlugin.CellState.STORM, cell.state);
        assertEquals(0f, cell.wane); assertEquals(0f, cell.signal);
    }

    @Test void missingTerrainIsUnavailableRatherThanAnEmptyAuthoritativeGrid() {
        Scene s = new Scene(4, 4); s.terrains.clear();
        CoopStormWorld world = new CoopStormWorld(() -> s.sector); world.install(new Object(), false, () -> s.remote);
        assertNull(world.captureHost()); assertEquals(false, world.diagnostics("", 1).get("available"));
        world.install(new Object(), true, () -> s.remote); assertDoesNotThrow(world::observeGuest);
        assertThrows(IllegalStateException.class, () -> world.applySnapshot(snapshot(4, 4, List.of())));
    }

    @Test void realXStreamRunsNativeAutomatonCallbacksAndReadsLegacyMissingMetadata() {
        HyperspaceAutomaton auto = new HyperspaceAutomaton(4, 4, 1.5f, 2.5f);
        byte[][] grid = new byte[4][4]; grid[1][2] = 2; grid[3][0] = 1;
        auto.coopApply(grid, 19, 2);
        XStream stream = new XStream(new DomDriver());
        String xml = stream.toXML(auto);
        assertTrue(xml.contains("<savedCells>"), "Package-private native writeReplace must encode transient cells");
        assertFalse(xml.contains("<cells>"));
        HyperspaceAutomaton restored = (HyperspaceAutomaton) stream.fromXML(xml);
        assertEquals(19, restored.coopGeneration());
        assertEquals(2, restored.getCells()[1][2]); assertEquals(1, restored.getCells()[3][0]);
        HyperspaceAutomaton legacy = (HyperspaceAutomaton) stream.fromXML(withoutCoopFields(xml));
        assertEquals(0, legacy.coopGeneration()); assertEquals(2, legacy.getCells()[1][2]);
        CoopStormBridge.configureGuest(false); legacy.advance(3);
        assertEquals(1, legacy.coopGeneration());
    }

    @Test void realXStreamPreservesNativePrivateTrackerMaximaAndPrimitiveEpoch() {
        var tracker = new HyperspaceTerrainPlugin.CellStateTracker(1, 2, 0, 4);
        tracker.state = HyperspaceTerrainPlugin.CellState.SIGNAL; tracker.signal = 1;
        tracker.coopEpoch = 17; tracker.coopTimeScale = 5;
        XStream stream = new XStream(new DomDriver()); String xml = stream.toXML(tracker);
        var restored = (HyperspaceTerrainPlugin.CellStateTracker) stream.fromXML(xml);
        assertEquals(0.75f, restored.getSignalBrightness());
        assertEquals(17, restored.coopEpoch); assertEquals(5, restored.coopTimeScale);
        restored.wane(3);
        restored = (HyperspaceTerrainPlugin.CellStateTracker) stream.fromXML(stream.toXML(restored));
        restored.advance(2.5f); assertEquals(2f / 3f, restored.getSignalBrightness(), 0.00001f);
        var legacy = (HyperspaceTerrainPlugin.CellStateTracker) stream.fromXML(withoutCoopFields(xml));
        assertEquals(0, legacy.coopEpoch); assertEquals(0, legacy.coopTimeScale);
        assertEquals(0.75f, legacy.getSignalBrightness(), "Existing native private fields are untouched");
    }

    @Test void realXStreamNativePluginRetainsOffscreenUnionAndOmitsRuntimeCaches() throws Exception {
        Scene s = new Scene(400, 4); s.hostPoint.x = -30_000; s.remotePoint.x = 30_000; installHost(s, 0, 1);
        s.terrain.put(50, 2, 4, 1); s.terrain.put(350, 2, 7, 1); s.terrain.step(0);
        CoopStormSnapshot captured = s.terrain.coopCapture(100);
        HyperspaceTerrainPlugin nativePlugin = nativeCopy(s.terrain);
        XStream stream = nativeStream(s); String xml = stream.toXML(nativePlugin);
        assertFalse(xml.contains("TestTerrain"), "Roundtrip uses the actual saved native classname");
        assertFalse(xml.contains("coopLastCapture")); assertFalse(xml.contains("coopPreviousRemote"));
        assertFalse(xml.contains("CoopStormBridge")); assertFalse(xml.contains("CoopStormWorld"));
        CoopStormBridge.clear(); // Real load can precede any session callback or remote mirror.
        HyperspaceTerrainPlugin restored = (HyperspaceTerrainPlugin) stream.fromXML(xml);
        assertEquals(HyperspaceTerrainPlugin.class, restored.getClass());
        assertNotNull(restored.getActiveCells()[350][2]);
        assertEquals(7f, restored.getActiveCells()[350][2].wait);
        assertEquals(captured.trackers().get(1).epoch(), restored.getActiveCells()[350][2].coopEpoch);
        String savedAgain = stream.toXML(restored);
        HyperspaceTerrainPlugin twice = (HyperspaceTerrainPlugin) stream.fromXML(savedAgain);
        assertNotNull(twice.getActiveCells()[350][2], "Saving before reconnect must retain peer-only phase");
        HyperspaceTerrainPlugin legacy = (HyperspaceTerrainPlugin) stream.fromXML(withoutCoopFields(xml));
        assertEquals(0, legacy.getActiveCells()[350][2].coopEpoch);
        installHost(s, 0, 1);
        CoopStormSnapshot adopted = legacy.coopCapture(100);
        assertTrue(adopted.trackers().stream().allMatch(row -> row.epoch() > 0));
    }

    @Test void realXStreamGuestProjectedCheckpointDoesNotApplyElapsedTimeTwice() throws Exception {
        Scene s = new Scene(4, 4); CoopStormBridge.configureGuest(true); s.elapsedDays = 0.25f;
        s.terrain.coopApply(snapshot(4, 4, List.of(row(1, 1, 2, 2, 0, 3, 0, 4, 0, 5))));
        XStream stream = nativeStream(s);
        String xml = stream.toXML(nativeCopy(s.terrain));
        CoopStormBridge.clear(); assertTrue(CoopStormBridge.isGuest());
        HyperspaceTerrainPlugin restored = (HyperspaceTerrainPlugin) stream.fromXML(xml);
        assertEquals(1.75f, restored.getActiveCells()[1][1].signal);
        restored.coopObserveGuest(); assertEquals(1.75f, restored.getActiveCells()[1][1].signal);
        s.elapsedDays = 0.5f; restored.coopObserveGuest();
        assertEquals(0.5f, restored.getActiveCells()[1][1].signal);
        restored.coopApply(snapshot(4, 4, List.of(row(1, 1, 2, 2, 0, 3, 0, 4, 0, 5))));
        assertEquals(0.5f, restored.getActiveCells()[1][1].signal, "A fresh baseline resets the saved projection cursor");
    }

    private static String withoutCoopFields(String xml) {
        return xml.replaceAll("(?s)\\s*<(coop[A-Za-z]+)>.*?</\\1>", "");
    }

    /** Only the engine entity container is converted; native weather classes use real XStream reflection. */
    private static XStream nativeStream(Scene scene) {
        XStream stream = new XStream(new DomDriver()) {
            @Override protected MapperWrapper wrapMapper(MapperWrapper next) {
                return new MapperWrapper(next) {
                    @Override public String serializedClass(Class type) {
                        return SectorEntityToken.class.isAssignableFrom(type) ? "storm-entity" : super.serializedClass(type);
                    }
                    @Override public Class realClass(String name) {
                        return name.equals("storm-entity") ? SectorEntityToken.class : super.realClass(name);
                    }
                };
            }
        };
        stream.registerConverter(new Converter() {
            public boolean canConvert(Class type) { return SectorEntityToken.class.isAssignableFrom(type); }
            public void marshal(Object value, HierarchicalStreamWriter writer, MarshallingContext context) {
                Vector2f point = ((SectorEntityToken) value).getLocation();
                writer.addAttribute("x", Float.toString(point.x)); writer.addAttribute("y", Float.toString(point.y));
            }
            public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
                Vector2f point = new Vector2f(Float.parseFloat(reader.getAttribute("x")), Float.parseFloat(reader.getAttribute("y")));
                return stub(SectorEntityToken.class, (name, args) -> name.equals("getLocation") ? point : null);
            }
        }, XStream.PRIORITY_VERY_HIGH);
        return stream;
    }

    private static HyperspaceTerrainPlugin nativeCopy(TestTerrain source) throws IllegalAccessException {
        HyperspaceTerrainPlugin target = new HyperspaceTerrainPlugin();
        for (Class<?> type = HyperspaceTerrainPlugin.class; type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true); field.set(target, field.get(source));
            }
        }
        return target;
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
    private static void assertAcceptedControls(CoopStormWorld world, long source, long window, long sharedOff, long privateOff) {
        Map<String, Object> diagnostics = world.diagnostics("", 1);
        assertEquals(source, diagnostics.get("acceptedSurgeSourceCount"));
        assertEquals(window, diagnostics.get("acceptedSurgeWindowCount"));
        assertEquals(sharedOff, diagnostics.get("acceptedSharedOffCount"));
        assertEquals(privateOff, diagnostics.get("acceptedPrivateSurgeOffCount"));
    }
    private static CoopStormSnapshot.Tracker row(int i, int j, long epoch, int state, float wait,
            float signal, float wane, float maxSignal, float maxWane, float scale) {
        return new CoopStormSnapshot.Tracker(i, j, epoch, state, wait, signal, wane, maxSignal, maxWane, scale);
    }
    private static CoopStormSnapshot snapshot(int w, int h, List<CoopStormSnapshot.Tracker> rows) {
        return new CoopStormSnapshot(1, 0, 100, 100, 2, w, h, CoopStormGrid.encode(new byte[w][h]), rows);
    }
    private static void installHost(Scene s, float extra, float scale) {
        CoopStormBridge.install(s, false, new CoopStormBridge.Handler() {
            public CampaignFleetAPI remoteFleet() { return s.remote; }
            public float remoteExtraRadius() { return extra; }
            public float remoteTimeScale() { return scale; }
            public void tileOff(float x, float y, float radius, float wait) { fail("Host queued Guest control"); }
            public void surgeWindow(float x, float y, float e, float t) { fail("Host queued Guest window"); }
            public void surgeSource(float x, float y) { fail("Host queued Guest surge source"); }
        });
    }

    private static SlipstreamTerrainPlugin2 privateNativeSurge(Scene s, Map<String, Object> memory,
                                                               boolean alive, boolean hyperspace) throws Exception {
        SlipstreamTerrainPlugin2 plugin = new SlipstreamTerrainPlugin2();
        plugin.getParams().enteringSlipstreamTextOverride = "Entering slipsurge";
        plugin.getParams().enteringSlipstreamTextDurationOverride = 0.1f;
        plugin.getParams().forceNoWindVisualEffectOnFleets = true;
        CampaignTerrainAPI entity = stub(CampaignTerrainAPI.class, (name, args) -> switch (name) {
            case "isAlive" -> alive; case "isInHyperspace" -> hyperspace;
            case "getContainingLocation" -> s.hyperspace;
            case "getMemoryWithoutUpdate" -> ApiProxies.memory(memory);
            default -> null;
        });
        for (Class<?> type = SlipstreamTerrainPlugin2.class; type != Object.class; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField("entity"); field.setAccessible(true); field.set(plugin, entity); return plugin; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new AssertionError("Native terrain entity field not found");
    }

    private static final class TestTerrain extends HyperspaceTerrainPlugin {
        TestTerrain(int w, int h) {
            params = new TileParams("", w, h, "terrain", "deep_hyperspace", 1, 1, "Hyperspace");
            tiles = new int[w][h]; activeCells = new CellStateTracker[w][h];
            auto = new HyperspaceAutomaton(w, h, 1.5f, 2.5f);
            entity = stub(SectorEntityToken.class, (name, args) -> name.equals("getLocation") ? new Vector2f() : null);
        }
        int[][] grid() { return auto.getCells(); }
        void put(int i, int j, float wait, float signal) { activeCells[i][j] = new CellStateTracker(i, j, wait, signal); }
        void step(float days) { coopAdvanceShared(days); }
        void strike(CampaignFleetAPI fleet) { applyStormStrikes(new CellStateTracker(0, 0, 0, 0), fleet, 1); }
        int savedRows() { return savedActiveCells.size(); }
    }

    private static final class Scene {
        final Vector2f hostPoint = new Vector2f(), remotePoint = new Vector2f();
        final List<CampaignTerrainAPI> terrains = new ArrayList<>();
        float elapsedDays;
        final LocationAPI hyperspace = stub(LocationAPI.class, (name, args) -> name.equals("getTerrainCopy") ? terrains : null);
        final CampaignFleetAPI player = fleet(hostPoint, true), remote = fleet(remotePoint, false);
        final CampaignClockAPI clock = stub(CampaignClockAPI.class, (name, args) -> switch (name) {
            case "getTimestamp" -> 100L;
            case "getElapsedDaysSince" -> elapsedDays;
            case "convertToDays" -> args[0];
            default -> null;
        });
        final ViewportAPI viewport = stub(ViewportAPI.class, (name, args) -> switch (name) {
            case "getLLX" -> hostPoint.x - 500f; case "getLLY" -> -500f;
            case "getVisibleWidth", "getVisibleHeight" -> 1000f;
            default -> null;
        });
        final SectorAPI sector = stub(SectorAPI.class, (name, args) -> switch (name) {
            case "getPlayerFleet" -> player;
            case "getHyperspace" -> hyperspace;
            case "getClock" -> clock;
            case "getViewport" -> viewport;
            case "getListenerManager" -> ApiProxies.listenerManager(new ArrayList<>());
            default -> null;
        });
        final TestTerrain terrain;
        Scene(int w, int h) {
            terrain = new TestTerrain(w, h);
            terrains.add(stub(CampaignTerrainAPI.class, (name, args) -> switch (name) {
                case "getPlugin" -> terrain; case "isAlive" -> true; default -> null;
            }));
            Global.setSector(sector);
        }
        CampaignFleetAPI fleet(Vector2f point, boolean own) {
            Map<String, Object> memory = new HashMap<>(); if (!own) memory.put("$coopMirrorFleet", true);
            return stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
                case "getLocation", "getLocationInHyperspace" -> point;
                case "isPlayerFleet" -> own; case "isInHyperspace" -> true;
                case "getContainingLocation" -> hyperspace;
                case "getMemoryWithoutUpdate" -> ApiProxies.memory(memory);
                default -> null;
            });
        }
    }

    private interface Answer { Object answer(String method, Object[] args); }
    private static <T> T stub(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return type.getSimpleName();
            Object value = answer.answer(method.getName(), args);
            return value == null ? ProxyDefaults.defaultValue(method.getReturnType()) : value;
        }));
    }
}
