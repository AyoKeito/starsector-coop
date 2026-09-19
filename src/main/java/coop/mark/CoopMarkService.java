package coop.mark;

import coop.net.CoopMessages;
import coop.util.CoopLog;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The in-game log marker: what happens between the key going down and two {@code COOP-MARK} lines
 * existing, one in each player's log.
 *
 * <p><b>The immediate line is not negotiable.</b> It is written before anything else is attempted -
 * before the send, before the dialog, before any question about whether the map is free. The marker
 * is pressed when something has already gone wrong, so every step after the first one is allowed to
 * fail and the line still lands. A note, the partner's copy and the HUD confirmation are all
 * improvements on top of a line that is already in the file.
 *
 * <p><b>Engine-free.</b> Everything this needs from the game - the day, where the fleet is, whether
 * the campaign map is free - arrives as a {@link World} from a supplier the pump owns, and
 * everything it does to the game leaves through a consumer. That is what lets the numbering, the
 * two-halves-of-one-marker protocol and both log lines be tested with no game running.
 */
public final class CoopMarkService {

    /** The engine facts a marker needs, gathered by the caller on the frame the key went down. */
    public record World(float day, String location, boolean mapFree) {
        public World {
            location = location == null ? "" : location;
        }

        /** What a frame with no sector looks like: a marker still gets written, with no place. */
        public static World unknown() {
            return new World(0f, "unknown", false);
        }
    }

    private final Supplier<String> role;
    private final Supplier<String> sessionId;
    private final LongSupplier nextSeq;
    private final LongSupplier clockMillis;
    private final Consumer<CoopMessages.Message> sender;
    private final Supplier<World> world;
    private final Consumer<String> notice;
    private final Consumer<String> noteBoxRequest;

    /** Markers pressed locally this session; the first one is {@code #1}. */
    private int pressed;
    /** The marker the note box is open for, or {@code ""} when it is not. */
    private String awaitingNoteFor = "";
    /**
     * That marker's world, kept so the note half reports where the press happened rather than where
     * the fleet had drifted to by the time the player finished typing.
     */
    private World awaitingNoteWorld = World.unknown();

    public CoopMarkService(Supplier<String> role,
                           Supplier<String> sessionId,
                           LongSupplier nextSeq,
                           LongSupplier clockMillis,
                           Consumer<CoopMessages.Message> sender,
                           Supplier<World> world,
                           Consumer<String> notice,
                           Consumer<String> noteBoxRequest) {
        this.role = Objects.requireNonNull(role, "role");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.nextSeq = Objects.requireNonNull(nextSeq, "nextSeq");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.world = Objects.requireNonNull(world, "world");
        this.notice = notice == null ? text -> { } : notice;
        this.noteBoxRequest = noteBoxRequest == null ? id -> { } : noteBoxRequest;
    }

    /** Markers pressed locally so far this session; test and bridge read. */
    public int pressedCount() {
        return pressed;
    }

    /** The marker the note box is open for, or {@code ""}. */
    public String awaitingNoteFor() {
        return awaitingNoteFor;
    }

    /** Session start/end: the counter is per session, and so is a half-finished marker. */
    public void reset() {
        pressed = 0;
        awaitingNoteFor = "";
        awaitingNoteWorld = World.unknown();
    }

    /**
     * The marker key went down. Writes the local line, sends the partner's copy, shows the presser
     * their own confirmation, and asks for the note box when the campaign map is free.
     *
     * @return the marker id that was written
     */
    public String onKeyPressed() {
        World snapshot = safeWorld();
        String id = CoopMarkFormat.markerId(role(), ++pressed);
        long seq = seq();
        CoopLog.info(CoopMarkService.class,
                CoopMarkFormat.line(id, snapshot.day(), snapshot.location(), seq, ""));
        send(id, snapshot, seq, "");
        show(CoopMarkFormat.ownNotice(id, ""));
        if (snapshot.mapFree()) {
            // Nothing is queued when the map is not free: a note box that opened after the market
            // screen closed would be a mystery dialog attached to a marker the player has already
            // stopped thinking about.
            awaitingNoteFor = id;
            awaitingNoteWorld = snapshot;
            try {
                noteBoxRequest.accept(id);
            } catch (RuntimeException | LinkageError ex) {
                awaitingNoteFor = "";
                CoopLog.warn(CoopMarkService.class,
                        "Coop marker " + id + " could not open its note box; the marker stands", ex);
            }
        }
        return id;
    }

    /**
     * The player pressed OK in the note box. An empty note is a no-op - the marker is already
     * complete - and so is a note for a marker this service is not waiting on, which is what a
     * stale dialog callback looks like.
     */
    public void onNoteEntered(String markerId, String note) {
        String id = markerId == null ? "" : markerId;
        String text = CoopMarkFormat.note(note);
        World snapshot = awaitingNoteWorld;
        boolean expected = !id.isEmpty() && id.equals(awaitingNoteFor);
        onNoteBoxClosed();
        if (!expected || text.isEmpty()) {
            return;
        }
        long seq = seq();
        CoopLog.info(CoopMarkService.class, CoopMarkFormat.noteLine(id, text));
        send(id, snapshot, seq, text);
        show(CoopMarkFormat.ownNotice(id, text));
    }

    /** Cancel, ESC, or the dialog going away on its own: nothing further is written. */
    public void onNoteBoxClosed() {
        awaitingNoteFor = "";
        awaitingNoteWorld = World.unknown();
    }

    /**
     * A partner's marker landed. The line written here is the <em>sender's</em>, rendered from the
     * sender's fields, so the two files carry the same characters for the same marker.
     */
    public void applyInbound(CoopMessages.Message message) {
        CoopMessages.Mark mark;
        try {
            mark = CoopMessages.parseMark(message);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopMarkService.class, "Coop could not read a partner's log marker", ex);
            return;
        }
        if (mark.hasNote()) {
            CoopLog.info(CoopMarkService.class,
                    CoopMarkFormat.noteLine(mark.markerId(), mark.note()));
        } else {
            CoopLog.info(CoopMarkService.class, CoopMarkFormat.line(mark.markerId(), mark.day(),
                    mark.location(), mark.markSeq(), ""));
        }
        show(CoopMarkFormat.partnerNotice(mark.markerId(), mark.note()));
    }

    private void send(String markerId, World snapshot, long seq, String note) {
        String session = safe(sessionId);
        if (session.isEmpty()) {
            // No session id, no message. The marker is still in this log, which is the half that
            // does not depend on a partner being there to receive it.
            return;
        }
        try {
            sender.accept(CoopMessages.mark(session, seq, clockMillis.getAsLong(), markerId,
                    snapshot.day(), snapshot.location(), seq, note));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopMarkService.class,
                    "Coop could not send log marker " + markerId + "; it is in this log only", ex);
        }
    }

    private void show(String text) {
        try {
            notice.accept(text);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopMarkService.class, "Coop could not show a marker notice", ex);
        }
    }

    private String role() {
        String value = safe(role);
        return CoopMarkFormat.ROLE_GUEST.equalsIgnoreCase(value)
                ? CoopMarkFormat.ROLE_GUEST : CoopMarkFormat.ROLE_HOST;
    }

    private World safeWorld() {
        try {
            World snapshot = world.get();
            return snapshot == null ? World.unknown() : snapshot;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopMarkService.class,
                    "Coop could not read the world for a log marker; writing it anyway", ex);
            return World.unknown();
        }
    }

    private long seq() {
        try {
            return nextSeq.getAsLong();
        } catch (RuntimeException | LinkageError ex) {
            return -1L;
        }
    }

    private static String safe(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }
}
