package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin.AbyssalLightParams;
import com.fs.starfarer.api.impl.campaign.abilities.InterdictionPulseAbility;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.ambient.CoopLightBridge;
import coop.fleet.CoopGuestMirrorHandle;
import coop.net.CoopJson;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Campaign adapter for resolved native lights and actor-owned, durable consumption results. */
public final class CoopLightWorld implements CoopLightAuthority.LightWorld {
    public static final String LIGHT_ID_KEY = CoopLightBridge.LIGHT_ID_KEY;
    public static final String DWELLER_FLEET_LIGHT_KEY = CoopLightBridge.ENCOUNTER_FLEET_KEY;
    public static final String STATE_KEY = "$coopLightState";
    public static final String HOST_ENTITY_ID_KEY = "$coopLightHostEntityId";
    private static final int MAX_PENDING = 1_024;
    private static final int MAX_HISTORY = 32_768;
    private static final int MAX_SAVED_CHARACTERS = 16 * 1024 * 1024;

    private final Supplier<SectorAPI> sectorSupplier;
    private final Supplier<CampaignFleetAPI> remoteActorSupplier;
    private final NativeCodec codec;
    private boolean guest;
    private String localActorId;
    private CoopLightBridge.Handler bridgeHandler;
    private Predicate<SectorEntityToken> dwellerDialogGate = entity -> true;
    private BiPredicate<String, String> dwellerClaimAllowed = (lightId, actorId) -> true;
    private SectorAPI cachedSector;
    private String cachedText;
    private State cachedState;

    interface NativeCodec {
        CoopLightRecord capture(CustomCampaignEntityAPI entity, String id);
        CustomCampaignEntityAPI create(LocationAPI location, CoopLightRecord record);
        void apply(CustomCampaignEntityAPI entity, CoopLightRecord record);
        boolean isDespawning(CustomCampaignEntityAPI entity);
        void despawn(CustomCampaignEntityAPI entity, boolean expand);
        float pulseRange(CampaignFleetAPI actor);
        float brightness(CampaignFleetAPI actor, CustomCampaignEntityAPI entity);
        void grant(CampaignFleetAPI actor, boolean sensorBurst, float bonus, int points);
    }

    private static final NativeCodec ENGINE_CODEC = new NativeCodec() {
        @Override public CoopLightRecord capture(CustomCampaignEntityAPI entity, String id) {
            AbyssalLightParams p = plugin(entity).getParams();
            Object seed = entity.getMemoryWithoutUpdate().get(MemFlags.SALVAGE_SEED);
            Object hostId = entity.getMemoryWithoutUpdate().get(HOST_ENTITY_ID_KEY);
            if (hostId != null && !(hostId instanceof String)) throw new IllegalStateException("Saved light native identity is not text");
            boolean dweller = entity.hasTag(Tags.DWELLER_LIGHT);
            if (dweller && !(seed instanceof Number)) throw new IllegalStateException("Dweller light has no native salvage seed");
            return new CoopLightRecord(CoopLightRecord.VERSION, id, hostId == null ? entity.getId() : (String) hostId,
                    entity.getLocation().x, entity.getLocation().y, p.color.getRGB(),
                    p.frequencyMultMin, p.frequencyMultMax, p.frequencyChangeMult,
                    p.size, p.detectedRange, p.bonusMult, Math.max(0f, p.durationDays),
                    p.pointsOverride, dweller, seed instanceof Number n ? n.longValue() : 0L);
        }

        @Override public CustomCampaignEntityAPI create(LocationAPI location, CoopLightRecord record) {
            CustomCampaignEntityAPI entity = (CustomCampaignEntityAPI) location.addCustomEntity(
                    "cooplight_" + record.id().substring(6), null, Entities.ABYSSAL_LIGHT,
                    Factions.NEUTRAL, params(record));
            apply(entity, record);
            return entity;
        }

        @Override public void apply(CustomCampaignEntityAPI entity, CoopLightRecord r) {
            AbyssalLightParams p = plugin(entity).getParams();
            copyParams(p, r);
            entity.setLocation(r.x(), r.y());
            float radiusMult = 1f + (r.bonusMult() - 1f) * 0.33f;
            entity.setRadius(entity.getCustomEntitySpec().getDefaultRadius() * radiusMult);
            entity.getDetectedRangeMod().modifyFlat("gen", r.detectedRange());
            if (r.dweller()) {
                entity.addTag(Tags.DWELLER_LIGHT);
                entity.getMemoryWithoutUpdate().set(MemFlags.SALVAGE_SEED, r.salvageSeed());
            } else {
                entity.removeTag(Tags.DWELLER_LIGHT);
                entity.getMemoryWithoutUpdate().unset(MemFlags.SALVAGE_SEED);
            }
        }

        @Override public boolean isDespawning(CustomCampaignEntityAPI entity) { return plugin(entity).isDespawning(); }
        @Override public void despawn(CustomCampaignEntityAPI entity, boolean expand) {
            CoopLightBridge.despawnAuthoritatively(entity, expand);
        }
        @Override public float pulseRange(CampaignFleetAPI actor) { return InterdictionPulseAbility.getRange(actor); }
        @Override public float brightness(CampaignFleetAPI actor, CustomCampaignEntityAPI entity) {
            return AbyssalLightEntityPlugin.getProximityBasedBrightnessFactor(actor, entity.getLocation());
        }
        @Override public void grant(CampaignFleetAPI actor, boolean sensorBurst, float bonus, int points) {
            AbyssalLightEntityPlugin.coopGrantPulseBonus(actor, sensorBurst, bonus, points);
        }
    };

    public CoopLightWorld() { this(Global::getSector); }

    public CoopLightWorld(Supplier<SectorAPI> sectorSupplier) {
        this(sectorSupplier, CoopGuestMirrorHandle::current, ENGINE_CODEC);
    }

    CoopLightWorld(Supplier<SectorAPI> sectorSupplier, Supplier<CampaignFleetAPI> remoteActorSupplier, NativeCodec codec) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
        this.remoteActorSupplier = Objects.requireNonNull(remoteActorSupplier, "remoteActorSupplier");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public void installBridge(boolean guest, String localActorId) {
        installBridge(guest, localActorId, entity -> true);
    }

    public void installBridge(boolean guest, String localActorId, Predicate<SectorEntityToken> gate) {
        this.guest = guest;
        this.localActorId = CoopLightRecord.identity(localActorId, "local light actor");
        this.dwellerDialogGate = Objects.requireNonNull(gate, "dweller dialog gate");
        if (bridgeHandler == null) bridgeHandler = new CoopLightBridge.Handler() {
            @Override public void requestPulse(SectorEntityToken light, boolean sensorBurst) {
                queueLocal(light, sensorBurst ? CoopLightCodec.Action.SENSOR_BURST : CoopLightCodec.Action.PULSE);
            }
            @Override public void requestDwellerConsumption(SectorEntityToken light) {
                queueLocal(light, CoopLightCodec.Action.DWELLER);
            }
            @Override public boolean allowDwellerDialog(SectorEntityToken light) {
                String id = ownedId(light);
                return id != null && isAvailable(id) && dwellerDialogGate.test(light);
            }
        };
        CoopLightBridge.install(guest, bridgeHandler);
    }

    public void resetBridge() {
        if (bridgeHandler != null) CoopLightBridge.uninstall(bridgeHandler);
        bridgeHandler = null;
        localActorId = null;
    }

    /** Transient session arbitration only; durable results remain authoritative across retries. */
    public void setDwellerClaimPolicy(BiPredicate<String, String> allowed) {
        dwellerClaimAllowed = Objects.requireNonNull(allowed, "dweller claim policy");
    }

    private void queueLocal(SectorEntityToken light, CoopLightCodec.Action action) {
        String id = ownedId(light);
        if (id == null || localActorId == null || !isAvailable(id)) return;
        State state = state();
        if (state.pending.values().stream().anyMatch(request -> request.lightId().equals(id))) return;
        if (state.pending.size() >= MAX_PENDING) throw new IllegalStateException("Light pending-request limit reached");
        CoopLightCodec.Request request = new CoopLightCodec.Request(id, UUID.randomUUID().toString(), action);
        state.pending.put(request.requestId(), request);
        save(state);
        if (!guest) acceptOutcome(applyRequest(request, localActorId));
    }

    @Override public List<CoopLightRecord> captureHost() {
        SectorAPI sector = sectorSupplier.get();
        if (sector == null || sector.getHyperspace() == null) return null;
        // A native reward failure or a save taken with a pending local operation can retry the
        // already committed Host result without authorizing or granting it a second time.
        if (!guest && localActorId != null) {
            for (CoopLightCodec.Request request : pendingRequests()) acceptOutcome(applyRequest(request, localActorId));
        }
        State state = state();
        List<CoopLightRecord> records = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SectorEntityToken token : entities()) {
            if (!isNativeHyperspaceLight(token)) continue;
            CustomCampaignEntityAPI entity = (CustomCampaignEntityAPI) token;
            String id = ownedId(entity);
            if (id != null && !seen.add(id)) throw new IllegalStateException("Duplicate Host light id " + id);
            if (!entity.isAlive() || codec.isDespawning(entity)) continue;
            if (id == null) {
                id = "light:" + UUID.randomUUID();
                entity.getMemoryWithoutUpdate().set(LIGHT_ID_KEY, id);
                seen.add(id);
            }
            if (state.consumed.contains(id)) codec.despawn(entity, false);
            else records.add(codec.capture(entity, id));
        }
        records.sort(Comparator.comparing(CoopLightRecord::id));
        // The wire cap must be checked before this can become an authoritative full set.
        CoopLightCodec.encodeSet(records);
        return List.copyOf(records);
    }

    @Override public Set<String> taggedIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (SectorEntityToken entity : entities()) {
            if (!isNativeHyperspaceLight(entity)) continue;
            String id = ownedId(entity);
            if (id != null && !ids.add(id)) throw new IllegalStateException("Duplicate local light id " + id);
        }
        return Collections.unmodifiableSet(ids);
    }

    @Override public void upsert(CoopLightRecord record) {
        Objects.requireNonNull(record, "record");
        if (state().consumed.contains(record.id())) return;
        SectorEntityToken target = interactionTarget();
        if (isNativeHyperspaceLight(target) && ownedId(target) == null) {
            throw new IllegalStateException("Deferring light migration during a legacy interaction");
        }
        CustomCampaignEntityAPI entity = find(record.id());
        if (entity != null) {
            if (!codec.isDespawning(entity)) {
                codec.apply(entity, record);
                entity.getMemoryWithoutUpdate().set(HOST_ENTITY_ID_KEY, record.hostEntityId());
            }
            return;
        }
        // Native engine ids alone can collide between independently evolved saves.
        for (SectorEntityToken candidate : entities()) {
            if (!isNativeHyperspaceLight(candidate) || ownedId(candidate) != null
                    || !candidate.isAlive() || codec.isDespawning((CustomCampaignEntityAPI) candidate)) continue;
            CoopLightRecord legacy = codec.capture((CustomCampaignEntityAPI) candidate, record.id());
            if (legacy.staticFingerprint().equals(record.staticFingerprint())) {
                entity = (CustomCampaignEntityAPI) candidate;
                break;
            }
        }
        if (entity == null) entity = codec.create(requireHyperspace(), record);
        else codec.apply(entity, record);
        entity.getMemoryWithoutUpdate().set(LIGHT_ID_KEY, record.id());
        entity.getMemoryWithoutUpdate().set(HOST_ENTITY_ID_KEY, record.hostEntityId());
    }

    @Override public void remove(String id) {
        CoopLightRecord.canonicalId(id);
        State state = state();
        if (state.consumed.add(id)) save(state);
        CustomCampaignEntityAPI entity = find(id);
        if (entity != null && !codec.isDespawning(entity)) codec.despawn(entity, false);
    }

    @Override public void reconcileLegacy(List<CoopLightRecord> manifest) {
        Objects.requireNonNull(manifest, "manifest");
        CoopLightCodec.encodeSet(manifest);
        State state = state();
        for (CoopLightRecord record : manifest) {
            if (!state.consumed.contains(record.id()) && find(record.id()) == null) {
                throw new IllegalStateException("Cannot reconcile before light materializes: " + record.id());
            }
        }
        List<SectorEntityToken> removals = new ArrayList<>();
        SectorEntityToken target = interactionTarget();
        for (SectorEntityToken entity : entities()) {
            if (!isNativeHyperspaceLight(entity) || ownedId(entity) != null) continue;
            if (entity == target) throw new IllegalStateException("Deferring legacy light cleanup during its interaction");
            removals.add(entity);
        }
        for (SectorEntityToken entity : removals) entity.getContainingLocation().removeEntity(entity);
    }

    @Override public List<CoopLightCodec.Request> pendingRequests() {
        return List.copyOf(state().pending.values());
    }

    @Override public CoopLightCodec.Outcome applyRequest(CoopLightCodec.Request request, String actorId) {
        Objects.requireNonNull(request, "request");
        CoopLightRecord.identity(actorId, "light actor");
        if (guest) throw new IllegalStateException("Guest cannot authorize light consumption");
        State state = state();
        CoopLightCodec.Outcome previous = state.results.get(request.requestId());
        if (previous != null) {
            requireSameRequest(previous, request, actorId);
            return previous;
        }
        CustomCampaignEntityAPI entity = find(request.lightId());
        CoopLightRecord record = entity == null ? null : codec.capture(entity, request.lightId());
        CampaignFleetAPI actor = actor(actorId);
        String rejection = rejection(request, actor, entity, state);
        if (rejection == null && request.action() == CoopLightCodec.Action.DWELLER
                && !dwellerClaimAllowed.test(request.lightId(), actorId)) {
            rejection = "light claimed by another actor";
        }
        float bonus = 0f;
        int points = 0;
        if (rejection == null && request.action() != CoopLightCodec.Action.DWELLER) {
            float brightness = codec.brightness(actor, entity);
            if (!Float.isFinite(brightness) || brightness < 0f || brightness > 1f) {
                throw new IllegalStateException("Invalid native light brightness");
            }
            bonus = record.bonusMult() * brightness;
            points = record.pointsOverride() > 0 ? record.pointsOverride()
                    : Math.round(com.fs.starfarer.api.impl.campaign.intel.events.ht.HTPoints.ABYSSAL_LIGHT_MIN
                    + (com.fs.starfarer.api.impl.campaign.intel.events.ht.HTPoints.ABYSSAL_LIGHT_AVG
                    - com.fs.starfarer.api.impl.campaign.intel.events.ht.HTPoints.ABYSSAL_LIGHT_MIN) * record.bonusMult());
        }
        CoopLightCodec.Outcome outcome = new CoopLightCodec.Outcome(request.requestId(), request.lightId(), actorId,
                request.action(), rejection == null, record, bonus, points, rejection == null ? "accepted" : rejection);
        state.results.put(request.requestId(), outcome);
        if (outcome.accepted()) state.consumed.add(request.lightId());
        // Result and tombstone are one primitive String write, before changing the native entity.
        save(state);
        if (outcome.accepted()) codec.despawn(entity, request.action() != CoopLightCodec.Action.DWELLER);
        return outcome;
    }

    private String rejection(CoopLightCodec.Request request, CampaignFleetAPI actor,
                             CustomCampaignEntityAPI entity, State state) {
        if (state.consumed.contains(request.lightId())) return "already consumed";
        if (entity == null || !entity.isAlive() || codec.isDespawning(entity)) return "light unavailable";
        if (actor == null || !actor.isAlive() || actor.getContainingLocation() != entity.getContainingLocation())
            return "actor is outside the light location";
        boolean dweller = entity.hasTag(Tags.DWELLER_LIGHT);
        if (dweller != (request.action() == CoopLightCodec.Action.DWELLER)) return "wrong light interaction";
        double dx = actor.getLocation().x - entity.getLocation().x;
        double dy = actor.getLocation().y - entity.getLocation().y;
        double range = switch (request.action()) {
            case PULSE -> codec.pulseRange(actor);
            case SENSOR_BURST -> AbyssalLightEntityPlugin.SENSOR_BURST_TRIGGER_RANGE + actor.getRadius() + entity.getRadius();
            case DWELLER -> AbyssalLightEntityPlugin.DWELLER_TRIGGER_RANGE + actor.getRadius() + entity.getRadius();
        };
        return !Double.isFinite(range) || range < 0 || !Double.isFinite(dx) || !Double.isFinite(dy)
                || dx * dx + dy * dy > range * range ? "actor is outside the light trigger range" : null;
    }

    @Override public void acceptOutcome(CoopLightCodec.Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        State state = state();
        CoopLightCodec.Request pending = state.pending.get(outcome.requestId());
        if (pending != null) requireSameRequest(outcome, pending, localActorId);
        boolean ownPending = pending != null && Objects.equals(localActorId, outcome.actorId());
        if (state.uncertain.contains(outcome.requestId())) {
            throw new IllegalStateException("Light reward application is uncertain for " + outcome.requestId()
                    + "; refusing to repeat a possibly applied native reward");
        }
        if (ownPending && !state.applied.contains(outcome.requestId()) && state.applied.size() >= MAX_HISTORY) {
            throw new IllegalStateException("Applied light reward history limit reached; refusing to forget outcomes");
        }
        if (outcome.accepted()) state.consumed.add(outcome.lightId());
        boolean grant = ownPending && !state.applied.contains(outcome.requestId()) && outcome.accepted()
                && outcome.action() != CoopLightCodec.Action.DWELLER;
        if (grant) {
            SectorAPI sector = Objects.requireNonNull(sectorSupplier.get(), "No sector for light reward");
            CampaignFleetAPI player = Objects.requireNonNull(sector.getPlayerFleet(), "No local fleet for light reward");
            // Persist before native mutation. A partial native failure or a failed final save must
            // remain visibly uncertain across adapter recreation and can never auto-grant again.
            state.uncertain.add(outcome.requestId());
            save(state);
            codec.grant(player, outcome.action() == CoopLightCodec.Action.SENSOR_BURST, outcome.bonus(), outcome.points());
            state.uncertain.remove(outcome.requestId());
        }
        if (ownPending) {
            state.applied.add(outcome.requestId());
            state.pending.remove(outcome.requestId());
        }
        save(state);
        if (outcome.accepted()) {
            CustomCampaignEntityAPI entity = find(outcome.lightId());
            if (entity != null && !codec.isDespawning(entity)) {
                codec.despawn(entity, outcome.action() != CoopLightCodec.Action.DWELLER);
            }
        } else if (outcome.record() != null) upsert(outcome.record());
    }

    /** A reloaded Guest retains pending requests and result guards; observing cannot consume a light. */
    @Override public void observeGuest() { state(); }

    /** Diagnostics do not adopt, expire, recover or acknowledge anything. */
    public Map<String, Integer> readOnlyStateCounts() {
        State state = state();
        return Map.of("pending", state.pending.size(), "results", state.results.size(),
                "tombstones", state.consumed.size(), "applied", state.applied.size(), "uncertain", state.uncertain.size());
    }

    public List<CoopLightRecord> readOnlySnapshot() {
        List<CoopLightRecord> records = new ArrayList<>();
        for (SectorEntityToken entity : entities()) {
            if (!isNativeHyperspaceLight(entity)) continue;
            String id = ownedId(entity);
            if (id != null && entity.isAlive() && !codec.isDespawning((CustomCampaignEntityAPI) entity)) {
                records.add(codec.capture((CustomCampaignEntityAPI) entity, id));
            }
        }
        records.sort(Comparator.comparing(CoopLightRecord::id));
        return List.copyOf(records);
    }

    public boolean isAvailable(String id) {
        CoopLightRecord.canonicalId(id);
        CustomCampaignEntityAPI entity = find(id);
        State state = state();
        return !state.consumed.contains(id) && state.pending.values().stream().noneMatch(r -> r.lightId().equals(id))
                && entity != null && entity.isAlive() && !codec.isDespawning(entity);
    }

    public static String ownedId(SectorEntityToken entity) {
        if (entity instanceof CampaignFleetAPI fleet && CoopLightBridge.isEncounterFleet(fleet)) {
            return (String) fleet.getMemoryWithoutUpdate().get(DWELLER_FLEET_LIGHT_KEY);
        }
        if (!isNativeHyperspaceLight(entity)) return null;
        Object value = entity.getMemoryWithoutUpdate().get(LIGHT_ID_KEY);
        if (value == null) return null;
        if (!(value instanceof String id)) throw new IllegalStateException("Saved light id is not text");
        return CoopLightRecord.canonicalId(id);
    }

    public static String lightId(SectorEntityToken entity) { return ownedId(entity); }

    private CampaignFleetAPI actor(String actorId) {
        SectorAPI sector = sectorSupplier.get();
        return Objects.equals(localActorId, actorId) ? sector == null ? null : sector.getPlayerFleet() : remoteActorSupplier.get();
    }

    private CustomCampaignEntityAPI find(String id) {
        CustomCampaignEntityAPI found = null;
        for (SectorEntityToken entity : entities()) {
            if (!isNativeHyperspaceLight(entity)) continue;
            if (!id.equals(ownedId(entity))) continue;
            if (found != null) throw new IllegalStateException("Duplicate local light id " + id);
            found = (CustomCampaignEntityAPI) entity;
        }
        return found;
    }

    private List<SectorEntityToken> entities() {
        SectorAPI sector = sectorSupplier.get();
        return sector == null || sector.getHyperspace() == null ? List.of()
                : new ArrayList<>(sector.getHyperspace().getAllEntities());
    }

    private LocationAPI requireHyperspace() {
        return Objects.requireNonNull(Objects.requireNonNull(sectorSupplier.get(), "No light sector").getHyperspace(), "No hyperspace");
    }

    private SectorEntityToken interactionTarget() {
        SectorAPI sector = sectorSupplier.get();
        InteractionDialogAPI dialog = sector == null || sector.getCampaignUI() == null ? null
                : sector.getCampaignUI().getCurrentInteractionDialog();
        return dialog == null ? null : dialog.getInteractionTarget();
    }

    private static boolean isNativeHyperspaceLight(SectorEntityToken entity) {
        return entity instanceof CustomCampaignEntityAPI && CoopLightBridge.isNativeLight(entity)
                && entity.getContainingLocation() != null && entity.getContainingLocation().isHyperspace();
    }

    private static AbyssalLightEntityPlugin plugin(SectorEntityToken entity) {
        return (AbyssalLightEntityPlugin) entity.getCustomPlugin();
    }

    private static AbyssalLightParams params(CoopLightRecord record) {
        AbyssalLightParams result = new AbyssalLightParams();
        copyParams(result, record);
        return result;
    }

    private static void copyParams(AbyssalLightParams p, CoopLightRecord r) {
        p.color = new Color(r.colorArgb(), true);
        p.frequencyMultMin = r.frequencyMultMin(); p.frequencyMultMax = r.frequencyMultMax();
        p.frequencyChangeMult = r.frequencyChangeMult(); p.size = r.size();
        p.detectedRange = r.detectedRange(); p.bonusMult = r.bonusMult();
        p.durationDays = r.remainingDays(); p.pointsOverride = r.pointsOverride();
    }

    private static void requireSameRequest(CoopLightCodec.Outcome outcome, CoopLightCodec.Request request, String actorId) {
        if (!outcome.lightId().equals(request.lightId()) || outcome.action() != request.action()
                || !outcome.actorId().equals(actorId)) throw new IllegalStateException("Light request identity was reused");
    }

    /** Only this primitive text is saved. All parsed records and callbacks remain transient. */
    private static final class State {
        final Map<String, CoopLightCodec.Request> pending = new LinkedHashMap<>();
        final Map<String, CoopLightCodec.Outcome> results = new LinkedHashMap<>();
        final Set<String> consumed = new LinkedHashSet<>();
        final Set<String> applied = new LinkedHashSet<>();
        final Set<String> uncertain = new LinkedHashSet<>();
        State copy() {
            State copy = new State();
            copy.pending.putAll(pending); copy.results.putAll(results);
            copy.consumed.addAll(consumed); copy.applied.addAll(applied); copy.uncertain.addAll(uncertain);
            return copy;
        }
    }

    private State state() {
        SectorAPI sector = sectorSupplier.get();
        if (sector == null) return new State();
        Object value = sector.getMemoryWithoutUpdate().get(STATE_KEY);
        if (value != null && !(value instanceof String)) throw new IllegalStateException("Saved light state is not text");
        String text = (String) value;
        if (sector == cachedSector && Objects.equals(text, cachedText) && cachedState != null) return cachedState.copy();
        State state = new State();
        if (text != null) {
            if (text.length() > MAX_SAVED_CHARACTERS) throw new IllegalStateException("Saved light state exceeds cap");
            Object parsed = CoopJson.parse(text);
            if (!(parsed instanceof List<?> entries)) throw new IllegalStateException("Saved light state is not an array");
            for (Object entry : entries) {
                if (!(entry instanceof String row) || row.length() < 2) throw new IllegalStateException("Invalid saved light state entry");
                String payload = row.substring(1);
                boolean fresh;
                switch (row.charAt(0)) {
                    case 'P' -> {
                        CoopLightCodec.Request request = CoopLightCodec.Request.decode(payload);
                        fresh = state.pending.putIfAbsent(request.requestId(), request) == null;
                    }
                    case 'R' -> {
                        CoopLightCodec.Outcome result = CoopLightCodec.Outcome.decode(payload);
                        fresh = state.results.putIfAbsent(result.requestId(), result) == null;
                    }
                    case 'T' -> fresh = state.consumed.add(CoopLightRecord.canonicalId(payload));
                    case 'A' -> fresh = state.applied.add(CoopLightRecord.uuid(payload, "applied light request"));
                    case 'U' -> fresh = state.uncertain.add(CoopLightRecord.uuid(payload, "uncertain light request"));
                    default -> throw new IllegalStateException("Unknown saved light state entry");
                }
                if (!fresh) throw new IllegalStateException("Duplicate saved light state entry");
            }
        }
        checkStateSize(state);
        cachedSector = sector; cachedText = text; cachedState = state.copy();
        return state;
    }

    private void save(State state) {
        save(state, encodeState(state));
    }

    private static String encodeState(State state) {
        checkStateSize(state);
        List<String> rows = new ArrayList<>();
        state.pending.values().forEach(request -> rows.add("P" + request.encode()));
        state.results.values().forEach(outcome -> rows.add("R" + outcome.encode()));
        state.consumed.forEach(id -> rows.add("T" + id));
        state.applied.forEach(id -> rows.add("A" + id));
        state.uncertain.forEach(id -> rows.add("U" + id));
        Collections.sort(rows);
        StringBuilder encoded = new StringBuilder("[");
        for (String row : rows) {
            if (encoded.length() > 1) encoded.append(',');
            encoded.append('"').append(CoopJson.escape(row)).append('"');
            if (encoded.length() > MAX_SAVED_CHARACTERS) throw new IllegalStateException("Saved light state exceeds cap");
        }
        return encoded.append(']').toString();
    }

    private void save(State state, String text) {
        SectorAPI sector = Objects.requireNonNull(sectorSupplier.get(), "No sector for light state");
        sector.getMemoryWithoutUpdate().set(STATE_KEY, text);
        cachedSector = sector; cachedText = text; cachedState = state.copy();
    }

    private static void checkStateSize(State state) {
        if (state.pending.size() > MAX_PENDING || state.results.size() > MAX_HISTORY
                || state.consumed.size() > MAX_HISTORY || state.applied.size() > MAX_HISTORY) {
            throw new IllegalStateException("Saved light state history limit reached; refusing to forget outcomes");
        }
        if (state.uncertain.size() > MAX_HISTORY || !state.pending.keySet().containsAll(state.uncertain)
                || state.uncertain.stream().anyMatch(state.applied::contains)) {
            throw new IllegalStateException("Invalid uncertain light reward state");
        }
    }
}
