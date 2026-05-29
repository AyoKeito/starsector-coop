package coop.seed;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import coop.handshake.CoopChecksum;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CoopSectorFingerprint {
    private CoopSectorFingerprint() {
    }

    public record Entry(String systemId, String marketId, int marketSize, String factionId,
                        int anchorX, int anchorY) {
        public Entry {
            systemId = normalize(systemId);
            marketId = normalize(marketId);
            factionId = normalize(factionId);
        }
    }

    /** System-only or test entry with no market: size 0 and empty faction. */
    public static Entry entry(String systemId, String marketId, float anchorX, float anchorY) {
        return entry(systemId, marketId, 0, null, anchorX, anchorY);
    }

    /**
     * Market-bearing entry. {@code marketSize} and {@code factionId} are seed-deterministic and add
     * structural signal (catches a market-population/ownership divergence the id/anchor alone would
     * miss) without the false-reject fragility of non-deterministic fields like fleets or conditions.
     */
    public static Entry entry(String systemId, String marketId, int marketSize, String factionId,
                              float anchorX, float anchorY) {
        return new Entry(systemId, marketId, marketSize, factionId, Math.round(anchorX), Math.round(anchorY));
    }

    public static String fingerprint(SectorAPI sector) {
        return fingerprintFromEntries(entriesFromSector(sector));
    }

    public static String canonical(SectorAPI sector) {
        return canonicalFromEntries(entriesFromSector(sector));
    }

    public static String fingerprintFromEntries(List<Entry> entries) {
        return CoopChecksum.sha256Text(canonicalFromEntries(entries));
    }

    public static String canonicalFromEntries(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparing(Entry::systemId)
                .thenComparing(Entry::marketId)
                .thenComparingInt(Entry::marketSize)
                .thenComparing(Entry::factionId)
                .thenComparingInt(Entry::anchorX)
                .thenComparingInt(Entry::anchorY));

        StringBuilder canonical = new StringBuilder(sorted.size() * 56);
        for (Entry entry : sorted) {
            if (canonical.length() > 0) {
                canonical.append('\n');
            }
            canonical.append(entry.systemId())
                    .append('|')
                    .append(entry.marketId())
                    .append('|')
                    .append(entry.marketSize())
                    .append('|')
                    .append(entry.factionId())
                    .append('|')
                    .append(entry.anchorX())
                    .append('|')
                    .append(entry.anchorY());
        }
        return canonical.toString();
    }

    private record MarketRef(String id, int size, String faction) {
    }

    private static List<Entry> entriesFromSector(SectorAPI sector) {
        Objects.requireNonNull(sector, "sector");
        Map<String, List<MarketRef>> marketsBySystem = marketsBySystem(sector);
        List<Entry> entries = new ArrayList<>();
        for (StarSystemAPI system : sector.getStarSystems()) {
            if (!includeSystem(system)) {
                continue;
            }
            String systemId = normalize(system.getId());
            Vector2f anchor = anchorCoordinates(system);
            List<MarketRef> markets = marketsBySystem.get(systemId);
            if (markets == null || markets.isEmpty()) {
                entries.add(entry(systemId, null, anchor.x, anchor.y));
                continue;
            }
            for (MarketRef market : markets) {
                entries.add(entry(systemId, market.id(), market.size(), market.faction(), anchor.x, anchor.y));
            }
        }
        return entries;
    }

    /**
     * All star systems belong in the fingerprint. Deep-space special content (gate hauler,
     * derelict rocks, abyssal objects) is made deterministic via coop-forks.jar RNG forks rather
     * than excluded here, so it is fingerprinted and must match. Only dynamic host-authoritative
     * base markets are dropped - see {@link #includeMarket(MarketAPI)}.
     */
    static boolean includeSystem(StarSystemAPI system) {
        return system != null;
    }

    /**
     * Hidden markets are dynamically-placed pirate/Luddic-Path bases (created with
     * {@code setHidden(true)} and an engine {@code genUID} id, added/removed on a timer all
     * campaign long). They are inherently not reproducible from the seed - their ids differ per
     * client by construction and they are host-authoritative - so they are excluded from the
     * deterministic seed-lock fingerprint (and replicated separately for gameplay correctness).
     */
    static boolean includeMarket(MarketAPI market) {
        return market != null && !market.isHidden();
    }

    private static Map<String, List<MarketRef>> marketsBySystem(SectorAPI sector) {
        Map<String, List<MarketRef>> marketsBySystem = new HashMap<>();
        EconomyAPI economy = sector.getEconomy();
        if (economy == null) {
            return marketsBySystem;
        }

        for (MarketAPI market : economy.getMarketsCopy()) {
            if (!includeMarket(market)) {
                continue;
            }
            StarSystemAPI system = market.getStarSystem();
            if (system == null) {
                continue;
            }
            marketsBySystem
                    .computeIfAbsent(normalize(system.getId()), ignored -> new ArrayList<>())
                    .add(new MarketRef(normalize(market.getId()), market.getSize(), normalize(market.getFactionId())));
        }
        return marketsBySystem;
    }

    private static Vector2f anchorCoordinates(StarSystemAPI system) {
        SectorEntityToken anchor = system.getHyperspaceAnchor();
        if (anchor != null) {
            Vector2f location = anchor.getLocationInHyperspace();
            if (location != null) {
                return location;
            }
            location = anchor.getLocation();
            if (location != null) {
                return location;
            }
        }

        Vector2f location = system.getLocation();
        if (location != null) {
            return location;
        }
        return new Vector2f();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
