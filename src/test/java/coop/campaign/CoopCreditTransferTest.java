package coop.campaign;

import coop.testing.FakeCreditEngine;
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

    private final FakeCreditEngine engine = new FakeCreditEngine(100_000L);
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
    void theWordingIsChosenFromTheReasonPrefixAndNeverEchoesTheRawReason() {
        transfer.receive("ledger-a", 1_000, CoopCreditTransfer.REASON_GIFT);
        assertEquals("Ayo sent you 1,000 credits.", engine.feed.get(0));

        // Credit red-team P2-2: this used to render "Received 180,000 credits (bounty:pirate_9)."
        // and would have shown Phase 34's internal bounty ids to the player.
        transfer.receive("ledger-b", 180_000, "bounty:pirate_9");
        assertEquals("Bounty payout: 180,000 credits.", engine.feed.get(1));

        transfer.receive("ledger-c", 25, "some_future_sender:internal_id_42");
        assertEquals("Received 25 credits.", engine.feed.get(2));

        transfer.receive("ledger-d", 25, "whatever");
        assertEquals("Received 25 credits.", engine.feed.get(3));

        assertTrue(engine.feed.stream().noneMatch(line -> line.contains("pirate_9")
                        || line.contains("internal_id_42")),
                "no wire string may reach the screen: " + engine.feed);
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

    // ---- P0-1: a wallet write that does not land ---------------------------------------------------

    @Test
    void aGrantWhoseWalletWriteFailsCreditsNothingIsNotRememberedAndThePayingRedeliveryStillPays() {
        engine.failNextWrites = 1;

        assertFalse(transfer.receive("ledger-a", 50_000, CoopCreditTransfer.REASON_GIFT),
                "a refused wallet write is not a successful payment");
        assertEquals(100_000L, engine.credits, "nothing was credited");
        assertFalse(transfer.hasApplied("ledger-a"),
                "the id must stay unapplied or the redelivery is discarded as a duplicate and the"
                        + " money is destroyed on both sides");
        assertTrue(engine.feed.isEmpty(), "nobody may be told about credits that never arrived");

        // The exact redelivery the reliable transport produces once the fleet exists again.
        assertTrue(transfer.receive("ledger-a", 50_000, CoopCreditTransfer.REASON_GIFT));

        assertEquals(150_000L, engine.credits, "paid exactly once, on the delivery that worked");
        assertEquals(1, engine.feed.size());
        assertEquals(2, engine.writeAttempts, "one refused write, one that landed");
    }

    @Test
    void aDebitThatDoesNotLandStopsTheSendRatherThanCreatingMoneyOnTheFarSide() {
        engine.failNextWrites = 1;

        assertEquals(CoopCreditTransfer.Result.SEND_FAILED, transfer.send(25_000));

        assertEquals(100_000L, engine.credits);
        assertTrue(link.sent.isEmpty(), "an undebited grant on the wire would mint credits");
    }

    // ---- P2-3: the arrival is also on the intel page ------------------------------------------------

    @Test
    void anArrivalIsRecordedOnTheIntelPageAsWellAsTheFeed() {
        transfer.receive("ledger-a", 1_000, CoopCreditTransfer.REASON_GIFT);

        // CoopFeed.post is dropped when there is no campaign UI - mid-battle, between screens - and
        // the player would see a balance change with no explanation anywhere (credit red-team P2-3).
        assertEquals(List.of("Ayo sent you 1,000 credits."), engine.intel);
    }

    // ---- P1-1/P1-2/P1-4: refunds -------------------------------------------------------------------

    @Test
    void aDiscardedGrantIsRefundedExactlyOnceAndSaysSo() {
        assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(25_000));
        assertEquals(75_000L, engine.credits);
        String ledgerId = link.sent.get(0).ledgerId();

        assertTrue(transfer.refund(ledgerId, 25_000, "queue-cap"));

        assertEquals(100_000L, engine.credits, "an undelivered gift is the sender's money again");
        assertEquals("Your 25,000 credits to Ayo could not be delivered and were returned.",
                engine.feed.get(engine.feed.size() - 1));
        assertTrue(engine.intel.contains(
                "Your 25,000 credits to Ayo could not be delivered and were returned."));

        assertFalse(transfer.refund(ledgerId, 25_000, "shutdown"),
                "a second notification for the same grant must pay nothing");
        assertEquals(100_000L, engine.credits);
    }

    @Test
    void aGrantThisEngineNeverSentIsNeverRefunded() {
        assertFalse(transfer.refund("somebody-elses-ledger-7", 999_999, "queue-cap"));

        assertEquals(100_000L, engine.credits, "refunding a stranger's grant would mint credits");
        assertTrue(engine.feed.isEmpty());
    }

    @Test
    void aRefundWhoseWalletWriteFailsKeepsTheGrantRefundableForTheNextNotification() {
        transfer.send(25_000);
        String ledgerId = link.sent.get(0).ledgerId();
        engine.failNextWrites = 1;

        assertFalse(transfer.refund(ledgerId, 25_000, "queue-cap"));
        assertEquals(75_000L, engine.credits);
        assertTrue(transfer.hasSent(ledgerId), "still owed, so a later notification can pay it");

        assertTrue(transfer.refund(ledgerId, 25_000, "shutdown"));
        assertEquals(100_000L, engine.credits);
    }

    @Test
    void onlyACreditsGrantMessageIsEverRefunded() {
        transfer.send(25_000);

        transfer.onOutboundDiscarded(
                coop.net.CoopMessages.ping("session-a", 1L, 1_000L), "queue-cap");

        assertEquals(75_000L, engine.credits, "the transport reports every discard; only grants pay");
    }

    @Test
    void aDiscardedGrantMessageIsParsedAndRefunded() {
        transfer.send(25_000);
        String ledgerId = link.sent.get(0).ledgerId();

        transfer.onOutboundDiscarded(coop.net.CoopMessages.creditsGrant("session-a", 1L, 1_000L,
                ledgerId, 25_000, CoopCreditTransfer.REASON_GIFT), "session-end");

        assertEquals(100_000L, engine.credits);
        assertFalse(transfer.hasSent(ledgerId));
    }

    @Test
    void clearingTheSessionLedgerLeavesTheSentIdsRefundable() {
        transfer.send(25_000);
        String ledgerId = link.sent.get(0).ledgerId();

        // Teardown is the moment the transport reports its undelivered grants, so the provenance set
        // has to outlive it or every refund at shutdown becomes a "did not send it" no-op.
        transfer.clear();

        assertTrue(transfer.refund(ledgerId, 25_000, "shutdown"));
        assertEquals(100_000L, engine.credits);
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
