package coop.campaign;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 32 addition B. The rules that decide whether money is created or destroyed: the cover check,
 * the debit-before-send ordering, the rollback when the transport refuses, and the ledger that makes
 * a re-delivered grant free.
 *
 * <p>Both seams are fakes, so nothing here needs a sector or a socket.
 */
class CoopCreditTransferTest {

    private final FakeEngine engine = new FakeEngine(100_000L);
    private final FakeLink link = new FakeLink();
    private final CoopCreditTransfer transfer = new CoopCreditTransfer(engine, link);

    @AfterEach
    void clearStaticState() {
        CoopCreditTransfer.uninstall();
    }

    // ---- sending ---------------------------------------------------------------------------------

    @Test
    void sendingDebitsLocallyAndPutsExactlyOneGrantOnTheWire() {
        assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(25_000));

        assertEquals(75_000L, engine.credits, "the debit happens on send, not on delivery");
        assertEquals(1, link.sent.size());
        assertEquals(25_000, link.sent.get(0).amount());
        assertEquals(CoopCreditTransfer.REASON_GIFT, link.sent.get(0).reason());
        assertEquals("ledger-1", link.sent.get(0).ledgerId());
    }

    @Test
    void sendingRefusesWhenTheSenderCannotCoverItAndNeitherDebitsNorSends() {
        assertEquals(CoopCreditTransfer.Result.INSUFFICIENT_FUNDS, transfer.send(100_001));

        assertEquals(100_000L, engine.credits);
        assertTrue(link.sent.isEmpty(), "nothing may reach the wire that the wallet did not cover");
        assertTrue(engine.feed.stream().anyMatch(line -> line.contains("not enough credits")),
                "the refusal has to be visible in game, not only in the log: " + engine.feed);
    }

    @Test
    void sendingRefusesWithNoSession() {
        link.canSend = false;

        assertEquals(CoopCreditTransfer.Result.NO_SESSION, transfer.send(1_000));

        assertEquals(100_000L, engine.credits);
        assertTrue(link.sent.isEmpty());
    }

    @Test
    void sendingRefusesANonPositiveAmount() {
        assertEquals(CoopCreditTransfer.Result.BAD_AMOUNT, transfer.send(0));
        assertEquals(CoopCreditTransfer.Result.BAD_AMOUNT, transfer.send(-1));
        assertEquals(CoopCreditTransfer.Result.BAD_AMOUNT,
                transfer.send(CoopCreditTransfer.MAX_AMOUNT + 1));

        assertEquals(100_000L, engine.credits);
        assertTrue(link.sent.isEmpty());
    }

    @Test
    void aTransportFailureRollsTheDebitBackRatherThanBurningTheCredits() {
        link.throwOnSend = true;

        assertEquals(CoopCreditTransfer.Result.SEND_FAILED, transfer.send(40_000));

        assertEquals(100_000L, engine.credits,
                "the debit is only safe because the send is reliable; an unsent grant must refund");
    }

    // ---- receiving -------------------------------------------------------------------------------

    @Test
    void aGrantIsCreditedOnceNoMatterHowManyTimesItIsDelivered() {
        assertTrue(transfer.receive("ledger-a", 30_000, CoopCreditTransfer.REASON_GIFT));
        assertFalse(transfer.receive("ledger-a", 30_000, CoopCreditTransfer.REASON_GIFT),
                "a duplicate ledger id is a no-op, which is what makes the escrow-free design safe");

        assertEquals(130_000L, engine.credits);
        assertEquals(1, engine.feed.size(), "one payment, one feed line");
    }

    @Test
    void twoDifferentGrantsAreBothCredited() {
        assertTrue(transfer.receive("ledger-a", 30_000, CoopCreditTransfer.REASON_GIFT));
        assertTrue(transfer.receive("ledger-b", 5_000, CoopCreditTransfer.REASON_GIFT));

        assertEquals(135_000L, engine.credits);
        assertTrue(transfer.hasApplied("ledger-a"));
        assertTrue(transfer.hasApplied("ledger-b"));
    }

    @Test
    void aGiftNamesThePartnerAndAnyOtherReasonNamesItself() {
        transfer.receive("ledger-a", 1_000, CoopCreditTransfer.REASON_GIFT);
        assertEquals("Ayo sent you 1,000 credits.", engine.feed.get(0));

        transfer.receive("ledger-b", 180_000, "bounty:pirate_9");
        assertEquals("Received 180,000 credits (bounty:pirate_9).", engine.feed.get(1));
    }

    @Test
    void aNamelessPartnerStillGetsAReadableLine() {
        engine.partner = "";

        transfer.receive("ledger-a", 1_000, CoopCreditTransfer.REASON_GIFT);

        assertEquals("Your co-op partner sent you 1,000 credits.", engine.feed.get(0));
    }

    @Test
    void receivingRejectsAnAmountThatIsNotMoney() {
        assertThrows(IllegalArgumentException.class,
                () -> transfer.receive("ledger-a", 0, CoopCreditTransfer.REASON_GIFT));
        assertThrows(IllegalArgumentException.class,
                () -> transfer.receive("ledger-a", -1, CoopCreditTransfer.REASON_GIFT));
        assertThrows(IllegalArgumentException.class, () -> transfer.receive(" ", 10, "gift"));

        assertEquals(100_000L, engine.credits);
    }

    @Test
    void theLedgerIsBoundedAndDropsTheOldestIdsFirst() {
        for (int i = 0; i < CoopCreditTransfer.LEDGER_CAPACITY + 5; i++) {
            transfer.receive("ledger-" + i, 1, CoopCreditTransfer.REASON_GIFT);
        }

        assertFalse(transfer.hasApplied("ledger-0"), "oldest evicted");
        assertTrue(transfer.hasApplied("ledger-" + (CoopCreditTransfer.LEDGER_CAPACITY + 4)));
    }

    @Test
    void clearingDropsTheLedgerWithTheSession() {
        transfer.receive("ledger-a", 1_000, CoopCreditTransfer.REASON_GIFT);
        transfer.clear();

        assertFalse(transfer.hasApplied("ledger-a"));
    }

    // ---- the page's pending amount ----------------------------------------------------------------

    @Test
    void thePendingAmountStepsAndClampsAtZero() {
        CoopCreditTransfer.uninstall();

        assertEquals(0, CoopCreditTransfer.pendingAmount());
        assertEquals(10_000, CoopCreditTransfer.stepPendingAmount(10_000));
        assertEquals(11_000, CoopCreditTransfer.stepPendingAmount(1_000));
        assertEquals(1_000, CoopCreditTransfer.stepPendingAmount(-10_000));
        assertEquals(0, CoopCreditTransfer.stepPendingAmount(-10_000), "never negative");
        assertEquals(CoopCreditTransfer.MAX_AMOUNT,
                CoopCreditTransfer.stepPendingAmount(Integer.MAX_VALUE), "and never past the codec");

        CoopCreditTransfer.clearPendingAmount();
        assertEquals(0, CoopCreditTransfer.pendingAmount());
    }

    @Test
    void uninstallingClearsBothTheHandleAndTheAmount() {
        CoopCreditTransfer.install(transfer);
        CoopCreditTransfer.stepPendingAmount(5_000);

        CoopCreditTransfer.uninstall();

        assertEquals(null, CoopCreditTransfer.active());
        assertEquals(0, CoopCreditTransfer.pendingAmount());
    }

    @Test
    void amountsAreFormattedTheSameOnEveryInstall() {
        assertEquals("1,000", CoopCreditTransfer.format(1_000));
        assertEquals("1,234,567", CoopCreditTransfer.format(1_234_567));
    }

    // ---- fakes -----------------------------------------------------------------------------------

    private static final class FakeEngine implements CoopCreditTransfer.Engine {
        private long credits;
        private String partner = "Ayo";
        private final List<String> feed = new ArrayList<>();

        private FakeEngine(long credits) {
            this.credits = credits;
        }

        @Override
        public long credits() {
            return credits;
        }

        @Override
        public void addCredits(long delta) {
            credits += delta;
        }

        @Override
        public void feed(String line) {
            feed.add(line);
        }

        @Override
        public String partnerName() {
            return partner;
        }
    }

    private record Grant(String ledgerId, int amount, String reason) {
    }

    private static final class FakeLink implements CoopCreditTransfer.Link {
        private boolean canSend = true;
        private boolean throwOnSend;
        private int minted;
        private final List<Grant> sent = new ArrayList<>();

        @Override
        public boolean canSend() {
            return canSend;
        }

        @Override
        public String mintLedgerId() {
            return "ledger-" + (++minted);
        }

        @Override
        public void sendGrant(String ledgerId, int amount, String reason) {
            if (throwOnSend) {
                throw new IllegalStateException("transport is down");
            }
            sent.add(new Grant(ledgerId, amount, reason));
        }
    }
}
