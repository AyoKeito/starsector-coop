package coop.campaign;

import com.fs.starfarer.api.campaign.LocationAPI;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which locations one host orbit-sync tick snapshots.
 *
 * <p><b>The measurement this exists for (2026-09-13).</b> Orbit sync used to send the host player's
 * location and nothing else, on the reasoning that an orbit desync is only visible when both players
 * are in the same system. With the guest alone in Magec, the Magec Fringe Jump-point sat at
 * (2709, 14537) on the guest and (4671, -15566) on the host, and the Maxios Jump-point 484 su apart:
 * the guest was parked on a jump point that, on the host, was somewhere else entirely, so every
 * host-positioned mirror in that system arrived at the wrong place on the guest's map. The guest's
 * own system has to be snapped too.
 *
 * <p>The selection is a pure function so it can be pinned without an engine; the two callers of it
 * (the host fleet's location and {@code CoopGuestMirrorHandle.current()}'s) are one field read each.
 */
class CoopCampaignReplicatorOrbitSyncTest {

    private static final LocationAPI MAGEC = location("magec", false);
    private static final LocationAPI CORVUS = location("corvus", false);
    private static final LocationAPI HYPERSPACE = location("hyperspace", true);

    @Test
    void oneSystemBetweenBothPlayersIsSnapshotOnce() {
        assertEquals(List.of(CORVUS), CoopCampaignReplicator.orbitSyncLocations(CORVUS, CORVUS));
    }

    @Test
    void aSeparateGuestSystemIsSnapshotAlongsideTheHostSystem() {
        List<LocationAPI> locations = CoopCampaignReplicator.orbitSyncLocations(CORVUS, MAGEC);

        assertEquals(2, locations.size(), "at most two, and this is the two-location case");
        assertSame(CORVUS, locations.get(0), "the host's location stays first");
        assertSame(MAGEC, locations.get(1));
    }

    /** Two references to the same system must not produce two snapshots of it. */
    @Test
    void theSameSystemReachedThroughTwoReferencesIsStillOneLocation() {
        LocationAPI otherHandle = location("corvus", false);

        assertEquals(List.of(CORVUS), CoopCampaignReplicator.orbitSyncLocations(CORVUS, otherHandle));
    }

    @Test
    void hyperspaceIsNeverSnapshotOnEitherSide() {
        assertEquals(List.of(MAGEC), CoopCampaignReplicator.orbitSyncLocations(MAGEC, HYPERSPACE));
        assertEquals(List.of(MAGEC), CoopCampaignReplicator.orbitSyncLocations(HYPERSPACE, MAGEC));
        assertTrue(CoopCampaignReplicator.orbitSyncLocations(HYPERSPACE, HYPERSPACE).isEmpty());
    }

    /** No guest paired, or a mirror not placed yet: the handle answers null and nothing changes. */
    @Test
    void anAbsentGuestMirrorLeavesTheHostOnlyBehaviourIntact() {
        assertEquals(List.of(CORVUS), CoopCampaignReplicator.orbitSyncLocations(CORVUS, null));
        assertEquals(List.of(MAGEC), CoopCampaignReplicator.orbitSyncLocations(null, MAGEC));
        assertTrue(CoopCampaignReplicator.orbitSyncLocations(null, null).isEmpty());
    }

    private static LocationAPI location(String id, boolean hyperspace) {
        return (LocationAPI) Proxy.newProxyInstance(
                LocationAPI.class.getClassLoader(),
                new Class<?>[]{LocationAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "isHyperspace" -> hyperspace;
                    case "toString" -> "Location[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
    }
}
