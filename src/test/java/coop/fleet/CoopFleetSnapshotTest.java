package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopFleetSnapshotTest {
    private static CoopFleetSnapshot.Member member(String id, String hull, String variant) {
        return new CoopFleetSnapshot.Member(id, hull, variant, "Ship " + id, "Captain " + id, 0.7f, 0.9f);
    }

    @Test
    void fleetHashIsIndependentOfMemberIterationOrder() {
        List<CoopFleetSnapshot.Member> first = List.of(
                member("m3", "onslaught", "onslaught_Standard"),
                member("m1", "wolf", "wolf_Assault"),
                member("m2", "lasher", "lasher_CS"));
        List<CoopFleetSnapshot.Member> second = List.of(
                member("m2", "lasher", "lasher_CS"),
                member("m3", "onslaught", "onslaught_Standard"),
                member("m1", "wolf", "wolf_Assault"));

        assertEquals(
                CoopFleetSnapshot.computeFleetHash(first),
                CoopFleetSnapshot.computeFleetHash(second));
    }

    @Test
    void fleetHashChangesWhenMemberHullOrVariantChanges() {
        List<CoopFleetSnapshot.Member> base = List.of(member("m1", "wolf", "wolf_Assault"));
        String baseHash = CoopFleetSnapshot.computeFleetHash(base);

        assertNotEquals(baseHash,
                CoopFleetSnapshot.computeFleetHash(List.of(member("m1", "lasher", "wolf_Assault"))));
        assertNotEquals(baseHash,
                CoopFleetSnapshot.computeFleetHash(List.of(member("m1", "wolf", "wolf_CS"))));
    }

    @Test
    void fleetHashIgnoresCrAndHullFractionEntirely() {
        // The hash gates a full mirror-roster teardown/rebuild, so it must be structural only.
        // CR/hull were originally hashed rounded to a whole percent, and at 10 game-seconds per real
        // second a repairing fleet crossed a percent boundary every second or two: a save full of
        // damaged NPC fleets rebuilt ~10 rosters per second and dropped the guest to 39 fps
        // (measured 2026-08-17). Repair state is applied in place by the mirror instead.
        List<CoopFleetSnapshot.Member> base = List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela", 0.70f, 0.90f));
        String baseHash = CoopFleetSnapshot.computeFleetHash(base);

        assertEquals(baseHash, CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela", 0.10f, 0.25f))));
        // Structural identity still flips it.
        assertNotEquals(baseHash, CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Renamed", 0.70f, 0.90f))));
    }

    @Test
    void encodeDecodeRoundTripsHeaderAndMembers() {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "player-1", "Alice", "corvus", 123.5f, -42.25f, 1.5f, -2.5f, "player", true,
                sensors(650f, 420f),
                List.of(member("m1", "wolf", "wolf_Assault"), member("m2", "lasher", "lasher_CS")));

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decodeFull(snapshot.encodeFull());

        assertEquals(snapshot, decoded);
    }

    @Test
    void encodeDecodePreservesDelimiterAndNewlineCharactersInNames() {
        CoopFleetSnapshot.Member tricky = new CoopFleetSnapshot.Member(
                "m|1", "wolf", "wolf_Assault", "ISS Pipe|Wolf", "Line1\nLine2", 0.5f, 0.5f);
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p|id", "Na|me\nBreak", "loc\nid", 0f, 0f, 0f, 0f, "fac|tion", false, sensors(650f, 420f), List.of(tricky));

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decodeFull(snapshot.encodeFull());

        assertEquals(snapshot, decoded);
        assertEquals("ISS Pipe|Wolf", decoded.members().get(0).shipName());
        assertEquals("Line1\nLine2", decoded.members().get(0).captainName());
    }

    // ---- Phase 12b: U+001F escaping ------------------------------------------------------------

    @Test
    void unitSeparatorInPlayerEditableNamesRoundTrips() {
        // U+001F is the datagram envelope's record separator. Ship and captain names are
        // player-editable, so an unescaped one used to split a record mid-field on the wire.
        String sep = String.valueOf((char) 0x1F);
        CoopFleetSnapshot.Member tricky = new CoopFleetSnapshot.Member(
                "m1", "wolf", "wolf_Assault", "ISS" + sep + "Wolf", "Cpt" + sep + "Ahab", 0.7f, 0.9f);
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p" + sep + "1", "Ali" + sep + "ce", "cor" + sep + "vus",
                0f, 0f, 0f, 0f, "play" + sep + "er", true, sensors(650f, 420f), List.of(tricky));

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decodeFull(snapshot.encodeFull());

        assertEquals(snapshot, decoded);
        assertEquals("ISS" + sep + "Wolf", decoded.members().get(0).shipName());
        assertEquals("Cpt" + sep + "Ahab", decoded.members().get(0).captainName());
    }

    @Test
    void escapedUnitSeparatorDoesNotSurviveIntoTheEncodedForm() {
        String sep = String.valueOf((char) 0x1F);
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p1", "Alice", "corvus", 0f, 0f, 0f, 0f, "player", true, sensors(650f, 420f),
                List.of(new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault",
                        "ISS" + sep + "Wolf", "Cpt", 0.7f, 0.9f)));

        assertFalse(snapshot.encodeFull().contains(sep),
                "a raw U+001F in the encoded payload would split the datagram record");
    }

    // ---- Phase 16: replicated permanent hullmods -----------------------------------------------

    @Test
    void permanentHullModFieldsRoundTripInEveryCombination() {
        // Clean, d-mods only, S-mods only, S-modded built-ins only, and all three together: the four
        // shapes a real roster mixes, plus the empty one that must not turn into a phantom mod id.
        List<CoopFleetSnapshot.Member> members = List.of(
                new CoopFleetSnapshot.Member("m0", "wolf", "wolf_Assault", "Clean", "", 1f, 1f,
                        "", "", ""),
                new CoopFleetSnapshot.Member("m1", "falcon_default_D", "falcon_Assault", "Battered",
                        "", 0.4f, 0.6f, "compromised_storage,damagedengines", "", ""),
                new CoopFleetSnapshot.Member("m2", "wolf", "wolf_Assault", "Refit", "", 1f, 1f,
                        "", "heavyarmor,hardenedshieldemitter", ""),
                new CoopFleetSnapshot.Member("m3", "hound", "hound_Standard", "Built-in", "", 1f, 1f,
                        "", "", "solar_shielding"),
                new CoopFleetSnapshot.Member("m4", "onslaught_default_D", "onslaught_Standard",
                        "Veteran", "Ahab", 0.7f, 0.8f, "structuraldamage", "heavyarmor",
                        "armoredweapons"));
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p1", "Alice", "corvus", 0f, 0f, 0f, 0f, "player", true, sensors(650f, 420f), members);

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decodeFull(snapshot.encodeFull());

        assertEquals(snapshot, decoded);
        assertEquals("", decoded.members().get(0).dmodIds());
        assertEquals("compromised_storage,damagedengines", decoded.members().get(1).dmodIds());
        assertEquals("heavyarmor,hardenedshieldemitter", decoded.members().get(2).sModIds());
        assertEquals("solar_shielding", decoded.members().get(3).sModdedBuiltInIds());
        assertEquals("structuraldamage", decoded.members().get(4).dmodIds());
        assertEquals("heavyarmor", decoded.members().get(4).sModIds());
        assertEquals("armoredweapons", decoded.members().get(4).sModdedBuiltInIds());
    }

    @Test
    void theSevenArgumentMemberIsTheCleanShip() {
        CoopFleetSnapshot.Member member = member("m1", "wolf", "wolf_Assault");
        assertEquals("", member.dmodIds());
        assertEquals("", member.sModIds());
        assertEquals("", member.sModdedBuiltInIds());
    }

    @Test
    void gainingOrLosingAPermanentHullModFlipsTheFleetHash() {
        // The mirror bakes these into the ship at build time, so the roster gate has to notice —
        // otherwise a ship that just picked up three d-mods keeps mirroring as pristine forever.
        String clean = CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "", 1f, 1f)));

        assertNotEquals(clean, CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "", 1f, 1f,
                        "damagedengines", "", ""))));
        assertNotEquals(clean, CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "", 1f, 1f,
                        "", "heavyarmor", ""))));
        assertNotEquals(clean, CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "", 1f, 1f,
                        "", "", "solar_shielding"))));
    }

    @Test
    void createComputesMatchingFleetHash() {
        List<CoopFleetSnapshot.Member> members = List.of(member("m1", "wolf", "wolf_Assault"));
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p1", "Alice", "corvus", 0f, 0f, 0f, 0f, "player", true, sensors(650f, 420f), members);

        assertEquals(CoopFleetSnapshot.computeFleetHash(members), snapshot.fleetHash());
    }

    // ---- wire-format float quantization --------------------------------------------------------

    @Test
    void onGridPositionsAndFractionsRoundTripExactly() {
        // Multiples of the 0.25 su / 0.001 fraction grids survive the encode untouched.
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p1", "Alice", "corvus", -14625.25f, 3080.75f, -14f, 3.25f, "player", true,
                sensors(650f, 420f),
                List.of(new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela",
                        0.85f, 0.667f)));

        assertEquals(snapshot, CoopFleetSnapshot.decodeFull(snapshot.encodeFull()));
    }

    @Test
    void offGridPositionsAndFractionsAreQuantizedToShortDecimals() {
        // Live values are arbitrary floats; at full precision every one of these costs 8-11 digits,
        // ten times a second. The header is x|y|vx|vy at indices 3-6, cr and hull at member 5 and 6.
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p1", "Alice", "corvus", -2324.3894f, 14625.111f, -14.077f, 3.3333333f, "player", true,
                sensors(650f, 420f),
                List.of(new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela",
                        0.84999996f, 0.6666667f)));

        String[] lines = snapshot.encodeFull().split("\n", -1);
        List<String> header = CoopFleetCodec.split(lines[0]);
        List<String> member = CoopFleetCodec.split(lines[1]);

        assertEquals("-2324.5", header.get(3));
        assertEquals("14625.0", header.get(4));
        assertEquals("-14.0", header.get(5));
        assertEquals("3.25", header.get(6));
        assertEquals("0.85", member.get(5));
        assertEquals("0.667", member.get(6));

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decodeFull(snapshot.encodeFull());
        assertEquals(-2324.5f, decoded.x());
        assertEquals(0.85f, decoded.members().get(0).cr());
        // Quantized once, stable forever: re-encoding the decoded snapshot is byte-identical.
        assertEquals(snapshot.encodeFull(), decoded.encodeFull());
    }

    @Test
    void quantizationNeverReachesTheFleetHashOrTheSnapshotItself() {
        // The hash gates the roster teardown/rebuild and takes structural strings only (see
        // fleetHashIgnoresCrAndHullFractionEntirely). Encoding must not round anything behind it, and
        // must not mutate the in-memory snapshot the local game still reads.
        List<CoopFleetSnapshot.Member> members = List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela",
                        0.84999996f, 0.6666667f));
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p1", "Alice", "corvus", -2324.3894f, 14625.111f, -14.077f, 3.3333333f, "player", true,
                sensors(650.37f, 419.9666f), members);

        String encoded = snapshot.encodeFull();

        assertEquals(CoopFleetSnapshot.computeFleetHash(members), snapshot.fleetHash());
        assertEquals(-2324.3894f, snapshot.x());
        assertEquals(0.84999996f, snapshot.members().get(0).cr());
        assertEquals(650.37f, snapshot.sensors().sensorProfile());
        // The hash on the wire is the one computed from the unquantized members.
        assertEquals(snapshot.fleetHash(),
                CoopFleetCodec.split(encoded.split("\n", -1)[0]).get(14));
    }

    @Test
    void quantizeSnapsToTheDecimalGridAndLeavesOddValuesAlone() {
        assertEquals(0.85f, CoopFleetCodec.quantize(0.84999996f, CoopFleetCodec.FRACTION_STEP));
        assertEquals(-14625.25f, CoopFleetCodec.quantize(-14625.3f, CoopFleetCodec.POSITION_STEP));
        assertEquals(0f, CoopFleetCodec.quantize(0.1f, CoopFleetCodec.POSITION_STEP));
        // Non-finite and out-of-grid-range values pass through rather than turning into garbage.
        assertEquals(Float.NaN, CoopFleetCodec.quantize(Float.NaN, CoopFleetCodec.POSITION_STEP));
        assertEquals(Float.POSITIVE_INFINITY,
                CoopFleetCodec.quantize(Float.POSITIVE_INFINITY, CoopFleetCodec.POSITION_STEP));
        assertEquals(Float.MAX_VALUE,
                CoopFleetCodec.quantize(Float.MAX_VALUE, CoopFleetCodec.POSITION_STEP));
    }


    // ---- Phase 20 M4 roster split -----------------------------------------------------------------

    @Test
    void theWireHashIsTheFirstSixteenHexOfTheFullHash() {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create("p1", "Alice", "corvus", 1f, 2f, 3f, 4f,
                "player", true, sensors(300f, 200f), List.of(member("m1", "wolf", "wolf_Assault")));

        assertEquals(16, snapshot.fleetHash16().length());
        assertTrue(snapshot.fleetHash().startsWith(snapshot.fleetHash16()));
        assertEquals("abc", CoopFleetSnapshot.hash16("abc"), "shorter hashes pass through");
        assertEquals("", CoopFleetSnapshot.hash16(null));
    }

    @Test
    void aTickRoundTripsEverythingTheMirrorReadsPerFrame() {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create("p1", "Alice", "corvus",
                -2324.25f, 14625.0f, -14f, 3.25f, "player", true,
                new CoopSensorSync.Profile(650.5f, 120.5f, 25.5f, 0.875f, 419.5f),
                List.of(member("m1", "wolf", "wolf_Assault"), member("m2", "lasher", "lasher_CS")));

        CoopFleetSnapshot.Tick tick = CoopFleetSnapshot.Tick.of(snapshot);
        CoopFleetSnapshot.Tick decoded = CoopFleetSnapshot.Tick.decode(tick.encode());

        assertEquals(tick, decoded);
        assertEquals("corvus", decoded.locationId());
        assertEquals(-2324.25f, decoded.x());
        assertEquals(snapshot.fleetHash16(), decoded.fleetHash16());
        assertEquals(2, decoded.members().size());
        assertEquals(0.7f, decoded.members().get(0).cr());
        assertEquals(0.9f, decoded.members().get(1).hullFraction());
    }

    @Test
    void anEmptyFleetTickRoundTrips() {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create("p1", "Alice", "corvus", 0f, 0f, 0f, 0f,
                "player", false, sensors(300f, 200f), List.of());

        CoopFleetSnapshot.Tick tick = CoopFleetSnapshot.Tick.of(snapshot);

        assertEquals(tick, CoopFleetSnapshot.Tick.decode(tick.encode()));
        assertFalse(CoopFleetSnapshot.Tick.decode(tick.encode()).transponderOn());
    }

    @Test
    void aTickWhoseMemberCountDisagreesWithItsPayloadIsRejected() {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create("p1", "Alice", "corvus", 0f, 0f, 0f, 0f,
                "player", true, sensors(300f, 200f), List.of(member("m1", "wolf", "wolf_Assault")));
        String encoded = CoopFleetSnapshot.Tick.of(snapshot).encode();

        assertThrows(IllegalArgumentException.class,
                () -> CoopFleetSnapshot.Tick.decode(encoded + "|0.5"));
    }

    @Test
    void aLocationIdBearingSeparatorsSurvivesTheTickEncoding() {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create("p1", "Alice", "weird|id\nhere",
                0f, 0f, 0f, 0f, "player", true, sensors(300f, 200f), List.of());

        CoopFleetSnapshot.Tick tick = CoopFleetSnapshot.Tick.of(snapshot);

        assertEquals("weird|id\nhere", CoopFleetSnapshot.Tick.decode(tick.encode()).locationId());
    }

    @Test
    void aThirtyShipTickComposesWellInsideTheWanBudget() {
        // The plan's acceptance number: a 30-ship fleet at 10 Hz used to compose to 4.3-5.4 KB, i.e.
        // 3-4 IP fragments per packet. Composed here means envelope plus BOTH sections, since the
        // redundancy layer always ships the previous tick alongside the current one.
        List<CoopFleetSnapshot.Member> members = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            members.add(new CoopFleetSnapshot.Member("PL" + i + "-1234567890", "onslaught",
                    "onslaught_xiv_Elite", "ISS Nevermore " + i, "Captain Vela Solvang",
                    0.673f, 0.812f, "damaged_engines,structural_damage", "safetyoverrides", ""));
        }
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "Alice Longname",
                "system_askonia_inner", -14625.25f, 3080.75f, -14f, 3.25f, "player", true,
                new CoopSensorSync.Profile(650.5f, 120.5f, 25.5f, 0.875f, 419.5f), members);

        String body = CoopFleetSnapshot.Tick.of(snapshot).encode();
        String composed = coop.net.CoopMessages.datagram(
                coop.net.CoopMessages.wireToken("session"), coop.net.CoopMessages.wireToken("player"),
                coop.net.CoopMessages.Type.FLEET_SNAPSHOT,
                List.of(new coop.net.CoopMessages.DatagramSection(4001L, 987654321L, 0, body),
                        new coop.net.CoopMessages.DatagramSection(4002L, 987654421L, 0, body)));
        int composedBytes = composed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        assertTrue(composedBytes <= coop.net.CoopNetService.MAX_DATAGRAM_BYTES,
                "composed 30-ship FLEET_SNAPSHOT was " + composedBytes + " B");
        assertTrue(snapshot.encodeFull().length() > body.length() * 3,
                "the full form is what made this stream fragment");
    }

    /** Phase 14b sensor identity fixture: profile + the three detected-range aggregates + strength. */
    private static CoopSensorSync.Profile sensors(float profile, float strength) {
        return new CoopSensorSync.Profile(profile, 0f, 0f, 1f, strength);
    }
}
