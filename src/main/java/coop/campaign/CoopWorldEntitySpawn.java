package coop.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Details of a runtime-created world entity, carried inside a {@link CoopWorldDelta.Kind#SPAWN}
 * delta's {@code newStateJson} field (Phase 12d).
 *
 * <p>Player-created cargo pods are the reason this exists. V1 has no direct trade UI by design, and
 * pods are the vanilla way two players hand each other fuel, supplies, or a ship — but they are
 * created at runtime by one client and, before this, never came into being on the other, leaving v1
 * with no item transfer of any kind.
 *
 * <p>Two things must ride the wire rather than be recomputed. The <b>coop entity id</b> is assigned
 * by the creating client, because {@code Misc.addCargoPods} calls {@code addCustomEntity(null, ...)}
 * and the engine mints an independent id per client; matching on engine ids is impossible, so both
 * sides tag the entity with this id instead and the salvage watcher and {@link CoopWorldDelta.Ledger}
 * key on it. The <b>position and velocity</b> ride too, because {@code addCargoPods} draws its
 * velocity from {@code Math.random()} and would otherwise diverge.
 *
 * <p>Encoded as a single delimited string (the TCP envelope parser is flat — no JSON arrays), reusing
 * {@link CoopDelimited}. Layout: a header record, then one record per cargo stack.
 */
public record CoopWorldEntitySpawn(String coopEntityId, String entityType, String locationId,
                                   float x, float y, float velocityX, float velocityY,
                                   Map<String, Integer> contents) {

    /** Memory key both clients set on a replicated entity so it can be matched across the session. */
    public static final String COOP_ENTITY_TAG = "$coopEntityId";

    /**
     * What a pod can hold. Keys in {@link #contents} are {@code KIND:id} so one map carries every
     * stack type a dumped {@code CargoAPI} can contain; commodities alone would silently drop the
     * weapons, fighters, and ships players most want to hand each other.
     *
     * <p><b>SHIP fidelity limitation:</b> ships are keyed by variant id, so hull mods, D-mods, and CR
     * do not survive the handover — a battered ship arrives pristine. Same root cause as Phase 12c
     * gap 2a (market ship listings), which is now fixed: the codec that closes it is
     * {@link CoopShipDetail}, and porting it here means widening this map's value from a count to a
     * per-member record. Tracked in {@code docs/starsector-runtime-limitations.md}.
     */
    public enum ItemKind {
        COMMODITY,
        WEAPON,
        FIGHTER,
        SHIP,
        /**
         * A {@code SpecialItemData} stack — AI cores, nanoforges, blueprints, modspecs. Its id half of
         * the {@code KIND:id} key is {@link CoopMarketSync#specialItemId(String, String)}, not a bare
         * spec id, because a special is identified by id <em>and</em> its nullable data payload.
         *
         * <p>Before this kind existed a jettisoned AI core fell through the classifier's
         * {@code COMMODITY} default and was silently re-materialized as a commodity of the same id —
         * i.e. mangled into nothing. The default is gone with it: an unclassifiable stack is now
         * skipped, not guessed at.
         */
        SPECIAL
    }

    public static String key(ItemKind kind, String id) {
        return kind.name() + ":" + id;
    }

    public CoopWorldEntitySpawn {
        coopEntityId = requireText(coopEntityId, "coopEntityId");
        entityType = requireText(entityType, "entityType");
        locationId = CoopDelimited.normalize(locationId);
        Map<String, Integer> copy = new LinkedHashMap<>();
        if (contents != null) {
            for (Map.Entry<String, Integer> entry : contents.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                if (entry.getValue() > 0) {
                    copy.put(entry.getKey().trim(), entry.getValue());
                }
            }
        }
        contents = Map.copyOf(copy);
    }

    public String encode() {
        StringBuilder out = new StringBuilder(96);
        out.append(CoopDelimited.field(coopEntityId))
                .append('|').append(CoopDelimited.field(entityType))
                .append('|').append(CoopDelimited.field(locationId))
                .append('|').append(floatText(x))
                .append('|').append(floatText(y))
                .append('|').append(floatText(velocityX))
                .append('|').append(floatText(velocityY))
                .append('|').append(contents.size());
        // Sorted so the encoding is stable regardless of map iteration order (Phase 8 rule).
        List<String> ids = new ArrayList<>(contents.keySet());
        ids.sort(String::compareTo);
        for (String id : ids) {
            out.append('\n').append(CoopDelimited.field(id)).append('|').append(contents.get(id));
        }
        return out.toString();
    }

    public static CoopWorldEntitySpawn decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        List<String> header = CoopDelimited.split(lines[0]);
        if (header.size() < 8) {
            throw new IllegalArgumentException("Spawn header needs 8 fields, got " + header.size());
        }
        int count = Integer.parseInt(header.get(7).trim());
        if (lines.length - 1 < count) {
            throw new IllegalArgumentException("Declared " + count + " content stacks but only "
                    + (lines.length - 1) + " lines present");
        }
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            List<String> fields = CoopDelimited.split(lines[i + 1]);
            if (fields.size() < 2) {
                throw new IllegalArgumentException("Cargo stack record needs 2 fields");
            }
            contents.put(fields.get(0), Integer.parseInt(fields.get(1).trim()));
        }
        return new CoopWorldEntitySpawn(header.get(0), header.get(1), header.get(2),
                Float.parseFloat(header.get(3)), Float.parseFloat(header.get(4)),
                Float.parseFloat(header.get(5)), Float.parseFloat(header.get(6)),
                contents);
    }

    /** Locale-independent, so a comma-decimal locale cannot corrupt the wire format. */
    private static String floatText(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }
}
