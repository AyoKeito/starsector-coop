package coop.save;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guest snapshot has to survive two very different round trips: Starsector's bundled XStream
 * 1.4.10, which writes it into the host save, and the delimited wire codec that gets it there. Both
 * are pinned here — a DTO that only round-trips one of them is a save that loads with a hole in it.
 */
class CoopGuestSnapshotTest {

    /**
     * The same aliases {@code CoopModPlugin.configureXStream} registers, on the real bundled XStream
     * 1.4.10 ({@code starsector-core/xstream-1.4.10.jar}).
     *
     * <p>{@link DomDriver} rather than the default: XStream 1.4.10's no-arg constructor reaches for
     * the Xpp3 pull parser, and {@code org.xmlpull} ships in none of the game's jars — which is also
     * why the engine builds its own {@code XStream} with an explicit driver. The parser is not what is
     * under test; the alias mapping and the DTO's serializability are, and both are driver-independent.
     */
    private static XStream aliasedXStream() {
        XStream x = new XStream(new DomDriver());
        x.alias("coopGuestSnap", CoopGuestSnapshot.class);
        x.alias("coopGuestSnapStack", CoopGuestSnapshot.CargoStack.class);
        x.alias("coopGuestSnapShip", CoopGuestSnapshot.Ship.class);
        x.alias("coopGuestSnapOfficer", CoopGuestSnapshot.Officer.class);
        return x;
    }

    private static CoopGuestSnapshot populated() {
        CoopGuestSnapshot snapshot = new CoopGuestSnapshot();
        snapshot.setCapturedAtMillis(1_700_000_000_123L);
        snapshot.setSessionId("session-a");
        snapshot.setPlayerId("guest-1");
        snapshot.setPlayerName("Bob | The Second\nLine");
        snapshot.setCampaignId("campaign-uuid-42");
        snapshot.setSeedString("MN-3157318841976950058");
        snapshot.setLocationId("corvus");
        snapshot.setFactionId("player");
        snapshot.setCredits(123456.75d);
        snapshot.setCargo(List.of(
                new CoopGuestSnapshot.CargoStack("RESOURCES", "supplies", 240.5d),
                new CoopGuestSnapshot.CargoStack("SPECIAL", "modspec:heavyarmor", 1d)));
        snapshot.setShips(List.of(
                new CoopGuestSnapshot.Ship("m1", "wolf", "wolf_Assault", "ISS Pipe|Wolf", "Vela",
                        0.7f, 0.9f, "", "heavyarmor", ""),
                new CoopGuestSnapshot.Ship("m2", "falcon_default_D", "falcon_Assault", "Battered",
                        "", 0.4f, 0.55f, "compromised_storage,damagedengines", "", "solar_shielding")));
        snapshot.setOfficers(List.of(
                new CoopGuestSnapshot.Officer("Vela", "aggressive", 5, "helmsmanship:2,target_analysis:1")));
        return snapshot;
    }

    private static void assertMatches(CoopGuestSnapshot expected, CoopGuestSnapshot actual) {
        assertEquals(expected.getFormatVersion(), actual.getFormatVersion());
        assertEquals(expected.getCapturedAtMillis(), actual.getCapturedAtMillis());
        assertEquals(expected.getSessionId(), actual.getSessionId());
        assertEquals(expected.getPlayerId(), actual.getPlayerId());
        assertEquals(expected.getPlayerName(), actual.getPlayerName());
        assertEquals(expected.getCampaignId(), actual.getCampaignId());
        assertEquals(expected.getSeedString(), actual.getSeedString());
        assertEquals(expected.getLocationId(), actual.getLocationId());
        assertEquals(expected.getFactionId(), actual.getFactionId());
        assertEquals(expected.getCredits(), actual.getCredits(), 0.0001d);

        assertEquals(expected.getCargo().size(), actual.getCargo().size());
        for (int i = 0; i < expected.getCargo().size(); i++) {
            CoopGuestSnapshot.CargoStack a = expected.getCargo().get(i);
            CoopGuestSnapshot.CargoStack b = actual.getCargo().get(i);
            assertEquals(a.getType(), b.getType());
            assertEquals(a.getId(), b.getId());
            assertEquals(a.getQuantity(), b.getQuantity(), 0.0001d);
        }

        assertEquals(expected.getShips().size(), actual.getShips().size());
        for (int i = 0; i < expected.getShips().size(); i++) {
            CoopGuestSnapshot.Ship a = expected.getShips().get(i);
            CoopGuestSnapshot.Ship b = actual.getShips().get(i);
            assertEquals(a.getFleetMemberId(), b.getFleetMemberId());
            assertEquals(a.getHullId(), b.getHullId());
            assertEquals(a.getVariantId(), b.getVariantId());
            assertEquals(a.getShipName(), b.getShipName());
            assertEquals(a.getCaptainName(), b.getCaptainName());
            assertEquals(a.getCr(), b.getCr(), 0.0001f);
            assertEquals(a.getHullFraction(), b.getHullFraction(), 0.0001f);
            assertEquals(a.getDmodIds(), b.getDmodIds());
            assertEquals(a.getSModIds(), b.getSModIds());
            assertEquals(a.getSModdedBuiltInIds(), b.getSModdedBuiltInIds());
        }

        assertEquals(expected.getOfficers().size(), actual.getOfficers().size());
        for (int i = 0; i < expected.getOfficers().size(); i++) {
            CoopGuestSnapshot.Officer a = expected.getOfficers().get(i);
            CoopGuestSnapshot.Officer b = actual.getOfficers().get(i);
            assertEquals(a.getName(), b.getName());
            assertEquals(a.getPersonality(), b.getPersonality());
            assertEquals(a.getLevel(), b.getLevel());
            assertEquals(a.getSkills(), b.getSkills());
        }
    }

    // ---- XStream (the save) ---------------------------------------------------------------------

    @Test
    void roundTripsThroughARealXStreamWithTheAliasesApplied() {
        XStream x = aliasedXStream();
        CoopGuestSnapshot snapshot = populated();

        String xml = x.toXML(snapshot);
        Object restored = x.fromXML(xml);

        assertTrue(restored instanceof CoopGuestSnapshot);
        assertMatches(snapshot, (CoopGuestSnapshot) restored);
    }

    @Test
    void theSerializedFormUsesTheAliasesRatherThanPackagePaths() {
        // The point of the aliases: a save entry that survives a package rename, and does not spell
        // out the mod's internal layout in every host save file.
        String xml = aliasedXStream().toXML(populated());

        assertTrue(xml.contains("<coopGuestSnap>"), xml);
        assertTrue(xml.contains("coopGuestSnapShip"), xml);
        assertTrue(xml.contains("coopGuestSnapStack"), xml);
        assertTrue(xml.contains("coopGuestSnapOfficer"), xml);
        assertFalse(xml.contains("coop.save.CoopGuestSnapshot"), xml);
    }

    @Test
    void anEmptySnapshotRoundTripsWithoutNullCollections() {
        // The no-arg constructor is what XStream reaches for; a snapshot captured before the guest
        // had a fleet must still deserialize into usable empty lists rather than nulls.
        XStream x = aliasedXStream();
        CoopGuestSnapshot restored = (CoopGuestSnapshot) x.fromXML(x.toXML(new CoopGuestSnapshot()));

        assertNotNull(restored.getCargo());
        assertNotNull(restored.getShips());
        assertNotNull(restored.getOfficers());
        assertTrue(restored.getCargo().isEmpty());
        assertEquals(CoopGuestSnapshot.FORMAT_VERSION, restored.getFormatVersion());
    }

    @Test
    void theDtoHoldsNoEngineReferences() {
        // A single live engine reference in here would drag an arbitrary slice of the campaign object
        // graph into every host save. Checked structurally so it cannot regress by accident.
        for (Class<?> type : List.of(CoopGuestSnapshot.class, CoopGuestSnapshot.Ship.class,
                CoopGuestSnapshot.CargoStack.class, CoopGuestSnapshot.Officer.class)) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getName();
                assertFalse(fieldType.startsWith("com.fs."),
                        type.getSimpleName() + "." + field.getName() + " is an engine reference");
            }
        }
    }

    // ---- Wire codec (guest -> host) -------------------------------------------------------------

    @Test
    void encodeBodyDecodeBodyRoundTripsIncludingDelimitersInNames() {
        CoopGuestSnapshot snapshot = populated();

        assertMatches(snapshot, CoopGuestSnapshot.decodeBody(snapshot.encodeBody()));
    }

    @Test
    void anEmptySnapshotEncodesToASingleHeaderLine() {
        CoopGuestSnapshot empty = new CoopGuestSnapshot();

        String body = empty.encodeBody();

        assertFalse(body.contains("\n"));
        assertMatches(empty, CoopGuestSnapshot.decodeBody(body));
    }

    @Test
    void aTruncatedBodyIsRejectedRatherThanSilentlyShort() {
        String body = populated().encodeBody();
        String truncated = body.substring(0, body.lastIndexOf('\n'));

        assertThrows(IllegalArgumentException.class, () -> CoopGuestSnapshot.decodeBody(truncated));
    }
}
