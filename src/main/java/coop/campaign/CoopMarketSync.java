package coop.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-authoritative market contents and transaction effects (Phase 12).
 *
 * <p>The submarket ship/weapon/fighter/commodity stock and the hireable officer/mercenary pool at a
 * market are host-owned. When either player opens a market the host snapshots its contents
 * ({@code MARKET_SNAPSHOT}) and the guest renders that snapshot ({@link #applySnapshot}) instead of
 * generating its own, closing the "host and guest see different shop/officer stock" divergence.
 *
 * <p>Buy/sell/hire applies to the host's canonical market: the acting client sends a
 * {@code MARKET_TXN} ({@link Transaction}); the host applies it ({@link #applyTransaction}) to the
 * authoritative stock and re-broadcasts the resulting snapshot. Credits/cargo/ships/officers land in
 * the acting player's own per-player state (handled by the engine on that client) — this model only
 * tracks the shared stock/availability so both clients agree on what remains for sale.
 */
public final class CoopMarketSync {

    public enum ItemKind {
        COMMODITY,
        SHIP,
        WEAPON,
        FIGHTER,
        OFFICER,
        MERC
    }

    /** A single stockable line item at a market. */
    public record StockItem(ItemKind kind, String itemId, int quantity, float unitPrice) {
        public StockItem {
            kind = Objects.requireNonNull(kind, "kind");
            itemId = requireText(itemId, "itemId");
            if (quantity < 0) {
                throw new IllegalArgumentException("quantity must be >= 0");
            }
        }

        public String key() {
            return kind.name() + ":" + itemId;
        }

        public StockItem withQuantity(int newQuantity) {
            return new StockItem(kind, itemId, Math.max(0, newQuantity), unitPrice);
        }
    }

    /** Acting-client report of a buy/sell/hire against a market. */
    public record Transaction(String marketId, ItemKind kind, String itemId, int qty, float unitPrice) {
        public Transaction {
            marketId = requireText(marketId, "marketId");
            kind = Objects.requireNonNull(kind, "kind");
            itemId = requireText(itemId, "itemId");
        }
    }

    // marketId -> (item key -> stock)
    private final Map<String, Map<String, StockItem>> stockByMarket = new LinkedHashMap<>();

    /** Replace a market's stock with the host snapshot. Returns the stored items in order. */
    public synchronized List<StockItem> applySnapshot(String marketId, List<StockItem> items) {
        String norm = requireText(marketId, "marketId");
        Map<String, StockItem> stock = new LinkedHashMap<>();
        if (items != null) {
            for (StockItem item : items) {
                stock.put(item.key(), item);
            }
        }
        stockByMarket.put(norm, stock);
        return new ArrayList<>(stock.values());
    }

    public synchronized List<StockItem> contents(String marketId) {
        Map<String, StockItem> stock = stockByMarket.get(requireText(marketId, "marketId"));
        return stock == null ? List.of() : new ArrayList<>(stock.values());
    }

    public synchronized StockItem item(String marketId, ItemKind kind, String itemId) {
        Map<String, StockItem> stock = stockByMarket.get(requireText(marketId, "marketId"));
        return stock == null ? null : stock.get(kind.name() + ":" + requireText(itemId, "itemId"));
    }

    /**
     * Applies a transaction to the canonical stock: a buy/hire (positive {@code qty}) decrements the
     * shared availability; a sell (negative {@code qty}) increments it. SHIP/OFFICER/MERC lines are
     * unique (quantity drops to 0 on purchase, removing the listing). Returns the resulting stock for
     * the market so the host can re-broadcast it.
     */
    public synchronized List<StockItem> applyTransaction(Transaction txn) {
        Objects.requireNonNull(txn, "txn");
        Map<String, StockItem> stock = stockByMarket.computeIfAbsent(txn.marketId(), k -> new LinkedHashMap<>());
        String key = txn.kind().name() + ":" + txn.itemId();
        StockItem existing = stock.get(key);
        int current = existing == null ? 0 : existing.quantity();
        float price = existing == null ? txn.unitPrice() : existing.unitPrice();
        int updated = current - txn.qty();
        if (updated <= 0) {
            stock.remove(key);
        } else {
            stock.put(key, new StockItem(txn.kind(), txn.itemId(), updated, price));
        }
        return new ArrayList<>(stock.values());
    }

    public synchronized void clear() {
        stockByMarket.clear();
    }

    // ---- Snapshot encoding (single delimited string carried over TCP) -------------------------

    public static String encodeStock(List<StockItem> items) {
        StringBuilder out = new StringBuilder(32 + (items == null ? 0 : items.size()) * 24);
        out.append(items == null ? 0 : items.size());
        if (items != null) {
            for (StockItem item : items) {
                out.append('\n')
                        .append(item.kind().name())
                        .append('|').append(CoopDelimited.field(item.itemId()))
                        .append('|').append(item.quantity())
                        .append('|').append(Float.toString(item.unitPrice()));
            }
        }
        return out.toString();
    }

    public static List<StockItem> decodeStock(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        int count = Integer.parseInt(lines[0].trim());
        if (lines.length - 1 < count) {
            throw new IllegalArgumentException("Declared " + count + " stock items but only "
                    + (lines.length - 1) + " lines present");
        }
        List<StockItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            List<String> f = CoopDelimited.split(lines[i + 1]);
            if (f.size() != 4) {
                throw new IllegalArgumentException("Expected 4 stock fields, got " + f.size());
            }
            items.add(new StockItem(ItemKind.valueOf(f.get(0)), f.get(1),
                    Integer.parseInt(f.get(2).trim()), Float.parseFloat(f.get(3).trim())));
        }
        return items;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }
}
