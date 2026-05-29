package coop.rng;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;

/**
 * Deterministic, session-seeded RNG source for coop procgen forks.
 *
 * <p><b>Classloader contract:</b> this class is bundled into {@code jars/coop-forks.jar} and loaded by
 * the JVM system/application classloader (the same one that loads {@code starfarer.api.jar}), because the
 * forked engine classes that reference it are also on the prepended {@code -classpath}. It therefore must
 * NOT depend on any class from {@code coop.jar} (which loads in Starsector's child mod classloader and is
 * invisible to the parent system loader). It must also derive the coop session seed from a source visible
 * to the system loader: the {@code -Dcoop.newGameSeed} JVM system property set by the launch scripts.
 *
 * <p>Seed derivation mirrors {@code coop.seed.CoopSeedSync#stableSeedLong}: SHA-256 over UTF-8 text, first
 * 8 bytes big-endian, sign bit masked (vanilla {@code SectorProcGen.prepare} skips {@code setSeed} when the
 * seed is {@code <= 0}). Per-topic streams hash {@code seedString \0 topic \0 key0 \0 key1 ...}.
 */
public final class CoopRandom {

    /** JVM system property carrying the shared coop new-game seed string, e.g. {@code MN-1234567890123456789}. */
    public static final String NEW_GAME_SEED_PROPERTY = "coop.newGameSeed";

    private CoopRandom() {
    }

    /** True when a coop session seed is present and forks should produce deterministic output. */
    public static boolean isCoopSession() {
        return !sessionSeedString().isEmpty();
    }

    /** The raw coop session seed string, or empty when not in a coop session. */
    public static String sessionSeedString() {
        String value = System.getProperty(NEW_GAME_SEED_PROPERTY);
        return value == null ? "" : value.trim();
    }

    /** Logging helper: the session seed string, or {@code <none>} when not in a coop session. */
    public static String sessionSeedStringOrNone() {
        String value = sessionSeedString();
        return value.isEmpty() ? "<none>" : value;
    }

    /** Stable positive long derived from the session seed string. Throws when not in a coop session. */
    public static long sessionSeedLong() {
        String seed = sessionSeedString();
        if (seed.isEmpty()) {
            throw new IllegalStateException("No coop session seed (-D" + NEW_GAME_SEED_PROPERTY + ")");
        }
        return maskSign(hashToLong(seed));
    }

    /**
     * A deterministic {@link Random} for the given topic/keys, derived from the coop session seed.
     * Throws when not in a coop session; callers in dual coop/vanilla code paths should use
     * {@link #ofOrDefault(String, Object...)} instead.
     */
    public static Random of(String topic, Object... keys) {
        if (!isCoopSession()) {
            throw new IllegalStateException("CoopRandom.of called outside a coop session for topic=" + topic);
        }
        return new Random(maskSign(derive(sessionSeedString(), topic, keys)));
    }

    /**
     * Coop-seeded {@link Random} when in a coop session, otherwise a plain unseeded {@code new Random()} so
     * non-coop launches keep exact vanilla behaviour. Safe to call from forked engine static initializers.
     */
    public static Random ofOrDefault(String topic, Object... keys) {
        String seed = sessionSeedString();
        if (seed.isEmpty()) {
            return new Random();
        }
        return new Random(maskSign(derive(seed, topic, keys)));
    }

    private static long derive(String seedString, String topic, Object... keys) {
        StringBuilder sb = new StringBuilder(seedString.length() + 32);
        sb.append(seedString).append('\0').append(topic == null ? "" : topic);
        if (keys != null) {
            for (Object key : keys) {
                sb.append('\0').append(key == null ? "" : key.toString());
            }
        }
        return hashToLong(sb.toString());
    }

    private static long hashToLong(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (hash[i] & 0xffL);
            }
            return value;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive coop RNG seed", ex);
        }
    }

    private static long maskSign(long value) {
        return value & Long.MAX_VALUE;
    }
}
