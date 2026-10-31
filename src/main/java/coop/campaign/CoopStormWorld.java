package coop.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTerrain;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellState;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import coop.storm.CoopStormBridge;
import coop.storm.CoopStormControl;
import coop.storm.CoopStormSnapshot;
import coop.storm.CoopStormExecutionProbe;
import coop.debug.CoopAgentBridge;
import coop.util.CoopDebug;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Campaign adapter for Host-owned native weather and recurring, bounded Guest surge effects. */
public final class CoopStormWorld implements CoopStormAuthority.World {
    private static final int MAX_PENDING = 4096;
    private static final float MAX_OFF_RADIUS = 10_000f;
    private static final float REMOTE_POSITION_TOLERANCE = 4_000f;
    private static final float WINDOW_REFRESH_SECONDS = 2f;
    private final Supplier<SectorAPI> sectorSupplier;
    private Supplier<CampaignFleetAPI> remoteFleetSupplier = () -> null;
    private final Map<String, CoopStormControl> pending = new LinkedHashMap<>();
    private Object owner;
    private SectorAPI installedSector;
    private boolean guest;
    private long latestRevision;
    private float remoteExtraRadius;
    private float remoteTimeScale = 1f;
    private long remoteWindowTimestamp;
    private long remoteSurgeSourceTimestamp;
    private boolean remoteSurgeSourceKnown;
    private long acceptedSurgeSourceCount;
    private long acceptedSurgeWindowCount;
    private long acceptedSharedOffCount;
    private long acceptedPrivateSurgeOffCount;
    private long lastAcceptedSurgeSourceTimestamp;
    private float lastAcceptedSurgeSourceX, lastAcceptedSurgeSourceY;
    private float lastAcceptedPrivateSurgeOffX, lastAcceptedPrivateSurgeOffY, lastAcceptedPrivateSurgeOffRadius;

    public CoopStormWorld(Supplier<SectorAPI> sectorSupplier) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier);
    }

    public void install(Object newOwner, boolean isGuest, Supplier<CampaignFleetAPI> remoteSupplier) {
        Objects.requireNonNull(newOwner, "storm owner"); Objects.requireNonNull(remoteSupplier, "remote fleet supplier");
        SectorAPI sector = sectorSupplier.get();
        if (owner == newOwner && installedSector == sector && guest == isGuest) {
            remoteFleetSupplier = remoteSupplier;
            return;
        }
        resetTransport();
        owner = newOwner; installedSector = sector; guest = isGuest; remoteFleetSupplier = remoteSupplier;
        CoopStormBridge.install(owner, guest, new CoopStormBridge.Handler() {
            @Override public boolean executionDiagnosticsEnabled() {
                return currentSector() && CoopDebug.diagnosticsEnabled() && CoopAgentBridge.configuredPort() != 0;
            }
            @Override public CampaignFleetAPI remoteFleet() { return currentSector() ? remoteFleetSupplier.get() : null; }
            @Override public float remoteExtraRadius() { return windowCurrent() ? remoteExtraRadius : 0f; }
            @Override public float remoteTimeScale() { return windowCurrent() ? remoteTimeScale : 1f; }
            @Override public void tileOff(float x, float y, float radius, float waitDays) {
                if (!currentSector() || !guest) return;
                if (!Float.isFinite(radius) || radius <= 0 || radius > MAX_OFF_RADIUS
                        || !Float.isFinite(waitDays) || waitDays < 0 || waitDays > 1) return;
                CoopStormControl control = CoopStormControl.tileOff(UUID.randomUUID().toString(), latestRevision, x, y, radius, waitDays);
                queue("off:" + x + ":" + y + ":" + radius, control);
            }
            @Override public void surgeWindow(float x, float y, float extra, float scale) {
                if (!currentSector() || !guest || !(extra == 16_000f && scale == 5f || extra == 0f && scale == 1f)) return;
                queue("window", CoopStormControl.surgeWindow(UUID.randomUUID().toString(), latestRevision, x, y, extra, scale));
            }
            @Override public void surgeSource(float x, float y) {
                if (!currentSector() || !guest) return;
                queue("surgeSource", CoopStormControl.surgeSource(UUID.randomUUID().toString(), latestRevision, x, y));
            }
        });
    }

    public void uninstall() {
        if (owner != null) CoopStormBridge.uninstall(owner);
        resetTransport(); owner = null; installedSector = null; remoteFleetSupplier = () -> null;
    }

    private boolean currentSector() { return installedSector != null && sectorSupplier.get() == installedSector; }
    private boolean windowCurrent() {
        if (!currentSector() || remoteExtraRadius <= 0) return false;
        float elapsed = installedSector.getClock().getElapsedDaysSince(remoteWindowTimestamp);
        float lifetime = installedSector.getClock().convertToDays(WINDOW_REFRESH_SECONDS);
        return Float.isFinite(elapsed) && Float.isFinite(lifetime) && elapsed >= 0 && lifetime > 0 && elapsed < lifetime;
    }

    private boolean surgeSourceCurrent() {
        if (!currentSector() || !remoteSurgeSourceKnown) return false;
        float elapsed = installedSector.getClock().getElapsedDaysSince(remoteSurgeSourceTimestamp);
        return Float.isFinite(elapsed) && elapsed >= 0 && elapsed <= 1.7f;
    }

    private void queue(String key, CoopStormControl control) {
        if (!pending.containsKey(key) && pending.size() >= MAX_PENDING) {
            pending.remove(pending.keySet().iterator().next());
        }
        pending.put(key, control);
    }

    @Override public CoopStormSnapshot captureHost() {
        if (guest) throw new IllegalStateException("Guest cannot publish authoritative storms");
        HyperspaceTerrainPlugin plugin = pluginOrNull();
        if (plugin == null) return null;
        if (remoteFleetSupplier.get() == null) return null;
        CoopStormSnapshot snapshot = plugin.coopCapture(installedSector.getClock().getTimestamp());
        latestRevision = snapshot.revision();
        return snapshot;
    }

    @Override public void applySnapshot(CoopStormSnapshot snapshot) {
        if (!guest) throw new IllegalStateException("Host cannot apply Guest storm state");
        requirePlugin().coopApply(snapshot);
        latestRevision = snapshot.revision();
    }

    @Override public void observeGuest() {
        HyperspaceTerrainPlugin plugin = guest ? pluginOrNull() : null;
        if (plugin != null) plugin.coopObserveGuest();
    }

    /** Drained only by the active Guest pump; native recurring effects refresh after a dropped link. */
    @Override public List<CoopStormControl> pendingControls() {
        if (!guest || !currentSector()) return List.of();
        List<CoopStormControl> result = new ArrayList<>(pending.values());
        result.sort(java.util.Comparator.comparingInt(control ->
                control.action() == CoopStormControl.Action.SURGE_WINDOW ? 0
                        : control.action() == CoopStormControl.Action.SURGE_SOURCE ? 1 : 2));
        pending.clear();
        return result;
    }

    @Override public void applyControl(CoopStormControl control, String actorId) {
        Objects.requireNonNull(control, "storm control");
        if (guest || !currentSector() || actorId == null || actorId.isBlank())
            throw new IllegalArgumentException("Authenticated Guest storm control required");
        CampaignFleetAPI remote = remoteFleetSupplier.get();
        if (remote == null || !remote.isInHyperspace() || remote.getContainingLocation() != installedSector.getHyperspace())
            throw new IllegalArgumentException("Guest storm control has no current hyperspace actor");
        if (control.baseRevision() > latestRevision || latestRevision - control.baseRevision() > 40)
            throw new IllegalArgumentException("Storm control is outside its authoritative revision window");
        Vector2f point = new Vector2f(control.x(), control.y());
        float distance = Vector2f.sub(point, remote.getLocation(), null).length();
        if (!Float.isFinite(distance)) throw new IllegalArgumentException("Guest storm actor position is nonfinite");
        HyperspaceTerrainPlugin nativeWeather = requirePlugin();
        if (control.action() == CoopStormControl.Action.SURGE_SOURCE) {
            if (distance > REMOTE_POSITION_TOLERANCE)
                throw new IllegalArgumentException("Storm surge source does not match current Guest position");
            remoteSurgeSourceTimestamp = installedSector.getClock().getTimestamp();
            remoteSurgeSourceKnown = true;
            acceptedSurgeSourceCount++;
            lastAcceptedSurgeSourceTimestamp = remoteSurgeSourceTimestamp;
            lastAcceptedSurgeSourceX = control.x(); lastAcceptedSurgeSourceY = control.y();
            return;
        }
        if (control.action() == CoopStormControl.Action.SURGE_WINDOW) {
            boolean clear = control.extraRadius() == 0f && control.timeScale() == 1f;
            if (!(clear || control.extraRadius() == 16_000f && control.timeScale() == 5f)
                    || distance > REMOTE_POSITION_TOLERANCE)
                throw new IllegalArgumentException("Storm surge window does not match native Guest geometry");
            remoteExtraRadius = control.extraRadius(); remoteTimeScale = control.timeScale();
            remoteWindowTimestamp = installedSector.getClock().getTimestamp();
            if (!clear) {
                remoteSurgeSourceTimestamp = remoteWindowTimestamp; remoteSurgeSourceKnown = true;
            }
            nativeWeather.coopRemoteWindowChanged();
            acceptedSurgeWindowCount++;
            return;
        }
        if (control.radius() > MAX_OFF_RADIUS || distance > 26_000f + control.radius())
            throw new IllegalArgumentException("Storm clearing is outside the Guest weather neighborhood");
        boolean sharedStream = matchesSharedStream(control);
        if (!sharedStream && !(surgeSourceCurrent() && control.radius() <= 3_000f))
            throw new IllegalArgumentException("Storm clearing has no shared stream or current native surge window");
        nativeWeather.setTileState(point, control.radius(), CellState.OFF, control.waitDays(), -1f, -1f);
        if (sharedStream) acceptedSharedOffCount++;
        else {
            acceptedPrivateSurgeOffCount++;
            lastAcceptedPrivateSurgeOffX = control.x(); lastAcceptedPrivateSurgeOffY = control.y();
            lastAcceptedPrivateSurgeOffRadius = control.radius();
        }
    }

    private boolean matchesSharedStream(CoopStormControl control) {
        for (CampaignTerrainAPI terrain : installedSector.getHyperspace().getTerrainCopy()) {
            if (!(terrain.getPlugin() instanceof SlipstreamTerrainPlugin2 stream) || !terrain.isAlive()) continue;
            // Trail widths change between polls; accept only exact, recent Host-published geometry.
            if (coop.ambient.CoopSlipstreamFadeBridge.matchesPublishedTrailClear(
                    stream, control.x(), control.y(), control.radius())) return true;
            for (SlipstreamTerrainPlugin2.SlipstreamSegment segment : stream.getSegments()) {
                float expectedRadius = segment.width * 0.5f + stream.getParams().edgeWidth + 100f;
                if (Math.abs(expectedRadius - control.radius()) <= 2f
                        && Math.abs(segment.loc.x - control.x()) <= 2f && Math.abs(segment.loc.y - control.y()) <= 2f
                        && segment.bMult > 0f && !segment.fader.isFadedOut()) return true;
            }
        }
        return false;
    }

    @Override public void resetTransport() {
        pending.clear(); remoteExtraRadius = 0f; remoteTimeScale = 1f; remoteWindowTimestamp = 0;
        remoteSurgeSourceTimestamp = 0; remoteSurgeSourceKnown = false;
        acceptedSurgeSourceCount = 0; acceptedSurgeWindowCount = 0;
        acceptedSharedOffCount = 0; acceptedPrivateSurgeOffCount = 0;
        lastAcceptedSurgeSourceTimestamp = 0; lastAcceptedSurgeSourceX = 0f; lastAcceptedSurgeSourceY = 0f;
        lastAcceptedPrivateSurgeOffX = 0f; lastAcceptedPrivateSurgeOffY = 0f; lastAcceptedPrivateSurgeOffRadius = 0f;
    }

    public Map<String, Object> diagnostics(String cells, int limit) {
        HyperspaceTerrainPlugin plugin = pluginOrNull();
        Map<String, Object> result = plugin == null ? new LinkedHashMap<>() : plugin.coopDiagnostics(cells, limit);
        result.putIfAbsent("available", false);
        result.put("role", (owner == null ? CoopStormBridge.isGuest() : guest) ? "GUEST" : "HOST");
        result.put("pendingControlCount", pending.size());
        result.put("execution", CoopStormExecutionProbe.snapshot());
        result.put("actors", actors(plugin));
        result.put("acceptedSurgeSourceCount", acceptedSurgeSourceCount);
        result.put("acceptedSurgeWindowCount", acceptedSurgeWindowCount);
        result.put("acceptedSharedOffCount", acceptedSharedOffCount);
        result.put("acceptedPrivateSurgeOffCount", acceptedPrivateSurgeOffCount);
        result.put("lastAcceptedSurgeSourceTimestamp", lastAcceptedSurgeSourceTimestamp);
        result.put("lastAcceptedSurgeSourceX", lastAcceptedSurgeSourceX);
        result.put("lastAcceptedSurgeSourceY", lastAcceptedSurgeSourceY);
        result.put("lastAcceptedPrivateSurgeOffX", lastAcceptedPrivateSurgeOffX);
        result.put("lastAcceptedPrivateSurgeOffY", lastAcceptedPrivateSurgeOffY);
        result.put("lastAcceptedPrivateSurgeOffRadius", lastAcceptedPrivateSurgeOffRadius);
        result.put("surgeSourceCurrent", surgeSourceCurrent());
        boolean window = windowCurrent();
        result.put("surgeWindowCurrent", window);
        result.put("effectiveRemoteExtraRadius", window ? remoteExtraRadius : 0f);
        result.put("effectiveRemoteTimeScale", window ? remoteTimeScale : 1f);
        return result;
    }

    /** Reads the exact native strike gates without applying terrain or changing cached memory. */
    private Map<String, Object> actors(HyperspaceTerrainPlugin plugin) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean current = currentSector();
        result.put("available", current && plugin != null);
        if (!current) result.put("reason", "storm_world_not_installed_for_current_campaign");
        else if (plugin == null) result.put("reason", "native_hyperspace_terrain_unavailable");
        result.put("owner", actor(plugin, current ? installedSector.getPlayerFleet() : null,
                current ? "local_player_unavailable" : "storm_world_not_current"));
        result.put("peer", actor(plugin, current ? remoteFleetSupplier.get() : null,
                current ? "peer_mirror_unavailable" : "storm_world_not_current"));
        return result;
    }

    private static Map<String, Object> actor(HyperspaceTerrainPlugin plugin, CampaignFleetAPI fleet, String missingReason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("present", fleet != null);
        if (fleet == null) { result.put("reason", missingReason); return result; }
        result.put("id", fleet.getId());
        result.put("identity", Integer.toUnsignedString(System.identityHashCode(fleet), 16));
        result.put("isPlayerFleet", fleet.isPlayerFleet());
        result.put("isMirror", CoopStormBridge.isMirror(fleet));
        Vector2f position = fleet.getLocation(), velocity = fleet.getVelocity();
        result.put("x", position == null ? null : position.x); result.put("y", position == null ? null : position.y);
        result.put("velocityX", velocity == null ? null : velocity.x); result.put("velocityY", velocity == null ? null : velocity.y);
        result.put("speed", velocity == null ? null : velocity.length()); result.put("radius", fleet.getRadius());
        result.put("currentBurn", fleet.getCurrBurnLevel());
        result.put("inHyperspace", fleet.isInHyperspace());
        result.put("transition", fleet.isInHyperspaceTransition());
        result.put("inBattle", fleet.getBattle() != null);
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        Object mutex = memory == null ? null : memory.get(BaseTerrain.TERRAIN_LOCK_KEY);
        result.put("terrainMutexType", mutex == null ? null : mutex.getClass().getName());
        result.put("terrainMutexValue", mutex == null || mutex instanceof String || mutex instanceof Number
                || mutex instanceof Boolean ? mutex : "nonprimitive");
        result.put("nativeGatesAvailable", false);
        if (plugin == null) { result.put("reason", "native_hyperspace_terrain_unavailable"); return result; }
        if (position == null || velocity == null || fleet.getStats() == null || fleet.getFleetData() == null) {
            result.put("reason", "native_fleet_gate_inputs_unavailable"); return result;
        }
        if (plugin.getEntity() == null || plugin.getActiveCells() == null) {
            result.put("reason", "native_hyperspace_terrain_not_initialized"); return result;
        }
        int goSlowBurn = goSlowBurn(fleet);
        result.put("goSlowBurn", goSlowBurn);
        result.put("slowMoving", fleet.getCurrBurnLevel() <= goSlowBurn);
        result.put("nativeGatesAvailable", true);
        result.put("terrainId", plugin.getEntity() == null ? null : plugin.getEntity().getId());
        result.put("terrainPrevented", plugin.isPreventedFromAffecting(fleet));
        result.put("inClouds", plugin.isInClouds(fleet));
        result.put("locationInClouds", plugin.isInClouds(position, fleet.getRadius()));
        int[] selected = plugin.getTilePreferStorm(position, fleet.getRadius());
        int[] exact = plugin.getTile(position);
        result.put("selectedTile", tile(selected)); result.put("selectedCell", cell(plugin, selected));
        result.put("exactTile", tile(exact)); result.put("exactCell", cell(plugin, exact));
        return result;
    }

    /** Exact pinned Misc math, reading existing mods instead of lazily creating one via getMod. */
    private static int goSlowBurn(CampaignFleetAPI fleet) {
        var dynamic = fleet.getStats().getDynamic();
        if (dynamic == null || dynamic.getMods() == null)
            throw new IllegalStateException("Native fleet has no dynamic stat map");
        var modifier = dynamic.getMods().get(Stats.MOVE_SLOW_SPEED_BONUS_MOD);
        float bonus = modifier == null ? 0f : modifier.computeEffective(0f);
        int burn = Math.round(fleet.getFleetData().getMinBurnLevel() * Misc.SNEAK_BURN_MULT);
        burn += bonus;
        return burn;
    }

    private static List<Integer> tile(int[] coordinates) {
        return coordinates == null ? null : List.of(coordinates[0], coordinates[1]);
    }

    private static Map<String, Object> cell(HyperspaceTerrainPlugin plugin, int[] tile) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (tile == null) { result.put("present", false); result.put("reason", "no_native_tile_selected"); return result; }
        var rows = plugin.getActiveCells();
        if (rows == null || tile[0] < 0 || tile[0] >= rows.length || rows[tile[0]] == null
                || tile[1] < 0 || tile[1] >= rows[tile[0]].length)
            throw new IllegalStateException("Native storm tile is outside tracker dimensions");
        var state = rows[tile[0]][tile[1]];
        result.put("present", state != null);
        if (state == null) { result.put("reason", "native_tracker_absent"); return result; }
        result.put("i", state.i); result.put("j", state.j); result.put("epoch", state.coopEpoch);
        result.put("phase", state.state == null ? null : state.state.name());
        result.put("storming", state.isStorming()); result.put("signaling", state.isSignaling());
        result.put("wait", state.wait); result.put("signal", state.signal); result.put("wane", state.wane);
        result.put("flickerPresent", state.flicker != null);
        result.put("peakFrame", state.flicker != null && state.flicker.isPeakFrame());
        return result;
    }

    private HyperspaceTerrainPlugin requirePlugin() {
        HyperspaceTerrainPlugin result = pluginOrNull();
        if (result == null) throw new IllegalStateException("Current campaign has no native hyperspace storm terrain");
        return result;
    }

    private HyperspaceTerrainPlugin pluginOrNull() {
        if (!currentSector() || installedSector.getHyperspace() == null) return null;
        HyperspaceTerrainPlugin found = null;
        for (CampaignTerrainAPI terrain : installedSector.getHyperspace().getTerrainCopy()) {
            if (terrain.getPlugin() instanceof HyperspaceTerrainPlugin plugin) {
                if (found != null) throw new IllegalStateException("Multiple native hyperspace storm terrains");
                found = plugin;
            }
        }
        return found;
    }
}
