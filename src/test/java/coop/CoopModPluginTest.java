package coop;

import coop.net.CoopStallNotice;
import coop.ui.CoopSessionIntelFeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The mod-plugin teardown, which {@code onGameLoad} itself cannot be tested through: it needs a live
 * {@code Global.getSector()}. What is asserted here is the part that has no engine in it — the static
 * handles a replaced pump leaves behind.
 */
class CoopModPluginTest {

    @AfterEach
    void clearStatics() {
        CoopSessionIntelFeed.uninstall();
        CoopStallNotice.setActive(null);
    }

    @Test
    void b6_theIntelFeedAndStallHookAreClearedWhenAPumpIsReplaced() {
        CoopSessionIntelFeed feed = new CoopSessionIntelFeed(() -> 1_000L);
        CoopSessionIntelFeed.install(feed);
        AtomicInteger stalls = new AtomicInteger();
        CoopStallNotice.setActive((reason, expected) -> stalls.incrementAndGet());
        assertNotNull(CoopSessionIntelFeed.active());

        CoopModPlugin.tearDownPreviousPump(null);

        // Pre-fix nothing ever uninstalled the feed, so loading a solo save after a coop session left
        // the "Coop Session" intel page rendering the previous game's role, partner and RTT.
        assertNull(CoopSessionIntelFeed.active());
        CoopStallNotice.notifyLocalStall(CoopStallNotice.REASON_LOCAL_SAVE, 1L);
        assertEquals(0, stalls.get(), "a replaced pump must not still be receiving save callbacks");
    }
}
