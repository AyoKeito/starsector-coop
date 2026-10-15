package coop.combat.join;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.fleet.CoopMirrorTags;

import java.util.List;

/**
 * Whether the partner's player-fleet mirror currently carries a non-flagship with
 * {@link CoopRemoteCommRules#HULLMOD_ID}.
 */
public final class CoopMarkedRemoteShipProbe {

    private CoopMarkedRemoteShipProbe() {
    }

    public static boolean hasMarkedNonFlagship(SectorAPI sector) {
        if (sector == null) {
            return false;
        }
        CampaignFleetAPI player = sector.getPlayerFleet();
        if (player == null) {
            return false;
        }
        CampaignFleetAPI mirror = CoopPartnerSeparationProbe.findPartnerPlayerMirror(sector, player);
        if (mirror == null || mirror.getMembersWithFightersCopy() == null) {
            return false;
        }
        List<FleetMemberAPI> members = mirror.getMembersWithFightersCopy();
        for (int i = 0; i < members.size(); ++i) {
            if (!CoopRemoteCommRules.canMarkAtFleetIndex(i)) {
                continue;
            }
            FleetMemberAPI member = members.get(i);
            if (member == null || member.isFighterWing()) {
                continue;
            }
            try {
                if (member.getVariant() != null
                        && member.getVariant().hasHullMod(CoopRemoteCommRules.HULLMOD_ID)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        // Tag presence alone is not enough — but if the mirror exists and ally is allowed, still
        // allow the call UI so the player can learn they need to mark a ship.
        try {
            if (mirror.getMemoryWithoutUpdate() != null
                    && mirror.getMemoryWithoutUpdate().contains(CoopMirrorTags.PLAYER_MIRROR_TAG)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
