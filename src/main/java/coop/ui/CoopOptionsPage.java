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

/**
 * Phase 28 milestone 3: the "Coop Options" intel entry - the in-campaign editor for everything in
 * {@link CoopOptionsRegistry}.
 *
 * <p>Third coop page, on the same transient lifecycle as the other two ({@link CoopSessionIntel},
 * {@link CoopSessionStatsIntel}): removed in {@code beforeGameSave}, recreated in
 * {@code afterGameSave} and {@code onGameLoad}, so no instance of this class ever reaches XStream.
 * It holds no state at all - every value is read live from {@link CoopOptionsPolicy} and
 * {@link CoopOptionsStore} on render, and the one piece of state the page's own widgets own (the
 * pending credit-transfer amount) lives in a static on {@link coop.campaign.CoopCreditTransfer},
 * where the save cycle cannot reach it either.
 *
 * <p><b>The intel surface is buttons, not a settings menu.</b> {@code TooltipMakerAPI.addButton}
 * with a {@code buttonPressConfirmed} callback is the whole vocabulary, so booleans get a toggle,
 * enums get a cycle, bounded integers get a {@code -}/{@code +} pair, and free text cannot be edited
 * here at all (see {@link CoopOptionsView}). The title screen has no mod API, so this page plus the
 * settings file is the complete surface - there is no pre-campaign UI to add one to.
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

    /** Button id for "hand the pending amount to the partner" (Phase 32 addition B). */
    public static final Object BUTTON_SEND_CREDITS = new Object();

    /** Button id for "back to nothing pending". */
    public static final Object BUTTON_CLEAR_CREDITS = new Object();

    /** Heading of the credit-transfer block. */
    static final String CREDITS_HEADING = "Send credits";

    /** Rendered instead of the page when anything at all goes wrong building it. */
    static final String UNAVAILABLE_LINE = CoopOptionsView.UNAVAILABLE_LINE;

    /** Log-once guard for a broken render. Static: one warning per process, not one per open. */
    private static boolean renderFailureLogged;

    /** One button press: which key, and which way. */
    record Press(String key, int direction) {
    }

    /** One press of a credit step button: how many credits to add to the pending amount. */
    record CreditStep(int delta) {
    }

    /**
     * Everything the "Send credits" block renders, decided without touching the engine so it can be
     * unit-tested. See {@link #creditRow}.
     *
     * @param amountText  the pending amount, always shown, so the player can read what Send will do
     * @param walletText  what the local player has, or "" when there is no wallet to read
     * @param note        the one-line reason Send is disabled, or "" when it is not
     * @param sendEnabled whether the Send button is live
     * @param canStep     whether the amount buttons are worth drawing at all
     * @param canClear    whether "Clear amount" is worth drawing; true whenever something is pending,
     *                    independent of {@code canStep}, so an amount stepped up before the link died
     *                    can still be put away (credit red-team P2-5)
     */
    record CreditRow(String amountText, String walletText, String note, boolean sendEnabled,
                     boolean canStep, boolean canClear) {
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
     * <p>Send is live only when a session is up and the peer is connected — the button must not
     * promise something {@link coop.campaign.CoopCreditTransfer#send} would refuse — and, on top of
     * that, only when the pending amount is something the local wallet can actually cover.
     *
     * <p>An unreadable wallet ({@code credits < 0}) disables Send and says so (credit red-team P2-4).
     * It used to leave the button live on the reasoning that the real cover check is in {@code send};
     * that is true, but {@code send} answers it with "not enough credits — 25,000 needed, 0
     * available" on a page that shows no balance at all, so the player got an enabled button that
     * always failed with a message contradicting what was in front of them.
     *
     * @param canSend  the transfer's own answer for "is there a session with a connected peer"
     * @param pending  the amount the step buttons have accumulated
     * @param credits  the local player's credits, negative when unreadable
     */
    static CreditRow creditRow(boolean canSend, int pending, long credits) {
        String amountText = coop.campaign.CoopCreditTransfer.format(Math.max(0, pending));
        String walletText = credits < 0 ? ""
                : coop.campaign.CoopCreditTransfer.format(credits);
        boolean canClear = pending > 0;
        if (!canSend) {
            return new CreditRow(amountText, walletText,
                    "No co-op session; there is nobody to send credits to.", false, false, canClear);
        }
        if (credits < 0) {
            return new CreditRow(amountText, walletText,
                    "Your wallet could not be read; credits cannot be sent right now.",
                    false, true, canClear);
        }
        if (pending <= 0) {
            return new CreditRow(amountText, walletText,
                    "Step the amount up, then press Send.", false, true, canClear);
        }
        if (credits < pending) {
            return new CreditRow(amountText, walletText,
                    "You do not have that many credits.", false, true, canClear);
        }
        return new CreditRow(amountText, walletText, "", true, true, canClear);
    }

    /** The live model: the installed transfer's session state, wallet and pending amount. */
    static CreditRow liveCreditRow() {
        try {
            coop.campaign.CoopCreditTransfer transfer = coop.campaign.CoopCreditTransfer.active();
            return creditRow(transfer != null && transfer.canSend(),
                    coop.campaign.CoopCreditTransfer.pendingAmount(),
                    transfer == null ? -1L : transfer.credits());
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            return creditRow(false, 0, -1L);
        }
    }

    /**
     * One heading, one line naming the pending amount, one line naming the wallet, the step buttons
     * and Send. Drawn even with no session so the feature is discoverable before one starts - it is
     * the Send button that is dead then, not the block.
     */
    private void addCreditsBlock(TooltipMakerAPI info, float width) {
        CreditRow row = liveCreditRow();
        Color highlight = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();
        info.addSectionHeading(CREDITS_HEADING, Alignment.MID, 12f);
        info.addPara("Hands credits straight to your partner. The amount leaves your account when you"
                + " press Send; it arrives once, even across a reconnect; and if it cannot be"
                + " delivered at all, it comes back to you.", gray, 6f);
        info.addPara("Amount to send: " + row.amountText() + " credits",
                row.sendEnabled() ? highlight : gray, 6f);
        if (!row.walletText().isEmpty()) {
            info.addPara(BULLET + "You have " + row.walletText() + " credits", gray, 2f);
        }
        if (!row.note().isEmpty()) {
            info.addPara(BULLET + row.note(), gray, 2f);
        }
        if (row.canStep()) {
            for (int step : coop.campaign.CoopCreditTransfer.STEPS) {
                addButton(info, "+ " + coop.campaign.CoopCreditTransfer.format(step),
                        new CreditStep(step), width);
            }
            for (int step : coop.campaign.CoopCreditTransfer.STEPS) {
                addButton(info, "- " + coop.campaign.CoopCreditTransfer.format(step),
                        new CreditStep(-step), width);
            }
        }
        if (row.canClear()) {
            // Outside the canStep block on purpose: an amount stepped up before the link dropped has
            // to be clearable without waiting for the session to come back.
            addButton(info, "Clear amount", BUTTON_CLEAR_CREDITS, width);
        }
        ButtonAPI send = addButton(info, "Send " + row.amountText() + " credits",
                BUTTON_SEND_CREDITS, width);
        if (send != null && !row.sendEnabled()) {
            send.setEnabled(false);
        }
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
            if (buttonId == BUTTON_RESET || buttonId == BUTTON_SEND_CREDITS) {
                // Money, and irreversible: there is no take-back message and no escrow to cancel.
                return true;
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
                text = sendCreditsPrompt();
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
                sendPendingCredits();
            } else if (buttonId == BUTTON_CLEAR_CREDITS) {
                coop.campaign.CoopCreditTransfer.clearPendingAmount();
            } else if (buttonId instanceof CreditStep step) {
                coop.campaign.CoopCreditTransfer.stepPendingAmount(step.delta());
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
    static String sendCreditsPrompt() {
        coop.campaign.CoopCreditTransfer transfer = coop.campaign.CoopCreditTransfer.active();
        String amount = coop.campaign.CoopCreditTransfer.format(
                coop.campaign.CoopCreditTransfer.pendingAmount());
        String partner = transfer == null ? "your co-op partner" : transfer.partnerLabelForUi();
        return "Send " + amount + " credits to " + partner + "?\n"
                + "The credits leave your account now and arrive on the other side once, even if the"
                + " link drops in between. If they cannot be delivered at all, they come back to"
                + " you.\n"
                + "Once they arrive there is no way to take them back.";
    }

    /**
     * One Send press. The transfer owns every rule (cover check, debit, wire, feed line); this only
     * clears the pending amount on a success, so a second press cannot repeat a gift by accident.
     */
    private void sendPendingCredits() {
        coop.campaign.CoopCreditTransfer transfer = coop.campaign.CoopCreditTransfer.active();
        if (transfer == null) {
            CoopLog.warn(CoopOptionsPage.class,
                    "Coop credits cannot be sent: no session is installed");
            return;
        }
        if (transfer.send(coop.campaign.CoopCreditTransfer.pendingAmount())
                == coop.campaign.CoopCreditTransfer.Result.SENT) {
            coop.campaign.CoopCreditTransfer.clearPendingAmount();
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
