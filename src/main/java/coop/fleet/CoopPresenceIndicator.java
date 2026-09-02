package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * Makes the mirrored remote-player fleet an always-visible presence on the campaign map, rendered in
 * the local player's own faction color and labeled with the remote username
 * ({@code COOP_MP_DESIGN.md} section "Presence indicator").
 *
 * <h2>Visibility mechanism (rewritten 2026-08-19, Phase 14b)</h2>
 * <p>Until Phase 14b this forced {@code setSensorProfile(100000)} plus a {@code +100000} flat on the
 * mirror's {@code detectedRangeMod}. That worked for the map, and it was <b>the single biggest blocker
 * to stealth in the whole mod</b>: those are the two target-side terms of
 * {@code BaseCampaignEntity.getMaxSensorRangeToDetect} ({@code nb/.../BaseCampaignEntity.java:1130}),
 * which every hostile NPC on the host reads through {@code getVisibilityLevelTo} before it will even
 * consider hunting a fleet ({@code TacticalModule.java:271-273}). A guest running dark was detectable
 * from across the system by every pirate in it; Go Dark, the transponder and the burn abilities could
 * not matter, because the fabricated numbers dwarfed them.
 *
 * <p>The replacement is the engine's own player-only detectability lever:
 * {@code fleet.getStats().getDynamic().getStat(Stats.DETECTED_BY_PLAYER_RANGE_MULT)}. The detection
 * formula applies it <em>only when the observer is the player fleet</em>
 * ({@code BaseCampaignEntity.java:1142-1145}) — vanilla itself uses it this way in
 * {@code ThreatFleetBehaviorScript}. So the local player keeps seeing their partner from far away and
 * identified (the identification bands are fractions of the same boosted range: faction details inside
 * {@code max(0.1 x range, 50)}), while every NPC on this client computes the mirror's detection range
 * from the replicated, vanilla-exact sensor identity {@link CoopSensorSync} pins on it.
 *
 * <p>This is still an approximation of "always visible" rather than a hard override — no such override
 * exists in the 0.98a public API — but it is now an approximation that costs the simulation nothing.
 *
 * <p><b>Transponder is intentionally NOT forced here (Phase 9 decoupling).</b> Forcing the transponder
 * on would overwrite the replicated real state, hiding the remote player's running-dark from the other
 * client's host-side simulation (so patrols could not react to it). The mirror's transponder is driven
 * purely by the snapshot in {@link CoopFleetMirror}.
 *
 * <p>Coloring: the mirror fleet is assigned the local player's own faction so it renders in that
 * faction's color and is non-hostile to the local player. The username is set as the fleet name so
 * the campaign-map label reads as the remote player.
 */
public final class CoopPresenceIndicator {
    static final String PRESENCE_SOURCE = "coop_presence";

    /**
     * How much further than vanilla the <em>local player</em> detects their partner's mirror. 50x turns
     * a typical ~1,500 su detection range into far more than the engine's {@code sensorRangeMax} cap
     * (5,000 su in-system, 2,000 in hyperspace, {@code BaseCampaignEntity.java:1153-1156}), so the
     * mirror is effectively always visible wherever the cap allows, and identified out to
     * {@code 0.1 x} that — i.e. everywhere it is visible at all.
     */
    static final float PLAYER_DETECTION_MULT = 50f;

    /** Used when no username is known; still a noun phrase, so the faction article reads correctly. */
    private static final String DEFAULT_LABEL = "coop partner";

    /**
     * Campaign-map label for the mirror fleet.
     *
     * <p>The mirror belongs to the local player's faction, and vanilla prefixes every player-faction
     * fleet with that faction's {@code displayNameWithArticle} — "Your" out of
     * {@code data/world/factions/player.faction}, or "The Foo Republic" once colonies have named the
     * faction. So the label has to be a noun phrase that reads after either prefix: the bare username
     * gave "Your Alice". Prefixing the name with "partner" makes it "Your partner Alice" /
     * "The Foo Republic partner Alice".
     */
    public static String presenceLabel(String username) {
        if (username == null) {
            return DEFAULT_LABEL;
        }
        String trimmed = username.trim();
        return trimmed.isEmpty() ? DEFAULT_LABEL : "partner " + trimmed;
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
     * modifier is keyed by a stable source so it is not stacked, and {@code MutableStat.modifyMult}
     * is a no-op when the value has not changed.
     */
    public void apply(CampaignFleetAPI mirrorFleet, String username) {
        if (mirrorFleet == null) {
            return;
        }
        try {
            mirrorFleet.setName(presenceLabel(username));
            MutableStat detectedByPlayer =
                    mirrorFleet.getStats().getDynamic().getStat(Stats.DETECTED_BY_PLAYER_RANGE_MULT);
            if (detectedByPlayer != null) {
                detectedByPlayer.modifyMult(PRESENCE_SOURCE, PLAYER_DETECTION_MULT, "Coop partner");
            }
        } catch (RuntimeException ignored) {
            // Visibility styling is cosmetic; never let it abort the mirror update.
        }
    }
}
