package coop.time;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import coop.input.CoopCampaignInputBlocker;
import coop.net.CoopMessages;

import java.util.Objects;
import java.util.function.Supplier;

public class CoopTimeLock {
    public static final long SNAPSHOT_INTERVAL_MILLIS = 200L;

    private final Supplier<SectorAPI> sectorSupplier;

    public CoopTimeLock() {
        this(Global::getSector);
    }

    public CoopTimeLock(Supplier<SectorAPI> sectorSupplier) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
    }

    public TimeSnapshot capture(long sentAtMillis) {
        SectorAPI sector = requireSector();
        CampaignClockAPI clock = sector.getClock();
        // Hold-Shift fast-forward is a 2x local clock loop inside CampaignState.advance; it does
        // NOT set isInFastAdvance()/isFastForwardIteration() (both read false during it, verified).
        // CampaignUIAPI.isFastForward() is the public getter that reflects that state.
        boolean fastForward = hostFastForward(sector);
        return new TimeSnapshot(
                sector.isPaused(),
                fastForward,
                clock.getTimestamp(),
                clock.getDay(),
                sentAtMillis);
    }

    public void apply(TimeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SectorAPI sector = requireSector();
        sector.setPaused(snapshot.paused());
        // setInFastAdvance(true) drives the extra CampaignClock.advance() path in
        // CampaignEngine.advance(), making the guest's clock run faster to mirror the host. This
        // cannot block a guest's own local hold-Shift (that loop lives in obfuscated CampaignState
        // and polls the key directly); guest self-fast-forward is a documented v1 limitation.
        // Mirror the host's fast-advance flag for animation/UI consistency. This does NOT control
        // time speed: setInFastAdvance was verified not to change the guest clock rate. Fast-forward
        // is instead neutralized for both clients by the campaignSpeedupMult=1 override in
        // data/config/settings.json (hold-Shift then advances at 1x), so clocks stay aligned.
        sector.setInFastAdvance(snapshot.fastForward());
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
            listeners.addListener(new CoopCampaignInputBlocker(), true);
        } else if (!active && installed) {
            listeners.removeListenerOfClass(CoopCampaignInputBlocker.class);
        }
    }

    /**
     * Phase 9: toggle the installed guest input blocker's interaction lock (consume world input
     * except camera movement while the remote player holds an interaction). No-op when no blocker is
     * installed, which is the host case (the host blocks its own new interactions via
     * {@code setDisallowPlayerInteractionsForOneFrame} instead).
     */
    public void setInteractionBlocked(boolean blocked, String entityName) {
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        ListenerManagerAPI listeners = sector.getListenerManager();
        if (listeners == null) {
            return;
        }
        for (CoopCampaignInputBlocker blocker : listeners.getListeners(CoopCampaignInputBlocker.class)) {
            blocker.setInteractionBlocked(blocked, entityName);
        }
    }

    public static TimeSnapshot fromMessage(CoopMessages.Message message) {
        Objects.requireNonNull(message, "message");
        if (message.type() != CoopMessages.Type.TIME_SNAPSHOT) {
            throw new IllegalArgumentException("Expected TIME_SNAPSHOT, got " + message.type());
        }
        return new TimeSnapshot(
                Boolean.parseBoolean(CoopMessages.requiredPayloadString(message, "paused")),
                Boolean.parseBoolean(CoopMessages.requiredPayloadString(message, "fastForward")),
                CoopMessages.requiredPayloadLong(message, "timestampMillis"),
                CoopMessages.requiredPayloadLong(message, "campaignDay"),
                CoopMessages.requiredPayloadLong(message, "sentAtMillis"));
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

    public record TimeSnapshot(boolean paused, boolean fastForward, long timestampMillis,
                               long campaignDay, long sentAtMillis) {
    }
}
