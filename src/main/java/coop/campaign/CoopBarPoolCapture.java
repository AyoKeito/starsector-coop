package coop.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEvent;
import com.fs.starfarer.api.util.TimeoutTracker;

import coop.util.CoopLog;

/**
 * Host side of the Phase 12c bar pool: enumerate the sector's portside bar offers and decide when the
 * guest needs a fresh snapshot.
 *
 * <p><b>The pool is global, not per-market.</b> {@code PortsideBarData} holds one list for the whole
 * sector; market scoping happens at render time in {@code BarCMD} through
 * {@code shouldShowAtMarket(market)} plus the {@code shownAt} pin. So this captures the whole list —
 * there is no "the bar at Jangala" to snapshot — and the guest's own {@code BarCMD} does the
 * filtering. That is also why this is a <b>push</b> watcher rather than a request/response on market
 * open: a fast click on the bar option beats any round trip, and the pool the guest needs is the same
 * pool everywhere.
 *
 * <p><b>Enumeration must not call the filter.</b> {@code shouldShowAtMarket} is not a pure predicate:
 * {@code DeliveryBarEvent} writes sector memory from it and {@code HubMissionBarEventWrapper} builds
 * (and aborts) an entire mission inside it. The capture therefore reads only identity, seed and pin.
 *
 * <p><b>Intel-backed offers are excluded.</b> {@code PirateBaseRumorBarEvent} and
 * {@code LuddicPathBaseBarEvent} hold a live intel reference; there is nothing to send that would let
 * the guest rebuild one. The guest keeps generating those itself off the Phase 13 replicated base
 * intel, and {@link CoopBarPoolInjector} leaves them alone. They are also the only two vanilla bar
 * events that ever return true from {@code shouldRemoveEvent()}, which is what
 * {@code PortsideBarData.advance} prunes on — so nothing in the replicated subset is ever pruned out
 * from under the guest.
 */
public final class CoopBarPoolCapture {

    /**
     * Bar events that own a live intel reference. Matched by class simple name rather than
     * {@code instanceof} so this file does not have to import the intel classes (and so a future
     * intel-backed event can be added without touching imports).
     */
    static final Set<String> INTEL_BACKED = Set.of(
            "PirateBaseRumorBarEvent",
            "LuddicPathBaseBarEvent");

    /** Signature field separator; a control char so no id or class name can forge a boundary. */
    private static final char SEP = '\u001f';

    /** Signature of the last broadcast pool; {@code null} re-arms a full rebroadcast. */
    private String lastSignature;

    /** Re-arm the rebroadcast (session start, so a rejoining guest gets a warm pool). */
    public void reset() {
        lastSignature = null;
    }

    /**
     * Enumerate the sector's replicable bar offers, in pool order. Never throws.
     *
     * <p>Returns {@code null} — not an empty list — when there is no pool to read (no sector yet, or
     * the read failed). The distinction is load-bearing: an empty snapshot is an instruction to the
     * guest to clear its bar, so "I could not look" must never be sent as "there is nothing there".
     */
    public List<CoopMissionBoardSync.Entry> capture() {
        try {
            PortsideBarData data = PortsideBarData.getInstance();
            if (data == null || data.getEvents() == null) {
                return null;
            }
            BarEventManager manager = BarEventManager.getInstance();
            TimeoutTracker<PortsideBarEvent> active = manager == null ? null : manager.getActive();
            List<CoopMissionBoardSync.Entry> entries = new ArrayList<>();
            // Copy first: reading an event's seed cannot mutate the list, but the pool is a live
            // engine list and a defensive copy costs nothing at this size.
            for (PortsideBarEvent event : new ArrayList<>(data.getEvents())) {
                CoopMissionBoardSync.Entry entry = captureOne(event, active);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBarPoolCapture.class, "Failed to capture portside bar pool", ex);
            return null;
        }
    }

    private CoopMissionBoardSync.Entry captureOne(PortsideBarEvent event,
                                                  TimeoutTracker<PortsideBarEvent> active) {
        if (event == null) {
            return null;
        }
        String kind = event.getClass().getSimpleName();
        if (isIntelBacked(kind)) {
            return null;
        }
        String barEventId = event.getBarEventId();
        if (barEventId == null || barEventId.trim().isEmpty()) {
            // No stable identity means the guest could never name it back; skip rather than guess.
            return null;
        }
        Long seed = CoopBarSync.readEventSeed(event);
        if (seed == null) {
            // No content seed: the offer has no regenerable content (or the handle failed). Still
            // worth replicating — the guest gets the right offer, just with its own defaults.
            seed = 0L;
        }
        String shownAtMarketId = "";
        if (event instanceof BaseBarEvent base) {
            MarketAPI shownAt = base.getShownAt();
            if (shownAt != null && shownAt.getId() != null) {
                shownAtMarketId = shownAt.getId();
            }
        }
        return CoopMissionBoardSync.Entry.barOffer(barEventId, kind, seed, shownAtMarketId,
                remainingDays(event, active));
    }

    /**
     * Remaining active days, or 0 when unknown.
     *
     * <p>Guarded by {@code contains} on purpose: {@code TimeoutTracker.getRemaining} runs through
     * {@code getData}, which <b>inserts</b> the item with {@code remaining = 0} when it is missing.
     * Calling it unguarded from a read-only poll would quietly add pool events to the host's live
     * {@code active} tracker, where they become orphan-sweep fodder. Read-only means read-only.
     */
    private long remainingDays(PortsideBarEvent event, TimeoutTracker<PortsideBarEvent> active) {
        if (active == null || !active.contains(event)) {
            return 0L;
        }
        return Math.max(0L, (long) Math.floor(active.getRemaining(event)));
    }

    // ---- Pure diff logic (unit-tested) ---------------------------------------------------------

    public static boolean isIntelBacked(String eventClassSimpleName) {
        return eventClassSimpleName != null && INTEL_BACKED.contains(eventClassSimpleName);
    }

    /**
     * Order-sensitive pool signature. Order is load-bearing, not cosmetic: {@code BarCMD.showOptions}
     * shuffles the pool with a {@code Random} seeded from the (synced) manager seed, and
     * {@code Collections.shuffle}'s permutation depends only on the list <em>size</em> and that
     * random — so the same seed over a differently ordered list shows a different subset. A reorder
     * with no membership change must therefore still trigger a rebroadcast.
     */
    static String signature(List<CoopMissionBoardSync.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder(entries.size() * 32);
        sb.append(entries.size());
        for (CoopMissionBoardSync.Entry entry : entries) {
            sb.append(SEP).append(entry.missionId())
                    .append(SEP).append(entry.contentSeed())
                    .append(SEP).append(entry.eventKind())
                    .append(SEP).append(entry.marketId());
        }
        return sb.toString();
    }

    /**
     * True when {@code entries} differs from the last broadcast pool; records it as the new baseline.
     * {@code expiresAtDay} is deliberately outside the signature — it ticks down continuously and
     * would make every poll a "change".
     */
    public boolean markChanged(List<CoopMissionBoardSync.Entry> entries) {
        String signature = signature(entries);
        if (signature.equals(lastSignature)) {
            return false;
        }
        lastSignature = signature;
        return true;
    }
}
