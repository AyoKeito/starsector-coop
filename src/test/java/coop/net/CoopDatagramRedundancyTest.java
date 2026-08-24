package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopDatagramRedundancyTest {

    private final CoopDatagramRedundancy redundancy = new CoopDatagramRedundancy();

    @Test
    void firstSendCarriesOneSection() {
        String encoded = redundancy.compose(
                "session-a", CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1");
        CoopMessages.Datagram datagram = CoopMessages.parseDatagram(encoded);
        assertEquals(1, datagram.sections().size());
        assertEquals("batch-1", datagram.sections().get(0).body());
    }

    @Test
    void secondSendCarriesPreviousSectionOldestFirst() {
        redundancy.compose("session-a", CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1");
        String encoded = redundancy.compose(
                "session-a", CoopMessages.Type.NPC_FLEET_MOTION, 2L, 200L, "batch-2");
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
        redundancy.compose("session-a", CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "motion-1");
        String snapshot = redundancy.compose(
                "session-a", CoopMessages.Type.FLEET_SNAPSHOT, 2L, 200L, "snapshot-1");
        // The snapshot datagram must not have inherited the motion stream's previous section.
        assertEquals(1, CoopMessages.parseDatagram(snapshot).sections().size());
    }

    @Test
    void resetForgetsHeldSections() {
        redundancy.compose("session-a", CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1");
        redundancy.reset();
        String encoded = redundancy.compose(
                "session-b", CoopMessages.Type.NPC_FLEET_MOTION, 1L, 100L, "batch-1b");
        // A stale section from the previous session must not leak into the new one.
        assertEquals(1, CoopMessages.parseDatagram(encoded).sections().size());
    }
}
