package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the 2026-08-19 "guest mirrors wore the wrong roster" investigation: the host's
 * roster capture must degrade one ship at a time, never truncate.
 */
class CoopFleetSnapshotFactoryTest {

    /** A roster where any slot can be made to throw, standing in for an engine {@code FleetMember}. */
    private static final class FakeSource implements CoopFleetSnapshotFactory.MemberSource {
        private final List<String> hullIds;
        private final List<Integer> throwingSlots;
        private final List<Integer> wingSlots;

        FakeSource(List<String> hullIds, List<Integer> throwingSlots, List<Integer> wingSlots) {
            this.hullIds = hullIds;
            this.throwingSlots = throwingSlots;
            this.wingSlots = wingSlots;
        }

        @Override
        public int size() {
            return hullIds.size();
        }

        @Override
        public boolean isFighterWing(int index) {
            return wingSlots.contains(index);
        }

        @Override
        public CoopFleetSnapshot.Member capture(int index) {
            if (throwingSlots.contains(index)) {
                throw new IllegalStateException("ship " + index + " cannot report its hull");
            }
            String hullId = hullIds.get(index);
            return new CoopFleetSnapshot.Member(hullId + index, hullId, hullId + "_Standard",
                    "Ship " + index, "", 1f, 1f);
        }
    }

    private static List<CoopFleetSnapshot.Member> capture(List<String> hullIds,
                                                          List<Integer> throwingSlots,
                                                          List<Integer> wingSlots) {
        List<CoopFleetSnapshot.Member> out = new ArrayList<>();
        CoopFleetSnapshotFactory.captureInto(out, new FakeSource(hullIds, throwingSlots, wingSlots));
        return out;
    }

    @Test
    void oneUnreadableShipCostsOnlyThatShip() {
        // The old single-try-around-the-loop form returned [hound] here and dropped everything after
        // the throw. The guest then latched that truncated roster: its structural hash is stable, so
        // CoopFleetMirror's gate accepts it once and never rebuilds.
        List<CoopFleetSnapshot.Member> members =
                capture(List.of("hound", "cerberus", "mule", "nebula"), List.of(1), List.of());
        assertEquals("hound x1, mule x1, nebula x1", CoopRosterSummary.ofMembers(members));
    }

    @Test
    void aThrowOnTheFirstShipNoLongerEmptiesTheWholeRoster() {
        // This is the case that reached the guest as "roster refreshed to 0 ship(s)".
        List<CoopFleetSnapshot.Member> members =
                capture(List.of("hound", "cerberus", "mule"), List.of(0), List.of());
        assertEquals("cerberus x1, mule x1", CoopRosterSummary.ofMembers(members));
    }

    @Test
    void skippedShipsAreCounted() {
        List<CoopFleetSnapshot.Member> out = new ArrayList<>();
        int skipped = CoopFleetSnapshotFactory.captureInto(out,
                new FakeSource(List.of("hound", "cerberus", "mule"), List.of(0, 2), List.of()));
        assertEquals(2, skipped);
        assertEquals(1, out.size());
    }

    @Test
    void fighterWingsAreStillExcludedAndDoNotCountAsFailures() {
        List<CoopFleetSnapshot.Member> out = new ArrayList<>();
        int skipped = CoopFleetSnapshotFactory.captureInto(out,
                new FakeSource(List.of("hound", "talon_wing", "mule"), List.of(), List.of(1)));
        assertEquals(0, skipped);
        assertEquals("hound x1, mule x1", CoopRosterSummary.ofMembers(out));
    }

    // ---- Resolvable-by-construction ship ids ---------------------------------------------------

    /** Stands in for this install's spec store. */
    private static Predicate<String> installHas(String... ids) {
        Set<String> known = new HashSet<>(Arrays.asList(ids));
        return known::contains;
    }

    @Test
    void anInflatedShipStreamsTheStockVariantItWasAutofitFrom() {
        // The live variant id of an inflated ship is DefaultFleetInflater's
        // createEmptyVariant(fleet.getId() + "_" + memberIndex, ...) — "905d_3" here — which exists
        // only inside the host's engine. The inflater records where it autofit from in
        // getOriginalVariant(); that is the id both installs share.
        assertEquals("falcon_Assault", CoopFleetSnapshotFactory.streamableVariantId(
                "falcon_Assault", "905d_3", "905d_3", installHas("falcon_Assault")));
    }

    @Test
    void aRuntimeVariantIdIsNeverPutOnTheWire() {
        // Nothing resolvable: send "" so the receiver takes the hull path, rather than an id that
        // makes createFleetMember substitute a placeholder ship without complaining.
        assertEquals("", CoopFleetSnapshotFactory.streamableVariantId(
                "", "905d_3", "905d_3", installHas("falcon_Assault")));
    }

    @Test
    void aStockVariantIsStreamedUnchangedWhenThereIsNoOriginal() {
        assertEquals("falcon_Assault", CoopFleetSnapshotFactory.streamableVariantId(
                "", "falcon_Assault", "falcon_Assault", installHas("falcon_Assault")));
    }

    @Test
    void aDHullIsKeptWhenItResolvesAndStrippedWhenItDoesNot() {
        // D hulls are generated at load from the same ship data on both installs, so normally they
        // resolve and the mirror keeps the damaged hull; the strip is the safety net.
        assertEquals("falcon_default_D", CoopFleetSnapshotFactory.streamableHullId(
                "falcon_default_D", installHas("falcon", "falcon_default_D")));
        assertEquals("falcon", CoopFleetSnapshotFactory.streamableHullId(
                "falcon_default_D", installHas("falcon")));
    }

    @Test
    void baseHullIdIsTheInverseOfMiscGetDHullId() {
        assertEquals("falcon", CoopFleetSnapshotFactory.baseHullId("falcon_default_D"));
        assertEquals("falcon", CoopFleetSnapshotFactory.baseHullId("falcon"));
        assertEquals("", CoopFleetSnapshotFactory.baseHullId(null));
    }

    // ---- D-mod capture ---------------------------------------------------------------------------

    @Test
    void anAcquiredDmodIsStreamedEvenThoughItIsAPermaMod() {
        // The 2026-09-06 live smoke's "visible differences in fleet d-mods between host and guest".
        // Capture read getNonBuiltInHullmods(), which drops every perma-mod as well as every built-in
        // (HullVariantSpec: `isBuiltInMod(id) || getPermaMods().contains(id)`), and DModManager only
        // ever installs a d-mod as addPermaMod(id, false) — so the field was empty for every ship in
        // the game and every mirror rendered pristine. The engine's own predicate
        // (DModManager.getNumNonBuiltInDMods) walks getHullMods() and subtracts the hull's built-ins.
        Global.setSettings(hullModSpecs("ill_advised", "compromised_storage", "damagedengines"));
        try {
            assertEquals("compromised_storage,damagedengines", CoopFleetSnapshotFactory.captureDmodIds(
                    variant(List.of("compromised_storage", "damagedengines", "augmented_drive"),
                            List.of("compromised_storage", "damagedengines"), List.of())),
                    "d-mods are perma-mods; a capture that skips perma-mods captures nothing");
        } finally {
            Global.setSettings(null);
        }
    }

    @Test
    void aHullsOwnBuiltInDmodIsNotStreamedAsAcquiredDamage() {
        // vanilla colossus2 builds in ill_advised, which carries the dmod tag (the only stock hull
        // that does). Streaming it made the receiver run DModManager.setDHull on a pristine ship: the
        // mirror wore the _D hull, sprite and "(D)" designation its owner's ship does not, baked into
        // the structural hash. The hull's built-ins reach the receiver through hullId/variantId.
        Global.setSettings(hullModSpecs("ill_advised", "compromised_storage"));
        try {
            assertEquals("", CoopFleetSnapshotFactory.captureDmodIds(
                    variant(List.of("ill_advised", "heavyarmor"), List.of(), List.of("ill_advised"))));
            assertEquals("compromised_storage", CoopFleetSnapshotFactory.captureDmodIds(
                    variant(List.of("ill_advised", "compromised_storage"),
                            List.of("compromised_storage"), List.of("ill_advised"))),
                    "a d-mod the ship actually acquired still travels alongside a built-in one");
        } finally {
            Global.setSettings(null);
        }
    }

    @Test
    void aVariantThatCannotReportItsHullSpecCapturesAsClean() {
        // Failing open would put the hull's built-ins back on the wire, which is the bug above.
        Global.setSettings(hullModSpecs("ill_advised"));
        try {
            assertEquals("", CoopFleetSnapshotFactory.captureDmodIds(throwingVariant()));
            assertEquals("", CoopFleetSnapshotFactory.captureDmodIds(null));
        } finally {
            Global.setSettings(null);
        }
    }

    /**
     * A variant whose hull mods are {@code hullMods} (perma-mods included, as the engine's own
     * {@code getHullMods()} reports them) and whose hull spec builds in {@code builtIns}.
     */
    private static ShipVariantAPI variant(List<String> hullMods, List<String> permaMods,
                                          List<String> builtIns) {
        Object hullSpec = Proxy.newProxyInstance(
                ShipHullSpecAPI.class.getClassLoader(),
                new Class<?>[] {ShipHullSpecAPI.class},
                (spec, method, args) -> switch (method.getName()) {
                    case "getBuiltInMods" -> builtIns;
                    case "isBuiltInMod" -> builtIns.contains((String) args[0]);
                    case "toString" -> "FakeHullSpec";
                    case "hashCode" -> System.identityHashCode(spec);
                    case "equals" -> spec == args[0];
                    default -> null;
                });
        return (ShipVariantAPI) Proxy.newProxyInstance(
                ShipVariantAPI.class.getClassLoader(),
                new Class<?>[] {ShipVariantAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHullMods" -> hullMods;
                    case "getPermaMods" -> new HashSet<>(permaMods);
                    case "getHullSpec" -> hullSpec;
                    // The engine's own filter, so a capture that reaches for the convenient-looking
                    // method gets the empty answer that shipped the bug rather than a passing test.
                    case "getNonBuiltInHullmods" -> hullMods.stream()
                            .filter(id -> !builtIns.contains(id) && !permaMods.contains(id))
                            .toList();
                    case "toString" -> "FakeVariant";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** A variant that throws the moment its hull spec is read. */
    private static ShipVariantAPI throwingVariant() {
        return (ShipVariantAPI) Proxy.newProxyInstance(
                ShipVariantAPI.class.getClassLoader(),
                new Class<?>[] {ShipVariantAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHullMods" -> List.of("ill_advised");
                    case "getHullSpec" -> throw new IllegalStateException("no hull spec");
                    case "toString" -> "ThrowingVariant";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** Settings whose hullmod specs carry the d-mod tag for exactly {@code dmodIds}. */
    private static SettingsAPI hullModSpecs(String... dmodIds) {
        Set<String> dmods = new HashSet<>(Arrays.asList(dmodIds));
        return (SettingsAPI) Proxy.newProxyInstance(
                SettingsAPI.class.getClassLoader(),
                new Class<?>[] {SettingsAPI.class},
                (settings, method, args) -> {
                    if (!"getHullModSpec".equals(method.getName())) {
                        return method.getName().equals("toString") ? "FakeSettings" : null;
                    }
                    boolean dmod = dmods.contains((String) args[0]);
                    return Proxy.newProxyInstance(
                            HullModSpecAPI.class.getClassLoader(),
                            new Class<?>[] {HullModSpecAPI.class},
                            (spec, specMethod, specArgs) -> switch (specMethod.getName()) {
                                case "hasTag" -> dmod && Tags.HULLMOD_DMOD.equals(specArgs[0]);
                                case "toString" -> "FakeHullModSpec";
                                case "hashCode" -> System.identityHashCode(spec);
                                case "equals" -> spec == specArgs[0];
                                default -> null;
                            });
                });
    }

    // ---- Partial rosters -------------------------------------------------------------------------

    @Test
    void aCaptureSaysHowMuchOfTheRosterItLost() {
        // captureMembers answers "four ships" and "four of six, two threw" with the same list. That is
        // the right trade for the 10 Hz mirror stream and the wrong one for CoopBattleBridge, whose
        // survivor list deletes every ship it does not name.
        assertFalse(new CoopFleetSnapshotFactory.Capture(
                capture(List.of("wolf", "lasher"), List.of(), List.of()), 0).partial());
        assertTrue(new CoopFleetSnapshotFactory.Capture(
                capture(List.of("wolf", "lasher"), List.of(1), List.of()), 1).partial());
        assertEquals(1, capture(List.of("wolf", "lasher"), List.of(1), List.of()).size());
    }
}
