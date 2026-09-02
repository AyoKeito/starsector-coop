package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopDatagramRedundancyTest {

    private static final String TOKEN = "0123456789abcdef";
    private static final String SENDER = "fedcba9876543210";

    private final CoopDatagramRedundancy redundancy = new CoopDatagramRedundancy();

    @Test
    void firstSendCarriesOneSection() {
        String encoded = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1");
        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals(1, datagram.sections().size());
        assertEquals("batch-1", datagram.sections().get(0).body());
    }

    @Test
    void composedDatagramCarriesTokenAndSenderId() {
        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1"));
        assertEquals(TOKEN, datagram.token());
        assertEquals(SENDER, datagram.senderId());
    }

    @Test
    void secondSendCarriesPreviousSectionOldestFirst() {
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1");
        String encoded = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 2L, 200L, "batch-2");
        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals(2, datagram.sections().size());
        assertEquals(1L, datagram.sections().get(0).epoch());
        assertEquals("batch-1", datagram.sections().get(0).body());
        assertEquals(2L, datagram.sections().get(1).epoch());
        assertEquals(200L, datagram.sections().get(1).sentGameTimeMillis());
        assertEquals("batch-2", datagram.sections().get(1).body());
    }

    @Test
    void typesKeepIndependentPreviousSections() {
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "motion-1");
        String snapshot = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 2L, 200L, "snapshot-1");
        // The snapshot datagram must not have inherited the motion stream's previous section.
        assertEquals(1, CoopMessages.parseDatagram(snapshot).sections().size());
    }

    /**
     * Phase 20 M4 splits a batch across chunks that share an epoch. Chunk 1's redundant section must
     * be chunk 1's own previous send — pairing it with chunk 0's would hand the receiver a body it
     * then applies to the wrong slice of the batch.
     */
    @Test
    void chunksKeepIndependentPreviousSections() {
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, 0, "c0-e1");
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, 1, "c1-e1");

        CoopMessages.Datagram chunkOne = CoopMessages.parseDatagram(redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 2L, 200L, 1, "c1-e2"));

        assertEquals(2, chunkOne.sections().size());
        assertEquals("c1-e1", chunkOne.sections().get(0).body(), "chunk 1 inherited another chunk's section");
        assertEquals(1, chunkOne.sections().get(0).chunk());
        assertEquals("c1-e2", chunkOne.sections().get(1).body());
        assertEquals(1, chunkOne.sections().get(1).chunk());
    }

    @Test
    void resetForgetsHeldSections() {
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1");
        redundancy.reset();
        String encoded = redundancy.compose(
                "aaaaaaaaaaaaaaaa", SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1b");
        // A stale section from the previous session must not leak into the new one.
        assertEquals(1, CoopMessages.parseDatagram(encoded).sections().size());
    }
}
