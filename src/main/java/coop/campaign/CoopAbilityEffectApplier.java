package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.abilities.InterdictionPulseAbility;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.util.Misc;
import coop.fleet.CoopGuestMirrorHandle;
import coop.util.CoopLog;

import java.util.List;

/**
 * Host-side execution of a guest's world-affecting ability (Phase 12c task A1).
 *
 * <p>Before this class the host's {@code ABILITY_ACTIVATE} handler only logged, so a guest's
 * interdiction pulse or distress call had no effect on the authoritative world at all. The fix is to
 * run the <em>vanilla</em> ability plugin on the guest's mirror fleet rather than re-implement its
 * effect: the mirror is a real {@code CampaignFleetAPI} sitting in the host's world at the guest's
 * position, and every ability is an entity script with no functional player gate.
 *
 * <p><b>Why this cannot echo.</b> {@code BaseAbilityPlugin.activate()} only consults
 * {@code isPlayerFleet()} to decide whether to fire {@code reportPlayerActivatedAbility} — the mirror
 * is not the player fleet, so the notification never fires and
 * {@link CoopCampaignReplicator#onPlayerActivatedAbility} can never re-capture what we just applied.
 * No replay guard is needed here.
 *
 * <p><b>Why abilities are added lazily.</b> The mirror is built by {@code CampaignEngine
 * .createEmptyFleet}, which adds no abilities at all. Adding the full set at mirror-creation time
 * would serialize an ability plugin (and its per-plugin state) into every host save for abilities the
 * guest may never use; {@code BaseCampaignEntity.addAbility} is idempotent, so adding on first use is
 * both cheaper and equivalent.
 *
 * <p><b>Why the mirror's protective flags do not get in the way.</b> The interdiction victim loop
 * walks {@code fleet.getContainingLocation().getFleets()} directly — it never asks the AI or the
 * engagement flags for permission, so {@code FLEET_IGNORES_OTHER_FLEETS}/{@code setNoEngaging}/
 * {@code setAIMode} neither help nor hinder it.
 */
public final class CoopAbilityEffectApplier {

    /** What the host decided to do with an incoming {@code ABILITY_ACTIVATE}. */
    public enum Decision {
        /** Run the vanilla plugin on the guest's mirror fleet. */
        ACTIVATE_ON_MIRROR,
        /** No mirror in the world (guest not spawned yet, mid-teardown): nothing to activate on. */
        SKIP_NO_MIRROR,
        /**
         * The ability's only world effect is already replicated by a dedicated sync, so activating
         * here would create a second source of truth. {@code sensor_burst} is the one case: its
         * effect is a {@code detectedRangeMod} spike, and Phase 14b's {@link coop.fleet.CoopSensorSync}
         * pins the mirror's whole {@code detectedRangeMod} total per frame from the guest's real
         * fleet — the pin would simply cancel a locally-activated burst on the next frame. A
         * backstop since Phase 12c A2 moved the id into {@link CoopAbilityArbiter#LOCAL_ABILITIES},
         * so a current guest never sends it.
         */
        SKIP_ALREADY_REPLICATED,
        /**
         * A world-affecting ability with no host-side implementation yet. Today that is
         * {@code remote_survey} (surveys a planet the guest is near: needs the survey-level write to
         * ride a WORLD_DELTA, not a mirror activation) and {@code generate_slipsurge} (Phase 26 owns
         * slipstream replication), plus any modded id the arbiter defaulted to world-affecting.
         */
        SKIP_UNHANDLED
    }

    /** Outcome of the activation attempt itself, once {@link Decision#ACTIVATE_ON_MIRROR} is taken. */
    public enum ActivationResult {
        ACTIVATED,
        /** {@code addAbility} did not produce a plugin — unknown/absent ability spec. */
        NO_PLUGIN,
        /** On cooldown, already in progress, or otherwise refused by the plugin. */
        NOT_USABLE
    }

    private CoopAbilityEffectApplier() {
    }

    /**
     * Pure classification of an incoming ability id. Separated from the engine work so the routing
     * is unit-testable without standing up a fleet.
     */
    public static Decision decide(String abilityId, boolean mirrorPresent) {
        String id = abilityId == null ? "" : abilityId.trim();
        if (Abilities.SENSOR_BURST.equals(id)) {
            // Checked before the mirror test: this is a no-op regardless of whether a mirror exists.
            return Decision.SKIP_ALREADY_REPLICATED;
        }
        if (!Abilities.INTERDICTION_PULSE.equals(id) && !Abilities.DISTRESS_CALL.equals(id)) {
            return Decision.SKIP_UNHANDLED;
        }
        return mirrorPresent ? Decision.ACTIVATE_ON_MIRROR : Decision.SKIP_NO_MIRROR;
    }

    /**
     * Host entry point: apply a guest's world-affecting ability to the authoritative world.
     *
     * @return the decision taken, for logging and tests
     */
    public static Decision apply(String abilityId, String playerId) {
        CampaignFleetAPI mirror = null;
        try {
            mirror = CoopGuestMirrorHandle.current();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopAbilityEffectApplier.class, "Failed to resolve the guest mirror fleet", ex);
        }
        Decision decision = decide(abilityId, mirror != null);
        switch (decision) {
            case SKIP_NO_MIRROR -> CoopLog.warn(CoopAbilityEffectApplier.class,
                    "ABILITY_ACTIVATE " + abilityId + " from " + playerId
                            + " dropped: no guest mirror fleet in the world");
            case SKIP_ALREADY_REPLICATED -> CoopLog.info(CoopAbilityEffectApplier.class,
                    "ABILITY_ACTIVATE " + abilityId + " from " + playerId
                            + " needs no host action (sensor sync already pins the mirror's profile)");
            case SKIP_UNHANDLED -> CoopLog.warn(CoopAbilityEffectApplier.class,
                    "ABILITY_ACTIVATE " + abilityId + " from " + playerId
                            + " has no host-side effect wired; ignoring");
            case ACTIVATE_ON_MIRROR -> activateOnMirror(mirror, abilityId, playerId);
        }
        return decision;
    }

    private static void activateOnMirror(CampaignFleetAPI mirror, String abilityId, String playerId) {
        ActivationResult result;
        try {
            result = activate(mirror, abilityId);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopAbilityEffectApplier.class,
                    "Failed to activate " + abilityId + " on the guest mirror", ex);
            return;
        }
        CoopLog.info(CoopAbilityEffectApplier.class, "Coop ability " + abilityId + " from " + playerId
                + " on the guest mirror: " + result);
        if (result == ActivationResult.ACTIVATED && Abilities.INTERDICTION_PULSE.equals(abilityId)) {
            applyInterdictionRepHit(mirror);
        }
    }

    private static ActivationResult activate(CampaignFleetAPI mirror, String abilityId) {
        if (!mirror.hasAbility(abilityId)) {
            mirror.addAbility(abilityId);
        }
        AbilityPlugin plugin = mirror.getAbility(abilityId);
        if (plugin == null) {
            return ActivationResult.NO_PLUGIN;
        }
        if (!plugin.isUsable()) {
            return ActivationResult.NOT_USABLE;
        }
        plugin.activate();
        return ActivationResult.ACTIVATED;
    }

    // ---- Interdiction reputation hit ----------------------------------------------------------

    /**
     * Replicates the standing hit vanilla's interdiction pulse takes for the guest.
     *
     * <p>{@code InterdictionPulseAbility.applyEffect} (api_src lines 293-297) gates its
     * {@code adjustPlayerReputation(RepActions.INTERDICTED, ...)} on {@code fleet.isPlayerFleet()},
     * which the mirror is not — so the mirror's pulse interdicts its victims but silently costs the
     * guest nothing. We re-apply the hit here against the host's sector, which is the authoritative
     * owner of player-faction standings; the existing {@code onPlayerReputationChange} capture turns
     * it into a {@code REP_DELTA} for the guest with no new channel.
     *
     * <p><b>Deviation: timing.</b> Vanilla applies the hit inside {@code applyEffect} at pulse time —
     * after the charge-up, against the fleets in range <em>then</em>. Hooking that moment would mean
     * forking {@code InterdictionPulseAbility}, so this runs at activation time instead, over the
     * fleets in range at activation. A fleet that flees the radius during the charge-up (or enters it)
     * therefore counts differently for standing than it does for the interdict itself. Same victim
     * conditions otherwise.
     *
     * <p><b>Deviation: visibility.</b> Vanilla's "Interdict avoided!" skip reads
     * {@code getVisibilityLevelToPlayerFleet()}, which on the host means visibility to the
     * <em>host's</em> fleet, not the guest's. Kept as-is: it only decides whether a
     * zero-second (fully avoided) interdict still costs standing, and there is no per-guest
     * visibility surface to substitute.
     */
    private static void applyInterdictionRepHit(CampaignFleetAPI mirror) {
        try {
            SectorAPI sector = Global.getSector();
            LocationAPI location = mirror.getContainingLocation();
            if (sector == null || location == null) {
                return;
            }
            FactionAPI mirrorFaction = mirror.getFaction();
            List<CampaignFleetAPI> fleets = location.getFleets();
            if (fleets == null) {
                return;
            }
            float range = InterdictionPulseAbility.getRange(mirror);
            int hits = 0;
            for (int i = 0; i < fleets.size(); i++) {
                CampaignFleetAPI other = fleets.get(i);
                if (other == null || other == mirror || other.getFaction() == null) {
                    continue;
                }
                boolean sameFaction = mirrorFaction != null
                        && mirrorFaction.getId() != null
                        && mirrorFaction.getId().equals(other.getFaction().getId());
                float distance = Misc.getDistance(mirror.getLocation(), other.getLocation());
                if (!shouldTakeInterdictionRepHit(sameFaction, other.isInHyperspaceTransition(),
                        distance <= range, isVisibleToPlayerFleet(other),
                        InterdictionPulseAbility.getInterdictSeconds(mirror, other),
                        other.knowsWhoPlayerIs())) {
                    continue;
                }
                sector.adjustPlayerReputation(
                        new RepActionEnvelope(RepActions.INTERDICTED, null, null, false),
                        other.getFaction().getId());
                hits++;
            }
            if (hits > 0) {
                CoopLog.info(CoopAbilityEffectApplier.class,
                        "Coop interdiction rep hit applied for the guest against " + hits + " fleet(s)");
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopAbilityEffectApplier.class,
                    "Failed to apply the guest's interdiction reputation hit", ex);
        }
    }

    /**
     * The victim conditions of vanilla's interdiction rep hit, collapsed into one predicate.
     *
     * <p>Vanilla's loop: skip the pulsing fleet itself, skip same-faction fleets, skip fleets in
     * hyperspace transition, skip fleets outside the pulse radius, then {@code continue} on a victim
     * that both sees the pulse and shrugs it off ({@code interdictSeconds <= 0}), and finally require
     * {@code other.knowsWhoPlayerIs()}. The same-faction test appears twice in vanilla (once as an
     * early {@code continue}, once in the rep gate itself) and is collapsed here.
     */
    static boolean shouldTakeInterdictionRepHit(boolean sameFaction, boolean inHyperspaceTransition,
                                                boolean inRange, boolean visibleToPlayerFleet,
                                                float interdictSeconds, boolean knowsWhoPlayerIs) {
        if (sameFaction || inHyperspaceTransition || !inRange) {
            return false;
        }
        if (visibleToPlayerFleet && interdictSeconds <= 0f) {
            return false; // "Interdict avoided!" — vanilla skips the victim entirely.
        }
        return knowsWhoPlayerIs;
    }

    /**
     * Vanilla's third case is {@code SENSOR_CONTACT && fleet.isPlayerFleet()} — true for a player
     * pulse, and the guest's pulse is one in every sense that matters here, so it is kept unqualified.
     */
    private static boolean isVisibleToPlayerFleet(CampaignFleetAPI fleet) {
        VisibilityLevel vis = fleet.getVisibilityLevelToPlayerFleet();
        return vis == VisibilityLevel.COMPOSITION_AND_FACTION_DETAILS
                || vis == VisibilityLevel.COMPOSITION_DETAILS
                || vis == VisibilityLevel.SENSOR_CONTACT;
    }
}
