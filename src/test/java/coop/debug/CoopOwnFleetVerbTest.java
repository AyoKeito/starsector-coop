package coop.debug;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import coop.net.CoopNetPump;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ownfleet} bridge verb.
 *
 * <p>Its whole reason to exist is that the live S5-B smoke could read a CR number off the fleet
 * screen and nothing else — not {@code maxCR}, not the mothball flags, and above all not the engine's
 * own CR-event trail, which is where a "no supplies" penalty says so in vanilla's own words. So what
 * this pins is the field list: a verb that answers with three of the eight fields is a verb that
 * sends the next smoke back for a fourth run.
 */
class CoopOwnFleetVerbTest {

    @Test
    void theVerbIsRegistered() {
        assertTrue(new CoopAgentCommands().verbs().contains("ownfleet"));
    }

    @Test
    void itReportsTheFleetAndEveryReadinessFieldPerMember() throws Exception {
        JSONObject out = CoopAgentCommands.ownfleet(new JSONObject(), context(sector()));

        assertEquals("Jul 4, c206", out.getString("date"));
        assertEquals(14, out.getInt("hour"));
        assertEquals("corvus", out.getString("locationId"));
        assertEquals(0d, out.getDouble("supplies"), 1e-6);
        assertEquals(120d, out.getDouble("fuel"), 1e-6);
        assertEquals(40, out.getInt("crew"));
        assertEquals(4d, out.getDouble("fleetMinCrew"), 1e-6);
        assertEquals(1, out.getInt("memberCount"));
        assertTrue(out.has("diagnostics"));
        assertTrue(out.has("battleActive"));
        assertTrue(out.has("modWrites"));

        JSONObject member = out.getJSONArray("members").getJSONObject(0);
        assertEquals("m1", member.getString("id"));
        assertEquals("ziggurat_Experimental", member.getString("hullId"));
        assertEquals("ziggurat_Experimental", member.getString("variantId"));
        assertEquals("Bad Faith", member.getString("name"));
        assertEquals(0.21d, member.getDouble("cr"), 1e-6);
        assertEquals(0.70d, member.getDouble("maxCr"), 1e-6);
        assertEquals(0.21d, member.getDouble("baseCr"), 1e-6);
        assertEquals(0.87d, member.getDouble("hullFraction"), 1e-6);
        assertFalse(member.getBoolean("mothballed"));
        assertTrue(member.getBoolean("crashMothballing"));
        assertFalse(member.getBoolean("suspendRepairs"));
        assertTrue(member.getBoolean("needsRepairs"));
        assertEquals(4d, member.getDouble("minCrew"), 1e-6);
        assertEquals(6d, member.getDouble("neededCrew"), 1e-6);
        assertEquals(0.02d, member.getDouble("repairRatePerDay"), 1e-6);
        assertEquals(-0.05d, member.getDouble("decreaseRate"), 1e-6);
    }

    /**
     * The field this verb was written for: vanilla's own reason strings for why readiness is moving.
     * A smoke that sees {@code no_supplies} here never has to ask whether the mod did it.
     */
    @Test
    void itCarriesTheEnginesOwnCrEventTrail() throws Exception {
        JSONObject member = CoopAgentCommands.ownfleet(new JSONObject(), context(sector()))
                .getJSONArray("members").getJSONObject(0);

        JSONObject noSupply = member.getJSONObject("noSupplyCrLoss");
        assertEquals("no_supplies", noSupply.getString("id"));
        assertEquals(-0.04d, noSupply.getDouble("crAmount"), 1e-6);
        assertEquals("Out of supplies", noSupply.getString("text"));

        JSONArray events = member.getJSONArray("recentEvents");
        assertEquals(1, events.length());
        assertEquals("no_supplies", events.getJSONObject(0).getString("id"));
        assertEquals(3.5d, events.getJSONObject(0).getDouble("elapsed"), 1e-6);
    }

    /** The drop ring rides along and is empty until the probe has run with diagnostics on. */
    @Test
    void itCarriesTheProbesDropRing() throws Exception {
        CoopOwnFleetProbe.INSTANCE.reset();

        JSONObject out = CoopAgentCommands.ownfleet(new JSONObject(), context(sector()));

        assertEquals(0, out.getJSONArray("drops").length());
    }

    /** No campaign is an error response, not an empty object that reads like "nothing is wrong". */
    @Test
    void noSectorIsAnError() {
        assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.ownfleet(new JSONObject(), context(null)));
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private static CoopAgentCommands.Context context(SectorAPI sector) {
        return new CoopAgentCommands.Context() {
            @Override
            public SectorAPI sector() {
                return sector;
            }

            @Override
            public CoopNetPump pump() {
                return null;
            }
        };
    }

    private static SectorAPI sector() {
        CampaignFleetAPI fleet = fleet();
        CampaignClockAPI clock = proxy(CampaignClockAPI.class, method -> switch (method) {
            case "getDateString" -> "Jul 4, c206";
            case "getHour" -> 14;
            case "getTimestamp" -> 9_000L;
            default -> null;
        });
        return proxy(SectorAPI.class, method -> switch (method) {
            case "getPlayerFleet" -> fleet;
            case "getClock" -> clock;
            case "isPaused" -> false;
            default -> null;
        });
    }

    private static CampaignFleetAPI fleet() {
        FleetMemberAPI member = member();
        FleetDataAPI data = proxy(FleetDataAPI.class, method -> switch (method) {
            case "getMembersListCopy" -> List.of(member);
            case "getNumMembers" -> 1;
            default -> null;
        });
        CargoAPI cargo = proxy(CargoAPI.class, method -> switch (method) {
            case "getSupplies" -> 0f;
            case "getFuel" -> 120f;
            case "getTotalCrew" -> 40;
            case "getMaxCapacity" -> 200f;
            default -> null;
        });
        LocationAPI location = proxy(LocationAPI.class, method -> "getId".equals(method)
                ? "corvus" : null);
        return proxy(CampaignFleetAPI.class, method -> switch (method) {
            case "getFleetData" -> data;
            case "getCargo" -> cargo;
            case "getContainingLocation" -> location;
            case "isInHyperspace" -> false;
            default -> null;
        });
    }

    private static FleetMemberAPI member() {
        RepairTrackerAPI.CREvent event = new RepairTrackerAPI.CREvent(-0.04f, "Out of supplies");
        event.id = "no_supplies";
        event.elapsed = 3.5f;
        RepairTrackerAPI repair = proxy(RepairTrackerAPI.class, method -> switch (method) {
            case "getCR", "getBaseCR" -> 0.21f;
            case "getMaxCR" -> 0.70f;
            case "isMothballed", "isSuspendRepairs" -> false;
            case "isCrashMothballed" -> true;
            case "getRepairRatePerDay" -> 0.02f;
            case "getDecreaseRate" -> -0.05f;
            case "getRecoveryRate" -> 0.01f;
            case "getRemainingRepairTime" -> 4f;
            case "computeRepairednessFraction" -> 0.87f;
            case "getCRPriorToMothballing" -> 0f;
            case "getNoSupplyCRLossEvent" -> event;
            case "getRecentEvents" -> List.of(event);
            default -> null;
        });
        FleetMemberStatusAPI status = proxy(FleetMemberStatusAPI.class, method -> switch (method) {
            case "getHullFraction" -> 0.87f;
            case "getHullDamageTaken" -> 400f;
            case "needsRepairs" -> true;
            default -> null;
        });
        ShipVariantAPI variant = proxy(ShipVariantAPI.class, method ->
                "getHullVariantId".equals(method) ? "ziggurat_Experimental" : null);
        return proxy(FleetMemberAPI.class, method -> switch (method) {
            case "getId" -> "m1";
            case "getHullId" -> "ziggurat_Experimental";
            case "getShipName" -> "Bad Faith";
            case "getVariant" -> variant;
            case "getRepairTracker" -> repair;
            case "getStatus" -> status;
            case "getMinCrew" -> 4f;
            case "getNeededCrew" -> 6f;
            case "getMaxCrew" -> 8f;
            case "getCrewFraction" -> 1f;
            case "isMothballed" -> false;
            default -> null;
        });
    }

    /** Anything the lambda answers null for falls through to the zero value for its return type. */
    @FunctionalInterface
    private interface Answers {
        Object answer(String method);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answers answers) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (self, method, args) -> switch (method.getName()) {
                    case "toString" -> "Fake" + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> {
                        Object answer = answers.answer(method.getName());
                        yield answer == null ? defaultValue(method.getReturnType()) : answer;
                    }
                });
    }
}
