package coop.campaign;

import java.util.Objects;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CharacterDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

import coop.util.CoopLog;

/**
 * Phase 32: mirrors the host's faction commission onto the guest, one memory key wide.
 *
 * <p>The military submarket's per-item legality asks {@code hasCommission()}, which is nothing more
 * than {@code submarket.getFaction().getId().equals(Misc.getCommissionFactionId())}
 * ({@code MilitarySubmarketPlugin.java:114-116}), and {@code Misc.getCommissionFactionId} reads the
 * string {@code MemFlags.FCM_FACTION} — {@code "$fcm_faction"}, {@code MemFlags.java:116} — off
 * {@code Global.getSector().getCharacterData().getMemoryWithoutUpdate()}
 * ({@code Misc.java:2419-2421}). That is the same object and key
 * {@code FactionCommissionIntel.missionAccepted} sets and {@code endMission} unsets
 * ({@code FactionCommissionIntel.java:107}, {@code :120}). Writing it on the guest is therefore the
 * complete fix for the one clause of shared-submarket access that diverges between the engines.
 *
 * <p><b>The guest never gets a {@code FactionCommissionIntel}, and the mod enforces that.</b> This
 * used to be asserted here and left to good behaviour; nothing stopped a guest docking at any
 * faction market and signing one through vanilla (red-team P1-5). It now cannot: the mod's
 * {@code data/campaign/rules.csv} replaces vanilla's {@code cmsn_askForCommissionOpt} and
 * {@code cmsn_resignCommissionOpt} rows — the only two {@code PopulateOptions} entries that offer
 * {@code cmsn_askCommission} and {@code cmsn_resignCommission} — with copies carrying
 * {@link CoopStoryChainGate#GUEST_RULE_CONDITION}, exactly the mechanism the Galatia Academy chain
 * is gated with. Resigning is gated for the same reason as signing: the flag this class writes makes
 * {@code Commission hasFactionCommission} true on the guest, so vanilla would have offered a guest
 * the option to resign a commission that belongs to the host's intel, and unset the mirrored key.
 *
 * <p>The intel object is the thing
 * that runs the commission: it is registered as a sector script and a listener, it pays the monthly
 * salary, it posts and settles commission bounties, and it undoes its own reputation changes when
 * the commission ends. A second copy on the guest would pay a second salary out of a wallet the host
 * already paid into, and terminate twice. So this class deliberately mirrors only the flag vanilla
 * <em>reads</em>, not the machinery that writes it; the commission itself, its salary and its
 * bounties stay host-side, which is the accepted limitation the plan records. The companion key
 * {@code MemFlags.FCM_EVENT} (the intel instance itself) is likewise never written on the guest —
 * there is no instance to put there, and nothing in the submarket access path looks at it.
 *
 * <p>Host-only in one more sense: {@link CoopWorldDelta.Kind#COMMISSION} is
 * {@link CoopWorldDelta.Kind#hostOnly() host-only}, so a guest-originated one is refused on arrival
 * and this class's apply path only ever runs on a guest.
 */
public final class CoopCommissionSync {

    /** How often the host re-reads the commission. One second, as the plan specifies. */
    public static final long POLL_INTERVAL_MILLIS = 1000L;

    /**
     * The delta's entity id. The commission is one per campaign rather than per world entity, so the
     * (kind, entity) ledger key needs a constant to hang off.
     *
     * <p>Namespaced, and deliberately (red-team P2-9). {@code CoopWorldDelta.Ledger.apply}
     * short-circuits any non-consuming delta whose <em>raw</em> entity id is already in
     * {@code consumedEntityIds}, a set keyed by unprefixed engine ids. The old constant was
     * {@code "player"}: one consuming delta ever recorded against an entity literally named
     * {@code player} would have frozen the commission mirror for the rest of the session. Nothing
     * produces such an id today ({@code consumeKeyIfTracked} only yields salvageable ids), but this
     * was the one un-namespaced entity id in the whole delta vocabulary.
     */
    public static final String ENTITY_ID = "coop:commission";

    /** The memory key vanilla reads. {@code MemFlags.FCM_FACTION}, restated for the log/tests. */
    public static final String MEMORY_KEY = MemFlags.FCM_FACTION;

    /** What the mirror reads and writes, so the decision logic is testable without a sector. */
    public interface Engine {

        /** The local commission's faction id, or null/empty when there is none. */
        String commissionFactionId();

        /** Writes the faction id, or clears the key when {@code factionId} is empty. */
        void writeCommissionFactionId(String factionId);
    }

    private final Engine engine;
    private String lastSeen;
    private boolean seeded;
    private long lastPollMillis;
    private boolean pollSeeded;
    private boolean lastPollWasSessionBaseline;

    /**
     * True when <em>this</em> engine wrote {@link #MEMORY_KEY} from a remote delta and has not
     * cleared it since. Deliberately not reset by {@link #reset()}: it describes a write that landed
     * in the campaign's character memory, not a field of this session, and {@link #clearMirrored()}
     * is what takes both back down together.
     */
    private boolean mirrored;

    public CoopCommissionSync(Engine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Forgets the poll timer and the last value seen. Called on session teardown. */
    public void reset() {
        lastSeen = null;
        seeded = false;
        lastPollMillis = 0L;
        pollSeeded = false;
        lastPollWasSessionBaseline = false;
    }

    /**
     * The host's poll, throttled to {@link #POLL_INTERVAL_MILLIS}.
     *
     * <p>The first poll of a session reports whatever it reads, which is the plan's "send the
     * current value once per session after the session becomes active": a guest joining a campaign
     * where the commission was signed months ago has no other way to learn about it, since the
     * change it would otherwise wait for happened before it connected.
     *
     * <p><b>Including the empty one.</b> This used to stay silent when the session started with no
     * commission, on the argument that "no commission" is the state a guest is in already. It is
     * not, in the two cases that matter (red-team P1-5). A guest is <em>not</em> in that state when
     * it is carrying a mirrored value from before a reconnect, and the host's commission may have
     * ended while it was gone — {@link CoopCampaignReplicator}'s forced rebroadcast re-arms this
     * poll on a resume, and the empty payload it now sends is exactly what corrects that. Nor is it
     * in that state when it has somehow written {@code $fcm_faction} on its own. One
     * {@code WORLD_DELTA} per session, ~120 bytes, buys both.
     *
     * @return the payload to send — a faction id, or {@code ""} for no commission — or null when
     *         there is nothing to send
     */
    public String poll(long nowMillis) {
        if (pollSeeded && nowMillis - lastPollMillis < POLL_INTERVAL_MILLIS) {
            return null;
        }
        pollSeeded = true;
        lastPollMillis = nowMillis;
        String current = normalize(engine.commissionFactionId());
        if (seeded && current.equals(lastSeen)) {
            lastPollWasSessionBaseline = false;
            return null;
        }
        lastPollWasSessionBaseline = !seeded;
        seeded = true;
        lastSeen = current;
        return current;
    }

    /**
     * Whether the value the last {@link #poll(long)} returned was this session's first, and so must
     * be sent even though the world ledger may already hold that payload from before a reconnect.
     * Read immediately after {@code poll}; any later poll overwrites it.
     */
    public boolean lastPollWasSessionBaseline() {
        return lastPollWasSessionBaseline;
    }

    /** Guest: write (or clear) the key vanilla reads. See the class javadoc for what is not done. */
    public void applyRemote(String factionId) {
        String normalized = normalize(factionId);
        engine.writeCommissionFactionId(normalized);
        // Recorded on the applying side too, so a guest that is later promoted or that starts its own
        // poll does not re-report a value it was handed.
        seeded = true;
        lastSeen = normalized;
        // An empty payload is the host's commission ending: the key is now unset, so there is
        // nothing left for the teardown to take back down.
        mirrored = !normalized.isEmpty();
        CoopLog.info(CoopCommissionSync.class,
                "Coop applied COMMISSION faction=" + describe(normalized));
    }

    /**
     * Session teardown: unsets {@link #MEMORY_KEY} when this engine is the one that wrote it.
     *
     * <p><b>Why this is not optional.</b> {@code $fcm_faction} lives in
     * {@code getCharacterData().getMemoryWithoutUpdate()}, which is saved with the game. A guest that
     * mirrored the host's commission and then ended the session would carry that faction's
     * military-submarket access, commission rules and commission dialogue into every later save
     * forever, with no path back: the guest never gets a {@code FactionCommissionIntel} to end (see
     * the class javadoc), and the host's poll is silent about a commission that was already over
     * before the next session started. Same argument, same shape and the same teardown as
     * {@code clearMirroredExpeditionWarnings} — coop-owned state has no meaning outside a session.
     *
     * <p>The {@link #mirrored} guard is the whole point: a guest that signed its own commission in
     * its own campaign never had one mirrored onto it and must not have it torn off.
     *
     * @return true when the key was actually unset
     */
    public boolean clearMirrored() {
        if (!mirrored) {
            return false;
        }
        mirrored = false;
        engine.writeCommissionFactionId("");
        CoopLog.info(CoopCommissionSync.class,
                "Coop cleared mirrored COMMISSION " + MEMORY_KEY + " on teardown");
        return true;
    }

    /** A faction id for a log line, or {@code "none"} for the empty payload. */
    public static String describe(String factionId) {
        String normalized = normalize(factionId);
        return normalized.isEmpty() ? "none" : normalized;
    }

    /** Null and blank both mean "no commission", and must compare equal. */
    private static String normalize(String factionId) {
        return factionId == null ? "" : factionId.trim();
    }

    /** The engine seam wired to {@code Global}. */
    public static Engine liveEngine() {
        return new Engine() {
            @Override
            public String commissionFactionId() {
                try {
                    // Vanilla's own accessor, deliberately: if the engine ever moves where the value
                    // lives, the read follows it and only the write below needs revisiting.
                    return Global.getSector() == null ? null : Misc.getCommissionFactionId();
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopCommissionSync.class, "Could not read the commission faction", ex);
                    return null;
                }
            }

            @Override
            public void writeCommissionFactionId(String factionId) {
                MemoryAPI memory = characterMemory();
                if (memory == null) {
                    CoopLog.warn(CoopCommissionSync.class,
                            "Coop COMMISSION dropped: no character memory to write " + MEMORY_KEY);
                    return;
                }
                if (factionId == null || factionId.isEmpty()) {
                    memory.unset(MEMORY_KEY);
                } else {
                    memory.set(MEMORY_KEY, factionId);
                }
            }
        };
    }

    /** {@code getCharacterData().getMemoryWithoutUpdate()}, null-safe at every hop. */
    static MemoryAPI characterMemory() {
        try {
            SectorAPI sector = Global.getSector();
            CharacterDataAPI character = sector == null ? null : sector.getCharacterData();
            return character == null ? null : character.getMemoryWithoutUpdate();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCommissionSync.class, "Could not read character memory", ex);
            return null;
        }
    }
}
