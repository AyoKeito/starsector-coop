package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dormant per-frame watch on the <em>local player's own</em> fleet (S5-B instrumentation, 2026-09-15).
 *
 * <h2>Why it exists</h2>
 * A live smoke reported the guest's own one-ship fleet losing CR (34% &rarr; 21%) and hull
 * (100% &rarr; 87%) between a won battle and six campaign days later, with no storm and no second
 * battle. The mod is not supposed to write CR, hull or cargo on a player's own fleet at all — own
 * fleet is locally authoritative, only mirrors are written — so either vanilla is doing it for a
 * reason the log does not name, or one of our writers is reaching a member it must never reach. This
 * probe answers both halves without a second smoke run:
 *
 * <ul>
 *   <li><b>The vanilla half</b> — every frame, diff the fleet against the previous frame and name
 *       every CR/hull/roster/supply drop, <em>with the engine's own CR-event trail attached</em>
 *       ({@code RepairTrackerAPI.getRecentEvents()} and {@code getNoSupplyCRLossEvent()} are what
 *       vanilla itself shows in the tooltip, so a "no supplies" or "no crew" penalty names itself).
 *       {@link #tick} is off unless {@link CoopDebug#diagnosticsEnabled()}.</li>
 *   <li><b>The mod half</b> — {@link #noteWrite(FleetMemberAPI, String)} is called by every one of
 *       our CR/hull/mothball writers. It costs one field read and one identity compare on the normal
 *       path (the member belongs to a mirror, not to the player fleet) and logs a WARN with a compact
 *       stack when it does not. <b>That guard is live whether or not diagnostics are on</b>: a mod
 *       write to the player's own fleet is a defect, not a measurement, and it must not need a
 *       debug flag to be visible in a user's log.</li>
 * </ul>
 *
 * <h2>Grep markers</h2>
 * <ul>
 *   <li>{@value #DROP_PREFIX} — one line per detected drop (CR, hull, roster, supplies)</li>
 *   <li>{@value #DAY_PREFIX} — once per campaign day: the maintenance drip totals</li>
 *   <li>{@value #SUMMARY_PREFIX} — every {@value #SUMMARY_INTERVAL_MILLIS} ms: date + supplies +
 *       per-member CR/hull, so the drift rate can be read straight off the log</li>
 *   <li>{@value #MOD_WRITE_PREFIX} — WARN: one of our writers touched a player-fleet member</li>
 * </ul>
 *
 * <h2>Shape</h2>
 * The diff is a pure function over two immutable snapshots ({@link #diff(FleetState, FleetState)}),
 * so the whole detection rule is unit-testable with no engine at all. Capture and logging are the
 * only parts that touch the API, and every accessor there is best-effort: a member that throws while
 * being read contributes its zero values rather than taking the frame down.
 */
public final class CoopOwnFleetProbe {

    /** The one instance the pump ticks. */
    public static final CoopOwnFleetProbe INSTANCE = new CoopOwnFleetProbe();

    public static final String DROP_PREFIX = "Coop ownfleet DROP";
    public static final String DAY_PREFIX = "Coop ownfleet DAY";
    public static final String SUMMARY_PREFIX = "Coop ownfleet SUMMARY";
    public static final String MOD_WRITE_PREFIX = "Coop ownfleet MODWRITE";

    /** A CR move smaller than this is wire/float noise, not a drop. */
    public static final float CR_EPSILON = 0.001f;
    /**
     * Hull fraction has no quantization step to hide behind — vanilla never lowers it outside combat
     * — so the threshold only has to clear float comparison noise.
     */
    public static final float HULL_EPSILON = 0.0001f;
    /** A supply move smaller than this is the per-day maintenance drip; counted, not logged. */
    public static final float SUPPLY_EPSILON = 0.5f;

    /** How often the standing summary line is emitted, in real milliseconds. */
    public static final long SUMMARY_INTERVAL_MILLIS = 10_000L;
    /** How many drop events the bridge verb can hand back. */
    public static final int DROP_RING_CAPACITY = 50;
    /** Frames between {@link #MOD_WRITE_PREFIX} WARNs; the count carries the ones in between. */
    private static final long MOD_WRITE_LOG_INTERVAL_MILLIS = 2000L;
    /** Stack frames shown per mod-write WARN. Enough to name the caller and its caller's caller. */
    private static final int MOD_WRITE_STACK_DEPTH = 8;
    /** CR events quoted per member; vanilla keeps only a handful live at a time anyway. */
    private static final int MAX_CR_EVENTS = 6;

    // ---- immutable snapshot model (the pure diff's whole input) ----------------------------------

    /** One ship, as of one frame. Everything the drop line has to carry about a member. */
    public record MemberState(String memberId, String hullId, String variantId, String shipName,
                              float cr, float maxCr, float baseCr, boolean mothballed,
                              boolean crashMothballed, boolean suspendRepairs, float hullFraction,
                              float minCrew, float neededCrew, float crewFraction,
                              float repairRatePerDay, float remainingRepairTime,
                              String crEvents) {
    }

    /** The player fleet, as of one frame. */
    public record FleetState(long realTimeMillis, long campaignTimestamp, String date, int hour,
                             int campaignDay, float supplies, float fuel, int crew,
                             boolean inHyperspace, boolean paused, boolean battleActive,
                             String locationId, List<MemberState> members) {
        public MemberState member(String id) {
            for (MemberState member : members) {
                if (member.memberId().equals(id)) {
                    return member;
                }
            }
            return null;
        }
    }

    /** What kind of drop a {@link Drop} records. */
    public enum Kind { CR, HULL, MEMBER_GONE, MEMBER_NEW, SUPPLIES }

    /**
     * One detected change. {@code member} is the <em>new</em> state for CR/HULL/MEMBER_NEW and the
     * last known state for MEMBER_GONE; it is null only for SUPPLIES, which is a fleet-level fact.
     */
    public record Drop(Kind kind, String memberId, float oldValue, float newValue,
                       MemberState member) {
    }

    // ---- mutable probe state ---------------------------------------------------------------------

    private FleetState previous;
    private long nextSummaryAtMillis;
    private final Deque<String> drops = new ArrayDeque<>();

    /** Per-campaign-day maintenance accounting, reset on the day boundary. */
    private int dripDay = Integer.MIN_VALUE;
    private String dripDayDate = "";
    private float dripSpent;
    private float dripGained;
    private int dripFrames;
    private float dripDayStartSupplies;
    private final Map<String, Float> dripDayStartCr = new LinkedHashMap<>();

    private long modWriteCount;
    private long nextModWriteLogAtMillis;

    private CoopOwnFleetProbe() {
    }

    // ---- the pure half ---------------------------------------------------------------------------

    /**
     * Every drop between two consecutive frames, in a stable order: supplies first (a fleet-level
     * cause), then per-member roster changes, then CR, then hull.
     *
     * <p>Members are paired by id. An id that is present in both frames contributes CR/hull drops; an
     * id only in {@code previous} is MEMBER_GONE; an id only in {@code next} is MEMBER_NEW. Rises are
     * never reported — CR recovering and hull being repaired are what is supposed to happen, and the
     * standing summary line already carries the levels.
     *
     * <p>A drop <em>equal</em> to the epsilon is not a drop: the thresholds are "more than", so the
     * 0.001 wire step ({@code CoopFleetCodec.FRACTION_STEP}) cannot register by itself.
     */
    public static List<Drop> diff(FleetState previous, FleetState next) {
        List<Drop> out = new ArrayList<>();
        if (previous == null || next == null) {
            return out;
        }
        float supplyDrop = previous.supplies() - next.supplies();
        if (supplyDrop > SUPPLY_EPSILON) {
            out.add(new Drop(Kind.SUPPLIES, "", previous.supplies(), next.supplies(), null));
        }
        for (MemberState before : previous.members()) {
            MemberState after = next.member(before.memberId());
            if (after == null) {
                out.add(new Drop(Kind.MEMBER_GONE, before.memberId(), 1f, 0f, before));
            }
        }
        for (MemberState after : next.members()) {
            MemberState before = previous.member(after.memberId());
            if (before == null) {
                out.add(new Drop(Kind.MEMBER_NEW, after.memberId(), 0f, 1f, after));
            }
        }
        for (MemberState after : next.members()) {
            MemberState before = previous.member(after.memberId());
            if (before == null) {
                continue;
            }
            if (before.cr() - after.cr() > CR_EPSILON) {
                out.add(new Drop(Kind.CR, after.memberId(), before.cr(), after.cr(), after));
            }
            if (before.hullFraction() - after.hullFraction() > HULL_EPSILON) {
                out.add(new Drop(Kind.HULL, after.memberId(), before.hullFraction(),
                        after.hullFraction(), after));
            }
        }
        return out;
    }

    /** The per-frame supply move, negative when supplies were spent. Pure, for the drip accounting. */
    public static float supplyDelta(FleetState previous, FleetState next) {
        if (previous == null || next == null) {
            return 0f;
        }
        return next.supplies() - previous.supplies();
    }

    // ---- the engine half -------------------------------------------------------------------------

    /**
     * The pump's call site: the diagnostics gate is checked <em>before</em> {@code Global} is touched
     * at all, so on the normal path (diagnostics off) this is one static boolean read and a return,
     * and a pump unit test that never installs a sector still runs clean.
     */
    public void tickFromGlobal(long nowMillis) {
        if (!CoopDebug.diagnosticsEnabled()) {
            previous = null;
            return;
        }
        SectorAPI sector;
        try {
            sector = Global.getSector();
        } catch (RuntimeException | LinkageError ignored) {
            previous = null;
            return;
        }
        tick(sector, nowMillis);
    }

    /**
     * One frame. Off unless diagnostics are on, and the previous frame is dropped when they are so a
     * mid-session toggle cannot diff against a state from minutes ago and report a phantom cliff.
     *
     * <p>Never throws: the pump's frame body is wrapped, but a diagnostic must not be the thing that
     * costs a frame.
     */
    public void tick(SectorAPI sector, long nowMillis) {
        if (!CoopDebug.diagnosticsEnabled()) {
            previous = null;
            return;
        }
        FleetState now;
        try {
            now = capture(sector, nowMillis);
        } catch (RuntimeException | LinkageError ex) {
            previous = null;
            return;
        }
        if (now == null) {
            previous = null;
            return;
        }
        try {
            FleetState before = previous;
            previous = now;
            if (before != null) {
                for (Drop drop : diff(before, now)) {
                    record(before, now, drop);
                }
                accrueDrip(before, now);
            }
            rollDayIfNeeded(now);
            maybeSummarize(now, nowMillis);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopOwnFleetProbe.class, "Coop ownfleet probe frame failed", ex);
        }
    }

    /** Session edge / campaign load: forget the previous frame and the day accounting. */
    public void reset() {
        previous = null;
        dripDay = Integer.MIN_VALUE;
        dripDayDate = "";
        dripSpent = 0f;
        dripGained = 0f;
        dripFrames = 0;
        dripDayStartSupplies = 0f;
        dripDayStartCr.clear();
        nextSummaryAtMillis = 0L;
    }

    /** The drop ring, oldest first. Empty unless the probe has been ticked with diagnostics on. */
    public List<String> recentDrops() {
        return new ArrayList<>(drops);
    }

    /** The last captured frame, or null. The bridge verb prefers live reads and uses this for context. */
    public FleetState lastState() {
        return previous;
    }

    /** How many times one of our writers has been caught on a player-fleet member this process. */
    public long modWriteCount() {
        return modWriteCount;
    }

    /**
     * Called by every mod path that writes CR, hull or mothball state on a {@link FleetMemberAPI}.
     *
     * <p>On the normal path — the member belongs to a mirror — this is one interface call
     * ({@code getFleetData()}), two field reads and an identity compare, which is nothing beside the
     * write it guards. When the member <em>does</em> belong to the local player's fleet it logs a
     * WARN with a compact call stack, which is the whole point: that is the one thing this mod is not
     * allowed to do, and the stack names the exact writer.
     *
     * <p>Deliberately not gated on {@link CoopDebug#diagnosticsEnabled()} — see the class javadoc.
     */
    public static void noteWrite(FleetMemberAPI member, String reason) {
        if (member == null) {
            return;
        }
        try {
            if (!belongsToPlayerFleet(member)) {
                return;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A member that cannot answer which roster it is in is not evidence of anything.
            return;
        }
        INSTANCE.reportModWrite(member, reason);
    }

    private static boolean belongsToPlayerFleet(FleetMemberAPI member) {
        FleetDataAPI data = member.getFleetData();
        if (data == null) {
            return false;
        }
        CampaignFleetAPI owner = data.getFleet();
        if (owner == null) {
            return false;
        }
        SectorAPI sector = Global.getSector();
        return sector != null && owner == sector.getPlayerFleet();
    }

    private void reportModWrite(FleetMemberAPI member, String reason) {
        modWriteCount++;
        String line;
        try {
            line = MOD_WRITE_PREFIX + " reason=" + reason
                    + " member=" + safeId(member)
                    + " hull=" + safeHullId(member)
                    + " variant=" + safeVariantId(member)
                    + " cr=" + fmt(safeCr(member))
                    + " hull%=" + fmt(safeHullFraction(member))
                    + " count=" + modWriteCount
                    + " at " + compactStack();
        } catch (RuntimeException | LinkageError ex) {
            line = MOD_WRITE_PREFIX + " reason=" + reason + " (member unreadable) count=" + modWriteCount;
        }
        push(line);
        long now = System.currentTimeMillis();
        if (now < nextModWriteLogAtMillis) {
            return;
        }
        nextModWriteLogAtMillis = now + MOD_WRITE_LOG_INTERVAL_MILLIS;
        CoopLog.warn(CoopOwnFleetProbe.class, line);
    }

    /**
     * The current call site, innermost first, with this class's own frames stripped. Built from
     * {@code new Throwable().getStackTrace()} rather than a logged exception so the WARN stays one
     * grep-able line: the point is to name the writer, not to print sixty frames of engine plumbing.
     */
    private static String compactStack() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StringBuilder out = new StringBuilder(160);
        int shown = 0;
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (className.equals(CoopOwnFleetProbe.class.getName())) {
                continue;
            }
            if (shown > 0) {
                out.append(" < ");
            }
            int dot = className.lastIndexOf('.');
            out.append(dot < 0 ? className : className.substring(dot + 1))
                    .append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (++shown >= MOD_WRITE_STACK_DEPTH) {
                break;
            }
        }
        return out.toString();
    }

    // ---- capture ---------------------------------------------------------------------------------

    /** The live player fleet as an immutable snapshot, or null when there is no fleet to watch. */
    public static FleetState capture(SectorAPI sector, long nowMillis) {
        if (sector == null) {
            return null;
        }
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        if (fleet == null) {
            return null;
        }
        CampaignClockAPI clock = sector.getClock();
        String date = "";
        long timestamp = 0L;
        int hour = 0;
        int day = 0;
        if (clock != null) {
            date = clock.getDateString() == null ? "" : clock.getDateString();
            timestamp = clock.getTimestamp();
            hour = clock.getHour();
            day = clock.getCycle() * 10_000 + clock.getMonth() * 100 + clock.getDay();
        }
        CargoAPI cargo = fleet.getCargo();
        float supplies = cargo == null ? 0f : cargo.getSupplies();
        float fuel = cargo == null ? 0f : cargo.getFuel();
        int crew = cargo == null ? 0 : cargo.getTotalCrew();
        LocationAPI location = fleet.getContainingLocation();
        boolean hyper = false;
        try {
            hyper = fleet.isInHyperspace();
        } catch (RuntimeException | LinkageError ignored) {
            // best effort
        }
        boolean paused = false;
        try {
            paused = sector.isPaused();
        } catch (RuntimeException | LinkageError ignored) {
            // best effort
        }
        List<MemberState> members = new ArrayList<>();
        FleetDataAPI data = fleet.getFleetData();
        if (data != null) {
            for (FleetMemberAPI member : data.getMembersListCopy()) {
                if (member == null) {
                    continue;
                }
                members.add(captureMember(member));
            }
        }
        return new FleetState(nowMillis, timestamp, date, hour, day, supplies, fuel, crew,
                hyper, paused, battleActive(), location == null || location.getId() == null
                        ? "" : location.getId(), List.copyOf(members));
    }

    /**
     * Where {@link #battleActive()} reads from. The pump installs the battle bridge's
     * {@code isAnyCoopBattleActive()} at construction; until then (unit tests, no pump) the
     * fallback asks whether a combat engine exists, which is true during a fight but also stays
     * true after a save is loaded (the engine object outlives the battle), so the fallback is only
     * a rough answer.
     */
    private static volatile java.util.function.BooleanSupplier battleSource = null;

    public static void setBattleSource(java.util.function.BooleanSupplier source) {
        battleSource = source;
    }

    /** True while the local client is in a co-op tracked battle (see {@link #setBattleSource}). */
    public static boolean battleActive() {
        java.util.function.BooleanSupplier source = battleSource;
        try {
            if (source != null) {
                return source.getAsBoolean();
            }
            return Global.getCombatEngine() != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static MemberState captureMember(FleetMemberAPI member) {
        RepairTrackerAPI repair = null;
        try {
            repair = member.getRepairTracker();
        } catch (RuntimeException | LinkageError ignored) {
            // best effort
        }
        FleetMemberStatusAPI status = null;
        try {
            status = member.getStatus();
        } catch (RuntimeException | LinkageError ignored) {
            // best effort
        }
        return new MemberState(safeId(member), safeHullId(member), safeVariantId(member),
                safeShipName(member),
                repair == null ? 0f : repair.getCR(),
                repair == null ? 0f : repair.getMaxCR(),
                repair == null ? 0f : repair.getBaseCR(),
                repair != null && repair.isMothballed(),
                repair != null && repair.isCrashMothballed(),
                repair != null && repair.isSuspendRepairs(),
                status == null ? 0f : status.getHullFraction(),
                safeFloat(member::getMinCrew), safeFloat(member::getNeededCrew),
                safeFloat(member::getCrewFraction),
                repair == null ? 0f : repair.getRepairRatePerDay(),
                repair == null ? 0f : repair.getRemainingRepairTime(),
                crEventText(repair));
    }

    /**
     * Vanilla's own CR audit trail, compacted onto one line. This is the field that answers the S5-B
     * question outright: a "no supplies" or "no crew" penalty arrives here with the engine's own
     * label on it, so a vanilla cause and a mod cause are distinguishable at a glance.
     */
    static String crEventText(RepairTrackerAPI repair) {
        if (repair == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(64);
        try {
            RepairTrackerAPI.CREvent noSupply = repair.getNoSupplyCRLossEvent();
            if (noSupply != null) {
                out.append("noSupply[").append(fmt(noSupply.getCrAmount())).append(' ')
                        .append(text(noSupply.getText())).append(']');
            }
        } catch (RuntimeException | LinkageError ignored) {
            // best effort
        }
        try {
            List<RepairTrackerAPI.CREvent> events = repair.getRecentEvents();
            int shown = 0;
            if (events != null) {
                for (RepairTrackerAPI.CREvent event : events) {
                    if (event == null) {
                        continue;
                    }
                    if (out.length() > 0) {
                        out.append(' ');
                    }
                    out.append(text(event.id)).append('=').append(fmt(event.getCrAmount()))
                            .append('@').append(fmt(event.getElapsed()));
                    if (++shown >= MAX_CR_EVENTS) {
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // best effort
        }
        return out.toString();
    }

    // ---- logging ---------------------------------------------------------------------------------

    private void record(FleetState before, FleetState now, Drop drop) {
        String line = formatDrop(before, now, drop);
        push(line);
        CoopLog.info(CoopOwnFleetProbe.class, line);
    }

    /** The drop line. Package-private so a test can assert the fields are all present. */
    static String formatDrop(FleetState before, FleetState now, Drop drop) {
        StringBuilder out = new StringBuilder(320);
        out.append(DROP_PREFIX).append(' ').append(drop.kind())
                .append(" date=").append(now.date()).append(" hour=").append(now.hour())
                .append(" dtMs=").append(now.realTimeMillis() - before.realTimeMillis());
        MemberState member = drop.member();
        if (member != null) {
            out.append(" member=").append(member.memberId())
                    .append(" hull=").append(member.hullId())
                    .append(" variant=").append(member.variantId())
                    .append(" name=").append(text(member.shipName()));
        } else if (!drop.memberId().isEmpty()) {
            out.append(" member=").append(drop.memberId());
        }
        switch (drop.kind()) {
            case CR -> out.append(" cr=").append(fmt(drop.oldValue()))
                    .append("->").append(fmt(drop.newValue()));
            case HULL -> out.append(" hull=").append(fmt(drop.oldValue()))
                    .append("->").append(fmt(drop.newValue()));
            case SUPPLIES -> out.append(" supplies=").append(fmt(drop.oldValue()))
                    .append("->").append(fmt(drop.newValue()));
            default -> out.append(" roster=").append(before.members().size())
                    .append("->").append(now.members().size());
        }
        if (member != null) {
            out.append(" maxCR=").append(fmt(member.maxCr()))
                    .append(" baseCR=").append(fmt(member.baseCr()))
                    .append(" mothballed=").append(member.mothballed())
                    .append(" crashMothballed=").append(member.crashMothballed())
                    .append(" suspendRepairs=").append(member.suspendRepairs())
                    .append(" hullFrac=").append(fmt(member.hullFraction()))
                    .append(" minCrew=").append(fmt(member.minCrew()))
                    .append(" neededCrew=").append(fmt(member.neededCrew()))
                    .append(" crewFrac=").append(fmt(member.crewFraction()))
                    .append(" repairRate=").append(fmt(member.repairRatePerDay()))
                    .append(" repairLeft=").append(fmt(member.remainingRepairTime()))
                    .append(" crEvents=[").append(member.crEvents()).append(']');
        }
        out.append(" supplies=").append(fmt(now.supplies()))
                .append(" fuel=").append(fmt(now.fuel()))
                .append(" crew=").append(now.crew())
                .append(" fleetMinCrew=").append(fmt(fleetMinCrew(now)))
                .append(" hyper=").append(now.inHyperspace())
                .append(" paused=").append(now.paused())
                .append(" battle=").append(now.battleActive())
                .append(" loc=").append(now.locationId());
        return out.toString();
    }

    static float fleetMinCrew(FleetState state) {
        float total = 0f;
        for (MemberState member : state.members()) {
            total += member.minCrew();
        }
        return total;
    }

    private void accrueDrip(FleetState before, FleetState now) {
        float delta = supplyDelta(before, now);
        if (delta < 0f) {
            dripSpent += -delta;
        } else if (delta > 0f) {
            dripGained += delta;
        }
        dripFrames++;
    }

    /**
     * The once-per-campaign-day maintenance summary. This is where the ordinary drip goes: every
     * frame's supply move is counted, and the day boundary prints the totals with the day's net CR
     * movement beside them, so "the fleet is simply eating its supplies" and "something took 13
     * points of CR in one step" are never confused for one another.
     */
    private void rollDayIfNeeded(FleetState now) {
        if (dripDay == Integer.MIN_VALUE) {
            startDay(now);
            return;
        }
        if (now.campaignDay() == dripDay) {
            return;
        }
        StringBuilder out = new StringBuilder(200);
        out.append(DAY_PREFIX).append(' ').append(dripDayDate)
                .append(" spent=").append(fmt(dripSpent))
                .append(" gained=").append(fmt(dripGained))
                .append(" netSupplies=").append(fmt(now.supplies() - dripDayStartSupplies))
                .append(" supplies=").append(fmt(now.supplies()))
                .append(" frames=").append(dripFrames);
        for (MemberState member : now.members()) {
            Float started = dripDayStartCr.get(member.memberId());
            out.append(' ').append(member.memberId()).append(":cr=")
                    .append(started == null ? "?" : fmt(started))
                    .append("->").append(fmt(member.cr()))
                    .append(" hull=").append(fmt(member.hullFraction()));
        }
        CoopLog.info(CoopOwnFleetProbe.class, out.toString());
        startDay(now);
    }

    private void startDay(FleetState now) {
        dripDay = now.campaignDay();
        dripDayDate = now.date();
        dripSpent = 0f;
        dripGained = 0f;
        dripFrames = 0;
        dripDayStartSupplies = now.supplies();
        dripDayStartCr.clear();
        for (MemberState member : now.members()) {
            dripDayStartCr.put(member.memberId(), member.cr());
        }
    }

    private void maybeSummarize(FleetState now, long nowMillis) {
        if (nowMillis < nextSummaryAtMillis) {
            return;
        }
        nextSummaryAtMillis = nowMillis + SUMMARY_INTERVAL_MILLIS;
        StringBuilder out = new StringBuilder(200);
        out.append(SUMMARY_PREFIX).append(' ').append(now.date()).append(" hour=").append(now.hour())
                .append(" supplies=").append(fmt(now.supplies()))
                .append(" fuel=").append(fmt(now.fuel()))
                .append(" crew=").append(now.crew())
                .append(" hyper=").append(now.inHyperspace())
                .append(" paused=").append(now.paused())
                .append(" battle=").append(now.battleActive())
                .append(" loc=").append(now.locationId());
        for (MemberState member : now.members()) {
            out.append(' ').append(member.memberId())
                    .append('[').append(member.hullId()).append(']')
                    .append(" cr=").append(fmt(member.cr()))
                    .append('/').append(fmt(member.maxCr()))
                    .append(" hull=").append(fmt(member.hullFraction()))
                    .append(member.mothballed() ? " MOTHBALLED" : "")
                    .append(member.crashMothballed() ? " CRASH" : "");
        }
        CoopLog.info(CoopOwnFleetProbe.class, out.toString());
    }

    private void push(String line) {
        drops.addLast(line);
        while (drops.size() > DROP_RING_CAPACITY) {
            drops.removeFirst();
        }
    }

    // ---- best-effort accessors -------------------------------------------------------------------

    private interface FloatRead {
        float read();
    }

    private static float safeFloat(FloatRead read) {
        try {
            return read.read();
        } catch (RuntimeException | LinkageError ignored) {
            return 0f;
        }
    }

    static String safeId(FleetMemberAPI member) {
        try {
            return member.getId() == null ? "" : member.getId();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    static String safeHullId(FleetMemberAPI member) {
        try {
            return member.getHullId() == null ? "" : member.getHullId();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    static String safeVariantId(FleetMemberAPI member) {
        try {
            ShipVariantAPI variant = member.getVariant();
            String id = variant == null ? null : variant.getHullVariantId();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    static String safeShipName(FleetMemberAPI member) {
        try {
            return member.getShipName() == null ? "" : member.getShipName();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static float safeCr(FleetMemberAPI member) {
        RepairTrackerAPI repair = member.getRepairTracker();
        return repair == null ? 0f : repair.getCR();
    }

    private static float safeHullFraction(FleetMemberAPI member) {
        FleetMemberStatusAPI status = member.getStatus();
        return status == null ? 0f : status.getHullFraction();
    }

    /** Four decimals, {@code Locale.ROOT}: the same format the rest of the mod's diagnostics use. */
    static String fmt(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** Spaces would break the one-line grep shape, so they become underscores. */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.replace(' ', '_');
    }
}
