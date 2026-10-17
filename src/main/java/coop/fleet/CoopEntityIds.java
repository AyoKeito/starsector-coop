package coop.fleet;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared entity identity without changing native IDs used by engine location caches. */
public final class CoopEntityIds {
    public static final String ENCOUNTER_ENTITY_ID_KEY = "$coopEncEntityId";
    private static final String ENCOUNTER_ID_KEY = "$coopEncId";
    private static final String PREFIX = "coopenc_";
    private static final Pattern ENCOUNTER_KEY = Pattern.compile(
            "coopenc_([A-Za-z0-9_-]{1,128})_(system|anchor|jump_point|well)([0-9]{1,4})");

    private CoopEntityIds() { }

    /** Native IDs remain the fallback, including replicas saved before the metadata key existed. */
    public static String keyOf(SectorEntityToken entity) {
        if (entity == null) return null;
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        Object key = memory == null ? null : memory.get(ENCOUNTER_ENTITY_ID_KEY);
        return key instanceof String text && !text.isEmpty() ? text : entity.getId();
    }

    public static SectorEntityToken byId(SectorAPI sector, String key) {
        if (sector == null || key == null || key.isEmpty()) return null;
        if (key.startsWith(PREFIX)) return encounterById(sector, key);
        try {
            SectorEntityToken cached = sector.getEntityById(key);
            if (cached != null) return cached;
        } catch (ClassCastException ex) {
            // Native LocationToken pseudo-centres implement SectorEntityToken, but the global
            // lookup may cast them to BaseCampaignEntity while validating its cached entry.
            // Walk the location lists directly, without asking another engine ID cache.
        }
        SectorEntityToken found = null;
        Set<LocationAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LocationAPI location : CoopLocations.all(sector)) {
            if (location == null || !seen.add(location)) continue;
            found = nativeMatch(location, key, found);
        }
        LocationAPI hyper = sector.getHyperspace();
        if (hyper != null && seen.add(hyper)) found = nativeMatch(hyper, key, found);
        return found;
    }

    private static SectorEntityToken encounterById(SectorAPI sector, String key) {
        Matcher parsed = ENCOUNTER_KEY.matcher(key);
        if (!parsed.matches()) return null;
        String ordinal = parsed.group(3);
        int number = Integer.parseInt(ordinal);
        if (number >= 4096 || !Integer.toString(number).equals(ordinal)) return null;
        String instance = parsed.group(1);
        LocationAPI resolved = CoopLocations.byId(sector, instance);
        if (!(resolved instanceof StarSystemAPI system) || !instance.equals(system.getOptionalUniqueId())) return null;
        LocationAPI location = parsed.group(2).equals("system") ? system : sector.getHyperspace();
        if (location == null) return null;
        SectorEntityToken found = null;
        for (SectorEntityToken entity : entities(location)) {
            if (entity == null || !key.equals(keyOf(entity))) continue;
            MemoryAPI memory = entity.getMemoryWithoutUpdate();
            if (memory == null || !instance.equals(memory.get(ENCOUNTER_ID_KEY))) continue;
            if (found != null && found != entity) throw duplicate(key);
            found = entity;
        }
        return found;
    }

    private static SectorEntityToken nativeMatch(LocationAPI location, String key, SectorEntityToken found) {
        for (SectorEntityToken entity : entities(location)) {
            if (entity == null || !Objects.equals(key, entity.getId())) continue;
            if (found != null && found != entity) throw duplicate(key);
            found = entity;
        }
        return found;
    }

    private static List<SectorEntityToken> entities(LocationAPI location) {
        List<SectorEntityToken> entities = location.getAllEntities();
        return entities == null ? List.of() : new ArrayList<>(entities);
    }

    private static IllegalStateException duplicate(String key) {
        return new IllegalStateException("Duplicate shared entity identity: " + key);
    }
}
