package coop.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diagnostics toggle is a cached flag refreshed on a frame poll (perf audit #15): the hot paths
 * read it 3-4x a frame and must not pay for a {@code Properties} lookup plus a sector-memory read.
 * There is no sector in a unit test, so the property is the lever here; the memory-flag half of
 * {@link CoopDebug#refresh()} degrades to "not flagged" exactly as it does on a client with no game
 * loaded.
 */
class CoopDebugTest {

    private String savedProperty;
    private String savedDelayProperty;

    @BeforeEach
    void setUp() {
        savedProperty = System.getProperty(CoopDebug.PROPERTY);
        savedDelayProperty = System.getProperty(CoopDebug.INTERACTION_DELAY_PROPERTY);
        System.clearProperty(CoopDebug.PROPERTY);
        System.clearProperty(CoopDebug.INTERACTION_DELAY_PROPERTY);
        CoopDebug.setEnabledForTesting(false);
        CoopDebug.setInteractionClaimDelayMillisForTesting(0);
        CoopDebug.resetPollCounterForTesting();
    }

    @AfterEach
    void tearDown() {
        restore(CoopDebug.PROPERTY, savedProperty);
        restore(CoopDebug.INTERACTION_DELAY_PROPERTY, savedDelayProperty);
        CoopDebug.setEnabledForTesting(false);
        CoopDebug.setInteractionClaimDelayMillisForTesting(0);
        CoopDebug.resetPollCounterForTesting();
    }

    private static void restore(String key, String saved) {
        if (saved == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, saved);
        }
    }

    @Test
    void diagnosticsEnabledReadsTheCachedFlagAndNotTheProperty() {
        System.setProperty(CoopDebug.PROPERTY, "true");

        // Nothing polled yet, so the reader still sees the cached value. This is the whole point: the
        // read is a field access, and the ~5 s engage latency is the sanctioned cost.
        assertFalse(CoopDebug.diagnosticsEnabled(), "reader must not re-read the JVM property");

        CoopDebug.refresh();
        assertTrue(CoopDebug.diagnosticsEnabled(), "refresh must pick the property up");
    }

    @Test
    void pollFrameRefreshesOnlyOnTheThreeHundredthFrame() {
        System.setProperty(CoopDebug.PROPERTY, "true");

        for (int i = 0; i < CoopDebug.TOGGLE_POLL_FRAMES - 1; i++) {
            CoopDebug.pollFrame();
        }
        assertFalse(CoopDebug.diagnosticsEnabled(), "toggle engaged before the poll window elapsed");

        CoopDebug.pollFrame();
        assertTrue(CoopDebug.diagnosticsEnabled(), "toggle did not engage on the poll frame");
    }

    @Test
    void pollFrameAlsoDisengagesTheToggle() {
        CoopDebug.setEnabledForTesting(true);

        for (int i = 0; i < CoopDebug.TOGGLE_POLL_FRAMES; i++) {
            CoopDebug.pollFrame();
        }

        assertFalse(CoopDebug.diagnosticsEnabled(), "toggle must disengage once the flag is gone");
    }

    // ---- Phase 18 latency lever -----------------------------------------------------------------

    @Test
    void interactionDelayIsDormantWithoutTheProperty() {
        CoopDebug.refresh();

        assertEquals(0, CoopDebug.interactionClaimDelayMillis());
    }

    @Test
    void interactionDelayReadsTheProperty() {
        System.setProperty(CoopDebug.INTERACTION_DELAY_PROPERTY, "250");

        CoopDebug.refresh();

        assertEquals(250, CoopDebug.interactionClaimDelayMillis());
    }

    @Test
    void aBadOrNegativeInteractionDelayIsDormantRatherThanFatal() {
        System.setProperty(CoopDebug.INTERACTION_DELAY_PROPERTY, "soon");
        CoopDebug.refresh();
        assertEquals(0, CoopDebug.interactionClaimDelayMillis());

        System.setProperty(CoopDebug.INTERACTION_DELAY_PROPERTY, "-40");
        CoopDebug.refresh();
        assertEquals(0, CoopDebug.interactionClaimDelayMillis());
    }

    @Test
    void anAbsurdInteractionDelayIsClamped() {
        System.setProperty(CoopDebug.INTERACTION_DELAY_PROPERTY,
                String.valueOf(CoopDebug.MAX_INTERACTION_DELAY_MILLIS * 10L));

        CoopDebug.refresh();

        assertEquals(CoopDebug.MAX_INTERACTION_DELAY_MILLIS, CoopDebug.interactionClaimDelayMillis());
    }

    @Test
    void pollWindowRestartsAfterEachRefresh() {
        for (int i = 0; i < CoopDebug.TOGGLE_POLL_FRAMES; i++) {
            CoopDebug.pollFrame();
        }
        System.setProperty(CoopDebug.PROPERTY, "true");

        for (int i = 0; i < CoopDebug.TOGGLE_POLL_FRAMES - 1; i++) {
            CoopDebug.pollFrame();
        }
        assertFalse(CoopDebug.diagnosticsEnabled(), "second window ended early");

        CoopDebug.pollFrame();
        assertTrue(CoopDebug.diagnosticsEnabled(), "second window never fired");
    }
}
