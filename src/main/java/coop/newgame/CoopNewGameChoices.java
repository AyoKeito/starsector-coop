package coop.newgame;

import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import coop.config.CoopOptionsRegistry;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetStartupConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure decision logic behind {@link CoopNewGameDialogPlugin}: launch-property text in, world-setting
 * values and banner strings out. Deliberately free of engine calls so it is unit-testable without a
 * running game -- {@link StarAge} is the one engine type referenced, and only because "property text
 * to enum value" is the decision being made here. It is a plain enum with no static initialiser that
 * touches {@code Global}, so loading it in a test costs nothing.
 *
 * <p>Value vocabulary is taken from the 0.98a new-game options panel itself (verified in the
 * bytecode, not assumed): the two sector-size radio buttons write the literals {@code "small"} and
 * {@code "normal"}, and the star-age buttons write {@link StarAge} constants with the {@code ANY}
 * button labelled "Mixed" -- hence {@code mixed} is accepted as an alias for {@code ANY}.
 */
final class CoopNewGameChoices {

    /**
     * Read through {@code CoopOptionsStore.rawOneShot} rather than {@code System.getProperty} since
     * Phase 31: {@code -D} still wins, but the launcher can only reach these through
     * {@code saves/common/coop_options.json.data}, so the warnings below name the key without a
     * {@code -D} prefix.
     */
    static final String SECTOR_SIZE_PROPERTY = CoopOptionsRegistry.SECTOR_SIZE;
    static final String SECTOR_AGE_PROPERTY = CoopOptionsRegistry.SECTOR_AGE;

    static final String SECTOR_SIZE_SMALL = "small";
    static final String SECTOR_SIZE_NORMAL = "normal";

    /** Engine fallback when the panel has not supplied one: {@code CharacterCreationData} inits to "normal". */
    static final String FALLBACK_SECTOR_SIZE = SECTOR_SIZE_NORMAL;
    /** Engine fallback when the panel has not supplied one: the field inits to null, which SectorProcGen reads as ANY. */
    static final StarAge FALLBACK_SECTOR_AGE = StarAge.ANY;

    private CoopNewGameChoices() {
    }

    /** Resolved world settings plus any warnings the caller should log once. */
    record Choices(String sectorSize, StarAge sectorAge, List<String> warnings) {
        Choices {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * True only for a real coop launch (host or guest). A bare {@code -Dcoop.newGameSeed} with no
     * host/guest property is not a coop launch, and neither is a plain solo start -- both must leave
     * the vanilla dialog untouched.
     */
    static boolean isCoopLaunch(CoopNetStartupConfig config) {
        return config != null
                && config.isPresent()
                && (config.role() == CoopConnectionRole.HOST || config.role() == CoopConnectionRole.GUEST);
    }

    static Choices resolve(String sizeProperty, String ageProperty, String panelSectorSize, StarAge panelSectorAge) {
        List<String> warnings = new ArrayList<>();
        String size = resolveSectorSize(sizeProperty, panelSectorSize, warnings);
        StarAge age = resolveSectorAge(ageProperty, panelSectorAge, warnings);
        return new Choices(size, age, warnings);
    }

    private static String resolveSectorSize(String property, String panelDefault, List<String> warnings) {
        String fallback = normalizeSize(panelDefault);
        String requested = trimToEmpty(property);
        if (requested.isEmpty()) {
            return fallback;
        }
        String lower = requested.toLowerCase(Locale.ROOT);
        if (SECTOR_SIZE_SMALL.equals(lower) || SECTOR_SIZE_NORMAL.equals(lower)) {
            return lower;
        }
        warnings.add("Ignoring " + SECTOR_SIZE_PROPERTY + "=" + requested
                + ": expected one of [" + SECTOR_SIZE_SMALL + ", " + SECTOR_SIZE_NORMAL
                + "]; using the new-game panel default " + fallback);
        return fallback;
    }

    private static StarAge resolveSectorAge(String property, StarAge panelDefault, List<String> warnings) {
        StarAge fallback = panelDefault == null ? FALLBACK_SECTOR_AGE : panelDefault;
        String requested = trimToEmpty(property);
        if (requested.isEmpty()) {
            return fallback;
        }
        StarAge parsed = parseStarAge(requested);
        if (parsed != null) {
            return parsed;
        }
        warnings.add("Ignoring " + SECTOR_AGE_PROPERTY + "=" + requested
                + ": expected one of " + starAgeVocabulary()
                + "; using the new-game panel default " + fallback);
        return fallback;
    }

    static StarAge parseStarAge(String text) {
        String upper = trimToEmpty(text).toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) {
            return null;
        }
        // The ANY radio button is labelled "Mixed" in the panel, so accept what the player sees.
        if ("MIXED".equals(upper)) {
            return StarAge.ANY;
        }
        for (StarAge age : StarAge.values()) {
            if (age.name().equals(upper)) {
                return age;
            }
        }
        return null;
    }

    private static String starAgeVocabulary() {
        StringBuilder sb = new StringBuilder("[");
        for (StarAge age : StarAge.values()) {
            sb.append(age.name().toLowerCase(Locale.ROOT)).append(", ");
        }
        return sb.append("mixed]").toString();
    }

    private static String normalizeSize(String panelDefault) {
        String trimmed = trimToEmpty(panelDefault).toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? FALLBACK_SECTOR_SIZE : trimmed;
    }

    /** Banner shown above the new-game options panel. Empty when this is not a coop launch. */
    static String bannerText(CoopConnectionRole role, String host, int port, String seedString) {
        if (role == CoopConnectionRole.GUEST) {
            // Rewritten in Phase 31. The old text said the settings "come from the host", which is
            // not what happens: this client generates its own sector from the seed it was given -
            // by the host's invite, through coop.newGameSeed - and the seed lock verifies the two
            // sectors match afterwards. Nothing is fetched from the host at this point.
            return "Joining coop host " + trimToEmpty(host) + ":" + port
                    + ". The seed " + trimToEmpty(seedString) + " came from the host's invite, and"
                    + " sector size and star age are pinned to match the host, so all three fields"
                    + " below are locked.";
        }
        if (role == CoopConnectionRole.HOST) {
            return "Hosting a coop game on port " + port + ". Seed " + trimToEmpty(seedString)
                    + "; sector size and star age are pinned so the guest can generate the same sector.";
        }
        return "";
    }

    static String pinnedLogLine(String seedString, String sectorSize, StarAge sectorAge, CoopConnectionRole role) {
        return "Coop new game pinned seed=" + trimToEmpty(seedString)
                + " sectorSize=" + sectorSize
                + " sectorAge=" + sectorAge
                + " (role " + (role == null ? CoopConnectionRole.NONE : role) + ")";
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
