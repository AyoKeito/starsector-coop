package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ghosts.GhostFrequencies;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhostManager;
import com.fs.starfarer.api.impl.campaign.ghosts.types.AbyssalDrifterGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.AbyssalDrifterGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.types.ChargerGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.ChargerGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.types.NoGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.NoGhostCreator;
import com.fs.starfarer.api.impl.campaign.ghosts.types.ZigguratGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.ZigguratGhostCreator;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import coop.presence.CoopPresenceRegistry;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Guest-local cosmetics. The manager, random state and cooldowns never enter a save. */
public final class CoopSensorGhostManager extends SensorGhostManager {
    private static final String HANDLE = "$ghostManager";
    private static final String OWNED_ENTITY_TAG = "coop_cosmetic_ghost";
    private static final List<SensorGhostCreator> COSMETIC_CREATORS = List.of(
            new ChargerGhostCreator(), new NoGhostCreator(), new ZigguratGhostCreator(),
            new AbyssalDrifterGhostCreator());
    private static final Set<Class<?>> COSMETIC_GHOSTS = Set.of(
            ChargerGhost.class, NoGhost.class, ZigguratGhost.class, AbyssalDrifterGhost.class);

    private final transient SectorAPI owner;
    private transient boolean closed;

    private CoopSensorGhostManager(SectorAPI owner) {
        this.owner = owner;
    }

    /** Guest launch only; repeated installation in one sector preserves the current local ghosts. */
    public static CoopSensorGhostManager install(SectorAPI sector, boolean guest) {
        if (sector == null) return null;
        if (!guest) {
            removeStaleGhostEntities(sector, true);
            return null;
        }
        CoopSensorGhostManager existing = installed(sector);
        boolean supported = supportedVersion();
        if (existing != null && supported) {
            removeManagers(sector, existing);
            existing.bindHandle();
            return existing;
        }
        removeManagers(sector, null);
        removeStaleGhostEntities(sector, false);
        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        if (memory != null) memory.unset(HANDLE);
        if (!supported) {
            CoopLog.warn(CoopSensorGhostManager.class,
                    "Coop ghost manager disabled: expected Starsector " + CoopPresenceRegistry.PINNED_VERSION);
            return null;
        }
        CoopSensorGhostManager manager = new CoopSensorGhostManager(sector);
        sector.addTransientScript(manager);
        manager.bindHandle();
        CoopLog.info(CoopSensorGhostManager.class, "Coop ghost manager installed: "
                + cosmeticCreatorNames());
        return manager;
    }

    /** Native lookup uses sector memory, so detach that otherwise persistent root before saving. */
    public static void beforeSave(SectorAPI sector) {
        if (sector != null && sector.getMemoryWithoutUpdate() != null
                && sector.getMemoryWithoutUpdate().get(HANDLE) instanceof CoopSensorGhostManager)
            sector.getMemoryWithoutUpdate().unset(HANDLE);
    }

    /** Also used after a failed save; rebind only the registered manager of this exact sector. */
    public static void afterSave(SectorAPI sector) {
        CoopSensorGhostManager manager = installed(sector);
        if (manager != null) manager.bindHandle();
    }

    public static boolean ownsCachedHandle(SectorAPI sector) {
        if (sector == null || sector.getMemoryWithoutUpdate() == null) return false;
        Object handle = sector.getMemoryWithoutUpdate().get(HANDLE);
        return handle instanceof CoopSensorGhostManager manager && manager.owner == sector && !manager.closed
                && scripts(sector.getTransientScripts()).contains(manager);
    }

    private static CoopSensorGhostManager installed(SectorAPI sector) {
        if (sector == null) return null;
        for (EveryFrameScript script : scripts(sector.getTransientScripts())) {
            if (script instanceof CoopSensorGhostManager manager && manager.owner == sector && !manager.closed)
                return manager;
        }
        return null;
    }

    private static void removeManagers(SectorAPI sector, CoopSensorGhostManager keep) {
        List<EveryFrameScript> registered = new ArrayList<>(scripts(sector.getScripts()));
        registered.addAll(scripts(sector.getTransientScripts()));
        for (EveryFrameScript script : registered) {
            if (!(script instanceof SensorGhostManager manager) || script == keep) continue;
            for (SensorGhost ghost : new ArrayList<>(manager.getGhosts())) removeEntity(ghost);
            manager.getGhosts().clear();
            sector.removeScript(script);
            sector.removeTransientScript(script);
            if (manager instanceof CoopSensorGhostManager cosmetic) cosmetic.closed = true;
        }
    }

    private static void removeStaleGhostEntities(SectorAPI sector, boolean ownedOnly) {
        List<SectorEntityToken> ghosts = sector.getEntitiesWithTag(ownedOnly ? OWNED_ENTITY_TAG : "ghost");
        if (ghosts == null) return;
        for (SectorEntityToken entity : new ArrayList<>(ghosts)) {
            if (entity != null && Entities.SENSOR_GHOST.equals(entity.getCustomEntityType())
                    && entity.getContainingLocation() != null) entity.getContainingLocation().removeEntity(entity);
        }
    }

    private static void removeEntity(SensorGhost ghost) {
        SectorEntityToken entity = ghost == null ? null : ghost.getEntity();
        if (entity != null && entity.getContainingLocation() != null) entity.getContainingLocation().removeEntity(entity);
    }

    private static List<EveryFrameScript> scripts(List<EveryFrameScript> scripts) {
        return scripts == null ? List.of() : scripts;
    }

    private static boolean supportedVersion() {
        return Global.getSettings() != null && Global.getSettings().getVersionString() != null
                && Global.getSettings().getVersionString().contains(CoopPresenceRegistry.PINNED_VERSION);
    }

    private void bindHandle() {
        if (!closed && Global.getSector() == owner && supportedVersion()) {
            MemoryAPI memory = owner.getMemoryWithoutUpdate();
            if (memory != null && memory.get(HANDLE) != this) memory.set(HANDLE, this);
        }
    }

    @Override public void advance(float amount) {
        if (isDone() || !supportedVersion()) return;
        bindHandle();
        // An externally injected ghost must not acquire a world-mutating advance through this manager.
        for (Iterator<SensorGhost> iterator = ghosts.iterator(); iterator.hasNext();) {
            SensorGhost ghost = iterator.next();
            if (!isCosmeticGhost(ghost)) {
                removeEntity(ghost);
                iterator.remove();
            }
        }
        super.advance(amount);
    }

    @Override public boolean isDone() {
        return closed || Global.getSector() != owner
                || !scripts(owner.getTransientScripts()).contains(this);
    }

    /** Pinned native selection/cooldown lifecycle, with an instance-independent closed creator list. */
    @Override public void spawnGhost() {
        if (isDone() || !supportedVersion() || owner.getPlayerFleet() == null) return;
        var fleet = owner.getPlayerFleet();
        boolean nearStream = Misc.isInsideSlipstream(fleet.getLocation(), 1000f, fleet.getContainingLocation());
        boolean inAbyss = Misc.isInAbyss(fleet);
        WeightedRandomPicker<SensorGhostCreator> picker = new WeightedRandomPicker<>(random);
        for (SensorGhostCreator creator : COSMETIC_CREATORS) {
            if (perCreatorTimeouts.contains(creator.getId())) continue;
            if (nearStream && !creator.canSpawnWhilePlayerInOrNearSlipstream()) continue;
            if (inAbyss && !creator.canSpawnWhilePlayerInAbyss()) continue;
            if (!inAbyss && !creator.canSpawnWhilePlayerOutsideAbyss()) continue;
            picker.add(creator, frequency(creator));
        }
        SensorGhostCreator creator = picker.pick();
        if (creator != null) createCosmeticGhost(creator);
    }

    private float frequency(SensorGhostCreator creator) {
        if (creator.getClass() != NoGhostCreator.class) return creator.getFrequency(this);
        float total = 0;
        for (SensorGhostCreator cosmetic : COSMETIC_CREATORS)
            if (cosmetic.getClass() != NoGhostCreator.class) total += cosmetic.getFrequency(this);
        // Native NoGhostCreator consults the global CREATORS list, including unsafe/modded entries.
        return noGhostWeight(spawnTriggeredBySensorBurst, GhostFrequencies.getCoreFactor(), total);
    }

    static float noGhostWeight(boolean sensorBurst, float coreFactor, float allowedWeight) {
        return sensorBurst ? 0f : Math.max(10f, allowedWeight) * (0.5f + coreFactor * 5f);
    }

    /** Shared path for ordinary selection and deterministic native-creator lifecycle tests. */
    void createCosmeticGhost(SensorGhostCreator creator) {
        if (COSMETIC_CREATORS.stream().noneMatch(allowed -> allowed.getClass() == creator.getClass()))
            throw new IllegalArgumentException("Guest cosmetic creator is not allowed: " + creator.getClass().getName());
        List<SensorGhost> created = creator.createGhost(this);
        boolean failed = created == null || created.isEmpty();
        if (created != null) {
            for (SensorGhost ghost : created) {
                if (!isCosmeticGhost(ghost)) {
                    for (SensorGhost cleanup : created) removeEntity(cleanup);
                    throw new IllegalStateException("Guest cosmetic creator returned an unapproved ghost");
                }
                failed |= ghost.isCreationFailed();
                ghost.setDespawnInAbyss(!creator.canSpawnWhilePlayerInAbyss());
            }
        }
        if (failed) {
            perCreatorTimeouts.set(creator.getId(), MIN_FAILED_CREATOR_TIMEOUT_DAYS
                    + random.nextFloat() * (MAX_FAILED_CREATOR_TIMEOUT_DAYS - MIN_FAILED_CREATOR_TIMEOUT_DAYS));
            return;
        }
        for (SensorGhost ghost : created)
            if (ghost.getEntity() != null) ghost.getEntity().addTag(OWNED_ENTITY_TAG);
        ghosts.addAll(created);
        if (random.nextFloat() < FULL_TIMEOUT_TRIGGER_PROBABILITY)
            timeoutRemaining = MIN_FULL_GHOST_TIMEOUT_DAYS
                    + random.nextFloat() * (MAX_FULL_GHOST_TIMEOUT_DAYS - MIN_FULL_GHOST_TIMEOUT_DAYS);
        else timeoutRemaining = MIN_SHORT_GHOST_TIMEOUT_DAYS
                    + random.nextFloat() * (MAX_SHORT_GHOST_TIMEOUT_DAYS - MIN_SHORT_GHOST_TIMEOUT_DAYS);
        perCreatorTimeouts.set(creator.getId(), creator.getTimeoutDaysOnSuccessfulCreate(this));
    }

    static boolean isCosmeticGhost(SensorGhost ghost) {
        return ghost != null && COSMETIC_GHOSTS.contains(ghost.getClass());
    }

    public static List<String> cosmeticCreatorNames() {
        return COSMETIC_CREATORS.stream().map(creator -> creator.getClass().getSimpleName()).toList();
    }
}
