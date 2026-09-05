package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-settings guard. This is the whole reason {@link CoopColors} exists, so it is the whole
 * test: {@code Misc}'s static field initializers call {@code Global.getSettings().getColor(...)}
 * ({@code Misc.java:201-207}), so mentioning that class with no settings installed throws
 * {@code ExceptionInInitializerError} once and {@code NoClassDefFoundError} for the rest of the JVM.
 *
 * <p>The settings are explicitly nulled rather than assumed absent: whether some earlier test class
 * left a fake installed is not this test's business, and depending on it would make the assertion an
 * accident of test ordering.
 *
 * <p><b>Deliberately not tested: that the with-settings branch returns vanilla's own colour.</b>
 * Asserting it means initializing {@code Misc} against a proxy, and if that proxy did not satisfy
 * every key the class reads in its initializer, this test would be the thing that poisons
 * {@code Misc} for every test after it — the exact failure the class was written to prevent. What is
 * pinned instead is the branch selector, {@link CoopColors#settingsPresent()}, which is the only
 * decision this class makes.
 */
class CoopColorsTest {

    private SettingsAPI previous;

    @BeforeEach
    void rememberSettings() {
        previous = settingsOrNull();
    }

    @AfterEach
    void restoreSettings() {
        Global.setSettings(previous);
    }

    @Test
    void withNoSettingsTheLiteralIsReturnedAndNothingIsThrown() {
        Global.setSettings(null);

        assertFalse(CoopColors.settingsPresent());
        assertSame(CoopColors.NEGATIVE_HIGHLIGHT, CoopColors.negativeHighlight());
    }

    @Test
    void theLiteralIsVanillasNegativeHighlightRed() {
        assertEquals(new Color(255, 110, 110), CoopColors.NEGATIVE_HIGHLIGHT);
    }

    @Test
    void settingsBeingPresentIsWhatSendsTheLookupToVanilla() {
        Global.setSettings(emptySettings());

        assertTrue(CoopColors.settingsPresent());
    }

    private static SettingsAPI settingsOrNull() {
        try {
            return Global.getSettings();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static SettingsAPI emptySettings() {
        return (SettingsAPI) Proxy.newProxyInstance(
                SettingsAPI.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "Settings";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
