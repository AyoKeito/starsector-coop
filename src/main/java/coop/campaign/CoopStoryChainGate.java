package coop.campaign;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetStartupConfig;
import coop.util.CoopLog;

/**
 * Publishes "this client is the guest" into sector memory, where the rules engine can read it.
 *
 * <p><b>Why.</b> The Galatia Academy story chain — {@code GAIntro}, {@code GAIntro2},
 * {@code GATalkToBaird}, {@code GAKallichore}, {@code GAFindingCoureuse}, {@code GAProjectZiggurat},
 * {@code GAAtTheGates}, {@code GADetectHyperspaceOddity} and the Sebestyen contact jobs — runs
 * entirely inside one engine. Its state is per-client memory ({@code $player.metBaird},
 * {@code $global.gaKA_completed}, the mission references under {@code $global.ga*_ref}) and the
 * places it uses are generated locally by whichever mission needs them. None of that is on the wire.
 * A guest who starts the chain therefore gets a private second storyline the host never sees, in a
 * world the two clients no longer agree on. Until a phase replicates story state, the host plays the
 * chain and the guest rides along in the shared world; {@code data/campaign/rules.csv} enforces that
 * by adding {@link #GUEST_RULE_CONDITION} to every root entry of the chain.
 *
 * <p><b>Set on the guest, removed everywhere else.</b> Sector memory rides the save, and a save can
 * change hands: the host's coordinated autosave is exactly what a guest loads to rejoin (Phase 16),
 * and the same file can later be loaded by the host again. Writing {@code false} on a host would
 * leave the answer to whichever client wrote it last. Unsetting instead means the flag is only ever
 * present on a client that is a guest <em>right now</em>, and a missing memory key evaluates false in
 * the rules engine, so the host reads {@code !$global.coopIsGuest} as true and takes the vanilla path.
 *
 * <p><b>Role, not session.</b> The role read here is the launch configuration, not a live connection:
 * a guest that has not finished connecting, or whose host has dropped, is still a guest, and its
 * world is still the host's. Nothing here waits for a session.
 */
public final class CoopStoryChainGate {

    /**
     * The sector-memory key. The rules engine sees it as {@code $global.coopIsGuest} — {@code $global}
     * is sector memory, and the leading {@code $} is part of the stored key, the same shape as
     * {@code $coopDebug} and {@code $coopWiretap}.
     */
    public static final String GUEST_MEMORY_FLAG = "$coopIsGuest";

    /**
     * The condition appended to every gated row in the mod's {@code data/campaign/rules.csv}. Kept
     * here so the CSV and the publisher cannot drift apart without a test noticing.
     */
    public static final String GUEST_RULE_CONDITION = "!$global.coopIsGuest";

    private CoopStoryChainGate() {
    }

    /**
     * Publishes the flag for this launch's role. Called from the one prologue both
     * {@code onNewGame} and {@code onGameLoad} run, so it is in place before the rules engine can
     * fire anything.
     *
     * @param sector the campaign being entered; null is accepted and does nothing
     */
    public static void publish(SectorAPI sector) {
        publish(sector, launchRole());
    }

    /**
     * The half with no {@code System.getProperty} in it, so a test can hand in a role directly.
     *
     * @param sector the campaign being entered; null is accepted and does nothing
     * @param role   this launch's coop role
     */
    static void publish(SectorAPI sector, CoopConnectionRole role) {
        if (sector == null) {
            return;
        }
        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        if (memory == null) {
            return;
        }
        if (role == CoopConnectionRole.GUEST) {
            memory.set(GUEST_MEMORY_FLAG, true);
            CoopLog.info(CoopStoryChainGate.class,
                    "Guest launch: the Galatia Academy story chain is off on this client");
        } else {
            // Not set-to-false: see the class note. Unset on a save that never carried the key is a
            // no-op, which is the overwhelmingly common case (host, and solo launches).
            memory.unset(GUEST_MEMORY_FLAG);
        }
    }

    /**
     * This launch's role, or {@link CoopConnectionRole#NONE} when the startup properties cannot be
     * read at all. A configuration this client refuses to parse is one it cannot connect with
     * either, so it is not a guest of anything and vanilla behaviour is the right answer; the refusal
     * itself is already reported where the transport reads the same properties.
     */
    static CoopConnectionRole launchRole() {
        try {
            return CoopNetStartupConfig.fromSystemProperties().role();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopStoryChainGate.class,
                    "Coop could not read this launch's role; leaving the story-chain gate open", ex);
            return CoopConnectionRole.NONE;
        }
    }
}
