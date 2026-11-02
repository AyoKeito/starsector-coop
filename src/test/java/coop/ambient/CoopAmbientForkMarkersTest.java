package coop.ambient;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Engine updates reapply these marked replacements, including extracted native method bodies. */
class CoopAmbientForkMarkersTest {
    private static final Pattern COOP_REFERENCE = Pattern.compile(
            "\\b(?:coop(?:\\.|[A-Z]\\w*)|Coop[A-Z]\\w*)|\\$coop\\w*");

    @Test
    void dwellerAndTransmitterCustomCodeHasBalancedEngineUpdateMarkers() throws Exception {
        for (String relative : List.of(
                "forks/com/fs/starfarer/api/impl/campaign/rulecmd/DwellerCMD.java",
                "forks/com/fs/starfarer/api/impl/campaign/rulecmd/salvage/special/TransmitterTrapSpecial.java")) {
            Path path = Path.of(relative);
            List<String> lines = Files.readAllLines(path);
            boolean inside = false;
            int hunks = 0;
            int customLines = 0;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                String location = path + ":" + (index + 1);
                if (line.contains("COOP FORK BEGIN")) {
                    assertFalse(inside, "Nested fork marker at " + location);
                    inside = true;
                    hunks++;
                } else if (line.contains("COOP FORK END")) {
                    assertTrue(inside, "Unmatched fork end at " + location);
                    inside = false;
                } else if (COOP_REFERENCE.matcher(line).find()) {
                    customLines++;
                    assertTrue(inside, "Unmarked co-op code would be lost on engine update at " + location);
                }
            }
            assertFalse(inside, "Unclosed fork marker in " + path);
            assertTrue(hunks > 0, "Missing engine update markers in " + path);
            assertTrue(customLines > 0, "Missing expected co-op hooks in " + path);
        }
    }
}
