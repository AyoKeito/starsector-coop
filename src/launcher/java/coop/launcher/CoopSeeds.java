package coop.launcher;

import java.security.SecureRandom;

import coop.net.CoopNetStartupConfig;

/**
 * Seed strings for the {@code Generate} button.
 *
 * <p>Shape is fixed by the game, not by us: vanilla's own new-game code rebuilds a {@code long} out
 * of the digits after {@code MN-} and throws a bare {@link NumberFormatException} deep inside
 * {@code CampaignState.createUI} when they overflow. {@link CoopNetStartupConfig#validateNewGameSeed}
 * is the rule; this class only produces values that satisfy it.
 */
public final class CoopSeeds {

    /** Prefix the game's own seed strings use. */
    public static final String PREFIX = "MN-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private CoopSeeds() {
    }

    /** A fresh {@code MN-<non-negative long>}. Never returns a value the validator rejects. */
    public static String generate() {
        // nextLong() >>> 1 keeps the sign bit clear, so the digits always fit a signed long and
        // vanilla's parse of them cannot overflow.
        long value = RANDOM.nextLong() >>> 1;
        return PREFIX + value;
    }

    /**
     * The reason {@code seed} is not usable, or {@code null} when it is (including blank, which
     * simply means "let the game pick"). Delegates to the mod so the launcher and the game can never
     * disagree about what a legal seed is.
     */
    public static String validate(String seed) {
        String trimmed = seed == null ? "" : seed.trim();
        return CoopNetStartupConfig.validateNewGameSeed(trimmed);
    }
}
