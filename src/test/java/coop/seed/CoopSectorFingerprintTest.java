package coop.seed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopSectorFingerprintTest {
    @Test
    void stableSortedFingerprintOutputIsIndependentOfInsertionOrder() {
        List<CoopSectorFingerprint.Entry> firstOrder = List.of(
                CoopSectorFingerprint.entry("beta", "market-c", 10.2f, 20.4f),
                CoopSectorFingerprint.entry("alpha", "market-a", -2.6f, 7.6f),
                CoopSectorFingerprint.entry("beta", "market-b", 10.2f, 20.4f));
        List<CoopSectorFingerprint.Entry> secondOrder = List.of(
                CoopSectorFingerprint.entry("beta", "market-b", 10.2f, 20.4f),
                CoopSectorFingerprint.entry("beta", "market-c", 10.2f, 20.4f),
                CoopSectorFingerprint.entry("alpha", "market-a", -2.6f, 7.6f));

        assertEquals(
                "alpha|market-a|-3|8\n"
                        + "beta|market-b|10|20\n"
                        + "beta|market-c|10|20",
                CoopSectorFingerprint.canonicalFromEntries(firstOrder));
        assertEquals(
                CoopSectorFingerprint.canonicalFromEntries(firstOrder),
                CoopSectorFingerprint.canonicalFromEntries(secondOrder));
        assertEquals(
                CoopSectorFingerprint.fingerprintFromEntries(firstOrder),
                CoopSectorFingerprint.fingerprintFromEntries(secondOrder));
    }

    @Test
    void emptyMarketIdStillRecordsSystemAndRoundedAnchor() {
        List<CoopSectorFingerprint.Entry> entries = List.of(
                CoopSectorFingerprint.entry("lonely", null, 12.49f, -18.51f));

        assertEquals("lonely||12|-19", CoopSectorFingerprint.canonicalFromEntries(entries));
    }
}
