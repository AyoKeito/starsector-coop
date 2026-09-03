package coop;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.thoughtworks.xstream.XStream;
import coop.fleet.CoopFullFidelitySystemDriver;
import coop.fleet.CoopGuestMirrorHandle;
import coop.fleet.CoopLocations;
import coop.fleet.CoopMirrorOrphanSweeper;
import coop.debug.CoopAgentBridge;
import coop.fleet.CoopSystemDriveFrameHook;
import coop.net.CoopNetPump;
import coop.net.CoopNetPumpInstaller;
import coop.net.CoopNetService;
import coop.net.CoopNetStartupConfig;
import coop.net.CoopStallNotice;
import coop.ui.CoopSessionIntelFeed;
import coop.save.CoopGuestSnapshot;
import coop.save.CoopGuestSnapshotStore;
import coop.save.CoopSaveCheckpoint;
import coop.seed.CoopSeedSync;
import coop.stats.CoopSessionStats;
import coop.stats.CoopSessionStatsStore;
import coop.ui.CoopLinkHud;
import coop.ui.CoopSessionIntel;
import coop.config.CoopOptionsPolicy;
import coop.ui.CoopOptionsPage;
import coop.ui.CoopSessionStatsIntel;
import coop.util.CoopLog;

public class CoopModPlugin extends BaseModPlugin {
    private CoopNetService netService;
    /**
     * The previous game's pump, kept only so its router mapping can be released on the next load.
     * {@code onGameLoad} is the sole teardown hook the engine offers a mod — there is no quit or exit
     * callback — so a process that never loads another game leaves the mapping to expire on its own
     * one-hour lease. That is why the lease is short.
     */
    private CoopNetPump netPump;

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        CoopLog.info(CoopModPlugin.class, "CoopModPlugin loaded");
        String newGameSeed = CoopNetStartupConfig.newGameSeedFromSystemProperties();
        if (!newGameSeed.isEmpty()) {
            CoopLog.info(CoopModPlugin.class,
                    "Coop new-game seed override configured via "
                            + CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY
                            + "=" + newGameSeed);
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (netService != null) {
            // Graceful session end: loading another game tears this session's transport down, so the
            // partner gets one last checkpoint before the socket closes (the send is flushed inline).
            // There is no engine hook for quitting to the menu or exiting, so this is the only
            // graceful end the mod can observe in-process.
            CoopSaveCheckpoint.notifySessionEnding();
            netService.shutdown();
        }
        if (netPump != null) {
            tearDownPreviousPump(netPump);
            netPump = null;
        }
        // The held guest snapshot belongs to the session that just ended; the copy already written
        // into the previous save is untouched, which is the point of it.
        CoopGuestSnapshotStore.clear();
        // Same shape: the previous session's tally belongs to the game that just ended. The copy
        // already written into that save is untouched; the new pump reads its own back.
        CoopSessionStatsStore.clear();
        // Process-wide caches keyed to the game that just ended: the location id map (ids are only
        // unique within a campaign) and the handle on the previous session's mirror fleet.
        CoopLocations.invalidate();
        CoopGuestMirrorHandle.clear();
        netService = new CoopNetService();
        // Before the pump installs: no session can be active yet, so this only ever sees mirrors
        // orphaned by a previous session's save (see CoopMirrorOrphanSweeper).
        CoopMirrorOrphanSweeper.sweep(Global.getSector());
        // Before the pump, so it sits earlier in the transient script list and runs first each frame:
        // it captures the engine's per-frame delta for the full-fidelity guest-system drive and is the
        // always-on observer that releases the drive when the pump stops ticking it.
        CoopSystemDriveFrameHook.install(Global.getSector());
        CoopNetPump pump = CoopNetPumpInstaller.install(Global.getSector(), netService);
        netPump = pump;
        // Phase 20.6 milestone 0: the always-on link status line. Cosmetic and self-disabling, so it
        // goes in right after the pump it reads from and before anything that could fail on its own.
        CoopLinkHud.install(Global.getSector(), pump);
        // Phase 20.6: the "Coop Session" intel page. Registered after the pump because the pump's
        // constructor installs the feed the page reads. Transient since Phase 21: removed before
        // every save and recreated here and after one, so no instance of it ever reaches XStream and
        // a solo load of a coop save has nothing to hide.
        CoopSessionIntel.ensureRegistered(Global.getSector());
        // Phase 21: the "Coop Stats" page, on the same transient lifecycle. The counters themselves
        // live in the save under CoopSessionStats.PERSISTENT_KEY; only the page is recreated.
        CoopSessionStatsIntel.ensureRegistered(Global.getSector());
        // Phase 28 milestone 3: the "Coop Options" page, same transient lifecycle again. The values
        // it edits live in the campaign's persistent data (policy) and in saves/common (local
        // preferences); the page itself is pure UI and holds nothing.
        CoopOptionsPage.ensureRegistered(Global.getSector());
        // Phase 30 dev tooling, dormant unless -Dcoop.debug.bridge=<port> is set: no socket, no log
        // line and no script when it is absent. Installed here rather than inside the pump because it
        // has to answer before and without a coop session, and always as a TRANSIENT script — it owns
        // live channels and XStream must never walk it into a save. Installed after the pump so the
        // scan that finds the pump's capture code has something to find on the first bridged frame.
        CoopAgentBridge.install(Global.getSector());
        CoopSeedSync.storeCurrentSectorFingerprint();
        CoopLog.info(CoopModPlugin.class, "CoopNetPump registered");
    }

    /**
     * Everything the outgoing pump owns that the engine will not clean up for us. Package-private and
     * static so it can be tested: {@code onGameLoad} itself needs a live {@code Global.getSector()}.
     *
     * @param previous the pump being replaced; null is accepted so the static handles can be cleared
     *                 without one
     */
    static void tearDownPreviousPump(CoopNetPump previous) {
        if (previous != null) {
            // Releasing the router mapping for a port we are about to re-open would undo the new
            // session's own mapping (Phase 20.3), so this must run before the new pump is built.
            previous.shutdownPortMapper();
        }
        // Red-team B6: the intel page's feed handle is installed by every pump and was never taken
        // down, so the page kept rendering the previous game's session - role, partner name, RTT and
        // all - over a save that has no coop session in it at all. The new pump installs its own
        // feed; this is the window between the two.
        CoopSessionIntelFeed.uninstall();
        // Phase 28: same window, same argument. The policy belongs to the campaign the outgoing pump
        // was playing, and the options page must not edit it into the next one.
        CoopOptionsPolicy.uninstall();
        // Phase 21, same argument for the stats page: its source is a lambda closed over the outgoing
        // pump's tally, and leaving it installed would render the previous game's counters over a
        // save that has no coop session in it.
        CoopSessionStatsIntel.clearSource();
        // Same shape for the stall hook: with no pump there is nothing to route beforeGameSave to.
        CoopStallNotice.setActive(null);
    }

    @Override
    public void onNewGameAfterProcGen() {
        CoopSeedSync.storeCurrentSectorFingerprint();
    }

    /**
     * Aliases for the Phase 16 guest snapshot, so the entry the host save carries under
     * {@code coop.guestFleetSnapshot} is named rather than spelled as a package path — a rename would
     * otherwise make every existing save unreadable.
     */
    @Override
    public void configureXStream(XStream x) {
        super.configureXStream(x);
        x.alias("coopGuestSnap", CoopGuestSnapshot.class);
        x.alias("coopGuestSnapStack", CoopGuestSnapshot.CargoStack.class);
        x.alias("coopGuestSnapShip", CoopGuestSnapshot.Ship.class);
        x.alias("coopGuestSnapOfficer", CoopGuestSnapshot.Officer.class);
        // Phase 21 session stats, same argument: the tally rides the save under
        // CoopSessionStats.PERSISTENT_KEY, and a package rename without these aliases would make
        // every save carrying one unreadable.
        x.alias("coopStats", CoopSessionStats.class);
        x.alias("coopStatsPlayer", CoopSessionStats.PlayerStats.class);
        x.alias("coopStatsLoss", CoopSessionStats.ShipLoss.class);
    }

    /**
     * Belt and braces for the full-fidelity guest-system drive. The drive never removes anything from
     * the engine, so a save taken mid-drive is already correct; this just guarantees nothing of ours
     * reorders a list or advances a location while XStream is walking the sector.
     *
     * <p>Also the moment the guest's snapshot is folded into the host save (Phase 16) — write-only
     * disaster-recovery material, see {@link CoopGuestSnapshotStore}.
     */
    @Override
    public void beforeGameSave() {
        CoopFullFidelitySystemDriver.beginSave();
        CoopGuestSnapshotStore.writeIntoCurrentSector();
        // Phase 21: the tally goes in, the two intel pages come out. Both pages are transient by
        // design (see CoopSessionIntel) -- nothing of either class may reach XStream -- while the
        // counters they render are plain data and are exactly what has to survive.
        CoopSessionStatsStore.writeIntoCurrentSector();
        CoopSessionIntel.remove(Global.getSector());
        CoopSessionStatsIntel.remove(Global.getSector());
        CoopOptionsPage.remove(Global.getSector());
        // Red-team B4: both roles announce the stall a save is about to cause, flushed inline, so
        // the partner's link-death rule does not read a guest's manual save as a dead link. Last,
        // because the two calls above are what make the save correct and this one is courtesy.
        CoopStallNotice.notifyLocalStall(CoopStallNotice.REASON_LOCAL_SAVE,
                CoopStallNotice.SAVE_EXPECTED_MILLIS);
    }

    /**
     * Coordinated saves (Phase 16): every completed host save tells the guest to take its own
     * autosave. This fires on the guest too — the role gate lives in the pump's sender, so a guest's
     * coordinated autosave cannot echo a checkpoint back at the host.
     */
    @Override
    public void afterGameSave() {
        CoopFullFidelitySystemDriver.endSave();
        // Phase 21: put the transient pages back, before anything else. The save is written; from
        // here the player is back in the campaign and expects the intel screen intact.
        CoopSessionIntel.ensureRegistered(Global.getSector());
        CoopSessionStatsIntel.ensureRegistered(Global.getSector());
        CoopOptionsPage.ensureRegistered(Global.getSector());
        CoopSaveCheckpoint.notifyLocalGameSaved(CoopSaveCheckpoint.REASON_HOST_SAVE);
    }
}
