package coop.fleet;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.SectorAPI;

import java.util.Objects;

/**
 * Feeds {@link CoopFullFidelitySystemDriver} the one thing it cannot read for itself, and guarantees
 * it is told when it stops being driven.
 *
 * <p>{@code CampaignEngine.advance} hands every script the frame's campaign-time delta -- the same
 * {@code f2} it gives its own current location -- as the argument to {@link #advance(float)} (line
 * 1094). That value is the only correct timestep to hand-advance a location with, and it is not
 * reachable from {@link CoopNpcFleetReplicator#tick()}, which takes no arguments. So this script
 * captures it once per frame.
 *
 * <p>It is installed before the network pump and runs while paused, which matters for both jobs. It
 * runs first, so the delta the driver uses later in the same frame is this frame's. And it runs
 * unconditionally -- the driver's own tick only happens while a session is streaming on the host, so
 * without an always-on observer nothing would notice a session ending and the smoother bypass would
 * stay latched on a system nobody is driving any more.
 *
 * <p>Nothing here needs cleanup: the driven system is never removed from the engine's list, so if this
 * script and the whole mod vanished mid-frame the engine would just resume advancing it on its next
 * stride.
 */
public final class CoopSystemDriveFrameHook implements EveryFrameScript {

    /**
     * Registers the hook, replacing any previous one. Must be called <em>before</em> the network pump
     * is installed so it sits earlier in the engine's transient script list and therefore runs first.
     */
    public static void install(SectorAPI sector) {
        Objects.requireNonNull(sector, "sector");
        sector.removeScriptsOfClass(CoopSystemDriveFrameHook.class);
        sector.removeTransientScriptsOfClass(CoopSystemDriveFrameHook.class);
        sector.addTransientScript(new CoopSystemDriveFrameHook());
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        CoopFullFidelitySystemDriver.frameBoundary(amount);
    }
}
