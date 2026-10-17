package coop.ambient;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

/**
 * Transient role guard for the pinned native wreck hooks. Bundled only in coop-forks.jar so the
 * engine's parent classloader can read it; no transport or mod-classloader object crosses this API.
 */
public final class CoopWreckBridge {
    public static final String WRECK_ID_KEY = "$coopWreckId";
    public static final int MAX_ID_LENGTH = 256;
    public static final float SHARED_SPAWN_NEIGHBORHOOD = 10000f;
    private static volatile boolean guestMode;
    private static Boolean versionOk;

    private CoopWreckBridge() {
    }

    /** Set before the first campaign frame, including while a guest is still in the lobby. */
    public static void setGuestMode(boolean value) {
        guestMode = value;
    }

    public static synchronized void clear() {
        guestMode = false;
        versionOk = null;
    }

    /** A stale fork never changes native behavior on an unsupported engine version. */
    public static boolean isGuestMode() {
        return guestMode && versionMatches();
    }

    /** Only a canonical, explicitly owned wreck is exempt from guest physics and native expiry. */
    public static boolean isGuestReplica(SectorEntityToken entity) {
        if (!isGuestMode() || entity == null) return false;
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        return memory != null && isCanonicalId(memory.get(WRECK_ID_KEY));
    }

    static boolean isCanonicalId(Object value) {
        return value instanceof String id && id.startsWith("wreck:") && id.length() > 6
                && id.length() <= MAX_ID_LENGTH && id.substring(6).matches("[A-Za-z0-9_-]+");
    }

    /** Native spawn rings have a maximum 5000-su radius; overlapping neighborhoods get one check. */
    public static boolean needsGuestSpawnCheck(boolean hostInHyperspace, float distanceSquared) {
        return !hostInHyperspace || Float.isFinite(distanceSquared)
                && distanceSquared >= SHARED_SPAWN_NEIGHBORHOOD * SHARED_SPAWN_NEIGHBORHOOD;
    }

    private static synchronized boolean versionMatches() {
        if (versionOk != null) return versionOk;
        String version = Global.getSettings() == null ? null : Global.getSettings().getVersionString();
        versionOk = version != null && version.contains(coop.presence.CoopPresenceRegistry.PINNED_VERSION);
        if (!versionOk) {
            Global.getLogger(CoopWreckBridge.class).warn("[COOP-FORK] wreck hooks disabled: expected "
                    + coop.presence.CoopPresenceRegistry.PINNED_VERSION + ", got " + version);
        }
        return versionOk;
    }
}
