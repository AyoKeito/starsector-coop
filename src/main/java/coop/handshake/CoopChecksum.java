package coop.handshake;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class CoopChecksum {
    public static final String MISSING = "MISSING";

    private CoopChecksum() {
    }

    public static String sha256IfExists(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return MISSING;
        }
        try {
            return sha256(path);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to checksum " + path, ex);
        }
    }

    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read = input.read(buffer);
            while (read >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
                read = input.read(buffer);
            }
        }
        return hex(digest.digest());
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
