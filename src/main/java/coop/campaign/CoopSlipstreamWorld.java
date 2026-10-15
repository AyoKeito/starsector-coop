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
import coop.ambient.CoopSlipstreamFadeBridge;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/** Campaign-thread engine adapter. Only primitive ids and kinds are written into saved memory. */
public final class CoopSlipstreamWorld implements CoopSlipstreamAuthority.StreamWorld {
    public static final String STREAM_ID = "$coopStreamId";
    public static final String STREAM_KIND = "$coopStreamKind";
    public static final String FADE_ORIGIN = "$coopStreamFadeOrigin";
    public static final String FADE_TICK = "$coopStreamFadeTick";
    public static final String FADE_CAPTURE = "$coopStreamFadeCapture";
    public static final String FADE_PAUSED = "$coopStreamFadePaused";
    public static final String FADE_HYPERSPACE = "$coopStreamFadeHyperspace";

    private static SectorAPI controlledSector;
    private static boolean controllingHost;
    private static boolean frozen;
    private static final Map<SlipstreamTerrainPlugin2, Controlled> CONTROLS = new IdentityHashMap<>();
    private static final Map<String, List<CoopSlipstreamRecord>> PENDING = new LinkedHashMap<>();

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

    /** Global transient state is shared by the pump's adapter and read-only bridge adapter views. */
    public static void tickControlled(SectorAPI sector, boolean host, boolean holdForGrace) {
        tickControlled(sector, host, holdForGrace, null);
    }

    public static void tickControlled(SectorAPI sector, boolean host, boolean holdForGrace,
                                      Boolean authoritativeHostPaused) {
        if (sector == null) return;
        selectControlledSector(sector);
        controllingHost = host;
        frozen = holdForGrace;
        List<CampaignTerrainAPI> terrains = hyperspace(sector).getTerrainCopy();
        Set<CampaignTerrainAPI> live = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CampaignTerrainAPI terrain : terrains) {
            if (eligible(terrain) && (host || ownedId(terrain) != null)) live.add(terrain);
        }
        for (Controlled old : new ArrayList<>(CONTROLS.values())) {
            if (!live.contains(old.terrain) || old.host != host) release(old.terrain, true);
        }
        if (sector.getClock() == null) {
            if (!live.isEmpty()) throw new IllegalStateException("Controlled slipstreams require a campaign clock");
            return;
        }
        long timestamp = sector.getClock().getTimestamp();
        boolean paused = sector.isPaused();
        boolean inHyperspace = host && sector.getCurrentLocation() == sector.getHyperspace();
        for (CampaignTerrainAPI terrain : live) {
            SlipstreamTerrainPlugin2 plugin = plugin(terrain);
            Controlled controlled = CONTROLS.get(plugin);
            if (controlled == null) {
                CoopSlipstreamRecord.Timeline timeline = savedTimeline(terrain, timestamp, paused, inHyperspace);
                controlled = register(terrain, host, timeline);
            }
            if (holdForGrace) continue;
            // Only the host adopts native ability/manager commands. Guest state comes from wire
            // checkpoints, and the native fork has already skipped its uncontrolled raw step.
            if (host && (!controlled.port.capture().equals(controlled.driver.state())
                    || controlled.driver.hostInHyperspace() != inHyperspace)) {
                controlled.driver.adoptCommand(timestamp, paused, inHyperspace);
            }
            if (!host && authoritativeHostPaused != null) controlled.driver.setHostPauseInput(authoritativeHostPaused);
            controlled.driver.advanceTo(timestamp, !host);
            storeTimeline(controlled, timestamp, host ? paused : controlled.driver.authoritativePaused());
        }
        if (!host && !holdForGrace) {
            for (String id : new ArrayList<>(PENDING.keySet())) {
                List<CoopSlipstreamRecord> queue = PENDING.get(id);
                CoopSlipstreamRecord checkpoint = null;
                while (!queue.isEmpty() && queue.get(0).timeline().committedCampaignMillis() <= timestamp) {
                    checkpoint = queue.remove(0);
                }
                if (checkpoint != null) {
                    List<CoopSlipstreamRecord> future = new ArrayList<>(queue);
                    PENDING.remove(id);
                    new CoopSlipstreamWorld(() -> sector).upsert(checkpoint);
                    if (!future.isEmpty()) PENDING.put(id, future);
                    Controlled applied = findById(checkpoint.hostId());
                    if (applied != null) {
                        if (authoritativeHostPaused != null) applied.driver.setHostPauseInput(authoritativeHostPaused);
                        applied.driver.advanceTo(timestamp, true);
                        storeTimeline(applied, timestamp, applied.driver.authoritativePaused());
                    }
                }
            }
        }
    }

    /** Register saved canonical terrain before its first native campaign advance after loading. */
    public static void prepareLoad(SectorAPI sector) {
        if (sector == null || sector.getClock() == null) return;
        selectControlledSector(sector);
        for (CampaignTerrainAPI terrain : hyperspace(sector).getTerrainCopy()) {
            if (!eligible(terrain) || !(terrain.getMemoryWithoutUpdate().get(FADE_ORIGIN) instanceof Number)) continue;
            CoopSlipstreamRecord.Timeline timeline = savedTimeline(terrain,
                    sector.getClock().getTimestamp(), sector.isPaused(),
                    sector.getCurrentLocation() == sector.getHyperspace());
            register(terrain, ownedId(terrain) == null, timeline);
        }
    }

    /** A configured peer may spend many paused frames in its lobby before the handshake completes. */
    public static void holdLoadedControlled(SectorAPI sector) {
        if (sector != null) selectControlledSector(sector);
        frozen = true;
    }

    /** No plugin, sector, cursor or transport references survive a solo/session/load boundary. */
    public static void releaseControlled() {
        for (Controlled controlled : new ArrayList<>(CONTROLS.values())) release(controlled.terrain, true);
        CONTROLS.clear();
        PENDING.clear();
        CoopSlipstreamFadeBridge.clear();
        controlledSector = null;
        controllingHost = false;
        frozen = false;
    }

    private static void selectControlledSector(SectorAPI sector) {
        if (controlledSector != sector) {
            releaseControlled();
            controlledSector = sector;
        }
    }

    private static Controlled register(CampaignTerrainAPI terrain, boolean host,
                                       CoopSlipstreamRecord.Timeline timeline) {
        SlipstreamTerrainPlugin2 plugin = plugin(terrain);
        CoopSlipstreamFadeBridge.register(plugin);
        NativeFadePort port = new NativeFadePort(terrain, host);
        Controlled controlled = new Controlled(terrain, host, port,
                new CoopSlipstreamFadeDriver<>(port, timeline));
        CONTROLS.put(plugin, controlled);
        return controlled;
    }

    private static Controlled findById(String id) {
        for (Controlled controlled : CONTROLS.values()) if (id.equals(ownedId(controlled.terrain))) return controlled;
        return null;
    }

    private static void release(CampaignTerrainAPI terrain, boolean forgetTimeline) {
        if (!(terrain.getPlugin() instanceof SlipstreamTerrainPlugin2 plugin)) return;
        if (CONTROLS.remove(plugin) != null) CoopSlipstreamFadeBridge.unregister(plugin);
        if (forgetTimeline) {
            MemoryAPI memory = terrain.getMemoryWithoutUpdate();
            for (String key : List.of(FADE_ORIGIN, FADE_TICK, FADE_CAPTURE, FADE_PAUSED, FADE_HYPERSPACE)) memory.unset(key);
        }
    }

    private static CoopSlipstreamRecord.Timeline savedTimeline(CampaignTerrainAPI terrain,
            long timestamp, boolean paused, boolean inHyperspace) {
        MemoryAPI memory = terrain.getMemoryWithoutUpdate();
        Object origin = memory.get(FADE_ORIGIN), tick = memory.get(FADE_TICK);
        if (!(origin instanceof Number o) || !(tick instanceof Number t)) {
            return CoopSlipstreamRecord.Timeline.atOrigin(timestamp, paused, inHyperspace);
        }
        long committed = Math.addExact(o.longValue(), Math.multiplyExact(t.longValue(),
                CoopSlipstreamFadeDriver.QUANTUM_CAMPAIGN_MILLIS));
        Object captured = memory.get(FADE_CAPTURE);
        long capture = Math.max(committed, captured instanceof Number c ? c.longValue() : timestamp);
        return new CoopSlipstreamRecord.Timeline(o.longValue(), t.longValue(), capture,
                memory.get(FADE_PAUSED) instanceof Boolean p ? p : paused,
                memory.get(FADE_HYPERSPACE) instanceof Boolean h ? h : inHyperspace);
    }

    private static void storeTimeline(Controlled controlled, long timestamp, boolean paused) {
        CoopSlipstreamRecord.Timeline timeline = controlled.driver.timeline(timestamp, paused);
        MemoryAPI memory = controlled.terrain.getMemoryWithoutUpdate();
        memory.set(FADE_ORIGIN, timeline.originCampaignMillis());
        memory.set(FADE_TICK, timeline.committedTick());
        memory.set(FADE_CAPTURE, timeline.captureCampaignMillis());
        memory.set(FADE_PAUSED, timeline.hostPaused());
        memory.set(FADE_HYPERSPACE, timeline.hostInHyperspace());
    }

    private record Controlled(CampaignTerrainAPI terrain, boolean host, NativeFadePort port,
                              CoopSlipstreamFadeDriver<NativeFadeState> driver) { }

    private record NativeFadeState(List<CoopSlipstreamRecord.Fader> faders, List<Float> intensity,
                                   CoopSlipstreamRecord.Fade fade,
                                   CoopSlipstreamFadeBridge.SurgeWaveState wave) { }

    private static final class NativeFadePort implements CoopSlipstreamFadeDriver.Port<NativeFadeState> {
        private final CampaignTerrainAPI terrain;
        private final boolean host;
        private NativeFadePort(CampaignTerrainAPI terrain, boolean host) {
            this.terrain = terrain;
            this.host = host;
        }
        @Override public NativeFadeState capture() {
            SlipstreamTerrainPlugin2 plugin = plugin(terrain);
            List<CoopSlipstreamRecord.Fader> faders = new ArrayList<>();
            List<Float> intensity = new ArrayList<>();
            for (SlipstreamSegment segment : plugin.getSegments()) {
                faders.add(captureFader(segment.fader));
                intensity.add(segment.bMult);
            }
            return new NativeFadeState(List.copyOf(faders), List.copyOf(intensity), readFade(plugin),
                    CoopSlipstreamFadeBridge.captureSurgeWave(plugin));
        }
        @Override public void restore(NativeFadeState state) {
            SlipstreamTerrainPlugin2 plugin = plugin(terrain);
            CoopSlipstreamFadeBridge.restoreSurgeWave(plugin, state.wave());
            List<SlipstreamSegment> segments = plugin.getSegments();
            if (segments.size() != state.faders().size()) throw new IllegalStateException("Controlled stream geometry changed");
            for (int i = 0; i < segments.size(); i++) {
                restoreFader(segments.get(i).fader, state.faders().get(i));
                segments.get(i).bMult = state.intensity().get(i);
            }
            restoreFade(plugin, state.fade());
        }
        @Override public void advance(float seconds, boolean hostInHyperspace) {
            CoopSlipstreamFadeBridge.advanceControlled(plugin(terrain), seconds, hostInHyperspace, host);
        }
    }

    @Override
    public List<CoopSlipstreamRecord> captureHost() {
        SectorAPI sector = sector();
        tickControlled(sector, true, false);
        Set<CampaignTerrainAPI> seasonal = seasonal(sector);
        List<CoopSlipstreamRecord> records = new ArrayList<>();
        for (CampaignTerrainAPI terrain : hyperspace(sector).getTerrainCopy()) {
            if (eligible(terrain)) {
                records.add(capture(sector, terrain, terrain.getId(), seasonal.contains(terrain)
                        ? CoopSlipstreamRecord.Kind.SEASONAL : CoopSlipstreamRecord.Kind.OTHER));
            }
        }
        records.sort(Comparator.comparing(CoopSlipstreamRecord::hostId));
        return List.copyOf(records);
    }

    @Override
    public Set<String> taggedIds() {
        Set<String> ids = new LinkedHashSet<>(PENDING.keySet());
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
        selectControlledSector(sector);
        long timestamp = sector.getClock().getTimestamp();
        if (!record.timeline().hostPaused()
                && record.timeline().committedCampaignMillis() > timestamp) {
            List<CoopSlipstreamRecord> queue = PENDING.computeIfAbsent(record.hostId(), ignored -> new ArrayList<>());
            if (!queue.isEmpty() && queue.get(queue.size() - 1).timeline().committedCampaignMillis()
                    == record.timeline().committedCampaignMillis()) queue.remove(queue.size() - 1);
            queue.add(record);
            // Preserve the first future anchor until reachable; replacing it with each new host
            // checkpoint would starve a guest whose accepted clock offset stays slightly behind.
            while (queue.size() > 8) queue.remove(1);
            return;
        }
        PENDING.remove(record.hostId());
        LocationAPI hyper = hyperspace(sector);
        CampaignTerrainAPI terrain = null;
        for (CampaignTerrainAPI candidate : hyper.getTerrainCopy()) {
            if (!record.hostId().equals(ownedId(candidate))) continue;
            if (terrain == null && eligible(candidate)
                    && sameGeometry(plugin(candidate), record.segments())) {
                terrain = candidate;
            } else {
                release(candidate, true);
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
        CoopSlipstreamFadeBridge.register(plugin);
        NativeFadePort port = new NativeFadePort(terrain, false);
        Controlled controlled = new Controlled(terrain, false, port,
                new CoopSlipstreamFadeDriver<>(port, record.timeline()));
        CONTROLS.put(plugin, controlled);
        storeTimeline(controlled, timestamp, record.timeline().hostPaused());
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
        CoopSlipstreamRecord.SurgeWaveState wave = record.surgeWave();
        CoopSlipstreamFadeBridge.restoreSurgeWave(plugin,
                new CoopSlipstreamFadeBridge.SurgeWaveState(wave.present(), wave.done(), wave.index(),
                        wave.elapsed(), wave.maxElapsed()));
        restoreSegments(plugin, record.segments());
        restoreFade(plugin, record.fade());
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
        PENDING.remove(id);
        LocationAPI hyper = hyperspace(sector());
        for (CampaignTerrainAPI terrain : hyper.getTerrainCopy()) {
            if (id.equals(ownedId(terrain))) {
                release(terrain, true);
                hyper.removeEntity(terrain);
            }
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
                release(terrain, true);
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
            CoopSlipstreamRecord record = capture(sector, terrain, id == null ? terrain.getId() : id, kind);
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
        return addSeasonal(false).created();
    }

    /** Diagnostics for the explicit bridge trigger, never for normal manager advancement. */
    public record SeasonalAddResult(boolean invoked, boolean created, boolean rearmedUsedRoute,
                                    boolean configPresent, String routeId, int configuredRoutes,
                                    int unusedBefore, int unusedAfter, List<String> newIds,
                                    String reason) {
        public SeasonalAddResult {
            newIds = List.copyOf(newIds);
        }
    }

    /**
     * Calls the native builder. The optional QA setup reuses one exhausted route only after the
     * season's old terrain has gone; every generated point, parameter and fade remains native.
     */
    public SeasonalAddResult addSeasonal(boolean rearmUsedRoute) {
        SectorAPI sector = sector();
        SlipstreamManager manager = manager(sector);
        if (manager == null) throw new IllegalStateException("No live SlipstreamManager on the host");
        SlipstreamManager.StreamConfig config = seasonalConfig(manager);
        List<SlipstreamManager.StreamData> routes = config == null ? List.of() : List.copyOf(config.streams);
        int unusedBefore = unusedRoutes(routes);
        SlipstreamManager.StreamData rearmed = null;
        if (rearmUsedRoute) {
            if (!seasonal(sector).isEmpty()) {
                throw new IllegalStateException("Cannot rearm a used route while live seasonal streams remain");
            }
            if (config == null || routes.isEmpty()) {
                throw new IllegalStateException("Cannot rearm a used route: no nonempty native stream configuration");
            }
            if (unusedBefore != 0) {
                throw new IllegalStateException("Native stream configuration still has unused routes; use the default trigger");
            }
            // Native configs have unique letter ids. Stable stream order also breaks any id ties.
            rearmed = routes.stream().filter(route -> route.wasUsed)
                    .min(Comparator.comparing((SlipstreamManager.StreamData route) -> route.id,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElseThrow(() -> new IllegalStateException("Native stream configuration has no used route"));
        }
        LocationAPI hyper = hyperspace(sector);
        Set<CampaignTerrainAPI> before = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> beforeIds = new LinkedHashSet<>();
        for (CampaignTerrainAPI terrain : hyper.getTerrainCopy()) {
            before.add(terrain);
            beforeIds.add(terrain.getId());
        }
        String routeId = rearmed == null ? null : rearmed.id;
        if (rearmed == null) {
            manager.addStream(sector.getClock().getMonth());
        } else {
            boolean originalWasUsed = rearmed.wasUsed;
            rearmed.wasUsed = false;
            try {
                manager.addStream(sector.getClock().getMonth());
            } finally {
                rearmed.wasUsed = originalWasUsed;
            }
        }
        List<String> newIds = new ArrayList<>();
        for (CampaignTerrainAPI terrain : hyper.getTerrainCopy()) {
            if (!before.contains(terrain) && eligible(terrain) && !beforeIds.contains(terrain.getId())) {
                String id = terrain.getId();
                if (id == null || id.isBlank() || newIds.contains(id)) {
                    throw new IllegalStateException("Native stream builder produced an invalid or duplicate terrain id");
                }
                newIds.add(id);
                if (controlledSector == sector && controllingHost) {
                    register(terrain, true, CoopSlipstreamRecord.Timeline.atOrigin(
                            sector.getClock().getTimestamp(), sector.isPaused(),
                            sector.getCurrentLocation() == sector.getHyperspace()));
                }
            }
        }
        newIds.sort(Comparator.naturalOrder());
        if (rearmUsedRoute && newIds.isEmpty()) {
            throw new IllegalStateException("Rearmed native route " + routeId
                    + " produced no new live slipstream; native generation may have discarded the terrain");
        }
        String reason = !newIds.isEmpty() ? "created" : config == null ? "config_absent"
                : routes.isEmpty() ? "no_routes" : unusedBefore == 0 ? "routes_exhausted"
                : "native_builder_returned_without_live_stream";
        return new SeasonalAddResult(true, !newIds.isEmpty(), rearmUsedRoute, config != null,
                routeId, routes.size(), unusedBefore, unusedRoutes(routes), newIds, reason);
    }

    private static int unusedRoutes(List<SlipstreamManager.StreamData> routes) {
        int unused = 0;
        for (SlipstreamManager.StreamData route : routes) if (!route.wasUsed) unused++;
        return unused;
    }

    /** Bound only by the explicit debug action; normal replication never reads this field. */
    private static SlipstreamManager.StreamConfig seasonalConfig(SlipstreamManager manager) {
        try {
            MethodHandle getter = MethodHandles.privateLookupIn(SlipstreamManager.class, MethodHandles.lookup())
                    .findGetter(SlipstreamManager.class, "config", SlipstreamManager.StreamConfig.class);
            return (SlipstreamManager.StreamConfig) getter.invoke(manager);
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot read native slipstream configuration", failure);
        }
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
        SectorAPI sector = sector();
        for (CampaignTerrainAPI terrain : seasonal(sector)) {
            if (!eligible(terrain) || plugin(terrain).isDespawning()) continue;
            plugin(terrain).despawn(delay, days, random);
            Controlled controlled = CONTROLS.get(plugin(terrain));
            if (controlled != null && controlled.host) {
                controlled.driver.adoptCommand(sector.getClock().getTimestamp(), sector.isPaused(),
                        sector.getCurrentLocation() == sector.getHyperspace());
                storeTimeline(controlled, sector.getClock().getTimestamp(), sector.isPaused());
            }
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

    private static CoopSlipstreamRecord capture(SectorAPI sector, CampaignTerrainAPI terrain, String id,
                                                CoopSlipstreamRecord.Kind kind) {
        SlipstreamTerrainPlugin2 plugin = plugin(terrain);
        List<CoopSlipstreamRecord.Segment> segments = new ArrayList<>();
        for (SlipstreamSegment segment : plugin.getSegments()) {
            FaderUtil fader = segment.fader;
            segments.add(new CoopSlipstreamRecord.Segment(segment.loc.x, segment.loc.y,
                    segment.width, segment.bMult, captureFader(fader)));
        }
        List<String> tags = new ArrayList<>(terrain.getTags());
        tags.sort(Comparator.naturalOrder());
        CoopSlipstreamFadeBridge.SurgeWaveState wave = CoopSlipstreamFadeBridge.captureSurgeWave(plugin);
        Controlled controlled = CONTROLS.get(plugin);
        long timestamp = sector.getClock().getTimestamp();
        CoopSlipstreamRecord.Timeline timeline = controlled == null
                ? CoopSlipstreamRecord.Timeline.atOrigin(timestamp, sector.isPaused(),
                    sector.getCurrentLocation() == sector.getHyperspace())
                : controlled.driver.timeline(timestamp,
                    controlled.host ? sector.isPaused() : controlled.driver.authoritativePaused());
        return new CoopSlipstreamRecord(id, kind, captureParams(plugin.getParams()), tags,
                segments, readFade(plugin), timeline,
                new CoopSlipstreamRecord.SurgeWaveState(wave.present(), wave.done(), wave.index(),
                        wave.elapsed(), wave.maxElapsed()));
    }

    private static CoopSlipstreamRecord.Fader captureFader(FaderUtil fader) {
        return new CoopSlipstreamRecord.Fader(fader.getBrightness(), fader.getDurationIn(),
                fader.getDurationOut(), CoopSlipstreamRecord.FaderState.valueOf(fader.getState().name()),
                fader.isBounceUp(), fader.isBounceDown());
    }

    private static CoopSlipstreamRecord.Fade readFade(SlipstreamTerrainPlugin2 plugin) {
        Handles h = handles();
        return new CoopSlipstreamRecord.Fade(
                (float[]) h.spawnNoise.read(plugin), (float) h.spawnDays.read(plugin),
                (float) h.spawnElapsed.read(plugin), (float[]) h.despawnNoise.read(plugin),
                (float) h.despawnDelay.read(plugin), (float) h.despawnDays.read(plugin),
                (float) h.despawnElapsed.read(plugin));
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
