package coop.debug;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.testing.ProxyDefaults;
import coop.time.CoopClockReconciler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopDebugClockAlignmentTest {
    private static final long HOST = -55_644_500_923_000L;
    private static final long GUEST = -55_644_499_345_400L;

    @Test
    void alignsTheLowerPausedClockExactlyEvenInsideTheReconcilerDeadZone() {
        FakePort port = new FakePort(HOST);
        CoopDebugClockAlignment.Result result = CoopDebugClockAlignment.align(port, true, GUEST);

        assertEquals(HOST, result.beforeTimestamp());
        assertEquals(GUEST, result.afterTimestamp());
        assertTrue(result.advanced());
        assertEquals(List.of(GUEST), port.writes);
    }

    @Test
    void anAlreadyEqualTimestampIsAnIdempotentNoOp() {
        FakePort port = new FakePort(GUEST);
        CoopDebugClockAlignment.Result result = CoopDebugClockAlignment.align(port, true, GUEST);

        assertEquals(GUEST, result.beforeTimestamp());
        assertEquals(GUEST, result.afterTimestamp());
        assertFalse(result.advanced());
        assertTrue(port.writes.isEmpty());
    }

    @Test
    void refusesUnpausedAccessBeforeReadingOrWritingTheClock() {
        FakePort port = new FakePort(HOST);
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align(port, false, GUEST));
        assertEquals(0, port.reads);
        assertTrue(port.writes.isEmpty());
    }

    @Test
    void refusesBackwardOversizedAndOverflowingTargetsWithoutAWrite() {
        FakePort port = new FakePort(GUEST);
        assertThrows(IllegalArgumentException.class,
                () -> CoopDebugClockAlignment.align(port, true, HOST));
        assertThrows(IllegalArgumentException.class,
                () -> CoopDebugClockAlignment.align(port, true,
                        GUEST + CoopDebugClockAlignment.MAX_FORWARD_MILLIS + 1));
        FakePort extreme = new FakePort(Long.MIN_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> CoopDebugClockAlignment.align(extreme, true, Long.MAX_VALUE));
        assertTrue(port.writes.isEmpty());
        assertTrue(extreme.writes.isEmpty());
    }

    @Test
    void allowsExactlyOneGameDayForward() {
        FakePort port = new FakePort(HOST);
        long target = HOST + CoopDebugClockAlignment.MAX_FORWARD_MILLIS;
        assertEquals(target, CoopDebugClockAlignment.align(port, true, target).afterTimestamp());
    }

    @Test
    void clockReadWriteAndReadbackFailuresAreExplicit() {
        FakePort cannotRead = new FakePort(HOST);
        cannotRead.readFailure = true;
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align(cannotRead, true, GUEST));
        assertTrue(cannotRead.writes.isEmpty());

        FakePort cannotWrite = new FakePort(HOST);
        cannotWrite.writeFailure = true;
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align(cannotWrite, true, GUEST));

        FakePort ignoresWrite = new FakePort(HOST);
        ignoresWrite.ignoreWrite = true;
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align(ignoresWrite, true, GUEST));
    }

    @Test
    void nativePortWritesTheLiveCalendarAndInheritedTimestampCache() {
        DerivedClock clock = new DerivedClock(HOST);
        CoopDebugClockAlignment.Result result = CoopDebugClockAlignment.align(sector(clock, true), GUEST);

        assertEquals(GUEST, clock.getTimestamp());
        assertEquals(GUEST, clock.getCal().getTimeInMillis());
        assertEquals(GUEST, result.afterTimestamp());
    }

    @Test
    void missingNativeTimestampFieldFailsBeforeMutatingTheCalendar() {
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTimeInMillis(HOST);
        CampaignClockAPI clock = (CampaignClockAPI) Proxy.newProxyInstance(
                CampaignClockAPI.class.getClassLoader(), new Class<?>[]{CampaignClockAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCal" -> calendar;
                    case "getTimestamp" -> HOST;
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align(sector(clock, true), GUEST));
        assertEquals(HOST, calendar.getTimeInMillis());
    }

    @Test
    void nativeEntryRefusesAnUnpausedOrAbsentCampaign() {
        DerivedClock clock = new DerivedClock(HOST);
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align(sector(clock, false), GUEST));
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align((SectorAPI) null, GUEST));
        assertThrows(IllegalStateException.class,
                () -> CoopDebugClockAlignment.align(sector(null, true), GUEST));
        assertEquals(HOST, clock.getTimestamp());
        assertEquals(HOST, clock.getCal().getTimeInMillis());
    }

    private static SectorAPI sector(CampaignClockAPI clock, boolean paused) {
        return (SectorAPI) Proxy.newProxyInstance(SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getClock" -> clock;
                    case "isPaused" -> paused;
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
    }

    private static final class FakePort implements CoopClockReconciler.ClockPort {
        long timestamp;
        int reads;
        boolean readFailure, writeFailure, ignoreWrite;
        final List<Long> writes = new ArrayList<>();

        FakePort(long timestamp) { this.timestamp = timestamp; }

        @Override public long getTimestamp() {
            reads++;
            if (readFailure) throw new IllegalStateException("read failed");
            return timestamp;
        }

        @Override public void setTimestamp(long value) {
            if (writeFailure) throw new IllegalStateException("write failed");
            writes.add(value);
            if (!ignoreWrite) timestamp = value;
        }
    }

    private static class BaseClock implements CampaignClockAPI {
        private long timestamp;
        private final GregorianCalendar calendar = new GregorianCalendar();

        BaseClock(long timestamp) {
            this.timestamp = timestamp;
            calendar.setTimeInMillis(timestamp);
        }

        @Override public long getTimestamp() { return timestamp; }
        @Override public GregorianCalendar getCal() { return calendar; }
        @Override public int getCycle() { return 0; }
        @Override public int getMonth() { return 0; }
        @Override public int getDay() { return 0; }
        @Override public int getHour() { return 0; }
        @Override public float convertToDays(float seconds) { return 0f; }
        @Override public float convertToMonths(float seconds) { return 0f; }
        @Override public float getElapsedDaysSince(long timestamp) { return 0f; }
        @Override public String getMonthString() { return ""; }
        @Override public String getShortMonthString() { return ""; }
        @Override public float getSecondsPerDay() { return 10f; }
        @Override public CampaignClockAPI createClock(long timestamp) { return new BaseClock(timestamp); }
        @Override public String getDateString() { return ""; }
        @Override public float convertToSeconds(float days) { return 0f; }
        @Override public String getShortDate() { return ""; }
        @Override public String getCycleString() { return ""; }
    }

    private static final class DerivedClock extends BaseClock {
        DerivedClock(long timestamp) { super(timestamp); }
    }
}
