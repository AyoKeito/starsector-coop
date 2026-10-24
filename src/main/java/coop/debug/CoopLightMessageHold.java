package coop.debug;

import coop.campaign.CoopLightCodec;
import coop.campaign.CoopLightRecord;
import coop.net.CoopMessages;
import coop.util.CoopDebug;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One transient, explicitly armed logical light message. Matching retries stay held too. */
public final class CoopLightMessageHold {
    public static final int MAX_SECONDS = 600;
    public record Context(Object sector, Object owner, String sessionId, String actorId,
                          String remoteActorId, long connectionGeneration) { }
    public record Held(String stage, CoopMessages.Message message, String peerId, long connectionGeneration) { }

    private Context context;
    private String stage;
    private String lightId;
    private CoopLightCodec.Action action;
    private String armId;
    private long armedAt;
    private long deadline;
    private Held held;
    private String identity;
    private long matchingMessages;
    private Map<String, Object> lastReceipt = Map.of();

    public boolean isArmed() { return context != null; }

    public Map<String, Object> arm(Context current, String stage, String lightId, String action,
                                   int seconds, long now) {
        check(current, now);
        if (!CoopDebug.diagnosticsEnabled()) throw new IllegalStateException("Light message hold requires diagnostics");
        requireContext(current);
        if (context != null) throw new IllegalStateException("A light message hold is already armed; release or drop it first");
        if (!"claim_reply".equals(stage) && !"consumption_request".equals(stage))
            throw new IllegalArgumentException("stage must be claim_reply or consumption_request");
        lightId = CoopLightRecord.canonicalId(lightId);
        CoopLightCodec.Action selectedAction = null;
        if ("consumption_request".equals(stage)) {
            try { selectedAction = CoopLightCodec.Action.valueOf(Objects.requireNonNull(action, "action")); }
            catch (IllegalArgumentException | NullPointerException invalid) {
                throw new IllegalArgumentException("consumption_request requires PULSE, SENSOR_BURST or DWELLER action", invalid);
            }
        } else if (action != null && !action.isBlank()) {
            throw new IllegalArgumentException("claim_reply has no consumption action");
        }
        if (seconds < 1 || seconds > MAX_SECONDS)
            throw new IllegalArgumentException("seconds must be between 1 and " + MAX_SECONDS);
        this.context = current;
        this.stage = stage;
        this.lightId = lightId;
        this.action = selectedAction;
        this.armId = UUID.randomUUID().toString();
        armedAt = now;
        deadline = Math.addExact(now, seconds * 1_000L);
        held = null;
        identity = null;
        matchingMessages = 0;
        return status(current, now);
    }

    /** Only a real accepted Guest claim reply, after the normal arbiter committed it, can latch. */
    public boolean holdClaimReply(Context current, CoopMessages.Message message, String peerId, long now) {
        check(current, now);
        if (context == null || !"claim_reply".equals(stage) || message.type() != CoopMessages.Type.INTERACTION_ACCEPT
                || !context.sessionId().equals(message.sessionId())
                || !context.remoteActorId().equals(CoopMessages.requiredPayloadString(message, "playerId"))
                || !lightId.equals(CoopMessages.requiredPayloadString(message, "entityId"))) return false;
        String token = CoopMessages.interactionLightClaimToken(message);
        if (token == null || token.isBlank()) return false;
        return capture(current, message, peerId, token);
    }

    /** The request remains unacknowledged by the native light authority, with its original wire envelope. */
    public boolean holdConsumptionRequest(Context current, CoopMessages.Message message,
                                            long connectionGeneration, long now) {
        check(current, now);
        if (context == null || !"consumption_request".equals(stage) || message.type() != CoopMessages.Type.LIGHT_REQUEST
                || !context.sessionId().equals(message.sessionId()) || connectionGeneration != context.connectionGeneration()
                || message.senderId() != null && !context.remoteActorId().equals(message.senderId())) return false;
        CoopLightCodec.Request request = CoopLightCodec.decodeRequest(CoopMessages.requiredPayloadString(message, "request"));
        if (!lightId.equals(request.lightId()) || action != request.action()) return false;
        return capture(current, message, message.senderId(), request.requestId());
    }

    private boolean capture(Context current, CoopMessages.Message message, String peerId, String candidateIdentity) {
        if (identity != null && !identity.equals(candidateIdentity)) return false;
        if (held == null) {
            identity = candidateIdentity;
            held = new Held(stage, message, peerId, current.connectionGeneration());
        }
        matchingMessages++;
        return true;
    }

    /** Consumes before returning; the caller must pass the original envelope through its normal path. */
    public Held release(Context current, long now) {
        check(current, now);
        if (context == null || held == null) throw new IllegalStateException("No current held light message to release");
        Held result = held;
        finish("release_requested", now);
        return result;
    }

    /** Records the actual release path's disposition, never an inferred native application. */
    public void released(String disposition, long now) {
        Map<String, Object> receipt = new LinkedHashMap<>(lastReceipt);
        receipt.put("releaseDisposition", Objects.requireNonNull(disposition, "release disposition"));
        receipt.put("releaseCompletedAtMillis", now);
        lastReceipt = Map.copyOf(receipt);
    }

    public Map<String, Object> drop(String reason, long now) {
        if (context != null) finish(Objects.requireNonNull(reason, "drop reason"), now);
        return snapshot(now);
    }

    public void check(Context current, long now) {
        if (context == null) return;
        if (!CoopDebug.diagnosticsEnabled()) finish("diagnostics_disabled", now);
        else if (!sameContext(context, current)) finish("context_changed", now);
        else if (now >= deadline || now < armedAt) finish("expired_dropped", now);
    }

    public Map<String, Object> status(Context current, long now) {
        check(current, now);
        return snapshot(now);
    }

    private Map<String, Object> snapshot(long now) {
        return Map.of("armed", context != null, "held", held != null,
                "arm", context == null ? Map.of() : details(now), "lastReceipt", lastReceipt);
    }

    private Map<String, Object> details(long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("armId", armId); out.put("stage", stage); out.put("lightId", lightId);
        out.put("action", action == null ? "" : action.name()); out.put("armedAtMillis", armedAt);
        out.put("deadlineMillis", deadline); out.put("remainingMillis", Math.max(0L, deadline - now));
        out.put("sessionId", context.sessionId()); out.put("actorId", context.actorId());
        out.put("remoteActorId", context.remoteActorId()); out.put("connectionGeneration", context.connectionGeneration());
        out.put("sectorIdentity", Integer.toHexString(System.identityHashCode(context.sector())));
        out.put("ownerIdentity", Integer.toHexString(System.identityHashCode(context.owner())));
        out.put("latchedIdentity", identity == null ? "" : identity); out.put("matchingMessages", matchingMessages);
        if (held != null) {
            out.put("messageType", held.message().type().name()); out.put("messageSeq", held.message().seq());
            out.put("messageSentAtMillis", held.message().sentAtMillis());
            out.put("messageSenderId", held.message().senderId() == null ? "" : held.message().senderId());
            out.put("peerId", held.peerId() == null ? "" : held.peerId());
        }
        return Map.copyOf(out);
    }

    private void finish(String reason, long now) {
        Map<String, Object> receipt = new LinkedHashMap<>(details(now));
        receipt.put("reason", reason); receipt.put("finishedAtMillis", now);
        receipt.put("captured", held != null);
        lastReceipt = Map.copyOf(receipt);
        context = null;
        held = null;
        identity = null;
    }

    private static boolean sameContext(Context expected, Context current) {
        return current != null && expected.sector() == current.sector() && expected.owner() == current.owner()
                && expected.sessionId().equals(current.sessionId()) && expected.actorId().equals(current.actorId())
                && expected.remoteActorId().equals(current.remoteActorId())
                && expected.connectionGeneration() == current.connectionGeneration();
    }

    private static void requireContext(Context context) {
        if (context == null || context.sector() == null || context.owner() == null || context.sessionId() == null
                || context.sessionId().isBlank() || context.actorId() == null || context.actorId().isBlank()
                || context.remoteActorId() == null || context.remoteActorId().isBlank())
            throw new IllegalStateException("Light message hold requires the installed active Host context");
    }
}
