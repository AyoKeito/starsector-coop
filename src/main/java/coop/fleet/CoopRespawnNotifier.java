package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Fleet-wipe detection for the wiped client (Phase 17).
 *
 * <p><b>The mod builds no respawn.</b> Vanilla 0.98a already owns the whole flow:
 * {@code CampaignState.showShuttleDialog()} removes the wiped player fleet, builds a fresh one from
 * the player faction's {@code "shuttle"} stock fleet, carries officers/skills/abilities/reputation and
 * mission cargo across, hands back {@code max(credits * 0.8, 2000)}, and teleports the player to a
 * size-weighted random friendly market. All this class does is notice that it happened, so the partner
 * can be told; without the notice the survivor's only cue is the partner mirror silently jumping
 * across the sector.
 *
 * <p><b>Detection is the object-identity swap</b>, because {@code SectorAPI.setPlayerFleet()} is what
 * the respawn does and every {@code getPlayerFleet()} call site in this mod already re-reads per use
 * for exactly that reason. An identity change alone is too weak a signal to banner on, so a swap only
 * counts when the fleet being replaced looks wiped — see {@link #isRespawnSwap}. The first frame of a
 * session only seeds the tracked reference and fires nothing, and {@link #reset()} puts it back to
 * that state whenever the session stops streaming, so a reconnect re-seeds rather than banners.
 *
 * <p>The engine lives behind {@link Probe} so the frame logic is unit-testable; {@link #engineProbe}
 * is the real implementation and is never touched by tests. Nothing here throws: a probe that cannot
 * read the sector reports "no fleet" and the frame is a no-op.
 */
public final class CoopRespawnNotifier {

    /** How far from the new fleet a market still counts as "where it respawned". */
    private static final float DESTINATION_MARKET_RANGE = 3000f;

    /** The engine facts one frame of detection needs, behind a seam so the logic is testable. */
    public interface Probe {
        /** The current player fleet, or null when there is none / the sector is unreadable. */
        Object playerFleet();

        /** Whether the given fleet is still in the world ({@code SectorEntityToken.isAlive()}). */
        boolean isAlive(Object fleet);

        /** Ship count of the given fleet; 0 for anything unreadable. */
        int memberCount(Object fleet);

        /** Best-effort display name of where the given fleet is; "" when unresolvable. */
        String destinationName(Object fleet);
    }

    /** What a detected wipe carries onto the wire. */
    public record Respawn(String destinationName) {
        public Respawn {
            destinationName = destinationName == null ? "" : destinationName;
        }
    }

    /** Sentinel for "no fleet has been observed yet", so the first frame cannot look like a swap. */
    private static final int NOT_OBSERVED = -1;

    private Object trackedFleet;
    private int lastMemberCount = NOT_OBSERVED;

    /**
     * One frame of detection.
     *
     * @return the respawn to announce, or null on every ordinary frame.
     */
    public Respawn onFrame(Probe probe) {
        if (probe == null) {
            return null;
        }
        Object current;
        try {
            current = probe.playerFleet();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
        if (current == null) {
            // Transient (loading, teardown, an unreadable sector). Keep the tracked reference: losing
            // it here would make the next readable frame look like a swap.
            return null;
        }
        Object previous = trackedFleet;
        if (previous == current) {
            lastMemberCount = safeMemberCount(probe, current);
            return null;
        }

        trackedFleet = current;
        int previousMemberCount = lastMemberCount;
        lastMemberCount = safeMemberCount(probe, current);
        if (previous == null) {
            // First frame of the session: seed only.
            return null;
        }
        if (!isRespawnSwap(previousMemberCount, safeMemberCount(probe, previous),
                safeIsAlive(probe, previous))) {
            return null;
        }
        return new Respawn(safeDestinationName(probe, current));
    }

    /**
     * Forgets the tracked fleet, so the next active frame seeds instead of announcing. Called whenever
     * the session stops streaming (disconnect, session end, reconnect), because the fleet may well
     * have been swapped by an unrelated flow — a save load, say — while nobody was watching.
     */
    public void reset() {
        trackedFleet = null;
        lastMemberCount = NOT_OBSERVED;
    }

    /**
     * The pure half: does a player-fleet identity swap look like a fleet wipe rather than one of the
     * other flows that call {@code setPlayerFleet()}?
     *
     * <p>Three independent signals, any one of which is enough. The last count observed while the old
     * fleet was still current is the strongest — a wiped fleet sits at 0 ships from the end of the
     * battle until the shuttle dialog runs — but the old object is also readable after the swap, and
     * {@code showShuttleDialog} has by then removed it from its location, so it reads dead and empty
     * too. {@link #NOT_OBSERVED} means the old fleet was swapped on the very frame it was first seen,
     * which is not a wipe anyone watched happen.
     */
    static boolean isRespawnSwap(int lastObservedMemberCount, int previousMemberCountNow,
                                 boolean previousAlive) {
        if (lastObservedMemberCount == NOT_OBSERVED) {
            return false;
        }
        return lastObservedMemberCount == 0 || previousMemberCountNow == 0 || !previousAlive;
    }

    private static int safeMemberCount(Probe probe, Object fleet) {
        try {
            return Math.max(0, probe.memberCount(fleet));
        } catch (RuntimeException | LinkageError ex) {
            return 0;
        }
    }

    private static boolean safeIsAlive(Probe probe, Object fleet) {
        try {
            return probe.isAlive(fleet);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static String safeDestinationName(Probe probe, Object fleet) {
        try {
            String name = probe.destinationName(fleet);
            return name == null ? "" : name;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    /** The live-engine {@link Probe}. Tolerates a null sector so the pump can call it unconditionally. */
    public static Probe engineProbe(SectorAPI sector) {
        return new Probe() {
            @Override
            public Object playerFleet() {
                // Re-read every frame, never cached: setPlayerFleet() swapping the object is the very
                // thing being detected.
                return sector == null ? null : sector.getPlayerFleet();
            }

            @Override
            public boolean isAlive(Object fleet) {
                return fleet instanceof CampaignFleetAPI campaignFleet && campaignFleet.isAlive();
            }

            @Override
            public int memberCount(Object fleet) {
                if (!(fleet instanceof CampaignFleetAPI campaignFleet)) {
                    return 0;
                }
                return campaignFleet.getNumShips();
            }

            @Override
            public String destinationName(Object fleet) {
                return describeDestination(fleet);
            }
        };
    }

    /**
     * Where the respawned fleet came out: the market it was placed at if one is close enough, else the
     * containing system. Best-effort by contract — the banner reads better with a blank tail than the
     * detection does with an exception.
     */
    static String describeDestination(Object fleet) {
        if (!(fleet instanceof CampaignFleetAPI campaignFleet)) {
            return "";
        }
        try {
            MarketAPI market = Misc.findNearestLocalMarket(
                    campaignFleet, DESTINATION_MARKET_RANGE, null);
            if (market != null && market.getName() != null && !market.getName().isEmpty()) {
                return market.getName();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to the location name.
        }
        try {
            LocationAPI location = campaignFleet.getContainingLocation();
            if (location != null) {
                String name = location.getNameWithLowercaseType();
                if (name == null || name.isEmpty()) {
                    name = location.getName();
                }
                return name == null ? "" : name;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // As above: a missing destination is a shorter banner, not a failure.
        }
        return "";
    }
}
