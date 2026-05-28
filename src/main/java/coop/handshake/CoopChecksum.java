package coop.handshake;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class CoopChecksum {
    private static final String UNAVAILABLE_PREFIX = "UNAVAILABLE:";

    private CoopChecksum() {
    }

    public static String sha256Text(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to checksum text", ex);
        }
    }

    public static String unavailable(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        return UNAVAILABLE_PREFIX + normalized;
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            value.append(Character.forDigit((b >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(b & 0x0f, 16));
        }
        return value.toString();
    }
}
