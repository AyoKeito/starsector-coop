package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.NascentGravityWellAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.util.Misc;
import coop.fleet.CoopLocations;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Saved host-side lifetime for a recipe-bearing abyssal system. Only the engine system reference
 * and primitive visit/position history survive a save; no session or transport object is retained.
 */
public final class CoopAbyssalDespawner implements EveryFrameScript {
    public static final float MIN_DAYS_SINCE_VISIT = 10f;
    public static final float MIN_DISTANCE_LY = 5f;
    public static final float CHECK_INTERVAL_SECONDS = 1.5f;

    private StarSystemAPI system;
    private float checkElapsed;
    private boolean guestObserved;
    private long guestLastVisitTimestamp;
    private boolean lastGuestInside;
    private float lastGuestHyperX;
    private float lastGuestHyperY;

    // Loading a save must not remove a system before its current session/grace state is published.
    private transient boolean runtimeReady;
    private transient boolean reconnectGrace;

    public CoopAbyssalDespawner(StarSystemAPI system) {
        this.system = Objects.requireNonNull(system, "system");
    }

    public StarSystemAPI getSystem() {
        return system;
    }

    /**
     * Host integration calls this each frame, including paused frames and session teardown.
     * A null presence retains the guest's last visit, position and inside hold. A later observed
     * presence outside this system is the only operation that releases a saved inside hold.
     */
    public void updateGuestPresence(SectorEntityToken guest, boolean inReconnectGrace) {
        if (system == null) return;
        if (guest != null) {
            Vector2f position = guest.getLocationInHyperspace();
            if (position == null || !Float.isFinite(position.x) || !Float.isFinite(position.y)) {
                throw new IllegalStateException("Guest abyssal presence has no finite hyperspace position");
            }
            CampaignClockAPI clock = Objects.requireNonNull(
                    Objects.requireNonNull(Global.getSector(), "sector").getClock(), "campaign clock");
            boolean inside = guest.getContainingLocation() == system;
            // The first observation starts a conservative ten-day window even if never visited.
            // A saved inside hold may outlive many solo host days. Start the leave window when a
            // later session finally confirms the guest outside, rather than counting that outage.
            if (!guestObserved || inside || lastGuestInside) guestLastVisitTimestamp = clock.getTimestamp();
            guestObserved = true;
            lastGuestInside = inside;
            lastGuestHyperX = position.x;
            lastGuestHyperY = position.y;
        }
        reconnectGrace = inReconnectGrace;
        runtimeReady = true;
    }

    /** Current authoritative criteria, exposed for diagnostics without advancing the script. */
    public boolean canDespawnNow(SectorAPI sector) {
        if (system == null || sector == null || !runtimeReady || reconnectGrace || lastGuestInside) return false;
        float hostDays = system.getDaysSinceLastPlayerVisit();
        if (!Float.isFinite(hostDays) || hostDays < MIN_DAYS_SINCE_VISIT) return false;
        CampaignFleetAPI host = sector.getPlayerFleet();
        if (host == null || host.getContainingLocation() == system) return false;
        Vector2f origin = system.getLocation();
        Vector2f hostPosition = host.getLocationInHyperspace();
        if (!farEnough(origin, hostPosition)) return false;
        if (!guestObserved) return true; // A guest that has never joined cannot pin solo systems forever.
        CampaignClockAPI clock = sector.getClock();
        if (clock == null) return false;
        float guestDays = clock.getElapsedDaysSince(guestLastVisitTimestamp);
        return Float.isFinite(guestDays) && guestDays >= MIN_DAYS_SINCE_VISIT
                && farEnough(origin, new Vector2f(lastGuestHyperX, lastGuestHyperY));
    }

    @Override
    public void advance(float amount) {
        if (system == null || !runtimeReady || reconnectGrace) return;
        if (!Float.isFinite(amount) || amount < 0f) throw new IllegalArgumentException("Invalid campaign frame duration");
        checkElapsed += amount;
        if (checkElapsed < CHECK_INTERVAL_SECONDS) return;
        checkElapsed = 0f;
        SectorAPI sector = Global.getSector();
        if (!canDespawnNow(sector)) return;
        removeSystemAndLinks(sector, system);
        system = null;
    }

    @Override
    public boolean isDone() {
        return system == null;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    private static boolean farEnough(Vector2f origin, Vector2f position) {
        if (origin == null || position == null || !Float.isFinite(origin.x) || !Float.isFinite(origin.y)
                || !Float.isFinite(position.x) || !Float.isFinite(position.y)) return false;
        float units = Misc.getUnitsPerLightYear();
        if (!Float.isFinite(units) || units <= 0f) throw new IllegalStateException("Invalid units per light year");
        return Math.hypot((double) position.x - origin.x, (double) position.y - origin.y)
                >= (double) MIN_DISTANCE_LY * units;
    }

    /**
     * Vanilla AbyssalLocationDespawner cleanup: linked first-destination jump points, nascent wells,
     * anchor, then deferred system removal. This also serves Guest full-manifest reconciliation.
     */
    public static void removeSystemAndLinks(SectorAPI sector, StarSystemAPI system) {
        Objects.requireNonNull(sector, "sector");
        Objects.requireNonNull(system, "system");
        LocationAPI hyper = Objects.requireNonNull(sector.getHyperspace(), "hyperspace");
        Set<SectorEntityToken> remove = new LinkedHashSet<>();
        List<SectorEntityToken> jumpPoints = hyper.getJumpPoints();
        if (jumpPoints != null) for (SectorEntityToken token : new ArrayList<>(jumpPoints)) {
            if (!(token instanceof JumpPointAPI jump)) continue;
            List<JumpPointAPI.JumpDestination> destinations = jump.getDestinations();
            if (destinations == null || destinations.isEmpty()) continue;
            SectorEntityToken destination = destinations.get(0).getDestination();
            if (destination != null && destination.getStarSystem() == system) remove.add(token);
        }
        List<NascentGravityWellAPI> wells = hyper.getGravityWells();
        if (wells != null) for (NascentGravityWellAPI well : new ArrayList<>(wells)) {
            SectorEntityToken target = well.getTarget();
            if (target != null && target.getStarSystem() == system) remove.add(well);
        }
        if (system.getHyperspaceAnchor() != null) remove.add(system.getHyperspaceAnchor());
        sector.removeStarSystemNextFrame(system);
        for (SectorEntityToken token : remove) hyper.removeEntity(token);
        CoopLocations.invalidate();
    }
}
