package coop.combat;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * Stages the vanilla "this fleet moves to engage you" posture on a guest-side NPC mirror so that the
 * host-pushed {@code ENGAGE_GUEST} handoff opens as the ordinary being-caught encounter — the same
 * dialog, the same options, the same disengage mechanics a solo player gets (Phase 14, revised
 * 2026-08-19: the handoff used to call {@code startBattle} directly and dropped the guest straight
 * into the deployment screen with no choice).
 *
 * <h2>What the dialog actually reads</h2>
 * {@code FleetInteractionDialogPluginImpl}'s opening posture is one predicate,
 * {@code otherFleetWantsToFight()} (FID:3483 &rarr; {@code fleetWantsToFight}, FID:3492-3519). It is a
 * two-factor AND:
 * <ol>
 *   <li>hostility — {@code ai.isHostileTo(player) || context.isEngagedInHostilities() ||
 *       $cfai_makePreventDisengage} (FID:3514-3517); and</li>
 *   <li>{@code ai.pickEncounterOption(context, playerFleet) == ENGAGE} (FID:3510).</li>
 * </ol>
 * True &rarr; FID:973 prints {@code initialAggressive} ("The &lt;faction&gt; fleet maneuvers to prevent
 * you from disengaging easily") and the option panel takes the FID:2868-2921 subtree, where the free
 * {@code Leave} is unreachable and the player gets Disengage / Attempt to disengage / the
 * story-point clean disengage instead. False &rarr; "assumes a neutral posture" and a plain Leave,
 * which for a handoff the host already decided on would read as a bug.
 *
 * <h2>Why stage at all, when the host already checked the pick</h2>
 * The watcher fires only for fleets whose own AI answered {@code ENGAGE}
 * ({@code CoopNpcThreatWatcher.picksEngage}) — but it asks that question <em>host-side, against the
 * host's mirror of the guest fleet</em>, while the dialog re-asks it <em>guest-side, against the
 * guest's real fleet</em>. The strength maths behind the answer (TacticalModule:1334-1415: member
 * strength sums, CR-malfunction share, burn levels, personality thresholds) reads live rosters, so
 * the two evaluations can disagree at the margin — and a disagreement turns a committed handoff into
 * a neutral menu. One flag closes that gap deterministically.
 *
 * <h2>The pick flag, and why not more</h2>
 * {@code $cfai_makeAggressive} is checked first thing in {@code TacticalModule.pickEncounterOption}
 * (:1286) and returns {@code ENGAGE} outright when the target is the player fleet, ahead of every
 * strength comparison and ahead of the 0.3 s decision cache. That is factor 2 of the AND.
 *
 * <p>Deliberately <b>not</b> set: {@code $cfai_makePreventDisengage}. It would also force ENGAGE
 * (:1292) but it additionally satisfies the hostility disjunct for non-hostile fleets and flips the
 * pursuit pick from {@code LET_THEM_GO} to {@code HARRY} (:1454) — i.e. it removes the real
 * clean-getaway mechanics. The handoff is meant to present a vanilla encounter, not an inescapable
 * one; whether the guest can slip away stays vanilla's call (burn levels via
 * {@code FleetEncounterContext.canOutrunOtherFleet}:2880, fleet size via {@code canDisengage}
 * FID:3181, the 1-SP {@code CLEAN_DISENGAGE} option at FID:2917).
 *
 * <p>Also deliberately not touched: {@link MemFlags#FLEET_IGNORES_OTHER_FLEETS}, which mirrors carry
 * permanently. The dialog reads it in exactly one place — {@code pullInNearbyFleets} (FID:540-542) —
 * and only for <em>bystander</em> candidates, never for the interaction target itself. Clearing it
 * (as the customs staging must, for a different rule path) would only make the mirror eligible to be
 * dragged into unrelated battles.
 *
 * <h2>Factor 1: the mirror is not hostile to a transponder-off guest (S4-E, 2026-09-14)</h2>
 * The aggressive flag alone was not enough, and the guest log said so: <em>every</em> handoff ever
 * staged with {@code hostileToPlayer=false}, so {@code fleetWantsToFight} failed at FID:3514 and the
 * guest was offered a free Leave against a fleet the host had already decided caught it.
 * {@code $cfai_makeAggressive} only means "engage <em>if</em> already hostile"; it is factor 2 and
 * factor 2 alone.
 *
 * <p>{@code TacticalModule.isHostileTo} (:1095-1112) decides hostility toward the player fleet from
 * fleet <em>memory</em> first: {@code $cfai_makeHostile} &rarr; hostile; {@code $cfai_makeHostileWhileTOff}
 * with the player's transponder off &rarr; hostile (:1108); otherwise the faction-standing answer is
 * reached only when the player's transponder is on or {@code knowsWhoPlayerIs()} — transponder on,
 * {@code $sawPlayerTransponderOn}, or unique-signature hulls (:1111). A guest running dark is none of
 * those, so a pirate the guest has never squawked at is <em>not</em> hostile to it, by design.
 *
 * <p>Vanilla closes that same gap for its own fleets in the identification block of
 * {@code TacticalModule.advance} (:283-290): when a fleet with the right visibility level makes a
 * transponder-off player, it writes
 * {@code Misc.setFlagWithReason(memory, "$cfai_makeHostileWhileTOff", "tOff", true, 1f)} — "we know
 * who that is, and we are hostile to them while they keep the transponder off". That block is behind
 * {@code campaignFleet2.isPlayerFleet()}, so it never runs for a mirror: the mirror carries no vanilla
 * fleet memory at all (nothing in {@code $cfai_*} is replicated).
 *
 * <p>So the staging writes that same flag, with the coop reason and vanilla's own 1-day expiry. This
 * is not an override of the identification rule — it is the rule's conclusion, transplanted. The host
 * ran the real check against the real fleets and decided this NPC caught the guest, and "it caught
 * you" is exactly what vanilla means by "it made you at contact". Verified live 2026-09-14: writing
 * the flag onto the mirror over the debug bridge turned the next handoff's staging line to
 * {@code hostileToPlayer=true} and replaced the free Leave with "Attempt to disengage".
 *
 * <p>Note what it still does <b>not</b> do: it is conditional on the guest's transponder being off.
 * A guest flying with the transponder on takes the {@code knowsWhoPlayerIs} path and faction standing
 * decides, which is the vanilla answer and already hostile for the pirates and Remnants the watcher
 * hands off. Hostility is all this buys — the disengage rolls stay vanilla's, as above.
 *
 * <h2>No permanent posture pollution</h2>
 * Both flags are written through {@link Misc#setFlagWithReason} with a coop-owned reason, so they are
 * reference-counted rather than bare sets: {@code Misc}:1439-1451 registers
 * {@code <flag>_coopEngage} as a <em>required key</em> of {@code <flag>}, and {@code MemoryAPI} drops
 * the base key once no required key survives. Three independent things retire the aggressive flag —
 * vanilla's own one-battle unset (via {@link MemFlags#MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY},
 * honoured in {@code CampaignEngine.reportBattleOccurred}), the {@link #AGGRESSIVE_EXPIRY_DAYS} expiry
 * on the reason key, and {@link #clear} on the no-battle outcome. The hostility flag has two of those
 * three (no one-battle unset exists for it), so on a handoff that turns into a battle its reason key
 * lives out the 1-day expiry — the same lifetime vanilla's own {@code "tOff"} reason would have had on
 * a fleet that identified the guest itself, which is the point. Neither path can strand another mod's
 * or vanilla's reason for the same flag: {@link #clear} unsets our reason key only.
 */
public final class CoopEngageDialogStaging {

    /** Coop-owned reason, so the flag is reference-counted alongside vanilla's own ("tOff", "pursue"). */
    static final String REASON = "coopEngage";

    /**
     * Backstop expiry on both reason keys, in campaign days. Short: the encounter resolves within
     * seconds of real time, and the two normal retirements ({@link #clear} on a no-battle outcome,
     * vanilla's one-battle unset on a fought one) both land first for the aggressive flag. For the
     * hostility flag it is also the ordinary lifetime, and it is deliberately vanilla's own number for
     * the same flag (TacticalModule:284, reason {@code "tOff"}, 1 day).
     */
    static final float AGGRESSIVE_EXPIRY_DAYS = 1f;

    private CoopEngageDialogStaging() {
    }

    /**
     * Applies the aggressor posture to {@code mirror}'s own memory and returns a log-friendly summary
     * of what was set. Never throws: a half-staged mirror still opens a real encounter dialog (the
     * fleet's native pick may well have said ENGAGE anyway), while an exception here would take the
     * pump frame down.
     */
    public static String stage(CampaignFleetAPI mirror) {
        MemoryAPI mem = memoryOf(mirror);
        if (mem == null) {
            return "no-memory";
        }
        StringBuilder out = new StringBuilder();
        setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        // Factor 1 of fleetWantsToFight: a mirror carries none of vanilla's fleet memory, so nothing
        // ever told it that it has made a transponder-off guest. The host's handoff decision is that
        // identification; see the class javadoc (S4-E, 2026-09-14).
        setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_MAKE_HOSTILE_WHILE_TOFF, true);
        // Vanilla's "aggressive for this encounter only" idiom (MakeOtherFleetAggressiveOnce): the
        // engine hard-unsets $cfai_makeAggressive in reportBattleOccurred when this is set, so a
        // handoff that turns into a real battle cleans itself up with no mod bookkeeping at all.
        setFlag(mem, out, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY, true);
        return out.toString().trim();
    }

    /**
     * Retires the staged posture for an encounter that produced no battle (the guest disengaged, or
     * just left). Unsetting the reason key is what drops the base flag — see the class javadoc — so
     * this never disturbs a reason some other system put on the same flag: vanilla's own "tOff" and
     * "battle" reasons for {@code $cfai_makeHostileWhileTOff} survive untouched, and if one of them is
     * live the base flag stays set, which is correct — that fleet made the guest on its own account.
     * Never throws.
     */
    public static String clear(CampaignFleetAPI mirror) {
        MemoryAPI mem = memoryOf(mirror);
        if (mem == null) {
            return "no-memory";
        }
        StringBuilder out = new StringBuilder();
        setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, false);
        setFlagWithReason(mem, out, MemFlags.MEMORY_KEY_MAKE_HOSTILE_WHILE_TOFF, false);
        unsetFlag(mem, out, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY);
        return out.toString().trim();
    }

    /**
     * Log line naming anything that would make the encounter open in the wrong shape. A mirror that
     * is not hostile still opens the dialog, but the aggressor branch needs the hostility half of
     * {@code fleetWantsToFight} (FID:3514) — so an unexpected {@code hostile=false} here is the first
     * thing to look at if the guest sees a neutral posture. It is read after {@link #stage} has run,
     * so since S4-E a false means the hostility flag did not take.
     */
    public static String describePreconditions(CampaignFleetAPI mirror, CampaignFleetAPI player) {
        return CoopCustomsDialogStaging.describePreconditions(mirror)
                + " hostileToPlayer=" + isHostileTo(mirror, player);
    }

    private static boolean isHostileTo(CampaignFleetAPI mirror, CampaignFleetAPI player) {
        try {
            return mirror != null && player != null && mirror.isHostileTo(player);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static void setFlagWithReason(MemoryAPI mem, StringBuilder out, String key, boolean value) {
        try {
            Misc.setFlagWithReason(mem, key, REASON, value, AGGRESSIVE_EXPIRY_DAYS);
            out.append(key).append('_').append(REASON).append('=').append(value).append(' ');
        } catch (RuntimeException | LinkageError ex) {
            out.append(key).append('_').append(REASON).append("=THREW ");
        }
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

    private static MemoryAPI memoryOf(CampaignFleetAPI fleet) {
        try {
            return fleet == null ? null : fleet.getMemoryWithoutUpdate();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
