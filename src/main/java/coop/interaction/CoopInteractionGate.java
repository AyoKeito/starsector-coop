package coop.interaction;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-authoritative arbiter for shared campaign interactions (Phase 9, COOP_MP_DESIGN.md 8.5).
 *
 * <p>The host calls {@link #arbitrate(String, String, String)} for every interaction either player
 * tries to open. The first claim on an entity wins and is held until {@link #release(String,
 * String)}; later claims by a different player are rejected with {@code already_claimed_by:<id>}.
 * Because {@code arbitrate} assigns a strictly increasing {@code hostSeq} in call order, "first
 * click wins" is decided by host receive sequence, not by sender wall-clock time.
 *
 * <p>Non-authoritative clients (the guest) do not arbitrate; they mirror the host's decisions by
 * recording {@code INTERACTION_ACCEPT} via {@link #applyAccepted(CoopInteractionClaim)} and
 * dropping released entities via {@link #release(String, String)}. Both sides then ask
 * {@link #blockingClaimFor(String)} whether the local player is currently locked out because the
 * remote player holds an interaction.
 *
 * <p>Phase 9 only blocks and shows status; full dialog replication is deferred.
 */
public final class CoopInteractionGate {
    private final Map<String, CoopInteractionClaim> claimsByEntity = new HashMap<>();
    private long hostSeqCounter;

    /**
     * Host-side arbitration. Assigns the next host receive sequence and accepts the claim unless the
     * entity is already held by a different player.
     */
    public synchronized ClaimResult arbitrate(String entityId, String playerId, String entityName) {
        String normEntity = requireText(entityId, "entityId");
        String normPlayer = requireText(playerId, "playerId");
        long hostSeq = ++hostSeqCounter;
        CoopInteractionClaim existing = claimsByEntity.get(normEntity);
        if (existing != null && !existing.playerId().equals(normPlayer)) {
            return ClaimResult.rejected(existing.playerId(), hostSeq);
        }
        if (existing != null) {
            // Same player re-claiming an entity it already holds keeps the original claim (and its
            // earlier hostSeq); the bumped counter is discarded so re-clicks never reset ordering.
            return ClaimResult.accepted(existing);
        }
        CoopInteractionClaim claim = new CoopInteractionClaim(normEntity, normPlayer, entityName, hostSeq);
        claimsByEntity.put(normEntity, claim);
        return ClaimResult.accepted(claim);
    }

    /** Record a host-accepted claim on a non-authoritative client. Idempotent. */
    public synchronized void applyAccepted(CoopInteractionClaim claim) {
        Objects.requireNonNull(claim, "claim");
        claimsByEntity.put(claim.entityId(), claim);
    }

    /** Release the entity's claim only if it is held by {@code playerId}. Returns true if released. */
    public synchronized boolean release(String entityId, String playerId) {
        String normEntity = requireText(entityId, "entityId");
        String normPlayer = requireText(playerId, "playerId");
        CoopInteractionClaim existing = claimsByEntity.get(normEntity);
        if (existing != null && existing.playerId().equals(normPlayer)) {
            claimsByEntity.remove(normEntity);
            return true;
        }
        return false;
    }

    /** Force-release an entity regardless of holder (e.g. combat start/end on that entity). */
    public synchronized boolean releaseEntity(String entityId) {
        return claimsByEntity.remove(requireText(entityId, "entityId")) != null;
    }

    /** Drop every claim held by a player (e.g. that player disconnected). */
    public synchronized void releaseAll(String playerId) {
        String normPlayer = requireText(playerId, "playerId");
        claimsByEntity.values().removeIf(claim -> claim.playerId().equals(normPlayer));
    }

    public synchronized void clear() {
        claimsByEntity.clear();
    }

    public synchronized String holderOf(String entityId) {
        CoopInteractionClaim claim = claimsByEntity.get(requireText(entityId, "entityId"));
        return claim == null ? null : claim.playerId();
    }

    /**
     * The claim that currently locks {@code localPlayerId} out: the earliest (lowest {@code hostSeq})
     * active claim held by any other player, or null if the local player is free to interact.
     */
    public synchronized CoopInteractionClaim blockingClaimFor(String localPlayerId) {
        String normPlayer = requireText(localPlayerId, "localPlayerId");
        CoopInteractionClaim earliest = null;
        for (CoopInteractionClaim claim : claimsByEntity.values()) {
            if (claim.playerId().equals(normPlayer)) {
                continue;
            }
            if (earliest == null || claim.hostSeq() < earliest.hostSeq()) {
                earliest = claim;
            }
        }
        return earliest;
    }

    public synchronized boolean isBlockedFor(String localPlayerId) {
        return blockingClaimFor(localPlayerId) != null;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }

    /** Outcome of {@link CoopInteractionGate#arbitrate}. */
    public record ClaimResult(boolean accepted, CoopInteractionClaim claim,
                              String rejectedByPlayerId, long hostSeq) {
        public static ClaimResult accepted(CoopInteractionClaim claim) {
            Objects.requireNonNull(claim, "claim");
            return new ClaimResult(true, claim, null, claim.hostSeq());
        }

        public static ClaimResult rejected(String holderPlayerId, long hostSeq) {
            return new ClaimResult(false, null, requireText(holderPlayerId, "holderPlayerId"), hostSeq);
        }

        /** Rejection reason string sent back to a losing claimant. */
        public String rejectReason() {
            return "already_claimed_by:" + rejectedByPlayerId;
        }
    }
}
