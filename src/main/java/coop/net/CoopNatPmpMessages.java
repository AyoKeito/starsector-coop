package coop.net;

import java.util.Objects;

/**
 * NAT-PMP (RFC 6886) request/response encoding — the secondary port-mapping protocol
 * {@link CoopPortMapper} falls back to when SSDP finds no UPnP IGD.
 *
 * <p>Why it is worth the ~100 lines: NAT-PMP is a fixed-layout binary UDP exchange with no
 * discovery step, no XML and no HTTP. Where UPnP needs four round trips through two protocols,
 * this needs one datagram per mapping, so it is the cheapest possible second chance — and Apple
 * gateways plus a slice of consumer firmware speak it while shipping UPnP disabled.
 *
 * <p>PCP (RFC 6887) is the successor and shares port 5351; it is <em>not</em> implemented here.
 * A PCP MAP request needs a 24-byte nonce, the client's own IP in the header, and a protocol-number
 * field, and every PCP-capable gateway is required to answer NAT-PMP as well (RFC 6887 §9), so the
 * extra code buys nothing this phase. Recorded rather than done.
 */
public final class CoopNatPmpMessages {
    public static final int PORT = 5351;

    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_UNSUPPORTED_VERSION = 1;
    public static final int RESULT_NOT_AUTHORIZED = 2;
    public static final int RESULT_NETWORK_FAILURE = 3;
    public static final int RESULT_OUT_OF_RESOURCES = 4;
    public static final int RESULT_UNSUPPORTED_OPCODE = 5;

    private static final int OPCODE_EXTERNAL_ADDRESS = 0;
    private static final int OPCODE_MAP_UDP = 1;
    private static final int OPCODE_MAP_TCP = 2;
    private static final int RESPONSE_FLAG = 128;

    private CoopNatPmpMessages() {
    }

    /** Two bytes: version 0, opcode 0. */
    public static byte[] externalAddressRequest() {
        return new byte[]{0, (byte) OPCODE_EXTERNAL_ADDRESS};
    }

    /**
     * Twelve bytes: version, opcode, two reserved zero bytes, internal port, suggested external
     * port, lifetime seconds. A lifetime of 0 with external port 0 is the RFC's mapping delete.
     */
    public static byte[] mapRequest(boolean udp, int internalPort, int externalPort, int lifetimeSeconds) {
        requirePort(internalPort, "internalPort");
        if (externalPort < 0 || externalPort > 65535) {
            throw new IllegalArgumentException("externalPort must be in range 0..65535");
        }
        if (lifetimeSeconds < 0) {
            throw new IllegalArgumentException("lifetimeSeconds must not be negative");
        }
        byte[] request = new byte[12];
        request[0] = 0;
        request[1] = (byte) (udp ? OPCODE_MAP_UDP : OPCODE_MAP_TCP);
        request[2] = 0;
        request[3] = 0;
        putShort(request, 4, internalPort);
        putShort(request, 6, externalPort);
        putInt(request, 8, lifetimeSeconds);
        return request;
    }

    /** The mapping-release request: same external port, lifetime 0 (RFC 6886 §3.4). */
    public static byte[] releaseRequest(boolean udp, int internalPort) {
        return mapRequest(udp, internalPort, 0, 0);
    }

    public record ExternalAddressResponse(int resultCode, String address) {
        public ExternalAddressResponse {
            Objects.requireNonNull(address, "address");
        }

        public boolean success() {
            return resultCode == RESULT_SUCCESS;
        }
    }

    public record MapResponse(int resultCode, boolean udp, int internalPort, int externalPort, int lifetimeSeconds) {
        public boolean success() {
            return resultCode == RESULT_SUCCESS;
        }
    }

    /**
     * @throws IllegalArgumentException when the datagram is too short or is not a NAT-PMP
     *                                  external-address response
     */
    public static ExternalAddressResponse parseExternalAddress(byte[] data, int length) {
        Objects.requireNonNull(data, "data");
        if (length < 12) {
            throw new IllegalArgumentException("NAT-PMP external address response must be 12 bytes, got " + length);
        }
        requireResponse(data, RESPONSE_FLAG + OPCODE_EXTERNAL_ADDRESS);
        int result = readShort(data, 2);
        String address = (data[8] & 0xFF) + "." + (data[9] & 0xFF) + "." + (data[10] & 0xFF) + "." + (data[11] & 0xFF);
        return new ExternalAddressResponse(result, result == RESULT_SUCCESS ? address : "");
    }

    /**
     * @throws IllegalArgumentException when the datagram is too short or is not a NAT-PMP map response
     */
    public static MapResponse parseMap(byte[] data, int length) {
        Objects.requireNonNull(data, "data");
        if (length < 16) {
            throw new IllegalArgumentException("NAT-PMP map response must be 16 bytes, got " + length);
        }
        int opcode = data[1] & 0xFF;
        if (opcode != RESPONSE_FLAG + OPCODE_MAP_UDP && opcode != RESPONSE_FLAG + OPCODE_MAP_TCP) {
            throw new IllegalArgumentException("Not a NAT-PMP map response, opcode " + opcode);
        }
        if ((data[0] & 0xFF) != 0) {
            throw new IllegalArgumentException("Unsupported NAT-PMP version " + (data[0] & 0xFF));
        }
        return new MapResponse(
                readShort(data, 2),
                opcode == RESPONSE_FLAG + OPCODE_MAP_UDP,
                readShort(data, 8),
                readShort(data, 10),
                (int) readUnsignedInt(data, 12));
    }

    /** Human text for a result code, for the failure line the connection doctor prints. */
    public static String describeResult(int resultCode) {
        return switch (resultCode) {
            case RESULT_SUCCESS -> "success";
            case RESULT_UNSUPPORTED_VERSION -> "unsupported version";
            case RESULT_NOT_AUTHORIZED -> "not authorized (port mapping disabled on the router)";
            case RESULT_NETWORK_FAILURE -> "network failure (router has no upstream lease)";
            case RESULT_OUT_OF_RESOURCES -> "out of resources";
            case RESULT_UNSUPPORTED_OPCODE -> "unsupported opcode";
            default -> "unknown result code " + resultCode;
        };
    }

    private static void requireResponse(byte[] data, int expectedOpcode) {
        if ((data[0] & 0xFF) != 0) {
            throw new IllegalArgumentException("Unsupported NAT-PMP version " + (data[0] & 0xFF));
        }
        if ((data[1] & 0xFF) != expectedOpcode) {
            throw new IllegalArgumentException("Unexpected NAT-PMP opcode " + (data[1] & 0xFF));
        }
    }

    private static void requirePort(int port, String name) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " must be in range 1..65535");
        }
    }

    private static void putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 1] = (byte) (value & 0xFF);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
