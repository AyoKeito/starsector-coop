package coop.combat.join;

/**
 * Spectator-side hyperspace support call while a remote battle is active.
 *
 * <p>Banners are produced as strings for the battle bridge to queue; accept/decline are driven by
 * abilities or future UI. Pure enough for headless tests.
 */
public final class CoopRemoteSupportPrompt {

    public enum State {
        IDLE,
        PENDING,
        ACCEPTED,
        DECLINED,
        NO_MARKED_SHIP
    }

    private State state = State.IDLE;
    private String battleId = "";
    private String partnerName = "";
    private String enemySummary = "";

    public State state() {
        return state;
    }

    public String battleId() {
        return battleId;
    }

    public boolean isPending() {
        return state == State.PENDING;
    }

    /**
     * Opens a support call for a remote (or unknown-fallback-remote) battle.
     *
     * @return banner lines to queue (may be empty if ignored)
     */
    public String[] beginRemoteCall(String battleId, String partnerName, String enemySummary,
                                    boolean hasMarkedShip) {
        this.battleId = battleId == null ? "" : battleId.trim();
        this.partnerName = partnerName == null || partnerName.isBlank() ? "your partner" : partnerName.trim();
        this.enemySummary = enemySummary == null ? "" : enemySummary.trim();
        if (!hasMarkedShip) {
            this.state = State.NO_MARKED_SHIP;
            return new String[]{
                    "Coop: " + this.partnerName + " calls for hyperspace support, but no ship is marked"
                            + " with Hyperspace Comm Seat — you stay paused until the battle ends."
            };
        }
        this.state = State.PENDING;
        String enemy = this.enemySummary.isEmpty() ? "an enemy" : this.enemySummary;
        return new String[]{
                "Coop: " + this.partnerName + " is fighting " + enemy
                        + " — hyperspace support call. Use the Hyperspace Support ability to Accept"
                        + " or Decline. Losses stay with the engaged fleet."
        };
    }

    /** Proximity battles do not open a remote call; optional info banner. */
    public String[] beginProximityNotice(String partnerName, String enemySummary) {
        clear();
        String who = partnerName == null || partnerName.isBlank() ? "Your partner" : partnerName.trim();
        String enemy = enemySummary == null || enemySummary.isBlank() ? "an enemy" : enemySummary.trim();
        return new String[]{
                "Coop: " + who + " engaged " + enemy
                        + " within ally range — proximity join (side-by-side fleets) is available."
        };
    }

    public boolean accept() {
        if (state != State.PENDING) {
            return false;
        }
        state = State.ACCEPTED;
        return true;
    }

    public boolean decline() {
        if (state != State.PENDING) {
            return false;
        }
        state = State.DECLINED;
        return true;
    }

    public void clear() {
        state = State.IDLE;
        battleId = "";
        partnerName = "";
        enemySummary = "";
    }

    public String acceptBanner() {
        return "Coop: hyperspace link accepted — remote-pilot a marked non-flagship on "
                + partnerName + "'s fleet. Losses stay with the engaged fleet.";
    }

    public String declineBanner() {
        return "Coop: hyperspace support declined — campaign stays paused until "
                + partnerName + "'s battle ends.";
    }
}
