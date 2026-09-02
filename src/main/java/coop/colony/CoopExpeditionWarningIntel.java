package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 24 milestone 3: the guest's mirrored warning that an NPC force is coming for a player colony.
 * The mod's first custom intel plugin.
 *
 * <p><b>Why this exists.</b> Expeditions, inspections and colony-crisis attacks are simulated
 * host-side only — the guest's {@code PunitiveExpeditionManager} and the route machinery behind them
 * are on the Phase 13 suppressor's list — so the vanilla countdown intel is host-local. The decision
 * (2026-06-10) was to mirror the warning <em>data</em> into a coop-owned intel entry rather than build
 * generic intel replication, which would mean serialising arbitrary vanilla intel graphs across the
 * wire. This class is that entry: a handful of strings and an int, fed by {@code EXPEDITION_WARNING}.
 *
 * <p><b>Save shape.</b> Every field is a primitive or a {@code String} on purpose. The intel object
 * lands in the guest's save through XStream, which does not run constructors or field initialisers on
 * load, so anything richer would be one refactor away from breaking existing saves. The enums the
 * wire uses are stored by name for the same reason: an enum field serialises by constant name, and a
 * rename would break loads. {@link #kind()} and {@link #status()} re-parse defensively.
 *
 * <p><b>Lifecycle safety.</b> The entry must not survive as a stale countdown in a save that is later
 * loaded solo, so it expires itself: {@link #advanceImpl} ends it once
 * {@link #STALE_DAYS} of campaign time pass without a coop session touching it. A live session
 * refreshes every mirrored entry on each reconcile pass, several times a minute, so the timer only
 * ever runs out when there is nobody left to refresh it. Session teardown clears the entries
 * outright; the timer is the backstop for the case teardown never happened (a crash, a save taken
 * mid-session and loaded alone).
 */
public class CoopExpeditionWarningIntel extends BaseIntelPlugin {

    /**
     * In-game days without a coop update before the entry ends itself. Ten days is far longer than
     * any gap a live session can produce (the reconcile runs every five seconds of real time) and far
     * shorter than a player would tolerate a frozen countdown for.
     */
    public static final float STALE_DAYS = 10f;

    /**
     * The {@code listInfoParam} marker for the "it is here" update, the same shape vanilla uses
     * ({@code RaidIntel.ENTERED_SYSTEM_UPDATE}, {@code PunitiveExpeditionIntel.ENTERED_SYSTEM_UPDATE}).
     * Static, so it is not serialised, and identity-only: nothing reads its contents.
     */
    public static final Object ARRIVED_UPDATE = new Object();

    private String kindName;
    private String factionId;
    private String targetMarketId;
    private String targetName;
    private int etaDays;
    private String statusName;
    /**
     * Display text for the threat's objective, already resolved host-side ("saturation bombardment",
     * "raid to disrupt Heavy Industry", "expedition"). Empty when the host could not resolve one, in
     * which case the line is omitted rather than rendered as "unknown".
     */
    private String goal;
    /** Campaign-clock timestamp of the last coop update; {@code getElapsedDaysSince} reads it. */
    private long lastTouchedTimestamp;
    /**
     * Whether the one-time {@code setImportant(true)} has been applied. Deserialises as {@code false}
     * on entries saved before the flag existed, so {@link #update} migrates those exactly once —
     * after which the player's own star/unstar choice is never overridden again.
     */
    private boolean importantApplied;

    public CoopExpeditionWarningIntel(CoopExpeditionWarning warning) {
        assign(warning);
        // Vanilla flags its counterpart important at construction (PunitiveExpeditionIntel.java:121).
        // Matched here once, never re-applied on update, so the player stays free to unstar it.
        setImportant(true);
        importantApplied = true;
        // BaseIntelPlugin does not register itself as a script, and without one advanceImpl never
        // runs, so the self-expire would never fire. Vanilla's own intel does exactly this
        // (RaidIntel.java:87, FleetGroupIntel.java:100) and removes it again in notifyEnded().
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            sector.addScript(this);
        }
    }

    /**
     * Overwrites the mirrored values and refreshes the staleness timer, and sends the player an
     * update when the threat has arrived.
     *
     * <p><b>Why the update matters beyond the notification.</b> An intel entry the player has clicked
     * once loses its {@code New} tag permanently ({@code BaseIntelPlugin.reportPlayerClickedOn} nulls
     * {@code neverClicked}), and the intel screen's category filter is persisted in the save. A player
     * whose filter is set to {@code New} therefore stops seeing a read entry the moment the list is
     * rebuilt — on the next screen open, or on the next load. That is vanilla behaviour, and vanilla's
     * only answer to it is the message feed: every vanilla threat intel pushes an update when its
     * stage moves ({@code PunitiveExpeditionIntel.notifyEnteredSystem} sends
     * {@code ENTERED_SYSTEM_UPDATE}). A silently mutating mirror has no such moment, which is what the
     * 2026-09-01 smoke ran into. The mirrored set only carries one stage change — the arrival — so
     * that is the one this sends.
     */
    public final void update(CoopExpeditionWarning warning) {
        if (warning == null) {
            return;
        }
        CoopExpeditionWarning.Status before = status();
        assign(warning);
        if (!importantApplied) {
            setImportant(true);
            importantApplied = true;
        }
        if (announcesArrival(before, status())) {
            announceArrival();
        }
    }

    /**
     * The pure half of the update-message decision. Only the arrival is worth the player's attention:
     * a countdown ticking down is not an event, and a host-side re-estimate that puts an arrived
     * threat back to inbound must not re-announce.
     */
    static boolean announcesArrival(CoopExpeditionWarning.Status before,
                                    CoopExpeditionWarning.Status after) {
        return before != CoopExpeditionWarning.Status.ARRIVED
                && after == CoopExpeditionWarning.Status.ARRIVED;
    }

    /**
     * Vanilla's own channel: {@code sendUpdateIfPlayerHasIntel} is a no-op until the entry is
     * player-visible, so this cannot fire for an entry that never made it into the intel manager.
     */
    private void announceArrival() {
        try {
            sendUpdateIfPlayerHasIntel(ARRIVED_UPDATE, false);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopExpeditionWarningIntel.class,
                    "Failed to announce a coop expedition warning arrival", ex);
        }
    }

    /**
     * Refreshes the staleness timer without changing anything else. Called for every entry on every
     * reconcile pass, including the ones the host's set did not move.
     */
    public final void touch() {
        lastTouchedTimestamp = clockTimestamp();
    }

    private void assign(CoopExpeditionWarning warning) {
        if (warning == null) {
            return;
        }
        this.kindName = warning.kind().name();
        this.factionId = warning.factionId();
        this.targetMarketId = warning.targetMarketId();
        this.targetName = warning.targetName();
        this.etaDays = warning.etaDays();
        this.statusName = warning.status().name();
        this.goal = warning.goal();
        this.lastTouchedTimestamp = clockTimestamp();
    }

    /** The record this entry mirrors, for the reconcile's local-set read. */
    public CoopExpeditionWarning toRecord() {
        return new CoopExpeditionWarning(kind(), factionId, targetMarketId, targetName, etaDays,
                status(), goalText());
    }

    /** Never null: a save written before the field existed loads it as null. */
    public String goalText() {
        return goal == null ? "" : goal;
    }

    public CoopExpeditionWarning.Kind kind() {
        return parseKind(kindName);
    }

    public CoopExpeditionWarning.Status status() {
        return parseStatus(statusName);
    }

    public String targetMarketId() {
        return targetMarketId == null ? "" : targetMarketId;
    }

    static CoopExpeditionWarning.Kind parseKind(String raw) {
        if (raw == null) {
            return CoopExpeditionWarning.Kind.RAID;
        }
        try {
            return CoopExpeditionWarning.Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return CoopExpeditionWarning.Kind.RAID;
        }
    }

    static CoopExpeditionWarning.Status parseStatus(String raw) {
        if (raw == null) {
            return CoopExpeditionWarning.Status.INBOUND;
        }
        try {
            return CoopExpeditionWarning.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return CoopExpeditionWarning.Status.INBOUND;
        }
    }

    // ---- Self-expire ---------------------------------------------------------------------------

    /**
     * The pure half of the lifecycle decision, so the rule is testable without a campaign clock.
     * A negative elapsed reading (a clock that moved backwards across a load) is not a reason to end
     * anything.
     */
    public static boolean shouldSelfExpire(float daysSinceTouch) {
        return daysSinceTouch >= STALE_DAYS;
    }

    @Override
    protected void advanceImpl(float amount) {
        if (isEnding() || isEnded()) {
            return;
        }
        try {
            if (lastTouchedTimestamp == 0L) {
                // Loaded from a save written before the field existed, or never touched. Start the
                // clock now rather than expiring instantly.
                lastTouchedTimestamp = clockTimestamp();
                return;
            }
            if (shouldSelfExpire(daysSinceTouched())) {
                CoopLog.info(CoopExpeditionWarningIntel.class,
                        "Coop expedition warning for " + targetMarketId() + " expired: no session"
                                + " update in " + STALE_DAYS + " days");
                endImmediately();
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopExpeditionWarningIntel.class,
                    "Failed to advance a coop expedition warning; ending it", ex);
            endImmediately();
        }
    }

    float daysSinceTouched() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getClock() == null) {
            return 0f;
        }
        return sector.getClock().getElapsedDaysSince(lastTouchedTimestamp);
    }

    private static long clockTimestamp() {
        try {
            SectorAPI sector = Global.getSector();
            return sector == null || sector.getClock() == null ? 0L : sector.getClock().getTimestamp();
        } catch (RuntimeException | LinkageError ex) {
            return 0L;
        }
    }

    @Override
    protected void notifyEnded() {
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            sector.removeScript(this);
        }
    }

    // ---- Rendering -----------------------------------------------------------------------------

    @Override
    public String getName() {
        String kind = switch (kind()) {
            case PUNITIVE_EXPEDITION -> "Punitive Expedition";
            case INSPECTION -> "Inspection";
            case HOSTILE_ACTIVITY -> "Hostile Fleets";
            case RAID -> "Raid";
        };
        String faction = factionDisplayName();
        String prefix = faction.isEmpty() ? kind : faction + " " + kind;
        String target = targetDisplayName();
        return target.isEmpty() ? prefix : prefix + " - " + target;
    }

    /**
     * The intel-list bullets. Highlighted exactly where vanilla highlights: the target colony in the
     * owning faction's UI colour ({@code RaidIntel.java:334}, {@code PunitiveExpeditionIntel.java:283}),
     * the goal in the negative-highlight colour ({@code PunitiveExpeditionIntel.java:289}) and the day
     * count in the standard highlight colour ({@code RaidIntel.java:357}). Vanilla pads the first
     * bullet and zeroes the rest, so this does too.
     */
    @Override
    protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode, boolean isUpdate,
                                   Color tc, float initPad) {
        float pad = initPad;
        String target = targetDisplayName();
        if (!target.isEmpty()) {
            info.addPara("Target: %s", pad, tc, targetHighlightColor(), target);
            pad = 0f;
        }
        String goal = goalText();
        if (!goal.isEmpty()) {
            info.addPara("Goal: %s", pad, tc, goalHighlightColor(), goal);
            pad = 0f;
        }
        String etaHighlight = etaHighlight();
        if (etaHighlight.isEmpty()) {
            info.addPara(etaLine(), tc, pad);
        } else {
            info.addPara(etaFormat(), pad, tc, Misc.getHighlightColor(), etaHighlight);
        }
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        info.addPara("Reported by your partner's client. The attacking force is simulated on their"
                + " machine and its fleets are mirrored to yours.", pad);
        String faction = factionDisplayName();
        if (!faction.isEmpty()) {
            info.addPara("Faction: %s", pad, factionHighlightColor(), faction);
        }
        String target = targetDisplayName();
        if (!target.isEmpty()) {
            info.addPara("Target colony: %s", pad, targetHighlightColor(), target);
        }
        String goal = goalText();
        if (!goal.isEmpty()) {
            info.addPara("Goal: %s", pad, goalHighlightColor(), goal);
        }
        String etaHighlight = etaHighlight();
        if (etaHighlight.isEmpty()) {
            info.addPara(etaLine(), pad);
        } else {
            info.addPara(etaFormat(), pad, Misc.getHighlightColor(), etaHighlight);
        }
    }

    /**
     * The ETA sentence with a {@code %s} where the day count goes, so the count can be highlighted.
     * ASCII only, and singular/plural handled: this is player-facing text.
     */
    String etaFormat() {
        if (status() == CoopExpeditionWarning.Status.ARRIVED) {
            return "Status: in the target system now.";
        }
        if (etaDays <= 0) {
            return "Status: arriving imminently.";
        }
        return "Estimated arrival: %s" + (etaDays == 1 ? " day." : " days.");
    }

    /** The substring of {@link #etaFormat()} to highlight, or empty when the line has no variable. */
    String etaHighlight() {
        if (status() == CoopExpeditionWarning.Status.ARRIVED || etaDays <= 0) {
            return "";
        }
        return String.valueOf(etaDays);
    }

    /** The fully rendered ETA sentence, for the paths that cannot highlight. */
    String etaLine() {
        String highlight = etaHighlight();
        return highlight.isEmpty() ? etaFormat() : etaFormat().replace("%s", highlight);
    }

    /**
     * The threatened colony's colour: the owning faction's, which is what both vanilla hierarchies
     * use for a target name. Falls back to the plain highlight colour when this engine cannot resolve
     * the market (the guest normally can — it mirrors player colonies).
     */
    private Color targetHighlightColor() {
        try {
            MarketAPI market = targetMarket();
            FactionAPI owner = market == null ? null : market.getFaction();
            Color color = owner == null ? null : owner.getBaseUIColor();
            return color == null ? Misc.getHighlightColor() : color;
        } catch (RuntimeException | LinkageError ex) {
            return Misc.getHighlightColor();
        }
    }

    private Color factionHighlightColor() {
        try {
            FactionAPI faction = threatFaction();
            Color color = faction == null ? null : faction.getBaseUIColor();
            return color == null ? Misc.getHighlightColor() : color;
        } catch (RuntimeException | LinkageError ex) {
            return Misc.getHighlightColor();
        }
    }

    /**
     * Vanilla only ever bullets a "Goal:" line for a saturation bombardment, and paints it with the
     * negative-highlight colour; every goal this entry shows is an attack on the player's colony, so
     * they all get that treatment.
     */
    private Color goalHighlightColor() {
        try {
            return Misc.getNegativeHighlightColor();
        } catch (RuntimeException | LinkageError ex) {
            return Misc.getHighlightColor();
        }
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(Tags.INTEL_MILITARY);
        tags.add(Tags.INTEL_COLONIES);
        // Phase 20.6: the intel screen builds its filter list from the tags registered entries carry,
        // so sharing this one puts every coop-owned entry behind a single "Coop" tab. The vanilla
        // tags stay: a colony threat still belongs under Military and Colonies.
        tags.add(coop.ui.CoopSessionIntel.TAG_COOP);
        if (factionId != null && !factionId.isEmpty()) {
            tags.add(factionId);
        }
        return tags;
    }

    @Override
    public String getSortString() {
        return "Colony Threat";
    }

    /**
     * A faction crest, which is the safest possible icon: it is always a loaded sprite, and it is what
     * both vanilla raid hierarchies use ({@code RaidIntel.java:504},
     * {@code FleetGroupIntel.java:970}). Falls back to {@code null}, which the intel contract
     * explicitly allows — "40x40, no icon if null" — rather than to a sprite key that might not exist.
     */
    @Override
    public String getIcon() {
        try {
            FactionAPI faction = threatFaction();
            String crest = faction == null ? null : faction.getCrest();
            return crest == null || crest.isEmpty() ? null : crest;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        FactionAPI faction = threatFaction();
        return faction == null ? super.getFactionForUIColors() : faction;
    }

    /** Puts the entry on the map at the threatened colony, when this engine can find it. */
    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        MarketAPI market = targetMarket();
        return market == null ? null : market.getPrimaryEntity();
    }

    private FactionAPI threatFaction() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || factionId == null || factionId.isEmpty()) {
                return null;
            }
            return sector.getFaction(factionId);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private String factionDisplayName() {
        FactionAPI faction = threatFaction();
        if (faction == null) {
            return factionId == null ? "" : factionId;
        }
        String name = faction.getDisplayName();
        return name == null ? "" : name;
    }

    /**
     * The colony's name. Prefers this engine's live market — the guest mirrors player colonies, so it
     * normally has one — and falls back to the name the host put on the wire.
     */
    private String targetDisplayName() {
        MarketAPI market = targetMarket();
        if (market != null && market.getName() != null && !market.getName().isEmpty()) {
            return market.getName();
        }
        return targetName == null ? "" : targetName;
    }

    private MarketAPI targetMarket() {
        try {
            if (targetMarketId == null || targetMarketId.isEmpty()) {
                return null;
            }
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getEconomy() == null) {
                return null;
            }
            return sector.getEconomy().getMarket(targetMarketId);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
