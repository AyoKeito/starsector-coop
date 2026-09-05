package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20 M6, task 3: the guest must not be able to trade against un-synced stock in the window
 * between {@code MARKET_OPEN} and {@code MARKET_SNAPSHOT}.
 *
 * <p>These tests fix the <b>ordering</b>, which is the property that actually matters: the trade
 * options are disabled from the frame the request goes out, and they only come back after
 * {@code applyMarketSnapshot} has already written the host's canonical stock into the guest's engine.
 * There must be no frame on which the options are live and the cargo is still the guest's own roll.
 */
class CoopCampaignReplicatorMarketGateTest {

    private final AtomicLong clock = new AtomicLong(1000L);

    @AfterEach
    void clearSector() {
        Global.setSector(null);
    }

    @Test
    void theTradeOptionsAreShutFromTheOpenRequestUntilTheSnapshotIsApplied() {
        FakeDialog dialog = new FakeDialog();
        FakeMarket market = new FakeMarket("sindria", true);
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);
        assertEquals("sindria", replicator.marketSyncGate().pendingMarketId());
        // Nothing is disabled until the first tick; the dialog is not even reachable before then.
        assertTrue(dialog.options.enabled.isEmpty());

        replicator.tickMarketSyncGate();
        assertFalse(dialog.options.enabled.get("marketOpenCoreUI"));
        assertFalse(dialog.options.enabled.get("marketOpenCargo"));
        assertFalse(dialog.options.enabled.get("marketOpenRefit"));
        assertTrue(dialog.text.paras.stream().anyMatch(p -> p.startsWith("Syncing market inventory")));
        // A route the gate does not own must be left alone.
        assertNull(dialog.options.enabled.get("marketLeave"));

        // A second frame with the reply still in flight keeps re-asserting (the rule engine
        // repopulates the panel on its own schedule).
        dialog.options.enabled.clear();
        clock.set(1200L);
        replicator.tickMarketSyncGate();
        assertFalse(dialog.options.enabled.get("marketOpenCoreUI"));
        assertEquals(1, dialog.text.paras.size(), "the syncing line is printed once, not per frame");

        // 500 ms later the snapshot lands: the stock is written first, the options open after.
        clock.set(1500L);
        replicator.handle(CoopMessages.marketSnapshot("session-a", 9L, 1500L, "sindria",
                Submarkets.SUBMARKET_OPEN, 1, CoopMarketSync.encodeStock(List.of())));

        assertNull(replicator.marketSyncGate().pendingMarketId());
        assertTrue(dialog.options.enabled.get("marketOpenCoreUI"));
        assertTrue(dialog.options.enabled.get("marketOpenCargo"));
        assertTrue(dialog.options.enabled.get("marketOpenRefit"));
        assertTrue(market.snapshotApplied, "the release must come after the engine write, not before");
    }

    @Test
    void theGateTimesOutAndOpensTheShopWhenNoSnapshotEverArrives() {
        // The host has no counterpart market (procgen derelict, ruins): no snapshot is coming, and a
        // player must never be locked out by a message that is not on its way.
        FakeDialog dialog = new FakeDialog();
        FakeMarket market = new FakeMarket("sindria", true);
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);
        replicator.tickMarketSyncGate();
        assertFalse(dialog.options.enabled.get("marketOpenCoreUI"));

        clock.set(1000L + CoopMarketSyncGate.TIMEOUT_MILLIS);
        replicator.tickMarketSyncGate();

        assertTrue(dialog.options.enabled.get("marketOpenCoreUI"));
        assertTrue(dialog.options.enabled.get("marketOpenFleet"));
    }

    @Test
    void aMarketWithNoOpenSubmarketIsNeverGated() {
        // Nothing to be wrong about, and the dialog has no trade options to disable anyway.
        FakeDialog dialog = new FakeDialog();
        FakeMarket market = new FakeMarket("derelict-7", false);
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);

        assertNull(replicator.marketSyncGate().pendingMarketId());
        replicator.tickMarketSyncGate();
        assertTrue(dialog.options.enabled.isEmpty());
        assertTrue(dialog.text.paras.isEmpty());
    }

    @Test
    void closingTheDialogDropsTheGate() {
        FakeDialog dialog = new FakeDialog();
        FakeMarket market = new FakeMarket("sindria", true);
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);
        replicator.tickMarketSyncGate();
        replicator.onPlayerClosedMarket(market.api());

        assertNull(replicator.marketSyncGate().pendingMarketId());
    }

    @Test
    void aDialogWithoutTradeOptionsIsNotAnnouncedAt() {
        // A colony info screen or a modded dialog that offers no submarket route: the gate is armed
        // (the market does have stock) but there is nothing being held back, so there is nothing to
        // say. Announcing a sync the player cannot observe is noise.
        FakeDialog dialog = new FakeDialog();
        dialog.options.present.clear();
        FakeMarket market = new FakeMarket("sindria", true);
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);
        replicator.tickMarketSyncGate();

        assertTrue(dialog.text.paras.isEmpty());
    }

    @Test
    void aHiddenBaseMarketIsNeverGated() {
        // A guest's mirrored pirate/Luddic-path base is built locally with Misc.genUID(), so its id
        // cannot exist in the host's economy and no snapshot is ever coming. It does have an open
        // submarket, so the stock predicate alone armed the gate and shut the shop for the full
        // timeout on every dock at a hidden base.
        FakeDialog dialog = new FakeDialog();
        FakeMarket market = new FakeMarket("1c3f", true).hidden();
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);

        assertNull(replicator.marketSyncGate().pendingMarketId());
        replicator.tickMarketSyncGate();
        assertTrue(dialog.options.enabled.isEmpty());
        assertTrue(dialog.text.paras.isEmpty());
    }

    @Test
    void aGateThatCannotReachTheUiPutsTheOptionsBackBeforeItForgetsItself() {
        // "Fail open" has to mean re-enabling what the same pass just disabled. Clearing the gate
        // zeroes pendingMarketId, and every path that would restore the options is gated on the gate
        // still being armed -- so a bare clear() left the dialog greyed out with nothing to fix it.
        FakeDialog dialog = new FakeDialog();
        dialog.text.throwOnAddPara = true;
        FakeMarket market = new FakeMarket("sindria", true);
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);
        replicator.tickMarketSyncGate();

        assertNull(replicator.marketSyncGate().pendingMarketId());
        assertTrue(dialog.options.enabled.get("marketOpenCoreUI"));
        assertTrue(dialog.options.enabled.get("marketOpenRefit"));
    }

    @Test
    void losingTheSessionPutsTheOptionsBackToo() {
        FakeDialog dialog = new FakeDialog();
        FakeMarket market = new FakeMarket("sindria", true);
        Global.setSector(sector(market, dialog));
        CoopCampaignReplicator replicator = guestReplicator();

        replicator.onPlayerOpenedMarket(market.api(), false);
        replicator.tickMarketSyncGate();
        assertFalse(dialog.options.enabled.get("marketOpenCoreUI"));

        replicator.dispose(Global.getSector());

        assertNull(replicator.marketSyncGate().pendingMarketId());
        assertTrue(dialog.options.enabled.get("marketOpenCoreUI"));
    }

    // ---- fakes ---------------------------------------------------------------------------------

    private CoopCampaignReplicator guestReplicator() {
        return new CoopCampaignReplicator(
                new RecordingNetService(CoopConnectionRole.GUEST), TestSessions.activeGuestSession(),
                clock::get);
    }

    private static SectorAPI sector(FakeMarket market, FakeDialog dialog) {
        EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                EconomyAPI.class.getClassLoader(), new Class<?>[]{EconomyAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMarket" -> market.id.equals(args[0]) ? market.api() : null;
                    case "toString" -> "FakeEconomy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        CampaignUIAPI ui = (CampaignUIAPI) Proxy.newProxyInstance(
                CampaignUIAPI.class.getClassLoader(), new Class<?>[]{CampaignUIAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCurrentInteractionDialog" -> dialog.api();
                    case "toString" -> "FakeUi";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(), new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEconomy" -> economy;
                    case "getCampaignUI" -> ui;
                    case "toString" -> "FakeSector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** Records the enabled/disabled calls the gate makes, keyed by option id. */
    private static final class FakeOptionPanel {
        private final List<String> present = new ArrayList<>(CoopMarketSyncGate.TRADE_OPTION_IDS);
        private final Map<String, Boolean> enabled = new LinkedHashMap<>();

        private OptionPanelAPI api() {
            return (OptionPanelAPI) Proxy.newProxyInstance(
                    OptionPanelAPI.class.getClassLoader(), new Class<?>[]{OptionPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasOption" -> present.contains(String.valueOf(args[0]));
                        case "setEnabled" -> {
                            enabled.put(String.valueOf(args[0]), (Boolean) args[1]);
                            yield null;
                        }
                        case "toString" -> "FakeOptions";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class FakeTextPanel {
        private final List<String> paras = new ArrayList<>();
        /** Models a dialog whose text panel is gone by the time the gate reaches for it. */
        private boolean throwOnAddPara;

        private TextPanelAPI api() {
            return (TextPanelAPI) Proxy.newProxyInstance(
                    TextPanelAPI.class.getClassLoader(), new Class<?>[]{TextPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addPara" -> {
                            if (throwOnAddPara) {
                                throw new IllegalStateException("no text panel");
                            }
                            paras.add(String.valueOf(args[0]));
                            yield null;
                        }
                        case "toString" -> "FakeText";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }

    private static final class FakeDialog {
        private final FakeOptionPanel options = new FakeOptionPanel();
        private final FakeTextPanel text = new FakeTextPanel();

        private InteractionDialogAPI api() {
            return (InteractionDialogAPI) Proxy.newProxyInstance(
                    InteractionDialogAPI.class.getClassLoader(),
                    new Class<?>[]{InteractionDialogAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getOptionPanel" -> options.api();
                        case "getTextPanel" -> text.api();
                        case "toString" -> "FakeDialog";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }

    /** A market whose open submarket exists (or not) and whose cargo notices a snapshot apply. */
    private static final class FakeMarket {
        private final String id;
        private final boolean hasOpenSubmarket;
        private boolean snapshotApplied;
        private boolean hidden;

        private FakeMarket(String id, boolean hasOpenSubmarket) {
            this.id = id;
            this.hasOpenSubmarket = hasOpenSubmarket;
        }

        private FakeMarket hidden() {
            this.hidden = true;
            return this;
        }

        private MarketAPI api() {
            CargoAPI cargo = (CargoAPI) Proxy.newProxyInstance(
                    CargoAPI.class.getClassLoader(), new Class<?>[]{CargoAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getStacksCopy" -> {
                            // The replacement pass reads the guest's current stacks; seeing it run is
                            // how the test knows the engine write happened before the release.
                            snapshotApplied = true;
                            yield List.of();
                        }
                        case "getMothballedShips" -> null;
                        case "toString" -> "FakeCargo";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            SubmarketAPI submarket = (SubmarketAPI) Proxy.newProxyInstance(
                    SubmarketAPI.class.getClassLoader(), new Class<?>[]{SubmarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCargo", "getCargoNullOk" -> cargo;
                        case "toString" -> "FakeSubmarket";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            return (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(), new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        // Phase 32: one shared submarket, so one snapshot resolves the gate.
                        case "hasSubmarket" -> hasOpenSubmarket
                                && Submarkets.SUBMARKET_OPEN.equals(args[0]);
                        case "getSubmarket" -> hasOpenSubmarket
                                && Submarkets.SUBMARKET_OPEN.equals(args[0]) ? submarket : null;
                        case "isHidden" -> hidden;
                        case "getId" -> id;
                        case "getPeopleCopy" -> List.of();
                        case "toString" -> "FakeMarket[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }
}
