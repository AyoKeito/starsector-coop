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
 * <p><b>The guest never gets a {@code FactionCommissionIntel}.</b> The intel object is the thing
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
     * (kind, entity) ledger key needs a constant to hang off; "player" is who it belongs to.
     */
    public static final String ENTITY_ID = "player";

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
    }

    /**
     * The host's poll, throttled to {@link #POLL_INTERVAL_MILLIS}.
     *
     * <p>The first poll of a session reports whatever it reads, which is the plan's "send the
     * current value once per session after the session becomes active": a guest joining a campaign
     * where the commission was signed months ago has no other way to learn about it, since the
     * change it would otherwise wait for happened before it connected.
     *
     * <p><b>With one exception: a session that starts with no commission says nothing.</b> Every
     * other poller in this family is silent when it has nothing to report — the expedition-warning
     * host poll refuses to broadcast an empty set for the same reason — and "no commission" is the
     * state a guest is in already unless it is carrying one from a previous session. Paying a
     * session-start broadcast on every campaign to correct that one case is the wrong trade, and the
     * case self-corrects the moment the host signs or ends anything. The residual gap is narrow and
     * documented: a host whose commission ended while the guest was disconnected leaves the guest's
     * military-submarket access clause reading true until the host's next commission change.
     *
     * @return the payload to send — a faction id, or {@code ""} for a commission that just ended —
     *         or null when there is nothing to send
     */
    public String poll(long nowMillis) {
        if (pollSeeded && nowMillis - lastPollMillis < POLL_INTERVAL_MILLIS) {
            return null;
        }
        pollSeeded = true;
        lastPollMillis = nowMillis;
        String current = normalize(engine.commissionFactionId());
        if (seeded && current.equals(lastSeen)) {
            return null;
        }
        boolean firstPollOfTheSession = !seeded;
        seeded = true;
        lastSeen = current;
        if (firstPollOfTheSession && current.isEmpty()) {
            return null;
        }
        return current;
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
