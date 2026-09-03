package coop.config;

import coop.config.CoopOptionsStore.Source;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopOptionsStoreTest {

    /** A JSON source with both layers held in memory, plus a counter so reads can be asserted on. */
    private static final class FakeSource implements CoopOptionsStore.JsonSource {
        private JSONObject shipped;
        private JSONObject common;
        private int shippedReads;
        private int commonReads;
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
        public void invalidate() {
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

    @Test
    void aBlankPropertyIsTreatedAsAbsent() {
        FakeSource source = new FakeSource();
        source.common = json(Map.of("coop.password", "hunter2"));
        CoopOptionsStore store = new CoopOptionsStore(source, props("coop.password", "   "));

        assertEquals("hunter2", store.raw("coop.password"));
        assertFalse(store.hasProperty("coop.password"));
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

    @Test
    void theSystemStoreIsASingletonAndSurvivesTheAbsenceOfAGame() {
        CoopOptionsStore store = CoopOptionsStore.system();
        assertNotNull(store);
        assertEquals(store, CoopOptionsStore.system());
        // Global.getSettings() is null outside a running game; the store must degrade, not throw.
        assertEquals("auto", store.raw("coop.portMapping"));
        assertEquals("1", store.raw("coop.maxGuests"));
    }
}
