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

    public record Entry(String systemId, String marketId, int anchorX, int anchorY) {
        public Entry {
            systemId = normalize(systemId);
            marketId = normalize(marketId);
        }
    }

    public static Entry entry(String systemId, String marketId, float anchorX, float anchorY) {
        return new Entry(systemId, marketId, Math.round(anchorX), Math.round(anchorY));
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
                .thenComparingInt(Entry::anchorX)
                .thenComparingInt(Entry::anchorY));

        StringBuilder canonical = new StringBuilder(sorted.size() * 48);
        for (Entry entry : sorted) {
            if (canonical.length() > 0) {
                canonical.append('\n');
            }
            canonical.append(entry.systemId())
                    .append('|')
                    .append(entry.marketId())
                    .append('|')
                    .append(entry.anchorX())
                    .append('|')
                    .append(entry.anchorY());
        }
        return canonical.toString();
    }

    private static List<Entry> entriesFromSector(SectorAPI sector) {
        Objects.requireNonNull(sector, "sector");
        Map<String, List<String>> marketsBySystem = marketsBySystem(sector);
        List<Entry> entries = new ArrayList<>();
        for (StarSystemAPI system : sector.getStarSystems()) {
            String systemId = normalize(system.getId());
            Vector2f anchor = anchorCoordinates(system);
            List<String> marketIds = marketsBySystem.get(systemId);
            if (marketIds == null || marketIds.isEmpty()) {
                entries.add(entry(systemId, null, anchor.x, anchor.y));
                continue;
            }
            for (String marketId : marketIds) {
                entries.add(entry(systemId, marketId, anchor.x, anchor.y));
            }
        }
        return entries;
    }

    private static Map<String, List<String>> marketsBySystem(SectorAPI sector) {
        Map<String, List<String>> marketsBySystem = new HashMap<>();
        EconomyAPI economy = sector.getEconomy();
        if (economy == null) {
            return marketsBySystem;
        }

        for (MarketAPI market : economy.getMarketsCopy()) {
            StarSystemAPI system = market.getStarSystem();
            if (system == null) {
                continue;
            }
            marketsBySystem
                    .computeIfAbsent(normalize(system.getId()), ignored -> new ArrayList<>())
                    .add(normalize(market.getId()));
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
