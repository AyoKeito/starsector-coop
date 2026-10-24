package coop.campaign;

import coop.net.CoopMessages;
import coop.net.CoopNetService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static coop.campaign.CoopEncounterRecord.*;
import static org.junit.jupiter.api.Assertions.*;

class CoopEncounterCodecTest {
    @Test void nestedSpecialRecipeEscapesAndExactSeedRoundTrip() {
        CoopEncounterRecord source = CoopEncounterFixtures.sample("uuid");
        String encoded = CoopEncounterCodec.encodeSet(List.of(source));
        assertEquals(List.of(source), CoopEncounterCodec.decodeSet(encoded));
        Special ghost = CoopEncounterCodec.decodeSet(encoded).get(0).specials().stream()
                .filter(s -> s.type() == SpecialType.GHOST_WRECK).findFirst().orElseThrow();
        assertEquals(CoopWreckFixtures.sample(wreckId(ghost.entityId())), ghost.wreck());
        assertEquals("GS_OPIS|\\\n", ghost.memory().get(0).value());
        assertEquals("EP|\\\n\r", source.encounterPointId());
        assertEquals(new Orbit(entityId("uuid", Role.SYSTEM, 1), 71.5f, 400f, 38.25f), ghost.orbit());
    }

    @Test void manifestAndHashAreOrderIndependentButIncludeEveryRecipeAndOverlay() {
        CoopEncounterRecord a = CoopEncounterFixtures.sample("a"), b = CoopEncounterFixtures.sample("b");
        assertEquals(CoopEncounterCodec.encodeSet(List.of(a, b)), CoopEncounterCodec.encodeSet(List.of(b, a)));
        assertEquals(CoopEncounterCodec.setHash(List.of(a, b)), CoopEncounterCodec.setHash(List.of(b, a)));
        assertEquals(64, CoopEncounterCodec.setHash(List.of(a)).length());
        assertNotEquals(CoopEncounterCodec.setHash(List.of(a)), CoopEncounterCodec.setHash(List.of(a.withOverlay(Overlay.empty()))));
        assertNotEquals(CoopEncounterCodec.setHash(List.of(a)), CoopEncounterCodec.setHash(List.of(b)));
        CoopEncounterRecord newSeed = new CoopEncounterRecord(a.instanceId(), 7, a.encounterPointId(), a.x(), a.y(), a.depth(),
                a.threatRollRan(), a.creator(), a.threatTag(), a.entities(), a.specials(), a.overlay());
        assertNotEquals(CoopEncounterCodec.setHash(List.of(a)), CoopEncounterCodec.setHash(List.of(newSeed)));
        assertEquals(List.of(), CoopEncounterCodec.decodeSet(CoopEncounterCodec.encodeSet(List.of())));
    }

    @Test void duplicateInstanceIdsAreRejectedOnBothEncodingAndDecoding() {
        CoopEncounterRecord a = CoopEncounterFixtures.sample("a");
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.encodeSet(List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.setHash(List.of(a, a)));
        String encoded = "ENC_SET|1|2|" + CoopDelimited.field(a.encode()) + "|" + CoopDelimited.field(a.encode());
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeSet(encoded));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.encodeSet(Collections.nCopies(MAX_ENCOUNTERS + 1, a)));
    }

    @Test void unsupportedVersionsCannotPartiallyApply() {
        String record = CoopEncounterFixtures.sample("a").encode();
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decode("2" + record.substring(1)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeSet("ENC_SET|2|0"));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeEntityState("ENC_STATE|2|x"));
    }

    @Test void countsEnumsBooleansNumbersAndTrailingFieldsAreValidated() {
        String encoded = CoopEncounterFixtures.sample("a").encode();
        for (String mutation : List.of(change(encoded, 4, "NaN"), change(encoded, 5, "Infinity"),
                change(encoded, 6, "-1"), change(encoded, 7, "yes"), change(encoded, 8, "OTHER"),
                change(encoded, 9, "1"), change(encoded, 10, "-1"), change(encoded, 10, "4097"),
                change(encoded, 11, "OTHER_ROLE"), encoded + "|extra"))
            assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decode(mutation));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeSet("ENC_SET|1|-1"));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeSet("ENC_SET|1|129"));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeSet("ENC_SET|1|0|trailing"));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeSet("OTHER|1|0"));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeEntityState("OTHER|1|0"));
    }

    @Test void everyMissingTrailingFieldIsRejected() {
        List<String> fields = CoopDelimited.split(CoopEncounterFixtures.sample("a").encode());
        for (int i = 0; i < fields.size(); i++) {
            String prefix = fields.subList(0, i).stream().map(CoopDelimited::field)
                    .reduce((a, b) -> a + "|" + b).orElse("");
            assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decode(prefix));
        }
    }

    @Test void invalidEscapesAreNotSilentlyNormalized() {
        String encoded = CoopEncounterFixtures.sample("a").encode();
        for (String mutation : List.of(encoded + "\\", encoded + "\\x", encoded + "\n", encoded + "\r"))
            assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decode(mutation));
    }

    @Test void stateWireRetainsTypedHintFlagsAndRejectsBadOptionalBoolean() {
        EntityState state = new EntityState(entityId("uuid", Role.SYSTEM, 1), "PRELIMINARY", false,
                List.of(new CoopWreckRecord.MemoryValue("$gsType", "STRING", "GS_OPIS|\\\n", 90),
                        new CoopWreckRecord.MemoryValue("$visited", "BOOLEAN", "true", -1)));
        String encoded = CoopEncounterCodec.encodeEntityState(state);
        assertEquals(state, CoopEncounterCodec.decodeEntityState(encoded));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeEntityState(change(encoded, 5, "yes")));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeEntityState(encoded + "|extra"));
    }

    @Test void nativeBlackHoleClearAndRevisitRoundTripThroughStateAndSavedOverlayWithoutVersionChange() {
        CoopEncounterRecord original = CoopEncounterFixtures.sample("uuid");
        String key = entityId("uuid", Role.SYSTEM, 1);
        EntityState completed = new EntityState(key, "SEEN", null, List.of(
                new CoopWreckRecord.MemoryValue("$abyssalBlackHoleReadingsRevisit", "BOOLEAN", "true", -1)));
        String wire = CoopEncounterCodec.encodeEntityState(completed);
        assertTrue(wire.startsWith("ENC_STATE|1|"));
        assertFalse(wire.contains("$abyssalBlackHoleReadings|"));
        assertEquals(completed, CoopEncounterCodec.decodeEntityState(wire));
        CoopEncounterRecord saved = original.withOverlay(new Overlay(List.of(), List.of(completed)));
        assertEquals(saved, CoopEncounterRecord.decode(saved.encode()));
        assertEquals(List.of(saved), CoopEncounterCodec.decodeSet(CoopEncounterCodec.encodeSet(List.of(saved))));
    }

    @Test void inputAndOutputSizeCapsApplyBeforeNativeReplay() {
        String tooLarge = "x".repeat(CoopEncounterCodec.MAX_ENCODED_CHARACTERS + 1);
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decode(tooLarge));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeSet(tooLarge));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.decodeEntityState(
                "x".repeat(CoopEncounterCodec.MAX_STATE_ENCODED_CHARACTERS + 1)));
        List<CoopEncounterRecord> records = new ArrayList<>();
        // A valid collection can still exceed the one-frame payload cap; never emit a partial set.
        String longSpec = "s".repeat(256);
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 1024; i++) entities.add(new Entity(Role.SYSTEM, i, longSpec));
        records.add(new CoopEncounterRecord("a", 0, "ep", 0, 0, 0, false, Creator.PARENT, false,
                entities, List.of(), Overlay.empty()));
        records.add(new CoopEncounterRecord("b", 0, "ep", 0, 0, 0, false, Creator.PARENT, false,
                entities, List.of(), Overlay.empty()));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterCodec.encodeSet(records));
    }

    @Test void validQuoteHeavySetFitsBothRealJsonLayersAndFullTcpEnvelope() {
        CoopEncounterRecord source = largeMembership("\"", 900);
        String set = CoopEncounterCodec.encodeSet(List.of(source));
        CoopMessages.Message message = CoopMessages.encounterSet("s".repeat(256), Long.MAX_VALUE,
                Long.MAX_VALUE, set).withSenderId("p".repeat(256));
        String frame = CoopMessages.encode(message);

        assertTrue(frame.getBytes(StandardCharsets.UTF_8).length < CoopNetService.MAX_FRAME_BYTES);
        assertEquals(List.of(source), CoopEncounterCodec.decodeSet(CoopMessages.requiredPayloadString(
                CoopMessages.decode(frame), "encounters")));
    }

    @Test void rawPayloadWithinCharacterCapStillRejectsExcessiveDoubleJsonEscaping() {
        // Both memberships are below 384 KiB before JSON encoding. Quotes become four bytes;
        // an unnamed C0 control becomes seven bytes after both escaping layers.
        assertThrows(IllegalArgumentException.class, () -> largeMembership("\"", 1024).encode());
        assertThrows(IllegalArgumentException.class, () -> largeMembership("\u0001", 600).encode());
    }

    @Test void utf8AndNamedControlBudgetsMatchActualEnvelopeEncoding() {
        for (CoopEncounterRecord source : List.of(largeMembership("界", 400), largeMembership("\t", 900))) {
            String set = CoopEncounterCodec.encodeSet(List.of(source));
            String frame = CoopMessages.encode(CoopMessages.encounterSet("session", 1, 2, set));
            assertTrue(frame.getBytes(StandardCharsets.UTF_8).length < CoopNetService.MAX_FRAME_BYTES);
            assertEquals(List.of(source), CoopEncounterCodec.decodeSet(set));
        }
    }

    private static CoopEncounterRecord largeMembership(String character, int count) {
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) entities.add(new Entity(Role.SYSTEM, i, character.repeat(256)));
        return new CoopEncounterRecord("large", 0, "ep", 0, 0, 0, false, Creator.PARENT, false,
                entities, List.of(), Overlay.empty());
    }

    private static String change(String encoded, int index, String replacement) {
        List<String> fields = new ArrayList<>(CoopDelimited.split(encoded)); fields.set(index, replacement);
        return fields.stream().map(CoopDelimited::field).reduce((a, b) -> a + "|" + b).orElse("");
    }
}
