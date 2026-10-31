package coop.debug;

import com.fs.starfarer.api.campaign.SectorAPI;
import coop.campaign.CoopLightCodec;
import coop.campaign.CoopLightWorld;
import coop.util.CoopDebug;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit, transient one-shot interruption after a native light reward has actually returned. */
public final class CoopLightGrantFault {
    private static Arm armed;
    private static Map<String, Object> lastTrip = Map.of();
    private static Map<String, Object> lastDisarm = Map.of();

    private CoopLightGrantFault() { }

    /** Called by the installed World after it validates an existing, eligible local request. */
    public static synchronized Map<String, Object> arm(CoopLightWorld owner, SectorAPI sector,
                                                       String actorId, CoopLightCodec.Request request) {
        checkContext();
        if (!CoopDebug.diagnosticsEnabled()) throw new IllegalStateException("Light grant fault requires diagnostics");
        Objects.requireNonNull(owner, "fault owner");
        Objects.requireNonNull(sector, "fault sector");
        Objects.requireNonNull(request, "fault request");
        if (!owner.isCurrentGrantFaultContext(sector, actorId))
            throw new IllegalStateException("Light grant fault requires the currently installed Guest adapter");
        if (request.action() == CoopLightCodec.Action.DWELLER)
            throw new IllegalArgumentException("Dweller consumption has no native light reward to interrupt");
        if (!owner.isEligibleGrantFaultRequest(request))
            throw new IllegalStateException("Light grant fault requires an exact pending request without uncertain or applied state");
        if (armed != null) throw new IllegalStateException("A light grant fault is already armed; disarm it explicitly first");
        armed = new Arm(owner, sector, actorId, request);
        return status();
    }

    /** Re-checks the installed adapter without adopting a light or touching any saved state. */
    public static synchronized void checkContext() {
        if (armed == null) return;
        if (!CoopDebug.diagnosticsEnabled()) {
            disarm("diagnostics_disabled");
            return;
        }
        CoopLightWorld owner = armed.owner.get();
        SectorAPI sector = armed.sector.get();
        if (owner == null || sector == null || !owner.isCurrentGrantFaultContext(sector, armed.actorId))
            disarm("adapter_or_campaign_changed");
    }

    /** An unrelated or rejected outcome cancels the arm instead of leaving a later surprise. */
    public static synchronized void beforeOutcome(CoopLightWorld owner, CoopLightCodec.Outcome outcome) {
        checkContext();
        if (armed != null && (armed.owner.get() != owner || !armed.matches(outcome) || !outcome.accepted()))
            disarm("outcome_mismatch_or_rejection");
    }

    /** Consumed before throwing, after U was recorded in campaign memory and native grant returned. */
    public static synchronized void afterNativeGrant(CoopLightWorld owner, SectorAPI sector,
                                                      CoopLightCodec.Outcome outcome) {
        checkContext();
        if (armed == null) return;
        if (armed.owner.get() != owner || armed.sector.get() != sector || !armed.matches(outcome) || !outcome.accepted()) {
            disarm("outcome_mismatch_or_rejection");
            return;
        }
        Map<String, Object> receipt = new LinkedHashMap<>(armed.details);
        receipt.put("trippedAtMillis", System.currentTimeMillis());
        receipt.put("phase", "post_native_grant_before_applied");
        receipt.put("nativeGrantReturned", true);
        receipt.put("expectedBonus", outcome.bonus());
        receipt.put("expectedPoints", outcome.points());
        armed = null;
        lastTrip = Map.copyOf(receipt);
        throw new IllegalStateException("Injected one-shot light grant interruption after native reward for "
                + "requestId=" + outcome.requestId() + " lightId=" + outcome.lightId() + " actorId=" + outcome.actorId()
                + " armId=" + receipt.get("armId") + " phase=" + receipt.get("phase")
                + "; uncertain guard retained in campaign memory");
    }

    public static synchronized Map<String, Object> disarm(String reason) {
        Objects.requireNonNull(reason, "disarm reason");
        if (armed != null) {
            Map<String, Object> receipt = new LinkedHashMap<>(armed.details);
            receipt.put("disarmedAtMillis", System.currentTimeMillis());
            receipt.put("reason", reason);
            lastDisarm = Map.copyOf(receipt);
            armed = null;
        }
        return snapshot();
    }

    public static synchronized void disarmOwner(CoopLightWorld owner, String reason) {
        if (armed != null && armed.owner.get() == owner) disarm(reason);
    }

    public static synchronized Map<String, Object> status() {
        checkContext();
        return snapshot();
    }

    private static Map<String, Object> snapshot() {
        return Map.of("armed", armed != null, "arm", armed == null ? Map.of() : armed.details,
                "lastTrip", lastTrip, "lastDisarm", lastDisarm);
    }

    private static final class Arm {
        final WeakReference<CoopLightWorld> owner;
        final WeakReference<SectorAPI> sector;
        final String actorId;
        final CoopLightCodec.Request request;
        final Map<String, Object> details;

        Arm(CoopLightWorld owner, SectorAPI sector, String actorId, CoopLightCodec.Request request) {
            this.owner = new WeakReference<>(owner);
            this.sector = new WeakReference<>(sector);
            this.actorId = actorId;
            this.request = request;
            details = Map.of("armId", UUID.randomUUID().toString(), "armedAtMillis", System.currentTimeMillis(),
                    "worldIdentity", Integer.toHexString(System.identityHashCode(owner)),
                    "sectorIdentity", Integer.toHexString(System.identityHashCode(sector)), "actorId", actorId,
                    "requestId", request.requestId(), "lightId", request.lightId(), "action", request.action().name());
        }

        boolean matches(CoopLightCodec.Outcome outcome) {
            return actorId.equals(outcome.actorId()) && request.requestId().equals(outcome.requestId())
                    && request.lightId().equals(outcome.lightId()) && request.action() == outcome.action();
        }
    }
}
