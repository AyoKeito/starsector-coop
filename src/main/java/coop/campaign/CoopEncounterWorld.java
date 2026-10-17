package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.NascentGravityWellAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalLocationDespawner;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectEPEC;
import com.fs.starfarer.api.impl.campaign.enc.EncounterPoint;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceAbyssPluginImpl.AbyssalEPData;
import coop.ambient.CoopEncounterReplayBridge;
import coop.fleet.CoopEntityIds;
import coop.fleet.CoopLocations;
import coop.presence.CoopPresenceRegistry;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import static coop.campaign.CoopEncounterRecord.Role;
import static coop.campaign.CoopEncounterRecord.SpecialType;

/** Native encounter capture/replay. Saved state is a primitive recipe string on its own system. */
public final class CoopEncounterWorld implements CoopEncounterAuthority.EncounterWorld {
    public static final String RECIPE_KEY = "$coopEncounterRecipe";
    public static final String ENCOUNTER_ID_KEY = "$coopEncId";
    private static final String RUINS_EXPLORED = "$ruinsExplored";
    private static final String VAMBRACE_TYPE = "derelict_vambrace";
    private static final Set<String> PLANET_HINTS = Set.of("$abyssalBlackHoleReadings",
            "$abyssalGasGiantTurbulence", "$abyssalPlanetoidMiningOp");

    @FunctionalInterface
    interface ReplayGenerator { StarSystemAPI generate(SectorAPI sector, CoopEncounterRecord recipe); }

    private final Supplier<SectorAPI> sectorSupplier;
    private final ReplayGenerator replayGenerator;
    private final Set<String> warnedLegacy = new HashSet<>();
    private final Set<StarSystemAPI> legacyAtLoad = Collections.newSetFromMap(new IdentityHashMap<>());
    private SectorAPI initializedSector;
    private final Map<String, CoopEncounterRecord.EntityState> guestBaseline = new HashMap<>();
    private final Map<String, CoopEncounterRecord.EntityState> guestPending = new HashMap<>();

    public CoopEncounterWorld() { this(Global::getSector); }
    public CoopEncounterWorld(Supplier<SectorAPI> sectorSupplier) { this(sectorSupplier, CoopEncounterWorld::replayNative); }
    CoopEncounterWorld(Supplier<SectorAPI> sectorSupplier, ReplayGenerator replayGenerator) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier);
        this.replayGenerator = Objects.requireNonNull(replayGenerator);
        initializeSector(sectorSupplier.get());
    }

    @Override public List<CoopEncounterRecord> captureHost() {
        SectorAPI sector = sectorSupplier.get();
        if (sector == null) return null;
        initializeSector(sector);
        List<CoopEncounterRecord> records = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (StarSystemAPI system : systems(sector)) {
            if (!isAbyssal(system)) continue;
            CoopEncounterRecord recipe = savedRecipe(system);
            if (recipe == null) {
                if (legacyAtLoad.contains(system)) { warnLegacy(system); continue; }
                MemoryAPI memory = system.getMemoryWithoutUpdate();
                if (!memory.contains(CoopEncounterReplayBridge.SEED_KEY)) { warnLegacy(system); continue; }
                Object creator = memory.get(CoopEncounterReplayBridge.CREATOR_KEY);
                if (!(creator instanceof String text) || !CoopEncounterReplayBridge.supportsCreator(text)) {
                    warnLegacy(system); continue;
                }
                recipe = captureNew(system);
            }
            if (!ids.add(recipe.instanceId())) throw new IllegalStateException("Duplicate abyssal encounter instance " + recipe.instanceId());
            ensureHostDespawner(system);
            CoopEncounterRecord current = recipe.withOverlay(captureOverlay(sector, system, recipe));
            system.getMemoryWithoutUpdate().set(RECIPE_KEY, current.encode());
            records.add(current);
        }
        records.sort(Comparator.comparing(CoopEncounterRecord::instanceId));
        return List.copyOf(records);
    }

    private CoopEncounterRecord captureNew(StarSystemAPI system) {
        String instance = Objects.requireNonNull(system.getOptionalUniqueId(), "Abyssal system has no unique id");
        CoopEncounterRecord.entityId(instance, Role.SYSTEM, 0); // validate before assigning shared keys
        MemoryAPI memory = system.getMemoryWithoutUpdate();
        long seed = Long.parseLong(requiredString(memory, CoopEncounterReplayBridge.SEED_KEY));
        String pointId = requiredString(memory, CoopEncounterReplayBridge.POINT_ID_KEY);
        float x = Float.parseFloat(requiredString(memory, CoopEncounterReplayBridge.POINT_X_KEY));
        float y = Float.parseFloat(requiredString(memory, CoopEncounterReplayBridge.POINT_Y_KEY));
        float depth = Float.parseFloat(requiredString(memory, CoopEncounterReplayBridge.DEPTH_KEY));
        String roll = requiredString(memory, CoopEncounterReplayBridge.THREAT_ROLL_RAN_KEY);
        if (!roll.equals("true") && !roll.equals("false")) throw new IllegalStateException("Invalid saved encounter threat branch");
        CoopEncounterRecord.Creator creator = CoopEncounterRecord.Creator.fromClassName(
                requiredString(memory, CoopEncounterReplayBridge.CREATOR_KEY));
        Map<Role, List<SectorEntityToken>> groups = membership(system);
        List<CoopEncounterRecord.Entity> entities = new ArrayList<>();
        for (Role role : Role.values()) {
            List<SectorEntityToken> tokens = groups.get(role);
            for (int ordinal = 0; ordinal < tokens.size(); ordinal++) {
                SectorEntityToken token = tokens.get(ordinal);
                entities.add(new CoopEncounterRecord.Entity(role, ordinal, specId(token)));
                own(token, instance, CoopEncounterRecord.entityId(instance, role, ordinal));
            }
        }
        system.getMemoryWithoutUpdate().set(ENCOUNTER_ID_KEY, instance);
        List<CoopEncounterRecord.Special> specials = new ArrayList<>();
        for (SectorEntityToken entity : groups.get(Role.SYSTEM)) {
            MemoryAPI entityMemory = entity.getMemoryWithoutUpdate();
            SpecialType type = null;
            if (entityMemory.contains("$gsType")) type = SpecialType.GHOST_WRECK;
            else if (VAMBRACE_TYPE.equals(entity.getCustomEntityType())) type = SpecialType.VAMBRACE;
            else if (entity instanceof PlanetAPI && PLANET_HINTS.stream().anyMatch(entityMemory::contains)) type = SpecialType.PLANET_FLAGS;
            if (type == null) continue;
            CoopWreckRecord wreck = null;
            if (type == SpecialType.GHOST_WRECK) {
                if (!(entity instanceof CustomCampaignEntityAPI custom) || !CoopWreckCodec.isNativeWreck(entity))
                    throw new IllegalStateException("Ghost ship hint is not a native derelict");
                wreck = CoopWreckCodec.capture(custom, CoopEncounterRecord.wreckId(CoopEntityIds.keyOf(entity)));
            }
            specials.add(new CoopEncounterRecord.Special(type, CoopEntityIds.keyOf(entity),
                    type == SpecialType.PLANET_FLAGS ? null : orbit(entity), wreck, memoryValues(entityMemory)));
        }
        CoopEncounterRecord recipe = new CoopEncounterRecord(instance, seed, pointId, x, y, depth,
                Boolean.parseBoolean(roll), creator, system.hasTag(Tags.SYSTEM_CAN_SPAWN_THREAT), entities,
                specials, CoopEncounterRecord.Overlay.empty());
        recipe = recipe.withOverlay(captureOverlay(sectorSupplier.get(), system, recipe));
        system.getMemoryWithoutUpdate().set(RECIPE_KEY, recipe.encode());
        CoopLocations.invalidate();
        return recipe;
    }

    @Override public Set<String> taggedIds() {
        SectorAPI sector = sectorSupplier.get(); if (sector == null) return Set.of();
        Set<String> ids = new HashSet<>();
        for (StarSystemAPI system : systems(sector)) {
            String id = ownedSystemId(system);
            if (id != null && !ids.add(id)) throw new IllegalStateException("Duplicate local encounter instance " + id);
        }
        return Set.copyOf(ids);
    }

    @Override public void upsert(CoopEncounterRecord recipe) {
        Objects.requireNonNull(recipe); SectorAPI sector = Objects.requireNonNull(sectorSupplier.get(), "No sector for encounter replay");
        initializeSector(sector);
        StarSystemAPI existing = findSystem(sector, recipe.instanceId());
        if (existing != null) {
            CoopEncounterRecord previous = Objects.requireNonNull(savedRecipe(existing), "Owned encounter has no saved recipe");
            if (!previous.withOverlay(CoopEncounterRecord.Overlay.empty()).equals(recipe.withOverlay(CoopEncounterRecord.Overlay.empty())))
                throw new IllegalStateException("Immutable encounter recipe changed: " + recipe.instanceId());
            seedGuestBaseline(previous);
            existing.removeScriptsOfClass(AbyssalLocationDespawner.class);
            existing.removeScriptsOfClass(CoopAbyssalDespawner.class);
            applyOverlay(sector, existing, recipe, true);
            existing.getMemoryWithoutUpdate().set(RECIPE_KEY, recipe.encode());
            return;
        }
        Set<StarSystemAPI> before = identitySet(systems(sector));
        try {
            StarSystemAPI created = Objects.requireNonNull(replayGenerator.generate(sector, recipe), "Encounter replay returned no system");
            if (before.contains(created)) throw new IllegalStateException("Replay returned an existing system");
            created.setOptionalUniqueId(recipe.instanceId());
            materializeSpecialsAndIdentity(created, recipe);
            created.getMemoryWithoutUpdate().set(ENCOUNTER_ID_KEY, recipe.instanceId());
            created.removeScriptsOfClass(AbyssalLocationDespawner.class);
            created.removeScriptsOfClass(CoopAbyssalDespawner.class);
            applyOverlay(sector, created, recipe, false);
            created.getMemoryWithoutUpdate().set(RECIPE_KEY, recipe.encode());
            seedGuestBaseline(recipe); CoopLocations.invalidate();
        } catch (RuntimeException | LinkageError ex) {
            for (StarSystemAPI added : systems(sector)) if (!before.contains(added))
                CoopAbyssalDespawner.removeSystemAndLinks(sector, added);
            throw ex;
        }
    }

    private static StarSystemAPI replayNative(SectorAPI sector, CoopEncounterRecord recipe) {
        Set<StarSystemAPI> before = identitySet(systems(sector));
        AbyssalEPData data = new AbyssalEPData(); data.depth = recipe.depth(); data.random = new Random(recipe.effectiveSeed());
        EncounterPoint point = new EncounterPoint(recipe.encounterPointId(), sector.getHyperspace(),
                new Vector2f(recipe.x(), recipe.y()), "abyssal"); point.custom = data;
        Random previous = StarSystemGenerator.random;
        CoopEncounterReplayBridge.beginReplay(recipe.effectiveSeed(), recipe.threatRollRan(), recipe.threatTag());
        try { new AbyssalRogueStellarObjectEPEC().createEncounter(null, point); }
        finally { CoopEncounterReplayBridge.endReplay(); StarSystemGenerator.random = previous; }
        List<StarSystemAPI> added = systems(sector).stream().filter(s -> !before.contains(s)).toList();
        if (added.size() != 1) throw new IllegalStateException("Replay created " + added.size() + " systems");
        return added.get(0);
    }

    private static void materializeSpecialsAndIdentity(StarSystemAPI system, CoopEncounterRecord recipe) {
        Set<String> appended = new HashSet<>();
        for (CoopEncounterRecord.Special special : recipe.specials())
            if (special.type() != SpecialType.PLANET_FLAGS) appended.add(special.entityId());
        Map<Role, List<SectorEntityToken>> groups = membership(system);
        Map<String, SectorEntityToken> assigned = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            List<CoopEncounterRecord.Entity> expected = recipe.entities().stream()
                    .filter(e -> e.role() == role && !appended.contains(recipe.entityId(e))).toList();
            List<SectorEntityToken> actual = groups.get(role);
            if (actual.size() != expected.size()) throw new IllegalStateException("Encounter " + role + " membership count differs");
            for (int index = 0; index < expected.size(); index++) {
                CoopEncounterRecord.Entity member = expected.get(index); SectorEntityToken token = actual.get(index);
                if (member.ordinal() != index || !member.specId().equals(specId(token)))
                    throw new IllegalStateException("Encounter base membership/spec differs at " + role + index);
                own(token, recipe.instanceId(), recipe.entityId(member)); assigned.put(CoopEntityIds.keyOf(token), token);
            }
        }
        List<CoopEncounterRecord.Special> additions = recipe.specials().stream().filter(s -> s.type() != SpecialType.PLANET_FLAGS)
                .sorted(Comparator.comparingInt(s -> member(recipe, s.entityId()).ordinal())).toList();
        for (CoopEncounterRecord.Special special : additions) {
            CoopEncounterRecord.Entity member = member(recipe, special.entityId());
            if (member.role() != Role.SYSTEM || member.ordinal() != assignedSystemCount(recipe, assigned))
                throw new IllegalStateException("Encounter specials are not the final native system members");
            SectorEntityToken entity;
            if (special.type() == SpecialType.GHOST_WRECK) entity = CoopWreckCodec.create(system, special.wreck(), null);
            else entity = system.addCustomEntity(null, "Derelict Structure", VAMBRACE_TYPE, "neutral");
            if (!member.specId().equals(specId(entity))) throw new IllegalStateException("Encounter special spec differs");
            own(entity, recipe.instanceId(), special.entityId()); assigned.put(CoopEntityIds.keyOf(entity), entity);
            applySpecialOrbitAndPose(entity, special, assigned);
            applyMemory(entity.getMemoryWithoutUpdate(), special.memory());
        }
        for (CoopEncounterRecord.Special special : recipe.specials()) if (special.type() == SpecialType.PLANET_FLAGS) {
            SectorEntityToken entity = assigned.get(special.entityId());
            if (!(entity instanceof PlanetAPI)) throw new IllegalStateException("Encounter hint target is not a planet");
            applyMemory(entity.getMemoryWithoutUpdate(), special.memory());
        }
        if (assigned.size() != recipe.entities().size()) throw new IllegalStateException("Incomplete encounter membership after replay");
    }

    private static int assignedSystemCount(CoopEncounterRecord recipe, Map<String, SectorEntityToken> assigned) {
        return (int) recipe.entities().stream().filter(e -> e.role() == Role.SYSTEM && assigned.containsKey(recipe.entityId(e))).count();
    }

    private void applyOverlay(SectorAPI sector, StarSystemAPI system, CoopEncounterRecord recipe, boolean preserveLocalEdits) {
        Map<String, SectorEntityToken> current = ownedEntities(sector, system, recipe);
        Set<SectorEntityToken> removals = identitySet(List.of());
        for (String consumed : recipe.overlay().consumedIds()) if (current.containsKey(consumed)) removals.add(current.get(consumed));
        if (!removals.isEmpty()) preflightRemoval(sector, system, removals, false);
        for (String consumed : recipe.overlay().consumedIds()) {
            SectorEntityToken entity = current.remove(consumed);
            if (entity != null) entity.getContainingLocation().removeEntity(entity);
            guestPending.remove(consumed); guestBaseline.remove(consumed);
        }
        for (CoopEncounterRecord.EntityState state : recipe.overlay().entities()) {
            SectorEntityToken entity = current.get(state.entityId()); if (entity == null) continue;
            if (preserveLocalEdits) {
                CoopEncounterRecord.EntityState actual = state(entity);
                CoopEncounterRecord.EntityState baseline = guestBaseline.get(state.entityId());
                if (baseline != null && !actual.equals(baseline)) guestPending.put(state.entityId(), actual);
                CoopEncounterRecord.EntityState pending = guestPending.get(state.entityId());
                if (pending != null && !subsumes(state, pending)) continue;
                guestPending.remove(state.entityId());
            }
            applyState(entity, state); guestBaseline.put(state.entityId(), state);
        }
    }

    @Override public List<CoopEncounterRecord.EntityState> guestStates() {
        SectorAPI sector = sectorSupplier.get(); if (sector == null) return List.of();
        List<CoopEncounterRecord.EntityState> states = new ArrayList<>();
        for (StarSystemAPI system : systems(sector)) {
            if (ownedSystemId(system) == null) continue;
            CoopEncounterRecord recipe = savedRecipe(system); if (recipe == null) continue;
            for (SectorEntityToken entity : ownedEntities(sector, system, recipe).values()) states.add(state(entity));
        }
        states.sort(Comparator.comparing(CoopEncounterRecord.EntityState::entityId)); return List.copyOf(states);
    }

    @Override public List<CoopEncounterRecord.EntityState> guestBaselineStates() {
        SectorAPI sector = sectorSupplier.get(); if (sector == null) return List.of();
        List<CoopEncounterRecord.EntityState> states = new ArrayList<>();
        for (StarSystemAPI system : systems(sector)) if (ownedSystemId(system) != null) {
            CoopEncounterRecord recipe = savedRecipe(system); if (recipe != null) states.addAll(recipe.overlay().entities());
        }
        states.sort(Comparator.comparing(CoopEncounterRecord.EntityState::entityId)); return List.copyOf(states);
    }

    @Override public Set<String> guestConsumedIds() {
        SectorAPI sector = sectorSupplier.get(); if (sector == null) return Set.of();
        Set<String> consumed = new HashSet<>();
        for (StarSystemAPI system : systems(sector)) if (ownedSystemId(system) != null) {
            CoopEncounterRecord recipe = savedRecipe(system); if (recipe == null) continue;
            Map<String, SectorEntityToken> actual = ownedEntities(sector, system, recipe);
            for (CoopEncounterRecord.Entity member : recipe.entities()) {
                String id = recipe.entityId(member);
                if (member.role() == Role.SYSTEM && !recipe.overlay().consumedIds().contains(id) && !actual.containsKey(id)) consumed.add(id);
            }
        }
        return Set.copyOf(consumed);
    }

    private void seedGuestBaseline(CoopEncounterRecord recipe) {
        for (CoopEncounterRecord.EntityState state : recipe.overlay().entities()) guestBaseline.putIfAbsent(state.entityId(), state);
    }

    @Override public Map<String, String> captureCurrentOrbits(List<CoopEncounterRecord> records) {
        SectorAPI sector = Objects.requireNonNull(sectorSupplier.get(), "No sector for encounter orbits");
        Map<String, String> result = new LinkedHashMap<>();
        for (CoopEncounterRecord record : records) {
            StarSystemAPI system = Objects.requireNonNull(findSystem(sector, record.instanceId()), "Missing Host encounter for current orbits");
            Set<String> systemMembers = new HashSet<>();
            for (CoopEncounterRecord.Entity member : record.entities())
                if (member.role() == Role.SYSTEM) systemMembers.add(record.entityId(member));
            List<SectorEntityToken> circular = new ArrayList<>();
            for (SectorEntityToken entity : ownedEntities(sector, system, record).values()) {
                if (!systemMembers.contains(CoopEntityIds.keyOf(entity))) continue;
                if (entity.getOrbit() != null && entity.getCircularOrbitRadius() > 0 && entity.getCircularOrbitPeriod() > 0) {
                    if (entity.getOrbitFocus() != null && !systemMembers.contains(CoopEntityIds.keyOf(entity.getOrbitFocus())))
                        throw new IllegalStateException("Encounter circular orbit focus is outside original system membership");
                    circular.add(entity);
                }
            }
            result.put(record.instanceId(), CoopOrbitSync.encode(CoopCampaignReplicator.orbitEntries(circular)));
        }
        return Map.copyOf(result);
    }

    @Override public void applyCurrentOrbits(CoopEncounterRecord record, String encodedOrbits) {
        SectorAPI sector = Objects.requireNonNull(sectorSupplier.get(), "No sector for encounter orbits");
        StarSystemAPI system = Objects.requireNonNull(findSystem(sector, record.instanceId()), "Missing Guest encounter for current orbits");
        Map<String, SectorEntityToken> current = ownedEntities(sector, system, record);
        Map<String, Integer> creationOrder = new HashMap<>();
        for (int index = 0; index < record.entities().size(); index++)
            creationOrder.put(record.entityId(record.entities().get(index)), index);
        List<CoopOrbitSync.OrbitEntry> entries = new ArrayList<>(CoopOrbitSync.decode(encodedOrbits));
        // Native creation installs each focus before its children; specials are the final members.
        entries.sort(Comparator.comparingInt(entry -> creationOrder.get(entry.entityId())));
        for (CoopOrbitSync.OrbitEntry entry : entries) {
            SectorEntityToken entity = current.get(entry.entityId());
            if (entity == null || entry.focusId() != null && !current.containsKey(entry.focusId())) continue;
            // Locally consumed originals remain absent until their reliable Host acknowledgement.
            CoopCampaignReplicator.applyOrbitTo(entity, entry, current, sector);
        }
    }

    /** Reliable guest state deltas may touch only a recorded member of an owned encounter. */
    public static void applyEntityState(SectorAPI sector, String wireEntityId, CoopEncounterRecord.EntityState incoming) {
        Objects.requireNonNull(sector); Objects.requireNonNull(incoming);
        if (!incoming.entityId().equals(wireEntityId)) throw new IllegalArgumentException("Encounter state identity mismatch");
        for (StarSystemAPI system : systems(sector)) {
            if (ownedSystemId(system) == null) continue;
            CoopEncounterRecord recipe = savedRecipe(system); if (recipe == null) continue;
            if (recipe.entities().stream().noneMatch(e -> recipe.entityId(e).equals(wireEntityId))) continue;
            SectorEntityToken entity = ownedEntities(sector, system, recipe).get(wireEntityId);
            if (entity == null) return; // consumed before an older state delta arrived
            applyState(entity, incoming); return;
        }
        throw new IllegalArgumentException("Unknown encounter entity state target " + wireEntityId);
    }

    private static void applyState(SectorEntityToken entity, CoopEncounterRecord.EntityState incoming) {
        MarketAPI market = entity.getMarket();
        if (incoming.surveyLevel() != null) {
            if (!(entity instanceof PlanetAPI) || market == null) throw new IllegalArgumentException("Survey state target is not a planet market");
            MarketAPI.SurveyLevel next = MarketAPI.SurveyLevel.valueOf(incoming.surveyLevel());
            MarketAPI.SurveyLevel current = market.getSurveyLevel();
            if (current == null || next.ordinal() > current.ordinal()) {
                if (next == MarketAPI.SurveyLevel.FULL && market.getConditions() != null)
                    for (var condition : market.getConditions()) condition.setSurveyed(true);
                market.setSurveyLevel(next);
            }
        }
        if (Boolean.TRUE.equals(incoming.ruinsExplored()) && market != null)
            market.getMemoryWithoutUpdate().set(RUINS_EXPLORED, true);
        applyMemory(entity.getMemoryWithoutUpdate(), incoming.memory());
    }

    @Override public void remove(String instanceId) {
        SectorAPI sector = sectorSupplier.get(); if (sector == null) return;
        StarSystemAPI system = findSystem(sector, instanceId); if (system == null) return;
        preflightRemoval(sector, system, Set.of(), true);
        CampaignFleetAPI player = sector.getPlayerFleet();
        if (player != null && player.getContainingLocation() == system) {
            Vector2f point = system.getHyperspaceAnchor() == null ? system.getLocation() : system.getHyperspaceAnchor().getLocation();
            float x = point.x, y = point.y; system.removeEntity(player); sector.getHyperspace().addEntity(player);
            player.setLocation(x, y); sector.setCurrentLocation(sector.getHyperspace());
        }
        CoopAbyssalDespawner.removeSystemAndLinks(sector, system);
        String prefix = "coopenc_" + instanceId + "_";
        guestBaseline.keySet().removeIf(id -> id.startsWith(prefix)); guestPending.keySet().removeIf(id -> id.startsWith(prefix));
    }

    @Override public void updateGuestPresence(boolean reconnectGrace) {
        SectorAPI sector = sectorSupplier.get(); if (sector == null) return;
        for (StarSystemAPI system : systems(sector)) {
            if (savedRecipe(system) == null) continue;
            ensureHostDespawner(system).updateGuestPresence(CoopPresenceRegistry.get(), reconnectGrace);
        }
    }

    private static CoopAbyssalDespawner ensureHostDespawner(StarSystemAPI system) {
        system.removeScriptsOfClass(AbyssalLocationDespawner.class);
        CoopAbyssalDespawner keep = null;
        for (EveryFrameScript script : new ArrayList<>(system.getScripts())) if (script instanceof CoopAbyssalDespawner despawner) {
            if (keep == null && despawner.getSystem() == system) keep = despawner;
            else system.removeScript(script);
        }
        if (keep == null) { keep = new CoopAbyssalDespawner(system); system.addScript(keep); }
        return keep;
    }

    private static CoopEncounterRecord.Overlay captureOverlay(SectorAPI sector, StarSystemAPI system, CoopEncounterRecord recipe) {
        Map<String, SectorEntityToken> current = ownedEntities(sector, system, recipe);
        List<String> consumed = new ArrayList<>(); List<CoopEncounterRecord.EntityState> states = new ArrayList<>();
        for (CoopEncounterRecord.Entity member : recipe.entities()) {
            String id = recipe.entityId(member); SectorEntityToken entity = current.get(id);
            if (entity == null) consumed.add(id); else states.add(state(entity));
        }
        return new CoopEncounterRecord.Overlay(consumed, states);
    }

    private static CoopEncounterRecord.EntityState state(SectorEntityToken entity) {
        MarketAPI market = entity instanceof PlanetAPI ? entity.getMarket() : null;
        String survey = market == null || market.getSurveyLevel() == null ? null : market.getSurveyLevel().name();
        Boolean ruins = market == null || !hasRuins(market) ? null : market.getMemoryWithoutUpdate().getBoolean(RUINS_EXPLORED);
        return new CoopEncounterRecord.EntityState(CoopEntityIds.keyOf(entity), survey, ruins, memoryValues(entity.getMemoryWithoutUpdate()));
    }

    private static List<CoopWreckRecord.MemoryValue> memoryValues(MemoryAPI memory) {
        List<CoopWreckRecord.MemoryValue> result = new ArrayList<>();
        for (String key : CoopEncounterRecord.MEMORY_KEYS) {
            if (!memory.contains(key)) continue; Object value = memory.get(key);
            String type = key.equals("$gsType") ? "STRING" : "BOOLEAN";
            if (type.equals("STRING") ? !(value instanceof String) : !(value instanceof Boolean))
                throw new IllegalStateException("Encounter hint has unsupported type: " + key);
            result.add(new CoopWreckRecord.MemoryValue(key, type, value.toString(), memory.getExpire(key)));
        }
        result.sort(Comparator.comparing(CoopWreckRecord.MemoryValue::key)); return List.copyOf(result);
    }

    private static void applyMemory(MemoryAPI memory, List<CoopWreckRecord.MemoryValue> values) {
        Map<String, CoopWreckRecord.MemoryValue> wanted = new HashMap<>();
        for (CoopWreckRecord.MemoryValue value : values) wanted.put(value.key(), value);
        for (String key : CoopEncounterRecord.MEMORY_KEYS) {
            if (key.equals("$visited") && memory.getBoolean(key)) continue;
            CoopWreckRecord.MemoryValue value = wanted.get(key);
            if (value == null) memory.unset(key);
            else memory.set(key, value.type().equals("STRING") ? value.value() : Boolean.valueOf(value.value()), value.expiresDays());
        }
    }

    private static boolean hasRuins(MarketAPI market) {
        return market.hasCondition(Conditions.RUINS_SCATTERED) || market.hasCondition(Conditions.RUINS_WIDESPREAD)
                || market.hasCondition(Conditions.RUINS_EXTENSIVE) || market.hasCondition(Conditions.RUINS_VAST);
    }

    private static boolean subsumes(CoopEncounterRecord.EntityState host, CoopEncounterRecord.EntityState pending) {
        if (pending.surveyLevel() != null && (host.surveyLevel() == null
                || MarketAPI.SurveyLevel.valueOf(host.surveyLevel()).ordinal() < MarketAPI.SurveyLevel.valueOf(pending.surveyLevel()).ordinal())) return false;
        if (Boolean.TRUE.equals(pending.ruinsExplored()) && !Boolean.TRUE.equals(host.ruinsExplored())) return false;
        Map<String, CoopWreckRecord.MemoryValue> incoming = new HashMap<>(), wanted = new HashMap<>();
        for (CoopWreckRecord.MemoryValue value : host.memory()) incoming.put(value.key(), value);
        for (CoopWreckRecord.MemoryValue value : pending.memory()) wanted.put(value.key(), value);
        for (String key : CoopEncounterRecord.MEMORY_KEYS) {
            CoopWreckRecord.MemoryValue a = incoming.get(key), b = wanted.get(key);
            if (key.equals("$visited") && b != null && b.value().equals("true")) {
                if (a == null || !a.value().equals("true")) return false;
            } else if (key.equals("$visited")) {
                // A later Host visit may add true while acknowledging an earlier unrelated edit.
                if (a != null && a.value().equals("true")) continue;
                if (!sameValue(a, b)) return false;
            } else if (!sameValue(a, b)) return false;
        }
        return true;
    }
    private static boolean sameValue(CoopWreckRecord.MemoryValue a, CoopWreckRecord.MemoryValue b) {
        return a == null ? b == null : b != null && a.type().equals(b.type()) && a.value().equals(b.value());
    }

    private static void preflightRemoval(SectorAPI sector, StarSystemAPI system, Set<SectorEntityToken> targets, boolean wholeSystem) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        if (player != null && player.isInHyperspaceTransition()) throw new IllegalStateException("Encounter removal waits for the native jump transition");
        var ui = sector.getCampaignUI(); var dialog = ui == null ? null : ui.getCurrentInteractionDialog();
        SectorEntityToken target = dialog == null ? null : dialog.getInteractionTarget();
        if (target == null) return;
        MemoryAPI targetMemory = target.getMemoryWithoutUpdate();
        boolean inside = target.getContainingLocation() == system || target.getStarSystem() == system
                || targetMemory != null && Objects.equals(system.getOptionalUniqueId(), targetMemory.get(ENCOUNTER_ID_KEY));
        if (targets.contains(target) || wholeSystem && inside)
            throw new IllegalStateException("Encounter removal waits for the current interaction dialog");
    }

    private void initializeSector(SectorAPI sector) {
        if (sector == null || sector == initializedSector) return;
        initializedSector = sector; legacyAtLoad.clear(); warnedLegacy.clear(); guestBaseline.clear(); guestPending.clear();
        for (StarSystemAPI system : systems(sector)) if (system.getMemoryWithoutUpdate().get(RECIPE_KEY) == null) legacyAtLoad.add(system);
    }

    private static Map<Role, List<SectorEntityToken>> membership(StarSystemAPI system) {
        Map<Role, List<SectorEntityToken>> groups = new EnumMap<>(Role.class);
        for (Role role : Role.values()) groups.put(role, new ArrayList<>());
        Set<SectorEntityToken> seen = identitySet(List.of());
        for (SectorEntityToken entity : new ArrayList<>(system.getAllEntities())) if (!(entity instanceof CampaignFleetAPI) && seen.add(entity))
            groups.get(Role.SYSTEM).add(entity);
        SectorEntityToken anchor = system.getHyperspaceAnchor();
        if (anchor != null && seen.add(anchor)) groups.get(Role.ANCHOR).add(anchor);
        if (system.getAutogeneratedJumpPointsInHyper() != null) for (JumpPointAPI jump : system.getAutogeneratedJumpPointsInHyper())
            if (seen.add(jump)) groups.get(Role.JUMP_POINT).add(jump);
        if (system.getAutogeneratedNascentWellsInHyper() != null) for (NascentGravityWellAPI well : system.getAutogeneratedNascentWellsInHyper())
            if (seen.add(well)) groups.get(Role.WELL).add(well);
        return groups;
    }

    private static Map<String, SectorEntityToken> ownedEntities(SectorAPI sector, StarSystemAPI system, CoopEncounterRecord recipe) {
        Set<String> wanted = new HashSet<>(); for (CoopEncounterRecord.Entity member : recipe.entities()) wanted.add(recipe.entityId(member));
        Map<String, SectorEntityToken> result = new LinkedHashMap<>();
        List<SectorEntityToken> candidates = new ArrayList<>(system.getAllEntities());
        if (sector.getHyperspace() != null) candidates.addAll(sector.getHyperspace().getAllEntities());
        for (SectorEntityToken entity : candidates) {
            if (entity instanceof CampaignFleetAPI || !entity.isAlive() || entity.isExpired()) continue;
            String key = CoopEntityIds.keyOf(entity);
            if (!wanted.contains(key)) continue;
            if (!recipe.instanceId().equals(entity.getMemoryWithoutUpdate().get(ENCOUNTER_ID_KEY))) continue;
            if (result.putIfAbsent(key, entity) != null) throw new IllegalStateException("Duplicate owned encounter entity " + key);
        }
        return result;
    }

    private static CoopEncounterRecord.Entity member(CoopEncounterRecord recipe, String id) {
        return recipe.entities().stream().filter(e -> recipe.entityId(e).equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown encounter special membership"));
    }
    private static CoopEncounterRecord.Orbit orbit(SectorEntityToken entity) {
        SectorEntityToken focus = entity.getOrbitFocus();
        return focus == null ? null : new CoopEncounterRecord.Orbit(CoopEntityIds.keyOf(focus), entity.getCircularOrbitAngle(),
                entity.getCircularOrbitRadius(), entity.getCircularOrbitPeriod());
    }
    private static void setOrbit(SectorEntityToken entity, CoopEncounterRecord.Orbit orbit, Map<String, SectorEntityToken> assigned) {
        if (orbit == null) { entity.setOrbit(null); return; }
        SectorEntityToken focus = Objects.requireNonNull(assigned.get(orbit.focusId()), "Unknown encounter special orbit focus");
        entity.setCircularOrbit(focus, orbit.angle(), orbit.radius(), orbit.period());
    }
    static void applySpecialOrbitAndPose(SectorEntityToken entity, CoopEncounterRecord.Special special,
                                         Map<String, SectorEntityToken> assigned) {
        setOrbit(entity, special.orbit(), assigned);
        if (special.type() == SpecialType.GHOST_WRECK) {
            // Native Dire hints install the orbit first, then put the ship at its planet's pose.
            // setCircularOrbit recomputes a different initial point, so restore the captured result.
            CoopWreckRecord wreck = special.wreck();
            entity.setLocation(wreck.x(), wreck.y()); entity.getVelocity().set(wreck.vx(), wreck.vy());
        }
    }
    private static void own(SectorEntityToken entity, String instance, String id) {
        // LocationToken centers/anchors cannot pass the engine's global entity lookup cast.
        // Keep every native engine ID and resolve our separately stored membership key locally.
        entity.getMemoryWithoutUpdate().set(ENCOUNTER_ID_KEY, instance);
        entity.getMemoryWithoutUpdate().set(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, id);
    }
    private static String specId(SectorEntityToken entity) {
        if (entity instanceof PlanetAPI planet) return "planet:" + planet.getSpec().getPlanetType();
        if (entity instanceof CampaignTerrainAPI terrain) return "terrain:" + terrain.getType();
        if (entity.getCustomEntityType() != null) return "custom:" + entity.getCustomEntityType();
        if (entity instanceof JumpPointAPI) return "jump_point";
        if (entity instanceof NascentGravityWellAPI) return "well";
        return "token";
    }
    private static boolean isAbyssal(StarSystemAPI system) { return system.hasTag(Tags.TEMPORARY_LOCATION) && system.hasTag(Tags.SYSTEM_ABYSSAL); }
    private static String ownedSystemId(StarSystemAPI system) {
        Object id = system.getMemoryWithoutUpdate().get(ENCOUNTER_ID_KEY); if (id == null) return null;
        if (!(id instanceof String text) || !text.equals(system.getOptionalUniqueId()) || !isAbyssal(system))
            throw new IllegalStateException("Invalid encounter system ownership");
        CoopEncounterRecord.entityId(text, Role.SYSTEM, 0); return text;
    }
    private static CoopEncounterRecord savedRecipe(StarSystemAPI system) {
        Object encoded = system.getMemoryWithoutUpdate().get(RECIPE_KEY); if (encoded == null) return null;
        if (!(encoded instanceof String text)) throw new IllegalStateException("Encounter recipe is not primitive text");
        CoopEncounterRecord recipe = CoopEncounterRecord.decode(text);
        if (!recipe.instanceId().equals(system.getOptionalUniqueId())) throw new IllegalStateException("Saved encounter instance differs from system");
        return recipe;
    }
    private static StarSystemAPI findSystem(SectorAPI sector, String id) {
        StarSystemAPI found = null;
        for (StarSystemAPI system : systems(sector)) if (id.equals(ownedSystemId(system))) {
            if (found != null) throw new IllegalStateException("Duplicate encounter instance " + id); found = system;
        }
        return found;
    }
    private static List<StarSystemAPI> systems(SectorAPI sector) {
        return sector.getStarSystems() == null ? List.of() : new ArrayList<>(sector.getStarSystems());
    }
    private static <T> Set<T> identitySet(List<T> values) {
        Set<T> result = Collections.newSetFromMap(new IdentityHashMap<>()); result.addAll(values); return result;
    }
    private static String requiredString(MemoryAPI memory, String key) {
        Object value = memory.get(key); if (!(value instanceof String text)) throw new IllegalStateException("Missing primitive encounter capture: " + key); return text;
    }
    private void warnLegacy(StarSystemAPI system) {
        String id = system.getOptionalUniqueId() == null ? system.getId() : system.getOptionalUniqueId();
        if (warnedLegacy.add(id)) CoopLog.warn(CoopEncounterWorld.class, "Skipping pre-P26 or unsupported abyssal system without a replay recipe: " + id);
    }
}
