package coop.net;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopPortMapperTest {
    @Test
    void disabledMapperFinishesImmediatelyWithoutTouchingTheNetwork() {
        CoopPortMapper mapper = CoopPortMapper.start(27015, false, () -> 0L);

        CoopPortMapper.Result result = mapper.result();

        assertTrue(result.finished());
        assertFalse(result.mapped());
        assertEquals(CoopPortMapper.Tier.NONE, result.tier());
        assertTrue(result.failureText().contains("coop.portMapping=off"), result.failureText());
        assertEquals("", result.externalEndpoint());

        mapper.tick(1L);
        mapper.shutdown();
        assertTrue(mapper.result().finished());
    }

    @Test
    void rejectsAnOutOfRangePort() {
        assertThrows(IllegalArgumentException.class, () -> CoopPortMapper.start(0, true, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> CoopPortMapper.start(70000, true, () -> 0L));
    }

    @Test
    void silentDiscoveryFallsThroughNatPmpAndGivesUpWithAFailureText() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startOffline(27015, clock::get);

        for (int i = 0; i < 500 && !mapper.result().finished(); i++) {
            mapper.tick(clock.addAndGet(100L));
        }

        CoopPortMapper.Result result = mapper.result();
        assertTrue(result.finished(), "state machine must reach a terminal state on its own");
        assertFalse(result.mapped());
        assertEquals(CoopPortMapper.Tier.NONE, result.tier());
        assertTrue(result.failureText().toLowerCase().contains("nat-pmp"), result.failureText());
        // Whole run: 3 s SSDP window plus three NAT-PMP attempts 750 ms apart.
        assertTrue(clock.get() - 1_000_000L < 8_000L, "gave up in " + (clock.get() - 1_000_000L) + " ms");
    }

    @Test
    void tickIsIdempotentAfterAFailureAndShutdownIsSafeToCallTwice() {
        AtomicLong clock = new AtomicLong(0L);
        CoopPortMapper mapper = CoopPortMapper.startOffline(27015, clock::get);
        for (int i = 0; i < 500 && !mapper.result().finished(); i++) {
            mapper.tick(clock.addAndGet(100L));
        }

        String failure = mapper.result().failureText();
        mapper.tick(clock.addAndGet(60_000L));
        assertEquals(failure, mapper.result().failureText());

        mapper.shutdown();
        mapper.shutdown();
        assertTrue(mapper.result().finished());
    }

    @Test
    void classifiesCarrierGradeAndPrivateAddressesAsUnroutable() {
        for (String address : new String[]{
                "10.0.0.1", "10.255.255.255",
                "172.16.0.1", "172.31.255.254",
                "192.168.0.1", "192.168.1.1",
                "100.64.0.1", "100.127.255.254",
                "169.254.1.1",
                "127.0.0.1"}) {
            assertTrue(CoopPortMapper.isUnroutableExternalAddress(address), address + " must be unroutable");
        }
    }

    @Test
    void classifiesPublicAddressesAsRoutableIncludingTheNeighboursOfEveryPrivateRange() {
        for (String address : new String[]{
                "203.0.113.7", "8.8.8.8",
                "9.255.255.255", "11.0.0.0",
                "172.15.255.255", "172.32.0.0",
                "192.167.255.255", "192.169.0.0",
                "100.63.255.255", "100.128.0.0",
                "169.253.255.255", "169.255.0.0",
                "126.255.255.255", "128.0.0.1"}) {
            assertFalse(CoopPortMapper.isUnroutableExternalAddress(address), address + " must be routable");
        }
    }

    @Test
    void unparsableAddressesAreNotClaimedToBeCarrierGradeNat() {
        assertFalse(CoopPortMapper.isUnroutableExternalAddress(null));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress(""));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("2001:db8::1"));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("10.0.0"));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("10.0.0.999"));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("ten.zero.zero.one"));
    }

    @Test
    void guessesTheGatewayAsTheFirstHostOfTheLocalSubnet() {
        assertEquals("192.168.1.1", CoopPortMapper.guessDefaultGateway("192.168.1.5"));
        assertEquals("10.0.7.1", CoopPortMapper.guessDefaultGateway("10.0.7.240"));
        assertEquals("", CoopPortMapper.guessDefaultGateway(""));
        assertEquals("", CoopPortMapper.guessDefaultGateway(null));
        assertEquals("", CoopPortMapper.guessDefaultGateway("2001:db8::1"));
    }

    @Test
    void resultReportsAShareableEndpointOnlyWhenSomethingWasMapped() {
        CoopPortMapper.Result mapped = new CoopPortMapper.Result(CoopPortMapper.Tier.UPNP,
                "192.168.1.1", "Test Router", "203.0.113.7", 27015, false, "", true);
        assertTrue(mapped.mapped());
        assertEquals("203.0.113.7:27015", mapped.externalEndpoint());

        CoopPortMapper.Result failed = new CoopPortMapper.Result(CoopPortMapper.Tier.NONE,
                "", "", "", 0, false, "no gateway", true);
        assertFalse(failed.mapped());
        assertEquals("", failed.externalEndpoint());
    }

    @Test
    void namesEachMappingTierForTheDoctorBlock() {
        assertEquals("UPnP IGD", CoopPortMapper.describeTier(CoopPortMapper.Tier.UPNP));
        assertEquals("NAT-PMP", CoopPortMapper.describeTier(CoopPortMapper.Tier.NAT_PMP));
        assertEquals("PCP", CoopPortMapper.describeTier(CoopPortMapper.Tier.PCP));
        assertEquals("none", CoopPortMapper.describeTier(CoopPortMapper.Tier.NONE));
    }
}
