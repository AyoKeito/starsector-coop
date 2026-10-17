package coop.combat.shared;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import coop.util.CoopLog;

import java.util.List;

/**
 * Phase A spike: put the non-engaging partner into a real {@code CombatEngine}.
 *
 * <p>Creates a temporary shadow enemy fleet from hull ids (from the engager's battle status /
 * begin payload) and calls {@code CampaignUIAPI.startBattle}. This is the dual-engine entry that
 * Suite/BO assumes; frame sync and claim layer on top once both engines exist.
 */
public final class CoopSharedCombatEntry {

    public static final String SHADOW_FLEET_NAME = "Coop Shadow Hostiles";

    private CoopSharedCombatEntry() {
    }

    /**
     * @param enemyHullIds hull specs to seed the opposing side; if empty, uses a single onslaught hull
     * @return true when {@code startBattle} was issued without throwing
     */
    public static boolean enterAgainstShadowEnemy(List<String> enemyHullIds) {
        try {
            SectorAPI sector = Global.getSector();
            CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
            CampaignFleetAPI player = sector == null ? null : sector.getPlayerFleet();
            if (ui == null || player == null) {
                CoopLog.warn(CoopSharedCombatEntry.class, "shared combat entry aborted: no UI/player");
                return false;
            }
            CampaignFleetAPI shadow = createShadowEnemyFleet(sector, player, enemyHullIds);
            if (shadow == null) {
                CoopLog.warn(CoopSharedCombatEntry.class, "shared combat entry aborted: no shadow fleet");
                return false;
            }
            BattleCreationContext ctx = new BattleCreationContext(
                    player, FleetGoal.ATTACK, shadow, FleetGoal.ATTACK);
            ui.startBattle(ctx);
            CoopLog.info(CoopSharedCombatEntry.class,
                    "Coop shared combat ENTRY issued vs shadow enemies hulls="
                            + (enemyHullIds == null ? 0 : enemyHullIds.size()));
            return true;
        } catch (Throwable t) {
            CoopLog.warn(CoopSharedCombatEntry.class, "shared combat entry failed", t);
            return false;
        }
    }

    static CampaignFleetAPI createShadowEnemyFleet(SectorAPI sector, CampaignFleetAPI player,
                                                     List<String> enemyHullIds) {
        try {
            CampaignFleetAPI shadow = Global.getFactory().createEmptyFleet(
                    "pirates", SHADOW_FLEET_NAME, false);
            if (shadow == null) {
                return null;
            }
            List<String> hulls = enemyHullIds;
            if (hulls == null || hulls.isEmpty()) {
                hulls = List.of("onslaught_Standard");
            }
            int added = 0;
            for (String hullOrVariant : hulls) {
                if (hullOrVariant == null || hullOrVariant.isBlank()) {
                    continue;
                }
                String id = hullOrVariant.trim();
                try {
                    FleetMemberAPI member = Global.getFactory()
                            .createFleetMember(FleetMemberType.SHIP, id);
                    if (member != null) {
                        shadow.getFleetData().addFleetMember(member);
                        ++added;
                    }
                } catch (Throwable t) {
                    // try as bare hull id with _Standard
                    try {
                        FleetMemberAPI member = Global.getFactory()
                                .createFleetMember(FleetMemberType.SHIP, id + "_Standard");
                        if (member != null) {
                            shadow.getFleetData().addFleetMember(member);
                            ++added;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (added >= 12) {
                    break;
                }
            }
            if (added == 0) {
                return null;
            }
            if (player.getContainingLocation() != null) {
                player.getContainingLocation().spawnFleet(
                        player, 80f, 80f, shadow);
            }
            return shadow;
        } catch (Throwable t) {
            CoopLog.warn(CoopSharedCombatEntry.class, "createShadowEnemyFleet failed", t);
            return null;
        }
    }
}
