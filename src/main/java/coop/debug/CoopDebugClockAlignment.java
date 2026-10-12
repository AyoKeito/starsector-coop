package coop.debug;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.time.CoopClockReconciler;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Objects;

/** Explicit bridge action for pairing paused QA samples. Never called by clock reconciliation. */
public final class CoopDebugClockAlignment {
    public static final long MAX_FORWARD_MILLIS = CoopClockReconciler.MILLIS_PER_GAME_DAY;

    private CoopDebugClockAlignment() {
    }

    public record Result(long beforeTimestamp, long afterTimestamp, boolean advanced) {
    }

    /**
     * The bridge verifies an active shared pause before calling; this also checks the live engine
     * pause. Advances only the lower clock to the timestamp the two-client caller chose.
     */
    public static Result align(SectorAPI sector, long targetTimestamp) {
        if (sector == null) throw new IllegalStateException("No active campaign sector");
        if (!sector.isPaused()) throw new IllegalStateException("Clock alignment requires a paused campaign");
        CampaignClockAPI clock = sector.getClock();
        if (clock == null) throw new IllegalStateException("No active campaign clock");
        CoopClockReconciler.ClockPort port;
        try {
            port = resolve(clock);
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot bind the campaign clock timestamp", failure);
        }
        return align(port, true, targetTimestamp);
    }

    /** Injectable seam; the same guards and exact readback apply to the native clock. */
    public static Result align(CoopClockReconciler.ClockPort port, boolean paused,
                               long targetTimestamp) {
        if (!paused) throw new IllegalStateException("Clock alignment requires a paused campaign");
        Objects.requireNonNull(port, "port");
        long before = read(port);
        if (targetTimestamp < before) {
            throw new IllegalArgumentException("Clock alignment cannot move backward");
        }
        long delta;
        try {
            delta = Math.subtractExact(targetTimestamp, before);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Clock alignment exceeds one game day", overflow);
        }
        if (delta > MAX_FORWARD_MILLIS) {
            throw new IllegalArgumentException("Clock alignment exceeds one game day");
        }
        if (delta == 0) return new Result(before, before, false);
        try {
            port.setTimestamp(targetTimestamp);
        } catch (Throwable failure) {
            throw new IllegalStateException("Failed to align the campaign clock", failure);
        }
        long after = read(port);
        if (after != targetTimestamp) {
            throw new IllegalStateException("Campaign clock alignment readback differs from target");
        }
        return new Result(before, after, true);
    }

    private static long read(CoopClockReconciler.ClockPort port) {
        try {
            return port.getTimestamp();
        } catch (Throwable failure) {
            throw new IllegalStateException("Failed to read the campaign clock", failure);
        }
    }

    /** Same calendar/cache write as CoopClockReconciler, with no saved or cached clock instance. */
    private static CoopClockReconciler.ClockPort resolve(CampaignClockAPI clock) throws Throwable {
        MethodHandle setter = null;
        NoSuchFieldException lastMiss = null;
        for (Class<?> type = clock.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                setter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findSetter(type, "timestamp", long.class).bindTo(clock);
                break;
            } catch (NoSuchFieldException miss) {
                lastMiss = miss;
            }
        }
        if (setter == null) {
            throw lastMiss != null ? lastMiss : new NoSuchFieldException("timestamp");
        }
        return new NativePort(clock, setter);
    }

    private record NativePort(CampaignClockAPI clock, MethodHandle timestampSetter)
            implements CoopClockReconciler.ClockPort {
        @Override public long getTimestamp() { return clock.getTimestamp(); }

        @Override public void setTimestamp(long value) throws Throwable {
            // Resolve the handle before either write; preserve both live and save-visible state.
            clock.getCal().setTimeInMillis(value);
            timestampSetter.invoke(value);
        }
    }
}
