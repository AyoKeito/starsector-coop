package coop.campaign;

/**
 * Phase 32: the origin-namespaced fleet-member id a shared inventory listing travels under.
 *
 * <h2>The defect this closes</h2>
 * A stored hull is identified on the wire by its fleet-member id, and that id is the only handle a
 * withdrawal has ({@code MARKET_TXN(SHIP, itemId, +1)} scans the locker for a member with that id).
 * Vanilla mints member ids with {@code Misc.genUID()}, which is a per-sector monotonic counter
 * rendered as short hex — {@code 8f9a}, {@code 8fa4}. Both engines run the same campaign, so both
 * counters mint from the <em>same numeric range</em>, and the guest's counter runs ahead of the
 * host's because every market open rebuilds every shop listing from scratch. Stamping the
 * depositing engine's raw id into the receiving engine's object graph therefore schedules a
 * collision: the id the guest parks in shared storage today is an id the host will mint tomorrow
 * for something else, and a withdrawal then hands back whichever member the roster happens to list
 * first.
 *
 * <h2>The id rule</h2>
 * <ul>
 *   <li><b>On the wire</b> a hull is always named by {@link #wireId(String, String)} =
 *       {@code c_<originPlayerId>_<originMemberId>}. The {@code c_} prefix is not a legal genUID
 *       (genUID output is pure hex with no underscore), so a wire id can never collide with an id
 *       either engine mints on its own; the player-id segment keeps the two engines' id spaces
 *       disjoint from each other.</li>
 *   <li>{@code wireId} is <b>idempotent</b>: an id that already carries the prefix is returned
 *       unchanged. That is what lets a hull deposited by the guest, re-captured by the host and
 *       shipped back to the guest keep one stable name for the whole round trip instead of
 *       gaining a prefix per hop.</li>
 *   <li><b>On the receiving engine</b> a rebuilt member is stamped with the wire id verbatim
 *       ({@code setId}), so it is recognised by plain equality from then on.</li>
 *   <li><b>On the originating engine</b> the real ship keeps its own local id — nothing rewrites a
 *       live object's identity — so a match there is
 *       {@link #matchesLocal(String, String, String)}: equal, or equal once the local id is
 *       namespaced with this engine's own player id.</li>
 * </ul>
 *
 * <p>The player-id segment is sanitised to letters, digits and {@code -} so the composed id stays a
 * plain token in a save file and in a {@code CoopDelimited} field. Real player ids are UUIDs, for
 * which the sanitiser is the identity.
 *
 * <p>Pure string algebra: no engine calls, nothing to fail.
 */
public final class CoopMemberIds {

    /** Marks an id as coop-minted. Not a legal {@code Misc.genUID()} output (hex only). */
    public static final String PREFIX = "c_";

    private CoopMemberIds() {
    }

    /**
     * The name this engine's member travels under.
     *
     * @param localPlayerId  this engine's own player id (the <em>origin</em> of the deposit).
     * @param localMemberId  the member id as this engine's object graph knows it.
     * @return {@code localMemberId} unchanged when it is blank or already namespaced, else
     *         {@code c_<playerId>_<memberId>}.
     */
    public static String wireId(String localPlayerId, String localMemberId) {
        String memberId = localMemberId == null ? "" : localMemberId.trim();
        if (memberId.isEmpty() || isCoopId(memberId)) {
            return memberId;
        }
        return PREFIX + sanitize(localPlayerId) + "_" + memberId;
    }

    /**
     * Does a wire id name this engine's member?
     *
     * <p>Two cases, and both are needed: the receiving engine rebuilt the member <em>with</em> the
     * wire id (plain equality), while the originating engine still holds the real ship under its
     * own local id (namespace it and compare).
     */
    public static boolean matchesLocal(String wireId, String localMemberId, String localPlayerId) {
        if (wireId == null || localMemberId == null) {
            return false;
        }
        String local = localMemberId.trim();
        if (local.isEmpty()) {
            return false;
        }
        return wireId.equals(local) || wireId.equals(wireId(localPlayerId, local));
    }

    /** True for an id this class minted, i.e. one that no {@code genUID} can produce. */
    public static boolean isCoopId(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    private static String sanitize(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return "anon";
        }
        String trimmed = playerId.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '-' ? c : '-');
        }
        return out.toString();
    }
}
