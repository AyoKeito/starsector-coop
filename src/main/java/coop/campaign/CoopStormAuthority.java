package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.storm.CoopStormControl;
import coop.storm.CoopStormSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Transient transport cursors only; native World owns persisted grid and tracker checkpoints. */
public final class CoopStormAuthority {
    public static final long SNAPSHOT_POLL_INTERVAL_MILLIS = 250L;
    public static final int MAX_CONTROLS_PER_POLL = 4096;

    public interface World {
        /** Null means native terrain is unavailable, never an authoritative empty grid. */
        CoopStormSnapshot captureHost();
        /** Install a fully validated grid and complete tracker map atomically. */
        void applySnapshot(CoopStormSnapshot snapshot);
        void observeGuest();
        /** Bounded coalesced queue, drained once per poll. Native effects refresh these intents. */
        List<CoopStormControl> pendingControls();
        /** Revalidate remote fleet proximity/native source semantics before any weather mutation. */
        void applyControl(CoopStormControl control, String authenticatedActor);
        /** Clears only ephemeral queues/lookups, never saved native weather. */
        default void resetTransport() { }
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final World world;
    private final CoopStormCodec.Assembler assembler = new CoopStormCodec.Assembler();
    private CoopStormSnapshot sent, desired;
    private long nextHostPoll, nextGuestPoll;
    private boolean fullBroadcast = true, retryApply;

    public CoopStormAuthority(CoopNetService service, CoopSessionState session, LongSupplier clock, World world) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.world = Objects.requireNonNull(world, "world");
    }

    public void tickHost() {
        if (!active(CoopConnectionRole.HOST)) return;
        long now = clock.getAsLong();
        if (now < nextHostPoll) return;
        try {
            CoopStormSnapshot current = world.captureHost();
            if (current == null) return;
            validateOrder(current, sent);
            if (!fullBroadcast && current.equals(sent)) return;
            List<CoopMessages.Message> messages = new ArrayList<>();
            for (String encoded : CoopStormCodec.encodeSnapshotParts(current)) {
                CoopMessages.Message message = fullBroadcast
                        ? CoopMessages.stormSet(session.sessionId(), service.nextSeq(), now, encoded)
                        : CoopMessages.stormGrid(session.sessionId(), service.nextSeq(), now, encoded);
                requireFrame(message); messages.add(message);
            }
            messages.forEach(service::send); sent = current; fullBroadcast = false;
        } finally { nextHostPoll = now + SNAPSHOT_POLL_INTERVAL_MILLIS; }
    }

    public void observeGuest() { world.observeGuest(); }

    public void tickGuest() {
        if (!active(CoopConnectionRole.GUEST)) return;
        world.observeGuest();
        long now = clock.getAsLong();
        if (now >= nextGuestPoll) {
            try {
                List<CoopStormControl> controls = List.copyOf(world.pendingControls());
                if (controls.size() > MAX_CONTROLS_PER_POLL)
                    throw new IllegalArgumentException("Too many pending native storm controls");
                Set<String> ids = new HashSet<>();
                List<CoopMessages.Message> messages = new ArrayList<>(controls.size());
                for (CoopStormControl control : controls) {
                    if (!ids.add(control.requestId())) throw new IllegalArgumentException("Duplicate native storm control identity");
                    CoopMessages.Message message = CoopMessages.stormControl(session.sessionId(), service.nextSeq(), now,
                            CoopStormCodec.encodeControl(control));
                    requireFrame(message); messages.add(message);
                }
                messages.forEach(service::send);
            } finally { nextGuestPoll = now + SNAPSHOT_POLL_INTERVAL_MILLIS; }
        }
        if (retryApply) reconcile();
    }

    public void applySet(String encoded) { accept(encoded, true); }
    public void applyGrid(String encoded) { accept(encoded, false); }

    private void accept(String encoded, boolean baseline) {
        requireRole(CoopConnectionRole.GUEST);
        requireFrame(baseline ? CoopMessages.stormSet(session.sessionId(), 0, 0, encoded)
                : CoopMessages.stormGrid(session.sessionId(), 0, 0, encoded));
        if (!baseline && desired == null) throw new IllegalStateException("Storm GRID arrived before SET baseline");
        CoopStormSnapshot snapshot = assembler.accept(encoded, baseline, clock.getAsLong(), desired == null ? -1 : desired.revision());
        if (snapshot == null) return;
        try { validateOrder(snapshot, desired); }
        catch (RuntimeException failure) { assembler.reset(); throw failure; }
        if (snapshot.equals(desired) && !retryApply) return;
        desired = snapshot; retryApply = true;
        world.observeGuest(); reconcile();
    }

    private void reconcile() {
        world.applySnapshot(desired);
        retryApply = false;
    }

    public void applyControl(String encoded, String authenticatedActor) {
        requireRole(CoopConnectionRole.HOST);
        if (authenticatedActor == null || authenticatedActor.isBlank()
                || !authenticatedActor.equals(session.remotePlayerId()))
            throw new IllegalArgumentException("Storm control actor is not the connected Guest");
        CoopStormControl control = CoopStormCodec.decodeControl(encoded);
        requireFrame(CoopMessages.stormControl(session.sessionId(), 0, 0, encoded));
        world.applyControl(control, authenticatedActor);
    }

    private static void validateOrder(CoopStormSnapshot incoming, CoopStormSnapshot previous) {
        if (previous == null) return;
        if (incoming.revision() < previous.revision() || incoming.generation() < previous.generation()
                || incoming.snapshotTimestamp() < previous.snapshotTimestamp())
            throw new IllegalArgumentException("Stale storm checkpoint");
        if (incoming.revision() == previous.revision() && !incoming.equals(previous))
            throw new IllegalArgumentException("Conflicting duplicate storm revision");
        if (incoming.generation() == previous.generation()
                && (!incoming.encodedCells().equals(previous.encodedCells())
                    || incoming.commitTimestamp() != previous.commitTimestamp()
                    || incoming.intervalDurationDays() != previous.intervalDurationDays()
                    || incoming.width() != previous.width() || incoming.height() != previous.height()))
            throw new IllegalArgumentException("Storm grid changed without a native generation commit");
    }

    private boolean active(CoopConnectionRole role) { return session.role() == role && session.sessionId() != null; }
    private void requireRole(CoopConnectionRole role) {
        if (!active(role)) throw new IllegalStateException("Storm operation requires an active " + role + " session");
    }
    private static void requireFrame(CoopMessages.Message message) {
        if (!CoopSlipstreamAuthority.fitsFrame(message)) throw new IllegalArgumentException("Storm payload exceeds TCP frame cap");
    }

    public void rearmFullBroadcast() {
        fullBroadcast = true; nextHostPoll = 0; nextGuestPoll = 0;
        // Resume is a fresh publication boundary, while desired keeps same-session ordering.
        assembler.reset();
    }

    /** A new campaign/session forgets transport ordering but retains native saved weather. */
    public void reset() {
        sent = null; desired = null; retryApply = false;
        assembler.reset();
        world.resetTransport(); rearmFullBroadcast();
    }
}
