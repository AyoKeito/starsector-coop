package coop.save;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import coop.fleet.CoopFleetSnapshot;
import coop.fleet.CoopFleetSnapshotFactory;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures the local player's campaign state into a {@link CoopGuestSnapshot} (Phase 16, guest side).
 *
 * <p>Every engine read is best-effort and independently guarded: this runs on a 30-second timer for a
 * write-only recovery artifact, so one unreadable cargo stack must cost that stack and nothing else.
 * A snapshot with a hole in it is still worth more than no snapshot.
 *
 * <p>The ship roster comes straight from {@link CoopFleetSnapshotFactory#captureMembers}, the same
 * capture the 10 Hz mirror stream uses — so the stock-id contract and the Phase 16 permanent-hullmod
 * fields are inherited rather than reimplemented here.
 */
public final class CoopGuestSnapshotFactory {

    private CoopGuestSnapshotFactory() {
    }

    /**
     * @param sector     the live sector; a null sector or a fleet-less player yields null.
     * @return the snapshot, or null when there is nothing to capture.
     */
    public static CoopGuestSnapshot capture(SectorAPI sector, String sessionId, String playerId,
                                            String playerName, String campaignId, String seedString,
                                            long nowMillis) {
        if (sector == null) {
            return null;
        }
        CampaignFleetAPI fleet;
        try {
            fleet = sector.getPlayerFleet();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopGuestSnapshotFactory.class,
                    "Failed to read the player fleet for the coop guest snapshot", ex);
            return null;
        }
        if (fleet == null) {
            return null;
        }

        CoopGuestSnapshot snapshot = new CoopGuestSnapshot();
        snapshot.setCapturedAtMillis(nowMillis);
        snapshot.setSessionId(sessionId);
        snapshot.setPlayerId(playerId);
        snapshot.setPlayerName(playerName);
        snapshot.setCampaignId(campaignId);
        snapshot.setSeedString(seedString);
        snapshot.setLocationId(locationId(fleet));
        snapshot.setFactionId(factionId(fleet));

        CargoAPI cargo = cargoOrNull(fleet);
        snapshot.setCredits(credits(cargo));
        snapshot.setCargo(captureCargo(cargo));
        snapshot.setShips(captureShips(fleet));
        snapshot.setOfficers(captureOfficers(fleet));
        return snapshot;
    }

    private static CargoAPI cargoOrNull(CampaignFleetAPI fleet) {
        try {
            return fleet.getCargo();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static double credits(CargoAPI cargo) {
        try {
            return cargo == null || cargo.getCredits() == null ? 0d : cargo.getCredits().get();
        } catch (RuntimeException | LinkageError ignored) {
            return 0d;
        }
    }

    private static List<CoopGuestSnapshot.CargoStack> captureCargo(CargoAPI cargo) {
        List<CoopGuestSnapshot.CargoStack> stacks = new ArrayList<>();
        if (cargo == null) {
            return stacks;
        }
        List<CargoStackAPI> source;
        try {
            source = cargo.getStacksCopy();
        } catch (RuntimeException | LinkageError ignored) {
            return stacks;
        }
        if (source == null) {
            return stacks;
        }
        for (CargoStackAPI stack : source) {
            try {
                if (stack == null || stack.isNull()) {
                    continue;
                }
                stacks.add(new CoopGuestSnapshot.CargoStack(
                        stack.getType() == null ? "" : stack.getType().name(),
                        stackId(stack),
                        stack.getSize()));
            } catch (RuntimeException | LinkageError ignored) {
                // One unreadable stack costs exactly itself.
            }
        }
        return stacks;
    }

    /**
     * The stack's identity as a stock id. {@code getData()} is the engine's own discriminator — a
     * commodity id string, a {@code WeaponSpecAPI}, a {@code SpecialItemData} — so its string form is
     * only meaningful for the commodity case; the typed accessors cover the rest.
     */
    private static String stackId(CargoStackAPI stack) {
        try {
            if (stack.isCommodityStack()) {
                return normalize(stack.getCommodityId());
            }
            if (stack.isWeaponStack() && stack.getWeaponSpecIfWeapon() != null) {
                return normalize(stack.getWeaponSpecIfWeapon().getWeaponId());
            }
            if (stack.isFighterWingStack() && stack.getFighterWingSpecIfWing() != null) {
                return normalize(stack.getFighterWingSpecIfWing().getId());
            }
            // No modspec branch: in 0.98a a modspec is a special stack whose data carries the hullmod
            // id, and isModSpecStack() is deprecated in favour of exactly that.
            if (stack.isSpecialStack() && stack.getSpecialDataIfSpecial() != null) {
                String id = normalize(stack.getSpecialDataIfSpecial().getId());
                String data = normalize(stack.getSpecialDataIfSpecial().getData());
                return data.isEmpty() ? id : id + ":" + data;
            }
            Object data = stack.getData();
            return data == null ? "" : String.valueOf(data);
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static List<CoopGuestSnapshot.Ship> captureShips(CampaignFleetAPI fleet) {
        List<CoopGuestSnapshot.Ship> ships = new ArrayList<>();
        List<CoopFleetSnapshot.Member> members;
        try {
            members = CoopFleetSnapshotFactory.captureMembers(fleet);
        } catch (RuntimeException | LinkageError ignored) {
            return ships;
        }
        for (CoopFleetSnapshot.Member member : members) {
            ships.add(new CoopGuestSnapshot.Ship(member.fleetMemberId(), member.hullId(),
                    member.variantId(), member.shipName(), member.captainName(), member.cr(),
                    member.hullFraction(), member.dmodIds(), member.sModIds(),
                    member.sModdedBuiltInIds()));
        }
        return ships;
    }

    private static List<CoopGuestSnapshot.Officer> captureOfficers(CampaignFleetAPI fleet) {
        List<CoopGuestSnapshot.Officer> officers = new ArrayList<>();
        List<OfficerDataAPI> source;
        try {
            source = fleet.getFleetData().getOfficersCopy();
        } catch (RuntimeException | LinkageError ignored) {
            return officers;
        }
        if (source == null) {
            return officers;
        }
        for (OfficerDataAPI data : source) {
            try {
                PersonAPI person = data == null ? null : data.getPerson();
                if (person == null) {
                    continue;
                }
                MutableCharacterStatsAPI stats = person.getStats();
                officers.add(new CoopGuestSnapshot.Officer(
                        normalize(person.getNameString()),
                        personality(person),
                        stats == null ? 0 : stats.getLevel(),
                        skills(stats)));
            } catch (RuntimeException | LinkageError ignored) {
                // One unreadable officer costs exactly itself.
            }
        }
        return officers;
    }

    private static String personality(PersonAPI person) {
        try {
            return person.getPersonalityAPI() == null ? "" : normalize(person.getPersonalityAPI().getId());
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    /** {@code skillId:level} pairs, comma joined — flat on purpose; see {@code Officer}. */
    private static String skills(MutableCharacterStatsAPI stats) {
        if (stats == null) {
            return "";
        }
        try {
            StringBuilder out = new StringBuilder(64);
            for (MutableCharacterStatsAPI.SkillLevelAPI skill : stats.getSkillsCopy()) {
                if (skill == null || skill.getSkill() == null || skill.getLevel() <= 0f) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(skill.getSkill().getId()).append(':').append(skill.getLevel());
            }
            return out.toString();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static String locationId(CampaignFleetAPI fleet) {
        try {
            LocationAPI location = fleet.getContainingLocation();
            return location == null ? "" : normalize(location.getId());
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static String factionId(CampaignFleetAPI fleet) {
        try {
            return fleet.getFaction() == null ? "" : normalize(fleet.getFaction().getId());
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
