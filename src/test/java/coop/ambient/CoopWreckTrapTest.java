package coop.ambient;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.FleetAIFlags;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.TacticalModulePlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.TransmitterTrapSpecial;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.TransmitterTrapSpecial.TransmitterTrapSpecialData;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the pinned trap special, including its real existing-fleet interception path. */
class CoopWreckTrapTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;
    private final LocationAPI hyper = proxy(LocationAPI.class, Map.of("isHyperspace", true));
    private final List<Assignment> assignments = new ArrayList<>();
    private final Map<String, Object> fleetMemory = new HashMap<>();
    private SectorEntityToken tacticalTarget;
    private int playerLookups;
    private int fleetLookups;
    private CampaignFleetAPI host;
    private CampaignFleetAPI guest;
    private CampaignFleetAPI ambush;

    @BeforeEach
    void setup() {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        CoopWreckBridge.clear();
        Global.setSettings(proxy(SettingsAPI.class,
                Map.of("getVersionString", "Starsector 0.98a-RC8", "getColor", Color.WHITE)));
        host = fleet(hyper, new Vector2f(1f, 2f));
        guest = fleet(hyper, new Vector2f(31000f, -12000f));
        TacticalModulePlugin tactical = (TacticalModulePlugin) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TacticalModulePlugin.class}, (p, m, a) -> {
                    if (m.getName().equals("setTarget")) tacticalTarget = (SectorEntityToken) a[0];
                    return ProxyDefaults.defaultValue(m.getReturnType());
                });
        ModularFleetAIAPI ai = proxy(ModularFleetAIAPI.class, Map.of("getTacticalModule", tactical));
        MemoryAPI memory = memory(fleetMemory);
        ambush = (CampaignFleetAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getAI" -> ai;
                    case "getMemoryWithoutUpdate" -> memory;
                    case "addAssignmentAtStart" -> {
                        assignments.add(new Assignment((FleetAssignment) a[0], (SectorEntityToken) a[1], (Float) a[2]));
                        yield null;
                    }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        Global.setSector((SectorAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SectorAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getPlayerFleet" -> { playerLookups++; yield host; }
                    case "getEntityById" -> {
                        fleetLookups++;
                        yield "ambush".equals(a[0]) ? ambush : null;
                    }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                }));
    }

    @AfterEach
    void cleanup() {
        CoopWreckBridge.clear();
        Global.setSettings(previousSettings);
        Global.setSector(previousSector);
    }

    @Test
    void managedGuestAlarmRecordsOnlyPrimitiveActivationWithoutLocalFleetWork() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put(CoopWreckBridge.WRECK_ID_KEY, "wreck:trap");
        SectorEntityToken wreck = wreck(hyper, values);
        NativeTrap special = special(wreck, trap());
        CoopWreckBridge.setGuestMode(true);

        special.transmitterActivated();

        assertEquals(Boolean.TRUE, values.get("$coopWreckTrapActivated"));
        assertEquals(Set.of(CoopWreckBridge.WRECK_ID_KEY, "$coopWreckTrapActivated"), values.keySet());
        assertEquals(0, playerLookups);
        assertEquals(0, fleetLookups);
        assertTrue(assignments.isEmpty());
        assertNull(tacticalTarget);
    }

    @Test
    void unmanagedGuestAlarmRetainsTheNativeLocalPlayerInterceptionPath() throws Exception {
        Map<String, Object> values = new HashMap<>();
        NativeTrap special = special(wreck(hyper, values), trap());
        CoopWreckBridge.setGuestMode(true);

        special.transmitterActivated();

        assertFalse(values.containsKey("$coopWreckTrapActivated"));
        assertEquals(1, playerLookups);
        assertEquals(1, fleetLookups);
        assertEquals(List.of(new Assignment(FleetAssignment.INTERCEPT, host, 30f)), assignments);
        assertSame(host, tacticalTarget);
    }

    @Test
    void managedHostAlarmStillUsesTheNativeHostTarget() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put(CoopWreckBridge.WRECK_ID_KEY, "wreck:trap");

        special(wreck(hyper, values), trap()).transmitterActivated();

        assertFalse(values.containsKey("$coopWreckTrapActivated"));
        assertSame(host, assignments.get(0).target());
        assertSame(host, tacticalTarget);
    }

    @Test
    void hostReplayOfGuestAlarmTargetsGuestForAssignmentsAiAndLastKnownPosition() {
        Map<String, Object> values = new HashMap<>();
        values.put(MemFlags.SALVAGE_SPECIAL_DATA, trap());
        values.put(MemFlags.SALVAGE_SEED, 71723271L);

        TransmitterTrapSpecial.coopActivateForGuest(wreck(hyper, values), guest);

        assertEquals(0, playerLookups, "the host's local fleet must never become the guest alarm target");
        assertEquals(1, fleetLookups);
        assertEquals(List.of(new Assignment(FleetAssignment.INTERCEPT, guest, 30f)), assignments);
        assertSame(guest, tacticalTarget);
        Vector2f lastKnown = (Vector2f) fleetMemory.get(FleetAIFlags.PLACE_TO_LOOK_FOR_TARGET);
        assertEquals(31000f, lastKnown.x);
        assertEquals(-12000f, lastKnown.y);
        assertEquals(Boolean.TRUE, fleetMemory.get(MemFlags.MEMORY_KEY_MAKE_HOSTILE));
        assertEquals(Boolean.TRUE, fleetMemory.get(MemFlags.MEMORY_KEY_LOW_REP_IMPACT));
    }

    @Test
    void guestCannotUseTheHostAlarmReplayEntry() {
        CoopWreckBridge.setGuestMode(true);
        assertThrows(IllegalStateException.class,
                () -> TransmitterTrapSpecial.coopActivateForGuest(wreck(hyper, new HashMap<>()), guest));
        assertEquals(0, fleetLookups);
    }

    @Test
    void hostReplayRefusesMissingTargetsDifferentLocationsAndMissingTrapPayload() {
        SectorEntityToken noTrap = wreck(hyper, new HashMap<>());
        assertThrows(IllegalStateException.class, () -> TransmitterTrapSpecial.coopActivateForGuest(null, guest));
        assertThrows(IllegalStateException.class, () -> TransmitterTrapSpecial.coopActivateForGuest(noTrap, null));
        CampaignFleetAPI otherLocation = fleet(proxy(LocationAPI.class, Map.of()), new Vector2f());
        assertThrows(IllegalStateException.class,
                () -> TransmitterTrapSpecial.coopActivateForGuest(noTrap, otherLocation));
        assertThrows(IllegalStateException.class, () -> TransmitterTrapSpecial.coopActivateForGuest(noTrap, guest));
        assertEquals(0, fleetLookups);
        assertTrue(assignments.isEmpty());
    }

    @Test
    void pinnedTrapForkKeepsNativeSerializedInstanceFields() {
        assertEquals(Set.of("data"), instanceFields(TransmitterTrapSpecial.class));
        assertEquals(Set.of("prob", "fleetId", "nearbyFleetFaction", "useClosestFleetInRange",
                "useAllFleetsInRange", "params", "minRange", "maxRange"),
                instanceFields(TransmitterTrapSpecialData.class));
    }

    private static Set<String> instanceFields(Class<?> type) {
        Set<String> result = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) result.add(field.getName());
        }
        return result;
    }

    private static TransmitterTrapSpecialData trap() {
        TransmitterTrapSpecialData result = new TransmitterTrapSpecialData();
        result.fleetId = "ambush";
        result.prob = 1f;
        return result;
    }

    private static NativeTrap special(SectorEntityToken wreck, TransmitterTrapSpecialData data) throws Exception {
        NativeTrap special = new NativeTrap(wreck);
        Field field = TransmitterTrapSpecial.class.getDeclaredField("data");
        field.setAccessible(true);
        field.set(special, data);
        return special;
    }

    private static SectorEntityToken wreck(LocationAPI location, Map<String, Object> values) {
        return proxy(SectorEntityToken.class,
                Map.of("getContainingLocation", location, "getMemoryWithoutUpdate", memory(values)));
    }

    private static CampaignFleetAPI fleet(LocationAPI location, Vector2f position) {
        return proxy(CampaignFleetAPI.class, Map.of("getContainingLocation", location, "getLocation", position));
    }

    private static MemoryAPI memory(Map<String, Object> values) {
        return (MemoryAPI) Proxy.newProxyInstance(MemoryAPI.class.getClassLoader(), new Class<?>[]{MemoryAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "get" -> values.get(a[0]);
                    case "getLong" -> ((Number) values.getOrDefault(a[0], 0L)).longValue();
                    case "contains" -> values.containsKey(a[0]);
                    case "set" -> { values.put((String) a[0], a[1]); yield null; }
                    case "unset" -> { values.remove(a[0]); yield null; }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> switch (m.getName()) {
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            case "toString" -> type.getSimpleName();
            default -> answers.containsKey(m.getName()) ? answers.get(m.getName())
                    : ProxyDefaults.defaultValue(m.getReturnType());
        });
    }

    private record Assignment(FleetAssignment kind, SectorEntityToken target, float days) { }

    private static final class NativeTrap extends TransmitterTrapSpecial {
        NativeTrap(SectorEntityToken wreck) {
            entity = wreck;
            random = new Random(417L);
        }
    }
}
