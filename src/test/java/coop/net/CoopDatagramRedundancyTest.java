package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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


    // ---- Phase 20 M4 baseline composition ---------------------------------------------------------

    @Test
    void aBaselineComposedDatagramCarriesTheFullPreviousThenTheDelta() {
        String encoded = CoopDatagramRedundancy.composeWithBaseline(TOKEN, SENDER,
                CoopMessages.Type.NPC_FLEET_MOTION, 4L, 400L, 2, "F\nfull-previous",
                5L, 500L, "D\ndelta-current");

        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals(2, datagram.sections().size());
        assertEquals(4L, datagram.sections().get(0).epoch());
        assertEquals(400L, datagram.sections().get(0).sentGameTimeMillis());
        assertEquals("F\nfull-previous", datagram.sections().get(0).body());
        assertEquals(5L, datagram.sections().get(1).epoch());
        assertEquals("D\ndelta-current", datagram.sections().get(1).body());
        assertEquals(2, datagram.sections().get(0).chunk());
        assertEquals(2, datagram.sections().get(1).chunk(),
                "both sections describe the same slice of the batch");
    }

    @Test
    void theFirstSendOfAChunkHasNoBaselineSection() {
        String encoded = CoopDatagramRedundancy.composeWithBaseline(TOKEN, SENDER,
                CoopMessages.Type.NPC_FLEET_MOTION, 0L, 0L, 3, null, 5L, 500L, "D\ndelta-current");

        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals(1, datagram.sections().size());
        assertEquals(5L, datagram.sections().get(0).epoch());
        assertEquals(3, datagram.sections().get(0).chunk());
    }

    @Test
    void theSizeHelperMatchesWhatComposeActuallyProduces() {
        // The chunk packer sizes candidates arithmetically instead of composing; if the two ever
        // disagree the budget silently stops being a budget.
        String encoded = CoopDatagramRedundancy.composeWithBaseline(TOKEN, SENDER,
                CoopMessages.Type.NPC_FLEET_MOTION, 4L, 400L, 12, "F\nfull",
                5L, 500L, "D\ndelta with a é in it");
        int predicted = CoopMessages.datagramBytes(TOKEN, SENDER,
                CoopMessages.Type.NPC_FLEET_MOTION,
                CoopMessages.parseDatagram(encoded).sections());

        assertEquals(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, predicted);
        assertTrue(predicted > CoopMessages.utf8Length("F\nfull"));
    }

    @Test
    void previousSectionsAreTrackedPerChunkNotPerType() {
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, 0, "c0-t1");
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, 1, "c1-t1");

        String encoded = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.NPC_FLEET_MOTION, 2L, 200L, 0, "c0-t2");

        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals("c0-t1", datagram.sections().get(0).body(),
                "chunk 0's redundant section must be chunk 0's previous send");
    }

    // ---- Phase 29 M2: the depth escape hatch -----------------------------------------------------

    @Test
    void theDefaultDepthIsOne() {
        assertEquals(CoopDatagramRedundancy.DEFAULT_DEPTH, redundancy.depth());
    }

    @Test
    void depthTwoComposesThreeSectionsOldestFirst() {
        redundancy.setDepth(2);

        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 1L, 100L, "batch-1");
        assertEquals(2, CoopMessages.parseDatagram(redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 2L, 200L, "batch-2"))
                .sections().size(), "the second send has only one previous to carry");
        String encoded = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 3L, 300L, "batch-3");

        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals(3, datagram.sections().size());
        assertEquals(1L, datagram.sections().get(0).epoch());
        assertEquals("batch-1", datagram.sections().get(0).body());
        assertEquals(2L, datagram.sections().get(1).epoch());
        assertEquals("batch-2", datagram.sections().get(1).body());
        assertEquals(3L, datagram.sections().get(2).epoch());
        assertEquals("batch-3", datagram.sections().get(2).body());
    }

    @Test
    void depthNeverExceedsTwoAndNeverDropsBelowOne() {
        redundancy.setDepth(9);
        assertEquals(CoopDatagramRedundancy.MAX_DEPTH, redundancy.depth());
        redundancy.setDepth(0);
        assertEquals(CoopDatagramRedundancy.DEFAULT_DEPTH, redundancy.depth());
        redundancy.setDepth(-3);
        assertEquals(CoopDatagramRedundancy.DEFAULT_DEPTH, redundancy.depth());
    }

    @Test
    void loweringTheDepthDropsTheSurplusOnTheVeryNextDatagram() {
        redundancy.setDepth(2);
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 1L, 100L, "batch-1");
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 2L, 200L, "batch-2");

        redundancy.setDepth(1);
        String encoded = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 3L, 300L, "batch-3");

        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals(2, datagram.sections().size());
        assertEquals("batch-2", datagram.sections().get(0).body(), "the eldest is the one dropped");
    }

    @Test
    void theWatermarkAcceptsThreeSectionsAndDedupsTheOnesItHasSeen() {
        redundancy.setDepth(2);
        CoopDatagramWatermark watermark = new CoopDatagramWatermark();

        String first = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 1L, 100L, "batch-1");
        String second = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 2L, 200L, "batch-2");
        String third = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 3L, 300L, "batch-3");

        assertEquals(1, watermark.accept(CoopMessages.parseDatagram(first)).size());
        assertEquals(1, watermark.accept(CoopMessages.parseDatagram(second)).size(),
                "the carried copy of batch-1 has already been applied");
        assertEquals(1, watermark.accept(CoopMessages.parseDatagram(third)).size());
        assertEquals(3L, watermark.watermarkFor(SENDER, CoopMessages.Type.FLEET_SNAPSHOT));
    }

    @Test
    void aDepthTwoDatagramRecoversTwoConsecutiveLostSends() {
        redundancy.setDepth(2);
        CoopDatagramWatermark watermark = new CoopDatagramWatermark();

        String first = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 1L, 100L, "batch-1");
        // Sends 2 and 3 are composed but never delivered - the whole point of depth 2.
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 2L, 200L, "batch-2");
        redundancy.compose(TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 3L, 300L, "batch-3");
        String fourth = redundancy.compose(
                TOKEN, SENDER, CoopMessages.Type.FLEET_SNAPSHOT, 4L, 400L, "batch-4");

        watermark.accept(CoopMessages.parseDatagram(first));
        java.util.List<CoopMessages.DatagramSection> fresh =
                watermark.accept(CoopMessages.parseDatagram(fourth));

        assertEquals(3, fresh.size(), "both lost sends plus the current one");
        assertEquals("batch-2", fresh.get(0).body());
        assertEquals("batch-3", fresh.get(1).body());
        assertEquals("batch-4", fresh.get(2).body());
    }

    @Test
    void resetPutsTheDepthBack() {
        redundancy.setDepth(2);
        redundancy.reset();
        assertEquals(CoopDatagramRedundancy.DEFAULT_DEPTH, redundancy.depth());
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
