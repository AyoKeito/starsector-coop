package coop.ui;

import coop.util.CoopLog;

import java.util.Locale;

/**
 * Which screen corner {@link CoopLinkHud} anchors its one-line link status to. Configured via
 * {@code coop.hudCorner} (see {@code coop.net.CoopNetStartupConfig#HUD_CORNER_PROPERTY}); kept as a
 * plain string property rather than one enum constant each because the property is meant to be typed
 * by hand on a launch command line, and {@code TR}/{@code TL}/{@code BR}/{@code BL} is what fits
 * there.
 */
public enum CoopHudCorner {
    TOP_RIGHT,
    TOP_LEFT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT;

    /** What an absent or unrecognised property value falls back to. */
    public static final CoopHudCorner DEFAULT = TOP_RIGHT;

    /**
     * Parses the {@code coop.hudCorner} value: {@code TR}, {@code TL}, {@code BR} or {@code BL},
     * case-insensitively, with surrounding whitespace ignored. A null, blank, or unrecognised value
     * logs one WARN and returns {@link #DEFAULT} — this is a cosmetic placement setting, and a typo in
     * it must not be able to stop the HUD (or, worse, the game) from starting.
     */
    public static CoopHudCorner parse(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT;
        }
        switch (trimmed.toUpperCase(Locale.ROOT)) {
            case "TR":
                return TOP_RIGHT;
            case "TL":
                return TOP_LEFT;
            case "BR":
                return BOTTOM_RIGHT;
            case "BL":
                return BOTTOM_LEFT;
            default:
                CoopLog.warn(CoopHudCorner.class, "coop.hudCorner=" + trimmed
                        + " is not one of TR/TL/BR/BL; using TR");
                return DEFAULT;
        }
    }
}
