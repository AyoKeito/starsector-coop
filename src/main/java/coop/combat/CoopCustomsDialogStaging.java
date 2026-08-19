package coop.combat;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import coop.net.CoopMessages;

/**
 * Stages the vanilla patrol-stop posture on a guest-side NPC mirror so that opening the standard
 * fleet interaction against it makes the rules engine take a scan branch instead of the plain
 * engage/leave menu (Phase 14, {@code DIALOG_BEGIN}).
 *
 * <p>Spike-proven 2026-08-19 (see {@code docs/PHASE14_SPIKE_NOTES.md}): the full vanilla running-dark
 * confrontation ran end to end against a Hegemony mirror on the guest — hail, {@code AdjustRep
 * TRANSPONDER_OFF}, comply/story-point/refuse, {@code CargoScan} against the guest's real cargo — and
 * the faction rep delta reached the host through the existing Phase 12 {@code GUEST_REP_DELTA} path
 * with no new code.
 *
 * <p><b>Which rules.</b> The plan's named customs rules ({@code $doingCustomsInspection},
 * rules.csv:2749-2854) are pre-0.8 dead code: nothing sets the key and the driving rulecmds do not
 * ship. The two live paths are
 * <ul>
 *   <li>{@link CoopMessages.DialogKind#CUSTOMS} &rarr; {@code tOffPatrolBegin} (rules.csv:3395), the
 *       running-dark stop — exactly the Phase 9 gap fix, and the default;</li>
 *   <li>{@link CoopMessages.DialogKind#INSPECTION} &rarr; {@code cargoScanInitial}
 *       (rules.csv:3551), the smuggling-suspicion scan — fewest preconditions, the fallback.</li>
 * </ul>
 * Both end in the same {@code CargoScan} rulecmd, which reads
 * {@code Global.getSector().getPlayerFleet()} for the cargo it scans and judges legality against the
 * patrol's faction — against a local mirror that is precisely the desired behaviour.
 *
 * <p><b>Preconditions that break the path silently</b> (verified in the spike, checked by
 * {@link #describePreconditions}): the mirror needs a commander (a null one removes
 * {@code MemKeys.ENTITY} from the rule memory map, so every {@code OpenCommLink} {@code $entity.*}
 * condition fails), a {@code $sourceMarket} pointing at a real non-free-port market (else
 * {@code CargoScan} dereferences it and NPEs), a non-hostile posture, and a non-player faction.
 */
public final class CoopCustomsDialogStaging {

    /** Per-encounter latches the two live scan rules bail on (rules.csv:3395 / :3551). */
    private static final String TOFF_DID_ALREADY_FLAG = "$tOff_didAlready";
    private static final String CARGO_SCAN_DID_ALREADY_FLAG = "$cargoScan_didAlready";

    private CoopCustomsDialogStaging() {
    }

    /**
     * Applies the posture flags to {@code mirror}'s own memory and returns a log-friendly summary of
     * what was set. All flags go on the fleet memory bare (no {@code $entity.} prefix): at
     * {@code BeginFleetEncounter} no active person is set yet, so {@code MemKeys.LOCAL} is the fleet.
     *
     * <p>Never throws: each flag write is individually guarded, because a half-staged mirror still
     * opens a normal encounter dialog while an exception here would take the pump frame down.
     */
    public static String stage(SectorAPI sector, CampaignFleetAPI mirror, CoopMessages.DialogKind kind) {
        MemoryAPI mem = memoryOf(mirror);
        if (mem == null) {
            return "no-memory";
        }
        StringBuilder out = new StringBuilder();
        // A fleet that ignores the player, or is ignored by everyone, will not stop anybody. The
        // mirror carries FLEET_IGNORES_OTHER_FLEETS permanently (it closes the battle pull-in path),
        // so it is cleared for the encounter and restored by the next mirror snapshot apply.
        unsetFlag(mem, out, MemFlags.FLEET_IGNORES_OTHER_FLEETS);
        unsetFlag(mem, out, MemFlags.MEMORY_KEY_IGNORE_PLAYER_COMMS);
        unsetFlag(mem, out, MemFlags.MEMORY_KEY_PATROL_ALLOW_TOFF);
        setFlag(mem, out, MemFlags.FLEET_DO_NOT_IGNORE_PLAYER, true);
        setFlag(mem, out, MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        unsetFlag(mem, out, TOFF_DID_ALREADY_FLAG);
        unsetFlag(mem, out, CARGO_SCAN_DID_ALREADY_FLAG);
        out.append(applySourceMarket(sector, mirror, mem)).append(' ');

        if (kind == CoopMessages.DialogKind.INSPECTION) {
            // cargoScanInitial keys off $pursuePlayer_smugglingScan (score 50). SmugglingScanScript
            // writes it through Misc.setFlagWithReason, which also maintains the required-key link --
            // do the same rather than poking the derived key by hand.
            setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_PURSUE_PLAYER, "smugglingScan", 1f);
            setFlagWithReason(mem, out,
                    MemFlags.MEMORY_KEY_STICK_WITH_PLAYER_IF_ALREADY_TARGET, "smugglingScan", 10f);
        } else {
            // tOffPatrolBegin needs CaresAboutTransponder (which reduces to
            // $cfai_makeAggressive_tOff, per CaresAboutTransponder.java:28 + Misc.flagHasReason) plus
            // $sawPlayerTransponderOff at score 100. The latter is normally written by
            // CoreCampaignPluginImpl.updateEntityFacts while the player runs dark; the host has
            // already decided that is the case, so set it directly.
            setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, "tOff", 7f);
            setFlag(mem, out, MemFlags.MEMORY_KEY_SAW_PLAYER_WITH_TRANSPONDER_OFF, true);
        }
        return out.toString().trim();
    }

    /** Log line naming any precondition that would make the rules path fall through silently. */
    public static String describePreconditions(CampaignFleetAPI mirror) {
        return "faction=" + factionOf(mirror)
                + " playerFaction=" + isPlayerFaction(mirror)
                + " commander=" + commanderOf(mirror);
    }

    /**
     * {@code CargoScan} reads {@code Misc.getSourceMarket(other).getMemory()} before its own null
     * check, so a mirror without a valid {@code $sourceMarket} crashes the scan. Pick a non-free-port
     * market of the mirror's own faction, preferring one in the mirror's location. (The transponder
     * branch also requires {@code !$sourceMarket.mc:free_market}, so free ports are excluded twice
     * over on purpose.)
     */
    private static String applySourceMarket(SectorAPI sector, CampaignFleetAPI mirror, MemoryAPI mem) {
        try {
            String existing = mem.getString(MemFlags.MEMORY_KEY_SOURCE_MARKET);
            if (existing != null && !existing.isEmpty() && sector.getEconomy().getMarket(existing) != null) {
                return "$sourceMarket=" + existing + "(kept)";
            }
        } catch (RuntimeException | LinkageError ex) {
            // fall through and pick one
        }
        MarketAPI picked = pickSourceMarket(sector, mirror);
        if (picked == null) {
            return "$sourceMarket=NONE(CargoScan would NPE)";
        }
        try {
            mem.set(MemFlags.MEMORY_KEY_SOURCE_MARKET, picked.getId());
            return "$sourceMarket=" + picked.getId();
        } catch (RuntimeException | LinkageError ex) {
            return "$sourceMarket=THREW";
        }
    }

    private static MarketAPI pickSourceMarket(SectorAPI sector, CampaignFleetAPI mirror) {
        String factionId = factionOf(mirror);
        MarketAPI fallback = null;
        try {
            LocationAPI location = mirror.getContainingLocation();
            for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
                if (market == null || market.getFactionId() == null
                        || !market.getFactionId().equals(factionId)
                        || market.hasCondition(Conditions.FREE_PORT)) {
                    continue;
                }
                if (location != null && market.getContainingLocation() == location) {
                    return market;
                }
                if (fallback == null) {
                    fallback = market;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return fallback;
        }
        return fallback;
    }

    private static void setFlagWithReason(MemoryAPI mem, StringBuilder out, String key,
                                          String reason, float expireDays) {
        try {
            Misc.setFlagWithReason(mem, key, reason, true, expireDays);
            out.append(key).append('_').append(reason).append("=true ");
        } catch (RuntimeException | LinkageError ex) {
            out.append(key).append('_').append(reason).append("=THREW ");
        }
    }

    private static void setFlag(MemoryAPI mem, StringBuilder out, String key, Object value) {
        try {
            mem.set(key, value);
            out.append(key).append('=').append(value).append(' ');
        } catch (RuntimeException | LinkageError ex) {
            out.append(key).append("=THREW ");
        }
    }

    private static void unsetFlag(MemoryAPI mem, StringBuilder out, String key) {
        try {
            mem.unset(key);
            out.append('-').append(key).append(' ');
        } catch (RuntimeException | LinkageError ex) {
            out.append('-').append(key).append("=THREW ");
        }
    }

    private static MemoryAPI memoryOf(CampaignFleetAPI fleet) {
        try {
            return fleet == null ? null : fleet.getMemoryWithoutUpdate();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String factionOf(CampaignFleetAPI fleet) {
        try {
            return fleet == null || fleet.getFaction() == null ? "?" : fleet.getFaction().getId();
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static boolean isPlayerFaction(CampaignFleetAPI fleet) {
        try {
            return fleet != null && fleet.getFaction() != null && fleet.getFaction().isPlayerFaction();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static String commanderOf(CampaignFleetAPI fleet) {
        try {
            return fleet == null || fleet.getCommander() == null ? "none" : fleet.getCommander().getNameString();
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }
}
