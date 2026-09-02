package coop.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * <p><b>Chunks (Phase 20 M4).</b> A batch too large for one MTU-safe datagram is split into chunk
 * datagrams that all share one epoch, so strictly-greater alone would drop every chunk after the
 * first. The rule is therefore per {@code (senderId, type)}: a section is fresh when its epoch is
 * above the watermark, or equal to it and its chunk index has not been seen at that epoch yet. A
 * higher epoch resets the seen-chunk set, which is what bounds it — it only ever holds the chunks of
 * one tick.
 *
 * <p>That is exactly the property duplicate suppression needs and no more. A redundancy copy of
 * chunk 3 that already arrived is dropped; the same copy covering a lost chunk 3 passes; a reordered
 * datagram from an older epoch applies nothing.
 */
public final class CoopDatagramWatermark {

    private record StreamKey(String senderId, CoopMessages.Type type) {
    }

    /** Per-stream high water: the epoch, and which chunks of exactly that epoch have been seen. */
    private static final class Mark {
        private long epoch = Long.MIN_VALUE;
        private final Set<Integer> chunksAtEpoch = new HashSet<>();

        /** True when this section is fresh; mutates the mark to record it. */
        boolean accept(long epoch, int chunk) {
            if (epoch > this.epoch) {
                this.epoch = epoch;
                chunksAtEpoch.clear();
                chunksAtEpoch.add(chunk);
                return true;
            }
            return epoch == this.epoch && chunksAtEpoch.add(chunk);
        }
    }

    private final Map<StreamKey, Mark> watermarkByStream = new HashMap<>();
    private String token = "";

    /** The datagram's fresh sections, oldest first; empty when everything in it is stale. */
    public List<CoopMessages.DatagramSection> accept(CoopMessages.Datagram datagram) {
        Objects.requireNonNull(datagram, "datagram");
        if (!token.equals(datagram.token())) {
            watermarkByStream.clear();
            token = datagram.token();
        }
        StreamKey key = new StreamKey(datagram.senderId(), datagram.type());
        Mark mark = watermarkByStream.computeIfAbsent(key, ignored -> new Mark());
        List<CoopMessages.DatagramSection> fresh = new ArrayList<>(datagram.sections().size());
        // Sections are composed oldest first, so evaluating them in wire order is what lets a
        // redundancy copy of a lost section apply before the current one raises the watermark past it.
        for (CoopMessages.DatagramSection section : datagram.sections()) {
            if (mark.accept(section.epoch(), section.chunk())) {
                fresh.add(section);
            }
        }
        return fresh;
    }

    /**
     * Which sections of {@code datagram} are fresh, as a per-index mask. Same single-pass mutation as
     * {@link #accept} — call one or the other per datagram, never both — but it keeps the index, which
     * the pump needs to pair an accepted section with the body it decoded for it (a chunked
     * {@code NPC_FLEET_MOTION} delta only decodes against the section before it, so decoding happens
     * before this filter runs).
     */
    public boolean[] acceptedMask(CoopMessages.Datagram datagram) {
        Objects.requireNonNull(datagram, "datagram");
        List<CoopMessages.DatagramSection> sections = datagram.sections();
        List<CoopMessages.DatagramSection> fresh = accept(datagram);
        boolean[] mask = new boolean[sections.size()];
        int cursor = 0;
        for (int i = 0; i < sections.size() && cursor < fresh.size(); i++) {
            // accept() returns the very instances it was given, in order, so identity is exact and a
            // duplicate section within one datagram cannot be double-counted.
            if (sections.get(i) == fresh.get(cursor)) {
                mask[i] = true;
                cursor++;
            }
        }
        return mask;
    }

    /** The high-water epoch for one stream, or {@link Long#MIN_VALUE} when it has none yet. */
    long watermarkFor(String senderId, CoopMessages.Type type) {
        Mark mark = watermarkByStream.get(new StreamKey(senderId, type));
        return mark == null ? Long.MIN_VALUE : mark.epoch;
    }

    /** Chunks already seen at the current high-water epoch of one stream; test read. */
    Set<Integer> chunksAtWatermark(String senderId, CoopMessages.Type type) {
        Mark mark = watermarkByStream.get(new StreamKey(senderId, type));
        return mark == null ? Set.of() : Set.copyOf(mark.chunksAtEpoch);
    }

    /** Clears all watermarks (session teardown). */
    public void reset() {
        watermarkByStream.clear();
        token = "";
    }
}
