package coop.storm;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import coop.presence.CoopPresenceRegistry;
import org.lwjgl.util.vector.Vector2f;

/** Parent-loader seam for the pinned weather forks. Runtime callbacks never enter a save. */
public final class CoopStormBridge {
    public interface Handler {
        CampaignFleetAPI remoteFleet();
        float remoteExtraRadius();
        float remoteTimeScale();
        void tileOff(float x, float y, float radius, float waitDays);
        void surgeWindow(float x, float y, float extraRadius, float timeScale);
        void surgeSource(float x, float y);
        default boolean executionDiagnosticsEnabled() { return false; }
    }

    private static volatile boolean guest;
    private static Handler handler;
    private static Object owner;
    private static Boolean versionOk;

    private CoopStormBridge() { }

    /** Called at application startup, before native readResolve and before any campaign handler. */
    public static void configureGuest(boolean value) { guest = value; }

    public static boolean isGuest() { return guest && versionMatches(); }
    public static boolean sharing() { return handler != null && versionMatches(); }

    public static void install(Object newOwner, boolean isGuest, Handler value) {
        if (newOwner == null || value == null) throw new IllegalArgumentException("Storm bridge owner and handler required");
        owner = newOwner;
        handler = value;
        guest = isGuest;
        CoopStormExecutionProbe.install(value::executionDiagnosticsEnabled);
    }

    public static void uninstall(Object oldOwner) {
        if (owner == oldOwner) { handler = null; owner = null; CoopStormExecutionProbe.clear(); }
    }

    /** Role belongs to the launch and deliberately survives campaign replacement. */
    public static void clear() { handler = null; owner = null; versionOk = null; CoopStormExecutionProbe.clear(); }

    public static CampaignFleetAPI remoteFleet() {
        return !isGuest() && sharing() ? handler.remoteFleet() : null;
    }

    public static CampaignFleetAPI peerFleet() {
        return sharing() ? handler.remoteFleet() : null;
    }

    public static float remoteExtraRadius() {
        return !isGuest() && sharing() ? handler.remoteExtraRadius() : 0f;
    }

    public static float remoteTimeScale() {
        return !isGuest() && sharing() ? handler.remoteTimeScale() : 1f;
    }

    /** Guest native stream/surge effects become bounded weather intents, never private mutations. */
    public static boolean interceptTileState(Vector2f loc, float radius, int state,
                                             float waitDays, float minSignal, float maxSignal) {
        if (!isGuest()) return false;
        if (state == 0 && handler != null && loc != null && minSignal == -1f && maxSignal == -1f) {
            handler.tileOff(loc.x, loc.y, radius, waitDays);
        }
        return true;
    }

    public static void requestWindow(float extraRadius, float timeScale) {
        if (!isGuest() || handler == null || Global.getSector() == null) return;
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null || !fleet.isInHyperspace()) return;
        Vector2f loc = fleet.getLocation();
        handler.surgeWindow(loc.x, loc.y, extraRadius, timeScale);
    }

    /** Called only by a live native clearing branch, including after the expansion script ended. */
    public static void observeNativeSurgeSource(SlipstreamTerrainPlugin2 source) {
        if (!isGuest() || handler == null || Global.getSector() == null || source == null
                || source.getClass() != SlipstreamTerrainPlugin2.class || source.isDynamic()) return;
        SectorEntityToken entity = source.getEntity();
        var params = source.getParams();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        MemoryAPI memory = entity == null ? null : entity.getMemoryWithoutUpdate();
        if (entity == null || !entity.isAlive() || !entity.isInHyperspace()
                || entity.getContainingLocation() != Global.getSector().getHyperspace()
                || memory == null || memory.contains("$coopStreamId")
                || params == null || !"Entering slipsurge".equals(params.enteringSlipstreamTextOverride)
                || params.enteringSlipstreamTextDurationOverride == null
                || params.enteringSlipstreamTextDurationOverride.floatValue() != 0.1f
                || !params.forceNoWindVisualEffectOnFleets || fleet == null || !fleet.isInHyperspace()
                || fleet.getContainingLocation() != entity.getContainingLocation()) return;
        Vector2f loc = fleet.getLocation();
        if (loc != null) handler.surgeSource(loc.x, loc.y);
    }

    public static boolean isMirror(CampaignFleetAPI fleet) {
        MemoryAPI memory = fleet == null ? null : fleet.getMemoryWithoutUpdate();
        return memory != null && (memory.contains("$coopMirrorFleet") || memory.contains("$coopNpcFleetId"));
    }

    /** Mirrors can affect shared storm phases on Host, but never receive native physical strikes. */
    public static boolean allowStrikes(CampaignFleetAPI fleet) {
        return !isMirror(fleet) && (!isGuest() || fleet != null && fleet.isPlayerFleet());
    }

    private static synchronized boolean versionMatches() {
        if (versionOk != null) return versionOk;
        if (Global.getSettings() == null) return false;
        String version = Global.getSettings().getVersionString();
        versionOk = version != null && version.contains(CoopPresenceRegistry.PINNED_VERSION);
        return versionOk;
    }
}
