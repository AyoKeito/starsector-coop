package coop.ambient;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin.DespawnType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import coop.presence.CoopPresenceRegistry;

import java.util.UUID;

/** Transient system-loader seam for the pinned native light plugin. Never saved on an entity. */
public final class CoopLightBridge {
    public static final String LIGHT_ID_KEY = "$coopLightId";
    public static final String ENCOUNTER_FLEET_KEY = "$coopLightEncounterFleet";
    private static final String PLAYER_MIRROR = "$coopMirrorFleet";
    private static final String NPC_MIRROR = "$coopNpcFleetId";
    private static volatile boolean guestMode;
    private static Handler handler;
    private static Boolean versionOk;
    private static int authoritativeDespawnDepth;

    public interface Handler {
        void requestPulse(SectorEntityToken light, boolean sensorBurst);
        void requestDwellerConsumption(SectorEntityToken light);
        boolean allowDwellerDialog(SectorEntityToken light);
    }

    private CoopLightBridge() { }

    public static void setGuestMode(boolean value) { guestMode = value; }

    public static void install(boolean guest, Handler value) {
        if (handler != value) CoopLightDeferredEngagement.clear();
        guestMode = guest;
        handler = value;
    }

    /** Identity only: deferred native actions cannot migrate to another session's adapter. */
    public static Object interactionOwner() { return handler; }

    /** A retiring adapter cannot uninstall a newer campaign's callback. */
    public static void uninstall(Handler owner) {
        if (handler == owner) {
            handler = null;
            CoopLightDeferredEngagement.clear();
        }
    }

    public static void clear() {
        CoopLightDeferredEngagement.clear();
        guestMode = false;
        handler = null;
        versionOk = null;
        authoritativeDespawnDepth = 0;
    }

    public static boolean isCanonicalId(Object value) {
        if (!(value instanceof String id) || !id.startsWith("light:") || id.length() != 42) return false;
        try { return UUID.fromString(id.substring(6)).toString().equals(id.substring(6)); }
        catch (IllegalArgumentException ex) { return false; }
    }

    public static boolean isNativeLight(SectorEntityToken light) {
        return light != null && light.getCustomPlugin() != null
                && light.getCustomPlugin().getClass() == AbyssalLightEntityPlugin.class;
    }

    public static String lightId(SectorEntityToken light) {
        if (!isSharedLight(light)) return null;
        MemoryAPI memory = light.getMemoryWithoutUpdate();
        Object value = memory == null ? null : memory.get(LIGHT_ID_KEY);
        return isCanonicalId(value) ? (String) value : null;
    }

    /** Legacy guest lights are frozen too, until a complete Host manifest replaces them. */
    public static boolean holdsGuestLifetime(SectorEntityToken light) {
        return guestMode && versionMatches() && isSharedLight(light);
    }

    private static boolean isSharedLight(SectorEntityToken light) {
        return isNativeLight(light) && light.getContainingLocation() != null
                && light.getContainingLocation().isHyperspace();
    }

    public static boolean isPlayerMirror(CampaignFleetAPI fleet) {
        MemoryAPI memory = fleet == null ? null : fleet.getMemoryWithoutUpdate();
        return memory != null && memory.contains(PLAYER_MIRROR);
    }

    private static boolean isAnyMirror(CampaignFleetAPI fleet) {
        MemoryAPI memory = fleet == null ? null : fleet.getMemoryWithoutUpdate();
        return memory != null && (memory.contains(PLAYER_MIRROR) || memory.contains(NPC_MIRROR));
    }

    /** True means the native grant/despawn branch was handled or deliberately suppressed. */
    public static boolean handlePulse(SectorEntityToken light, CampaignFleetAPI fleet, boolean sensorBurst) {
        if (!versionMatches() || !isSharedLight(light)) return false;
        if (isAnyMirror(fleet)) return true;
        if (guestMode) {
            if (fleet != null && fleet.isPlayerFleet() && lightId(light) != null && handler != null) {
                handler.requestPulse(light, sensorBurst);
            }
            return true;
        }
        if (fleet != null && fleet.isPlayerFleet() && lightId(light) != null && handler != null) {
            handler.requestPulse(light, sensorBurst);
            return true;
        }
        return false;
    }

    /** Mirrors are never native NPC casualties, and Guest never runs NPC light interactions. */
    public static boolean allowDwellerEncounter(SectorEntityToken light, CampaignFleetAPI fleet) {
        if (!versionMatches() || !isSharedLight(light)) return true;
        return !isAnyMirror(fleet) && (!guestMode || fleet != null && fleet.isPlayerFleet());
    }

    public static boolean allowDwellerDialog(SectorEntityToken light, CampaignFleetAPI fleet) {
        if (!versionMatches() || !isSharedLight(light)) return true;
        if (!allowDwellerEncounter(light, fleet)) return false;
        return handler == null ? !guestMode : handler.allowDwellerDialog(light);
    }

    /** Native DwellerCMD calls despawn when combat starts. Guest queues that shared consumption. */
    public static boolean allowNativeDespawn(SectorEntityToken light) {
        if (authoritativeDespawnDepth > 0 || !holdsGuestLifetime(light)) return true;
        if (lightId(light) != null && handler != null) handler.requestDwellerConsumption(light);
        return false;
    }

    public static void despawnAuthoritatively(SectorEntityToken light, boolean expand) {
        if (!isNativeLight(light)) throw new IllegalArgumentException("Not an exact native abyssal light");
        authoritativeDespawnDepth++;
        try {
            ((AbyssalLightEntityPlugin) light.getCustomPlugin()).despawn(
                    expand ? DespawnType.EXPAND : DespawnType.FADE_OUT);
        } finally {
            authoritativeDespawnDepth--;
        }
    }

    public static void markEncounterFleet(CampaignFleetAPI fleet, SectorEntityToken light) {
        String id = lightId(light);
        if (fleet != null && id != null && versionMatches()) {
            fleet.getMemoryWithoutUpdate().set(ENCOUNTER_FLEET_KEY, id);
        }
    }

    public static boolean isEncounterFleet(CampaignFleetAPI fleet) {
        return fleet != null && fleet.getFaction() != null
                && Factions.DWELLER.equals(fleet.getFaction().getId())
                && fleet.getMemoryWithoutUpdate() != null
                && isCanonicalId(fleet.getMemoryWithoutUpdate().get(ENCOUNTER_FLEET_KEY));
    }

    public static void discardEncounterFleet(CampaignFleetAPI fleet) {
        if (isEncounterFleet(fleet) && fleet.getContainingLocation() != null) {
            fleet.getContainingLocation().removeEntity(fleet);
        }
    }

    private static synchronized boolean versionMatches() {
        if (versionOk != null) return versionOk;
        String version = Global.getSettings() == null ? null : Global.getSettings().getVersionString();
        versionOk = version != null && version.contains(CoopPresenceRegistry.PINNED_VERSION);
        if (!versionOk) Global.getLogger(CoopLightBridge.class).warn("[COOP-FORK] light hooks disabled: expected "
                + CoopPresenceRegistry.PINNED_VERSION + ", got " + version);
        return versionOk;
    }
}
