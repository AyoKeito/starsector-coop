package coop.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static coop.util.CoopText.requireText;

/**
 * Host-authoritative shared mission/bar/contact/bounty pool and its first-come claims (Phase 12).
 *
 * <p>Two responsibilities, both host-owned:
 * <ul>
 *   <li><b>Pool:</b> when a shared board opens, the host snapshots the visible entries and
 *   broadcasts them ({@code MISSION_POOL_SNAPSHOT}). The guest renders/filters from this host pool
 *   ({@link #applySnapshot(List)}) instead of generating an independent pool, so both players see
 *   the same offers. Portside bar <em>events</em> ride the same pool with {@link SourceType#BAR};
 *   Phase 12c wires that path end to end ({@link CoopBarPoolCapture} on the host,
 *   {@link CoopBarPoolInjector} + {@link CoopBarGenerationSuppressor} on the guest).</li>
 *   <li><b>Claims:</b> the host accepts the first claim for an unclaimed {@code missionId}, records
 *   the accepting player, and rejects later claims with {@code already_claimed_by:<playerId>}
 *   ({@link #arbitrate(String, String)}). Mission rewards go to the accepting player only; they are
 *   never split (combat spoils are the separate Phase 15 solo-fighter path).</li>
 * </ul>
 *
 * <p>The same-market bar presence text (showing that the other player is also at a bar without
 * granting them the same claim) is surfaced by the caller via {@link #claimHolder(String)}.
 */
public final class CoopMissionBoardSync {

    public enum SourceType {
        BAR,
        CONTACT,
        BOUNTY,
        MISSION_BOARD
    }

    /**
     * A single shared offer. {@code acceptedByPlayerId} is empty until someone claims it.
     *
     * <p>Two fields exist for the Phase 12c bar pool and are blank/zero for every other source type:
     * <ul>
     *   <li>{@code contentSeed} — the one {@code long} a concrete {@link
     *   com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent} regenerates all of its content
     *   from (see {@link CoopBarSync}). Bar offers are code objects and cannot be serialized, so the
     *   wire carries the seed and the guest reconstructs the object around it.</li>
     *   <li>{@code eventKind} — the host-side concrete class simple name, the discriminator the guest
     *   uses to pick a construction path ({@code HubMissionBarEventWrapper} takes the spec-id
     *   constructor, anything else goes through a registered creator).</li>
     * </ul>
     * For {@link SourceType#BAR} entries {@code marketId} carries the {@code shownAt} pin (blank when
     * the offer is not pinned to a market) and {@code expiresAtDay} carries the remaining active days,
     * not an absolute day — the pool is global, so there is no owning market and no absolute deadline
     * the guest could act on.
     */
    public record Entry(String marketId, SourceType sourceType, String missionId, String title,
                        String giverId, String rewardSummary, String acceptedByPlayerId, long expiresAtDay,
                        long contentSeed, String eventKind) {
        public Entry {
            sourceType = Objects.requireNonNull(sourceType, "sourceType");
            missionId = requireText(missionId, "missionId");
            marketId = CoopDelimited.normalize(marketId);
            title = CoopDelimited.normalize(title);
            giverId = CoopDelimited.normalize(giverId);
            rewardSummary = CoopDelimited.normalize(rewardSummary);
            acceptedByPlayerId = CoopDelimited.normalize(acceptedByPlayerId);
            eventKind = CoopDelimited.normalize(eventKind);
        }

        /** A captured portside bar offer: identity + the seed its content regenerates from. */
        public static Entry barOffer(String barEventId, String eventKind, long contentSeed,
                                     String shownAtMarketId, long daysRemaining) {
            return new Entry(shownAtMarketId, SourceType.BAR, barEventId, "", "", "", "",
                    daysRemaining, contentSeed, eventKind);
        }

        public boolean isClaimed() {
            return !acceptedByPlayerId.isEmpty();
        }

        public Entry withAcceptedBy(String playerId) {
            return new Entry(marketId, sourceType, missionId, title, giverId, rewardSummary,
                    CoopDelimited.normalize(playerId), expiresAtDay, contentSeed, eventKind);
        }
    }

    private final Map<String, Entry> poolByMissionId = new LinkedHashMap<>();
    private final Map<String, CoopMissionClaim> claimsByMissionId = new LinkedHashMap<>();
    private long hostSeqCounter;

    // ---- Pool (host snapshots; guest renders) -------------------------------------------------

    /** Replace the entire local pool with the host's snapshot (idempotent render source). */
    public synchronized void applySnapshot(List<Entry> entries) {
        poolByMissionId.clear();
        if (entries != null) {
            for (Entry entry : entries) {
                poolByMissionId.put(entry.missionId(), entry);
            }
        }
        // Purge claims for offers that vanished from the pool. The host's pool is canonical, so a
        // claim on a mission no longer in it is dead bookkeeping; leaving it meant orphan claims
        // accumulated for the whole session. Host and guest run the same purge.
        claimsByMissionId.keySet().removeIf(missionId -> !poolByMissionId.containsKey(missionId));
        // Re-stamp the surviving claims onto the fresh entries. Captured entries always arrive with
        // acceptedByPlayerId blank (the host reads identity and seed off the engine, not the claim
        // ledger), so without this every snapshot un-claimed everything it re-sent: visibleEntriesFor
        // let a taken offer through again and the guest re-injected a mission its partner had already
        // accepted. The claim ledger is the authority, not the entry field.
        for (CoopMissionClaim claim : claimsByMissionId.values()) {
            markEntryClaimed(claim.missionId(), claim.acceptedByPlayerId());
        }
    }

    public synchronized List<Entry> pool() {
        return new ArrayList<>(poolByMissionId.values());
    }

    public synchronized Entry entry(String missionId) {
        return poolByMissionId.get(requireText(missionId, "missionId"));
    }

    /**
     * Entries this player may still accept: unclaimed entries plus entries this player already holds.
     * Entries claimed by the other player are filtered out so the guest never re-takes a taken offer.
     */
    public synchronized List<Entry> visibleEntriesFor(String playerId) {
        String norm = requireText(playerId, "playerId");
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : poolByMissionId.values()) {
            if (!entry.isClaimed() || entry.acceptedByPlayerId().equals(norm)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    // ---- Claims (host-authoritative first-come arbitration) -----------------------------------

    /**
     * Host-side arbitration. Assigns the next host receive sequence and accepts the claim unless the
     * mission is already held by a different player.
     */
    public synchronized ClaimResult arbitrate(String missionId, String playerId) {
        String normMission = requireText(missionId, "missionId");
        String normPlayer = requireText(playerId, "playerId");
        long hostSeq = ++hostSeqCounter;
        CoopMissionClaim existing = claimsByMissionId.get(normMission);
        if (existing != null && !existing.acceptedByPlayerId().equals(normPlayer)) {
            return ClaimResult.rejected(existing.acceptedByPlayerId(), hostSeq);
        }
        if (existing != null) {
            return ClaimResult.accepted(existing);
        }
        CoopMissionClaim claim = new CoopMissionClaim(normMission, normPlayer, hostSeq);
        claimsByMissionId.put(normMission, claim);
        markEntryClaimed(normMission, normPlayer);
        return ClaimResult.accepted(claim);
    }

    /** Record a host-accepted claim on the guest. Idempotent. */
    public synchronized void applyAccepted(CoopMissionClaim claim) {
        Objects.requireNonNull(claim, "claim");
        claimsByMissionId.put(claim.missionId(), claim);
        markEntryClaimed(claim.missionId(), claim.acceptedByPlayerId());
    }

    public synchronized String claimHolder(String missionId) {
        CoopMissionClaim claim = claimsByMissionId.get(requireText(missionId, "missionId"));
        return claim == null ? null : claim.acceptedByPlayerId();
    }

    public synchronized boolean isClaimed(String missionId) {
        return claimsByMissionId.containsKey(requireText(missionId, "missionId"));
    }

    public synchronized void clear() {
        poolByMissionId.clear();
        claimsByMissionId.clear();
    }

    private void markEntryClaimed(String missionId, String playerId) {
        Entry entry = poolByMissionId.get(missionId);
        if (entry != null) {
            poolByMissionId.put(missionId, entry.withAcceptedBy(playerId));
        }
    }

    // ---- Snapshot encoding (single delimited string carried over TCP) -------------------------

    /** Fields per encoded entry line. Bumped from 8 to 10 by Phase 12c (contentSeed, eventKind). */
    static final int ENTRY_FIELD_COUNT = 10;

    public static String encodePool(List<Entry> entries) {
        StringBuilder out = new StringBuilder(64 + (entries == null ? 0 : entries.size()) * 48);
        out.append(entries == null ? 0 : entries.size());
        if (entries != null) {
            for (Entry entry : entries) {
                out.append('\n')
                        .append(CoopDelimited.field(entry.marketId()))
                        .append('|').append(entry.sourceType().name())
                        .append('|').append(CoopDelimited.field(entry.missionId()))
                        .append('|').append(CoopDelimited.field(entry.title()))
                        .append('|').append(CoopDelimited.field(entry.giverId()))
                        .append('|').append(CoopDelimited.field(entry.rewardSummary()))
                        .append('|').append(CoopDelimited.field(entry.acceptedByPlayerId()))
                        .append('|').append(entry.expiresAtDay())
                        .append('|').append(entry.contentSeed())
                        .append('|').append(CoopDelimited.field(entry.eventKind()));
            }
        }
        return out.toString();
    }

    public static List<Entry> decodePool(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        int count = Integer.parseInt(lines[0].trim());
        if (lines.length - 1 < count) {
            throw new IllegalArgumentException("Declared " + count + " entries but only "
                    + (lines.length - 1) + " entry lines present");
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            List<String> f = CoopDelimited.split(lines[i + 1]);
            // Reject, never shift: a line from a peer running the pre-12c 8-field codec must fail
            // loudly here rather than silently decode with fields slid into the wrong slots. The
            // handshake manifest already refuses mismatched builds; this is the second line.
            if (f.size() != ENTRY_FIELD_COUNT) {
                throw new IllegalArgumentException("Expected " + ENTRY_FIELD_COUNT
                        + " mission entry fields, got " + f.size());
            }
            entries.add(new Entry(f.get(0), SourceType.valueOf(f.get(1)), f.get(2), f.get(3),
                    f.get(4), f.get(5), f.get(6), Long.parseLong(f.get(7).trim()),
                    Long.parseLong(f.get(8).trim()), f.get(9)));
        }
        return entries;
    }

    /** Outcome of {@link #arbitrate(String, String)}. */
    public record ClaimResult(boolean accepted, CoopMissionClaim claim,
                              String rejectedByPlayerId, long hostSeq) {
        public static ClaimResult accepted(CoopMissionClaim claim) {
            Objects.requireNonNull(claim, "claim");
            return new ClaimResult(true, claim, null, claim.hostSeq());
        }

        public static ClaimResult rejected(String holderPlayerId, long hostSeq) {
            return new ClaimResult(false, null, requireText(holderPlayerId, "holderPlayerId"), hostSeq);
        }

        public String rejectReason() {
            return "already_claimed_by:" + rejectedByPlayerId;
        }
    }
}
