package coop.time;

/**
 * Phase 11: shared pause coordinator. A small, foundational extension of the Phase 7 time lock that
 * lets either player assert a <b>shared</b> pause so both clocks stop together and never desync.
 *
 * <p><b>Authority model.</b> The host is authoritative. It tracks the intents and computes the
 * effective pause as their OR:
 * <pre>effectivePaused = hostPauseIntent || guestKeyPauseIntent || guestScreenPauseIntent || eitherInCombat</pre>
 * The host applies {@code effectivePaused} to its own {@code Sector} clock; the value then rides the
 * existing Phase 7 {@code TIME_SNAPSHOT} to the guest, whose clock always follows the host snapshot.
 * The guest never calls {@code setPaused} locally to drive divergence — it only reports its
 * <i>intent</i> to the host via {@code PAUSE_INTENT} and waits for the host's snapshot.
 *
 * <p><b>Two guest intents, different override rules (v1 decision).</b> The guest has two distinct
 * pause sources and the host treats them differently:
 * <ul>
 *   <li><b>Key pause</b> ({@code guestKeyPauseIntent}) — the guest tapped the pause key. The host
 *   <i>may</i> override/resume past this (see {@link #onHostPauseKey()}), because it is a casual
 *   manual pause.</li>
 *   <li><b>Screen pause</b> ({@code guestScreenPauseIntent}) — the guest opened a vanilla-blocking
 *   screen (map/fleet/character/refit/cargo/intel/menu/dialog). The host <i>cannot</i> override this:
 *   it exists so the guest can read/plan without the host's world running on. It clears only when the
 *   guest closes the screen.</li>
 * </ul>
 *
 * <p><b>No guest-side sticky key bit (avoids desync).</b> Because the host may force-clear the key
 * pause, the guest does not keep its own copy of it: it sends the key intent on <i>every</i> press
 * (resolved against the observed pause state), so a host override can never leave the two out of
 * sync. The screen intent is a level the guest sends on open/close. The pause KEY is resolved against
 * {@link #observedPaused() observedPaused} so a tap always means "give me the opposite of what I see"
 * rather than a blind toggle — without it the guest could queue a phantom pause while the host already
 * holds the world paused, surfacing when the host later releases.
 *
 * <p>Phase 14 feeds combat-start/-end into {@link #setEitherInCombat(boolean)}; this phase does not
 * wire the combat trigger itself.
 *
 * <p>All methods are synchronized: the guest pause-key press arrives from the campaign input listener
 * while the rest is driven from the campaign pump; the two run on the campaign thread but may
 * interleave around input dispatch.
 */
public final class CoopSharedPauseCoordinator {
    // ---- Host authority state (also the guest's view of its own reported intents) ----
    private boolean hostPauseIntent;
    private boolean guestKeyPauseIntent;     // guest manual pause — host MAY override
    private boolean guestScreenPauseIntent;  // guest screen-open pause — host may NOT override
    private boolean eitherInCombat;
    private boolean reconnectHold;
    private long appliedGuestSeq = Long.MIN_VALUE;

    // ---- Guest local outgoing state ----
    private boolean guestScreenLevel;        // current local screen-open state (change-detected)
    private boolean pendingGuestKeyPress;    // a pause key press waiting to be forwarded
    private long localSeqCounter;

    // ---- Shared: the pause state this client currently observes on its own clock ----
    private boolean observedPaused;

    /* ===================== Host side ===================== */

    /**
     * Record the host's own pause intent. The pump uses this for vanilla <i>auto</i>-pause edges
     * (combat/messages, which reach the clock without the pause key); the pause key itself routes
     * through {@link #onHostPauseKey()}.
     */
    public synchronized void setHostPauseIntent(boolean paused) {
        hostPauseIntent = paused;
    }

    /**
     * Host pressed the pause key. Resolved against the observed pause state:
     * <ul>
     *   <li>running &rarr; assert the host pause;</li>
     *   <li>paused &rarr; resume: clear the host intent <i>and</i> force-clear the guest's manual
     *   (key) pause. The guest's screen pause is deliberately left alone, so the host can resume a
     *   guest's casual pause but cannot interrupt a guest reading a menu.</li>
     * </ul>
     */
    public synchronized void onHostPauseKey() {
        if (observedPaused) {
            hostPauseIntent = false;
            guestKeyPauseIntent = false;
        } else {
            hostPauseIntent = true;
        }
    }

    public synchronized boolean hostPauseIntent() {
        return hostPauseIntent;
    }

    /**
     * Apply a guest manual (key) pause intent with last-writer-wins debounce: an intent whose
     * {@code seq} is not strictly greater than the last applied seq is stale and ignored. Returns true
     * if applied.
     *
     * <p><b>Phase 20 M6 latency audit — why this cannot strand the host's clock.</b> The failure this
     * debounce would produce is "the guest unpaused, the host stayed paused" (or the reverse): an
     * intent overtaking its successor and the newer one then being discarded as stale. It is
     * unreachable on this transport. {@code PAUSE_INTENT} travels TCP over the single
     * {@code activeChannel}, JSON-line framed, and the pump drains that channel in arrival order —
     * so at 200 ms RTT with 2% loss the loss becomes a retransmit, i.e. <em>delay</em>, and delay
     * cannot reorder a byte stream. The guest stamps every intent from one monotonic
     * {@link #nextLocalSeq()} counter shared by both sources, so seqs arrive strictly increasing and
     * <em>every</em> intent is applied; the debounce never actually discards anything in production.
     * It is kept as the guard for the one case that can go backwards — a resumed session whose peer is
     * a fresh process counting from zero, which {@link #resetGuestIntentWatermark()} handles — and for
     * any future carriage that is not a single ordered stream.
     *
     * <p>The two sources sharing one watermark is safe for the same reason: they also share the
     * sender's counter, so a SCREEN intent can never carry a seq that makes a later KEY intent look
     * stale.
     */
    public synchronized boolean applyGuestKeyPauseIntent(boolean paused, long seq) {
        if (seq <= appliedGuestSeq) {
            return false;
        }
        appliedGuestSeq = seq;
        guestKeyPauseIntent = paused;
        return true;
    }

    /** Apply a guest screen-open pause intent with the same last-writer-wins debounce. */
    public synchronized boolean applyGuestScreenPauseIntent(boolean paused, long seq) {
        if (seq <= appliedGuestSeq) {
            return false;
        }
        appliedGuestSeq = seq;
        guestScreenPauseIntent = paused;
        return true;
    }

    public synchronized boolean guestKeyPauseIntent() {
        return guestKeyPauseIntent;
    }

    public synchronized boolean guestScreenPauseIntent() {
        return guestScreenPauseIntent;
    }

    /**
     * Session resume: forget the last applied guest intent seq without touching any pause state.
     *
     * <p>Found by the Phase 20 M6 audit. The watermark is a property of the <em>sender's counter</em>,
     * and a guest that comes back as a fresh process (the supported rejoin: restart, load the
     * coordinated autosave, resume the same session id) starts that counter at zero again while the
     * host still holds whatever the old process reached. Every pause intent the returning guest sends
     * is then silently discarded as stale until it counts back past the old high-water mark — the host
     * ignoring the guest's pause key and screen opens for the first several presses, with nothing in
     * the log to say why.
     *
     * <p>Safe to do unconditionally at resume: the socket that could have carried an older intent is
     * gone with the old connection, so there is nothing in flight for the watermark to protect
     * against. The intents themselves are deliberately left alone — the world's pause state must
     * survive the outage, only the sequencing does not.
     */
    public synchronized void resetGuestIntentWatermark() {
        appliedGuestSeq = Long.MIN_VALUE;
    }

    /** Host override: force-clear the guest's manual (key) pause only; never the screen pause. */
    public synchronized void forceClearGuestKeyPauseIntent() {
        guestKeyPauseIntent = false;
    }

    /** Phase 14 hook: combat start/end. */
    public synchronized void setEitherInCombat(boolean inCombat) {
        eitherInCombat = inCombat;
    }

    public synchronized boolean eitherInCombat() {
        return eitherInCombat;
    }

    /**
     * Phase 20.2: the host holds the world for the whole reconnect grace window. Unlike every other
     * intent this one is not a player's choice — it is the session refusing to advance past a partner
     * who is not there to see it — so no pause key clears it and it outranks the rest in the HUD's
     * holder wording. Cleared when the window closes, whichever way it closes.
     */
    public synchronized void setReconnectHold(boolean held) {
        reconnectHold = held;
    }

    public synchronized boolean reconnectHold() {
        return reconnectHold;
    }

    /** The host-authoritative effective pause: the OR of all intents. */
    public synchronized boolean effectivePaused() {
        return hostPauseIntent || guestKeyPauseIntent || guestScreenPauseIntent || eitherInCombat
                || reconnectHold;
    }

    /* ===================== Guest side ===================== */

    /**
     * Guest pressed the pause key. Records a pending press; the pump forwards it next frame as the
     * opposite of the observed pause state. Not stored as a sticky intent so a host force-clear can
     * never desync the two.
     */
    public synchronized void recordGuestPauseKeyPress() {
        pendingGuestKeyPress = true;
    }

    /** Pump: consume a pending guest pause-key press (true once per press). */
    public synchronized boolean consumeGuestKeyPress() {
        boolean pending = pendingGuestKeyPress;
        pendingGuestKeyPress = false;
        return pending;
    }

    /** The manual pause value to send for a key press: the opposite of what the guest observes. */
    public synchronized boolean desiredKeyPause() {
        return !observedPaused;
    }

    /**
     * Guest: update the local screen-open level. Returns true if it changed (and therefore needs to be
     * forwarded to the host).
     */
    public synchronized boolean updateGuestScreenLevel(boolean open) {
        if (open == guestScreenLevel) {
            return false;
        }
        guestScreenLevel = open;
        return true;
    }

    public synchronized boolean guestScreenLevel() {
        return guestScreenLevel;
    }

    /** Record the pause state this client currently observes on its own clock. */
    public synchronized void setObservedPaused(boolean paused) {
        observedPaused = paused;
    }

    public synchronized boolean observedPaused() {
        return observedPaused;
    }

    /** Next monotonic seq for an outgoing {@code PAUSE_INTENT} (last-writer-wins on the host). */
    public synchronized long nextLocalSeq() {
        return ++localSeqCounter;
    }

    /** Clear all transient intent state on session end so nothing leaks across sessions. */
    public synchronized void reset() {
        hostPauseIntent = false;
        guestKeyPauseIntent = false;
        guestScreenPauseIntent = false;
        eitherInCombat = false;
        reconnectHold = false;
        appliedGuestSeq = Long.MIN_VALUE;
        guestScreenLevel = false;
        pendingGuestKeyPress = false;
        localSeqCounter = 0L;
        observedPaused = false;
    }
}
