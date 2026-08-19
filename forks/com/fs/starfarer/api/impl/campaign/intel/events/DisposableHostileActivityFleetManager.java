/*
 * ==========================================================================================
 * COOP FORK - DO NOT EDIT GAMEPLAY LOGIC. Targeted guest-presence fork only.
 * ------------------------------------------------------------------------------------------
 * Starsector version : 0.98a-RC8
 * Original source     : starfarer.api.zip!/com/fs/starfarer/api/impl/campaign/intel/events/DisposableHostileActivityFleetManager.java
 *                       (byte-identical copy of the 0.98a-RC8 source; verified before editing)
 * Compiled into       : mods/coop/jars/coop-forks.jar (prepended to vmparams -classpath
 *                       ahead of starfarer.api.jar so the JVM resolves this copy first).
 *
 * Why fork: this subclass does NOT inherit DisposableFleetManager's system picker - it overrides
 * pickNearestPopulatedSystem() with its own copy of the same player-distance loop (over systems with
 * player colonies instead of over markets). Forking only the base would leave hostile-activity fleets
 * blind to the guest, so the override gets the identical treatment.
 *
 * Both edits are guarded on presence != null; nothing is reordered and no instance field is added
 * (EveryFrameScript, XStream-serialised into saves). With no registered presence every code path
 * below evaluates exactly as vanilla does. The accessor coopPresence() is inherited from the forked
 * PlayerVisibleFleetManager.
 *
 * Edits (all tagged "COOP FORK" inline):
 *   - line ~120 : pickNearestPopulatedSystem()    - candidate distance = min over {player, presence}
 *   - line ~135 : pickNearestPopulatedSystem()    - the "stick with current system" fallback, same min
 *
 * Version drift guard: this fork mirrors 0.98a-RC8 line for line. On first use of the presence term
 * the running game version is checked against CoopPresenceRegistry.PINNED_VERSION; on a mismatch one
 * loud warning is logged and the presence term is disabled for the process, leaving stock behaviour.
 *
 * Sibling presence forks (same mechanism, same guard): RouteManager, PlayerVisibleFleetManager,
 * DisposableFleetManager, SourceBasedFleetManager, DisposableThreatFleetManager.
 * ==========================================================================================
 */
package com.fs.starfarer.api.impl.campaign.intel.events;

import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.DisposableFleetManager;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * @author Alex Mosolov
 *
 * Copyright 2022 Fractal Softworks, LLC
 */
public class DisposableHostileActivityFleetManager extends DisposableFleetManager {

	protected Random random = new Random();
	
	public DisposableHostileActivityFleetManager() {
		//updateSpawnRateMult();
	}
	
	protected Object readResolve() {
		super.readResolve();
		return this;
	}
	
	
	@Override
	protected String getSpawnId() {
		return "hostile_activity";
	}
	
	protected HostileActivityEventIntel getIntel() {
		if (currSpawnLoc == null) return null;
		return HostileActivityEventIntel.get();
	}
	
	@Override
	protected int getDesiredNumFleetsForSpawnLocation() {
		HostileActivityEventIntel intel = getIntel();
		if (intel == null) return 0;
		
		float delay = 30f;
		if (!Global.getSettings().isDevMode()) {
			delay = 90f;
		}
		Long timestamp = intel.getPlayerVisibleTimestamp();
		if (timestamp != null) {
			float daysSince = Global.getSector().getClock().getElapsedDaysSince(timestamp);
			if (daysSince < delay) return 0;
		}
		
		if (currSpawnLoc != null) {
			boolean longEnough = false;
			for (MarketAPI market : Misc.getMarketsInLocation(currSpawnLoc, Factions.PLAYER)) {
				if (market.getDaysInExistence() >= delay) {
					longEnough = true;
				}
			}
			if (!longEnough) {
				return 0;
			}
		}
		
		float mag = intel.getTotalActivityMagnitude(currSpawnLoc);

		// less than half the fleets when market presence is minimal
		float mag2 = intel.getMarketPresenceFactor(currSpawnLoc);
		mag = Misc.interpolate(mag, mag2, 0.6f);
		
		//float mag2 = intel.getTotalActivityMagnitude(true);
		// about half the fleets when effect is fully suppressed
		// keeping the fleets the same size as w/o suppression, though; this is handled
		// in BaseHostileActivityPlugin.getEffectMagnitudeAdjustedBySuppression()
		//mag = Misc.interpolate(mag, mag2, 0.5f);

		if (mag <= 0f) return 0;
		//if (mag > 2f) mag = 2f;
		if (mag > 1f) mag = 1f;
		
		float desiredNumFleets = 1f;
		
		float max = Global.getSettings().getFloat("maxHostileActivityFleetsPerSystem");
		
		float mult = intel.getNumFleetsMultiplier(currSpawnLoc);
		
		desiredNumFleets += (int)Math.round(mag * (max - 1f) * mult);
		
		return (int) Math.round(desiredNumFleets);
	}

	
	protected StarSystemAPI pickCurrentSpawnLocation() {
		return pickNearestPopulatedSystem();
	}
	protected StarSystemAPI pickNearestPopulatedSystem() {
		if (Global.getSector().isInNewGameAdvance()) return null;
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return null;
		// COOP FORK: resolved once per pass; null outside a co-op session, which leaves both distance
		// tests below at their vanilla single-player values.
		SectorEntityToken coopPresence = coopPresence();
		StarSystemAPI nearest = null;

		float minDist = Float.MAX_VALUE;
		
		
//		List<MarketAPI> markets = Misc.getPlayerMarkets(false);
//		List<StarSystemAPI> systems = new ArrayList<StarSystemAPI>();
//		for (MarketAPI market : markets) {
//			StarSystemAPI system = market.getStarSystem();
//			if (system != null && !systems.contains(system)) {
//				systems.add(system);
//			}
//		}
		
//		for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel(HostileActivityIntel.class)) {
//			StarSystemAPI system = ((HostileActivityIntel)intel).getSystem();
		for (StarSystemAPI system : Misc.getSystemsWithPlayerColonies(false)) {
			
			float distToPlayerLY = Misc.getDistanceLY(player.getLocationInHyperspace(), system.getLocation());
			// COOP FORK (edit 1): "distance to the player" becomes "distance to the nearest player",
			// so a colony system the guest is sitting in is eligible to become currSpawnLoc on its own.
			if (coopPresence != null) {
				float coopDistLY = Misc.getDistanceLY(coopPresence.getLocationInHyperspace(), system.getLocation());
				if (coopDistLY < distToPlayerLY) distToPlayerLY = coopDistLY;
			}
			if (distToPlayerLY > MAX_RANGE_FROM_PLAYER_LY) continue;
			
			if (distToPlayerLY < minDist) {
				//if (system.getStar() != null && system.getStar().getSpec().isPulsar()) continue;
				nearest = system;
				minDist = distToPlayerLY;
			}
		}
		
		// stick with current system longer unless something else is closer
		if (nearest == null && currSpawnLoc != null) {
			float distToPlayerLY = Misc.getDistanceLY(player.getLocationInHyperspace(), currSpawnLoc.getLocation());
			// COOP FORK (edit 2): same min-over-players, so the system the guest is in keeps its
			// spawn slot while the guest is still near it.
			if (coopPresence != null) {
				float coopDistLY = Misc.getDistanceLY(coopPresence.getLocationInHyperspace(), currSpawnLoc.getLocation());
				if (coopDistLY < distToPlayerLY) distToPlayerLY = coopDistLY;
			}
			if (distToPlayerLY <= DESPAWN_RANGE_LY) {
				nearest = currSpawnLoc;
			}
		}
		
		return nearest;
	}
	
	protected CampaignFleetAPI spawnFleetImpl() {
		StarSystemAPI system = currSpawnLoc;
		if (system == null) return null;
		
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return null;
		
		HostileActivityEventIntel intel = getIntel();
		if (intel == null) return null;
		
		String idKey = "$dhafm_ID";
		
		WeightedRandomPicker<HostileActivityFactor> picker = new WeightedRandomPicker<HostileActivityFactor>(random);
		
		for (EventFactor factor : intel.getFactors()) {
			if (!(factor instanceof HostileActivityFactor)) {
				continue;
			}
			//if (factor instanceof LuddicPathHostileActivityFactor) continue;
			
			HostileActivityFactor curr = (HostileActivityFactor) factor;
			int count = 0;
			for (ManagedFleetData data : active) {
				if (data.fleet != null &&
						curr.getId().equals(data.fleet.getMemoryWithoutUpdate().getString(idKey))) {
					count++;
				}
			}
			if (count < curr.getMaxNumFleets(currSpawnLoc)) {
				picker.add(curr, curr.getSpawnFrequency(currSpawnLoc));
			}
		}
		
		HostileActivityFactor pick = picker.pick();
		if (pick == null) return null;
		
		
		CampaignFleetAPI fleet = pick.createFleet(currSpawnLoc, random);
		if (fleet == null || fleet.isEmpty()) return null;
		
		fleet.getMemoryWithoutUpdate().set(idKey, pick.getId());
		
		setLocationAndOrders(fleet, pick.getSpawnInHyperProbability(currSpawnLoc), pick.getStayInHyperProbability(currSpawnLoc));
		
		return fleet;
	}
	
}








