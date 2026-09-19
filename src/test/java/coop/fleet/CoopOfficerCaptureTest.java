package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who gets officers on the wire, and who deliberately does not.
 *
 * <p>The player snapshot does, because Phase 33's ally fights with them and pays real losses for it.
 * The NPC replicator does not: {@code NPC_FLEET_SET} carries every replicated fleet in the sector and
 * a guest's NPC mirrors never fight, so doubling the per-ship record would be a payload cost with no
 * gameplay behind it.
 */
class CoopOfficerCaptureTest {

    @Test
    void thePlayerPathStreamsTheOfficer() {
        CoopFleetSnapshot.Member member = CoopFleetSnapshotFactory
                .captureRoster(fleetWithCaptainedShip()).members().get(0);

        assertEquals("Hanan Yusuf", member.captainName());
        assertEquals(5, member.captainLevel());
        assertEquals("aggressive", member.captainPersonality());
        assertEquals("combat_endurance:1,target_analysis:2", member.captainSkills());
    }

    @Test
    void theNpcPathLeavesTheOfficerOff() {
        CoopFleetSnapshot.Member member = CoopFleetSnapshotFactory
                .captureMembersWithoutOfficers(fleetWithCaptainedShip()).get(0);

        // The name stays: it has been on the wire since Phase 8 and the mirror's tooltip reads it.
        assertEquals("Hanan Yusuf", member.captainName());
        assertEquals(0, member.captainLevel());
        assertEquals("", member.captainPersonality());
        assertEquals("", member.captainSkills());
    }

    @Test
    void aCharacterThatCannotReportItsSkillsStreamsNone() {
        PersonAPI throwing = (PersonAPI) Proxy.newProxyInstance(
                PersonAPI.class.getClassLoader(),
                new Class<?>[] {PersonAPI.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("no stats");
                });

        assertEquals("", CoopFleetSnapshotFactory.captureSkills(throwing));
        assertEquals("", CoopFleetSnapshotFactory.captureSkills(null));
    }

    // ---- fakes -----------------------------------------------------------------------------------

    private static CampaignFleetAPI fleetWithCaptainedShip() {
        FleetMemberAPI member = captainedMember();
        FleetDataAPI data = (FleetDataAPI) Proxy.newProxyInstance(
                FleetDataAPI.class.getClassLoader(),
                new Class<?>[] {FleetDataAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMembersListCopy" -> new ArrayList<>(List.of(member));
                    case "toString" -> "FleetData";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getFleetData" -> data;
                    case "getName" -> "Ayo's fleet";
                    case "toString" -> "Fleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** No variant on purpose: it keeps the hullmod capture away from {@code Global.getSettings()}. */
    private static FleetMemberAPI captainedMember() {
        PersonAPI captain = person(5, "aggressive",
                skills("combat_endurance", 1f, "target_analysis", 2f, "gunnery_implants", 0f));
        FleetMemberStatusAPI status = (FleetMemberStatusAPI) Proxy.newProxyInstance(
                FleetMemberStatusAPI.class.getClassLoader(),
                new Class<?>[] {FleetMemberStatusAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHullFraction" -> 0.9f;
                    case "toString" -> "Status";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        RepairTrackerAPI repair = (RepairTrackerAPI) Proxy.newProxyInstance(
                RepairTrackerAPI.class.getClassLoader(),
                new Class<?>[] {RepairTrackerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCR", "getBaseCR" -> 0.8f;
                    case "isMothballed" -> Boolean.FALSE;
                    case "toString" -> "Repair";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (FleetMemberAPI) Proxy.newProxyInstance(
                FleetMemberAPI.class.getClassLoader(),
                new Class<?>[] {FleetMemberAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "m1";
                    case "isFighterWing" -> Boolean.FALSE;
                    case "getVariant" -> null;
                    case "getSpecId", "getHullId" -> "falcon";
                    case "getShipName" -> "ISS Anything";
                    case "getCaptain" -> captain;
                    case "getStatus" -> status;
                    case "getRepairTracker" -> repair;
                    case "toString" -> "Member";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PersonAPI person(int level, String personality,
                                    List<MutableCharacterStatsAPI.SkillLevelAPI> skills) {
        MutableCharacterStatsAPI stats = (MutableCharacterStatsAPI) Proxy.newProxyInstance(
                MutableCharacterStatsAPI.class.getClassLoader(),
                new Class<?>[] {MutableCharacterStatsAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLevel" -> level;
                    case "getSkillsCopy" -> skills;
                    case "toString" -> "Stats";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        Object personalityApi = Proxy.newProxyInstance(
                com.fs.starfarer.api.characters.PersonalityAPI.class.getClassLoader(),
                new Class<?>[] {com.fs.starfarer.api.characters.PersonalityAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> personality;
                    case "toString" -> "Personality";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (PersonAPI) Proxy.newProxyInstance(
                PersonAPI.class.getClassLoader(),
                new Class<?>[] {PersonAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDefault" -> Boolean.FALSE;
                    case "getNameString" -> "Hanan Yusuf";
                    case "getStats" -> stats;
                    case "getPersonalityAPI" -> personalityApi;
                    case "toString" -> "Person";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** {@code getSkillsCopy()} hands back every skill, most at level 0; the encoder drops those. */
    private static List<MutableCharacterStatsAPI.SkillLevelAPI> skills(Object... idsAndLevels) {
        Map<String, Float> ordered = new LinkedHashMap<>();
        for (int i = 0; i < idsAndLevels.length; i += 2) {
            ordered.put((String) idsAndLevels[i], (Float) idsAndLevels[i + 1]);
        }
        List<MutableCharacterStatsAPI.SkillLevelAPI> out = new ArrayList<>();
        for (Map.Entry<String, Float> entry : ordered.entrySet()) {
            SkillSpecAPI spec = (SkillSpecAPI) Proxy.newProxyInstance(
                    SkillSpecAPI.class.getClassLoader(),
                    new Class<?>[] {SkillSpecAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> entry.getKey();
                        case "toString" -> "Skill " + entry.getKey();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            out.add((MutableCharacterStatsAPI.SkillLevelAPI) Proxy.newProxyInstance(
                    MutableCharacterStatsAPI.SkillLevelAPI.class.getClassLoader(),
                    new Class<?>[] {MutableCharacterStatsAPI.SkillLevelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getLevel" -> entry.getValue();
                        case "getSkill" -> spec;
                        case "toString" -> "SkillLevel";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    }));
        }
        return out;
    }
}
