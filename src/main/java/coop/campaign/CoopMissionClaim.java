package coop.campaign;

import static coop.util.CoopText.requireText;

/**
 * A first-come claim on a shared mission/bar/contact/bounty entry (Phase 12).
 *
 * <p>Mirrors {@link coop.interaction.CoopInteractionClaim}: the host assigns a strictly increasing
 * {@code hostSeq} in receive order, so "first to accept wins" is decided by host receive sequence,
 * not sender wall-clock. The claim records which player now owns the mission's reward.
 */
public record CoopMissionClaim(String missionId, String acceptedByPlayerId, long hostSeq) {
    public CoopMissionClaim {
        missionId = requireText(missionId, "missionId");
        acceptedByPlayerId = requireText(acceptedByPlayerId, "acceptedByPlayerId");
    }

}
