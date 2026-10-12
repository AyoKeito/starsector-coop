package coop.net;

import coop.campaign.CoopCreditTransfer;
import coop.testing.FakeCreditEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopCreditTransferQueueCapTest {

    @Test
    void aGiftThatTriggersTheTransportQueueCapIsRefundedDuringSend() {
        CoopNetService service = new CoopNetService(() -> 1L, 1);
        FakeCreditEngine wallet = new FakeCreditEngine(100_000L);
        CoopCreditTransfer transfer = new CoopCreditTransfer(wallet, new CoopCreditTransfer.Link() {
            @Override
            public boolean canSend() {
                return true;
            }

            @Override
            public String mintLedgerId() {
                return "cap-triggering-gift";
            }

            @Override
            public void sendGrant(String ledgerId, int amount, String reason) {
                service.send(CoopMessages.creditsGrant("session-a", service.nextSeq(), 1L,
                        ledgerId, amount, reason));
            }
        });
        service.setOutboundDiscardListener(transfer);
        try {
            for (int i = 0; i < CoopNetService.QUEUE_DROP_LINK_MESSAGES; i++) {
                service.send(CoopMessages.ping("session-a", service.nextSeq(), 1L));
            }
            assertEquals(CoopNetService.QUEUE_DROP_LINK_MESSAGES, service.outboundQueueDepth());

            assertEquals(CoopCreditTransfer.Result.SEND_FAILED, transfer.send(25_000));

            assertEquals(100_000L, wallet.credits);
            assertEquals(0, service.outboundQueueDepth());
            assertFalse(transfer.hasSent("cap-triggering-gift"));
            assertTrue(wallet.feed.stream().noneMatch(line -> line.startsWith("Sent ")));
        } finally {
            service.shutdown();
        }
    }
}
