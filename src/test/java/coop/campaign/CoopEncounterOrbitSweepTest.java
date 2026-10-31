package coop.campaign;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.session.CoopSessionState;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CoopEncounterOrbitSweepTest {
    @Test void capturesOnlyOneManagedOffscreenInstancePerTurnInStableIdOrder() {
        Scene scene = new Scene("zeta", "alpha", "middle");
        scene.systems.add(system("legacy", false));
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        List<String> captured = new ArrayList<>();
        CoopEncounterOrbitSweep sweep = sweep(service, TestSessions.activeHostSession(), captured);

        assertEquals("alpha", sweep.publishNext(scene.api, 1000, Set.of()));
        assertEquals(List.of("alpha"), captured, "the other recipes are not captured on this turn");
        assertEquals("middle", sweep.publishNext(scene.api, 2000, Set.of()));
        assertEquals("zeta", sweep.publishNext(scene.api, 3000, Set.of()));
        assertEquals("alpha", sweep.publishNext(scene.api, 4000, Set.of()));

        assertEquals(List.of("alpha", "middle", "zeta", "alpha"), captured);
        assertEquals(captured, locations(service));
        assertTrue(service.sent.stream().allMatch(message -> message.type() == CoopMessages.Type.ORBIT_SNAPSHOT));
    }

    @Test void occupiedLocationsAndEmptyTablesConsumeTheirTurnWithoutExtraSnapshots() {
        Scene scene = new Scene("alpha", "beta", "gamma");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        List<String> captured = new ArrayList<>();
        CoopEncounterOrbitSweep sweep = new CoopEncounterOrbitSweep(service, TestSessions.activeHostSession(), (sector, system) -> {
            captured.add(system.getOptionalUniqueId());
            return system.getOptionalUniqueId().equals("beta") ? "" : orbit(system.getOptionalUniqueId());
        });

        assertNull(sweep.publishNext(scene.api, 1000, Set.of("alpha")));
        assertTrue(captured.isEmpty(), "a system already covered by occupied-location sync is not walked again");
        assertNull(sweep.publishNext(scene.api, 2000, Set.of("alpha")));
        assertEquals("gamma", sweep.publishNext(scene.api, 3000, Set.of("alpha")));
        assertNull(sweep.publishNext(scene.api, 4000, Set.of("alpha")));

        assertEquals(List.of("beta", "gamma"), captured);
        assertEquals(List.of("gamma"), locations(service));
    }

    @Test void removalEmptySectorAndResetNeverKeepAStaleCursorOrSystemReference() {
        Scene scene = new Scene("alpha", "beta", "gamma");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopEncounterOrbitSweep sweep = sweep(service, TestSessions.activeHostSession(), new ArrayList<>());
        assertEquals("alpha", sweep.publishNext(scene.api, 1000, Set.of()));
        scene.systems.removeIf(system -> system.getOptionalUniqueId().equals("beta"));
        assertEquals("gamma", sweep.publishNext(scene.api, 2000, Set.of()));
        sweep.reset();
        assertEquals("alpha", sweep.publishNext(scene.api, 3000, Set.of()));
        scene.systems.clear();
        assertNull(sweep.publishNext(scene.api, 4000, Set.of()));
        scene.systems.add(system("beta", true)); scene.systems.add(system("alpha", true));
        assertEquals("alpha", sweep.publishNext(scene.api, 5000, Set.of()));
    }

    @Test void aNewCampaignSweepStartsAtTheFirstManagedInstance() {
        Scene scene = new Scene("beta", "alpha");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = TestSessions.activeHostSession();
        CoopEncounterOrbitSweep first = sweep(service, session, new ArrayList<>());
        first.publishNext(scene.api, 1000, Set.of()); first.publishNext(scene.api, 2000, Set.of());
        assertEquals("alpha", sweep(service, session, new ArrayList<>()).publishNext(scene.api, 3000, Set.of()));
    }

    @Test void guestAndUnestablishedHostNeverCaptureOrPublish() {
        Scene scene = new Scene("alpha");
        AtomicInteger captured = new AtomicInteger();
        for (CoopConnectionRole role : List.of(CoopConnectionRole.GUEST, CoopConnectionRole.HOST)) {
            RecordingNetService service = new RecordingNetService(role);
            CoopSessionState session = role == CoopConnectionRole.GUEST
                    ? TestSessions.activeGuestSession() : new CoopSessionState(TestSessions.sequencedIds("lobby", "host"));
            if (role == CoopConnectionRole.HOST) session.startHost("Host");
            CoopEncounterOrbitSweep sweep = new CoopEncounterOrbitSweep(service, session, (sector, system) -> {
                captured.incrementAndGet(); return orbit(system.getOptionalUniqueId());
            });
            assertNull(sweep.publishNext(scene.api, 1000, Set.of()));
            assertTrue(service.sent.isEmpty());
        }
        assertEquals(0, captured.get());
    }

    @Test void moreThanTheManagedInstanceCapFailsBeforeAnyCaptureOrSend() {
        Scene scene = new Scene();
        for (int index = 0; index <= CoopEncounterRecord.MAX_ENCOUNTERS; index++)
            scene.systems.add(system("instance" + index, true));
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        List<String> captured = new ArrayList<>();
        CoopEncounterOrbitSweep sweep = sweep(service, TestSessions.activeHostSession(), captured);
        assertThrows(IllegalStateException.class, () -> sweep.publishNext(scene.api, 1000, Set.of()));
        assertTrue(captured.isEmpty()); assertTrue(service.sent.isEmpty());
    }

    @Test void oversizedOrbitTableFailsLoudlyWithoutQueueingAMessage() {
        Scene scene = new Scene("alpha");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopEncounterOrbitSweep sweep = new CoopEncounterOrbitSweep(service, TestSessions.activeHostSession(),
                (sector, system) -> "x".repeat(CoopEncounterOrbitSetCodec.MAX_ENCODED_BYTES + 1));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> sweep.publishNext(scene.api, 1000, Set.of()));
        assertTrue(error.getMessage().contains("byte cap")); assertTrue(service.sent.isEmpty());
    }

    @Test void escapedEnvelopeCapIsCheckedBeforeSendingEvenWhenRawTableFits() {
        Scene scene = new Scene("alpha");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        // Exercise the publisher's independent final-envelope defense with a faulty capture source.
        String escaped = "\u0000".repeat(160_000);
        assertTrue(escaped.length() < CoopEncounterOrbitSetCodec.MAX_ENCODED_BYTES);
        CoopEncounterOrbitSweep sweep = new CoopEncounterOrbitSweep(service, TestSessions.activeHostSession(),
                (sector, system) -> escaped);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> sweep.publishNext(scene.api, 1000, Set.of()));
        assertTrue(error.getMessage().contains("TCP frame cap")); assertTrue(service.sent.isEmpty());
    }

    @Test void aFailedCaptureDoesNotStarveOtherManagedInstancesOnFollowingTicks() {
        Scene scene = new Scene("alpha", "beta");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopEncounterOrbitSweep sweep = new CoopEncounterOrbitSweep(service, TestSessions.activeHostSession(), (sector, system) -> {
            if (system.getOptionalUniqueId().equals("alpha")) throw new IllegalStateException("invalid saved membership");
            return orbit(system.getOptionalUniqueId());
        });
        assertThrows(IllegalStateException.class, () -> sweep.publishNext(scene.api, 1000, Set.of()));
        assertEquals("beta", sweep.publishNext(scene.api, 2000, Set.of()));
        assertEquals(List.of("beta"), locations(service));
    }

    private static CoopEncounterOrbitSweep sweep(RecordingNetService service, CoopSessionState session, List<String> captured) {
        return new CoopEncounterOrbitSweep(service, session, (sector, system) -> {
            captured.add(system.getOptionalUniqueId()); return orbit(system.getOptionalUniqueId());
        });
    }

    private static String orbit(String instance) {
        return CoopOrbitSync.encode(List.of(new CoopOrbitSync.OrbitEntry("coopenc_" + instance + "_system1",
                "coopenc_" + instance + "_system0", 100, 20, 37)));
    }

    private static List<String> locations(RecordingNetService service) {
        return service.sent.stream().map(message -> CoopMessages.payload(message).requiredString("locationId")).toList();
    }

    private static StarSystemAPI system(String id, boolean managed) {
        Map<String, Object> values = new HashMap<>();
        if (managed) {
            values.put(CoopEncounterWorld.ENCOUNTER_ID_KEY, id);
            values.put(CoopEncounterWorld.RECIPE_KEY, "primitive recipe; capture stub owns its validation");
        }
        MemoryAPI memory = proxy(MemoryAPI.class, (p, method, args) -> switch (method.getName()) {
            case "get" -> values.get(args[0]);
            case "contains" -> values.containsKey(args[0]);
            default -> ProxyDefaults.defaultValue(method.getReturnType());
        });
        return proxy(StarSystemAPI.class, (p, method, args) -> switch (method.getName()) {
            case "getId", "getOptionalUniqueId" -> id;
            case "getMemoryWithoutUpdate" -> memory;
            case "hasTag" -> Tags.TEMPORARY_LOCATION.equals(args[0]) || Tags.SYSTEM_ABYSSAL.equals(args[0]);
            default -> ProxyDefaults.defaultValue(method.getReturnType());
        });
    }

    private static final class Scene {
        final List<StarSystemAPI> systems = new ArrayList<>();
        final SectorAPI api = proxy(SectorAPI.class, (p, method, args) -> method.getName().equals("getStarSystems")
                ? systems : ProxyDefaults.defaultValue(method.getReturnType()));
        Scene(String... ids) { for (String id : ids) systems.add(system(id, true)); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
