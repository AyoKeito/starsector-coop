package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.ImportantPeopleAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.RelationshipAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import coop.testing.RecordingNetService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static coop.testing.ProxyDefaults.defaultValue;
import static coop.testing.TestSessions.activeGuestSession;
import static coop.testing.TestSessions.activeHostSession;

class CoopCampaignReplicatorRepTest {

    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
    }

    @Test
    void guestPersonReputationChangeReportsDeltaToHost() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1234L);

        replicator.onPlayerReputationChange(person("p_contact", null), -0.03f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message message = service.sent.get(0);
        assertEquals(CoopMessages.Type.GUEST_REP_DELTA, message.type());
        assertEquals("PERSON", CoopMessages.requiredPayloadString(message, "targetType"));
        assertEquals("p_contact", CoopMessages.requiredPayloadString(message, "targetId"));
        assertEquals(-0.03f, CoopMessages.requiredPayloadFloat(message, "delta"));
    }

    @Test
    void hostAppliesGuestPersonDeltaAndRebroadcastsCanonicalResult() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        RecordingRelationship relationship = new RecordingRelationship(0.1f);
        PersonAPI person = person("p_contact", relationship.proxy());
        Global.setSector(sectorWithPerson(person));
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 5678L);

        replicator.handle(CoopMessages.guestRepDelta(
                "session-a", 7L, 5000L, "PERSON", "p_contact", -0.25f));

        assertEquals(-0.15f, relationship.rel, 0.0001f);
        assertEquals(1, service.sent.size());
        CoopMessages.Message message = service.sent.get(0);
        assertEquals(CoopMessages.Type.REP_DELTA, message.type());
        assertEquals("PERSON", CoopMessages.requiredPayloadString(message, "targetType"));
        assertEquals("p_contact", CoopMessages.requiredPayloadString(message, "targetId"));
        assertEquals(-0.25f, CoopMessages.requiredPayloadFloat(message, "delta"));
        assertEquals(-0.15f, CoopMessages.requiredPayloadFloat(message, "resultingValue"), 0.0001f);
    }

    private static PersonAPI person(String id, RelationshipAPI relationship) {
        return (PersonAPI) Proxy.newProxyInstance(
                PersonAPI.class.getClassLoader(),
                new Class<?>[]{PersonAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getRelToPlayer" -> relationship;
                    case "toString" -> "Person[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static SectorAPI sectorWithPerson(PersonAPI person) {
        ImportantPeopleAPI importantPeople = (ImportantPeopleAPI) Proxy.newProxyInstance(
                ImportantPeopleAPI.class.getClassLoader(),
                new Class<?>[]{ImportantPeopleAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPerson" -> person.getId().equals(args[0]) ? person : null;
                    case "toString" -> "ImportantPeople";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getImportantPeople" -> importantPeople;
                    case "toString" -> "Sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class RecordingRelationship {
        private float rel;

        private RecordingRelationship(float rel) {
            this.rel = rel;
        }

        private RelationshipAPI proxy() {
            return (RelationshipAPI) Proxy.newProxyInstance(
                    RelationshipAPI.class.getClassLoader(),
                    new Class<?>[]{RelationshipAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getRel" -> rel;
                        case "setRel" -> {
                            rel = (float) args[0];
                            yield null;
                        }
                        case "toString" -> "Relationship[" + rel + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

}
