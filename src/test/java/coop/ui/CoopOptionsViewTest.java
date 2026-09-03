package coop.ui;

import coop.config.CoopOptionsRegistry;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 28 milestone 3: the page's whole decision surface, with no engine anywhere near it. */
class CoopOptionsViewTest {

    private static final String PAUSE = CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS;

    /** Reader over plain maps: policy values, pending flags, the local stack, and -D. */
    private static final class FakeReader implements CoopOptionsView.Reader {
        private final Map<String, String> policy = new HashMap<>();
        private final Map<String, String> local = new HashMap<>();
        private final Set<String> pending = new HashSet<>();
        private final Set<String> commandLine = new HashSet<>();
        private final Set<String> userFile = new HashSet<>();
        private boolean policyInstalled = true;

        @Override
        public String policyValue(String key) {
            if (!policyInstalled) {
                return null;
            }
            String value = policy.get(key);
            return value == null ? CoopOptionsRegistry.require(key).defaultValue() : value;
        }

        @Override
        public boolean policyPending(String key) {
            return pending.contains(key);
        }

        @Override
        public String localValue(String key) {
            String value = local.get(key);
            return value == null ? CoopOptionsRegistry.require(key).defaultValue() : value;
        }

        @Override
        public boolean commandLine(String key) {
            return commandLine.contains(key);
        }

        @Override
        public boolean userFile(String key) {
            return userFile.contains(key);
        }
    }

    private static CoopOptionsView.Row row(CoopOptionsView view, String key) {
        for (CoopOptionsView.Section section : view.sections()) {
            for (CoopOptionsView.Row row : section.rows()) {
                if (row.key().equals(key)) {
                    return row;
                }
            }
        }
        return null;
    }

    // ---- rows per role ---------------------------------------------------------------------------

    @Test
    void everyFileBackedKeyGetsARowAndNoCommandLineOnlyKeyDoes() {
        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.HOST, true, new FakeReader());

        for (CoopOptionsRegistry.Option option : CoopOptionsRegistry.options()) {
            CoopOptionsView.Row row = row(view, option.key());
            if (option.dOnly()) {
                assertNull(row, option.key() + " is -D only and must not be offered as a setting");
            } else {
                assertNotNull(row, option.key() + " has no row");
            }
        }
    }

    @Test
    void theHostEditsPolicyAndTheGuestReadsIt() {
        CoopOptionsView hostView = CoopOptionsView.of(CoopConnectionRole.HOST, true, new FakeReader());
        CoopOptionsView guestView = CoopOptionsView.of(CoopConnectionRole.GUEST, true, new FakeReader());

        CoopOptionsView.Row hostRow = row(hostView, PAUSE);
        assertTrue(hostRow.editable());
        assertEquals(CoopOptionsView.Control.TOGGLE, hostRow.control());
        assertEquals(CoopOptionsView.TAG_CAMPAIGN, hostRow.sourceTag());

        CoopOptionsView.Row guestRow = row(guestView, PAUSE);
        assertFalse(guestRow.editable(), "the button is absent, not an error");
        assertEquals(CoopOptionsView.Control.NONE, guestRow.control());
        assertEquals(CoopOptionsView.TAG_HOST_SETTING, guestRow.sourceTag());
    }

    @Test
    void aClientThatHasNotStartedHostingStillOwnsItsOwnCampaignsRules() {
        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.NONE, false, new FakeReader());

        assertTrue(row(view, PAUSE).editable(),
                "setting the rules before opening the session is the normal path");
    }

    @Test
    void aGuestStillEditsItsOwnLocalPreferences() {
        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.GUEST, true, new FakeReader());

        assertTrue(row(view, CoopOptionsRegistry.HUD_CORNER).editable());
        assertEquals(CoopOptionsView.Control.CYCLE, row(view, CoopOptionsRegistry.HUD_CORNER).control());
    }

    @Test
    void launchRowsAreEditableOnlyWhileNoSessionIsRunning() {
        FakeReader reader = new FakeReader();

        assertFalse(row(CoopOptionsView.of(CoopConnectionRole.HOST, true, reader),
                CoopOptionsRegistry.PORT_MAPPING).editable());
        assertEquals(CoopOptionsView.NOTE_NEXT_LAUNCH,
                row(CoopOptionsView.of(CoopConnectionRole.HOST, true, reader),
                        CoopOptionsRegistry.PORT_MAPPING).note());
        assertTrue(row(CoopOptionsView.of(CoopConnectionRole.NONE, false, reader),
                CoopOptionsRegistry.PORT_MAPPING).editable());
    }

    @Test
    void aCommandLineValueIsReadOnlyWhateverItsTier() {
        FakeReader reader = new FakeReader();
        reader.commandLine.add(PAUSE);
        reader.commandLine.add(CoopOptionsRegistry.HUD_CORNER);

        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.HOST, false, reader);

        assertEquals(CoopOptionsView.TAG_COMMAND_LINE, row(view, PAUSE).sourceTag());
        assertFalse(row(view, PAUSE).editable());
        assertEquals(CoopOptionsView.TAG_COMMAND_LINE, row(view, CoopOptionsRegistry.HUD_CORNER).sourceTag());
        assertFalse(row(view, CoopOptionsRegistry.HUD_CORNER).editable());
    }

    @Test
    void aLocalOverrideIsTaggedAsTheUsersOwn() {
        FakeReader reader = new FakeReader();
        reader.userFile.add(CoopOptionsRegistry.HUD_CORNER);
        reader.local.put(CoopOptionsRegistry.HUD_CORNER, "BL");

        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.NONE, false, reader);

        assertEquals(CoopOptionsView.TAG_LOCAL, row(view, CoopOptionsRegistry.HUD_CORNER).sourceTag());
        assertEquals("BL", row(view, CoopOptionsRegistry.HUD_CORNER).valueText());
        assertEquals(CoopOptionsView.TAG_DEFAULT,
                row(view, CoopOptionsRegistry.FEED_VERBOSITY).sourceTag());
    }

    // ---- notes -----------------------------------------------------------------------------------

    @Test
    void aPendingPolicyChangeSaysWhenItWillApply() {
        FakeReader reader = new FakeReader();
        reader.policy.put(PAUSE, "false");
        reader.pending.add(PAUSE);

        CoopOptionsView.Row row = row(CoopOptionsView.of(CoopConnectionRole.HOST, true, reader), PAUSE);

        assertTrue(row.pendingNote().startsWith("pending"), row.pendingNote());
        assertTrue(row.pendingNote().contains("next screen open/close"), row.pendingNote());
    }

    @Test
    void inertKeysSayThatNothingReadsThemYet() {
        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.HOST, false, new FakeReader());

        for (String key : CoopOptionsView.INERT_KEYS) {
            CoopOptionsView.Row row = row(view, key);
            assertTrue(row.note().startsWith("no effect in this build"), key + ": " + row.note());
        }
        assertEquals("", row(view, PAUSE).note(), "the one wired policy key has no caveat");
    }

    @Test
    void theTwoLaunchReadPolicyKeysSayWhereTheyAreActuallyRead() {
        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.HOST, false, new FakeReader());

        for (String key : CoopOptionsView.LAUNCH_READ_POLICY_KEYS) {
            assertTrue(row(view, key).note().contains("at launch"), key);
        }
    }

    @Test
    void freeTextRowsPointAtTheSettingsFileInsteadOfPretendingToBeEditable() {
        CoopOptionsView view = CoopOptionsView.of(CoopConnectionRole.NONE, false, new FakeReader());

        CoopOptionsView.Row name = row(view, CoopOptionsRegistry.PLAYER_NAME);
        assertEquals(CoopOptionsView.Control.NONE, name.control());
        assertEquals(CoopOptionsView.NOTE_FILE_ONLY, name.note());
    }

    @Test
    void thePasswordIsNeverPrintedAndCanOnlyBeCleared() {
        FakeReader reader = new FakeReader();
        reader.local.put(CoopOptionsRegistry.PASSWORD, "hunter2");

        CoopOptionsView.Row row = row(CoopOptionsView.of(CoopConnectionRole.NONE, false, reader),
                CoopOptionsRegistry.PASSWORD);

        assertEquals("set", row.valueText());
        assertFalse(row.valueText().contains("hunter2"));
        assertEquals(CoopOptionsView.Control.CLEAR, row.control());
    }

    @Test
    void maxGuestsHasNoStepperBecauseItsBoundsLeaveNothingToChoose() {
        CoopOptionsView.Row row = row(CoopOptionsView.of(CoopConnectionRole.HOST, false,
                new FakeReader()), CoopOptionsRegistry.MAX_GUESTS);

        assertEquals(CoopOptionsView.Control.NONE, row.control());
    }

    // ---- button maths ----------------------------------------------------------------------------

    @Test
    void toggleCycleAndStepperProduceTheExpectedNextValue() {
        assertEquals("false", CoopOptionsView.nextValue(PAUSE, "true", 1));
        assertEquals("true", CoopOptionsView.nextValue(PAUSE, "false", 1));

        assertEquals("TL", CoopOptionsView.nextValue(CoopOptionsRegistry.HUD_CORNER, "TR", 1));
        assertEquals("BL", CoopOptionsView.nextValue(CoopOptionsRegistry.HUD_CORNER, "TR", -1));

        assertEquals("75", CoopOptionsView.nextValue(
                CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "60", 1));
        assertEquals("45", CoopOptionsView.nextValue(
                CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "60", -1));
    }

    @Test
    void theStepperStopsAtTheRegistryBounds() {
        assertNull(CoopOptionsView.nextValue(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "0", -1));
        assertNull(CoopOptionsView.nextValue(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "3600", 1));
        assertEquals("3600", CoopOptionsView.nextValue(
                CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "3590", 1));
    }

    @Test
    void aSingleValueEnumHasNothingToCycleTo() {
        assertNull(CoopOptionsView.nextValue(CoopOptionsRegistry.LOOT_SPLIT, "equal", 1));
    }

    // ---- confirmation ----------------------------------------------------------------------------

    @Test
    void exactlyThreeKeysAskForConfirmationAndEachNamesTheTradeOff() {
        assertEquals(Set.of(CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, CoopOptionsRegistry.PASSWORD,
                CoopOptionsRegistry.RECONNECT_GRACE_SECONDS), CoopOptionsView.CONFIRM_REQUIRED);

        assertTrue(CoopOptionsView.confirmPrompt(PAUSE, "true")
                .contains("world moves while your partner reads"));
        assertTrue(CoopOptionsView.confirmPrompt(CoopOptionsRegistry.PASSWORD, "x")
                .contains("Anyone who can reach your host port"));
        assertTrue(CoopOptionsView.confirmPrompt(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "60")
                .contains("frozen"));
        assertEquals("", CoopOptionsView.confirmPrompt(CoopOptionsRegistry.HUD_CORNER, "TR"));
    }

    @Test
    void theResetPromptTellsAGuestItsPartnersRulesAreSafe() {
        assertTrue(CoopOptionsView.resetPrompt(true).contains("belong"));
        assertTrue(CoopOptionsView.resetPrompt(false).contains("campaign's session rules"));
    }

    // ---- feed lines ------------------------------------------------------------------------------

    @Test
    void theFeedLineReadsDifferentlyOnEachSide() {
        assertEquals("Co-op: Pause while a guest reads a screen set to off.",
                CoopOptionsView.changeLine(PAUSE, "false", true));
        assertEquals("Co-op: the host set Pause while a guest reads a screen to off.",
                CoopOptionsView.changeLine(PAUSE, "false", false));
    }

    @Test
    void everyFileBackedKeyHasAPlayerFacingLabel() {
        for (CoopOptionsRegistry.Option option : CoopOptionsRegistry.fileBackedOptions()) {
            assertFalse(CoopOptionsView.label(option.key()).equals(option.key()),
                    option.key() + " has no label");
        }
    }
}
