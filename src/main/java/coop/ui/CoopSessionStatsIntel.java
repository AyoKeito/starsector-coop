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
import coop.stats.CoopSessionStats;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Phase 21: the "Coop Stats" intel entry — the session's counters, records and ship-loss ledger.
 *
 * <h2>Why a second entry rather than a longer "Coop Session" page</h2>
 *
 * The 20.6 {@link CoopSessionIntel} page is already a full screen of link diagnostics, and the plan
 * pins the overflow rule: <em>one screen; overflow goes to a second intel entry, not a scrollbar.</em>
 * The two pages also answer different questions at different times — one is "is the link healthy
 * right now", the other is "what have we done together" — and a player looking for the second does
 * not want to scroll past the first.
 *
 * <h2>Transient, unlike its sibling</h2>
 *
 * {@link CoopSessionIntel} survives in the save because it holds no fields at all. This entry does
 * not go into the save either, but for the opposite reason: it renders numbers that live in
 * {@code sector.getPersistentData()} under {@link CoopSessionStats#PERSISTENT_KEY}, and an intel
 * object caching a stale copy of those would be a second source of truth. So the plugin is removed
 * in {@code beforeGameSave} and recreated in {@code afterGameSave}/{@code onGameLoad}
 * ({@link #remove(SectorAPI)} / {@link #ensureRegistered(SectorAPI)}), and the one piece of UI state
 * worth keeping — whether the player pinned it — lives in sector memory under
 * {@link #PIN_MEMORY_KEY} rather than on the object. A solo load of a coop save therefore shows no
 * coop entry at all, structurally, with nothing to migrate.
 *
 * <h2>Where the numbers come from</h2>
 *
 * A static {@link #setSource(Supplier, Supplier)} handle, installed by the wiring wave when the pump
 * is created. Same reasoning as {@code CoopSessionIntelFeed}: the intel screen constructs and holds
 * this object, so a constructor argument would never reach it. With no source installed the entry is
 * hidden and the page reads "No session statistics yet."
 *
 * <h2>Wiring the coordinator must add (this worker was not allowed to touch the pump or the plugin)</h2>
 * <pre>{@code
 * // --- CoopModPlugin.onGameLoad, after the pump is installed ---
 * CoopSessionStatsIntel.setSource(statsTally::current, statsTally::awayPlayerIds);
 * CoopSessionStatsIntel.ensureRegistered(Global.getSector());
 *
 * // --- CoopModPlugin.beforeGameSave, before the sector is walked ---
 * statsTally.current().writeInto(Global.getSector().getPersistentData());
 * CoopSessionStatsIntel.remove(Global.getSector());
 *
 * // --- CoopModPlugin.afterGameSave ---
 * CoopSessionStatsIntel.ensureRegistered(Global.getSector());
 *
 * // --- CoopNetPump / session teardown ---
 * CoopSessionStatsIntel.clearSource();
 * }</pre>
 */
public class CoopSessionStatsIntel extends BaseIntelPlugin {

    /** The entry's title, and its sort string. Sorts after "Coop Session" in the same bucket. */
    public static final String NAME = "Coop Stats";

    /** Sector-memory key holding the pin state across the remove/recreate cycle. */
    public static final String PIN_MEMORY_KEY = "$coopStatsPinned";

    /** Button id for the manual refresh. Identity-compared, like vanilla's {@code BUTTON_DELETE}. */
    public static final Object BUTTON_REFRESH = new Object();

    /** Rendered instead of the page when anything at all goes wrong building it. */
    static final String UNAVAILABLE_LINE = "Coop session statistics are unavailable.";

    /** Log-once guard for a broken render. Static: one warning per process, not one per open. */
    private static boolean renderFailureLogged;

    private static volatile Supplier<CoopSessionStats> statsSource;
    private static volatile Supplier<Set<String>> awaySource;

    // ---- data source -----------------------------------------------------------------------------

    /** Installs the stats supplier with no away-set; every column renders as present. */
    public static void setSource(Supplier<CoopSessionStats> source) {
        setSource(source, null);
    }

    /**
     * Installs the stats supplier and the set of player ids that are currently disconnected. Both are
     * polled on render, never cached: the page re-renders on open and on the Refresh button, so a
     * cached copy could only ever be older than what a fresh call returns.
     */
    public static void setSource(Supplier<CoopSessionStats> source, Supplier<Set<String>> away) {
        statsSource = source;
        awaySource = away;
    }

    /** Session teardown: the entry hides itself again. */
    public static void clearSource() {
        statsSource = null;
        awaySource = null;
    }

    /** The stats to render, or null when there is no source or it has nothing yet. */
    static CoopSessionStats currentStats() {
        Supplier<CoopSessionStats> source = statsSource;
        if (source == null) {
            return null;
        }
        try {
            return source.get();
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            return null;
        }
    }

    /** Player ids currently disconnected; never null. */
    static Set<String> currentAwayPlayerIds() {
        Supplier<Set<String>> source = awaySource;
        if (source == null) {
            return Set.of();
        }
        try {
            Set<String> away = source.get();
            return away == null ? Set.of() : away;
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
            return Set.of();
        }
    }

    // ---- registration ----------------------------------------------------------------------------

    /**
     * Adds the entry if this campaign does not already have one, and returns it either way.
     * Idempotent, so the wiring wave can call it from {@code onGameLoad} and {@code afterGameSave}
     * without checking.
     *
     * <p>{@code addIntel(plugin, true)} rather than {@code queueIntel}: queueing is comm-relay gated
     * and this page must be readable from anywhere.
     */
    public static CoopSessionStatsIntel ensureRegistered(SectorAPI sector) {
        try {
            if (sector == null) {
                return null;
            }
            IntelManagerAPI manager = sector.getIntelManager();
            if (manager == null) {
                return null;
            }
            CoopSessionStatsIntel existing = findExisting(manager);
            if (existing != null) {
                return existing;
            }
            CoopSessionStatsIntel intel = new CoopSessionStatsIntel();
            manager.addIntel(intel, true);
            CoopLog.info(CoopSessionStatsIntel.class, "Coop stats intel entry registered");
            return intel;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionStatsIntel.class, "Could not register the coop stats intel entry", ex);
            return null;
        }
    }

    /**
     * Drops the entry from the intel manager. Called before a save so nothing of this class reaches
     * XStream; safe to call when there is nothing registered.
     *
     * @return true when an entry was actually removed
     */
    public static boolean remove(SectorAPI sector) {
        try {
            if (sector == null) {
                return false;
            }
            IntelManagerAPI manager = sector.getIntelManager();
            if (manager == null) {
                return false;
            }
            CoopSessionStatsIntel existing = findExisting(manager);
            if (existing == null) {
                return false;
            }
            manager.removeIntel(existing);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionStatsIntel.class, "Could not remove the coop stats intel entry", ex);
            return false;
        }
    }

    private static CoopSessionStatsIntel findExisting(IntelManagerAPI manager) {
        List<IntelInfoPlugin> found = manager.getIntel(CoopSessionStatsIntel.class);
        if (found == null) {
            return null;
        }
        for (IntelInfoPlugin plugin : found) {
            if (plugin instanceof CoopSessionStatsIntel intel) {
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

    /** Never swept, and never deleted: there is no delete button, per the plan's non-deletable rule. */
    @Override
    public boolean shouldRemoveIntel() {
        return false;
    }

    /** Hidden until a stats source is installed — which is to say, until a coop pump exists. */
    @Override
    public boolean isHidden() {
        try {
            return statsSource == null;
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

    /**
     * No star. {@code isImportant} is a pin/sort control, not a delete guard, and a page that is
     * removed and recreated around every save would lose a pin set on the object. The state is still
     * read from and written to sector memory ({@link #PIN_MEMORY_KEY}) so that a later polish pass
     * can turn the button on without inventing the persistence for it.
     */
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
            CoopLog.warn(CoopSessionStatsIntel.class, "Could not store the coop stats pin state", ex);
        }
    }

    /** Never "new": it is registered again on every load, and news it is not. */
    @Override
    public boolean isNew() {
        return false;
    }

    /** Below "Coop Session" in the same bucket — diagnostics first, scrapbook second. */
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

    /** Shares {@link CoopSessionIntel#TAG_COOP} so both coop entries filter into one bucket. */
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

    /** The one line under the title in the intel list. */
    static String listLine() {
        CoopSessionStats stats = currentStats();
        if (stats == null || stats.isEmpty()) {
            return CoopSessionStatsView.NO_DATA_LINE;
        }
        return "Day " + CoopSessionStatsView.formatDays(stats.daysElapsed())
                + ", flown together " + CoopSessionStatsView.formatDuration(
                        stats.timeFlownTogetherSeconds());
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
            render(info, CoopSessionStatsView.of(currentStats(), currentAwayPlayerIds()), width);
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

    /**
     * Maps the view's strings onto widgets and does nothing else. Every decision about content lives
     * in {@link CoopSessionStatsView}; if a number is wrong here, it is wrong there.
     */
    void render(TooltipMakerAPI info, CoopSessionStatsView view, float width) {
        Color highlight = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();
        Color text = Misc.getTextColor();

        if (!view.hasData()) {
            info.addPara(CoopSessionStatsView.NO_DATA_LINE, gray, 10f);
            addRefreshButton(info, width);
            return;
        }

        info.addSectionHeading("This session", Alignment.MID, 12f);
        float pad = 6f;
        for (String line : view.headline()) {
            info.addPara(line, highlight, pad);
            pad = 3f;
        }

        renderCards(info, view, gray, highlight);
        renderTable(info, view, width, text);
        renderLedger(info, view, gray);
        renderFooter(info, view, gray);
        addRefreshButton(info, width);
    }

    private void renderCards(TooltipMakerAPI info, CoopSessionStatsView view, Color gray,
                            Color highlight) {
        info.addSectionHeading("Records", Alignment.MID, 12f);
        List<CoopSessionStatsView.Card> cards = view.cards();
        if (cards.isEmpty()) {
            info.addPara("Nothing has run long enough to be a record yet.", gray, 6f);
            return;
        }
        float pad = 6f;
        for (CoopSessionStatsView.Card card : cards) {
            // Title and criterion on one line, holder and number on the next: the criterion is
            // printed on every card on purpose, so "Explorer" never has to be guessed at.
            info.addPara(card.title() + " - " + card.criterion(), gray, pad);
            info.addPara(card.holders() + ", " + card.value(), highlight, 2f);
            pad = 6f;
        }
    }

    private void renderTable(TooltipMakerAPI info, CoopSessionStatsView view, float width,
                             Color text) {
        List<String> headers = view.columnHeaders();
        float labelWidth = Math.max(120f, width * 0.30f);
        float columnWidth = headers.isEmpty() ? width
                : Math.max(70f, (width - labelWidth - 10f) / headers.size());

        for (CoopSessionStatsView.Section section : view.sections()) {
            info.addSectionHeading(section.title(), Alignment.MID, 12f);
            Object[] columns = new Object[(headers.size() + 1) * 2];
            columns[0] = "";
            columns[1] = labelWidth;
            for (int i = 0; i < headers.size(); i++) {
                columns[(i + 1) * 2] = headers.get(i);
                columns[(i + 1) * 2 + 1] = columnWidth;
            }
            info.beginTable(Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Misc.getBrightPlayerColor(), 20f, columns);
            for (CoopSessionStatsView.Row row : section.rows()) {
                // One colour for every cell in the row. No leader highlight, by decision.
                Object[] data = new Object[(row.cells().size() + 1) * 2];
                data[0] = text;
                data[1] = row.label();
                for (int i = 0; i < row.cells().size(); i++) {
                    data[(i + 1) * 2] = text;
                    data[(i + 1) * 2 + 1] = row.cells().get(i);
                }
                info.addRow(data);
            }
            info.addTable("", 0, 6f);
        }
    }

    private void renderLedger(TooltipMakerAPI info, CoopSessionStatsView view, Color gray) {
        info.addSectionHeading("Hulls lost", Alignment.MID, 12f);
        float pad = 6f;
        for (String line : view.ledger()) {
            info.addPara(BULLET + line, gray, pad);
            pad = 2f;
        }
    }

    private void renderFooter(TooltipMakerAPI info, CoopSessionStatsView view, Color gray) {
        info.addSectionHeading("How these are counted", Alignment.MID, 12f);
        float pad = 6f;
        for (String line : view.footer()) {
            info.addPara(line, gray, pad);
            pad = 2f;
        }
    }

    // ---- refresh ---------------------------------------------------------------------------------

    /**
     * The page re-renders on open and on this button, never on a timer: a full re-render resets the
     * large description's scroll position and there is no API to save and restore it.
     */
    private void addRefreshButton(TooltipMakerAPI info, float width) {
        try {
            // No keyboard shortcut: the intel screen already binds most letters, and a page whose
            // only action is "draw the same thing again" is not worth a collision.
            ButtonAPI button = info.addButton("Refresh", BUTTON_REFRESH,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Math.max(80f, Math.min(width, 200f)), 20f, 20f);
            if (button == null) {
                logRenderFailureOnce(new IllegalStateException("addButton returned null"));
            }
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    /** No confirmation on a refresh; the only button here is idempotent and free. */
    @Override
    public boolean doesButtonHaveConfirmDialog(Object buttonId) {
        return false;
    }

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        try {
            if (ui != null) {
                ui.updateUIForItem(this);
            }
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
    }

    // ---- failure handling ------------------------------------------------------------------------

    private static void addUnavailableLine(TooltipMakerAPI info) {
        try {
            info.addPara(UNAVAILABLE_LINE, Misc.getNegativeHighlightColor(), 10f);
        } catch (RuntimeException | LinkageError ignored) {
            // The tooltip itself is broken; there is nothing left to degrade to.
        }
    }

    private static void logRenderFailureOnce(Throwable ex) {
        if (renderFailureLogged) {
            return;
        }
        renderFailureLogged = true;
        CoopLog.warn(CoopSessionStatsIntel.class, "Coop stats intel page failed to render", ex);
    }
}
