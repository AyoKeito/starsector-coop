package coop.testing;

import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The two session states nearly every replicator test starts from: a host and a guest that have
 * finished the handshake and locked the seed.
 *
 * <p>Both builders appeared verbatim in eleven and thirteen test classes respectively - same ids,
 * same seed, same fingerprint - each with its own private id supplier. The only variation was
 * cosmetic: some guest copies passed {@code () -> "guest-player"} and some passed a one-element
 * sequence, which are the same thing because the guest path draws the id once.
 *
 * <p>The id suppliers were not the same, though. Three flavours were in the tree: repeat the last id
 * once the list runs out, throw {@code IndexOutOfBoundsException}, and throw
 * {@code NoSuchElementException}. {@link #sequencedIds} is the repeating one, because it is the only
 * one that cannot turn a builder into a failure the caller never saw before - the throwing copies
 * only ever passed by never over-drawing. {@link #strictSequencedIds} keeps the throwing behaviour
 * available for a test that wants an over-draw to be loud.
 */
public final class TestSessions {

    /** The seed every one of these builders locked. */
    public static final long SEED = 123L;

    private TestSessions() {
    }

    /** Host side: lobby joined, handshake done, seed locked. */
    public static CoopSessionState activeHostSession() {
        CoopSessionState session = new CoopSessionState(
                sequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(SEED, "seed-a", "fingerprint-a");
        return session;
    }

    /** Guest side: lobby accepted, handshake done, seed locked. */
    public static CoopSessionState activeGuestSession() {
        CoopSessionState session = new CoopSessionState(sequencedIds("guest-player"));
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(SEED, "seed-a", "fingerprint-a");
        return session;
    }

    /** Hands out {@code ids} in order, then repeats the last one for every further draw. */
    public static Supplier<String> sequencedIds(String... ids) {
        List<String> queue = List.of(ids);
        return new Supplier<>() {
            private int index;

            @Override
            public String get() {
                String id = queue.get(Math.min(index, queue.size() - 1));
                index++;
                return id;
            }
        };
    }

    /** Hands out {@code ids} in order and throws once they run out. */
    public static Supplier<String> strictSequencedIds(String... ids) {
        List<String> queue = List.of(ids);
        return new Supplier<>() {
            private int index;

            @Override
            public String get() {
                if (index >= queue.size()) {
                    throw new NoSuchElementException("id supplier drawn " + (index + 1)
                            + " times but was given only " + queue.size());
                }
                return queue.get(index++);
            }
        };
    }
}
