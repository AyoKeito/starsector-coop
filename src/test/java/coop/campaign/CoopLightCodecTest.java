package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoopLightCodecTest {
    static final String ID = "light:00000000-0000-0000-0000-000000000001";
    static final String OTHER = "light:00000000-0000-0000-0000-000000000002";
    static final String REQUEST = "10000000-0000-0000-0000-000000000001";

    static CoopLightRecord light(String id) {
        return new CoopLightRecord(1, id, "native-" + id, 42.25f, -110.5f,
                0xfedcab98, 0.5f, 1.5f, 0.8f, 200f, 20_000f, 1.75f, 30f,
                -1, false, Long.MIN_VALUE);
    }

    @Test void completeNativeRecipeRoundTripsBitExactly() {
        CoopLightRecord record = light(ID);
        assertEquals(record, CoopLightRecord.decode(record.encode()));
        assertEquals(record.encode(), CoopLightRecord.decode(record.encode()).encode());
    }

    @Test void signedArgbAndFullSalvageSeedRangeSurvive() {
        CoopLightRecord original = light(ID);
        CoopLightRecord decoded = CoopLightRecord.decode(original.encode());
        assertEquals(0xfedcab98, decoded.colorArgb());
        assertEquals(Long.MIN_VALUE, decoded.salvageSeed());
    }

    @Test void lifetimeChangesDoNotChangeStaticFingerprint() {
        CoopLightRecord a = light(ID), b = withLifetime(a, 0f);
        assertNotEquals(a.encode(), b.encode());
        assertEquals(a.staticFingerprint(), b.staticFingerprint());
        assertEquals(CoopLightCodec.staticFingerprint(List.of(a)), CoopLightCodec.staticFingerprint(List.of(b)));
        assertNotEquals(a.staticFingerprint(), light(OTHER).staticFingerprint());
    }

    @Test void completeManifestHasStableOrderAndImmutableDecodedList() {
        String manifest = CoopLightCodec.encodeSet(List.of(light(OTHER), light(ID)));
        assertEquals(CoopLightCodec.encodeSet(List.of(light(ID), light(OTHER))), manifest);
        List<CoopLightRecord> records = CoopLightCodec.decodeSet(manifest);
        assertEquals(List.of(light(ID), light(OTHER)), records);
        assertThrows(UnsupportedOperationException.class, () -> records.clear());
    }

    @Test void authoritativeEmptyManifestIsDistinctFromUnavailableNull() {
        assertEquals(List.of(), CoopLightCodec.decodeSet(CoopLightCodec.encodeSet(List.of())));
        assertThrows(NullPointerException.class, () -> CoopLightCodec.encodeSet(null));
        assertThrows(IllegalArgumentException.class, () -> CoopLightCodec.decodeSet(null));
    }

    @Test void duplicateCanonicalAndNativeIdentitiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CoopLightCodec.encodeSet(List.of(light(ID), light(ID))));
        CoopLightRecord a = light(ID), b = new CoopLightRecord(1, OTHER, a.hostEntityId(), a.x(), a.y(),
                a.colorArgb(), a.frequencyMultMin(), a.frequencyMultMax(), a.frequencyChangeMult(), a.size(),
                a.detectedRange(), a.bonusMult(), a.remainingDays(), a.pointsOverride(), a.dweller(), a.salvageSeed());
        assertThrows(IllegalArgumentException.class, () -> CoopLightCodec.encodeSet(List.of(a, b)));
    }

    @Test void maxLightCountAndMultibytePayloadCapAreEnforced() {
        List<CoopLightRecord> records = new ArrayList<>();
        for (int i = 0; i < CoopLightRecord.MAX_LIGHTS; i++) {
            String id = "light:" + String.format("00000000-0000-0000-0000-%012d", i);
            records.add(light(id));
        }
        assertEquals(CoopLightRecord.MAX_LIGHTS, CoopLightCodec.decodeSet(CoopLightCodec.encodeSet(records)).size());
        records.add(light("light:ffffffff-ffff-ffff-ffff-ffffffffffff"));
        assertThrows(IllegalArgumentException.class, () -> CoopLightCodec.encodeSet(records));
        List<CoopLightRecord> unicode = new ArrayList<>();
        for (int i = 0; i < CoopLightRecord.MAX_LIGHTS; i++) {
            CoopLightRecord a = records.get(i);
            unicode.add(new CoopLightRecord(1, a.id(), "界".repeat(250) + String.format("%06d", i), a.x(), a.y(),
                    a.colorArgb(), a.frequencyMultMin(), a.frequencyMultMax(), a.frequencyChangeMult(),
                    a.size(), a.detectedRange(), a.bonusMult(), a.remainingDays(), a.pointsOverride(), a.dweller(), a.salvageSeed()));
        }
        assertThrows(IllegalArgumentException.class, () -> CoopLightCodec.encodeSet(unicode));
        assertThrows(IllegalArgumentException.class, () -> CoopLightCodec.decodeSet("界".repeat(140_000)));
    }

    @Test void malformedOrTrailingPayloadCannotBeReadAsACompleteRecipe() {
        String valid = light(ID).encode();
        for (String malformed : List.of("", "LIGHT", valid.substring(0, valid.length() - 1), valid + "0:",
                valid.replace("5:LIGHT", "5:WRONG"), valid.replaceFirst("1:1", "1:9"),
                "-1:LIGHT", "99999999999999999:x")) {
            assertThrows(IllegalArgumentException.class, () -> CoopLightRecord.decode(malformed), malformed);
        }
    }

    @Test void canonicalIdsCannotAddressArbitraryNativeEntities() {
        for (String id : List.of("native-id", "light:foo", "light:1-1-1-1-1", "light:AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"))
            assertThrows(IllegalArgumentException.class, () -> light(id));
        assertThrows(IllegalArgumentException.class, () -> light(null));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightRecord(2, ID, "native", 0, 0, 0,
                1, 1, 1, 1, 1, 1, 1, 0, false, 0));
    }

    @Test void nativeIdentityRejectsBlankControlOversizeAndBrokenUnicode() {
        for (String nativeId : List.of("", " ", "x\ny", "a".repeat(257), "\ud800"))
            assertThrows(IllegalArgumentException.class, () -> new CoopLightRecord(1, ID, nativeId, 0, 0, 0,
                    1, 1, 1, 1, 1, 1, 1, 0, false, 0));
    }

    @Test void numericBoundsRejectNonFiniteAndImpossibleNativeValues() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 1_000_001f})
            assertThrows(IllegalArgumentException.class, () -> withLifetime(light(ID), invalid));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightRecord(1, ID, "native", 1_000_001, 0, 0,
                1, 1, 1, 1, 1, 1, 1, 0, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightRecord(1, ID, "native", 0, 0, 0,
                2, 1, 1, 1, 1, 1, 1, 0, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightRecord(1, ID, "native", 0, 0, 0,
                1, 1, 1, 0, 1, 1, 1, 0, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightRecord(1, ID, "native", 0, 0, 0,
                1, 1, 1, 1, 1, 1, 1, -2, false, 0));
    }

    @Test void everyInteractionRequestRoundTripsWithoutActorIdentity() {
        for (CoopLightCodec.Action action : CoopLightCodec.Action.values()) {
            CoopLightCodec.Request request = new CoopLightCodec.Request(ID, REQUEST, action);
            assertEquals(request, CoopLightCodec.decodeRequest(request.encode()));
            assertFalse(request.encode().contains("guest-player"));
        }
        assertThrows(IllegalArgumentException.class, () -> new CoopLightCodec.Request(ID, "request-1", CoopLightCodec.Action.PULSE));
        assertThrows(IllegalArgumentException.class, () -> CoopLightCodec.decodeRequest(
                new CoopLightCodec.Request(ID, REQUEST, CoopLightCodec.Action.PULSE).encode().replace("5:PULSE", "5:OTHER")));
    }

    @Test void resolvedRewardAndOptionalCurrentRecipeRoundTrip() {
        CoopLightCodec.Outcome accepted = new CoopLightCodec.Outcome(REQUEST, ID, "guest-player", CoopLightCodec.Action.PULSE,
                true, light(ID), 0.625f, 25, "accepted\nwith:\"native\" reward");
        assertEquals(accepted, CoopLightCodec.decodeOutcome(accepted.encode()));
        CoopLightCodec.Outcome denied = new CoopLightCodec.Outcome(REQUEST, ID, "guest-player", CoopLightCodec.Action.PULSE,
                false, light(ID), 0f, 0, "out of range");
        assertEquals(denied, CoopLightCodec.decodeOutcome(denied.encode()));
        CoopLightCodec.Outcome noRecipe = new CoopLightCodec.Outcome(REQUEST, ID, "guest-player", CoopLightCodec.Action.DWELLER,
                true, null, 0f, 0, "committed");
        assertEquals(noRecipe, CoopLightCodec.decodeOutcome(noRecipe.encode()));
    }

    @Test void outcomeCannotRenameLightOrGrantRewardForDeniedOrDwellerClaims() {
        assertThrows(IllegalArgumentException.class, () -> new CoopLightCodec.Outcome(REQUEST, ID, "guest", CoopLightCodec.Action.PULSE,
                true, light(OTHER), 1f, 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightCodec.Outcome(REQUEST, ID, "guest", CoopLightCodec.Action.PULSE,
                false, null, 1f, 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightCodec.Outcome(REQUEST, ID, "guest", CoopLightCodec.Action.DWELLER,
                true, null, 1f, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightCodec.Outcome(REQUEST, ID, "guest", CoopLightCodec.Action.PULSE,
                true, null, Float.NaN, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightCodec.Outcome(REQUEST, ID, "guest", CoopLightCodec.Action.PULSE,
                true, null, 1f, -1, ""));
        assertThrows(IllegalArgumentException.class, () -> new CoopLightCodec.Outcome(REQUEST, ID, "guest", CoopLightCodec.Action.PULSE,
                true, null, 1f, 0, "x".repeat(1_025)));
    }

    static CoopLightRecord withLifetime(CoopLightRecord a, float days) {
        return new CoopLightRecord(a.version(), a.id(), a.hostEntityId(), a.x(), a.y(), a.colorArgb(),
                a.frequencyMultMin(), a.frequencyMultMax(), a.frequencyChangeMult(), a.size(),
                a.detectedRange(), a.bonusMult(), days, a.pointsOverride(), a.dweller(), a.salvageSeed());
    }
}
