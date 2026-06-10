package coop.time;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import coop.input.CoopCampaignInputBlocker;
import coop.input.CoopHostPauseInputListener;
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
        RecordingSector recording = new RecordingSector(true, false, true, 222333444L, 17);
        CoopTimeLock timeLock = new CoopTimeLock(recording::proxy);

        CoopTimeLock.TimeSnapshot snapshot = timeLock.capture(12000L);

        assertTrue(snapshot.paused());
        assertTrue(snapshot.fastForward());
        assertEquals(222333444L, snapshot.timestampMillis());
        assertEquals(17L, snapshot.campaignDay());
        assertEquals(12000L, snapshot.sentAtMillis());
    }

    @Test
    void guestApplySetsPauseAndFastForwardOnTransition() {
        RecordingSector recording = new RecordingSector(false, false, 222333444L, 17);
        CoopTimeLock timeLock = new CoopTimeLock(recording::proxy);

        timeLock.apply(new CoopTimeLock.TimeSnapshot(true, true, 222333555L, 18L, 13000L));

        assertTrue(recording.paused);
        assertTrue(recording.sectorFastAdvance);
        assertEquals(1, recording.setPausedCalls);
        assertEquals(1, recording.setFastAdvanceCalls);
    }

    @Test
    void guestApplyDoesNotReissueUnchangedPauseEveryFrame() {
        // Regression: re-issuing setPaused(true) every frame clobbered a vanilla interaction
        // dialog's option reshow on the guest (blank "You decide to..." after leaving the trade
        // tab). The guest must only flip the clock on a genuine transition, like the host does.
        RecordingSector recording = new RecordingSector(true, false, 222333444L, 17);
        CoopTimeLock timeLock = new CoopTimeLock(recording::proxy);

        // Already paused, not fast-forwarding; applying the same state repeatedly must be a no-op.
        timeLock.apply(new CoopTimeLock.TimeSnapshot(true, false, 222333555L, 18L, 13000L));
        timeLock.apply(new CoopTimeLock.TimeSnapshot(true, false, 222333666L, 19L, 13200L));

        assertEquals(0, recording.setPausedCalls);
        assertEquals(0, recording.setFastAdvanceCalls);

        // A real transition still flips the clock exactly once.
        timeLock.apply(new CoopTimeLock.TimeSnapshot(false, false, 222333777L, 20L, 13400L));
        assertFalse(recording.paused);
        assertEquals(1, recording.setPausedCalls);
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

    @Test
    void syncHostInputListenerRegistersTransientListenerOnceAndUnregistersWhenInactive() {
        RecordingSector recording = new RecordingSector(false, false, 222333444L, 17);
        CoopTimeLock timeLock = new CoopTimeLock(recording::proxy);
        timeLock.setPauseCoordinator(new CoopSharedPauseCoordinator());

        timeLock.syncHostInputListener(true);
        timeLock.syncHostInputListener(true);
        timeLock.syncHostInputListener(false);

        assertEquals(1, recording.listenerManager.added.size());
        assertInstanceOf(CoopHostPauseInputListener.class, recording.listenerManager.added.get(0));
        assertEquals(List.of(true), recording.listenerManager.transientFlags);
        assertEquals(List.of(CoopHostPauseInputListener.class), recording.listenerManager.removedClasses);
        assertFalse(recording.listenerManager.hasHostPauseInputListener);
    }

    private static final class RecordingSector {
        private boolean paused;
        private boolean sectorFastAdvance;
        private int setPausedCalls;
        private int setFastAdvanceCalls;
        private final boolean uiFastForward;
        private final long timestampMillis;
        private final int day;
        private final RecordingListenerManager listenerManager = new RecordingListenerManager();

        private RecordingSector(boolean paused, boolean fastForward, long timestampMillis, int day) {
            this(paused, fastForward, fastForward, timestampMillis, day);
        }

        private RecordingSector(boolean paused, boolean sectorFastAdvance, boolean uiFastForward,
                                long timestampMillis, int day) {
            this.paused = paused;
            this.sectorFastAdvance = sectorFastAdvance;
            this.uiFastForward = uiFastForward;
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
                                setPausedCalls++;
                                return null;
                            }
                            case "isInFastAdvance" -> {
                                return sectorFastAdvance;
                            }
                            case "setInFastAdvance" -> {
                                sectorFastAdvance = (boolean) args[0];
                                setFastAdvanceCalls++;
                                return null;
                            }
                            case "isFastForwardIteration" -> {
                                // Read only by temporary capture() diagnostic logging.
                                return sectorFastAdvance;
                            }
                            case "getCampaignUI" -> {
                                return campaignUiProxy();
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

        private CampaignUIAPI campaignUiProxy() {
            return (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignUIAPI.class},
                    (proxy, method, args) -> {
                        if ("isFastForward".equals(method.getName())) {
                            return uiFastForward;
                        }
                        throw new UnsupportedOperationException(method.getName());
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
        private boolean hasHostPauseInputListener;

        private ListenerManagerAPI proxy() {
            return (ListenerManagerAPI) Proxy.newProxyInstance(
                    ListenerManagerAPI.class.getClassLoader(),
                    new Class<?>[]{ListenerManagerAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "hasListenerOfClass" -> {
                                if (args[0] == CoopCampaignInputBlocker.class) {
                                    return hasInputBlocker;
                                }
                                if (args[0] == CoopHostPauseInputListener.class) {
                                    return hasHostPauseInputListener;
                                }
                                return false;
                            }
                            case "addListener" -> {
                                added.add(args[0]);
                                transientFlags.add(args.length > 1 && (boolean) args[1]);
                                if (args[0] instanceof CoopCampaignInputBlocker) {
                                    hasInputBlocker = true;
                                }
                                if (args[0] instanceof CoopHostPauseInputListener) {
                                    hasHostPauseInputListener = true;
                                }
                                return null;
                            }
                            case "removeListenerOfClass" -> {
                                removedClasses.add((Class<?>) args[0]);
                                if (args[0] == CoopCampaignInputBlocker.class) {
                                    hasInputBlocker = false;
                                }
                                if (args[0] == CoopHostPauseInputListener.class) {
                                    hasHostPauseInputListener = false;
                                }
                                return null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
