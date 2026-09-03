package coop.ui;

import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anchor math for {@link CoopLinkHud}'s four HUD corners. Pure and GL-free by design — see
 * {@link CoopLinkHud#anchor}.
 */
class CoopLinkHudTest {

    private static final float SCREEN_WIDTH = 1920f;
    private static final float SCREEN_HEIGHT = 1080f;
    private static final float TEXT_WIDTH = 240f;
    private static final float TEXT_HEIGHT = 18f;

    @Test
    void topRightAnchorsToTheRightEdgeBelowTheTopOffset() {
        CoopLinkHud.HudAnchor anchor = CoopLinkHud.anchor(
                CoopHudCorner.TOP_RIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, TEXT_WIDTH, TEXT_HEIGHT);

        assertEquals(SCREEN_WIDTH - CoopLinkHud.SIDE_MARGIN - TEXT_WIDTH, anchor.x());
        assertEquals(SCREEN_HEIGHT - CoopLinkHud.TOP_OFFSET, anchor.y());
    }

    @Test
    void topLeftAnchorsToTheLeftEdgeBelowTheTopOffset() {
        CoopLinkHud.HudAnchor anchor = CoopLinkHud.anchor(
                CoopHudCorner.TOP_LEFT, SCREEN_WIDTH, SCREEN_HEIGHT, TEXT_WIDTH, TEXT_HEIGHT);

        assertEquals(CoopLinkHud.SIDE_MARGIN, anchor.x());
        assertEquals(SCREEN_HEIGHT - CoopLinkHud.TOP_OFFSET, anchor.y());
    }

    @Test
    void bottomRightAnchorsToTheRightEdgeAboveTheBottomOffset() {
        CoopLinkHud.HudAnchor anchor = CoopLinkHud.anchor(
                CoopHudCorner.BOTTOM_RIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, TEXT_WIDTH, TEXT_HEIGHT);

        assertEquals(SCREEN_WIDTH - CoopLinkHud.SIDE_MARGIN - TEXT_WIDTH, anchor.x());
        assertEquals(CoopLinkHud.BOTTOM_OFFSET + TEXT_HEIGHT, anchor.y());
    }

    @Test
    void bottomLeftAnchorsToTheLeftEdgeAboveTheBottomOffset() {
        CoopLinkHud.HudAnchor anchor = CoopLinkHud.anchor(
                CoopHudCorner.BOTTOM_LEFT, SCREEN_WIDTH, SCREEN_HEIGHT, TEXT_WIDTH, TEXT_HEIGHT);

        assertEquals(CoopLinkHud.SIDE_MARGIN, anchor.x());
        assertEquals(CoopLinkHud.BOTTOM_OFFSET + TEXT_HEIGHT, anchor.y());
    }

    @Test
    void aNullCornerFallsBackToTopRightRatherThanThrowing() {
        CoopLinkHud.HudAnchor anchor = CoopLinkHud.anchor(
                null, SCREEN_WIDTH, SCREEN_HEIGHT, TEXT_WIDTH, TEXT_HEIGHT);

        assertEquals(SCREEN_WIDTH - CoopLinkHud.SIDE_MARGIN - TEXT_WIDTH, anchor.x());
        assertEquals(SCREEN_HEIGHT - CoopLinkHud.TOP_OFFSET, anchor.y());
    }

    // ---- the disable flag (red-team item 2) -----------------------------------------------------

    /** A store whose user-file layer holds {@code values} and whose command line holds nothing. */
    private static CoopOptionsStore storeWithCommonFile(Map<String, Object> values) {
        JSONObject common = new JSONObject();
        try {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                common.put(entry.getKey(), entry.getValue());
            }
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
        return new CoopOptionsStore(new CoopOptionsStore.JsonSource() {
            @Override
            public JSONObject shipped() {
                return null;
            }

            @Override
            public JSONObject common() {
                return common;
            }
        }, key -> null);
    }

    @Test
    void theDisableFlagIsTheRegisteredOptionNotAPrivateStringLiteral() {
        assertEquals(CoopOptionsRegistry.HUD_DISABLE, CoopLinkHud.DISABLE_PROPERTY);
        assertTrue(CoopOptionsRegistry.fileBackedOptions().stream()
                        .anyMatch(option -> option.key().equals(CoopLinkHud.DISABLE_PROPERTY)),
                "the flag is file-backed, so it must not be read off System.getProperty");
    }

    /**
     * The regression: the flag is registered and file-backed, but installation used to test it with
     * {@code System.getProperty}, so turning the HUD off in {@code saves/common/coop_options.json.data}
     * did nothing.
     */
    @Test
    void theUserSettingsFileCanTurnTheHudOff() {
        assertTrue(CoopLinkHud.disabledByOption(
                storeWithCommonFile(Map.of(CoopLinkHud.DISABLE_PROPERTY, true))));
        assertFalse(CoopLinkHud.disabledByOption(
                storeWithCommonFile(Map.of(CoopLinkHud.DISABLE_PROPERTY, false))));
        assertFalse(CoopLinkHud.disabledByOption(storeWithCommonFile(new HashMap<>())));
    }

    /** A typo is warned about by the store and read as the default, never as "off". */
    @Test
    void anUnreadableDisableValueLeavesTheHudInstalled() {
        assertFalse(CoopLinkHud.disabledByOption(
                storeWithCommonFile(Map.of(CoopLinkHud.DISABLE_PROPERTY, "yes please"))));
    }

    @Test
    void theCommandLineStillWinsOverTheFile() {
        String previous = System.getProperty(CoopLinkHud.DISABLE_PROPERTY);
        try {
            System.setProperty(CoopLinkHud.DISABLE_PROPERTY, "true");
            assertTrue(CoopLinkHud.disabledByOption());

            System.setProperty(CoopLinkHud.DISABLE_PROPERTY, "false");
            assertFalse(CoopLinkHud.disabledByOption());

            System.clearProperty(CoopLinkHud.DISABLE_PROPERTY);
            assertFalse(CoopLinkHud.disabledByOption());
        } finally {
            if (previous == null) {
                System.clearProperty(CoopLinkHud.DISABLE_PROPERTY);
            } else {
                System.setProperty(CoopLinkHud.DISABLE_PROPERTY, previous);
            }
        }
    }
}
