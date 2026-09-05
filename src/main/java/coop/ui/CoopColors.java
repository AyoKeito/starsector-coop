package coop.ui;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.util.Misc;

/**
 * Vanilla's UI colours, asked for in a way that is safe outside a running game.
 *
 * <p><b>Why this class exists.</b> {@code Misc}'s static initializer reads a dozen values out of
 * {@code Global.getSettings()} ({@code Misc.java}, the {@code getXColor} family). Touching the class
 * with no settings installed throws {@code ExceptionInInitializerError} the first time and
 * {@code NoClassDefFoundError} on every subsequent touch — the class is <em>permanently</em>
 * unusable for the rest of the JVM. Catching the error at the call site is not enough: the damage is
 * done to the whole process, so a single colour lookup on a code path a unit test happens to reach
 * poisons {@code Misc} for every later test in the same JVM, including ones that install settings
 * properly.
 *
 * <p>So the guard is a <em>look before you leap</em> on {@code Global.getSettings()}, not a
 * {@code catch}: with no settings the vanilla class is never mentioned, and a literal that matches
 * what vanilla would have returned is used instead. A colour is cosmetic — a feed line in the wrong
 * shade of red still says what it needs to say — and no coop code path is worth losing {@code Misc}
 * over.
 *
 * <p>Only the colours coop code actually asks for outside the intel-rendering paths live here. The
 * intel pages ({@code CoopSessionIntel}, {@code CoopOptionsPage}, {@code CoopSessionStatsIntel},
 * {@code CoopExpeditionWarningIntel}) call {@code Misc} directly and may keep doing so: they render
 * only from a live {@code TooltipMakerAPI}, which does not exist without a game, so no test reaches
 * them.
 */
public final class CoopColors {

    /** Vanilla's negative-highlight red, spelled out for the no-settings case. */
    public static final Color NEGATIVE_HIGHLIGHT = new Color(255, 110, 110);

    private CoopColors() {
    }

    /** {@code Misc.getNegativeHighlightColor()}, or {@link #NEGATIVE_HIGHLIGHT} with no game. */
    public static Color negativeHighlight() {
        if (!settingsPresent()) {
            return NEGATIVE_HIGHLIGHT;
        }
        try {
            Color color = Misc.getNegativeHighlightColor();
            return color == null ? NEGATIVE_HIGHLIGHT : color;
        } catch (RuntimeException | LinkageError ex) {
            // Settings existed but the lookup failed anyway (a stripped install, a mod that removed
            // the key). Nothing player-facing is worth a crash, and the literal is a fine stand-in.
            return NEGATIVE_HIGHLIGHT;
        }
    }

    /**
     * True when there is a settings object for {@code Misc} to read. Deliberately does not mention
     * {@code Misc}: naming it here would defeat the whole point of the class.
     */
    public static boolean settingsPresent() {
        try {
            return Global.getSettings() != null;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
