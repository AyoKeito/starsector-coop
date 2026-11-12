package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhostManager;
import com.fs.starfarer.api.impl.campaign.ghosts.types.AbyssalDrifterGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.types.ChargerGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.types.LeviathanCalfGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.LeviathanGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.NoGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.types.ZigguratGhostCreator;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import coop.ambient.CoopSlipstreamFadeBridge;
import coop.config.CoopOptionsRegistry;
import coop.net.CoopConnectionRole;
import coop.presence.CoopPresenceRegistry;
import coop.util.CoopDebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded native M5 observations and explicit diagnostic setup; never called by simulation. */
public final class CoopGhostDiagnostics {
    private static final String HANDLE = "$ghostManager";
    private static final String OWNED_TAG = "coop_cosmetic_ghost";
    private static final int MAX_MANAGERS = 16;
    private static final int MAX_GHOSTS = 256;
    private static final int MAX_ENTITIES = 512;
    private static final int MAX_TRAILS = 64;
    private static final int MAX_SEGMENTS = 4096;

    private CoopGhostDiagnostics() { }

    /** Reads cached/registered objects directly; native lazy manager lookup would mutate the cache. */
    public static Map<String, Object> capture(SectorAPI sector) {
        if (sector == null) throw new IllegalStateException("No campaign loaded");
        List<EveryFrameScript> persistent = scripts(sector.getScripts());
        List<EveryFrameScript> transientScripts = scripts(sector.getTransientScripts());
        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        Object cached = memory == null ? null : memory.get(HANDLE);
        List<SensorGhostManager> managers = managers(persistent, transientScripts, cached);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cacheType", type(cached));
        out.put("cacheIdentity", identity(cached));
        out.put("ownedCachedHandle", CoopSensorGhostManager.ownsCachedHandle(sector));
        out.put("cosmeticCreators", CoopSensorGhostManager.cosmeticCreatorNames());
        out.put("managerCount", managers.size());
        out.put("managersTruncated", managers.size() > MAX_MANAGERS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SensorGhostManager manager : managers.subList(0, Math.min(MAX_MANAGERS, managers.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type(manager));
            row.put("identity", identity(manager));
            row.put("persistent", containsIdentity(persistent, manager));
            row.put("transient", containsIdentity(transientScripts, manager));
            row.put("cached", cached == manager);
            row.put("ghostCount", manager.getGhosts().size());
            row.put("ghostsTruncated", manager.getGhosts().size() > MAX_GHOSTS);
            row.put("ghosts", manager.getGhosts().stream().limit(MAX_GHOSTS)
                    .map(CoopGhostDiagnostics::ghost).toList());
            rows.add(row);
        }
        out.put("managers", rows);
        List<SectorEntityToken> entities = new ArrayList<>();
        if (sector.getHyperspace() != null) {
            for (SectorEntityToken entity : sector.getHyperspace().getCustomEntities()) {
                if (Entities.SENSOR_GHOST.equals(entity.getCustomEntityType())) entities.add(entity);
            }
        }
        out.put("sensorEntityCount", entities.size());
        out.put("sensorEntitiesTruncated", entities.size() > MAX_ENTITIES);
        out.put("sensorEntities", entities.stream().limit(MAX_ENTITIES)
                .map(CoopGhostDiagnostics::entity).toList());
        List<CampaignTerrainAPI> trails = new ArrayList<>();
        if (sector.getHyperspace() != null) {
            for (CampaignTerrainAPI terrain : sector.getHyperspace().getTerrainCopy()) {
                if (terrain.getPlugin() instanceof SlipstreamTerrainPlugin2 stream && stream.isDynamic())
                    trails.add(terrain);
            }
        }
        out.put("dynamicTerrainCount", trails.size());
        out.put("trailsTruncated", trails.size() > MAX_TRAILS);
        List<Map<String, Object>> trailRows = new ArrayList<>();
        int remainingSegments = MAX_SEGMENTS;
        for (CampaignTerrainAPI terrain : trails.subList(0, Math.min(MAX_TRAILS, trails.size()))) {
            SlipstreamTerrainPlugin2 stream = (SlipstreamTerrainPlugin2) terrain.getPlugin();
            Map<String, Object> row = entity(terrain);
            row.put("pluginType", type(stream));
            row.put("pluginIdentity", identity(stream));
            row.put("nativeTrail", CoopSlipstreamFadeBridge.isNativeTrail(stream));
            row.put("holdsNativeFade", CoopSlipstreamFadeBridge.holdsNativeFade(stream));
            row.put("sharedId", terrain.getMemoryWithoutUpdate().get("$coopStreamId"));
            row.put("sharedKind", terrain.getMemoryWithoutUpdate().get("$coopStreamKind"));
            row.put("despawning", stream.isDespawning());
            row.put("segmentCount", stream.getSegments().size());
            int count = Math.min(remainingSegments, stream.getSegments().size());
            row.put("segmentsTruncated", count < stream.getSegments().size());
            List<Map<String, Object>> segments = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                SlipstreamTerrainPlugin2.SlipstreamSegment segment = stream.getSegments().get(i);
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("identity", identity(segment));
                point.put("x", segment.loc.x);
                point.put("y", segment.loc.y);
                point.put("width", segment.width);
                point.put("brightnessMult", segment.bMult);
                point.put("brightness", segment.fader.getBrightness());
                point.put("faderState", segment.fader.getState().name());
                point.put("discovered", segment.discovered);
                segments.add(point);
            }
            remainingSegments -= count;
            row.put("segments", segments);
            trailRows.add(row);
        }
        out.put("trails", trailRows);
        return out;
    }

    /** One native attempt, with no forced placement, RNG reset, retry or synthetic terrain. */
    public static Map<String, Object> spawn(SectorAPI sector, CoopConnectionRole role,
                                            boolean gameplaySessionActive, String kind) {
        requireSetup(sector, gameplaySessionActive);
        SensorGhostCreator creator = switch (kind) {
            case "charger" -> new ChargerGhostCreator();
            case "no_ghost" -> new NoGhostCreator();
            case "ziggurat" -> new ZigguratGhostCreator();
            case "abyssal_drifter" -> new AbyssalDrifterGhostCreator();
            case "leviathan", "calf" -> null;
            default -> throw new IllegalArgumentException("Unknown diagnostic ghost kind: " + kind);
        };
        List<SensorGhostManager> registered = managers(scripts(sector.getScripts()),
                scripts(sector.getTransientScripts()), null);
        SensorGhostManager manager;
        if (creator != null) {
            if (role != CoopConnectionRole.GUEST) throw new IllegalStateException("Cosmetic setup requires Guest");
            List<SensorGhostManager> matches = registered.stream()
                    .filter(value -> value.getClass() == CoopSensorGhostManager.class).toList();
            if (matches.size() != 1 || !CoopSensorGhostManager.ownsCachedHandle(sector))
                throw new IllegalStateException("Guest has no uniquely registered owned cosmetic manager");
            manager = matches.get(0);
        } else {
            if (role != CoopConnectionRole.HOST) throw new IllegalStateException("Trail setup requires Host");
            List<SensorGhostManager> matches = registered.stream()
                    .filter(value -> value.getClass() == SensorGhostManager.class).toList();
            if (matches.size() != 1) throw new IllegalStateException("Host has no unique native ghost manager");
            manager = matches.get(0);
        }
        Set<SensorGhost> before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(manager.getGhosts());
        if (creator != null) {
            ((CoopSensorGhostManager) manager).createCosmeticGhost(creator);
        } else {
            SensorGhost source = "leviathan".equals(kind)
                    ? new LeviathanGhost(manager, 0) : new LeviathanCalfGhost(manager);
            if (!source.isCreationFailed()) manager.getGhosts().add(source);
        }
        List<SensorGhost> added = manager.getGhosts().stream().filter(value -> !before.contains(value)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("managerType", type(manager));
        out.put("managerIdentity", identity(manager));
        out.put("created", added.size());
        out.put("refusedByNativeCreator", added.isEmpty());
        out.put("ghosts", added.stream().map(CoopGhostDiagnostics::ghost).toList());
        return out;
    }

    private static void requireSetup(SectorAPI sector, boolean gameplaySessionActive) {
        if (!CoopDebug.diagnosticsEnabled()) throw new IllegalStateException("Ghost setup requires diagnostics");
        int port;
        try { port = Integer.parseInt(System.getProperty(CoopOptionsRegistry.DEBUG_BRIDGE, "0")); }
        catch (NumberFormatException error) { throw new IllegalStateException("Ghost setup requires a configured bridge", error); }
        if (port < 1 || port > 65535) throw new IllegalStateException("Ghost setup requires a configured bridge");
        if (!gameplaySessionActive) throw new IllegalStateException("Ghost setup requires an active gameplay session");
        if (sector == null || Global.getSector() != sector || !sector.isPaused())
            throw new IllegalStateException("Ghost setup requires the current paused campaign");
        if (Global.getSettings() == null || Global.getSettings().getVersionString() == null
                || !Global.getSettings().getVersionString().contains(CoopPresenceRegistry.PINNED_VERSION))
            throw new IllegalStateException("Ghost setup requires pinned Starsector " + CoopPresenceRegistry.PINNED_VERSION);
        if (sector.getPlayerFleet() == null || sector.getHyperspace() == null
                || sector.getCurrentLocation() != sector.getHyperspace()
                || sector.getPlayerFleet().getContainingLocation() != sector.getHyperspace())
            throw new IllegalStateException("Ghost setup requires the real player in hyperspace");
    }

    private static List<SensorGhostManager> managers(List<EveryFrameScript> persistent,
                                                    List<EveryFrameScript> transientScripts, Object cached) {
        List<SensorGhostManager> result = new ArrayList<>();
        List<Object> candidates = new ArrayList<>(persistent);
        candidates.addAll(transientScripts);
        if (cached != null) candidates.add(cached);
        for (Object value : candidates) {
            if (value instanceof SensorGhostManager manager && result.stream().noneMatch(old -> old == manager))
                result.add(manager);
        }
        return result;
    }

    private static Map<String, Object> ghost(SensorGhost ghost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type(ghost));
        row.put("identity", identity(ghost));
        if (ghost == null) return row;
        row.put("creationFailed", ghost.isCreationFailed());
        row.put("cosmeticAllowed", CoopSensorGhostManager.isCosmeticGhost(ghost));
        row.put("despawnInAbyss", ghost.isDespawnInAbyss());
        row.put("behaviors", ghost.getScript().stream().map(CoopGhostDiagnostics::type).toList());
        row.put("entity", ghost.getEntity() == null ? null : entity(ghost.getEntity()));
        return row;
    }

    private static Map<String, Object> entity(SectorEntityToken entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entity.getId());
        row.put("identity", identity(entity));
        row.put("type", entity.getCustomEntityType());
        row.put("alive", entity.isAlive());
        row.put("radius", entity.getRadius());
        row.put("ownedCosmetic", entity.hasTag(OWNED_TAG));
        row.put("x", entity.getLocation().x);
        row.put("y", entity.getLocation().y);
        row.put("scripts", entity.getScripts().stream().map(CoopGhostDiagnostics::type).toList());
        return row;
    }

    private static boolean containsIdentity(List<?> values, Object wanted) {
        return values.stream().anyMatch(value -> value == wanted);
    }

    private static List<EveryFrameScript> scripts(List<EveryFrameScript> scripts) {
        return scripts == null ? List.of() : scripts;
    }

    private static String type(Object value) { return value == null ? null : value.getClass().getName(); }
    private static String identity(Object value) {
        return value == null ? null : Integer.toUnsignedString(System.identityHashCode(value), 16);
    }
}
