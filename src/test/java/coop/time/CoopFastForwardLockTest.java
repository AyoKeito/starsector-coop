package coop.time;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine classes are not on the test classpath and must never be initialized outside the game,
 * so everything here runs against the injected {@code Handles}/{@code MultSetting} seams.
 */
class CoopFastForwardLockTest {

    @Test
    void enforceForcesToggleModeAndTheSharedMultiplier() {
        FakeHandles handles = new FakeHandles(false, false);
        FakeMult mult = new FakeMult(3f);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> handles, mult, false);

        lock.enforceSessionState();

        assertTrue(lock.isAvailable());
        assertTrue(handles.toggle);
        assertEquals(List.of(true), handles.toggleWrites);
        assertEquals(CoopFastForwardLock.SESSION_MULT, mult.value);
        assertEquals(1, mult.writes);
    }

    @Test
    void enforceIsIdempotentPerFrameOnceTheStateIsAlreadyForced() {
        FakeHandles handles = new FakeHandles(false, false);
        FakeMult mult = new FakeMult(1f);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> handles, mult, false);

        lock.enforceSessionState();
        lock.enforceSessionState();
        lock.enforceSessionState();

        assertEquals(1, handles.toggleWrites.size());
        assertEquals(1, mult.writes);

        // The player unticks the vanilla checkbox mid-session: the next frame re-forces it.
        handles.toggle = false;
        lock.enforceSessionState();
        assertTrue(handles.toggle);
        assertEquals(2, handles.toggleWrites.size());
    }

    @Test
    void unavailableLockFallsBackToTheOneTimesMultiplierAndNeverWritesTheToggle() {
        FakeHandles handles = new FakeHandles(false, false);
        FakeMult mult = new FakeMult(2f);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> null, mult, false);

        lock.enforceSessionState();

        assertFalse(lock.isAvailable());
        assertEquals(CoopFastForwardLock.FALLBACK_MULT, mult.value);
        assertTrue(handles.toggleWrites.isEmpty());
        assertTrue(handles.ffWrites.isEmpty());
    }

    @Test
    void disablePropertyForcesTheFallbackPath() {
        FakeMult mult = new FakeMult(2f);
        boolean[] resolverCalled = {false};
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> {
            resolverCalled[0] = true;
            return new FakeHandles(false, false);
        }, mult, true);

        lock.enforceSessionState();

        assertFalse(lock.isAvailable());
        assertFalse(resolverCalled[0]);
        assertEquals(CoopFastForwardLock.FALLBACK_MULT, mult.value);
    }

    @Test
    void disablePropertyIsReadFromTheSystemPropertyByTheProductionConstructor() {
        String previous = System.getProperty(CoopFastForwardLock.DISABLE_PROPERTY);
        try {
            System.setProperty(CoopFastForwardLock.DISABLE_PROPERTY, "true");
            // No engine touch: the production constructor only reads the property; isAvailable()
            // short-circuits on it before any resolve is attempted.
            assertFalse(new CoopFastForwardLock().isAvailable());
        } finally {
            if (previous == null) {
                System.clearProperty(CoopFastForwardLock.DISABLE_PROPERTY);
            } else {
                System.setProperty(CoopFastForwardLock.DISABLE_PROPERTY, previous);
            }
        }
    }

    @Test
    void aThrowingResolverFlipsAvailableStickyFalseAndNeverThrowsOut() {
        FakeMult mult = new FakeMult(2f);
        int[] attempts = {0};
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> {
            attempts[0]++;
            throw new IllegalStateException("no such field");
        }, mult, false);

        lock.enforceSessionState();
        lock.writeFastForward(true);
        lock.enforceSessionState();

        assertFalse(lock.isAvailable());
        assertEquals(1, attempts[0], "resolution must be attempted once, then stay sticky-failed");
        assertEquals(CoopFastForwardLock.FALLBACK_MULT, mult.value);
    }

    @Test
    void aHandleThatThrowsMidSessionDegradesToTheFallbackLock() {
        FakeHandles handles = new FakeHandles(true, false);
        handles.throwOnToggleRead = true;
        FakeMult mult = new FakeMult(2f);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> handles, mult, false);

        lock.enforceSessionState();

        assertFalse(lock.isAvailable());
        assertEquals(CoopFastForwardLock.FALLBACK_MULT, mult.value);
    }

    @Test
    void restoreDefaultsPutsBackTheRememberedToggleAndTheEngineDefaultMultiplier() {
        FakeHandles handles = new FakeHandles(false, true);
        FakeMult mult = new FakeMult(1f);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> handles, mult, false);

        lock.enforceSessionState();
        assertTrue(handles.toggle);

        lock.restoreDefaults();

        assertFalse(handles.toggle, "the player had hold mode; the session must not keep toggle mode");
        assertEquals(List.of(true, false), handles.toggleWrites);
        assertEquals(CoopFastForwardLock.SESSION_MULT, mult.value);
    }

    @Test
    void restoreDefaultsLeavesAPlayerWhoAlreadyPreferredToggleModeAlone() {
        FakeHandles handles = new FakeHandles(true, false);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> handles, new FakeMult(2f), false);

        lock.enforceSessionState();
        lock.restoreDefaults();

        assertTrue(handles.toggle);
        assertTrue(handles.toggleWrites.isEmpty());
    }

    @Test
    void restoreOnlyFiresOnceAfterASessionEnds() {
        FakeHandles handles = new FakeHandles(false, false);
        FakeMult mult = new FakeMult(1f);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> handles, mult, false);

        // Not enforcing yet: the pump's else-branch must be a pure no-op every frame.
        lock.restoreDefaultsIfEnforcing();
        lock.restoreDefaultsIfEnforcing();
        assertEquals(0, mult.writes);
        assertTrue(handles.toggleWrites.isEmpty());

        lock.enforceSessionState();
        assertTrue(lock.isEnforcing());

        lock.restoreDefaultsIfEnforcing();
        lock.restoreDefaultsIfEnforcing();
        lock.restoreDefaultsIfEnforcing();

        assertFalse(lock.isEnforcing());
        assertEquals(List.of(true, false), handles.toggleWrites, "restore must write the toggle once");
    }

    @Test
    void writeFastForwardOnlyWritesOnAChange() {
        FakeHandles handles = new FakeHandles(true, false);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> handles, new FakeMult(2f), false);

        lock.writeFastForward(false);
        assertTrue(handles.ffWrites.isEmpty());

        lock.writeFastForward(true);
        lock.writeFastForward(true);
        assertEquals(List.of(true), handles.ffWrites);
        assertTrue(handles.fastForward);

        lock.writeFastForward(false);
        assertEquals(List.of(true, false), handles.ffWrites);
        assertFalse(handles.fastForward);
    }

    @Test
    void writeFastForwardIsANoOpWhenUnavailable() {
        FakeHandles handles = new FakeHandles(true, false);
        CoopFastForwardLock lock = new CoopFastForwardLock(() -> null, new FakeMult(2f), false);

        lock.writeFastForward(true);

        assertTrue(handles.ffWrites.isEmpty());
    }

    private static final class FakeHandles implements CoopFastForwardLock.Handles {
        private boolean toggle;
        private boolean fastForward;
        private boolean throwOnToggleRead;
        private final List<Boolean> toggleWrites = new ArrayList<>();
        private final List<Boolean> ffWrites = new ArrayList<>();

        private FakeHandles(boolean toggle, boolean fastForward) {
            this.toggle = toggle;
            this.fastForward = fastForward;
        }

        @Override
        public boolean readToggle() {
            if (throwOnToggleRead) {
                throw new IllegalStateException("handle went bad");
            }
            return toggle;
        }

        @Override
        public void writeToggle(boolean value) {
            toggle = value;
            toggleWrites.add(value);
        }

        @Override
        public boolean readFastForward() {
            return fastForward;
        }

        @Override
        public void writeFastForward(boolean value) {
            fastForward = value;
            ffWrites.add(value);
        }
    }

    private static final class FakeMult implements CoopFastForwardLock.MultSetting {
        private float value;
        private int writes;

        private FakeMult(float value) {
            this.value = value;
        }

        @Override
        public float read() {
            return value;
        }

        @Override
        public void write(float value) {
            this.value = value;
            writes++;
        }
    }
}
