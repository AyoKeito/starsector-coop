package com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.FleetAIFlags;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RemnantSeededFleetManager;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageSpecialInteraction.SalvageSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageSpecialInteraction.SalvageSpecialPlugin;
import com.fs.starfarer.api.util.Misc;

public class TransmitterTrapSpecial extends BaseSalvageSpecial {

	
	public static class TransmitterTrapSpecialData implements SalvageSpecialData {
		public float prob = 0.5f;
		
		public String fleetId;
		
		public String nearbyFleetFaction = null;
		public Boolean useClosestFleetInRange = null;
		public Boolean useAllFleetsInRange = null;
		
		public FleetParamsV3 params;
		
		public float minRange = 2500;
		public float maxRange = 5000;
		
		public TransmitterTrapSpecialData() {
		}
		
		public TransmitterTrapSpecialData(FleetParamsV3 params) {
			this.params = params;
		}

		public TransmitterTrapSpecialData(float prob, FleetParamsV3 params) {
			this.prob = prob;
			this.params = params;
		}

		public SalvageSpecialPlugin createSpecialPlugin() {
			return new TransmitterTrapSpecial();
		}
	}
	
	private TransmitterTrapSpecialData data;
	
	public TransmitterTrapSpecial() {
	}
	

	@Override
	public void init(InteractionDialogAPI dialog, Object specialData) {
		super.init(dialog, specialData);
		
		data = (TransmitterTrapSpecialData) specialData;
		
		initEntityLocation();
	}

	private void initEntityLocation() {
		
		if (random.nextFloat() > data.prob) {
			
			if (random.nextFloat() > 0.5f) {
				addText("Your salvage crews discover a transmitter set to send a signal when " +
						"tripped by an alarm system, but it doesn't appear to be functional. " +
						"Closer examination indicates it was probably set many cycles ago.");
			} else {
				addText("Your salvage crews discover a transmitter set to send a signal when " +
						"tripped by an alarm system. The alarm went off as intended, but the transmitter " +
						"was fried by a power surge before it could do its job.");
			}
			
			setDone(true);
			setEndWithContinue(true);
			setShowAgain(false);
			return;
		}

		if (entity instanceof PlanetAPI) {
			addText("As your salvage crews begin their work, a transmitter hidden somewhere planetside " +
					"sends out an encrypted, broadwave signal. Whatever destination it's meant for, " +
					"it has to be nearby.");	
		} else {
			addText("As your salvage crews begin their work, a transmitter inside the $shortName " +
					"sends out an encrypted, broadwave signal. Whatever destination it's meant for, " +
					"it has to be nearby.");
		}
		
		transmitterActivated();
		
		setDone(true);
		setEndWithContinue(true);
		setShowAgain(false);
	}
	
	
	public void transmitterActivated() {
		// COOP: the host owns fleets created or redirected by a managed wreck's alarm.
		if (coop.ambient.CoopWreckBridge.isGuestReplica(entity)) {
			entity.getMemoryWithoutUpdate().set("$coopWreckTrapActivated", true);
			return;
		}
		coopActivate(data, entity, random, Global.getSector().getPlayerFleet());
	}

	/** Applies a guest's completed native alarm once, without opening a host dialog. */
	public static void coopActivateForGuest(SectorEntityToken wreck, CampaignFleetAPI guest) {
		if (coop.ambient.CoopWreckBridge.isGuestMode()) throw new IllegalStateException("Host trap authority required");
		if (wreck == null || guest == null || wreck.getContainingLocation() != guest.getContainingLocation()) {
			throw new IllegalStateException("Guest trap target is unavailable");
		}
		Object payload = wreck.getMemoryWithoutUpdate().get(MemFlags.SALVAGE_SPECIAL_DATA);
		if (!(payload instanceof TransmitterTrapSpecialData trap)) {
			throw new IllegalStateException("Host wreck has no transmitter trap");
		}
		java.util.Random nativeRandom = Misc.getRandom(Misc.getSalvageSeed(wreck), 50);
		nativeRandom.nextFloat(); // initEntityLocation consumed the native probability roll on guest.
		coopActivate(trap, wreck, nativeRandom, guest);
	}

	private static void coopActivate(TransmitterTrapSpecialData data, SectorEntityToken entity,
			java.util.Random random, CampaignFleetAPI target) {
		if (data == null) return;
		if (entity == null) return;
		
		if (data.fleetId != null) {
			SectorEntityToken found = Global.getSector().getEntityById(data.fleetId);
			if (found instanceof CampaignFleetAPI) {
				CampaignFleetAPI fleet = (CampaignFleetAPI) found;
				FleetMemberAPI flagship = fleet.getFlagship();
				boolean makeAggressive = false;
				if (flagship != null) {
					makeAggressive = Misc.isAutomated(flagship.getVariant());
				}
				coopMakeFleetInterceptPlayer(fleet, makeAggressive, true, true, 30f, target);
			}
			return;
		}
		
		if (data.useAllFleetsInRange != null && data.useAllFleetsInRange) {
			boolean foundSomeFleets = false;
			for (CampaignFleetAPI fleet : entity.getContainingLocation().getFleets()) {
				if (data.nearbyFleetFaction != null && 
						!data.nearbyFleetFaction.equals(fleet.getFaction().getId())) {
					continue;
				}
				
				if (fleet.isStationMode()) continue;
				
				if (fleet.getMemoryWithoutUpdate().is(MemFlags.MEMORY_KEY_TRADE_FLEET, true)) continue;
				
				float dist = Misc.getDistance(fleet.getLocation(), entity.getLocation());
				if (dist < data.maxRange) {
					FleetMemberAPI flagship = fleet.getFlagship();
					boolean makeAggressive = false;
					if (flagship != null) {
						makeAggressive = Misc.isAutomated(flagship.getVariant());
					}
					coopMakeFleetInterceptPlayer(fleet, makeAggressive, true, true, 30f, target);
					foundSomeFleets = true;
				}
			}
			if (foundSomeFleets) return;
		}
		
		if (data.useClosestFleetInRange != null && data.useClosestFleetInRange) {
			CampaignFleetAPI closest = null;
			float minDist = Float.MAX_VALUE;
			for (CampaignFleetAPI fleet : entity.getContainingLocation().getFleets()) {
				if (data.nearbyFleetFaction != null && 
						!data.nearbyFleetFaction.equals(fleet.getFaction().getId())) {
					continue;
				}
				
				if (fleet.isStationMode()) continue;
				
				if (fleet.getMemoryWithoutUpdate().is(MemFlags.MEMORY_KEY_TRADE_FLEET, true)) continue;
				
				float dist = Misc.getDistance(fleet.getLocation(), entity.getLocation());
				if (dist < data.maxRange && dist < minDist) {
					closest = fleet;
					minDist = dist;
				}
			}
			if (closest != null) {
				FleetMemberAPI flagship = closest.getFlagship();
				boolean makeAggressive = false;
				if (flagship != null) {
					makeAggressive = Misc.isAutomated(flagship.getVariant());
				}
				coopMakeFleetInterceptPlayer(closest, makeAggressive, true, true, 30f, target);
				return;
			}
		}
		
		if (data.params != null) {
			CampaignFleetAPI fleet = FleetFactoryV3.createFleet(data.params);
			if (fleet == null || fleet.isEmpty()) return;
			
			if (Factions.REMNANTS.equals(fleet.getFaction().getId())) {
				RemnantSeededFleetManager.initRemnantFleetProperties(null, fleet, false);
			} else {
				fleet.setTransponderOn(false);
				fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PIRATE, true);
				Misc.makeNoRepImpact(fleet, "tTrap");
			}
			
			float range = data.minRange + random.nextFloat() * (data.maxRange - data.minRange);
			Vector2f loc = Misc.getPointAtRadius(entity.getLocation(), range);
			
			entity.getContainingLocation().addEntity(fleet);
			fleet.setLocation(loc.x, loc.y);
			
			FleetMemberAPI flagship = fleet.getFlagship();
			boolean makeAggressive = false;
			if (flagship != null) {
				makeAggressive = Misc.isAutomated(flagship.getVariant());
			}
			coopMakeFleetInterceptPlayer(fleet, makeAggressive, true, true, 30f, target);
			
			
//			SectorEntityToken despawnLoc = entity.getContainingLocation().createToken(20000, 0);
//			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, despawnLoc, 10000f);
			Misc.giveStandardReturnToSourceAssignments(fleet, false);
			return;
		}
	}
	
	
	
	
	public static void makeFleetInterceptPlayer(CampaignFleetAPI fleet, boolean makeAggressive, boolean makeLowRepImpact, float interceptDays) {
		makeFleetInterceptPlayer(fleet, makeAggressive, makeLowRepImpact, true, interceptDays);
	}
	public static void makeFleetInterceptPlayer(CampaignFleetAPI fleet, boolean makeAggressive, boolean makeLowRepImpact, boolean makeHostile, float interceptDays) {
		coopMakeFleetInterceptPlayer(fleet, makeAggressive, makeLowRepImpact, makeHostile,
				interceptDays, Global.getSector().getPlayerFleet());
	}

	private static void coopMakeFleetInterceptPlayer(CampaignFleetAPI fleet, boolean makeAggressive,
			boolean makeLowRepImpact, boolean makeHostile, float interceptDays, CampaignFleetAPI playerFleet) {
		
		if (fleet.getAI() == null) {
			fleet.setAI(Global.getFactory().createFleetAI(fleet));
			fleet.setLocation(fleet.getLocation().x, fleet.getLocation().y);
		}
		
		if (makeAggressive) {
			float expire = fleet.getMemoryWithoutUpdate().getExpire(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true, Math.max(expire, interceptDays));
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY, true, Math.max(expire, interceptDays));
		}
		
		if (makeHostile) {
			fleet.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_ALLOW_DISENGAGE);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true, interceptDays);
		}
		fleet.getMemoryWithoutUpdate().set(FleetAIFlags.PLACE_TO_LOOK_FOR_TARGET, new Vector2f(playerFleet.getLocation()), interceptDays);

		if (makeLowRepImpact) {
			Misc.makeLowRepImpact(fleet, "ttSpecial");
		}
		
		if (fleet.getAI() instanceof ModularFleetAIAPI) {
			((ModularFleetAIAPI)fleet.getAI()).getTacticalModule().setTarget(playerFleet);
		}
		
		fleet.addAssignmentAtStart(FleetAssignment.INTERCEPT, playerFleet, interceptDays, null);
	}
	
	
	@Override
	public void optionSelected(String optionText, Object optionData) {
		super.optionSelected(optionText, optionData);
	}

	
	public static void main(String[] args) {
		Boolean b = null;//new Boolean(true);
		System.out.println(b == true);
	}
}






