package coop.combat.join;

/**
 * Marker-hullmod rules for remote hyperspace-comm takeover (mirrors Suite {@code suite_remote_comm}).
 */
public final class CoopRemoteCommRules {

    public static final String HULLMOD_ID = "coop_remote_comm";

    private CoopRemoteCommRules() {
    }

    /** Fleet index 0 is the flagship — never a remote-claim target. */
    public static boolean canMarkAtFleetIndex(int fleetIndex) {
        return fleetIndex > 0;
    }

    /**
     * Pure claim eligibility (no engine).
     */
    public static boolean isClaimableFlags(boolean alive, boolean fighter, boolean shuttle,
                                            boolean hasRemoteCommMod, boolean isEngagedPlayerShip) {
        if (!alive || fighter || shuttle) {
            return false;
        }
        if (!hasRemoteCommMod) {
            return false;
        }
        if (isEngagedPlayerShip) {
            return false;
        }
        return true;
    }
}
