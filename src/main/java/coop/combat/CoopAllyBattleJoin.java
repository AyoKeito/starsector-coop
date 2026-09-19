package coop.combat;

/**
 * Phase 33: the partner's mirror has just been pulled into a battle on this engine. Raised once per
 * battle by {@code CoopFleetMirror} on the piloting engine and carried to the owner as
 * {@code ALLY_BATTLE_JOIN}, so the owner sees why its screen is held ("your fleet is fighting
 * alongside ...") instead of finding out from the loss banner afterwards.
 *
 * @param ownerPlayerId the player whose real fleet the mirror stands for
 * @param enemySummary  the other side's primary fleet name as the engine names it, blank if unknown
 */
public record CoopAllyBattleJoin(String ownerPlayerId, String enemySummary) {

    public CoopAllyBattleJoin {
        ownerPlayerId = ownerPlayerId == null ? "" : ownerPlayerId.trim();
        enemySummary = enemySummary == null ? "" : enemySummary.trim();
    }
}
