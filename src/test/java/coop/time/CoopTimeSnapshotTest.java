package coop.time;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import coop.input.CoopCampaignInputBlocker;
import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopTimeSnapshotTest {
    @Test
    void timeSnapshotMessageCarriesPhaseSevenPayloadFields() {
        CoopTimeLock.TimeSnapshot snapshot =
                new CoopTimeLock.TimeSnapshot(true, false, 123456789L, 42L, 9000L);

        CoopMessages.Message message = CoopMessages.timeSnapshot("session-a", 12L,
                snapshot.paused(),
                snapshot.fastForward(),
                snapshot.timestampMillis(),
                snapshot.campaignDay(),
                snapshot.sentAtMillis());
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.TIME_SNAPSHOT, decoded.type());
        assertEquals("session-a", decoded.sessionId());
        assertEquals(12L, decoded.seq());
        assertEquals(9000L, decoded.sentAtMillis());
        assertEquals("true", CoopMessages.requiredPayloadString(decoded, "paused"));
        assertEquals("false", CoopMessages.requiredPayloadString(decoded, "fastForward"));
        assertEquals(123456789L, CoopMessages.requiredPayloadLong(decoded, "timestampMillis"));
        assertEquals(42L, CoopMessages.requiredPayloadLong(decoded, "campaignDay"));
        assertEquals(9000L, CoopMessages.requiredPayloadLong(decoded, "sentAtMillis"));
    }

    @Test
    void timeSnapshotRoundTripsThroughMessagePayload() {
        CoopTimeLock.TimeSnapshot snapshot =
                new CoopTimeLock.TimeSnapshot(false, true, 987654321L, 128L, 11000L);

        CoopTimeLock.TimeSnapshot decoded = CoopTimeLock.fromMessage(
                CoopMessages.timeSnapshot("session-a", 13L,
                        snapshot.paused(),
                        snapshot.fastForward(),
                        snapshot.timestampMillis(),
                        snapshot.campaignDay(),
                        snapshot.sentAtMillis()));

        assertEquals(snapshot, decoded);
    }

    @Test
    void capturesSectorPauseFastForwardAndClock() {
        RecordingSector recording = new RecordingSector(true, true, 222333444L, 17);
        CoopTimeLock timeLock = new CoopTimeLock(recording::proxy);

        CoopTimeLock.TimeSnapshot snapshot = timeLock.capture(12000L);

        assertTrue(snapshot.paused());
        assertTrue(snapshot.fastForward());
        assertEquals(222333444L, snapshot.timestampMillis());
        assertEquals(17L, snapshot.campaignDay());
        assertEquals(12000L, snapshot.sentAtMillis());
    }

    @Test
    void guestApplySetsPauseAndFastForwardEveryFrame() {
        RecordingSector recording = new RecordingSector(false, false, 222333444L, 17);
        CoopTimeLock timeLock = new CoopTimeLock(recording::proxy);

        timeLock.apply(new CoopTimeLock.TimeSnapshot(true, true, 222333555L, 18L, 13000L));

        assertTrue(recording.paused);
        assertTrue(recording.fastForward);
    }

    @Test
    void syncGuestInputBlockerRegistersTransientListenerOnceAndUnregistersWhenInactive() {
        RecordingSector recording = new RecordingSector(false, false, 222333444L, 17);
        CoopTimeLock timeLock = new CoopTimeLock(recording::proxy);

        timeLock.syncGuestInputBlocker(true);
        timeLock.syncGuestInputBlocker(true);
        timeLock.syncGuestInputBlocker(false);

        assertEquals(1, recording.listenerManager.added.size());
        assertInstanceOf(CoopCampaignInputBlocker.class, recording.listenerManager.added.get(0));
        assertEquals(List.of(true), recording.listenerManager.transientFlags);
        assertEquals(List.of(CoopCampaignInputBlocker.class), recording.listenerManager.removedClasses);
        assertFalse(recording.listenerManager.hasInputBlocker);
    }

    private static final class RecordingSector {
        private boolean paused;
        private boolean fastForward;
        private final long timestampMillis;
        private final int day;
        private final RecordingListenerManager listenerManager = new RecordingListenerManager();

        private RecordingSector(boolean paused, boolean fastForward, long timestampMillis, int day) {
            this.paused = paused;
            this.fastForward = fastForward;
            this.timestampMillis = timestampMillis;
            this.day = day;
        }

        private SectorAPI proxy() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isPaused" -> {
                                return paused;
                            }
                            case "setPaused" -> {
                                paused = (boolean) args[0];
                                return null;
                            }
                            case "isFastForwardIteration" -> {
                                return fastForward;
                            }
                            case "setFastForwardIteration" -> {
                                fastForward = (boolean) args[0];
                                return null;
                            }
                            case "getClock" -> {
                                return clockProxy();
                            }
                            case "getListenerManager" -> {
                                return listenerManager.proxy();
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private CampaignClockAPI clockProxy() {
            return (CampaignClockAPI) Proxy.newProxyInstance(
                    CampaignClockAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignClockAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getTimestamp" -> {
                                return timestampMillis;
                            }
                            case "getDay" -> {
                                return day;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    private static final class RecordingListenerManager {
        private final List<Object> added = new ArrayList<>();
        private final List<Boolean> transientFlags = new ArrayList<>();
        private final List<Class<?>> removedClasses = new ArrayList<>();
        private boolean hasInputBlocker;

        private ListenerManagerAPI proxy() {
            return (ListenerManagerAPI) Proxy.newProxyInstance(
                    ListenerManagerAPI.class.getClassLoader(),
                    new Class<?>[]{ListenerManagerAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "hasListenerOfClass" -> {
                                return args[0] == CoopCampaignInputBlocker.class && hasInputBlocker;
                            }
                            case "addListener" -> {
                                added.add(args[0]);
                                transientFlags.add(args.length > 1 && (boolean) args[1]);
                                hasInputBlocker = args[0] instanceof CoopCampaignInputBlocker;
                                return null;
                            }
                            case "removeListenerOfClass" -> {
                                removedClasses.add((Class<?>) args[0]);
                                if (args[0] == CoopCampaignInputBlocker.class) {
                                    hasInputBlocker = false;
                                }
                                return null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
