package coop.net;

/**
 * The certified send rates the UDP state streams ({@code FLEET_SNAPSHOT}, {@code NPC_FLEET_MOTION})
 * are allowed to run at (Phase 29 M2). Discrete and code-chosen: a free rate dial would turn every
 * desync report into "what rate were you running?", and each tier has to be certified on the shaped
 * matrix once rather than a continuum of them never.
 *
 * <p>Intervals are <em>game</em> time, not wall time — {@link CoopStreamCadence} measures the stream
 * clock, so under fast-forward the wall-clock send rate rises with the FF factor and the receiver's
 * interpolation buffer keeps the same depth in game seconds.
 *
 * <p><b>{@link #TOP} is defined but dark.</b> {@link #TOP_TIER_ENABLED} is {@code false} and the
 * ladder {@link #upshift()} climbs stops at {@link #DEFAULT}. Doubling the rate doubles chunk volume,
 * and the plan's rule is that 20 Hz ships only after a shaped-matrix certification pass taken at that
 * doubled volume shows MTU headroom (Phase 20.1's datagram-size histogram is the instrument). The
 * enum member exists so the wire field, the parser and the HUD already speak the value when that
 * certification happens; nothing selects it today.
 */
public enum CoopCadenceTier {

    /** 5 Hz. The degraded floor: loss, latency, outbound backlog, or the TCP-wrapped fallback. */
    FLOOR(5),
    /** 10 Hz. What a healthy link runs at, and the rate every pre-M2 build ran at unconditionally. */
    DEFAULT(10),
    /** 20 Hz. Defined, parsed, displayable — and never selected; see the class doc. */
    TOP(20);

    /** Whether {@link #TOP} may be climbed to. False until the shaped matrix certifies it. */
    public static final boolean TOP_TIER_ENABLED = false;

    private final int hz;
    private final long intervalMillis;

    CoopCadenceTier(int hz) {
        this.hz = hz;
        this.intervalMillis = 1000L / hz;
    }

    /** The tier's rate in hertz; this is what travels on {@code LINK_STATUS} and shows on the HUD. */
    public int hz() {
        return hz;
    }

    /** The send interval in milliseconds of stream time. */
    public long intervalMillis() {
        return intervalMillis;
    }

    /** The highest tier the ladder may climb to right now. */
    public static CoopCadenceTier highestEnabled() {
        return TOP_TIER_ENABLED ? TOP : DEFAULT;
    }

    /** One step up the enabled ladder, or this tier when it is already the highest enabled one. */
    public CoopCadenceTier upshift() {
        CoopCadenceTier ceiling = highestEnabled();
        if (ordinal() >= ceiling.ordinal()) {
            return ceiling;
        }
        return values()[ordinal() + 1];
    }

    /**
     * The tier a peer's announced {@code cadenceHz} names.
     *
     * <p>Anything unrecognised — an older peer's absent field arrives as the parser's 10, a newer
     * peer's tier we have no member for, a garbage number from a spoofer that got past the session
     * token — becomes {@link #DEFAULT} rather than throwing. A cadence is a performance hint; a link
     * report is not worth dropping over one, and 10 Hz is what every build before M2 sent at.
     *
     * <p>{@link #TOP} is likewise refused while it is dark: a peer must not be able to talk this side
     * into an uncertified rate by announcing it.
     */
    public static CoopCadenceTier fromHz(int hz) {
        for (CoopCadenceTier tier : values()) {
            if (tier.hz == hz) {
                return tier == TOP && !TOP_TIER_ENABLED ? DEFAULT : tier;
            }
        }
        return DEFAULT;
    }
}
