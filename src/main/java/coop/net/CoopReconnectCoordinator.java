package coop.net;

import java.util.Objects;

/**
 * Phase 20.2 in-session reconnect grace: the state machine that turns a dead <em>socket</em> into a
 * held session rather than an ended one.
 *
 * <p><b>Why this exists.</b> WAN blips are routine — a NAT rebind, a Wi-Fi roam, a two-second carrier
 * hiccup — and before this milestone every one of them ran the full teardown: the host freed the guest
 * slot, both sides dropped the seed lock, and the only way back was a fresh lobby, handshake and
 * seed-lock round on a new socket. The transport was already resilient (the guest has retried every
 * 500 ms forever since Phase 12b); it was the <em>session</em> layer that gave up. The grace window
 * closes that gap: for {@code coop.reconnectGraceSeconds} the session record stays exactly as it was,
 * the shared clock is held, and a peer whose process is still alive can pick the same session back up.
 *
 * <p><b>What it deliberately does not do.</b> Relaunch-from-save rejoin is still out of scope. The
 * grace window only serves a peer whose campaign is still loaded and whose only casualty was the link;
 * a guest that quit to the menu has no session left to resume, and its next connection is an ordinary
 * lobby attempt after the grace expires.
 *
 * <p><b>Why a stranger cannot end the wait early.</b> {@link #evaluateResumeRequest} matches both the
 * session id and the remote player id before it will accept, and a mismatch is answered with a reject
 * that leaves the wait running. The host keeps the transport's session token cleared for the whole
 * window (so no stale datagram from the dead connection applies) and re-sets it only on an accepted
 * resume. A {@code LOBBY_HELLO} arriving mid-grace is likewise rejected rather than served: until the
 * window closes, the slot belongs to the peer that lost it.
 *
 * <p>Pure logic on a caller-supplied wall clock: no sockets, no engine, no dialogs. Everything the
 * grace has to <em>do</em> — hold the pause, open a dialog, rebroadcast the world, tear the session
 * down — is a {@link Listener} callback the pump implements, so the whole machine is testable on a
 * fake clock and works unchanged when there is no dialog at all.
 */
public final class CoopReconnectCoordinator {

    /** Where the session is between "the socket died" and "we know how that ended". */
    public enum State {
        /** No grace window; the normal case, and what every terminal transition returns to. */
        IDLE,
        /** Host: the guest's socket died mid-session and the world is held awaiting its return. */
        HOST_WAIT,
        /** Guest: the host's socket died mid-session and the 500 ms retry is running underneath. */
        GUEST_RECONNECTING
    }

    /** The host's verdict on one {@code SESSION_RESUME_REQUEST}. */
    public enum ResumeDecision {
        /** Same session, same player: resume it. */
        ACCEPT,
        /** There is no grace window open, so there is nothing to resume. */
        REJECT_NOT_WAITING,
        /** The request names a different session. */
        REJECT_SESSION_MISMATCH,
        /** The request names a different player than the one that dropped. */
        REJECT_PLAYER_MISMATCH;

        public boolean accepted() {
            return this == ACCEPT;
        }
    }

    /**
     * What the grace window makes the rest of the mod do. Every method is called exactly once per
     * transition, from the campaign thread, inside the pump's frame.
     */
    public interface Listener {
        /** A grace window just opened: hold the clock, show the dialog, tell the player. */
        void onGraceStarted(State state, long graceMillis);

        /**
         * The peer came back on the same session: release the hold, close the dialog, and force the
         * full session-start rebroadcast so the returning peer's world is re-seeded.
         */
        void onResumed(State previous);

        /** The window closed without a resume: run the ordinary full session teardown. */
        void onEnded(State previous, String reason);
    }

    /** Terminal reason: the window ran out. */
    public static final String REASON_GRACE_EXPIRED = "reconnect grace expired";
    /** Terminal reason: the local player pressed the dialog's end/give-up option. */
    public static final String REASON_ENDED_BY_PLAYER = "ended by player";
    /** Terminal reason (guest): the host answered the resume request with a reject. */
    public static final String REASON_HOST_REJECTED = "host rejected the resume";
    /** Reject text a {@code LOBBY_HELLO} gets while a window is open. */
    public static final String LOBBY_REJECT_IN_GRACE = "session in reconnect grace";

    private final long graceMillis;
    private final Listener listener;

    private State state = State.IDLE;
    private String sessionId;
    private String peerPlayerId;
    private long graceEndsAtMillis;
    private boolean resumeRequestSent;

    /**
     * @param graceMillis how long a window stays open; clamped at zero, which makes every drop end the
     *                    session immediately (the pre-20.2 behaviour, reachable by configuration)
     * @param listener    the side effects; must not be null (use a no-op implementation in tests that
     *                    do not care)
     */
    public CoopReconnectCoordinator(long graceMillis, Listener listener) {
        this.graceMillis = Math.max(0L, graceMillis);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public State state() {
        return state;
    }

    /** True while a grace window is open in either role. */
    public boolean active() {
        return state != State.IDLE;
    }

    public boolean hostWaiting() {
        return state == State.HOST_WAIT;
    }

    public boolean guestReconnecting() {
        return state == State.GUEST_RECONNECTING;
    }

    /** The session the window is holding, or null when idle. */
    public String sessionId() {
        return sessionId;
    }

    /** Configured window length in milliseconds. */
    public long graceMillis() {
        return graceMillis;
    }

    /** Milliseconds left in the window, floored at zero; zero when idle. */
    public long remainingMillis(long nowMillis) {
        if (state == State.IDLE) {
            return 0L;
        }
        return Math.max(0L, graceEndsAtMillis - nowMillis);
    }

    /** Whole seconds left, rounded up so a live countdown never shows 0 while the window is open. */
    public int remainingSeconds(long nowMillis) {
        long remaining = remainingMillis(nowMillis);
        return (int) ((remaining + 999L) / 1000L);
    }

    /**
     * Host: the guest's socket died while the session was live. Keeps the session identity so the
     * returning guest can be matched against it.
     *
     * @param sessionId    the live session id, which must survive the drop
     * @param peerPlayerId the dropped guest's player id
     */
    public void beginHostWait(String sessionId, String peerPlayerId, long nowMillis) {
        begin(State.HOST_WAIT, sessionId, peerPlayerId, nowMillis);
    }

    /**
     * Guest: the host's socket died while the session was live. The transport's own 500 ms retry is
     * what actually reconnects; this only remembers what to ask for when it does.
     *
     * @param sessionId    the live session id
     * @param peerPlayerId this guest's OWN player id — it is what the host matches the request against
     */
    public void beginGuestReconnect(String sessionId, String peerPlayerId, long nowMillis) {
        begin(State.GUEST_RECONNECTING, sessionId, peerPlayerId, nowMillis);
    }

    private void begin(State target, String sessionId, String peerPlayerId, long nowMillis) {
        if (state != State.IDLE) {
            return;
        }
        if (sessionId == null || sessionId.isEmpty() || peerPlayerId == null || peerPlayerId.isEmpty()) {
            // Nothing to match a resume against, so there is nothing a grace window could protect.
            return;
        }
        this.state = target;
        this.sessionId = sessionId;
        this.peerPlayerId = peerPlayerId;
        this.graceEndsAtMillis = nowMillis + graceMillis;
        this.resumeRequestSent = false;
        listener.onGraceStarted(target, graceMillis);
    }

    /**
     * Frame tick. Fires {@link Listener#onEnded} once when the window runs out; a no-op when idle.
     *
     * @return true when this tick ended the window
     */
    public boolean tick(long nowMillis) {
        if (state == State.IDLE || nowMillis < graceEndsAtMillis) {
            return false;
        }
        end(REASON_GRACE_EXPIRED);
        return true;
    }

    /**
     * Guest bookkeeping: the socket went down again (or has not come up yet), so the next connection
     * owes a fresh {@code SESSION_RESUME_REQUEST}. Idempotent, called every frame the link is down.
     */
    public void noteChannelDown() {
        resumeRequestSent = false;
    }

    /** True on the frame a reconnected guest still owes its resume request. */
    public boolean resumeRequestDue() {
        return state == State.GUEST_RECONNECTING && !resumeRequestSent;
    }

    /** Marks the request as sent so the guest asks once per socket, not once per frame. */
    public void markResumeRequestSent() {
        resumeRequestSent = true;
    }

    /**
     * Host: does this {@code SESSION_RESUME_REQUEST} belong to the session being held? Pure — the
     * caller sends the answer and then calls {@link #resume} on acceptance, so a send failure cannot
     * leave the machine claiming a resume that never reached the guest.
     */
    public ResumeDecision evaluateResumeRequest(String requestSessionId, String requestPlayerId) {
        if (state != State.HOST_WAIT) {
            return ResumeDecision.REJECT_NOT_WAITING;
        }
        if (requestSessionId == null || !requestSessionId.equals(sessionId)) {
            return ResumeDecision.REJECT_SESSION_MISMATCH;
        }
        if (requestPlayerId == null || !requestPlayerId.equals(peerPlayerId)) {
            return ResumeDecision.REJECT_PLAYER_MISMATCH;
        }
        return ResumeDecision.ACCEPT;
    }

    /** Human-readable reject text for the wire, so the peer's log says why it was turned away. */
    public static String rejectReason(ResumeDecision decision) {
        return switch (decision) {
            case ACCEPT -> "";
            case REJECT_NOT_WAITING -> "no reconnect grace window is open";
            case REJECT_SESSION_MISMATCH -> "session id does not match the held session";
            case REJECT_PLAYER_MISMATCH -> "player id does not match the disconnected partner";
        };
    }

    /**
     * The link is back on the same session: closes the window and fires
     * {@link Listener#onResumed}. Both roles call it — the host after sending
     * {@code SESSION_RESUME_ACCEPT}, the guest on receiving one.
     *
     * @return true when a window was actually open to resume
     */
    public boolean resume() {
        if (state == State.IDLE) {
            return false;
        }
        State previous = state;
        clear();
        listener.onResumed(previous);
        return true;
    }

    /**
     * Closes the window without a resume: the dialog's end/give-up option, a host reject, or any other
     * decision that the session is over. Fires {@link Listener#onEnded}.
     *
     * @return true when a window was actually open to end
     */
    public boolean end(String reason) {
        if (state == State.IDLE) {
            return false;
        }
        State previous = state;
        clear();
        listener.onEnded(previous, reason == null ? "" : reason);
        return true;
    }

    /**
     * Drops the window with no callback at all. For the one case where the session is being torn down
     * by something else entirely (the transport went to {@code NONE}, the game unloaded) and running
     * the teardown listener would be a second, redundant reset.
     */
    public void abandon() {
        clear();
    }

    private void clear() {
        state = State.IDLE;
        sessionId = null;
        peerPlayerId = null;
        graceEndsAtMillis = 0L;
        resumeRequestSent = false;
    }
}
