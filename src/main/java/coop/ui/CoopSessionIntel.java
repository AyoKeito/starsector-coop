package coop.ui;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 20.6 / Phase 21: the "Coop Session" intel entry - a page in the intel screen showing who is
 * connected, what the link is doing, how it has behaved over the last few minutes, and (on the host)
 * whether this machine is reachable at all.
 *
 * <p><b>Why an intel entry rather than another HUD widget.</b> {@link CoopLinkHud} is a live readout
 * you have to be looking at, and it has room for one line. The questions a player actually asks when
 * a session feels bad - "has it been like this the whole time", "does my partner see the same
 * numbers", "is my router mapping the port" - need a page, and the intel screen is the only
 * API-sanctioned page surface in the campaign ({@code IntelInfoPlugin.hasLargeDescription()} +
 * {@code createLargeDescription(CustomPanelAPI, w, h)}, verified against the 0.98a API sources).
 *
 * <p><b>Transient, not persisted (Phase 21).</b> This class used to be registered once and left in
 * the save forever, on the theory that {@link #isHidden()} would cover solo play. It doesn't cover
 * everything a save can do to it: XStream runs neither constructors nor field initialisers on load,
 * so a saved instance is whatever bytecode the build that wrote the save happened to have, and a
 * later build's {@code render} method can be handed an object that never ran its own constructor.
 * The actual fix is to never let an instance reach the save at all. {@link CoopModPlugin} removes the
 * entry in {@code beforeGameSave} and re-adds a brand-new one in {@code afterGameSave} and in
 * {@code onGameLoad}. A save written under this policy carries zero instances of this class, so there
 * is nothing to go stale. The class name itself cannot change - XStream still has to resolve
 * instances left by saves from before this phase landed - which is why {@link #ensureRegistered}
 * additionally sweeps and discards every existing instance before adding its one fresh one: an old
 * save's instance is not "the" entry to reuse, it's exactly the stale object this phase exists to
 * get rid of.
 *
 * <p><b>No instance fields, kept anyway.</b> Everything on the page is read live from
 * {@link CoopSessionIntelFeed}. That was originally the plan's defence against a saved instance
 * having nothing to migrate; it is no longer load-bearing since a saved instance should not exist at
 * all, but it costs nothing to keep and it is one less thing to reconsider if the remove/re-add hooks
 * are ever skipped by a bug.
 *
 * <p><b>Lifecycle flags stay false, for a different reason now.</b> {@link #isEnded()},
 * {@link #isEnding()}, and {@link #shouldRemoveIntel()} all still return values that tell the intel
 * manager's own sweep to leave the entry alone - but the entry's lifecycle is no longer "the manager
 * never removes it", it's "the mod removes and re-adds it explicitly, twice per save, and the manager
 * sweep is not one of the two places that happens." Letting the manager's own end/ending logic remove
 * the entry mid-session would race the mod's own remove/re-add calls for no benefit.
 *
 * <p><b>Rendering cannot crash the intel screen.</b> Every engine-facing method here is wrapped. A
 * throwable inside the page degrades to a single "unavailable" line and one log warning per session,
 * because an exception out of {@code createLargeDescription} takes the whole intel tab with it.
 *
 * <h2>Wiring the coordinator must add (this worker was not allowed to touch {@code CoopNetPump} or
 * {@code CoopModPlugin})</h2>
 * <pre>{@code
 * // --- CoopNetPump: field, next to the other UI-facing state ---
 * private final CoopSessionIntelFeed intelFeed = new CoopSessionIntelFeed();
 *
 * // --- CoopNetPump constructor (or installer), once the pump exists ---
 * CoopSessionIntelFeed.install(intelFeed);
 *
 * // --- CoopModPlugin.onGameLoad, after the pump is installed ---
 * CoopSessionIntel.ensureRegistered(Global.getSector());
 *
 * // --- CoopModPlugin.beforeGameSave, before the engine writes the file ---
 * CoopSessionIntel.remove(Global.getSector());
 *
 * // --- CoopModPlugin.afterGameSave, once the file is written ---
 * CoopSessionIntel.ensureRegistered(Global.getSector());
 *
 * // --- CoopNetPump, in the LINK_STATUS send path (once per interval, NOT per frame) ---
 * intelFeed.publishSession(role(), hudState(false).status(), partnerDisplayName());
 * intelFeed.publishLink(linkQuality.snapshot(nowMillis), transportToken);
 * // host only, whenever the port mapper produces a fresh result:
 * intelFeed.noteReachability(portMapper.result());
 *
 * // --- CoopNetPump, where an inbound LINK_STATUS is dispatched ---
 * intelFeed.notePeerLink(CoopMessages.parseLinkStatus(message));
 *
 * // --- CoopNetPump, at each link transition that already logs / posts to CoopFeed ---
 * intelFeed.noteEvent("UDP blocked - state stream moved to TCP");
 * intelFeed.noteEvent("UDP recovered - state stream back on UDP");
 * intelFeed.noteEvent("Link degraded - " + rtt + " ms, " + loss + "% loss");
 * intelFeed.noteEvent("Link recovered");
 * intelFeed.noteEvent("Peer disconnected - holding for reconnect");
 * intelFeed.noteEvent("Peer reconnected");
 * intelFeed.noteEvent("Reconnect grace expired");
 *
 * // --- CoopNetPump.dispose / session teardown ---
 * intelFeed.endSession();      // keeps the event log, drops every live reading
 * CoopSessionIntelFeed.uninstall();
 * }</pre>
 */
public class CoopSessionIntel extends BaseIntelPlugin {

    /** The entry's title, and its sort string. */
    public static final String NAME = "Coop Session";

    /**
     * The mod's own intel category. The intel screen builds its filter list out of the tags the
     * registered entries carry, so a tag nothing else uses becomes a "Coop" bucket of its own.
     *
     * <p><b>Note for the coordinator:</b> Phase 24's {@code CoopExpeditionWarningIntel} does not
     * carry this tag today (it tags {@code INTEL_MILITARY} + {@code INTEL_COLONIES} + the faction
     * id). Adding {@code CoopSessionIntel.TAG_COOP} to its {@code getIntelTags} is the one-line
     * change that makes both coop entries sit in the same bucket; it is not made here because that
     * file belongs to another worker in this phase.
     */
    public static final String TAG_COOP = "Coop";

    /** Rendered instead of the page when anything at all goes wrong building it. */
    static final String UNAVAILABLE_LINE = "Coop session details are unavailable.";

    /** Log-once guard for a broken render. Static: one warning per process, not one per open. */
    private static boolean renderFailureLogged;

    // ---- registration ----------------------------------------------------------------------------

    /**
     * Sweeps out every existing instance (see the class Javadoc: an existing instance is either
     * left over from a save written before Phase 21, or a leftover from a remove/re-add pair that
     * did not complete), adds exactly one fresh instance, and returns it.
     *
     * <p>Safe to call from both {@code onGameLoad} and {@code afterGameSave} without checking
     * anything first - it always ends with exactly one entry registered, never zero, never two.
     *
     * <p>Registered with {@code addIntel(plugin, true)} rather than {@code queueIntel}, because
     * queueing is comm-relay gated and a diagnostics page must be readable from anywhere - including
     * from deep space with a dead link, which is exactly when a player wants it.
     *
     * @return the newly registered entry, or null when there is no sector or the manager refused
     */
    public static CoopSessionIntel ensureRegistered(SectorAPI sector) {
        try {
            if (sector == null) {
                return null;
            }
            IntelManagerAPI manager = sector.getIntelManager();
            if (manager == null) {
                return null;
            }
            int swept = removeAll(manager);
            if (swept > 0) {
                CoopLog.info(CoopSessionIntel.class,
                        "Swept " + swept + " stale coop session intel entr" + (swept == 1 ? "y" : "ies")
                                + " (left over from an older save or an incomplete remove/re-add)");
            }
            CoopSessionIntel intel = new CoopSessionIntel();
            // forceNoMessage: registration happens on every load and after every save, and a
            // "new intel" toast every time would be noise for an entry the player already knows about.
            manager.addIntel(intel, true);
            // No line for the ordinary case, for the same reason there is no toast: this runs twice
            // per save. The sweep above logs when it actually found something, and that is the only
            // registration event a log reader has any use for.
            return intel;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionIntel.class, "Could not register the coop session intel entry", ex);
            return null;
        }
    }

    /**
     * Drops the entry from the intel manager. Called from {@code CoopModPlugin.beforeGameSave} so
     * no instance of this class reaches XStream; safe to call when there is nothing registered.
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
            CoopSessionIntel existing = findExisting(manager);
            if (existing == null) {
                return false;
            }
            manager.removeIntel(existing);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionIntel.class, "Could not remove the coop session intel entry", ex);
            return false;
        }
    }

    private static CoopSessionIntel findExisting(IntelManagerAPI manager) {
        List<IntelInfoPlugin> found = manager.getIntel(CoopSessionIntel.class);
        if (found == null) {
            return null;
        }
        for (IntelInfoPlugin plugin : found) {
            if (plugin instanceof CoopSessionIntel intel) {
                return intel;
            }
        }
        return null;
    }

    /**
     * Removes every registered instance of this class, not just the first. Iterates a defensive
     * copy of {@code manager.getIntel(...)} so removing entries mid-loop does not disturb the list
     * being walked.
     *
     * @return how many instances were removed
     */
    private static int removeAll(IntelManagerAPI manager) {
        List<IntelInfoPlugin> found = manager.getIntel(CoopSessionIntel.class);
        if (found == null || found.isEmpty()) {
            return 0;
        }
        List<IntelInfoPlugin> copy = new ArrayList<>(found);
        int removed = 0;
        for (IntelInfoPlugin plugin : copy) {
            if (plugin instanceof CoopSessionIntel) {
                manager.removeIntel(plugin);
                removed++;
            }
        }
        return removed;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /**
     * False, but not because the entry is permanent. The mod controls this entry's lifecycle
     * explicitly through {@link #remove} and {@link #ensureRegistered}; letting the intel manager's
     * own sweep remove it on top of that would race those calls for no benefit.
     */
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
     * Hidden unless a coop role is live. This is the second line of defence, not the first: after
     * Phase 21 the entry should not be in a solo save at all, because it is removed before every save
     * and only re-added on load. {@link #isHidden} exists for the case where an instance is present
     * anyway - a save from a build before this phase, or a save taken while the remove hook failed to
     * run - so a dead diagnostics page still does not show up as clutter in solo play.
     */
    @Override
    public boolean isHidden() {
        try {
            return !CoopSessionIntelFeed.roleActive();
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
    }

    /** Not a notification. The message feed already carries link transitions (see {@link CoopFeed}). */
    @Override
    public boolean autoAddCampaignMessage() {
        return false;
    }

    @Override
    public String getCommMessageSound() {
        return null;
    }

    /** No star: this is a permanent utility page, not something the player pins. */
    @Override
    public boolean hasImportantButton() {
        return false;
    }

    @Override
    public boolean isImportant() {
        return false;
    }

    /**
     * Never "new". A permanent diagnostics page is not news, and vanilla's {@code isNew()} would
     * report it as such for five days after every registration - including the one that runs on
     * every game load. Overriding it also keeps {@link #getIntelTags} free of the campaign clock
     * {@code BaseIntelPlugin.isNew()} reads, which is what makes the tag set unit-testable.
     */
    @Override
    public boolean isNew() {
        return false;
    }

    /** First in its bucket - it is the only entry a player opens this tab deliberately to read. */
    @Override
    public IntelSortTier getSortTier() {
        return IntelSortTier.TIER_0;
    }

    @Override
    public String getSortString() {
        return NAME;
    }

    /** Widened from {@code protected} for the same reason Phase 24's entry widened it: it is data. */
    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(TAG_COOP);
        return tags;
    }

    @Override
    public String getIcon() {
        return null;
    }

    // ---- list row --------------------------------------------------------------------------------

    /** One line under the title: what the session is doing, and the round trip. */
    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        try {
            info.addPara(NAME, getTitleColor(mode), 0f);
            info.addPara(CoopSessionIntelModel.listLine(CoopSessionIntelFeed.currentModel()),
                    getBulletColorForMode(mode), 0f);
        } catch (RuntimeException | LinkageError ex) {
            logRenderFailureOnce(ex);
        }
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
            render(info, CoopSessionIntelFeed.currentModel(), width);
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
     * The page itself. Text and rows only - no OpenGL, no custom panel plugin - because the intel
     * page is re-rendered on open and on nothing else, and a drawing surface here would have no way
     * to refresh itself.
     */
    void render(TooltipMakerAPI info, CoopSessionIntelModel model, float width) {
        CoopSessionIntelModel value = model == null ? CoopSessionIntelModel.empty() : model;
        Color highlight = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();

        info.addPara("Role: %s", 10f, highlight,
                CoopSessionIntelModel.roleText(value.localRole()));
        info.addPara("Session: %s", 3f, highlight,
                value.sessionState().isEmpty() ? "no session" : value.sessionState());
        info.addPara("Partner: %s", 3f, highlight,
                value.partnerName().isEmpty() ? CoopSessionIntelModel.UNKNOWN : value.partnerName());

        renderLink(info, value, highlight, gray);
        renderPeer(info, value, gray);
        renderReachability(info, value, highlight);
        renderEvents(info, value, gray);
        renderHistory(info, value, highlight, gray);
    }

    private void renderLink(TooltipMakerAPI info, CoopSessionIntelModel model, Color highlight,
                            Color gray) {
        info.addSectionHeading("Link", Alignment.MID, 12f);
        CoopSessionIntelModel.LinkSample link = model.localLink();
        if (link == null) {
            info.addPara("No link measurements yet.", gray, 6f);
            return;
        }
        info.addPara("Round trip: %s     95th percentile: %s", 6f, linkColor(link),
                CoopSessionIntelModel.formatRtt(link.rttMillis()),
                CoopSessionIntelModel.formatRtt(link.p95RttMillis()));
        info.addPara("Datagram loss: %s", 3f, linkColor(link),
                CoopSessionIntelModel.formatLoss(link.lossPercent()));
        info.addPara("State stream: %s     Path: %s", 3f, transportColor(link),
                CoopSessionIntelModel.describeStateStream(link.transport(), link.cadenceHz()),
                CoopSessionIntelModel.describeUdpPath(link.udpInboundOk()));
        info.addPara("TCP quiet for %s", 3f, highlight,
                CoopSessionIntelModel.formatDuration(link.tcpSilenceMillis()));
    }

    private void renderPeer(TooltipMakerAPI info, CoopSessionIntelModel model, Color gray) {
        info.addSectionHeading("Peer sees", Alignment.MID, 12f);
        CoopSessionIntelModel.LinkSample peer = model.peerLink();
        if (peer == null) {
            info.addPara("Your partner has not reported a link status yet.", gray, 6f);
            return;
        }
        Long age = model.peerLinkAgeMillis();
        info.addPara("Reported %s", 6f, gray,
                age == null ? CoopSessionIntelModel.UNKNOWN : CoopSessionIntelModel.formatAge(age));
        info.addPara("Round trip: %s     Datagram loss: %s", 3f, linkColor(peer),
                CoopSessionIntelModel.formatRtt(peer.rttMillis()),
                CoopSessionIntelModel.formatLoss(peer.lossPercent()));
        info.addPara("State stream: %s     Path: %s", 3f, transportColor(peer),
                CoopSessionIntelModel.describeStateStream(peer.transport(), peer.cadenceHz()),
                CoopSessionIntelModel.describeUdpPath(peer.udpInboundOk()));
    }

    private void renderReachability(TooltipMakerAPI info, CoopSessionIntelModel model,
                                    Color highlight) {
        CoopSessionIntelModel.Reachability reach = model.reachability();
        if (reach == null) {
            return;
        }
        info.addSectionHeading("Reachability", Alignment.MID, 12f);
        info.addPara("Port mapping: %s", 6f, highlight, reach.tierText());
        info.addPara("External endpoint: %s", 3f, highlight, reach.externalEndpoint());
        info.addPara("Carrier-grade NAT: %s", 3f, highlight, reach.cgnatVerdict());
    }

    private void renderEvents(TooltipMakerAPI info, CoopSessionIntelModel model, Color gray) {
        info.addSectionHeading("Recent events", Alignment.MID, 12f);
        List<CoopSessionIntelModel.Event> events = model.events();
        if (events.isEmpty()) {
            info.addPara("Nothing has gone wrong yet.", gray, 6f);
            return;
        }
        float pad = 6f;
        for (CoopSessionIntelModel.Event event : events) {
            info.addPara(BULLET + event.line() + "  (" + event.ageText() + ")", pad);
            pad = 2f;
        }
    }

    private void renderHistory(TooltipMakerAPI info, CoopSessionIntelModel model, Color highlight,
                               Color gray) {
        info.addSectionHeading("History", Alignment.MID, 12f);
        CoopSessionIntelModel.HistoryStats stats = model.stats();
        if (stats.samples() == 0) {
            info.addPara("No samples yet. One is taken every few seconds while a session is live.",
                    gray, 6f);
            return;
        }
        info.addPara("Last %s samples, oldest first, about five seconds apart.", 6f, highlight,
                String.valueOf(stats.samples()));
        info.addPara("RTT", gray, 6f);
        info.addPara(CoopSessionIntelModel.sparkline(model.rttHistory()), highlight, 2f);
        info.addPara("min %s / median %s / max %s", 2f, highlight,
                CoopSessionIntelModel.formatRtt(stats.minRttMillis()),
                CoopSessionIntelModel.formatRtt(stats.medianRttMillis()),
                CoopSessionIntelModel.formatRtt(stats.maxRttMillis()));
        info.addPara("Loss", gray, 6f);
        info.addPara(CoopSessionIntelModel.sparkline(model.lossHistory()), highlight, 2f);
        info.addPara("min %s / median %s / max %s", 2f, highlight,
                CoopSessionIntelModel.formatLoss(stats.minLossPercent()),
                CoopSessionIntelModel.formatLoss(stats.medianLossPercent()),
                CoopSessionIntelModel.formatLoss(stats.maxLossPercent()));
    }

    // ---- colours ---------------------------------------------------------------------------------

    /** Green while the numbers are fine, yellow once they are in the doctor's degraded range. */
    private static Color linkColor(CoopSessionIntelModel.LinkSample sample) {
        return CoopSessionIntelModel.degraded(sample)
                ? Misc.getHighlightColor() : Misc.getPositiveHighlightColor();
    }

    /** Yellow on the TCP fallback: the session still works, it just works worse. */
    private static Color transportColor(CoopSessionIntelModel.LinkSample sample) {
        return sample != null && sample.onFallback()
                ? Misc.getHighlightColor() : Misc.getPositiveHighlightColor();
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
        CoopLog.warn(CoopSessionIntel.class, "Coop session intel page failed to render", ex);
    }
}
