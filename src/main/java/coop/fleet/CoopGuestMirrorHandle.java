package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import java.util.List;

/**
 * The one live handle on the remote player's mirror fleet, so nothing has to go looking for it.
 *
 * <p><b>Why.</b> Three separate hot paths used to hunt the same fleet with the same sector-wide scan —
 * {@code CoopNpcThreatWatcher.tick} (every frame), {@link CoopFullFidelitySystemDriver} (every frame,
 * through {@link CoopGuestPresence}), and {@link CoopNpcFleetReplicator}'s motion send (10 Hz). Each
 * scan walked every location and read {@code getMemoryWithoutUpdate()} on every fleet in it, which is
 * worse than it sounds: that call <em>lazily allocates</em> a {@code Memory} for any entity that does
 * not have one yet, so the scan was also a per-frame allocator over the whole NPC population. And it
 * ran unconditionally, including on a host with no guest connected, where the answer is always null.
 *
 * <p><b>How.</b> {@link CoopFleetMirror} is the only thing that ever creates this fleet, so it
 * publishes the reference the moment it builds one and clears it on dispose. Readers get an O(1)
 * {@link #current()} that only validates the handle is still a live world fleet. The single scan that
 * remains is {@link #reresolve(SectorAPI)}, run by {@link CoopGuestPresence}'s 0.5 Hz pass, which is
 * what makes a wrong or missed publish self-heal within two seconds rather than persist.
 *
 * <p><b>Role note.</b> The slot holds <em>this client's</em> {@code $coopMirrorFleet} — the mirror of
 * the other player. On the host that is the guest's mirror, which is what every reader here wants and
 * why the class is named for it; on the guest the same slot holds the host's mirror, and nothing
 * guest-side reads it (all three call paths are host-gated by the pump). Do not add a guest-side
 * reader without renaming this.
 *
 * <p>Campaign thread only, like everything else in the pump's frame.
 */
public final class CoopGuestMirrorHandle {

    private static CampaignFleetAPI mirror;

    private CoopGuestMirrorHandle() {
    }

    /** Called by {@link CoopFleetMirror} when it creates (or disposes, with null) the player mirror. */
    public static void publish(CampaignFleetAPI fleet) {
        mirror = fleet;
    }

    /** Session end / teardown / game load. */
    public static void clear() {
        mirror = null;
    }

    /**
     * Drops the handle only if it is this exact fleet. Called from every {@link CoopFleetMirror}
     * dispose, including the NPC mirrors' — which must not be able to clear the player mirror's slot.
     */
    public static void clearIfSame(CampaignFleetAPI fleet) {
        if (fleet != null && mirror == fleet) {
            mirror = null;
        }
    }

    /**
     * The mirror fleet, or null. O(1): a field read plus the two engine calls that say whether the
     * fleet is still in the world. A handle that fails that test is dropped, so the next
     * {@link #reresolve(SectorAPI)} is what repopulates it.
     */
    public static CampaignFleetAPI current() {
        CampaignFleetAPI fleet = mirror;
        if (fleet == null) {
            return null;
        }
        if (!isInWorld(fleet)) {
            mirror = null;
            return null;
        }
        return fleet;
    }

    /**
     * Authoritative re-resolve: one sector scan, whose result becomes the handle. Called from
     * {@link CoopGuestPresence}'s pass (0.5 Hz), which needed a scan of its own anyway — so this
     * costs nothing new and bounds how long a stale handle can survive.
     */
    public static CampaignFleetAPI reresolve(SectorAPI sector) {
        CampaignFleetAPI found = scan(sector);
        mirror = found;
        return found;
    }

    /** The Phase 8 guest-player mirror, identified by its {@code $coopMirrorFleet} memory flag. */
    static CampaignFleetAPI scan(SectorAPI sector) {
        if (sector == null) {
            return null;
        }
        List<LocationAPI> locations = CoopLocations.all(sector);
        for (int i = 0; i < locations.size(); i++) {
            LocationAPI location = locations.get(i);
            if (location == null) {
                continue;
            }
            List<CampaignFleetAPI> fleets = location.getFleets();
            if (fleets == null) {
                continue;
            }
            for (int f = 0; f < fleets.size(); f++) {
                CampaignFleetAPI fleet = fleets.get(f);
                if (fleet == null) {
                    continue;
                }
                MemoryAPI memory = fleet.getMemoryWithoutUpdate();
                if (memory != null && memory.getBoolean(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG)) {
                    return fleet;
                }
            }
        }
        return null;
    }

    private static boolean isInWorld(CampaignFleetAPI fleet) {
        try {
            if (!fleet.isAlive()) {
                return false;
            }
            LocationAPI location = fleet.getContainingLocation();
            return location != null;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
