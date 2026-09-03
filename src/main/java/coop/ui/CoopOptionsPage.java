package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.Alignment;
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
 * {@link CoopOptionsStore} on render.
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

    /** Rendered instead of the page when anything at all goes wrong building it. */
    static final String UNAVAILABLE_LINE = CoopOptionsView.UNAVAILABLE_LINE;

    /** Log-once guard for a broken render. Static: one warning per process, not one per open. */
    private static boolean renderFailureLogged;

    /** One button press: which key, and which way. */
    record Press(String key, int direction) {
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

    private void addResetButton(TooltipMakerAPI info, float width) {
        addButton(info, "Reset to defaults", BUTTON_RESET, width);
    }

    private void addButton(TooltipMakerAPI info, String text, Object id, float width) {
        try {
            info.addButton(text, id, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    width, 20f, 8f);
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    // ---- button handling -------------------------------------------------------------------------

    @Override
    public boolean doesButtonHaveConfirmDialog(Object buttonId) {
        try {
            if (buttonId == BUTTON_RESET) {
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
