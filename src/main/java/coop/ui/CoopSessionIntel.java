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
import java.util.List;
import java.util.Set;

/**
 * Phase 20.6: the "Coop Session" intel entry - a permanent page in the intel screen showing who is
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
 * <p><b>No persisted state, on purpose.</b> This object lands in the save through XStream like every
 * other intel plugin, and XStream runs neither constructors nor field initialisers on load. The
 * safest possible save shape is therefore no instance fields at all: everything on the page is read
 * live from {@link CoopSessionIntelFeed}, and an entry restored from a save written by a different
 * build has nothing to migrate. That is the same lesson Phase 24's {@code importantApplied} flag
 * came out of, taken one step further - there is no flag to migrate because there is no flag.
 *
 * <p><b>Permanent, and that is the whole lifecycle.</b> {@link #isEnded()} and {@link #isEnding()}
 * are false forever and {@link #shouldRemoveIntel()} refuses removal, so the intel manager's sweep
 * never takes it. What changes instead is {@link #isHidden()}: with no coop role active the entry
 * vanishes from the screen, which is how a save loaded solo shows no coop clutter without the entry
 * having to be deleted and recreated.
 *
 * <p><b>Rendering cannot crash the intel screen.</b> Every engine-facing method here is wrapped. A
 * throwable inside the page degrades to a single "unavailable" line and one log warning per session,
 * because an exception out of {@code createLargeDescription} takes the whole intel tab with it.
 *
 * <h2>Wiring the coordinator must add (this worker was not allowed to touch {@code CoopNetPump})</h2>
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
     * Adds the entry if this campaign does not already have one, and returns it either way.
     * Idempotent across game loads: the entry persists in the save, so on a reload the manager
     * already holds it and this call finds it rather than adding a second.
     *
     * <p>Registered with {@code addIntel(plugin, true)} rather than {@code queueIntel}, because
     * queueing is comm-relay gated and a diagnostics page must be readable from anywhere - including
     * from deep space with a dead link, which is exactly when a player wants it.
     *
     * @return the live entry, or null when there is no sector or the manager refused
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
            CoopSessionIntel existing = findExisting(manager);
            if (existing != null) {
                return existing;
            }
            CoopSessionIntel intel = new CoopSessionIntel();
            // forceNoMessage: registration happens on every load, and a "new intel" toast every load
            // would be noise for an entry the player already knows about.
            manager.addIntel(intel, true);
            CoopLog.info(CoopSessionIntel.class, "Coop session intel entry registered");
            return intel;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionIntel.class, "Could not register the coop session intel entry", ex);
            return null;
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

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Permanent: nothing ends this entry, so the manager sweep never sees a reason to drop it. */
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
     * Hidden unless a coop role is live. This is the solo-play answer: the mod's policy is that
     * enabling it means coop rules, but a save can still be loaded with no session running, and a
     * dead diagnostics page in the intel screen is clutter.
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
                CoopSessionIntelModel.describeTransport(link.transport()),
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
                CoopSessionIntelModel.describeTransport(peer.transport()),
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
