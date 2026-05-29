package coop.seed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CoopSectorFingerprintTest {
    @Test
    void stableSortedFingerprintOutputIsIndependentOfInsertionOrder() {
        List<CoopSectorFingerprint.Entry> firstOrder = List.of(
                CoopSectorFingerprint.entry("beta", "market-c", 5, "hegemony", 10.2f, 20.4f),
                CoopSectorFingerprint.entry("alpha", "market-a", 3, "tritachyon", -2.6f, 7.6f),
                CoopSectorFingerprint.entry("beta", "market-b", 4, "independent", 10.2f, 20.4f));
        List<CoopSectorFingerprint.Entry> secondOrder = List.of(
                CoopSectorFingerprint.entry("beta", "market-b", 4, "independent", 10.2f, 20.4f),
                CoopSectorFingerprint.entry("beta", "market-c", 5, "hegemony", 10.2f, 20.4f),
                CoopSectorFingerprint.entry("alpha", "market-a", 3, "tritachyon", -2.6f, 7.6f));

        assertEquals(
                "alpha|market-a|3|tritachyon|-3|8\n"
                        + "beta|market-b|4|independent|10|20\n"
                        + "beta|market-c|5|hegemony|10|20",
                CoopSectorFingerprint.canonicalFromEntries(firstOrder));
        assertEquals(
                CoopSectorFingerprint.canonicalFromEntries(firstOrder),
                CoopSectorFingerprint.canonicalFromEntries(secondOrder));
        assertEquals(
                CoopSectorFingerprint.fingerprintFromEntries(firstOrder),
                CoopSectorFingerprint.fingerprintFromEntries(secondOrder));
    }

    @Test
    void emptyMarketIdRecordsSystemRoundedAnchorAndZeroSizeEmptyFaction() {
        List<CoopSectorFingerprint.Entry> entries = List.of(
                CoopSectorFingerprint.entry("lonely", null, 12.49f, -18.51f));

        assertEquals("lonely||0||12|-19", CoopSectorFingerprint.canonicalFromEntries(entries));
    }

    @Test
    void marketSizeOrFactionChangeChangesFingerprint() {
        List<CoopSectorFingerprint.Entry> base = List.of(
                CoopSectorFingerprint.entry("sys", "mkt", 5, "hegemony", 1f, 2f));
        List<CoopSectorFingerprint.Entry> differentSize = List.of(
                CoopSectorFingerprint.entry("sys", "mkt", 6, "hegemony", 1f, 2f));
        List<CoopSectorFingerprint.Entry> differentFaction = List.of(
                CoopSectorFingerprint.entry("sys", "mkt", 5, "tritachyon", 1f, 2f));

        String baseFingerprint = CoopSectorFingerprint.fingerprintFromEntries(base);
        assertNotEquals(baseFingerprint, CoopSectorFingerprint.fingerprintFromEntries(differentSize));
        assertNotEquals(baseFingerprint, CoopSectorFingerprint.fingerprintFromEntries(differentFaction));
    }
}
