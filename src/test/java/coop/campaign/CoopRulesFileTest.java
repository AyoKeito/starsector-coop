package coop.campaign;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped {@code data/campaign/rules.csv}, which nothing else can check: the engine reads
 * it at data-load time, ten seconds before any mod code runs, and a mistake in it either throws
 * during loading or - worse - silently drops a row.
 *
 * <p>Two things are asserted. First the file's own invariants, which the header comment spells out
 * and which the engine enforces with a {@code RuntimeException}: seven columns per row, and no id
 * used twice. Duplicate ids are the trap worth a test, because our comment rows are <em>quoted</em>
 * and so survive the CSV parser and occupy an id - two identical comment lines anywhere in the file
 * are a hard load failure that no amount of reading the diff makes obvious.
 *
 * <p>Then the Galatia Academy gate: every root entry into the story chain has to be present as a
 * replacement row carrying {@link CoopStoryChainGate#GUEST_RULE_CONDITION}, with the vanilla
 * conditions it was copied from still in place, so the row is unreachable on a guest and byte-for-byte
 * vanilla on the host. A sample of each row's vanilla conditions is listed here rather than the whole
 * set: enough that dropping one while transcribing is caught, without pinning prose the next game
 * version may reword.
 */
class CoopRulesFileTest {

    private static final Path RULES = Path.of("data", "campaign", "rules.csv");

    /** Every academy root entry, mapped to vanilla conditions that must have survived the copy. */
    private static final Map<String, List<String>> ACADEMY_GATE = academyGate();

    private static Map<String, List<String>> academyGate() {
        Map<String, List<String>> gate = new LinkedHashMap<>();
        gate.put("goToTheGABarEventOption",
                List.of("$global.daysSinceStart > 180", "!$player.metBaird", "RollProbability 0.2"));
        gate.put("goToGA_barEvent",
                List.of("$option == goToGA_barEvent", "GenGAIntroAcademician"));
        gate.put("gaAddOptionMeetProvost",
                List.of("$id == station_galatia_academy", "!$player.metBaird"));
        gate.put("gaIntro2surveyOpen",
                List.of("$option == surveyPerform", "!$global.gaIntro2found", "RollProbability 0.3 score:1000"));
        gate.put("gaDHOhookStart",
                List.of("!$global.gaDHO_didInvite", "$global.daysSinceStart > 365", "RollProbability 0.3"));
        gate.put("gaDHOhookStartDev",
                List.of("!$global.gaDHO_didInvite", "$global.isDevMode"));
        gate.put("gaDHOjustFoundArrayStart",
                List.of("$oneslaughtSensorArray score:100", "!$global.gaDHO_inProgress"));
        gate.put("hamatsu_PostShipRecoverySpecial",
                List.of("$srs_memberId == hamatsu score:1000"));
        gate.put("gaDevMenuOption",
                List.of("$id == station_galatia_academy", "$global.isDevMode"));
        return gate;
    }

    @Test
    void everyRowHasTheSevenColumnsTheLoaderExpects() throws IOException {
        List<List<String>> rows = rows();

        assertEquals(List.of("id", "trigger", "conditions", "script", "text", "options", "notes"),
                rows.get(0), "header");
        for (List<String> row : rows) {
            assertEquals(7, row.size(), "wrong column count in row starting " + row.get(0));
        }
    }

    @Test
    void noIdIsUsedTwice() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (List<String> row : rows()) {
            String id = row.get(0);
            if (id.isEmpty()) {
                // An all-empty row is dropped by the loader; that is what our spacer lines are.
                continue;
            }
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        assertEquals(List.of(), duplicates,
                "duplicate ids are a RuntimeException at data load; quoted comment rows count as ids");
    }

    @Test
    void everyAcademyRootEntryIsGatedOnTheGuestFlag() throws IOException {
        Map<String, String> conditionsById = new LinkedHashMap<>();
        for (List<String> row : rows()) {
            conditionsById.put(row.get(0), row.get(2));
        }

        for (Map.Entry<String, List<String>> entry : ACADEMY_GATE.entrySet()) {
            String id = entry.getKey();
            String conditions = conditionsById.get(id);
            assertNotNull(conditions, "no row replaces vanilla rule " + id);
            assertTrue(conditions.lines().map(String::trim)
                            .anyMatch(CoopStoryChainGate.GUEST_RULE_CONDITION::equals),
                    id + " is not gated on " + CoopStoryChainGate.GUEST_RULE_CONDITION);
            for (String vanilla : entry.getValue()) {
                assertTrue(conditions.lines().map(String::trim).anyMatch(vanilla::equals),
                        id + " lost the vanilla condition '" + vanilla + "', so the host path changed");
            }
        }
    }

    @Test
    void theGuestConditionIsSpeltTheWayTheFlagIsPublished() {
        // The rules engine reads sector memory under $global, and the leading $ is part of the key.
        assertEquals("!$global." + CoopStoryChainGate.GUEST_MEMORY_FLAG.substring(1),
                CoopStoryChainGate.GUEST_RULE_CONDITION);
    }

    /**
     * The mod's rules.csv, parsed. Quoted fields may span lines and may contain commas and doubled
     * quotes, so this is a real CSV reader rather than a split on commas.
     */
    private static List<List<String>> rows() throws IOException {
        String text = Files.readString(RULES, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
