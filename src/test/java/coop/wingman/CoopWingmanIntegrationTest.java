package coop.wingman;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopWingmanIntegrationTest {

    private static final class FakeApi implements CoopWingmanApi {
        boolean enabled = true;
        boolean joined;
        String localName = "Ann";
        int openHostCalls;
        boolean openHostOk = true;
        List<String> joiners = new ArrayList<>();
        final List<String[]> reservations = new ArrayList<>();

        @Override public boolean wingmanEnabled() { return enabled; }
        @Override public boolean isJoined() { return joined; }
        @Override public String localName() { return localName; }
        @Override public boolean openHost() { openHostCalls++; return openHostOk; }
        @Override public String pumpHost() { return ""; }
        @Override public List<String> playerNames() { return joiners; }
        @Override public void reserveShip(String name, String fleetMemberId) {
            reservations.add(new String[]{name, fleetMemberId});
        }
    }

    private static final class Harness {
        final AtomicReference<String> sessionId = new AtomicReference<>("");
        final AtomicReference<CoopConnectionRole> role = new AtomicReference<>(CoopConnectionRole.HOST);
        final FakeApi api = new FakeApi();
        final AtomicReference<Boolean> option = new AtomicReference<>(true);
        final AtomicReference<String> flagship = new AtomicReference<>("");
        final List<CoopMessages.Message> sent = new ArrayList<>();
        final List<String> feed = new ArrayList<>();
        final AtomicInteger seq = new AtomicInteger(1);
        final CoopWingmanIntegration integration = new CoopWingmanIntegration(
                role::get, seq::getAndIncrement, sessionId::get, () -> 0L, api,
                option::get, flagship::get, sent::add, feed::add);
    }

    @Test
    void hostOpensAndPumpsOncePerSession() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.tick();
        assertEquals(1, h.api.openHostCalls);
        // a new session re-opens
        h.sessionId.set("s2");
        h.integration.tick();
        assertEquals(2, h.api.openHostCalls);
    }

    @Test
    void hostReservesGuestFlagshipOnceJoinerWithKnownNameIsConnected() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.onGuestWingmanName("Ann");
        h.flagship.set("flagship-7");
        h.api.joiners.add("ann"); // Wingman matches ignoring case
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
        assertEquals("Ann", h.api.reservations.get(0)[0]);
        assertEquals("flagship-7", h.api.reservations.get(0)[1]);
        // no double reservation in the same session
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
        // next session reserves again
        h.sessionId.set("s2");
        h.integration.tick();
        h.integration.onGuestWingmanName("Ann");
        h.integration.tick();
        assertEquals(2, h.api.reservations.size());
    }

    @Test
    void hostDoesNotReserveBeforeFlagshipOrNameKnown() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.api.joiners.add("Ann");
        h.integration.tick(); // no name yet
        assertEquals(0, h.api.reservations.size());
        h.integration.onGuestWingmanName("Ann");
        h.integration.tick(); // no flagship yet
        assertEquals(0, h.api.reservations.size());
        h.flagship.set("f1");
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
    }

    @Test
    void guestSendsOneHelloAndOneHintPerSession() {
        Harness h = new Harness();
        h.role.set(CoopConnectionRole.GUEST);
        h.api.localName = "Guesty";
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.tick();
        assertEquals(1, h.sent.size());
        CoopMessages.Message hello = h.sent.get(0);
        assertEquals(CoopMessages.Type.WINGMAN_HELLO, hello.type());
        assertEquals("Guesty", CoopMessages.parseWingmanHelloName(hello));
        assertEquals(1, h.feed.size());
        assertTrue(h.feed.get(0).contains("Wingman"));
        // next session re-sends
        h.sessionId.set("s2");
        h.integration.tick();
        assertEquals(2, h.sent.size());
        assertEquals(2, h.feed.size());
    }

    @Test
    void nothingHappensWhenOptionOffOrWingmanMissing() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.option.set(false);
        h.integration.tick();
        assertEquals(0, h.api.openHostCalls);

        h.option.set(true);
        h.api.enabled = false;
        h.integration.tick();
        assertEquals(0, h.api.openHostCalls);
        assertFalse(h.integration.guestCombatIsWingmanJoin());
    }

    @Test
    void guestCombatIsWingmanJoinGatesOnRoleSessionAndJoin() {
        Harness h = new Harness();
        assertFalse(h.integration.guestCombatIsWingmanJoin()); // host + no session
        h.sessionId.set("s1");
        h.role.set(CoopConnectionRole.GUEST);
        assertFalse(h.integration.guestCombatIsWingmanJoin()); // not joined
        h.api.joined = true;
        assertTrue(h.integration.guestCombatIsWingmanJoin());
    }

    @Test
    void classifyDecisionCoversAllQuadrants() {
        assertTrue(CoopWingmanIntegration.classifyGuestWingmanJoin(false, true, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(true, true, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(false, false, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(false, true, false, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(false, true, true, false));
    }

    @Test
    void wingmanHelloPayloadRoundTripsAndTrimsOnTheHostSide() {
        CoopMessages.Message hello = CoopMessages.wingmanHello("s1", 7L, 100L, "Ann");
        assertEquals("Ann", CoopMessages.parseWingmanHelloName(hello));
        assertEquals(CoopMessages.Type.WINGMAN_HELLO, hello.type());

        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick(); // session boundary first; the name arrives on a later frame
        h.integration.onGuestWingmanName("  Someone  ");
        h.flagship.set("f9");
        h.api.joiners.add("SOMEONE");
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
        assertEquals("Someone", h.api.reservations.get(0)[0]);
    }

    @Test
    void ignoredOnTheWrongRole() {
        Harness h = new Harness();
        h.role.set(CoopConnectionRole.GUEST);
        h.sessionId.set("s1");
        h.integration.onGuestWingmanName("Ann");
        h.flagship.set("f9");
        h.api.joiners.add("Ann");
        h.integration.tick();
        assertEquals(0, h.api.reservations.size());
        assertEquals(0, h.api.openHostCalls);
    }
}
