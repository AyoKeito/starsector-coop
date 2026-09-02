package coop.time;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Phase 7c: converges the GUEST's campaign clock onto the host's, using the drift signal that the
 * {@code TIME_SNAPSHOT} stream has been carrying (and discarding) since Phase 7.
 *
 * <p><b>Why drift exists.</b> {@code CampaignClock.advance(float)} does
 * {@code cal.add(Calendar.SECOND, (int)(...))} — it truncates the fractional calendar-seconds every
 * frame, and the loss is a function of each machine's frame pacing. Two clients therefore drift apart
 * at a shared 1x with zero network fault. Every pause/unpause mirror edge costs the guest another
 * ~200 ms + RTT of host time (1 real second = 0.1 game-day). Measured: ~0.2 game-days over two idle
 * hours, ~2 game-days over a dialog-heavy hour. Prevention is impossible; reconciliation is the fix.
 *
 * <p><b>The three rules that make correction safe.</b>
 *
 * <ul>
 *   <li><b>Guest-only.</b> The host clock is authoritative and never touched.</li>
 *   <li><b>Never backward.</b> The engine's only clock-derived cached state is
 *       {@code ReachEconomyStepper.prevMonth}, and month-end fires on {@code getMonth() != prevMonth}
 *       — an <em>inequality</em>. A backward write across a month boundary pays monthly income twice
 *       (once crossing back, once re-crossing forward). A forward jump fires it at most once no
 *       matter how many boundaries it clears. So a guest that is AHEAD slows down; it never rewinds.
 *       Every write in this class goes through {@link #writeForward}, which refuses a non-forward
 *       target outright.</li>
 *   <li><b>Slew while running, snap only in quiescent windows.</b> Unpaused corrections are bounded
 *       to a fraction of the frame's own clock advance, so the clock still moves forward every frame
 *       and monotonicity holds by construction. Full-drift snaps happen during a shared pause (both
 *       clocks frozen, the offset is exact) or — with a persistence gate and a loud log — when
 *       unpaused drift exceeds two game-days.</li>
 * </ul>
 *
 * <p><b>What this class must never touch (Phase 29 M1 invariant).</b> It writes {@code cal} and the
 * cached {@code timestamp} field and nothing else. The mirror-motion pipeline
 * ({@code CoopStreamClock}, {@code CoopMotionTimeline}, {@code advanceMirrorMotion}) runs on raw
 * frame dt and is provably decoupled from the campaign clock; implementing "slew" by scaling a dt
 * would silently couple the two and stack this rate on top of the timeline's own.
 *
 * <p><b>Always write both.</b> {@code cal} is {@code transient}; save serialization writes only
 * {@code timestamp}, and {@code readResolve()} rebuilds {@code cal} FROM it. A {@code cal}-only write
 * is silently reverted by the next save/load. {@code getTimestamp()} also reads the cached field, so
 * a {@code cal}-only write leaves every {@code getElapsedDaysSince} consumer stale until the engine's
 * next {@code advance()}.
 *
 * <p><b>Engine access.</b> {@code getCal()} is on the public {@code CampaignClockAPI}, so only the
 * private {@code timestamp} field needs a handle — {@code java.lang.invoke} only, because
 * {@code java.lang.reflect.*} is hard-blocked by the game's script classloader (compiles and
 * unit-tests green, throws in-game). Any {@code Throwable} from resolve or invoke goes sticky:
 * {@link #isAvailable()} stays false, one warning is logged, and the guest falls back to exactly the
 * pre-7c behaviour (uncorrected drift). {@code -Dcoop.clock.disable=true} forces that fallback.
 */
public final class CoopClockReconciler {

    /** Debug lever (read once at construction) that forces the no-op path without a code edit. */
    public static final String DISABLE_PROPERTY = "coop.clock.disable";

    /** Campaign-calendar milliseconds in one game day ({@code getElapsedDaysSince} divides by 8.64E7). */
    public static final long MILLIS_PER_GAME_DAY = 86_400_000L;
    /**
     * Calendar-ms the clock advances per REAL second at 1x ({@code secondsPerDay = 10}, so one real
     * second is 0.1 game-day). Under fast-forward the engine calls {@code advance()} once per extra
     * iteration with the same per-call amount, so this per-call conversion is unchanged.
     */
    public static final double MILLIS_PER_REAL_SECOND = 8_640_000d;

    /** Rank filter width. 9 survives 4 outliers; the survey's windows ran 8 (NTP) to 40 (GGPO). */
    static final int RING_SIZE = 9;
    /** Start correcting above this: 0.05 game-day, ~half a real second of game time. */
    static final long ENTRY_THRESHOLD_MILLIS = MILLIS_PER_GAME_DAY / 20;
    /** ...and keep correcting until below this: 0.01 game-day, ~8.6 game-minutes. */
    static final long EXIT_THRESHOLD_MILLIS = MILLIS_PER_GAME_DAY / 100;
    /** Above 0.1 game-day the fast slew tier applies; below it the correction tapers to 10%. */
    static final long TAPER_THRESHOLD_MILLIS = MILLIS_PER_GAME_DAY / 10;
    /** Unpaused snap threshold. */
    static final long BIG_DRIFT_MILLIS = 2 * MILLIS_PER_GAME_DAY;

    /** Steady slew: the most corroborated rate in the survey (Unity NfE ±10%, chrony 8.33%). */
    static final double SLEW_RATE = 0.10;
    /** Fast tier, only while drift exceeds {@link #TAPER_THRESHOLD_MILLIS}; no surveyed system exceeds 30%. */
    static final double FAST_SLEW_RATE = 0.30;

    /** An OS stall manufactures one huge sample; NTP's stepout timer exists for exactly this. */
    static final int BIG_DRIFT_MIN_SAMPLES = 3;
    static final long BIG_DRIFT_MIN_SPAN_MILLIS = 2_000L;

    /** Guest-ahead is legitimate and self-healing, but must never be silent. */
    static final long GUEST_AHEAD_WARN_AFTER_MILLIS = 60_000L;

    /** NTP's popcorn suppressor: discard beyond {@code SGATE x} the ring's own dispersion. */
    static final double SPIKE_GATE_FACTOR = 3.0;
    static final int SPIKE_GATE_MIN_SAMPLES = 3;

    /** How often the diagnostics line prints while {@link CoopDebug#diagnosticsEnabled()} is on. */
    static final long DIAGNOSTICS_INTERVAL_MILLIS = 5_000L;

    /**
     * The engine seam. Both methods may throw: the production implementation is a
     * {@link MethodHandle} whose {@code invoke} is declared {@code throws Throwable}, plus live
     * {@code Global.getSector()} lookups. The reconciler catches {@code Throwable} and goes sticky.
     */
    public interface ClockPort {
        long getTimestamp() throws Throwable;

        /** Writes {@code cal} AND the cached {@code timestamp} field to the same value, in that order. */
        void setTimestamp(long value) throws Throwable;
    }

    /** Resolver seam so tests can inject fakes (and a throwing resolver) with no engine on the path. */
    public interface ClockPortResolver {
        /** @return the resolved port, or {@code null} when there is no live campaign clock yet. */
        ClockPort resolve() throws Throwable;
    }

    private final ClockPortResolver resolver;
    private final LongSupplier wallClockMillis;
    private final boolean disabledByProperty;

    private ClockPort port;
    /** Sticky: once anything throws we never touch the clock again this session. */
    private boolean failed;
    private boolean warned;

    private final long[] samples = new long[RING_SIZE];
    private int sampleCount;
    private int writeIndex;
    /** The median of {@link #samples}, kept current on every push and reduced by every applied slew. */
    private long driftEstimateMillis;

    /** Latest snapshot state, used by the shared-pause snap and the slew's paused check. */
    private boolean lastHostPaused;
    private boolean lastHostFastForward;
    private boolean lastGuestPaused;
    private long lastHostTimestampMillis;

    private boolean correcting;

    private int bigDriftStreak;
    private long bigDriftFirstSeenMillis;

    private boolean guestAheadTracking;
    private long guestAheadSinceMillis;
    private boolean guestAheadWarned;

    private long pauseGateDiscards;
    private long spikeGateDiscards;
    private long sharedPauseSnaps;
    private long unpausedSnaps;
    private long nextDiagnosticsAtMillis;

    public CoopClockReconciler() {
        this(CoopClockReconciler::resolveEngineClockPort, System::currentTimeMillis,
                Boolean.getBoolean(DISABLE_PROPERTY));
    }

    public CoopClockReconciler(ClockPortResolver resolver, LongSupplier wallClockMillis, boolean disabled) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
        this.disabledByProperty = disabled;
    }

    /**
     * False means the guest runs exactly as it did before Phase 7c: uncorrected drift, no crash. A
     * missing sector/clock (title screen, teardown) is NOT a failure — it returns false and retries
     * on the next call, because the pump constructs this once and outlives any single campaign.
     */
    public boolean isAvailable() {
        return ensureResolved();
    }

    /**
     * Called by the pump when a {@code TIME_SNAPSHOT} has been applied on the guest.
     *
     * @param hostTimestampMillis the host's {@code CampaignClock.getTimestamp()} at send time
     * @param hostPaused          the host's {@code sector.isPaused()} at send time
     * @param hostFastForward     the host's {@code CampaignState.fastForward}; recorded for the
     *                            diagnostics line only. Latency compensation is 0 in v1 (LAN one-way
     *                            delay is ~1/1000 of the entry dead zone), so nothing scales by it yet
     * @param guestPaused         the pump's local {@code sector.isPaused()} read
     */
    public void onSnapshot(long hostTimestampMillis, boolean hostPaused, boolean hostFastForward,
                           boolean guestPaused) {
        if (!ensureResolved()) {
            return;
        }
        lastHostPaused = hostPaused;
        lastHostFastForward = hostFastForward;
        lastGuestPaused = guestPaused;
        lastHostTimestampMillis = hostTimestampMillis;

        long guestTimestampMillis;
        try {
            guestTimestampMillis = port.getTimestamp();
        } catch (Throwable ex) {
            fail("Failed to read the guest campaign clock", ex);
            return;
        }
        // Latency compensation is deliberately 0 (see the hostFastForward javadoc).
        long drift = hostTimestampMillis - guestTimestampMillis;

        // Gate (a): during a pause mirror edge the two clocks are LEGITIMATELY running at different
        // rates for a frame or two, so the sample measures the edge, not the offset.
        if (hostPaused != guestPaused) {
            pauseGateDiscards++;
            return;
        }
        // Gate (b): self-scaling popcorn suppressor. Needs enough samples to have a dispersion
        // estimate at all; with a perfectly constant ring (RMS 0) there is no scale to compare
        // against, so the sample is accepted rather than rejected on a divide-by-nothing.
        if (sampleCount >= SPIKE_GATE_MIN_SAMPLES) {
            long median = median();
            double rms = rmsDeviation(median);
            if (rms > 0d && Math.abs(drift - median) > SPIKE_GATE_FACTOR * rms) {
                spikeGateDiscards++;
                return;
            }
        }

        samples[writeIndex] = drift;
        writeIndex = (writeIndex + 1) % RING_SIZE;
        if (sampleCount < RING_SIZE) {
            sampleCount++;
        }
        driftEstimateMillis = median();

        updateBigDriftPersistence();
        updateGuestAheadTracking();
    }

    /**
     * Called every frame from the pump on the guest while the session is active.
     *
     * @param amountSeconds the pump's raw {@code advance(float)} parameter — REAL seconds, untouched
     * @param guestPaused   the guest's LIVE {@code sector.isPaused()} this frame. The pump keeps
     *                      receiving a positive {@code amountSeconds} while paused, but the engine is
     *                      not advancing the clock then, so a slew would be a naked write: a negative
     *                      one would move the clock backward. The last snapshot's pause flag is not
     *                      good enough here — the guest's own pause key pauses the guest before the
     *                      host's next snapshot confirms it
     */
    public void tick(float amountSeconds, boolean guestPaused) {
        if (!ensureResolved()) {
            return;
        }
        long now = wallClockMillis.getAsLong();
        maybeWarnGuestAhead(now);

        long drift = driftEstimateMillis;
        boolean inEpisode = updateEpisode(drift);
        maybeLogDiagnostics(now, drift, inEpisode, guestPaused);
        if (!inEpisode) {
            return;
        }

        // (2) Shared pause: both clocks are frozen, so the measured offset is exact and the host
        // timestamp is not stale. This is a genuine quiescent window — chrony/linuxptp only
        // approximate one by stepping at boot. Absorb the whole positive drift silently.
        if (lastHostPaused && guestPaused) {
            if (drift > 0 && writeForward(lastHostTimestampMillis)) {
                sharedPauseSnaps++;
                if (CoopDebug.diagnosticsEnabled()) {
                    CoopLog.info(CoopClockReconciler.class, "Coop clock shared-pause snap: forward "
                            + days(drift) + " game-days to host timestamp " + lastHostTimestampMillis
                            + "; sample ring cleared");
                }
                clearSamples();
            }
            // Never slew while paused: the engine is not advancing the clock, so a negative
            // correction would be a naked backward write and a positive one an invisible jump.
            return;
        }
        // Guest paused but the host is not (a pause mirror edge, or the guest's own key pause
        // ahead of the host's confirming snapshot): same reasoning, the engine clock is frozen.
        if (guestPaused) {
            return;
        }

        // (3) Unpaused big-drift snap, behind the persistence gate: a single over-threshold median
        // is far more likely to be one OS stall (GC, window drag, disk hitch) than a real multi-day
        // offset, and NTP's state machine discards the first over-threshold sample for the same
        // reason. Target is current + drift, not the recorded host timestamp: the host clock is
        // RUNNING here, so that stamp is stale by the snapshot interval plus the >=2 s the
        // persistence gate took (>= 0.2 game-days, more than the entry dead zone).
        if (drift > BIG_DRIFT_MILLIS && bigDriftStreak >= BIG_DRIFT_MIN_SAMPLES
                && now - bigDriftFirstSeenMillis >= BIG_DRIFT_MIN_SPAN_MILLIS) {
            if (writeForwardBy(drift)) {
                unpausedSnaps++;
                CoopLog.warn(CoopClockReconciler.class, "Coop clock unpaused snap: guest was "
                        + days(drift) + " game-days behind the host over " + bigDriftStreak
                        + " consecutive estimates spanning " + (now - bigDriftFirstSeenMillis)
                        + "ms; snapped forward, sample ring cleared");
                clearSamples();
            }
            return;
        }

        // (4) Slew. The clamp is a fraction of the frame's OWN clock advance, so a negative
        // correction is strictly smaller than what the engine just added: the clock still moves
        // forward this frame. That is the monotonicity guarantee, by construction.
        if (amountSeconds <= 0f) {
            return;
        }
        double frameMillis = amountSeconds * MILLIS_PER_REAL_SECOND;
        double rate = Math.abs(drift) > TAPER_THRESHOLD_MILLIS ? FAST_SLEW_RATE : SLEW_RATE;
        long limit = (long) (rate * frameMillis);
        if (limit <= 0L) {
            return;
        }
        long correction = Math.max(-limit, Math.min(limit, drift));
        if (correction == 0L) {
            return;
        }
        long current;
        try {
            current = port.getTimestamp();
        } catch (Throwable ex) {
            fail("Failed to read the guest campaign clock", ex);
            return;
        }
        if (!write(current + correction)) {
            return;
        }
        // Anti-windup. Source's AdjustAverageDifferenceBy exists solely to stop the filter
        // re-commanding a correction it already issued; at 5 Hz without this the loop over-corrects
        // by up to a full ring of stale samples.
        driftEstimateMillis -= correction;
        for (int i = 0; i < sampleCount; i++) {
            samples[i] -= correction;
        }
    }

    /**
     * Invalidates every buffered sample and ends any correction episode. Called on the session edge,
     * when the guest's interaction dialog closes (pre-dialog samples are stale), and after any snap —
     * Source and RFC 5905 both invalidate all samples after a step, or the pre-step samples
     * immediately command a second, opposite correction.
     */
    public void clearSamples() {
        Arrays.fill(samples, 0L);
        sampleCount = 0;
        writeIndex = 0;
        driftEstimateMillis = 0L;
        correcting = false;
        bigDriftStreak = 0;
        bigDriftFirstSeenMillis = 0L;
        guestAheadTracking = false;
        guestAheadSinceMillis = 0L;
        guestAheadWarned = false;
    }

    // ---- test/diagnostic seams ------------------------------------------------------------------

    long driftEstimateMillis() {
        return driftEstimateMillis;
    }

    int sampleCount() {
        return sampleCount;
    }

    boolean isCorrecting() {
        return correcting;
    }

    long pauseGateDiscards() {
        return pauseGateDiscards;
    }

    long spikeGateDiscards() {
        return spikeGateDiscards;
    }

    // ---- internals ------------------------------------------------------------------------------

    private boolean ensureResolved() {
        if (disabledByProperty) {
            if (!warned) {
                warned = true;
                CoopLog.warn(CoopClockReconciler.class, "-D" + DISABLE_PROPERTY
                        + "=true: guest campaign clock reconciliation disabled by debug property;"
                        + " the guest campaign date will drift from the host uncorrected");
            }
            return false;
        }
        if (failed) {
            return false;
        }
        if (port != null) {
            return true;
        }
        try {
            port = resolver.resolve();
        } catch (Throwable ex) {
            fail("Failed to resolve the campaign clock timestamp handle", ex);
            return false;
        }
        // A null resolve is "no live campaign clock yet", not a failure: this object outlives any one
        // campaign, so retry on the next call instead of going sticky on a title-screen frame.
        return port != null;
    }

    private void fail(String message, Throwable ex) {
        failed = true;
        port = null;
        if (warned) {
            return;
        }
        warned = true;
        String tail = "; the guest campaign date will drift from the host uncorrected";
        if (ex == null) {
            CoopLog.warn(CoopClockReconciler.class, "Coop clock reconciler unavailable: " + message + tail);
        } else {
            CoopLog.warn(CoopClockReconciler.class, "Coop clock reconciler unavailable: " + message + tail, ex);
        }
    }

    /** Two thresholds, not one: the wide entry keeps the rate at exactly 1.0 almost all the time,
     * the tight exit parks each episode ~5x closer than the entry zone (month-number alignment). */
    private boolean updateEpisode(long drift) {
        long magnitude = Math.abs(drift);
        if (correcting) {
            if (magnitude < EXIT_THRESHOLD_MILLIS) {
                correcting = false;
            }
        } else if (magnitude > ENTRY_THRESHOLD_MILLIS) {
            correcting = true;
        }
        return correcting;
    }

    private void updateBigDriftPersistence() {
        if (driftEstimateMillis > BIG_DRIFT_MILLIS) {
            if (bigDriftStreak == 0) {
                bigDriftFirstSeenMillis = wallClockMillis.getAsLong();
            }
            bigDriftStreak++;
        } else {
            bigDriftStreak = 0;
            bigDriftFirstSeenMillis = 0L;
        }
    }

    private void updateGuestAheadTracking() {
        if (driftEstimateMillis < -ENTRY_THRESHOLD_MILLIS) {
            if (!guestAheadTracking) {
                guestAheadTracking = true;
                guestAheadSinceMillis = wallClockMillis.getAsLong();
            }
        } else {
            guestAheadTracking = false;
            guestAheadSinceMillis = 0L;
            guestAheadWarned = false;
        }
    }

    private void maybeWarnGuestAhead(long now) {
        if (guestAheadWarned || !guestAheadTracking
                || now - guestAheadSinceMillis <= GUEST_AHEAD_WARN_AFTER_MILLIS) {
            return;
        }
        guestAheadWarned = true;
        CoopLog.warn(CoopClockReconciler.class, "Coop clock guest ahead: guest clock is "
                + days(-driftEstimateMillis) + " game-days AHEAD of the host and has been for "
                + (now - guestAheadSinceMillis) + "ms; converging by slow-down only (the guest clock"
                + " is never rewound - a backward write would double-pay monthly income)");
    }

    private void maybeLogDiagnostics(long now, long drift, boolean inEpisode, boolean livePaused) {
        if (!CoopDebug.diagnosticsEnabled()) {
            return;
        }
        if (now < nextDiagnosticsAtMillis) {
            return;
        }
        nextDiagnosticsAtMillis = now + DIAGNOSTICS_INTERVAL_MILLIS;
        CoopLog.info(CoopClockReconciler.class, "Coop clock drift: median=" + days(drift)
                + " game-days (" + drift + "ms) samples=" + sampleCount
                + " episode=" + inEpisode
                + " hostPaused=" + lastHostPaused + " guestPaused=" + livePaused
                + " sampleGuestPaused=" + lastGuestPaused
                + " hostFF=" + lastHostFastForward
                + " discards[pause=" + pauseGateDiscards + " spike=" + spikeGateDiscards + "]"
                + " snaps[sharedPause=" + sharedPauseSnaps + " unpaused=" + unpausedSnaps + "]");
    }

    /** Absolute forward write; refuses anything that is not strictly forward. */
    private boolean writeForward(long target) {
        long current;
        try {
            current = port.getTimestamp();
        } catch (Throwable ex) {
            fail("Failed to read the guest campaign clock", ex);
            return false;
        }
        if (target <= current) {
            return false;
        }
        return write(target);
    }

    /** Relative forward write against the clock's live value; refuses a non-positive delta. */
    private boolean writeForwardBy(long deltaMillis) {
        if (deltaMillis <= 0L) {
            return false;
        }
        long current;
        try {
            current = port.getTimestamp();
        } catch (Throwable ex) {
            fail("Failed to read the guest campaign clock", ex);
            return false;
        }
        return write(current + deltaMillis);
    }

    private boolean write(long target) {
        try {
            port.setTimestamp(target);
            return true;
        } catch (Throwable ex) {
            fail("Failed to write the guest campaign clock", ex);
            return false;
        }
    }

    private long median() {
        long[] sorted = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(sorted);
        return sorted[sampleCount / 2];
    }

    private double rmsDeviation(long median) {
        double sum = 0d;
        for (int i = 0; i < sampleCount; i++) {
            double d = (double) samples[i] - (double) median;
            sum += d * d;
        }
        return Math.sqrt(sum / sampleCount);
    }

    private static String days(long millis) {
        return String.format(Locale.ROOT, "%+.4f", millis / (double) MILLIS_PER_GAME_DAY);
    }

    // ---- production engine access ---------------------------------------------------------------

    /**
     * {@code getCal()} is on the public {@code CampaignClockAPI} (verified by javap of
     * {@code starfarer.api.jar}), so the only handle needed is the setter for the private
     * {@code long timestamp} cache. Walks the hierarchy the way {@code CoopBarSync.resolveHandles()}
     * and {@code CoopFastForwardLock} do, so an intermediate subclass cannot break the lookup.
     */
    private static ClockPort resolveEngineClockPort() throws Throwable {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return null;
        }
        CampaignClockAPI clock = sector.getClock();
        if (clock == null) {
            return null;
        }
        Class<?> clockClass = clock.getClass();
        MethodHandle setter = null;
        NoSuchFieldException lastMiss = null;
        for (Class<?> c = clockClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                setter = MethodHandles.privateLookupIn(c, MethodHandles.lookup())
                        .findSetter(c, "timestamp", long.class);
                break;
            } catch (NoSuchFieldException miss) {
                lastMiss = miss;
            }
        }
        if (setter == null) {
            throw lastMiss != null ? lastMiss : new NoSuchFieldException("timestamp");
        }
        return new EngineClockPort(clockClass, setter);
    }

    /**
     * Holds the clock <em>class</em>, never an instance: the live clock is fetched per call so a
     * resolved handle never outlives the campaign it was bound to. A different runtime class fails
     * the cast loudly and trips the sticky fallback, which is the intended outcome.
     */
    private record EngineClockPort(Class<?> clockClass, MethodHandle timestampSetter) implements ClockPort {

        private CampaignClockAPI clock() {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                throw new IllegalStateException("No active campaign sector");
            }
            CampaignClockAPI clock = sector.getClock();
            if (clock == null) {
                throw new IllegalStateException("No active campaign clock");
            }
            return clock;
        }

        @Override
        public long getTimestamp() {
            return clock().getTimestamp();
        }

        @Override
        public void setTimestamp(long value) throws Throwable {
            CampaignClockAPI clock = clock();
            // cal first, then the cached field, same value: cal is the live source of truth every
            // date getter reads, timestamp is the persisted one that survives save/load.
            clock.getCal().setTimeInMillis(value);
            timestampSetter.invoke(clockClass.cast(clock), value);
        }
    }
}
