package coop;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.thoughtworks.xstream.XStream;
import coop.config.CoopOptionsStore;
import coop.fleet.CoopFullFidelitySystemDriver;
import coop.net.CoopNetStartupConfig;
import coop.net.CoopStallNotice;
import coop.presence.CoopPresenceRegistry;
import coop.rng.CoopRandom;
import coop.stats.CoopSessionStats;
import coop.ui.CoopSessionIntelFeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        CoopFullFidelitySystemDriver.endSave();
        CoopPresenceRegistry.clear();
    }

    // ---- config-plugin-1: the save that failed -------------------------------------------------

    @Test
    void aFailedSaveGivesTheFullFidelityDriveBack() {
        CoopFullFidelitySystemDriver.beginSave();
        assertTrue(CoopFullFidelitySystemDriver.isSaveInProgress());

        // No sector here, so the three intel pages are no-ops; what is asserted is the static that
        // outlives the campaign. Pre-fix the engine called onGameSaveFailed into BaseModPlugin's
        // empty default, afterGameSave never ran, and the guest-system drive stayed off for the rest
        // of the JVM process - across campaign loads, with only a successful save able to clear it.
        new CoopModPlugin().onGameSaveFailed();

        assertFalse(CoopFullFidelitySystemDriver.isSaveInProgress());
    }

    @Test
    void aStuckSaveFlagCannotOutliveTheCampaignThatSetIt() {
        CoopFullFidelitySystemDriver.beginSave();

        CoopModPlugin.clearPreviousGameStatics();

        assertFalse(CoopFullFidelitySystemDriver.isSaveInProgress());
    }

    // ---- config-plugin-5: the guest-presence slot ----------------------------------------------

    @Test
    void loadingAnotherGameDropsThePreviousSectorsGuestPresence() {
        CoopPresenceRegistry.set(dummyEntity());
        assertNotNull(CoopPresenceRegistry.get());

        CoopModPlugin.clearPreviousGameStatics();

        // Pre-fix the only release was a frame boundary that needs a campaign frame the pump did not
        // tick, and quitting to the title screen produces no campaign frame at all: the dead sector's
        // mirror stayed published to the forked spawners through the next game's procgen.
        assertNull(CoopPresenceRegistry.get());
    }

    // ---- save-seed-handshake-1 / forks-1: the forked Misc.random -------------------------------

    @Test
    void aSettingsFileSeedRebindsTheForkedSharedRandom() {
        String seed = "MN-1234567890123456789";
        Consumer<Random> previousSink = CoopModPlugin.sharedRandomSink;
        String previousSeed = System.getProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
        AtomicReference<Random> bound = new AtomicReference<>();
        CoopModPlugin.sharedRandomSink = bound::set;
        System.clearProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
        try {
            CoopModPlugin.publishSeedForTheForks(seed);

            assertEquals(seed, System.getProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY));
            // Misc.random is a static field initialiser bound during data loading, ~10 s before this
            // plugin exists, so a seed that arrives through the launcher's settings file rather than
            // a -D used to leave it a plain unseeded new Random() on both clients.
            assertNotNull(bound.get(), "the fork's shared Random was never rebound");
            assertEquals(CoopRandom.ofOrDefault("Misc.random").nextLong(), bound.get().nextLong(),
                    "the rebound stream must be the one the fork would have built from a -D");
        } finally {
            CoopModPlugin.sharedRandomSink = previousSink;
            if (previousSeed == null) {
                System.clearProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
            } else {
                System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, previousSeed);
            }
        }
    }

    @Test
    void aCommandLineSeedLeavesTheForkedSharedRandomAlone() {
        Consumer<Random> previousSink = CoopModPlugin.sharedRandomSink;
        String previousSeed = System.getProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
        AtomicInteger rebinds = new AtomicInteger();
        CoopModPlugin.sharedRandomSink = random -> rebinds.incrementAndGet();
        System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, "MN-1234567890123456789");
        try {
            CoopModPlugin.publishSeedForTheForks("MN-1234567890123456789");

            // The command line is the top of the stack and was there before Misc initialised, so the
            // field is already correct; rebinding it would throw away draws the fork has made.
            assertEquals(0, rebinds.get());
        } finally {
            CoopModPlugin.sharedRandomSink = previousSink;
            if (previousSeed == null) {
                System.clearProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
            } else {
                System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, previousSeed);
            }
        }
    }

    // ---- config-plugin-4: the one-shot consent flag --------------------------------------------

    @Test
    void everyConsentKeyThePluginConsumesIsAOneShotKey() {
        assertTrue(CoopModPlugin.oneShotConsentKeys()
                .contains(coop.config.CoopOptionsRegistry.ADOPT_CAMPAIGN_ID));
        // The invite's campaign id is consumed for the same reason: left in the file it would warn
        // about an invite nobody is acting on any more, on every launch, forever.
        assertTrue(CoopModPlugin.oneShotConsentKeys()
                .contains(coop.config.CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID));
        for (String key : CoopModPlugin.oneShotConsentKeys()) {
            // Consuming a key that is not -D-only would delete a standing setting out of the user's
            // file; CoopOptionsStore.consumeOneShot refuses one, and this is the same rule stated
            // where the set is chosen.
            assertTrue(CoopOptionsStore.ONE_SHOT_KEYS.contains(key), key);
        }
    }

    /** A do-nothing {@link SectorEntityToken}; the registry slot only ever needs identity. */
    private static SectorEntityToken dummyEntity() {
        return (SectorEntityToken) Proxy.newProxyInstance(
                CoopModPluginTest.class.getClassLoader(),
                new Class<?>[]{SectorEntityToken.class},
                (proxy, method, args) -> null);
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
