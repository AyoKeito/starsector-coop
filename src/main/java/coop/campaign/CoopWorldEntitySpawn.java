package coop.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static coop.util.CoopText.requireText;

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
 * <p>Since stable-location construction rides the same kind, three more fields hang off the header:
 * the {@link #factionId} the entity flies (a built objective is {@code player}; a pod is neutral),
 * the {@link #consumedEntityId} the construction replaced (the stable location vanilla removes in
 * {@code Objectives.build}), and the {@link #orbit} to place it in. The orbit rides the wire rather
 * than being copied from the consumed entity on arrival so the two deltas are order-independent: the
 * {@code CONSUME} for that stable location is emitted by the same pass and may land first.
 *
 * <p>Encoded as a single delimited string (the TCP envelope parser is flat — no JSON arrays), reusing
 * {@link CoopDelimited}. Layout: a header record, then one record per cargo stack.
 */
public record CoopWorldEntitySpawn(String coopEntityId, String entityType, String locationId,
                                   float x, float y, float velocityX, float velocityY,
                                   Map<String, Integer> contents, String factionId,
                                   String consumedEntityId, Orbit orbit) {

    /** Memory key both clients set on a replicated entity so it can be matched across the session. */
    public static final String COOP_ENTITY_TAG = "$coopEntityId";

    /**
     * A circular orbit, in the only four numbers {@code SectorEntityToken.setCircularOrbit} takes.
     * {@link #NONE} means "this entity is placed at a fixed {@code x,y}" — which is every cargo pod.
     */
    public record Orbit(String focusId, float angle, float radius, float period) {
        public static final Orbit NONE = new Orbit("", 0f, 0f, 0f);

        public Orbit {
            focusId = CoopDelimited.normalize(focusId);
        }

        /** An orbit is only usable when it names a focus and actually turns. */
        public boolean isPresent() {
            return !focusId.isBlank() && period != 0f;
        }
    }

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

    /** The cargo-pod shape (Phase 12d): neutral faction, nothing consumed, fixed position. */
    public CoopWorldEntitySpawn(String coopEntityId, String entityType, String locationId,
                                float x, float y, float velocityX, float velocityY,
                                Map<String, Integer> contents) {
        this(coopEntityId, entityType, locationId, x, y, velocityX, velocityY, contents,
                "", "", Orbit.NONE);
    }

    public CoopWorldEntitySpawn {
        coopEntityId = requireText(coopEntityId, "coopEntityId");
        factionId = CoopDelimited.normalize(factionId);
        consumedEntityId = CoopDelimited.normalize(consumedEntityId);
        orbit = orbit == null ? Orbit.NONE : orbit;
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
        // Trailing header fields, appended after the stack count so an older-shaped header still
        // parses: decode reads them only when present.
        out.append('|').append(CoopDelimited.field(factionId))
                .append('|').append(CoopDelimited.field(consumedEntityId))
                .append('|').append(CoopDelimited.field(orbit.focusId()))
                .append('|').append(floatText(orbit.angle()))
                .append('|').append(floatText(orbit.radius()))
                .append('|').append(floatText(orbit.period()));
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
        Orbit orbit = header.size() > 13
                ? new Orbit(header.get(10), Float.parseFloat(header.get(11)),
                        Float.parseFloat(header.get(12)), Float.parseFloat(header.get(13)))
                : Orbit.NONE;
        return new CoopWorldEntitySpawn(header.get(0), header.get(1), header.get(2),
                Float.parseFloat(header.get(3)), Float.parseFloat(header.get(4)),
                Float.parseFloat(header.get(5)), Float.parseFloat(header.get(6)),
                contents,
                header.size() > 8 ? header.get(8) : "",
                header.size() > 9 ? header.get(9) : "",
                orbit);
    }

    /** Locale-independent, so a comma-decimal locale cannot corrupt the wire format. */
    private static String floatText(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

}
