package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoopMarketSyncTest {

    private static CoopMarketSync.StockItem commodity(String id, int qty) {
        return new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.COMMODITY, id, qty, 100f);
    }

    @Test
    void guestRendersHostMarketContents() {
        CoopMarketSync sync = new CoopMarketSync();
        sync.applySnapshot("jangala", List.of(commodity("supplies", 500),
                new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP, "ship-1", 1, 50000f)));

        assertEquals(2, sync.contents("jangala").size());
        assertEquals(500, sync.item("jangala", CoopMarketSync.ItemKind.COMMODITY, "supplies").quantity());
    }

    @Test
    void buyTransactionDecrementsCanonicalStock() {
        CoopMarketSync sync = new CoopMarketSync();
        sync.applySnapshot("jangala", List.of(commodity("supplies", 500)));

        List<CoopMarketSync.StockItem> updated = sync.applyTransaction(
                new CoopMarketSync.Transaction("jangala", CoopMarketSync.ItemKind.COMMODITY, "supplies", 120, 100f));

        assertEquals(1, updated.size());
        assertEquals(380, sync.item("jangala", CoopMarketSync.ItemKind.COMMODITY, "supplies").quantity());
    }

    @Test
    void sellTransactionIncrementsStock() {
        CoopMarketSync sync = new CoopMarketSync();
        sync.applySnapshot("jangala", List.of(commodity("supplies", 500)));

        sync.applyTransaction(new CoopMarketSync.Transaction(
                "jangala", CoopMarketSync.ItemKind.COMMODITY, "supplies", -50, 100f));

        assertEquals(550, sync.item("jangala", CoopMarketSync.ItemKind.COMMODITY, "supplies").quantity());
    }

    @Test
    void buyingUniqueShipRemovesListing() {
        CoopMarketSync sync = new CoopMarketSync();
        sync.applySnapshot("jangala", List.of(
                new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP, "ship-1", 1, 50000f)));

        sync.applyTransaction(new CoopMarketSync.Transaction(
                "jangala", CoopMarketSync.ItemKind.SHIP, "ship-1", 1, 50000f));

        assertNull(sync.item("jangala", CoopMarketSync.ItemKind.SHIP, "ship-1"));
        assertEquals(0, sync.contents("jangala").size());
    }

    @Test
    void stockEncodingRoundTrips() {
        List<CoopMarketSync.StockItem> items = List.of(
                commodity("supplies", 500),
                new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.WEAPON, "heavymg", 4, 1200.5f),
                new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.OFFICER, "officer-7", 1, 0f));
        List<CoopMarketSync.StockItem> back = CoopMarketSync.decodeStock(CoopMarketSync.encodeStock(items));

        assertEquals(3, back.size());
        assertEquals(CoopMarketSync.ItemKind.WEAPON, back.get(1).kind());
        assertEquals("heavymg", back.get(1).itemId());
        assertEquals(4, back.get(1).quantity());
        assertEquals(1200.5f, back.get(1).unitPrice());
    }

    @Test
    void stockEncodingCarriesThePerKindDetailBlob() {
        String detail = "member-1|ISS Hope|hound_Standard|hound_dhull|0.4000|0|0|dmod_engine||||||";
        List<CoopMarketSync.StockItem> back = CoopMarketSync.decodeStock(CoopMarketSync.encodeStock(
                List.of(commodity("supplies", 500),
                        new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP, "member-1", 1, 0f, detail))));

        assertEquals("", back.get(0).detail(), "fungible lines carry no detail");
        assertEquals(detail, back.get(1).detail());
    }

    @Test
    void aStockLineMissingTheDetailFieldIsRejected() {
        // Fail loudly rather than shifting fields: a 4-field line is a stale-build sender, and silently
        // reading unitPrice as the detail would corrupt every listing after it.
        assertThrows(IllegalArgumentException.class,
                () -> CoopMarketSync.decodeStock("1\nSHIP|member-1|1|0.0"));
    }

    @Test
    void aSoldBackShipCarriesItsDetailIntoTheCanonicalStock() {
        CoopMarketSync sync = new CoopMarketSync();
        sync.applySnapshot("jangala", List.of());

        sync.applyTransaction(new CoopMarketSync.Transaction("jangala",
                CoopMarketSync.ItemKind.SHIP, "member-9", -1, 0f, "blob"));

        assertEquals("blob", sync.item("jangala", CoopMarketSync.ItemKind.SHIP, "member-9").detail(),
                "the host must shelve the hull the player actually sold, not a pristine reroll");
    }

    @Test
    void hireDiffReportsOnlyThePeopleThatVanished() {
        Map<String, CoopMarketSync.ItemKind> applied = new LinkedHashMap<>();
        applied.put("officer-1", CoopMarketSync.ItemKind.OFFICER);
        applied.put("merc-2", CoopMarketSync.ItemKind.MERC);
        applied.put("admin-3", CoopMarketSync.ItemKind.ADMIN);

        CoopMarketSync.HireDiff diff = CoopMarketSync.diffHires(applied, Set.of("officer-1"));

        assertEquals(Set.of("merc-2", "admin-3"), diff.hired().keySet());
        assertEquals(CoopMarketSync.ItemKind.ADMIN, diff.hired().get("admin-3"),
                "the claim has to say which pool the host should remove from");
        assertEquals(Set.of("officer-1"), diff.remaining().keySet());
    }

    @Test
    void hireDiffIgnoresPeopleTheLocalClientRolledItself() {
        Map<String, CoopMarketSync.ItemKind> applied = new LinkedHashMap<>();
        applied.put("officer-1", CoopMarketSync.ItemKind.OFFICER);

        CoopMarketSync.HireDiff diff = CoopMarketSync.diffHires(applied,
                Set.of("officer-1", "locally-rolled-9"));

        assertEquals(Set.of(), diff.hired().keySet());
        assertEquals(Set.of("officer-1"), diff.remaining().keySet());
    }

    @Test
    void anEmptyBaselineClaimsNothing() {
        assertEquals(Set.of(), CoopMarketSync.diffHires(Map.of(), Set.of()).hired().keySet());
        assertEquals(Set.of(), CoopMarketSync.diffHires(null, Set.of("a")).hired().keySet());
    }

    @Test
    void marketMessagesRoundTrip() {
        CoopMessages.Message snapshot = CoopMessages.marketSnapshot("s1", 1L, 10L, "jangala",
                CoopMarketSync.encodeStock(List.of(commodity("supplies", 500))));
        CoopMessages.Message decodedSnap = CoopMessages.decode(CoopMessages.encode(snapshot));
        assertEquals(CoopMessages.Type.MARKET_SNAPSHOT, decodedSnap.type());
        assertEquals("jangala", CoopMessages.requiredPayloadString(decodedSnap, "marketId"));
        assertEquals(500, CoopMarketSync.decodeStock(
                CoopMessages.requiredPayloadString(decodedSnap, "stock")).get(0).quantity());

        CoopMessages.Message open = CoopMessages.marketOpen("s1", 5L, 5L, "jangala", "guest");
        CoopMessages.Message decodedOpen = CoopMessages.decode(CoopMessages.encode(open));
        assertEquals(CoopMessages.Type.MARKET_OPEN, decodedOpen.type());
        assertEquals("jangala", CoopMessages.requiredPayloadString(decodedOpen, "marketId"));
        assertEquals("guest", CoopMessages.requiredPayloadString(decodedOpen, "playerId"));

        CoopMessages.Message txn = CoopMessages.marketTxn("s1", 2L, 20L, "jangala",
                "COMMODITY", "supplies", 120, 105.5f, "guest");
        CoopMessages.Message decodedTxn = CoopMessages.decode(CoopMessages.encode(txn));
        assertEquals(CoopMessages.Type.MARKET_TXN, decodedTxn.type());
        assertEquals("COMMODITY", CoopMessages.requiredPayloadString(decodedTxn, "kind"));
        assertEquals(120, CoopMessages.requiredPayloadLong(decodedTxn, "qty"));
        assertEquals(105.5f, CoopMessages.requiredPayloadFloat(decodedTxn, "unitPrice"));
        assertEquals("guest", CoopMessages.requiredPayloadString(decodedTxn, "actingPlayerId"));
        assertEquals("", CoopMessages.requiredPayloadString(decodedTxn, "detail"));
    }

    @Test
    void aShipSellBackTxnCarriesTheDetailBlobThroughTheEnvelope() {
        String blob = new CoopShipDetail("member-4", "ISS Grudge", "hound_Standard", "hound_dhull",
                0.31f, 3, 2, List.of("dmod_engine"), List.of(), List.of(), List.of(), List.of(),
                Map.of("WS0001", "lightmg"), Map.of()).encode();

        CoopMessages.Message txn = CoopMessages.marketTxn("s1", 2L, 20L, "jangala",
                "SHIP", "member-4", -1, 0f, "guest", blob);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(txn));

        assertEquals(blob, CoopMessages.requiredPayloadString(decoded, "detail"));
        assertEquals("hound_dhull",
                CoopShipDetail.decode(CoopMessages.requiredPayloadString(decoded, "detail")).hullSpecId());
    }
}
