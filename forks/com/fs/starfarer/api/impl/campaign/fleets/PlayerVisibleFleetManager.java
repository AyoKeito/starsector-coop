/*
 * ==========================================================================================
 * COOP FORK - DO NOT EDIT GAMEPLAY LOGIC. Targeted guest-presence fork only.
 * ------------------------------------------------------------------------------------------
 * Starsector version : 0.98a-RC8
 * Original source     : starfarer.api.zip!/com/fs/starfarer/api/impl/campaign/fleets/PlayerVisibleFleetManager.java
 *                       (byte-identical copy of the 0.98a-RC8 source; verified before editing)
 * Compiled into       : mods/coop/jars/coop-forks.jar (prepended to vmparams -classpath
 *                       ahead of starfarer.api.jar so the JVM resolves this copy first).
 *
 * Why fork: this is the despawn half of the player-proximity ambient spawner family
 * (DisposableFleetManager -> DisposablePirateFleetManager / DisposableLuddicPathFleetManager /
 * DisposableHostileActivityFleetManager / DisposableThreatFleetManager). Every ~1 day it culls any
 * managed fleet that is not visible to Global.getSector().getPlayerFleet(). On the host the co-op
 * guest is a mirror fleet, never the player fleet, so a fleet the guest is staring at counts as
 * unseen and gets despawned out from under it. Same class of problem as the RouteManager fork, and
 * the same fix shape: a second "presence" term published by coop.presence.CoopPresenceRegistry.
 *
 * The single edit is an additive disjunction guarded on presence != null; nothing is reordered and
 * no instance field is added (this class and its subclasses are EveryFrameScripts XStream-serialised
 * into saves, so a new field would change the save shape). With no registered presence - solo play,
 * guest side, coop.jar absent - the code path below is exactly vanilla's.
 *
 * Edits (all tagged "COOP FORK" inline):
 *   - line ~90  : isVisibleToPlayer(...)          - OR in the same test against the guest presence
 *   - new static : coopPresence()                  - inherited by every subclass fork
 *   - new method : isVisibleToCoopPresence(...)    - vanilla's own test, presence as the observer
 *
 * Version drift guard: this fork mirrors 0.98a-RC8 line for line. On first use of the presence term
 * the running game version is checked against CoopPresenceRegistry.PINNED_VERSION; on a mismatch one
 * loud warning is logged and the presence term is disabled for the process, leaving stock behaviour.
 *
 * Sibling presence forks (same mechanism, same guard): RouteManager, DisposableFleetManager,
 * SourceBasedFleetManager, DisposableHostileActivityFleetManager, DisposableThreatFleetManager.
 * ==========================================================================================
 */
package com.fs.starfarer.api.impl.campaign.fleets;

import java.util.Iterator;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;


public abstract class PlayerVisibleFleetManager extends BaseLimitedFleetManager {

	protected IntervalUtil despawnTracker = new IntervalUtil(0.75f, 1.25f);

	// ---- COOP FORK: guest presence ---------------------------------------------------------------

	/**
	 * COOP FORK. The co-op guest's presence entity (its mirror fleet on the host), or null.
	 *
	 * <p>Returns null - and therefore leaves every caller at exact vanilla behaviour - when there is no
	 * co-op session, when the guest mirror does not exist yet, when coop.jar never registered anything,
	 * when coop-forks.jar is on the classpath without the mod (registry stays at its null default), and
	 * when the running game is not the pinned Starsector build.
	 *
	 * <p>Declared here rather than on each fork so the whole DisposableFleetManager subtree - including
	 * the forked subclasses in other packages - inherits one accessor.
	 */
	protected static SectorEntityToken coopPresence() {
		return coop.presence.CoopPresenceRegistry.getForFork("PlayerVisibleFleetManager");
	}

	// ---- end COOP FORK block ---------------------------------------------------------------------


	protected Object readResolve() {
		super.readResolve();
		if (despawnTracker == null) {
			despawnTracker = new IntervalUtil(0.75f, 1.25f);
		}
		return this;
	}
	
	@Override
	public void advance(float amount) {
		super.advance(amount);
		
		boolean reset = false;
		//reset = true;
		
		if (reset) {
			if (this instanceof DisposableFleetManager) {
				DisposableFleetManager dfm = (DisposableFleetManager) this;
				dfm.recentSpawns.clear();
			}
		}
		
		float days = Global.getSector().getClock().convertToDays(amount);
		despawnTracker.advance(days);
		if (despawnTracker.intervalElapsed()) {
			Iterator<ManagedFleetData> iter = active.iterator();
			while (iter.hasNext()) {
				ManagedFleetData curr = iter.next();
				if (reset ||
						(!isVisibleToPlayer(curr.fleet) && isOkToDespawnAssumingNotPlayerVisible(curr.fleet))) {
					if (curr.fleet.getBattle() == null) {
						curr.fleet.despawn(FleetDespawnReason.PLAYER_FAR_AWAY, null);
						iter.remove();
					}
					// can't just directly despawn as it might be involved in a battle or something else
//					if (curr.fleet.getAI() != null) {
//						curr.fleet.getAI().clearAssignments();
//						curr.fleet.getAI().addAssignmentAtStart(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, curr.fleet, 100f, null);
//					}
				}
			}
		}
	}
	
	protected abstract boolean isOkToDespawnAssumingNotPlayerVisible(CampaignFleetAPI fleet);
	
	protected boolean isVisibleToPlayer(CampaignFleetAPI fleet) {
		// COOP FORK (edit 1): a fleet the co-op guest can see is being watched by a real player and must
		// not be culled. Evaluated first and only ever short-circuits to true, so with no presence
		// registered the remainder of this method is reached in exactly vanilla's state.
		SectorEntityToken coopPresence = coopPresence();
		if (coopPresence != null && isVisibleToCoopPresence(fleet, coopPresence)) return true;

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return false;

		if (player.getContainingLocation() != fleet.getContainingLocation()) {
			float dist = Misc.getDistance(player.getLocationInHyperspace(), fleet.getLocationInHyperspace());
			return dist < getHyperspaceCullRange();
		}
		
		float cullRange = player.getMaxSensorRangeToDetect(fleet) + getInSystemCullRange();
		float dist = Misc.getDistance(player.getLocation(), fleet.getLocation());
		return dist < cullRange;
	}

	/**
	 * COOP FORK. isVisibleToPlayer(...) above, evaluated with the guest presence entity as the observer
	 * instead of the player fleet - same branches, same cull ranges, same comparisons. The presence is a
	 * CampaignFleetAPI in practice (the guest's mirror fleet), so the sensor-range term is the identical
	 * call; the SectorEntityToken fallback exists only so this cannot NPE if the slot ever holds a plain
	 * token.
	 */
	protected boolean isVisibleToCoopPresence(CampaignFleetAPI fleet, SectorEntityToken presence) {
		if (presence.getContainingLocation() == null) return false;

		if (presence.getContainingLocation() != fleet.getContainingLocation()) {
			float dist = Misc.getDistance(presence.getLocationInHyperspace(), fleet.getLocationInHyperspace());
			return dist < getHyperspaceCullRange();
		}

		float sensorRange;
		if (presence instanceof CampaignFleetAPI) {
			sensorRange = ((CampaignFleetAPI) presence).getMaxSensorRangeToDetect(fleet);
		} else {
			sensorRange = Global.getSettings().getMaxSensorRange();
		}
		float cullRange = sensorRange + getInSystemCullRange();
		float dist = Misc.getDistance(presence.getLocation(), fleet.getLocation());
		return dist < cullRange;
	}

	protected float getHyperspaceCullRange() {
		return 1500;
	}
	
	protected float getInSystemCullRange() {
		return 500;
	}
}


















