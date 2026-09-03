package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import coop.net.CoopConnectionRole;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Phase 21 desync dialogs: one per detectable cause, never one dialog with a swappable reason
 * string.
 *
 * <p><b>Why three classes and not a message table.</b> The spec's cautionary tale is Age of Empires 2,
 * whose single cause-less "out of sync" box outlived the game by a decade in support threads. A dialog
 * that only names the failure leaves the player with nothing to do; each of these names the cause and
 * the fix for that cause, in that order, and the only way to keep that honest is for each cause to own
 * its own text.
 *
 * <p><b>Why an interaction dialog and not {@code showConfirmDialog}.</b> Coop dialogs are exclusive:
 * the integration rule is that every one of them opens through the retry-until-{@code
 * showInteractionDialog} loop, which needs a plugin and gives a success signal that
 * {@code showConfirmDialog} does not. It also buys multiple paragraphs, which a fingerprint pair and a
 * capped mod list need.
 *
 * <p><b>Reading order is problem, cause, remedy, then detail.</b> Fabric Loader's solution-above-"more
 * details" ordering: the fingerprints and the raw reject text are last because they are for the log
 * reader, not the player. There is no "OK" button, no "error"/"failed"/"invalid" wording and no
 * exclamation marks anywhere in these strings - the session did not do anything wrong, it stopped.
 *
 * <p><b>ASCII only.</b> The mod's bitmap font renders anything outside ASCII as a box, and an em dash
 * has already shipped as a "?" once. Hyphens and plain quotes only.
 *
 * <p><b>Total.</b> Every engine call is wrapped, exactly as {@link CoopReconnectDialogPlugin} does it:
 * a dialog that cannot render must never take down the frame that is trying to end the session
 * cleanly. The feed line and the {@code [COOP-DOCTOR]} marker survive a dialog that never appears.
 */
public abstract class CoopDesyncDialog implements InteractionDialogPlugin, CoopDismissableDialog {

    /** Where "Open support thread" goes. */
    public static final String SUPPORT_URL = "https://github.com/AyoKeito/starsector-coop/issues";

    /** The log file the player is told to search; stated by name because nothing can copy it there. */
    public static final String LOG_FILE = "starsector.log";

    static final String OPTION_CLOSE_TEXT = "Close";
    static final String OPTION_RETRY_TEXT = "Try again";
    static final String OPTION_SUPPORT_TEXT = "Open support thread";

    private static final Object OPTION_CLOSE = new Object();
    private static final Object OPTION_RETRY = new Object();
    private static final Object OPTION_SUPPORT = new Object();

    /**
     * How the support option opens a browser. LWJGL's {@code Sys.openURL} is a static native call with
     * no seam of its own, so the indirection lives here; tests swap it out rather than launching a
     * browser on the machine running the suite.
     */
    static Consumer<String> urlOpener = url -> org.lwjgl.Sys.openURL(url);

    final CoopDesyncReason reason;
    final CoopConnectionRole role;

    private final Runnable onClose;
    private final Runnable onRetry;

    private InteractionDialogAPI dialog;

    CoopDesyncDialog(CoopDesyncReason reason, CoopConnectionRole role, Runnable onClose, Runnable onRetry) {
        this.reason = reason == null
                ? CoopDesyncReason.classify(null, CoopDesyncReason.Source.OTHER)
                : reason;
        this.role = role == null ? CoopConnectionRole.NONE : role;
        this.onClose = onClose == null ? () -> { } : onClose;
        this.onRetry = onRetry;
    }

    /**
     * The dialog for one classified reason. This is the seam the pump calls; it never returns null, so
     * an unrecognised reason still produces a dialog rather than a session that ends in silence.
     *
     * @param reason  the classified reason
     * @param role    this machine's role, which decides whether "you" means the host or the guest
     * @param onClose what the closing option runs before the dialog dismisses - the pump decides what
     *                closing means (teardown, return to lobby, nothing)
     * @param onRetry what the "Try again" option runs; may be null, and is only ever offered when
     *                {@link CoopDesyncReason#retryable()} is true
     */
    public static CoopDesyncDialog forReason(CoopDesyncReason reason, CoopConnectionRole role,
                                             Runnable onClose, Runnable onRetry) {
        CoopDesyncReason value = reason == null
                ? CoopDesyncReason.classify(null, CoopDesyncReason.Source.OTHER)
                : reason;
        return switch (value.kind()) {
            case SEED -> new Seed(value, role, onClose, onRetry);
            case MODS -> new Mods(value, role, onClose, onRetry);
            case GAME -> new Game(value, role, onClose, onRetry);
            case SESSION -> new Session(value, role, onClose, onRetry);
            case UNMAPPED -> new Unmapped(value, role, onClose, onRetry);
        };
    }

    /**
     * The campaign-feed line that goes with the dialog. The feed is the channel that survives dialog
     * teardown, so it always names the code: a player who dismissed the dialog before reading it can
     * still find out which one it was.
     */
    public static String feedLine(CoopDesyncReason reason) {
        CoopDesyncReason value = reason == null
                ? CoopDesyncReason.classify(null, CoopDesyncReason.Source.OTHER)
                : reason;
        return "Co-op: " + shortCause(value) + " (" + value.code() + ") - see the dialog";
    }

    /**
     * Three or four words naming the cause, with no code and no punctuation, for surfaces that have
     * no room for a sentence — the feed line above and the pump's HUD status line, which reads
     * "rejected: COOP-SEED, seed mismatch". Extracted so the two say the same thing: a HUD that
     * named the cause differently from the feed banner beside it would read as two different
     * failures.
     */
    public static String shortCause(CoopDesyncReason reason) {
        CoopDesyncReason value = reason == null
                ? CoopDesyncReason.classify(null, CoopDesyncReason.Source.OTHER)
                : reason;
        return switch (value.kind()) {
            case SEED -> value.campaignIdMismatch() ? "campaign mismatch" : "seed mismatch";
            case MODS -> "mod mismatch";
            case GAME -> "game version mismatch";
            case SESSION -> "session not resumed";
            case UNMAPPED -> "session ended";
        };
    }

    // ------------------------------------------------------------ dialog body

    /** First paragraph: what happened, in the player's terms. */
    abstract String title();

    /** Cause then remedy, in that order and in likelihood order within the remedy. */
    abstract List<String> bodyParagraphs();

    /** The detail a log reader wants and a player does not; always last, before the support line. */
    List<String> technicalParagraphs() {
        return List.of();
    }

    /** Label of the closing option; a verb, never "OK". */
    String closeOptionText() {
        return OPTION_CLOSE_TEXT;
    }

    String closeOptionTooltip() {
        return "Closes this dialog. The co-op session has already stopped; your own campaign stays"
                + " loaded.";
    }

    // ------------------------------------------------------- plugin lifecycle

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            dialog.hideVisualPanel();
        } catch (Throwable ignored) {
            // Cosmetic only: a visual panel that will not hide beats no dialog at all.
        }
        renderBody();
        renderOptions();
        // Deliberately no setOptionOnEscape: ESC must not dismiss a dialog that is the only place the
        // support code is written down. The options are the way out.
    }

    @Override
    public void advance(float amount) {
        // Nothing moves in these dialogs: every fact in them was fixed the moment the session ended.
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (optionData == OPTION_SUPPORT) {
            openSupportThread();
            // Stays open on purpose: the browser opens behind the game, and the code the player is
            // about to quote is on this screen. Re-assert the options for the same reason the
            // reconnect dialog does - a modal with an empty option panel is the trapped-player bug.
            renderOptions();
            return;
        }
        if (optionData == OPTION_RETRY) {
            run(onRetry, "retry");
            close();
            return;
        }
        if (optionData != OPTION_CLOSE) {
            return;
        }
        // The caller's teardown runs first, then the dismiss: closing before it would leave the
        // pump's own close path racing this handler.
        run(onClose, "close");
        close();
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    @Override
    public Object getContext() {
        return null;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return Map.of();
    }

    @Override
    public void close() {
        InteractionDialogAPI open = dialog;
        dialog = null;
        if (open == null) {
            return;
        }
        try {
            open.dismiss();
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop desync dialog could not be dismissed", ex);
        }
    }

    /** True when this dialog offers a retry; only the transient session causes do. */
    final boolean offersRetry() {
        return reason.retryable() && onRetry != null;
    }

    private void renderBody() {
        try {
            TextPanelAPI text = dialog == null ? null : dialog.getTextPanel();
            if (text == null) {
                return;
            }
            text.addPara(title());
            for (String paragraph : bodyParagraphs()) {
                if (paragraph != null && !paragraph.isEmpty()) {
                    text.addPara(paragraph);
                }
            }
            for (String paragraph : technicalParagraphs()) {
                if (paragraph != null && !paragraph.isEmpty()) {
                    text.addPara(paragraph);
                }
            }
            text.addPara(supportParagraph());
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop desync dialog could not render its text", ex);
        }
    }

    private void renderOptions() {
        try {
            OptionPanelAPI options = dialog == null ? null : dialog.getOptionPanel();
            if (options == null) {
                return;
            }
            options.clearOptions();
            if (offersRetry()) {
                options.addOption(OPTION_RETRY_TEXT, OPTION_RETRY, retryOptionTooltip());
            }
            options.addOption(OPTION_SUPPORT_TEXT, OPTION_SUPPORT,
                    "Opens the co-op issue tracker in your browser. The game keeps running behind it.");
            options.addOption(closeOptionText(), OPTION_CLOSE, closeOptionTooltip());
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop desync dialog could not render its options", ex);
        }
    }

    String retryOptionTooltip() {
        return "Asks the host for the session once more. Nothing is lost if it is turned down again.";
    }

    /**
     * The support line every dialog ends with: the code, the file, and the exact search string. That
     * is the whole support affordance - there is no clipboard write in this engine, so a player has to
     * be able to retype what they are looking for.
     */
    final String supportParagraph() {
        return "Support code " + reason.code() + ". The full detail is in " + LOG_FILE
                + " - search it for: " + CoopDoctorMarker.searchString(reason);
    }

    private void openSupportThread() {
        try {
            urlOpener.accept(SUPPORT_URL);
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop desync dialog could not open " + SUPPORT_URL, ex);
        }
    }

    private void run(Runnable action, String what) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop desync dialog " + what + " action failed", ex);
        }
    }

    /** "you" / "the host" / "the guest", from the reading side. */
    final String partner() {
        return role == CoopConnectionRole.HOST ? "the guest" : "the host";
    }

    private String localOf(String hostValue, String guestValue) {
        return role == CoopConnectionRole.HOST ? hostValue : guestValue;
    }

    private String remoteOf(String hostValue, String guestValue) {
        return role == CoopConnectionRole.HOST ? guestValue : hostValue;
    }

    /** "yours X, the host's Y", with either side omitted when the diff did not carry it. */
    final String sideBySide(String label, String hostValue, String guestValue) {
        String local = orUnknown(localOf(hostValue, guestValue));
        String remote = orUnknown(remoteOf(hostValue, guestValue));
        return label + " - yours " + local + ", " + partner() + "'s " + remote + ".";
    }

    private static String orUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "not reported" : value.trim();
    }

    // ------------------------------------------------------------ the three

    /**
     * COOP-SEED: the two players are not in the same sector, or not in the same campaign. A hard block
     * with no "join anyway" - there is no version of joining that produces a shared world here, and an
     * override option would only move the failure to somewhere less legible.
     */
    static final class Seed extends CoopDesyncDialog {

        Seed(CoopDesyncReason reason, CoopConnectionRole role, Runnable onClose, Runnable onRetry) {
            super(reason, role, onClose, onRetry);
        }

        @Override
        String title() {
            if (reason.campaignIdMismatch()) {
                return role == CoopConnectionRole.HOST
                        ? "The guest's save is not from this co-op campaign."
                        : "This save is not from the host's co-op campaign.";
            }
            return "Your sector and " + partner() + "'s sector are not the same.";
        }

        @Override
        List<String> bodyParagraphs() {
            List<String> body = new ArrayList<>();
            if (reason.campaignIdMismatch()) {
                if (role == CoopConnectionRole.HOST) {
                    body.add("Co-op stamps a campaign with an id the first time a session runs in it."
                            + " The save the guest loaded carries a different id, or none, so it is a"
                            + " different campaign even when the seed matches.");
                    body.add("Most likely fix: the guest loads the co-op save from this campaign - the"
                            + " one written while you were in a session together. Your lobby is"
                            + " already waiting.");
                    body.add("If the guest meant to start over inside your campaign instead, they"
                            + " relaunch with launch-guest.ps1 -AdoptCampaign. That gives them a fresh"
                            + " world and leaves their old save's progress behind.");
                    return body;
                }
                body.add("Co-op stamps a campaign with an id the first time a session runs in it. The"
                        + " save loaded here carries a different id, so it is a different campaign even"
                        + " when the seed matches.");
                body.add("Most likely fix: load the co-op save from this campaign - the one written"
                        + " while you were in a session together.");
                body.add("If you meant to start over inside the host's campaign instead,"
                        + " relaunch with launch-guest.ps1 -AdoptCampaign. That accepts a fresh world"
                        + " on this side and leaves this save's progress behind.");
                return body;
            }
            body.add("A co-op session needs both players in a sector built from the same seed. These"
                    + " two were built differently, so the same place is not in the same location on"
                    + " the two maps and nothing can be kept in step.");
            String hostSeed = reason.hostSeed();
            if (role == CoopConnectionRole.HOST) {
                body.add("Most likely fix: " + partner() + " starts a new campaign from your seed."
                        + " Read it out to them: " + (reason.hostSeed().isEmpty()
                        ? "it is on the co-op line of your log" : reason.hostSeed()));
            } else if (!hostSeed.isEmpty()) {
                body.add("Most likely fix: start a new campaign here and type the host's seed into the"
                        + " seed field on the New Game screen: " + hostSeed);
            } else {
                body.add("Most likely fix: ask the host for their seed and start a new campaign here"
                        + " with it typed into the seed field on the New Game screen.");
            }
            body.add("Otherwise: both of you load the co-op saves written during an earlier session."
                    + " Those already share a sector.");
            return body;
        }

        @Override
        List<String> technicalParagraphs() {
            List<String> detail = new ArrayList<>();
            if (reason.campaignIdMismatch()) {
                detail.add(sideBySide("Campaign id", reason.hostCampaignId(),
                        reason.guestCampaignId().isEmpty() ? "none stored" : reason.guestCampaignId()));
                return detail;
            }
            if (!reason.hostSeed().isEmpty() || !reason.guestSeed().isEmpty()) {
                detail.add(sideBySide("Seed", reason.hostSeed(), reason.guestSeed()));
            }
            String hostShort = CoopDesyncReason.shortFingerprint(reason.hostFingerprint());
            String guestShort = CoopDesyncReason.shortFingerprint(reason.guestFingerprint());
            if (!hostShort.isEmpty() || !guestShort.isEmpty()) {
                detail.add(sideBySide("Sector fingerprint", hostShort, guestShort)
                        + " Those are the first 8 characters; read them to each other to confirm you"
                        + " are looking at the same difference.");
            }
            return detail;
        }
    }

    /**
     * COOP-MODS: the two installs differ. Every row carries its own remedy verb, and blame goes to
     * whichever side is actually behind - being told to update a mod that is already newer is how a
     * player learns to stop reading these.
     */
    static final class Mods extends CoopDesyncDialog {

        Mods(CoopDesyncReason reason, CoopConnectionRole role, Runnable onClose, Runnable onRetry) {
            super(reason, role, onClose, onRetry);
        }

        @Override
        String title() {
            return "The two installs do not have the same mods.";
        }

        @Override
        List<String> bodyParagraphs() {
            List<String> body = new ArrayList<>();
            body.add("Co-op compares every enabled mod before a session starts. One difference is"
                    + " enough to change the world the two of you are meant to share, so the session"
                    + " stops here rather than drifting apart later.");
            if (!reason.ironModeSide().isEmpty()) {
                body.add("Iron Mode is on for " + ironSideText() + ". Co-op has to save on demand, so"
                        + " the campaign has to be started without Iron Mode.");
            }
            if (reason.gameVersionMismatch()) {
                body.add(sideBySide("Starsector version", reason.hostGameVersion(),
                        reason.guestGameVersion())
                        + " Both players need the same game version before the mods matter.");
            }
            if (reason.coopBuildMismatch()) {
                body.add(sideBySide("Co-op mod build", reason.hostCoopBuild(), reason.guestCoopBuild())
                        + " Copy the same build to both machines.");
            }
            for (CoopDesyncReason.ModRow row : reason.modRows()) {
                body.add("- " + row.displayName() + ": " + row.verdictText(role)
                        + " - " + row.remedyText(role));
            }
            if (reason.hiddenModRows() > 0) {
                body.add("... and " + reason.hiddenModRows() + " more. The full list is in " + LOG_FILE + ".");
            }
            if (reason.modRows().isEmpty() && reason.manifestUnreadable()) {
                body.add("The other side's mod list could not be read at all, so there is no per-mod"
                        + " list to show here. The raw text is in " + LOG_FILE + ".");
            }
            if (reason.hasSameVersionDifferentContents()) {
                body.add("A mod above reports the same version on both sides but its files differ."
                        + " That is a real difference, not a display quirk: a partial download or a"
                        + " hand-edited file will do it. Reinstall it from the same download the other"
                        + " player used.");
            }
            body.add("Co-op never downloads mods for you. Change the mod set in the launcher on the"
                    + " side that needs it, then start the session again.");
            return body;
        }

        private String ironSideText() {
            boolean localIsHost = role == CoopConnectionRole.HOST;
            boolean ironIsHost = "host".equals(reason.ironModeSide());
            return ironIsHost == localIsHost ? "this campaign" : partner() + "'s campaign";
        }
    }

    /**
     * COOP-GAME: this install's Starsector is not the one the mod was built for.
     *
     * <p>The only dialog here with no partner in it. It is raised before a socket is opened, from a
     * check that ran at application load, so there is no "you" and "the host" to phrase against and
     * no side to assign blame to - both players are told the same thing, and the fix is the same
     * sentence on both machines.
     *
     * <p>The remedy names the developer flag last and by its launcher label, not by its property
     * name. A tester who wants it will find it; a player who does not know what it is will not be
     * invited to type a {@code -D} into {@code vmparams} to make a refusal go away.
     */
    static final class Game extends CoopDesyncDialog {

        Game(CoopDesyncReason reason, CoopConnectionRole role, Runnable onClose, Runnable onRetry) {
            super(reason, role, onClose, onRetry);
        }

        @Override
        String title() {
            return "This Starsector version does not match the mod.";
        }

        @Override
        List<String> bodyParagraphs() {
            String mod = orUnknownVersion(reason.modGameVersion());
            String game = orUnknownVersion(reason.installedGameVersion());
            List<String> body = new ArrayList<>();
            body.add("The co-op mod was built for Starsector " + mod + ". This game is "
                    + game + ". Part of the mod is compiled against the game's own code, so a"
                    + " different version does not just look wrong, it behaves in ways nothing here"
                    + " has ever been tested against.");
            body.add("Install Starsector " + mod + " on both PCs, or wait for a release of the mod"
                    + " built for " + game + ".");
            body.add("Testers can set the developer flag Allow game version mismatch in the"
                    + " launcher's Advanced card. That runs co-op on this version anyway and is not"
                    + " supported.");
            body.add("Nothing was started: no port was opened and no connection was made. Your"
                    + " campaign is unaffected and plays as single player.");
            return body;
        }

        @Override
        List<String> technicalParagraphs() {
            return List.of("Mod built for " + orUnknownVersion(reason.modGameVersion())
                    + ", game reports " + orUnknownVersion(reason.installedGameVersion()) + ".");
        }

        private static String orUnknownVersion(String value) {
            return value == null || value.trim().isEmpty() ? "an unknown version" : value.trim();
        }
    }

    /**
     * COOP-SESSION: the session itself could not be picked back up. One body per detectable cause, and
     * the grace window stated as a number - "the window closed" without the length is a fact the
     * player cannot act on.
     */
    static final class Session extends CoopDesyncDialog {

        Session(CoopDesyncReason reason, CoopConnectionRole role, Runnable onClose, Runnable onRetry) {
            super(reason, role, onClose, onRetry);
        }

        private CoopDesyncReason.SessionCause cause() {
            return reason.sessionCause() == null
                    ? CoopDesyncReason.SessionCause.OTHER
                    : reason.sessionCause();
        }

        @Override
        String title() {
            return switch (cause()) {
                case GRACE_EXPIRED -> "The co-op session was not resumed: the reconnect window closed"
                        + " before the link came back.";
                case DIFFERENT_CAMPAIGN -> partner() + " is holding a different session than the one"
                        + " this game tried to resume.";
                case SLOT_TAKEN -> "That place in the session belongs to a different player.";
                case HOST_IN_GRACE -> partner() + " is still holding the session for the player who"
                        + " dropped.";
                case ENDED_BY_PLAYER -> "The co-op session was ended by hand.";
                case OTHER -> "The co-op session ended.";
            };
        }

        @Override
        List<String> bodyParagraphs() {
            List<String> body = new ArrayList<>();
            switch (cause()) {
                case GRACE_EXPIRED -> {
                    body.add(reason.graceSeconds() >= 0
                            ? "Co-op held the world for " + reason.graceSeconds() + " seconds waiting"
                            + " for the connection to come back. It did not return in that time, so"
                            + " the session was released."
                            : "Co-op held the world open waiting for the connection to come back. The"
                            + " window ran out before it returned, so the session was released.");
                    body.add("Start a fresh session: whoever dropped loads their co-op save and"
                            + " connects again; the other side's lobby is already waiting. Both saves"
                            + " carry the same campaign, so the progress up to the last co-op save is"
                            + " still there.");
                }
                case DIFFERENT_CAMPAIGN -> {
                    body.add("That usually means " + partner() + " restarted, or loaded a different"
                            + " save, while this side was away.");
                    body.add("Load the co-op save that matches the campaign " + partner() + " is"
                            + " running now, then connect again.");
                }
                case SLOT_TAKEN -> {
                    body.add(partner() + " is keeping that place for a different player id than this"
                            + " one - usually a second copy of the game, or a different launch of it.");
                    body.add("Close the other copy and connect again, or start a fresh session"
                            + " together.");
                }
                case HOST_IN_GRACE -> {
                    body.add("One player is admitted at a time, and that window is still open. It"
                            + " closes on its own when it runs out"
                            + (reason.graceSeconds() >= 0
                            ? ", which takes up to " + reason.graceSeconds() + " seconds." : "."));
                    body.add("Wait a moment, then try again.");
                }
                case ENDED_BY_PLAYER -> {
                    body.add("Somebody pressed the end option on the reconnect dialog rather than"
                            + " waiting the window out.");
                    body.add("The host can load its co-op save and start a new session whenever you"
                            + " are both ready.");
                }
                case OTHER -> {
                    body.add("The link is down and the session was released.");
                    body.add("Start a fresh session: whoever dropped loads their co-op save and"
                            + " connects again; the other side's lobby is already waiting.");
                }
            }
            return body;
        }

        @Override
        List<String> technicalParagraphs() {
            return List.of("Reported reason: " + firstLine(reason.rawReason()));
        }
    }

    /**
     * The fallback. It exists so that a reason nobody anticipated still puts a dialog on screen with
     * the raw text in it: a session that ends in silence is the failure mode this whole set is here to
     * prevent, and an empty message box is only marginally better.
     */
    static final class Unmapped extends CoopDesyncDialog {

        private static final int TITLE_LIMIT = 90;

        Unmapped(CoopDesyncReason reason, CoopConnectionRole role, Runnable onClose, Runnable onRetry) {
            super(reason, role, onClose, onRetry);
        }

        @Override
        String title() {
            return "Co-op session ended (" + shorten(firstLine(reason.rawReason())) + ")";
        }

        @Override
        List<String> bodyParagraphs() {
            return List.of(
                    "Co-op does not have a specific message for that one. The text in brackets above"
                            + " is exactly what was reported.",
                    "Start a fresh session: whoever dropped loads their co-op save and connects"
                            + " again; the other side's lobby is already waiting.");
        }

        @Override
        List<String> technicalParagraphs() {
            String raw = reason.rawReason();
            String first = firstLine(raw);
            if (first.equals(raw) && first.length() <= TITLE_LIMIT) {
                // Already fully visible in the title; repeating it verbatim adds nothing.
                return List.of();
            }
            return List.of("Reported reason: " + raw.replace('\n', ' '));
        }

        private static String shorten(String value) {
            return value.length() <= TITLE_LIMIT ? value : value.substring(0, TITLE_LIMIT - 3) + "...";
        }
    }

    static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        int newline = value.indexOf('\n');
        return newline < 0 ? value.trim() : value.substring(0, newline).trim();
    }
}
