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
import coop.net.CoopNetPumpInstaller;
import coop.net.CoopNetService;
import coop.net.CoopNetStartupConfig;
import coop.save.CoopGuestSnapshot;
import coop.save.CoopGuestSnapshotStore;
import coop.save.CoopSaveCheckpoint;
import coop.seed.CoopSeedSync;
import coop.util.CoopLog;

public class CoopModPlugin extends BaseModPlugin {
    private CoopNetService netService;

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
        // The held guest snapshot belongs to the session that just ended; the copy already written
        // into the previous save is untouched, which is the point of it.
        CoopGuestSnapshotStore.clear();
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
        CoopNetPumpInstaller.install(Global.getSector(), netService);
        // Phase 30 dev tooling, dormant unless -Dcoop.debug.bridge=<port> is set: no socket, no log
        // line and no script when it is absent. Installed here rather than inside the pump because it
        // has to answer before and without a coop session, and always as a TRANSIENT script — it owns
        // live channels and XStream must never walk it into a save. Installed after the pump so the
        // scan that finds the pump's capture code has something to find on the first bridged frame.
        CoopAgentBridge.install(Global.getSector());
        CoopSeedSync.storeCurrentSectorFingerprint();
        CoopLog.info(CoopModPlugin.class, "CoopNetPump registered");
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
    }

    /**
     * Coordinated saves (Phase 16): every completed host save tells the guest to take its own
     * autosave. This fires on the guest too — the role gate lives in the pump's sender, so a guest's
     * coordinated autosave cannot echo a checkpoint back at the host.
     */
    @Override
    public void afterGameSave() {
        CoopFullFidelitySystemDriver.endSave();
        CoopSaveCheckpoint.notifyLocalGameSaved(CoopSaveCheckpoint.REASON_HOST_SAVE);
    }
}
