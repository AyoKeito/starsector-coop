package coop.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Receiver-side ordering guard for the UDP state streams (Phase 29 M1 wire prerequisite; the Phase
 * 20.1 epoch guard). Keeps a high-water epoch per {@code (senderId, type)} and returns only the
 * sections of a datagram that are strictly newer, in wire order (oldest first). A reordered datagram —
 * whose sections all sit at or below the watermark — yields nothing instead of applying a stale
 * position, and a redundant section ({@link CoopDatagramRedundancy}) that already arrived is dropped
 * the same way while a redundant section covering a lost packet passes.
 *
 * <p><b>Why the key includes the sender (Phase 20.1).</b> Epochs come from each sender's own
 * {@link CoopStreamClock}; they are not comparable across senders. Keyed by type alone, a peer that
 * happened to start its stream earlier would park the watermark above a second peer's epochs and
 * silently swallow that peer's entire stream. With one guest this is invisible, which is exactly why
 * it is fixed now rather than during the multi-guest phase.
 *
 * <p><b>Session scope.</b> The table resets when the datagram {@code token} changes: a new session
 * means every peer's epochs may restart from zero, and a watermark left at the old session's high
 * water would swallow the new session's whole stream.
 *
 * <p><b>Chunks.</b> {@code chunk} is deliberately ignored here — it is 0 on every stream today.
 * Phase 20 M4, which is what puts non-zero chunks on the wire, changes the rule to "epoch equal to the
 * watermark is accepted when the chunk has not been seen yet", since all chunks of one tick share an
 * epoch and strictly-greater would drop every chunk after the first.
 */
public final class CoopDatagramWatermark {

    private record StreamKey(String senderId, CoopMessages.Type type) {
    }

    private final Map<StreamKey, Long> watermarkByStream = new HashMap<>();
    private String token = "";

    /** The datagram's fresh sections, oldest first; empty when everything in it is stale. */
    public List<CoopMessages.DatagramSection> accept(CoopMessages.Datagram datagram) {
        Objects.requireNonNull(datagram, "datagram");
        if (!token.equals(datagram.token())) {
            watermarkByStream.clear();
            token = datagram.token();
        }
        StreamKey key = new StreamKey(datagram.senderId(), datagram.type());
        long watermark = watermarkByStream.getOrDefault(key, Long.MIN_VALUE);
        List<CoopMessages.DatagramSection> fresh = new ArrayList<>(datagram.sections().size());
        long highest = watermark;
        for (CoopMessages.DatagramSection section : datagram.sections()) {
            if (section.epoch() > watermark) {
                fresh.add(section);
                highest = Math.max(highest, section.epoch());
            }
        }
        if (highest > watermark) {
            watermarkByStream.put(key, highest);
        }
        return fresh;
    }

    /** Clears all watermarks (session teardown). */
    public void reset() {
        watermarkByStream.clear();
        token = "";
    }
}
