package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetActionTextProvider;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.TacticalModulePlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

import java.util.Locale;

/**
 * Host-side capture of the one line a hovered NPC fleet's tooltip shows under its name — "travelling
 * to Jangala", "pursuing your fleet", "running from the Hegemony Patrol", "engaged in battle".
 *
 * <p><b>Why this exists.</b> That line is not a field on the fleet; the vanilla tooltip derives it
 * live from the fleet's {@code ModularFleetAI} — its current assignment, its tactical module's target
 * and largest enemy, its fleeing/busy/maintaining-contact flags. A guest mirror
 * ({@link CoopFleetMirror}) is an empty AI-mode fleet with no assignments, whose tactical module never
 * acquires a target (it is shielded out of every engagement), so on the guest that derivation produces
 * nothing and every mirrored fleet reads as a bare name. The {@code aiAssignmentSummary} field of
 * {@link CoopNpcFleetSnapshot} has carried the space for this since Phase 9 but the host wrote
 * {@code ""} into it; this class fills it, and the guest pins the result onto the mirror with
 * {@code setActionTextOverride} + {@code setNullAIActionText} so the vanilla tooltip renders it.
 *
 * <h2>What is replicated</h2>
 * <p>{@link #resolve(View)} is a line-by-line re-implementation of the resolution in the 0.98a tooltip
 * builder ({@code com.fs.starfarer.ui.impl.StandardTooltipV2$9}, and its identical twin {@code F$2}),
 * over a {@link View} captured off the engine by {@link #capture}. Splitting it that way is the whole
 * testability story: the branchy half is a pure function of a value object, so the pursuit/fleeing/
 * assignment cases are unit tests rather than an in-game errand.
 *
 * <h2>Observer rewrites (the one place this deliberately differs from vanilla)</h2>
 * <p>Vanilla resolves the text for <em>the client doing the hovering</em>, so "your fleet" means "the
 * local player's fleet". Here the text is captured on the host and read by the guest, so the two
 * player-fleet references swap:
 *
 * <ul>
 *   <li>a target that is the <b>host's own player fleet</b> becomes the host player's name — the same
 *       {@link CoopPresenceIndicator#presenceLabel(String)} the guest's mirror of the host wears, so
 *       the tooltip names the fleet the guest can actually see;</li>
 *   <li>a target that is the <b>guest's mirror on the host</b> becomes "your fleet", because from the
 *       guest's seat that mirror <em>is</em> their fleet.</li>
 * </ul>
 *
 * <p>{@code Misc.isAvoidingPlayerHalfheartedly} stays host-player-relative (it takes only the fleet and
 * reads the host's sector state); that is an accepted edge case on the "avoiding contact" wording.
 *
 * <h2>Cost and safety</h2>
 * <p>This runs once per replicated fleet per {@code NPC_FLEET_SET} send (1 Hz), on the same hot path
 * that captures rosters. A tooltip string must never be able to break replication, so {@link #capture}
 * swallows everything and returns {@code ""}; the result is stripped of newlines (which the tooltip
 * cannot render anyway) and capped at {@value #MAX_LENGTH} characters so no fleet can inflate the set
 * message. No reflection: every read is public 0.98a API.
 */
// Public since Phase 30: the dormant agent bridge (coop.debug) is the second caller of capture().
public final class CoopNpcActionTextCapture {

    /** Longest text put on the wire. Vanilla's own strings sit far under this; the cap is a bound. */
    static final int MAX_LENGTH = 80;

    /** Vanilla's station "avoiding" vs "disengaging from" threshold ({@code StandardTooltipV2$9}). */
    static final float STATION_AVOID_DISTANCE = 1000f;

    private CoopNpcActionTextCapture() {
    }

    // ---- Engine capture -------------------------------------------------------------------------

    /**
     * The action text for one host-native NPC fleet, or {@code ""} when it has none (or anything at
     * all went wrong reading it).
     *
     * @param fleet          the host fleet being replicated
     * @param hostPlayerFleet the host's own player fleet, rewritten to {@code hostPlayerLabel}
     * @param guestMirror    the guest's mirror on the host ({@code $coopMirrorFleet}), rewritten to
     *                       "your fleet"; may be null
     * @param hostPlayerLabel what the guest calls the host player (its mirror's campaign-map label)
     */
    public static String capture(CampaignFleetAPI fleet, CampaignFleetAPI hostPlayerFleet,
                                 CampaignFleetAPI guestMirror, String hostPlayerLabel) {
        if (fleet == null) {
            return "";
        }
        try {
            return resolve(view(fleet, hostPlayerFleet, guestMirror, hostPlayerLabel));
        } catch (RuntimeException | LinkageError ignored) {
            // Never abort a set send over a tooltip string.
            return "";
        }
    }

    private static View view(CampaignFleetAPI fleet, CampaignFleetAPI hostPlayerFleet,
                             CampaignFleetAPI guestMirror, String hostPlayerLabel) {
        View view = new View();
        view.hostPlayerLabel = text(hostPlayerLabel);
        view.inBattle = fleet.getBattle() != null;
        view.nullAiActionText = text(fleet.getNullAIActionText());

        CampaignFleetAIAPI ai = fleet.getAI();
        // Vanilla checks the concrete ModularFleetAI; for host-native NPC fleets that is exactly what
        // implements this interface, and the interface is all the mod may reference.
        if (!(ai instanceof ModularFleetAIAPI)) {
            return view;
        }
        ModularFleetAIAPI modular = (ModularFleetAIAPI) ai;
        view.hasAi = true;
        view.actionTextOverride = modular.getActionTextOverride();

        FleetAssignmentDataAPI assignment = modular.getCurrentAssignment();
        if (assignment != null) {
            view.hasAssignment = true;
            view.assignment = assignment.getAssignment();
            view.assignmentActionText = assignment.getActionText();
            view.assignmentTarget =
                    target(assignment.getTarget(), fleet, hostPlayerFleet, guestMirror);
        }

        TacticalModulePlugin tactical = modular.getTacticalModule();
        if (tactical != null) {
            view.fleeing = tactical.isFleeing();
            view.busy = tactical.isBusy();
            view.maintainingContact = tactical.isMaintainingContact();
            view.tacticalTarget = target(tactical.getTarget(), fleet, hostPlayerFleet, guestMirror);
            SectorEntityToken largest = tactical.getLargestEnemy();
            view.largestEnemy = target(largest, fleet, hostPlayerFleet, guestMirror);
            if (largest != null) {
                view.distanceToLargestEnemy = Misc.getDistance(fleet, largest);
            }
            view.priorityTarget =
                    target(tactical.getPriorityTarget(), fleet, hostPlayerFleet, guestMirror);
        }

        // Only consulted when the branches above produced nothing, but the provider call is the read:
        // resolve() cannot invoke it, so it happens here.
        FleetActionTextProvider provider = modular.getActionTextProvider();
        if (provider != null) {
            view.providerActionText = provider.getActionText(fleet);
        }

        view.avoidingPlayerHalfheartedly = avoidingPlayerHalfheartedly(fleet);
        view.abyssalFlag = abyssalFlag(fleet);
        view.avoidingAbyssalHyperspace = view.abyssalFlag && inHyperspace(fleet);
        return view;
    }

    private static boolean avoidingPlayerHalfheartedly(CampaignFleetAPI fleet) {
        try {
            return Misc.isAvoidingPlayerHalfheartedly(fleet);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * The raw {@code $avoidingAbyssalHyperspace} memory flag, unqualified by where the fleet is.
     *
     * <p>Vanilla reads it exactly once and uses it twice, and the two uses are not the same test
     * ({@code StandardTooltipV2$9}: the local is assigned inside
     * {@code if ((bl = ...getBoolean(...)) && ...isInHyperspace())}, so it always carries the raw
     * flag, and the trailing "looking for" block later gates on {@code !bl} alone). Folding the
     * hyperspace qualifier into the one field made a fleet that still carried the flag outside
     * hyperspace resolve to "looking for ..." here and to its assignment text in the vanilla tooltip
     * this class transcribes line by line.
     */
    private static boolean abyssalFlag(CampaignFleetAPI fleet) {
        try {
            MemoryAPI memory = fleet.getMemoryWithoutUpdate();
            return memory != null && memory.getBoolean(MemFlags.AVOIDING_ABYSSAL_HYPERSPACE);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean inHyperspace(CampaignFleetAPI fleet) {
        try {
            return fleet.isInHyperspace();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * One target token as the resolution needs to see it, with the two observer rewrites already
     * decided. The guest-mirror test is identity against the published handle first and the
     * {@code $coopMirrorFleet} memory tag second, so a missing handle does not silently turn "your
     * fleet" back into the host player's name.
     */
    private static Target target(SectorEntityToken token, CampaignFleetAPI observer,
                                 CampaignFleetAPI hostPlayerFleet, CampaignFleetAPI guestMirror) {
        if (token == null) {
            return null;
        }
        boolean isFleet = token instanceof CampaignFleetAPI;
        CampaignFleetAPI asFleet = isFleet ? (CampaignFleetAPI) token : null;
        String name = text(token.getName());
        String nameWithFaction = asFleet == null ? name : text(asFleet.getNameWithFaction());
        boolean stationMode = asFleet != null && asFleet.isStationMode();
        boolean guestMirrorTarget = isFleet
                && ((guestMirror != null && token == guestMirror) || isTaggedPlayerMirror(asFleet));
        boolean hostPlayer = !guestMirrorTarget && isFleet
                && (token == hostPlayerFleet || asFleet.isPlayerFleet());
        return new Target(name, nameWithFaction, isFleet, stationMode,
                visibleToSensorsOf(token, observer), hostPlayer, guestMirrorTarget);
    }

    private static boolean isTaggedPlayerMirror(CampaignFleetAPI fleet) {
        try {
            MemoryAPI memory = fleet == null ? null : fleet.getMemoryWithoutUpdate();
            return memory != null && memory.getBoolean(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean visibleToSensorsOf(SectorEntityToken token, CampaignFleetAPI observer) {
        try {
            return token.isVisibleToSensorsOf(observer);
        } catch (RuntimeException | LinkageError ignored) {
            // Treating an unreadable target as visible keeps the wording on the "pursuing" branch
            // rather than fabricating a "looking for".
            return true;
        }
    }

    // ---- The pure resolution --------------------------------------------------------------------

    /**
     * The tooltip's own resolution order, transcribed. Reading it against
     * {@code StandardTooltipV2$9} is the point; the only intentional divergences are the two observer
     * rewrites in {@link #describeWithFaction} / the "your fleet" tests, and the
     * {@code BaseLocation.LocationToken} check in the trailing "looking for" block — that is a core
     * class the mod cannot reference, so a nameless target stands in for it.
     */
    static String resolve(View view) {
        String text = null;
        if (view.hasAi) {
            Target chosen = view.tacticalTarget;
            if (view.largestEnemy != null && view.fleeing) {
                chosen = view.largestEnemy;
            }
            if (chosen == null && view.priorityTarget != null && view.priorityTarget.isFleet()) {
                chosen = view.priorityTarget;
            }
            Target targetFleet = chosen != null && chosen.isFleet() ? chosen : null;
            FleetAssignment assignment = view.assignment;
            boolean following = targetFleet != null
                    && ((assignment == FleetAssignment.INTERCEPT && targetFleet.visibleToObserved())
                    || assignment == FleetAssignment.FOLLOW);
            boolean lostContact =
                    (assignment == FleetAssignment.INTERCEPT || assignment == FleetAssignment.FOLLOW)
                            && (targetFleet == null || !targetFleet.visibleToObserved());

            if ((view.busy || lostContact) && targetFleet != null && (!following || view.fleeing)) {
                Target largest = view.largestEnemy;
                if (largest != null && view.fleeing) {
                    if (largest.isGuestMirror()) {
                        text = view.avoidingPlayerHalfheartedly
                                ? "avoiding contact" : "running from your fleet";
                    } else if (largest.stationMode()) {
                        text = (view.distanceToLargestEnemy < STATION_AVOID_DISTANCE
                                ? "avoiding " : "disengaging from ") + describeWithFaction(largest, view);
                    } else {
                        text = "running from " + describeWithFaction(largest, view);
                    }
                } else {
                    String verb = "pursuing";
                    if (targetFleet.stationMode()) {
                        verb = "engaging";
                    } else if (view.maintainingContact) {
                        verb = "maintaining contact with";
                    }
                    if (!targetFleet.visibleToObserved()) {
                        verb = "looking for";
                    }
                    text = verb + " " + describeWithFaction(targetFleet, view);
                }
            } else if (view.actionTextOverride != null) {
                text = view.actionTextOverride;
            } else if (view.providerActionText != null) {
                text = view.providerActionText;
            }

            if (view.avoidingAbyssalHyperspace) {
                text = "avoiding abyssal hyperspace";
            }

            if (view.hasAssignment && text == null) {
                text = view.assignmentActionText;
                if (text == null) {
                    text = assignment == null ? "" : assignment.getDescription().toLowerCase(Locale.ROOT);
                    Target target = view.assignmentTarget;
                    if (assignment != null && assignment.isAddTargetName() && target != null) {
                        if (target.isFleet() && view.maintainingContact) {
                            text = "maintaining contact with";
                        }
                        text = text + " " + describePlain(target, view);
                    }
                }
            }

            // Vanilla gates this block on the RAW memory flag, not on the hyperspace-qualified text
            // condition above it — see abyssalFlag().
            if (targetFleet != null && !targetFleet.visibleToObserved() && !view.abyssalFlag) {
                text = "looking for";
                if (assignment != FleetAssignment.STANDING_DOWN) {
                    if (targetFleet.isGuestMirror() || targetFleet.isHostPlayer()) {
                        text = text + " " + describeWithFaction(targetFleet, view);
                    } else if (!targetFleet.name().isEmpty()) {
                        text = text + " " + targetFleet.nameWithFaction();
                    } else {
                        // Vanilla's BaseLocation.LocationToken branch, approximated by "has no name".
                        text = text + " unknown location";
                    }
                }
            }
        }
        if (view.inBattle) {
            text = "engaged in battle";
        } else if (!view.hasAi) {
            text = view.nullAiActionText;
        }
        return sanitize(text);
    }

    /** Vanilla's {@code getNameWithFaction()} slot, with the two observer rewrites applied. */
    private static String describeWithFaction(Target target, View view) {
        if (target.isGuestMirror()) {
            return "your fleet";
        }
        if (target.isHostPlayer()) {
            return view.hostPlayerLabel;
        }
        return target.nameWithFaction();
    }

    /** Vanilla's assignment-target slot, which uses the plain {@code getName()}. */
    private static String describePlain(Target target, View view) {
        if (target.isGuestMirror()) {
            return "your fleet";
        }
        if (target.isHostPlayer()) {
            return view.hostPlayerLabel;
        }
        return target.name();
    }

    /**
     * Newlines cannot render in the tooltip and would only cost wire bytes ({@link CoopFleetCodec}
     * escapes them, so they round-trip rather than corrupting the record — this is presentation, not
     * safety). The length cap bounds what one fleet can add to {@code NPC_FLEET_SET}.
     */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() > MAX_LENGTH) {
            flat = flat.substring(0, MAX_LENGTH).trim();
        }
        return flat;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    // ---- The seam -------------------------------------------------------------------------------

    /**
     * A target token, flattened to the seven things the resolution asks about it.
     *
     * @param name             {@code getName()}, "" when it has none
     * @param nameWithFaction  {@code getNameWithFaction()} for fleets, else {@link #name}
     * @param isFleet          whether the token is a {@code CampaignFleetAPI} (vanilla casts to it)
     * @param stationMode      {@code isStationMode()}
     * @param visibleToObserved {@code isVisibleToSensorsOf(theFleetBeingCaptured)}
     * @param isHostPlayer     the host's own player fleet — rewritten to the host's label
     * @param isGuestMirror    the guest's mirror on the host — rewritten to "your fleet"
     */
    record Target(String name, String nameWithFaction, boolean isFleet, boolean stationMode,
                  boolean visibleToObserved, boolean isHostPlayer, boolean isGuestMirror) {

        /** An ordinary visible NPC fleet. */
        static Target fleet(String nameWithFaction) {
            return new Target(nameWithFaction, nameWithFaction, true, false, true, false, false);
        }

        /** The host's own player fleet, as the host sees it. */
        static Target hostPlayer() {
            return new Target("Player fleet", "Player fleet", true, false, true, true, false);
        }

        /** The guest's mirror on the host ({@code $coopMirrorFleet}). */
        static Target guestMirror(String label) {
            return new Target(label, label, true, false, true, false, true);
        }

        /** A non-fleet token, e.g. a market or a jump point an assignment points at. */
        static Target entity(String name) {
            return new Target(name, name, false, false, true, false, false);
        }

        Target notVisible() {
            return new Target(name, nameWithFaction, isFleet, stationMode, false, isHostPlayer,
                    isGuestMirror);
        }

        Target asStation() {
            return new Target(name, nameWithFaction, isFleet, true, visibleToObserved, isHostPlayer,
                    isGuestMirror);
        }
    }

    /**
     * Everything {@link #resolve(View)} reads, captured off the engine in one pass.
     *
     * <p>Deliberately mutable plain fields rather than a record: there are eighteen of them, all
     * optional, and every test sets two or three. A canonical record constructor would make each test
     * a wall of {@code false, null, ""} that hides the case it is actually about, and a builder would
     * be eighteen methods of ceremony for an internal DTO. Written once by {@link #view}, then read.
     */
    static final class View {
        /** {@code fleet.getBattle() != null} — the mirror never has a battle, so this rides the wire. */
        boolean inBattle;
        /** Whether the fleet has a {@code ModularFleetAI}; false selects {@link #nullAiActionText}. */
        boolean hasAi;
        String nullAiActionText = "";
        boolean hasAssignment;
        FleetAssignment assignment;
        /** {@code FleetAssignmentDataAPI.getActionText()} — null means "derive from the assignment". */
        String assignmentActionText;
        Target assignmentTarget;
        Target tacticalTarget;
        Target largestEnemy;
        Target priorityTarget;
        boolean fleeing;
        boolean busy;
        boolean maintainingContact;
        String actionTextOverride;
        /** Already-invoked {@code FleetActionTextProvider.getActionText(fleet)}. */
        String providerActionText;
        boolean avoidingPlayerHalfheartedly;
        /** Raw {@code $avoidingAbyssalHyperspace}; gates the trailing "looking for" block. */
        boolean abyssalFlag;
        /** {@link #abyssalFlag} and in hyperspace; the "avoiding abyssal hyperspace" text itself. */
        boolean avoidingAbyssalHyperspace;
        float distanceToLargestEnemy;
        /** What the guest calls the host player; substituted wherever vanilla says "your fleet". */
        String hostPlayerLabel = "";
    }
}
