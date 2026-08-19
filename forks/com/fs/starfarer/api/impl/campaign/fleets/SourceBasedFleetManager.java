/*
 * ==========================================================================================
 * COOP FORK - DO NOT EDIT GAMEPLAY LOGIC. Targeted guest-presence fork only.
 * ------------------------------------------------------------------------------------------
 * Starsector version : 0.98a-RC8
 * Original source     : starfarer.api.zip!/com/fs/starfarer/api/impl/campaign/fleets/SourceBasedFleetManager.java
 *                       (byte-identical copy of the 0.98a-RC8 source; verified before editing)
 * Compiled into       : mods/coop/jars/coop-forks.jar (prepended to vmparams -classpath
 *                       ahead of starfarer.api.jar so the JVM resolves this copy first).
 *
 * Why fork: the garrison-style manager behind Remnant station defenders and the tutorial's rogue
 * miners. It keeps minFleets alive normally and ramps toward maxFleets as the player closes on the
 * source entity, then despawns the surplus once the player is far away again. Both terms read
 * Global.getSector().getPlayerFleet(); on the host the co-op guest is a mirror fleet, never the
 * player fleet, so a guest flying into a Remnant system sees the minimum garrison and any fleet it is
 * looking at is a despawn candidate.
 *
 * The fork adds the co-op guest's presence entity - published by coop.presence.CoopPresenceRegistry -
 * as a second candidate in both distance terms: nearest-player wins. Every edit is guarded on
 * presence != null; nothing is reordered and no instance field is added (this is an EveryFrameScript
 * XStream-serialised into saves, so a new field would change the save shape). With no registered
 * presence every code path below evaluates exactly as vanilla does.
 *
 * Spawn geometry: not player-relative here. spawnFleet() is abstract and every implementation places
 * the fleet at or around the source entity, so scaling the count off the nearer player is coherent on
 * its own - the fleets appear at the station the guest is approaching.
 *
 * Edits (all tagged "COOP FORK" inline):
 *   - line ~125 : advance(...) distFromSource     - min over {player, presence}; drives currMax
 *   - line ~145 : advance(...) distFromPlayer     - min over {player, presence}; despawn gate
 *
 * Version drift guard: this fork mirrors 0.98a-RC8 line for line. On first use of the presence term
 * the running game version is checked against CoopPresenceRegistry.PINNED_VERSION; on a mismatch one
 * loud warning is logged and the presence term is disabled for the process, leaving stock behaviour.
 *
 * Sibling presence forks (same mechanism, same guard): RouteManager, PlayerVisibleFleetManager,
 * DisposableFleetManager, DisposableHostileActivityFleetManager, DisposableThreatFleetManager.
 * ==========================================================================================
 */
package com.fs.starfarer.api.impl.campaign.fleets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.util.Misc;

/**
 * Up to a configurable number of fleets. Instant despawn when player is far enough.
 * 
 * New fleets generated after respawnDelay if some are destroyed.
 * 
 * @author Alex Mosolov
 *
 * Copyright 2017 Fractal Softworks, LLC
 */
public abstract class SourceBasedFleetManager implements FleetEventListener, EveryFrameScript {

	public static float DESPAWN_THRESHOLD_PAD_LY = 1;
	public static float DESPAWN_MIN_DIST_LY = 3;
	
	protected List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
	protected float thresholdLY = 4f;
	protected SectorEntityToken source;
	
	public static boolean DEBUG = true;
	protected int minFleets;
	protected int maxFleets;
	protected float respawnDelay;
	
	protected float destroyed = 0;
	
	protected Vector2f sourceLocation = new Vector2f();
	
	public SourceBasedFleetManager(SectorEntityToken source, float thresholdLY, int minFleets, int maxFleets, float respawnDelay) {
		this.source = source;
		this.thresholdLY = thresholdLY;
		this.minFleets = minFleets;
		this.maxFleets = maxFleets;
		this.respawnDelay = respawnDelay;
	}
	
	public float getThresholdLY() {
		return thresholdLY;
	}

	public SectorEntityToken getSource() {
		return source;
	}

	protected abstract CampaignFleetAPI spawnFleet();

	// ---- COOP FORK: guest presence ---------------------------------------------------------------

	/**
	 * COOP FORK. The co-op guest's presence entity (its mirror fleet on the host), or null.
	 *
	 * <p>Returns null - and therefore leaves every caller at exact vanilla behaviour - when there is no
	 * co-op session, when the guest mirror does not exist yet, when coop.jar never registered anything,
	 * when coop-forks.jar is on the classpath without the mod (registry stays at its null default), and
	 * when the running game is not the pinned Starsector build.
	 */
	protected static SectorEntityToken coopPresence() {
		return coop.presence.CoopPresenceRegistry.getForFork("SourceBasedFleetManager");
	}

	// ---- end COOP FORK block ---------------------------------------------------------------------

	public void advance(float amount) {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();

//		if (destroyed > 0) {
//			System.out.println("destroyed: " + destroyed);
//		}
		float days = Global.getSector().getClock().convertToDays(amount);
		destroyed -= days / respawnDelay;
		if (destroyed < 0) destroyed = 0;
		
		
		// clean up orphaned, juuust in case - could've been directly removed elsewhere, say, instead of despawned.
		Iterator<CampaignFleetAPI> iter = fleets.iterator();
		while (iter.hasNext()) {
			if (!iter.next().isAlive()) {
				iter.remove();
			}
		}

//		if (source != null && source.getContainingLocation().getName().toLowerCase().contains("idimmeron")) {
//			System.out.println("wefwefwefw");
//		}
		
		if (source != null) {
			if (!source.isAlive()) {
				source = null;
			} else {
				sourceLocation.set(source.getLocationInHyperspace());
			}
		}
		
		// COOP FORK: resolved once per pass; null outside a co-op session, which leaves both distance
		// tests below at their vanilla single-player values.
		SectorEntityToken coopPresence = coopPresence();

		float distFromSource = Misc.getDistanceLY(player.getLocationInHyperspace(), sourceLocation);
		// COOP FORK (edit 1): the garrison ramps up for whichever player is nearer the source, so the
		// guest approaching a Remnant station sees the same build-up the host would.
		if (coopPresence != null) {
			float coopDistFromSource = Misc.getDistanceLY(coopPresence.getLocationInHyperspace(), sourceLocation);
			if (coopDistFromSource < distFromSource) distFromSource = coopDistFromSource;
		}
		float f = 0f;
		if (distFromSource < thresholdLY) {
			f = (thresholdLY - distFromSource) / (thresholdLY * 0.1f);
			if (f > 1) f = 1;
		}
		int currMax = minFleets + Math.round((maxFleets - minFleets) * f);
		currMax -= Math.ceil(destroyed);
		
		if (source == null) {
			currMax = 0;
		}
		
		// try to despawn some fleets if above maximum
		if (currMax < fleets.size()) {
			for (CampaignFleetAPI fleet : new ArrayList<CampaignFleetAPI>(fleets)) {
				float distFromPlayer = Misc.getDistanceLY(player.getLocationInHyperspace(), fleet.getLocationInHyperspace());
				// COOP FORK (edit 2): a surplus fleet is only far away if it is far from BOTH players,
				// so one is never yanked out from under the guest because the host left.
				if (coopPresence != null) {
					float coopDistFromPlayer = Misc.getDistanceLY(coopPresence.getLocationInHyperspace(), fleet.getLocationInHyperspace());
					if (coopDistFromPlayer < distFromPlayer) distFromPlayer = coopDistFromPlayer;
				}
				if (distFromPlayer > DESPAWN_MIN_DIST_LY && distFromPlayer > thresholdLY + DESPAWN_THRESHOLD_PAD_LY) {
					fleet.despawn(FleetDespawnReason.PLAYER_FAR_AWAY, null);
					if (fleets.size() <= currMax) break;
				}
			}
			
		}
		
		// spawn some if below maximum
		if (currMax > fleets.size()) {
			CampaignFleetAPI fleet = spawnFleet();
			if (fleet != null) {
				fleets.add(fleet);
				//if (shouldAddEventListenerToFleet()) {
					fleet.addEventListener(this);
				//}
			}
		}
		
		if (source == null && fleets.size() == 0) {
			setDone(true);
		}
	}
	
//	protected boolean shouldAddEventListenerToFleet() {
//		return true;
//	}

	private boolean done = false;
	public boolean isDone() {
		return done;
	}
	
	public void setDone(boolean done) {
		this.done = done;
	}

	public boolean runWhilePaused() {
		return false;
		//return Global.getSettings().isDevMode();
	}

	
	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
		if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
			destroyed++;
		}
		fleets.remove(fleet);
	}
	
	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
		
	}
	
//	public static void main(String[] args) {
//		int minFleets = 4;
//		int maxFleets = 10;
//		float thresholdLY = 1f;
//		
//		for (float d = 0; d < 3f; d += 0.03f) {
//			float f = 0f;
//			if (d < thresholdLY) {
//				f = (thresholdLY - d) / (thresholdLY * 0.1f);
//				if (f > 1) f = 1;
//			}
//			int numFleets = minFleets + Math.round((maxFleets - minFleets) * f);
//			System.out.println("Num fleets: " + numFleets + " at range " + d);
//		} 
//	}
}




