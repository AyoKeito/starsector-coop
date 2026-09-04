package coop.campaign;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static coop.util.CoopText.requireText;

/**
 * One hireable person in a market's officer/mercenary/admin pool (Phase 12c gap 2d).
 *
 * <p>The pool is rolled per client by the sector's {@code OfficerManagerEvent}, off
 * {@code Misc.random} and {@code Math.random()}, so host and guest saw different captains at the same
 * bar. The host's pool is canonical: it rides the {@code MARKET_SNAPSHOT} as one
 * {@link CoopMarketSync.StockItem} per person (kind {@link CoopMarketSync.ItemKind#OFFICER},
 * {@link CoopMarketSync.ItemKind#MERC} or {@link CoopMarketSync.ItemKind#ADMIN}, quantity 1, this
 * blob in the item's detail field) and the guest strips its own pool and rebuilds the host's.
 *
 * <p><b>Admins are their own kind, not an OFFICER role flag.</b> The engine keeps them in a second
 * list ({@code availableAdmins}) reached through a second pair of accessors
 * ({@code addAvailableAdmin} / {@code getAdmin}), so the apply side has to branch on it regardless;
 * making it a distinct {@code ItemKind} means the stock key ({@code KIND:id}) is already unique and
 * the hire claim carries the routing information the host needs with no extra payload field.
 *
 * <p>{@code hiringBonus} and {@code salary} are the exact ints from the engine's
 * {@code AvailableOfficer}, never the {@code $ome_hiringBonus} / {@code $ome_salary} memory values —
 * those are pre-formatted display strings ({@code Misc.getWithDGS}) and parsing them back would be
 * locale- and separator-dependent.
 *
 * <p>Wire format: one {@code |}-separated {@link CoopDelimited} record (level 2, carried inside one
 * field of the stock line), with the skill map at level 3 using {@link CoopShipDetail}'s
 * {@code key=value} comma scheme.
 */
public record CoopPersonDetail(String personId,
                               String first,
                               String last,
                               String gender,
                               String portraitSprite,
                               String personalityId,
                               String rankId,
                               String postId,
                               String factionId,
                               int level,
                               long xp,
                               Role role,
                               int hiringBonus,
                               int salary,
                               int adminTier,
                               float timeRemainingDays,
                               Map<String, Float> skills) {

    /** Which of the engine's two hireable pools this person belongs to. */
    public enum Role {
        OFFICER,
        MERC,
        ADMIN
    }

    /** Number of {@code |}-separated fields in the encoded form. */
    public static final int FIELD_COUNT = 17;

    /**
     * What {@link #timeRemainingDays} falls back to when the host reports nothing usable.
     *
     * <p>Load-bearing, not cosmetic. {@code AvailableOfficer.timeRemaining} defaults to {@code 0f}
     * and {@code OfficerManagerEvent.advance} deletes every entry whose counter has run out on its
     * 1-3 day tracker ({@code impl/campaign/events/OfficerManagerEvent.java:192-205}) — comm
     * directory entry, {@code $ome_hireable} flag and all. A rebuilt hireable that carries no
     * lifetime is therefore gone from the guest's market within three campaign days of the snapshot
     * that put it there. Vanilla's own spawn uses 60-120 days
     * ({@code getOfficerDuration}); this is the low end of that.
     */
    public static final float DEFAULT_LIFETIME_DAYS = 60f;

    public CoopPersonDetail {
        personId = requireText(personId, "personId");
        first = CoopDelimited.normalize(first);
        last = CoopDelimited.normalize(last);
        gender = CoopDelimited.normalize(gender);
        portraitSprite = CoopDelimited.normalize(portraitSprite);
        personalityId = CoopDelimited.normalize(personalityId);
        rankId = CoopDelimited.normalize(rankId);
        postId = CoopDelimited.normalize(postId);
        factionId = CoopDelimited.normalize(factionId);
        role = Objects.requireNonNull(role, "role");
        Map<String, Float> copy = new LinkedHashMap<>();
        if (skills != null) {
            for (Map.Entry<String, Float> entry : skills.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        // Deliberately not Map.copyOf: its iteration order is salted per JVM run, which would make the
        // encoded string unstable between clients (Phase 8 rule: encodings are byte-stable).
        skills = Collections.unmodifiableMap(copy);
    }

    /** The stock kind this role rides as. */
    public CoopMarketSync.ItemKind stockKind() {
        return switch (role) {
            case OFFICER -> CoopMarketSync.ItemKind.OFFICER;
            case MERC -> CoopMarketSync.ItemKind.MERC;
            case ADMIN -> CoopMarketSync.ItemKind.ADMIN;
        };
    }

    /** The role a stock kind denotes, or null when the kind is not a person listing. */
    public static Role roleOf(CoopMarketSync.ItemKind kind) {
        return switch (kind) {
            case OFFICER -> Role.OFFICER;
            case MERC -> Role.MERC;
            case ADMIN -> Role.ADMIN;
            default -> null;
        };
    }

    public String encode() {
        return CoopDelimited.field(personId)
                + '|' + CoopDelimited.field(first)
                + '|' + CoopDelimited.field(last)
                + '|' + CoopDelimited.field(gender)
                + '|' + CoopDelimited.field(portraitSprite)
                + '|' + CoopDelimited.field(personalityId)
                + '|' + CoopDelimited.field(rankId)
                + '|' + CoopDelimited.field(postId)
                + '|' + CoopDelimited.field(factionId)
                + '|' + level
                + '|' + xp
                + '|' + role.name()
                + '|' + hiringBonus
                + '|' + salary
                + '|' + adminTier
                + '|' + Float.toString(timeRemainingDays)
                + '|' + CoopDelimited.field(encodeSkills(skills));
    }

    public static CoopPersonDetail decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        List<String> f = CoopDelimited.split(encoded);
        if (f.size() != FIELD_COUNT) {
            throw new IllegalArgumentException("Expected " + FIELD_COUNT + " person detail fields, got " + f.size());
        }
        return new CoopPersonDetail(f.get(0), f.get(1), f.get(2), f.get(3), f.get(4), f.get(5),
                f.get(6), f.get(7), f.get(8),
                Integer.parseInt(f.get(9).trim()),
                Long.parseLong(f.get(10).trim()),
                Role.valueOf(f.get(11).trim()),
                Integer.parseInt(f.get(12).trim()),
                Integer.parseInt(f.get(13).trim()),
                Integer.parseInt(f.get(14).trim()),
                Float.parseFloat(f.get(15).trim()),
                decodeSkills(f.get(16)));
    }

    private static String encodeSkills(Map<String, Float> skills) {
        // Sorted so two clients encoding the same pool produce the same bytes regardless of the order
        // the engine handed back the skill list.
        List<String> ids = new ArrayList<>(skills.keySet());
        Collections.sort(ids);
        Map<String, String> text = new LinkedHashMap<>();
        for (String id : ids) {
            text.put(id, Float.toString(skills.get(id)));
        }
        return CoopShipDetail.joinMap(text);
    }

    private static Map<String, Float> decodeSkills(String encoded) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : CoopShipDetail.splitMap(encoded).entrySet()) {
            out.put(entry.getKey(), Float.parseFloat(entry.getValue().trim()));
        }
        return out;
    }

}
