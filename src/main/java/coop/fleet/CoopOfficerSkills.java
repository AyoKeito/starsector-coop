package coop.fleet;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire encoding for one character's skills (Phase 33): {@code skillId:level} pairs joined by
 * commas, e.g. {@code "combat_endurance:1,target_analysis:2"}. Level 2 is an elite skill; the engine
 * models a skill level as a float but only ever seats 1 or 2 on an officer, so it rides as an int and
 * a fractional reading rounds.
 *
 * <p><b>Why these two separators.</b> A skill id is a spreadsheet id out of {@code skills.csv} —
 * lowercase letters, digits and underscores in vanilla and in every mod that expects
 * {@code Global.getSettings().getSkillSpec(id)} to find it — so neither {@code ,} nor {@code :} can
 * appear inside one. That matters because this string is itself a single {@link CoopFleetCodec}
 * field: the codec escapes its own delimiters, and a separator that collided with an id would split
 * a record rather than a pair. The same reasoning and the same comma are already in
 * {@link CoopShipMods}'s hullmod-id lists.
 *
 * <p>Pure on both sides: the engine reads that produce the entries live in
 * {@code CoopFleetSnapshotFactory} and the writes that consume them in {@code CoopFleetMirror}, so
 * the format itself is unit-testable without a game.
 */
public final class CoopOfficerSkills {

    /** Between two {@code id:level} pairs. */
    static final char PAIR_SEPARATOR = ',';
    /** Between a skill id and its level. */
    static final char LEVEL_SEPARATOR = ':';

    /** One skill at one level. Level 1 is a normal pick, level 2 an elite one. */
    public record Entry(String skillId, int level) {
        public Entry {
            skillId = skillId == null ? "" : skillId.trim();
            level = Math.max(0, level);
        }
    }

    private CoopOfficerSkills() {
    }

    /**
     * Encodes the entries in the order given. Entries with a blank id or a level of zero are dropped
     * rather than encoded: the engine hands back a full skill list with most levels at 0 for any
     * character that has ever had its stats refreshed, and an officer with three picks would
     * otherwise stream sixty pairs of which fifty-seven mean "no".
     */
    public static String encode(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(entries.size() * 20);
        for (Entry entry : entries) {
            if (entry == null || entry.skillId().isEmpty() || entry.level() <= 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(PAIR_SEPARATOR);
            }
            out.append(entry.skillId()).append(LEVEL_SEPARATOR).append(entry.level());
        }
        return out.toString();
    }

    /**
     * Decodes what {@link #encode} produced. Total by design: a pair this cannot read is dropped and
     * the rest of the list still lands. A missing skill costs the mirror a little strength, while a
     * throw on the roster path costs the whole ship — and this runs on a build that has already
     * accepted the roster it is part of.
     */
    public static List<Entry> decode(String encoded) {
        List<Entry> entries = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return entries;
        }
        for (String pair : encoded.split(String.valueOf(PAIR_SEPARATOR), -1)) {
            int split = pair.indexOf(LEVEL_SEPARATOR);
            if (split <= 0 || split == pair.length() - 1) {
                continue;
            }
            String skillId = pair.substring(0, split).trim();
            int level;
            try {
                level = Integer.parseInt(pair.substring(split + 1).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (skillId.isEmpty() || level <= 0) {
                continue;
            }
            entries.add(new Entry(skillId, level));
        }
        return entries;
    }

    /** A float skill level from the engine as the wire's int. Below half a level is "not taken". */
    public static int levelOf(float engineLevel) {
        if (!Float.isFinite(engineLevel) || engineLevel < 0.5f) {
            return 0;
        }
        return Math.round(engineLevel);
    }
}
