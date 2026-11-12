package coop.seed;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSectorFingerprintTest {
    @Test
    void temporarySystemsAndTheirMarketsDoNotChangeCanonicalFingerprint() {
        StarSystemAPI core = system("core", false, 10.2f, 20.4f);
        StarSystemAPI empty = system("empty", false, -2.6f, 7.6f);
        StarSystemAPI temporary = system("abyssal", true, 300f, 400f);
        MarketAPI colony = market(core, "colony", false, 5, "player");
        MarketAPI temporaryMarket = market(temporary, "encounter-market", false, 3, "independent");
        SectorAPI permanentSector = sector(List.of(core, empty), List.of(colony));
        SectorAPI hostWithEncounter = sector(List.of(core, temporary, empty),
                List.of(colony, temporaryMarket));

        assertEquals("core|colony|5|player|10|20\nempty||0||-3|8",
                CoopSectorFingerprint.canonical(permanentSector));
        assertEquals(CoopSectorFingerprint.canonical(permanentSector),
                CoopSectorFingerprint.canonical(hostWithEncounter));
        assertEquals(CoopSectorFingerprint.fingerprint(permanentSector),
                CoopSectorFingerprint.fingerprint(hostWithEncounter));
        assertTrue(CoopSectorFingerprint.includeSystem(core));
        assertFalse(CoopSectorFingerprint.includeSystem(temporary));
        assertFalse(CoopSectorFingerprint.includeSystem(null));
    }

    @Test
    void temporaryMarketDoesNotLeakIntoPermanentSystemWithTheSameId() {
        StarSystemAPI permanent = system("deep space", false, 10f, 20f);
        StarSystemAPI temporary = system("deep space", true, 300f, 400f);
        MarketAPI temporaryMarket = market(temporary, "encounter-market", false, 3, "independent");
        SectorAPI baseline = sector(List.of(permanent), List.of());
        SectorAPI withEncounter = sector(List.of(permanent, temporary), List.of(temporaryMarket));

        assertEquals("deep space||0||10|20", CoopSectorFingerprint.canonical(withEncounter));
        assertEquals(CoopSectorFingerprint.canonical(baseline),
                CoopSectorFingerprint.canonical(withEncounter));
        assertEquals(CoopSectorFingerprint.fingerprint(baseline),
                CoopSectorFingerprint.fingerprint(withEncounter));
    }

    @Test
    void permanentMarketsRemainFingerprintInputsAndHiddenMarketsDoNot() {
        StarSystemAPI permanent = system("core", false, 10f, 20f);
        MarketAPI colony = market(permanent, "colony", false, 5, "player");
        MarketAPI hiddenBase = market(permanent, "dynamic-base", true, 4, "pirates");
        SectorAPI baseline = sector(List.of(permanent), List.of(colony));

        assertEquals("core|colony|5|player|10|20", CoopSectorFingerprint.canonical(baseline));
        assertEquals(CoopSectorFingerprint.canonical(baseline),
                CoopSectorFingerprint.canonical(sector(List.of(permanent), List.of(hiddenBase, colony))));
        assertNotEquals(CoopSectorFingerprint.fingerprint(baseline),
                CoopSectorFingerprint.fingerprint(sector(List.of(permanent), List.of())));
        assertNotEquals(CoopSectorFingerprint.fingerprint(baseline),
                CoopSectorFingerprint.fingerprint(sector(List.of(permanent),
                        List.of(market(permanent, "colony", false, 6, "player")))));
        assertNotEquals(CoopSectorFingerprint.fingerprint(baseline),
                CoopSectorFingerprint.fingerprint(sector(List.of(permanent),
                        List.of(market(permanent, "colony", false, 5, "hegemony")))));
        StarSystemAPI moved = system("core", false, 30f, 20f);
        assertNotEquals(CoopSectorFingerprint.fingerprint(baseline),
                CoopSectorFingerprint.fingerprint(sector(List.of(moved),
                        List.of(market(moved, "colony", false, 5, "player")))));
    }

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

    private static StarSystemAPI system(String id, boolean temporary, float x, float y) {
        return proxy(StarSystemAPI.class, (proxy, method, args) -> switch (method.getName()) {
            case "getId" -> id;
            case "hasTag" -> temporary && Tags.TEMPORARY_LOCATION.equals(args[0]);
            case "getHyperspaceAnchor" -> null;
            case "getLocation" -> new Vector2f(x, y);
            default -> throw new AssertionError("Unexpected system call: " + method.getName());
        });
    }

    private static MarketAPI market(StarSystemAPI system, String id, boolean hidden, int size,
                                    String faction) {
        return proxy(MarketAPI.class, (proxy, method, args) -> switch (method.getName()) {
            case "getStarSystem" -> system;
            case "getId" -> id;
            case "isHidden" -> hidden;
            case "getSize" -> size;
            case "getFactionId" -> faction;
            default -> throw new AssertionError("Unexpected market call: " + method.getName());
        });
    }

    private static SectorAPI sector(List<StarSystemAPI> systems, List<MarketAPI> markets) {
        EconomyAPI economy = proxy(EconomyAPI.class, (proxy, method, args) -> {
            if ("getMarketsCopy".equals(method.getName())) {
                return markets;
            }
            throw new AssertionError("Unexpected economy call: " + method.getName());
        });
        return proxy(SectorAPI.class, (proxy, method, args) -> switch (method.getName()) {
            case "getStarSystems" -> systems;
            case "getEconomy" -> economy;
            default -> throw new AssertionError("Unexpected sector call: " + method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
