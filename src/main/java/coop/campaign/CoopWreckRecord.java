package coop.campaign;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Host-owned native wreck recipe. Sensor discovery and faders are deliberately local. */
public record CoopWreckRecord(int version, String id, String hostEntityId, String name,
                              String factionId, float x, float y, float vx, float vy,
                              float facing, float angularVelocity, float elapsedDays,
                              float durationDays, Boolean expiring, boolean canHaveExtraCargo,
                              Ship ship, long salvageSeed, Recovery recovery, Recovery previousRecovery,
                              List<Drop> dropValue, List<Drop> dropRandom,
                              ExtraCargo extraSalvage, ExtraCargo temporaryExtraSalvage,
                              Float discoveryXP, Float salvageXP, float sensorProfile,
                              boolean discoverable, float radius, List<Modifier> detectionModifiers,
                              List<String> tags, List<MemoryValue> memory) {
    public static final int VERSION = 1;
    public static final int MAX_WRECKS = 1024;
    public static final int MAX_ENCODED_CHARACTERS = 768 * 1024;
    public static final int MAX_COLLECTION = 1024;
    public static final int MAX_STRING = 64 * 1024;

    public CoopWreckRecord {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported wreck version: " + version);
        id = canonicalId(id);
        hostEntityId = text(hostEntityId, false);
        name = text(name, true);
        factionId = text(factionId, false);
        for (float v : new float[]{x, y, vx, vy, facing, angularVelocity, elapsedDays,
                durationDays, sensorProfile, radius}) finite(v);
        if (Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000
                || Math.abs(vx) > 100_000 || Math.abs(vy) > 100_000
                || elapsedDays < 0 || durationDays < 0 || radius < 0 || radius > 100_000) {
            throw new IllegalArgumentException("Wreck numeric range");
        }
        nullableFinite(discoveryXP); nullableFinite(salvageXP);
        ship = Objects.requireNonNull(ship, "ship");
        dropValue = list(dropValue); dropRandom = list(dropRandom);
        extraSalvage = Objects.requireNonNull(extraSalvage, "extraSalvage");
        temporaryExtraSalvage = Objects.requireNonNull(temporaryExtraSalvage, "temporaryExtraSalvage");
        detectionModifiers = list(detectionModifiers); tags = strings(tags); memory = list(memory);
        unique(tags, "tags");
        unique(memory.stream().map(MemoryValue::key).toList(), "memory keys");
        unique(detectionModifiers.stream().map(m -> m.kind() + ":" + m.source()).toList(), "modifiers");
    }

    /** A variant id and a resolved variant are distinct native storage forms. */
    public record Ship(String condition, String variantId, CoopShipDetail variant, List<VariantState> variantState,
                       CoopPersonDetail captain, CaptainState captainState, String shipName, String fleetMemberId,
                       boolean addDmods, boolean pruneWeapons, Boolean nameAlwaysKnown, float sModProb) {
        public Ship {
            condition = text(condition, false);
            if (!Set.of("PRISTINE", "GOOD", "AVERAGE", "BATTERED", "WRECKED").contains(condition))
                throw new IllegalArgumentException("Unknown wreck ship condition");
            variantId = nullableText(variantId); shipName = nullableText(shipName);
            fleetMemberId = nullableText(fleetMemberId);
            variantState = list(variantState);
            if (captain == null && captainState != null) throw new IllegalArgumentException("Captain metadata without captain");
            if (variant == null && !variantState.isEmpty()) throw new IllegalArgumentException("Variant metadata without variant");
            if (variantState.stream().map(VariantState::modulePath).distinct().count() != variantState.size())
                throw new IllegalArgumentException("Duplicate variant metadata path");
            if (variantId == null && variant == null) throw new IllegalArgumentException("Wreck has no variant");
            finite(sModProb);
            if (sModProb < 0 || sModProb > 1) throw new IllegalArgumentException("Invalid s-mod probability");
        }
        public Ship(String condition, String variantId, CoopShipDetail variant, CoopPersonDetail captain,
                    String shipName, String fleetMemberId, boolean addDmods, boolean pruneWeapons,
                    Boolean nameAlwaysKnown, float sModProb) {
            this(condition, variantId, variant, List.of(), captain, null, shipName, fleetMemberId,
                    addDmods, pruneWeapons, nameAlwaysKnown, sModProb);
        }
        public Ship(String condition, String variantId, CoopShipDetail variant, List<VariantState> variantState,
                    CoopPersonDetail captain, String shipName, String fleetMemberId, boolean addDmods,
                    boolean pruneWeapons, Boolean nameAlwaysKnown, float sModProb) {
            this(condition, variantId, variant, variantState, captain, null, shipName, fleetMemberId,
                    addDmods, pruneWeapons, nameAlwaysKnown, sModProb);
        }
    }

    public record CaptainState(String aiCoreId, List<String> tags, String importance, String voice,
                               List<MemoryValue> memory) {
        public CaptainState {
            aiCoreId = nullableText(aiCoreId); tags = strings(tags); unique(tags, "captain tags");
            importance = nullableText(importance); voice = nullableText(voice);
            memory = list(memory); unique(memory.stream().map(MemoryValue::key).toList(), "captain memory");
            if (importance != null && !Set.of("VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH").contains(importance))
                throw new IllegalArgumentException("Unknown captain importance");
        }
        public CaptainState(String aiCoreId, List<String> tags, String importance, String voice) {
            this(aiCoreId, tags, importance, voice, List.of());
        }
    }

    /** Tags can change boardability; source and original variant are native autofit inputs. */
    public record VariantState(List<String> modulePath, List<String> tags, String source,
                               String originalVariant, boolean goalVariant, boolean mayAutoAssignWeapons) {
        public VariantState {
            modulePath = strings(modulePath); tags = strings(tags); unique(tags, "variant tags");
            if (modulePath.size() > CoopShipDetail.MAX_MODULE_NESTING) throw new IllegalArgumentException("Variant metadata too deep");
            source = nullableText(source); originalVariant = nullableText(originalVariant);
            if (source != null && !Set.of("STOCK", "MISSION_DESIGN", "MISSION_SAVE", "REFIT", "HULL").contains(source))
                throw new IllegalArgumentException("Unknown variant source");
        }
    }

    public record Recovery(String description, Boolean storyPointRecovery,
                           Boolean notNowOptionExits, Boolean noDescriptionText, List<Ship> ships,
                           Special special) {
        public Recovery {
            description = nullableText(description); ships = list(ships);
            if (special != null && (description != null || storyPointRecovery != null
                    || notNowOptionExits != null || noDescriptionText != null || !ships.isEmpty()))
                throw new IllegalArgumentException("Mixed native salvage special families");
        }
        public Recovery(String description, Boolean storyPointRecovery, Boolean notNowOptionExits,
                        Boolean noDescriptionText, List<Ship> ships) {
            this(description, storyPointRecovery, notNowOptionExits, noDescriptionText, ships, null);
        }
        public static Recovery special(Special value) {
            return new Recovery(null, null, null, null, List.of(), Objects.requireNonNull(value));
        }
    }

    /** Other native wreck discoveries, including their already-generated officer/admin. */
    public record Special(String type, List<String> values, CoopPersonDetail person, CaptainState personState) {
        public Special {
            type = text(type, false); Objects.requireNonNull(values);
            if (values.size() > MAX_COLLECTION) throw new IllegalArgumentException("Too many special fields");
            List<String> checked = new ArrayList<>(); for (String value : values) checked.add(nullableText(value));
            values = Collections.unmodifiableList(checked);
            if (person == null && personState != null) throw new IllegalArgumentException("Person metadata without person");
            int expected = switch (type) {
                case "SLEEPER_PODS", "CARGO_MANIFEST" -> 3; case "SURVEY_DATA" -> 4;
                case "BREADCRUMB" -> 2; case "TOPOGRAPHIC" -> 1; case "BLUEPRINT" -> 0;
                case "TRANSMITTER_TRAP" -> 8;
                default -> throw new IllegalArgumentException("Unsupported native salvage special " + type);
            };
            if (values.size() != expected) throw new IllegalArgumentException("Wrong native special field count");
            switch (type) {
                case "SLEEPER_PODS" -> {
                    if (!Set.of("CREW", "MARINES", "OFFICER", "ADMIN", "ORGANS").contains(values.get(0)))
                        throw new IllegalArgumentException("Unknown sleeper special type");
                    Integer.parseInt(values.get(1)); Integer.parseInt(values.get(2));
                }
                case "CARGO_MANIFEST" -> { finite(Float.parseFloat(values.get(1))); finite(Float.parseFloat(values.get(2))); }
                case "SURVEY_DATA" -> {
                    String kind = values.get(0);
                    if (kind != null && !Set.of("SCRAMBLED", "PLANET_SURVEY_DATA", "PLANET_INTERESTING_PROPERTY",
                            "SYSTEM_PRELIMINARY_SURVEY", "AUTO_PICK", "AUTO_PICK_NOT_SYSTEM").contains(kind))
                        throw new IllegalArgumentException("Unknown survey special type");
                    if (!Set.of("true", "false").contains(values.get(3))) throw new IllegalArgumentException("Bad survey flag");
                }
                case "TOPOGRAPHIC" -> Integer.parseInt(values.get(0));
                case "TRANSMITTER_TRAP" -> {
                    finite(Float.parseFloat(values.get(0))); finite(Float.parseFloat(values.get(5))); finite(Float.parseFloat(values.get(6)));
                    for (int i : new int[]{3, 4}) if (values.get(i) != null && !Set.of("true", "false").contains(values.get(i)))
                        throw new IllegalArgumentException("Invalid trap flag");
                }
                default -> { }
            }
        }
    }

    public record Drop(String group, int chances, int maxChances, int value, float valueMult,
                       List<DropRow> custom) {
        public Drop { group = nullableText(group); finite(valueMult); custom = list(custom); }
    }

    public record DropRow(String commodity, String group, float frequency, float weight) {
        public DropRow {
            commodity = text(commodity, false); group = text(group, true);
            finite(frequency); finite(weight);
            if (frequency < 0 || weight < 0) throw new IllegalArgumentException("Negative drop weight");
        }
    }

    /** Special item id and data are separate, including colons and empty/null data. */
    public record Cargo(String type, String id, String data, float quantity) {
        public Cargo {
            type = text(type, false); id = text(id, false); data = nullableText(data); finite(quantity);
            if (!Set.of("RESOURCES", "WEAPONS", "FIGHTER_CHIP", "SPECIAL").contains(type)
                    || quantity < 0 || quantity > 1_000_000_000f)
                throw new IllegalArgumentException("Invalid wreck cargo");
        }
    }

    public record ExtraCargo(boolean present, float expiresDays, List<Cargo> cargo) {
        public ExtraCargo {
            finite(expiresDays); cargo = list(cargo);
            if (!present && !cargo.isEmpty()) throw new IllegalArgumentException("Absent extra cargo has stacks");
        }
        public static ExtraCargo absent() { return new ExtraCargo(false, -1f, List.of()); }
    }

    public record Modifier(String kind, String source, String description, float value) {
        public Modifier {
            kind = text(kind, false); source = text(source, false); description = nullableText(description);
            if (!Set.of("FLAT", "PERCENT", "MULT").contains(kind)) throw new IllegalArgumentException("Bad stat kind");
            finite(value);
        }
    }

    public record MemoryValue(String key, String type, String value, float expiresDays) {
        public MemoryValue {
            key = text(key, false); type = text(type, false); value = text(value, true); finite(expiresDays);
            switch (type) {
                case "STRING" -> { }
                case "BOOLEAN" -> { if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Bad boolean"); }
                case "LONG" -> Long.parseLong(value);
                case "DOUBLE" -> { if (!Double.isFinite(Double.parseDouble(value))) throw new IllegalArgumentException("Bad number"); }
                default -> throw new IllegalArgumentException("Bad memory type");
            }
        }
    }

    public String encode() {
        Writer w = new Writer();
        w.add(version); w.add(id); w.add(hostEntityId); w.add(name); w.add(factionId);
        w.add(x); w.add(y); w.add(vx); w.add(vy); w.add(facing); w.add(angularVelocity);
        w.add(elapsedDays); w.add(durationDays); w.optional(expiring); w.add(canHaveExtraCargo);
        w.ship(ship); w.add(salvageSeed); w.recovery(recovery); w.recovery(previousRecovery);
        w.drops(dropValue); w.drops(dropRandom); w.extra(extraSalvage); w.extra(temporaryExtraSalvage);
        w.optional(discoveryXP); w.optional(salvageXP); w.add(sensorProfile); w.add(discoverable); w.add(radius);
        w.add(detectionModifiers.size());
        for (Modifier m : detectionModifiers) { w.add(m.kind); w.add(m.source); w.nullable(m.description); w.add(m.value); }
        w.add(tags.size()); for (String tag : tags) w.add(tag);
        w.add(memory.size());
        for (MemoryValue m : memory) { w.add(m.key); w.add(m.type); w.add(m.value); w.add(m.expiresDays); }
        return w.finish();
    }

    public static CoopWreckRecord decode(String encoded) {
        Reader r = new Reader(encoded);
        int version = r.integer(); String id = r.next(), hostId = r.next(), name = r.next(), faction = r.next();
        float x = r.number(), y = r.number(), vx = r.number(), vy = r.number(), facing = r.number();
        float angular = r.number(), elapsed = r.number(), duration = r.number();
        Boolean expiring = r.optionalBoolean(); boolean extra = r.bool();
        Ship ship = r.ship(); long seed = r.longInteger(); Recovery recovery = r.recovery(), previous = r.recovery();
        List<Drop> value = r.drops(), random = r.drops(); ExtraCargo cargo = r.extra(), temporary = r.extra();
        Float discoveryXP = r.optionalFloat(), salvageXP = r.optionalFloat();
        float sensor = r.number(); boolean discoverable = r.bool(); float radius = r.number();
        List<Modifier> modifiers = new ArrayList<>();
        for (int n = r.count(); n > 0; n--) modifiers.add(new Modifier(r.next(), r.next(), r.nullable(), r.number()));
        List<String> tags = new ArrayList<>(); for (int n = r.count(); n > 0; n--) tags.add(r.next());
        List<MemoryValue> memory = new ArrayList<>();
        for (int n = r.count(); n > 0; n--) memory.add(new MemoryValue(r.next(), r.next(), r.next(), r.number()));
        r.end();
        return new CoopWreckRecord(version, id, hostId, name, faction, x, y, vx, vy, facing, angular,
                elapsed, duration, expiring, extra, ship, seed, recovery, previous, value, random, cargo,
                temporary, discoveryXP, salvageXP, sensor, discoverable, radius, modifiers, tags, memory);
    }

    public static String encodeIds(Collection<String> ids) {
        List<String> ordered = new ArrayList<>(Objects.requireNonNull(ids));
        if (ordered.size() > MAX_WRECKS) throw new IllegalArgumentException("Too many wrecks");
        ordered.replaceAll(CoopWreckRecord::canonicalId); unique(ordered, "wreck ids");
        ordered.sort(Comparator.naturalOrder());
        Writer w = new Writer(); w.add(ordered.size()); for (String id : ordered) w.add(id); return w.finish();
    }

    public static List<String> decodeIds(String encoded) {
        Reader r = new Reader(encoded); List<String> ids = new ArrayList<>();
        for (int n = r.count(); n > 0; n--) ids.add(canonicalId(r.next())); r.end();
        unique(ids, "wreck ids"); return List.copyOf(ids);
    }

    private static String canonicalId(String value) {
        value = text(value, false);
        if (!value.startsWith("wreck:") || value.length() <= 6 || value.length() > 256
                || !value.substring(6).matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid canonical wreck id");
        return value;
    }
    private static void finite(float value) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite wreck number"); }
    private static void nullableFinite(Float value) { if (value != null) finite(value); }
    private static String nullableText(String value) { return value == null ? null : text(value, true); }
    private static String text(String value, boolean empty) {
        Objects.requireNonNull(value, "text");
        if (value.length() > MAX_STRING || (!empty && value.isEmpty())) throw new IllegalArgumentException("Invalid wreck text");
        return value;
    }
    private static <T> List<T> list(Collection<T> values) {
        Objects.requireNonNull(values, "list");
        if (values.size() > MAX_COLLECTION) throw new IllegalArgumentException("Wreck collection too large");
        return List.copyOf(values);
    }
    private static List<String> strings(Collection<String> values) {
        List<String> checked = list(values); checked.forEach(v -> text(v, false)); return checked;
    }
    private static void unique(Collection<String> values, String label) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Duplicate " + label);
    }

    private static final class Writer {
        private final List<String> fields = new ArrayList<>();
        void add(Object value) { fields.add(CoopDelimited.field(String.valueOf(value))); }
        void nullable(String value) { add(value != null); if (value != null) add(value); }
        void optional(Object value) { add(value == null ? "" : value); }
        void ship(Ship s) {
            add(s.condition); nullable(s.variantId); nullable(s.variant == null ? null : s.variant.encode());
            add(s.variantState.size()); for (VariantState state : s.variantState) {
                add(state.modulePath.size()); for (String slot : state.modulePath) add(slot);
                add(state.tags.size()); for (String tag : state.tags) add(tag);
                nullable(state.source); nullable(state.originalVariant); add(state.goalVariant); add(state.mayAutoAssignWeapons);
            }
            nullable(s.captain == null ? null : s.captain.encode()); captainState(s.captainState);
            nullable(s.shipName); nullable(s.fleetMemberId);
            add(s.addDmods); add(s.pruneWeapons); optional(s.nameAlwaysKnown); add(s.sModProb);
        }
        void recovery(Recovery value) {
            add(value != null); if (value == null) return;
            nullable(value.description); optional(value.storyPointRecovery); optional(value.notNowOptionExits);
            optional(value.noDescriptionText); add(value.ships.size()); value.ships.forEach(this::ship);
            add(value.special != null); if (value.special != null) {
                add(value.special.type); add(value.special.values.size());
                for (String field : value.special.values) nullable(field);
                nullable(value.special.person == null ? null : value.special.person.encode()); captainState(value.special.personState);
            }
        }
        void captainState(CaptainState state) {
            add(state != null); if (state == null) return;
            nullable(state.aiCoreId); add(state.tags.size()); for (String tag : state.tags) add(tag);
            nullable(state.importance); nullable(state.voice); add(state.memory.size());
            for (MemoryValue value : state.memory) { add(value.key); add(value.type); add(value.value); add(value.expiresDays); }
        }
        void drops(List<Drop> drops) {
            add(drops.size()); for (Drop d : drops) {
                nullable(d.group); add(d.chances); add(d.maxChances); add(d.value); add(d.valueMult); add(d.custom.size());
                for (DropRow row : d.custom) { add(row.commodity); add(row.group); add(row.frequency); add(row.weight); }
            }
        }
        void extra(ExtraCargo value) {
            add(value.present); add(value.expiresDays); add(value.cargo.size());
            for (Cargo c : value.cargo) { add(c.type); add(c.id); nullable(c.data); add(c.quantity); }
        }
        String finish() {
            String result = String.join("|", fields);
            if (result.length() > MAX_ENCODED_CHARACTERS) throw new IllegalArgumentException("Wreck payload too large");
            return result;
        }
    }

    private static final class Reader {
        private final List<String> fields; private int index;
        Reader(String text) {
            if (text == null || text.length() > MAX_ENCODED_CHARACTERS) throw new IllegalArgumentException("Wreck payload too large/null");
            fields = CoopDelimited.split(text);
        }
        String next() { if (index >= fields.size()) throw new IllegalArgumentException("Truncated wreck payload"); return text(fields.get(index++), true); }
        int integer() { return Integer.parseInt(next()); }
        long longInteger() { return Long.parseLong(next()); }
        float number() { float value = Float.parseFloat(next()); finite(value); return value; }
        boolean bool() { String v = next(); if (v.equals("true")) return true; if (v.equals("false")) return false; throw new IllegalArgumentException("Bad wreck boolean"); }
        int count() { int n = integer(); if (n < 0 || n > MAX_COLLECTION) throw new IllegalArgumentException("Bad wreck count"); return n; }
        String nullable() { return bool() ? next() : null; }
        Boolean optionalBoolean() { String v = next(); if (v.isEmpty()) return null; if (v.equals("true")) return true; if (v.equals("false")) return false; throw new IllegalArgumentException("Bad nullable boolean"); }
        Float optionalFloat() { String v = next(); if (v.isEmpty()) return null; float f = Float.parseFloat(v); finite(f); return f; }
        Ship ship() {
            String condition = next(), variantId = nullable(), detail = nullable();
            List<VariantState> metadata = new ArrayList<>();
            for (int n = count(); n > 0; n--) {
                List<String> path = new ArrayList<>(); for (int m = count(); m > 0; m--) path.add(next());
                List<String> tags = new ArrayList<>(); for (int m = count(); m > 0; m--) tags.add(next());
                metadata.add(new VariantState(path, tags, nullable(), nullable(), bool(), bool()));
            }
            String captain = nullable();
            CaptainState captainState = captainState();
            return new Ship(condition, variantId, detail == null ? null : CoopShipDetail.decode(detail), metadata,
                    captain == null ? null : CoopPersonDetail.decode(captain), captainState,
                    nullable(), nullable(), bool(), bool(), optionalBoolean(), number());
        }
        Recovery recovery() {
            if (!bool()) return null;
            String description = nullable(); Boolean story = optionalBoolean(), exits = optionalBoolean(), noText = optionalBoolean();
            List<Ship> ships = new ArrayList<>(); for (int n = count(); n > 0; n--) ships.add(ship());
            Special special = null;
            if (bool()) {
                String type = next(); List<String> values = new ArrayList<>();
                for (int n = count(); n > 0; n--) values.add(nullable());
                String person = nullable(); CaptainState state = captainState();
                special = new Special(type, values, person == null ? null : CoopPersonDetail.decode(person), state);
            }
            return new Recovery(description, story, exits, noText, ships, special);
        }
        CaptainState captainState() {
            if (!bool()) return null;
            String core = nullable(); List<String> tags = new ArrayList<>();
            for (int n = count(); n > 0; n--) tags.add(next());
            String importance = nullable(), voice = nullable(); List<MemoryValue> memory = new ArrayList<>();
            for (int n = count(); n > 0; n--) memory.add(new MemoryValue(next(), next(), next(), number()));
            return new CaptainState(core, tags, importance, voice, memory);
        }
        List<Drop> drops() {
            List<Drop> drops = new ArrayList<>(); for (int n = count(); n > 0; n--) {
                String group = nullable(); int chances = integer(), max = integer(), value = integer(); float mult = number();
                List<DropRow> rows = new ArrayList<>();
                for (int m = count(); m > 0; m--) rows.add(new DropRow(next(), next(), number(), number()));
                drops.add(new Drop(group, chances, max, value, mult, rows));
            } return drops;
        }
        ExtraCargo extra() {
            boolean present = bool(); float expiry = number(); List<Cargo> cargo = new ArrayList<>();
            for (int n = count(); n > 0; n--) cargo.add(new Cargo(next(), next(), nullable(), number()));
            return new ExtraCargo(present, expiry, cargo);
        }
        void end() { if (index != fields.size()) throw new IllegalArgumentException("Trailing wreck fields"); }
    }
}
