package coop.time;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import coop.input.CoopCampaignInputBlocker;
import coop.input.CoopHostPauseInputListener;
import coop.net.CoopMessages;

import java.util.Objects;
import java.util.function.Supplier;

public class CoopTimeLock {
    public static final long SNAPSHOT_INTERVAL_MILLIS = 200L;

    private final Supplier<SectorAPI> sectorSupplier;
    // Phase 11: handed to the guest input blocker so a guest pause-key press flips the shared-pause
    // intent instead of pausing locally. Null until the pump installs it (and in legacy/unit paths).
    private CoopSharedPauseCoordinator pauseCoordinator;
    // Phase 7b: owns every MethodHandles touch of the engine's fast-forward state. Null until the
    // pump injects it (and in legacy/unit paths), in which case apply() simply mirrors no speed.
    private CoopFastForwardLock fastForwardLock;

    public CoopTimeLock() {
        this(Global::getSector);
    }

    public CoopTimeLock(Supplier<SectorAPI> sectorSupplier) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
    }

    public void setPauseCoordinator(CoopSharedPauseCoordinator pauseCoordinator) {
        this.pauseCoordinator = pauseCoordinator;
    }

    public void setFastForwardLock(CoopFastForwardLock fastForwardLock) {
        this.fastForwardLock = fastForwardLock;
    }

    /**
     * Captures the host's clock state for the wire. {@code pausedBy} is passed in rather than read
     * here: the lock owns the engine clock, but only the pump's pause coordinator knows which
     * intent is holding the shared pause.
     *
     * @param pausedBy raw shared-pause holder token ({@code host}, {@code guest},
     *                 {@code guest screen}, {@code combat}), or {@code ""}/null for nobody
     */
    public TimeSnapshot capture(long sentAtMillis, String pausedBy) {
        SectorAPI sector = requireSector();
        CampaignClockAPI clock = sector.getClock();
        // Phase 7b: during a session fast-forward runs in vanilla's toggle mode, so this bit is the
        // host's persistent CampaignState.fastForward field — the shared time-speed state the guest
        // mirrors. CampaignUIAPI.isFastForward() is the public getter for that exact field.
        boolean fastForward = hostFastForward(sector);
        return new TimeSnapshot(
                sector.isPaused(),
                fastForward,
                clock.getTimestamp(),
                clock.getDay(),
                sentAtMillis,
                pausedBy == null ? "" : pausedBy);
    }

    public void apply(TimeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SectorAPI sector = requireSector();
        // Only flip the clock when the desired state actually differs from what the sector already
        // shows. Re-issuing setPaused(true) every frame (the guest applies a host snapshot each
        // frame) fights a vanilla interaction dialog's own pause/option-reshow state machine: it left
        // the guest's station dialog blank with no options when returning from the trade tab (the
        // dialog needs a few un-clobbered frames to repopulate). Idempotent-on-change avoids that
        // while still mirroring the host authoritatively.
        if (sector.isPaused() != snapshot.paused()) {
            sector.setPaused(snapshot.paused());
        }
        // Phase 7b: mirror the host's fast-forward by writing the guest's own CampaignState
        // fastForward field (the lock owns that MethodHandles write, and only writes on a change).
        // The old setInFastAdvance(...) mirror is gone: it was verified not to change the guest clock
        // rate at all, so it moved nothing but a cosmetic flag.
        if (fastForwardLock != null) {
            fastForwardLock.writeFastForward(snapshot.fastForward());
        }
    }

    private boolean hostFastForward(SectorAPI sector) {
        try {
            CampaignUIAPI ui = sector.getCampaignUI();
            return ui != null && ui.isFastForward();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public void syncGuestInputBlocker(boolean active) {
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }

        ListenerManagerAPI listeners = sector.getListenerManager();
        if (listeners == null) {
            return;
        }

        boolean installed = listeners.hasListenerOfClass(CoopCampaignInputBlocker.class);
        if (active && !installed) {
            listeners.addListener(new CoopCampaignInputBlocker(pauseCoordinator), true);
        } else if (!active && installed) {
            listeners.removeListenerOfClass(CoopCampaignInputBlocker.class);
        }
    }

    /**
     * Phase 11: install/remove the host-side pause-key interceptor so the host's own pause key flips
     * {@code hostPauseIntent} and is consumed (the host clock is then driven only by the coordinator's
     * {@code effectivePaused}, never flickered by a raw vanilla toggle). No-op if no coordinator is set.
     */
    public void syncHostInputListener(boolean active) {
        if (pauseCoordinator == null) {
            return;
        }
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        ListenerManagerAPI listeners = sector.getListenerManager();
        if (listeners == null) {
            return;
        }
        boolean installed = listeners.hasListenerOfClass(CoopHostPauseInputListener.class);
        if (active && !installed) {
            listeners.addListener(new CoopHostPauseInputListener(pauseCoordinator), true);
        } else if (!active && installed) {
            listeners.removeListenerOfClass(CoopHostPauseInputListener.class);
        }
    }

    /**
     * Phase 9: toggle the installed guest input blocker's interaction lock (consume world input
     * except camera movement while the remote player holds an interaction). No-op when no blocker is
     * installed, which is the host case (the host blocks its own new interactions via
     * {@code setDisallowPlayerInteractionsForOneFrame} instead).
     */
    public void setInteractionBlocked(boolean blocked) {
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        ListenerManagerAPI listeners = sector.getListenerManager();
        if (listeners == null) {
            return;
        }
        for (CoopCampaignInputBlocker blocker : listeners.getListeners(CoopCampaignInputBlocker.class)) {
            blocker.setInteractionBlocked(blocked);
        }
    }

    /**
     * Suspend/resume the installed guest input blocker's consumption while a vanilla blocking screen
     * (interaction dialog / in-game menu / core tab) owns the keyboard. Prevents the blocker from
     * eating the spacebar/ESC/keys a station dialog needs to advance to its options and be dismissed.
     */
    public void setInputBlockerSuspended(boolean suspended) {
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        ListenerManagerAPI listeners = sector.getListenerManager();
        if (listeners == null) {
            return;
        }
        for (CoopCampaignInputBlocker blocker : listeners.getListeners(CoopCampaignInputBlocker.class)) {
            blocker.setSuspended(suspended);
        }
    }

    public static TimeSnapshot fromMessage(CoopMessages.Message message) {
        Objects.requireNonNull(message, "message");
        if (message.type() != CoopMessages.Type.TIME_SNAPSHOT) {
            throw new IllegalArgumentException("Expected TIME_SNAPSHOT, got " + message.type());
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        return new TimeSnapshot(
                Boolean.parseBoolean(payload.requiredString("paused")),
                Boolean.parseBoolean(payload.requiredString("fastForward")),
                payload.requiredLong("timestampMillis"),
                payload.requiredLong("campaignDay"),
                payload.requiredLong("sentAtMillis"),
                // Optional on purpose: a peer built before the field existed still parses, as "nobody".
                payload.optionalString("pausedBy", ""));
    }

    private SectorAPI requireSector() {
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            throw new IllegalStateException("No active campaign sector");
        }
        return sector;
    }

    private SectorAPI sectorOrNull() {
        try {
            return sectorSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * @param pausedBy raw shared-pause holder as the host computed it ({@code host}, {@code guest},
     *                 {@code guest screen}, {@code combat}), or {@code ""} when nobody holds it.
     *                 Never a display string — {@code CoopHudState.displayHolder} resolves the
     *                 wording per reading role.
     */
    public record TimeSnapshot(boolean paused, boolean fastForward, long timestampMillis,
                               long campaignDay, long sentAtMillis, String pausedBy) {
    }
}
