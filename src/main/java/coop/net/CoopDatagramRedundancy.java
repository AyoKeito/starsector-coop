package coop.net;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sender-side loss immunity for the UDP state streams (Phase 29 M1 wire prerequisite): every datagram
 * carries the previous send of the same stream as an extra section, oldest first. At 2% loss a single
 * dropped packet's sections simply arrive in the next one, so the receiver's interpolation buffer
 * never sees a gap — cheaper and more reliable than tuning starvation behavior to mask loss (see the
 * plan's Phase 29 research banner). Two consecutive drops (~0.04% independent, rarer than that in
 * practice because the loss that matters is bursty and the starvation ladder covers bursts) fall
 * through to {@link coop.fleet.CoopMotionInterpolator}'s capped extrapolation.
 *
 * <p>Depth is one previous section, not two: the datagram buffer has room, but a
 * {@code FLEET_SNAPSHOT} body carries a full roster and tripling it buys a 0.0008% case. Documented
 * as calibration, not configuration (no Phase 28 key).
 *
 * <p>A "stream" is {@code (type, chunk)}, not {@code type} alone. Chunk is 0 for everything today,
 * but once Phase 20 M4 splits a batch across chunks, chunk 3's redundant section must be chunk 3's
 * previous send — pairing it with whatever chunk happened to be composed last would ship a body the
 * receiver then applies to the wrong slice of the batch.
 */
public final class CoopDatagramRedundancy {

    private record StreamKey(CoopMessages.Type type, int chunk) {
    }

    private final Map<StreamKey, CoopMessages.DatagramSection> previousByStream = new HashMap<>();

    /**
     * Encodes a chunk-0 datagram carrying {@code body} stamped with {@code epoch}/
     * {@code sentGameTimeMillis}, preceded by the previous section of the same stream when one exists,
     * and records the new section as next time's previous.
     */
    public String compose(String token, String senderId, CoopMessages.Type type, long epoch,
                          long sentGameTimeMillis, String body) {
        return compose(token, senderId, type, epoch, sentGameTimeMillis, 0, body);
    }

    /** As above for an explicit chunk index; "previous" is tracked per {@code (type, chunk)}. */
    public String compose(String token, String senderId, CoopMessages.Type type, long epoch,
                          long sentGameTimeMillis, int chunk, String body) {
        Objects.requireNonNull(type, "type");
        CoopMessages.DatagramSection current =
                new CoopMessages.DatagramSection(epoch, sentGameTimeMillis, chunk, body);
        CoopMessages.DatagramSection previous = previousByStream.put(new StreamKey(type, chunk), current);
        List<CoopMessages.DatagramSection> sections =
                previous == null ? List.of(current) : List.of(previous, current);
        return CoopMessages.datagram(token, senderId, type, sections);
    }

    /**
     * The delta-coded form of the same two-section layout (Phase 20 M4, {@code NPC_FLEET_MOTION}):
     * the redundant previous send goes out as the FULL body it always was, and the current send goes
     * out as a delta <em>against that section</em>. Both halves are in the same packet by
     * construction, so the baseline can never be lost separately from the delta that needs it — which
     * is the whole reason the ack-free mask is safe without Quake 3's acked-baseline machinery.
     *
     * <p>Stateless, unlike {@link #compose}: a chunked producer already has to keep its own per-chunk
     * batch to compute the delta, so having this class keep a second copy of the same thing would be
     * two sources of truth for one baseline. The caller passes both bodies and both stamps.
     *
     * <p>{@code fullPrevBody} may be null, which is the first send of a chunk: a single full section
     * goes out with the current stamps and nothing is delta-coded.
     */
    public static String composeWithBaseline(String token, String senderId, CoopMessages.Type type,
                                             long prevEpoch, long prevGameTimeMillis, int chunk,
                                             String fullPrevBody,
                                             long curEpoch, long curGameTimeMillis,
                                             String bodyAgainstPrev) {
        Objects.requireNonNull(type, "type");
        CoopMessages.DatagramSection current =
                new CoopMessages.DatagramSection(curEpoch, curGameTimeMillis, chunk, bodyAgainstPrev);
        if (fullPrevBody == null) {
            return CoopMessages.datagram(token, senderId, type, List.of(current));
        }
        return CoopMessages.datagram(token, senderId, type, List.of(
                new CoopMessages.DatagramSection(prevEpoch, prevGameTimeMillis, chunk, fullPrevBody),
                current));
    }

    /** Forgets held sections (session end / replicator reset) so a new session starts clean. */
    public void reset() {
        previousByStream.clear();
    }
}
