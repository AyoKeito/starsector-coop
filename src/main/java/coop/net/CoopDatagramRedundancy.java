package coop.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * <p><b>Depth is one previous section by default</b>, not two: the datagram buffer has room, but a
 * {@code FLEET_SNAPSHOT} body carries a full roster and tripling it buys a 0.0008% case. Phase 29 M2
 * makes depth an internal escape hatch — {@link #setDepth(int)}, 1 or 2 — raised to 2 only while the
 * cadence controller holds the floor tier <em>for a loss reason</em> on a UDP path. That is the
 * RED/RFC 2198 crossover: at burst loss around 10% a second copy pays for itself, while on a clean
 * link, on the reliable TCP fallback, or when the floor was chosen because the outbound queue is
 * already backed up, the extra section is pure cost and in the backlog case actively harmful.
 * Calibration, not configuration (no Phase 28 key).
 *
 * <p>A "stream" is {@code (type, chunk)}, not {@code type} alone. Chunk is 0 for everything today,
 * but once Phase 20 M4 splits a batch across chunks, chunk 3's redundant section must be chunk 3's
 * previous send — pairing it with whatever chunk happened to be composed last would ship a body the
 * receiver then applies to the wrong slice of the batch.
 */
public final class CoopDatagramRedundancy {

    /** Depth every stream starts at, and the only depth a healthy link ever uses. */
    public static final int DEFAULT_DEPTH = 1;
    /** The burst-loss escape hatch's depth; see the class doc. */
    public static final int MAX_DEPTH = 2;

    private record StreamKey(CoopMessages.Type type, int chunk) {
    }

    /** Per stream, the most recent sends still riding along, oldest first. */
    private final Map<StreamKey, ArrayDeque<CoopMessages.DatagramSection>> previousByStream =
            new HashMap<>();

    private int depth = DEFAULT_DEPTH;

    /**
     * Sets how many previous sections ride along, clamped to
     * [{@link #DEFAULT_DEPTH}, {@link #MAX_DEPTH}]. Lowering it drops the surplus held sections at
     * once, so the very next datagram is already the smaller shape.
     */
    public void setDepth(int depth) {
        this.depth = Math.max(DEFAULT_DEPTH, Math.min(MAX_DEPTH, depth));
        for (ArrayDeque<CoopMessages.DatagramSection> held : previousByStream.values()) {
            while (held.size() > this.depth) {
                held.removeFirst();
            }
        }
    }

    /** The depth currently in force. */
    public int depth() {
        return depth;
    }

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
        ArrayDeque<CoopMessages.DatagramSection> held = previousByStream
                .computeIfAbsent(new StreamKey(type, chunk), ignored -> new ArrayDeque<>());
        List<CoopMessages.DatagramSection> sections = new ArrayList<>(held.size() + 1);
        sections.addAll(held);
        sections.add(current);
        held.addLast(current);
        while (held.size() > depth) {
            held.removeFirst();
        }
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
        List<CoopMessages.DatagramSection> previous = fullPrevBody == null
                ? List.of()
                : List.of(new CoopMessages.DatagramSection(prevEpoch, prevGameTimeMillis, chunk,
                        fullPrevBody));
        return composeWithBaselines(token, senderId, type, previous, curEpoch, curGameTimeMillis,
                chunk, bodyAgainstPrev);
    }

    /**
     * The depth-N form of {@link #composeWithBaseline} (Phase 29 M2). {@code previousFullSections} is
     * this chunk's recent sends as FULL bodies, oldest first, and the current send is delta-coded
     * against the <em>last</em> of them — the section physically before it in the packet, which is
     * exactly what the receiver resolves the delta against.
     *
     * <p>Sizing stays the caller's job and the invariant grows with the depth: a chunk packed so that
     * {@code overhead + (depth + 1) * fullFormBytes} fits the budget makes any {@code depth + 1}
     * consecutive sends of that chunk fit together, since a delta is never larger than the full form
     * of the same records. See {@code CoopNpcFleetReplicator.sendMotionChunks}.
     */
    public static String composeWithBaselines(String token, String senderId, CoopMessages.Type type,
                                              List<CoopMessages.DatagramSection> previousFullSections,
                                              long curEpoch, long curGameTimeMillis, int chunk,
                                              String bodyAgainstPrev) {
        Objects.requireNonNull(type, "type");
        CoopMessages.DatagramSection current =
                new CoopMessages.DatagramSection(curEpoch, curGameTimeMillis, chunk, bodyAgainstPrev);
        if (previousFullSections == null || previousFullSections.isEmpty()) {
            return CoopMessages.datagram(token, senderId, type, List.of(current));
        }
        List<CoopMessages.DatagramSection> sections =
                new ArrayList<>(previousFullSections.size() + 1);
        sections.addAll(previousFullSections);
        sections.add(current);
        return CoopMessages.datagram(token, senderId, type, sections);
    }

    /**
     * Forgets held sections (session end / replicator reset) so a new session starts clean, and puts
     * the depth back to {@link #DEFAULT_DEPTH} — a new link has no measured loss to justify the
     * escape hatch, and the pump re-raises it on the next evaluation if one appears.
     */
    public void reset() {
        previousByStream.clear();
        depth = DEFAULT_DEPTH;
    }
}
