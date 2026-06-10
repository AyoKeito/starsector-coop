package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Makes the mirrored remote-player fleet an always-visible presence on the campaign map, rendered in
 * the local player's own faction color and labeled with the remote username
 * ({@code COOP_MP_DESIGN.md} section "Presence indicator").
 *
 * <h2>Visibility mechanism and its documented limitation</h2>
 * <p>Starsector 0.98a-RC8 exposes no public API that hard-forces a fleet to be visible irrespective
 * of sensor range. The closest public levers are the fleet's own sensor profile and detected-range
 * modifier ({@link com.fs.starfarer.api.campaign.SectorEntityToken#setSensorProfile(Float)} and
 * {@link com.fs.starfarer.api.campaign.SectorEntityToken#getDetectedRangeMod()}). We therefore force
 * a very large sensor profile and detected-range bonus, which makes the mirror detectable across
 * normal play distances. This is the public-API approximation of "always visible"; it is driven by
 * sensor mechanics rather than a hard visibility override, so at pathological extreme ranges the
 * engine may still cull it. If a future phase needs a true hard override, the documented fallback
 * (per the Phase 8 plan) is a custom campaign-map overlay marker anchored to the mirror fleet's
 * location.
 *
 * <p><b>Transponder is intentionally NOT forced here (Phase 9 decoupling).</b> Visibility comes only
 * from the sensor profile + detected-range bonus, which are independent of transponder state. Forcing
 * the transponder on would have overwritten the replicated real state, hiding the remote player's
 * running-dark from the other client's host-side simulation (so patrols could not react to it). The
 * mirror's transponder is now driven purely by the snapshot in {@link CoopFleetMirror}.
 *
 * <p>Coloring: the mirror fleet is assigned the local player's own faction so it renders in that
 * faction's color and is non-hostile to the local player. The username is set as the fleet name so
 * the campaign-map label reads as the remote player.
 */
public final class CoopPresenceIndicator {
    static final String PRESENCE_SOURCE = "coop_presence";
    static final float SENSOR_PROFILE = 100000f;
    static final float DETECTED_RANGE_BONUS = 100000f;
    private static final String DEFAULT_LABEL = "Coop Player";

    /** Campaign-map label for the mirror fleet: the remote username, with a stable fallback. */
    public static String presenceLabel(String username) {
        if (username == null) {
            return DEFAULT_LABEL;
        }
        String trimmed = username.trim();
        return trimmed.isEmpty() ? DEFAULT_LABEL : trimmed;
    }

    /**
     * Faction whose color the mirror is rendered in. Per the Phase 8 plan this is the local player's
     * own faction; a blank/unknown local faction falls back to {@link Factions#PLAYER}.
     */
    public static String presenceFactionId(String localPlayerFactionId) {
        if (localPlayerFactionId == null) {
            return Factions.PLAYER;
        }
        String trimmed = localPlayerFactionId.trim();
        return trimmed.isEmpty() ? Factions.PLAYER : trimmed;
    }

    /**
     * Applies always-visible presence styling to the mirror fleet. Safe to call every update; the
     * detected-range modifier is keyed by a stable source so it is not stacked.
     */
    public void apply(CampaignFleetAPI mirrorFleet, String username) {
        if (mirrorFleet == null) {
            return;
        }
        try {
            mirrorFleet.setName(presenceLabel(username));
            // Transponder is driven by the snapshot in CoopFleetMirror, not forced here (Phase 9):
            // visibility comes from the sensor profile + detected-range bonus, which are transponder-
            // independent, so the remote player's real transponder state survives replication.
            mirrorFleet.setSensorProfile(SENSOR_PROFILE);
            mirrorFleet.getDetectedRangeMod().modifyFlat(PRESENCE_SOURCE, DETECTED_RANGE_BONUS);
        } catch (RuntimeException ignored) {
            // Visibility styling is cosmetic; never let it abort the mirror update.
        }
    }
}
