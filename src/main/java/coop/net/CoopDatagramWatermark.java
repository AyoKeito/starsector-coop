package coop.net;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Receiver-side ordering guard for the UDP state streams (Phase 29 M1 wire prerequisite; the Phase
 * 20.1 epoch guard pulled forward). Keeps a per-type high-water epoch and returns only the sections
 * of a datagram that are strictly newer, in wire order (oldest first). A reordered datagram — whose
 * sections all sit at or below the watermark — yields nothing instead of applying a stale position,
 * and a redundant section ({@link CoopDatagramRedundancy}) that already arrived is dropped the same
 * way while a redundant section covering a lost packet passes.
 *
 * <p>Watermarks are keyed to the datagram's {@code sessionId}: a new session means the peer's
 * {@link CoopStreamClock} epochs may restart, so the first datagram of an unfamiliar session resets
 * the table. Phase 20.1 later generalizes the key to {@code (senderId, type)} for multi-guest.
 */
public final class CoopDatagramWatermark {

    private final Map<CoopMessages.Type, Long> watermarkByType = new EnumMap<>(CoopMessages.Type.class);
    private String sessionId = "";

    /** The datagram's fresh sections, oldest first; empty when everything in it is stale. */
    public List<CoopMessages.DatagramSection> accept(CoopMessages.Datagram datagram) {
        Objects.requireNonNull(datagram, "datagram");
        if (!sessionId.equals(datagram.sessionId())) {
            watermarkByType.clear();
            sessionId = datagram.sessionId();
        }
        long watermark = watermarkByType.getOrDefault(datagram.type(), Long.MIN_VALUE);
        List<CoopMessages.DatagramSection> fresh = new ArrayList<>(datagram.sections().size());
        long highest = watermark;
        for (CoopMessages.DatagramSection section : datagram.sections()) {
            if (section.epoch() > watermark) {
                fresh.add(section);
                highest = Math.max(highest, section.epoch());
            }
        }
        if (highest > watermark) {
            watermarkByType.put(datagram.type(), highest);
        }
        return fresh;
    }

    /** Clears all watermarks (session teardown). */
    public void reset() {
        watermarkByType.clear();
        sessionId = "";
    }
}
