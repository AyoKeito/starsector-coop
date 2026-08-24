package coop.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopDatagramWatermarkTest {

    private final CoopDatagramWatermark watermark = new CoopDatagramWatermark();

    private static CoopMessages.Datagram datagram(String sessionId, CoopMessages.Type type,
                                                  CoopMessages.DatagramSection... sections) {
        return new CoopMessages.Datagram(sessionId, type, List.of(sections));
    }

    private static CoopMessages.DatagramSection section(long epoch, String body) {
        return new CoopMessages.DatagramSection(epoch, epoch * 100L, body);
    }

    @Test
    void reorderedDatagramIsDroppedEntirely() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(2, "b2")));
        List<CoopMessages.DatagramSection> fresh = watermark.accept(
                datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "b1")));
        assertTrue(fresh.isEmpty(), "a stale position must never be applied");
    }

    @Test
    void redundantSectionAlreadySeenIsDroppedNewSectionPasses() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "b1")));
        // Next datagram: redundant copy of epoch 1 plus the new epoch 2.
        List<CoopMessages.DatagramSection> fresh = watermark.accept(datagram(
                "s", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "b1"), section(2, "b2")));
        assertEquals(1, fresh.size());
        assertEquals(2L, fresh.get(0).epoch());
    }

    @Test
    void redundantSectionCoveringALostPacketPasses() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "b1")));
        // The epoch-2 datagram was lost; epoch 3 arrives carrying 2 as its redundant section.
        List<CoopMessages.DatagramSection> fresh = watermark.accept(datagram(
                "s", CoopMessages.Type.NPC_FLEET_MOTION, section(2, "b2"), section(3, "b3")));
        assertEquals(2, fresh.size());
        assertEquals(2L, fresh.get(0).epoch());
        assertEquals(3L, fresh.get(1).epoch());
    }

    @Test
    void duplicateDatagramIsDropped() {
        CoopMessages.Datagram original =
                datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "b1"));
        watermark.accept(original);
        assertTrue(watermark.accept(original).isEmpty());
    }

    @Test
    void typesTrackIndependentWatermarks() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(5, "m5")));
        // FLEET_SNAPSHOT epochs come from the same sender counter but the type watermark is its own.
        List<CoopMessages.DatagramSection> fresh = watermark.accept(
                datagram("s", CoopMessages.Type.FLEET_SNAPSHOT, section(2, "f2")));
        assertEquals(1, fresh.size());
    }

    @Test
    void newSessionIdResetsWatermarks() {
        watermark.accept(datagram("s1", CoopMessages.Type.NPC_FLEET_MOTION, section(50, "old")));
        // A new session's peer restarts its epochs; its first datagram must not be swallowed.
        List<CoopMessages.DatagramSection> fresh = watermark.accept(
                datagram("s2", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "new")));
        assertEquals(1, fresh.size());
        assertEquals("new", fresh.get(0).body());
    }

    @Test
    void explicitResetClearsWatermarks() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(50, "old")));
        watermark.reset();
        assertEquals(1, watermark.accept(
                datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "new"))).size());
    }
}
