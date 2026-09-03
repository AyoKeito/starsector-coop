package coop.session;

/**
 * The five coarse, named steps a joining player walks through, in order. Phase 21's lobby renders
 * them as a step counter ("Syncing 3/5"), never as a percent bar: a bar pinned at 100% reads as hung,
 * and the machine-level detail belongs in the log rather than on the roster row.
 *
 * <p><b>Where each step comes from</b> (the concrete signals, so the mapping is auditable):
 * <ul>
 *   <li>{@link #LINK_ESTABLISHED} — the socket is up and the host answered {@code LOBBY_ACCEPT}
 *   ({@code CoopLobbyState.GUEST_CONNECTED} / {@code HOST_CONNECTED}).</li>
 *   <li>{@link #VERSIONS_MATCHED} — {@code HANDSHAKE_RESULT} accepted, i.e.
 *   {@link CoopSessionState#handshakeValidated()}.</li>
 *   <li>{@link #SEED_LOCKED} — {@link CoopSessionState#seedLong()} is set; campaign id, seed string
 *   and sector fingerprint all matched.</li>
 *   <li>{@link #SNAPSHOT_APPLIED} — the guest holds the host's world clock: it has received at least
 *   one {@code TIME_SNAPSHOT} on the live session. That message is the only host&rarr;guest world
 *   stream that is unconditional and periodic (5 Hz), so it can never fail to arrive on a healthy
 *   link; {@code BASE_SET} and {@code NPC_FLEET_SET} are change-driven and may legitimately never be
 *   sent, which would wedge the ready gate forever. It sits behind the full handshake + seed lock, so
 *   by the time it lands the guest is running the host's campaign.</li>
 *   <li>{@link #READY} — the player pressed Ready. The only step a human, not the protocol, causes.</li>
 * </ul>
 *
 * <p>Ordinal order is the progress order; {@link #atLeast(CoopJoinPhase)} is the comparison the pump
 * and roster use so nothing has to know the ordinals.
 */
public enum CoopJoinPhase {
    LINK_ESTABLISHED("Connecting"),
    VERSIONS_MATCHED("Checking versions"),
    SEED_LOCKED("Locking the sector"),
    SNAPSHOT_APPLIED("Syncing the world"),
    READY("Ready");

    /** How many steps the counter shows; the denominator of "Syncing 3/5". */
    public static final int STEP_COUNT = 5;

    private final String displayWord;

    CoopJoinPhase(String displayWord) {
        this.displayWord = displayWord;
    }

    /** Short human wording for this step, used by the connecting dialog's phase list. */
    public String displayWord() {
        return displayWord;
    }

    /** 1-based position in the sequence; the numerator of "Syncing 3/5". */
    public int stepIndex() {
        return ordinal() + 1;
    }

    /** Always {@link #STEP_COUNT}; a method so callers read {@code phase.stepCount()} symmetrically. */
    public int stepCount() {
        return STEP_COUNT;
    }

    /** True when this step is at or past {@code other}. */
    public boolean atLeast(CoopJoinPhase other) {
        return other == null || ordinal() >= other.ordinal();
    }

    /** The further along of the two; nulls are treated as "no progress at all". */
    public static CoopJoinPhase max(CoopJoinPhase a, CoopJoinPhase b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * Wire-tolerant parse: an unknown name (a newer peer with a step this build has never heard of)
     * yields {@code fallback} rather than throwing, so one unexpected token cannot take the lobby down.
     */
    public static CoopJoinPhase parse(String name, CoopJoinPhase fallback) {
        if (name == null) {
            return fallback;
        }
        for (CoopJoinPhase phase : values()) {
            if (phase.name().equals(name)) {
                return phase;
            }
        }
        return fallback;
    }
}
