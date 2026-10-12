package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.abilities.GenerateSlipsurgeAbility;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamManager;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2.SlipstreamParams2;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2.SlipstreamSegment;
import com.fs.starfarer.api.util.FaderUtil;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/** Campaign-thread engine adapter. Only primitive ids and kinds are written into saved memory. */
public final class CoopSlipstreamWorld implements CoopSlipstreamAuthority.StreamWorld {
    public static final String STREAM_ID = "$coopStreamId";
    public static final String STREAM_KIND = "$coopStreamKind";

    private static Handles handles;
    private final Supplier<SectorAPI> sectorSupplier;
    private SectorAPI observedSector;
    private final Set<CampaignTerrainAPI> observedSeasonal =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public CoopSlipstreamWorld() {
        this(Global::getSector);
    }

    public CoopSlipstreamWorld(Supplier<SectorAPI> sectorSupplier) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
    }

    @Override
    public List<CoopSlipstreamRecord> captureHost() {
        SectorAPI sector = sector();
        Set<CampaignTerrainAPI> seasonal = seasonal(sector);
        List<CoopSlipstreamRecord> records = new ArrayList<>();
        for (CampaignTerrainAPI terrain : hyperspace(sector).getTerrainCopy()) {
            if (eligible(terrain)) {
                records.add(capture(terrain, terrain.getId(), seasonal.contains(terrain)
                        ? CoopSlipstreamRecord.Kind.SEASONAL : CoopSlipstreamRecord.Kind.OTHER));
            }
        }
        records.sort(Comparator.comparing(CoopSlipstreamRecord::hostId));
        return List.copyOf(records);
    }

    @Override
    public Set<String> taggedIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (CampaignTerrainAPI terrain : hyperspace(sector()).getTerrainCopy()) {
            String id = ownedId(terrain);
            if (id != null && terrain.isAlive()) ids.add(id);
        }
        return Set.copyOf(ids);
    }

    @Override
    public void upsert(CoopSlipstreamRecord record) {
        Objects.requireNonNull(record, "record");
        SectorAPI sector = sector();
        LocationAPI hyper = hyperspace(sector);
        CampaignTerrainAPI terrain = null;
        for (CampaignTerrainAPI candidate : hyper.getTerrainCopy()) {
            if (!record.hostId().equals(ownedId(candidate))) continue;
            if (terrain == null && eligible(candidate)
                    && sameGeometry(plugin(candidate), record.segments())) {
                terrain = candidate;
            } else {
                hyper.removeEntity(candidate);
            }
        }

        boolean created = terrain == null;
        SlipstreamTerrainPlugin2 plugin;
        if (created) {
            SlipstreamParams2 params = new SlipstreamParams2();
            restoreParams(params, record.params());
            SectorEntityToken added = hyper.addTerrain(Terrain.SLIPSTREAM, params);
            if (!(added instanceof CampaignTerrainAPI addedTerrain)
                    || !(addedTerrain.getPlugin() instanceof SlipstreamTerrainPlugin2)) {
                if (added != null) hyper.removeEntity(added);
                throw new IllegalStateException("Engine did not create slipstream terrain");
            }
            terrain = addedTerrain;
            plugin = plugin(terrain);
            try {
                for (CoopSlipstreamRecord.Segment segment : record.segments()) {
                    plugin.addSegment(new Vector2f(segment.x(), segment.y()), segment.width());
                }
                apply(terrain, plugin, record, true);
            } catch (RuntimeException | Error failure) {
                hyper.removeEntity(terrain);
                throw failure;
            }
        } else {
            plugin = plugin(terrain);
            restoreParams(plugin.getParams(), record.params());
            apply(terrain, plugin, record, false);
        }
    }

    private static void apply(CampaignTerrainAPI terrain, SlipstreamTerrainPlugin2 plugin,
                              CoopSlipstreamRecord record, boolean created) {
        restoreSegments(plugin, record.segments());
        // recompute initializes the segment indices, directions and lengths used by both fades.
        plugin.recompute();
        restoreFade(plugin, record.fade());
        for (String oldTag : new ArrayList<>(terrain.getTags())) {
            if (!record.terrainTags().contains(oldTag)) terrain.removeTag(oldTag);
        }
        for (String tag : record.terrainTags()) terrain.addTag(tag);
        MemoryAPI memory = terrain.getMemoryWithoutUpdate();
        memory.set(STREAM_ID, record.hostId());
        memory.set(STREAM_KIND, record.kind().name());
        if (created && record.unfinishedSurgeFadeIn()) {
            // The native constructor restarts the sweep. Never restart it on an in-place upsert.
            terrain.addScript(new GenerateSlipsurgeAbility.SlipsurgeFadeInScript(plugin));
            // Its constructor forces every segment out and recalculates bMult. Keep the host's
            // already-progressed faders and intensity while the restarted sweep continues.
            restoreSegments(plugin, record.segments());
        }
        plugin.recomputeEncounterPoints();
    }

    private static void restoreSegments(SlipstreamTerrainPlugin2 plugin,
                                        List<CoopSlipstreamRecord.Segment> sourceSegments) {
        List<SlipstreamSegment> segments = plugin.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            SlipstreamSegment target = segments.get(i);
            CoopSlipstreamRecord.Segment source = sourceSegments.get(i);
            target.width = source.width();
            target.wobbledWidth = target.width - plugin.getParams().edgeWidth * 0.5f;
            target.bMult = source.bMult();
            restoreFader(target.fader, source.fader());
        }
    }

    @Override
    public void despawn(CoopSlipstreamRecord.Despawn record) {
        Objects.requireNonNull(record, "record");
        for (CampaignTerrainAPI terrain : hyperspace(sector()).getTerrainCopy()) {
            if (record.hostId().equals(ownedId(terrain)) && eligible(terrain)) {
                SlipstreamTerrainPlugin2 plugin = plugin(terrain);
                Handles h = handles();
                h.despawnNoise.write(plugin, record.noise());
                h.despawnDelay.write(plugin, record.delay());
                h.despawnDays.write(plugin, record.days());
                h.despawnElapsed.write(plugin, record.elapsed());
            }
        }
    }

    @Override
    public void remove(String id) {
        if (id == null) return;
        LocationAPI hyper = hyperspace(sector());
        for (CampaignTerrainAPI terrain : hyper.getTerrainCopy()) {
            if (id.equals(ownedId(terrain))) hyper.removeEntity(terrain);
        }
    }

    /** Must run before the guest suppressor removes the manager on the first session frame. */
    @Override
    public void sweepSeasonal() {
        SectorAPI sector = sector();
        seasonal(sector);
        LocationAPI hyper = hyperspace(sector);
        for (CampaignTerrainAPI terrain : hyper.getTerrainCopy()) {
            if (observedSeasonal.contains(terrain) && ownedId(terrain) == null) {
                hyper.removeEntity(terrain);
            }
        }
        observedSeasonal.clear();
    }

    public record BrightnessSample(float distance, float brightness) {
    }

    public record TableRow(long campaignTimestamp, CoopSlipstreamRecord stream,
                           List<BrightnessSample> brightness) {
        public TableRow {
            brightness = List.copyOf(brightness);
        }
    }

    /** Read-only bridge sample; native brightness already includes the segment's bMult. */
    public List<TableRow> captureTable() {
        SectorAPI sector = sector();
        long timestamp = sector.getClock().getTimestamp();
        Set<CampaignTerrainAPI> seasonal = seasonal(sector);
        List<TableRow> rows = new ArrayList<>();
        for (CampaignTerrainAPI terrain : hyperspace(sector).getTerrainCopy()) {
            if (!eligible(terrain)) continue;
            String id = ownedId(terrain);
            CoopSlipstreamRecord.Kind kind = seasonal.contains(terrain)
                    ? CoopSlipstreamRecord.Kind.SEASONAL : CoopSlipstreamRecord.Kind.OTHER;
            if (id != null) {
                Object storedKind = terrain.getMemoryWithoutUpdate().get(STREAM_KIND);
                if (storedKind instanceof String name) kind = CoopSlipstreamRecord.Kind.valueOf(name);
            }
            CoopSlipstreamRecord record = capture(terrain, id == null ? terrain.getId() : id, kind);
            SlipstreamTerrainPlugin2 plugin = plugin(terrain);
            List<BrightnessSample> samples = new ArrayList<>();
            float length = plugin.getTotalLength();
            for (float distance = 0; distance < length; distance += 1000f) {
                samples.add(new BrightnessSample(distance, plugin.getFaderBrightness(distance)));
            }
            samples.add(new BrightnessSample(length, plugin.getFaderBrightness(length)));
            rows.add(new TableRow(timestamp, record, samples));
        }
        rows.sort(Comparator.comparing(row -> row.stream().hostId()));
        return List.copyOf(rows);
    }

    public boolean addSeasonal() {
        SectorAPI sector = sector();
        SlipstreamManager manager = manager(sector);
        if (manager == null) return false;
        manager.addStream(sector.getClock().getMonth());
        return true;
    }

    public int despawnSeasonal() {
        return despawnSeasonal(0f, 10f);
    }

    public int despawnSeasonal(float delay, float days) {
        if (!Float.isFinite(delay) || delay < 0 || !Float.isFinite(days) || days <= 0) {
            throw new IllegalArgumentException("Invalid seasonal despawn delay/days");
        }
        int count = 0;
        Random random = new Random();
        for (CampaignTerrainAPI terrain : seasonal(sector())) {
            if (!eligible(terrain) || plugin(terrain).isDespawning()) continue;
            plugin(terrain).despawn(delay, days, random);
            count++;
        }
        return count;
    }

    private SectorAPI sector() {
        SectorAPI sector = sectorSupplier.get();
        if (sector == null) throw new IllegalStateException("Campaign sector is unavailable");
        if (observedSector != sector) {
            observedSector = sector;
            observedSeasonal.clear();
        }
        return sector;
    }

    private static LocationAPI hyperspace(SectorAPI sector) {
        LocationAPI hyper = sector.getHyperspace();
        if (hyper == null) throw new IllegalStateException("Campaign hyperspace is unavailable");
        return hyper;
    }

    private Set<CampaignTerrainAPI> seasonal(SectorAPI sector) {
        Set<CampaignTerrainAPI> current = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean managerPresent = false;
        for (EveryFrameScript script : sector.getScripts()) {
            if (!(script instanceof SlipstreamManager manager)) continue;
            managerPresent = true;
            Object value = handles().active.read(manager);
            if (!(value instanceof List<?> active)) {
                throw new IllegalStateException("SlipstreamManager.active is unavailable");
            }
            for (Object entry : active) {
                if (!(entry instanceof SlipstreamManager.AddedStream added)) {
                    throw new IllegalStateException("Unrecognized seasonal slipstream entry");
                }
                if (added.terrain != null && added.terrain.isAlive()) current.add(added.terrain);
            }
        }
        if (managerPresent) {
            observedSeasonal.clear();
            observedSeasonal.addAll(current);
        }
        return current;
    }

    private static SlipstreamManager manager(SectorAPI sector) {
        for (EveryFrameScript script : sector.getScripts()) {
            if (script instanceof SlipstreamManager manager) return manager;
        }
        return null;
    }

    private static boolean eligible(CampaignTerrainAPI terrain) {
        return terrain != null && terrain.isAlive()
                && terrain.getPlugin() instanceof SlipstreamTerrainPlugin2 plugin && !plugin.isDynamic();
    }

    private static SlipstreamTerrainPlugin2 plugin(CampaignTerrainAPI terrain) {
        return (SlipstreamTerrainPlugin2) terrain.getPlugin();
    }

    private static String ownedId(CampaignTerrainAPI terrain) {
        if (terrain == null || !(terrain.getPlugin() instanceof SlipstreamTerrainPlugin2)) return null;
        Object value = terrain.getMemoryWithoutUpdate().get(STREAM_ID);
        return value instanceof String id && !id.isBlank() ? id : null;
    }

    private static boolean sameGeometry(SlipstreamTerrainPlugin2 plugin,
                                        List<CoopSlipstreamRecord.Segment> wanted) {
        List<SlipstreamSegment> current = plugin.getSegments();
        if (current.size() != wanted.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).loc.x != wanted.get(i).x()
                    || current.get(i).loc.y != wanted.get(i).y()) return false;
        }
        return true;
    }

    private static CoopSlipstreamRecord capture(CampaignTerrainAPI terrain, String id,
                                                CoopSlipstreamRecord.Kind kind) {
        SlipstreamTerrainPlugin2 plugin = plugin(terrain);
        List<CoopSlipstreamRecord.Segment> segments = new ArrayList<>();
        for (SlipstreamSegment segment : plugin.getSegments()) {
            FaderUtil fader = segment.fader;
            segments.add(new CoopSlipstreamRecord.Segment(segment.loc.x, segment.loc.y,
                    segment.width, segment.bMult, new CoopSlipstreamRecord.Fader(fader.getBrightness(),
                    fader.getDurationIn(), fader.getDurationOut(),
                    CoopSlipstreamRecord.FaderState.valueOf(fader.getState().name()),
                    fader.isBounceUp(), fader.isBounceDown())));
        }
        List<String> tags = new ArrayList<>(terrain.getTags());
        tags.sort(Comparator.naturalOrder());
        boolean surge = false;
        for (EveryFrameScript script : terrain.getScripts()) {
            if (script instanceof GenerateSlipsurgeAbility.SlipsurgeFadeInScript && !script.isDone()) {
                surge = true;
                break;
            }
        }
        Handles h = handles();
        CoopSlipstreamRecord.Fade fade = new CoopSlipstreamRecord.Fade(
                (float[]) h.spawnNoise.read(plugin), (float) h.spawnDays.read(plugin),
                (float) h.spawnElapsed.read(plugin), (float[]) h.despawnNoise.read(plugin),
                (float) h.despawnDelay.read(plugin), (float) h.despawnDays.read(plugin),
                (float) h.despawnElapsed.read(plugin));
        return new CoopSlipstreamRecord(id, kind, captureParams(plugin.getParams()), tags,
                segments, fade, surge);
    }

    private static CoopSlipstreamRecord.Params captureParams(SlipstreamParams2 p) {
        return new CoopSlipstreamRecord.Params(p.enteringSlipstreamTextOverride,
                p.enteringSlipstreamTextDurationOverride, p.forceNoWindVisualEffectOnFleets,
                p.spriteKey1, p.spriteKey2, p.spriteKey3, p.edgeKey,
                p.baseWidth, p.widthForMaxSpeed, p.edgeWidth, p.areaPerParticle, p.maxParticles,
                p.minSpeed, p.maxSpeed, p.minDur, p.maxDur, p.particleFadeInTime,
                p.lineLengthFractionOfSpeed, p.burnLevel, p.maxBurnLevelForTextureScroll,
                p.slowDownInWiderSections, p.widthForMaxSpeedMinMult, p.widthForMaxSpeedMaxMult,
                p.accelerationMult, p.name, p.texScrollMult0, p.texScrollMult1, p.texScrollMult2);
    }

    private static void restoreParams(SlipstreamParams2 p, CoopSlipstreamRecord.Params source) {
        p.enteringSlipstreamTextOverride = source.enteringSlipstreamTextOverride();
        p.enteringSlipstreamTextDurationOverride = source.enteringSlipstreamTextDurationOverride();
        p.forceNoWindVisualEffectOnFleets = source.forceNoWindVisualEffectOnFleets();
        p.spriteKey1 = source.spriteKey1();
        p.spriteKey2 = source.spriteKey2();
        p.spriteKey3 = source.spriteKey3();
        p.edgeKey = source.edgeKey();
        p.baseWidth = source.baseWidth();
        p.widthForMaxSpeed = source.widthForMaxSpeed();
        p.edgeWidth = source.edgeWidth();
        p.areaPerParticle = source.areaPerParticle();
        p.maxParticles = source.maxParticles();
        p.minSpeed = source.minSpeed();
        p.maxSpeed = source.maxSpeed();
        p.minDur = source.minDur();
        p.maxDur = source.maxDur();
        p.particleFadeInTime = source.particleFadeInTime();
        p.lineLengthFractionOfSpeed = source.lineLengthFractionOfSpeed();
        p.burnLevel = source.burnLevel();
        p.maxBurnLevelForTextureScroll = source.maxBurnLevelForTextureScroll();
        p.slowDownInWiderSections = source.slowDownInWiderSections();
        p.widthForMaxSpeedMinMult = source.widthForMaxSpeedMinMult();
        p.widthForMaxSpeedMaxMult = source.widthForMaxSpeedMaxMult();
        p.accelerationMult = source.accelerationMult();
        p.name = source.name();
        p.texScrollMult0 = source.texScrollMult0();
        p.texScrollMult1 = source.texScrollMult1();
        p.texScrollMult2 = source.texScrollMult2();
    }

    private static void restoreFader(FaderUtil target, CoopSlipstreamRecord.Fader source) {
        target.setDurationIn(source.durationIn());
        target.setDurationOut(source.durationOut());
        target.setBrightness(source.brightness());
        target.setState(FaderUtil.State.valueOf(source.state().name()));
        target.setBounce(source.bounceUp(), source.bounceDown());
    }

    private static void restoreFade(SlipstreamTerrainPlugin2 plugin, CoopSlipstreamRecord.Fade fade) {
        Handles h = handles();
        h.spawnNoise.write(plugin, fade.spawnNoise());
        h.spawnDays.write(plugin, fade.spawnDays());
        h.spawnElapsed.write(plugin, fade.spawnElapsed());
        h.despawnNoise.write(plugin, fade.despawnNoise());
        h.despawnDelay.write(plugin, fade.despawnDelay());
        h.despawnDays.write(plugin, fade.despawnDays());
        h.despawnElapsed.write(plugin, fade.despawnElapsed());
    }

    private static synchronized Handles handles() {
        if (handles == null) {
            try {
                handles = new Handles();
            } catch (Throwable failure) {
                throw new IllegalStateException("Cannot bind slipstream engine fields", failure);
            }
        }
        return handles;
    }

    private static final class Handles {
        final Field spawnNoise, spawnDays, spawnElapsed;
        final Field despawnNoise, despawnDelay, despawnDays, despawnElapsed;
        final Field active;

        Handles() throws Throwable {
            MethodHandles.Lookup pluginLookup = MethodHandles.privateLookupIn(
                    SlipstreamTerrainPlugin2.class, MethodHandles.lookup());
            spawnNoise = new Field(pluginLookup, SlipstreamTerrainPlugin2.class, "spawnNoise", float[].class);
            spawnDays = new Field(pluginLookup, SlipstreamTerrainPlugin2.class, "spawnDays", float.class);
            spawnElapsed = new Field(pluginLookup, SlipstreamTerrainPlugin2.class, "spawnElapsed", float.class);
            despawnNoise = new Field(pluginLookup, SlipstreamTerrainPlugin2.class, "despawnNoise", float[].class);
            despawnDelay = new Field(pluginLookup, SlipstreamTerrainPlugin2.class, "despawnDelay", float.class);
            despawnDays = new Field(pluginLookup, SlipstreamTerrainPlugin2.class, "despawnDays", float.class);
            despawnElapsed = new Field(pluginLookup, SlipstreamTerrainPlugin2.class, "despawnElapsed", float.class);
            MethodHandles.Lookup managerLookup = MethodHandles.privateLookupIn(
                    SlipstreamManager.class, MethodHandles.lookup());
            active = new Field(managerLookup, SlipstreamManager.class, "active", List.class);
        }
    }

    private static final class Field {
        final String name;
        final MethodHandle getter;
        final MethodHandle setter;

        Field(MethodHandles.Lookup lookup, Class<?> type, String name, Class<?> fieldType)
                throws Throwable {
            this.name = name;
            getter = lookup.findGetter(type, name, fieldType);
            setter = lookup.findSetter(type, name, fieldType);
        }

        Object read(Object owner) {
            try {
                return getter.invoke(owner);
            } catch (Throwable failure) {
                throw new IllegalStateException("Cannot read slipstream field " + name, failure);
            }
        }

        void write(Object owner, Object value) {
            try {
                setter.invoke(owner, value);
            } catch (Throwable failure) {
                throw new IllegalStateException("Cannot restore slipstream field " + name, failure);
            }
        }
    }
}
