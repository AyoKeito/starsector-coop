package coop.net;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopDatagramWatermarkTest {

    private static final String SENDER_A = "aaaaaaaaaaaaaaaa";
    private static final String SENDER_B = "bbbbbbbbbbbbbbbb";

    private final CoopDatagramWatermark watermark = new CoopDatagramWatermark();

    private static CoopMessages.Datagram datagram(String token, CoopMessages.Type type,
                                                  CoopMessages.DatagramSection... sections) {
        return datagram(token, SENDER_A, type, sections);
    }

    private static CoopMessages.Datagram datagram(String token, String senderId, CoopMessages.Type type,
                                                  CoopMessages.DatagramSection... sections) {
        return new CoopMessages.Datagram(token, senderId, type, List.of(sections));
    }

    private static CoopMessages.DatagramSection section(long epoch, String body) {
        return new CoopMessages.DatagramSection(epoch, epoch * 100L, body);
    }

    private static CoopMessages.DatagramSection chunked(long epoch, int chunk, String body) {
        return new CoopMessages.DatagramSection(epoch, epoch * 100L, chunk, body);
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

    /**
     * Phase 20.1: epochs come from each sender's own stream clock and are not comparable across
     * senders. Keyed by type alone, the peer that started streaming first would park the watermark
     * above a second peer's epochs and swallow that peer's whole stream.
     */
    @Test
    void sendersTrackIndependentWatermarks() {
        watermark.accept(datagram("s", SENDER_A, CoopMessages.Type.FLEET_SNAPSHOT, section(5, "a5")));

        List<CoopMessages.DatagramSection> fresh = watermark.accept(
                datagram("s", SENDER_B, CoopMessages.Type.FLEET_SNAPSHOT, section(3, "b3")));

        assertEquals(1, fresh.size(), "sender A's epoch must not censor sender B's stream");
        assertEquals("b3", fresh.get(0).body());
    }

    @Test
    void oneSendersReorderDoesNotDisturbAnother() {
        watermark.accept(datagram("s", SENDER_A, CoopMessages.Type.FLEET_SNAPSHOT, section(4, "a4")));
        watermark.accept(datagram("s", SENDER_B, CoopMessages.Type.FLEET_SNAPSHOT, section(9, "b9")));

        assertTrue(watermark.accept(
                        datagram("s", SENDER_A, CoopMessages.Type.FLEET_SNAPSHOT, section(3, "a3"))).isEmpty(),
                "sender A's own reorder is still stale");
        assertEquals(1, watermark.accept(
                        datagram("s", SENDER_A, CoopMessages.Type.FLEET_SNAPSHOT, section(5, "a5"))).size(),
                "sender A's next tick must still pass under sender B's higher epoch");
    }

    @Test
    void newTokenResetsWatermarksForEverySender() {
        watermark.accept(datagram("s1", SENDER_A, CoopMessages.Type.NPC_FLEET_MOTION, section(50, "old-a")));
        watermark.accept(datagram("s1", SENDER_B, CoopMessages.Type.NPC_FLEET_MOTION, section(50, "old-b")));

        // A new session's peers restart their epochs; neither first datagram may be swallowed.
        List<CoopMessages.DatagramSection> freshA = watermark.accept(
                datagram("s2", SENDER_A, CoopMessages.Type.NPC_FLEET_MOTION, section(1, "new-a")));
        List<CoopMessages.DatagramSection> freshB = watermark.accept(
                datagram("s2", SENDER_B, CoopMessages.Type.NPC_FLEET_MOTION, section(1, "new-b")));

        assertEquals(1, freshA.size());
        assertEquals("new-a", freshA.get(0).body());
        assertEquals(1, freshB.size());
        assertEquals("new-b", freshB.get(0).body());
    }


    // ---- Phase 20 M4 chunks ----------------------------------------------------------------------

    @Test
    void everyChunkOfOneTickIsAcceptedDespiteSharingAnEpoch() {
        // Strictly-greater alone would take chunk 0 and swallow the rest of the batch.
        for (int chunk = 0; chunk < 4; chunk++) {
            List<CoopMessages.DatagramSection> fresh = watermark.accept(datagram("s",
                    CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, chunk, "c" + chunk)));
            assertEquals(1, fresh.size(), "chunk " + chunk);
            assertEquals("c" + chunk, fresh.get(0).body());
        }
        assertEquals(Set.of(0, 1, 2, 3),
                watermark.chunksAtWatermark(SENDER_A, CoopMessages.Type.NPC_FLEET_MOTION));
    }

    @Test
    void chunksMayArriveInAnyOrder() {
        assertEquals(1, watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 3, "c3"))).size());
        assertEquals(1, watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 0, "c0"))).size());
        assertEquals(1, watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 1, "c1"))).size());
    }

    @Test
    void aDuplicateChunkAtTheSameEpochIsDropped() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 2, "c2")));

        assertEquals(List.of(), watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 2, "c2-again"))));
    }

    @Test
    void aHigherEpochResetsTheSeenChunkSet() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 2, "c2")));

        assertEquals(1, watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION, chunked(8L, 2, "next-c2"))).size());
        assertEquals(Set.of(2),
                watermark.chunksAtWatermark(SENDER_A, CoopMessages.Type.NPC_FLEET_MOTION));
        // The old epoch is now stale for every chunk, seen or not.
        assertEquals(List.of(), watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 5, "late-c5"))));
    }

    @Test
    void aRedundancyCopyOfAnAlreadySeenChunkIsDroppedButItsCurrentSectionIsNot() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 1, "c1")));

        List<CoopMessages.DatagramSection> fresh = watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION,
                chunked(7L, 1, "c1"), chunked(8L, 1, "c1-next")));

        assertEquals(1, fresh.size());
        assertEquals("c1-next", fresh.get(0).body());
    }

    @Test
    void aRedundancyCopyCoveringALostChunkStillApplies() {
        List<CoopMessages.DatagramSection> fresh = watermark.accept(datagram("s",
                CoopMessages.Type.NPC_FLEET_MOTION,
                chunked(7L, 1, "lost-c1"), chunked(8L, 1, "c1-next")));

        assertEquals(2, fresh.size());
        assertEquals("lost-c1", fresh.get(0).body(), "oldest first, so the mirror sees both samples");
    }

    @Test
    void theAcceptedMaskNamesTheSectionsByIndex() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 1, "c1")));

        // Section 0 was already applied; section 1 is new. The pump needs the index, because section
        // 1's delta only decodes against section 0's body.
        boolean[] mask = watermark.acceptedMask(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION,
                chunked(7L, 1, "c1"), chunked(8L, 1, "c1-next")));

        assertArrayEquals(new boolean[] {false, true}, mask);
    }

    @Test
    void theAcceptedMaskIsAllFalseForAFullyStaleDatagram() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, chunked(9L, 0, "c0")));

        boolean[] mask = watermark.acceptedMask(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION,
                chunked(7L, 0, "old"), chunked(8L, 0, "older-current")));

        assertArrayEquals(new boolean[] {false, false}, mask);
        assertFalse(mask[0] || mask[1]);
    }

    @Test
    void chunksAreTrackedPerSenderAndType() {
        watermark.accept(datagram("s", SENDER_A, CoopMessages.Type.NPC_FLEET_MOTION,
                chunked(7L, 0, "a0")));

        assertEquals(1, watermark.accept(datagram("s", SENDER_B,
                CoopMessages.Type.NPC_FLEET_MOTION, chunked(7L, 0, "b0"))).size());
        assertEquals(1, watermark.accept(datagram("s", SENDER_A,
                CoopMessages.Type.FLEET_SNAPSHOT, chunked(7L, 0, "a-snap"))).size());
    }

    @Test
    void explicitResetClearsWatermarks() {
        watermark.accept(datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(50, "old")));
        watermark.reset();
        assertEquals(1, watermark.accept(
                datagram("s", CoopMessages.Type.NPC_FLEET_MOTION, section(1, "new"))).size());
    }
}
