package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PersistentUIDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import coop.fleet.CoopNpcFleetReplicator;
import coop.presence.CoopPresenceRegistry;
import coop.util.CoopDebug;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit QA setup through the native player autopilot, never a movement or velocity override. */
public final class CoopCourseDiagnostics {
    private static final float MAX_COORDINATE = 1_000_000f;

    private CoopCourseDiagnostics() { }

    public static Map<String, Object> set(SectorAPI sector, boolean gameplayActive,
                                          Float x, Float y, boolean clear) {
        if (clear ? x != null || y != null : x == null || y == null)
            throw new IllegalArgumentException("course requires both x and y, or clear without coordinates");
        if (!clear && (!valid(x) || !valid(y)))
            throw new IllegalArgumentException("course coordinates must be finite and within 1000000");
        if (!CoopDebug.diagnosticsEnabled())
            throw new IllegalStateException("course requires debug diagnostics");
        if (CoopAgentBridge.configuredPort() == 0)
            throw new IllegalStateException("course requires the opt-in agent bridge");
        if (Global.getSettings() == null || Global.getSettings().getVersionString() == null
                || !Global.getSettings().getVersionString().contains(CoopPresenceRegistry.PINNED_VERSION))
            throw new IllegalStateException("course requires Starsector " + CoopPresenceRegistry.PINNED_VERSION);
        if (sector == null || Global.getSector() != sector || !gameplayActive || !sector.isPaused())
            throw new IllegalStateException("course requires the active current campaign while paused");
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        LocationAPI location = sector.getCurrentLocation();
        if (fleet == null || !fleet.isPlayerFleet() || location == null
                || fleet.getContainingLocation() != location || fleet.getMemoryWithoutUpdate() == null
                || fleet.getMemoryWithoutUpdate().getBoolean(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG)
                || fleet.getMemoryWithoutUpdate().contains(CoopNpcFleetReplicator.NPC_MIRROR_TAG))
            throw new IllegalStateException("course requires the real player fleet in the current location");
        if (fleet.isInHyperspaceTransition() || fleet.getBattle() != null)
            throw new IllegalStateException("course is unavailable during a jump or battle");
        CampaignUIAPI ui = sector.getCampaignUI();
        PersistentUIDataAPI data = sector.getUIData();
        if (ui == null || data == null || ui.isShowingDialog())
            throw new IllegalStateException("course requires an available campaign screen without a dialog");

        SectorEntityToken requested = null;
        if (clear) ui.clearLaidInCourse();
        else {
            requested = location.createToken(x, y);
            if (requested == null || requested.getContainingLocation() != location)
                throw new IllegalStateException("native course token was not created in the current location");
            sector.layInCourseFor(requested);
        }
        SectorEntityToken course = data.getCourseTarget();
        SectorEntityToken interaction = fleet.getInteractionTarget();
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("qa", true);
        receipt.put("mode", clear ? "clear" : "set");
        receipt.put("nativeReturned", true);
        receipt.put("courseTargetMatches", course == requested);
        receipt.put("interactionTargetMatches", interaction == requested);
        receipt.put("followingDirectCommand", ui.isFollowingDirectCommand());
        receipt.put("courseTarget", target(course));
        receipt.put("interactionTarget", target(interaction));
        receipt.put("position", Map.of("x", fleet.getLocation().x, "y", fleet.getLocation().y));
        return receipt;
    }

    private static boolean valid(Float value) {
        return value != null && Float.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    private static Map<String, Object> target(SectorEntityToken target) {
        if (target == null) return Map.of("present", false);
        String id = target.getId();
        return Map.of("present", true, "engineId", id == null ? "" : id,
                "identity", Integer.toHexString(System.identityHashCode(target)),
                "x", target.getLocation().x, "y", target.getLocation().y);
    }
}
