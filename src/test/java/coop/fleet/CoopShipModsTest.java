package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permanent-hullmod replication rules, and above all the clone-before-mutate invariant: a mirror
 * ship is built from the receiving install's <em>shared global variant spec</em>, so an apply that
 * touched the source object would d-mod or S-mod every clean hull of that variant in the receiving
 * player's universe. That is the failure this class exists to make impossible, so it is tested
 * directly rather than left to a code comment.
 */
class CoopShipModsTest {

    // ---- encode / decode ------------------------------------------------------------------------

    @Test
    void encodeKeepsOnlyMatchingIdsAndSortsThem() {
        List<String> hullMods = List.of("heavyarmor", "compromised_storage", "damagedengines");
        assertEquals("compromised_storage,damagedengines",
                CoopShipMods.encode(hullMods, id -> id.startsWith("c") || id.startsWith("d")));
    }

    @Test
    void encodeSortsSoTwoCapturesOfTheSameShipProduceTheSameBytes() {
        // The field feeds the structural fleet hash; hullmod iteration order is not a contract.
        Set<String> forwards = new LinkedHashSet<>(List.of("structuraldamage", "compromised_storage"));
        Set<String> backwards = new LinkedHashSet<>(List.of("compromised_storage", "structuraldamage"));
        assertEquals(CoopShipMods.encode(forwards, null), CoopShipMods.encode(backwards, null));
    }

    @Test
    void aShipWithNoMatchingModsEncodesAsAnEmptyField() {
        assertEquals("", CoopShipMods.encode(List.of("heavyarmor"), id -> false));
        assertEquals("", CoopShipMods.encode(List.of(), null));
        assertEquals("", CoopShipMods.encode(null, null));
    }

    @Test
    void decodeReversesEncodeAndToleratesBlankFields() {
        assertEquals(List.of("compromised_storage", "damagedengines"),
                CoopShipMods.decode("compromised_storage,damagedengines"));
        assertEquals(List.of(), CoopShipMods.decode(""));
        assertEquals(List.of(), CoopShipMods.decode(null));
        assertEquals(List.of("solo"), CoopShipMods.decode("solo"));
        // A trailing separator must not conjure an empty id the engine would then look up.
        assertEquals(List.of("solo"), CoopShipMods.decode("solo,"));
        assertEquals(2, CoopShipMods.count("a,b"));
        assertEquals(0, CoopShipMods.count(""));
    }

    // ---- apply ----------------------------------------------------------------------------------

    @Test
    void applyMutatesTheCopyAndNeverTheSourceVariant() {
        FakeVariant global = new FakeVariant("wolf_Assault");
        RecordingOps ops = new RecordingOps(global);

        assertTrue(CoopShipMods.apply("compromised_storage,damagedengines", "heavyarmor",
                "solar_shielding", true, ops));

        // The source is the shared spec store object: it must come out exactly as it went in.
        assertTrue(global.dmods.isEmpty());
        assertTrue(global.sMods.isEmpty());
        assertTrue(global.sModdedBuiltIns.isEmpty());
        assertFalse(global.damagedHull);

        FakeVariant installed = ops.installed;
        assertFalse(installed == global);
        assertSame(installed, ops.copies.get(0));
        assertEquals(List.of("compromised_storage", "damagedengines"), installed.dmods);
        assertEquals(List.of("heavyarmor"), installed.sMods);
        assertEquals(List.of("solar_shielding"), installed.sModdedBuiltIns);
    }

    @Test
    void applyRefusesWhenTheCopyIsTheSourceItself() {
        // A VariantOps whose copyOf is a no-op is the exact bug this guard exists for; the refusal
        // costs one clean-looking mirror ship instead of every falcon_Assault in the universe.
        FakeVariant global = new FakeVariant("falcon_Assault");
        RecordingOps ops = new RecordingOps(global);
        ops.copyReturnsSource = true;

        assertFalse(CoopShipMods.apply("compromised_storage", "heavyarmor", "", true, ops));

        assertTrue(global.dmods.isEmpty());
        assertTrue(global.sMods.isEmpty());
        assertFalse(global.damagedHull);
        assertEquals(null, ops.installed);
    }

    @Test
    void theDamagedHullSwapRunsOnlyWhenTheCallerSaysTheOwnersHullIsDamaged() {
        // An S-modded but undamaged ship must not arrive wearing a damaged hull.
        RecordingOps sModsOnly = new RecordingOps(new FakeVariant("wolf_Assault"));
        assertTrue(CoopShipMods.apply("", "heavyarmor", "", false, sModsOnly));
        assertFalse(sModsOnly.installed.damagedHull);
        assertEquals(List.of("heavyarmor"), sModsOnly.installed.sMods);

        RecordingOps withDmods = new RecordingOps(new FakeVariant("wolf_Assault"));
        assertTrue(CoopShipMods.apply("damagedengines", "", "", true, withDmods));
        assertTrue(withDmods.installed.damagedHull);

        // D-mods without the damaged hull: DModManager.addDMods never swaps the hull itself, so this
        // is a real shape and the mirror must not invent the "(D)" designation.
        RecordingOps dmodsOnCleanHull = new RecordingOps(new FakeVariant("wolf_Assault"));
        assertTrue(CoopShipMods.apply("damagedengines", "", "", false, dmodsOnCleanHull));
        assertFalse(dmodsOnCleanHull.installed.damagedHull);
        assertEquals(List.of("damagedengines"), dmodsOnCleanHull.installed.dmods);

        // The damaged hull with no acquired d-mods (all of them built into the hull) still swaps.
        RecordingOps hullOnly = new RecordingOps(new FakeVariant("colossus2_Standard"));
        assertTrue(CoopShipMods.apply("", "", "", true, hullOnly));
        assertTrue(hullOnly.installed.damagedHull);
        assertTrue(hullOnly.installed.dmods.isEmpty());
    }

    @Test
    void theDamagedHullFlagIsReadOffTheStreamedHullIdNotTheDmodList() {
        // Misc.getDHullId is hullId + "_default_D", so the suffix is the flag: no new wire field.
        assertTrue(CoopShipMods.damagedHull("falcon_default_D", ""));
        assertTrue(CoopShipMods.damagedHull("falcon_default_D", "damagedengines"));
        assertFalse(CoopShipMods.damagedHull("falcon", "damagedengines"),
                "a clean hull stays clean even when the ship carries d-mods");
        assertFalse(CoopShipMods.damagedHull("falcon", ""));
        // Only when the sender could not name a hull at all does the d-mod list decide, which is the
        // pre-2026-09-07 rule.
        assertTrue(CoopShipMods.damagedHull("", "damagedengines"));
        assertFalse(CoopShipMods.damagedHull("", ""));
        assertFalse(CoopShipMods.damagedHull(null, null));
    }

    @Test
    void applyInstallsExactlyTheIdsTheSenderNamedAndNeverRollsMore() {
        // The receiver has no DModManager.addDMods and no FleetInflater on the mirror fleet; the
        // guard here is that apply() cannot grow the set on its own either.
        RecordingOps ops = new RecordingOps(new FakeVariant("falcon_Assault"));

        assertTrue(CoopShipMods.apply("compromised_storage", "", "", true, ops));

        assertEquals(List.of("compromised_storage"), ops.installed.dmods);
        assertEquals(1, ops.copies.size(), "one copy per apply, no speculative rebuilds");
    }

    @Test
    void aCleanShipCostsNothing() {
        RecordingOps ops = new RecordingOps(new FakeVariant("wolf_Assault"));

        assertFalse(CoopShipMods.apply("", "", "", false, ops));

        assertTrue(ops.copies.isEmpty());
        assertEquals(null, ops.installed);
    }

    @Test
    void aMemberWithNoReadableVariantIsSkippedRatherThanCrashing() {
        RecordingOps ops = new RecordingOps(null);
        assertFalse(CoopShipMods.apply("damagedengines", "heavyarmor", "", true, ops));
        assertEquals(null, ops.installed);
    }

    /** Stand-in for {@code ShipVariantAPI} carrying only the state this replication touches. */
    private static final class FakeVariant {
        private final String id;
        private final List<String> dmods = new ArrayList<>();
        private final List<String> sMods = new ArrayList<>();
        private final List<String> sModdedBuiltIns = new ArrayList<>();
        private boolean damagedHull;

        private FakeVariant(String id) {
            this.id = id;
        }

        private FakeVariant copy() {
            FakeVariant copy = new FakeVariant(id);
            copy.dmods.addAll(dmods);
            copy.sMods.addAll(sMods);
            copy.sModdedBuiltIns.addAll(sModdedBuiltIns);
            copy.damagedHull = damagedHull;
            return copy;
        }
    }

    private static final class RecordingOps implements CoopShipMods.VariantOps<FakeVariant> {
        private final FakeVariant source;
        private final List<FakeVariant> copies = new ArrayList<>();
        private FakeVariant installed;
        private boolean copyReturnsSource;

        private RecordingOps(FakeVariant source) {
            this.source = source;
        }

        @Override
        public FakeVariant currentVariant() {
            return source;
        }

        @Override
        public FakeVariant copyOf(FakeVariant variant) {
            FakeVariant copy = copyReturnsSource ? variant : variant.copy();
            copies.add(copy);
            return copy;
        }

        @Override
        public void setDamagedHull(FakeVariant variant) {
            variant.damagedHull = true;
        }

        @Override
        public void addDmod(FakeVariant variant, String hullModId) {
            variant.dmods.add(hullModId);
        }

        @Override
        public void addSMod(FakeVariant variant, String hullModId) {
            variant.sMods.add(hullModId);
        }

        @Override
        public void addSModdedBuiltIn(FakeVariant variant, String hullModId) {
            variant.sModdedBuiltIns.add(hullModId);
        }

        @Override
        public void install(FakeVariant variant) {
            installed = variant;
        }
    }
}
