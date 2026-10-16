package coop.combat.join;

import java.util.Collections;
import java.util.List;

/**
 * Optional multipilot / remote-claim hooks for a Suite-style combat package.
 *
 * <p>Default no-ops keep upstream coop playable without the Suite jar. {@link CoopSuiteCombatBridge}
 * installs a reflective Suite adapter when that mod is present.
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

    /** Extra campaign banners after Accept (Suite present / missing guidance). */
    default List<String> extraBannersAfterAccept(String battleId, String partnerName) {
        return Collections.emptyList();
    }

    /** Extra campaign banners after a proximity notice. */
    default List<String> extraBannersAfterProximity(String battleId, String partnerName) {
        return Collections.emptyList();
    }
}
