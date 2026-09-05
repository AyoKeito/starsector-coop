/*
 * ==========================================================================================
 * COOP FORK - DO NOT EDIT GAMEPLAY LOGIC. Targeted guest-presence fork only.
 * ------------------------------------------------------------------------------------------
 * Starsector version : 0.98a-RC8
 * Original source     : starfarer.api.zip!/com/fs/starfarer/api/impl/campaign/fleets/DisposableFleetManager.java
 *                       (byte-identical copy of the 0.98a-RC8 source; verified before editing)
 * Compiled into       : mods/coop/jars/coop-forks.jar (prepended to vmparams -classpath
 *                       ahead of starfarer.api.jar so the JVM resolves this copy first).
 *
 * Why fork: this is the ambient "stuff happens around the player" spawner - pirate hunters, Pather
 * cells, hostile-activity and Threat fleets all derive from it. It picks ONE star system per pass
 * (currSpawnLoc), the nearest populated system within MAX_RANGE_FROM_PLAYER_LY of
 * Global.getSector().getPlayerFleet(), and spawns into it. On the host the co-op guest is a mirror
 * fleet, never the player fleet, so a system only the guest is standing in is never picked and stays
 * ambient-empty until the host flies within 1.6 LY. Observed in-game 2026-08-19: no pirate hunters
 * around a guest-only Askonia until the host closed to ~1.6 LY.
 *
 * The fork adds the co-op guest's presence entity - published by coop.presence.CoopPresenceRegistry -
 * as a second candidate in the two distance tests that choose currSpawnLoc: the system is picked by
 * whichever player is nearer. Every edit is guarded on presence != null; nothing is reordered and no
 * instance field is added (this is an EveryFrameScript XStream-serialised into saves, so a new field
 * would change the save shape). With no registered presence every code path below evaluates exactly
 * as vanilla does.
 *
 * Spawn geometry: the placement inside currSpawnLoc is NOT player-independent, so it does need one
 * edit. setLocationAndOrders puts the fleet into currSpawnLoc (or into hyperspace, flying in under
 * DisposableAggroAssignmentAI), which is system-relative and does follow the presence-aware system
 * choice. The placement inside that system does not: DisposableAggroAssignmentAI branches on
 * `fleet.getContainingLocation() == Global.getSector().getCurrentLocation()` - the HOST's location,
 * always - and only the host-present branch routes through Misc.pickLocationNotNearPlayer. In a
 * guest-only system the other branch runs and drops the fleet at the guarded entity's radius + 100
 * units with no distance check against anyone, so a pirate hunter or Pather cell would pop in on top
 * of a guest parked at that planet or jump point. Vanilla never does that to the host, because that
 * branch only runs when nobody is there to see it.
 * The fix (edit 3) is a post-placement nudge rather than a rewrite of the vanilla branch: the AI's
 * constructor sets the fleet's position synchronously, so setLocationAndOrders reads that position
 * back afterwards and, only when the presence entity is in the same system and the fleet came out
 * inside vanilla's own maxSensorRange + 500 of it, moves the fleet to the distance vanilla itself
 * would have used (that minimum plus pickLocationNotNearPlayer's 2000-unit margin - see
 * coop.presence.CoopSpawnSpacing). Which branch the AI took, which entity it guarded and the
 * fly-in-from-hyperspace path are all untouched, and the moved point is only accepted when it is
 * clear of the host too, so the nudge can never hand the host the pop-in it removes for the guest.
 *
 * Known limitation (documented, not a bug): currSpawnLoc is a single field, and the no-new-fields
 * rule forbids a second one. With both players parked in different populated systems only one of the
 * two gets ambient spawns - whichever system wins the nearest-to-either test. It is stable rather
 * than flip-flopping (both distances are ~0 and market iteration order is fixed), and it is strictly
 * better than vanilla's "only the host ever counts".
 *
 * Edits (all tagged "COOP FORK" inline):
 *   - line ~250 : pickNearestPopulatedSystem()    - candidate distance = min over {player, presence}
 *   - line ~275 : pickNearestPopulatedSystem()    - the "stick with current system" fallback, same min
 *   - line ~380 : setLocationAndOrders()          - post-placement nudge off the presence entity
 *
 * Version drift guard: this fork mirrors 0.98a-RC8 line for line. On first use of the presence term
 * the running game version is checked against CoopPresenceRegistry.PINNED_VERSION; on a mismatch one
 * loud warning is logged and the presence term is disabled for the process, leaving stock behaviour.
 *
 * Sibling presence forks (same mechanism, same guard): RouteManager, PlayerVisibleFleetManager,
 * SourceBasedFleetManager, DisposableHostileActivityFleetManager, DisposableThreatFleetManager.
 * ==========================================================================================
 */
package com.fs.starfarer.api.impl.campaign.fleets;

import java.util.LinkedHashMap;

// COOP FORK: added for edit 3's position arithmetic; org.lwjgl is already on the game's classpath.
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI.EncounterOption;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.tutorial.TutorialMissionIntel;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.TimeoutTracker;

/**
 * Picks a star system near the player meeting certain criteria and spawns certain types of fleets there,
 * but outside the player's vision.
 * 
 * Despawns them as soon as possible when a different star system is picked.
 *
 * Copyright 2018 Fractal Softworks, LLC
 */
public abstract class DisposableFleetManager extends PlayerVisibleFleetManager {

	public static boolean DEBUG = false;
	
	public static final String KEY_SYSTEM = "$core_disposableFleetSpawnSystem";
	public static final String KEY_SPAWN_FP = "$core_disposableFleetSpawnFP";
	//public static final float MAX_RANGE_FROM_PLAYER_LY = 3f;
	public static final float MAX_RANGE_FROM_PLAYER_LY = RouteManager.SPAWN_DIST_LY;
	public static final float DESPAWN_RANGE_LY = MAX_RANGE_FROM_PLAYER_LY + 1.4f;
	
	protected IntervalUtil tracker2 = new IntervalUtil(0.75f, 1.25f);;
	protected LinkedHashMap<String, TimeoutTracker<Boolean>> recentSpawns = new LinkedHashMap<String, TimeoutTracker<Boolean>>();
	
	protected Object readResolve() {
		super.readResolve();
		return this;
	}
	
	protected float getExpireDaysPerFleet() {
		return 30f;
	}
	
	protected String getSpawnKey(StarSystemAPI system) {
		String sysId = system.getOptionalUniqueId();
		if (sysId == null) sysId = system.getName();
		return "$core_recentSpawn_" + getSpawnId() + "_" + sysId;
	}
	
	protected void addRecentSpawn(StarSystemAPI system) {
		String key = getSpawnKey(system);
		float e = Global.getSector().getMemoryWithoutUpdate().getExpire(key);
		if (e < 0) e = 0;
		e += getExpireDaysPerFleet();
		Global.getSector().getMemoryWithoutUpdate().set(key, true);
		Global.getSector().getMemoryWithoutUpdate().expire(key, e);
	}
	
	protected float getRecentSpawnsForSystem(StarSystemAPI system) {
		if (system == null) return 0f;
		String key = getSpawnKey(system);
		float e = Global.getSector().getMemoryWithoutUpdate().getExpire(key);
		if (e < 0) e = 0;
		return e / getExpireDaysPerFleet();
	}
	
	@Override
	protected int getMaxFleets() {
		return 100; // limiting is based on spawnRateMult instead
	}

	@Override
	protected boolean isOkToDespawnAssumingNotPlayerVisible(CampaignFleetAPI fleet) {
		if (currSpawnLoc == null) return true;
		String system = fleet.getMemoryWithoutUpdate().getString(KEY_SYSTEM);
		float spawnFP = fleet.getMemoryWithoutUpdate().getFloat(KEY_SPAWN_FP);
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		float playerFP = player.getFleetPoints();
		
		if (system == null || !system.equals(currSpawnLoc.getName())) return true;
		
		if (spawnFP >= fleet.getFleetPoints() * 2f) {
			if (fleet.getAI() instanceof CampaignFleetAIAPI) {
				CampaignFleetAIAPI ai = (CampaignFleetAIAPI) fleet.getAI();
				EncounterOption option = ai.pickEncounterOption(null, player, true);
				if (option == EncounterOption.DISENGAGE) return true;
			} else {
				return fleet.getFleetPoints() <= playerFP * 0.5f;
			}
		}
		
		return false;
	}

	@Override
	public float getSpawnRateMult() {
		return spawnRateMult;
	}

	protected float spawnRateMult = 1f;
	protected StarSystemAPI currSpawnLoc = null;
	
	protected void currSpawnLocChanged() {
		
	}
	
	@Override
	public void advance(float amount) {
		if (TutorialMissionIntel.isTutorialInProgress()) {
			return;
		}
		
		super.advance(amount);
		
		
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return;
		
		float days = Global.getSector().getClock().convertToDays(amount);
		if (DEBUG) {
			days *= 100f;
		}
		
		tracker2.advance(days);
		if (tracker2.intervalElapsed()) {
			StarSystemAPI closest = pickCurrentSpawnLocation();
			if (closest != currSpawnLoc) {
				currSpawnLoc = closest;
				currSpawnLocChanged();
			}
			
			if (withReturnToSourceAssignments()) {
				//List<ManagedFleetData> remove = new ArrayList<ManagedFleetData>();
				for (ManagedFleetData data : active) {
					if (Misc.isFleetReturningToDespawn(data.fleet)) continue;
					// if it's player-visible/in the currently active location,
					// make it return to source when it's been beat up enough
					// to be worth despawning
					//if (isOkToDespawnAssumingNotPlayerVisible(data.fleet)) {
					
					float fp = data.fleet.getFleetPoints();
					float spawnFP = data.fleet.getMemoryWithoutUpdate().getFloat(KEY_SPAWN_FP);
					if (fp < spawnFP * 0.33f) {
						Misc.giveStandardReturnToSourceAssignments(data.fleet);
						//remove.add(data);
					}
				}
			}
			
			//active.removeAll(remove);
			
			updateSpawnRateMult();
		}
	}
	
	protected boolean withReturnToSourceAssignments() {
		return true;
	}
	
	public StarSystemAPI getCurrSpawnLoc() {
		return currSpawnLoc;
	}

	protected void updateSpawnRateMult() {
		if (currSpawnLoc == null) {
			if (DEBUG) {
				System.out.println("No target system, spawnRateMult is 1");
			}
			spawnRateMult = 1f;
			return;
		}
		
		float desiredNumFleets = getDesiredNumFleetsForSpawnLocation();
		float recentSpawns = getRecentSpawnsForSystem(currSpawnLoc);
		if (active != null) {
			float activeInSystem = 0f;
			for (ManagedFleetData data : active) {
				if (data.spawnedFor == currSpawnLoc || data.fleet.getContainingLocation() == currSpawnLoc) {
					activeInSystem++;
				}
			}
			recentSpawns = Math.max(recentSpawns, activeInSystem);
		}
		
		spawnRateMult = (float) Math.pow(Math.max(0, (desiredNumFleets - recentSpawns) * 1f), 4f);
		if (spawnRateMult < 0) spawnRateMult = 0;
		
		//if (DEBUG || this instanceof DisposableHostileActivityFleetManager) {
		if (DEBUG) {
			System.out.println(String.format("ID: %s, system: %s, recent: %s, desired: %s, spawnRateMult: %s",
					getSpawnId(),
					currSpawnLoc.getName(),
					"" + recentSpawns,
					"" + desiredNumFleets,
					"" + spawnRateMult));
		}
	}

	protected abstract int getDesiredNumFleetsForSpawnLocation();
	
	protected abstract CampaignFleetAPI spawnFleetImpl();
	protected abstract String getSpawnId();
	
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
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden()) continue;
			if (market.getStarSystem() != null && market.getStarSystem().hasTag(Tags.SYSTEM_ABYSSAL)) continue;
			
			if (market.isPlayerOwned() && market.getSize() <= 3) continue;
			if (!market.hasSpaceport()) continue;
			
			float distToPlayerLY = Misc.getDistanceLY(player.getLocationInHyperspace(), market.getLocationInHyperspace());
			// COOP FORK (edit 1): "distance to the player" becomes "distance to the nearest player",
			// so a system the guest is sitting in is eligible to become currSpawnLoc on its own.
			if (coopPresence != null) {
				float coopDistLY = Misc.getDistanceLY(coopPresence.getLocationInHyperspace(), market.getLocationInHyperspace());
				if (coopDistLY < distToPlayerLY) distToPlayerLY = coopDistLY;
			}

			if (distToPlayerLY > MAX_RANGE_FROM_PLAYER_LY) continue;
			
			if (distToPlayerLY < minDist && market.getStarSystem() != null) {
				if (market.getStarSystem().getStar() != null) {
					if (market.getStarSystem().getStar().getSpec().isPulsar()) continue;
				}
				
				nearest = market.getStarSystem();
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
	
	public CampaignFleetAPI spawnFleet() {
		if (currSpawnLoc == null) return null;
		
		// otherwise, possible for jump-point dialog to say there's nothing on other side
		// but there will be by the time the player comes out
		if (Global.getSector().getPlayerFleet() != null && Global.getSector().getPlayerFleet().isInHyperspaceTransition()) {
			return null;
		}
		
		CampaignFleetAPI fleet = spawnFleetImpl();
		if (fleet != null) {
			fleet.getMemoryWithoutUpdate().set(KEY_SYSTEM, currSpawnLoc.getName());
			fleet.getMemoryWithoutUpdate().set(KEY_SPAWN_FP, fleet.getFleetPoints());
		}
		
		// do this even if fleet is null, to avoid non-stop fail-spawning of fleets 
		// if spawnFleetImpl() can't spawn one, for whatever reason
		addRecentSpawn(currSpawnLoc);
		updateSpawnRateMult();
		
		return fleet;
	}

	protected String getTravelText(StarSystemAPI system, CampaignFleetAPI fleet) {
		return "traveling to the " + system.getBaseName() + " star system";
	}
	
	protected String getActionInsideText(StarSystemAPI system, CampaignFleetAPI fleet) {
		boolean patrol = fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_PATROL_FLEET);
		String verb = "raiding";
		if (patrol) verb = "patrolling";
		return verb + " the " + system.getBaseName() + " star system";
	}
	
	protected String getActionOutsideText(StarSystemAPI system, CampaignFleetAPI fleet) {
		boolean patrol = fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_PATROL_FLEET);
		String verb = "raiding";
		if (patrol) verb = "patrolling";
		return verb + " around the " + system.getBaseName() + " star system";
	}

	protected void setLocationAndOrders(CampaignFleetAPI fleet, float probStartInHyper, float probStayInHyper) {
		StarSystemAPI system = getCurrSpawnLoc();
		
		boolean forceStartInHyper = false;
		if (currSpawnLoc != null) {
			float recentSpawns = getRecentSpawnsForSystem(currSpawnLoc);
			float max = getDesiredNumFleetsForSpawnLocation();
			if (recentSpawns > max * 0.75f || currSpawnLoc.getDaysSinceLastPlayerVisit() < 30f) {
				forceStartInHyper = Global.getSector().getPlayerFleet() != null && Global.getSector().getPlayerFleet().isInHyperspace();
			}
		}
		
		if ((float) Math.random() < probStartInHyper || forceStartInHyper) {
			Global.getSector().getHyperspace().addEntity(fleet);
		} else {
			system.addEntity(fleet);
		}
		fleet.addScript(new DisposableAggroAssignmentAI(fleet, system, this, probStayInHyper));

		// COOP FORK (edit 3): the AI constructor above sets the fleet's position synchronously, and in
		// a system the host is not in it does so with no distance check against anyone - which is how
		// an ambient fleet ends up in the co-op guest's lap. Read the position back and, only if the
		// guest's presence entity is in this same system and the fleet landed too close to it, move the
		// fleet out to the same berth vanilla gives the host. Everything the AI decided (which branch,
		// which entity it guards, the hyperspace fly-in) is left alone. Outside a co-op session
		// coopPresence() is null and this whole block is skipped, so behaviour is exactly vanilla.
		SectorEntityToken coopPresence = coopPresence();
		if (coopPresence != null && fleet.getContainingLocation() == system
				&& coopPresence.getContainingLocation() == system) {
			float coopMinDist = Global.getSettings().getMaxSensorRange() + 500f;
			Vector2f coopNudged = coop.presence.CoopSpawnSpacing.awayFrom(
					fleet.getLocation(), coopPresence.getLocation(), coopMinDist);
			if (coopNudged != null) {
				// ...and never at the host's expense. In the case this fixes the host is in another
				// system, so this is trivially true; the check is here so that if both players ever are
				// in the spawn system, the nudge is dropped rather than allowed to create for the host
				// the pop-in Misc.pickLocationNotNearPlayer just spared him.
				CampaignFleetAPI coopHost = Global.getSector().getPlayerFleet();
				boolean coopHostClear = coopHost == null || coopHost.getContainingLocation() != system
						|| Misc.getDistance(coopHost.getLocation(), coopNudged) >= coopMinDist;
				if (coopHostClear) {
					fleet.setLocation(coopNudged.x, coopNudged.y);
					Global.getLogger(DisposableFleetManager.class).info(
							"[COOP-FORK] " + getSpawnId() + " spawn nudged off the guest in "
									+ system.getNameWithLowercaseType());
				}
			}
		}
	}
}








