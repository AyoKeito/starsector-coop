package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Captures the local player's fleet into a {@link CoopFleetSnapshot} for replication.
 *
 * <p>All engine reads are best-effort: a single ship that fails to report a field must not abort the
 * whole snapshot, because dropping one campaign tick of mirror state is harmless (the next 10 Hz
 * snapshot supersedes it).
 */
public final class CoopFleetSnapshotFactory {
    private CoopFleetSnapshotFactory() {
    }

    public static CoopFleetSnapshot captureLocalPlayer(SectorAPI sector, String playerId, String username) {
        Objects.requireNonNull(sector, "sector");
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        if (fleet == null) {
            return null;
        }
        return capture(fleet, playerId, username);
    }

    public static CoopFleetSnapshot capture(CampaignFleetAPI fleet, String playerId, String username) {
        Objects.requireNonNull(fleet, "fleet");

        Vector2f location = fleet.getLocation();
        Vector2f velocity = fleet.getVelocity();
        String locationId = locationId(fleet.getContainingLocation());
        String factionId = factionId(fleet);
        boolean transponderOn = transponderOn(fleet);

        List<CoopFleetSnapshot.Member> members = new ArrayList<>();
        try {
            for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                if (member == null || member.isFighterWing()) {
                    continue;
                }
                members.add(captureMember(member));
            }
        } catch (RuntimeException ignored) {
            // Keep whatever members were captured before the failure.
        }

        return CoopFleetSnapshot.create(
                playerId,
                username,
                locationId,
                location == null ? 0f : location.x,
                location == null ? 0f : location.y,
                velocity == null ? 0f : velocity.x,
                velocity == null ? 0f : velocity.y,
                factionId,
                transponderOn,
                members);
    }

    private static CoopFleetSnapshot.Member captureMember(FleetMemberAPI member) {
        String variantId = "";
        try {
            if (member.getVariant() != null) {
                variantId = member.getVariant().getHullVariantId();
            }
        } catch (RuntimeException ignored) {
            variantId = "";
        }
        if (variantId == null || variantId.isEmpty()) {
            variantId = member.getSpecId();
        }

        String captainName = "";
        try {
            PersonAPI captain = member.getCaptain();
            if (captain != null && !captain.isDefault()) {
                captainName = captain.getNameString();
            }
        } catch (RuntimeException ignored) {
            captainName = "";
        }

        float cr = readFloat(() -> member.getRepairTracker().getCR(), 0f);
        float hullFraction = readFloat(() -> member.getStatus().getHullFraction(), 1f);

        return new CoopFleetSnapshot.Member(
                member.getId(),
                member.getHullId(),
                variantId,
                member.getShipName(),
                captainName,
                cr,
                hullFraction);
    }

    private static String locationId(LocationAPI location) {
        if (location == null) {
            return "";
        }
        String id = location.getId();
        return id == null ? "" : id;
    }

    private static String factionId(CampaignFleetAPI fleet) {
        try {
            if (fleet.getFaction() != null) {
                return fleet.getFaction().getId();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "";
    }

    private static boolean transponderOn(CampaignFleetAPI fleet) {
        try {
            return fleet.isTransponderOn();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private interface FloatRead {
        float read();
    }

    private static float readFloat(FloatRead read, float fallback) {
        try {
            return read.read();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
