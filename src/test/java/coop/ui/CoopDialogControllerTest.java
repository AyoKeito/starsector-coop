package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry-until-it-takes loop that every coop dialog opens through, plus the precedence rule that
 * stops two of them fighting for the one exclusive dialog slot.
 */
class CoopDialogControllerTest {

    @AfterEach
    void clearSector() {
        Global.setSector(null);
    }

    @Test
    void theFirstAttemptsAreDeferredThenItRetriesUntilTheSlotIsFree() {
        RecordingUi ui = new RecordingUi();
        ui.accept = false;
        Global.setSector(sectorProxy(ui));
        CoopDialogController controller = new CoopDialogController("lobby");
        FakeDialog plugin = new FakeDialog();

        controller.request(plugin);
        for (int i = 0; i < CoopDialogController.FRAMES_BEFORE_FIRST_SHOW; i++) {
            controller.tick();
        }
        assertEquals(0, ui.attempts, "vanilla's own recipe waits a couple of frames after a load");

        controller.tick();
        controller.tick();
        assertEquals(2, ui.attempts, "another dialog holds the slot, so it keeps asking");
        assertFalse(controller.isShown());
        assertTrue(controller.isRequested(), "wanted, just not up yet");

        ui.accept = true;
        controller.tick();
        assertTrue(controller.isShown());
        assertSame(plugin, ui.shown.get(ui.shown.size() - 1));

        int attemptsWhenShown = ui.attempts;
        controller.tick();
        assertEquals(attemptsWhenShown, ui.attempts, "no further attempts once it is on screen");
    }

    @Test
    void closingDismissesTheDialogAndStopsTrying() {
        RecordingUi ui = new RecordingUi();
        Global.setSector(sectorProxy(ui));
        CoopDialogController controller = new CoopDialogController("lobby");
        FakeDialog plugin = new FakeDialog();
        controller.request(plugin);
        tickPastDeferral(controller);

        controller.close();

        assertEquals(1, plugin.closes);
        assertFalse(controller.isRequested());
        assertFalse(controller.isShown());
        controller.close();
        assertEquals(1, plugin.closes, "close is idempotent");
    }

    @Test
    void requestingANewPluginReplacesTheOldOne() {
        RecordingUi ui = new RecordingUi();
        Global.setSector(sectorProxy(ui));
        CoopDialogController controller = new CoopDialogController("lobby");
        FakeDialog first = new FakeDialog();
        FakeDialog second = new FakeDialog();
        controller.request(first);
        tickPastDeferral(controller);

        controller.request(first);
        assertEquals(0, first.closes, "re-requesting the same plugin is a no-op");

        controller.request(second);
        assertEquals(1, first.closes);
        assertSame(second, controller.pending());
    }

    /**
     * Phase 21 red-team item 3. The old behaviour was to drop the request on the first throw, which
     * retired the dialog for the rest of the session - and the lobby's dialog is the one the world is
     * held paused behind. The request survives; the backoff is what keeps the retry off the frame.
     */
    @Test
    void aUiThatThrowsKeepsTheRequestAndRetriesOnABackoff() {
        RecordingUi ui = new RecordingUi();
        ui.explode = true;
        Global.setSector(sectorProxy(ui));
        AtomicLong now = new AtomicLong(10_000L);
        CoopDialogController controller = new CoopDialogController("lobby", now::get);
        controller.request(new FakeDialog());
        tickPastDeferral(controller);

        assertTrue(controller.isRequested(), "a dialog the world is waiting on is never retired");
        assertFalse(controller.isShown());
        assertEquals(1, ui.attempts, "one attempt, then the backoff");

        for (int frame = 0; frame < 100; frame++) {
            controller.tick();
        }
        assertEquals(1, ui.attempts, "a hundred frames inside the backoff is still one attempt");

        now.addAndGet(CoopDialogController.RETRY_BACKOFF_MILLIS);
        controller.tick();
        assertEquals(2, ui.attempts, "and it does try again once the backoff is up");

        ui.explode = false;
        now.addAndGet(CoopDialogController.RETRY_BACKOFF_MILLIS);
        controller.tick();
        assertTrue(controller.isShown(), "a UI that recovers gets the dialog it was owed");
    }

    @Test
    void withNoSectorEveryMethodIsANoOp() {
        Global.setSector(null);
        CoopDialogController controller = new CoopDialogController("lobby");
        FakeDialog plugin = new FakeDialog();

        controller.request(plugin);
        tickPastDeferral(controller);

        assertTrue(controller.isRequested());
        assertFalse(controller.isShown());
    }

    @Test
    void precedenceIsReconnectThenDesyncThenLobbyThenConnecting() {
        assertTrue(CoopDialogArbiter.RECONNECT.outranks(CoopDialogArbiter.DESYNC));
        assertTrue(CoopDialogArbiter.DESYNC.outranks(CoopDialogArbiter.LOBBY));
        assertTrue(CoopDialogArbiter.LOBBY.outranks(CoopDialogArbiter.CONNECTING));
        assertFalse(CoopDialogArbiter.CONNECTING.outranks(CoopDialogArbiter.LOBBY));

        assertFalse(CoopDialogArbiter.mayRequest(CoopDialogArbiter.LOBBY, CoopDialogArbiter.RECONNECT));
        assertFalse(CoopDialogArbiter.mayRequest(CoopDialogArbiter.CONNECTING, CoopDialogArbiter.LOBBY));
        assertTrue(CoopDialogArbiter.mayRequest(CoopDialogArbiter.RECONNECT, CoopDialogArbiter.LOBBY,
                CoopDialogArbiter.CONNECTING));
        assertTrue(CoopDialogArbiter.mayRequest(CoopDialogArbiter.LOBBY),
                "nothing else is asking, so it may open");
        assertTrue(CoopDialogArbiter.mayRequest(CoopDialogArbiter.LOBBY, CoopDialogArbiter.LOBBY),
                "its own request must not block it");
    }

    private static void tickPastDeferral(CoopDialogController controller) {
        for (int i = 0; i <= CoopDialogController.FRAMES_BEFORE_FIRST_SHOW; i++) {
            controller.tick();
        }
    }

    // ---- proxies -------------------------------------------------------------------------------

    private static SectorAPI sectorProxy(RecordingUi ui) {
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCampaignUI" -> ui.proxy();
                    case "toString" -> "sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class RecordingUi {
        private final List<InteractionDialogPlugin> shown = new ArrayList<>();
        private int attempts;
        private boolean accept = true;
        private boolean explode;

        private CampaignUIAPI proxy() {
            return (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignUIAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "showInteractionDialog" -> {
                            attempts++;
                            if (explode) {
                                throw new IllegalStateException("no dialog for you");
                            }
                            if (accept) {
                                shown.add((InteractionDialogPlugin) args[0]);
                            }
                            yield accept;
                        }
                        case "toString" -> "ui";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class FakeDialog implements InteractionDialogPlugin, CoopDismissableDialog {
        private int closes;

        @Override
        public void init(InteractionDialogAPI dialog) {
        }

        @Override
        public void optionSelected(String optionText, Object optionData) {
        }

        @Override
        public void optionMousedOver(String optionText, Object optionData) {
        }

        @Override
        public void advance(float amount) {
        }

        @Override
        public void backFromEngagement(EngagementResultAPI battleResult) {
        }

        @Override
        public Object getContext() {
            return null;
        }

        @Override
        public Map<String, MemoryAPI> getMemoryMap() {
            return Map.of();
        }

        @Override
        public void close() {
            closes++;
        }

        @SuppressWarnings("unused")
        private OptionPanelAPI unused() {
            return null;
        }
    }
}
