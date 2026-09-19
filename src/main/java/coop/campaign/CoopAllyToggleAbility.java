package coop.campaign;

import com.fs.starfarer.api.impl.campaign.abilities.BaseToggleAbility;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

/**
 * Phase 33: the owner-side consent toggle for AI-ally battles. One row in
 * {@code data/campaign/abilities.csv}, one plugin, and no effect of its own — the <em>state</em> is
 * the whole feature.
 *
 * <p>What reads it: {@code CoopFleetSnapshotFactory} puts {@code isActive()} on the fleet snapshot,
 * and the engine that holds this fleet's mirror writes {@code FLEET_IGNORES_OTHER_FLEETS} from the
 * bit on every apply ({@code CoopFleetMirror.applyPlayerMirrorPosture}). That flag is the only thing
 * vanilla's {@code FleetInteractionDialogPluginImpl.pullInNearbyFleets} consults, so clearing it is
 * what makes the mirror joinable and setting it is what keeps the mirror out of fights the way every
 * shipped session before 0.1.4 did.
 *
 * <p><b>Nothing is replicated by the ability system.</b> {@code CoopAbilityArbiter} lists the id as
 * local, so activating it never sends an {@code ABILITY_ACTIVATE} and the partner's engine never
 * toggles its own copy: each player's toggle governs their own fleet and nobody else's. The state
 * persists the way any ability's does, in the save with the fleet.
 *
 * <p>Default off. A player who never opens the ability bar keeps the pre-0.1.4 behaviour exactly.
 */
public class CoopAllyToggleAbility extends BaseToggleAbility {

    /**
     * The ability id, matching the {@code abilities.csv} row. A compile-time constant so the readers
     * in {@code coop.fleet} and {@code CoopAbilityArbiter} inline it and never load this class —
     * which extends an engine class whose static initializer touches {@code Global}.
     */
    public static final String ABILITY_ID = "coop_ally";

    @Override
    protected void activateImpl() {
        // No effect. The toggle's state is read off the fleet by CoopFleetSnapshotFactory.
    }

    @Override
    protected void applyEffect(float amount, float level) {
        // As above: nothing to apply, per frame or otherwise.
    }

    @Override
    protected void deactivateImpl() {
        // As above.
    }

    @Override
    protected void cleanupImpl() {
        // As above.
    }

    @Override
    public boolean hasTooltip() {
        return true;
    }

    @Override
    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        tooltip.addTitle("Fight Alongside");
        tooltip.addPara("While this is on, a battle your co-op partner starts can pull your fleet in"
                + " as an ally. The fleet AI flies your ships; you cannot give them orders or retreat"
                + " them.", 10f);
        tooltip.addPara("Damage and losses are real. A ship destroyed while fighting alongside your"
                + " partner is gone from your fleet, and there is no recovery for it.", 10f);
        tooltip.addPara("Your partner keeps the spoils: salvage, credits, recovered hulls and the"
                + " experience.", 10f);
        tooltip.addPara("Your fleet joins only a fight against a side it is already hostile to."
                + " Your partner attacking a neutral fleet is their fight alone.", 10f);
        tooltip.addPara("While this is off, your fleet is never pulled into your partner's battles.",
                10f);
    }
}
