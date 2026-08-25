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

    /**
     * Phase 24 M2 check: a mid-campaign player colony must not break the seed lock on session resume.
     *
     * <p>It does not, and needs no exclusion. Both sides recompute the fingerprint live from their own
     * loaded save at every seed lock ({@code CoopNetPump.maybeSendSeedLockRequest} falls through to
     * the live supplier because a stored {@code SeedData} carries no fingerprint), and on the
     * supported resume path both saves carry the same replicated colony. The colony is one added
     * entry, identical on both sides. Before colonization there is no entry at all: a planet-condition
     * market is never registered with the economy, so {@code getMarketsCopy()} never sees it — which is
     * also why abandonment removes the entry symmetrically rather than leaving a size-1 ghost behind.
     *
     * <p>The one fingerprint input a colony moves on its own is {@code marketSize}, and colony growth
     * is fully deterministic — no RNG anywhere in {@code CoreImmigrationPluginImpl} or any
     * {@code MarketImmigrationModifier} — so it is a state-mirroring risk of the same kind every
     * market already carries, not an asymmetric-by-construction input like the hidden dynamic bases
     * {@link CoopSectorFingerprint#includeMarket} drops.
     */
    @Test
    void aPlayerColonyIsASymmetricFingerprintEntryOnBothSides() {
        List<CoopSectorFingerprint.Entry> beforeColonizing = List.of(
                CoopSectorFingerprint.entry("eos", "market_core", 5, "hegemony", 1f, 2f));
        List<CoopSectorFingerprint.Entry> hostAfter = List.of(
                CoopSectorFingerprint.entry("eos", "market_core", 5, "hegemony", 1f, 2f),
                CoopSectorFingerprint.entry("eos", "market_planet_eos", 3, "player", 1f, 2f));
        List<CoopSectorFingerprint.Entry> guestAfter = List.of(
                CoopSectorFingerprint.entry("eos", "market_planet_eos", 3, "player", 1f, 2f),
                CoopSectorFingerprint.entry("eos", "market_core", 5, "hegemony", 1f, 2f));

        assertNotEquals(CoopSectorFingerprint.fingerprintFromEntries(beforeColonizing),
                CoopSectorFingerprint.fingerprintFromEntries(hostAfter),
                "the colony is a real entry, so a save with one is a different sector state");
        assertEquals(CoopSectorFingerprint.fingerprintFromEntries(hostAfter),
                CoopSectorFingerprint.fingerprintFromEntries(guestAfter),
                "both coordinated saves carry the same colony, so the seed lock still matches");
        assertEquals(CoopSectorFingerprint.fingerprintFromEntries(beforeColonizing),
                CoopSectorFingerprint.fingerprintFromEntries(List.of(
                        CoopSectorFingerprint.entry("eos", "market_core", 5, "hegemony", 1f, 2f))),
                "abandoning drops the entry again on both sides, back to the pre-colony fingerprint");
    }
}
