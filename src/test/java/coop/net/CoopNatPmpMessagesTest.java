package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNatPmpMessagesTest {
    @Test
    void externalAddressRequestIsTwoZeroBytes() {
        assertArrayEquals(new byte[]{0, 0}, CoopNatPmpMessages.externalAddressRequest());
    }

    @Test
    void udpMapRequestUsesOpcodeOneAndBigEndianFields() {
        byte[] request = CoopNatPmpMessages.mapRequest(true, 27015, 27015, 3600);

        assertEquals(12, request.length);
        assertArrayEquals(new byte[]{
                0, 1, 0, 0,
                (byte) 0x69, (byte) 0x87,
                (byte) 0x69, (byte) 0x87,
                0, 0, (byte) 0x0E, (byte) 0x10
        }, request);
    }

    @Test
    void tcpMapRequestUsesOpcodeTwo() {
        assertEquals(2, CoopNatPmpMessages.mapRequest(false, 1, 1, 60)[1]);
    }

    @Test
    void releaseRequestZeroesTheExternalPortAndLifetime() {
        byte[] request = CoopNatPmpMessages.releaseRequest(false, 27015);

        assertArrayEquals(new byte[]{
                0, 2, 0, 0,
                (byte) 0x69, (byte) 0x87,
                0, 0,
                0, 0, 0, 0
        }, request);
    }

    @Test
    void rejectsOutOfRangePorts() {
        assertThrows(IllegalArgumentException.class, () -> CoopNatPmpMessages.mapRequest(true, 0, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> CoopNatPmpMessages.mapRequest(true, 1, 70000, 60));
        assertThrows(IllegalArgumentException.class, () -> CoopNatPmpMessages.mapRequest(true, 1, 1, -1));
    }

    @Test
    void parsesASuccessfulExternalAddressResponse() {
        byte[] response = new byte[]{
                0, (byte) 128, 0, 0,
                0, 0, 0, 10,
                (byte) 203, 0, (byte) 113, 7
        };

        CoopNatPmpMessages.ExternalAddressResponse parsed =
                CoopNatPmpMessages.parseExternalAddress(response, response.length);

        assertTrue(parsed.success());
        assertEquals("203.0.113.7", parsed.address());
    }

    @Test
    void anUnsuccessfulExternalAddressResponseCarriesNoAddress() {
        byte[] response = new byte[]{0, (byte) 128, 0, 3, 0, 0, 0, 10, 0, 0, 0, 0};

        CoopNatPmpMessages.ExternalAddressResponse parsed =
                CoopNatPmpMessages.parseExternalAddress(response, response.length);

        assertFalse(parsed.success());
        assertEquals(CoopNatPmpMessages.RESULT_NETWORK_FAILURE, parsed.resultCode());
        assertEquals("", parsed.address());
    }

    @Test
    void rejectsATruncatedOrForeignExternalAddressResponse() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopNatPmpMessages.parseExternalAddress(new byte[]{0, (byte) 128}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CoopNatPmpMessages.parseExternalAddress(new byte[12], 12));
    }

    @Test
    void parsesAUdpMapResponse() {
        byte[] response = new byte[]{
                0, (byte) 129, 0, 0,
                0, 0, 0, 42,
                (byte) 0x69, (byte) 0x87,
                (byte) 0x69, (byte) 0x87,
                0, 0, (byte) 0x0E, (byte) 0x10
        };

        CoopNatPmpMessages.MapResponse parsed = CoopNatPmpMessages.parseMap(response, response.length);

        assertTrue(parsed.success());
        assertTrue(parsed.udp());
        assertEquals(27015, parsed.internalPort());
        assertEquals(27015, parsed.externalPort());
        assertEquals(3600, parsed.lifetimeSeconds());
    }

    @Test
    void parsesATcpMapResponseWithARouterChosenExternalPort() {
        byte[] response = new byte[]{
                0, (byte) 130, 0, 0,
                0, 0, 0, 42,
                (byte) 0x69, (byte) 0x87,
                (byte) 0x1F, (byte) 0x90,
                0, 0, (byte) 0x0E, (byte) 0x10
        };

        CoopNatPmpMessages.MapResponse parsed = CoopNatPmpMessages.parseMap(response, response.length);

        assertFalse(parsed.udp());
        assertEquals(8080, parsed.externalPort());
    }

    @Test
    void rejectsANonMapResponseAndAnUnsupportedVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopNatPmpMessages.parseMap(new byte[]{0, (byte) 128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 16));
        assertThrows(IllegalArgumentException.class,
                () -> CoopNatPmpMessages.parseMap(new byte[]{2, (byte) 129, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 16));
        assertThrows(IllegalArgumentException.class, () -> CoopNatPmpMessages.parseMap(new byte[8], 8));
    }

    @Test
    void namesEveryResultCode() {
        assertEquals("success", CoopNatPmpMessages.describeResult(0));
        assertEquals("unsupported version", CoopNatPmpMessages.describeResult(1));
        assertTrue(CoopNatPmpMessages.describeResult(2).contains("not authorized"));
        assertTrue(CoopNatPmpMessages.describeResult(3).contains("network failure"));
        assertEquals("out of resources", CoopNatPmpMessages.describeResult(4));
        assertEquals("unsupported opcode", CoopNatPmpMessages.describeResult(5));
        assertTrue(CoopNatPmpMessages.describeResult(99).contains("99"));
    }
}
