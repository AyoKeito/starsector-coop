package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic-only (CoopDebug) probe that dumps the engine's ground-truth detection numbers for every
 * NPC fleet so a "host sees it, guest doesn't" discrepancy can be diffed by {@code coopFleetId} across
 * the two logs instead of guessed at from screenshots.
 *
 * <p>The trick: the host owns the guest's own fleet as the Phase 8 reverse-mirror ({@code
 * $coopMirrorFleet}, built from the guest's real ships → real sensor strength). So the host can ask
 * the engine "what would the guest detect?" via {@link SectorEntityToken#getVisibilityLevelTo} /
 * {@link SectorEntityToken#getMaxSensorRangeToDetect} and we can compare that predicted guest-view
 * against what the guest client actually renders. Each line is keyed by the same {@code coopFleetId}
 * (host {@code fleet.getId()} == guest {@code $coopNpcFleetId}) so the two dumps line up.
 *
 * <p>Detection model (reverse-engineered from the API): an observer detects a target when
 * {@code distance <= observer.getMaxSensorRangeToDetect(target)}, where that range grows with the
 * observer's sensor strength and the target's sensor profile. Both inputs are logged so a NONE verdict
 * can be attributed to a low profile (replication gap), a weak observer (sensor-strength gap), or
 * simply being out of range (correct fog-of-war).
 */
public final class CoopFleetVisibilityProbe {
    private static final String PLAYER_MIRROR_TAG = CoopMirrorTags.PLAYER_MIRROR_TAG;
    private static final String NPC_MIRROR_TAG = CoopMirrorTags.NPC_MIRROR_TAG;

    private CoopFleetVisibilityProbe() {
    }

    /** Host dump: every real NPC fleet with the host-player view and the predicted guest view. */
    public static String dumpHost(SectorAPI sector) {
        if (sector == null) {
            return "host: no sector";
        }
        CampaignFleetAPI player = sector.getPlayerFleet();
        CampaignFleetAPI guestMirror = findPlayerMirror(sector);
        StringBuilder out = new StringBuilder(256);
        out.append("HOST visibility probe")
                .append(" hostPlayerStr=").append(player == null ? "?" : fmt(player.getSensorStrength()))
                .append(" guestMirror=").append(guestMirror == null ? "MISSING"
                        : "str=" + fmt(guestMirror.getSensorStrength()) + " loc=" + locId(guestMirror));
        List<CampaignFleetAPI> fleets = realNpcFleets(sector);
        out.append(" fleets=").append(fleets.size());
        for (CampaignFleetAPI fleet : fleets) {
            out.append("\n  H ").append(idOf(fleet)).append(" \"").append(safeName(fleet)).append('"')
                    .append(" loc=").append(locId(fleet))
                    .append(" prof=").append(fmt(fleet.getSensorProfile()))
                    .append(" str=").append(fmt(safeStrength(fleet)))
                    .append(" ringR=").append(fmt(player == null ? -1f : safeMaxRange(fleet, player)))
                    .append(" visHost=").append(visToPlayer(fleet));
            if (guestMirror != null) {
                out.append(" | guestView vis=").append(visTo(fleet, guestMirror))
                        .append(" maxRng=").append(fmt(safeMaxRange(guestMirror, fleet)))
                        .append(" dist=").append(fmt(distance(fleet, guestMirror)));
            }
        }
        return out.toString();
    }

    /** Guest dump: every NPC mirror with the guest-player view the host predicted. */
    public static String dumpGuest(SectorAPI sector) {
        if (sector == null) {
            return "guest: no sector";
        }
        CampaignFleetAPI player = sector.getPlayerFleet();
        StringBuilder out = new StringBuilder(256);
        out.append("GUEST visibility probe")
                .append(" guestPlayerStr=").append(player == null ? "?" : fmt(player.getSensorStrength()))
                .append(" loc=").append(player == null ? "?" : locId(player));
        List<CampaignFleetAPI> mirrors = npcMirrors(sector);
        out.append(" mirrors=").append(mirrors.size());
        for (CampaignFleetAPI mirror : mirrors) {
            out.append("\n  G ").append(npcMirrorId(mirror)).append(" \"").append(safeName(mirror)).append('"')
                    .append(" loc=").append(locId(mirror))
                    .append(" prof=").append(fmt(mirror.getSensorProfile()))
                    .append(" str=").append(fmt(safeStrength(mirror)))
                    .append(" ringR=").append(fmt(player == null ? -1f : safeMaxRange(mirror, player)))
                    .append(" vis=").append(visToPlayer(mirror));
            if (player != null) {
                out.append(" maxRng=").append(fmt(safeMaxRange(player, mirror)))
                        .append(" dist=").append(fmt(distance(mirror, player)));
            }
        }
        return out.toString();
    }

    /**
     * The guest's <em>actual</em> visibility of every host NPC mirror, keyed by {@code coopFleetId}:
     * the machine-readable half of {@link #dumpGuest}, for the agent bridge's {@code visibility} verb.
     *
     * <p>Same walk, same accessors as the dump — only the id is the full {@code coopFleetId} instead of
     * the log-friendly short form, because this map is compared against the host's estimate below.
     */
    public static Map<String, String> guestVisibilityActual(SectorAPI sector) {
        Map<String, String> out = new TreeMap<>();
        if (sector == null) {
            return out;
        }
        for (CampaignFleetAPI mirror : npcMirrors(sector)) {
            out.put(fullNpcMirrorId(mirror), visToPlayer(mirror));
        }
        return out;
    }

    /**
     * The host's <em>estimate</em> of that same map, asked of the engine through the guest's reverse
     * mirror. Equal to {@link #guestVisibilityActual} on the other client whenever the two sides' sensor
     * model agrees; the entries that differ are the sensor-replication gaps.
     */
    public static Map<String, String> guestVisibilityEstimate(SectorAPI sector) {
        Map<String, String> out = new TreeMap<>();
        if (sector == null) {
            return out;
        }
        CampaignFleetAPI guestMirror = findPlayerMirror(sector);
        for (CampaignFleetAPI fleet : realNpcFleets(sector)) {
            out.put(fullIdOf(fleet), guestMirror == null ? "?" : visTo(fleet, guestMirror));
        }
        return out;
    }

    // ---- collection helpers ------------------------------------------------------------------

    private static List<CampaignFleetAPI> realNpcFleets(SectorAPI sector) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        List<CampaignFleetAPI> out = new ArrayList<>();
        for (LocationAPI loc : allLocations(sector)) {
            for (CampaignFleetAPI fleet : loc.getFleets()) {
                if (fleet == null || fleet == player || fleet.isStationMode()) {
                    continue;
                }
                if (isPlayerMirror(fleet) || hasTag(fleet, NPC_MIRROR_TAG)) {
                    continue;
                }
                out.add(fleet);
            }
        }
        return out;
    }

    private static List<CampaignFleetAPI> npcMirrors(SectorAPI sector) {
        List<CampaignFleetAPI> out = new ArrayList<>();
        for (LocationAPI loc : allLocations(sector)) {
            for (CampaignFleetAPI fleet : loc.getFleets()) {
                if (fleet != null && hasTag(fleet, NPC_MIRROR_TAG)) {
                    out.add(fleet);
                }
            }
        }
        return out;
    }

    private static List<LocationAPI> allLocations(SectorAPI sector) {
        List<LocationAPI> locs = new ArrayList<>(sector.getAllLocations());
        LocationAPI hyper = sector.getHyperspace();
        if (hyper != null && !locs.contains(hyper)) {
            locs.add(hyper);
        }
        locs.removeIf(l -> l == null);
        return locs;
    }

    private static CampaignFleetAPI findPlayerMirror(SectorAPI sector) {
        for (LocationAPI loc : allLocations(sector)) {
            for (CampaignFleetAPI fleet : loc.getFleets()) {
                if (fleet != null && isPlayerMirror(fleet)) {
                    return fleet;
                }
            }
        }
        return null;
    }

    // ---- field accessors (all best-effort; debug must never throw) -----------------------------

    private static boolean isPlayerMirror(CampaignFleetAPI fleet) {
        MemoryAPI m = fleet.getMemoryWithoutUpdate();
        return m != null && m.getBoolean(PLAYER_MIRROR_TAG);
    }

    private static boolean hasTag(CampaignFleetAPI fleet, String key) {
        MemoryAPI m = fleet.getMemoryWithoutUpdate();
        return m != null && m.contains(key);
    }

    private static String npcMirrorId(CampaignFleetAPI fleet) {
        return shortId(fullNpcMirrorId(fleet));
    }

    private static String idOf(CampaignFleetAPI fleet) {
        return shortId(fullIdOf(fleet));
    }

    /** Unshortened forms of the two ids above; the structured view keys on them, the dumps shorten. */
    private static String fullIdOf(CampaignFleetAPI fleet) {
        try {
            String id = fleet.getId();
            return id == null || id.isEmpty() ? "?" : id;
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    private static String fullNpcMirrorId(CampaignFleetAPI fleet) {
        try {
            Object v = fleet.getMemoryWithoutUpdate().get(NPC_MIRROR_TAG);
            String id = v == null ? fleet.getId() : v.toString();
            return id == null || id.isEmpty() ? "?" : id;
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    private static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        return id.length() <= 10 ? id : id.substring(id.length() - 10);
    }

    private static String safeName(CampaignFleetAPI fleet) {
        try {
            String n = fleet.getName();
            return n == null ? "" : n;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String locId(SectorEntityToken token) {
        try {
            LocationAPI loc = token.getContainingLocation();
            return loc == null ? "none" : loc.getId();
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    private static String visToPlayer(SectorEntityToken token) {
        try {
            return String.valueOf(token.getVisibilityLevelToPlayerFleet());
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    private static String visTo(SectorEntityToken token, SectorEntityToken observer) {
        try {
            return String.valueOf(token.getVisibilityLevelTo(observer));
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    private static float safeStrength(SectorEntityToken token) {
        try {
            return token.getSensorStrength();
        } catch (RuntimeException ex) {
            return -1f;
        }
    }

    private static float safeMaxRange(SectorEntityToken observer, SectorEntityToken target) {
        try {
            return observer.getMaxSensorRangeToDetect(target);
        } catch (RuntimeException ex) {
            return -1f;
        }
    }

    private static float distance(SectorEntityToken a, SectorEntityToken b) {
        try {
            Vector2f pa = a.getLocation();
            Vector2f pb = b.getLocation();
            if (pa == null || pb == null) {
                return -1f;
            }
            float dx = pa.x - pb.x;
            float dy = pa.y - pb.y;
            return (float) Math.sqrt(dx * dx + dy * dy);
        } catch (RuntimeException ex) {
            return -1f;
        }
    }

    private static String fmt(float v) {
        return String.format("%.0f", v);
    }
}
