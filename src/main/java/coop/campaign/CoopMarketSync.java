package coop.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static coop.util.CoopText.requireText;

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
        /**
         * A {@code SpecialItemData} stack: modspecs, blueprints, AI cores, nanoforges, and every other
         * item the engine models as {@code CargoItemType.SPECIAL}. The item id is not a plain spec id
         * — it is {@link #specialItemId(String, String)}, because a special is identified by
         * <em>both</em> its id and its (nullable) data payload, and {@code SpecialItemData.equals}
         * compares both. A modspec's data is the hullmod id it teaches; an AI core's is null.
         */
        SPECIAL,
        OFFICER,
        MERC,
        /**
         * A freelance administrator. Distinct from {@link #OFFICER} because the engine keeps admins in
         * a second pool with its own accessors ({@code addAvailableAdmin} / {@code getAdmin}), so the
         * apply side has to branch on it anyway — see {@link CoopPersonDetail}.
         */
        ADMIN
    }

    /**
     * A single stockable line item at a market.
     *
     * <p>{@code detail} is an opaque, kind-specific blob (empty for fungible stacks). SHIP listings
     * carry a {@link CoopShipDetail} — a hull's D-mods, s-mods, refit and CR are not recoverable from
     * a variant id, and a listing rebuilt from the id alone arrives pristine and mispriced.
     * OFFICER/MERC/ADMIN listings carry a {@link CoopPersonDetail}.
     *
     * <p>Because the detail reconstructs the real variant, {@link #unitPrice} stays 0 for ships and
     * the guest's own engine prices the listing — by construction it now prices the same hull with
     * the same mods and the same CR as the host's, so the two displayed prices agree without the
     * price ever riding the wire.
     */
    public record StockItem(ItemKind kind, String itemId, int quantity, float unitPrice, String detail) {
        public StockItem {
            kind = Objects.requireNonNull(kind, "kind");
            itemId = requireText(itemId, "itemId");
            if (quantity < 0) {
                throw new IllegalArgumentException("quantity must be >= 0");
            }
            detail = CoopDelimited.normalize(detail);
        }

        /** A fungible line with no kind-specific detail. */
        public StockItem(ItemKind kind, String itemId, int quantity, float unitPrice) {
            this(kind, itemId, quantity, unitPrice, "");
        }

        public String key() {
            return kind.name() + ":" + itemId;
        }

        public StockItem withQuantity(int newQuantity) {
            return new StockItem(kind, itemId, Math.max(0, newQuantity), unitPrice, detail);
        }
    }

    /**
     * Acting-client report of a buy/sell/hire against a market.
     *
     * <p>{@code detail} mirrors {@link StockItem#detail()}: a sell-back carries the full
     * {@link CoopShipDetail} of the hull being handed over, so the ship the host puts back on the
     * shelf is the battered one the player actually sold, not a pristine reroll of its base variant.
     */
    public record Transaction(String marketId, ItemKind kind, String itemId, int qty, float unitPrice,
                              String detail) {
        public Transaction {
            marketId = requireText(marketId, "marketId");
            kind = Objects.requireNonNull(kind, "kind");
            itemId = requireText(itemId, "itemId");
            detail = CoopDelimited.normalize(detail);
        }

        public Transaction(String marketId, ItemKind kind, String itemId, int qty, float unitPrice) {
            this(marketId, kind, itemId, qty, unitPrice, "");
        }
    }

    // ---- SPECIAL item identity ------------------------------------------------------------------

    /**
     * Packs a {@code SpecialItemData}'s (id, data) pair into one stock item id.
     *
     * <p>Both halves are load-bearing: {@code SpecialItemData.equals} compares id <em>and</em> data,
     * and {@code CargoAPI.removeItems(SPECIAL, data, n)} matches by equality, so a modspec
     * reconstructed with a blank data instead of its hullmod id would be un-removable. {@code data} is
     * nullable in the engine (AI cores, nanoforges) and null is normalized to the empty string on the
     * wire; {@link #specialData(String)} maps it back to null.
     *
     * <p>Packed as a two-field {@link CoopDelimited} record so an id or data containing {@code |}
     * still round-trips (one nesting level inside the stock line's own field).
     */
    public static String specialItemId(String id, String data) {
        return CoopDelimited.field(requireText(id, "specialId"))
                + "|" + CoopDelimited.field(data == null ? "" : data);
    }

    public static String specialId(String itemId) {
        return CoopDelimited.split(Objects.requireNonNull(itemId, "itemId")).get(0);
    }

    /** The special's data payload, or null when it carries none (AI cores, nanoforges). */
    public static String specialData(String itemId) {
        List<String> fields = CoopDelimited.split(Objects.requireNonNull(itemId, "itemId"));
        String data = fields.size() < 2 ? "" : fields.get(1);
        return data.isEmpty() ? null : data;
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
     * shared availability; a sell (negative {@code qty}) increments it. SHIP/OFFICER/MERC/ADMIN lines
     * are unique (quantity drops to 0 on purchase, removing the listing). Returns the resulting stock
     * for the market so the host can re-broadcast it.
     */
    public synchronized List<StockItem> applyTransaction(Transaction txn) {
        Objects.requireNonNull(txn, "txn");
        Map<String, StockItem> stock = stockByMarket.computeIfAbsent(txn.marketId(), k -> new LinkedHashMap<>());
        String key = txn.kind().name() + ":" + txn.itemId();
        StockItem existing = stock.get(key);
        int current = existing == null ? 0 : existing.quantity();
        float price = existing == null ? txn.unitPrice() : existing.unitPrice();
        // A sell-back of something the market never listed brings its own detail with it; an existing
        // listing keeps the one it was snapshotted with.
        String detail = existing == null ? txn.detail() : existing.detail();
        int updated = current - txn.qty();
        if (updated <= 0) {
            stock.remove(key);
        } else {
            stock.put(key, new StockItem(txn.kind(), txn.itemId(), updated, price, detail));
        }
        return new ArrayList<>(stock.values());
    }

    public synchronized void clear() {
        stockByMarket.clear();
    }

    // ---- Hire detection (Phase 12c gap 2d) ------------------------------------------------------

    /** Split of a market's previously-applied hireable pool into "was hired" and "still there". */
    public record HireDiff(Map<String, ItemKind> hired, Map<String, ItemKind> remaining) {
    }

    /**
     * The engine fires no event when a player hires an officer, mercenary or administrator, so the
     * acting client detects its own hires by diffing: anyone the last snapshot put in the pool who is
     * no longer hireable when the market screen closes was hired.
     *
     * <p>One-directional on purpose. A person appearing who was <em>not</em> in the applied set is not
     * a hire and is not reported — that is the local {@code OfficerManagerEvent} rolling its own
     * candidate, which the next host snapshot will overwrite anyway.
     *
     * @param applied        personId -&gt; the kind it was listed as, as of the last applied snapshot
     * @param stillHireable  the person ids currently flagged hireable at that market
     */
    public static HireDiff diffHires(Map<String, ItemKind> applied, Set<String> stillHireable) {
        Map<String, ItemKind> hired = new LinkedHashMap<>();
        Map<String, ItemKind> remaining = new LinkedHashMap<>();
        if (applied != null) {
            for (Map.Entry<String, ItemKind> entry : applied.entrySet()) {
                if (stillHireable != null && stillHireable.contains(entry.getKey())) {
                    remaining.put(entry.getKey(), entry.getValue());
                } else {
                    hired.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new HireDiff(hired, remaining);
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
                        .append('|').append(Float.toString(item.unitPrice()))
                        .append('|').append(CoopDelimited.field(item.detail()));
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
            if (f.size() != 5) {
                throw new IllegalArgumentException("Expected 5 stock fields, got " + f.size());
            }
            items.add(new StockItem(ItemKind.valueOf(f.get(0)), f.get(1),
                    Integer.parseInt(f.get(2).trim()), Float.parseFloat(f.get(3).trim()), f.get(4)));
        }
        return items;
    }

}
