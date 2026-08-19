package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.util.CoopLog;
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
                // Phase 14b: the remote client pins this onto the mirror so its NPC AI detects the
                // remote player at vanilla ranges — transponder, Go Dark, burns, sensor burst, terrain.
                CoopSensorSync.capture(fleet),
                captureMembers(fleet));
    }

    /**
     * Captures a fleet's ship roster as replicable members, shared by the Phase 8 player snapshot and
     * the Phase 9 NPC fleet snapshots ({@link CoopNpcFleetReplicator}). Best-effort: a member that
     * fails to report a field is skipped rather than aborting the whole roster.
     *
     * <p><b>The per-member catch is load-bearing (2026-08-19).</b> It used to be a single try around
     * the whole loop, which meant one ship that threw while being read truncated the roster at that
     * point — and a throw on the <em>first</em> ship replicated the fleet as zero ships. That is not a
     * dropped frame: {@code CoopFleetSnapshot#computeFleetHash} of the truncated list is perfectly
     * stable, so the guest's {@code CoopFleetMirror#refreshRosterIfChanged} gate accepts it once and
     * then never rebuilds until the host fleet's real roster changes. The guest log for build 56b025f
     * shows the end state directly: 20 "roster refreshed to 0 ship(s)" lines, six of them inside a
     * single set apply.
     */
    public static List<CoopFleetSnapshot.Member> captureMembers(CampaignFleetAPI fleet) {
        Objects.requireNonNull(fleet, "fleet");
        List<CoopFleetSnapshot.Member> members = new ArrayList<>();
        List<FleetMemberAPI> source;
        try {
            source = fleet.getFleetData().getMembersListCopy();
        } catch (RuntimeException ex) {
            // The fleet itself cannot report a roster: nothing to salvage.
            CoopLog.warn(CoopFleetSnapshotFactory.class,
                    "Coop could not read the fleet data of " + safeName(fleet), ex);
            return members;
        }
        if (source == null) {
            return members;
        }
        int skipped = captureInto(members, engineSource(source));
        if (skipped > 0) {
            CoopLog.warn(CoopFleetSnapshotFactory.class, "Coop skipped " + skipped
                    + " unreadable ship(s) while capturing the roster of " + safeName(fleet)
                    + "; the mirror will be short by that many");
        }
        return members;
    }

    /**
     * The resilience rule on its own, behind a seam so it can be unit-tested without an engine: read
     * every slot, keep every slot that reads, and let a slot that throws cost exactly itself.
     *
     * @return how many members were skipped because reading them threw.
     */
    static int captureInto(List<CoopFleetSnapshot.Member> out, MemberSource source) {
        int skipped = 0;
        for (int i = 0; i < source.size(); i++) {
            boolean wing;
            try {
                wing = source.isFighterWing(i);
            } catch (RuntimeException ignored) {
                // A member that cannot answer "am I a wing?" is treated as one, i.e. not replicated.
                wing = true;
            }
            if (wing) {
                continue;
            }
            try {
                out.add(source.capture(i));
            } catch (RuntimeException ignored) {
                skipped++;
            }
        }
        return skipped;
    }

    /** One fleet's replicable ship slots. {@link #captureInto} assumes any call here can throw. */
    interface MemberSource {
        int size();

        boolean isFighterWing(int index);

        CoopFleetSnapshot.Member capture(int index);
    }

    private static MemberSource engineSource(List<FleetMemberAPI> members) {
        return new MemberSource() {
            @Override
            public int size() {
                return members.size();
            }

            @Override
            public boolean isFighterWing(int index) {
                FleetMemberAPI member = members.get(index);
                return member == null || member.isFighterWing();
            }

            @Override
            public CoopFleetSnapshot.Member capture(int index) {
                return captureMember(members.get(index));
            }
        };
    }

    private static String safeName(CampaignFleetAPI fleet) {
        try {
            String name = fleet.getName();
            return name == null ? "?" : name;
        } catch (RuntimeException ignored) {
            return "?";
        }
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
