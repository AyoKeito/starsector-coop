package coop.fleet;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one place the mod walks the sector's locations, and the one place it turns a location id back
 * into a {@link LocationAPI}.
 *
 * <p><b>Why this exists.</b> Four classes each carried their own copy of the same walk, and every copy
 * called {@code getAllLocations()} <em>twice</em> — once to iterate, once to run a
 * {@code !contains(hyperspace)} guard. That is not a free call:
 * {@code CampaignEngine.getAllLocations()} builds two fresh {@code ArrayList}s and copies every star
 * system (~130 in a stock sector) into them on each invocation, so the guard alone doubled the cost of
 * a walk that several of the callers run every frame. The guard is also dead:
 * <b>{@code getAllLocations()} already includes hyperspace</b> (verified against 0.98a engine
 * bytecode — the returned list is the star systems plus the hyperspace location), so the appended
 * entry was never reached and the {@code contains} scan never found anything to skip.
 *
 * <p>One call, one pass, no guard. Nulls are skipped, exactly as the old copies did.
 *
 * <h2>The id&rarr;location cache</h2>
 * {@link #byId(SectorAPI, String)} answers the "which location is this id?" question the mirror
 * driving path asks at 10 Hz per mirrored fleet. Locations are created at world generation and are
 * stable for the life of a campaign, so the map is built once per sector by a single walk and reused.
 * It is invalidated by identity — a different {@link SectorAPI} instance means a different game — and
 * explicitly by {@link #invalidate()} from {@code CoopModPlugin.onGameLoad}, which is belt and braces
 * for the case where a reload hands back the same engine object.
 *
 * <p>Campaign thread only. There is no synchronization here and none is needed; every caller runs
 * inside {@code EveryFrameScript.advance()}.
 */
public final class CoopLocations {

    /** The sector the {@link #byId} map was built from; identity-compared, never dereferenced. */
    private static SectorAPI cachedSector;
    private static final Map<String, LocationAPI> BY_ID = new HashMap<>();
    /** The last id a freshly rebuilt map still did not contain (see {@link #byId}). */
    private static String lastMissedId = "";

    private CoopLocations() {
    }

    /**
     * Visits every location in the sector exactly once, hyperspace included (it is in the engine's
     * own list — see the class doc). Null entries are skipped; a null sector visits nothing.
     */
    public static void forEach(SectorAPI sector, Consumer<LocationAPI> consumer) {
        if (consumer == null) {
            return;
        }
        List<LocationAPI> all = all(sector);
        for (int i = 0; i < all.size(); i++) {
            LocationAPI location = all.get(i);
            if (location != null) {
                consumer.accept(location);
            }
        }
    }

    /**
     * Every location, hyperspace included, from <em>one</em> {@code getAllLocations()} call. For the
     * searches {@link #forEach} cannot serve because they want to stop early; entries may be null and
     * the returned list must not be mutated.
     */
    public static List<LocationAPI> all(SectorAPI sector) {
        if (sector == null) {
            return List.of();
        }
        List<LocationAPI> all = sector.getAllLocations();
        return all == null ? List.of() : all;
    }

    /**
     * The location carrying {@code locationId}, or null when this client has none.
     *
     * <p>Served from a per-sector id map rather than a linear scan: the callers are the mirror apply
     * paths, which ask once per mirrored fleet per motion record (10 Hz) and once per fleet per
     * {@code NPC_FLEET_SET}.
     */
    public static LocationAPI byId(SectorAPI sector, String locationId) {
        if (sector == null || locationId == null || locationId.isEmpty()) {
            return null;
        }
        boolean freshlyBuilt = false;
        if (sector != cachedSector) {
            rebuild(sector);
            freshlyBuilt = true;
        }
        LocationAPI cached = BY_ID.get(locationId);
        if (cached != null) {
            return cached;
        }
        // A miss is either an id this client genuinely does not have, or a location that appeared
        // after the map was built (worldgen does not, but a mod's createStarSystem() could). One
        // rebuild answers both. Remembering the id that survived a rebuild is what keeps a snapshot
        // stream referencing an unknown location from rebuilding on every record.
        if (freshlyBuilt || locationId.equals(lastMissedId)) {
            lastMissedId = locationId;
            return null;
        }
        rebuild(sector);
        LocationAPI resolved = BY_ID.get(locationId);
        if (resolved == null) {
            lastMissedId = locationId;
        }
        return resolved;
    }

    /** Drops the id map. Called on game load: a new campaign has new locations under old ids. */
    public static void invalidate() {
        cachedSector = null;
        lastMissedId = "";
        BY_ID.clear();
    }

    /** Entries currently cached (diagnostics + tests). */
    static int cachedIdCount() {
        return BY_ID.size();
    }

    private static void rebuild(SectorAPI sector) {
        BY_ID.clear();
        lastMissedId = "";
        cachedSector = sector;
        forEach(sector, location -> {
            String id = location.getId();
            if (id != null && !id.isEmpty()) {
                BY_ID.putIfAbsent(id, location);
            }
        });
    }
}
