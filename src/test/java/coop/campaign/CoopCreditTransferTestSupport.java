package coop.campaign;

/** Shares the replicator's package-private wallet seam with transport integration tests. */
public final class CoopCreditTransferTestSupport {
    private CoopCreditTransferTestSupport() {
    }

    public static CoopCreditTransfer installEngine(CoopCampaignReplicator replicator,
                                                   CoopCreditTransfer.Engine engine) {
        return replicator.replaceCreditTransferEngineForTest(engine);
    }
}
