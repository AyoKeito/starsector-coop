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

    public static boolean isIronModeActive(boolean activeSectorIronMode, Map<String, ?> persistentData) {
        return activeSectorIronMode || containsIronModeTrue(persistentData);
    }

    private static boolean containsIronModeTrue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (IRON_MODE_FIELD.equals(String.valueOf(entry.getKey())) && isTrue(entry.getValue())) {
                    return true;
                }
                if (containsIronModeTrue(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (containsIronModeTrue(entry)) {
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
