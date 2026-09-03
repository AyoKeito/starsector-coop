package coop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
