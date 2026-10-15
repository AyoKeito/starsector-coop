package coop.combat.join;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.fleet.CoopMirrorTags;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * Live-engine probe: distance from the local player fleet to the partner's player-fleet mirror.
 */
public final class CoopPartnerSeparationProbe {

    private CoopPartnerSeparationProbe() {
    }

    public static CoopPartnerSeparation measure(SectorAPI sector) {
        if (sector == null) {
            return CoopPartnerSeparation.unknown();
        }
        CampaignFleetAPI player = sector.getPlayerFleet();
        if (player == null) {
            return CoopPartnerSeparation.unknown();
        }
        CampaignFleetAPI mirror = findPartnerPlayerMirror(sector, player);
        if (mirror == null) {
            return CoopPartnerSeparation.unknown();
        }
        LocationAPI playerLoc = player.getContainingLocation();
        LocationAPI mirrorLoc = mirror.getContainingLocation();
        if (playerLoc == null || mirrorLoc == null) {
            return CoopPartnerSeparation.unknown();
        }
        if (playerLoc != mirrorLoc) {
            return CoopPartnerSeparation.differentLocation();
        }
        Vector2f a = player.getLocation();
        Vector2f b = mirror.getLocation();
        if (a == null || b == null) {
            return CoopPartnerSeparation.unknown();
        }
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return CoopPartnerSeparation.sameLocation((float) Math.sqrt(dx * dx + dy * dy));
    }

    static CampaignFleetAPI findPartnerPlayerMirror(SectorAPI sector, CampaignFleetAPI localPlayer) {
        // Prefer the player's current location first (common case).
        CampaignFleetAPI hit = findMirrorIn(localPlayer.getContainingLocation(), localPlayer);
        if (hit != null) {
            return hit;
        }
        try {
            for (LocationAPI loc : sector.getAllLocations()) {
                hit = findMirrorIn(loc, localPlayer);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Throwable ignored) {
            // some test stubs omit getAllLocations
        }
        return null;
    }

    private static CampaignFleetAPI findMirrorIn(LocationAPI loc, CampaignFleetAPI localPlayer) {
        if (loc == null) {
            return null;
        }
        List<CampaignFleetAPI> fleets;
        try {
            fleets = loc.getFleets();
        } catch (Throwable t) {
            return null;
        }
        if (fleets == null) {
            return null;
        }
        for (CampaignFleetAPI fleet : fleets) {
            if (fleet == null || fleet == localPlayer) {
                continue;
            }
            try {
                if (fleet.getMemoryWithoutUpdate() != null
                        && fleet.getMemoryWithoutUpdate().contains(CoopMirrorTags.PLAYER_MIRROR_TAG)) {
                    return fleet;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
