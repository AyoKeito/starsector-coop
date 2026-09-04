package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopLauncherConfigTest {

    private static Map<String, String> owned(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static JSONObject reparse(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception ex) {
            throw new IllegalStateException("the launcher wrote something that is not JSON: "
                    + text, ex);
        }
    }

    @Test
    void aMissingFileIsAnEmptyConfigAndNotAnError() {
        CoopLauncherConfig config = CoopLauncherConfig.read(new File("no-such-file.json.data"));

        assertNull(config.readError());
        assertFalse(config.fileExisted());
        assertEquals("", config.value("coop.hostPort"));
    }

    @Test
    void theRealGuestTestClientFileIsRead() {
        // Verbatim copy of K:\Starsector-coop-test\guest\saves\common\coop_options.json.data.
        CoopLauncherConfig config = CoopLauncherConfig.parse(
                "{\n    \"coop.hud.disable\": \"false\",\n    \"coop.hudCorner\": \"TL\"\n}");

        assertNull(config.readError());
        assertEquals("TL", config.value("coop.hudCorner"));
        assertEquals("false", config.value("coop.hud.disable"));
    }

    @Test
    void anUnparsableFileIsRefusedRatherThanTreatedAsEmpty() {
        CoopLauncherConfig config = CoopLauncherConfig.parse("{ \"coop.hudCorner\": ");

        assertNotNull(config.readError());
        assertThrows(IllegalStateException.class,
                () -> config.write(new File("unused"), true, owned(CoopLauncherConfig.HOST_PORT, "7777")));
    }

    @Test
    void aCommentedFileCountsAsUnparsable() {
        // The engine's save-data reader does not strip # comments, so neither does the launcher.
        assertNotNull(CoopLauncherConfig.parse("# mine\n{\"coop.hudCorner\":\"TL\"}").readError());
    }

    @Test
    void hostOwnershipWritesThePortAndBlanksTheGuestKeys() {
        JSONObject json = reparse(CoopLauncherConfig.parse("{}").compose(true,
                owned(CoopLauncherConfig.HOST_PORT, "7777")));

        assertEquals("7777", json.optString("coop.hostPort", null));
        assertEquals("", json.optString("coop.connectHost", null));
        assertEquals("", json.optString("coop.connectPort", null));
    }

    @Test
    void guestOwnershipWritesTheAddressAndBlanksTheHostPort() {
        JSONObject json = reparse(CoopLauncherConfig.parse("{}").compose(false,
                owned(CoopLauncherConfig.CONNECT_HOST, "203.0.113.9",
                        CoopLauncherConfig.CONNECT_PORT, "7777")));

        assertEquals("", json.optString("coop.hostPort", null));
        assertEquals("203.0.113.9", json.optString("coop.connectHost", null));
        assertEquals("7777", json.optString("coop.connectPort", null));
    }

    @Test
    void switchingRoleCancelsThePreviousRolesKeys() {
        CoopLauncherConfig config = CoopLauncherConfig.parse(
                "{\"coop.hostPort\":\"7777\",\"coop.connectHost\":\"\",\"coop.connectPort\":\"\"}");

        JSONObject json = reparse(config.compose(false,
                owned(CoopLauncherConfig.CONNECT_HOST, "host.example",
                        CoopLauncherConfig.CONNECT_PORT, "7788")));

        assertEquals("", json.optString("coop.hostPort", null));
        assertEquals("host.example", json.optString("coop.connectHost", null));
    }

    @Test
    void aBlankOptionalValueRemovesTheKeyRatherThanWritingAnEmptyOne() {
        CoopLauncherConfig config = CoopLauncherConfig.parse(
                "{\"coop.password\":\"old\",\"coop.hudCorner\":\"BL\",\"coop.newGameSeed\":\"MN-1\"}");

        JSONObject json = reparse(config.compose(true, owned(
                CoopLauncherConfig.HOST_PORT, "7777",
                CoopLauncherConfig.PASSWORD, "",
                CoopLauncherConfig.HUD_CORNER, "  ",
                CoopLauncherConfig.NEW_GAME_SEED, "")));

        assertFalse(json.has("coop.password"));
        assertFalse(json.has("coop.hudCorner"));
        assertFalse(json.has("coop.newGameSeed"));
    }

    @Test
    void everyLauncherOwnedValueIsWrittenAsAJsonString() {
        JSONObject json = reparse(CoopLauncherConfig.parse("{}").compose(true, owned(
                CoopLauncherConfig.HOST_PORT, "7777",
                CoopLauncherConfig.RECONNECT_GRACE_SECONDS, "120",
                CoopLauncherConfig.PORT_MAPPING, "off",
                CoopLauncherConfig.SECTOR_SIZE, "small",
                CoopLauncherConfig.SECTOR_AGE, "young",
                CoopLauncherConfig.NEW_GAME_SEED, "MN-42",
                CoopLauncherConfig.PASSWORD, "hunter2")));

        for (String key : new String[]{"coop.hostPort", "coop.reconnectGraceSeconds",
                "coop.portMapping", "coop.sectorSize", "coop.sectorAge", "coop.newGameSeed",
                "coop.password"}) {
            assertTrue(json.opt(key) instanceof String, key + " must be written as a JSON string");
        }
        assertEquals("120", json.optString("coop.reconnectGraceSeconds", null));
        assertEquals("young", json.optString("coop.sectorAge", null));
    }

    @Test
    void keysTheLauncherDoesNotOwnAreCarriedThroughUntouched() {
        CoopLauncherConfig config = CoopLauncherConfig.parse(
                "{\"coop.partnerColor\":\"orange\",\"coop.feedVerbosity\":\"minimal\","
                        + "\"coop.somethingFromANewerBuild\":true}");

        JSONObject json = reparse(config.compose(true, owned(CoopLauncherConfig.HOST_PORT, "7777")));

        assertEquals("orange", json.optString("coop.partnerColor", null));
        assertEquals("minimal", json.optString("coop.feedVerbosity", null));
        assertEquals(Boolean.TRUE, json.opt("coop.somethingFromANewerBuild"));
    }

    @Test
    void theOutputIsStableAcrossRuns() {
        CoopLauncherConfig config = CoopLauncherConfig.parse(
                "{\"coop.partnerColor\":\"orange\",\"coop.hudCorner\":\"BL\"}");
        Map<String, String> values = owned(CoopLauncherConfig.HOST_PORT, "7777",
                CoopLauncherConfig.HUD_CORNER, "TR");

        assertEquals(config.compose(true, values), config.compose(true, values));
        // Role keys lead, so a diff between two runs reads top-down.
        assertTrue(config.compose(true, values).indexOf("coop.hostPort")
                < config.compose(true, values).indexOf("coop.partnerColor"));
    }

    @Test
    void aRoleKeyWithNoValueIsAProgrammingErrorAndSaysSo() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopLauncherConfig.parse("{}").compose(true, owned()));
        assertThrows(IllegalArgumentException.class,
                () -> CoopLauncherConfig.parse("{}").compose(false,
                        owned(CoopLauncherConfig.CONNECT_HOST, "h")));
    }

    @Test
    void writingCreatesTheCommonFolderAndProducesAFileThatReadsBack(@TempDir Path temp)
            throws IOException {
        File file = temp.resolve("saves").resolve("common").resolve("coop_options.json.data")
                .toFile();
        CoopLauncherConfig config = CoopLauncherConfig.read(file);

        config.write(file, true, owned(CoopLauncherConfig.HOST_PORT, "7777",
                CoopLauncherConfig.NEW_GAME_SEED, "MN-42"));

        assertTrue(file.isFile());
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.endsWith("}\n"), text);
        CoopLauncherConfig reread = CoopLauncherConfig.read(file);
        assertNull(reread.readError());
        assertEquals("7777", reread.value("coop.hostPort"));
        assertEquals("MN-42", reread.value("coop.newGameSeed"));
        assertEquals("", reread.value("coop.connectHost"));
    }

    /**
     * The adopt-campaign tick is consent for one launch. The mod publishes every key in this file
     * as a system property at every application load, so a key left behind would keep consenting -
     * including on a start made by double-clicking the game.
     */
    @Test
    void theAdoptCampaignConsentIsClearedAndEverythingElseSurvives(@TempDir Path temp)
            throws IOException {
        File file = temp.resolve("coop_options.json.data").toFile();
        CoopLauncherConfig.read(file).write(file, false, owned(
                CoopLauncherConfig.CONNECT_HOST, "203.0.113.9",
                CoopLauncherConfig.CONNECT_PORT, "7777",
                CoopLauncherConfig.PASSWORD, "hunter2",
                CoopLauncherConfig.ADOPT_CAMPAIGN_ID, "true"));
        assertEquals("true", CoopLauncherConfig.read(file).value("coop.adoptCampaignId"));

        assertTrue(CoopLauncherConfig.clearAdoptCampaignConsent(file));

        CoopLauncherConfig reread = CoopLauncherConfig.read(file);
        assertNull(reread.readError());
        assertEquals("", reread.value("coop.adoptCampaignId"));
        assertFalse(reread.keys().contains("coop.adoptCampaignId"), reread.keys().toString());
        assertEquals("203.0.113.9", reread.value("coop.connectHost"));
        assertEquals("7777", reread.value("coop.connectPort"));
        assertEquals("hunter2", reread.value("coop.password"));
    }

    @Test
    void clearingTheConsentTouchesNothingWhenItIsNotThere(@TempDir Path temp) throws IOException {
        File file = temp.resolve("coop_options.json.data").toFile();
        CoopLauncherConfig.read(file).write(file, true, owned(CoopLauncherConfig.HOST_PORT, "7777"));
        String before = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        assertFalse(CoopLauncherConfig.clearAdoptCampaignConsent(file));
        assertFalse(CoopLauncherConfig.clearAdoptCampaignConsent(
                temp.resolve("not-there.json.data").toFile()));

        assertEquals(before, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    /** Same rule as write(): a file that will not parse is never rewritten. */
    @Test
    void anUnreadableFileIsLeftAloneRatherThanRewrittenWithoutTheConsent(@TempDir Path temp)
            throws IOException {
        File file = temp.resolve("coop_options.json.data").toFile();
        String broken = "# hand-edited\ncoop.adoptCampaignId=true\n";
        Files.writeString(file.toPath(), broken, StandardCharsets.UTF_8);

        assertFalse(CoopLauncherConfig.clearAdoptCampaignConsent(file));
        assertEquals(broken, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void awkwardValuesAreEscapedRatherThanBreakingTheFile(@TempDir Path temp) throws IOException {
        File file = temp.resolve("coop_options.json.data").toFile();
        CoopLauncherConfig config = CoopLauncherConfig.read(file);

        config.write(file, true, owned(CoopLauncherConfig.HOST_PORT, "7777",
                CoopLauncherConfig.PASSWORD, "quote\" back\\slash\ttab"));

        assertEquals("quote\" back\\slash\ttab",
                CoopLauncherConfig.read(file).value("coop.password"));
    }
}
