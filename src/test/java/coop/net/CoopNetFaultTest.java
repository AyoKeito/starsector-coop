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

    // ---- arming ----------------------------------------------------------------------------------

    /**
     * The whole lifecycle of a delayed fault against a fake clock. The tester is a human at two game
     * windows, and the reason arming exists is that a fault which lands while they are still in a
     * menu proves nothing — so "armed" has to mean the traffic is genuinely untouched, not merely
     * labelled differently.
     */
    @Test
    void anArmedFaultTouchesNothingUntilItsStartInstant() {
        CoopNetFault fault = CoopNetFault.discard(START, 40, 5);

        assertEquals(START + 5_000L, fault.startsAtMillis());
        assertEquals(START + 45_000L, fault.endsAtMillis(),
                "the delay shifts the whole window; the outage is still the 40 s that was asked for");

        assertTrue(fault.armedAt(START));
        assertFalse(fault.activeAt(START));
        assertFalse(fault.shouldDiscardTcp(START), "an armed discard must not swallow a byte");
        assertFalse(fault.shouldDropDatagram(START, alwaysLow()));

        // The last millisecond of the delay is still the delay.
        assertTrue(fault.armedAt(START + 4_999L));
        assertFalse(fault.shouldDiscardTcp(START + 4_999L));

        assertFalse(fault.armedAt(START + 5_000L), "inclusive at the start instant, like expiry");
        assertTrue(fault.activeAt(START + 5_000L));
        assertTrue(fault.shouldDiscardTcp(START + 5_000L));
        assertTrue(fault.shouldDropDatagram(START + 5_000L, alwaysHigh()));

        assertTrue(fault.activeAt(START + 44_999L));
        assertTrue(fault.expiredAt(START + 45_000L));
        assertFalse(fault.activeAt(START + 45_000L));
        assertFalse(fault.shouldDiscardTcp(START + 45_000L));
    }

    /** With no delay a fault is running from the instant it is made; nothing about that changed. */
    @Test
    void aFaultWithNoDelayIsNeverArmed() {
        CoopNetFault fault = CoopNetFault.loss(START, 40, 50);

        assertFalse(fault.armedAt(START));
        assertTrue(fault.activeAt(START));
        assertEquals(START, fault.startsAtMillis());
        assertEquals(0L, fault.startsInSeconds(START));
    }

    /**
     * The countdown the HUD draws, at its boundaries. Rounded up, so it steps 5, 4, 3, 2, 1 and the
     * line changes shape rather than ever showing a zero: at 4.2 s away it reads 5, at exactly 4.0 s
     * away it reads 4.
     */
    @Test
    void theStartCountdownRoundsUpAndReachesZeroOnlyOnceTheFaultIsRunning() {
        CoopNetFault fault = CoopNetFault.discard(START, 40, 5);

        assertEquals(5L, fault.startsInSeconds(START));
        assertEquals(5L, fault.startsInSeconds(START + 800L), "4.2 s away is still a 5 on screen");
        assertEquals(4L, fault.startsInSeconds(START + 1_000L), "exactly 4.0 s away reads 4");
        assertEquals(3L, fault.startsInSeconds(START + 2_000L));
        assertEquals(2L, fault.startsInSeconds(START + 3_000L));
        assertEquals(1L, fault.startsInSeconds(START + 4_000L));
        assertEquals(1L, fault.startsInSeconds(START + 4_999L), "still a whole second to wait");
        assertEquals(0L, fault.startsInSeconds(START + 5_000L));
        assertEquals(0L, fault.startsInSeconds(START + 500_000L), "never negative");
    }

    /**
     * While armed, the remaining outage is the duration that was requested — not the distance to the
     * end of the window. Reporting 45 for a 40 s fault armed 5 s out would claim a longer outage than
     * anybody asked for, and the HUD's active line reads straight off this.
     */
    @Test
    void remainingSecondsIgnoresTheDelayWhileArmed() {
        CoopNetFault fault = CoopNetFault.discard(START, 40, 5);

        assertEquals(40L, fault.remainingSeconds(START));
        assertEquals(40L, fault.remainingSeconds(START + 4_999L));
        assertEquals(40L, fault.remainingSeconds(START + 5_000L), "the first second of the outage");
        assertEquals(39L, fault.remainingSeconds(START + 6_000L));
        assertEquals(0L, fault.remainingSeconds(START + 45_000L));
    }

    /**
     * The flip is latched, because the caller logs it from a per-poll step: without the latch the
     * started line would be written on every poll for the rest of the outage.
     */
    @Test
    void theStartIsAnnouncedExactlyOnce() {
        CoopNetFault fault = CoopNetFault.discard(START, 40, 5);

        assertFalse(fault.announceStart(START), "nothing to announce while it is still armed");
        assertFalse(fault.announceStart(START + 4_999L));

        assertTrue(fault.announceStart(START + 5_000L), "the flip");
        assertFalse(fault.announceStart(START + 5_001L), "and never again");
        assertFalse(fault.announceStart(START + 20_000L));
    }

    /** A fault that starts immediately still announces once, so the two paths log the same way. */
    @Test
    void anUndelayedFaultAnnouncesOnceToo() {
        CoopNetFault fault = CoopNetFault.loss(START, 10, 25);

        assertTrue(fault.announceStart(START));
        assertFalse(fault.announceStart(START));
    }

    @Test
    void theArmedLogLineNamesTheWaitAndTheDurationItIsWaitingFor() {
        assertEquals("discard armed, starts in 5 s for 40 s",
                CoopNetFault.discard(START, 40, 5).describeArmed(START));
        assertEquals("loss armed, starts in 3 s for 15 s (30% of inbound datagrams)",
                CoopNetFault.loss(START, 15, 30, 3).describeArmed(START));
        assertEquals("discard armed, starts in 2 s for 40 s",
                CoopNetFault.discard(START, 40, 5).describeArmed(START + 3_000L),
                "the line is rendered against the clock, so a late log still reads truthfully");
    }

    /** Refused, not clamped, for the same reason the duration is. */
    @Test
    void aDelayOutsideTheCapIsRefused() {
        assertEquals(60, CoopNetFault.MAX_DELAY_SECONDS);

        assertThrows(IllegalArgumentException.class, () -> CoopNetFault.discard(START, 40, -1));
        assertThrows(IllegalArgumentException.class,
                () -> CoopNetFault.discard(START, 40, CoopNetFault.MAX_DELAY_SECONDS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> CoopNetFault.loss(START, 40, 50, CoopNetFault.MAX_DELAY_SECONDS + 1));

        assertEquals(START, CoopNetFault.discard(START, 40, 0).startsAtMillis());
        assertEquals(START + 60_000L,
                CoopNetFault.discard(START, 40, CoopNetFault.MAX_DELAY_SECONDS).startsAtMillis());
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
