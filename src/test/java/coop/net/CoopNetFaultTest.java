package coop.net;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision logic behind the {@code netfault} bridge verb, on its own: modes, expiry, loss
 * sampling and the counters the status block reports. No sockets — the transport wiring is pinned by
 * {@link CoopNetServiceTest}.
 */
class CoopNetFaultTest {

    private static final long START = 1_000_000L;

    // ---- modes -----------------------------------------------------------------------------------

    @Test
    void discardSwallowsTcpAndUdpAlike() {
        CoopNetFault fault = CoopNetFault.discard(START, 10);

        assertEquals(CoopNetFault.Mode.DISCARD, fault.mode());
        assertTrue(fault.shouldDiscardTcp(START));
        assertTrue(fault.shouldDropDatagram(START, alwaysHigh()),
                "discard drops every datagram, transport traffic included, whatever the draw says");
    }

    /**
     * The whole point of the second mode: TCP keeps flowing, so a session degrades rather than dying,
     * which is the shape of a lossy link rather than an outage.
     */
    @Test
    void lossLeavesTcpAlone() {
        CoopNetFault fault = CoopNetFault.loss(START, 10, 50);

        assertEquals(CoopNetFault.Mode.LOSS, fault.mode());
        assertFalse(fault.shouldDiscardTcp(START));
    }

    // ---- expiry ----------------------------------------------------------------------------------

    @Test
    void aFaultIsOverAtItsDeadlineAndStopsActingBeforeAnybodyClearsIt() {
        CoopNetFault fault = CoopNetFault.discard(START, 40);

        assertEquals(START + 40_000L, fault.endsAtMillis());
        assertFalse(fault.expiredAt(START + 39_999L));
        assertTrue(fault.shouldDiscardTcp(START + 39_999L));

        assertTrue(fault.expiredAt(START + 40_000L));
        assertFalse(fault.shouldDiscardTcp(START + 40_000L),
                "an expired fault must stop discarding on its own, not wait to be cleared");
        assertFalse(fault.shouldDropDatagram(START + 40_000L, alwaysLow()));
    }

    @Test
    void remainingSecondsCountsDownAndFloorsAtZero() {
        CoopNetFault fault = CoopNetFault.loss(START, 40, 25);

        assertEquals(40L, fault.remainingSeconds(START), "a 40 s fault reads 40 the instant it starts");
        // Rounded up, so a fault with any time left never reads as finished.
        assertEquals(40L, fault.remainingSeconds(START + 500L));
        assertEquals(39L, fault.remainingSeconds(START + 1_000L));
        assertEquals(1L, fault.remainingSeconds(START + 39_500L));
        assertEquals(0L, fault.remainingSeconds(START + 40_000L));
        assertEquals(0L, fault.remainingSeconds(START + 500_000L), "never negative");
    }

    // ---- loss sampling ---------------------------------------------------------------------------

    /** 100 % is the setting a test uses to prove "nothing gets through"; it must never let one slip. */
    @Test
    void totalLossDropsEveryDatagram() {
        CoopNetFault fault = CoopNetFault.loss(START, 10, 100);
        Random random = new Random(1234L);

        for (int i = 0; i < 500; i++) {
            assertTrue(fault.shouldDropDatagram(START, random), "datagram " + i + " survived 100% loss");
        }
    }

    /**
     * A seed, not a statistic: the same seed has to produce the same drops on every run, or a smoke
     * that reproduces a bug once cannot reproduce it twice.
     */
    @Test
    void aSeededHalfLossDropsTheSameDatagramsEveryRun() {
        int firstRun = dropsOutOf(200, 50, 987_654L);
        int secondRun = dropsOutOf(200, 50, 987_654L);

        assertEquals(firstRun, secondRun, "the same seed must decide the same way twice");
        assertTrue(firstRun > 60 && firstRun < 140,
                "50% of 200 should land nowhere near either extreme, got " + firstRun);
    }

    @Test
    void oneSurvivesAtOnePercentAndTheDrawIsTheOnlyThingConsulted() {
        CoopNetFault fault = CoopNetFault.loss(START, 10, 1);

        assertTrue(fault.shouldDropDatagram(START, fixedDraw(0)));
        assertFalse(fault.shouldDropDatagram(START, fixedDraw(1)));
    }

    // ---- counters --------------------------------------------------------------------------------

    @Test
    void countersAccumulateAndRenderAsTheEndOfFaultLogLine() {
        CoopNetFault fault = CoopNetFault.discard(START, 10);

        fault.noteDiscardedBytes(12_000L);
        fault.noteDiscardedBytes(345L);
        fault.noteDiscardedBytes(0L);
        fault.noteDiscardedBytes(-5L);
        fault.noteDroppedDatagram();
        fault.noteDroppedDatagram();

        assertEquals(12_345L, fault.discardedBytes(), "a non-positive read is not a discarded byte");
        assertEquals(2L, fault.droppedDatagrams());
        assertEquals("discarded 12345 bytes, dropped 2 datagrams", fault.describeCounters());
    }

    @Test
    void theStartLogLineNamesTheModeAndTheDuration() {
        assertEquals("discard for 40 s", CoopNetFault.discard(START, 40).describe());
        assertEquals("loss for 15 s (30% of inbound datagrams)",
                CoopNetFault.loss(START, 15, 30).describe());
        assertEquals("discard", CoopNetFault.Mode.DISCARD.wireName());
        assertEquals("loss", CoopNetFault.Mode.LOSS.wireName());
    }

    // ---- caps ------------------------------------------------------------------------------------

    /**
     * Refused, not clamped. A clamped duration would answer a different question than the one asked
     * and the caller would never know which fault it was actually watching.
     */
    @Test
    void aDurationOutsideTheCapIsRefused() {
        assertEquals(180, CoopNetFault.MAX_SECONDS);

        assertThrows(IllegalArgumentException.class, () -> CoopNetFault.discard(START, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopNetFault.discard(START, -1));
        assertThrows(IllegalArgumentException.class,
                () -> CoopNetFault.discard(START, CoopNetFault.MAX_SECONDS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> CoopNetFault.loss(START, CoopNetFault.MAX_SECONDS + 1, 50));

        assertEquals(CoopNetFault.MAX_SECONDS,
                CoopNetFault.discard(START, CoopNetFault.MAX_SECONDS).seconds());
        assertEquals(1, CoopNetFault.discard(START, 1).seconds());
    }

    @Test
    void aLossFractionOutsideOneToAHundredIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CoopNetFault.loss(START, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopNetFault.loss(START, 10, 101));
        assertThrows(IllegalArgumentException.class, () -> CoopNetFault.loss(START, 10, -1));

        assertEquals(100, CoopNetFault.loss(START, 10, 100).lossPercent());
        assertEquals(1, CoopNetFault.loss(START, 10, 1).lossPercent());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static int dropsOutOf(int datagrams, int lossPercent, long seed) {
        CoopNetFault fault = CoopNetFault.loss(START, 60, lossPercent);
        Random random = new Random(seed);
        int dropped = 0;
        for (int i = 0; i < datagrams; i++) {
            if (fault.shouldDropDatagram(START, random)) {
                fault.noteDroppedDatagram();
                dropped++;
            }
        }
        assertEquals(dropped, fault.droppedDatagrams());
        return dropped;
    }

    private static Random fixedDraw(int value) {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }

    private static Random alwaysHigh() {
        return fixedDraw(99);
    }

    private static Random alwaysLow() {
        return fixedDraw(0);
    }
}
