package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyDecivListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.CargoPodsEntityPlugin;
import com.fs.starfarer.api.impl.campaign.GateEntityPlugin;
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMission;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionBarEventWrapper;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import coop.colony.CoopColonyIncome;
import coop.colony.CoopColonyManagement;
import coop.colony.CoopColonySync;
import coop.colony.CoopExpeditionWarning;
import coop.colony.CoopExpeditionWarningSync;
import coop.colony.CoopRaidOutcomeSync;
import coop.rewards.CoopRewardSplitter;
import coop.ui.CoopFeed;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.awt.Color;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Phase 12 hub: replicates host-authoritative campaign state across the coop session.
 *
 * <p>Covers shared reputation ({@link CoopRepDelta}), faction-to-faction relations
 * ({@link CoopFactionRelations}), the shared mission/bar pool + first-come claims
 * ({@link CoopMissionBoardSync}), host-authoritative market contents + transactions
 * ({@link CoopMarketSync}), salvage/exploration/construction world deltas ({@link CoopWorldDelta}),
 * and world-affecting ability arbitration ({@link CoopAbilityArbiter}).
 *
 * <p><b>Authority model.</b> The host owns every shared model. It captures vanilla events through
 * {@link CoopCampaignEventListener} and broadcasts the resulting values to the guest, which applies
 * them by <em>setting</em> state rather than re-simulating. Guest-driven outcomes the host cannot
 * observe (a salvaged entity, a market transaction, an activated world ability) funnel up as a
 * single explicit report; the host integrates and re-broadcasts. The {@link ReplayGuard} ensures
 * applying a host-originated event never causes the applier to rebroadcast it.
 *
 * <p>The pure decision/model classes are unit tested; this orchestrator wires them to the live
 * engine (best-effort, defensive) and to the net service, and is exercised in the two-instance
 * smoke test.
 */
public final class CoopCampaignReplicator
        implements CoopCampaignEventListener.Sink, CoopRaidOutcomeSync.Sink, CoopColonySync.Sink,
        CoopColonyIncome.Sink {

    /**
     * Phase 32: the submarkets the host owns and replicates, in snapshot order.
     *
     * <p>Everything the market path reads or writes must name one of these — see
     * {@link #submarketCargo(MarketAPI, String)} for why the list is an allowlist rather than a
     * denylist, and why {@code local_resources} is not on it.
     */
    static final List<String> SHARED_SUBMARKETS = List.of(
            Submarkets.SUBMARKET_OPEN,
            Submarkets.SUBMARKET_BLACK,
            Submarkets.GENERIC_MILITARY,
            Submarkets.SUBMARKET_STORAGE);

    /**
     * Re-entrancy guard: while {@link #isReplaying()} the applier is mid-apply of a host-originated
     * event, so any vanilla event it triggers must not be captured and rebroadcast.
     */
    public static final class ReplayGuard {
        private int depth;

        public void begin() {
            depth++;
        }

        public void end() {
            if (depth > 0) {
                depth--;
            }
        }

        public boolean isReplaying() {
            return depth > 0;
        }
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final ReplayGuard replayGuard = new ReplayGuard();

    private final Map<String, Float> repTable = new HashMap<>();
    private final CoopFactionRelations factionRelations = new CoopFactionRelations();
    private final CoopMissionBoardSync missionBoard = new CoopMissionBoardSync();
    private final CoopMarketSync marketSync = new CoopMarketSync();
    private final CoopWorldDelta.Ledger worldLedger = new CoopWorldDelta.Ledger();

    /**
     * Guest-side hire detection baseline: marketId -> (personId -> the kind it was listed as) as of
     * the last applied MARKET_SNAPSHOT. There is no vanilla hire event, so a person present here and
     * absent from the market's live hireable set at close was hired by the local player.
     */
    private final Map<String, Map<String, CoopMarketSync.ItemKind>> appliedHireables = new HashMap<>();

    // Salvage watcher: salvageable entity ids present at the local player's location last pass. A
    // tracked id that vanishes means the local player salvaged/disassembled it -> WORLD_DELTA(CONSUME).
    private final Set<String> trackedSalvageables = new HashSet<>();
    private String watchedLocationId;
    /**
     * How often the watcher walks the player's location. It used to run every frame over every entity
     * there (358 in an asteroid belt), reading {@code getMemoryWithoutUpdate()} — which lazily allocates
     * a save-persisted Memory for entities that lack one — and allocating a fresh id set each time
     * (perf audit #5). The output is an event report, so seeing a salvage up to 250 ms late is not
     * observable: nothing in the session reads a CONSUME delta on a deadline.
     */
    static final long SALVAGE_SCAN_INTERVAL_MILLIS = 250L;
    private long lastSalvageScanMillis;
    /** Scratch, reused across passes: ids present at the watched location on the current pass. */
    private final Set<String> salvageScanScratch = new HashSet<>();
    /** Scratch, reused across passes: tracked ids that vanished on the current pass. */
    private final List<String> salvageConsumedScratch = new ArrayList<>();
    /**
     * Scratch, reused across passes: entities that appeared at the watched location on the current
     * pass and are player constructions worth replicating (see {@link #isReplicableConstruction}).
     */
    private final List<SectorEntityToken> constructionScratch = new ArrayList<>();

    // Orbit-angle sync: host re-broadcasts orbiting-body angles ~1Hz so the guest can snap out the
    // small clock-drift offset that makes shared systems' planets/jumps appear at different angles.
    static final long ORBIT_SYNC_INTERVAL_MILLIS = 1000L;
    private long lastOrbitSyncMillis;
    private int lastOrbitBodyCount = -1;

    // Player faction standings: host re-broadcasts the full set on a slow cadence and the guest
    // force-matches it. Event-driven REP_DELTA covers host-side changes immediately; this snapshot is
    // the safety net that converges drift the host can't see (e.g. the guest's own transponder-off
    // penalties, applied independently in the guest's simulation).
    static final long PLAYER_REP_SYNC_INTERVAL_MILLIS = 30000L;
    private long lastPlayerRepSyncMillis;

    // Phase 13 skeleton mutations: campaign-objective ownership and story-gate activation are polled
    // on a slow cadence and broadcast as WORLD_DELTAs, joined in Phase 12c by planet survey levels
    // and ruins exploration. All are rare (war-sim swings are days apart; gates activate once a
    // campaign; a survey is a manual player act), so the poll is cheap: two tag lookups and one
    // planet list per location, every few seconds.
    static final long SKELETON_POLL_INTERVAL_MILLIS = 5000L;
    private final CoopSkeletonMutationWatcher skeletonWatcher = new CoopSkeletonMutationWatcher();
    private long lastSkeletonPollMillis;
    private DecivCapture decivCapture;

    // Phase 32: the two 1 Hz reconcilers that carry shared-submarket access. Storage unlock is polled
    // on both roles while a dialog is open (the fee is paid inside it and there is no event to hook);
    // the commission is host-only. Both keep their engine contact behind a seam so the decisions are
    // unit-testable, and both are emitted on the same WORLD_DELTA channel as the skeleton mutations.
    /**
     * Phase 32 addition A: the hidden-base {@code hostMarketId <-> localMarketId} table. Empty (and
     * therefore the identity function in both directions) on the host and for every market whose id
     * already agrees across the two engines, which is every market except a mirrored pirate or
     * Luddic-Path base. {@code CoopBaseAuthority} is the only writer.
     */
    private final CoopMarketIds marketIds = new CoopMarketIds();

    /**
     * Guest, Phase 32 (P1-4): the market this dock has already asked the host about.
     *
     * <p>Vanilla reports a market open more than once per dock — the dock dialog's
     * {@code showInteractionDialog}, then the core Crew/Cargo screen's own
     * {@code reportPlayerOpenedMarketAndCargoUpdated}. The second one fires from <em>inside</em> the
     * trade screen, which is the one place {@link CoopMarketSyncGate} structurally cannot reach (it
     * disables dock-dialog option ids, and the player is already past them). Answering it means a
     * full strip-and-replace of the black market and the storage locker while the player is looking
     * at them. So one {@code MARKET_OPEN} per dock: cleared on market close, on a different market,
     * and on session teardown. Host-initiated re-snapshots are unaffected.
     */
    private String marketOpenRequestedFor;

    /**
     * Host, Phase 32 (P1-3): {@code senderId#seq} of every {@code MARKET_TXN} line already applied.
     *
     * <p>{@code MARKET_TXN} is on the pump's {@code survivesTheDropEdge} list and a peer's
     * {@code detach} requeues a partially written frame, so a line parked before a reconnect can be
     * delivered twice. Every apply is additive against a locker — a duplicated deposit is a phantom
     * hull no withdrawal can remove, a duplicated withdrawal takes a second ship — so the "the host
     * never rebroadcasts" transport argument needs an actual check behind it. Bounded to
     * {@link #MARKET_TXN_LEDGER_MAX} in insertion order: this only has to span a reconnect edge, not
     * a campaign.
     */
    private final Set<String> appliedMarketTxns = new LinkedHashSet<>();

    /** How many {@code (senderId, seq)} pairs the duplicate-transaction ledger remembers. */
    private static final int MARKET_TXN_LEDGER_MAX = 512;

    private final CoopStorageUnlockSync storageUnlockSync =
            new CoopStorageUnlockSync(CoopStorageUnlockSync.liveEngine());
    private final CoopCommissionSync commissionSync =
            new CoopCommissionSync(CoopCommissionSync.liveEngine());

    // Phase 24 milestone 1: player raids/bombardments against colonies. Bidirectional -- whoever
    // performs the act captures the vanilla outcome and reports it; the host canonicalizes and
    // rebroadcasts, and the ledger absorbs the echo on the originator.
    private final CoopRaidOutcomeSync.Ledger raidLedger = new CoopRaidOutcomeSync.Ledger();
    private CoopRaidOutcomeSync.HostileActCapture raidCapture;

    // Phase 24 milestone 2: colony lifecycle. Same bidirectional shape as the raid channel -- the
    // colonizing player captures the finished colony, the host canonicalizes and rebroadcasts, the
    // ledger absorbs the echo. Founding is captured a frame late, so the capture needs a tick.
    private final CoopColonySync.Ledger colonyLedger = new CoopColonySync.Ledger();
    private CoopColonySync.ColonizationCapture colonyCapture;

    // Phase 24 milestone 3: colony management. Bidirectional like the two channels above -- whoever
    // edited the colony ships the resulting absolute state, the host canonicalizes and rebroadcasts,
    // the ledger absorbs the echo. The Phase 10 interaction gate is a global first-come lockout on
    // dialogs, so the two players are never in colony screens at once and there is no conflict to
    // resolve.
    //
    // Two capture routes into one send helper: the content poll is the primary one (it is the only
    // route that sees a colony managed remotely from the command UI, which docks nowhere and fires no
    // market callback), and the open/close diff is the low-latency assist for the docked case vanilla
    // does report. See CoopColonyManagement.
    private final CoopColonyManagement.Ledger colonyMgmtLedger = new CoopColonyManagement.Ledger();
    private final CoopColonyManagement.Diff colonyMgmtDiff = new CoopColonyManagement.Diff();
    private final CoopColonyManagement.Poll colonyMgmtPoll = new CoopColonyManagement.Poll();
    /**
     * The engine write for one inbound {@code COLONY_MGMT}, as a seam. Production is
     * {@code CoopColonyManagement::applyToEngine}; a test swaps in an apply that fails, because the
     * failure path (suppress the market's poll instead of re-reporting the stale state) is not
     * reachable through a proxy market that behaves.
     */
    private Predicate<CoopColonyManagement.State> colonyMgmtApply =
            CoopColonyManagement::applyToEngine;
    /**
     * Colony-management poll cadence. Two seconds is the same figure the bar pool uses and is well
     * inside human reaction time for "I toggled free port and my partner's colony followed"; the tick
     * itself is a walk of the player's colonies reading a few fields each.
     */
    static final long COLONY_MGMT_POLL_INTERVAL_MILLIS = 2000L;
    private long lastColonyMgmtPollMillis;

    // Phase 24 milestone 3: colony income. No money crosses the wire -- both engines pay their own
    // player the full local colony net at month end, and each deducts its own half. COLONY_INCOME
    // carries the host's figure for drift logging only. See CoopColonyIncome.
    private CoopColonyIncome.MonthEndCapture colonyIncomeCapture;
    /** Month-end banners, queued because a month can end on a frame with no campaign UI yet. */
    private final Deque<String> pendingIncomeBanners = new ArrayDeque<>();
    static final int MAX_PENDING_INCOME_BANNERS = 8;
    /** Guest side of the income drift line: the two halves arrive in either order. */
    private Float pendingHostColonyNet;
    private long pendingHostColonyCount;
    private CoopColonyIncome.MonthTotals pendingLocalColonyTotals;

    // Phase 24 milestone 3: NPC threats against player colonies. Host-only simulation, so the host
    // scans its intel manager on a low-rate tick and broadcasts the whole set on hash change; the
    // guest reconciles its coop-owned warning intel against it. Same shape as Phase 13's BASE_SET.
    private long nextWarningPollAtMillis;
    private String lastWarningSetHash = "";
    private List<CoopExpeditionWarning> desiredWarnings = List.of();
    private boolean desiredWarningsReceived;
    private long nextWarningReconcileAtMillis;

    // Phase 12c bar pool: the host polls the global portside bar pool and pushes the ordered list on
    // change. Two seconds is well inside a dock-to-bar-click, and the pool only ever changes on
    // BarEventManager's 0.4-0.6 day generation tick or when someone accepts an offer.
    static final long BAR_POOL_POLL_INTERVAL_MILLIS = 2000L;
    /** The bar pool is sector-global, so its snapshot has no owning market. */
    static final String BAR_POOL_MARKET_ID = "";
    private final CoopBarPoolCapture barPoolCapture = new CoopBarPoolCapture();
    private final CoopBarPoolInjector barPoolInjector = new CoopBarPoolInjector();
    /** Phase 12 first-come trigger: watches the local pool for the player accepting an offer. */
    private final CoopBarAcceptanceWatcher barAcceptanceWatcher = new CoopBarAcceptanceWatcher();
    private long lastBarPoolPollMillis;

    private CoopCampaignEventListener listener;
    private boolean factionRelationsSeeded;

    public CoopCampaignReplicator(CoopNetService service, CoopSessionState session) {
        this(service, session, System::currentTimeMillis);
    }

    public CoopCampaignReplicator(CoopNetService service, CoopSessionState session, LongSupplier clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
        // A STORAGE_UNLOCK for a base can arrive before the base is mapped -- the world delta and the
        // BASE_SET are independent messages -- and applyRemote then flags it under the host's id.
        // Learning the mapping is the one moment that flag can be moved onto the local market.
        marketIds.setListener(storageUnlockSync::onMarketIdMapped);
    }

    /**
     * The hidden-base market-id table (Phase 32 addition A). Written by {@code CoopBaseAuthority},
     * read here and by the Phase 30 bridge dump.
     */
    public CoopMarketIds marketIds() {
        return marketIds;
    }

    // ---- Listener lifecycle -------------------------------------------------------------------

    /** Registers the campaign event listener on the sector (idempotent). */
    public void registerOn(SectorAPI sector) {
        if (sector == null || listener != null) {
            return;
        }
        listener = new CoopCampaignEventListener(this);
        sector.addTransientListener(listener);
        // Session start: re-arm the bar-pool rebroadcast so a (re)joining guest gets a warm pool on
        // the first poll rather than waiting for the host's next offer to spawn or expire.
        barPoolCapture.reset();
        barPoolInjector.reset();
        barAcceptanceWatcher.reset();
        lastBarPoolPollMillis = 0L;
        // CargoScreenListener is dispatched through the listener manager, not the campaign-event
        // list, so it needs its own transient registration (Phase 12d: cargo pod replication).
        try {
            sector.getListenerManager().addListener(listener, true);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop cargo-screen listener; pod replication will not fire", ex);
        }
        // Deciv capture is its own listener interface (ColonyDecivListener), dispatched through the
        // listener manager rather than the campaign-event list.
        try {
            decivCapture = new DecivCapture();
            sector.getListenerManager().addListener(decivCapture, true);
        } catch (RuntimeException | LinkageError ex) {
            decivCapture = null;
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop deciv listener; DECIV world-deltas will not fire", ex);
        }
        // Phase 24 M1: raids/bombardments arrive on their own vanilla listener interface, also via
        // the listener manager rather than the campaign-event list.
        try {
            raidCapture = new CoopRaidOutcomeSync.HostileActCapture(this);
            sector.getListenerManager().addListener(raidCapture, true);
        } catch (RuntimeException | LinkageError ex) {
            raidCapture = null;
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop hostile-act listener; RAID_RESULT will not fire", ex);
        }
        // Phase 24 M2: colonization/abandonment is a third listener-manager interface.
        try {
            colonyCapture = new CoopColonySync.ColonizationCapture(this);
            sector.getListenerManager().addListener(colonyCapture, true);
        } catch (RuntimeException | LinkageError ex) {
            colonyCapture = null;
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop colonization listener; COLONY_FOUNDED will not fire", ex);
        }
        // Phase 24 M3: the month-end callback exists only on EconomyTickListener -- the
        // CampaignEventListener path this class already rides reports economy *ticks* and nothing
        // else -- so the income split needs its own listener-manager registration.
        try {
            colonyIncomeCapture = new CoopColonyIncome.MonthEndCapture(this);
            sector.getListenerManager().addListener(colonyIncomeCapture, true);
        } catch (RuntimeException | LinkageError ex) {
            colonyIncomeCapture = null;
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop economy month-end listener; colony income will not split", ex);
        }
        // Phase 24 M3: a (re)start re-arms the host's warning rebroadcast so a fresh connection gets
        // the full set, and drops any management baseline left over from the last session. The
        // management poll re-arms too: a hash from the last session says nothing about what the peer
        // on the other end of this connection holds, and the host's first tick after the edge
        // re-sends every colony to heal whatever diverged while the channel was down.
        colonyMgmtDiff.reset();
        colonyMgmtPoll.armBaseline();
        lastColonyMgmtPollMillis = 0L;
        resetExpeditionWarningStreams();
        CoopLog.info(CoopCampaignReplicator.class, "Coop campaign event listener registered");
    }

    /** Removes the listener and clears replicated state on session end. */
    public void dispose(SectorAPI sector) {
        if (sector != null && listener != null) {
            try {
                sector.removeListener(listener);
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to remove coop campaign listener", ex);
            }
            try {
                sector.getListenerManager().removeListener(listener);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class,
                        "Failed to remove coop cargo-screen listener", ex);
            }
        }
        if (sector != null && decivCapture != null) {
            try {
                sector.getListenerManager().removeListener(decivCapture);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to remove coop deciv listener", ex);
            }
        }
        if (sector != null && raidCapture != null) {
            try {
                sector.getListenerManager().removeListener(raidCapture);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to remove coop hostile-act listener", ex);
            }
        }
        if (sector != null && colonyCapture != null) {
            try {
                sector.getListenerManager().removeListener(colonyCapture);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class,
                        "Failed to remove coop colonization listener", ex);
            }
        }
        if (sector != null && colonyIncomeCapture != null) {
            try {
                sector.getListenerManager().removeListener(colonyIncomeCapture);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class,
                        "Failed to remove coop economy month-end listener", ex);
            }
        }
        if (colonyIncomeCapture != null) {
            colonyIncomeCapture.reset();
        }
        colonyIncomeCapture = null;
        colonyMgmtDiff.reset();
        colonyMgmtPoll.reset();
        lastColonyMgmtPollMillis = 0L;
        colonyMgmtLedger.clear();
        pendingIncomeBanners.clear();
        pendingHostColonyNet = null;
        pendingHostColonyCount = 0L;
        pendingLocalColonyTotals = null;
        // The mirrored warnings are coop-owned intel with no meaning outside a session, and leaving
        // them in the save would show the player a frozen countdown for a threat they cannot see.
        // The entries' own staleness timer is the backstop for a teardown that never runs.
        clearMirroredExpeditionWarnings(sector);
        resetExpeditionWarningStreams();
        desiredWarnings = List.of();
        desiredWarningsReceived = false;
        if (raidCapture != null) {
            raidCapture.reset();
        }
        raidCapture = null;
        raidLedger.clear();
        if (colonyCapture != null) {
            colonyCapture.reset();
        }
        colonyCapture = null;
        colonyLedger.clear();
        decivCapture = null;
        listener = null;
        factionRelationsSeeded = false;
        lastPlayerRepSyncMillis = 0L;
        lastSkeletonPollMillis = 0L;
        storageUnlockSync.reset();
        // The table names this campaign's live base markets; CoopBaseAuthority refills it from the
        // host's post-reconnect BASE_SET.
        marketIds.clear();
        commissionSync.reset();
        lastBarPoolPollMillis = 0L;
        barPoolCapture.reset();
        barPoolInjector.reset();
        barAcceptanceWatcher.reset();
        skeletonWatcher.clear();
        repTable.clear();
        factionRelations.clear();
        missionBoard.clear();
        marketSync.clear();
        appliedHireables.clear();
        // Phase 32: the per-dock MARKET_OPEN latch and the duplicate-transaction ledger are both
        // session-scoped. Keeping the latch across a teardown would silence the first open of the
        // next session; keeping the ledger would only pin memory (seqs restart with the service).
        marketOpenRequestedFor = null;
        appliedMarketTxns.clear();
        // Same rule as tickMarketSyncGate: put the trade options back before forgetting the gate,
        // or a teardown mid-dock leaves them greyed out with nothing left to re-enable them.
        if (marketSyncGate.pendingMarketId() != null) {
            releaseMarketSyncGate("session teardown");
            marketSyncGate.clear();
        }
        worldLedger.clear();
        // Salvage-watcher baseline too: leaving it populated meant that on reconnect in the same
        // system, every entity consumed last session looked "newly missing" and was re-reported as a
        // fresh WORLD_DELTA(CONSUME). Clearing forces a silent re-seed on the next tick.
        trackedSalvageables.clear();
        salvageScanScratch.clear();
        salvageConsumedScratch.clear();
        constructionScratch.clear();
        watchedLocationId = null;
        lastSalvageScanMillis = 0L;
        // Grant ids are minted per session and saved nowhere, so a new session cannot collide with
        // one of these and keeping them would only pin memory.
        creditTransfer.clear();
    }

    public boolean isRegistered() {
        return listener != null;
    }

    // ---- Inbound routing ----------------------------------------------------------------------

    /** Routes a campaign-replication message. Returns true if it was a Phase 12 message type. */
    public boolean handle(CoopMessages.Message message) {
        Objects.requireNonNull(message, "message");
        switch (message.type()) {
            case REP_DELTA -> applyRepDelta(message);
            case GUEST_REP_DELTA -> handleGuestRepDelta(message);
            case PLAYER_REP_SNAPSHOT -> applyPlayerRepSnapshot(message);
            case FACTION_REL_DELTA -> applyFactionRelDelta(message);
            case MISSION_POOL_SNAPSHOT -> applyMissionPool(message);
            case MISSION_CLAIM_REQUEST -> hostHandleMissionClaim(message);
            case MISSION_CLAIM_ACCEPT -> guestApplyMissionAccept(message);
            case MISSION_CLAIM_REJECT -> guestApplyMissionReject(message);
            case MARKET_OPEN -> handleMarketOpen(message);
            case MARKET_SNAPSHOT -> applyMarketSnapshot(message);
            case MARKET_TXN -> hostApplyMarketTxn(message);
            case WORLD_DELTA -> handleWorldDelta(message);
            case RAID_RESULT -> handleRaidResult(message);
            case COLONY_FOUNDED, COLONY_ABANDONED -> handleColonyLifecycle(message);
            case COLONY_MGMT -> handleColonyMgmt(message);
            case COLONY_INCOME -> handleColonyIncome(message);
            case EXPEDITION_WARNING -> handleExpeditionWarning(message);
            case ABILITY_ACTIVATE -> hostHandleAbilityActivate(message);
            case ORBIT_SNAPSHOT -> applyOrbitSnapshot(message);
            case CREDITS_GRANT -> handleCreditsGrant(message);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- Reputation (host capture -> guest apply) ---------------------------------------------

    @Override
    public void onPlayerReputationChange(String factionId, float delta) {
        if (replayGuard.isReplaying() || !isActive() || factionId == null) {
            return;
        }
        try {
            if (isHost()) {
                float resulting = playerRelationshipTo(factionId);
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, factionId), resulting);
                send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.FACTION.name(), factionId, delta, resulting));
                CoopLog.info(CoopCampaignReplicator.class, "Coop REP_DELTA faction=" + factionId
                        + " delta=" + delta + " resulting=" + resulting);
            } else if (isGuest()) {
                // The guest forwards its own earned/lost faction rep to the host, which folds the DELTA
                // (not the resulting value: per-client baselines differ) into the canonical standing and
                // rebroadcasts the authoritative result. The replayGuard check above means changes the
                // guest applied from a host message are never re-reported.
                send(CoopMessages.guestRepDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.FACTION.name(), factionId, delta));
                CoopLog.info(CoopCampaignReplicator.class, "Coop GUEST_REP_DELTA faction=" + factionId
                        + " delta=" + delta);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture faction reputation change", ex);
        }
    }

    @Override
    public void onPlayerReputationChange(PersonAPI person, float delta) {
        if (replayGuard.isReplaying() || !isActive() || person == null) {
            return;
        }
        try {
            String personId = person.getId();
            if (isHost()) {
                float resulting = person.getRelToPlayer() != null ? person.getRelToPlayer().getRel() : delta;
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.PERSON, personId), resulting);
                send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.PERSON.name(), personId, delta, resulting));
                CoopLog.info(CoopCampaignReplicator.class, "Coop REP_DELTA person=" + personId
                        + " delta=" + delta + " resulting=" + resulting);
            } else if (isGuest()) {
                send(CoopMessages.guestRepDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.PERSON.name(), personId, delta));
                CoopLog.info(CoopCampaignReplicator.class, "Coop GUEST_REP_DELTA person=" + personId
                        + " delta=" + delta);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture person reputation change", ex);
        }
    }

    private void applyRepDelta(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopRepDelta.TargetType type = CoopRepDelta.TargetType.valueOf(
                payload.requiredString("targetType"));
        String targetId = payload.requiredString("targetId");
        float resulting = payload.requiredFloat("resultingValue");
        repTable.put(CoopRepDelta.relationshipKey(type, targetId), resulting);
        boolean appliedToEngine = false;
        replayGuard.begin();
        try {
            if (type == CoopRepDelta.TargetType.FACTION) {
                FactionAPI player = playerFaction();
                if (player != null) {
                    player.setRelationship(targetId, resulting);
                    appliedToEngine = true;
                }
            } else {
                PersonAPI person = findPerson(targetId);
                if (person != null && person.getRelToPlayer() != null) {
                    person.getRelToPlayer().setRel(resulting);
                    appliedToEngine = true;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply REP_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        if (appliedToEngine) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop applied REP_DELTA " + type + ":" + targetId
                    + " -> " + resulting);
        } else {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop REP_DELTA stored but target missing "
                    + type + ":" + targetId + " -> " + resulting);
        }
    }

    /**
     * Host: a guest-earned/lost reputation increment. Folds the DELTA into the canonical target
     * relationship (current + delta, clamped) and rebroadcasts the resulting value so the guest
     * converges without trusting the guest's local baseline.
     */
    private void handleGuestRepDelta(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopRepDelta.TargetType type = CoopRepDelta.TargetType.valueOf(
                payload.requiredString("targetType"));
        String targetId = payload.requiredString("targetId");
        float delta = payload.requiredFloat("delta");
        if (type == CoopRepDelta.TargetType.FACTION) {
            handleGuestFactionRepDelta(targetId, delta);
        } else {
            handleGuestPersonRepDelta(targetId, delta);
        }
    }

    private void handleGuestFactionRepDelta(String factionId, float delta) {
        FactionAPI player = playerFaction();
        if (player == null) {
            return;
        }
        float resulting = clampRelationship(player.getRelationship(factionId) + delta);
        // Suppress the host's own listener in case setRelationship fires it, so we send exactly one
        // authoritative REP_DELTA below rather than risk a duplicate.
        replayGuard.begin();
        try {
            player.setRelationship(factionId, resulting);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply GUEST_REP_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, factionId), resulting);
        send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                CoopRepDelta.TargetType.FACTION.name(), factionId, delta, resulting));
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied GUEST_REP_DELTA faction=" + factionId
                + " delta=" + delta + " -> " + resulting);
    }

    private void handleGuestPersonRepDelta(String personId, float delta) {
        PersonAPI person = findPerson(personId);
        if (person == null || person.getRelToPlayer() == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop GUEST_REP_DELTA person target missing id=" + personId);
            return;
        }
        float resulting = clampRelationship(person.getRelToPlayer().getRel() + delta);
        // Suppress the host's own listener in case setRel fires it; the host sends the one canonical
        // REP_DELTA below.
        replayGuard.begin();
        try {
            person.getRelToPlayer().setRel(resulting);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply GUEST_REP_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.PERSON, personId), resulting);
        send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                CoopRepDelta.TargetType.PERSON.name(), personId, delta, resulting));
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied GUEST_REP_DELTA person=" + personId
                + " delta=" + delta + " -> " + resulting);
    }

    /** Host: broadcast the full set of player faction standings on a slow cadence (drift safety net). */
    public void tickPlayerRepSync() {
        if (!isHost() || replayGuard.isReplaying() || !isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastPlayerRepSyncMillis < PLAYER_REP_SYNC_INTERVAL_MILLIS) {
            return;
        }
        lastPlayerRepSyncMillis = nowMillis;
        try {
            SectorAPI sector = Global.getSector();
            FactionAPI player = sector == null ? null : sector.getPlayerFaction();
            if (player == null) {
                return;
            }
            Map<String, Float> standings = new LinkedHashMap<>();
            for (FactionAPI faction : sector.getAllFactions()) {
                if (faction.getId().equals(player.getId())) {
                    continue; // standing to self is constant
                }
                float value = player.getRelationship(faction.getId());
                standings.put(faction.getId(), value);
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, faction.getId()), value);
            }
            send(CoopMessages.playerRepSnapshot(session.sessionId(), service.nextSeq(), nowMillis,
                    CoopRepDelta.encodeFactionStandings(standings)));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Player reputation snapshot capture failed", ex);
        }
    }

    /** Guest: force player faction standings to the host's values (overwrites any local drift). */
    private void applyPlayerRepSnapshot(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        Map<String, Float> standings = CoopRepDelta.decodeFactionStandings(
                CoopMessages.requiredPayloadString(message, "reps"));
        FactionAPI player = playerFaction();
        if (player == null) {
            return;
        }
        int changed = 0;
        replayGuard.begin();
        try {
            for (Map.Entry<String, Float> entry : standings.entrySet()) {
                String factionId = entry.getKey();
                float target = entry.getValue();
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, factionId), target);
                if (Math.abs(player.getRelationship(factionId) - target) > 0.0001f) {
                    player.setRelationship(factionId, target);
                    changed++;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply PLAYER_REP_SNAPSHOT", ex);
        } finally {
            replayGuard.end();
        }
        if (changed > 0) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop applied PLAYER_REP_SNAPSHOT corrected="
                    + changed + "/" + standings.size() + " faction standings");
        }
    }

    // ---- Faction-to-faction relations ---------------------------------------------------------
    // No vanilla event reports inter-faction standing changes, so the host diffs all faction pairs
    // on each economy tick (bounded by faction count, daily cadence) and broadcasts only changes.

    @Override
    public void onEconomyTick(int iterIndex) {
        if (!isHost() || replayGuard.isReplaying() || !isActive()) {
            return;
        }
        captureFactionRelationChanges();
    }

    // ---- Phase 14 battle-outcome enrichment ----------------------------------------------------
    // Pure pass-through to whoever is observing battles (CoopBattleBridge). Deliberately no policy
    // here: the coop battle window is opened/closed by the bridge's own seams, and these callbacks
    // only supply a nicer outcome string than "UNKNOWN".

    /** Observer of the vanilla battle-result callbacks; wired to the Phase 14 battle bridge. */
    public interface BattleObserver {
        void onBattleOccurred(boolean playerWon);

        void onPlayerEngagement(String outcome);
    }

    private BattleObserver battleObserver;

    public void setBattleObserver(BattleObserver observer) {
        this.battleObserver = observer;
    }

    /**
     * Phase 21 session-stats tally hooks.
     *
     * <p>Four of the counters on the stats page are events this class already handles and already
     * de-duplicates: a market transaction, a mission claim the host accepted, a consumed salvageable,
     * a founded colony. The counters themselves live in the pump, so the replicator reports rather
     * than tallies, and each call below sits at the point where that event has already passed its
     * ledger. Null sink = no stats, which is what every existing test gets.
     */
    public interface StatsSink {
        /** One market transaction; {@code netCredits} is signed (a purchase is negative). */
        void onTrade(String playerId, String marketId, long netCredits);

        /** One mission claim the host's arbiter accepted. */
        void onMissionClaimed(String playerId);

        /** One consumed salvageable, past the world-delta ledger. */
        void onSalvageConsumed();

        /** One colony founded, past the colony ledger. */
        void onColonyFounded(String playerId);
    }

    private StatsSink statsSink;

    public void setStatsSink(StatsSink sink) {
        this.statsSink = sink;
    }

    /** Runs a stats hook when one is installed, swallowing whatever it throws. */
    private void tally(java.util.function.Consumer<StatsSink> hook) {
        StatsSink sink = statsSink;
        if (sink == null) {
            return;
        }
        try {
            hook.accept(sink);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop session stats hook failed", ex);
        }
    }

    @Override
    public void onBattleOccurred(boolean playerWon) {
        if (battleObserver != null) {
            battleObserver.onBattleOccurred(playerWon);
        }
    }

    @Override
    public void onPlayerEngagement(boolean playerWon, boolean playerOutBeforeEnd) {
        if (battleObserver == null) {
            return;
        }
        battleObserver.onPlayerEngagement(
                playerOutBeforeEnd ? "DISENGAGED" : (playerWon ? "WIN" : "LOSS"));
    }

    private void captureFactionRelationChanges() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            FactionAPI playerFaction = sector.getPlayerFaction();
            String playerFactionId = playerFaction == null ? null : playerFaction.getId();
            List<FactionAPI> factions = sector.getAllFactions();
            for (int i = 0; i < factions.size(); i++) {
                FactionAPI a = factions.get(i);
                if (playerFactionId != null && playerFactionId.equals(a.getId())) {
                    continue; // player standings ride REP_DELTA, not FACTION_REL_DELTA
                }
                for (int j = i + 1; j < factions.size(); j++) {
                    FactionAPI b = factions.get(j);
                    if (playerFactionId != null && playerFactionId.equals(b.getId())) {
                        continue;
                    }
                    float current = a.getRelationship(b.getId());
                    boolean known = factionRelations.isKnown(a.getId(), b.getId());
                    if (!known || factionRelations.relationship(a.getId(), b.getId()) != current) {
                        factionRelations.applyResult(a.getId(), b.getId(), current);
                        if (factionRelationsSeeded) {
                            send(CoopMessages.factionRelDelta(session.sessionId(), service.nextSeq(), now(),
                                    a.getId(), b.getId(), current));
                        }
                    }
                }
            }
            factionRelationsSeeded = true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture faction relation changes", ex);
        }
    }

    private void applyFactionRelDelta(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String factionA = payload.requiredString("factionA");
        String factionB = payload.requiredString("factionB");
        float resulting = payload.requiredFloat("resultingValue");
        factionRelations.applyResult(factionA, factionB, resulting);
        replayGuard.begin();
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                FactionAPI a = sector.getFaction(factionA);
                if (a != null) {
                    a.setRelationship(factionB, resulting);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply FACTION_REL_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied FACTION_REL_DELTA " + factionA + "/"
                + factionB + " -> " + resulting);
    }

    // ---- Mission/bar pool + claims ------------------------------------------------------------

    @Override
    public void onPlayerOpenedMarket(MarketAPI market, boolean cargoUpdated) {
        if (!isActive() || replayGuard.isReplaying() || market == null) {
            return;
        }
        // Phase 12c gap 2e: the host re-snapshots when the engine says the submarket plugins just
        // restocked. Without it a market the host reopened after a 30-day ship/weapon reroll kept
        // serving the guest the stock it had captured before the reroll.
        //
        // Safe against accelerated restock: broadcastMarketSnapshot re-enters
        // updateCargoPrePlayerInteraction with a zero-day sinceLastCargoUpdate, and vanilla's own
        // sub-unit guard refuses the fractional add, so the second call adds nothing. Gated on a live
        // connection so a host docking with nobody attached emits nothing.
        if (isHost() && cargoUpdated && service.isConnected()) {
            broadcastMarketSnapshot(market);
        }
        // Phase 24 M3: baseline the colony-management state so the close can diff against it. Both
        // roles do this -- either player manages the shared colonies from their own client.
        try {
            colonyMgmtDiff.onOpened(session.localPlayerId(), market);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Failed to baseline colony management state on market open", ex);
        }
        // Host-authoritative market contents are synced once, at open (never per-frame, so the trade
        // UI is never fought mid-transaction). The host's engine market IS the canonical source: the
        // guest asks the host for a snapshot and applies it to its own market once; thereafter both
        // sides apply the same per-transaction delta, so they stay consistent with no live re-sync.
        if (isGuest()) {
            // Phase 32 (P1-4): one MARKET_OPEN per dock. The second vanilla callback fires from
            // inside the core trade screen, and answering it lands a full strip-and-replace of the
            // black market and the storage locker under the player's hands -- the one case the sync
            // gate cannot cover, because the gate disables dock-dialog options and the player is
            // already past them. Nothing else happens on the repeat either: the hire baseline stays
            // (no second snapshot is coming to re-add a person hired in between, and the close diff
            // still catches it) and the gate keeps its original arming clock.
            if (market.getId() != null && market.getId().equals(marketOpenRequestedFor)) {
                CoopLog.debug(CoopCampaignReplicator.class,
                        "Coop MARKET_OPEN suppressed: this dock already asked for market="
                                + market.getId() + " (vanilla reports one open twice)");
                return;
            }
            marketOpenRequestedFor = market.getId();
            // Drop the hire baseline before asking for a fresh one. It is a claim generator: every
            // person still in it at market-close that is no longer hireable is reported to the host
            // as "the guest hired them", and the host deletes them from the canonical pool. A
            // baseline left over from an earlier snapshot (the host docking here, or a previous
            // visit) describes people this client's own OfficerManagerEvent may since have pruned,
            // so if the reply does not land before the screen closes the guest silently wipes the
            // host's pool. No snapshot, no claim.
            // ...but diff it first. The vanilla open callback fires more than once per dock session
            // (CampaignState.showInteractionDialog on the dock dialog, then the core Crew/Cargo
            // screen's own reportPlayerOpenedMarketAndCargoUpdated), and a hire made in between --
            // the comm directory is reachable from the dock dialog -- would otherwise never be
            // diffed at close, while the second snapshot re-added the hired person to the pool.
            if (appliedHireables.containsKey(market.getId())) {
                try {
                    reportHiresOnClose(market);
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopCampaignReplicator.class,
                            "Failed to diff hireable pool on market re-open", ex);
                }
            }
            appliedHireables.remove(market.getId());
            // Phase 32 addition A: a mirrored hidden base is the one market whose local id the
            // host's economy cannot resolve, so the request names the host's id for it. Identity for
            // everything else.
            String wireMarketId = marketIds.toWire(market.getId());
            send(CoopMessages.marketOpen(session.sessionId(), service.nextSeq(), now(),
                    wireMarketId, CoopMessages.SUBMARKET_ALL, session.localPlayerId()));
            CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_OPEN requested market="
                    + wireMarketId + localSuffix(market.getId(), wireMarketId));
            // Phase 20 M6: hold the trade screens shut until that reply lands. Only for a market that
            // actually has stock to be wrong about -- a procgen derelict never gets a snapshot back,
            // and there is nothing on its dialog for the gate to disable anyway.
            //
            // Hidden markets were excluded for the same reason, one step further along: a pirate
            // or Luddic-path base market is minted locally with Misc.genUID() (vanilla
            // PirateBaseIntel / LuddicPathBaseIntel), so the guest's mirrored copy carried an id the
            // host's economy could not resolve. The old predicate armed the gate on every dock at a
            // hidden base and held the shop shut for the full timeout with no snapshot ever on its
            // way.
            //
            // Phase 32 addition A: once CoopBaseAuthority has paired this base with the host's, a
            // snapshot *is* on its way, so the gate arms for it like any other market. An unmapped
            // hidden base -- a base the guest has not reconciled yet, or one the host does not have
            // -- keeps the old exclusion, because for that one nothing is coming back.
            if (hasSharedSubmarket(market)
                    && (!isHiddenMarket(market) || marketIds.isMappedLocal(market.getId()))) {
                marketSyncGate.onOpenRequested(market.getId(), now());
            }
        }
        // When the host opens, its engine market is already canonical; the guest (if it later opens
        // the same market) pulls it via MARKET_OPEN. Simultaneous same-market use is prevented by
        // the Phase 10 gate, whose WAN race Phase 18 closes (the per-submarket mutex that this line
        // used to promise was cancelled on 2026-08-20 — see the Phase 18 banner).
    }

    /**
     * Phase 18: the local player left a market screen. Pure forwarding — the observer (the pump)
     * uses it to confirm a rejected interaction's dialog is gone; Phase 24 will diff the colony
     * state here.
     */
    @Override
    public void onPlayerClosedMarket(MarketAPI market) {
        if (market == null) {
            return;
        }
        // Phase 32 (P1-4): the dock is over, so the next open of this market is a real one again.
        // Cleared unconditionally rather than only for a matching id: a close is a close.
        marketOpenRequestedFor = null;
        // Phase 12c gap 2d: there is no vanilla hire event, so the guest claims its hires here by
        // diffing the market's hireable set against the set the last snapshot applied.
        if (isGuest() && isActive() && !replayGuard.isReplaying()) {
            try {
                reportHiresOnClose(market);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to diff hireable pool on market close", ex);
            }
        }
        // Phase 24 M3: the colony-management diff. Runs before the observer call below so a close that
        // both ends an interaction and edited a colony reports the edit first.
        if (isActive() && !replayGuard.isReplaying()) {
            try {
                reportColonyMgmtOnClose(market);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class,
                        "Failed to diff colony management state on market close", ex);
            }
        }
        // Phase 20 M6: the dialog is gone, so there is nothing left to gate. A snapshot that arrives
        // after this just applies to a market nobody is looking at, which is the pre-existing model.
        if (marketSyncGate.onResolved(market.getId())) {
            CoopLog.info(CoopCampaignReplicator.class,
                    "Coop market sync gate dropped: the dialog closed before the snapshot arrived"
                            + " market=" + market.getId());
        }
        if (marketCloseObserver == null) {
            return;
        }
        String entityId = null;
        try {
            SectorEntityToken primary = market.getPrimaryEntity();
            entityId = primary == null ? null : primary.getId();
        } catch (RuntimeException | LinkageError ex) {
            // A procgen/local market may have no primary entity; the market id alone still helps.
        }
        marketCloseObserver.onMarketClosed(entityId, market.getId());
    }

    /** Observer of the vanilla market-close callback; wired to the Phase 18 reject bookkeeping. */
    public interface MarketCloseObserver {
        /**
         * @param entityId the market's primary entity id (the id the interaction gate claims), or
         *                 null when the market has no primary entity.
         * @param marketId the market's own id.
         */
        void onMarketClosed(String entityId, String marketId);
    }

    private MarketCloseObserver marketCloseObserver;

    public void setMarketCloseObserver(MarketCloseObserver observer) {
        this.marketCloseObserver = observer;
    }

    // ---- Phase 20 M6: market open-snapshot gate ------------------------------------------------

    private final CoopMarketSyncGate marketSyncGate = new CoopMarketSyncGate();

    /** Test/bridge seam: the pure gate state behind {@link #tickMarketSyncGate()}. */
    public CoopMarketSyncGate marketSyncGate() {
        return marketSyncGate;
    }

    /**
     * Per-frame (guest): hold the dock dialog's trade options shut while a {@code MARKET_SNAPSHOT} is
     * outstanding, so nothing can be bought against the guest's own un-synced roll and no snapshot can
     * land under an open trade screen. See {@link CoopMarketSyncGate} for why both are real defects at
     * WAN latency and why the gate must always time out.
     *
     * <p>The disable is re-asserted every frame rather than once, because the rule engine repopulates
     * the option panel on its own schedule (any {@code FireBest}/{@code MarketPostOpen} pass rebuilds
     * it) and a one-shot disable would silently come back enabled.
     */
    public void tickMarketSyncGate() {
        if (marketSyncGate.pendingMarketId() == null) {
            return;
        }
        if (!isGuest() || !isActive()) {
            // Re-enable first: clearing zeroes pendingMarketId, and every path that would put the
            // options back (the per-frame re-assert, the snapshot release) is gated on the gate
            // still being armed. A bare clear() therefore leaves whatever the last frame disabled
            // greyed out in a still-open dialog, forever.
            releaseMarketSyncGate("session/role lost");
            marketSyncGate.clear();
            return;
        }
        try {
            long nowMillis = now();
            if (marketSyncGate.pollTimedOut(nowMillis)) {
                CoopLog.warn(CoopCampaignReplicator.class,
                        "Coop market sync gate timed out after " + CoopMarketSyncGate.TIMEOUT_MILLIS
                                + " ms with no MARKET_SNAPSHOT market=" + marketSyncGate.pendingMarketId()
                                + "; opening the trade screens against the local stock");
                releaseMarketSyncGate("timeout");
                return;
            }
            if (!marketSyncGate.isBlocking(nowMillis)) {
                return;
            }
            OptionPanelAPI options = currentOptionPanel();
            if (options == null) {
                return;
            }
            boolean gated = setTradeOptionsEnabled(options, false);
            // Only speak when there was something to hold back. A dialog with no trade options (a
            // derelict, a colony info screen) is not being gated, so announcing a sync would be noise.
            if (gated && marketSyncGate.pollAnnounce(nowMillis)) {
                announceMarketSyncing();
            }
        } catch (RuntimeException | LinkageError ex) {
            // A gate that cannot reach the UI must fail open, not wedge the dialog. Failing open
            // means putting the options back, not just forgetting the gate: the disable above may
            // already have landed before the throw (announceMarketSyncing is the likely thrower),
            // and after clear() nothing re-enables them.
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply the coop market sync gate", ex);
            releaseMarketSyncGate("gate failure");
            marketSyncGate.clear();
        }
    }

    /** Re-enable whatever the gate disabled. Total: a missing dialog just means nothing to restore. */
    private void releaseMarketSyncGate(String reason) {
        try {
            OptionPanelAPI options = currentOptionPanel();
            if (options != null) {
                setTradeOptionsEnabled(options, true);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Failed to re-enable market trade options (" + reason + ")", ex);
        }
    }

    /** @return true when at least one trade option was present, i.e. the gate is actually holding. */
    private static boolean setTradeOptionsEnabled(OptionPanelAPI options, boolean enabled) {
        boolean any = false;
        for (String id : CoopMarketSyncGate.TRADE_OPTION_IDS) {
            if (options.hasOption(id)) {
                options.setEnabled(id, enabled);
                any = true;
            }
        }
        return any;
    }

    private static OptionPanelAPI currentOptionPanel() {
        SectorAPI sector = Global.getSector();
        CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
        InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
        return dialog == null ? null : dialog.getOptionPanel();
    }

    private void announceMarketSyncing() {
        SectorAPI sector = Global.getSector();
        CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
        InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
        TextPanelAPI text = dialog == null ? null : dialog.getTextPanel();
        if (text != null) {
            text.addPara(CoopMarketSyncGate.SYNCING_TEXT);
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop market sync gate holding trade options"
                + " market=" + marketSyncGate.pendingMarketId());
    }

    /**
     * Does this market have any submarket the snapshot replaces? Total; false on any failure.
     *
     * <p>Presence, not unlock state: the guest arms its sync gate off this, and whether the host
     * will actually ship a storage snapshot is the host's call (see {@link #snapshotTargets}). The
     * degenerate market — storage present, locked, and no shop at all — resolves by the gate's own
     * timeout, the same way a procgen derelict already does.
     */
    private static boolean hasSharedSubmarket(MarketAPI market) {
        try {
            if (market == null) {
                return false;
            }
            for (String specId : SHARED_SUBMARKETS) {
                if (market.hasSubmarket(specId)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * A market with no cross-client identity: hidden-base markets (pirate/Luddic path) are minted on
     * whichever client built the base, so the two never share an id. Total; treated as "not hidden"
     * on any failure, which just restores the old behaviour for that market.
     */
    private static boolean isHiddenMarket(MarketAPI market) {
        try {
            return market != null && market.isHidden();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** Host: a player opened a market; capture the canonical stock of every shared submarket. */
    private void handleMarketOpen(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        String marketId = marketIds.toLocal(CoopMessages.requiredPayloadString(message, "marketId"));
        String requestedSubmarketId = CoopMessages.requiredPayloadString(message, "submarketId");
        MarketAPI market = findMarket(marketId);
        if (market == null) {
            // Expected, not an anomaly: the guest opens uncolonized/procgen entities (derelicts,
            // survey targets, ruins) whose "market" is a local, unregistered MarketAPI with no
            // counterpart in the host's economy. There is nothing canonical to snapshot, so the
            // guest's own local one stands. Debug level so a routine exploration run does not spam
            // warnings.
            CoopLog.debug(CoopCampaignReplicator.class,
                    "Coop MARKET_OPEN skipped: no host-side market for id=" + marketId
                            + " (uncolonized/procgen entity; the guest keeps its local one)");
            return;
        }
        // Phase 20.5: a snapshot answers the player who opened the market. With one guest this is the
        // same wire bytes as a broadcast; with more, a market opened in one corner of the sector has
        // no business landing in everyone else's economy view.
        broadcastMarketSnapshot(market, message.senderId(), requestedSubmarketId);
    }

    /** Host: snapshot every shared submarket at this market and send them (host-local open). */
    private void broadcastMarketSnapshot(MarketAPI market) {
        broadcastMarketSnapshot(market, null, CoopMessages.SUBMARKET_ALL);
    }

    /**
     * Host: one {@code MARKET_SNAPSHOT} per shared submarket present at this market (Phase 32).
     *
     * <p>Every snapshot of the batch carries the same {@code submarketCount}, which is how the
     * guest's {@link CoopMarketSyncGate} knows the market as a whole is canonical: releasing the
     * trade options after the first would let the player into a screen whose black market or
     * storage locker is still their own engine's roll.
     *
     * @param toSenderId          the peer that asked, or null to broadcast (a host-local market open).
     * @param requestedSubmarketId {@link CoopMessages#SUBMARKET_ALL} for every shared submarket (what
     *                             a dock asks for), or one spec id to re-snapshot only that shop.
     */
    private void broadcastMarketSnapshot(MarketAPI market, String toSenderId, String requestedSubmarketId) {
        List<String> targets = snapshotTargets(market);
        if (!CoopMessages.SUBMARKET_ALL.equals(requestedSubmarketId)) {
            // A targeted request: honour it only if that shop is one this market actually shares.
            // Answering a request for local_resources (or a typo) with the open market's stock is the
            // class of silent substitution the allowlist exists to prevent.
            targets = targets.contains(requestedSubmarketId) ? List.of(requestedSubmarketId) : List.of();
            if (targets.isEmpty()) {
                CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_OPEN asked for submarket="
                        + requestedSubmarketId + " at market=" + market.getId()
                        + ", which is not a shared submarket here; nothing sent");
                return;
            }
        }
        if (targets.isEmpty()) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_OPEN: market=" + market.getId()
                    + " has no shared submarket to snapshot (storage is only shared once unlocked)");
            return;
        }
        int count = targets.size();
        for (String specId : targets) {
            // The host is canonical, so a shop must be *stocked* before it is canonical: a market the
            // host has never docked at has never had its stock generated, and snapshotting it would
            // hand the guest an empty shelf. See ensureSubmarketStocked (storage never rolls).
            ensureSubmarketStocked(market, specId);
            List<CoopMarketSync.StockItem> items = captureSubmarketStock(market, specId);
            if (Submarkets.SUBMARKET_OPEN.equals(specId)) {
                // The hireable pool is a property of the market, not of a submarket cargo, so it
                // rides exactly one snapshot of the batch rather than being replicated four times.
                items.addAll(captureHireablePool(market));
            }
            marketSync.applySnapshot(market.getId(), specId, items);
            String encoded = CoopMarketSync.encodeStock(items);
            if (!snapshotFitsAFrame(market, specId, items.size(), encoded)) {
                continue;
            }
            sendTo(toSenderId, CoopMessages.marketSnapshot(session.sessionId(), service.nextSeq(), now(),
                    market.getId(), specId, count, encoded));
            CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_SNAPSHOT market=" + market.getId()
                    + " submarket=" + specId + " of " + count
                    + " items=" + items.size() + " " + kindBreakdown(items));
        }
    }

    /**
     * Soft threshold at which an outbound {@code MARKET_SNAPSHOT} is loud about its size. Mirrors
     * {@code CoopNetService.WARN_FRAME_BYTES}; the transport warns once per message <em>type</em>,
     * which for a per-market snapshot is one warning for the whole session.
     */
    static final int SNAPSHOT_WARN_BYTES = 256 * 1024;

    /**
     * Hard cap, mirroring {@code CoopNetService.MAX_FRAME_BYTES}. Mirrored rather than referenced
     * because both transport constants are package-private to {@code coop.net} and this class must
     * not widen them; if that cap is ever changed, change this one with it. Past this the transport
     * drops the frame with a
     * WARN and continues, and nothing above it learns the snapshot never went — the guest's gate
     * then times out, its locker keeps whatever it had, and the two engines diverge silently.
     *
     * <p><b>No chunking (accepted limitation).</b> Splitting a submarket's listings across frames is
     * a protocol change; this build refuses the send instead and tells the host's player, so the
     * divergence is visible rather than silent. A locker that big is unreachable in ordinary play:
     * a fully specified capital hull encodes to a few KB, so the cap is thousands of hulls.
     */
    static final int SNAPSHOT_MAX_BYTES = 1024 * 1024;

    /**
     * Host: is this snapshot small enough to put on the wire? Warns near the transport's soft
     * threshold; refuses, logs at ERROR and posts a feed line past its hard cap (Phase 32, P2-8).
     */
    private boolean snapshotFitsAFrame(MarketAPI market, String specId, int itemCount, String encoded) {
        int bytes = encoded == null ? 0 : encoded.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < SNAPSHOT_WARN_BYTES) {
            return true;
        }
        String marketId = market == null ? "?" : market.getId();
        if (bytes < SNAPSHOT_MAX_BYTES) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_SNAPSHOT is large: market="
                    + marketId + " submarket=" + specId + " items=" + itemCount + " bytes=" + bytes
                    + " (soft threshold " + SNAPSHOT_WARN_BYTES + ", hard cap " + SNAPSHOT_MAX_BYTES
                    + "); there is no chunking, so past the cap it will not be sent at all");
            return true;
        }
        CoopLog.error(CoopCampaignReplicator.class, "Coop MARKET_SNAPSHOT refused: market="
                + marketId + " submarket=" + specId + " items=" + itemCount + " encodes to " + bytes
                + " bytes, past the " + SNAPSHOT_MAX_BYTES + " byte frame cap. It is not sent, so"
                + " the partner's copy of this inventory stays their own until it shrinks.");
        // A literal colour rather than Misc.getNegativeHighlightColor(): Misc's static initializer
        // reads a dozen values out of Global.getSettings(), so the first touch of that class outside
        // a live game leaves it permanently unusable for the rest of the JVM. Nothing on a snapshot
        // path is worth that.
        CoopFeed.post("Coop: " + specId + " at " + marketDisplayName(market)
                + " is too large to share (" + (bytes / 1024) + " KB)", FEED_WARNING_COLOR);
        return false;
    }

    /** Vanilla's negative-highlight red, spelled out; see {@link #snapshotFitsAFrame}. */
    private static final Color FEED_WARNING_COLOR = new Color(255, 110, 110);

    /** The market's display name for a player-facing line, falling back to its id. Total. */
    private static String marketDisplayName(MarketAPI market) {
        try {
            if (market == null) {
                return "an unknown market";
            }
            String name = market.getName();
            return name == null || name.isBlank() ? market.getId() : name;
        } catch (RuntimeException | LinkageError ex) {
            return "an unknown market";
        }
    }

    private String kindBreakdown(List<CoopMarketSync.StockItem> items) {
        Map<CoopMarketSync.ItemKind, Integer> byKind = new LinkedHashMap<>();
        for (CoopMarketSync.StockItem item : items) {
            byKind.merge(item.kind(), 1, Integer::sum);
        }
        return byKind.toString();
    }

    /**
     * Broadcast a captured mission/bar pool (host), along with the {@code BarEventManager} seed the
     * guest needs to shuffle it into the same shown subset ({@code 0} = not carrying one).
     */
    public void broadcastMissionPool(String marketId, List<CoopMissionBoardSync.Entry> entries, long barSeed) {
        if (!isHost() || !isActive()) {
            return;
        }
        missionBoard.applySnapshot(entries);
        send(CoopMessages.missionPoolSnapshot(session.sessionId(), service.nextSeq(), now(),
                marketId, CoopMissionBoardSync.encodePool(entries), barSeed));
    }

    /**
     * Forgets that the bar pool was already broadcast, so {@link #tickBarPool()} sends it again on
     * its next poll even though nothing about the pool changed (net-fix-7).
     *
     * <p>Called when the transport's outbound queue cap discarded a {@code MISSION_POOL_SNAPSHOT}.
     * The cap treats snapshots as safe to drop because a newer one follows, which is exactly what
     * {@link CoopBarPoolCapture#markChanged} guarantees will <em>not</em> happen: the signature is
     * unchanged, so the watcher sends nothing and the guest keeps rendering a bar the host no longer
     * has. The poll timer is cleared too, so the resend is one frame away rather than one poll
     * interval.
     */
    public void forceResendMissionPool() {
        barPoolCapture.reset();
        lastBarPoolPollMillis = 0L;
    }

    /**
     * Pulls the next player-reputation snapshot forward to the next tick (net-fix-7), for the same
     * reason as {@link #forceResendMissionPool()}. This one is on a timer rather than a content hash,
     * so it heals on its own — but {@code PLAYER_REP_SYNC_INTERVAL_MILLIS} of guest-side standings
     * drift is long enough to be visible in a dialog, and clearing a timestamp costs nothing.
     */
    public void forceResendPlayerRepSnapshot() {
        lastPlayerRepSyncMillis = 0L;
    }

    /**
     * Phase 12c host bar-pool watcher: poll the global portside pool, and on any membership, seed,
     * pin or <em>order</em> change push the whole ordered list to the guest.
     *
     * <p>Push, not request/response. The pool is sector-global rather than per-market, so there is
     * nothing to fetch on market open, and a player who clicks the bar option the same frame they
     * dock would beat a round trip anyway. The poll is cheap: a walk of a list that holds a handful of
     * events, reading one field each.
     */
    public void tickBarPool() {
        if (!isHost() || !isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastBarPoolPollMillis < BAR_POOL_POLL_INTERVAL_MILLIS) {
            return;
        }
        lastBarPoolPollMillis = nowMillis;
        List<CoopMissionBoardSync.Entry> entries = barPoolCapture.capture();
        // Null means "could not read the pool", which is not the same as "the pool is empty" — an
        // empty snapshot tells the guest to clear its bar, so it must only ever be a real reading.
        if (entries == null) {
            return;
        }
        // The manager seed rides with the pool: it is what BarCMD shuffles the pool with, so sending
        // one without the other still shows the two players different bars. It is part of the change
        // test for the same reason — vanilla re-rolls it on a timer of its own, and a re-roll over an
        // unchanged pool is still a divergence.
        Long barSeed = CoopBarSync.hostSeed();
        long seedValue = barSeed == null ? 0L : barSeed;
        if (!barPoolCapture.markChanged(entries, seedValue)) {
            return;
        }
        broadcastMissionPool(BAR_POOL_MARKET_ID, entries, seedValue);
        CoopLog.info(CoopCampaignReplicator.class, "Coop MISSION_POOL_SNAPSHOT bar offers="
                + entries.size() + " barSeed=" + (barSeed == null ? "unreadable" : barSeed));
    }

    private void applyMissionPool(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        List<CoopMissionBoardSync.Entry> entries = CoopMissionBoardSync.decodePool(
                payload.requiredString("pool"));
        missionBoard.applySnapshot(entries);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied MISSION_POOL_SNAPSHOT entries=" + entries.size());
        long barSeed = payload.requiredLong("barSeed");
        if (barSeed != 0L) {
            CoopBarSync.applySeed(barSeed);
        }
        // Bar offers are the one pool source that has a live engine counterpart to rebuild; contact
        // and bounty entries stay per-player by design and are model-only here. visibleEntriesFor
        // plus the injector's own claimed-entry filter keep a claimed offer out of the rebuilt pool,
        // but only because something now raises those claims: until 2026-09-04 nothing ever sent a
        // MISSION_CLAIM_REQUEST, claimsByMissionId stayed empty for the whole session, and this
        // filter was a no-op that let both players accept the same offer. The arbitration lives in
        // the host's arbitrate(); the trigger that feeds it is tickBarAcceptance().
        String playerId = session.localPlayerId();
        List<CoopMissionBoardSync.Entry> offers = playerId == null || playerId.trim().isEmpty()
                ? missionBoard.pool()
                : missionBoard.visibleEntriesFor(playerId);
        // Drain any pending local acceptance before the rebuild, then re-baseline after it: the
        // rebuild removes and re-adds pool events wholesale, and every one of those removals would
        // otherwise read as the player having accepted it.
        tickBarAcceptance();
        barPoolInjector.apply(offers);
        barAcceptanceWatcher.resync();
    }

    /**
     * Phase 12 first-come trigger: raise a claim for every bar offer the local player just accepted.
     *
     * <p>Cheap enough to run every frame — {@link CoopBarAcceptanceWatcher} compares id sets and only
     * touches the engine for an id that actually vanished. Runs on both roles: the host arbitrates
     * its own acceptance locally, the guest asks.
     */
    public void tickBarAcceptance() {
        if (!isActive()) {
            return;
        }
        List<String> accepted = barAcceptanceWatcher.poll();
        for (String missionId : accepted) {
            if (isHost()) {
                if (hostClaimMissionLocally(missionId)) {
                    CoopLog.info(CoopCampaignReplicator.class,
                            "Coop mission claimed locally id=" + missionId);
                } else {
                    // The guest's request reached arbitrate() first. Rare (the host arbitrates its
                    // own acceptance in-process) but possible across a WAN round trip.
                    rollbackMissionAcceptance(missionId, "host lost the race to " + partnerName());
                }
            } else if (isGuest()) {
                CoopLog.info(CoopCampaignReplicator.class, "Coop mission claim requested id=" + missionId);
                guestRequestMissionClaim(missionId);
            }
        }
    }

    private void hostHandleMissionClaim(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String missionId = payload.requiredString("missionId");
        String playerId = payload.requiredString("playerId");
        // Read before arbitrating: arbitrate() re-accepts the SAME player's repeat claim (it is
        // idempotent by design, so a resent request still gets an accept back), which means
        // accepted() alone is not a dedup. An unheld mission is.
        boolean freshClaim = missionBoard.claimHolder(missionId) == null;
        CoopMissionBoardSync.ClaimResult result = missionBoard.arbitrate(missionId, playerId);
        if (result.accepted()) {
            sendTo(message.senderId(), CoopMessages.missionClaimAccept(session.sessionId(),
                    service.nextSeq(), now(), missionId, playerId, result.hostSeq()));
            CoopLog.info(CoopCampaignReplicator.class, "Coop mission claim accepted missionId=" + missionId
                    + " playerId=" + playerId + " hostSeq=" + result.hostSeq());
            if (freshClaim && !playerId.equals(session.localPlayerId())) {
                // The offer is taken, so it must leave the host's own bar too. Filtering the guest's
                // snapshot is not enough: the host's engine pool is where the offers actually live,
                // and leaving it there let the host walk into the same bar and accept it a second
                // time. notifyWasInteractedWith is exactly the vanilla consume - it drops the event
                // from the pool and from active, and puts the creator on its accepted timeout.
                consumeLocalBarOffer(missionId);
            }
            if (freshClaim) {
                tally(sink -> sink.onMissionClaimed(playerId));
            }
        } else {
            sendTo(message.senderId(), CoopMessages.missionClaimReject(session.sessionId(),
                    service.nextSeq(), now(), missionId, result.rejectReason()));
            CoopLog.info(CoopCampaignReplicator.class, "Coop mission claim rejected missionId=" + missionId
                    + " requester=" + playerId + " " + result.rejectReason());
        }
    }

    /** Host-local mission acceptance: arbitrate then broadcast the accept to the guest. */
    public boolean hostClaimMissionLocally(String missionId) {
        if (!isHost() || !isActive()) {
            return false;
        }
        boolean freshClaim = missionBoard.claimHolder(missionId) == null;
        CoopMissionBoardSync.ClaimResult result = missionBoard.arbitrate(missionId, session.localPlayerId());
        if (result.accepted()) {
            send(CoopMessages.missionClaimAccept(session.sessionId(), service.nextSeq(), now(),
                    missionId, session.localPlayerId(), result.hostSeq()));
            barAcceptanceWatcher.dropRollbackHandle(missionId);
            // Same unheld-mission dedup as the remote path above: one tally per missionId, ever.
            if (freshClaim) {
                tally(sink -> sink.onMissionClaimed(session.localPlayerId()));
            }
        }
        return result.accepted();
    }

    /** Guest-local mission acceptance: request the claim from the host. */
    public void guestRequestMissionClaim(String missionId) {
        if (!isGuest() || !isActive()) {
            return;
        }
        send(CoopMessages.missionClaimRequest(session.sessionId(), service.nextSeq(), now(),
                missionId, session.localPlayerId()));
    }

    private void guestApplyMissionAccept(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopMissionClaim claim = new CoopMissionClaim(
                payload.requiredString("missionId"),
                payload.requiredString("playerId"),
                payload.requiredLong("hostSeq"));
        missionBoard.applyAccepted(claim);
        barAcceptanceWatcher.dropRollbackHandle(claim.missionId());
        if (!claim.acceptedByPlayerId().equals(session.localPlayerId())) {
            // The host took it. Filtering the next snapshot is not enough on its own: the offer is
            // sitting in this client's live pool right now, and the guest could walk into the bar and
            // accept it before the host's pool even changes.
            consumeLocalBarOffer(claim.missionId());
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop mission claim accepted id="
                + claim.missionId() + " playerId=" + claim.acceptedByPlayerId());
    }

    private void guestApplyMissionReject(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String missionId = payload.requiredString("missionId");
        String reason = payload.requiredString("reason");
        CoopLog.warn(CoopCampaignReplicator.class, "Coop mission claim rejected id=" + missionId
                + " " + reason);
        rollbackMissionAcceptance(missionId, reason);
    }

    /**
     * Undo a locally accepted bar mission the host refused the claim for, and tell the player why.
     *
     * <p><b>Best effort, and the gap is real.</b> A {@code HubMissionBarEventWrapper} keeps the
     * {@code HubMission} it built, and that object is reachable through the event reference
     * {@link CoopBarAcceptanceWatcher} retained when it detected the acceptance - {@code BarCMD}
     * cannot have aborted it in the meantime, because {@code abortMissions} only walks events still
     * in the pool and an accepted one has left it. So the intel entry, the sector script and the
     * mission's own {@code Abortable} changes can all be undone through public API. What cannot:
     * anything {@code BaseHubMission.accept} handed over before the intel was added -
     * {@code cargoOnAccept} stacks are in the player's hold and stay there. Non-mission offers (the
     * historian, commodity and planetary-shield events) have no handle to undo at all; those are
     * already-consummated transactions, so the player keeps the goods and only gets the message.
     *
     * <p>The message is posted either way. A silent rollback failure would leave a player holding a
     * mission their partner also holds, with no idea why the two diverged.
     */
    private void rollbackMissionAcceptance(String missionId, String reason) {
        CoopLog.warn(CoopCampaignReplicator.class, "Coop mission rollback id=" + missionId
                + " reason=" + reason);
        PortsideBarEvent handle = barAcceptanceWatcher.rollbackHandle(missionId);
        barAcceptanceWatcher.dropRollbackHandle(missionId);
        boolean undone = false;
        try {
            if (handle instanceof HubMissionBarEventWrapper wrapper) {
                undone = undoAcceptedMission(wrapper.getMission());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Coop mission rollback id=" + missionId + " threw while undoing the mission", ex);
        }
        if (!undone) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop mission rollback id=" + missionId
                    + " could not be undone (no live mission handle); the local player keeps whatever"
                    + " the offer already gave them");
        }
        CoopFeed.post(partnerName() + " already took that offer.", negativeColor());
    }

    /** Removes an accepted mission's intel, script and setup changes. True when it was undone. */
    private boolean undoAcceptedMission(HubMission mission) {
        if (mission == null) {
            return false;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return false;
        }
        replayGuard.begin();
        try {
            mission.abort();
            if (mission instanceof IntelInfoPlugin intel && sector.getIntelManager() != null) {
                sector.getIntelManager().removeIntel(intel);
            }
            if (mission instanceof EveryFrameScript script) {
                sector.removeScript(script);
            }
            return true;
        } finally {
            replayGuard.end();
        }
    }

    /**
     * Consume one bar offer out of this client's own portside pool, the way vanilla does when it is
     * accepted. Used when the <em>other</em> player won the claim, so the offer cannot be taken twice.
     */
    private void consumeLocalBarOffer(String missionId) {
        if (missionId == null || missionId.trim().isEmpty()) {
            return;
        }
        String id = missionId.trim();
        try {
            PortsideBarData data = PortsideBarData.getInstance();
            if (data == null || data.getEvents() == null) {
                return;
            }
            for (PortsideBarEvent event : new ArrayList<>(data.getEvents())) {
                if (event == null || !id.equals(event.getBarEventId())) {
                    continue;
                }
                BarEventManager manager = BarEventManager.getInstance();
                if (manager != null) {
                    manager.notifyWasInteractedWith(event);
                } else {
                    data.removeEvent(event);
                }
                // Our own removal, so it must not come back as "the local player accepted it".
                barAcceptanceWatcher.forget(id);
                CoopLog.info(CoopCampaignReplicator.class,
                        "Coop consumed local bar offer id=" + id + " (claimed by the other player)");
                return;
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Coop could not consume local bar offer id=" + id, ex);
        }
    }

    /** The feed's "bad news" colour, or null (plain text) outside a running game. */
    private static Color negativeColor() {
        try {
            return Misc.getNegativeHighlightColor();
        } catch (RuntimeException | LinkageError ex) {
            // Misc reads it out of Global.getSettings(); a message without a colour still lands.
            return null;
        }
    }

    /** The partner's display name for player-facing text, with a neutral fallback. */
    private String partnerName() {
        String name = session.remoteName();
        return name == null || name.trim().isEmpty() ? "Your partner" : name.trim();
    }

    // ---- Market contents + transactions -------------------------------------------------------
    //
    // Two ids run through every message in this section, and they are translated in exactly one
    // place each. Getting either direction wrong loses a ship, so both rules are stated here.
    //
    // 1. MARKET ID (CoopMarketIds, Phase 32 addition A). toWire on the way out (sendMarketTxn, the
    //    MARKET_OPEN send), toLocal on the way in (handleMarketOpen, hostApplyMarketTxn,
    //    applyMarketSnapshot). Identity for every market whose id agrees across the two engines,
    //    which is all of them except a mirrored pirate / Luddic-Path base.
    //
    // 2. FLEET-MEMBER ID (CoopMemberIds, Phase 32). A hull listed in a shared inventory travels
    //    under c_<originPlayerId>_<originMemberId>, never under a raw genUID. Vanilla mints member
    //    ids from a per-sector counter that both engines draw from, so a raw id stamped into the
    //    other engine's object graph is an id that engine will itself mint later -- a scheduled
    //    collision in the one place where a collision means "withdraw ship A, get ship B".
    //      - capture (captureSubmarketStock, reportShipDeltas) stamps wireId(localPlayerId, id)
    //        into both the StockItem.itemId and the CoopShipDetail.memberId, and the stamp is
    //        idempotent so a hull round-tripping guest -> host -> guest keeps one stable name;
    //      - a rebuilt member on the receiving engine is setId()'d to that wire id, which cannot
    //        collide with a genUID (the c_ prefix is not hex);
    //      - the originating engine's real ship keeps its own local id, so every match against a
    //        local roster -- withdrawal by id, the storage reconcile, the duplicate-add guard --
    //        goes through CoopMemberIds.matchesLocal rather than String.equals.

    @Override
    public void onPlayerMarketTransaction(PlayerMarketTransaction transaction) {
        if (replayGuard.isReplaying() || !isActive() || transaction == null
                || transaction.getMarket() == null) {
            return;
        }
        // The host's engine market is canonical and was already mutated by its own vanilla
        // transaction, so the host needs no coop action here. The guest reports each commodity delta;
        // the host applies it to its canonical market. Since both sides started identical at open and
        // apply the same delta, displayed quantities stay consistent without any live re-sync.
        String submarketId = submarketSpecId(transaction);
        if (!isGuest()) {
            // Phase 21 stats, host only: this is the host's own trade, and it is the only place the
            // credit value of one is available at all (the wire's MARKET_TXN carries per-item
            // deltas, not prices). Dedup guarantee: vanilla fires onPlayerMarketTransaction exactly
            // once per confirmed transaction, and the replay guard above has already excluded the
            // re-drive of a remote one.
            //
            // Storage is not a trade: parking your own cargo in a locker and taking it back out fires
            // this callback too, and tallying it would put a market you never traded at into the
            // "markets traded with" set. Every other submarket (open, black, military, local
            // resources) is a real trade and still counts.
            if (Submarkets.SUBMARKET_STORAGE.equals(submarketId)) {
                return;
            }
            String hostMarketId = transaction.getMarket().getId();
            long credits = (long) transaction.getCreditValue();
            tally(sink -> sink.onTrade(session.localPlayerId(), hostMarketId, credits));
            return;
        }
        String marketId = transaction.getMarket().getId();
        // Guest capture is fenced to the submarkets the host is canonical for (Phase 32's allowlist,
        // see {@link #submarketCargo(MarketAPI, String)}). The spec id is stamped on the wire, so the
        // host applies each line to the shop or locker it actually happened in -- before that stamp
        // existed the host applied everything to its open market, which is why a storage withdrawal
        // used to delete open-market stock and a deposit to invent some.
        if (submarketId == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop market transaction at market="
                    + marketId + " has no submarket; nothing reported, because there is no way to"
                    + " tell which of the host's inventories it belongs to");
            return;
        }
        if (!isSharedSubmarket(submarketId)) {
            // local_resources is a derived view of colony production, not a stocked inventory, and an
            // unknown submarket is one this build has never reasoned about. Info, not warn: this is a
            // routine and correct drop, not an anomaly.
            CoopLog.info(CoopCampaignReplicator.class, "Coop market transaction at market="
                    + marketId + " submarket=" + submarketId + " not reported; only "
                    + SHARED_SUBMARKETS + " are host-synced");
            return;
        }
        try {
            // Bought: item leaves the market (+qty removed from stock). Sold: it returns (-qty).
            // Storage reads the same two directions: taking a ship out of the locker is "bought"
            // (by member id), leaving one there is "sold" (with its full CoopShipDetail).
            reportCargoDeltas(marketId, submarketId, transaction.getBought(), +1);
            reportCargoDeltas(marketId, submarketId, transaction.getSold(), -1);
            reportShipDeltas(marketId, submarketId, transaction.getShipsBought(), +1);
            reportShipDeltas(marketId, submarketId, transaction.getShipsSold(), -1);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to report market transaction", ex);
        }
    }

    /**
     * The spec id of the shop a transaction happened in, or {@code null} when there is none or it
     * cannot be read. Total: a broken or half-built transaction must not throw out of the vanilla
     * callback, and a {@code null} here is handled by every caller as "unknown shop".
     */
    private static String submarketSpecId(PlayerMarketTransaction transaction) {
        try {
            SubmarketAPI submarket = transaction == null ? null : transaction.getSubmarket();
            return submarket == null ? null : submarket.getSpecId();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /** Report commodity/weapon/fighter deltas from one side of a transaction (sign +1 bought, -1 sold). */
    private void reportCargoDeltas(String marketId, String submarketId, CargoAPI cargo, int sign) {
        if (cargo == null) {
            return;
        }
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            StackRef ref = classify(stack);
            if (ref == null) {
                continue;
            }
            int qty = Math.round(stack.getSize()) * sign;
            if (qty != 0) {
                sendMarketTxn(marketId, submarketId, ref.kind(), ref.id(), qty);
            }
        }
    }

    /**
     * Report ship deltas, one line per hull, keyed by the fleet member's id.
     *
     * <p>Member id, not variant id: a listing is a specific hull with its own D-mods and CR (see
     * {@link CoopShipDetail}), and the ids match across clients because the guest reconstructs each
     * listing with {@code setId} from the host's snapshot. So a <b>bought</b> ship is stripped from
     * the host's shelf by id, and a ship <b>sold back</b> carries its full detail blob so the host
     * shelves the battered hull the player actually handed over rather than a pristine reroll.
     *
     * <p>Storage uses the identical two directions and needs the detail for the same reason, only
     * more so: the hull the partner deposits is the only copy there is.
     */
    private void reportShipDeltas(String marketId, String submarketId,
                                  List<PlayerMarketTransaction.ShipSaleInfo> ships, int sign) {
        if (ships == null) {
            return;
        }
        for (PlayerMarketTransaction.ShipSaleInfo info : ships) {
            CoopShipDetail detail = stampWireMemberId(
                    captureShipDetail(info == null ? null : info.getMember()));
            if (detail == null) {
                continue;
            }
            sendMarketTxn(marketId, submarketId, CoopMarketSync.ItemKind.SHIP, detail.memberId(), sign,
                    sign < 0 ? detail.encode() : "");
        }
    }

    /**
     * Re-stamps a freshly captured detail with its origin-namespaced wire id ({@link CoopMemberIds}).
     *
     * <p>The one place a hull's name crosses from "this engine's object graph" to "the wire", and it
     * is deliberately not folded into {@code captureShipDetail}: that method is static and shared
     * with the module recursion, which has no member id and no session to namespace one with.
     * Idempotent, so re-capturing a member this engine rebuilt from a snapshot does not stack a
     * second prefix on it.
     */
    private CoopShipDetail stampWireMemberId(CoopShipDetail detail) {
        if (detail == null) {
            return null;
        }
        String wireId = CoopMemberIds.wireId(session.localPlayerId(), detail.memberId());
        return wireId.equals(detail.memberId()) ? detail : detail.withMemberId(wireId);
    }

    private void sendMarketTxn(String marketId, String submarketId, CoopMarketSync.ItemKind kind,
                               String itemId, int qty) {
        sendMarketTxn(marketId, submarketId, kind, itemId, qty, "");
    }

    /**
     * The one place a {@code MARKET_TXN} is put on the wire, and therefore the one place the local
     * market id is translated to the host's (Phase 32 addition A). Every caller passes the id its own
     * engine knows the market by; identity for everything but a mirrored hidden base.
     */
    private void sendMarketTxn(String marketId, String submarketId, CoopMarketSync.ItemKind kind,
                               String itemId, int qty, String detail) {
        String wireMarketId = marketIds.toWire(marketId);
        send(CoopMessages.marketTxn(session.sessionId(), service.nextSeq(), now(),
                wireMarketId, submarketId, kind.name(), itemId, qty, 0f, session.localPlayerId(), detail));
        CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_TXN sent market=" + wireMarketId
                + localSuffix(marketId, wireMarketId)
                + " submarket=" + submarketId + " " + kind + ":" + itemId + " qty=" + qty);
    }

    /** {@code " (local=<id>)"} when a translation happened, empty when the two ids are the same. */
    private static String localSuffix(String localId, String wireId) {
        return localId == null || localId.equals(wireId) ? "" : " (local=" + localId + ")";
    }

    /** The receive-side mirror of {@link #localSuffix}: names the host id behind a local one. */
    private static String hostSuffix(String localId, String wireId) {
        return wireId == null || wireId.equals(localId) ? "" : " (host=" + wireId + ")";
    }

    /**
     * Host: apply one guest transaction line to the canonical submarket it happened in.
     *
     * <p><b>Ordering (Phase 32).</b> {@code MARKET_TXN} and {@code MARKET_OPEN} both ride the one
     * reliable TCP stream, which delivers in send order, and the pump applies what it drains in that
     * same order. So a guest deposit sent before the guest's next {@code MARKET_OPEN} is applied to
     * the host's storage <em>before</em> the snapshot for that open is built, and the guest gets its
     * own deposit back in the snapshot rather than a locker that has forgotten it. The one case that
     * crosses is a host-initiated re-snapshot (the Phase 12c gap 2e reroll) racing a deposit in
     * flight: the guest's view is then stale until its next open, and nothing is lost.
     */
    private void hostApplyMarketTxn(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        // Phase 32 (P1-3): the dedup check the old comment only argued for. MARKET_TXN survives the
        // drop edge and a detaching peer requeues a partially written frame, so the same line can
        // arrive twice across a reconnect -- and every apply here is additive against a locker.
        if (!recordMarketTxnApplied(message)) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_TXN ignored as a duplicate"
                    + " delivery sender=" + message.senderId() + " seq=" + message.seq());
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        // Identity on the host (its table is always empty), translated at the boundary all the same
        // so the direction of every market id in this class is stated rather than assumed.
        String marketId = marketIds.toLocal(payload.requiredString("marketId"));
        String submarketId = payload.requiredString("submarketId");
        if (!isSharedSubmarket(submarketId)) {
            // The sending guest already filters against this allowlist, so a line that gets here is a
            // stale build or a tampered peer. Refuse it at the boundary rather than let it fall
            // through to an accessor that would deny it anyway: the one thing that must never happen
            // is a delta finding some other submarket to land on.
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_TXN refused: submarket="
                    + submarketId + " at market=" + marketId + " is not a shared inventory");
            return;
        }
        CoopMarketSync.ItemKind kind = CoopMarketSync.ItemKind.valueOf(
                payload.requiredString("kind"));
        String itemId = payload.requiredString("itemId");
        int qty = (int) payload.requiredLong("qty");
        String detail = payload.requiredString("detail");
        String actingPlayerId = payload.requiredString("actingPlayerId");
        boolean isHire = CoopPersonDetail.roleOf(kind) != null;
        // Phase 32 (P3-12): the allowlist above says "one of the four shared inventories", which is
        // not the same fence for a hire. The hireable pool lives on the market and rides the
        // open-market snapshot only, so an OFFICER line stamped submarketId=storage would otherwise
        // pass the allowlist and then delete a person from the host's pool, ignoring the submarket
        // it claimed to happen in.
        if (isHire && !Submarkets.SUBMARKET_OPEN.equals(submarketId)) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_TXN refused: a " + kind
                    + " hire can only happen at " + Submarkets.SUBMARKET_OPEN + ", not submarket="
                    + submarketId + " at market=" + marketId);
            return;
        }
        // Phase 21 stats. Dedup is the (senderId, seq) ledger at the top of this method (Phase 32
        // P1-3), not the transport argument this comment used to make. Counted here, after every
        // refusal, so a line the host drops does not put a market into the player's trade history.
        //
        // Accepted v1 limitation, stated here so it is not read as a bug: the credit magnitude is
        // qty * unitPrice, and unitPrice has always been sent as 0f (sendMarketTxn) because the
        // sender diffs stack sizes and never sees a price. So a guest trade joins the
        // markets-traded-with set -- which is what the page's "markets traded with" column counts --
        // but contributes nothing to best-single-trade. Filling that in needs a price on the wire,
        // which is a protocol change this phase is not allowed to make.
        long netCredits = (long) (qty * payload.requiredFloat("unitPrice"));
        // Parking cargo in a locker is not a trade with the market; the host's own storage moves are
        // already excluded from the stats in onPlayerMarketTransaction, and the guest's must be too.
        if (!Submarkets.SUBMARKET_STORAGE.equals(submarketId)) {
            tally(sink -> sink.onTrade(actingPlayerId, marketId, netCredits));
        }
        // Keep the in-memory model in step (used by tests / future assertions).
        marketSync.applyTransaction(new CoopMarketSync.Transaction(marketId, submarketId, kind, itemId,
                qty, payload.requiredFloat("unitPrice"), detail));
        // Phase 32 (P0-1): a market this engine cannot resolve -- an unmapped mirrored hidden base
        // is the case that produced this finding. The guest holds a deposit until the base is
        // mapped, but a line that gets here anyway is a real loss, so it is named in full (submarket,
        // kind, item, quantity) rather than dying two frames later inside a cargo accessor. No
        // parking: replaying a stale cargo delta against a locker that has since been snapshotted is
        // its own way to lose a ship.
        MarketAPI targetMarket = findMarket(marketId);
        if (targetMarket == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_TXN dropped: no host-side market"
                    + " for id=" + marketId + " submarket=" + submarketId + " " + kind + ":" + itemId
                    + " qty=" + qty + (detail.isEmpty() ? "" : " (carrying a ship detail)")
                    + "; the line is lost");
            return;
        }
        // Phase 32 (P2-6): the guest paid for the locker and the STORAGE_UNLOCK poll has not caught
        // up yet. The host accepting a storage line while snapshotTargets still hides storage is
        // what puts the same hull in both lockers at once, so the acceptance itself is the unlock.
        if (Submarkets.SUBMARKET_STORAGE.equals(submarketId)
                && CoopStorageUnlock.unlock(Global.getSector(), targetMarket)) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop storage unlocked at market=" + marketId
                    + " by a guest transaction arriving before the STORAGE_UNLOCK poll");
        }
        // A hire is an availability removal on a second engine structure (the officer manager's pools),
        // not a cargo delta, so it routes past applyItemDeltaToEngine entirely. No credit deduction:
        // credits are per-player and the guest's own engine already charged the hiring bonus.
        boolean applied = isHire
                ? applyHireToEngine(marketId, itemId)
                : applyItemDeltaToEngine(marketId, submarketId, kind, itemId, qty, detail);
        // Only claim "applied" when the engine mutation ran; the previous unconditional log asserted
        // success over a silent no-op, which is how the propagation bug stayed invisible.
        if (applied) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop applied MARKET_TXN market=" + marketId
                    + " submarket=" + submarketId + " " + kind + ":" + itemId + " qty=" + qty);
        }
    }

    /**
     * Records a {@code MARKET_TXN} as applied. Returns false when this exact line has already been
     * applied on this host, i.e. the caller must not apply it again (Phase 32, P1-3).
     *
     * <p>Keyed {@code senderId#seq}: a sender's sequence numbers are unique within a session, and
     * the duplicate this guards against is a re-delivery of the <em>same</em> frame across a
     * reconnect edge, not two lines that happen to look alike. Two identical deposits deliberately
     * made twice by the player carry different seqs and both apply, which is the intended reading.
     *
     * <p>Bounded in insertion order to {@link #MARKET_TXN_LEDGER_MAX}: it only has to outlive one
     * reconnect grace, and an unbounded set would grow for the length of the campaign.
     */
    private boolean recordMarketTxnApplied(CoopMessages.Message message) {
        String key = (message.senderId() == null ? "" : message.senderId()) + "#" + message.seq();
        if (!appliedMarketTxns.add(key)) {
            return false;
        }
        while (appliedMarketTxns.size() > MARKET_TXN_LEDGER_MAX) {
            appliedMarketTxns.remove(appliedMarketTxns.iterator().next());
        }
        return true;
    }

    private void applyMarketSnapshot(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        // Phase 32 addition A: translated once, here, so everything downstream -- the in-memory
        // stock model, the engine apply and the sync gate, which was armed with the local id -- is
        // keyed the same way. Identity for every market except a mirrored hidden base.
        String wireMarketId = payload.requiredString("marketId");
        String marketId = marketIds.toLocal(wireMarketId);
        String submarketId = payload.requiredString("submarketId");
        int submarketCount = (int) payload.requiredLong("submarketCount");
        if (!isSharedSubmarket(submarketId)) {
            // Same boundary refusal as MARKET_TXN, and for a sharper reason: a snapshot apply is a
            // full replacement, so a snapshot naming a submarket this build does not share must not
            // reach any cargo at all.
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_SNAPSHOT refused: submarket="
                    + submarketId + " at market=" + marketId + " is not a shared inventory");
            return;
        }
        List<CoopMarketSync.StockItem> items = CoopMarketSync.decodeStock(
                payload.requiredString("stock"));
        marketSync.applySnapshot(marketId, submarketId, items);
        // One-shot apply to the guest's engine copy of that one submarket, so it shows the host's
        // canonical stock and nothing else does.
        boolean applied = applySnapshotToEngine(marketId, submarketId, items);
        if (Submarkets.SUBMARKET_OPEN.equals(submarketId)) {
            // The hireable pool lives on the market, not in a submarket cargo, so it applies even
            // when the guest has no materialized open-market cargo to replace -- and it rides the
            // open-market snapshot only, which is the one the host puts it on.
            applyHireablePool(findMarket(marketId), items);
        }
        // Phase 20 M6: the stock is canonical now, so the trade screens open. Ordering is load-bearing
        // -- the release happens after applySnapshotToEngine, never before, so there is no frame on
        // which the options are live and the cargo is still the guest's own roll. An apply that wrote
        // nothing leaves the gate armed: its own timeout is then the thing that opens the shop, and
        // the log must not claim a success that did not happen.
        //
        // Phase 32: and it only opens once *every* submarket of this open has landed, so the player
        // is never let into a screen whose black market or locker is still their own engine's.
        //
        // Phase 32 (P2-5): the count is the *host's* submarket set. A colony or rebuilt hidden base
        // whose submarket set differs per engine leaves the guest permanently one short, and the
        // shop then opens on the gate's 5 s timeout at that one market, every dock, with nothing in
        // the log pointing at why. A snapshot that arrived and was addressed to the pending market
        // counts; whether the engine wrote is a separate fact, and it is the one that gets named.
        if (!applied) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_SNAPSHOT wrote nothing:"
                    + " submarket=" + submarketId + " does not materialize at market=" + marketId
                    + hostSuffix(marketId, wireMarketId)
                    + " on this client (the host has it, this engine does not); counted toward the"
                    + " sync gate so the trade screens are not held for the full timeout");
        }
        if (marketSyncGate.onResolved(marketId, submarketId, submarketCount)) {
            releaseMarketSyncGate(applied ? "snapshot applied" : "snapshot batch complete");
        }
        if (!applied) {
            return;
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied MARKET_SNAPSHOT market=" + marketId
                + hostSuffix(marketId, wireMarketId)
                + " submarket=" + submarketId + " of " + submarketCount + " items=" + items.size());
    }

    /**
     * Guest: bring one submarket's stock into line with the host's canonical set.
     *
     * <p><b>Shops are replaced; the locker is reconciled (Phase 32).</b> For {@code open_market},
     * {@code black_market} and {@code generic_military} this is a full strip-and-rebuild, because
     * weapon/fighter/ship <em>types</em> are rolled independently per engine and merging quantities
     * would leave the guest's own roll unioned with the host's.
     *
     * <p>{@code storage} cannot be treated that way, and the old code that did was an amplifier
     * sitting under every other defect in this codec. The locker is not a shelf that rerolls in 30
     * days: the hull the partner parked exists nowhere else. A wholesale
     * {@code clearMothballedShips} meant that the depositor's own real fleet member was destroyed on
     * the next dock and replaced by a rebuild of a rebuild of its own blob -- so every fidelity gap
     * in {@link CoopShipDetail} applied twice, permanently, to a ship the player never sold; and
     * anything the host's capture happened to omit (one throw in one accessor) was deleted from the
     * guest's engine with no log and no way back.
     *
     * <p>So for storage: a local hull the snapshot still lists <em>and</em> whose own capture encodes
     * to the same blob is left strictly alone (same object, same identity, no rebuild); one the
     * snapshot lists differently is replaced; one the snapshot does not list is removed (the partner
     * withdrew it); one the snapshot lists and this engine does not have is built. Cargo stacks stay
     * set-semantics on both paths, because a stack of supplies is fungible and a stack of supplies
     * is all it is.
     *
     * @return {@code true} only when the write actually ran against a real cargo.
     */
    private boolean applySnapshotToEngine(String marketId, String submarketId,
                                          List<CoopMarketSync.StockItem> items) {
        MarketAPI market = findMarket(marketId);
        CargoAPI cargo = submarketCargo(market, submarketId);
        if (cargo == null) {
            // First dock at a market this client has never opened: BaseSubmarketPlugin builds the
            // submarket cargo lazily in getCargo(), so getCargoNullOk() is still null at snapshot
            // time (the dock dialog reports the open before anything stocks it). Bailing out here
            // wrote nothing at all and left the guest's own, unseeded roll to materialize under the
            // trade screen a moment later -- with the gate already released and "applied" logged.
            //
            // Stock it the way the host does before its own capture. That both materializes the
            // cargo and spends the plugin's restock windows (sinceLastCargoUpdate and sinceSWUpdate
            // are zeroed), so the engine's own updateCargoPrePlayerInteraction cannot roll fresh
            // weapons/fighters/ships on top of the host set once the trade UI opens. Plain
            // getCargo() would create the cargo but leave those windows open, i.e. re-introduce the
            // guest's own roll by a slower route.
            //
            // Storage never reaches here: submarketCargo materializes the locker with getCargo(),
            // because a locker has no roll to spend and a deposit must not be dropped.
            ensureSubmarketStocked(market, submarketId);
            cargo = submarketCargo(market, submarketId);
        }
        if (cargo == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_SNAPSHOT not applied to engine:"
                    + " no cargo for market=" + marketId + " submarket=" + submarketId);
            return false;
        }
        boolean isStorage = Submarkets.SUBMARKET_STORAGE.equals(submarketId);
        replayGuard.begin();
        try {
            // A snapshot is a full *replacement*, not a quantity-merge. Weapon/fighter/ship TYPES are
            // rolled independently per instance, so just setting the host's item quantities would
            // leave the guest's own roll behind (the host set unioned with the guest's). So strip the
            // guest's current weapons/fighters/ships and any commodity the host no longer stocks,
            // then add the host's canonical set back. After this the guest holds exactly the host set.
            //
            // Phase 32 (P3-11): the target quantities are summed per commodity id first. A submarket
            // cargo holding two stacks of the same commodity is not a shape vanilla produces (stacks
            // merge per commodity), but if one ever arrives, the second line must add to the first
            // rather than overwrite it -- setCommodityQuantity is set-semantics while everything
            // beside it is add-semantics, so the naive loop silently loses the earlier stack.
            Map<String, Integer> commodityTargets = new LinkedHashMap<>();
            for (CoopMarketSync.StockItem item : items) {
                if (item.kind() == CoopMarketSync.ItemKind.COMMODITY) {
                    commodityTargets.merge(item.itemId(), item.quantity(), Integer::sum);
                }
            }
            Set<String> snapshotCommodities = commodityTargets.keySet();
            for (CargoStackAPI stack : cargo.getStacksCopy()) {
                StackRef ref = classify(stack);
                if (ref == null) {
                    continue;
                }
                int size = Math.round(stack.getSize());
                switch (ref.kind()) {
                    case WEAPON -> cargo.removeWeapons(ref.id(), size);
                    case FIGHTER -> cargo.removeFighters(ref.id(), size);
                    // Specials are rolled per instance like weapons (which nanoforge, which core,
                    // which blueprint), so they are stripped wholesale and re-added from the host set.
                    case SPECIAL -> removeSpecial(cargo, ref.id(), size);
                    case COMMODITY -> {
                        if (!snapshotCommodities.contains(ref.id())) {
                            cargo.removeCommodity(ref.id(), size);
                        }
                    }
                    default -> { /* nothing */ }
                }
            }
            if (isStorage) {
                // A locker is never wiped. See the method javadoc: this removes only what the
                // snapshot no longer lists and rebuilds only what actually changed.
                reconcileStoredHulls(cargo, items, marketId);
            } else {
                clearMothballedShips(cargo);
            }
            for (Map.Entry<String, Integer> target : commodityTargets.entrySet()) {
                try {
                    // Commodities survive the strip (shared list), so set them to the target.
                    setCommodityQuantity(cargo, target.getKey(), target.getValue());
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply snapshot item "
                            + CoopMarketSync.ItemKind.COMMODITY + ":" + target.getKey(), ex);
                }
            }
            for (CoopMarketSync.StockItem item : items) {
                try {
                    switch (item.kind()) {
                        // Summed and applied above, so the loop does not set the same id twice.
                        case COMMODITY -> { /* handled by commodityTargets */ }
                        // Weapons/fighters/specials were stripped to zero, so just add the host's.
                        case WEAPON -> cargo.addWeapons(item.itemId(), item.quantity());
                        case FIGHTER -> cargo.addFighters(item.itemId(), item.quantity());
                        case SPECIAL -> addSpecial(cargo, item.itemId(), item.quantity());
                        // Storage hulls are the reconcile's business, not this loop's.
                        case SHIP -> {
                            if (!isStorage) {
                                addMothballedShipFromDetail(cargo, item.detail(), submarketId);
                            }
                        }
                        // Hireable people are not cargo; applyHireablePool handles them.
                        default -> { /* officers/mercs/admins */ }
                    }
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply snapshot item "
                            + item.kind() + ":" + item.itemId(), ex);
                }
            }
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply market snapshot to engine", ex);
            return false;
        } finally {
            replayGuard.end();
        }
    }

    /**
     * Guest: bring the storage locker's hulls into line with the host's listing set without wiping
     * it (Phase 32; the amplifier behind the whole ship-detail red team).
     *
     * <p>Four cases, decided per local member and then per unmatched listing:
     * <ol>
     *   <li><b>Unchanged</b> — the snapshot lists this member ({@link CoopMemberIds#matchesLocal})
     *       and the member's own capture, re-stamped with the listing's wire id so the id itself is
     *       not a difference, encodes to the same blob. The object is left strictly alone. This is
     *       the depositor's own ship on every dock after the deposit, which is the case that used to
     *       be destroyed and rebuilt from its own lossy round trip.</li>
     *   <li><b>Changed</b> — listed, but the blob differs (the partner refitted it, repaired it,
     *       renamed it). Removed and rebuilt from the host's blob, because the host is canonical.</li>
     *   <li><b>Gone</b> — not listed at all: the partner withdrew it. Removed.</li>
     *   <li><b>New</b> — listed and absent here: built and added.</li>
     * </ol>
     *
     * <p>A local member whose own capture fails is treated as "changed" rather than kept: an object
     * this engine cannot describe is one it cannot compare, and the host's blob is the better copy.
     */
    private void reconcileStoredHulls(CargoAPI cargo, List<CoopMarketSync.StockItem> items,
                                      String marketId) {
        List<CoopMarketSync.StockItem> listings = new ArrayList<>();
        for (CoopMarketSync.StockItem item : items) {
            if (item.kind() == CoopMarketSync.ItemKind.SHIP) {
                listings.add(item);
            }
        }
        FleetDataAPI ships = mothballedShips(cargo);
        if (ships == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop storage reconcile skipped: no mothballed"
                    + " roster at market=" + marketId + " and none could be initialised;"
                    + " " + listings.size() + " listed hull(s) not applied");
            return;
        }
        String localPlayerId = session.localPlayerId();
        Set<String> matchedListings = new HashSet<>();
        List<CoopMarketSync.StockItem> toBuild = new ArrayList<>();
        int kept = 0;
        int replaced = 0;
        int removed = 0;
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            CoopMarketSync.StockItem listing = null;
            for (CoopMarketSync.StockItem candidate : listings) {
                if (matchedListings.contains(candidate.itemId())) {
                    continue;
                }
                if (CoopMemberIds.matchesLocal(candidate.itemId(), memberIdOf(member), localPlayerId)) {
                    listing = candidate;
                    break;
                }
            }
            if (listing == null) {
                ships.removeFleetMember(member);
                removed++;
                continue;
            }
            matchedListings.add(listing.itemId());
            if (storedHullMatchesListing(member, listing)) {
                kept++;
                continue;
            }
            ships.removeFleetMember(member);
            toBuild.add(listing);
            replaced++;
        }
        for (CoopMarketSync.StockItem listing : listings) {
            if (!matchedListings.contains(listing.itemId())) {
                toBuild.add(listing);
            }
        }
        for (CoopMarketSync.StockItem listing : toBuild) {
            try {
                addMothballedShipFromDetail(cargo, listing.detail(), Submarkets.SUBMARKET_STORAGE);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply stored hull "
                        + listing.itemId() + " at market=" + marketId, ex);
            }
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop storage reconciled market=" + marketId
                + " listed=" + listings.size() + " kept=" + kept + " replaced=" + replaced
                + " removed=" + removed + " added=" + (toBuild.size() - replaced));
    }

    /** A member's id, or the empty string when it cannot be read. Total. */
    private static String memberIdOf(FleetMemberAPI member) {
        try {
            String id = member == null ? null : member.getId();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    /**
     * Is this local hull already exactly what the listing describes? Compares the member's own
     * capture against the incoming blob with the listing's wire id stamped on, so the origin
     * namespace is not read as a difference. False on any failure — an unreadable local object is
     * replaced by the host's copy rather than kept on a guess.
     */
    private boolean storedHullMatchesListing(FleetMemberAPI member, CoopMarketSync.StockItem listing) {
        try {
            String blob = listing.detail();
            if (blob == null || blob.isEmpty()) {
                return false;
            }
            CoopShipDetail local = captureShipDetail(member);
            return local != null && local.withMemberId(listing.itemId()).encode().equals(blob);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** Set a commodity stack to a target quantity via add/remove. */
    private void setCommodityQuantity(CargoAPI cargo, String commodityId, int target) {
        int delta = Math.round(target - cargo.getCommodityQuantity(commodityId));
        if (delta > 0) {
            cargo.addCommodity(commodityId, delta);
        } else if (delta < 0) {
            cargo.removeCommodity(commodityId, -delta);
        }
    }

    /**
     * Host/guest: change one canonical submarket's stock by a signed delta for any item kind.
     * Returns {@code true} only when the engine mutation actually ran, so callers do not claim
     * success on a no-op.
     *
     * <p>For the three shops this deliberately uses {@code getCargoNullOk()} and gives up when the
     * submarket cargo has not been materialized (the normal state for a market this client has never
     * docked at). Bare {@code getCargo()} is <em>not</em> the fix there: that accessor only creates
     * an empty cargo, so the delta would land on stock that was never generated.
     * {@link #ensureSubmarketStocked} is what materializes it properly, and it runs on the snapshot
     * path where it belongs. Making a guest purchase durable is a model problem regardless: shop
     * commodity stock is a stockpile the engine refills toward {@code getStockpileLimit} on every
     * interaction, so deltas do not survive. Tracked as Phase 12c gap 2e.
     *
     * <p>Storage is the exception and is materialized on demand (Phase 32): a locker holds exactly
     * what the players put in it, nothing regenerates, and dropping a deposit because this client
     * has never opened storage here would lose a ship for good.
     */
    private boolean applyItemDeltaToEngine(String marketId, String submarketId,
                                           CoopMarketSync.ItemKind kind, String itemId,
                                           int qty, String detail) {
        CargoAPI cargo = submarketCargo(marketId, submarketId);
        if (cargo == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_TXN not applied to engine: no"
                    + " materialized " + submarketId + " cargo for market=" + marketId
                    + " " + kind + ":" + itemId
                    + " qty=" + qty + " (this client has not docked there)");
            return false;
        }
        replayGuard.begin();
        try {
            addItemToEngine(cargo, submarketId, kind, itemId, qty, detail);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply market delta to engine "
                    + kind + ":" + itemId + " submarket=" + submarketId, ex);
            return false;
        } finally {
            replayGuard.end();
        }
    }

    // qty>0 means the buyer removed it from the market (stock decreases); qty<0 means it was sold back.
    // For storage the same two directions read as "took it out of the locker" and "left it there".
    private void addItemToEngine(CargoAPI cargo, String submarketId, CoopMarketSync.ItemKind kind,
                                 String itemId, int qty, String detail) {
        switch (kind) {
            case COMMODITY -> {
                if (qty > 0) {
                    cargo.removeCommodity(itemId, qty);
                } else {
                    cargo.addCommodity(itemId, -qty);
                }
            }
            case WEAPON -> {
                if (qty > 0) {
                    cargo.removeWeapons(itemId, qty);
                } else {
                    cargo.addWeapons(itemId, -qty);
                }
            }
            case FIGHTER -> {
                if (qty > 0) {
                    cargo.removeFighters(itemId, qty);
                } else {
                    cargo.addFighters(itemId, -qty);
                }
            }
            case SPECIAL -> {
                if (qty > 0) {
                    removeSpecial(cargo, itemId, qty);
                } else {
                    addSpecial(cargo, itemId, -qty);
                }
            }
            case SHIP -> {
                if (qty > 0) {
                    removeMothballedShipById(cargo, itemId, submarketId);
                } else {
                    addMothballedShipFromDetail(cargo, detail, submarketId);
                }
            }
            // Hires are an availability removal on the officer manager, not a cargo delta; they route
            // through applyHireToEngine before reaching here.
            default -> { /* officers/mercs/admins */ }
        }
    }

    // ---- SPECIAL stacks (Phase 12c gap 2c) ------------------------------------------------------
    //
    // A special is identified by SpecialItemData's (id, data) pair, which its equals() compares in
    // full, and removeItems matches by equality. Reconstructing an AI core's null data as "" -- or a
    // modspec's hullmod id as null -- yields an item that looks right and cannot be removed.

    private void addSpecial(CargoAPI cargo, String itemId, int quantity) {
        if (quantity <= 0) {
            return;
        }
        cargo.addSpecial(specialData(itemId), quantity);
    }

    private void removeSpecial(CargoAPI cargo, String itemId, int quantity) {
        if (quantity <= 0) {
            return;
        }
        cargo.removeItems(CargoAPI.CargoItemType.SPECIAL, specialData(itemId), quantity);
    }

    private static SpecialItemData specialData(String itemId) {
        return new SpecialItemData(CoopMarketSync.specialId(itemId), CoopMarketSync.specialData(itemId));
    }

    // ---- Mothballed ship listings ---------------------------------------------------------------

    /**
     * Rebuilds one listed hull from its {@link CoopShipDetail}, D-mods, refit, CR and all.
     *
     * <p>The variant is always {@code clone()}d before it is touched: {@code createFleetMember} can
     * hand back a shared stock variant, and mutating that would rewrite the hull for every ship in the
     * sector using it (vanilla does the same dance in {@code ShipRecoverySpecial}). Setting the source
     * to REFIT and clearing the original-variant link is what makes the copy a standalone,
     * independently-modifiable variant rather than a view onto the stock one — and it is also what
     * D-modding does, so a captured D-hull round-trips into the same shape it came from.
     *
     * <p><b>Storage never drops a deposit (Phase 32).</b> When the rebuild throws — a hull mod, hull
     * spec or variant this client cannot resolve — a shop listing is skipped, because the worst case
     * there is one hull missing from a shelf that rerolls in 30 days. A storage locker is the
     * opposite: the ship the partner parked exists nowhere else once their engine handed it over, so
     * a failed rebuild falls back to the base variant off {@link #createBaseMember} with a WARN
     * naming the member id. A pristine hull is a loss of D-mods and CR; a skipped one is a loss of
     * the ship.
     *
     * @param submarketId which inventory this listing is going into, which is what decides the
     *                    failure policy above. Passed down rather than re-derived: by the time the
     *                    cargo is in hand there is no way to ask it which submarket it belongs to.
     */
    private void addMothballedShipFromDetail(CargoAPI cargo, String encodedDetail, String submarketId) {
        boolean isStorage = Submarkets.SUBMARKET_STORAGE.equals(submarketId);
        if (encodedDetail == null || encodedDetail.isEmpty()) {
            // Phase 32 (ship-detail P0-1): a SHIP line with no blob names a hull that exists on the
            // sender and cannot be built here. Silent before; on a locker it is a ship that just
            // failed to arrive, so it is named.
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing carries no detail blob;"
                    + " nothing to rebuild submarket=" + submarketId
                    + (isStorage ? " (a stored hull did not make the trip)" : ""));
            return;
        }
        CoopShipDetail detail;
        try {
            detail = CoopShipDetail.decode(encodedDetail);
        } catch (RuntimeException ex) {
            // Nothing to fall back to: without a decoded detail there is not even a member id or a
            // hull to name, so this is a loss on both paths and the WARN is all that can be done.
            CoopLog.warn(CoopCampaignReplicator.class, "Malformed ship detail blob; listing skipped"
                    + " submarket=" + submarketId, ex);
            return;
        }
        FleetDataAPI ships = mothballedShips(cargo);
        if (ships == null) {
            return;
        }
        // Phase 32 (P1-2/P1-3 backstop): never let one id name two hulls in one roster. A duplicate
        // delivery, or a snapshot that re-lists a hull the reconcile already kept, would otherwise
        // add a second member under the same id -- and a withdrawal removes the *first* match, so
        // the twin stays in the locker forever and is re-published on every snapshot.
        if (rosterHolds(ships, detail.memberId())) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing member=" + detail.memberId()
                    + " is already in the " + submarketId + " roster; the duplicate add is skipped");
            return;
        }
        try {
            FleetMemberAPI member = createBaseMember(detail);
            if (member == null) {
                return;
            }
            applyShipDetail(member, detail);
            ships.addFleetMember(member);
        } catch (RuntimeException | LinkageError ex) {
            // A variant, hull spec or hull mod this client cannot resolve (a mod mismatch): never
            // crash the whole snapshot apply over one listing.
            if (!isStorage) {
                CoopLog.warn(CoopCampaignReplicator.class, "Could not rebuild mothballed ship member="
                        + detail.memberId() + " variant=" + detail.baseVariantId()
                        + "; shop listing skipped", ex);
                return;
            }
            CoopLog.warn(CoopCampaignReplicator.class, "Could not rebuild stored ship member="
                    + detail.memberId() + " variant=" + detail.baseVariantId()
                    + " at full fidelity; storing its base variant instead so the deposit is not lost",
                    ex);
            addBaseVariantToStorage(ships, detail);
        }
    }

    /**
     * Storage fallback for a hull whose full-fidelity rebuild threw: the plain base member, with the
     * CR from the detail if that much of it was readable. Refit, D-mods and name are gone — the
     * player gets a pristine hull back instead of their battered one — but the ship is still there,
     * which is the property a locker has to keep.
     */
    private void addBaseVariantToStorage(FleetDataAPI ships, CoopShipDetail detail) {
        try {
            FleetMemberAPI member = createBaseMember(detail);
            if (member == null) {
                CoopLog.warn(CoopCampaignReplicator.class, "Stored ship member=" + detail.memberId()
                        + " names neither a resolvable variant nor a resolvable hull on this client;"
                        + " the deposit is lost");
                return;
            }
            member.setId(detail.memberId());
            if (member.getRepairTracker() != null) {
                member.getRepairTracker().setMothballed(true);
                member.getRepairTracker().setCR(detail.baseCR());
            }
            ships.addFleetMember(member);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Could not store even the base variant of"
                    + " member=" + detail.memberId() + "; the deposit is lost", ex);
        }
    }

    /**
     * The fleet member a rebuilt listing starts from, or null when even the hull cannot be resolved.
     *
     * <p><b>Why the variant id is not enough on its own.</b> A variant id only names a spec while the
     * variant is a stock one. A ship the player refitted before selling it back carries a runtime
     * variant id that exists on that member and nowhere else, so
     * {@code createFleetMember(SHIP, thatId)} throws on the receiving client and the listing used to
     * be dropped outright — i.e. the seller's hull disappeared from the shared shelf at the next
     * snapshot, which is worse than the pristine-rebuild gap this codec was written to close. Vanilla
     * hits the same wall and answers it the same way ({@code impl/campaign/CoreScript.java:639-641}
     * falls back off {@code isStockVariant()}).
     *
     * <p>The empty variant off the hull spec is the backstop rather than {@code getOriginalVariant()}
     * because that one is documented "may or may not be set". Nothing is lost by starting from empty:
     * every field that makes the listing itself — hull spec, perma/s/refit/suppressed mods, weapons,
     * wings, vents, caps, CR — is re-applied on top by the caller regardless of what it starts from.
     */
    private FleetMemberAPI createBaseMember(CoopShipDetail detail) {
        if (Global.getSettings().doesVariantExist(detail.baseVariantId())) {
            return Global.getFactory().createFleetMember(FleetMemberType.SHIP, detail.baseVariantId());
        }
        ShipHullSpecAPI hull = detail.hullSpecId().isEmpty()
                ? null : Global.getSettings().getHullSpec(detail.hullSpecId());
        if (hull == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing member=" + detail.memberId()
                    + " names neither a known variant (" + detail.baseVariantId()
                    + ") nor a known hull (" + detail.hullSpecId() + "); skipped");
            return null;
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop ship listing member=" + detail.memberId()
                + " rebuilt from an empty " + hull.getHullId() + " variant (custom variant id "
                + detail.baseVariantId() + " is not a spec on this client)");
        return Global.getFactory().createFleetMember(FleetMemberType.SHIP,
                Global.getSettings().createEmptyVariant(detail.baseVariantId(), hull));
    }

    /**
     * Writes one decoded {@link CoopShipDetail} onto a freshly created member: the refit onto a
     * private copy of its variant, then the member-level state (id, name, mothballed CR, hull).
     *
     * <p>Split out of {@link #addMothballedShipFromDetail} so the rebuild can be unit-tested against
     * a proxied member without a game factory, and because Phase 32's module support needs the
     * variant half of it to recurse.
     *
     * <p>Order matters twice. The variant is installed before the hull fraction is written, because
     * {@code setVariant(v, false, true)} runs a stats update that re-derives the member's status (and
     * for a modular hull re-counts the per-module statuses); a hull fraction written first would be
     * thrown away. And the member id is written after the variant for the same reason the original
     * code did: nothing in the variant path needs it, and a listing that arrives without one is a
     * defect worth naming rather than a silent rename.
     */
    static void applyShipDetail(FleetMemberAPI member, CoopShipDetail detail) {
        if (member == null || detail == null || member.getVariant() == null) {
            return;
        }
        ShipVariantAPI variant = member.getVariant().clone();
        variant.setSource(VariantSource.REFIT);
        variant.setOriginalVariant(null);
        applyVariantDetail(variant, detail);

        member.setVariant(variant, false, true);
        if (detail.memberId().isEmpty()) {
            // Only modules legitimately have no member id, and a module never reaches this method.
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing variant="
                    + detail.baseVariantId() + " arrived with no member id; keeping the locally"
                    + " generated one, so a later per-member delta cannot address this listing");
        } else {
            member.setId(detail.memberId());
        }
        if (!detail.shipName().isEmpty()) {
            member.setShipName(detail.shipName());
        }
        if (member.getRepairTracker() != null) {
            member.getRepairTracker().setMothballed(true);
            member.getRepairTracker().setCR(detail.baseCR());
        }
        if (member.getStatus() != null) {
            // Phase 32: a locker has to hand a damaged ship back damaged. Base CR is the repair
            // tracker's; hull damage is the status's, and nothing else on the wire carries it.
            member.getStatus().setHullFraction(detail.hullFraction());
        }
    }

    /**
     * The variant half of the rebuild, applied in place to a variant the caller already cloned and
     * marked REFIT. Recurses for module slots, which is why it takes a variant rather than a member:
     * a module is a {@code ShipVariantAPI} hanging off its parent, with no fleet member of its own.
     */
    private static void applyVariantDetail(ShipVariantAPI variant, CoopShipDetail detail) {
        if (!detail.hullSpecId().isEmpty() && variant.getHullSpec() != null
                && !detail.hullSpecId().equals(variant.getHullSpec().getHullId())) {
            // The D-hull swap: DModManager.setDHull replaces the hull spec outright, so a listing
            // whose id says "hound" but whose spec says "hound_dhull" only survives if we do too.
            variant.setHullSpecAPI(Global.getSettings().getHullSpec(detail.hullSpecId()));
        }
        for (String modId : detail.suppressedMods()) {
            variant.addSuppressedMod(modId);
        }
        for (String modId : detail.permaMods()) {
            // Vanilla's DModManager order: a perma-mod is un-suppressed first, or the hull mod is
            // installed and inert.
            variant.removeSuppressedMod(modId);
            variant.addPermaMod(modId, detail.sMods().contains(modId));
        }
        for (String modId : detail.refitMods()) {
            variant.addMod(modId);
        }
        for (String modId : detail.sModdedBuiltIns()) {
            // Built-ins are already installed; s-modding one is an addPermaMod with the s-mod flag
            // (there is no dedicated setter, and the returned set is not documented as live). Best
            // effort: a built-in that fails to read back as s-modded is a small stat difference on
            // a shop hull, not a broken listing.
            try {
                variant.addPermaMod(modId, true);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.debug(CoopCampaignReplicator.class,
                        "Could not mark built-in hull mod as s-modded: " + modId);
            }
        }
        for (String slotId : new ArrayList<>(orEmptyList(variant.getNonBuiltInWeaponSlots()))) {
            variant.clearSlot(slotId);
        }
        for (Map.Entry<String, String> weapon : detail.weapons().entrySet()) {
            variant.addWeapon(weapon.getKey(), weapon.getValue());
        }
        for (Map.Entry<String, String> wing : detail.wings().entrySet()) {
            variant.setWingId(Integer.parseInt(wing.getKey()), wing.getValue());
        }
        variant.setNumFluxVents(detail.vents());
        variant.setNumFluxCapacitors(detail.caps());
        if (!detail.displayName().isEmpty()) {
            // A player-renamed variant ("Elite", or whatever the owner typed in the refit screen).
            variant.setVariantDisplayName(detail.displayName());
        }
        applyWeaponGroups(variant, detail);
        applyModules(variant, detail);
    }

    /**
     * Restores the owner's firing groups, or leaves vanilla's autogenerated ones when the blob has
     * none (an older listing, or a hull whose owner never touched the groups).
     *
     * <p>Slots that hold no weapon on this client are dropped and a group left with no slots is not
     * added, which is exactly what {@code CoreAutofitPlugin.doFit} does when it copies groups between
     * variants ({@code api_src/.../CoreAutofitPlugin.java:524-535}). That case only arises when a
     * weapon id did not resolve here — a mod mismatch, where the listing is already degraded — and an
     * empty group is a shape vanilla never builds, so it is not worth risking on the receiving side.
     * If everything drops out we fall back to autogeneration rather than ship a group-less variant.
     */
    private static void applyWeaponGroups(ShipVariantAPI variant, CoopShipDetail detail) {
        List<CoopShipDetail.WeaponGroup> groups = detail.weaponGroups();
        if (groups.isEmpty()) {
            variant.autoGenerateWeaponGroups();
            return;
        }
        List<WeaponGroupSpec> live = variant.getWeaponGroups();
        if (live == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing member=" + detail.memberId()
                    + " has weapon groups but the variant exposes none; autogenerating instead");
            variant.autoGenerateWeaponGroups();
            return;
        }
        int added = 0;
        try {
            live.clear();
            for (CoopShipDetail.WeaponGroup group : groups) {
                WeaponGroupSpec spec = new WeaponGroupSpec(group.alternating()
                        ? WeaponGroupType.ALTERNATING : WeaponGroupType.LINKED);
                spec.setAutofireOnByDefault(group.autofire());
                for (String slotId : group.slots()) {
                    if (variant.getWeaponId(slotId) != null) {
                        spec.addSlot(slotId);
                    }
                }
                if (!spec.getSlots().isEmpty()) {
                    variant.addWeaponGroup(spec);
                    added++;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Could not apply weapon groups to listing member="
                    + detail.memberId() + "; autogenerating instead", ex);
            variant.autoGenerateWeaponGroups();
            return;
        }
        if (added == 0) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing member=" + detail.memberId()
                    + " carried " + groups.size() + " weapon group(s) but none of their slots hold a"
                    + " weapon on this client; autogenerating instead");
            variant.autoGenerateWeaponGroups();
        }
    }

    /**
     * Rebuilds each module of a modular hull from its nested detail and hangs it back on the parent.
     *
     * <p>Phase 12c left this as an accepted gap and a shop could live with it; a shared storage
     * locker cannot, because a stored Prometheus Mk.II or a captured station came back with pristine
     * modules. Each module is a full {@link CoopShipDetail} in its own right, so the same
     * {@link #applyVariantDetail} runs on it — including its own modules, should a mod nest them.
     *
     * <p>A module that cannot be rebuilt is logged and left as whatever the base variant put in that
     * slot, which is the pre-Phase-32 behaviour for that one module rather than a lost ship.
     */
    private static void applyModules(ShipVariantAPI variant, CoopShipDetail detail) {
        if (detail.modules().isEmpty()) {
            return;
        }
        for (Map.Entry<String, CoopShipDetail> entry : detail.modules().entrySet()) {
            String slotId = entry.getKey();
            CoopShipDetail module = entry.getValue();
            try {
                ShipVariantAPI moduleVariant = baseModuleVariant(variant, slotId, module);
                if (moduleVariant == null) {
                    CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing member="
                            + detail.memberId() + " module slot=" + slotId + " names neither a known"
                            + " variant (" + module.baseVariantId() + ") nor a known hull ("
                            + module.hullSpecId() + "); left as the base variant's module");
                    continue;
                }
                moduleVariant.setSource(VariantSource.REFIT);
                moduleVariant.setOriginalVariant(null);
                applyVariantDetail(moduleVariant, module);
                variant.setModuleVariant(slotId, moduleVariant);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Could not rebuild module slot=" + slotId
                        + " of listing member=" + detail.memberId(), ex);
            }
        }
    }

    /**
     * A private, modifiable variant to build one module on, or null when nothing resolves.
     *
     * <p>Preference order is the same argument as {@link #createBaseMember}: the module the base
     * variant already put in that slot when it is the same variant (no lookup needed, and it is
     * already the right hull), then the stock variant by id, then that slot's existing module
     * whatever it is, then an empty variant off the hull spec. Always a {@code clone()} — vanilla's
     * own module refit does the same ({@code CoreAutofitPlugin.java:305-323}), because
     * {@code getModuleVariant} can hand back a shared stock variant and mutating it would rewrite
     * that module for every ship in the sector.
     */
    private static ShipVariantAPI baseModuleVariant(ShipVariantAPI parent, String slotId,
                                                    CoopShipDetail module) {
        ShipVariantAPI existing = parent.getModuleVariant(slotId);
        if (existing != null && module.baseVariantId().equals(existing.getHullVariantId())) {
            return existing.clone();
        }
        if (Global.getSettings().doesVariantExist(module.baseVariantId())) {
            ShipVariantAPI stock = Global.getSettings().getVariant(module.baseVariantId());
            if (stock != null) {
                return stock.clone();
            }
        }
        if (existing != null) {
            return existing.clone();
        }
        ShipHullSpecAPI hull = module.hullSpecId().isEmpty()
                ? null : Global.getSettings().getHullSpec(module.hullSpecId());
        return hull == null ? null
                : Global.getSettings().createEmptyVariant(module.baseVariantId(), hull);
    }

    /** Legacy variant-id path, still used by cargo pods (which key contents by variant id). */
    private void addMothballedShipsByVariant(CargoAPI cargo, String variantId, int count) {
        FleetDataAPI ships = mothballedShips(cargo);
        if (ships == null || variantId == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            try {
                cargo.addMothballedShip(FleetMemberType.SHIP, variantId, null);
            } catch (RuntimeException | LinkageError ex) {
                // Variant not resolvable on this client (e.g. a custom autofit variant); skip it
                // rather than crash. Stock variants reconstruct cleanly; this is the documented gap.
                CoopLog.warn(CoopCampaignReplicator.class, "Could not add mothballed ship variant="
                        + variantId, ex);
                return;
            }
        }
    }

    /** True when this roster already holds a member the wire id names ({@link CoopMemberIds}). */
    private boolean rosterHolds(FleetDataAPI ships, String wireMemberId) {
        if (ships == null || wireMemberId == null || wireMemberId.isEmpty()) {
            return false;
        }
        String localPlayerId = session.localPlayerId();
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            if (CoopMemberIds.matchesLocal(wireMemberId, memberIdOf(member), localPlayerId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the listing this wire member id names. There is no {@code getMemberWithId}, so the
     * members list is scanned, and the match is {@link CoopMemberIds#matchesLocal} rather than
     * {@code equals}: the receiving engine's rebuilt member carries the wire id verbatim, but the
     * <em>originating</em> engine still holds the real ship under its own local id.
     *
     * <p>Phase 32 (P2-7): the roster is reached through {@link #mothballedShips(CargoAPI)} and a
     * null one falls through to the WARN below rather than returning silently. A withdrawal arriving
     * at a locker that has never held a ship on this engine is precisely the drift the WARN exists
     * to name, and it was the one case that produced no log at all.
     */
    private void removeMothballedShipById(CargoAPI cargo, String memberId, String submarketId) {
        if (memberId == null) {
            return;
        }
        FleetDataAPI ships = mothballedShips(cargo);
        String localPlayerId = session.localPlayerId();
        if (ships != null) {
            for (FleetMemberAPI member : ships.getMembersListCopy()) {
                if (CoopMemberIds.matchesLocal(memberId, memberIdOf(member), localPlayerId)) {
                    ships.removeFleetMember(member);
                    return;
                }
            }
        }
        // A withdrawal that matches nothing (Phase 32): the two lockers have drifted, which the next
        // MARKET_OPEN corrects by replacing the guest's copy wholesale. Named loudly because on
        // storage it means the partner is holding a ship this engine believes it still has.
        CoopLog.warn(CoopCampaignReplicator.class,
                "Coop ship delta: no mothballed listing with member id=" + memberId
                        + " in submarket=" + submarketId + "; the next snapshot corrects it");
    }

    /**
     * The mothballed-ship roster, materializing it if the cargo has never held one.
     * {@code getMothballedShips()} returns null until {@code initMothballedShips} has run, and a
     * snapshot that lands on a fresh cargo would otherwise silently drop every ship listing.
     */
    private FleetDataAPI mothballedShips(CargoAPI cargo) {
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships != null) {
            return ships;
        }
        try {
            cargo.initMothballedShips(Factions.NEUTRAL);
            return cargo.getMothballedShips();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Could not init mothballed ship storage", ex);
            return null;
        }
    }

    /**
     * Empties a <em>shop</em>'s ship shelf before the host's roll is added back.
     *
     * <p><b>Never call this on {@code storage}.</b> A shop shelf rerolls in 30 days; a locker holds
     * the only copy of a hull that exists. See {@link #reconcileStoredHulls} for what storage does
     * instead, and {@link #applySnapshotToEngine} for why the difference is load-bearing.
     */
    private void clearMothballedShips(CargoAPI cargo) {
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships == null) {
            return;
        }
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            ships.removeFleetMember(member);
        }
    }

    // ---- World deltas (salvage / explore / construct / parley) --------------------------------

    /** Report a guest-driven world mutation up to the host (and let the host integrate it). */
    public void reportWorldDelta(CoopWorldDelta delta) {
        if (!isActive() || delta == null) {
            return;
        }
        send(CoopMessages.worldDelta(session.sessionId(), service.nextSeq(), now(),
                delta.entityId(), delta.kind().name(), delta.consumed(),
                delta.newStateJson(), delta.actingPlayerId()));
    }

    // ---- Phase 24 M1: colony raids + bombardments ----------------------------------------------

    @Override
    public boolean shouldCaptureRaidOutcome() {
        // The replay guard is load-bearing here, not defensive: applying a remote outcome re-drives
        // the same vanilla effects, and without the guard the applier's own listener would capture
        // them as a fresh act and bounce it back.
        return isActive() && !replayGuard.isReplaying();
    }

    @Override
    public String raidActingPlayerId() {
        return session.localPlayerId();
    }

    /**
     * Either player finished a raid or bombardment locally. Vanilla already applied it here, so this
     * only reports it; the ledger entry taken now is what makes the host's rebroadcast a no-op when
     * it comes back.
     */
    @Override
    public void onRaidOutcomeCaptured(CoopRaidOutcomeSync.Outcome outcome) {
        if (outcome == null || !isActive()) {
            return;
        }
        if (!raidLedger.apply(outcome)) {
            return;
        }
        send(CoopMessages.raidResult(session.sessionId(), service.nextSeq(), now(), outcome.encode()));
        CoopLog.info(CoopCampaignReplicator.class, "Coop captured RAID_RESULT " + outcome.kind()
                + " market=" + outcome.marketId() + " id=" + outcome.outcomeId()
                + " industries=" + outcome.industries().size()
                + " deficits=" + outcome.deficits().size() + " deciv=" + outcome.decivilized());
    }

    /**
     * <p><b>Why the deciv call lives here and not in {@link CoopRaidOutcomeSync}.</b> A saturation
     * bombardment that wipes a colony out is resolved entirely inside vanilla's {@code MarketCMD} on
     * whichever client ran the dialog — {@code DecivTracker.decivilize(market, true)} at
     * {@code MarketCMD.java:2638}, then {@code reportSaturationBombardmentFinished} at {@code :2652}.
     * On a <em>guest</em> that produces no {@code DECIV} world-delta at all (the deciv capture is
     * host-gated by design), so {@code RAID_RESULT} is the only report that leaves, and it has to be
     * self-sufficient: without this the host kept a colony the guest had already deleted and the
     * guest's next reconnect failed the world fingerprint. {@link CoopRaidOutcomeSync} stays
     * engine-free apart from the market writes it already owns, so the engine call belongs here.
     *
     * <p><b>Deliberately outside the replay guard.</b> {@code decivilizeMarket} is what makes the
     * host's own {@code ColonyDecivListener} fire, which is what broadcasts the normal host
     * {@code DECIV} delta onward — inert on the guest that already decivilized (its market is gone
     * from the economy), and the only thing that would reach a third client. The guard would swallow
     * that capture. It cannot echo back as a {@code RAID_RESULT} either: decivilizing fires no
     * hostile-act listener, and the raid ledger already holds this outcome.
     */
    private void handleRaidResult(CoopMessages.Message message) {
        CoopRaidOutcomeSync.Outcome outcome = CoopRaidOutcomeSync.decode(
                CoopMessages.requiredPayloadString(message, "outcome"));
        boolean firstApply = raidLedger.apply(outcome);
        if (firstApply) {
            replayGuard.begin();
            try {
                CoopRaidOutcomeSync.applyToEngine(outcome);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply RAID_RESULT", ex);
            } finally {
                replayGuard.end();
            }
            if (outcome.decivilized()) {
                try {
                    decivilizeMarket(outcome.marketId(), true, "RAID_RESULT");
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopCampaignReplicator.class,
                            "Failed to decivilize for RAID_RESULT market=" + outcome.marketId(), ex);
                }
            }
        }
        // The host owns the canonical market: it integrates the guest's report and rebroadcasts so
        // both clients converge. The originator's ledger entry kills the echo.
        if (isHost() && isActive()) {
            send(CoopMessages.raidResult(session.sessionId(), service.nextSeq(), now(),
                    outcome.encode()));
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop RAID_RESULT " + outcome.kind() + " market="
                + outcome.marketId() + " id=" + outcome.outcomeId() + " firstApply=" + firstApply);
    }

    // ---- Phase 24 M2: colony lifecycle ---------------------------------------------------------

    @Override
    public boolean shouldCaptureColonyLifecycle() {
        // Same reasoning as shouldCaptureRaidOutcome: applying a remote COLONY_FOUNDED re-drives the
        // market mutations locally, and without the guard our own listener would report them as a
        // fresh colonization and bounce it back.
        return isActive() && !replayGuard.isReplaying();
    }

    @Override
    public String colonyActingPlayerId() {
        return session.localPlayerId();
    }

    /**
     * Either player founded or abandoned a colony locally. Vanilla already did the work here, so this
     * only reports it; the ledger entry taken now is what makes the host's rebroadcast a no-op when it
     * comes back.
     */
    @Override
    public void onColonyLifecycleCaptured(CoopColonySync.Event event) {
        if (event == null || !isActive()) {
            return;
        }
        if (!colonyLedger.apply(event)) {
            return;
        }
        if (event.kind() == CoopColonySync.Kind.FOUNDED) {
            // Phase 21 stats. Dedup guarantee: the colonyLedger.apply() above is the same gate that
            // stops the host's rebroadcast echoing back into this method, so one founding counts once
            // whichever client did it.
            tally(sink -> sink.onColonyFounded(session.localPlayerId()));
        }
        send(event.kind() == CoopColonySync.Kind.FOUNDED
                ? CoopMessages.colonyFounded(session.sessionId(), service.nextSeq(), now(), event.encode())
                : CoopMessages.colonyAbandoned(session.sessionId(), service.nextSeq(), now(), event.encode()));
        CoopLog.info(CoopCampaignReplicator.class, "Coop captured COLONY_" + event.kind()
                + " planet=" + event.planetId() + " market=" + event.marketId()
                + " id=" + event.eventId() + " name=" + event.name() + " size=" + event.size()
                + " industries=" + event.industries().size());
    }

    /**
     * Per-frame drain of pending colonizations. Founding is snapshotted one frame after vanilla
     * reports it, so the colony is definitely finished before it is read — see
     * {@link CoopColonySync.ColonizationCapture}.
     */
    public void tickColonyLifecycle() {
        if (colonyCapture == null || !isActive()) {
            return;
        }
        try {
            colonyCapture.drainPending();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to drain coop colonizations", ex);
        }
    }

    private void handleColonyLifecycle(CoopMessages.Message message) {
        CoopColonySync.Event event = CoopColonySync.decode(
                CoopMessages.requiredPayloadString(message, "colony"));
        boolean firstApply = colonyLedger.apply(event);
        if (firstApply && event.kind() == CoopColonySync.Kind.FOUNDED) {
            // Phase 21 stats, same ledger and same guarantee as onColonyLifecycleCaptured: this is
            // the peer's founding arriving once, credited to the peer.
            tally(sink -> sink.onColonyFounded(event.actingPlayerId()));
        }
        if (firstApply) {
            replayGuard.begin();
            try {
                CoopColonySync.applyToEngine(event);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply COLONY_" + event.kind(), ex);
            } finally {
                replayGuard.end();
            }
        }
        // The host owns the canonical world: it integrates the guest's report and rebroadcasts so both
        // clients converge. The originator's ledger entry kills the echo.
        if (isHost() && isActive()) {
            send(event.kind() == CoopColonySync.Kind.FOUNDED
                    ? CoopMessages.colonyFounded(session.sessionId(), service.nextSeq(), now(), event.encode())
                    : CoopMessages.colonyAbandoned(session.sessionId(), service.nextSeq(), now(), event.encode()));
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop COLONY_" + event.kind() + " planet="
                + event.planetId() + " id=" + event.eventId() + " firstApply=" + firstApply);
    }

    // ---- Phase 24 M3: colony management --------------------------------------------------------

    /**
     * The local player left a colony screen. Ships the whole post-close management state when it
     * differs from the open-time baseline, and nothing at all when it does not — which is the common
     * case, because a player docks at their own colony to trade far more often than to build.
     */
    private void reportColonyMgmtOnClose(MarketAPI market) {
        sendColonyMgmt(colonyMgmtDiff.onClosed(session.localPlayerId(), market), "close");
    }

    /**
     * Phase 24 M3 change poll: the primary management capture.
     *
     * <p>Runs on every client, not just the host: either player can edit either colony (the two shared
     * a faction), and the channel has always been bidirectional. Divergence is not a risk here because
     * the payload is absolute and the {@link CoopColonyManagement.Poll} only speaks when the local
     * content actually moved off what the peer last confirmed.
     */
    public void tickColonyManagement() {
        if (!isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastColonyMgmtPollMillis < COLONY_MGMT_POLL_INTERVAL_MILLIS) {
            return;
        }
        lastColonyMgmtPollMillis = nowMillis;
        try {
            // Before the capture, not after: a retry that succeeds marks the market synced, so the
            // poll below sees a market that is in step rather than one still suppressed.
            retryPendingColonyMgmtApplies();
            for (CoopColonyManagement.State state
                    : colonyMgmtPoll.poll(session.localPlayerId(), playerColonies(), isHost())) {
                sendColonyMgmt(state, "poll");
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to poll colony management state", ex);
        }
    }

    /**
     * Re-drives the inbound reports whose apply failed. The case this pays for is a market caught
     * mid-teardown or mid-registration: a second or two later the same absolute state applies
     * cleanly. When the budget runs out the report is abandoned but the market stays suppressed --
     * the state this engine kept is no less stale for the retries having stopped.
     */
    private void retryPendingColonyMgmtApplies() {
        for (CoopColonyManagement.State pending : colonyMgmtPoll.pendingApplyRetries()) {
            if (applyColonyMgmt(pending)) {
                colonyMgmtPoll.markSynced(pending);
                CoopLog.info(CoopCampaignReplicator.class, "Coop COLONY_MGMT apply retry SUCCEEDED"
                        + " market=" + pending.marketId() + " id=" + pending.reportId());
            } else if (!colonyMgmtPoll.canRetryPendingApply(pending.marketId())) {
                CoopLog.warn(CoopCampaignReplicator.class, "Coop COLONY_MGMT apply GAVE UP after "
                        + CoopColonyManagement.PENDING_APPLY_ATTEMPTS + " attempts market="
                        + pending.marketId() + " id=" + pending.reportId()
                        + "; stale state stays suppressed");
            }
        }
    }

    /**
     * Every player-owned colony on the local engine. Hyperspace is excluded for the same reason the
     * orbit and skeleton scans exclude it: nothing colonizable lives there, and a market that somehow
     * reports it is not a colony either engine can reconcile.
     */
    private List<MarketAPI> playerColonies() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getEconomy() == null) {
            return List.of();
        }
        List<MarketAPI> colonies = new ArrayList<>();
        for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
            if (market == null || !market.isInEconomy() || !market.isPlayerOwned()
                    || market.isPlanetConditionMarketOnly()) {
                continue;
            }
            LocationAPI location = market.getContainingLocation();
            if (location == null || location.isHyperspace()) {
                continue;
            }
            colonies.add(market);
        }
        return colonies;
    }

    /**
     * One inbound report into the local engine, through the seam. Returns false when the engine kept
     * state the report replaced -- a thrown apply counts, because a report that blew up halfway is
     * exactly the case the caller must not record as synced.
     */
    private boolean applyColonyMgmt(CoopColonyManagement.State state) {
        replayGuard.begin();
        try {
            return colonyMgmtApply.test(state);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply COLONY_MGMT", ex);
            return false;
        } finally {
            replayGuard.end();
        }
    }

    /**
     * The single outbound path for both capture routes, so the ledger entry that kills the host's echo
     * and the known-synced hash that stops the poll re-sending are always taken together.
     */
    private void sendColonyMgmt(CoopColonyManagement.State state, String source) {
        if (state == null || !isActive()) {
            return;
        }
        // The ledger entry taken now is what makes the host's rebroadcast a no-op when it comes back.
        if (!colonyMgmtLedger.apply(state)) {
            return;
        }
        colonyMgmtPoll.markSynced(state);
        send(CoopMessages.colonyMgmt(session.sessionId(), service.nextSeq(), now(), state.encode()));
        CoopLog.info(CoopCampaignReplicator.class, "Coop captured COLONY_MGMT market="
                + state.marketId() + " id=" + state.reportId() + " via=" + source
                + " industries=" + state.industries().size() + " queue=" + state.queue().size()
                + " freePort=" + state.freePort());
    }

    private void handleColonyMgmt(CoopMessages.Message message) {
        CoopColonyManagement.State state = CoopColonyManagement.decode(
                CoopMessages.requiredPayloadString(message, "mgmt"));
        boolean firstApply = colonyMgmtLedger.apply(state);
        if (firstApply) {
            if (applyColonyMgmt(state)) {
                // Only now is this state what both engines hold. Marking it synced is what stops both
                // polls re-reporting an engine-driven transition that fired on both engines at once,
                // forever.
                colonyMgmtPoll.markSynced(state);
            } else {
                // The engine kept the state the peer has already moved off. Marking that synced would
                // make the next poll tick report it as a fresh change and roll their edit back, so the
                // market is suppressed until the apply lands or the engine gets there by itself.
                colonyMgmtPoll.markPendingApply(state);
                CoopLog.warn(CoopCampaignReplicator.class, "Coop COLONY_MGMT apply FAILED market="
                        + state.marketId() + " id=" + state.reportId()
                        + "; suppressing stale re-report");
            }
        }
        // A duplicate changes nothing: whatever the first delivery decided -- synced or suppressed --
        // is still the truth, and re-marking it synced here would quietly undo a suppression.
        // The host owns the canonical market: it integrates the guest's report and rebroadcasts so
        // both clients converge. The originator's ledger entry kills the echo.
        if (isHost() && isActive()) {
            send(CoopMessages.colonyMgmt(session.sessionId(), service.nextSeq(), now(), state.encode()));
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop COLONY_MGMT market=" + state.marketId()
                + " id=" + state.reportId() + " firstApply=" + firstApply);
    }

    // ---- Phase 24 M3: colony income ------------------------------------------------------------

    @Override
    public boolean shouldSplitColonyIncome() {
        return isActive() && !replayGuard.isReplaying();
    }

    /**
     * A month ended locally. Both engines run the same replicated colonies and each has just paid its
     * own player the whole colony net, so each deducts the half it does not keep — no credits cross
     * the wire. See {@link CoopColonyIncome} for why a transfer would pay 150%.
     */
    @Override
    public void onColonyMonthEnd(CoopColonyIncome.MonthTotals totals) {
        if (totals == null || !isActive()) {
            return;
        }
        CoopRewardSplitter.Split split = CoopRewardSplitter.splitCredits(totals.net());
        if (split.total() != 0L) {
            long deducted = CoopColonyIncome.deductFromLocalPlayer(split.remoteShare());
            queueIncomeBanner(CoopColonyIncome.splitBanner(split));
            CoopLog.info(CoopCampaignReplicator.class, "Coop colony income split total="
                    + split.total() + " kept=" + split.localShare() + " deducted=" + deducted
                    + " colonies=" + totals.colonyCount());
        } else if (!totals.isSilent()) {
            // Colonies exist but broke even. Nothing to move and nothing worth a banner for.
            CoopLog.info(CoopCampaignReplicator.class,
                    "Coop colony month ended at break-even across " + totals.colonyCount() + " colonies");
        }
        if (isHost()) {
            send(CoopMessages.colonyIncome(session.sessionId(), service.nextSeq(), now(),
                    totals.net(), totals.colonyCount()));
        } else {
            pendingLocalColonyTotals = totals;
            maybeLogColonyIncomeDrift();
        }
    }

    /**
     * Host&rarr;guest canonical figure, used for one thing: a drift line. Correcting from it would
     * mean transferring credits, which is the design the local-half model replaces.
     */
    private void handleColonyIncome(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        pendingHostColonyNet = payload.requiredFloat("netCredits");
        pendingHostColonyCount = payload.requiredLong("colonyCount");
        maybeLogColonyIncomeDrift();
    }

    /**
     * The two halves of the comparison arrive in either order — the guest's own economy stepper and
     * the host's message are not sequenced against each other — so the line is logged once both are
     * in hand, and both are then cleared so the next month starts fresh.
     */
    private void maybeLogColonyIncomeDrift() {
        if (pendingHostColonyNet == null || pendingLocalColonyTotals == null) {
            return;
        }
        CoopLog.info(CoopCampaignReplicator.class, CoopColonyIncome.driftLine(
                pendingLocalColonyTotals, pendingHostColonyNet, pendingHostColonyCount));
        pendingHostColonyNet = null;
        pendingHostColonyCount = 0L;
        pendingLocalColonyTotals = null;
    }

    private void queueIncomeBanner(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        while (pendingIncomeBanners.size() >= MAX_PENDING_INCOME_BANNERS) {
            pendingIncomeBanners.poll();
        }
        pendingIncomeBanners.add(text);
    }

    /**
     * Per-frame flush of month-end banners. Queued rather than posted from the month-end callback for
     * the same reason the battle bridge queues its own: a frame can have no {@link CampaignUIAPI} at
     * all (load, teardown), and posting to a half-built UI is not worth the risk.
     */
    public void tickColonyIncome() {
        if (pendingIncomeBanners.isEmpty()) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
            if (ui == null) {
                return;
            }
            String banner;
            while ((banner = pendingIncomeBanners.poll()) != null) {
                try {
                    ui.addMessage(banner);
                } catch (RuntimeException | LinkageError ignored) {
                    // Banner is best-effort; the split itself already happened.
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to post a coop colony income banner", ex);
        }
    }

    // ---- Phase 24 M3: expedition warnings ------------------------------------------------------

    /**
     * Host: scan for threats aimed at player colonies and broadcast the set on change. Guest:
     * re-reconcile its mirrored warning intel on a slow tick, which also refreshes each entry's
     * staleness timer.
     */
    public void tickExpeditionWarnings() {
        if (!isActive() || !service.isConnected()) {
            return;
        }
        try {
            if (isHost()) {
                tickExpeditionWarningHost();
            } else if (isGuest()) {
                tickExpeditionWarningGuest();
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to sync coop expedition warnings", ex);
        }
    }

    private void tickExpeditionWarningHost() {
        long nowMillis = now();
        if (nowMillis < nextWarningPollAtMillis) {
            return;
        }
        nextWarningPollAtMillis = nowMillis + CoopExpeditionWarningSync.HOST_POLL_INTERVAL_MILLIS;
        List<CoopExpeditionWarning> warnings = CoopExpeditionWarningSync.captureHostWarnings();
        if (warnings == null) {
            // "No reading this poll", not "no threats". Broadcasting the resulting empty set would
            // tell the guest to drop every warning it is showing.
            return;
        }
        String hash = CoopExpeditionWarning.setHash(warnings);
        if (hash.equals(lastWarningSetHash)) {
            return;
        }
        lastWarningSetHash = hash;
        send(CoopMessages.expeditionWarning(session.sessionId(), service.nextSeq(), nowMillis,
                CoopExpeditionWarning.encodeSet(warnings)));
        CoopLog.info(CoopCampaignReplicator.class,
                "Coop sent EXPEDITION_WARNING warnings=" + warnings.size());
    }

    private void tickExpeditionWarningGuest() {
        if (!desiredWarningsReceived) {
            return;
        }
        long nowMillis = now();
        if (nowMillis < nextWarningReconcileAtMillis) {
            return;
        }
        nextWarningReconcileAtMillis =
                nowMillis + CoopExpeditionWarningSync.GUEST_RECONCILE_INTERVAL_MILLIS;
        IntelManagerAPI intel = intelManager(Global.getSector());
        if (intel == null) {
            return;
        }
        replayGuard.begin();
        try {
            CoopExpeditionWarningSync.Summary summary = CoopExpeditionWarningSync.apply(
                    new CoopExpeditionWarningSync.SectorWarningWorld(intel), desiredWarnings);
            if (!summary.isNoOp()) {
                CoopLog.info(CoopCampaignReplicator.class,
                        "Coop reconciled expedition warnings " + summary);
            }
        } finally {
            replayGuard.end();
        }
    }

    /**
     * Stores an inbound set. Deliberately does not reconcile here: inbound dispatch runs early in the
     * pump frame, and the same session-start ordering trap Phase 13 hit with {@code BASE_SET} (a set
     * applied before the session edge, then wiped by the reset that followed) applies equally.
     */
    private void handleExpeditionWarning(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        desiredWarnings = CoopExpeditionWarning.decodeSet(
                CoopMessages.requiredPayloadString(message, "warnings"));
        desiredWarningsReceived = true;
        // Reconcile on the very next tick rather than waiting out the low-rate interval.
        nextWarningReconcileAtMillis = 0L;
        CoopLog.info(CoopCampaignReplicator.class,
                "Coop received EXPEDITION_WARNING warnings=" + desiredWarnings.size());
    }

    /**
     * Session (re)start or teardown: forget the last-sent hash so the next host tick rebroadcasts the
     * full set, and let the guest reconcile immediately.
     *
     * <p>The guest's stored desired set deliberately survives this, for the reason Phase 13 recorded:
     * the host rebroadcasts on its own session edge, so a stale set is short-lived, but a wiped set is
     * unrecoverable until the host's hash happens to change.
     */
    private void resetExpeditionWarningStreams() {
        nextWarningPollAtMillis = 0L;
        nextWarningReconcileAtMillis = 0L;
        lastWarningSetHash = "";
    }

    private void clearMirroredExpeditionWarnings(SectorAPI sector) {
        try {
            IntelManagerAPI intel = intelManager(sector);
            if (intel == null) {
                return;
            }
            int cleared = new CoopExpeditionWarningSync.SectorWarningWorld(intel).clearAll();
            if (cleared > 0) {
                CoopLog.info(CoopCampaignReplicator.class,
                        "Coop cleared " + cleared + " mirrored expedition warnings on session end");
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Failed to clear mirrored coop expedition warnings", ex);
        }
    }

    private static IntelManagerAPI intelManager(SectorAPI sector) {
        SectorAPI resolved = sector == null ? Global.getSector() : sector;
        return resolved == null ? null : resolved.getIntelManager();
    }

    private void handleWorldDelta(CoopMessages.Message message) {
        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopWorldDelta delta = new CoopWorldDelta(
                payload.requiredString("entityId"),
                CoopWorldDelta.Kind.valueOf(payload.requiredString("kind")),
                Boolean.parseBoolean(payload.requiredString("consumed")),
                payload.requiredString("newStateJson"),
                payload.requiredString("actingPlayerId"));
        // Direction check, ahead of the ledger so a refused delta leaves no trace that would make a
        // later legitimate one look like a duplicate. Only DECIV is host-only, and the host is the
        // only side that can enforce it (a guest must still apply the host's).
        if (isHost() && delta.kind().hostOnly()) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop refused host-only WORLD_DELTA "
                    + delta.kind() + " entity=" + delta.entityId() + " from "
                    + delta.actingPlayerId() + ": that kind never travels guest->host");
            return;
        }
        boolean firstApply = worldLedger.apply(delta);
        if (firstApply && delta.consumed()) {
            // Phase 21 stats. Dedup guarantee: worldLedger.apply() returns true exactly once per
            // (entityId, kind) -- it is the ledger that kills the host's own rebroadcast echo below,
            // so counting behind it cannot double-count a salvage either client reported.
            tally(StatsSink::onSalvageConsumed);
        }
        if (firstApply) {
            replayGuard.begin();
            try {
                applyWorldDeltaToEngine(delta);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply WORLD_DELTA", ex);
            } finally {
                replayGuard.end();
            }
        }
        // Drop the entity from the local salvage baseline so applying this remote consume isn't
        // re-detected by our own watcher next tick as a fresh local salvage.
        trackedSalvageables.remove(delta.entityId());
        // Host integrates the guest report into authoritative state and rebroadcasts so both clients
        // converge; idempotency on the ledger means the echo is a no-op on the originator.
        if (isHost() && isActive()) {
            send(CoopMessages.worldDelta(session.sessionId(), service.nextSeq(), now(),
                    delta.entityId(), delta.kind().name(), delta.consumed(),
                    delta.newStateJson(), delta.actingPlayerId()));
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop WORLD_DELTA " + delta.kind() + " entity="
                + delta.entityId() + " consumed=" + delta.consumed() + " firstApply=" + firstApply);
    }

    private void applyWorldDeltaToEngine(CoopWorldDelta delta) {
        switch (delta.kind()) {
            case SPAWN -> {
                applySpawnToEngine(delta);
                return;
            }
            case DECIV -> {
                applyDecivToEngine(delta);
                return;
            }
            case OBJECTIVE_OWNERSHIP -> {
                applyObjectiveOwnershipToEngine(delta);
                return;
            }
            case GATE_ACTIVATED -> {
                applyGateStateToEngine(delta);
                return;
            }
            case SURVEY -> {
                applySurveyLevelToEngine(delta);
                return;
            }
            case RUINS_EXPLORED -> {
                applyRuinsExploredToEngine(delta);
                return;
            }
            case STORAGE_UNLOCK -> {
                // Phase 32 addition A: the entity id is a market id, so it needs the same
                // translation the MARKET_* messages get. An unmapped hidden base falls through as
                // itself and is flagged under the host's id; onMarketIdMapped moves that flag the
                // moment CoopBaseAuthority pairs the base.
                storageUnlockSync.applyRemote(marketIds.toLocal(delta.entityId()));
                return;
            }
            case COMMISSION -> {
                // Host-only, so this only ever runs on a guest: handleWorldDelta refuses a
                // guest-originated COMMISSION on the host before it reaches the engine at all.
                commissionSync.applyRemote(delta.newStateJson());
                return;
            }
            default -> {
                // Fall through to the consume path below.
            }
        }
        if (!delta.consumed()) {
            return;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getHyperspace() == null) {
            return;
        }
        // Remove the consumed entity wherever it lives so it cannot be re-looted on this client.
        // Resolved by coop id first for replicated entities, whose engine ids differ per client.
        SectorEntityToken entity = findConsumeTarget(sector, delta.entityId());
        if (entity != null && entity.getContainingLocation() != null) {
            entity.getContainingLocation().removeEntity(entity);
        }
    }

    /**
     * The entity a {@code CONSUME} delta is allowed to remove, or null.
     *
     * <p>{@link #findEntityForDelta} resolves an engine id through {@code sector.getEntityById},
     * which answers for <em>anything</em> — a fleet, a planet, a station, a jump point. Engine ids
     * are only stable across clients for entities that came out of worldgen: everything the engine
     * mints at runtime (post-battle wrecks, debris fields, mission tokens) takes its id from that
     * client's own {@code CampaignEngine} counter, so the same id names different objects on the two
     * clients. Removing whatever answers to it deletes an unrelated object out of the authoritative
     * world, and the host's rebroadcast makes that permanent.
     *
     * <p>The guard is the symmetric check: the sender reported an id that <em>its</em>
     * {@link #consumeKeyIfTracked} produced, so a local target that does not produce the same id is
     * not the thing that was salvaged. That structurally excludes fleets (Phase 9 owns those),
     * planets and jump points, and it refuses an engine-id match that lands on a coop-replicated
     * mirror (which keys on its coop id, never its engine id).
     */
    private SectorEntityToken findConsumeTarget(SectorAPI sector, String entityId) {
        SectorEntityToken entity = findEntityForDelta(sector, entityId);
        if (entity == null) {
            return null;
        }
        if (!entityId.equals(consumeKeyIfTracked(entity))) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop WORLD_DELTA CONSUME ignored: local"
                    + " entity " + entityId + " is not a consumable this client tracks (runtime-minted"
                    + " engine ids differ per client)");
            return null;
        }
        return entity;
    }

    // ---- Phase 13 skeleton mutations (DECIV / OBJECTIVE_OWNERSHIP / GATE_ACTIVATED) ------------

    /**
     * Host: a market decivilized. Captured through vanilla's own {@code ColonyDecivListener} rather
     * than a poll — the listener fires exactly once from inside {@code DecivTracker.decivilize}
     * (api_src {@code intel/deciv/DecivTracker.java:282}), whereas a poll would have to infer deciv
     * from a market leaving the economy, which also happens when a pirate base ends.
     */
    private void captureDeciv(MarketAPI market, boolean fullyDestroyed) {
        if (!isHost() || !isActive() || market == null || replayGuard.isReplaying()) {
            return;
        }
        try {
            // The campaign timestamp is what makes a second deciv on the same market id a distinct
            // payload; without it the ledger latched the first one for the whole session and a
            // colony re-founded on the same planet could never decivilize again on the guest. It is
            // deliberately the campaign clock and not a counter: a repeat fire of the *same* event
            // reads the same timestamp and is still deduped, exactly as before.
            CoopWorldDelta delta = new CoopWorldDelta(market.getId(), CoopWorldDelta.Kind.DECIV, false,
                    CoopSkeletonMutationWatcher.encodeDeciv(fullyDestroyed, campaignTimestamp()),
                    session.localPlayerId());
            if (worldLedger.apply(delta)) {
                reportWorldDelta(delta);
                CoopLog.info(CoopCampaignReplicator.class, "Coop captured DECIV market="
                        + market.getId() + " fullDestroy=" + fullyDestroyed);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture DECIV", ex);
        }
    }

    /**
     * Guest: reproduce the host's decivilization by calling the same public static routine the host's
     * tracker called ({@code DecivTracker.decivilize(market, fullDestroy, true)}, api_src
     * {@code intel/deciv/DecivTracker.java:189}). Re-deriving its twenty-odd steps by hand would be a
     * second implementation to keep in sync with every engine update; the vanilla call is exact.
     */
    private void applyDecivToEngine(CoopWorldDelta delta) {
        decivilizeMarket(delta.entityId(),
                CoopSkeletonMutationWatcher.decodeDecivFullDestroy(delta.newStateJson()), "DECIV");
    }

    /**
     * The one place this client ever calls {@code DecivTracker.decivilize} on a peer's behalf, so the
     * idempotence guard is identical no matter which delta asked for it — a {@code DECIV} from the
     * host, or a {@code RAID_RESULT} whose saturation bombardment wiped the colony out on the
     * originator ({@link #handleRaidResult}).
     *
     * <p>Vanilla's routine ends with {@code getEconomy().removeMarket(market)}, so "already done"
     * shows up as an unresolvable market id and the guard costs one lookup.
     *
     * @return {@code true} only when this call actually decivilized the market.
     */
    private boolean decivilizeMarket(String marketId, boolean fullDestroy, String source) {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getEconomy() == null || marketId == null) {
            return false;
        }
        MarketAPI market = sector.getEconomy().getMarket(marketId);
        CoopSkeletonMutationWatcher.DecivDecision decision = CoopSkeletonMutationWatcher.decideDeciv(
                market != null,
                market != null && market.hasCondition(Conditions.DECIVILIZED),
                market != null && market.getPrimaryEntity() != null);
        if (decision != CoopSkeletonMutationWatcher.DecivDecision.DECIVILIZE) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop deciv market=" + marketId + " via "
                    + source + " skipped: " + decision);
            return false;
        }
        DecivTracker.decivilize(market, fullDestroy, true);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied deciv market=" + marketId
                + " via " + source);
        return true;
    }

    /**
     * Flip a campaign objective's owner the way vanilla's own capture does — {@code setFaction} plus
     * clearing the non-functional memory flag ({@code rulecmd/salvage/Objectives.control}, api_src
     * lines 304-312). Runs on both roles since Phase 12c: host&rarr;guest for a war-sim flip, and
     * guest&rarr;host for an objective the guest captured in its own dialog.
     *
     * <p>Deliberately <em>not</em> mirrored: {@code ListenerUtil.reportObjectiveChangedHands}. Its
     * only core implementor is {@code WarSimScript} ({@code command/WarSimScript.java:314}), which
     * answers by spawning response fleets — the exact simulation the guest suppresses. Skipping it on
     * the host too means a guest capture draws no war-sim retaliation where the host's own capture
     * would; that is the conservative side of the trade (a missing response fleet, not a phantom one)
     * and stays consistent with the guest, whose sim could not mirror the spawn anyway. The
     * dialog-only reputation hit and the comm-sniffer unhack are likewise player-local concerns.
     */
    private void applyObjectiveOwnershipToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        String factionId = delta.newStateJson().trim();
        if (factionId.isEmpty()) {
            return;
        }
        SectorEntityToken objective = findEntityForDelta(sector, delta.entityId());
        if (objective == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "OBJECTIVE_OWNERSHIP for unknown entity "
                    + delta.entityId() + "; skipping");
            return;
        }
        FactionAPI current = objective.getFaction();
        if (!CoopSkeletonMutationWatcher.shouldSetObjectiveFaction(
                current == null ? null : current.getId(), factionId)) {
            return;
        }
        objective.setFaction(factionId);
        MemoryAPI memory = objective.getMemoryWithoutUpdate();
        if (memory != null) {
            memory.unset(MemFlags.OBJECTIVE_NON_FUNCTIONAL);
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied OBJECTIVE_OWNERSHIP entity="
                + delta.entityId() + " faction=" + factionId);
    }

    /**
     * Mirror the peer's gate state. Vanilla's {@code madeActive} latch is private and derived
     * ({@code GateEntityPlugin.advance}, api_src lines 274-284, sets it once
     * {@code canUseGates() && isScanned(entity)}), so setting the inputs is both sufficient and
     * reflection-free: this client's own gate plugin latches on its next frame.
     *
     * <p>The sector-global flags are applied even when the gate itself does not resolve — they are
     * the half that makes every gate usable — and are only ever set, never unset.
     *
     * <p>Runs on both roles now that either player can scan: host&rarr;guest carries the story's
     * activation plus {@code $canScanGates}, guest&rarr;host carries the guest's own scan.
     * {@code $numGatesScanned} is <em>not</em> on the wire — it is derived here instead, by calling
     * vanilla's own {@code addGateScanned()} whenever this client actually flips a gate's scanned
     * flag from a peer report. That keeps the counter equal to the number of gates this client knows
     * to be scanned on both sides (the Galatia questline reads it as a rules condition) without a
     * shared counter two independent polls would have to agree on.
     */
    private void applyGateStateToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        CoopSkeletonMutationWatcher.GateState state =
                CoopSkeletonMutationWatcher.decodeGateState(delta.newStateJson());
        MemoryAPI sectorMemory = sector.getMemoryWithoutUpdate();
        SectorEntityToken gate = findEntityForDelta(sector, delta.entityId());
        if (gate == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "GATE_ACTIVATED for unknown entity "
                    + delta.entityId() + "; applying sector gate flags only");
        }
        CoopSkeletonMutationWatcher.GateApply apply = CoopSkeletonMutationWatcher.decideGate(state,
                sectorMemory != null && sectorMemory.getBoolean(GateEntityPlugin.GATES_ACTIVE),
                sectorMemory != null && sectorMemory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES),
                gate == null || GateEntityPlugin.isScanned(gate),
                sectorMemory != null && sectorMemory.getBoolean(GateEntityPlugin.CAN_SCAN_GATES));
        if (apply.isNoOp()) {
            return;
        }
        if (sectorMemory != null) {
            if (apply.setGatesActive()) {
                sectorMemory.set(GateEntityPlugin.GATES_ACTIVE, true);
            }
            if (apply.setCanUseGates()) {
                sectorMemory.set(GateEntityPlugin.PLAYER_CAN_USE_GATES, true);
            }
            if (apply.setCanScanGates()) {
                sectorMemory.set(GateEntityPlugin.CAN_SCAN_GATES, true);
            }
        }
        if (apply.setScanned() && gate.getMemoryWithoutUpdate() != null) {
            gate.getMemoryWithoutUpdate().set(GateEntityPlugin.GATE_SCANNED, true);
            // Vanilla's own counter, bumped exactly where rules.csv bumps it (alongside
            // "$gateScanned = true"). Idempotent because decideGate only asks for the write when the
            // flag is not already set.
            GateEntityPlugin.addGateScanned();
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied GATE_ACTIVATED entity="
                + delta.entityId() + " " + apply);
    }

    /**
     * Raise a planet's survey level to the reported one (Phase 12c, plan gap 5). Max-wins by ordinal:
     * the level is monotonic in vanilla (nothing ever lowers it), so taking the higher of the two
     * makes the apply idempotent, commutative and reorder-proof — a SEEN arriving after a FULL, which
     * two independently polling clients can absolutely produce, is simply dropped.
     *
     * <p>FULL goes through {@code Misc.setFullySurveyed} (api_src {@code util/Misc.java:3003-3009})
     * rather than the plain setter, because the enum is only half of "fully surveyed": vanilla also
     * flips every {@code MarketConditionAPI.setSurveyed} bit, and without that the peer's planet reads
     * as FULL with its conditions still hidden. {@code withNotification=false} — the message belongs
     * to the player who actually ran the survey.
     */
    private void applySurveyLevelToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        MarketAPI market = planetMarketForDelta(sector, delta);
        if (market == null) {
            return;
        }
        MarketAPI.SurveyLevel incoming;
        try {
            incoming = MarketAPI.SurveyLevel.valueOf(delta.newStateJson().trim());
        } catch (IllegalArgumentException ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "SURVEY entity=" + delta.entityId()
                    + " carries unknown level '" + delta.newStateJson() + "'; skipping");
            return;
        }
        MarketAPI.SurveyLevel current = market.getSurveyLevel();
        if (current != null && incoming.ordinal() <= current.ordinal()) {
            return;
        }
        if (incoming == MarketAPI.SurveyLevel.FULL) {
            setFullySurveyed(market);
        } else {
            market.setSurveyLevel(incoming);
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied SURVEY entity=" + delta.entityId()
                + " level=" + incoming + " (was " + current + ")");
    }

    /**
     * Vanilla's routine first, so an engine update to it is picked up for free. Its two writes are
     * repeated inline if the class will not load: every static field on {@code Misc} initializes off
     * {@code Global.getSettings()} ({@code util/Misc.java:196}), which exists in the game and not in
     * a unit test, and a {@code NoClassDefFoundError} there must not cost the guest its survey.
     */
    private static void setFullySurveyed(MarketAPI market) {
        try {
            Misc.setFullySurveyed(market, null, false);
            return;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Misc.setFullySurveyed unavailable; applying its writes directly", ex);
        }
        List<MarketConditionAPI> conditions = market.getConditions();
        if (conditions != null) {
            for (MarketConditionAPI condition : conditions) {
                if (condition != null) {
                    condition.setSurveyed(true);
                }
            }
        }
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
    }

    /**
     * {@code Misc.hasRuins} without the {@code Misc} class dependency — the four condition ids are
     * compile-time constants ({@code util/Misc.java:5883-5889}).
     */
    private static boolean hasRuins(MarketAPI market) {
        return market.hasCondition(Conditions.RUINS_SCATTERED)
                || market.hasCondition(Conditions.RUINS_WIDESPREAD)
                || market.hasCondition(Conditions.RUINS_EXTENSIVE)
                || market.hasCondition(Conditions.RUINS_VAST);
    }

    /**
     * Mirror the {@code $ruinsExplored} flag vanilla's {@code salRuins_postSalvagePerform} rule sets
     * on the acting client only. One-way: the flag is never cleared, so a report that says anything
     * but {@code true} is ignored rather than unsetting a flag the local player earned.
     */
    private void applyRuinsExploredToEngine(CoopWorldDelta delta) {
        if (!Boolean.parseBoolean(delta.newStateJson().trim())) {
            return;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        MarketAPI market = planetMarketForDelta(sector, delta);
        if (market == null) {
            return;
        }
        MemoryAPI memory = market.getMemoryWithoutUpdate();
        if (memory == null
                || memory.getBoolean(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG)) {
            return;
        }
        memory.set(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG, true);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied RUINS_EXPLORED entity="
                + delta.entityId());
    }

    /** Resolves the planet market a {@link CoopWorldDelta.Kind#SURVEY}-family delta targets. */
    private MarketAPI planetMarketForDelta(SectorAPI sector, CoopWorldDelta delta) {
        SectorEntityToken entity = findEntityForDelta(sector, delta.entityId());
        MarketAPI market = entity == null ? null : entity.getMarket();
        if (market == null) {
            CoopLog.warn(CoopCampaignReplicator.class, delta.kind() + " for entity "
                    + delta.entityId() + " with no market here; skipping");
        }
        return market;
    }

    /**
     * Slow poll for the skeleton mutations that have no usable capture event — campaign objective
     * ownership (the war sim's own listener is the sim we suppress guest-side), story gate activation
     * (no event at all), and since Phase 12c planet survey levels plus ruins exploration (four of the
     * five survey mutation paths fire no listener, and rules.csv sets {@code $ruinsExplored} directly).
     *
     * <p><b>Survey and ruins run on both roles</b> for the same reason objectives do: both players
     * survey, and the flip has to reach the other side either way. Apply is max-wins on the level's
     * ordinal, which makes the two independent polls converge no matter what order the deltas land in.
     *
     * <p><b>Objectives run on both roles</b> (Phase 12c, plan gap 3b). The guest can capture a comm
     * relay / nav buoy / sensor array through its own local interaction dialog — {@code
     * Objectives.control} runs entirely client-side — and before this the flip stayed guest-local
     * until the host's war sim happened to overwrite it. Reporting upward on the same
     * {@code WORLD_DELTA(OBJECTIVE_OWNERSHIP)} channel makes the guest's capture authoritative: the
     * host applies it in {@link #applyObjectiveOwnershipToEngine} and rebroadcasts, and the ledger's
     * latest-wins payload dedup absorbs both the host's echo back to the guest and the guest's own
     * next poll (which sees the value it already recorded).
     *
     * <p><b>Gates run on both roles too.</b> They were host-only on the reasoning that the guest's
     * producers were suppressed sims, which is true of the war sim but not of the gate <em>scan</em>:
     * that is a plain rules.csv dialog option ({@code gateScanSel}) the guest runs locally, so a
     * guest scan never left the guest. Reversing the restriction is safe for the same two reasons
     * objectives are: every gate write is set-only and monotone
     * ({@link CoopSkeletonMutationWatcher#decideGate}), so the two polls converge rather than
     * oscillate; and {@link #handleWorldDelta} records the applied payload in the ledger, so the
     * applying side's own next poll reads back a value the ledger already holds and
     * {@link #emitSkeletonDelta} drops it. The host still owns the story flags — the guest cannot set
     * {@code $gatesActive} on its own — and it is the host&rarr;guest half of the same payload that
     * carries {@code $canScanGates}, which is what lets the guest be offered the scan at all.
     *
     * <p><b>Deciv stays host-only.</b> Its guest producer really is suppressed, and a guest-sourced
     * DECIV would delete a colony out of the authoritative world; {@link CoopWorldDelta.Kind#hostOnly}
     * refuses it on arrival.
     */
    private void tickSkeletonMutations() {
        if (!isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastSkeletonPollMillis < SKELETON_POLL_INTERVAL_MILLIS) {
            return;
        }
        lastSkeletonPollMillis = nowMillis;
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            MemoryAPI sectorMemory = sector.getMemoryWithoutUpdate();
            boolean gatesActive = sectorMemory != null
                    && sectorMemory.getBoolean(GateEntityPlugin.GATES_ACTIVE);
            boolean canUseGates = sectorMemory != null
                    && sectorMemory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES);
            boolean canScanGates = sectorMemory != null
                    && sectorMemory.getBoolean(GateEntityPlugin.CAN_SCAN_GATES);

            Map<String, String> objectiveOwners = new LinkedHashMap<>();
            Map<String, String> gateStates = new LinkedHashMap<>();
            Map<String, String> surveyLevels = new LinkedHashMap<>();
            Map<String, String> ruinsExplored = new LinkedHashMap<>();
            for (LocationAPI location : sector.getAllLocations()) {
                if (location == null) {
                    continue;
                }
                collectObjectiveOwners(location, objectiveOwners);
                collectSurveyState(location, surveyLevels, ruinsExplored);
                collectGateStates(location, gatesActive, canUseGates, canScanGates, gateStates);
            }
            for (CoopSkeletonMutationWatcher.Flip flip
                    : skeletonWatcher.diffObjectiveOwners(objectiveOwners)) {
                emitSkeletonDelta(CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, flip);
            }
            for (CoopSkeletonMutationWatcher.Flip flip
                    : skeletonWatcher.diffSurveyLevels(surveyLevels)) {
                emitSkeletonDelta(CoopWorldDelta.Kind.SURVEY, flip);
            }
            for (CoopSkeletonMutationWatcher.Flip flip
                    : skeletonWatcher.diffRuinsExplored(ruinsExplored)) {
                emitSkeletonDelta(CoopWorldDelta.Kind.RUINS_EXPLORED, flip);
            }
            for (CoopSkeletonMutationWatcher.Flip flip
                    : skeletonWatcher.diffGateStates(gateStates)) {
                emitSkeletonDelta(CoopWorldDelta.Kind.GATE_ACTIVATED, flip);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Skeleton mutation poll failed", ex);
        }
    }

    /**
     * Keys on the coop id for a player-built objective and on the engine id for a gen-time one, the
     * same rule {@link #consumeKeyIfTracked} uses. A built objective comes out of
     * {@code addCustomEntity(null, ...)}, so its engine id is minted per client and an ownership flip
     * reported under it could never resolve on the peer — which is exactly the
     * "OBJECTIVE_OWNERSHIP for unknown entity" warn this removes.
     */
    private void collectObjectiveOwners(LocationAPI location, Map<String, String> out) {
        List<SectorEntityToken> objectives = location.getEntitiesWithTag(Tags.OBJECTIVE);
        if (objectives == null) {
            return;
        }
        for (SectorEntityToken objective : objectives) {
            if (objective == null || objective.getId() == null || objective.getFaction() == null) {
                continue;
            }
            out.put(coopIdOrEngineId(objective), objective.getFaction().getId());
        }
    }

    /** The coop id a replicated entity was tagged with, or its engine id when it has none. */
    private static String coopIdOrEngineId(SectorEntityToken entity) {
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        Object coopId = memory == null ? null : memory.get(CoopWorldEntitySpawn.COOP_ENTITY_TAG);
        if (coopId != null && !String.valueOf(coopId).isBlank()) {
            return String.valueOf(coopId);
        }
        return entity.getId();
    }

    private void collectGateStates(LocationAPI location, boolean gatesActive, boolean canUseGates,
                                   boolean canScanGates, Map<String, String> out) {
        List<SectorEntityToken> gates = location.getEntitiesWithTag(Tags.GATE);
        if (gates == null) {
            return;
        }
        for (SectorEntityToken gate : gates) {
            if (gate == null || gate.getId() == null) {
                continue;
            }
            out.put(gate.getId(), CoopSkeletonMutationWatcher.encodeGateState(
                    GateEntityPlugin.isScanned(gate), gatesActive, canUseGates, canScanGates));
        }
    }

    /**
     * Reads every non-star planet's survey level and, for the ones that have ruins, whether those
     * ruins have been salvaged. Both roles: either player can survey, and the survey-data special
     * item can raise the level of a planet light-years from the player who cracked the cache, so the
     * walk is sector-wide rather than scoped to the acting client's system.
     *
     * <p>Ruins-bearing planets are fed on every pass with their current {@code true}/{@code false}
     * value rather than only when explored — the watcher only reports a change to an entry it has
     * seen before, so a map that omits the unexplored planets would never notice one becoming
     * explored.
     */
    private void collectSurveyState(LocationAPI location, Map<String, String> surveyOut,
                                    Map<String, String> ruinsOut) {
        List<PlanetAPI> planets = location.getPlanets();
        if (planets == null) {
            return;
        }
        for (PlanetAPI planet : planets) {
            if (planet == null || planet.isStar() || planet.getId() == null) {
                continue;
            }
            MarketAPI market = planet.getMarket();
            if (market == null) {
                continue;
            }
            MarketAPI.SurveyLevel level = market.getSurveyLevel();
            if (level != null) {
                surveyOut.put(planet.getId(), level.name());
            }
            if (hasRuins(market)) {
                MemoryAPI memory = market.getMemoryWithoutUpdate();
                ruinsOut.put(planet.getId(), Boolean.toString(memory != null
                        && memory.getBoolean(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG)));
            }
        }
    }

    /**
     * Record this client's own capture in the ledger (so the peer's echo is inert) and send it. On
     * the host that is a broadcast; on the guest it is the upward report the host then integrates.
     */
    private void emitSkeletonDelta(CoopWorldDelta.Kind kind, CoopSkeletonMutationWatcher.Flip flip) {
        if (emitWorldDelta(kind, flip.entityId(), flip.state())) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop captured " + kind + " entity="
                    + flip.entityId() + " state=" + flip.state());
        }
    }

    /**
     * Records one of this engine's own non-consuming captures in the ledger and sends it, returning
     * whether the ledger accepted it as new. Recording before sending is what makes the peer's echo
     * inert on the originator; every poller-driven kind goes through here.
     */
    private boolean emitWorldDelta(CoopWorldDelta.Kind kind, String entityId, String payload) {
        CoopWorldDelta delta = new CoopWorldDelta(entityId, kind, false, payload,
                session.localPlayerId());
        if (!worldLedger.apply(delta)) {
            return false;
        }
        reportWorldDelta(delta);
        return true;
    }

    /**
     * Phase 32: storage unlocks. Both roles poll, because either player can pay the fee; the host
     * additionally ships a once-per-session baseline of everything already unlocked, which is the
     * only way a guest joining an older campaign learns about unlocks paid before it arrived.
     */
    private void tickStorageUnlock() {
        try {
            if (isHost()) {
                for (String marketId : storageUnlockSync.takeBaseline()) {
                    emitWorldDelta(CoopWorldDelta.Kind.STORAGE_UNLOCK, marketId, "true");
                }
            }
            String unlocked = storageUnlockSync.pollDockedUnlock(now());
            // Phase 32 addition A: the poll reads the local market behind the dialog, so a hidden
            // base's unlock must be reported under the host's id or the far side flags a market that
            // does not exist there.
            String wireUnlocked = marketIds.toWire(unlocked);
            if (unlocked != null
                    && emitWorldDelta(CoopWorldDelta.Kind.STORAGE_UNLOCK, wireUnlocked, "true")) {
                CoopLog.info(CoopCampaignReplicator.class,
                        "Coop captured STORAGE_UNLOCK market=" + wireUnlocked
                                + localSuffix(unlocked, wireUnlocked));
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Storage-unlock poll failed", ex);
        }
    }

    /** Phase 32: the host's commission, mirrored to the guest as one memory key. Host-only. */
    private void tickCommission() {
        if (!isHost()) {
            return;
        }
        try {
            String factionId = commissionSync.poll(now());
            if (factionId != null && emitWorldDelta(CoopWorldDelta.Kind.COMMISSION,
                    CoopCommissionSync.ENTITY_ID, factionId)) {
                CoopLog.info(CoopCampaignReplicator.class,
                        "Coop captured COMMISSION faction=" + CoopCommissionSync.describe(factionId));
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Commission poll failed", ex);
        }
    }

    /** Vanilla colony-deciv hook; the only capture point for {@link CoopWorldDelta.Kind#DECIV}. */
    private final class DecivCapture implements ColonyDecivListener {
        @Override
        public void reportColonyAboutToBeDecivilized(MarketAPI market, boolean fullyDestroyed) {
            // No-op: the market still holds its pre-deciv state here, and the guest reproduces the
            // whole transition from the completed report below.
        }

        @Override
        public void reportColonyDecivilized(MarketAPI market, boolean fullyDestroyed) {
            captureDeciv(market, fullyDestroyed);
        }
    }

    /**
     * Per-frame salvage watcher: when a salvageable entity at the local player's location disappears
     * (salvaged/disassembled), report it as a CONSUME world-delta so the other client removes the
     * same entity. Deterministic worldgen means the entity id matches across both clients.
     */
    public void tickWorldDeltas() {
        if (!isActive()) {
            return;
        }
        // Self-throttled, and deliberately ahead of the hyperspace early-return below: objectives and
        // gates flip whether or not the polling client is sitting in a star system.
        tickSkeletonMutations();
        // Same reason (Phase 32): a dialog can be open and a commission can be signed or lost with
        // the player's fleet parked in hyperspace, and the salvage scan below returns early there.
        tickStorageUnlock();
        tickCommission();
        long nowMillis = now();
        if (!shouldScanSalvage(lastSalvageScanMillis, nowMillis)) {
            return;
        }
        lastSalvageScanMillis = nowMillis;
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI player = sector == null ? null : sector.getPlayerFleet();
            LocationAPI location = player == null ? null : player.getContainingLocation();
            if (location == null || location.isHyperspace()) {
                // Nothing to watch in hyperspace; drop the baseline so re-entering a system re-seeds
                // it rather than reporting the whole previous system as "salvaged".
                trackedSalvageables.clear();
                watchedLocationId = null;
                return;
            }
            // Scratch set, cleared and refilled: a fresh HashSet per pass was pure churn.
            Set<String> current = salvageScanScratch;
            current.clear();
            constructionScratch.clear();
            for (SectorEntityToken entity : location.getAllEntities()) {
                // One getMemoryWithoutUpdate() per entity, not two — the call allocates a Memory for
                // entities that lack one, so the old track-then-key pair did that twice over the whole
                // location every frame.
                String key = consumeKeyIfTracked(entity);
                if (key != null) {
                    current.add(key);
                    // Same diff, other direction: an id that is present now and was not last pass is
                    // a new entity. Only player constructions are worth a SPAWN.
                    if (!trackedSalvageables.contains(key) && isReplicableConstruction(entity)) {
                        constructionScratch.add(entity);
                    }
                }
            }
            // Entering a new location: re-seed the baseline silently (the old location's entities are
            // "gone" only because the player moved, not because they were salvaged).
            if (!Objects.equals(location.getId(), watchedLocationId)) {
                watchedLocationId = location.getId();
                trackedSalvageables.clear();
                trackedSalvageables.addAll(current);
                constructionScratch.clear();
                if (CoopDebug.diagnosticsEnabled()) {
                    dumpOrbitDiagnostics(location); // dormant; opt-in via CoopDebug
                }
                return;
            }
            // Adds before removes, deliberately: a construction consumes a stable location in the
            // same frame it creates the objective, and reporting the SPAWN first means the peer has
            // the replacement before the CONSUME for what it replaced arrives.
            for (int i = 0; i < constructionScratch.size(); i++) {
                SectorEntityToken built = constructionScratch.get(i);
                String previousKey = consumeKeyIfTracked(built);
                String coopId = reportLocalConstruction(built, location);
                if (coopId != null) {
                    // Tagging changed the entity's consume key from its engine id to its coop id;
                    // the scratch set still holds the old one, and leaving it there would make the
                    // next pass see the engine id vanish and report a phantom CONSUME.
                    current.remove(previousKey);
                    current.add(coopId);
                }
            }
            constructionScratch.clear();
            // A tracked entity that is no longer present was consumed by the local player this pass.
            // Collected first because the report path is free to touch the tracked set.
            salvageConsumedScratch.clear();
            for (String id : trackedSalvageables) {
                if (!current.contains(id)) {
                    salvageConsumedScratch.add(id);
                }
            }
            for (int i = 0; i < salvageConsumedScratch.size(); i++) {
                String id = salvageConsumedScratch.get(i);
                trackedSalvageables.remove(id);
                reportLocalSalvageConsume(id);
            }
            salvageConsumedScratch.clear();
            trackedSalvageables.addAll(current);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Salvage watcher failed", ex);
        }
    }

    /** The salvage watcher's timer gate (pure, unit-tested). First pass after a reset always runs. */
    static boolean shouldScanSalvage(long lastScanMillis, long nowMillis) {
        if (lastScanMillis == 0L) {
            return true; // fresh session / post-reset: seed the baseline on the first frame
        }
        long elapsed = nowMillis - lastScanMillis;
        return elapsed < 0L || elapsed >= SALVAGE_SCAN_INTERVAL_MILLIS; // negative = clock stepped back
    }

    /**
     * Dormant diagnostic (off unless {@link CoopDebug#diagnosticsEnabled()}): dump the intrinsic
     * circular-orbit parameters of every orbiting entity in a location, sorted by id, so the host and
     * guest logs can be diffed line-for-line. {@code radius+period+angle} mismatch on the same id =
     * non-deterministic orbit (e.g. the fringe jump-point); matching geometry with a tiny angle delta
     * scaling with 1/period = clock drift. Logged once per system entry while enabled.
     */
    private void dumpOrbitDiagnostics(LocationAPI location) {
        try {
            List<SectorEntityToken> orbiting = new ArrayList<>();
            for (SectorEntityToken e : location.getAllEntities()) {
                if (e instanceof CampaignFleetAPI || e.getOrbit() == null) {
                    continue;
                }
                orbiting.add(e);
            }
            orbiting.sort((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())));
            CoopLog.info(CoopCampaignReplicator.class, "Coop orbit-dump BEGIN loc=" + location.getId()
                    + " name=" + location.getName() + " entities=" + orbiting.size()
                    + " role=" + service.role());
            for (SectorEntityToken e : orbiting) {
                CoopLog.info(CoopCampaignReplicator.class, String.format(
                        "Coop orbit-dump id=%s type=%s focus=%s r=%.1f ang=%.2f period=%.2f pos=(%.1f,%.1f)",
                        e.getId(), e.getCustomEntityType(),
                        e.getOrbitFocus() == null ? "-" : e.getOrbitFocus().getId(),
                        e.getCircularOrbitRadius(), e.getCircularOrbitAngle(), e.getCircularOrbitPeriod(),
                        e.getLocation().x, e.getLocation().y));
            }
            CoopLog.info(CoopCampaignReplicator.class, "Coop orbit-dump END loc=" + location.getId());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "orbit-dump failed", ex);
        }
    }

    /**
     * Host: ~1Hz, broadcast the current orbit angle of every orbiting body in the host player's
     * location so the guest can snap out clock-drift. Only the host's location is sent — that is the
     * system the players share when together, which is the only time the desync is visible.
     */
    public void tickOrbitSync() {
        if (!isHost() || !isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastOrbitSyncMillis < ORBIT_SYNC_INTERVAL_MILLIS) {
            return;
        }
        lastOrbitSyncMillis = nowMillis;
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI player = sector == null ? null : sector.getPlayerFleet();
            LocationAPI location = player == null ? null : player.getContainingLocation();
            if (location == null || location.isHyperspace()) {
                return;
            }
            List<SectorEntityToken> bodies = syncableOrbitBodies(location);
            List<CoopOrbitSync.OrbitEntry> entries = new ArrayList<>(bodies.size());
            for (SectorEntityToken e : bodies) {
                String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
                entries.add(new CoopOrbitSync.OrbitEntry(e.getId(), focusId, e.getCircularOrbitRadius(),
                        e.getCircularOrbitPeriod(), e.getCircularOrbitAngle()));
            }
            maybeDumpOrbitBreakdown(bodies);
            if (!entries.isEmpty()) {
                send(CoopMessages.orbitSnapshot(session.sessionId(), service.nextSeq(), nowMillis,
                        location.getId(), CoopOrbitSync.encode(entries)));
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Orbit sync capture failed", ex);
        }
    }

    /** Guest: snap local orbiting bodies to the host's angles (string-id first, then orbit signature). */
    private void applyOrbitSnapshot(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String locationId = payload.requiredString("locationId");
        List<CoopOrbitSync.OrbitEntry> entries = CoopOrbitSync.decode(
                payload.requiredString("orbits"));
        SectorAPI sector = Global.getSector();
        if (sector == null || entries.isEmpty()) {
            return;
        }
        // Find the target system via the first resolvable stable-id body (its containing location);
        // entity ids are global but enumerating one location bounds the signature pool.
        LocationAPI location = null;
        for (CoopOrbitSync.OrbitEntry entry : entries) {
            if (CoopOrbitSync.isStableId(entry.entityId())) {
                SectorEntityToken token = sector.getEntityById(entry.entityId());
                if (token != null && token.getContainingLocation() != null) {
                    location = token.getContainingLocation();
                    break;
                }
            }
        }
        if (location == null) {
            return;
        }
        replayGuard.begin();
        try {
            // Signature index of local syncable bodies, used only as a fallback when an entity id
            // doesn't resolve. (focus|radius|period; consumed entries are removed to break co-orbit
            // ties like the planet 'barad' sharing an orbit with the hex-id nav buoy.)
            Map<String, List<SectorEntityToken>> bySignature = new HashMap<>();
            for (SectorEntityToken e : syncableOrbitBodies(location)) {
                String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
                bySignature.computeIfAbsent(
                        CoopOrbitSync.signature(focusId, e.getCircularOrbitRadius(), e.getCircularOrbitPeriod()),
                        k -> new ArrayList<>()).add(e);
            }
            // Id index of the target location's entities, built in one pass. The old code called
            // sector.getEntityById() per entry — a sector-wide scan across every location — and at
            // ~70 entries/s that was a measured 67-82 ms frame stall once per second on the guest
            // (2026-08-20 frame-profiler session). Every body in the snapshot lives in one host
            // location, and under the seed-lock fingerprint the guest's same-id bodies live in the
            // same system, so bounding the lookup to the resolved location is also the safer match.
            Map<String, SectorEntityToken> localById = new HashMap<>();
            for (SectorEntityToken e : location.getAllEntities()) {
                if (e != null && e.getId() != null) {
                    localById.putIfAbsent(e.getId(), e);
                }
            }
            int snapped = 0;
            for (CoopOrbitSync.OrbitEntry entry : entries) {
                SectorEntityToken local = resolveLocalBody(entry, localById, bySignature);
                if (local != null) {
                    applyOrbitTo(local, entry, localById, sector);
                    snapped++;
                }
            }
            // 1 Hz, and the concat ran whether or not anything would read it (perf audit #18). It
            // earned its keep in the orbit-desync smoke tests, so it stays — behind the same
            // diagnostics gate as the orbit dump it is read alongside.
            if (CoopDebug.diagnosticsEnabled()) {
                CoopLog.info(CoopCampaignReplicator.class, "Coop ORBIT_SNAPSHOT applied loc=" + locationId
                        + " entries=" + entries.size() + " snapped=" + snapped);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply ORBIT_SNAPSHOT", ex);
        } finally {
            replayGuard.end();
        }
    }

    /**
     * Resolve the local body for a host orbit entry. ID first: it matches named bodies AND early-gen
     * hex bodies whose ids agree across instances (e.g. the fringe jump-point '1b9'), even when their
     * orbit itself differs — which is exactly why a signature-only match misses it. Falls back to the
     * orbit signature for any body whose id doesn't resolve.
     */
    private SectorEntityToken resolveLocalBody(CoopOrbitSync.OrbitEntry entry,
                                               Map<String, SectorEntityToken> localById,
                                               Map<String, List<SectorEntityToken>> bySignature) {
        SectorEntityToken byId = localById.get(entry.entityId());
        if (byId != null && isSyncableOrbit(byId)) {
            removeFromSignaturePool(bySignature, byId);
            return byId;
        }
        List<SectorEntityToken> candidates = bySignature.get(
                CoopOrbitSync.signature(entry.focusId(), entry.radius(), entry.period()));
        return candidates != null && !candidates.isEmpty() ? candidates.remove(0) : null;
    }

    /**
     * Align a local body to the host entry. If the orbit geometry matches (the common, deterministic
     * case), snap only the angle (preserves any spin / point-down orbit). If radius or period differ
     * — a non-deterministically generated orbit like the fringe jump-point — reset the whole circular
     * orbit so distance and angle both match the host.
     */
    private void applyOrbitTo(SectorEntityToken local, CoopOrbitSync.OrbitEntry entry,
                              Map<String, SectorEntityToken> localById, SectorAPI sector) {
        boolean orbitGeometryDiffers = Math.abs(local.getCircularOrbitRadius() - entry.radius()) > 1f
                || Math.abs(local.getCircularOrbitPeriod() - entry.period()) > 0.5f;
        if (orbitGeometryDiffers) {
            // Rare branch (non-deterministic orbit like the fringe jump-point), so the sector-wide
            // lookup fallback is affordable here; the location map still catches the common case.
            SectorEntityToken focus = localById.get(entry.focusId());
            if (focus == null && entry.focusId() != null) {
                focus = sector.getEntityById(entry.focusId());
            }
            if (focus != null) {
                local.setCircularOrbit(focus, entry.angle(), entry.radius(), entry.period());
                return;
            }
        }
        local.setCircularOrbitAngle(entry.angle());
    }

    /**
     * Only navigationally meaningful orbiting bodies are angle-synced: named string-id entities
     * (planets/moons/stations/relays/gates), jump points, and planets. The asteroid swarm is excluded
     * — its hundreds of near-identical orbits collide on signature and would starve the jump-point
     * match (the symptom: every named body aligned but the fringe jump-point still drifting).
     */
    /**
     * The orbit-sync body set for a location: planets/moons, jump points, and stable-id custom
     * entities (stations, relays, buoys) — the landmarks players navigate by.
     *
     * <p>Enumerated by inclusion from the engine's typed lists, NOT via {@code getAllEntities()}.
     * The all-entities sweep was a measured defect (2026-08-17): with the host's fleet parked in an
     * asteroid belt, the engine's materialized asteroids and ring-band segments passed the old
     * per-entity filter and ballooned the 1 Hz snapshot from 13 entries to 358, none of which the
     * guest could match (per-instance ids, and cosmetic anyway). The typed lists exclude asteroid
     * and ring entities structurally, whatever classes or ids the engine gives them.
     */
    private List<SectorEntityToken> syncableOrbitBodies(LocationAPI location) {
        List<SectorEntityToken> bodies = new ArrayList<>();
        addSyncableOrbitBodies(bodies, location.getPlanets());
        addSyncableOrbitBodies(bodies, location.getJumpPoints());
        addSyncableOrbitBodies(bodies, location.getCustomEntities());
        return bodies;
    }

    private void addSyncableOrbitBodies(List<SectorEntityToken> out,
                                        List<? extends SectorEntityToken> candidates) {
        if (candidates == null) {
            return;
        }
        for (SectorEntityToken e : candidates) {
            if (e != null && isSyncableOrbit(e)) {
                out.add(e);
            }
        }
    }

    private boolean isSyncableOrbit(SectorEntityToken e) {
        if (e instanceof CampaignFleetAPI || e.getOrbit() == null || e.getCircularOrbitRadius() <= 0f) {
            return false;
        }
        return CoopOrbitSync.isStableId(e.getId()) || e instanceof JumpPointAPI || e instanceof PlanetAPI;
    }

    /**
     * Diagnostics only: logs the orbit-sync body count with a per-class breakdown whenever the count
     * changes, so a future stream balloon names its culprit class directly in the log.
     */
    private void maybeDumpOrbitBreakdown(List<SectorEntityToken> bodies) {
        if (!CoopDebug.diagnosticsEnabled() || bodies.size() == lastOrbitBodyCount) {
            return;
        }
        lastOrbitBodyCount = bodies.size();
        Map<String, Integer> byClass = new LinkedHashMap<>();
        for (SectorEntityToken e : bodies) {
            byClass.merge(e.getClass().getSimpleName(), 1, Integer::sum);
        }
        CoopLog.info(CoopCampaignReplicator.class,
                "Coop orbit-sync bodies=" + bodies.size() + " byClass=" + byClass);
    }

    private void removeFromSignaturePool(Map<String, List<SectorEntityToken>> bySignature,
                                         SectorEntityToken e) {
        String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
        List<SectorEntityToken> list = bySignature.get(
                CoopOrbitSync.signature(focusId, e.getCircularOrbitRadius(), e.getCircularOrbitPeriod()));
        if (list != null) {
            list.remove(e);
        }
    }

    /**
     * The consume watcher's per-entity step: the tracking key for an entity worth watching, or null.
     *
     * <p><b>Which entities are tracked</b> (Phase 12d widening).
     *
     * <p>Was an allowlist of {@code Tags.SALVAGEABLE} only, which silently missed everything nobody
     * had thought to tag: verified in-game on 2026-08-09, {@code nav_buoy_makeshift} is tagged
     * {@code [nav_buoy, neutrino_high, objective, makeshift]} and {@code cargo_pods} is tagged
     * {@code [has_interaction_dialog, neutrino, salvage_music]} — neither carries {@code salvageable},
     * so disassembling a makeshift structure or emptying a pod reported nothing at all.
     *
     * <p>Now: anything salvage-tagged, anything coop-replicated, or any custom entity. Custom
     * entities cover pods and the makeshift structures without naming either. Planets, stars, and
     * jump points are not custom entities and so stay out; fleets are excluded outright because
     * Phase 9 owns them.
     *
     * <p><b>Which key.</b> Coop-replicated entities key on their coop-assigned id because the engine
     * mints its own per client and the two never match; everything else keys on the engine id, which
     * deterministic worldgen makes identical across clients.
     */
    private String consumeKeyIfTracked(SectorEntityToken entity) {
        if (entity == null) {
            return null;
        }
        // The one memory read the whole per-entity pass gets: getMemoryWithoutUpdate() lazily
        // allocates a save-persisted Memory for entities that lack one, so asking twice (once to
        // decide, once to key) doubled that cost across every entity in the location.
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        boolean coopReplicated = memory != null && memory.contains(CoopWorldEntitySpawn.COOP_ENTITY_TAG);
        if (!shouldTrackForConsume(
                entity instanceof CampaignFleetAPI,
                entity.hasTag(Tags.SALVAGEABLE),
                coopReplicated,
                entity instanceof CustomCampaignEntityAPI)) {
            return null;
        }
        if (coopReplicated) {
            Object coopId = memory.get(CoopWorldEntitySpawn.COOP_ENTITY_TAG);
            if (coopId != null && !String.valueOf(coopId).isBlank()) {
                return String.valueOf(coopId);
            }
        }
        return entity.getId();
    }

    /** Pure decision function (unit-tested) behind {@link #consumeKeyIfTracked}. */
    static boolean shouldTrackForConsume(boolean isFleet, boolean salvageTagged,
                                         boolean coopReplicated, boolean isCustomEntity) {
        if (isFleet) {
            return false; // Phase 9 owns fleet existence
        }
        return salvageTagged || coopReplicated || isCustomEntity;
    }

    /** Finds a replicated entity by coop id, falling back to the engine id for worldgen entities. */
    private SectorEntityToken findEntityForDelta(SectorAPI sector, String entityId) {
        SectorEntityToken byEngineId = sector.getEntityById(entityId);
        if (byEngineId != null) {
            return byEngineId;
        }
        for (LocationAPI location : sector.getAllLocations()) {
            if (location == null) {
                continue;
            }
            for (SectorEntityToken entity : location.getAllEntities()) {
                MemoryAPI memory = entity == null ? null : entity.getMemoryWithoutUpdate();
                if (memory != null
                        && entityId.equals(String.valueOf(memory.get(CoopWorldEntitySpawn.COOP_ENTITY_TAG)))) {
                    return entity;
                }
            }
        }
        return null;
    }

    /**
     * The local player left cargo pods behind (jettison, or cargo left in stable orbit). Replicate
     * them so the partner can actually pick them up — v1 has no direct trade UI, so pods are the
     * only way the two players can hand each other anything (Phase 12d).
     */
    @Override
    public void onPlayerLeftCargoPods(SectorEntityToken pods) {
        if (!isActive() || pods == null || replayGuard.isReplaying()) {
            return;
        }
        try {
            LocationAPI location = pods.getContainingLocation();
            if (location == null) {
                return;
            }
            String coopEntityId = session.localPlayerId() + ":" + pods.getId();
            pods.getMemoryWithoutUpdate().set(CoopWorldEntitySpawn.COOP_ENTITY_TAG, coopEntityId);

            CoopWorldEntitySpawn spawn = new CoopWorldEntitySpawn(
                    coopEntityId,
                    Entities.CARGO_PODS,
                    location.getId(),
                    pods.getLocation() == null ? 0f : pods.getLocation().x,
                    pods.getLocation() == null ? 0f : pods.getLocation().y,
                    pods.getVelocity() == null ? 0f : pods.getVelocity().x,
                    pods.getVelocity() == null ? 0f : pods.getVelocity().y,
                    contentsOf(pods));

            CoopWorldDelta delta = new CoopWorldDelta(coopEntityId, CoopWorldDelta.Kind.SPAWN, false,
                    spawn.encode(), session.localPlayerId());
            // Mark locally applied first so the host's echo rebroadcast is a no-op here.
            if (worldLedger.apply(delta)) {
                reportWorldDelta(delta);
                CoopLog.info(CoopCampaignReplicator.class, "Coop reported cargo pod spawn id="
                        + coopEntityId + " stacks=" + spawn.contents().size());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to report cargo pods", ex);
        }
    }

    /**
     * Vanilla's own back-reference from a built objective to the stable location it replaced
     * ({@code Objectives.build} sets it to the removed entity, api_src
     * {@code rulecmd/salvage/Objectives.java:387}). There is no {@code MemFlags} constant for it.
     */
    private static final String ORIGINAL_STABLE_LOCATION = "$originalStableLocation";

    /**
     * Whether a newly-appeared entity is a player construction the peer needs materialized.
     *
     * <p>Two shapes, both of them halves of the same vanilla round trip. Building runs
     * {@code Objectives.build}: {@code addCustomEntity(null, null, comm_relay_makeshift|
     * nav_buoy_makeshift|sensor_array_makeshift, player)}, copy the stable location's orbit, remove
     * the stable location. Disassembling runs {@code Objectives.salvage}, which does the reverse and
     * puts a fresh {@code stable_location} back. Both create the entity with a null id, so the engine
     * mints one per client and the peer could never resolve it — hence a coop id and a SPAWN, exactly
     * as for cargo pods.
     *
     * <p>Already-coop-tagged entities are excluded, which is what stops the peer's applied copy from
     * being re-reported straight back at the originator.
     */
    private static boolean isReplicableConstruction(SectorEntityToken entity) {
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        if (memory != null && memory.contains(CoopWorldEntitySpawn.COOP_ENTITY_TAG)) {
            return false;
        }
        return isConstructionShape(entity.hasTag(Tags.OBJECTIVE), entity.hasTag(Tags.MAKESHIFT),
                entity.hasTag(Tags.STABLE_LOCATION));
    }

    /**
     * Pure decision function (unit-tested) behind {@link #isReplicableConstruction}.
     *
     * <p>{@code objective && makeshift} rather than {@code objective} alone: gen-time comm relays and
     * nav buoys carry {@code objective} too, and they exist identically on both clients already —
     * only the makeshift variants are player-built ({@code custom_entities.json} tags them
     * {@code [..., "objective", "makeshift"]}).
     */
    static boolean isConstructionShape(boolean objective, boolean makeshift, boolean stableLocation) {
        return (objective && makeshift) || stableLocation;
    }

    /**
     * Tag a local construction with a coop id and report it as a {@code SPAWN}.
     *
     * @return the assigned coop id, or {@code null} when nothing was reported.
     */
    private String reportLocalConstruction(SectorEntityToken built, LocationAPI location) {
        try {
            String coopEntityId = session.localPlayerId() + ":" + built.getId();
            MemoryAPI memory = built.getMemoryWithoutUpdate();
            if (memory == null) {
                return null;
            }
            memory.set(CoopWorldEntitySpawn.COOP_ENTITY_TAG, coopEntityId);

            SectorEntityToken focus = built.getOrbitFocus();
            CoopWorldEntitySpawn.Orbit orbit = focus == null || focus.getId() == null
                    ? CoopWorldEntitySpawn.Orbit.NONE
                    : new CoopWorldEntitySpawn.Orbit(focus.getId(), built.getCircularOrbitAngle(),
                            built.getCircularOrbitRadius(), built.getCircularOrbitPeriod());
            FactionAPI faction = built.getFaction();
            CoopWorldEntitySpawn spawn = new CoopWorldEntitySpawn(
                    coopEntityId,
                    built.getCustomEntityType(),
                    location.getId(),
                    built.getLocation() == null ? 0f : built.getLocation().x,
                    built.getLocation() == null ? 0f : built.getLocation().y,
                    0f, 0f,
                    Map.of(),
                    faction == null || faction.getId() == null ? "" : faction.getId(),
                    consumedStableLocationId(memory),
                    orbit);

            CoopWorldDelta delta = new CoopWorldDelta(coopEntityId, CoopWorldDelta.Kind.SPAWN, false,
                    spawn.encode(), session.localPlayerId());
            // Ledger first, so the host's echo rebroadcast is a no-op here (same as the pod path).
            if (worldLedger.apply(delta)) {
                reportWorldDelta(delta);
                CoopLog.info(CoopCampaignReplicator.class, "Coop reported construction id="
                        + coopEntityId + " type=" + spawn.entityType() + " faction="
                        + spawn.factionId() + " consumed=" + spawn.consumedEntityId());
            }
            return coopEntityId;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to report a local construction", ex);
            return null;
        }
    }

    /** The stable location vanilla removed to make room for this objective, keyed as a CONSUME would. */
    private String consumedStableLocationId(MemoryAPI memory) {
        Object original = memory.get(ORIGINAL_STABLE_LOCATION);
        if (original instanceof SectorEntityToken token) {
            String key = consumeKeyIfTracked(token);
            return key == null ? "" : key;
        }
        return "";
    }

    /**
     * Everything a pod holds, keyed {@code KIND:id}. Commodities alone would silently drop the
     * weapons, fighters, and ships players most want to hand each other — and a vanishing ship is a
     * far worse failure than a missing crate of supplies.
     */
    private Map<String, Integer> contentsOf(SectorEntityToken pods) {
        Map<String, Integer> out = new LinkedHashMap<>();
        CargoAPI cargo = pods instanceof CustomCampaignEntityAPI custom ? custom.getCargo() : null;
        if (cargo == null) {
            return out;
        }
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            StackRef ref = classify(stack);
            if (ref == null) {
                continue;
            }
            CoopWorldEntitySpawn.ItemKind kind = spawnKindOf(ref.kind());
            if (kind == null) {
                continue;
            }
            int size = Math.round(stack.getSize());
            if (size > 0) {
                out.merge(CoopWorldEntitySpawn.key(kind, ref.id()), size, Integer::sum);
            }
        }
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships != null) {
            for (FleetMemberAPI member : ships.getMembersListCopy()) {
                String variantId = shipVariantId(member);
                if (variantId != null) {
                    out.merge(CoopWorldEntitySpawn.key(
                            CoopWorldEntitySpawn.ItemKind.SHIP, variantId), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /**
     * The pod-content kind a cargo-stack kind maps to, or null when the stack cannot be pod content.
     *
     * <p>Deliberately no {@code default -> COMMODITY}. That default is what mangled a jettisoned AI
     * core: a SPECIAL stack fell through it and was re-materialized on the partner's client as a
     * commodity of the same id, i.e. as nothing at all. Unmapped kinds are now skipped, loudly-shaped
     * (an exhaustive switch, so a new ItemKind is a compile error here rather than a silent
     * mis-materialization).
     */
    private static CoopWorldEntitySpawn.ItemKind spawnKindOf(CoopMarketSync.ItemKind kind) {
        return switch (kind) {
            case COMMODITY -> CoopWorldEntitySpawn.ItemKind.COMMODITY;
            case WEAPON -> CoopWorldEntitySpawn.ItemKind.WEAPON;
            case FIGHTER -> CoopWorldEntitySpawn.ItemKind.FIGHTER;
            case SHIP -> CoopWorldEntitySpawn.ItemKind.SHIP;
            case SPECIAL -> CoopWorldEntitySpawn.ItemKind.SPECIAL;
            // People are not cargo and can never be in a pod.
            case OFFICER, MERC, ADMIN -> null;
        };
    }

    /** Materializes a replicated world entity on this client. */
    private void applySpawnToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        CoopWorldEntitySpawn spawn = CoopWorldEntitySpawn.decode(delta.newStateJson());
        if (findEntityForDelta(sector, spawn.coopEntityId()) != null) {
            return; // already materialized (duplicate packet, or our own echo)
        }
        LocationAPI location = locationById(sector, spawn.locationId());
        if (location == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Cannot spawn coop entity "
                    + spawn.coopEntityId() + ": unknown location " + spawn.locationId());
            return;
        }
        SectorEntityToken entity = location.addCustomEntity(null, null, spawn.entityType(),
                spawn.factionId().isBlank() ? Factions.NEUTRAL : spawn.factionId());
        entity.getLocation().set(spawn.x(), spawn.y());
        // Velocity rides the wire because Misc.addCargoPods draws it from Math.random().
        entity.getVelocity().set(spawn.velocityX(), spawn.velocityY());
        if (Entities.CARGO_PODS.equals(spawn.entityType())) {
            // Misc.addCargoPods' own post-creation tweaks. Deliberately not applied to a built
            // objective: a comm relay's sensor profile and discoverability come from its spec, and
            // pinning them to the pod values would make the peer's copy read wrong on the map.
            entity.setSensorProfile(1f);
            entity.setDiscoverable(null);
            entity.setDiscoveryXP(null);
        }
        applySpawnOrbit(sector, entity, spawn);
        entity.getMemoryWithoutUpdate().set(CoopWorldEntitySpawn.COOP_ENTITY_TAG, spawn.coopEntityId());
        removeConsumedStableLocation(sector, spawn);
        if (entity instanceof CustomCampaignEntityAPI custom && custom.getCargo() != null) {
            for (Map.Entry<String, Integer> entry : spawn.contents().entrySet()) {
                addSpawnContent(custom.getCargo(), entry.getKey(), entry.getValue());
            }
        }
        pinMirrorExpiry(entity);
        CoopLog.info(CoopCampaignReplicator.class, "Coop materialized entity " + spawn.coopEntityId()
                + " type=" + spawn.entityType() + " faction=" + spawn.factionId()
                + " in " + spawn.locationId());
    }

    /**
     * Put a replicated construction in the same orbit the originator's copy holds.
     *
     * <p>Vanilla copies the orbit object straight off the entity it replaces
     * ({@code built.setOrbit(entity.getOrbit().makeCopy())}); here the four circular-orbit numbers
     * ride the wire instead, so the apply does not depend on the replaced stable location still being
     * present. Its {@code CONSUME} is emitted by the same watcher pass and may land first.
     *
     * <p>The focus is a gen-time body (a planet or a star), so its engine id resolves on both
     * clients. If it does not, the entity keeps the fixed position that already rode along — wrong as
     * it drifts, but present and interactable, which is the better failure.
     */
    private void applySpawnOrbit(SectorAPI sector, SectorEntityToken entity,
                                 CoopWorldEntitySpawn spawn) {
        CoopWorldEntitySpawn.Orbit orbit = spawn.orbit();
        if (!orbit.isPresent()) {
            return;
        }
        SectorEntityToken focus = findEntityForDelta(sector, orbit.focusId());
        if (focus == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop spawn " + spawn.coopEntityId()
                    + " keeps its fixed position: unknown orbit focus " + orbit.focusId());
            return;
        }
        entity.setCircularOrbit(focus, orbit.angle(), orbit.radius(), orbit.period());
    }

    /**
     * Remove the stable location a construction consumed, if this client still has it.
     *
     * <p>Redundant with the {@code CONSUME} the originator's watcher emits for the same entity, and
     * that redundancy is the point: whichever of the two lands first does the removal and the other
     * finds nothing to do. The tag check keeps a stale or malformed id from deleting something else.
     */
    private void removeConsumedStableLocation(SectorAPI sector, CoopWorldEntitySpawn spawn) {
        if (spawn.consumedEntityId().isBlank()) {
            return;
        }
        SectorEntityToken consumed = findEntityForDelta(sector, spawn.consumedEntityId());
        if (consumed == null || consumed.getContainingLocation() == null) {
            return;
        }
        if (!consumed.hasTag(Tags.STABLE_LOCATION)) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop spawn " + spawn.coopEntityId()
                    + " names " + spawn.consumedEntityId() + " as its consumed stable location, but"
                    + " that entity is not one; leaving it alone");
            return;
        }
        consumed.getContainingLocation().removeEntity(consumed);
        CoopLog.info(CoopCampaignReplicator.class, "Coop removed stable location "
                + spawn.consumedEntityId() + " consumed by construction " + spawn.coopEntityId());
    }

    /**
     * Stop a mirrored copy from expiring on a timer of its own.
     *
     * <p>Decay is not a script the creator attaches and we can decline to copy: {@code cargo_pods}
     * declares {@code CargoPodsEntityPlugin} as its {@code pluginClass} in
     * {@code data/config/custom_entities.json}, so every {@code addCustomEntity(Entities.CARGO_PODS,
     * ...)} — including the one above — gets the decay plugin whether we want it or not, running its
     * own {@code elapsed} from zero. Worse, the plugin's {@code maxDays} only grows past its 1-day
     * default inside {@code updateBaseMaxDays()}, which it calls only while the entity is in the
     * <em>local</em> player's current location: the mirror of a pod dropped in a system the receiving
     * player is nowhere near therefore expires after a single day. Whichever copy expires first while
     * its own player is in that location is reported as a {@code CONSUME}, which then takes the
     * still-live original out from under the player who created it.
     *
     * <p>So the mirror never expires; decay stays owned by the creating client, which reports the
     * removal as a {@code CONSUME} and takes the pod out on both sides — which is what the old
     * comment here claimed was already happening.
     */
    void pinMirrorExpiry(SectorEntityToken entity) {
        try {
            if (entity != null && entity.getCustomPlugin() instanceof CargoPodsEntityPlugin pods) {
                pods.setNeverExpire(true);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Failed to pin the mirrored entity's expiry; it may decay on its own", ex);
        }
    }

    /** Adds one {@code KIND:id} content entry back into a materialized pod's cargo. */
    private void addSpawnContent(CargoAPI cargo, String key, int quantity) {
        int split = key.indexOf(':');
        if (split <= 0 || split == key.length() - 1) {
            CoopLog.warn(CoopCampaignReplicator.class, "Malformed spawn content key: " + key);
            return;
        }
        String id = key.substring(split + 1);
        CoopWorldEntitySpawn.ItemKind kind;
        try {
            kind = CoopWorldEntitySpawn.ItemKind.valueOf(key.substring(0, split));
        } catch (IllegalArgumentException ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Unknown spawn content kind in key: " + key);
            return;
        }
        try {
            switch (kind) {
                case COMMODITY -> cargo.addCommodity(id, quantity);
                case WEAPON -> cargo.addWeapons(id, quantity);
                case FIGHTER -> cargo.addFighters(id, quantity);
                case SPECIAL -> addSpecial(cargo, id, quantity);
                // Pod ships still key by variant id, so they still arrive pristine (see
                // CoopWorldEntitySpawn.ItemKind.SHIP).
                case SHIP -> addMothballedShipsByVariant(cargo, id, quantity);
            }
        } catch (RuntimeException | LinkageError ex) {
            // A variant or spec this client cannot resolve: skip that stack rather than lose the pod.
            CoopLog.warn(CoopCampaignReplicator.class, "Could not restore pod content " + key, ex);
        }
    }

    private LocationAPI locationById(SectorAPI sector, String locationId) {
        if (locationId == null || locationId.isBlank()) {
            return null;
        }
        for (LocationAPI location : sector.getAllLocations()) {
            if (location != null && locationId.equals(location.getId())) {
                return location;
            }
        }
        LocationAPI hyperspace = sector.getHyperspace();
        return hyperspace != null && locationId.equals(hyperspace.getId()) ? hyperspace : null;
    }

    /** Test seam: replaces the {@code COLONY_MGMT} engine write, so a test can make it fail. */
    void setColonyMgmtApplyForTest(Predicate<CoopColonyManagement.State> apply) {
        colonyMgmtApply = Objects.requireNonNull(apply, "apply");
    }

    /** Test seam: the guest hire baseline a snapshot would have installed. */
    void setHireBaselineForTest(String marketId, Map<String, CoopMarketSync.ItemKind> baseline) {
        appliedHireables.put(marketId, new LinkedHashMap<>(baseline));
    }

    /** Test seam: the current guest hire baseline for a market, or null. */
    Map<String, CoopMarketSync.ItemKind> hireBaselineForTest(String marketId) {
        return appliedHireables.get(marketId);
    }

    /** Test seam: in play this is reached from the salvage watcher's per-frame removal diff. */
    void reportLocalSalvageConsumeForTest(String entityId) {
        reportLocalSalvageConsume(entityId);
    }

    private void reportLocalSalvageConsume(String entityId) {
        CoopWorldDelta delta = new CoopWorldDelta(entityId, CoopWorldDelta.Kind.CONSUME, true, "",
                session.localPlayerId());
        // Mark applied locally so we never re-report it and the host's rebroadcast echo is a no-op.
        if (worldLedger.apply(delta)) {
            reportWorldDelta(delta);
            // Phase 21 red-team item 11. handleWorldDelta tallies the salvage that arrives over the
            // wire, which is the *other* client's. This is the local one, and behind the same ledger:
            // apply() returns true once per entity, so the echo that comes back cannot double-count.
            tally(StatsSink::onSalvageConsumed);
            CoopLog.info(CoopCampaignReplicator.class, "Coop salvage CONSUME reported entity=" + entityId);
        }
    }

    // ---- World-affecting abilities ------------------------------------------------------------

    @Override
    public void onPlayerActivatedAbility(AbilityPlugin ability, Object param) {
        if (!isActive() || ability == null) {
            return;
        }
        // Entry guard, matching every other capture path: never re-capture while applying a host
        // packet. Latent today (no replay path fires abilities) but the odd one out without it.
        if (replayGuard.isReplaying()) {
            return;
        }
        String abilityId = ability.getId();
        if (!CoopAbilityArbiter.isWorldAffecting(abilityId)) {
            return; // purely-local ability; not arbitrated
        }
        // Guest reports its world-affecting ability up to the host; the host applies/broadcasts.
        if (isGuest() && !replayGuard.isReplaying()) {
            send(CoopMessages.abilityActivate(session.sessionId(), service.nextSeq(), now(),
                    abilityId, session.localPlayerId(), ""));
            CoopLog.info(CoopCampaignReplicator.class, "Coop ABILITY_ACTIVATE (world) abilityId=" + abilityId);
        }
    }

    private void hostHandleAbilityActivate(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String abilityId = payload.requiredString("abilityId");
        String playerId = payload.requiredString("playerId");
        // The host applies the world-affecting effect against its authoritative NPC fleets/world by
        // running the vanilla ability plugin on the guest's mirror fleet (Phase 12c A1). NPC fleet
        // state changes propagate back to the guest via the Phase 9 NPC_FLEET_SET rebroadcast, and
        // the interdiction standing hit rides the existing REP_DELTA capture.
        CoopAbilityEffectApplier.Decision decision = CoopAbilityEffectApplier.apply(abilityId, playerId);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied ABILITY_ACTIVATE abilityId=" + abilityId
                + " playerId=" + playerId + " decision=" + decision);
    }

    // ---- Engine helpers (defensive) -----------------------------------------------------------

    // Captures one submarket's stock as the Trade (or Storage) screen shows it: commodities,
    // weapons, fighters and specials (cargo stacks) plus ships (one listing per mothballed hull,
    // carrying its full CoopShipDetail). The hireable officer/merc/admin pool lives on the market
    // rather than in a submarket, so captureHireablePool rides the open-market snapshot only.
    // Which submarkets may be named here is submarketCargo's allowlist.
    private List<CoopMarketSync.StockItem> captureSubmarketStock(MarketAPI market, String specId) {
        List<CoopMarketSync.StockItem> items = new ArrayList<>();
        CargoAPI cargo = submarketCargo(market, specId);
        if (cargo == null) {
            return items;
        }
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            StackRef ref = classify(stack);
            if (ref == null) {
                continue;
            }
            int qty = Math.max(0, Math.round(stack.getSize()));
            if (qty > 0) {
                items.add(new CoopMarketSync.StockItem(ref.kind(), ref.id(), qty, 0f));
            }
        }
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships != null) {
            boolean isStorage = Submarkets.SUBMARKET_STORAGE.equals(specId);
            // One listing per member, keyed by the member id rather than the variant id: two hulls of
            // the same variant are not interchangeable once one of them has three D-mods and 40% CR,
            // and a per-variant count could not tell the guest which is which. The id on the wire is
            // origin-namespaced (see CoopMemberIds and the section banner above).
            for (FleetMemberAPI member : ships.getMembersListCopy()) {
                CoopShipDetail detail = stampWireMemberId(captureShipDetail(member));
                if (detail == null && isStorage) {
                    // Phase 32 (P2-9 / ship-detail P0-1): a locker listing is never dropped
                    // silently. captureShipDetail returns null on any throw anywhere in the capture
                    // -- a modded module variant, an accessor that raises -- and the failure is
                    // deterministic, so it recurs on every snapshot. Omitting the line publishes a
                    // locker that does not contain the partner's ship. A degraded line (identity,
                    // hull, CR, damage; no refit) at least keeps the hull in the shared locker and
                    // says out loud what was lost.
                    detail = degradedStoredHullDetail(member);
                }
                if (detail != null) {
                    items.add(new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP,
                            detail.memberId(), 1, 0f, detail.encode()));
                }
            }
        }
        return items;
    }

    /**
     * The fallback storage listing for a hull whose full capture threw: member identity, ship name,
     * base variant / hull spec, base CR and hull fraction, and nothing else.
     *
     * <p>The rebuild on the far side then produces a pristine hull rather than the battered one --
     * a real loss of D-mods and refit -- but the ship is still in the locker, which is the property
     * a locker has to keep. Returns null (with a WARN) only when even this much cannot be read, and
     * that is the one case where the listing is dropped.
     */
    private CoopShipDetail degradedStoredHullDetail(FleetMemberAPI member) {
        String memberId = memberIdOf(member);
        try {
            String wireId = CoopMemberIds.wireId(session.localPlayerId(), memberId);
            if (wireId.isEmpty()) {
                throw new IllegalStateException("mothballed member has no id");
            }
            String hullSpecId = "";
            String variantId = "";
            ShipVariantAPI variant = member.getVariant();
            if (variant != null) {
                variantId = orBlank(variant.getHullVariantId());
                hullSpecId = variant.getHullSpec() == null ? "" : orBlank(variant.getHullSpec().getHullId());
            }
            // baseVariantId is required text; a hull id is a usable stand-in because createBaseMember
            // falls through to the empty-variant-off-the-hull-spec branch when it names no variant.
            String baseVariantId = variantId.isBlank() ? hullSpecId : variantId;
            if (baseVariantId.isBlank()) {
                throw new IllegalStateException("neither a variant id nor a hull id is readable");
            }
            float baseCR = member.getRepairTracker() == null ? 0f : member.getRepairTracker().getBaseCR();
            float hullFraction = member.getStatus() == null ? 1f : member.getStatus().getHullFraction();
            CoopLog.warn(CoopCampaignReplicator.class, "Coop stored hull member=" + wireId
                    + " could not be captured in full; shipping a degraded listing (" + baseVariantId
                    + ", no refit/D-mods/weapons) so the locker still holds it");
            return new CoopShipDetail(wireId, orBlank(member.getShipName()), baseVariantId, hullSpecId,
                    baseCR, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                    Map.of(), Map.of(), List.of(), hullFraction, "", Map.of());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop stored hull member="
                    + (memberId.isEmpty() ? "?" : memberId) + " cannot be described at all;"
                    + " it stays on this engine and the partner will not see it", ex);
            return null;
        }
    }

    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * Everything about one listed hull that its variant id does not carry: the D-mod hull swap, perma
     * mods (which is what D-mods are), s-mods, the refit, suppressed mods, weapons, wings, vents/caps
     * and base CR. See {@link CoopShipDetail} for why each of those is separately load-bearing.
     *
     * <p>Phase 32 adds the four things a shared storage locker needs and a shop did not: the owner's
     * weapon groups, the current hull fraction, a renamed variant's display name, and the module
     * variants of a modular hull (by recursion — the Phase 12c gap). Officers are not captured:
     * vanilla keeps the officer with the player when a ship is stored.
     */
    static CoopShipDetail captureShipDetail(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null) {
            return null;
        }
        try {
            String memberId = member.getId();
            if (memberId == null || memberId.isBlank()) {
                CoopLog.warn(CoopCampaignReplicator.class,
                        "Coop ship listing skipped: mothballed member has no id");
                return null;
            }
            float baseCR = member.getRepairTracker() == null ? 0f : member.getRepairTracker().getBaseCR();
            // Modules have their own indexed hull fractions (getHullFraction(int)) but no documented
            // index-to-slot mapping, so only the ship's own hull rides the wire.
            float hullFraction = member.getStatus() == null ? 1f : member.getStatus().getHullFraction();
            return captureVariantDetail(member.getVariant(), memberId, member.getShipName(),
                    baseCR, hullFraction, 0);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture ship detail for member "
                    + (member.getId() == null ? "?" : member.getId()), ex);
            return null;
        }
    }

    /**
     * The variant half of the capture, which a module re-enters with {@code depth + 1}.
     *
     * <p>A module is a variant with no fleet member behind it, so it carries no member id, no ship
     * name, no base CR and no hull fraction of its own; those four are the caller's to supply and are
     * empty/zero/undamaged for a module. Recursion stops at
     * {@link CoopShipDetail#MAX_MODULE_NESTING} so a mod that manages to make a module cycle costs a
     * warning rather than the whole snapshot.
     */
    private static CoopShipDetail captureVariantDetail(ShipVariantAPI variant, String memberId,
                                                       String shipName, float baseCR,
                                                       float hullFraction, int depth) {
        List<String> permaMods = new ArrayList<>(orEmpty(variant.getPermaMods()));
        List<String> sMods = new ArrayList<>(orEmpty(variant.getSMods()));
        List<String> refitMods = new ArrayList<>();
        for (String modId : orEmpty(variant.getNonBuiltInHullmods())) {
            if (!permaMods.contains(modId)) {
                refitMods.add(modId);
            }
        }
        Map<String, String> weapons = new LinkedHashMap<>();
        for (String slotId : orEmptyList(variant.getNonBuiltInWeaponSlots())) {
            String weaponId = variant.getWeaponId(slotId);
            if (weaponId != null) {
                weapons.put(slotId, weaponId);
            }
        }
        Map<String, String> wings = new LinkedHashMap<>();
        List<String> builtInWings = variant.getHullSpec() == null
                ? List.of() : orEmptyList(variant.getHullSpec().getBuiltInWings());
        List<String> allWings = orEmptyList(variant.getWings());
        for (int i = 0; i < allWings.size(); i++) {
            String wingId = allWings.get(i);
            if (wingId == null || wingId.isBlank()) {
                continue;
            }
            if (i < builtInWings.size() && wingId.equals(builtInWings.get(i))) {
                continue; // built-in bay: the hull spec puts it back on its own
            }
            wings.put(Integer.toString(i), wingId);
        }
        List<CoopShipDetail.WeaponGroup> weaponGroups = captureWeaponGroups(variant);
        Map<String, CoopShipDetail> modules = captureModules(variant, memberId, depth);
        return new CoopShipDetail(memberId,
                shipName,
                variant.getHullVariantId(),
                variant.getHullSpec() == null ? "" : variant.getHullSpec().getHullId(),
                baseCR,
                variant.getNumFluxVents(),
                variant.getNumFluxCapacitors(),
                permaMods,
                sMods,
                new ArrayList<>(orEmpty(variant.getSModdedBuiltIns())),
                refitMods,
                new ArrayList<>(orEmpty(variant.getSuppressedMods())),
                weapons,
                wings,
                weaponGroups,
                hullFraction,
                variant.getDisplayName() == null ? "" : variant.getDisplayName(),
                modules);
    }

    /**
     * The owner's firing groups. Vanilla autogenerates groups for a variant that has none, so a
     * listing that arrives without any is not wrong — but a player who split a Conquest's broadsides
     * into four alternating groups and then stored it would get them silently re-merged.
     */
    private static List<CoopShipDetail.WeaponGroup> captureWeaponGroups(ShipVariantAPI variant) {
        List<CoopShipDetail.WeaponGroup> groups = new ArrayList<>();
        List<WeaponGroupSpec> specs = variant.getWeaponGroups();
        if (specs == null) {
            return groups;
        }
        for (WeaponGroupSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            // WeaponGroupType has exactly two constants, so a boolean is a total mapping and the
            // record stays free of game API types.
            groups.add(new CoopShipDetail.WeaponGroup(
                    new ArrayList<>(orEmptyList(spec.getSlots())),
                    spec.getType() == WeaponGroupType.ALTERNATING,
                    spec.isAutofireOnByDefault()));
        }
        return groups;
    }

    /** Each module slot's variant as a nested detail; empty for the overwhelmingly common hull. */
    private static Map<String, CoopShipDetail> captureModules(ShipVariantAPI variant, String memberId,
                                                              int depth) {
        Map<String, CoopShipDetail> modules = new LinkedHashMap<>();
        List<String> slots = orEmptyList(variant.getModuleSlots());
        if (slots.isEmpty()) {
            return modules;
        }
        if (depth >= CoopShipDetail.MAX_MODULE_NESTING) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing member=" + memberId
                    + " nests modules deeper than " + CoopShipDetail.MAX_MODULE_NESTING
                    + " levels; the rest are captured as their base variants");
            return modules;
        }
        for (String slotId : slots) {
            if (slotId == null || slotId.isBlank()) {
                continue;
            }
            ShipVariantAPI moduleVariant = variant.getModuleVariant(slotId);
            if (moduleVariant == null) {
                continue;
            }
            modules.put(slotId, captureVariantDetail(moduleVariant, "", "", 0f, 1f, depth + 1));
        }
        return modules;
    }

    private static Collection<String> orEmpty(Collection<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> orEmptyList(List<String> values) {
        return values == null ? List.of() : values;
    }

    // ---- Hireable officers / mercenaries / administrators (Phase 12c gap 2d) --------------------
    //
    // The pool is rolled per client by the sector's OfficerManagerEvent off Misc.random and
    // Math.random(), so host and guest saw different captains standing at the same bar. The host's
    // pool rides the MARKET_SNAPSHOT alongside the stock (one StockItem per person) and the guest
    // strips its own and rebuilds the host's.
    //
    // There is no vanilla hire event, so a guest hire is detected by diffing the market's hireable set
    // on close against the set the last snapshot applied; a person that vanished was hired.

    /**
     * The people at a market that are actually for hire.
     *
     * <p>The engine's own {@code available} / {@code availableAdmins} lists are protected, so the pool
     * is enumerated the way vanilla's dialog does: comm-directory PERSON entries carrying the
     * {@code $ome_hireable} memory flag.
     */
    private List<PersonAPI> hireablePeople(MarketAPI market) {
        List<PersonAPI> out = new ArrayList<>();
        if (market == null) {
            return out;
        }
        try {
            CommDirectoryAPI directory = market.getCommDirectory();
            if (directory == null || directory.getEntriesCopy() == null) {
                return out;
            }
            for (CommDirectoryEntryAPI entry : directory.getEntriesCopy()) {
                if (entry == null || entry.getType() != CommDirectoryEntryAPI.EntryType.PERSON) {
                    continue;
                }
                if (!(entry.getEntryData() instanceof PersonAPI person)) {
                    continue;
                }
                MemoryAPI memory = person.getMemoryWithoutUpdate();
                if (memory != null && memory.is(OME_HIREABLE, true)) {
                    out.add(person);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to enumerate hireable people at market "
                    + market.getId(), ex);
        }
        return out;
    }

    private static final String OME_HIREABLE = "$ome_hireable";
    private static final String OME_IS_ADMIN = "$ome_isAdmin";
    private static final String OME_ADMIN_TIER = "$ome_adminTier";

    /** Host: one StockItem per hireable person at the market. */
    private List<CoopMarketSync.StockItem> captureHireablePool(MarketAPI market) {
        List<CoopMarketSync.StockItem> items = new ArrayList<>();
        OfficerManagerEvent manager = officerManager();
        if (manager == null) {
            // The core sector-gen script always installs one, so this means something removed it. Say
            // so: the snapshot that follows claims "no hireables anywhere" and the guest will strip its
            // own pool to match it.
            CoopLog.warn(CoopCampaignReplicator.class, "No OfficerManagerEvent on the host sector;"
                    + " every market snapshot will report an empty hireable pool");
            return items;
        }
        for (PersonAPI person : hireablePeople(market)) {
            CoopPersonDetail detail = capturePersonDetail(manager, person);
            if (detail != null) {
                items.add(new CoopMarketSync.StockItem(detail.stockKind(), detail.personId(),
                        1, 0f, detail.encode()));
            }
        }
        return items;
    }

    private CoopPersonDetail capturePersonDetail(OfficerManagerEvent manager, PersonAPI person) {
        try {
            String personId = person.getId();
            if (personId == null || personId.isBlank()) {
                return null;
            }
            // hiringBonus/salary come from the engine's AvailableOfficer, never from the
            // $ome_hiringBonus / $ome_salary memory keys: those are pre-formatted display strings
            // (Misc.getWithDGS) and parsing them back would be separator-dependent.
            OfficerManagerEvent.AvailableOfficer entry = manager.getOfficer(personId);
            CoopPersonDetail.Role role;
            if (entry != null) {
                role = Misc.isMercenary(person) ? CoopPersonDetail.Role.MERC : CoopPersonDetail.Role.OFFICER;
            } else {
                entry = manager.getAdmin(personId);
                if (entry == null) {
                    CoopLog.debug(CoopCampaignReplicator.class, "Hireable person " + personId
                            + " has no OfficerManagerEvent entry; not replicated");
                    return null;
                }
                role = CoopPersonDetail.Role.ADMIN;
            }
            FullName name = person.getName();
            MemoryAPI memory = person.getMemoryWithoutUpdate();
            int adminTier = 0;
            if (role == CoopPersonDetail.Role.ADMIN && memory != null && memory.contains(OME_ADMIN_TIER)) {
                adminTier = memory.getInt(OME_ADMIN_TIER);
            }
            Map<String, Float> skills = new LinkedHashMap<>();
            if (person.getStats() != null && person.getStats().getSkillsCopy() != null) {
                for (MutableCharacterStatsAPI.SkillLevelAPI skill : person.getStats().getSkillsCopy()) {
                    if (skill != null && skill.getSkill() != null && skill.getSkill().getId() != null) {
                        skills.put(skill.getSkill().getId(), skill.getLevel());
                    }
                }
            }
            return new CoopPersonDetail(personId,
                    name == null ? "" : name.getFirst(),
                    name == null ? "" : name.getLast(),
                    person.getGender() == null ? FullName.Gender.ANY.name() : person.getGender().name(),
                    person.getPortraitSprite(),
                    person.getPersonalityAPI() == null ? "" : person.getPersonalityAPI().getId(),
                    person.getRankId(),
                    person.getPostId(),
                    person.getFaction() == null ? "" : person.getFaction().getId(),
                    person.getStats() == null ? 0 : person.getStats().getLevel(),
                    person.getStats() == null ? 0L : person.getStats().getXP(),
                    role,
                    entry.hiringBonus,
                    entry.salary,
                    adminTier,
                    // The lifetime rides along or the guest's own OfficerManagerEvent deletes the
                    // rebuilt person within its 1-3 day prune tick (see DEFAULT_LIFETIME_DAYS).
                    entry.timeRemaining,
                    skills);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture hireable person detail", ex);
            return null;
        }
    }

    /**
     * Guest: replace the market's hireable pool with the host's.
     *
     * <p>Strip-then-add through {@code OfficerManagerEvent} rather than the comm directory directly:
     * {@code addAvailable} is what sets {@code $ome_eventRef} to <em>this client's own</em> manager
     * script, and the hiring dialog reads that reference to complete the hire. Hand-placing a person
     * into the comm directory produces a captain who can be talked to and never hired.
     */
    private void applyHireablePool(MarketAPI market, List<CoopMarketSync.StockItem> items) {
        if (market == null) {
            return;
        }
        OfficerManagerEvent manager = officerManager();
        if (manager == null) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "No OfficerManagerEvent on this sector; hireable pool not replicated");
            return;
        }
        Map<String, CoopMarketSync.ItemKind> applied = new LinkedHashMap<>();
        try {
            for (PersonAPI person : hireablePeople(market)) {
                OfficerManagerEvent.AvailableOfficer entry = manager.getOfficer(person.getId());
                if (entry == null) {
                    entry = manager.getAdmin(person.getId());
                }
                if (entry != null) {
                    manager.removeAvailable(entry);
                }
            }
            for (CoopMarketSync.StockItem item : items) {
                if (CoopPersonDetail.roleOf(item.kind()) == null || item.detail().isEmpty()) {
                    continue;
                }
                CoopPersonDetail detail = CoopPersonDetail.decode(item.detail());
                PersonAPI person = buildPerson(detail);
                if (person == null) {
                    continue;
                }
                OfficerManagerEvent.AvailableOfficer entry = new OfficerManagerEvent.AvailableOfficer(
                        person, market.getId(), detail.hiringBonus(), detail.salary());
                // Without this the field stays at its 0f default and the local manager's own prune
                // tick deletes the person 1-3 campaign days later — comm-directory entry, hireable
                // flag and all. See CoopPersonDetail.DEFAULT_LIFETIME_DAYS.
                entry.timeRemaining = detail.timeRemainingDays() > 0f
                        ? detail.timeRemainingDays()
                        : CoopPersonDetail.DEFAULT_LIFETIME_DAYS;
                if (detail.role() == CoopPersonDetail.Role.ADMIN) {
                    manager.addAvailableAdmin(entry);
                } else {
                    manager.addAvailable(entry);
                }
                applied.put(detail.personId(), item.kind());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply hireable pool for market "
                    + market.getId(), ex);
        }
        appliedHireables.put(market.getId(), applied);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied hireable pool market=" + market.getId()
                + " people=" + applied.size());
    }

    private PersonAPI buildPerson(CoopPersonDetail detail) {
        try {
            PersonAPI person = Global.getFactory().createPerson();
            person.setId(detail.personId());
            person.setName(new FullName(detail.first(), detail.last(), genderOf(detail.gender())));
            if (!detail.factionId().isEmpty()) {
                person.setFaction(detail.factionId());
            }
            if (!detail.portraitSprite().isEmpty()) {
                person.setPortraitSprite(detail.portraitSprite());
            }
            if (!detail.rankId().isEmpty()) {
                person.setRankId(detail.rankId());
            }
            if (!detail.postId().isEmpty()) {
                person.setPostId(detail.postId());
            }
            if (!detail.personalityId().isEmpty()) {
                person.setPersonality(detail.personalityId());
            }
            MutableCharacterStatsAPI stats = person.getStats();
            if (stats != null) {
                // Vanilla's own build order (OfficerManagerEvent.createAdmin): batch the skill writes
                // behind skipRefresh, then refresh once. Refreshing per skill is both slow and,
                // mid-build, wrong.
                stats.setSkipRefresh(true);
                stats.setLevel(detail.level());
                stats.setXP(detail.xp());
                for (Map.Entry<String, Float> skill : detail.skills().entrySet()) {
                    stats.setSkillLevel(skill.getKey(), skill.getValue());
                }
                stats.setSkipRefresh(false);
                stats.refreshCharacterStatsEffects();
            }
            if (detail.role() == CoopPersonDetail.Role.MERC) {
                Misc.setMercenary(person, true);
            }
            if (detail.role() == CoopPersonDetail.Role.ADMIN && person.getMemoryWithoutUpdate() != null) {
                person.getMemoryWithoutUpdate().set(OME_IS_ADMIN, true);
                person.getMemoryWithoutUpdate().set(OME_ADMIN_TIER, detail.adminTier());
            }
            return person;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to rebuild hireable person "
                    + detail.personId(), ex);
            return null;
        }
    }

    private static FullName.Gender genderOf(String name) {
        try {
            return FullName.Gender.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return FullName.Gender.ANY;
        }
    }

    /**
     * Guest: a person that was in the last applied pool and is no longer hireable was hired locally.
     * There is no vanilla hire event, so this close-time diff is the claim.
     *
     * <p>No credit deduction rides with it: credits are per-player and the acting client's own engine
     * already charged the hiring bonus. All the host needs is the availability removal.
     */
    private void reportHiresOnClose(MarketAPI market) {
        Map<String, CoopMarketSync.ItemKind> applied = appliedHireables.get(market.getId());
        if (applied == null || applied.isEmpty()) {
            return;
        }
        Set<String> stillHireable = new HashSet<>();
        for (PersonAPI person : hireablePeople(market)) {
            stillHireable.add(person.getId());
        }
        CoopMarketSync.HireDiff diff = CoopMarketSync.diffHires(applied, stillHireable);
        for (Map.Entry<String, CoopMarketSync.ItemKind> entry : diff.hired().entrySet()) {
            // Stamped open_market because that is the snapshot the hireable pool rides; the host
            // routes hire kinds to the officer manager before the submarket ever matters
            // (hostApplyMarketTxn -> applyHireToEngine), so the id is provenance, not a target.
            sendMarketTxn(market.getId(), Submarkets.SUBMARKET_OPEN, entry.getValue(),
                    entry.getKey(), 1, "");
            CoopLog.info(CoopCampaignReplicator.class, "Coop hire claim " + entry.getValue()
                    + ":" + entry.getKey() + " market=" + market.getId());
        }
        appliedHireables.put(market.getId(), diff.remaining());
    }

    /** Host: a guest hired someone; take them out of the canonical pool. */
    private boolean applyHireToEngine(String marketId, String personId) {
        OfficerManagerEvent manager = officerManager();
        if (manager == null) {
            return false;
        }
        replayGuard.begin();
        try {
            OfficerManagerEvent.AvailableOfficer entry = manager.getOfficer(personId);
            if (entry == null) {
                entry = manager.getAdmin(personId);
            }
            if (entry == null) {
                CoopLog.warn(CoopCampaignReplicator.class, "Coop hire claim for unknown person "
                        + personId + " at market=" + marketId);
                return false;
            }
            manager.removeAvailable(entry);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply hire claim " + personId, ex);
            return false;
        } finally {
            replayGuard.end();
        }
    }

    /**
     * The sector's officer manager. It is placed by the core sector-gen script and lives in the
     * every-frame script list; there is no registry accessor for it.
     */
    private OfficerManagerEvent officerManager() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getScripts() == null) {
                return null;
            }
            for (EveryFrameScript script : sector.getScripts()) {
                if (script instanceof OfficerManagerEvent manager) {
                    return manager;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to locate OfficerManagerEvent", ex);
        }
        return null;
    }

    /** Identifying (kind, id) for a fungible cargo stack; null for kinds we don't sync. */
    private record StackRef(CoopMarketSync.ItemKind kind, String id) {
    }

    private StackRef classify(CargoStackAPI stack) {
        if (stack == null) {
            return null;
        }
        // Specials are checked first: a special stack is not a commodity stack, but putting the check
        // last invites the same "unknown -> commodity" fallthrough that mangled jettisoned AI cores.
        if (stack.isSpecialStack() && stack.getSpecialDataIfSpecial() != null) {
            SpecialItemData data = stack.getSpecialDataIfSpecial();
            if (data.getId() == null || data.getId().isBlank()) {
                return null;
            }
            return new StackRef(CoopMarketSync.ItemKind.SPECIAL,
                    CoopMarketSync.specialItemId(data.getId(), data.getData()));
        }
        if (stack.isCommodityStack() && stack.getCommodityId() != null) {
            return new StackRef(CoopMarketSync.ItemKind.COMMODITY, stack.getCommodityId());
        }
        if (stack.isWeaponStack() && stack.getWeaponSpecIfWeapon() != null) {
            return new StackRef(CoopMarketSync.ItemKind.WEAPON, stack.getWeaponSpecIfWeapon().getWeaponId());
        }
        if (stack.isFighterWingStack() && stack.getFighterWingSpecIfWing() != null) {
            return new StackRef(CoopMarketSync.ItemKind.FIGHTER, stack.getFighterWingSpecIfWing().getId());
        }
        return null;
    }

    private String shipVariantId(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null) {
            return null;
        }
        return member.getVariant().getHullVariantId();
    }

    /**
     * Host: run the same stock generation a physical dock runs, before a submarket is snapshotted.
     *
     * <p>Vanilla only stocks a submarket when a player is about to interact with it: the core trade UI
     * calls {@link SubmarketPlugin#updateCargoPrePlayerInteraction()} on the way in, and that call is
     * what actually fills a shop with commodities, weapons, fighters and hulls (see
     * {@code OpenMarketPlugin} in api_src; vanilla's own {@code PK_CMD} stocks a market off-screen by
     * calling exactly this on {@code SUBMARKET_OPEN}). For a market the host has never docked at that
     * call has never run, so {@code getCargoNullOk()} is null and {@link #captureSubmarketStock}
     * would publish an empty shop as canonical — which is what the guest then rendered.
     *
     * <p>Re-roll frequency stays vanilla-equivalent because the plugin self-limits and we add no
     * guard of our own: commodity restock is proportional to {@code sinceLastCargoUpdate} (zeroed on
     * every call, and vanilla explicitly refuses the sub-one-unit add so repeated re-checking cannot
     * accelerate it), and the ship/weapon re-roll is gated by {@code okToUpdateShipsAndWeapons()},
     * i.e. once per 30 campaign days. A guest opening a market N times costs what a player docking N
     * times costs, no more.
     *
     * <p><b>Storage is deliberately not stocked</b> (Phase 32): {@code StoragePlugin}'s
     * {@code updateCargoPrePlayerInteraction} is empty and the locker is not a shop that rolls, so
     * there is nothing to spend. {@link #submarketCargo(MarketAPI, String)} materializes it with
     * {@code getCargo()} instead, which is all a locker ever needs.
     */
    private void ensureSubmarketStocked(MarketAPI market, String specId) {
        if (market == null || Submarkets.SUBMARKET_STORAGE.equals(specId)
                || !isSharedSubmarket(specId) || !market.hasSubmarket(specId)) {
            return;
        }
        SubmarketAPI submarket = market.getSubmarket(specId);
        SubmarketPlugin plugin = submarket == null ? null : submarket.getPlugin();
        if (plugin == null) {
            return;
        }
        boolean neverStocked = submarket.getCargoNullOk() == null;
        replayGuard.begin();
        try {
            plugin.updateCargoPrePlayerInteraction();
            CoopLog.info(CoopCampaignReplicator.class, "Coop pre-snapshot stock update market="
                    + market.getId() + " submarkets=[" + specId + "]"
                    + " neverStockedBefore=" + neverStocked
                    + " stacks=" + stackCount(submarket.getCargoNullOk()));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to update submarket stock before"
                    + " snapshot market=" + market.getId() + " submarket=" + specId, ex);
        } finally {
            replayGuard.end();
        }
    }

    private int stackCount(CargoAPI cargo) {
        List<CargoStackAPI> stacks = cargo == null ? null : cargo.getStacksCopy();
        return stacks == null ? 0 : stacks.size();
    }

    private CargoAPI submarketCargo(String marketId, String specId) {
        return submarketCargo(findMarket(marketId), specId);
    }

    /**
     * The one accessor every market capture, pre-stock, snapshot apply and per-item delta in this
     * class goes through, and the allowlist that says which submarkets they may reach (Phase 32,
     * replacing the Phase 18 open-market-only fence).
     *
     * <p><b>Allowed</b> — {@link #SHARED_SUBMARKETS}: {@code open_market}, {@code black_market},
     * {@code generic_military} and {@code storage}. All four are the same submarket-cargo shape and
     * are host-canonical, but they are <em>four separate inventories</em>: a snapshot apply is a
     * full replacement, so the specId a snapshot names is the only thing standing between the
     * host's shop roll and the player's parked ships. That is the whole boundary this method exists
     * to hold. Storage is reached with {@code getCargo()} — materialize it, nothing rolls, and a
     * deposit must never be dropped for want of a lazily-built cargo — while the three shops keep
     * {@code getCargoNullOk()} plus their {@link #ensureSubmarketStocked} call, because for a shop
     * "not stocked yet" is a real state that must not be answered with an empty shelf.
     *
     * <p><b>Denied, loudly</b> — {@code local_resources}
     * ({@link Submarkets#LOCAL_RESOURCES}) is a derived view of the colony's own production rather
     * than a stocked shop, so replacing it would fight the economy every month; and anything else is
     * a submarket this build has never reasoned about. Both get a WARN and a null: silently reading
     * the open market instead is exactly how a storage move once landed on the host's shop shelf.
     *
     * <p>Guest-side transaction capture in
     * {@link #onPlayerMarketTransaction(PlayerMarketTransaction)} filters against the same
     * allowlist and stamps the spec id on the wire, so the host applies every {@code MARKET_TXN}
     * to the submarket it actually happened in.
     *
     * <p>{@code CoopCampaignReplicatorStorageFenceTest} pins the boundary in both directions: a
     * storage snapshot reaches storage and nothing else, an open-market snapshot never reaches
     * storage, and {@code local_resources} is never read or written at all.
     */
    private CargoAPI submarketCargo(MarketAPI market, String specId) {
        if (!isSharedSubmarket(specId)) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop market sync refused submarket="
                    + specId + " market=" + (market == null ? "null" : market.getId())
                    + "; only " + SHARED_SUBMARKETS + " are host-canonical shared inventories");
            return null;
        }
        if (market == null || !market.hasSubmarket(specId)) {
            return null;
        }
        SubmarketAPI submarket = market.getSubmarket(specId);
        if (submarket == null) {
            return null;
        }
        // Storage: materialize. The locker rolls nothing, and a guest deposit that arrives before
        // this client ever opened storage here must still land somewhere.
        return Submarkets.SUBMARKET_STORAGE.equals(specId)
                ? submarket.getCargo() : submarket.getCargoNullOk();
    }

    /** True for the four submarkets Phase 32 shares; everything else is denied by the accessor. */
    private static boolean isSharedSubmarket(String specId) {
        return specId != null && SHARED_SUBMARKETS.contains(specId);
    }

    /**
     * The allowlisted submarkets present at this market that the host should snapshot, in a stable
     * order. Storage is included only when the coop unlock says the locker is open — an unlocked
     * storage submarket exists on every market in the sector, and snapshotting one nobody has paid
     * for would ship an empty locker the guest would then apply over its own.
     */
    private List<String> snapshotTargets(MarketAPI market) {
        List<String> targets = new ArrayList<>(SHARED_SUBMARKETS.size());
        if (market == null) {
            return targets;
        }
        for (String specId : SHARED_SUBMARKETS) {
            if (!market.hasSubmarket(specId)) {
                continue;
            }
            if (Submarkets.SUBMARKET_STORAGE.equals(specId)
                    && !CoopStorageUnlock.isUnlocked(Global.getSector(), market)) {
                continue;
            }
            targets.add(specId);
        }
        return targets;
    }

    /** The campaign clock's timestamp, or 0 when there is no readable clock. Total. */
    private long campaignTimestamp() {
        try {
            SectorAPI sector = Global.getSector();
            CampaignClockAPI clock = sector == null ? null : sector.getClock();
            return clock == null ? 0L : clock.getTimestamp();
        } catch (RuntimeException | LinkageError ex) {
            return 0L;
        }
    }

    /**
     * The economy lookup every market-id-keyed apply in this class goes through, and therefore the
     * one place an inbound id is resolved to a local market (Phase 32 addition A).
     *
     * <p>{@link CoopMarketIds#toLocal(String)} is the identity function for every market whose id
     * already agrees across the two engines -- which is all of them except a mirrored pirate or
     * Luddic-Path base, whose market the vanilla constructor mints with {@code Misc.genUID()} -- and
     * is always the identity on the host. It is also idempotent for an id that is already local, so
     * callers holding a local id (the colony path, the bridge) are unaffected.
     */
    private MarketAPI findMarket(String marketId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getEconomy() == null) {
            return null;
        }
        return sector.getEconomy().getMarket(marketIds.toLocal(marketId));
    }

    private float playerRelationshipTo(String factionId) {
        FactionAPI player = playerFaction();
        return player == null ? CoopRepDelta.BASELINE : player.getRelationship(factionId);
    }

    private static float clampRelationship(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private FactionAPI playerFaction() {
        SectorAPI sector = Global.getSector();
        return sector == null ? null : sector.getPlayerFaction();
    }

    private PersonAPI findPerson(String personId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getImportantPeople() == null) {
            return null;
        }
        return sector.getImportantPeople().getPerson(personId);
    }

    private void send(CoopMessages.Message message) {
        service.send(message);
    }

    /**
     * Phase 20.5 unicast: an answer that is only meaningful to the peer that asked. A null or unknown
     * id falls back to a broadcast inside the transport, which is what every pre-lobby and
     * host-originated case wants.
     */
    private void sendTo(String senderId, CoopMessages.Message message) {
        service.sendTo(senderId, message);
    }

    private long now() {
        return clock.getAsLong();
    }

    private boolean isHost() {
        return service.role() == CoopConnectionRole.HOST;
    }

    private boolean isGuest() {
        return service.role() == CoopConnectionRole.GUEST;
    }

    private boolean isActive() {
        return session.handshakeValidated() && session.seedLong() != null && session.sessionId() != null;
    }

    // ---- Accessors (wiring + tests) -----------------------------------------------------------

    public ReplayGuard replayGuard() {
        return replayGuard;
    }

    public float repRelationship(CoopRepDelta.TargetType type, String targetId) {
        return CoopRepDelta.relationship(repTable, type, targetId);
    }

    public CoopFactionRelations factionRelations() {
        return factionRelations;
    }

    public CoopMissionBoardSync missionBoard() {
        return missionBoard;
    }

    public CoopMarketSync marketSync() {
        return marketSync;
    }

    public CoopWorldDelta.Ledger worldLedger() {
        return worldLedger;
    }

    public CoopRaidOutcomeSync.Ledger raidLedger() {
        return raidLedger;
    }

    public CoopColonySync.Ledger colonyLedger() {
        return colonyLedger;
    }

    public CoopColonyManagement.Ledger colonyMgmtLedger() {
        return colonyMgmtLedger;
    }

    public CoopColonyManagement.Diff colonyMgmtDiff() {
        return colonyMgmtDiff;
    }

    public CoopColonyManagement.Poll colonyMgmtPoll() {
        return colonyMgmtPoll;
    }

    /** Test/diagnostic seam: month-end banners not yet posted to the campaign UI. */
    public int pendingIncomeBannerCount() {
        return pendingIncomeBanners.size();
    }

    /** Test/diagnostic seam: the last warning set the guest was told to reconcile against. */
    public List<CoopExpeditionWarning> desiredExpeditionWarnings() {
        return desiredWarnings;
    }

    public CoopSkeletonMutationWatcher skeletonWatcher() {
        return skeletonWatcher;
    }

    // ---- Phase 30 agent-bridge facades (dev tooling) -------------------------------------------
    //
    // The dormant agent bridge (coop.debug.CoopAgentBridge, -Dcoop.debug.bridge=<port>) is the
    // *second* caller of the capture and apply routines below. They stay private because the
    // replication path is their only production caller; these narrow public wrappers exist so the
    // bridge does not grow a parallel set of state readers that could drift from the wire's.
    // Nothing in the replication path calls them.

    /**
     * Bridge-only: one submarket's stock as a dock would show it.
     *
     * <p>{@code stockFirst} is the host/guest split the bridge's {@code market} verb needs. On the
     * host it is {@code true}, so this runs the same {@link #ensureSubmarketStocked} a real dock (and
     * {@link #broadcastMarketSnapshot}) runs before capturing — a market the host has never docked at
     * has no stock at all, and dumping it un-stocked would report an empty shop as canonical. That
     * generation is intended, not a bug: it is exactly what makes a host dump comparable to a guest
     * dump of the same market. On the guest it is {@code false} — the guest is not allowed to roll
     * stock, so the bridge reports its raw current cargo and lets
     * {@link #submarketStockedForBridge} say whether there is any.
     *
     * <p>{@code specId} is checked by the same allowlist the wire uses, so the bridge cannot dump
     * (or generate) a submarket the replication path would refuse to touch.
     */
    public List<CoopMarketSync.StockItem> captureMarketStockForBridge(MarketAPI market, String specId,
                                                                     boolean stockFirst) {
        if (market == null) {
            return new ArrayList<>();
        }
        if (stockFirst) {
            ensureSubmarketStocked(market, specId);
        }
        List<CoopMarketSync.StockItem> items = captureSubmarketStock(market, specId);
        if (Submarkets.SUBMARKET_OPEN.equals(specId)) {
            items.addAll(captureHireablePool(market));
        }
        return items;
    }

    /**
     * Bridge-only: whether this client's copy of that submarket has ever been stocked. False means
     * "never docked here", which the bridge reports as {@code "stocked":false} rather than as an
     * empty shop. Storage answers on {@code getCargoNullOk()} too — a locker nobody has opened on
     * this client is genuinely nothing to compare against.
     */
    public boolean submarketStockedForBridge(MarketAPI market, String specId) {
        if (market == null || !isSharedSubmarket(specId) || !market.hasSubmarket(specId)) {
            return false;
        }
        SubmarketAPI open = market.getSubmarket(specId);
        return open != null && open.getCargoNullOk() != null;
    }

    /** Bridge-only: second caller of {@link #collectSurveyState}. */
    public void collectSurveyStateForBridge(LocationAPI location, Map<String, String> surveyOut,
                                            Map<String, String> ruinsOut) {
        if (location == null) {
            return;
        }
        collectSurveyState(location, surveyOut, ruinsOut);
    }

    /**
     * Bridge-only: second caller of {@link #applySurveyLevelToEngine}, so the {@code surveyset} verb
     * writes through the same max-wins/{@code setFullySurveyed} path a replicated SURVEY delta does.
     */
    public void applySurveyLevelForBridge(String planetId, String surveyLevelName) {
        applySurveyLevelToEngine(new CoopWorldDelta(planetId, CoopWorldDelta.Kind.SURVEY, false,
                surveyLevelName, session.localPlayerId()));
    }

    /**
     * Bridge-only: second caller of {@link #applyObjectiveOwnershipToEngine}, so the
     * {@code objective} verb flips ownership through the same engine writes the dialog's capture
     * ends up producing (faction set + {@code OBJECTIVE_NON_FUNCTIONAL} cleared).
     */
    public void applyObjectiveOwnershipForBridge(String entityId, String factionId) {
        applyObjectiveOwnershipToEngine(new CoopWorldDelta(entityId,
                CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, false, factionId, session.localPlayerId()));
    }

    // ---- Phase 32 addition B: credit transfer ----------------------------------------------------

    /**
     * The credit transfer this replicator owns, installed as the static handle the options page
     * reads. Constructed here rather than in the pump because this is the class that already has
     * the session, the sequence counter and the send seam it needs, and because the inbound half is
     * an ordinary case on {@link #handle}.
     */
    private CoopCreditTransfer creditTransfer = CoopCreditTransfer.live(new GrantLink());

    {
        // Field initializer, not the constructor: there are two constructors and only one of them
        // does any work, and installing from a field block cannot be forgotten by a third.
        CoopCreditTransfer.install(creditTransfer);
    }

    /** The wire half of {@link CoopCreditTransfer}, bound to this replicator's session and service. */
    private final class GrantLink implements CoopCreditTransfer.Link {

        @Override
        public boolean canSend() {
            return isActive() && service.isConnected()
                    && service.role() != coop.net.CoopConnectionRole.NONE;
        }

        @Override
        public String mintLedgerId() {
            // Player id plus the transport's own monotonic sequence: unique within the session
            // without a UUID, and readable in a log line next to the seq of the message carrying it.
            return session.localPlayerId() + "-" + service.nextSeq();
        }

        @Override
        public void sendGrant(String ledgerId, int amount, String reason) {
            send(CoopMessages.creditsGrant(session.sessionId(), service.nextSeq(), now(),
                    ledgerId, amount, reason));
        }
    }

    /** The sender-side transfer, for the options page's Send button and for tests. */
    public CoopCreditTransfer creditTransfer() {
        return creditTransfer;
    }

    /**
     * Test seam: swaps the live wallet and feed for a fake while keeping this replicator's real
     * {@link GrantLink}, so a test exercises the actual session id, sequence and ledger-id minting
     * rather than a stand-in for them. Never called from production code.
     */
    CoopCreditTransfer replaceCreditTransferEngineForTest(CoopCreditTransfer.Engine engine) {
        creditTransfer = new CoopCreditTransfer(engine, new GrantLink());
        CoopCreditTransfer.install(creditTransfer);
        return creditTransfer;
    }

    /**
     * Inbound {@code CREDITS_GRANT}: credit the local player once per ledger id. Both roles receive;
     * this is the one message on the wire that moves money and it moves it in either direction.
     *
     * <p>A malformed grant is logged and dropped rather than guessed at — see
     * {@link CoopMessages#parseCreditsGrant}.
     */
    private void handleCreditsGrant(CoopMessages.Message message) {
        try {
            CoopMessages.CreditsGrant grant = CoopMessages.parseCreditsGrant(message);
            creditTransfer.receive(grant.ledgerId(), grant.amount(), grant.reason());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop could not apply a CREDITS_GRANT", ex);
        }
    }
}
