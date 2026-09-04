package coop.campaign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.SpecBarEventCreator;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionBarEventWrapper;

import coop.util.CoopLog;

/**
 * Guest side of the Phase 12c bar pool: rebuild the pool-managed part of {@code PortsideBarData} from
 * the host's ordered snapshot.
 *
 * <p><b>Managed subset.</b> The guest's pool is split in two. Everything the host can replicate is
 * <em>managed</em> and is cleared and rebuilt wholesale on each snapshot. The intel-backed offers
 * ({@link CoopBarPoolCapture#isIntelBacked}) are <em>protected</em>: they carry a live intel
 * reference that cannot cross the wire, the guest generates its own from Phase 13's replicated base
 * intel, and they are never touched here. Membership is decided by class, not by remembering what we
 * injected — a guest-generated offer left over from before suppression armed is managed (and so gets
 * culled), which is exactly what "the guest generates no offers of its own" requires. The injected
 * ids are still tracked, but for diagnostics and for the no-op check, not for ownership.
 *
 * <p><b>Order is the point.</b> {@code BarCMD.showOptions} does {@code events.addAll(pool)} then
 * {@code Collections.shuffle(events, random)} with a {@code Random} seeded off the (synced)
 * {@code BarEventManager} seed. {@code shuffle}'s permutation depends only on the list size and that
 * random, so the same seed applied to a differently ordered list picks a different subset. The
 * snapshot is an ordered list and the rebuild appends in exactly that order.
 *
 * <p><b>Reconstruction, not deserialization.</b> A {@code PortsideBarEvent} is a code object. The
 * wire carries its id, its class simple name and one {@code long} content seed; this constructs a
 * fresh instance through the vanilla path ({@code new HubMissionBarEventWrapper(specId)} for missions,
 * a registered creator otherwise), overwrites the seed via {@link CoopBarSync}, and lets the guest's
 * own engine regenerate the person, cargo, prices and mission body around it.
 *
 * <p><b>Never registered as a creator.</b> Injected events go into {@code PortsideBarData} only, never
 * into {@code BarEventManager.barEventCreators} — the manager's orphan sweep deletes events that are
 * in {@code barEventCreators} but not in {@code active}, and an event it has never heard of is
 * invisible to that sweep. The cost is that {@code getCreatorFor} returns null for them, so accepting
 * one sets no creator timeout on the guest; the host's snapshot is authoritative for what exists, so
 * that bookkeeping is the host's anyway.
 */
public final class CoopBarPoolInjector {

    /** Discriminator for the one bar event whose id is a spec id rather than its own class name. */
    static final String MISSION_WRAPPER_KIND = "HubMissionBarEventWrapper";

    /**
     * The seam the rebuild runs against, so the ordering and managed-subset logic is unit-testable
     * without an engine. {@link EnginePool} is the real implementation.
     */
    public interface PoolView {
        /** Class simple names of the events currently in the pool, in pool order. */
        List<String> eventKinds();

        /** Remove the pool event at {@code index} of the list {@link #eventKinds()} returned. */
        void removeAt(int index);

        /** Construct, seed, pin and append one offer. False when it could not be built. */
        boolean append(CoopMissionBoardSync.Entry entry);
    }

    /** What one rebuild did. */
    public record Rebuild(int removed, int kept, int injected, int failed) {
    }

    private String lastAppliedSignature;
    /** Lazily probed event-id -> creator index; null means "not built for this session yet". */
    private Map<String, BarEventManager.GenericBarEventCreator> creatorsByEventId;

    /** Session (re)start: forget the creator index and force the next snapshot to rebuild. */
    public void reset() {
        lastAppliedSignature = null;
        creatorsByEventId = null;
    }

    /**
     * Apply a host pool snapshot to the live sector. Returns null when nothing was done (not a bar
     * snapshot, no engine pool, or byte-identical to the last applied one).
     */
    public Rebuild apply(List<CoopMissionBoardSync.Entry> entries, String localPlayerId) {
        List<CoopMissionBoardSync.Entry> offers = injectable(entries, localPlayerId);
        String signature = CoopBarPoolCapture.signature(offers);
        if (signature.equals(lastAppliedSignature)) {
            return null;
        }
        try {
            PortsideBarData data = PortsideBarData.getInstance();
            if (data == null || data.getEvents() == null) {
                return null;
            }
            EnginePool pool = new EnginePool(data);
            Rebuild result = rebuild(pool, offers);
            lastAppliedSignature = signature;
            CoopLog.info(CoopBarPoolInjector.class, "Coop guest rebuilt portside bar pool: removed="
                    + result.removed() + " keptIntelBacked=" + result.kept()
                    + " injected=" + result.injected() + " failed=" + result.failed());
            return result;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBarPoolInjector.class, "Failed to inject host portside bar pool", ex);
            return null;
        }
    }

    // ---- Pure rebuild logic (unit-tested through PoolView) -------------------------------------

    /**
     * Clear the managed subset, then append the snapshot in order. Removal walks backwards so the
     * indices handed to {@link PoolView#removeAt} stay valid against the list it reported.
     */
    static Rebuild rebuild(PoolView view, List<CoopMissionBoardSync.Entry> offers) {
        List<String> kinds = view.eventKinds();
        int removed = 0;
        for (int i = kinds.size() - 1; i >= 0; i--) {
            if (isManaged(kinds.get(i))) {
                view.removeAt(i);
                removed++;
            }
        }
        int injected = 0;
        int failed = 0;
        for (CoopMissionBoardSync.Entry entry : offers) {
            if (view.append(entry)) {
                injected++;
            } else {
                failed++;
            }
        }
        return new Rebuild(removed, kinds.size() - removed, injected, failed);
    }

    /** Managed = replicable = everything the host can rebuild for us. */
    static boolean isManaged(String eventClassSimpleName) {
        return !CoopBarPoolCapture.isIntelBacked(eventClassSimpleName);
    }

    /**
     * The snapshot entries this client should actually put in its pool, in snapshot order: bar
     * entries only, never an intel-backed class (defensive — the host does not send those), never an
     * offer the other player already holds, and at most one per id because the engine guarantees one
     * active event per creator id.
     */
    static List<CoopMissionBoardSync.Entry> injectable(List<CoopMissionBoardSync.Entry> entries,
                                                       String localPlayerId) {
        List<CoopMissionBoardSync.Entry> offers = new ArrayList<>();
        if (entries == null) {
            return offers;
        }
        String player = localPlayerId == null ? "" : localPlayerId.trim();
        Set<String> seen = new HashSet<>();
        for (CoopMissionBoardSync.Entry entry : entries) {
            if (entry == null || entry.sourceType() != CoopMissionBoardSync.SourceType.BAR) {
                continue;
            }
            if (CoopBarPoolCapture.isIntelBacked(entry.eventKind())) {
                continue;
            }
            if (entry.isClaimed() && !entry.acceptedByPlayerId().equals(player)) {
                continue;
            }
            if (seen.add(entry.missionId())) {
                offers.add(entry);
            }
        }
        return offers;
    }

    // ---- Engine wiring -------------------------------------------------------------------------

    /** {@link PoolView} over the live {@code PortsideBarData}. */
    private final class EnginePool implements PoolView {
        private final PortsideBarData data;
        private final List<PortsideBarEvent> snapshot;
        private final List<String> injected = new ArrayList<>();

        private EnginePool(PortsideBarData data) {
            this.data = data;
            this.snapshot = new ArrayList<>(data.getEvents());
        }

        @Override
        public List<String> eventKinds() {
            List<String> kinds = new ArrayList<>(snapshot.size());
            for (PortsideBarEvent event : snapshot) {
                kinds.add(event == null ? "" : event.getClass().getSimpleName());
            }
            return kinds;
        }

        @Override
        public void removeAt(int index) {
            PortsideBarEvent event = snapshot.get(index);
            if (event != null) {
                // removeEvent, not the list: it also drops the event from BarEventManager.active,
                // which is where a leftover guest-generated offer would otherwise linger.
                data.removeEvent(event);
            }
        }

        @Override
        public boolean append(CoopMissionBoardSync.Entry entry) {
            PortsideBarEvent event = construct(entry);
            if (event == null) {
                return false;
            }
            if (entry.contentSeed() != 0L) {
                CoopBarSync.writeEventSeed(event, entry.contentSeed());
            }
            if (!entry.marketId().isEmpty()) {
                MarketAPI pin = findMarket(entry.marketId());
                if (pin != null) {
                    // Re-apply the host's shownAt pin so an offer already tied to one market stays
                    // tied to it here (BaseBarEvent.shouldShowAtMarket rejects every other market).
                    event.wasShownAtMarket(pin);
                }
            }
            data.addEvent(event);
            injected.add(entry.missionId());
            return true;
        }
    }

    /**
     * Build a fresh offer object for one snapshot entry through the vanilla construction paths.
     *
     * <p>{@code HubMissionBarEventWrapper} is special-cased because it is the one event whose
     * {@code getBarEventId()} is a {@code bar_events.csv} spec id rather than its own class name, so
     * the id is exactly the constructor argument. Everything else reports its class simple name, so
     * it is resolved through a creator that produces that class.
     */
    private PortsideBarEvent construct(CoopMissionBoardSync.Entry entry) {
        try {
            if (MISSION_WRAPPER_KIND.equals(entry.eventKind())) {
                return new HubMissionBarEventWrapper(entry.missionId());
            }
            BarEventManager.GenericBarEventCreator creator = creatorIndex().get(entry.missionId());
            if (creator != null) {
                return creator.createBarEvent();
            }
            // Last resort: the id happens to name a spec directly (mod content that keeps its spec
            // id and class name in step).
            if (Global.getSettings() != null
                    && Global.getSettings().getBarEventSpec(entry.missionId()) != null) {
                return new SpecBarEventCreator(entry.missionId()).createBarEvent();
            }
            CoopLog.warn(CoopBarPoolInjector.class, "No creator for host bar offer id="
                    + entry.missionId() + " kind=" + entry.eventKind() + "; it will not be shown");
            return null;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBarPoolInjector.class,
                    "Failed to construct host bar offer id=" + entry.missionId(), ex);
            return null;
        }
    }

    /**
     * Event id -> creator, built once per session by asking every registered creator for one throwaway
     * event and reading its id.
     *
     * <p>There is no cheaper mapping available: a creator's own {@code getBarEventId()} is the
     * <em>creator's</em> class name ({@code LuddicFarmerBarEventCreator}) while {@code BarCMD} keys
     * off the <em>event's</em> ({@code LuddicFarmerBarEvent}), and a non-mission spec's id names
     * neither. The probe instances are discarded unbuilt — no mission is created and nothing is added
     * to any pool — and this runs once, on the first snapshot of a session.
     */
    private Map<String, BarEventManager.GenericBarEventCreator> creatorIndex() {
        if (creatorsByEventId != null) {
            return creatorsByEventId;
        }
        Map<String, BarEventManager.GenericBarEventCreator> index = new HashMap<>();
        try {
            BarEventManager manager = BarEventManager.getInstance();
            List<BarEventManager.GenericBarEventCreator> creators =
                    manager == null ? null : manager.getCreators();
            if (creators != null) {
                for (BarEventManager.GenericBarEventCreator creator : new ArrayList<>(creators)) {
                    indexCreator(index, creator);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBarPoolInjector.class, "Failed to index bar event creators", ex);
        }
        creatorsByEventId = index;
        CoopLog.info(CoopBarPoolInjector.class,
                "Coop guest indexed " + index.size() + " bar event creator(s) by event id");
        return creatorsByEventId;
    }

    private void indexCreator(Map<String, BarEventManager.GenericBarEventCreator> index,
                              BarEventManager.GenericBarEventCreator creator) {
        if (creator == null) {
            return;
        }
        try {
            PortsideBarEvent probe = creator.createBarEvent();
            if (probe == null) {
                return;
            }
            String id = probe.getBarEventId();
            if (id != null && !id.trim().isEmpty()) {
                index.putIfAbsent(id.trim(), creator);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.debug(CoopBarPoolInjector.class, "Bar event creator "
                    + creator.getClass().getSimpleName() + " could not be probed: " + ex);
        }
    }

    private static MarketAPI findMarket(String marketId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getEconomy() == null) {
            return null;
        }
        return sector.getEconomy().getMarket(marketId);
    }
}
