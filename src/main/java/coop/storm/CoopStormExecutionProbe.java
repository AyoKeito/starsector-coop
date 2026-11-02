package coop.storm;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Opt-in, bounded observations of returned native physical-effect calls. Never saved. */
public final class CoopStormExecutionProbe {
    private static final int MAX_EVENTS = 64;
    private static final List<String> ACTIONS = List.of(
            "attempts", "blocked", "boostInstalled", "velocityWrites", "crApplications", "hullApplications");
    private static final Map<String, Long> COUNTS = new LinkedHashMap<>();
    private static final ArrayDeque<Map<String, Object>> EVENTS = new ArrayDeque<>();
    private static BooleanSupplier enabled = () -> false;
    private static long sequence;

    private CoopStormExecutionProbe() { }

    static void install(BooleanSupplier supplier) {
        clear();
        enabled = supplier;
    }

    static void clear() {
        enabled = () -> false;
        COUNTS.clear();
        EVENTS.clear();
        sequence = 0;
    }

    /** The guard decision is supplied by the native call site; this probe never decides physics. */
    public static void observeAttempt(CampaignFleetAPI fleet, int i, int j, boolean allowed) {
        String category = category(fleet);
        if (category == null) return;
        increment(category, "attempts");
        if (!allowed) increment(category, "blocked");
        // Per-frame refusal counts remain complete without crowding out returned physical calls.
        if (COUNTS.get(category + ".attempts") == 1L)
            append(event(fleet, category, allowed ? "attemptAllowed" : "attemptBlocked", i, j));
    }

    public static void observeBoostInstalled(CampaignFleetAPI fleet, int i, int j) {
        observe(fleet, "boostInstalled", i, j, null, 0f);
    }

    public static void observeCrApplied(CampaignFleetAPI fleet, int i, int j, String memberId, float amount) {
        observe(fleet, "crApplications", i, j, memberId, amount);
    }

    public static void observeHullApplied(CampaignFleetAPI fleet, int i, int j, String memberId, float amount) {
        observe(fleet, "hullApplications", i, j, memberId, amount);
    }

    /** Called after native setVelocity returns, including a zero delta at its elapsed endpoints. */
    public static void observeVelocityWrite(CampaignFleetAPI fleet, int i, int j, float beforeX, float beforeY) {
        String category = category(fleet);
        if (category == null) return;
        increment(category, "velocityWrites");
        Map<String, Object> event = event(fleet, category, "velocityWrites", i, j);
        Vector2f after = fleet.getVelocity();
        event.put("beforeX", beforeX); event.put("beforeY", beforeY);
        event.put("afterX", after.x); event.put("afterY", after.y);
        event.put("deltaX", after.x - beforeX); event.put("deltaY", after.y - beforeY);
        append(event);
    }

    /** Pure snapshot: reads neither native effect methods nor fleet state, and resets nothing. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled.getAsBoolean());
        result.put("sequence", sequence);
        result.put("eventLimit", MAX_EVENTS);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String category : List.of("OWNER", "MIRROR"))
            for (String action : ACTIONS) counts.put(category + "." + action, COUNTS.getOrDefault(category + "." + action, 0L));
        result.put("counts", Collections.unmodifiableMap(counts));
        result.put("events", List.copyOf(new ArrayList<>(EVENTS)));
        return result;
    }

    private static void observe(CampaignFleetAPI fleet, String action, int i, int j, String memberId, float amount) {
        String category = category(fleet);
        if (category == null) return;
        increment(category, action);
        Map<String, Object> event = event(fleet, category, action, i, j);
        if (memberId != null) event.put("memberId", memberId);
        if (action.equals("crApplications") || action.equals("hullApplications")) event.put("amount", amount);
        append(event);
    }

    private static String category(CampaignFleetAPI fleet) {
        if (!enabled.getAsBoolean() || fleet == null || Global.getSector() == null) return null;
        if (CoopStormBridge.isMirror(fleet)) return "MIRROR";
        return fleet == Global.getSector().getPlayerFleet() ? "OWNER" : null;
    }

    private static void increment(String category, String action) {
        COUNTS.merge(category + "." + action, 1L, Long::sum);
    }

    private static Map<String, Object> event(CampaignFleetAPI fleet, String category, String action, int i, int j) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sequence", ++sequence);
        result.put("campaignTimestamp", Global.getSector().getClock() == null ? null : Global.getSector().getClock().getTimestamp());
        result.put("fleetId", fleet.getId()); result.put("category", category); result.put("action", action);
        result.put("i", i); result.put("j", j);
        return result;
    }

    private static void append(Map<String, Object> event) {
        EVENTS.addLast(Collections.unmodifiableMap(event));
        while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
    }
}
