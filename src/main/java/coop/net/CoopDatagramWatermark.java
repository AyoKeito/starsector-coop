package coop.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Receiver-side ordering guard for the UDP state streams (Phase 29 M1 wire prerequisite; the Phase
 * 20.1 epoch guard). Keeps a high-water epoch per {@code (senderId, type, chunk)} and returns only the
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
 * <p><b>Why the key includes the chunk (red-team C7).</b> A batch too large for one MTU-safe datagram
 * is split into chunk datagrams that all share one epoch. A single mark per {@code (senderId, type)}
 * therefore had to carry a set of "chunks already seen at this epoch" beside it — and that set only
 * ever suppressed duplicates; it never let a <em>redundancy</em> copy of chunk 3 recover a lost
 * chunk 3 once chunk 4 had raised the shared mark. Per-chunk marks are the same rule stated once:
 * each chunk is its own small stream, strictly-greater is the whole test, and the equal-epoch set is
 * gone rather than simplified.
 *
 * <p><b>Session scope.</b> The table resets when the datagram {@code token} changes: a new session
 * means every peer's epochs may restart from zero, and a watermark left at the old session's high
 * water would swallow the new session's whole stream.
 *
 * <p><b>Bounds (red-team A5/A7).</b> Every key component is attacker-influenced once a packet has
 * passed the session token, so all three are bounded: {@code chunk} by {@link CoopMessages} at parse,
 * the table itself by {@link #MAX_STREAMS} (eldest evicted), and the epoch by {@link #EPOCH_WINDOW} —
 * a section whose epoch is negative, or absurdly far above the mark it would replace, is treated as
 * stale rather than allowed to park the mark near {@link Long#MAX_VALUE} and wedge the stream for the
 * rest of the session.
 */
public final class CoopDatagramWatermark {

    /**
     * How far above the current mark an epoch may jump and still be believed. Epochs advance by one
     * per send, so at 10 Hz this is nearly three hours of stream — no legitimate sender comes near
     * it, and a single crafted packet can no longer make every later real one look stale.
     */
    static final long EPOCH_WINDOW = 100_000L;

    /**
     * Streams remembered at once. One sender running every type across every chunk is a few dozen;
     * the cap exists so a peer holding the session token cannot grow this map without limit by
     * varying the sender id it stamps.
     */
    static final int MAX_STREAMS = 512;

    private record StreamKey(String senderId, CoopMessages.Type type, int chunk) {
    }

    /** Insertion-ordered so the eldest stream is the one evicted when the cap is reached. */
    private final Map<StreamKey, Long> watermarkByStream = new LinkedHashMap<>();
    private String token = "";
    private long rejectedBadEpoch;

    /** The datagram's fresh sections, oldest first; empty when everything in it is stale. */
    public List<CoopMessages.DatagramSection> accept(CoopMessages.Datagram datagram) {
        Objects.requireNonNull(datagram, "datagram");
        if (!token.equals(datagram.token())) {
            watermarkByStream.clear();
            token = datagram.token();
        }
        List<CoopMessages.DatagramSection> fresh = new ArrayList<>(datagram.sections().size());
        // Sections are composed oldest first, so evaluating them in wire order is what lets a
        // redundancy copy of a lost section apply before the current one raises the watermark past it.
        for (CoopMessages.DatagramSection section : datagram.sections()) {
            if (acceptSection(datagram.senderId(), datagram.type(), section)) {
                fresh.add(section);
            }
        }
        return fresh;
    }

    private boolean acceptSection(String senderId, CoopMessages.Type type,
                                  CoopMessages.DatagramSection section) {
        long epoch = section.epoch();
        if (epoch < 0L) {
            rejectedBadEpoch++;
            return false;
        }
        StreamKey key = new StreamKey(senderId, type, section.chunk());
        Long mark = watermarkByStream.get(key);
        if (mark == null) {
            if (watermarkByStream.size() >= MAX_STREAMS) {
                // Evict the eldest rather than grow: at worst this forgives a stream that went quiet
                // long enough for MAX_STREAMS others to appear, which costs one duplicate apply.
                Iterator<Map.Entry<StreamKey, Long>> eldest = watermarkByStream.entrySet().iterator();
                if (eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                }
            }
            watermarkByStream.put(key, epoch);
            return true;
        }
        if (epoch <= mark) {
            return false;
        }
        if (epoch - mark > EPOCH_WINDOW) {
            // A far-future stamp: believing it parks the mark where every real send that follows looks
            // stale, which is a permanently dead stream bought with one packet.
            rejectedBadEpoch++;
            return false;
        }
        watermarkByStream.put(key, epoch);
        return true;
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

    /**
     * The high-water epoch for one stream's chunk 0, or {@link Long#MIN_VALUE} when it has none yet.
     * Chunk 0 is every unchunked stream, which is what the pump's tests read.
     */
    long watermarkFor(String senderId, CoopMessages.Type type) {
        return watermarkFor(senderId, type, 0);
    }

    /** The high-water epoch for one chunk of one stream, or {@link Long#MIN_VALUE} when unseen. */
    long watermarkFor(String senderId, CoopMessages.Type type, int chunk) {
        Long mark = watermarkByStream.get(new StreamKey(senderId, type, chunk));
        return mark == null ? Long.MIN_VALUE : mark;
    }

    /** Sections refused for a negative or out-of-window epoch (red-team A7); evidence, not control. */
    public long rejectedBadEpoch() {
        return rejectedBadEpoch;
    }

    /** Streams currently remembered; the {@link #MAX_STREAMS} bound is what this proves. */
    int trackedStreams() {
        return watermarkByStream.size();
    }

    /** Clears all watermarks (session teardown). */
    public void reset() {
        watermarkByStream.clear();
        token = "";
    }
}
