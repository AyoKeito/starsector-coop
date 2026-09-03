package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import coop.handshake.CoopHandshakeDiff;
import coop.handshake.CoopHandshakeManifest;
import coop.net.CoopConnectionRole;
import coop.net.CoopReconnectCoordinator;
import coop.seed.CoopSeedSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proxy-driven, like {@link CoopReconnectDialogTest}: the three engine surfaces a desync dialog
 * touches and nothing else, so the whole set can be checked without a running game.
 *
 * <p>Two things get asserted on every dialog because they are the failure modes the spec was written
 * against: the wording rules (no "OK", no error vocabulary, no exclamation marks, ASCII only, support
 * code present) and the survival rules (a panel that throws must not escape into the pump, ESC is
 * never wired, the close callback runs before the dismiss).
 */
class CoopDesyncDialogTest {

    private static final String HOST_FP = "a1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff0";
    private static final String GUEST_FP = "f00dcafe11223344556677889900aabbccddeeff00112233445566778899aabb";

    private final Consumer<String> realOpener = CoopDesyncDialog.urlOpener;
    private final List<String> openedUrls = new ArrayList<>();

    @AfterEach
    void restoreUrlOpener() {
        CoopDesyncDialog.urlOpener = realOpener;
    }

    // -------------------------------------------------------------- dispatch

    @Test
    void eachKindGetsItsOwnDialogClassAndCode() {
        assertTrue(dialogFor(seedReason()) instanceof CoopDesyncDialog.Seed);
        assertTrue(dialogFor(campaignIdReason()) instanceof CoopDesyncDialog.Seed);
        assertTrue(dialogFor(modsReason()) instanceof CoopDesyncDialog.Mods);
        assertTrue(dialogFor(graceReason()) instanceof CoopDesyncDialog.Session);
        assertTrue(dialogFor(unmappedReason()) instanceof CoopDesyncDialog.Unmapped);
        assertTrue(CoopDesyncDialog.forReason(null, null, null, null) instanceof CoopDesyncDialog.Unmapped,
                "a null reason must still produce a dialog, never a silent session end");
    }

    @Test
    void theFeedLineNamesTheCodeForEveryKind() {
        assertEquals("Co-op: seed mismatch (COOP-SEED) - see the dialog",
                CoopDesyncDialog.feedLine(seedReason()));
        assertEquals("Co-op: campaign mismatch (COOP-SEED) - see the dialog",
                CoopDesyncDialog.feedLine(campaignIdReason()));
        assertEquals("Co-op: mod mismatch (COOP-MODS) - see the dialog",
                CoopDesyncDialog.feedLine(modsReason()));
        assertEquals("Co-op: session not resumed (COOP-SESSION) - see the dialog",
                CoopDesyncDialog.feedLine(graceReason()));
        assertEquals("Co-op: session ended (COOP-SESSION) - see the dialog",
                CoopDesyncDialog.feedLine(unmappedReason()));
    }

    // ------------------------------------------------------------- seed body

    @Test
    void theSeedDialogGivesBothRemediesInOrderAndBothFingerprintsShort() {
        RecordingDialog panel = show(dialogFor(seedReason()));
        String body = String.join("\n", panel.text.paragraphs);

        int newCampaign = indexOfParagraphContaining(panel, "type the host's seed");
        int loadSaves = indexOfParagraphContaining(panel, "load the co-op saves");
        assertTrue(newCampaign > 0, body);
        assertTrue(loadSaves > newCampaign, "remedies run in likelihood order: fresh campaign first");
        assertTrue(body.contains("coop-1122334455667788"),
                "the host's seed has to be literal text - the player cannot copy it");
        assertTrue(body.contains("a1b2-c3d4") && body.contains("f00d-cafe"),
                "both fingerprints, short and typeable: " + body);
        assertFalse(body.contains(HOST_FP), "64 characters of SHA-256 is not typeable");
        assertTrue(indexOfParagraphContaining(panel, "Sector fingerprint") > loadSaves,
                "technical detail sits below the remedy");
    }

    @Test
    void theSeedDialogIsAHardBlockWithNoJoinAnyway() {
        RecordingDialog panel = show(dialogFor(seedReason()));

        assertEquals(List.of(CoopDesyncDialog.OPTION_SUPPORT_TEXT, "Close"), panel.options.texts,
                "one closing option and the support link; there is no version of joining that works");
    }

    @Test
    void theCampaignIdDialogNamesTheAdoptOverrideRatherThanTheSeed() {
        RecordingDialog panel = show(dialogFor(campaignIdReason()));
        String body = String.join("\n", panel.text.paragraphs);

        assertTrue(body.contains("not from the host's co-op campaign"), body);
        assertTrue(body.contains("-AdoptCampaign"), body);
        assertTrue(body.contains("camp-7f3a"), body);
    }

    @Test
    void theHostsCampaignIdDialogTalksAboutTheGuestsSaveNotItsOwn() {
        RecordingDialog panel = show(CoopDesyncDialog.forReason(campaignIdReason(),
                CoopConnectionRole.HOST, () -> { }, null));
        String body = String.join("\n", panel.text.paragraphs);

        assertTrue(body.contains("The guest's save is not from this co-op campaign."), body);
        assertTrue(body.contains("the guest loads the co-op save from this campaign"), body);
        assertTrue(body.contains("they relaunch with launch-guest.ps1 -AdoptCampaign"), body);
        assertFalse(body.contains("This save is not from"), "the host did not load a wrong save: " + body);
        assertTrue(body.contains("camp-7f3a"), body);
    }

    // ------------------------------------------------------------- mods body

    @Test
    void theModDialogPrintsOneRowPerModWithItsOwnRemedyVerb() {
        RecordingDialog panel = show(dialogFor(modsReason()));
        String body = String.join("\n", panel.text.paragraphs);

        // Rows are keyed by mod id, not display name: CoopHandshakeDiff only emits a name line when
        // the two sides disagree about the name, and a mod missing on one side has no name at all.
        assertTrue(body.contains("- utility: you have 2.7 / the host has 2.8"), body);
        assertTrue(body.contains("- other: not installed - install it and enable it in the launcher"), body);
        assertTrue(body.contains("never downloads mods for you"),
                "no auto-download, and the dialog says so");
    }

    @Test
    void theModDialogAddsTheDedicatedSentenceForSameVersionDifferentContents() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("data/x.csv", "aaa"))),
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("data/x.csv", "bbb"))))
                .toDisplayString();
        RecordingDialog panel = show(dialogFor(
                CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE)));
        String body = String.join("\n", panel.text.paragraphs);

        assertTrue(body.contains("same version, different contents"), body);
        assertTrue(body.contains("not a display quirk"),
                "users do not believe the same-version case without the extra sentence");
    }

    @Test
    void theModDialogRoutesBlameToTheHostWhenTheHostIsBehind() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.7", Map.of())),
                manifest(mod("utility", "Utility Mod", "2.8", Map.of())))
                .toDisplayString();
        RecordingDialog panel = show(dialogFor(
                CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE)));

        assertTrue(String.join("\n", panel.text.paragraphs).contains("ask the host to update it to 2.8"),
                String.join("\n", panel.text.paragraphs));
    }

    @Test
    void theModDialogCapsTheListAndNamesTheLogForTheRest() {
        List<CoopHandshakeManifest.ModEntry> hostMods = new ArrayList<>();
        List<CoopHandshakeManifest.ModEntry> guestMods = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            hostMods.add(mod("mod" + i, "Mod " + i, "2.0", Map.of()));
            guestMods.add(mod("mod" + i, "Mod " + i, "1.0", Map.of()));
        }
        String raw = CoopHandshakeDiff.compare(
                new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", hostMods),
                new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", guestMods))
                .toDisplayString();
        RecordingDialog panel = show(dialogFor(
                CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE)));

        long rows = panel.text.paragraphs.stream().filter(p -> p.startsWith("- mod")).count();
        assertEquals(CoopDesyncReason.MAX_MOD_ROWS, rows, "an uncapped list runs off the screen");
        assertTrue(String.join("\n", panel.text.paragraphs)
                .contains("and 3 more. The full list is in starsector.log."));
    }

    // ---------------------------------------------------------- session body

    @Test
    void theGraceDialogStatesTheWindowAsANumberAndOffersNoRetry() {
        RecordingDialog panel = show(dialogFor(graceReason().withGraceSeconds(300)));
        String body = String.join("\n", panel.text.paragraphs);

        assertTrue(body.contains("held the world for 300 seconds"), body);
        assertEquals(List.of(CoopDesyncDialog.OPTION_SUPPORT_TEXT, "Close"), panel.options.texts,
                "an expired window cannot be retried, so no button pretends it can");
    }

    @Test
    void everySessionCauseGetsADistinctBody() {
        List<String> titles = new ArrayList<>();
        for (String raw : List.of(
                CoopReconnectCoordinator.REASON_GRACE_EXPIRED,
                CoopReconnectCoordinator.REASON_HOST_REJECTED + ": "
                        + CoopReconnectCoordinator.rejectReason(
                        CoopReconnectCoordinator.ResumeDecision.REJECT_SESSION_MISMATCH),
                CoopReconnectCoordinator.REASON_HOST_REJECTED + ": "
                        + CoopReconnectCoordinator.rejectReason(
                        CoopReconnectCoordinator.ResumeDecision.REJECT_PLAYER_MISMATCH),
                CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE,
                CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER)) {
            RecordingDialog panel = show(dialogFor(
                    CoopDesyncReason.classify(raw, CoopDesyncReason.Source.SESSION_RESUME)));
            titles.add(panel.text.paragraphs.get(0));
        }

        assertEquals(titles.size(), titles.stream().distinct().count(),
                "one dialog with a swappable reason string is exactly what this phase forbids: " + titles);
    }

    @Test
    void theRetryOptionAppearsOnlyForTheTransientCauseAndRunsItsCallback() {
        List<String> retried = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        CoopDesyncReason reason = CoopDesyncReason.classify(
                CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE, CoopDesyncReason.Source.SESSION_RESUME);
        assertTrue(reason.retryable(), "precondition");
        CoopDesyncDialog dialog = CoopDesyncDialog.forReason(reason, CoopConnectionRole.GUEST,
                () -> closed.add("closed"), () -> retried.add("retried"));

        RecordingDialog panel = show(dialog);
        assertEquals(List.of(CoopDesyncDialog.OPTION_RETRY_TEXT, CoopDesyncDialog.OPTION_SUPPORT_TEXT,
                "Close"), panel.options.texts);

        dialog.optionSelected(CoopDesyncDialog.OPTION_RETRY_TEXT,
                panel.options.dataFor(CoopDesyncDialog.OPTION_RETRY_TEXT));

        assertEquals(List.of("retried"), retried);
        assertEquals(List.of(), closed, "retry is not a close; the pump decides what happens next");
        assertEquals(1, panel.dismissCount);
    }

    @Test
    void aRetryableReasonWithNoRetryActionOffersNoRetryButton() {
        CoopDesyncReason reason = CoopDesyncReason.classify(
                CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE, CoopDesyncReason.Source.SESSION_RESUME);
        RecordingDialog panel = show(CoopDesyncDialog.forReason(reason, CoopConnectionRole.GUEST,
                () -> { }, null));

        assertFalse(panel.options.texts.contains(CoopDesyncDialog.OPTION_RETRY_TEXT),
                "a button with nothing behind it is worse than no button");
    }

    // -------------------------------------------------------------- fallback

    @Test
    void anUnmappedReasonPutsTheRawTextInTheTitle() {
        RecordingDialog panel = show(dialogFor(unmappedReason()));

        assertEquals("Co-op session ended (the modem exploded)", panel.text.paragraphs.get(0));
    }

    @Test
    void anEmptyReasonIsStillNeverABlankDialog() {
        RecordingDialog panel = show(CoopDesyncDialog.forReason(
                CoopDesyncReason.classify("  ", CoopDesyncReason.Source.OTHER),
                CoopConnectionRole.GUEST, () -> { }, null));

        assertFalse(panel.text.paragraphs.isEmpty());
        assertEquals("Co-op session ended (no reason recorded)", panel.text.paragraphs.get(0));
    }

    // ------------------------------------------------------------- behaviour

    @Test
    void everyDialogEndsWithTheCodeTheLogFileAndTheSearchString() {
        for (CoopDesyncReason reason : allReasons()) {
            RecordingDialog panel = show(dialogFor(reason));
            String last = panel.text.paragraphs.get(panel.text.paragraphs.size() - 1);
            assertTrue(last.contains("Support code " + reason.code()), last);
            assertTrue(last.contains("starsector.log"), last);
            assertTrue(last.contains(CoopDoctorMarker.searchString(reason)), last);
        }
    }

    @Test
    void noDialogUsesForbiddenWordingOrNonAsciiText() {
        for (CoopDesyncReason reason : allReasons()) {
            for (CoopConnectionRole role : List.of(CoopConnectionRole.HOST, CoopConnectionRole.GUEST)) {
                RecordingDialog panel = show(CoopDesyncDialog.forReason(reason, role, () -> { },
                        () -> { }));
                List<String> all = new ArrayList<>(panel.text.paragraphs);
                all.addAll(panel.options.texts);
                for (String line : all) {
                    assertFalse(line.contains("!"), "no exclamation marks: " + line);
                    for (String banned : List.of("error", "failed", "invalid", "Error", "Failed",
                            "Invalid")) {
                        assertFalse(line.contains(banned), "banned wording in: " + line);
                    }
                    for (int i = 0; i < line.length(); i++) {
                        char c = line.charAt(i);
                        assertTrue(c >= 0x20 && c < 0x7f,
                                "the bitmap font renders non-ASCII as a box: " + line);
                    }
                }
                assertFalse(panel.options.texts.contains("OK"), "no OK button anywhere");
            }
        }
    }

    @Test
    void escapeIsNeverWiredAndTheVisualPanelIsHidden() {
        for (CoopDesyncReason reason : allReasons()) {
            RecordingDialog panel = show(dialogFor(reason));
            assertEquals(0, panel.setOptionOnEscapeCount,
                    "ESC must not dismiss the only screen carrying the support code");
            assertEquals(1, panel.hideVisualPanelCount);
        }
    }

    @Test
    void closingRunsTheCallbackThenDismissesExactlyOnce() {
        List<String> events = new ArrayList<>();
        RecordingDialog panel = new RecordingDialog();
        panel.onDismiss = () -> events.add("dismissed");
        CoopDesyncDialog dialog = CoopDesyncDialog.forReason(seedReason(), CoopConnectionRole.GUEST,
                () -> events.add("closed"), null);
        dialog.init(panel.proxy());

        dialog.optionSelected("Close", panel.options.dataFor("Close"));
        // The pump's own close path may fire right behind the option handler.
        dialog.close();

        assertEquals(List.of("closed", "dismissed"), events,
                "teardown first, then the dismiss, and the dismiss is idempotent");
    }

    @Test
    void theSupportOptionOpensTheThreadAndLeavesTheDialogUp() {
        CoopDesyncDialog.urlOpener = openedUrls::add;
        RecordingDialog panel = new RecordingDialog();
        CoopDesyncDialog dialog = dialogFor(seedReason());
        dialog.init(panel.proxy());

        dialog.optionSelected(CoopDesyncDialog.OPTION_SUPPORT_TEXT,
                panel.options.dataFor(CoopDesyncDialog.OPTION_SUPPORT_TEXT));

        assertEquals(List.of(CoopDesyncDialog.SUPPORT_URL), openedUrls);
        assertEquals(0, panel.dismissCount, "the code the player is about to quote is on this screen");
        assertEquals(List.of(CoopDesyncDialog.OPTION_SUPPORT_TEXT, "Close"), panel.options.texts,
                "an empty option panel is the trapped-player bug");
    }

    @Test
    void aUrlOpenerThatThrowsNeverEscapes() {
        CoopDesyncDialog.urlOpener = url -> {
            throw new UnsatisfiedLinkError("no browser here");
        };
        RecordingDialog panel = new RecordingDialog();
        CoopDesyncDialog dialog = dialogFor(seedReason());
        dialog.init(panel.proxy());

        dialog.optionSelected(CoopDesyncDialog.OPTION_SUPPORT_TEXT,
                panel.options.dataFor(CoopDesyncDialog.OPTION_SUPPORT_TEXT));
    }

    @Test
    void anEngineThatThrowsFromEveryCallCannotTakeThePumpDown() {
        InteractionDialogAPI hostile = (InteractionDialogAPI) Proxy.newProxyInstance(
                InteractionDialogAPI.class.getClassLoader(),
                new Class<?>[]{InteractionDialogAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "HostileDialog";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new IllegalStateException("engine is on fire");
                });

        for (CoopDesyncReason reason : allReasons()) {
            CoopDesyncDialog dialog = CoopDesyncDialog.forReason(reason, CoopConnectionRole.GUEST,
                    () -> { }, () -> { });
            dialog.init(hostile);
            dialog.advance(0.016f);
            dialog.optionSelected("Close", null);
            dialog.close();
        }
    }

    @Test
    void aCloseActionThatThrowsStillDismissesTheDialog() {
        RecordingDialog panel = new RecordingDialog();
        CoopDesyncDialog dialog = CoopDesyncDialog.forReason(seedReason(), CoopConnectionRole.GUEST,
                () -> {
                    throw new IllegalStateException("teardown blew up");
                }, null);
        dialog.init(panel.proxy());

        dialog.optionSelected("Close", panel.options.dataFor("Close"));

        assertEquals(1, panel.dismissCount, "a broken teardown must not leave a modal on screen");
    }

    @Test
    void anUnknownOptionDataIsIgnored() {
        RecordingDialog panel = new RecordingDialog();
        CoopDesyncDialog dialog = dialogFor(seedReason());
        dialog.init(panel.proxy());

        dialog.optionSelected("Close", new Object());

        assertEquals(0, panel.dismissCount, "identity-keyed option data, so nothing else can collide");
    }

    // ----------------------------------------------------------------- setup

    private static List<CoopDesyncReason> allReasons() {
        return List.of(seedReason(), campaignIdReason(), modsReason(), graceReason(),
                CoopDesyncReason.classify(CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE,
                        CoopDesyncReason.Source.SESSION_RESUME),
                unmappedReason());
    }

    private static CoopDesyncReason seedReason() {
        return CoopDesyncReason.classify(
                CoopSeedSync.seedStringMismatch(
                        CoopSeedSync.formatSeedString(0x1122334455667788L),
                        CoopSeedSync.formatSeedString(0x99aabbccddeeff00L))
                        + "\n" + CoopSeedSync.fingerprintMismatch(HOST_FP, GUEST_FP),
                CoopDesyncReason.Source.SEED_LOCK);
    }

    private static CoopDesyncReason campaignIdReason() {
        return CoopDesyncReason.classify(
                "campaignId: host=camp-7f3a guest=<none>; this campaign is already in flight and this"
                        + " guest campaign is brand new (a fresh same-seed roll cannot silently rejoin"
                        + " it). To join anyway with a fresh start, relaunch the guest with"
                        + " -Dcoop.adoptCampaignId=true (launch-guest.ps1 -AdoptCampaign)",
                CoopDesyncReason.Source.SEED_LOCK);
    }

    private static CoopDesyncReason modsReason() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.8", Map.of()),
                        mod("other", "Other", "1.0", Map.of())),
                manifest(mod("utility", "Utility Mod", "2.7", Map.of())))
                .toDisplayString();
        return CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE);
    }

    private static CoopDesyncReason graceReason() {
        return CoopDesyncReason.classify(CoopReconnectCoordinator.REASON_GRACE_EXPIRED,
                CoopDesyncReason.Source.SESSION_RESUME);
    }

    private static CoopDesyncReason unmappedReason() {
        return CoopDesyncReason.classify("the modem exploded", CoopDesyncReason.Source.OTHER);
    }

    private static CoopDesyncDialog dialogFor(CoopDesyncReason reason) {
        return CoopDesyncDialog.forReason(reason, CoopConnectionRole.GUEST, () -> { }, null);
    }

    private static RecordingDialog show(CoopDesyncDialog dialog) {
        RecordingDialog panel = new RecordingDialog();
        dialog.init(panel.proxy());
        return panel;
    }

    private static int indexOfParagraphContaining(RecordingDialog panel, String needle) {
        for (int i = 0; i < panel.text.paragraphs.size(); i++) {
            if (panel.text.paragraphs.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static CoopHandshakeManifest manifest(CoopHandshakeManifest.ModEntry... mods) {
        return new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", List.of(mods));
    }

    private static CoopHandshakeManifest.ModEntry mod(String id, String name, String version,
                                                      Map<String, String> checksums) {
        return new CoopHandshakeManifest.ModEntry(id, name, version, "0.98a-RC8", "mods/" + id,
                List.of("jars/" + id + ".jar"), checksums);
    }

    /** The three engine surfaces a desync dialog touches, and nothing else. */
    private static final class RecordingDialog {
        private final RecordingTextPanel text = new RecordingTextPanel();
        private final RecordingOptionPanel options = new RecordingOptionPanel();
        private int dismissCount;
        private int hideVisualPanelCount;
        private int setOptionOnEscapeCount;
        private Runnable onDismiss;

        private InteractionDialogAPI proxy() {
            return (InteractionDialogAPI) Proxy.newProxyInstance(
                    InteractionDialogAPI.class.getClassLoader(),
                    new Class<?>[]{InteractionDialogAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hideVisualPanel" -> {
                            hideVisualPanelCount++;
                            yield null;
                        }
                        case "getTextPanel" -> text.proxy();
                        case "getOptionPanel" -> options.proxy();
                        case "setOptionOnEscape" -> {
                            setOptionOnEscapeCount++;
                            yield null;
                        }
                        case "dismiss" -> {
                            dismissCount++;
                            if (onDismiss != null) {
                                onDismiss.run();
                            }
                            yield null;
                        }
                        case "toString" -> "RecordingDialog";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class RecordingTextPanel {
        private final List<String> paragraphs = new ArrayList<>();

        private TextPanelAPI proxy() {
            return (TextPanelAPI) Proxy.newProxyInstance(
                    TextPanelAPI.class.getClassLoader(),
                    new Class<?>[]{TextPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addPara" -> {
                            paragraphs.add((String) args[0]);
                            yield null;
                        }
                        case "toString" -> "RecordingTextPanel";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class RecordingOptionPanel {
        private final List<String> texts = new ArrayList<>();
        private final List<Object> data = new ArrayList<>();

        private Object dataFor(String optionText) {
            int index = texts.indexOf(optionText);
            if (index < 0) {
                throw new IllegalArgumentException("no such option: " + optionText + " in " + texts);
            }
            return data.get(index);
        }

        private OptionPanelAPI proxy() {
            return (OptionPanelAPI) Proxy.newProxyInstance(
                    OptionPanelAPI.class.getClassLoader(),
                    new Class<?>[]{OptionPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "clearOptions" -> {
                            texts.clear();
                            data.clear();
                            yield null;
                        }
                        case "addOption" -> {
                            texts.add((String) args[0]);
                            data.add(args[1]);
                            yield null;
                        }
                        case "toString" -> "RecordingOptionPanel";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
