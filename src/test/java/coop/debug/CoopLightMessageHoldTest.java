package coop.debug;

import coop.campaign.CoopLightCodec;
import coop.net.CoopMessages;
import coop.util.CoopDebug;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoopLightMessageHoldTest {
    private static final String LIGHT = "light:00000000-0000-0000-0000-000000000001";
    private static final String OTHER_LIGHT = "light:00000000-0000-0000-0000-000000000002";
    private String oldDiagnostics;
    private final CoopLightMessageHold hold = new CoopLightMessageHold();
    private final CoopLightMessageHold.Context context = new CoopLightMessageHold.Context(
            new Object(), new Object(), "session", "host", "guest", 7);

    @BeforeEach void enable() {
        oldDiagnostics = System.getProperty(CoopDebug.PROPERTY);
        diagnostics(true);
    }

    @AfterEach void restore() {
        if (oldDiagnostics == null) System.clearProperty(CoopDebug.PROPERTY);
        else System.setProperty(CoopDebug.PROPERTY, oldDiagnostics);
        for (int i = 0; i < 300; i++) CoopDebug.pollFrame();
    }

    @Test void unarmedIsInertAndClaimHoldLatchesOnlyOneRealAcceptedGuestGeneration() {
        CoopMessages.Message original = accept(1, LIGHT, "guest", "generation-one");
        assertFalse(hold.holdClaimReply(context, original, "guest", 1_000));
        hold.arm(context, "claim_reply", LIGHT, null, 30, 1_000);
        assertFalse(hold.holdClaimReply(context, accept(2, OTHER_LIGHT, "guest", "generation-one"), "guest", 1_001));
        assertFalse(hold.holdClaimReply(context, accept(3, LIGHT, "host", "generation-one"), "host", 1_001));
        assertFalse(hold.holdClaimReply(context, CoopMessages.interactionReject("session", 4, 1_000,
                LIGHT, "already_claimed_by:host", "generation-one"), "guest", 1_001));
        assertTrue(hold.holdClaimReply(context, original, "guest", 1_001));
        assertTrue(hold.holdClaimReply(context, accept(5, LIGHT, "guest", "generation-one"), "guest", 1_002));
        assertFalse(hold.holdClaimReply(context, accept(6, LIGHT, "guest", "generation-two"), "guest", 1_002));
        Map<?, ?> arm = (Map<?, ?>) hold.status(context, 1_003).get("arm");
        assertEquals("generation-one", arm.get("latchedIdentity"));
        assertEquals(2L, arm.get("matchingMessages"));
        assertEquals(1L, arm.get("messageSeq"));
        CoopLightMessageHold.Held released = hold.release(context, 1_004);
        assertSame(original, released.message());
        assertEquals("guest", released.peerId());
        assertEquals(7L, released.connectionGeneration());
        assertFalse((Boolean) hold.status(context, 1_005).get("armed"));
        assertThrows(IllegalStateException.class, () -> hold.release(context, 1_006));
    }

    @Test void everyConsumptionActionHoldsOnlyItsExactRequestAndAllMatchingRetries() {
        for (CoopLightCodec.Action action : CoopLightCodec.Action.values()) {
            hold.arm(context, "consumption_request", LIGHT, action.name(), 30, 1_000);
            CoopLightCodec.Request request = new CoopLightCodec.Request(LIGHT, UUID.randomUUID().toString(), action);
            CoopMessages.Message original = request(10, request, "guest");
            assertFalse(hold.holdConsumptionRequest(context, request(9, request, "stranger"), 7, 1_001));
            assertFalse(hold.holdConsumptionRequest(context, original, 8, 1_001));
            assertTrue(hold.holdConsumptionRequest(context, original, 7, 1_001));
            assertTrue(hold.holdConsumptionRequest(context, request(11, request, "guest"), 7, 1_002));
            assertFalse(hold.holdConsumptionRequest(context, request(12,
                    new CoopLightCodec.Request(LIGHT, UUID.randomUUID().toString(), action), "guest"), 7, 1_003));
            assertFalse(hold.holdConsumptionRequest(context, request(13,
                    new CoopLightCodec.Request(OTHER_LIGHT, request.requestId(), action), "guest"), 7, 1_003));
            assertEquals(request.requestId(), ((Map<?, ?>) hold.status(context, 1_004).get("arm")).get("latchedIdentity"));
            assertSame(original, hold.release(context, 1_005).message());
        }
    }

    @Test void deadlineDropsTheRealEnvelopeWithoutAutomaticallyReleasingIt() {
        hold.arm(context, "claim_reply", LIGHT, null, 1, 1_000);
        hold.holdClaimReply(context, accept(1, LIGHT, "guest", "token"), "guest", 1_001);
        assertTrue((Boolean) hold.status(context, 1_999).get("held"));
        assertFalse((Boolean) hold.status(context, 2_000).get("armed"));
        Map<?, ?> receipt = (Map<?, ?>) hold.status(context, 2_000).get("lastReceipt");
        assertEquals("expired_dropped", receipt.get("reason"));
        assertEquals(true, receipt.get("captured"));
        assertThrows(IllegalStateException.class, () -> hold.release(context, 2_001));
    }

    @Test void everyContextIdentityChangeInvalidatesRatherThanDeliveringIntoTheNewContext() {
        List<CoopLightMessageHold.Context> changed = List.of(
                new CoopLightMessageHold.Context(new Object(), context.owner(), "session", "host", "guest", 7),
                new CoopLightMessageHold.Context(context.sector(), new Object(), "session", "host", "guest", 7),
                new CoopLightMessageHold.Context(context.sector(), context.owner(), "new-session", "host", "guest", 7),
                new CoopLightMessageHold.Context(context.sector(), context.owner(), "session", "new-host", "guest", 7),
                new CoopLightMessageHold.Context(context.sector(), context.owner(), "session", "host", "new-guest", 7),
                new CoopLightMessageHold.Context(context.sector(), context.owner(), "session", "host", "guest", 8));
        for (CoopLightMessageHold.Context replacement : changed) {
            hold.arm(context, "claim_reply", LIGHT, null, 30, 1_000);
            hold.holdClaimReply(context, accept(1, LIGHT, "guest", "token"), "guest", 1_001);
            assertThrows(IllegalStateException.class, () -> hold.release(replacement, 1_002));
            assertEquals("context_changed", ((Map<?, ?>) hold.status(context, 1_003).get("lastReceipt")).get("reason"));
            assertFalse(hold.isArmed());
        }
    }

    @Test void disablingDiagnosticsDropsAnArmAndCannotRearmItLater() {
        hold.arm(context, "claim_reply", LIGHT, null, 30, 1_000);
        hold.holdClaimReply(context, accept(1, LIGHT, "guest", "token"), "guest", 1_001);
        diagnostics(false);
        assertFalse((Boolean) hold.status(context, 1_002).get("armed"));
        assertEquals("diagnostics_disabled", ((Map<?, ?>) hold.status(context, 1_002).get("lastReceipt")).get("reason"));
        assertThrows(IllegalStateException.class, () -> hold.arm(context, "claim_reply", LIGHT, null, 30, 1_003));
        diagnostics(true);
        assertThrows(IllegalStateException.class, () -> hold.release(context, 1_004));
    }

    @Test void invalidArmsFailWithoutChangingTheInstrumentAndExistingArmCannotBeReplaced() {
        assertThrows(IllegalArgumentException.class, () -> hold.arm(context, "unknown", LIGHT, null, 30, 1_000));
        assertThrows(IllegalArgumentException.class, () -> hold.arm(context, "claim_reply", "light:bad", null, 30, 1_000));
        assertThrows(IllegalArgumentException.class, () -> hold.arm(context, "claim_reply", LIGHT, "PULSE", 30, 1_000));
        assertThrows(IllegalArgumentException.class, () -> hold.arm(context, "consumption_request", LIGHT, null, 30, 1_000));
        assertThrows(IllegalArgumentException.class, () -> hold.arm(context, "consumption_request", LIGHT, "unknown", 30, 1_000));
        assertThrows(IllegalArgumentException.class, () -> hold.arm(context, "claim_reply", LIGHT, null, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> hold.arm(context, "claim_reply", LIGHT, null, 601, 1_000));
        assertFalse(hold.isArmed());
        Map<String, Object> maximum = hold.arm(context, "claim_reply", LIGHT, null, 600, 1_000);
        assertEquals(601_000L, ((Map<?, ?>) maximum.get("arm")).get("deadlineMillis"));
        hold.drop("maximum_boundary_checked", 1_001);
        Map<String, Object> original = hold.arm(context, "claim_reply", LIGHT, null, 30, 1_000);
        assertThrows(IllegalStateException.class, () -> hold.arm(context, "claim_reply", OTHER_LIGHT, null, 30, 1_000));
        assertEquals(original, hold.status(context, 1_000));
    }

    @Test void explicitDropKeepsAReceiptAndReleaseDispositionDoesNotClaimApplication() {
        hold.arm(context, "claim_reply", LIGHT, null, 30, 1_000);
        hold.drop("bridge_command", 1_001);
        assertEquals(false, ((Map<?, ?>) hold.status(context, 1_002).get("lastReceipt")).get("captured"));
        hold.arm(context, "claim_reply", LIGHT, null, 30, 2_000);
        hold.holdClaimReply(context, accept(1, LIGHT, "guest", "token"), "guest", 2_001);
        hold.release(context, 2_002);
        hold.released("original_reply_enqueued", 2_003);
        Map<?, ?> receipt = (Map<?, ?>) hold.status(context, 2_004).get("lastReceipt");
        assertEquals("release_requested", receipt.get("reason"));
        assertEquals("original_reply_enqueued", receipt.get("releaseDisposition"));
        assertFalse(receipt.containsKey("nativeApplied"));
    }

    private static CoopMessages.Message accept(long seq, String light, String actor, String token) {
        return CoopMessages.interactionAccept("session", seq, 1_000, light, actor, "Light", 1, token);
    }

    private static CoopMessages.Message request(long seq, CoopLightCodec.Request request, String sender) {
        return CoopMessages.lightRequest("session", seq, 1_000, CoopLightCodec.encodeRequest(request)).withSenderId(sender);
    }

    private static void diagnostics(boolean enabled) {
        System.setProperty(CoopDebug.PROPERTY, Boolean.toString(enabled));
        for (int i = 0; i < 300; i++) CoopDebug.pollFrame();
        assertEquals(enabled, CoopDebug.diagnosticsEnabled());
    }
}
