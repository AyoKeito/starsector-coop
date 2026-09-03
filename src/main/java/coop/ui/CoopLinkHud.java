package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import coop.net.CoopNetPump;
import coop.net.CoopNetStartupConfig;
import coop.util.CoopLog;

import java.awt.Color;

/**
 * Phase 20.6 milestone 0: a one-line, always-on campaign HUD saying which side of the link you are,
 * what the session is doing, who is holding the shared pause, and — on the guest — how far its
 * campaign clock has drifted from the host's.
 *
 * <p>Drawn in the {@code aboveUIBelowTooltips} pass so it sits over the campaign map but never over
 * a tooltip. Anchored by default under the vanilla fps/version overlay in the top-right corner;
 * {@code -Dcoop.hudCorner=TL|TR|BL|BR} moves it to any corner (see {@link CoopHudCorner}).
 *
 * <p><b>Failure policy.</b> This is cosmetic; nothing it does may ever cost a frame. Every engine
 * touch runs under {@code catch (Throwable)}, and the first failure disables the instance for good
 * after exactly one log line — a HUD that stops drawing is a nuisance, a HUD that throws sixty times
 * a second in the render pass is a crash. {@code -Dcoop.hud.disable=true} skips installation
 * entirely.
 */
public final class CoopLinkHud implements CampaignUIRenderingListener {

    /** Launch flag: when true the listener is never registered. */
    public static final String DISABLE_PROPERTY = "coop.hud.disable";

    /** Gap from the left/right screen edge, in UI-coordinate pixels. Tune after a visual check. */
    static final float SIDE_MARGIN = 10f;
    /** Drop from the top screen edge to the line's top, clearing the vanilla fps/version overlay. */
    static final float TOP_OFFSET = 60f;
    /** Gap from the bottom screen edge to the line's bottom, clearing the vanilla bottom bar. */
    static final float BOTTOM_OFFSET = 60f;

    /** The vanilla UI font. */
    static final String FONT_PATH = "graphics/fonts/insignia15LTaa.fnt";

    /** Wall-clock throttle: the link state is re-read at most this often, not once per frame. */
    private static final long REFRESH_INTERVAL_MILLIS = 100L;

    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color PAUSED_COLOR = new Color(255, 215, 120);

    private final CoopNetPump pump;
    private final CoopHudCorner corner;

    private CoopBitmapFont font;
    private String separator = CoopHudState.SEPARATOR_PIPE;
    private boolean fontLoadAttempted;

    /** Sticky: set by the first Throwable out of anything below. Never cleared. */
    private boolean disabled;

    private long nextRefreshAtMillis;
    private String cachedBadge = CoopHudState.BADGE_COOP;
    private String cachedLine = "";
    private boolean cachedPaused;

    private CoopLinkHud(CoopNetPump pump, CoopHudCorner corner) {
        this.pump = pump;
        this.corner = corner == null ? CoopHudCorner.DEFAULT : corner;
    }

    /**
     * Registers a fresh HUD on {@code sector}, replacing any previous one (a game load installs a new
     * pump, and the listener must point at it). Transient: the listener holds a live pump reference
     * and must never be walked into a save.
     */
    public static void install(SectorAPI sector, CoopNetPump pump) {
        if (sector == null || pump == null) {
            return;
        }
        if (Boolean.parseBoolean(System.getProperty(DISABLE_PROPERTY))) {
            CoopLog.info(CoopLinkHud.class,
                    "Coop link HUD disabled via -D" + DISABLE_PROPERTY + "=true");
            return;
        }
        try {
            CoopHudCorner corner = CoopNetStartupConfig.hudCornerFromSystemProperties();
            sector.getListenerManager().removeListenerOfClass(CoopLinkHud.class);
            sector.getListenerManager().addListener(new CoopLinkHud(pump, corner), true);
        } catch (Throwable ex) {
            CoopLog.warn(CoopLinkHud.class, "Coop link HUD could not be installed; continuing without it", ex);
        }
    }

    @Override
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {
        // Nothing here: the line belongs above the UI.
    }

    @Override
    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        // Nothing here: never over a tooltip.
    }

    @Override
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {
        if (disabled) {
            return;
        }
        try {
            render();
        } catch (Throwable ex) {
            disabled = true;
            CoopLog.warn(CoopLinkHud.class, "Coop link HUD failed and is now off for this session", ex);
        }
    }

    private void render() {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null || ui.isHideUI()) {
            return;
        }
        if (!ensureFont()) {
            return;
        }

        refreshIfDue(sector);
        if (cachedLine.isEmpty()) {
            return;
        }

        float screenWidth = Global.getSettings().getScreenWidth();
        float screenHeight = Global.getSettings().getScreenHeight();
        float badgeWidth = font.width(cachedBadge);
        float totalWidth = font.width(cachedLine);
        HudAnchor anchor = anchor(corner, screenWidth, screenHeight, totalWidth, font.lineHeight());
        float x = anchor.x();
        float y = anchor.y();

        // Two draws so the badge keeps the player colour while the rest carries the pause state.
        // formatLine always leads with the badge, so this split is exact.
        String remainder = cachedLine.substring(Math.min(cachedBadge.length(), cachedLine.length()));
        font.draw(cachedBadge, x, y, badgeColor());
        font.draw(remainder, x + badgeWidth, y, cachedPaused ? PAUSED_COLOR : TEXT_COLOR);
    }

    /**
     * The line's top-left in UI coordinates (origin bottom-left, y up — see
     * {@link CoopBitmapFont#draw}), for the requested corner.
     *
     * <p>Pure and GL-free so the four placements are unit-testable without a live renderer.
     * {@code textHeight} is the font's line height, not this particular line's rendered height (the
     * bitmap font has no per-string height, only a per-font one) — it is only used to lift the line
     * clear of the bottom offset for the two bottom corners; the top corners anchor from the line's
     * top and never need it.
     *
     * @param corner      which corner to anchor to
     * @param screenWidth  UI-coordinate screen width
     * @param screenHeight UI-coordinate screen height
     * @param textWidth    the rendered line's width, from {@link CoopBitmapFont#width}
     * @param textHeight   the font's line height, from {@link CoopBitmapFont#lineHeight()}
     */
    static HudAnchor anchor(CoopHudCorner corner, float screenWidth, float screenHeight,
                             float textWidth, float textHeight) {
        CoopHudCorner effective = corner == null ? CoopHudCorner.DEFAULT : corner;
        boolean left = effective == CoopHudCorner.TOP_LEFT || effective == CoopHudCorner.BOTTOM_LEFT;
        boolean top = effective == CoopHudCorner.TOP_LEFT || effective == CoopHudCorner.TOP_RIGHT;
        float x = left ? SIDE_MARGIN : screenWidth - SIDE_MARGIN - textWidth;
        float y = top ? screenHeight - TOP_OFFSET : BOTTOM_OFFSET + textHeight;
        return new HudAnchor(x, y);
    }

    /** The line's top-left anchor point, in UI coordinates; see {@link #anchor}. */
    record HudAnchor(float x, float y) {
    }

    private void refreshIfDue(SectorAPI sector) {
        long now = System.currentTimeMillis();
        if (now < nextRefreshAtMillis) {
            return;
        }
        nextRefreshAtMillis = now + REFRESH_INTERVAL_MILLIS;
        CoopHudState state = pump.hudState(sector.isPaused());
        cachedBadge = state.roleBadge();
        cachedPaused = state.paused();
        cachedLine = CoopHudState.formatLine(state, separator);
    }

    private Color badgeColor() {
        Color color = Global.getSettings().getBasePlayerColor();
        return color == null ? TEXT_COLOR : color;
    }

    /** Loads the font once. A failed load is not retried — one warn, then the HUD stays dark. */
    private boolean ensureFont() {
        if (font != null) {
            return true;
        }
        if (fontLoadAttempted) {
            return false;
        }
        fontLoadAttempted = true;
        try {
            CoopBitmapFont loaded = CoopBitmapFont.load(FONT_PATH);
            separator = loaded.hasGlyph(CoopHudState.SEPARATOR_DOT_CODE_POINT)
                    ? CoopHudState.SEPARATOR_DOT
                    : CoopHudState.SEPARATOR_PIPE;
            font = loaded;
            return true;
        } catch (Throwable ex) {
            disabled = true;
            CoopLog.warn(CoopLinkHud.class,
                    "Coop link HUD could not load " + FONT_PATH + "; HUD is off for this session", ex);
            return false;
        }
    }
}
