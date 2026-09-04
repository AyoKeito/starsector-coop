package coop.config;

import coop.config.CoopOptionsStore.Source;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import coop.testing.LogCapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopOptionsStoreTest {

    /** A JSON source with both layers held in memory, plus a counter so reads can be asserted on. */
    private static final class FakeSource implements CoopOptionsStore.JsonSource {
        private JSONObject shipped;
        private JSONObject common;
        private int shippedReads;
        private int commonReads;
        private int writes;
        private boolean writable = true;
        private RuntimeException failure;

        @Override
        public JSONObject shipped() {
            shippedReads++;
            if (failure != null) {
                throw failure;
            }
            return shipped;
        }

        @Override
        public JSONObject common() {
            commonReads++;
            if (failure != null) {
                throw failure;
            }
            return common;
        }

        @Override
        public boolean writeCommon(JSONObject json) {
            if (!writable) {
                return false;
            }
            writes++;
            common = json;
            return true;
        }

        @Override
        public void invalidate() {
        }
    }

    /**
     * The one case that matters for writing: the user's file is on disk but will not parse. The real
     * {@code SettingsJsonSource} swallows the exception and reports it through these two flags, so
     * this fake does the same rather than throwing out of {@code common()}.
     */
    private static final class BrokenCommonSource implements CoopOptionsStore.JsonSource {
        private JSONObject shipped;
        private int writes;

        @Override
        public JSONObject shipped() {
            return shipped;
        }

        @Override
        public JSONObject common() {
            return null;
        }

        @Override
        public boolean commonReadFailed() {
            return true;
        }

        @Override
        public boolean commonFileExists() {
            return true;
        }

        @Override
        public boolean writeCommon(JSONObject json) {
            writes++;
            return true;
        }
    }

    /** A source that throws out of every read, as an engine call that blows up would. */
    private static final class ThrowingSource implements CoopOptionsStore.JsonSource {
        private int writes;

        @Override
        public JSONObject shipped() {
            throw new IllegalStateException("no shipped file here");
        }

        @Override
        public JSONObject common() {
            throw new IllegalStateException("saves/common is on fire");
        }

        @Override
        public boolean commonFileExists() {
            return true;
        }

        @Override
        public boolean writeCommon(JSONObject json) {
            writes++;
            return true;
        }
    }

    private static JSONObject json(Map<String, Object> values) {
        try {
            JSONObject object = new JSONObject();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
            return object;
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Function<String, String> props(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map::get;
    }

    // ---- precedence ---------------------------------------------------------------------------

    @Test
    void fallsAllTheWayBackToTheRegistryDefault() {
        CoopOptionsStore store = new CoopOptionsStore(new FakeSource(), props());

        assertEquals("auto", store.raw("coop.portMapping"));
        assertEquals("60", store.raw("coop.reconnectGraceSeconds"));
        assertEquals("", store.raw("coop.password"));
        assertEquals(Source.DEFAULT, store.sourceOf("coop.portMapping"));
    }

    @Test
    void shippedDefaultsBeatTheRegistry() {
        FakeSource source = new FakeSource();
        source.shipped = json(Map.of("coop.portMapping", "off"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("off", store.raw("coop.portMapping"));
        assertEquals(Source.SHIPPED, store.sourceOf("coop.portMapping"));
    }

    @Test
    void theCommonOverrideBeatsTheShippedDefault() {
        FakeSource source = new FakeSource();
        source.shipped = json(Map.of("coop.reconnectGraceSeconds", 60));
        source.common = json(Map.of("coop.reconnectGraceSeconds", 120));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals(120, store.integer("coop.reconnectGraceSeconds"));
        assertEquals(Source.COMMON, store.sourceOf("coop.reconnectGraceSeconds"));
    }

    @Test
    void thePropertyBeatsEverything() {
        FakeSource source = new FakeSource();
        source.shipped = json(Map.of("coop.reconnectGraceSeconds", 60));
        source.common = json(Map.of("coop.reconnectGraceSeconds", 120));
        CoopOptionsStore store = new CoopOptionsStore(source,
                props("coop.reconnectGraceSeconds", "5"));

        assertEquals(5, store.integer("coop.reconnectGraceSeconds"));
        assertEquals(Source.PROPERTY, store.sourceOf("coop.reconnectGraceSeconds"));
        assertTrue(store.hasProperty("coop.reconnectGraceSeconds"));
    }

    /**
     * Red-team item 3. {@code -Dcoop.password=} is the only way to run one session without the
     * password the settings file sets, and before this the property was trimmed to null before the
     * layer was chosen - so the file value came straight back and the gate stayed on.
     */
    @Test
    void anExplicitlyEmptyPropertyOverridesTheFileInsteadOfFallingThroughToIt() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.password", "hunter2"));
        CoopOptionsStore store = new CoopOptionsStore(source, props("coop.password", ""));

        assertEquals("", store.raw("coop.password"));
        assertEquals("", store.string("coop.password"));
        assertEquals(Source.PROPERTY, store.sourceOf("coop.password"),
                "an empty -D still means the property layer decided");
    }

    /** Whitespace is the same gesture as empty: the value was cleared, not left unsaid. */
    @Test
    void aWhitespaceOnlyPropertyClearsTheFileValueToo() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.password", "hunter2"));
        CoopOptionsStore store = new CoopOptionsStore(source, props("coop.password", "   "));

        assertEquals("", store.raw("coop.password"));
    }

    /**
     * The other half of item 3: {@code property()} and {@code hasProperty()} keep trim-to-null, so
     * an empty {@code -Dcoop.hostPort=} does not count as "the command line named a role key" in
     * {@code CoopNetStartupConfig}.
     */
    @Test
    void anEmptyPropertyStillDoesNotCountAsTheCommandLineNamingAKey() {
        CoopOptionsStore store = new CoopOptionsStore(new FakeSource(),
                props("coop.hostPort", "", "coop.connectHost", "   "));

        assertFalse(store.hasProperty("coop.hostPort"));
        assertNull(store.property("coop.hostPort"));
        assertFalse(store.hasProperty("coop.connectHost"));
        assertNull(store.property("coop.connectHost"));
    }

    /** An absent property still falls through to the file; only a present one decides. */
    @Test
    void anAbsentPropertyStillFallsThroughToTheFile() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.password", "hunter2"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("hunter2", store.raw("coop.password"));
        assertEquals(Source.COMMON, store.sourceOf("coop.password"));
    }

    // ---- the -D-only set ----------------------------------------------------------------------

    @Test
    void dOnlyKeysIgnoreBothFileLayers() {
        FakeSource source = new FakeSource();
        source.shipped = json(Map.of("coop.newGameSeed", "shipped-seed",
                "coop.debug.diagnostics", true));
        source.common = json(Map.of("coop.newGameSeed", "common-seed",
                "coop.adoptCampaignId", true));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("", store.raw("coop.newGameSeed"));
        assertEquals("false", store.raw("coop.adoptCampaignId"));
        assertEquals("false", store.raw("coop.debug.diagnostics"));
        assertEquals(Source.DEFAULT, store.sourceOf("coop.newGameSeed"));
    }

    @Test
    void aDOnlyKeyNeverTouchesEitherFileLayerAtAll() {
        FakeSource source = new FakeSource();
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        store.raw("coop.newGameSeed");
        store.raw("coop.adoptCampaignId");
        store.raw("coop.debug.bridge");
        store.sourceOf("coop.newGameSeed");

        assertEquals(0, source.commonReads, "a -D-only key must not read saves/common");
        assertEquals(0, source.shippedReads, "a -D-only key must not read the shipped defaults");
    }

    @Test
    void dOnlyKeysStillHonourTheProperty() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.newGameSeed", "common-seed"));
        CoopOptionsStore store = new CoopOptionsStore(source, props("coop.newGameSeed", "12345"));

        assertEquals("12345", store.raw("coop.newGameSeed"));
        assertEquals(Source.PROPERTY, store.sourceOf("coop.newGameSeed"));
    }

    // ---- Phase 31: the one-shot new-game keys --------------------------------------------------

    @Test
    void oneShotKeysAreReadFromTheUserFileTheLauncherWrites() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of(
                "coop.newGameSeed", "MN-42",
                "coop.sectorSize", "small",
                "coop.sectorAge", "young"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("MN-42", store.rawOneShot("coop.newGameSeed"));
        assertEquals("small", store.rawOneShot("coop.sectorSize"));
        assertEquals("young", store.rawOneShot("coop.sectorAge"));
        // raw() is unchanged: the ordinary read path still ignores the file for these.
        assertEquals("", store.raw("coop.newGameSeed"));
    }

    @Test
    void oneShotKeysStillLetTheCommandLineWin() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.newGameSeed", "MN-42"));
        CoopOptionsStore store = new CoopOptionsStore(source, props("coop.newGameSeed", "MN-7"));

        assertEquals("MN-7", store.rawOneShot("coop.newGameSeed"));
    }

    @Test
    void oneShotKeysNeverReadTheShippedDefaults() {
        FakeSource source = new FakeSource();
        source.shipped = json(Map.of("coop.sectorSize", "small"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("", store.rawOneShot("coop.sectorSize"));
    }

    @Test
    void anAbsentOneShotKeyFallsBackToTheRegistryDefault() {
        CoopOptionsStore store = new CoopOptionsStore(new FakeSource(), props());

        assertEquals("", store.rawOneShot("coop.newGameSeed"));
        assertEquals("", store.rawOneShot("coop.sectorSize"));
    }

    @Test
    void launcherOverridesListTheDOnlyKeysPresentInTheUserFileOnly() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of(
                "coop.debug.bridge", "7801",
                "coop.newGameSeed", "MN-42",
                "coop.hostPort", "7777"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        Map<String, String> overrides = store.launcherOverrides();
        assertEquals("7801", overrides.get("coop.debug.bridge"));
        assertEquals("MN-42", overrides.get("coop.newGameSeed"));
        assertEquals(2, overrides.size(), "an ordinary key is not a launcher override: " + overrides);
    }

    @Test
    void noOtherKeyMayBeReadThroughTheOneShotPath() {
        CoopOptionsStore store = new CoopOptionsStore(new FakeSource(), props());

        assertThrows(IllegalArgumentException.class, () -> store.rawOneShot("coop.hostPort"));
        assertThrows(IllegalArgumentException.class, () -> store.rawOneShot("coop.password"));
        // Every -D-only key is readable this way since the launcher's Advanced card exposes them.
        assertEquals("0", store.rawOneShot("coop.debug.bridge"));
        assertEquals("false", store.rawOneShot("coop.adoptCampaignId"));
    }

    @Test
    void aOneShotConsentKeyIsStruckFromTheFileOnceItHasBeenPublished() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of(
                "coop.adoptCampaignId", "true",
                "coop.newGameSeed", "MN-42",
                "coop.hudCorner", "BL"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());
        assertEquals("true", store.rawOneShot("coop.adoptCampaignId"));

        assertTrue(store.consumeOneShot("coop.adoptCampaignId"));

        // The launcher's only channel is this file, and only a launcher-driven launch rewrites it:
        // pre-fix a game started from the desktop shortcut read the previous session's consent again
        // and adopted an in-flight campaign id without anyone being asked.
        assertFalse(store.launcherOverrides().containsKey("coop.adoptCampaignId"));
        assertEquals("false", store.rawOneShot("coop.adoptCampaignId"));
        // Everything else in the file survives, including the keys this build does not write.
        assertEquals("MN-42", store.rawOneShot("coop.newGameSeed"));
        assertEquals("BL", store.raw("coop.hudCorner"));
        assertFalse(store.consumeOneShot("coop.adoptCampaignId"), "nothing left to consume");
        assertEquals(1, source.writes, "one rewrite, and none for the second call");
    }

    @Test
    void onlyAOneShotKeyMayBeConsumed() {
        CoopOptionsStore store = new CoopOptionsStore(new FakeSource(), props());

        assertThrows(IllegalArgumentException.class, () -> store.consumeOneShot("coop.hudCorner"));
    }

    /**
     * The class javadoc's caching rule: a read attempted before the engine exists is not an answer,
     * so nothing may be latched from it.
     */
    @Test
    void aReadThatCannotReachTheEngineDoesNotPinAnEmptyFileLayer() {
        UnreachableSource source = new UnreachableSource();
        source.common = json(Map.of("coop.hudCorner", "BL"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("TR", store.raw("coop.hudCorner"), "no engine yet, so no file layer");

        source.reachable = true;

        // Pre-fix the first read latched flatten(null) = {} on the store itself - the fix underneath
        // it was applied to SettingsJsonSource only - so the process-wide system() store ignored both
        // coop_options.json files for the rest of the run.
        assertEquals("BL", store.raw("coop.hudCorner"));
    }

    /** Answers nothing until the engine is up, exactly as {@code SettingsJsonSource} does. */
    private static final class UnreachableSource implements CoopOptionsStore.JsonSource {
        private JSONObject common;
        private boolean reachable;

        @Override
        public JSONObject shipped() {
            return null;
        }

        @Override
        public JSONObject common() {
            return reachable ? common : null;
        }

        @Override
        public boolean canReachTheEngine() {
            return reachable;
        }
    }

    /**
     * The launcher writes the -D-only keys into the user file, so the "entries this build does not
     * use" warning must stop naming them. A key no build knows still counts.
     */
    @Test
    void aOneShotKeyInTheUserFileIsNotReportedAsAnUnknownEntry() {
        LogCapture appender = LogCapture.attach(CoopOptionsStore.class);
        try {
            FakeSource source = new FakeSource();
            source.common = json(Map.of(
                    "coop.newGameSeed", "MN-42",
                    "coop.adoptCampaignId", "true",
                    "coop.noSuchKey", "1",
                    "coop.hudCorner", "BL"));
            CoopOptionsStore store = new CoopOptionsStore(source, props());

            store.raw("coop.hudCorner");

            String joined = String.join(" | ", appender.messages);
            assertTrue(joined.contains("coop.noSuchKey"), joined);
            assertFalse(joined.contains("coop.adoptCampaignId"), joined);
            assertFalse(joined.contains("coop.newGameSeed"), joined);
        } finally {
            appender.detach();
        }
    }

    @Test
    void theOneShotSetIsExactlyTheRegistrysDOnlyKeys() {
        Set<String> dOnly = new java.util.HashSet<>();
        for (CoopOptionsRegistry.Option option : CoopOptionsRegistry.options()) {
            if (option.dOnly()) {
                dOnly.add(option.key());
            }
        }
        assertEquals(dOnly, CoopOptionsStore.ONE_SHOT_KEYS);
        assertTrue(CoopOptionsStore.ONE_SHOT_KEYS.contains("coop.newGameSeed"));
        assertTrue(CoopOptionsStore.ONE_SHOT_KEYS.contains("coop.debug.bridge"));
    }

    // ---- validation ---------------------------------------------------------------------------

    @Test
    void typedGettersClampAndCanonicalise() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of(
                "coop.reconnectGraceSeconds", 99999,
                "coop.hudCorner", "bl",
                "coop.pauseOnGuestScreens", "FALSE"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals(3600, store.integer("coop.reconnectGraceSeconds"));
        assertEquals("BL", store.string("coop.hudCorner"));
        assertFalse(store.bool("coop.pauseOnGuestScreens"));
    }

    @Test
    void rawSkipsValidationSoStrictCallersStayStrict() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.reconnectGraceSeconds", "not-a-number"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("not-a-number", store.raw("coop.reconnectGraceSeconds"));
        assertEquals(60, store.integer("coop.reconnectGraceSeconds"));
    }

    @Test
    void jsonNumbersAndBooleansAreAcceptedAsWellAsStrings() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of(
                "coop.maxGuests", 1,
                "coop.guestColonizationConsent", true));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals(1, store.integer("coop.maxGuests"));
        assertTrue(store.bool("coop.guestColonizationConsent"));
    }

    @Test
    void typeMismatchesAtTheCallSiteFailLoudly() {
        CoopOptionsStore store = new CoopOptionsStore(new FakeSource(), props());

        assertThrows(IllegalArgumentException.class, () -> store.bool("coop.hudCorner"));
        assertThrows(IllegalArgumentException.class, () -> store.integer("coop.password"));
        assertThrows(IllegalArgumentException.class, () -> store.raw("coop.notAThing"));
        // An optional integer with no value has no sane number to hand back.
        assertThrows(IllegalStateException.class, () -> store.integer("coop.hostPort"));
    }

    // ---- degradation --------------------------------------------------------------------------

    @Test
    void aThrowingSourceDegradesToDefaults() {
        FakeSource source = new FakeSource();
        source.failure = new IllegalStateException("Global.getSettings() is null");
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("auto", store.raw("coop.portMapping"));
        assertEquals(60, store.integer("coop.reconnectGraceSeconds"));
        assertEquals("", store.raw("coop.hostPort"));
    }

    @Test
    void absentLayersAreSimplyEmpty() {
        CoopOptionsStore store = new CoopOptionsStore(new CoopOptionsStore.JsonSource() {
            @Override
            public JSONObject shipped() {
                return null;
            }

            @Override
            public JSONObject common() {
                return null;
            }
        }, props());

        assertEquals("true", store.raw("coop.allowGuestPause"));
    }

    @Test
    void unknownAndDOnlyEntriesInAFileAreIgnoredNotFatal() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of(
                "coop.notAThing", "whatever",
                "coop.newGameSeed", "nope",
                "coop.hudCorner", "BR"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("BR", store.string("coop.hudCorner"));
        assertEquals("", store.raw("coop.newGameSeed"));
    }

    // ---- caching ------------------------------------------------------------------------------

    @Test
    void fileLayersAreReadOnceAndReReadOnReload() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.hudCorner", "BR"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        store.raw("coop.hudCorner");
        store.raw("coop.portMapping");
        store.raw("coop.password");
        assertEquals(1, source.commonReads);
        assertEquals(1, source.shippedReads);

        source.common = json(Map.of("coop.hudCorner", "TL"));
        assertEquals("BR", store.raw("coop.hudCorner"));

        store.reload();
        assertEquals("TL", store.raw("coop.hudCorner"));
        assertEquals(2, source.commonReads);
    }

    @Test
    void propertiesAreReadLiveSoASetPropertyIsPickedUp() {
        Map<String, String> live = new HashMap<>();
        CoopOptionsStore store = new CoopOptionsStore(new FakeSource(), live::get);

        assertEquals("auto", store.raw("coop.portMapping"));
        live.put("coop.portMapping", "off");
        assertEquals("off", store.raw("coop.portMapping"));
    }

    // ---- production wiring ----------------------------------------------------------------------

    /**
     * Red-team item 4. The engine surfaces are memoised process-wide, and the latch used to be set
     * before the read was attempted - so one question asked while {@code Global.getSettings()} was
     * still null cached "there is no config file" for the whole run, and every setting in both files
     * was ignored for the rest of the session.
     */
    @Test
    void aReadBeforeTheEngineExistsDoesNotPoisonTheProcessWideCache() {
        CoopOptionsStore.SettingsJsonSource source = CoopOptionsStore.SettingsJsonSource.INSTANCE;
        source.invalidate();
        try {
            // Global.getSettings() is null in a unit test, which is exactly the situation of a class
            // that asks a config question before the game has finished starting.
            assertNull(source.shipped());
            assertNull(source.common());

            assertFalse(source.shippedCached(),
                    "a read with no engine must leave the shipped layer unresolved");
            assertFalse(source.commonCached(),
                    "a read with no engine must leave the common layer unresolved");
        } finally {
            source.invalidate();
        }
    }

    /**
     * Red-team item 5: one store per property set, so the once-per-key warning memory survives
     * between calls instead of every {@code CoopNetStartupConfig.from(Properties)} re-logging.
     */
    @Test
    void aPropertySetGetsOneMemoisedStoreRatherThanANewOnePerCall() {
        CoopOptionsStore.clearPropertyStoreCache();
        Properties properties = new Properties();
        properties.setProperty("coop.hudCorner", "sideways");

        assertSame(CoopOptionsStore.forProperties(properties),
                CoopOptionsStore.forProperties(properties));
    }

    @Test
    void theSystemStoreIsASingletonAndSurvivesTheAbsenceOfAGame() {
        CoopOptionsStore store = CoopOptionsStore.system();
        assertNotNull(store);
        assertEquals(store, CoopOptionsStore.system());
        // Global.getSettings() is null outside a running game; the store must degrade, not throw.
        assertEquals("auto", store.raw("coop.portMapping"));
        assertEquals("1", store.raw("coop.maxGuests"));
    }

    // ---- Phase 28 milestone 3: writing the user's own overrides ----------------------------------

    @Test
    void writeOverrideRoundTripsThroughTheCommonLayer() {
        FakeSource source = new FakeSource();
        source.shipped = json(Map.of(CoopOptionsRegistry.HUD_CORNER, "TR"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertEquals("TR", store.string(CoopOptionsRegistry.HUD_CORNER));
        assertTrue(store.writeOverride(CoopOptionsRegistry.HUD_CORNER, "BL"));

        assertEquals(1, source.writes);
        assertEquals("BL", store.string(CoopOptionsRegistry.HUD_CORNER),
                "the store must read its own write back without a relaunch");
        assertEquals(Source.COMMON, store.sourceOf(CoopOptionsRegistry.HUD_CORNER));
    }

    @Test
    void writeOverrideValidatesTheValueAndKeepsUnknownKeys() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.somethingFromANewerBuild", "keep me"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertTrue(store.writeOverride(CoopOptionsRegistry.HUD_CORNER, "bl"));

        assertEquals("BL", source.common.opt(CoopOptionsRegistry.HUD_CORNER),
                "the value is canonicalised on the way in");
        assertEquals("keep me", source.common.opt("coop.somethingFromANewerBuild"),
                "an older build must not trim a newer build's settings out of the file");
    }

    @Test
    void writeOverrideWithNullDropsTheOverride() {
        FakeSource source = new FakeSource();
        source.shipped = json(Map.of(CoopOptionsRegistry.HUD_DISABLE, false));
        source.common = json(Map.of(CoopOptionsRegistry.HUD_DISABLE, true));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertTrue(store.bool(CoopOptionsRegistry.HUD_DISABLE));
        assertTrue(store.writeOverride(CoopOptionsRegistry.HUD_DISABLE, null));

        assertFalse(store.bool(CoopOptionsRegistry.HUD_DISABLE));
        assertEquals(Source.SHIPPED, store.sourceOf(CoopOptionsRegistry.HUD_DISABLE));
    }

    @Test
    void writeOverrideRefusesPolicyAndCommandLineOnlyKeys() {
        FakeSource source = new FakeSource();
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertFalse(store.writeOverride(CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "false"),
                "policy belongs to the campaign, not to saves/common");
        assertFalse(store.writeOverride(CoopOptionsRegistry.NEW_GAME_SEED, "12345"),
                "a -D-only key must never become a standing file setting");
        assertEquals(0, source.writes);
    }

    @Test
    void aFailedWriteIsReportedRatherThanPretendedAway() {
        FakeSource source = new FakeSource();
        source.writable = false;
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertFalse(store.writeOverride(CoopOptionsRegistry.HUD_CORNER, "BL"));
        assertEquals("TR", store.string(CoopOptionsRegistry.HUD_CORNER));
    }

    // ---- review item 1: an unreadable user file is never rewritten -------------------------------

    @Test
    void anUnreadableUserFileIsRefusedRatherThanReplacedWithOneKey() {
        BrokenCommonSource source = new BrokenCommonSource();
        source.shipped = json(Map.of(CoopOptionsRegistry.HUD_CORNER, "TR"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertTrue(store.commonUnreadable());
        assertFalse(store.writeOverride(CoopOptionsRegistry.HUD_CORNER, "BL"),
                "a file that will not parse must be fixed by hand, not silently truncated");
        assertEquals(0, source.writes,
                "rewriting it would have thrown away every setting the file still holds");
    }

    @Test
    void aReadThatThrowsIsTreatedAsUnreadableToo() {
        ThrowingSource source = new ThrowingSource();
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertTrue(store.commonUnreadable());
        assertFalse(store.writeOverride(CoopOptionsRegistry.HUD_CORNER, "BL"));
        assertEquals(0, source.writes);
    }

    @Test
    void anAbsentUserFileIsStillWritten() {
        FakeSource source = new FakeSource();
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        assertFalse(store.commonUnreadable(),
                "\"there is no file yet\" is not a failure - creating it is what the first edit does");
        assertTrue(store.writeOverride(CoopOptionsRegistry.HUD_CORNER, "BL"));
        assertEquals(1, source.writes);
    }

    // ---- review item 4: the batch form -----------------------------------------------------------

    @Test
    void writeOverridesRewritesTheFileOnceForTheWholeSweep() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of(
                CoopOptionsRegistry.HUD_CORNER, "BL",
                CoopOptionsRegistry.HUD_DISABLE, true,
                "coop.somethingFromANewerBuild", "keep me"));
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        Map<String, String> cleared = new java.util.LinkedHashMap<>();
        cleared.put(CoopOptionsRegistry.HUD_CORNER, null);
        cleared.put(CoopOptionsRegistry.HUD_DISABLE, null);
        assertTrue(store.writeOverrides(cleared));

        assertEquals(1, source.writes, "one player action is one file rewrite");
        assertEquals("TR", store.string(CoopOptionsRegistry.HUD_CORNER));
        assertFalse(store.bool(CoopOptionsRegistry.HUD_DISABLE));
        assertEquals("keep me", source.common.opt("coop.somethingFromANewerBuild"));
    }

    @Test
    void writeOverridesRefusesTheKeysWriteOverrideRefusesAndKeepsTheRest() {
        FakeSource source = new FakeSource();
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        Map<String, String> mixed = new java.util.LinkedHashMap<>();
        mixed.put(CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "false");
        mixed.put(CoopOptionsRegistry.NEW_GAME_SEED, "12345");
        mixed.put(CoopOptionsRegistry.HUD_CORNER, "BL");
        assertTrue(store.writeOverrides(mixed));

        assertEquals(1, source.writes);
        assertEquals("BL", source.common.opt(CoopOptionsRegistry.HUD_CORNER));
        assertNull(source.common.opt(CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));
        assertNull(source.common.opt(CoopOptionsRegistry.NEW_GAME_SEED));
    }

    @Test
    void writeOverridesRefusesTheWholeSweepWhenTheFileCannotBeRead() {
        BrokenCommonSource source = new BrokenCommonSource();
        CoopOptionsStore store = new CoopOptionsStore(source, props());

        Map<String, String> cleared = new java.util.LinkedHashMap<>();
        cleared.put(CoopOptionsRegistry.HUD_CORNER, null);
        cleared.put(CoopOptionsRegistry.HUD_DISABLE, null);

        assertFalse(store.writeOverrides(cleared));
        assertEquals(0, source.writes);
    }
}
