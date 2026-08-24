package coop.fleet;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Phase 17 wipe detection. The engine sits behind {@link CoopRespawnNotifier.Probe}, so
 * these drive a fake sector: a fleet is any object, and the probe answers from a table the test
 * mutates the way {@code showShuttleDialog()} would.
 */
class CoopRespawnNotifierTest {

    /** A stand-in player fleet; identity is the whole point, so the payload is only for messages. */
    private static final class FakeFleet {
        private final String name;

        private FakeFleet(String name) {
            this.name = name;
        }
    }

    private static final class FakeProbe implements CoopRespawnNotifier.Probe {
        private final Map<FakeFleet, Integer> members = new HashMap<>();
        private final Map<FakeFleet, Boolean> alive = new HashMap<>();
        private FakeFleet current;

        private FakeFleet spawn(String name, int memberCount) {
            FakeFleet fleet = new FakeFleet(name);
            members.put(fleet, memberCount);
            alive.put(fleet, true);
            return fleet;
        }

        /** What the engine does on a wipe: the old fleet loses its ships, then is replaced. */
        private FakeFleet wipeAndReplace(String newName) {
            members.put(current, 0);
            alive.put(current, false);
            FakeFleet replacement = spawn(newName, 2);
            current = replacement;
            return replacement;
        }

        @Override
        public Object playerFleet() {
            return current;
        }

        @Override
        public boolean isAlive(Object fleet) {
            return Boolean.TRUE.equals(alive.get(fleet));
        }

        @Override
        public int memberCount(Object fleet) {
            Integer count = members.get(fleet);
            return count == null ? 0 : count;
        }

        @Override
        public String destinationName(Object fleet) {
            return fleet instanceof FakeFleet fake ? fake.name : "";
        }
    }

    @Test
    void theFirstFrameOnlySeedsTheTrackedFleet() {
        // Session start must never banner: the notifier has no previous fleet to compare against, and
        // firing here would greet every connect with "your partner was destroyed".
        CoopRespawnNotifier notifier = new CoopRespawnNotifier();
        FakeProbe probe = new FakeProbe();
        probe.current = probe.spawn("Naraka", 7);

        assertNull(notifier.onFrame(probe));
    }

    @Test
    void aWipeSwapFiresOnceWithTheDestination() {
        CoopRespawnNotifier notifier = new CoopRespawnNotifier();
        FakeProbe probe = new FakeProbe();
        probe.current = probe.spawn("Askonia", 7);
        assertNull(notifier.onFrame(probe));
        assertNull(notifier.onFrame(probe));

        // The wipe window: 0 ships for a few frames, then setPlayerFleet swaps the object.
        probe.members.put(probe.current, 0);
        assertNull(notifier.onFrame(probe));

        probe.wipeAndReplace("Naraka");
        CoopRespawnNotifier.Respawn respawn = notifier.onFrame(probe);
        assertNotNull(respawn);
        assertEquals("Naraka", respawn.destinationName());
    }

    @Test
    void theSameNewFleetNeverFiresAgain() {
        CoopRespawnNotifier notifier = new CoopRespawnNotifier();
        FakeProbe probe = new FakeProbe();
        probe.current = probe.spawn("Askonia", 7);
        notifier.onFrame(probe);
        probe.wipeAndReplace("Naraka");
        assertNotNull(notifier.onFrame(probe));

        for (int frame = 0; frame < 50; frame++) {
            assertNull(notifier.onFrame(probe), "frame " + frame + " re-fired");
        }
    }

    @Test
    void aSecondWipeLaterInTheSessionFiresAgain() {
        CoopRespawnNotifier notifier = new CoopRespawnNotifier();
        FakeProbe probe = new FakeProbe();
        probe.current = probe.spawn("Askonia", 7);
        notifier.onFrame(probe);
        probe.wipeAndReplace("Naraka");
        assertNotNull(notifier.onFrame(probe));

        probe.wipeAndReplace("Jangala");
        CoopRespawnNotifier.Respawn second = notifier.onFrame(probe);
        assertNotNull(second);
        assertEquals("Jangala", second.destinationName());
    }

    @Test
    void aTransientlyUnreadableSectorIsNotASwap() {
        // getPlayerFleet() returns null through loads and teardown. Dropping the tracked reference
        // there would make the next readable frame look like a fresh fleet appearing from nowhere.
        CoopRespawnNotifier notifier = new CoopRespawnNotifier();
        FakeProbe probe = new FakeProbe();
        FakeFleet fleet = probe.spawn("Askonia", 7);
        probe.current = fleet;
        notifier.onFrame(probe);

        probe.current = null;
        assertNull(notifier.onFrame(probe));
        probe.current = fleet;
        assertNull(notifier.onFrame(probe));
    }

    @Test
    void resetMakesTheNextFrameSeedInsteadOfFire() {
        // The pump resets whenever the session stops streaming, so a reconnect cannot banner on a
        // fleet swap nobody was watching (a save load, say).
        CoopRespawnNotifier notifier = new CoopRespawnNotifier();
        FakeProbe probe = new FakeProbe();
        probe.current = probe.spawn("Askonia", 7);
        notifier.onFrame(probe);

        notifier.reset();
        probe.wipeAndReplace("Naraka");
        assertNull(notifier.onFrame(probe));
    }

    @Test
    void aHealthyFleetSwapIsNotAWipe() {
        // setPlayerFleet() has other callers. A swap whose predecessor was alive and crewed is not a
        // wipe, and bannering on one would be worse than missing an exotic respawn path.
        assertTrue(CoopRespawnNotifier.isRespawnSwap(0, 2, true));
        assertTrue(CoopRespawnNotifier.isRespawnSwap(7, 0, true));
        assertTrue(CoopRespawnNotifier.isRespawnSwap(7, 3, false));
        org.junit.jupiter.api.Assertions.assertFalse(CoopRespawnNotifier.isRespawnSwap(7, 3, true));
        org.junit.jupiter.api.Assertions.assertFalse(CoopRespawnNotifier.isRespawnSwap(-1, 0, false));
    }

    @Test
    void aNonFleetDestinationResolvesToTheEmptyString() {
        assertEquals("", CoopRespawnNotifier.describeDestination(null));
        assertEquals("", CoopRespawnNotifier.describeDestination("not a fleet"));
    }

    @Test
    void theRespawnMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.respawnPlayer(
                "session-1", 42L, 1234L, "player-uuid", "Naraka");
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.RESPAWN_PLAYER, decoded.type());
        assertEquals("session-1", decoded.sessionId());
        assertEquals(42L, decoded.seq());
        assertEquals("player-uuid", CoopMessages.requiredPayloadString(decoded, "playerId"));
        assertEquals("Naraka", CoopMessages.requiredPayloadString(decoded, "destinationName"));
    }

    @Test
    void anUnresolvableDestinationStillEncodes() {
        CoopMessages.Message message = CoopMessages.respawnPlayer(
                "session-1", 1L, 1L, "player-uuid", null);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals("", CoopMessages.requiredPayloadString(decoded, "destinationName"));
    }
}
