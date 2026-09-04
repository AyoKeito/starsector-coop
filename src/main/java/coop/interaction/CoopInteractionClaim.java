package coop.interaction;

import static coop.util.CoopText.requireText;

/**
 * One held interaction on a shared campaign entity (Phase 9, COOP_MP_DESIGN.md 8.5).
 *
 * <p>A claim records who is interacting ({@code playerId}) with which world entity
 * ({@code entityId}), the human-readable entity label for the "Remote player is interacting"
 * status text ({@code entityName}), and the host receive sequence ({@code hostSeq}) the host
 * assigned when it arbitrated the claim. {@code hostSeq} is the authoritative first-come ordering
 * key: the lowest sequence for a given entity wins.
 */
public record CoopInteractionClaim(String entityId, String playerId, String entityName, long hostSeq) {
    public CoopInteractionClaim {
        entityId = requireText(entityId, "entityId");
        playerId = requireText(playerId, "playerId");
        entityName = entityName == null ? "" : entityName;
        if (hostSeq < 0) {
            throw new IllegalArgumentException("hostSeq must be >= 0");
        }
    }

}
