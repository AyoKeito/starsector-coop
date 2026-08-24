package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMotionSpeedProbeTest {

    private final CoopMotionSpeedProbe probe = new CoopMotionSpeedProbe();

    @Test
    void firstCallArmsTheWindowWithoutReporting() {
        probe.recordRendered(10.0);
        assertNull(probe.maybeReport(1_000L));
    }

    @Test
    void reportsRatioAfterTheWindowAndResets() {
        assertNull(probe.maybeReport(1_000L));
        probe.recordRendered(99.0);
        probe.recordAuthority(100.0);
        String report = probe.maybeReport(1_000L + CoopMotionSpeedProbe.WINDOW_MILLIS);
        assertTrue(report.contains("ratio=0.990"), report);
        assertTrue(report.contains("rendered=99su(1 moves)"), report);
        // The window reset: the next report only sees what came after it.
        probe.recordRendered(50.0);
        probe.recordAuthority(50.0);
        String next = probe.maybeReport(1_000L + 2 * CoopMotionSpeedProbe.WINDOW_MILLIS);
        assertTrue(next.contains("ratio=1.000"), next);
    }

    @Test
    void anEmptyWindowStaysSilent() {
        assertNull(probe.maybeReport(1_000L));
        assertNull(probe.maybeReport(1_000L + CoopMotionSpeedProbe.WINDOW_MILLIS));
    }

    @Test
    void zeroAuthorityDistanceReportsNoRatioInsteadOfDividing() {
        assertNull(probe.maybeReport(1_000L));
        probe.recordRendered(10.0);
        String report = probe.maybeReport(1_000L + CoopMotionSpeedProbe.WINDOW_MILLIS);
        assertTrue(report.contains("ratio=n/a"), report);
    }

    @Test
    void nonPositiveDistancesAreIgnored() {
        assertNull(probe.maybeReport(1_000L));
        probe.recordRendered(0.0);
        probe.recordAuthority(-5.0);
        assertNull(probe.maybeReport(1_000L + CoopMotionSpeedProbe.WINDOW_MILLIS));
    }

    @Test
    void resetDropsTheOpenWindow() {
        assertNull(probe.maybeReport(1_000L));
        probe.recordRendered(10.0);
        probe.recordAuthority(10.0);
        probe.reset();
        // Re-armed: the first call after reset only starts a window again.
        assertNull(probe.maybeReport(1_000L + CoopMotionSpeedProbe.WINDOW_MILLIS));
        assertNull(probe.maybeReport(1_000L + 2 * CoopMotionSpeedProbe.WINDOW_MILLIS));
    }
}
