package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.abilities.BaseToggleAbility;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import coop.combat.join.CoopCombatJoinCoordinator;
import coop.util.CoopLog;

import java.util.List;

/**
 * Spectator abilities for an active hyperspace support call ({@code PENDING} prompt).
 */
public class CoopRemoteSupportAbility extends BaseToggleAbility {

    public static final String ACCEPT_ID = "coop_remote_support_accept";
    public static final String DECLINE_ID = "coop_remote_support_decline";

    @Override
    protected void activateImpl() {
        CoopCombatJoinCoordinator join = CoopCombatJoinCoordinator.active();
        if (join == null || !join.prompt().isPending()) {
            return;
        }
        String id = abilitySpecId();
        List<String> banners = DECLINE_ID.equals(id)
                ? join.declineRemoteSupport()
                : join.acceptRemoteSupport();
        for (String banner : banners) {
            try {
                Global.getSector().getCampaignUI().addMessage(banner);
            } catch (Throwable t) {
                CoopLog.info(CoopRemoteSupportAbility.class, banner);
            }
        }
        // Instant gesture — do not leave the toggle "on".
        try {
            deactivate();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void applyEffect(float amount, float level) {
    }

    @Override
    protected void deactivateImpl() {
    }

    @Override
    protected void cleanupImpl() {
    }

    @Override
    public boolean isUsable() {
        CoopCombatJoinCoordinator join = CoopCombatJoinCoordinator.active();
        return join != null && join.prompt().isPending();
    }

    @Override
    public boolean hasTooltip() {
        return true;
    }

    @Override
    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        String id = abilitySpecId();
        if (DECLINE_ID.equals(id)) {
            tooltip.addTitle("Decline Hyperspace Support");
            tooltip.addPara("Stay paused until your partner's battle ends. Your fleet does not join.", 10f);
        } else {
            tooltip.addTitle("Accept Hyperspace Support");
            tooltip.addPara("Remote-pilot a ship marked Hyperspace Comm Seat on your partner's fleet."
                    + " Flagships cannot be claimed. Losses and spoils stay with the engaged player.", 10f);
        }
    }

    private String abilitySpecId() {
        try {
            return getSpec() != null ? getSpec().getId() : ACCEPT_ID;
        } catch (Throwable t) {
            return ACCEPT_ID;
        }
    }
}
