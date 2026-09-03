package coop;

import com.thoughtworks.xstream.XStream;
import coop.net.CoopStallNotice;
import coop.stats.CoopSessionStats;
import coop.ui.CoopSessionIntelFeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void theSessionStatsClassesAreAliasedSoARenameCannotBreakExistingSaves() {
        RecordingXStream x = new RecordingXStream();

        new CoopModPlugin().configureXStream(x);

        // The tally rides the save under CoopSessionStats.PERSISTENT_KEY. Without these three, the
        // save spells the package path and moving the class makes every save carrying one unreadable.
        assertTrue(x.aliases().containsKey("coopStats"));
        assertEquals(CoopSessionStats.class, x.aliases().get("coopStats"));
        assertEquals(CoopSessionStats.PlayerStats.class, x.aliases().get("coopStatsPlayer"));
        assertEquals(CoopSessionStats.ShipLoss.class, x.aliases().get("coopStatsLoss"));
    }

    /** Records the alias calls; {@code XStream.alias} is the only method the plugin uses. */
    private static final class RecordingXStream extends XStream {
        /**
         * Not a field initialiser: {@code XStream}'s own constructor registers ~40 built-in aliases
         * through {@link #alias}, and it does that before this subclass's initialisers run.
         */
        private java.util.Map<String, Class<?>> aliases;

        private java.util.Map<String, Class<?>> aliases() {
            if (aliases == null) {
                aliases = new java.util.HashMap<>();
            }
            return aliases;
        }

        /**
         * The no-arg XStream constructor reaches for an xmlpull parser that is not on the test
         * classpath (the game ships it, the build does not). DomDriver uses the JDK's own parser and
         * needs nothing extra; nothing here parses XML anyway.
         */
        private RecordingXStream() {
            super(new com.thoughtworks.xstream.io.xml.DomDriver());
        }

        @Override
        public void alias(String name, Class type) {
            aliases().put(name, type);
        }
    }
}
