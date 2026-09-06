package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import coop.config.CoopOptionsPolicy;
import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.net.CoopConnectionRole;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Phase 28 milestone 3: the "Coop Options" intel entry - the in-campaign editor for everything in
 * {@link CoopOptionsRegistry}.
 *
 * <p>Third coop page, on the same transient lifecycle as the other two ({@link CoopSessionIntel},
 * {@link CoopSessionStatsIntel}): removed in {@code beforeGameSave}, recreated in
 * {@code afterGameSave} and {@code onGameLoad}, so no instance of this class ever reaches XStream.
 * It holds no persistent state - every value is read live from {@link CoopOptionsPolicy} and
 * {@link CoopOptionsStore} on render, and the two things the page's own widgets own (the handle on
 * the credit-amount field and the last refusal shown under it) are rebuilt by the next render, so
 * the save cycle has nothing of this page's to reach.
 *
 * <p><b>The intel surface is buttons, not a settings menu.</b> {@code TooltipMakerAPI.addButton}
 * with a {@code buttonPressConfirmed} callback is the whole vocabulary for the option rows, so
 * booleans get a toggle, enums get a cycle, bounded integers get a {@code -}/{@code +} pair, and free
 * text cannot be edited here at all (see {@link CoopOptionsView}). The one exception is the credit
 * amount, which is a {@code TooltipMakerAPI.addTextField} - the first text input the mod puts on an
 * intel page, added because stepping to an arbitrary number with fixed-size buttons is a dozen
 * presses. The title screen has no mod API, so this page plus the settings file is the complete
 * surface - there is no pre-campaign UI to add one to.
 *
 * <p><b>Nothing here may take the intel screen down.</b> Every engine call is wrapped and a failure
 * degrades to one line plus one log warning, exactly like its two sibling pages: an exception out of
 * {@code createLargeDescription} takes the whole intel tab with it.
 */
public class CoopOptionsPage extends BaseIntelPlugin {

    /** The entry's title, and its sort string. Sorts after the other two coop entries. */
    public static final String NAME = "Coop Options";

    /** Sector-memory key holding the pin state across the remove/recreate cycle. */
    public static final String PIN_MEMORY_KEY = "$coopOptionsPinned";

    /** Button id for "put everything back the way it shipped". */
    public static final Object BUTTON_RESET = new Object();

    /** Button id for "hand the typed amount to the partner" (Phase 32 addition B). */
    public static final Object BUTTON_SEND_CREDITS = new Object();

    /** Heading of the credit-transfer block. */
    static final String CREDITS_HEADING = "Send credits";

    /**
     * Characters the amount field takes. {@code 1,000,000,000} is thirteen, which is the largest
     * amount {@code CoopMessages.MAX_CREDITS_GRANT} allows written the way this page prints numbers.
     */
    static final int AMOUNT_FIELD_MAX_CHARS = 13;

    /** Rendered instead of the page when anything at all goes wrong building it. */
    static final String UNAVAILABLE_LINE = CoopOptionsView.UNAVAILABLE_LINE;

    /** Log-once guard for a broken render. Static: one warning per process, not one per open. */
    private static boolean renderFailureLogged;

    /**
     * Reads the amount field. Rebound by every render to the widget that render built, and left
     * answering "" when there is no widget - a page that has never been drawn, or an engine that
     * refused to build the field.
     *
     * <p>Transient by nature rather than by keyword: this class is removed before every save (see the
     * class doc), so no instance of it, and no widget behind this lambda, ever reaches XStream.
     */
    Supplier<String> amountText = () -> "";

    /** Empties the widget behind {@link #amountText}; a no-op when there is no widget. */
    private Runnable amountClear = () -> { };

    /**
     * Why the last Send press did nothing, shown under the field until the next press.
     *
     * <p>Instance state, unlike the old pending amount: it is one page's transient feedback, it means
     * nothing to any other part of the mod, and it must not outlive the entry across a save.
     */
    private String lastCreditRefusal = "";

    /** One button press: which key, and which way. */
    record Press(String key, int direction) {
    }

    /**
     * Everything the "Send credits" block renders, decided without touching the engine so it can be
     * unit-tested. See {@link #creditRow}.
     *
     * <p>The amount is not in here: it is whatever is in the text field, which only the widget knows
     * and only a press reads. What this decides is whether sending is possible at all.
     *
     * @param walletText  what the local player has, or "" when there is no wallet to read
     * @param note        the one-line reason Send is dead, or "" when it is live
     * @param sendEnabled whether the Send button is live
     */
    record CreditRow(String walletText, String note, boolean sendEnabled) {
    }

    /**
     * One reading of the amount field: the amount, or the sentence saying why it is not one.
     *
     * @param amount  the parsed amount; meaningless unless {@link #ok()}
     * @param refusal the sentence shown under the field, or "" when the amount is good
     */
    record TypedAmount(int amount, String refusal) {

        TypedAmount {
            refusal = refusal == null ? "" : refusal;
        }

        boolean ok() {
            return refusal.isEmpty();
        }
    }

    // ---- registration ----------------------------------------------------------------------------

    /** Adds the entry if this campaign does not already have one. Idempotent. */
    public static CoopOptionsPage ensureRegistered(SectorAPI sector) {
        // Re-armed here rather than never: this runs on every campaign load, and the "one warning
        // per process" guard is meant to stop a broken render spamming the log within one session,
        // not to hide a different failure two campaigns later.
        renderFailureLogged = false;
        try {
            if (sector == null) {
                return null;
            }
            IntelManagerAPI manager = sector.getIntelManager();
            if (manager == null) {
                return null;
            }
            CoopOptionsPage existing = findExisting(manager);
            if (existing != null) {
                return existing;
            }
            CoopOptionsPage intel = new CoopOptionsPage();
            manager.addIntel(intel, true);
            CoopLog.info(CoopOptionsPage.class, "Coop options intel entry registered");
            return intel;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopOptionsPage.class, "Could not register the coop options intel entry", ex);
            return null;
        }
    }

    /** Drops the entry so nothing of this class reaches XStream. Safe with nothing registered. */
    public static boolean remove(SectorAPI sector) {
        try {
            if (sector == null) {
                return false;
            }
            IntelManagerAPI manager = sector.getIntelManager();
            if (manager == null) {
                return false;
            }
            CoopOptionsPage existing = findExisting(manager);
            if (existing == null) {
                return false;
            }
            manager.removeIntel(existing);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopOptionsPage.class, "Could not remove the coop options intel entry", ex);
            return false;
        }
    }

    private static CoopOptionsPage findExisting(IntelManagerAPI manager) {
        List<IntelInfoPlugin> found = manager.getIntel(CoopOptionsPage.class);
        if (found == null) {
            return null;
        }
        for (IntelInfoPlugin plugin : found) {
            if (plugin instanceof CoopOptionsPage intel) {
                return intel;
            }
        }
        return null;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    @Override
    public boolean isEnded() {
        return false;
    }

    @Override
    public boolean isEnding() {
        return false;
    }

    @Override
    public boolean shouldRemoveIntel() {
        return false;
    }

    /**
     * Hidden until a pump has installed a policy - which is to say, until this campaign is running
     * under the coop mod at all. A save loaded without the mod has no entry to hide.
     */
    @Override
    public boolean isHidden() {
        try {
            return CoopOptionsPolicy.active() == null;
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
    }

    @Override
    public boolean autoAddCampaignMessage() {
        return false;
    }

    @Override
    public String getCommMessageSound() {
        return null;
    }

    /** No star, for the same reason as the stats page: the object is recreated around every save. */
    @Override
    public boolean hasImportantButton() {
        return false;
    }

    @Override
    public boolean isImportant() {
        try {
            SectorAPI sector = Global.getSector();
            return sector != null && sector.getMemoryWithoutUpdate() != null
                    && sector.getMemoryWithoutUpdate().getBoolean(PIN_MEMORY_KEY);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    @Override
    public void setImportant(Boolean important) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getMemoryWithoutUpdate() == null) {
                return;
            }
            sector.getMemoryWithoutUpdate().set(PIN_MEMORY_KEY, Boolean.TRUE.equals(important));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopOptionsPage.class, "Could not store the coop options pin state", ex);
        }
    }

    @Override
    public boolean isNew() {
        return false;
    }

    @Override
    public IntelSortTier getSortTier() {
        return IntelSortTier.TIER_1;
    }

    @Override
    public String getSortString() {
        return NAME;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /** Shares {@link CoopSessionIntel#TAG_COOP} so all three coop entries filter into one bucket. */
    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(CoopSessionIntel.TAG_COOP);
        return tags;
    }

    @Override
    public String getIcon() {
        return null;
    }

    // ---- list row --------------------------------------------------------------------------------

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        try {
            info.addPara(NAME, getTitleColor(mode), 0f);
            info.addPara(listLine(), getBulletColorForMode(mode), 0f);
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    static String listLine() {
        return role() == CoopConnectionRole.GUEST
                ? "Your preferences; the host's session rules, read-only"
                : "Session rules and local preferences";
    }

    // ---- page ------------------------------------------------------------------------------------

    @Override
    public boolean hasSmallDescription() {
        return false;
    }

    @Override
    public boolean hasLargeDescription() {
        return true;
    }

    @Override
    public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
        TooltipMakerAPI info;
        try {
            info = panel.createUIElement(width, height, true);
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            return;
        }
        try {
            render(info, view(), width);
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            addUnavailableLine(info);
        }
        try {
            panel.addUIElement(info).inTL(0f, 0f);
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    /** The model for this client right now; see {@link CoopOptionsView}. */
    static CoopOptionsView view() {
        CoopConnectionRole role = role();
        return CoopOptionsView.of(role, role != CoopConnectionRole.NONE, new LiveReader());
    }

    private static CoopConnectionRole role() {
        try {
            CoopConnectionRole role = CoopSessionIntelFeed.currentModel().localRole();
            return role == null ? CoopConnectionRole.NONE : role;
        } catch (RuntimeException | LinkageError ex) {
            return CoopConnectionRole.NONE;
        }
    }

    /** The live policy and settings stack behind the view. */
    static final class LiveReader implements CoopOptionsView.Reader {
        private final CoopOptionsPolicy policy = CoopOptionsPolicy.active();
        private final CoopOptionsStore store = CoopOptionsStore.system();

        @Override
        public String policyValue(String key) {
            try {
                return policy == null ? null : policy.effective(key);
            } catch (RuntimeException | LinkageError ex) {
                return null;
            }
        }

        @Override
        public boolean policyPending(String key) {
            try {
                return policy != null && policy.hasPendingChange(key);
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }

        @Override
        public String localValue(String key) {
            try {
                return store.string(key);
            } catch (RuntimeException | LinkageError ex) {
                return CoopOptionsRegistry.require(key).defaultValue();
            }
        }

        /**
         * Deliberately {@code sourceOf(...) == PROPERTY} rather than {@code hasProperty(...)}.
         * {@code hasProperty} trims a blank away, but the resolution stack does not: an explicitly
         * empty {@code -Dcoop.hudCorner=} is the property layer deciding, and the file layers below
         * it never get a look in. Asking the trimming question tagged that row {@code (default)} and
         * drew a button on it, and pressing the button wrote a file value the next read discarded.
         */
        @Override
        public boolean commandLine(String key) {
            try {
                return store.sourceOf(key) == CoopOptionsStore.Source.PROPERTY;
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }

        @Override
        public boolean userFile(String key) {
            try {
                return store.sourceOf(key) == CoopOptionsStore.Source.COMMON;
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }
    }

    /** Maps the view onto widgets. Every decision lives in {@link CoopOptionsView}. */
    void render(TooltipMakerAPI info, CoopOptionsView view, float width) {
        Color highlight = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();
        Color warn = Misc.getNegativeHighlightColor();
        float buttonWidth = Math.max(120f, Math.min(width * 0.45f, 260f));

        for (CoopOptionsView.Section section : view.sections()) {
            if (section.rows().isEmpty()) {
                continue;
            }
            info.addSectionHeading(section.title(), Alignment.MID, 12f);
            info.addPara(section.subtitle(), gray, 6f);
            for (CoopOptionsView.Row row : section.rows()) {
                info.addPara(row.label() + ": " + row.valueText() + "  " + row.sourceTag(),
                        row.editable() ? highlight : gray, 6f);
                if (!row.pendingNote().isEmpty()) {
                    info.addPara(BULLET + row.pendingNote(), warn, 2f);
                }
                if (!row.note().isEmpty()) {
                    info.addPara(BULLET + row.note(), gray, 2f);
                }
                addRowButtons(info, row, buttonWidth);
            }
        }
        addCreditsBlock(info, buttonWidth);
        addResetButton(info, buttonWidth);
    }

    private void addRowButtons(TooltipMakerAPI info, CoopOptionsView.Row row, float width) {
        if (!row.editable()) {
            return;
        }
        switch (row.control()) {
            case TOGGLE -> addButton(info, Boolean.parseBoolean(row.rawValue()) ? "Turn off" : "Turn on",
                    new Press(row.key(), 1), width);
            case CYCLE -> {
                String next = CoopOptionsView.nextValue(row.key(), row.rawValue(), 1);
                if (next != null) {
                    addButton(info, "Change to " + next, new Press(row.key(), 1), width);
                }
            }
            case STEPPER -> {
                // Two stacked buttons rather than one row of two: the intel tooltip lays widgets out
                // vertically and hand-positioning them is the kind of layout maths that breaks on a
                // screen size nobody tested.
                if (CoopOptionsView.nextValue(row.key(), row.rawValue(), -1) != null) {
                    addButton(info, "Less", new Press(row.key(), -1), width);
                }
                if (CoopOptionsView.nextValue(row.key(), row.rawValue(), 1) != null) {
                    addButton(info, "More", new Press(row.key(), 1), width);
                }
            }
            case CLEAR -> {
                if (!row.rawValue().isEmpty()) {
                    addButton(info, "Clear", new Press(row.key(), 0), width);
                }
            }
            default -> {
                // No control for this row; the value is read-only here by design.
            }
        }
    }

    // ---- Phase 32 addition B: the "Send credits" block -------------------------------------------

    /**
     * The credit-transfer block's whole model, engine-free.
     *
     * <p>Send is live only when a session is up and the peer is connected: the button must not
     * promise something {@link coop.campaign.CoopCreditTransfer#send} would refuse. Whether the
     * amount itself is sendable is not decided here, because the amount is in the text field and
     * only a press reads it - see {@link #parseAmount}.
     *
     * <p>An unreadable wallet ({@code credits < 0}) disables Send and says so (credit red-team P2-4).
     * It used to leave the button live on the reasoning that the real cover check is in {@code send};
     * that is true, but {@code send} answers it with "not enough credits, 25,000 needed, 0
     * available" on a page that shows no balance at all, so the player got an enabled button that
     * always failed with a message contradicting what was in front of them.
     *
     * @param canSend the transfer's own answer for "is there a session with a connected peer"
     * @param credits the local player's credits, negative when unreadable
     */
    static CreditRow creditRow(boolean canSend, long credits) {
        String walletText = credits < 0 ? ""
                : coop.campaign.CoopCreditTransfer.format(credits);
        if (!canSend) {
            return new CreditRow(walletText,
                    "No co-op session; there is nobody to send credits to.", false);
        }
        if (credits < 0) {
            return new CreditRow(walletText,
                    "Your wallet could not be read; credits cannot be sent right now.", false);
        }
        return new CreditRow(walletText, "", true);
    }

    /** The live model: the installed transfer's session state and wallet. */
    static CreditRow liveCreditRow() {
        try {
            coop.campaign.CoopCreditTransfer transfer = coop.campaign.CoopCreditTransfer.active();
            return creditRow(transfer != null && transfer.canSend(),
                    transfer == null ? -1L : transfer.credits());
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            return creditRow(false, -1L);
        }
    }

    /**
     * Reads the amount the player typed.
     *
     * <p>Accepts what a player actually types at a number this size: plain digits, or digits grouped
     * with commas or spaces, because the page prints every other amount as {@code 25,000} and typing
     * back what you just read has to work. A decimal point gets its own sentence rather than being
     * silently swallowed - stripping it would turn {@code 1.5} into {@code 15}, which is the kind of
     * quiet mangling that ends with the wrong amount leaving the wallet.
     *
     * <p>Pure, and the whole reason the field is testable: text and a balance in, an amount or a
     * refusal out, no widget and no engine. {@code CoopCreditTransfer.send} still checks all of this
     * again - this only decides what the page says before it gets there.
     *
     * @param typed   the raw field text
     * @param credits the local balance, or negative when the wallet could not be read (no cover check)
     */
    static TypedAmount parseAmount(String typed, long credits) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) {
            return new TypedAmount(0, "Type an amount into the field, then press Send credits.");
        }
        if (text.startsWith("-")) {
            return new TypedAmount(0, "Credits can only be sent in positive amounts.");
        }
        if (text.indexOf('.') >= 0) {
            return new TypedAmount(0, "Credits are whole numbers; leave the decimal point out.");
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == ' ' || c == '\t') {
                continue;
            }
            if (c < '0' || c > '9') {
                return new TypedAmount(0, "\"" + text + "\" is not an amount of credits.");
            }
            digits.append(c);
        }
        if (digits.length() == 0) {
            return new TypedAmount(0, "Type an amount into the field, then press Send credits.");
        }
        long value;
        try {
            value = Long.parseLong(digits.toString());
        } catch (NumberFormatException ex) {
            // 19+ digits. Not a real amount, and the ceiling sentence is the useful answer.
            value = Long.MAX_VALUE;
        }
        if (value <= 0L) {
            return new TypedAmount(0, "Type an amount above zero.");
        }
        if (value > coop.campaign.CoopCreditTransfer.MAX_AMOUNT) {
            return new TypedAmount(0, "The most that can be sent at once is "
                    + coop.campaign.CoopCreditTransfer.format(
                            coop.campaign.CoopCreditTransfer.MAX_AMOUNT) + " credits.");
        }
        if (credits >= 0L && value > credits) {
            return new TypedAmount(0, "You have only "
                    + coop.campaign.CoopCreditTransfer.format(credits) + " credits.");
        }
        return new TypedAmount((int) value, "");
    }

    /**
     * One heading, the wallet line, one field to type the amount into and one Send button.
     *
     * <p>It used to be six fixed-step buttons plus Clear, which meant an odd amount took a dozen
     * presses and a bounty share could not be typed at all. Drawn even with no session so the feature
     * is discoverable before one starts - it is the Send button that is dead then, not the block.
     */
    private void addCreditsBlock(TooltipMakerAPI info, float width) {
        CreditRow row = liveCreditRow();
        Color highlight = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();
        Color warn = Misc.getNegativeHighlightColor();
        info.addSectionHeading(CREDITS_HEADING, Alignment.MID, 12f);
        info.addPara("Hands credits straight to your partner. The amount leaves your account when you"
                + " press Send credits; it arrives once, even across a reconnect; and if it cannot be"
                + " delivered at all, it comes back to you.", gray, 6f);
        if (!row.walletText().isEmpty()) {
            info.addPara(BULLET + "You have " + row.walletText() + " credits", gray, 6f);
        }
        info.addPara("Amount to send:", row.sendEnabled() ? highlight : gray, 6f);
        addAmountField(info, width);
        if (!row.note().isEmpty()) {
            info.addPara(BULLET + row.note(), gray, 2f);
        }
        if (!lastCreditRefusal.isEmpty()) {
            // The reason the last press did nothing. Without it the button looked broken: an amount
            // the wallet cannot cover, or a typo, produced no dialog and no change on the page.
            info.addPara(BULLET + lastCreditRefusal, warn, 2f);
        }
        ButtonAPI send = addButton(info, "Send credits", BUTTON_SEND_CREDITS, width);
        if (send != null && !row.sendEnabled()) {
            send.setEnabled(false);
        }
    }

    /**
     * The amount field, and the handle a press reads it through.
     *
     * <p>{@code addTextField} is the first text input the mod has put on an intel page; every other
     * editable value on this page is a button, because the registry's free-text keys are edited in
     * the settings file. An engine that refuses to build the widget leaves {@link #amountText}
     * answering "" , which reads as "nothing typed" and refuses the send politely.
     */
    private void addAmountField(TooltipMakerAPI info, float width) {
        amountText = () -> "";
        amountClear = () -> { };
        try {
            TextFieldAPI field = info.addTextField(Math.min(width, 200f), 4f);
            if (field == null) {
                return;
            }
            field.setMaxChars(AMOUNT_FIELD_MAX_CHARS);
            field.setUndoOnEscape(true);
            field.setHandleCtrlV(true);
            amountClear = field::deleteAll;
            amountText = () -> {
                try {
                    return field.getText();
                } catch (RuntimeException | LinkageError ex) {
                    logRenderFailureOnce(ex);
                    return "";
                }
            };
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    /** Why the last Send press did nothing, or "" when it worked. Rendered under the field. */
    String lastCreditRefusal() {
        return lastCreditRefusal;
    }

    /** What is in the field right now, run through {@link #parseAmount} against the live wallet. */
    TypedAmount typedAmount() {
        String typed;
        try {
            typed = amountText.get();
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            typed = "";
        }
        long credits;
        try {
            coop.campaign.CoopCreditTransfer transfer = coop.campaign.CoopCreditTransfer.active();
            credits = transfer == null ? -1L : transfer.credits();
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            credits = -1L;
        }
        return parseAmount(typed, credits);
    }

    private void addResetButton(TooltipMakerAPI info, float width) {
        addButton(info, "Reset to defaults", BUTTON_RESET, width);
    }

    /** @return the widget, or null when the engine refused to build it (already logged). */
    private ButtonAPI addButton(TooltipMakerAPI info, String text, Object id, float width) {
        try {
            return info.addButton(text, id, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    width, 20f, 8f);
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            return null;
        }
    }

    // ---- button handling -------------------------------------------------------------------------

    @Override
    public boolean doesButtonHaveConfirmDialog(Object buttonId) {
        try {
            if (buttonId == BUTTON_RESET) {
                return true;
            }
            if (buttonId == BUTTON_SEND_CREDITS) {
                // Money, and irreversible: there is no take-back message and no escrow to cancel, so
                // a good amount always gets the confirm step. A bad one skips it and goes straight to
                // buttonPressConfirmed, which is what puts the refusal on the page - there is nothing
                // to confirm about a typo, and a confirm dialog for one would be a worse way to say
                // no than a line under the field.
                return typedAmount().ok();
            }
            return buttonId instanceof Press press
                    && CoopOptionsView.CONFIRM_REQUIRED.contains(press.key());
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Wider than {@code BaseIntelPlugin}'s 550. These prompts are three paragraphs, not one line -
     * the pauseOnGuestScreens one runs to about sixty words - and at 550 they wrap into a column
     * tall enough to crowd the dialog.
     */
    @Override
    public float getConfirmationPromptWidth(Object buttonId) {
        return 650f;
    }

    @Override
    public void createConfirmationPrompt(Object buttonId, TooltipMakerAPI prompt) {
        try {
            String text;
            if (buttonId == BUTTON_RESET) {
                text = CoopOptionsView.resetPrompt(role() == CoopConnectionRole.GUEST);
            } else if (buttonId == BUTTON_SEND_CREDITS) {
                text = sendCreditsPrompt(typedAmount().amount());
            } else if (buttonId instanceof Press press) {
                text = CoopOptionsView.confirmPrompt(press.key(), currentValue(press.key()));
            } else {
                return;
            }
            float pad = 0f;
            for (String paragraph : text.split("\n")) {
                if (paragraph.isBlank()) {
                    continue;
                }
                prompt.addPara(paragraph, Misc.getTextColor(), pad);
                pad = 10f;
            }
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        try {
            if (buttonId == BUTTON_RESET) {
                resetToDefaults();
            } else if (buttonId == BUTTON_SEND_CREDITS) {
                sendTypedCredits();
            } else if (buttonId instanceof Press press) {
                apply(press);
            }
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
        try {
            if (ui != null) {
                ui.updateUIForItem(this);
            }
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    /** The value the button maths starts from: the policy's for a policy key, the stack's otherwise. */
    private static String currentValue(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        if (option.tier() == CoopOptionsRegistry.Tier.POLICY) {
            CoopOptionsPolicy policy = CoopOptionsPolicy.active();
            if (policy != null) {
                return policy.effective(key);
            }
        }
        return CoopOptionsStore.system().string(key);
    }

    /**
     * One press. A policy key goes through {@link CoopOptionsPolicy#set} - which writes the campaign's
     * persistent data and lets the pump broadcast the change - and everything else is written to the
     * user's own {@code saves/common/coop_options.json.data}.
     */
    private void apply(Press press) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.option(press.key());
        if (option == null) {
            return;
        }
        String current = currentValue(press.key());
        String next = press.direction() == 0
                ? "" : CoopOptionsView.nextValue(press.key(), current, press.direction());
        if (next == null) {
            return;
        }
        if (option.tier() == CoopOptionsRegistry.Tier.POLICY) {
            CoopOptionsPolicy policy = CoopOptionsPolicy.active();
            if (policy == null) {
                CoopLog.warn(CoopOptionsPage.class, "No coop policy is installed; " + press.key()
                        + " was not changed");
                return;
            }
            policy.set(press.key(), next);
            return;
        }
        CoopOptionsStore.system().writeOverride(press.key(), next);
    }

    /** The confirmation text for Send: what leaves, to whom, and that there is no undo. */
    static String sendCreditsPrompt(int typedAmount) {
        coop.campaign.CoopCreditTransfer transfer = coop.campaign.CoopCreditTransfer.active();
        String amount = coop.campaign.CoopCreditTransfer.format(Math.max(0, typedAmount));
        String partner = transfer == null ? "your co-op partner" : transfer.partnerLabelForUi();
        return "Send " + amount + " credits to " + partner + "?\n"
                + "The credits leave your account now and arrive on the other side once, even if the"
                + " link drops in between. If they cannot be delivered at all, they come back to"
                + " you.\n"
                + "Once they arrive there is no way to take them back.";
    }

    /**
     * One Send press. The transfer owns every rule (cover check, debit, wire, feed line); this reads
     * the field, refuses in writing rather than in silence, and empties the field on a success so a
     * second press cannot repeat a gift by accident.
     */
    private void sendTypedCredits() {
        TypedAmount typed = typedAmount();
        if (!typed.ok()) {
            lastCreditRefusal = typed.refusal();
            return;
        }
        coop.campaign.CoopCreditTransfer transfer = coop.campaign.CoopCreditTransfer.active();
        if (transfer == null) {
            lastCreditRefusal = "No co-op session; there is nobody to send credits to.";
            CoopLog.warn(CoopOptionsPage.class,
                    "Coop credits cannot be sent: no session is installed");
            return;
        }
        coop.campaign.CoopCreditTransfer.Result result = transfer.send(typed.amount());
        if (result == coop.campaign.CoopCreditTransfer.Result.SENT) {
            lastCreditRefusal = "";
            clearAmountField();
            return;
        }
        // send() has already put the exact reason in the campaign feed; this is the same news on the
        // page the player is looking at, so a refused press is never a button that just did nothing.
        lastCreditRefusal = switch (result) {
            case NO_SESSION -> "No co-op session; there is nobody to send credits to.";
            case INSUFFICIENT_FUNDS -> "You do not have that many credits.";
            case BAD_AMOUNT -> "That is not an amount of credits that can be sent.";
            default -> "The credits could not be sent; nothing left your account.";
        };
    }

    /**
     * Empties the field after a successful send. The next render rebuilds it empty anyway, but the
     * widget is still on screen while that render is queued, and a field still showing 25,000 next to
     * a "Sent 25,000 credits" feed line reads as a send that did not happen.
     */
    private void clearAmountField() {
        amountText = () -> "";
        try {
            amountClear.run();
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    /**
     * Everything back to the shipped values: the campaign's policy in one version bump, and every
     * local override dropped so the file layers below take over again.
     */
    private void resetToDefaults() {
        CoopOptionsPolicy policy = CoopOptionsPolicy.active();
        if (policy != null) {
            // Refuses on a guest by itself, which is the same rule the page's rows are drawn under.
            policy.resetToDefaults();
        }
        // Client-tier only: see CoopOptionsView#resetKeys. One write for the whole sweep, so an
        // unwritable settings file produces one WARN instead of one per key.
        Map<String, String> cleared = new LinkedHashMap<>();
        for (String key : CoopOptionsView.resetKeys()) {
            cleared.put(key, null);
        }
        CoopOptionsStore.system().writeOverrides(cleared);
        CoopLog.info(CoopOptionsPage.class, "Coop options reset to the shipped defaults");
    }

    // ---- failure handling ------------------------------------------------------------------------

    private static void addUnavailableLine(TooltipMakerAPI info) {
        try {
            info.addPara(UNAVAILABLE_LINE, Misc.getNegativeHighlightColor(), 10f);
        } catch (RuntimeException | LinkageError ignored) {
            // The tooltip itself is broken; there is nothing left to degrade to.
        }
    }

    static void logRenderFailureOnce(Throwable ex) {
        if (renderFailureLogged) {
            return;
        }
        renderFailureLogged = true;
        CoopLog.warn(CoopOptionsPage.class, "Coop options intel page failed", ex);
    }

    /** Whether the once-per-process render warning has already fired. Tests only. */
    static boolean renderFailureLogged() {
        return renderFailureLogged;
    }
}
