package coop.session;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;

import java.util.Collection;
import java.util.Map;

public final class CoopIronModeGuard {
    private static final String IRON_MODE_FIELD = "isIronMode";

    private CoopIronModeGuard() {
    }

    public static boolean isIronModeActive() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return false;
            }
            return isIronModeActive(sector.isIronMode(), sector.getPersistentData());
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Maximum container nesting the scan will walk: the top-level map plus one nested map/collection
     * level. Deeper data belongs to third-party mods storing their own state, and an unbounded walk
     * false-positives on any of them that happens to use an {@code isIronMode} key of its own.
     */
    private static final int MAX_SCAN_DEPTH = 2;

    public static boolean isIronModeActive(boolean activeSectorIronMode, Map<String, ?> persistentData) {
        return activeSectorIronMode || containsIronModeTrue(persistentData, 1);
    }

    private static boolean containsIronModeTrue(Object value, int depth) {
        if (value == null || depth > MAX_SCAN_DEPTH) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (IRON_MODE_FIELD.equals(String.valueOf(entry.getKey())) && isTrue(entry.getValue())) {
                    return true;
                }
                if (containsIronModeTrue(entry.getValue(), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (containsIronModeTrue(entry, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value instanceof String stringValue && Boolean.parseBoolean(stringValue.trim());
    }
}
