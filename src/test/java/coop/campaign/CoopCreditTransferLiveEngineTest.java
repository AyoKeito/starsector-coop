package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.util.MutableValue;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopCreditTransferLiveEngineTest {

    @BeforeEach
    void stubSettings() {
        Global.setSettings(ApiProxies.whiteSettings());
    }

    @AfterEach
    void clearGlobals() {
        Global.setSector(null);
        Global.setSettings(null);
        CoopCreditTransfer.uninstall();
    }

    @Test
    void oldTransportShutdownRefundsOnlyTheSectorThatWasDebited() {
        WalletSector outgoing = new WalletSector(100_000f);
        WalletSector loaded = new WalletSector(200_000f);
        Global.setSector(outgoing.proxy());
        CoopNetService service = new CoopNetService();
        CoopCreditTransfer transfer = CoopCreditTransfer.live(new CoopCreditTransfer.Link() {
            @Override
            public boolean canSend() {
                return true;
            }

            @Override
            public String mintLedgerId() {
                return "outgoing-gift";
            }

            @Override
            public void sendGrant(String ledgerId, int amount, String reason) {
                service.send(CoopMessages.creditsGrant("outgoing-session", 1L, 1L,
                        ledgerId, amount, reason));
            }
        });
        service.setOutboundDiscardListener(transfer);
        try {
            assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(25_000));
            assertEquals(75_000f, outgoing.credits.get());

            // The engine swaps Global's sector before CoopModPlugin.onGameLoad shuts the old link.
            Global.setSector(loaded.proxy());
            service.shutdown();

            assertEquals(100_000f, outgoing.credits.get());
            assertEquals(200_000f, loaded.credits.get(), "the newly loaded save never paid for this gift");
            assertFalse(transfer.hasSent("outgoing-gift"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aTemporaryMissingPlayerFleetCanRecoverWithinTheBoundSector() {
        WalletSector sector = new WalletSector(0f);
        Global.setSector(sector.proxy());
        CoopCreditTransfer transfer = CoopCreditTransfer.live(new NoSendLink());
        sector.hasFleet = false;

        assertFalse(transfer.receive("incoming-gift", 25_000, "gift"));
        assertFalse(transfer.hasApplied("incoming-gift"));

        sector.hasFleet = true;
        assertTrue(transfer.receive("incoming-gift", 25_000, "gift"));
        assertFalse(transfer.receive("incoming-gift", 25_000, "gift"));
        assertEquals(25_000f, sector.credits.get());
        assertTrue(transfer.hasApplied("incoming-gift"));
    }

    @Test
    void anEngineCreatedWithoutASectorNeverPaysIntoALaterLoadedSave() {
        Global.setSector(null);
        CoopCreditTransfer transfer = CoopCreditTransfer.live(new NoSendLink());
        WalletSector loaded = new WalletSector(200_000f);
        Global.setSector(loaded.proxy());

        assertEquals(-1L, transfer.credits());
        assertFalse(transfer.receive("unbound-gift", 25_000, "gift"));
        assertFalse(transfer.hasApplied("unbound-gift"));
        assertEquals(200_000f, loaded.credits.get());
    }

    private static final class NoSendLink implements CoopCreditTransfer.Link {
        @Override
        public boolean canSend() {
            return false;
        }

        @Override
        public String mintLedgerId() {
            throw new AssertionError("this test only receives");
        }

        @Override
        public void sendGrant(String ledgerId, int amount, String reason) {
            throw new AssertionError("this test only receives");
        }
    }

    private static final class WalletSector {
        private final MutableValue credits;
        private boolean hasFleet = true;
        private final SectorAPI sector;

        private WalletSector(float balance) {
            credits = new MutableValue(balance);
            CargoAPI cargo = (CargoAPI) Proxy.newProxyInstance(CargoAPI.class.getClassLoader(),
                    new Class<?>[]{CargoAPI.class}, (proxy, method, args) ->
                            "getCredits".equals(method.getName()) ? credits : null);
            CampaignFleetAPI fleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(), new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> "getCargo".equals(method.getName()) ? cargo : null);
            sector = (SectorAPI) Proxy.newProxyInstance(SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class}, (proxy, method, args) ->
                            "getPlayerFleet".equals(method.getName()) && hasFleet ? fleet : null);
        }

        private SectorAPI proxy() {
            return sector;
        }
    }
}
