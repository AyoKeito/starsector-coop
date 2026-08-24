package coop.net;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sender-side loss immunity for the UDP state streams (Phase 29 M1 wire prerequisite): every datagram
 * carries the previous send of the same type as an extra section, oldest first. At 2% loss a single
 * dropped packet's sections simply arrive in the next one, so the receiver's interpolation buffer
 * never sees a gap — cheaper and more reliable than tuning starvation behavior to mask loss (see the
 * plan's Phase 29 research banner). Two consecutive drops (~0.04% independent, rarer than that in
 * practice because the loss that matters is bursty and the starvation ladder covers bursts) fall
 * through to {@link coop.fleet.CoopMotionInterpolator}'s capped extrapolation.
 *
 * <p>Depth is one previous section, not two: the 64 KB datagram buffer has room, but a
 * {@code FLEET_SNAPSHOT} body carries a full roster and tripling it buys a 0.0008% case. Documented
 * as calibration, not configuration (no Phase 28 key).
 */
public final class CoopDatagramRedundancy {

    private final Map<CoopMessages.Type, CoopMessages.DatagramSection> previousByType =
            new EnumMap<>(CoopMessages.Type.class);

    /**
     * Encodes a datagram carrying {@code body} stamped with {@code epoch}/{@code sentGameTimeMillis},
     * preceded by the previous section of the same type when one exists, and records the new section
     * as next time's previous.
     */
    public String compose(String sessionId, CoopMessages.Type type, long epoch,
                          long sentGameTimeMillis, String body) {
        Objects.requireNonNull(type, "type");
        CoopMessages.DatagramSection current =
                new CoopMessages.DatagramSection(epoch, sentGameTimeMillis, body);
        CoopMessages.DatagramSection previous = previousByType.put(type, current);
        List<CoopMessages.DatagramSection> sections =
                previous == null ? List.of(current) : List.of(previous, current);
        return CoopMessages.datagram(sessionId, type, sections);
    }

    /** Forgets held sections (session end / replicator reset) so a new session starts clean. */
    public void reset() {
        previousByType.clear();
    }
}
