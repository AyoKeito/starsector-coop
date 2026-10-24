package coop.campaign;

import coop.net.CoopJson;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A revision-checked salvage change. Only the salvage projection may be applied to the host. */
public record CoopWreckMutation(String mutationId, long baseRevision, CoopWreckRecord after, boolean interacted) {
    public CoopWreckMutation(String mutationId, long baseRevision, CoopWreckRecord after) {
        this(mutationId, baseRevision, after, false);
    }
    public CoopWreckMutation {
        if (mutationId == null || !mutationId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid wreck mutation id");
        if (baseRevision < 0 || baseRevision == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid wreck revision");
        Objects.requireNonNull(after, "after");
    }

    public String id() { return after.id(); }
    public String encode() {
        return "{\"mutationId\":\"" + CoopJson.escape(mutationId) + "\",\"base\":" + baseRevision
                + ",\"after\":\"" + CoopJson.escape(after.encode()) + "\",\"interaction\":\"" + interacted + "\"}";
    }
    public static CoopWreckMutation decode(String encoded) {
        Map<String, Object> p = object(encoded);
        return new CoopWreckMutation(string(p, "mutationId"), number(p, "base"),
                CoopWreckRecord.decode(string(p, "after")), switch (string(p, "interaction")) {
                    case "true" -> true; case "false" -> false;
                    default -> throw new IllegalArgumentException("Invalid mutation interaction flag");
                });
    }

    public enum Status { ACCEPTED, CONFLICT, CONSUMED }
    public record Ack(String mutationId, String id, Status status, long revision, CoopWreckRecord current) {
        public Ack {
            if (mutationId == null || !mutationId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid mutation acknowledgement");
            CoopWreckRecord.encodeIds(List.of(id));
            Objects.requireNonNull(status);
            if (revision < 0 || current != null && !id.equals(current.id())) throw new IllegalArgumentException("Invalid acknowledgement state");
            if (status != Status.CONSUMED && current == null) throw new IllegalArgumentException("Missing acknowledged wreck state");
        }
        public String encode() {
            return "{\"mutationId\":\"" + CoopJson.escape(mutationId) + "\",\"id\":\"" + CoopJson.escape(id)
                    + "\",\"status\":\"" + status + "\",\"revision\":" + revision + ",\"current\":\""
                    + CoopJson.escape(current == null ? "" : current.encode()) + "\"}";
        }
        public static Ack decode(String encoded) {
            Map<String, Object> p = object(encoded);
            String state = string(p, "current");
            return new Ack(string(p, "mutationId"), string(p, "id"), Status.valueOf(string(p, "status")),
                    number(p, "revision"), state.isEmpty() ? null : CoopWreckRecord.decode(state));
        }
    }

    /** Lifetimes count down natively and local descriptive prose is not a salvage outcome. */
    public static boolean sameRecipe(CoopWreckRecord a, CoopWreckRecord b) {
        return sameRecipe(a, b, false);
    }
    public static boolean sameRecipe(CoopWreckRecord a, CoopWreckRecord b, boolean interacted) {
        return Objects.equals(a.recovery(), b.recovery()) && Objects.equals(a.previousRecovery(), b.previousRecovery())
                && a.dropValue().equals(b.dropValue()) && a.dropRandom().equals(b.dropRandom())
                && sameCargo(a.extraSalvage(), b.extraSalvage(), interacted)
                && sameCargo(a.temporaryExtraSalvage(), b.temporaryExtraSalvage(), interacted)
                && memory(a).equals(memory(b));
    }
    private static boolean sameCargo(CoopWreckRecord.ExtraCargo a, CoopWreckRecord.ExtraCargo b, boolean interacted) {
        if (!interacted && a.present() != b.present()) {
            CoopWreckRecord.ExtraCargo present = a.present() ? a : b;
            if (present.expiresDays() > 0f) return true;
        }
        return a.present() == b.present() && a.cargo().equals(b.cargo());
    }
    private static List<String> memory(CoopWreckRecord r) {
        return r.memory().stream().filter(v -> !v.key().equals("$fromGhost") && !v.key().equals(MemFlags.RECENTLY_SALVAGED))
                .map(v -> v.key() + "\n" + v.type() + "\n" + v.value()).sorted().toList();
    }

    /** All identity, physical state, base ship and sensor fields come from the authoritative target. */
    public CoopWreckRecord onto(CoopWreckRecord target) {
        return new CoopWreckRecord(target.version(), target.id(), target.hostEntityId(), target.name(),
                target.factionId(), target.x(), target.y(), target.vx(), target.vy(), target.facing(),
                target.angularVelocity(), target.elapsedDays(), target.durationDays(), target.expiring(),
                target.canHaveExtraCargo(), target.ship(), target.salvageSeed(), after.recovery(),
                after.previousRecovery(), after.dropValue(), after.dropRandom(),
                sameCargo(after.extraSalvage(), target.extraSalvage(), interacted) ? target.extraSalvage() : after.extraSalvage(),
                sameCargo(after.temporaryExtraSalvage(), target.temporaryExtraSalvage(), interacted)
                        ? target.temporaryExtraSalvage() : after.temporaryExtraSalvage(),
                target.discoveryXP(), target.salvageXP(), target.sensorProfile(), target.discoverable(),
                target.radius(), target.detectionModifiers(), target.tags(), after.memory().stream().map(v ->
                        target.memory().stream().filter(old -> old.key().equals(v.key()) && old.type().equals(v.type())
                                && old.value().equals(v.value())).findFirst().orElse(v)).toList());
    }

    public static CoopWreckRecord trapOnly(CoopWreckRecord baseline, CoopWreckRecord current) {
        java.util.ArrayList<CoopWreckRecord.MemoryValue> memory = new java.util.ArrayList<>(baseline.memory());
        memory.removeIf(v -> v.key().equals(CoopWreckWorld.TRAP_ACTIVATED));
        current.memory().stream().filter(v -> v.key().equals(CoopWreckWorld.TRAP_ACTIVATED)).forEach(memory::add);
        return new CoopWreckRecord(baseline.version(), baseline.id(), baseline.hostEntityId(), baseline.name(),
                baseline.factionId(), baseline.x(), baseline.y(), baseline.vx(), baseline.vy(), baseline.facing(),
                baseline.angularVelocity(), baseline.elapsedDays(), baseline.durationDays(), baseline.expiring(),
                baseline.canHaveExtraCargo(), baseline.ship(), baseline.salvageSeed(), baseline.recovery(),
                baseline.previousRecovery(), baseline.dropValue(), baseline.dropRandom(), baseline.extraSalvage(),
                baseline.temporaryExtraSalvage(), baseline.discoveryXP(), baseline.salvageXP(), baseline.sensorProfile(),
                baseline.discoverable(), baseline.radius(), baseline.detectionModifiers(), baseline.tags(), memory);
    }

    private static Map<String, Object> object(String encoded) {
        if (encoded == null || encoded.length() > 4 * CoopWreckRecord.MAX_ENCODED_CHARACTERS)
            throw new IllegalArgumentException("Wreck mutation too large");
        return CoopJson.parseObject(encoded);
    }
    private static String string(Map<String, Object> p, String key) {
        if (!(p.get(key) instanceof String s)) throw new IllegalArgumentException("Missing mutation field " + key);
        return s;
    }
    private static long number(Map<String, Object> p, String key) {
        if (!(p.get(key) instanceof Long n)) throw new IllegalArgumentException("Missing mutation number " + key);
        return n;
    }
}
