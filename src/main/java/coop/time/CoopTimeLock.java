package coop.time;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
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
        return new TimeSnapshot(
                sector.isPaused(),
                sector.isFastForwardIteration(),
                clock.getTimestamp(),
                clock.getDay(),
                sentAtMillis);
    }

    public void apply(TimeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SectorAPI sector = requireSector();
        sector.setPaused(snapshot.paused());
        sector.setFastForwardIteration(snapshot.fastForward());
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
