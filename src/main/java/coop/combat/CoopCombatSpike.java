package coop.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import coop.util.CoopDebug;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 14 spike harness — throwaway, diagnostics-only. Runs the three verification spikes the plan
 * mandates before any Phase 14 implementation (plan line 1351), each one locally triggered so the
 * network hop is out of the picture (the {@code ENGAGE_GUEST} message is proven TCP tech and is
 * deliberately not part of the spikes).
 *
 * <p>Everything is double-gated: {@link CoopDebug#diagnosticsEnabled()} <em>and</em> a per-spike
 * sector memory flag that is consumed (unset) when the spike fires, so each console command arms
 * exactly one run.
 *
 * <ul>
 *   <li>{@code $coopSpikeCustoms} (guest) — <b>SPIKE14a</b>: stage the vanilla patrol-stop memory flags
 *       on the nearest NPC mirror fleet and open the vanilla fleet interaction against it via
 *       {@link CampaignUIAPI#showInteractionDialog(SectorEntityToken)}, so the rules engine runs
 *       {@code CargoScan} against the local player's cargo. The flag's value picks the branch:
 *       {@code toff} (default, rules.csv:3395), {@code smuggle} (rules.csv:3551) or {@code legacy}
 *       (rules.csv:2755).</li>
 *   <li>{@code $coopSpikeEngage} (guest) — <b>SPIKE14b</b>: open a real battle against the nearest NPC
 *       mirror via {@link CampaignUIAPI#startBattle(BattleCreationContext)} and detect the return to
 *       the campaign state on the next frame this code runs.</li>
 *   <li>{@code $coopSpikeEject} (host) — <b>SPIKE14c</b>: clear the interim 12b
 *       {@link MemFlags#FLEET_IGNORED_BY_OTHER_FLEETS} on the guest player mirror so vanilla hostiles
 *       chase it, then run a per-frame watcher that ejects the mirror from any battle it lands in and
 *       samples chaser distance/velocity so closing speed and the engine contact range can be read
 *       straight out of the log. Stopped by {@code $coopSpikeEjectStop} or session end.</li>
 * </ul>
 *
 * <p>Debug code must never throw: every engine call is wrapped and returns a sentinel, matching
 * {@code CoopFleetVisibilityProbe}.
 */
public final class CoopCombatSpike {
    private static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    private static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    private static final String FLAG_CUSTOMS = "$coopSpikeCustoms";
    private static final String FLAG_ENGAGE = "$coopSpikeEngage";
    private static final String FLAG_EJECT = "$coopSpikeEject";
    private static final String FLAG_EJECT_STOP = "$coopSpikeEjectStop";
    private static final String FLAG_HUNT = "$coopSpikeHunt";

    /** rules.csv:2755 {@code customsInspectionScan} keys off this on the interaction target's memory. */
    private static final String CUSTOMS_INSPECTION_FLAG = "$doingCustomsInspection";
    /** Per-encounter latches the two live scan rules bail on (rules.csv:3395 / :3551). */
    private static final String TOFF_DID_ALREADY_FLAG = "$tOff_didAlready";
    private static final String CARGO_SCAN_DID_ALREADY_FLAG = "$cargoScan_didAlready";

    private static final String A = "SPIKE14a ";
    private static final String B = "SPIKE14b ";
    private static final String C = "SPIKE14c ";

    /**
     * Trigger file in the engine's common folder ({@code saves\common\coop_spike.data} on disk — the
     * engine appends {@code .data}). The test environment has no console mod, so spikes are armed by
     * writing a command into that file externally; the harness polls it at 1 Hz via the sandbox-legal
     * {@code SettingsAPI} common-file surface, consumes it, and translates it into the same sector
     * memory flags the console path would set. Commands: {@code customs [toff|smuggle|legacy]},
     * {@code engage}, {@code eject}, {@code ejectstop}.
     */
    private static final String TRIGGER_FILE = "coop_spike";
    private static final long TRIGGER_POLL_INTERVAL_MILLIS = 1000L;

    private long nextTriggerPollAtMillis;

    /** Chaser proximity below which the watcher starts sampling even without an assignment match. */
    private static final float SAMPLE_RANGE = 2000f;
    /** ~2 Hz sampling so the log stays readable but still resolves the contact-formation distance. */
    private static final long SAMPLE_INTERVAL_MILLIS = 500L;

    private boolean awaitingBattleReturn;
    private long battleOpenedAtMillis;

    private boolean ejectWatcherActive;
    private long nextSampleAtMillis;
    private int ejectCount;
    private String lastMemberState = "";
    private String previousFrameMemberState = "";
    private String lastHullState = "";
    private String previousFrameHullState = "";

    /**
     * Pump hook. Cheap and inert unless diagnostics are on; safe to call every frame.
     *
     * @param host true when this instance is the coop host (spike c is host-side; a/b are guest-side)
     */
    public void maybeRun(SectorAPI sector, boolean host, boolean sessionActive, long nowMillis) {
        if (!CoopDebug.diagnosticsEnabled()) {
            return;
        }
        if (sector == null) {
            return;
        }
        try {
            if (!sessionActive) {
                if (ejectWatcherActive) {
                    CoopLog.info(CoopCombatSpike.class, C + "watcher stopped: coop session ended"
                            + " ejects=" + ejectCount);
                    ejectWatcherActive = false;
                }
                return;
            }
            pollTriggerFile(sector, nowMillis);
            if (host) {
                if (consumeFlag(sector, FLAG_EJECT)) {
                    startEjectWatcher(sector);
                }
                if (consumeFlag(sector, FLAG_HUNT)) {
                    runHuntSpike(sector);
                }
                if (ejectWatcherActive) {
                    tickEjectWatcher(sector, nowMillis);
                }
            } else {
                reportBattleReturn(sector, nowMillis);
                Object customs = consumeFlagValue(sector, FLAG_CUSTOMS);
                if (customs != null) {
                    runCustomsSpike(sector, customs);
                }
                if (consumeFlag(sector, FLAG_ENGAGE)) {
                    runEngageSpike(sector, nowMillis);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCombatSpike.class, "Phase 14 spike harness failed", ex);
        }
    }

    // ---- (a) customs / inspection rules dialog against a mirror --------------------------------

    /**
     * Stages a vanilla patrol-stop posture on an NPC mirror and opens the standard fleet interaction
     * against it, so {@code FleetInteractionDialogPluginImpl} fires {@code BeginFleetEncounter} and the
     * rules engine takes a scan branch instead of the plain engage/leave menu. Three variants, selected
     * by the value of {@code $coopSpikeCustoms} (see {@code docs/PHASE14_SPIKE_NOTES.md}):
     *
     * <ul>
     *   <li>{@code toff} (default) — {@code tOffPatrolBegin}, rules.csv:3395. The running-dark
     *       confrontation, i.e. the committed fix for the Phase 9 transponder-reactions gap.</li>
     *   <li>{@code smuggle} — {@code cargoScanInitial}, rules.csv:3551. Fewest preconditions.</li>
     *   <li>{@code legacy} — {@code customsInspectionScan}, rules.csv:2755. Kept because the plan names
     *       "customs", but the driving script is not in the API dump and looks pre-0.8 dead code.</li>
     * </ul>
     */
    private void runCustomsSpike(SectorAPI sector, Object flagValue) {
        String variant = customsVariant(flagValue);
        CampaignFleetAPI player = sector.getPlayerFleet();
        CampaignFleetAPI mirror = nearestNpcMirror(sector, player);
        CoopLog.info(CoopCombatSpike.class, A + "armed variant=" + variant
                + " player=" + describe(player)
                + " mirror=" + describe(mirror)
                + " dist=" + fmt(distance(player, mirror))
                + " playerTransponderOn=" + safeTransponder(player));
        if (mirror == null) {
            CoopLog.warn(CoopCombatSpike.class, A + "FAIL no NPC mirror found " + mirrorInventory(sector));
            return;
        }
        MemoryAPI mem = memoryOf(mirror);
        if (mem == null) {
            CoopLog.warn(CoopCombatSpike.class, A + "FAIL mirror has no memory");
            return;
        }

        // Preconditions that silently break the rules path rather than throwing (all verified against
        // the api_src trace): a null commander removes MemKeys.ENTITY from the rule memory map so every
        // OpenCommLink `$entity.*` condition fails; a player-faction fleet gets a Leave-only option
        // panel; a hostile fleet fails the `!$isHostile` condition; and CargoScan dereferences
        // Misc.getSourceMarket(other) before its own null check, so a missing `$sourceMarket` is a
        // hard crash inside the scan.
        String commander = commanderOf(mirror);
        boolean playerFaction = isPlayerFaction(mirror);
        boolean hostile = hostileToPlayer(mirror, player);
        String market = applySourceMarket(sector, mirror, mem);
        CoopLog.info(CoopCombatSpike.class, A + "preconditions"
                + " faction=" + factionOf(mirror)
                + " playerFaction=" + playerFaction
                + " commander=" + commander
                + " hostileToPlayer=" + hostile
                + " sourceMarket=" + market);
        if (playerFaction || hostile || "none".equals(commander) || market.startsWith("NONE")) {
            CoopLog.warn(CoopCombatSpike.class, A + "WARN precondition(s) unmet — the rules path is "
                    + "expected to fall through to the plain encounter menu; opening anyway so the "
                    + "failure mode is on record");
        }

        String applied = applyCustomsFlags(mem, variant);
        CoopLog.info(CoopCombatSpike.class, A + "flags applied on mirror: " + applied);

        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            CoopLog.warn(CoopCombatSpike.class, A + "FAIL no campaign UI");
            return;
        }
        if (ui.isShowingDialog()) {
            CoopLog.warn(CoopCombatSpike.class, A + "FAIL a dialog is already open; close it and retry");
            return;
        }
        boolean shown;
        try {
            shown = ui.showInteractionDialog(mirror);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCombatSpike.class, A + "FAIL showInteractionDialog threw", ex);
            return;
        }
        CoopLog.info(CoopCombatSpike.class, A + "showInteractionDialog returned=" + shown
                + " dialog=" + describeDialogTarget(ui));
    }

    private static String customsVariant(Object flagValue) {
        String raw = String.valueOf(flagValue).trim().toLowerCase();
        return switch (raw) {
            case "smuggle", "smuggling", "cargoscan" -> "smuggle";
            case "legacy", "ci" -> "legacy";
            default -> "toff";
        };
    }

    /**
     * The posture flags, all on the mirror fleet's own memory — at {@code BeginFleetEncounter} no
     * active person is set yet, so {@code MemKeys.LOCAL} is the fleet and the rule conditions read
     * these bare (no {@code $entity.} prefix).
     */
    private static String applyCustomsFlags(MemoryAPI mem, String variant) {
        StringBuilder out = new StringBuilder();
        // A fleet that ignores the player, or is ignored by everyone (12b's interim flag), will not
        // stop anybody.
        unsetFlag(mem, out, MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
        unsetFlag(mem, out, MemFlags.FLEET_IGNORES_OTHER_FLEETS);
        unsetFlag(mem, out, MemFlags.MEMORY_KEY_IGNORE_PLAYER_COMMS);
        unsetFlag(mem, out, MemFlags.MEMORY_KEY_PATROL_ALLOW_TOFF);
        setFlag(mem, out, MemFlags.FLEET_DO_NOT_IGNORE_PLAYER, true);
        setFlag(mem, out, MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        // Both branches bail on their own "already did this" latch.
        unsetFlag(mem, out, TOFF_DID_ALREADY_FLAG);
        unsetFlag(mem, out, CARGO_SCAN_DID_ALREADY_FLAG);

        switch (variant) {
            case "smuggle" -> {
                // rules.csv:3551 cargoScanInitial keys off $pursuePlayer_smugglingScan (score 50).
                // SmugglingScanScript writes it via Misc.setFlagWithReason, which also maintains the
                // required-key link — do the same rather than poking the derived key by hand.
                setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_PURSUE_PLAYER, "smugglingScan", 1f);
                setFlagWithReason(mem, out,
                        MemFlags.MEMORY_KEY_STICK_WITH_PLAYER_IF_ALREADY_TARGET, "smugglingScan", 10f);
            }
            case "legacy" -> {
                setFlag(mem, out, CUSTOMS_INSPECTION_FLAG, true);
                setFlag(mem, out, MemFlags.MEMORY_KEY_CUSTOMS_INSPECTOR, true);
            }
            default -> {
                // rules.csv:3395 tOffPatrolBegin needs CaresAboutTransponder (which is exactly
                // $cfai_makeAggressive_tOff, per CaresAboutTransponder.java:28 + Misc.flagHasReason)
                // plus $sawPlayerTransponderOff at score 100. The latter is normally written by
                // CoreCampaignPluginImpl.updateEntityFacts when the player runs dark; set it directly
                // so the spike does not depend on the player's transponder state.
                setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, "tOff", 7f);
                setFlag(mem, out, MemFlags.MEMORY_KEY_SAW_PLAYER_WITH_TRANSPONDER_OFF, true);
                setFlag(mem, out, MemFlags.MEMORY_KEY_PATROL_FLEET, true);
            }
        }
        return out.toString();
    }

    private static void setFlagWithReason(MemoryAPI mem, StringBuilder out, String key,
                                          String reason, float expireDays) {
        try {
            Misc.setFlagWithReason(mem, key, reason, true, expireDays);
            out.append(key).append('_').append(reason).append("=true ");
        } catch (RuntimeException | LinkageError ex) {
            out.append(key).append('_').append(reason).append("=THREW ");
        }
    }

    /**
     * {@code CargoScan} reads {@code Misc.getSourceMarket(other).getMemory()} before its own null
     * check, so a mirror without a valid {@code $sourceMarket} crashes the scan. Pick a non-free-port
     * market of the mirror's own faction, preferring one in the mirror's location.
     */
    private static String applySourceMarket(SectorAPI sector, CampaignFleetAPI mirror, MemoryAPI mem) {
        try {
            String existing = mem.getString(MemFlags.MEMORY_KEY_SOURCE_MARKET);
            if (existing != null && !existing.isEmpty()
                    && sector.getEconomy().getMarket(existing) != null) {
                return existing + " (already set)";
            }
        } catch (RuntimeException | LinkageError ex) {
            // fall through and pick one
        }
        MarketAPI picked = pickSourceMarket(sector, mirror);
        if (picked == null) {
            return "NONE (CargoScan will NPE — mirror faction has no non-free-port market)";
        }
        try {
            mem.set(MemFlags.MEMORY_KEY_SOURCE_MARKET, picked.getId());
            return picked.getId() + " (assigned)";
        } catch (RuntimeException | LinkageError ex) {
            return "NONE (assignment threw)";
        }
    }

    private static MarketAPI pickSourceMarket(SectorAPI sector, CampaignFleetAPI mirror) {
        String factionId = factionOf(mirror);
        MarketAPI fallback = null;
        try {
            LocationAPI location = containingLocation(mirror);
            for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
                if (market == null || market.getFactionId() == null
                        || !market.getFactionId().equals(factionId)
                        || market.hasCondition(Conditions.FREE_PORT)) {
                    continue;
                }
                if (location != null && market.getContainingLocation() == location) {
                    return market;
                }
                if (fallback == null) {
                    fallback = market;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return fallback;
        }
        return fallback;
    }

    private static void setFlag(MemoryAPI mem, StringBuilder out, String key, Object value) {
        try {
            mem.set(key, value);
            out.append(key).append('=').append(value).append(' ');
        } catch (RuntimeException | LinkageError ex) {
            out.append(key).append("=THREW ");
        }
    }

    private static void unsetFlag(MemoryAPI mem, StringBuilder out, String key) {
        try {
            mem.unset(key);
            out.append('-').append(key).append(' ');
        } catch (RuntimeException | LinkageError ex) {
            out.append('-').append(key).append("=THREW ");
        }
    }

    // ---- (b) startBattle against a mirror ------------------------------------------------------

    private void runEngageSpike(SectorAPI sector, long nowMillis) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        CampaignFleetAPI mirror = nearestNpcMirror(sector, player);
        CoopLog.info(CoopCombatSpike.class, B + "armed"
                + " player=" + describe(player)
                + " mirror=" + describe(mirror)
                + " dist=" + fmt(distance(player, mirror))
                + " mirrorMembers=" + memberState(mirror));
        if (player == null || mirror == null) {
            CoopLog.warn(CoopCombatSpike.class, B + "FAIL missing "
                    + (player == null ? "player fleet" : "NPC mirror") + " " + mirrorInventory(sector));
            return;
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            CoopLog.warn(CoopCombatSpike.class, B + "FAIL no campaign UI");
            return;
        }
        BattleCreationContext context;
        try {
            context = new BattleCreationContext(player, FleetGoal.ATTACK, mirror, FleetGoal.ATTACK);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCombatSpike.class, B + "FAIL BattleCreationContext ctor threw", ex);
            return;
        }
        CoopLog.info(CoopCombatSpike.class, B + "calling startBattle");
        awaitingBattleReturn = true;
        battleOpenedAtMillis = nowMillis;
        try {
            ui.startBattle(context);
        } catch (RuntimeException | LinkageError ex) {
            awaitingBattleReturn = false;
            CoopLog.warn(CoopCombatSpike.class, B + "FAIL startBattle threw", ex);
            return;
        }
        CoopLog.info(CoopCombatSpike.class, B + "startBattle returned without throwing");
    }

    /** First campaign frame after the battle state exits — proves the round trip completed. */
    private void reportBattleReturn(SectorAPI sector, long nowMillis) {
        if (!awaitingBattleReturn) {
            return;
        }
        // The state transition is queued, so the pump can run one more campaign frame before combat
        // actually starts (observed: a premature "back in campaign after 33ms" while the deployment
        // screen was opening). Skip frames until enough wall time has passed for a real battle.
        if (nowMillis - battleOpenedAtMillis < 2000L) {
            CoopLog.info(CoopCombatSpike.class, B + "pre-transition frame elapsed="
                    + (nowMillis - battleOpenedAtMillis) + "ms — waiting for the combat round trip");
            return;
        }
        awaitingBattleReturn = false;
        CampaignFleetAPI player = sector.getPlayerFleet();
        CampaignFleetAPI mirror = nearestNpcMirror(sector, player);
        CoopLog.info(CoopCombatSpike.class, B + "back in campaign after "
                + (nowMillis - battleOpenedAtMillis) + "ms"
                + " playerMembers=" + memberState(player)
                + " mirror=" + describe(mirror)
                + " mirrorMembers=" + memberState(mirror));
    }

    // ---- (c) battle-eject timing ---------------------------------------------------------------

    private void startEjectWatcher(SectorAPI sector) {
        CampaignFleetAPI mirror = findPlayerMirror(sector);
        ejectCount = 0;
        nextSampleAtMillis = 0L;
        // Seed both from the live fleet, so a contact on the very first watched frame still has a real
        // baseline to diff against instead of an empty string.
        lastMemberState = memberState(mirror);
        previousFrameMemberState = lastMemberState;
        lastHullState = hullState(mirror);
        previousFrameHullState = lastHullState;
        if (mirror == null) {
            CoopLog.warn(CoopCombatSpike.class, C + "FAIL no guest player mirror found "
                    + mirrorInventory(sector));
            return;
        }
        ejectWatcherActive = true;
        String cleared = clearIgnoredFlag(mirror);
        CoopLog.info(CoopCombatSpike.class, C + "watcher armed mirror=" + describe(mirror)
                + " ignoredFlag=" + cleared
                + " members=" + memberState(mirror));
    }

    private void tickEjectWatcher(SectorAPI sector, long nowMillis) {
        if (consumeFlag(sector, FLAG_EJECT_STOP)) {
            CoopLog.info(CoopCombatSpike.class, C + "watcher stopped by " + FLAG_EJECT_STOP
                    + " ejects=" + ejectCount);
            ejectWatcherActive = false;
            return;
        }
        CampaignFleetAPI mirror = findPlayerMirror(sector);
        if (mirror == null) {
            CoopLog.warn(CoopCombatSpike.class, C + "watcher stopped: mirror gone ejects=" + ejectCount);
            ejectWatcherActive = false;
            return;
        }
        // The mirror is re-flagged whenever CoopFleetMirror recreates it, so keep clearing.
        if (isIgnored(mirror)) {
            CoopLog.info(CoopCombatSpike.class, C + "re-cleared " + MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS
                    + " on a recreated mirror: " + clearIgnoredFlag(mirror));
        }

        previousFrameMemberState = lastMemberState;
        lastMemberState = memberState(mirror);
        previousFrameHullState = lastHullState;
        lastHullState = hullState(mirror);

        BattleAPI battle = battleOf(mirror);
        if (battle != null) {
            handleContact(mirror, battle);
            return;
        }
        maybeSampleChasers(sector, mirror, nowMillis);
    }

    private void handleContact(CampaignFleetAPI mirror, BattleAPI battle) {
        String atContact = lastMemberState;
        String opponents = describeOpponents(mirror, battle);
        CoopLog.info(CoopCombatSpike.class, C + "CONTACT battle detected"
                + " opponents=[" + opponents + "]"
                + " membersPrevFrame=" + previousFrameMemberState
                + " membersAtContact=" + atContact);
        String leaveResult;
        try {
            battle.leave(mirror, false);
            leaveResult = "ok";
        } catch (RuntimeException | LinkageError ex) {
            leaveResult = "THREW " + ex;
        }
        ejectCount++;
        String after = memberState(mirror);
        String afterHull = hullState(mirror);
        boolean stillInBattle = battleOf(mirror) != null;
        // The verdict compares hull only. CR drifts every frame from supply use and repair, so a
        // hull+CR diff would report damage on a clean eject.
        CoopLog.info(CoopCombatSpike.class, C + "EJECT #" + ejectCount
                + " leave=" + leaveResult
                + " stillInBattle=" + stillInBattle
                + " membersAfter=" + after
                + " damaged=" + (!afterHull.equals(previousFrameHullState)));
        lastMemberState = after;
        lastHullState = afterHull;
    }

    /**
     * Distance/velocity samples for any hostile that is either targeting the mirror by assignment or
     * within {@link #SAMPLE_RANGE}. The closing-speed column is the component of the relative velocity
     * along the line to the mirror, which is exactly the number the Phase 14 handoff threshold needs
     * (threshold = engine contact range + closing speed x latency budget).
     */
    private void maybeSampleChasers(SectorAPI sector, CampaignFleetAPI mirror, long nowMillis) {
        if (nowMillis < nextSampleAtMillis) {
            return;
        }
        LocationAPI location = containingLocation(mirror);
        if (location == null) {
            return;
        }
        List<String> samples = new ArrayList<>();
        for (CampaignFleetAPI fleet : fleetsIn(location)) {
            if (fleet == null || fleet == mirror || isAnyMirror(fleet) || isPlayer(sector, fleet)) {
                continue;
            }
            float dist = distance(fleet, mirror);
            boolean targeting = assignmentTargets(fleet, mirror);
            if (!targeting && (dist < 0f || dist > SAMPLE_RANGE)) {
                continue;
            }
            samples.add(sampleLine(fleet, mirror, dist, targeting));
        }
        if (samples.isEmpty()) {
            return;
        }
        nextSampleAtMillis = nowMillis + SAMPLE_INTERVAL_MILLIS;
        CoopLog.info(CoopCombatSpike.class, C + "chase mirror=" + describe(mirror)
                + " mirrorVel=" + velocity(mirror)
                + " | " + String.join(" | ", samples));
    }

    private static String sampleLine(CampaignFleetAPI chaser, CampaignFleetAPI mirror,
                                     float dist, boolean targeting) {
        return describe(chaser)
                + " dist=" + fmt(dist)
                + " closing=" + fmt(closingSpeed(chaser, mirror))
                + " vel=" + velocity(chaser)
                + " burn=" + fmt(burnLevel(chaser))
                + " targetingMirror=" + targeting
                + " assignment=" + assignmentOf(chaser)
                + " hostile=" + hostileTo(chaser, mirror)
                + " pick=" + encounterOptionFor(chaser, mirror)
                + " seesMirror=" + visibleTo(mirror, chaser)
                + " fp=" + fleetPoints(chaser) + "v" + fleetPoints(mirror);
    }

    /** The engine's own engage-or-not answer, via the side-effect-free pureCheck overload. */
    private static String encounterOptionFor(CampaignFleetAPI chaser, CampaignFleetAPI mirror) {
        try {
            CampaignFleetAIAPI ai = chaser.getAI();
            if (ai == null) {
                return "noAI";
            }
            CampaignFleetAIAPI.EncounterOption option = ai.pickEncounterOption(null, mirror, true);
            return option == null ? "null" : option.name();
        } catch (RuntimeException | LinkageError ex) {
            return "THREW:" + ex.getClass().getSimpleName();
        }
    }

    private static String visibleTo(CampaignFleetAPI target, CampaignFleetAPI observer) {
        try {
            return String.valueOf(target.isVisibleToSensorsOf(observer));
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static String fleetPoints(CampaignFleetAPI fleet) {
        try {
            return String.valueOf(fleet.getFleetPoints());
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    /**
     * Spike c follow-up (2026-08-19): hostiles see the mirror and pick ENGAGE but never retask to
     * intercept it, and an ENGAGE-picker crossing at 10 su formed no battle. This probe answers the
     * remaining design question: does an <em>ordered</em> intercept consummate contact into a real
     * battle? Injects an INTERCEPT assignment toward the guest mirror on the nearest hostile,
     * preferring one whose own {@code pickEncounterOption} says ENGAGE. Run with the eject watcher
     * active so a formed battle is caught and ejected.
     */
    private void runHuntSpike(SectorAPI sector) {
        CampaignFleetAPI mirror = findPlayerMirror(sector);
        if (mirror == null) {
            CoopLog.warn(CoopCombatSpike.class, C + "HUNT FAIL no guest player mirror "
                    + mirrorInventory(sector));
            return;
        }
        LocationAPI location = containingLocation(mirror);
        if (location == null) {
            CoopLog.warn(CoopCombatSpike.class, C + "HUNT FAIL mirror has no location");
            return;
        }
        CampaignFleetAPI best = null;
        float bestDist = Float.MAX_VALUE;
        boolean bestEngages = false;
        for (CampaignFleetAPI fleet : fleetsIn(location)) {
            if (fleet == null || fleet == mirror || isAnyMirror(fleet) || isPlayer(sector, fleet)
                    || !"true".equals(hostileTo(fleet, mirror))) {
                continue;
            }
            float dist = distance(fleet, mirror);
            if (dist < 0f) {
                continue;
            }
            boolean engages = "ENGAGE".equals(encounterOptionFor(fleet, mirror));
            // Prefer any ENGAGE-picker over any non-picker; among equals, prefer the nearest.
            if (best == null || (engages && !bestEngages) || (engages == bestEngages && dist < bestDist)) {
                best = fleet;
                bestDist = dist;
                bestEngages = engages;
            }
        }
        if (best == null) {
            CoopLog.warn(CoopCombatSpike.class, C + "HUNT FAIL no hostile fleet in " + locId(mirror));
            return;
        }
        try {
            CampaignFleetAIAPI ai = best.getAI();
            if (ai == null) {
                CoopLog.warn(CoopCombatSpike.class, C + "HUNT FAIL chosen fleet has no AI "
                        + describe(best));
                return;
            }
            ai.addAssignmentAtStart(FleetAssignment.INTERCEPT, mirror, 2f, null);
            CoopLog.info(CoopCombatSpike.class, C + "HUNT injected INTERCEPT->mirror on "
                    + describe(best)
                    + " dist=" + fmt(bestDist)
                    + " pick=" + encounterOptionFor(best, mirror)
                    + " assignmentNow=" + assignmentOf(best));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCombatSpike.class, C + "HUNT FAIL addAssignmentAtStart threw", ex);
        }
    }

    // ---- trigger file --------------------------------------------------------------------------

    /**
     * Consumes {@code saves\common\coop_spike.data} and sets the corresponding spike memory flag, so
     * the file path and the (absent) console path arm the exact same one-shot logic. The read/delete
     * calls declare a checked {@code IOException}; caught as {@code Exception} so this class never
     * references {@code java.io} (the script classloader blocks it).
     */
    private void pollTriggerFile(SectorAPI sector, long nowMillis) {
        if (nowMillis < nextTriggerPollAtMillis) {
            return;
        }
        nextTriggerPollAtMillis = nowMillis + TRIGGER_POLL_INTERVAL_MILLIS;
        String command;
        try {
            if (!Global.getSettings().fileExistsInCommon(TRIGGER_FILE)) {
                return;
            }
            command = Global.getSettings().readTextFileFromCommon(TRIGGER_FILE);
            Global.getSettings().deleteTextFileFromCommon(TRIGGER_FILE);
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopCombatSpike.class, "SPIKE14 trigger file read failed", ex);
            return;
        }
        if (command == null) {
            return;
        }
        String[] parts = command.trim().toLowerCase().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        String flag;
        Object value = Boolean.TRUE;
        switch (parts[0]) {
            case "customs" -> {
                flag = FLAG_CUSTOMS;
                if (parts.length > 1) {
                    value = parts[1];
                }
            }
            case "engage" -> flag = FLAG_ENGAGE;
            case "eject" -> flag = FLAG_EJECT;
            case "ejectstop" -> flag = FLAG_EJECT_STOP;
            case "hunt" -> flag = FLAG_HUNT;
            default -> {
                CoopLog.warn(CoopCombatSpike.class, "SPIKE14 trigger file: unknown command \""
                        + command.trim() + "\"");
                return;
            }
        }
        try {
            MemoryAPI memory = sector.getMemoryWithoutUpdate();
            if (memory == null) {
                return;
            }
            memory.set(flag, value);
            CoopLog.info(CoopCombatSpike.class, "SPIKE14 trigger file: armed " + flag + "=" + value);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCombatSpike.class, "SPIKE14 trigger file arm failed", ex);
        }
    }

    // ---- lookup helpers ------------------------------------------------------------------------

    private static CampaignFleetAPI findPlayerMirror(SectorAPI sector) {
        for (LocationAPI location : allLocations(sector)) {
            for (CampaignFleetAPI fleet : fleetsIn(location)) {
                if (fleet != null && isPlayerMirror(fleet)) {
                    return fleet;
                }
            }
        }
        return null;
    }

    /** Nearest NPC mirror in the player's own location — a battle or dialog needs co-location. */
    private static CampaignFleetAPI nearestNpcMirror(SectorAPI sector, CampaignFleetAPI player) {
        if (player == null) {
            return null;
        }
        LocationAPI location = containingLocation(player);
        if (location == null) {
            return null;
        }
        CampaignFleetAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (CampaignFleetAPI fleet : fleetsIn(location)) {
            if (fleet == null || !isNpcMirror(fleet)) {
                continue;
            }
            float dist = distance(player, fleet);
            if (dist >= 0f && dist < bestDist) {
                bestDist = dist;
                best = fleet;
            }
        }
        return best;
    }

    /** Diagnostic tail so a "no mirror found" line says whether mirrors exist elsewhere. */
    private static String mirrorInventory(SectorAPI sector) {
        int npc = 0;
        int player = 0;
        StringBuilder locs = new StringBuilder();
        for (LocationAPI location : allLocations(sector)) {
            for (CampaignFleetAPI fleet : fleetsIn(location)) {
                if (fleet == null) {
                    continue;
                }
                if (isNpcMirror(fleet)) {
                    npc++;
                    locs.append(locId(fleet)).append(' ');
                } else if (isPlayerMirror(fleet)) {
                    player++;
                    locs.append(locId(fleet)).append(' ');
                }
            }
        }
        return "(sector has npcMirrors=" + npc + " playerMirrors=" + player + " at " + locs.toString().trim() + ")";
    }

    private static List<LocationAPI> allLocations(SectorAPI sector) {
        List<LocationAPI> locations = new ArrayList<>();
        try {
            List<LocationAPI> all = sector.getAllLocations();
            if (all != null) {
                for (LocationAPI location : all) {
                    if (location != null) {
                        locations.add(location);
                    }
                }
            }
            LocationAPI hyperspace = sector.getHyperspace();
            if (hyperspace != null && !locations.contains(hyperspace)) {
                locations.add(hyperspace);
            }
        } catch (RuntimeException | LinkageError ex) {
            // debug must never throw; return whatever we collected
        }
        return locations;
    }

    private static List<CampaignFleetAPI> fleetsIn(LocationAPI location) {
        try {
            List<CampaignFleetAPI> fleets = location.getFleets();
            return fleets == null ? List.of() : fleets;
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
    }

    private boolean consumeFlag(SectorAPI sector, String flag) {
        return consumeFlagValue(sector, flag) != null;
    }

    /** One-shot: returns the flag's value and unsets it, or null when it was not set. */
    private Object consumeFlagValue(SectorAPI sector, String flag) {
        try {
            MemoryAPI memory = sector.getMemoryWithoutUpdate();
            if (memory == null || !memory.contains(flag)) {
                return null;
            }
            Object value = memory.get(flag);
            memory.unset(flag);
            return value == null ? Boolean.TRUE : value;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String factionOf(CampaignFleetAPI fleet) {
        try {
            return fleet.getFaction() == null ? "?" : fleet.getFaction().getId();
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static boolean isPlayerFaction(CampaignFleetAPI fleet) {
        try {
            return fleet.getFaction() != null && fleet.getFaction().isPlayerFaction();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static String commanderOf(CampaignFleetAPI fleet) {
        try {
            return fleet.getCommander() == null ? "none" : fleet.getCommander().getNameString();
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static boolean hostileToPlayer(CampaignFleetAPI fleet, CampaignFleetAPI player) {
        try {
            return player != null && fleet.isHostileTo(player);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ---- accessors (all best-effort; debug must never throw) -----------------------------------

    private static MemoryAPI memoryOf(CampaignFleetAPI fleet) {
        try {
            return fleet.getMemoryWithoutUpdate();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static boolean isPlayerMirror(CampaignFleetAPI fleet) {
        MemoryAPI m = memoryOf(fleet);
        try {
            return m != null && m.getBoolean(PLAYER_MIRROR_TAG);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isNpcMirror(CampaignFleetAPI fleet) {
        MemoryAPI m = memoryOf(fleet);
        try {
            return m != null && m.contains(NPC_MIRROR_TAG);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isAnyMirror(CampaignFleetAPI fleet) {
        return isPlayerMirror(fleet) || isNpcMirror(fleet);
    }

    private static boolean isPlayer(SectorAPI sector, CampaignFleetAPI fleet) {
        try {
            return fleet == sector.getPlayerFleet();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isIgnored(CampaignFleetAPI fleet) {
        MemoryAPI m = memoryOf(fleet);
        try {
            return m != null && m.getBoolean(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static String clearIgnoredFlag(CampaignFleetAPI fleet) {
        MemoryAPI m = memoryOf(fleet);
        if (m == null) {
            return "no-memory";
        }
        try {
            boolean was = m.getBoolean(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
            m.unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
            return "was=" + was + " nowSet=" + m.contains(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
        } catch (RuntimeException | LinkageError ex) {
            return "THREW";
        }
    }

    private static BattleAPI battleOf(CampaignFleetAPI fleet) {
        try {
            return fleet.getBattle();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String describeOpponents(CampaignFleetAPI mirror, BattleAPI battle) {
        StringBuilder out = new StringBuilder();
        try {
            List<CampaignFleetAPI> other = battle.getOtherSideFor(mirror);
            if (other == null) {
                return "?";
            }
            for (CampaignFleetAPI fleet : other) {
                if (fleet == null) {
                    continue;
                }
                out.append(describe(fleet))
                        .append(" dist=").append(fmt(distance(fleet, mirror)))
                        .append(" closing=").append(fmt(closingSpeed(fleet, mirror)))
                        .append("; ");
            }
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
        return out.toString().trim();
    }

    /** Compact per-member hull/CR line so autoresolve damage is provable from the log alone. */
    private static String memberState(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        try {
            List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
            if (members == null || members.isEmpty()) {
                return "empty";
            }
            for (FleetMemberAPI member : members) {
                if (member == null) {
                    continue;
                }
                out.append(safeHullId(member))
                        .append(':').append(frac(hullFraction(member)))
                        .append('/').append(frac(combatReadiness(member)))
                        .append(' ');
            }
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
        return out.toString().trim();
    }

    /** Hull fractions only — the damage signature the eject verdict diffs. */
    private static String hullState(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        try {
            List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
            if (members == null) {
                return "?";
            }
            for (FleetMemberAPI member : members) {
                if (member != null) {
                    out.append(frac(hullFraction(member))).append(' ');
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
        return out.toString().trim();
    }

    private static String safeHullId(FleetMemberAPI member) {
        try {
            String id = member.getHullId();
            return id == null ? "?" : id;
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static float hullFraction(FleetMemberAPI member) {
        try {
            return member.getStatus().getHullFraction();
        } catch (RuntimeException | LinkageError ex) {
            return -1f;
        }
    }

    private static float combatReadiness(FleetMemberAPI member) {
        try {
            return member.getRepairTracker().getCR();
        } catch (RuntimeException | LinkageError ex) {
            return -1f;
        }
    }

    private static boolean assignmentTargets(CampaignFleetAPI fleet, CampaignFleetAPI mirror) {
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            if (ai == null) {
                return false;
            }
            FleetAssignmentDataAPI assignment = ai.getCurrentAssignment();
            return assignment != null && assignment.getTarget() == mirror;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static String assignmentOf(CampaignFleetAPI fleet) {
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            if (ai == null) {
                return "noAI";
            }
            FleetAssignmentDataAPI assignment = ai.getCurrentAssignment();
            if (assignment == null) {
                return "none";
            }
            SectorEntityToken target = assignment.getTarget();
            return assignment.getAssignment() + "->" + (target == null ? "null" : shortId(target.getId()));
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static String hostileTo(CampaignFleetAPI fleet, CampaignFleetAPI mirror) {
        try {
            CampaignFleetAIAPI ai = fleet.getAI();
            return ai == null ? "?" : String.valueOf(ai.isHostileTo(mirror));
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static String safeTransponder(CampaignFleetAPI fleet) {
        try {
            return fleet == null ? "?" : String.valueOf(fleet.isTransponderOn());
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static String describeDialogTarget(CampaignUIAPI ui) {
        try {
            if (ui.getCurrentInteractionDialog() == null) {
                return "none";
            }
            SectorEntityToken target = ui.getCurrentInteractionDialog().getInteractionTarget();
            return "open target=" + (target == null ? "null" : shortId(target.getId()))
                    + " plugin=" + ui.getCurrentInteractionDialog().getPlugin().getClass().getSimpleName();
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static String describe(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return "none";
        }
        try {
            return shortId(fleet.getId()) + "\"" + safeName(fleet) + "\"@" + locId(fleet);
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static String safeName(CampaignFleetAPI fleet) {
        try {
            String name = fleet.getName();
            return name == null ? "" : name;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static LocationAPI containingLocation(SectorEntityToken token) {
        try {
            return token.getContainingLocation();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String locId(SectorEntityToken token) {
        LocationAPI location = containingLocation(token);
        try {
            return location == null ? "none" : location.getId();
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        return id.length() <= 10 ? id : id.substring(id.length() - 10);
    }

    private static float distance(SectorEntityToken a, SectorEntityToken b) {
        if (a == null || b == null) {
            return -1f;
        }
        try {
            Vector2f pa = a.getLocation();
            Vector2f pb = b.getLocation();
            if (pa == null || pb == null) {
                return -1f;
            }
            float dx = pa.x - pb.x;
            float dy = pa.y - pb.y;
            return (float) Math.sqrt(dx * dx + dy * dy);
        } catch (RuntimeException | LinkageError ex) {
            return -1f;
        }
    }

    /** Positive = the chaser is closing on the mirror, in su/sec along the line between them. */
    private static float closingSpeed(CampaignFleetAPI chaser, CampaignFleetAPI mirror) {
        try {
            Vector2f pc = chaser.getLocation();
            Vector2f pm = mirror.getLocation();
            Vector2f vc = chaser.getVelocity();
            Vector2f vm = mirror.getVelocity();
            if (pc == null || pm == null || vc == null || vm == null) {
                return -1f;
            }
            float dx = pm.x - pc.x;
            float dy = pm.y - pc.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len <= 0.0001f) {
                return 0f;
            }
            return ((vc.x - vm.x) * dx + (vc.y - vm.y) * dy) / len;
        } catch (RuntimeException | LinkageError ex) {
            return -1f;
        }
    }

    private static String velocity(CampaignFleetAPI fleet) {
        try {
            Vector2f v = fleet.getVelocity();
            if (v == null) {
                return "?";
            }
            return "(" + fmt(v.x) + "," + fmt(v.y) + ")|" + fmt((float) Math.sqrt(v.x * v.x + v.y * v.y));
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static float burnLevel(CampaignFleetAPI fleet) {
        try {
            return fleet.getCurrBurnLevel();
        } catch (RuntimeException | LinkageError ex) {
            return -1f;
        }
    }

    private static String fmt(float value) {
        return String.format("%.1f", value);
    }

    private static String frac(float value) {
        return String.format("%.3f", value);
    }
}
