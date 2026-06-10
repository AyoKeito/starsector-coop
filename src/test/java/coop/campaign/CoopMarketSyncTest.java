package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    }
}
