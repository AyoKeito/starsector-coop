package coop.campaign;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Saved, resolved abyssal creation recipe and current shared outcomes. Contains no engine state. */
public record CoopEncounterRecord(int version, String instanceId, long effectiveSeed,
                                  String encounterPointId, float x, float y, float depth,
                                  boolean threatRollRan, Creator creator, boolean threatTag,
                                  List<Entity> entities, List<Special> specials, Overlay overlay) {
    public static final int VERSION = 1;
    public static final int MAX_ENCOUNTERS = 128;
    public static final int MAX_ENTITIES = 4096;
    public static final int MAX_SPECIALS = 256;
    public static final int MAX_MEMORY_VALUES = 32;
    public static final int MAX_ID_LENGTH = 256;
    public static final int MAX_STRING_LENGTH = 4096;
    public static final Set<String> MEMORY_KEYS = Set.of("$abyssalBlackHoleReadings",
            "$abyssalBlackHoleReadingsRevisit", "$abyssalGasGiantTurbulence",
            "$abyssalPlanetoidMiningOp", "$gsType", "$visited");
    private static final Set<String> SURVEY_LEVELS = Set.of("NONE", "SEEN", "PRELIMINARY", "FULL");
    private static final Pattern ENTITY_ID = Pattern.compile(
            "coopenc_([A-Za-z0-9_-]{1,128})_(system|anchor|jump_point|well)([0-9]{1,4})");

    public enum Creator {
        PARENT("com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectEPEC"),
        DIRE_HINTS("com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectDireHintsEPEC");

        private final String className;
        Creator(String className) { this.className = className; }
        public String className() { return className; }
        public static Creator fromClassName(String className) {
            for (Creator creator : values()) if (creator.className.equals(className)) return creator;
            throw new IllegalArgumentException("Unsupported encounter creator: " + className);
        }
    }

    /** Ordinals are original creation membership, never recomputed from the surviving subset. */
    public enum Role { SYSTEM, ANCHOR, JUMP_POINT, WELL }
    public enum SpecialType { GHOST_WRECK, VAMBRACE, PLANET_FLAGS }

    public CoopEncounterRecord {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported encounter version: " + version);
        instanceId = checkedInstanceId(instanceId);
        encounterPointId = text(encounterPointId, MAX_ID_LENGTH, false);
        coordinate(x); coordinate(y); finite(depth);
        if (depth < 0 || depth > 1_000_000f) throw new IllegalArgumentException("Encounter depth out of bounds");
        creator = Objects.requireNonNull(creator, "creator");
        entities = sorted(entities, MAX_ENTITIES, Comparator.comparing(Entity::role).thenComparingInt(Entity::ordinal));
        if (entities.isEmpty()) throw new IllegalArgumentException("Encounter membership is empty");
        Set<String> ids = new HashSet<>();
        for (Entity entity : entities) if (!ids.add(entityId(instanceId, entity.role(), entity.ordinal())))
            throw new IllegalArgumentException("Duplicate encounter membership");
        specials = sorted(specials, MAX_SPECIALS, Comparator.comparing(Special::entityId));
        Set<String> specialIds = new HashSet<>();
        for (Special special : specials) {
            requireMember(ids, special.entityId());
            if (!specialIds.add(special.entityId())) throw new IllegalArgumentException("Duplicate encounter special");
            if (special.orbit() != null) requireMember(ids, special.orbit().focusId());
        }
        overlay = Objects.requireNonNull(overlay, "overlay");
        for (String id : overlay.consumedIds()) requireMember(ids, id);
        for (EntityState state : overlay.entities()) {
            requireMember(ids, state.entityId());
            if (overlay.consumedIds().contains(state.entityId()))
                throw new IllegalArgumentException("Consumed entity has a surviving overlay");
        }
    }

    public CoopEncounterRecord(String instanceId, long effectiveSeed, String encounterPointId,
                               float x, float y, float depth, boolean threatRollRan, Creator creator,
                               boolean threatTag, List<Entity> entities, List<Special> specials, Overlay overlay) {
        this(VERSION, instanceId, effectiveSeed, encounterPointId, x, y, depth, threatRollRan,
                creator, threatTag, entities, specials, overlay);
    }

    public record Entity(Role role, int ordinal, String specId) {
        public Entity {
            role = Objects.requireNonNull(role, "role");
            if (ordinal < 0 || ordinal >= MAX_ENTITIES) throw new IllegalArgumentException("Encounter ordinal out of bounds");
            specId = text(specId, MAX_ID_LENGTH, true);
        }
    }

    /** Null Special.orbit means the special had no native orbit. */
    public record Orbit(String focusId, float angle, float radius, float period) {
        public Orbit {
            focusId = checkedEntityId(focusId);
            finite(angle); finite(radius); finite(period);
            if (Math.abs(angle) > 1_000_000f || radius < 0 || radius > 1_000_000f
                    || period <= 0 || period > 1_000_000f)
                throw new IllegalArgumentException("Encounter orbit out of bounds");
        }
    }

    /** Wreck data reuses the resolved native variant, recovery and salvage recipe. */
    public record Special(SpecialType type, String entityId, Orbit orbit, CoopWreckRecord wreck,
                          List<CoopWreckRecord.MemoryValue> memory) {
        public Special {
            type = Objects.requireNonNull(type, "special type");
            entityId = checkedEntityId(entityId);
            memory = checkedMemory(memory);
            if ((type == SpecialType.GHOST_WRECK) != (wreck != null))
                throw new IllegalArgumentException("Encounter ghost wreck data does not match special type");
            if (wreck != null && !wreck.id().equals(wreckId(entityId)))
                throw new IllegalArgumentException("Encounter wreck recipe identity mismatch");
            if (type == SpecialType.PLANET_FLAGS && orbit != null)
                throw new IllegalArgumentException("Planet hint flags do not replace the native orbit");
        }
    }

    /** A missing optional value leaves that native field alone; memory is the complete shared whitelist. */
    public record EntityState(String entityId, String surveyLevel, Boolean ruinsExplored,
                              List<CoopWreckRecord.MemoryValue> memory) {
        public EntityState {
            entityId = checkedEntityId(entityId);
            if (surveyLevel != null && !SURVEY_LEVELS.contains(surveyLevel))
                throw new IllegalArgumentException("Unknown encounter survey level: " + surveyLevel);
            memory = checkedMemory(memory);
        }
    }

    public record Overlay(List<String> consumedIds, List<EntityState> entities) {
        public Overlay {
            consumedIds = sorted(consumedIds, MAX_ENTITIES, Comparator.naturalOrder());
            consumedIds.forEach(CoopEncounterRecord::checkedEntityId);
            unique(consumedIds, "consumed encounter ids");
            entities = sorted(entities, MAX_ENTITIES, Comparator.comparing(EntityState::entityId));
            unique(entities.stream().map(EntityState::entityId).toList(), "encounter overlay ids");
        }
        public static Overlay empty() { return new Overlay(List.of(), List.of()); }
    }

    public String entityId(Entity entity) { return entityId(instanceId, entity.role(), entity.ordinal()); }

    public static String entityId(String instanceId, Role role, int ordinal) {
        checkedInstanceId(instanceId); Objects.requireNonNull(role, "role");
        if (ordinal < 0 || ordinal >= MAX_ENTITIES) throw new IllegalArgumentException("Encounter ordinal out of bounds");
        return "coopenc_" + instanceId + "_" + role.name().toLowerCase(java.util.Locale.ROOT) + ordinal;
    }

    /** The nested recipe id is not the native encounter entity id or a floating-wreck ownership tag. */
    public static String wreckId(String entityId) { return "wreck:" + checkedEntityId(entityId); }

    public CoopEncounterRecord withOverlay(Overlay replacement) {
        return new CoopEncounterRecord(version, instanceId, effectiveSeed, encounterPointId, x, y, depth,
                threatRollRan, creator, threatTag, entities, specials, replacement);
    }

    public String encode() { return CoopEncounterCodec.encode(this); }
    public static CoopEncounterRecord decode(String encoded) { return CoopEncounterCodec.decode(encoded); }

    static String text(String value, int maximum, boolean empty) {
        Objects.requireNonNull(value, "encounter text");
        if (value.length() > maximum || (!empty && value.isEmpty()))
            throw new IllegalArgumentException("Encounter text out of bounds");
        return value;
    }
    static void finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite encounter number");
    }
    private static void coordinate(float value) {
        finite(value);
        if (Math.abs(value) > 1_000_000f) throw new IllegalArgumentException("Encounter coordinate out of bounds");
    }
    private static String checkedInstanceId(String value) {
        text(value, 128, false);
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid encounter instance id");
        return value;
    }
    private static String checkedEntityId(String value) {
        text(value, MAX_ID_LENGTH - 6, false);
        Matcher matcher = ENTITY_ID.matcher(value);
        if (!matcher.matches())
            throw new IllegalArgumentException("Invalid encounter entity id");
        int ordinal = Integer.parseInt(matcher.group(3));
        if (ordinal >= MAX_ENTITIES || !Integer.toString(ordinal).equals(matcher.group(3)))
            throw new IllegalArgumentException("Invalid encounter entity ordinal");
        return value;
    }
    private static void requireMember(Set<String> ids, String value) {
        if (!ids.contains(value)) throw new IllegalArgumentException("Encounter refers to an unknown member: " + value);
    }
    private static List<CoopWreckRecord.MemoryValue> checkedMemory(List<CoopWreckRecord.MemoryValue> memory) {
        List<CoopWreckRecord.MemoryValue> result = sorted(memory, MAX_MEMORY_VALUES,
                Comparator.comparing(CoopWreckRecord.MemoryValue::key));
        unique(result.stream().map(CoopWreckRecord.MemoryValue::key).toList(), "encounter memory keys");
        for (CoopWreckRecord.MemoryValue value : result) {
            if (!MEMORY_KEYS.contains(value.key())) throw new IllegalArgumentException("Unsupported encounter memory key: " + value.key());
            text(value.value(), MAX_STRING_LENGTH, true);
            if (value.key().equals("$gsType")) {
                if (!value.type().equals("STRING") || value.value().isEmpty())
                    throw new IllegalArgumentException("Encounter ghost type must be a string");
            } else if (!value.type().equals("BOOLEAN")) {
                throw new IllegalArgumentException("Encounter hint flag must be a boolean");
            }
        }
        return result;
    }
    private static void unique(Collection<String> values, String label) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Duplicate " + label);
    }
    private static <T> List<T> sorted(Collection<T> values, int maximum, Comparator<? super T> comparator) {
        Objects.requireNonNull(values, "encounter collection");
        if (values.size() > maximum) throw new IllegalArgumentException("Encounter collection out of bounds");
        List<T> result = new ArrayList<>(List.copyOf(values));
        result.sort(comparator);
        return List.copyOf(result);
    }
}
