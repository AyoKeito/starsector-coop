package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal AngelCode BMFont (text format) reader and quad renderer, for the Phase 20.6 link HUD.
 *
 * <p>The engine exposes no text-drawing call to a {@code CampaignUIRenderingListener}, so the HUD
 * draws its own glyph quads the way LazyLib's LazyFont does: parse the {@code .fnt} sidecar, bind
 * the single page texture, emit one textured quad per character.
 *
 * <p>Two halves, deliberately split:
 * <ul>
 *   <li>{@link #parse(String)} is a pure function over the file's text — no engine, no GL, no IO —
 *   so the layout maths is unit-testable.</li>
 *   <li>{@link #load(String)} is the engine half: it asks {@code SettingsAPI} to do the file reads
 *   ({@code loadText}/{@code loadTexture}), because the mod classloader blocks {@code java.io.*}
 *   outright and the engine is the only legal reader.</li>
 * </ul>
 *
 * <p>Kerning pairs are ignored; the vanilla UI font ships none that matter at this size, and a
 * status line is not typography.
 */
public final class CoopBitmapFont {

    /** One character cell. Coordinates are page-texture pixels, y measured from the PNG's top. */
    public record Glyph(int id, int x, int y, int width, int height,
                        int xOffset, int yOffset, int xAdvance) {
    }

    /** Substituted for any character the page has no cell for; skipped when it is absent too. */
    private static final int FALLBACK_CHAR = '?';

    private final int lineHeight;
    private final int base;
    private final int scaleW;
    private final int scaleH;
    private final String pageFile;
    private final Map<Integer, Glyph> glyphs;

    /** Page texture handle, 0 until {@link #load(String)} resolves one. Draws no-op while 0. */
    private int textureId;

    private CoopBitmapFont(int lineHeight, int base, int scaleW, int scaleH, String pageFile,
                           Map<Integer, Glyph> glyphs) {
        this.lineHeight = lineHeight;
        this.base = base;
        this.scaleW = scaleW;
        this.scaleH = scaleH;
        this.pageFile = pageFile;
        this.glyphs = glyphs;
    }

    // ---- parsing --------------------------------------------------------------------------------

    /**
     * Parses the text form of a BMFont descriptor. Unknown line kinds (notably {@code kerning}) and
     * malformed lines are skipped rather than fatal: a font that renders 232 of 233 glyphs is a far
     * better outcome for a cosmetic HUD than one that throws.
     *
     * @throws IllegalArgumentException when the descriptor carries no usable page geometry or no
     *                                  glyphs at all, i.e. when nothing could be drawn from it
     */
    public static CoopBitmapFont parse(String fntText) {
        if (fntText == null) {
            throw new IllegalArgumentException("fntText is null");
        }
        int lineHeight = 0;
        int base = 0;
        int scaleW = 0;
        int scaleH = 0;
        String pageFile = null;
        Map<Integer, Glyph> glyphs = new HashMap<>();

        for (String rawLine : fntText.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int kindEnd = line.indexOf(' ');
            if (kindEnd < 0) {
                continue;
            }
            String kind = line.substring(0, kindEnd);
            Map<String, String> fields = fields(line.substring(kindEnd + 1));
            switch (kind) {
                case "common" -> {
                    lineHeight = intOr(fields, "lineHeight", lineHeight);
                    base = intOr(fields, "base", base);
                    scaleW = intOr(fields, "scaleW", scaleW);
                    scaleH = intOr(fields, "scaleH", scaleH);
                }
                case "page" -> {
                    String file = fields.get("file");
                    if (file != null && !file.isEmpty() && pageFile == null) {
                        pageFile = file;
                    }
                }
                case "char" -> {
                    Integer id = intOrNull(fields, "id");
                    if (id == null) {
                        continue;
                    }
                    glyphs.put(id, new Glyph(
                            id,
                            intOr(fields, "x", 0),
                            intOr(fields, "y", 0),
                            intOr(fields, "width", 0),
                            intOr(fields, "height", 0),
                            intOr(fields, "xoffset", 0),
                            intOr(fields, "yoffset", 0),
                            intOr(fields, "xadvance", 0)));
                }
                default -> {
                    // info, chars, kernings, kerning, anything else: nothing here needs them.
                }
            }
        }

        if (scaleW <= 0 || scaleH <= 0) {
            throw new IllegalArgumentException("BMFont descriptor has no page size (scaleW/scaleH)");
        }
        if (glyphs.isEmpty()) {
            throw new IllegalArgumentException("BMFont descriptor has no char lines");
        }
        if (lineHeight <= 0) {
            lineHeight = scaleH;
        }
        return new CoopBitmapFont(lineHeight, base, scaleW, scaleH, pageFile, glyphs);
    }

    /**
     * Parses {@code fntPath} and binds its page texture, using the engine for both file reads.
     * Callers wrap this in {@code catch (Throwable)}; it makes no attempt to recover on its own.
     *
     * @param fntPath engine-relative path, e.g. {@code graphics/fonts/insignia15LTaa.fnt}
     */
    public static CoopBitmapFont load(String fntPath) throws Exception {
        String text = Global.getSettings().loadText(fntPath);
        CoopBitmapFont font = parse(text);
        String page = font.pageFile();
        if (page == null || page.isEmpty()) {
            throw new IllegalStateException("BMFont descriptor names no page file: " + fntPath);
        }
        String pagePath = resolveSibling(fntPath, page);
        Global.getSettings().loadTexture(pagePath);
        SpriteAPI sprite = Global.getSettings().getSprite(pagePath);
        if (sprite == null) {
            throw new IllegalStateException("No sprite for font page " + pagePath);
        }
        font.textureId = sprite.getTextureId();
        return font;
    }

    /** Resolves a page file name against the descriptor's own directory. */
    static String resolveSibling(String path, String sibling) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? sibling : path.substring(0, slash + 1) + sibling;
    }

    // ---- metrics --------------------------------------------------------------------------------

    public int lineHeight() {
        return lineHeight;
    }

    public int base() {
        return base;
    }

    public String pageFile() {
        return pageFile;
    }

    public int textureId() {
        return textureId;
    }

    public boolean hasGlyph(int codePoint) {
        return glyphs.containsKey(codePoint);
    }

    public Glyph glyph(int codePoint) {
        return glyphs.get(codePoint);
    }

    /** The glyph to draw for {@code codePoint}: itself, else {@code '?'}, else null (skip it). */
    public Glyph glyphOrFallback(int codePoint) {
        Glyph glyph = glyphs.get(codePoint);
        return glyph != null ? glyph : glyphs.get(FALLBACK_CHAR);
    }

    /** Advance width of {@code text} in font pixels; unrenderable characters contribute nothing. */
    public float width(String text) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float total = 0f;
        for (int i = 0; i < text.length(); i++) {
            Glyph glyph = glyphOrFallback(text.charAt(i));
            if (glyph != null) {
                total += glyph.xAdvance();
            }
        }
        return total;
    }

    // ---- drawing --------------------------------------------------------------------------------

    /**
     * Draws one line of text as textured quads. {@code (x, y)} is the TOP-LEFT of the line in UI
     * coordinates (origin bottom-left, y up); glyphs hang downward from it by their {@code yoffset},
     * so successive lines would step down by {@link #lineHeight()}.
     *
     * <p>Sets up and restores its own GL state — the engine's UI pass makes no promises about what
     * is bound or enabled when a listener runs, and leaving state dirty would corrupt the vanilla UI
     * drawn after us. No-op when the page texture never resolved.
     */
    public void draw(String text, float x, float y, Color color) {
        if (textureId == 0 || text == null || text.isEmpty() || color == null) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT
                | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f,
                    color.getBlue() / 255f, color.getAlpha() / 255f);
            GL11.glBegin(GL11.GL_QUADS);
            float penX = x;
            for (int i = 0; i < text.length(); i++) {
                Glyph glyph = glyphOrFallback(text.charAt(i));
                if (glyph == null) {
                    continue;
                }
                if (glyph.width() > 0 && glyph.height() > 0) {
                    float left = penX + glyph.xOffset();
                    float right = left + glyph.width();
                    float top = y - glyph.yOffset();
                    float bottom = top - glyph.height();
                    // BMFont y grows downward from the page's top edge; GL texture v grows upward.
                    float u0 = glyph.x() / (float) scaleW;
                    float u1 = (glyph.x() + glyph.width()) / (float) scaleW;
                    float v0 = 1f - glyph.y() / (float) scaleH;
                    float v1 = 1f - (glyph.y() + glyph.height()) / (float) scaleH;
                    GL11.glTexCoord2f(u0, v0);
                    GL11.glVertex2f(left, top);
                    GL11.glTexCoord2f(u1, v0);
                    GL11.glVertex2f(right, top);
                    GL11.glTexCoord2f(u1, v1);
                    GL11.glVertex2f(right, bottom);
                    GL11.glTexCoord2f(u0, v1);
                    GL11.glVertex2f(left, bottom);
                }
                penX += glyph.xAdvance();
            }
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
    }

    // ---- field parsing --------------------------------------------------------------------------

    /** Splits {@code key=value} pairs, honouring the quoted values BMFont uses for file names. */
    private static Map<String, String> fields(String remainder) {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        int length = remainder.length();
        while (i < length) {
            while (i < length && Character.isWhitespace(remainder.charAt(i))) {
                i++;
            }
            int keyStart = i;
            while (i < length && remainder.charAt(i) != '=' && !Character.isWhitespace(remainder.charAt(i))) {
                i++;
            }
            if (i >= length || remainder.charAt(i) != '=') {
                // A bare token with no '=' - malformed; skip it and keep reading the line.
                if (i == keyStart) {
                    i++;
                }
                continue;
            }
            String key = remainder.substring(keyStart, i);
            i++;
            String value;
            if (i < length && remainder.charAt(i) == '"') {
                i++;
                int valueStart = i;
                while (i < length && remainder.charAt(i) != '"') {
                    i++;
                }
                value = remainder.substring(valueStart, Math.min(i, length));
                if (i < length) {
                    i++;
                }
            } else {
                int valueStart = i;
                while (i < length && !Character.isWhitespace(remainder.charAt(i))) {
                    i++;
                }
                value = remainder.substring(valueStart, i);
            }
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static int intOr(Map<String, String> fields, String key, int fallback) {
        Integer value = intOrNull(fields, key);
        return value == null ? fallback : value;
    }

    private static Integer intOrNull(Map<String, String> fields, String key) {
        String raw = fields.get(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
