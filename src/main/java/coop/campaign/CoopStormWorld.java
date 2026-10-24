package coop.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellState;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import coop.storm.CoopStormBridge;
import coop.storm.CoopStormControl;
import coop.storm.CoopStormSnapshot;
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
                control.action() == CoopStormControl.Action.SURGE_WINDOW ? 0 : 1));
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
            return;
        }
        if (control.radius() > MAX_OFF_RADIUS || distance > 26_000f + control.radius())
            throw new IllegalArgumentException("Storm clearing is outside the Guest weather neighborhood");
        if (!matchesSharedStream(control) && !(surgeSourceCurrent() && control.radius() <= 3_000f))
            throw new IllegalArgumentException("Storm clearing has no shared stream or current native surge window");
        nativeWeather.setTileState(point, control.radius(), CellState.OFF, control.waitDays(), -1f, -1f);
    }

    private boolean matchesSharedStream(CoopStormControl control) {
        for (CampaignTerrainAPI terrain : installedSector.getHyperspace().getTerrainCopy()) {
            if (!(terrain.getPlugin() instanceof SlipstreamTerrainPlugin2 stream) || !terrain.isAlive()) continue;
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
    }

    public Map<String, Object> diagnostics(String cells, int limit) {
        HyperspaceTerrainPlugin plugin = pluginOrNull();
        Map<String, Object> result = plugin == null ? new LinkedHashMap<>() : plugin.coopDiagnostics(cells, limit);
        result.putIfAbsent("available", false);
        result.put("role", (owner == null ? CoopStormBridge.isGuest() : guest) ? "GUEST" : "HOST");
        result.put("pendingControlCount", pending.size());
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
