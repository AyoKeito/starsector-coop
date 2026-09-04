package coop;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.thoughtworks.xstream.XStream;
import coop.fleet.CoopFullFidelitySystemDriver;
import coop.fleet.CoopGuestMirrorHandle;
import coop.fleet.CoopGuestPresence;
import coop.fleet.CoopLocations;
import coop.fleet.CoopMirrorOrphanSweeper;
import coop.config.CoopOptionsRegistry;
import coop.debug.CoopAgentBridge;
import coop.handshake.CoopGameVersionCheck;
import coop.fleet.CoopSystemDriveFrameHook;
import coop.newgame.CoopWorldSettings;
import coop.net.CoopNetPump;
import coop.net.CoopNetPumpInstaller;
import coop.net.CoopNetService;
import coop.net.CoopNetStartupConfig;
import coop.net.CoopStallNotice;
import coop.ui.CoopSessionIntelFeed;
import coop.save.CoopGuestSnapshot;
import coop.save.CoopGuestSnapshotStore;
import coop.save.CoopCampaignGuard;
import coop.save.CoopSaveCheckpoint;
import coop.save.CoopSaveIndex;
import coop.seed.CoopSeedSync;
import coop.stats.CoopSessionStats;
import coop.stats.CoopSessionStatsStore;
import coop.ui.CoopCampaignNotice;
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
        publishLauncherProperties();
        checkGameVersion();
        String newGameSeed = CoopNetStartupConfig.newGameSeedFromSystemProperties();
        if (!newGameSeed.isEmpty()) {
            CoopLog.info(CoopModPlugin.class,
                    "Coop new-game seed override configured via "
                            + CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY
                            + "=" + newGameSeed);
            publishSeedForTheForks(newGameSeed);
        }
    }

    /**
     * Phase 31: every {@code -D}-only key the launcher wrote into the settings file becomes a real
     * system property, unless the command line already carries one (a real {@code -D} stays the top
     * of the stack). The debug hatches and kill switches ({@code coop.debug.*}, {@code coop.ff.disable},
     * {@code coop.clock.disable}, {@code coop.fullFidelityGuestSystem}, {@code coop.adoptCampaignId})
     * are read with {@code System.getProperty} by their owners and nothing else; this is the one
     * place that lets a launcher-written value reach them. The seed goes through
     * {@link #publishSeedForTheForks} instead, because it has to be validated first.
     */
    private static void publishLauncherProperties() {
        java.util.Map<String, String> overrides;
        try {
            overrides = coop.config.CoopOptionsStore.system().launcherOverrides();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopModPlugin.class, "Coop could not read the launcher overrides", ex);
            return;
        }
        for (java.util.Map.Entry<String, String> entry : overrides.entrySet()) {
            String key = entry.getKey();
            if (CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY.equals(key)) {
                continue;
            }
            if (System.getProperty(key) != null) {
                CoopLog.info(CoopModPlugin.class, "Coop keeps the command line's -D" + key
                        + "; the settings file's value is ignored");
                continue;
            }
            System.setProperty(key, entry.getValue());
            CoopLog.info(CoopModPlugin.class, "Coop published the settings-file flag -D" + key
                    + "=" + entry.getValue() + " for readers that only see system properties");
        }
        consumePublishedConsentKeys(overrides.keySet());
    }

    /**
     * The {@code dOnly} keys that are a one-time gesture rather than a setting.
     *
     * <p>The launcher cannot set a {@code -D}, so a ticked consent box is written into
     * {@code saves/common/coop_options.json.data} - and only a launcher-driven launch rewrites that
     * file. Started any other way (the desktop shortcut, {@code launch-guest.ps1}) the game would
     * inherit the previous launcher session's {@code true} and silently override the Phase 6b seed
     * lock again, which is the one thing typed consent exists to prevent: adopting an in-flight
     * campaign id throws the other player's progress away. So the key is published for this launch
     * like every other flag and then struck from the file. The launcher clears it too - on game exit
     * and at its own startup - and the two compose: whichever runs first, the next launch starts
     * clean.
     *
     * <p>{@code coop.expectedCampaignId} is here for the same reason, one step milder: it is the
     * campaign id an invite is for, and it makes the mod tell a player who loads a different save
     * that it is the wrong one ({@code coop.save.CoopCampaignGuard}). Left standing in the file it
     * would keep saying that on every unrelated launch afterwards, about an invite nobody is acting
     * on any more, which is precisely how a warning turns into something players learn to click past.
     */
    private static final java.util.Set<String> ONE_SHOT_CONSENT_KEYS =
            java.util.Set.of(CoopOptionsRegistry.ADOPT_CAMPAIGN_ID,
                    CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID);

    /** Package-private for the test: the set above must never grow a standing setting. */
    static java.util.Set<String> oneShotConsentKeys() {
        return ONE_SHOT_CONSENT_KEYS;
    }

    /**
     * Strikes the consent keys just published out of the user file, so they apply to this launch and
     * no other. Total: a file that cannot be written leaves the flag where it was, which is exactly
     * today's behaviour, and says so once.
     */
    private static void consumePublishedConsentKeys(java.util.Set<String> published) {
        for (String key : ONE_SHOT_CONSENT_KEYS) {
            if (!published.contains(key)) {
                continue;
            }
            try {
                if (coop.config.CoopOptionsStore.system().consumeOneShot(key)) {
                    CoopLog.info(CoopModPlugin.class, "Coop consumed the one-shot consent flag "
                            + key + " from " + coop.config.CoopOptionsStore.COMMON_PATH
                            + ": it is a one-time gesture, so it will not apply to a later launch");
                }
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopModPlugin.class, "Coop could not clear the one-shot consent flag "
                        + key + " from " + coop.config.CoopOptionsStore.COMMON_PATH
                        + "; it would apply again on the next launch", ex);
            }
        }
    }

    /**
     * Phase 31: is this the Starsector the mod was built for?
     *
     * <p>Runs right after {@link #publishLauncherProperties()} so a launcher-written
     * {@code coop.allowGameVersionMismatch} is already a system property by the time it is read
     * here. The verdict is remembered on {@link CoopGameVersionCheck} and enforced by the pump,
     * which is where sockets are opened; nothing is refused at load time, because a player who
     * cannot start a session still has a perfectly good single-player campaign to load.
     *
     * <p>Total. A settings API that will not answer, or a mod manager that does not know its own
     * spec, leaves the verdict {@link CoopGameVersionCheck.Verdict#UNKNOWN} and lets the session
     * run: refusing on the strength of a read that failed would be the mod breaking itself.
     */
    private static void checkGameVersion() {
        String gameVersion = "";
        String modGameVersion = "";
        try {
            gameVersion = Global.getSettings().getGameVersion();
            com.fs.starfarer.api.ModSpecAPI spec =
                    Global.getSettings().getModManager().getModSpec(COOP_MOD_ID);
            modGameVersion = spec == null ? "" : spec.getGameVersion();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopModPlugin.class,
                    "Coop could not read the game version to compare against the mod's", ex);
        }
        boolean allowed = allowGameVersionMismatch();
        CoopGameVersionCheck.Result result =
                CoopGameVersionCheck.check(modGameVersion, gameVersion, allowed);
        CoopGameVersionCheck.remember(result);
        switch (result.verdict()) {
            case MATCH -> CoopLog.info(CoopModPlugin.class,
                    "Coop game version check: mod built for " + result.modGameVersion()
                            + ", game is " + result.gameVersion());
            case UNKNOWN -> CoopLog.warn(CoopModPlugin.class,
                    "Coop could not compare game versions (mod says \"" + result.modGameVersion()
                            + "\", game says \"" + result.gameVersion()
                            + "\"); co-op will run without the check");
            case ALLOWED -> {
                // The ERROR still goes out: the mismatch is a real fact about this install and the
                // one thing a support thread needs, whether or not a tester chose to run anyway.
                CoopLog.error(CoopModPlugin.class, result.mismatchMessage());
                CoopLog.warn(CoopModPlugin.class, "Coop is running on a mismatched game version"
                        + " because " + CoopOptionsRegistry.ALLOW_GAME_VERSION_MISMATCH
                        + " is set. The forks are compiled against "
                        + result.modGameVersion() + "; expect anything.");
            }
            case REFUSED -> CoopLog.error(CoopModPlugin.class, result.mismatchMessage());
        }
    }

    /** The mod id, which is also the folder name the install check enforces. */
    private static final String COOP_MOD_ID = "coop";

    /**
     * The developer escape hatch, read the way every other {@code dOnly} key is read: straight off
     * the system properties, which {@link #publishLauncherProperties()} has already topped up from
     * the settings file.
     */
    private static boolean allowGameVersionMismatch() {
        try {
            return Boolean.parseBoolean(
                    System.getProperty(CoopOptionsRegistry.ALLOW_GAME_VERSION_MISMATCH));
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Phase 31: republish the resolved seed as a JVM system property when it did not come from one.
     *
     * <p>{@code coop.rng.CoopRandom} lives in {@code coop-forks.jar}, which the JVM system
     * classloader owns; it cannot see {@code CoopOptionsStore} in the mod classloader, so it reads
     * {@code coop.newGameSeed} as a plain property and nothing else. Before the launcher existed
     * that was always true, because the only way to set the seed was a {@code -D} in
     * {@code vmparams}. The launcher writes the seed into
     * {@code saves/common/coop_options.json.data} instead, and without this bridge every forked
     * procgen path would fall back to {@code new Random()} and the two sectors would diverge in
     * exactly the places the Phase 6 audit added the fork for.
     *
     * <p>Runs in {@code onApplicationLoad}, which is long before any new game, and never overwrites
     * a real {@code -D}: the command line stays the top of the stack. It is <em>not</em> before every
     * fork, though - see {@link #rebindTheForkedSharedRandom()} for the one that reads the property
     * at class-init time and therefore has to be rebound rather than published to.
     */
    static void publishSeedForTheForks(String newGameSeed) {
        if (System.getProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY) != null) {
            return;
        }
        System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, newGameSeed);
        CoopLog.info(CoopModPlugin.class,
                "Coop published the settings-file seed as -D"
                        + CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY
                        + " so coop-forks.jar (system classloader) can see it");
        rebindTheForkedSharedRandom();
    }

    /**
     * Where the rebound shared {@link java.util.Random} goes. Production writes the forked
     * {@code Misc.random} field; a test swaps this, because merely touching {@code Misc} outside a
     * running game runs its {@code Global.getSettings()}-reading static initialiser.
     */
    static java.util.function.Consumer<java.util.Random> sharedRandomSink =
            random -> com.fs.starfarer.api.util.Misc.random = random;

    /**
     * The one fork the property bridge above cannot reach by setting a property: {@code Misc.random}
     * is a <em>static field initialiser</em> ({@code = CoopRandom.ofOrDefault("Misc.random")}) that
     * runs while the engine loads its data, some ten seconds before this plugin exists. A real
     * {@code -D} from a launch script is there in time; the Phase 31 launcher's settings-file seed is
     * not, so on a launcher-started game that field was built from "no seed" - a plain
     * {@code new Random()} - and every gen-time draw off it rolled differently on the two installs
     * while the log claimed the seed had been published. Rebinding it from the same
     * {@code CoopRandom} stream the fork would have used closes that, and only on the settings-file
     * path: {@link #publishSeedForTheForks} returns before this when a real {@code -D} is present,
     * and there the field is already right.
     *
     * <p>A plain public-field write, deliberately: the script classloader refuses
     * {@code java.lang.reflect}. The {@code [COOP-FORK] Misc fork active} probe line further up the
     * log was written before the seed existed and still reads {@code coopSession=false} on this path;
     * this line is the one that says the seed took.
     */
    private static void rebindTheForkedSharedRandom() {
        try {
            sharedRandomSink.accept(coop.rng.CoopRandom.ofOrDefault("Misc.random"));
            CoopLog.info(CoopModPlugin.class,
                    "Coop reseeded the forked Misc.random from the settings-file seed (the"
                            + " [COOP-FORK] probe line above was logged before the seed existed, so"
                            + " it reads coopSession=false on this path)");
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopModPlugin.class,
                    "Coop could not reseed the forked Misc.random, so gen-time RNG stays unseeded"
                            + " on this client; is coop-forks.jar on the JVM classpath?", ex);
        }
    }

    /**
     * Runs before the sector is generated, which is the only reason this override exists: the
     * process-wide statics below are read by the forked spawners <em>during</em> procgen, and
     * {@code onGameLoad} is too late for a new game (the engine calls it after procgen and the
     * initial time pass).
     */
    @Override
    public void onNewGame() {
        clearPreviousGameStatics();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        clearPreviousGameStatics();
        if (netService != null) {
            // Graceful session end: loading another game tears this session's transport down, so the
            // partner gets a last notice before the socket closes (the send is flushed inline). There
            // is no engine hook for quitting to the menu or exiting, so this is the only graceful end
            // the mod can observe in-process.
            //
            // It is a notice, not a save order. By the time this runs the engine has already replaced
            // the sector, and the campaign being left was never written - Starsector does not autosave
            // the current game when you load another one. The guest therefore does NOT autosave on a
            // "session end" checkpoint (CoopSaveCheckpoint.onCheckpointReceived): doing so would put
            // its save ahead of the host's last real one, and the guest rejoins by loading that file.
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
        // Phase 31: "is this the save the invite is for?", answered here rather than at the seed lock
        // minutes later. Warns and proceeds - the check itself decides nothing, and the seed lock on
        // connect still has the final say. Last in the method because it is advisory: nothing below
        // depends on it, and it must not stand between a load and a working session.
        installExpectedCampaignNotice(newGame);
        CoopLog.info(CoopModPlugin.class, "CoopNetPump registered");
    }

    /**
     * Queues the wrong-campaign message when there is one. Total: a guard that cannot read anything
     * says nothing, and a notice that cannot be queued is already in the log.
     *
     * @param newGame the flag {@code onGameLoad} was handed; it picks which of the guard's two
     *                questions is asked
     */
    private static void installExpectedCampaignNotice(boolean newGame) {
        try {
            CoopCampaignGuard.Notice notice = CoopCampaignGuard.forGameLoad(newGame);
            if (notice.silent()) {
                return;
            }
            CoopCampaignNotice.install(Global.getSector(), notice.message());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopModPlugin.class,
                    "Coop could not check this game against the invite's campaign id", ex);
        }
    }

    /**
     * Statics that belong to the game that just ended and that no engine hook clears on its own.
     *
     * <p>The guest-presence slot is the one that matters: it lives in {@code coop-forks.jar} on the
     * system classloader, the forked spawners read it every pass, and its only release is a frame
     * boundary that needs a campaign frame in which the pump did not tick. Quitting to the title
     * screen produces no such frame, so the previous sector's mirror fleet stayed published - through
     * the next game's procgen and its first frames - and route fleets materialised around a
     * hyperspace point belonging to a campaign that no longer exists. The full-fidelity drive is
     * cleared for the same reason: {@code saveInProgress} is a static that only {@code afterGameSave}
     * clears, and a failed save must not carry it into the next campaign.
     */
    static void clearPreviousGameStatics() {
        CoopGuestPresence.clearForGameLoad();
        CoopFullFidelitySystemDriver.reset();
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
        // Sector size and star age have no getter on a generated sector, so the pair the new-game
        // dialog pinned is written down here or lost. Nothing pending means nothing recorded.
        CoopWorldSettings.storePendingIntoCurrentSector();
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
    /**
     * The other end of {@link #beforeGameSave()}, and the one the engine takes when the write throws
     * (out of memory, disk full): {@code afterGameSave} is on the success path only, so without this
     * a failed save left the three intel pages removed for the rest of the session and the
     * full-fidelity guest-system drive latched off for the rest of the <em>process</em> - a static
     * nothing else clears. Everything {@code afterGameSave} does except the checkpoint: no save was
     * written, so there is nothing to tell the partner to mirror.
     */
    @Override
    public void onGameSaveFailed() {
        CoopFullFidelitySystemDriver.endSave();
        CoopSessionIntel.ensureRegistered(Global.getSector());
        CoopSessionStatsIntel.ensureRegistered(Global.getSector());
        CoopOptionsPage.ensureRegistered(Global.getSector());
        CoopLog.warn(CoopModPlugin.class, "The game reported a failed save; the coop intel pages and"
                + " the full-fidelity guest-system drive have been put back");
    }

    @Override
    public void afterGameSave() {
        CoopFullFidelitySystemDriver.endSave();
        // Phase 21: put the transient pages back, before anything else. The save is written; from
        // here the player is back in the campaign and expects the intel screen intact.
        CoopSessionIntel.ensureRegistered(Global.getSector());
        CoopSessionStatsIntel.ensureRegistered(Global.getSector());
        CoopOptionsPage.ensureRegistered(Global.getSector());
        // Phase 31: the row that lets the launcher name this save for a co-op invite. Here rather than
        // in beforeGameSave because the folder exists by now, and because a save that threw took
        // onGameSaveFailed instead - no row ever claims a save that did not land. The folder name is
        // read inside this call and never cached: the engine has swapped CampaignEngine.saveDirName
        // to the folder being written and restores it as soon as the save routine returns.
        CoopSaveIndex.recordCurrentSave();
        CoopSaveCheckpoint.notifyLocalGameSaved(CoopSaveCheckpoint.REASON_HOST_SAVE);
    }
}
