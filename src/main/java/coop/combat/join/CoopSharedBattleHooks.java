package coop.combat.join;

/**
 * Optional multipilot / remote-claim hooks for a Suite-style combat package.
 *
 * <p>Default no-ops keep upstream coop playable without the Suite jar. A future combat addon
 * implements this and registers via {@link CoopCombatJoinCoordinator#setSharedBattleHooks}.
 */
public interface CoopSharedBattleHooks {

    CoopSharedBattleHooks NOOP = new CoopSharedBattleHooks() {
    };

    /** Partner is in ally pull-in range — prepare same-battle multi-pilot if available. */
    default void onProximityJoinAvailable(String battleId, String partnerName) {
    }

    /** Spectator accepted hyperspace remote support for {@code battleId}. */
    default void onRemoteSupportAccepted(String battleId, String partnerName) {
    }

    /** Spectator declined or had no marked ship. */
    default void onRemoteSupportUnavailable(String battleId, CoopRemoteSupportPrompt.State reason) {
    }

    /** Remote battle ended — release any claim / local combat shim. */
    default void onRemoteBattleEnded(String battleId) {
    }
}
