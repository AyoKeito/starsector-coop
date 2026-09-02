package coop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopBitmapFontTest {

    /** Verbatim lines from starsector-core/graphics/fonts/insignia15LTaa.fnt, CRLF and all. */
    private static final String SAMPLE = String.join("\r\n",
            "info face=\"InsigniaLT\" size=15 bold=0 italic=0 charset=\"\" unicode=1 stretchH=100 smooth=1 aa=4 padding=0,0,0,0 spacing=1,1 outline=0",
            "common lineHeight=15 base=12 scaleW=256 scaleH=256 pages=1 packed=0 alphaChnl=1 redChnl=0 greenChnl=0 blueChnl=0",
            "page id=0 file=\"insignia15LTaa_0.png\"",
            "chars count=6",
            "char id=32   x=254   y=39    width=1     height=1     xoffset=0     yoffset=12    xadvance=4     page=0  chnl=15",
            "char id=33   x=165   y=38    width=2     height=11    xoffset=1     yoffset=2     xadvance=4     page=0  chnl=15",
            "char id=63   x=88    y=39    width=7     height=11    xoffset=0     yoffset=2     xadvance=8     page=0  chnl=15",
            "char id=97   x=10    y=20    width=8     height=8     xoffset=0     yoffset=5     xadvance=9     page=0  chnl=15",
            "char id=98   x=20    y=20    width=8     height=11    xoffset=0     yoffset=2     xadvance=10    page=0  chnl=15",
            "char id=183  x=212   y=69    width=3     height=3     xoffset=0     yoffset=7     xadvance=4     page=0  chnl=15",
            "kernings count=1",
            "kerning first=97 second=98 amount=-1",
            "");

    @Test
    void parsesCommonAndPageHeaders() {
        CoopBitmapFont font = CoopBitmapFont.parse(SAMPLE);

        assertEquals(15, font.lineHeight());
        assertEquals(12, font.base());
        assertEquals("insignia15LTaa_0.png", font.pageFile());
        assertEquals(0, font.textureId());
    }

    @Test
    void parsesEveryGlyphField() {
        CoopBitmapFont font = CoopBitmapFont.parse(SAMPLE);

        CoopBitmapFont.Glyph bang = font.glyph('!');
        assertNotNull(bang);
        assertEquals(33, bang.id());
        assertEquals(165, bang.x());
        assertEquals(38, bang.y());
        assertEquals(2, bang.width());
        assertEquals(11, bang.height());
        assertEquals(1, bang.xOffset());
        assertEquals(2, bang.yOffset());
        assertEquals(4, bang.xAdvance());
    }

    @Test
    void parsesSpaceGlyphWithWidthOne() {
        CoopBitmapFont font = CoopBitmapFont.parse(SAMPLE);

        CoopBitmapFont.Glyph space = font.glyph(' ');
        assertNotNull(space);
        assertEquals(1, space.width());
        assertEquals(1, space.height());
        assertEquals(12, space.yOffset());
        assertEquals(4, space.xAdvance());
    }

    @Test
    void parsesMiddleDotUsedAsTheHudSeparator() {
        CoopBitmapFont font = CoopBitmapFont.parse(SAMPLE);

        assertTrue(font.hasGlyph(CoopHudState.SEPARATOR_DOT_CODE_POINT));
        assertEquals(4, font.glyph(CoopHudState.SEPARATOR_DOT_CODE_POINT).xAdvance());
    }

    @Test
    void widthSumsAdvances() {
        CoopBitmapFont font = CoopBitmapFont.parse(SAMPLE);

        assertEquals(19f, font.width("ab"), 0.0001f);
        assertEquals(0f, font.width(""), 0.0001f);
        assertEquals(0f, font.width(null), 0.0001f);
    }

    @Test
    void kerningPairsAreIgnored() {
        CoopBitmapFont font = CoopBitmapFont.parse(SAMPLE);

        // 9 + 10, not 9 + 10 - 1: the kerning line is parsed past, not applied.
        assertEquals(font.glyph('a').xAdvance() + font.glyph('b').xAdvance(), font.width("ab"), 0.0001f);
    }

    @Test
    void missingGlyphFallsBackToQuestionMark() {
        CoopBitmapFont font = CoopBitmapFont.parse(SAMPLE);

        assertFalse(font.hasGlyph('Z'));
        assertNull(font.glyph('Z'));
        assertEquals(63, font.glyphOrFallback('Z').id());
        // Unknown characters therefore cost the '?' advance, not zero.
        assertEquals(8f, font.width("Z"), 0.0001f);
    }

    @Test
    void missingGlyphIsSkippedWhenThereIsNoQuestionMarkEither() {
        String noFallback = String.join("\n",
                "common lineHeight=15 base=12 scaleW=256 scaleH=256 pages=1",
                "page id=0 file=\"p_0.png\"",
                "char id=97 x=10 y=20 width=8 height=8 xoffset=0 yoffset=5 xadvance=9 page=0 chnl=15");
        CoopBitmapFont font = CoopBitmapFont.parse(noFallback);

        assertNull(font.glyphOrFallback('Z'));
        assertEquals(9f, font.width("aZ"), 0.0001f);
    }

    @Test
    void malformedLinesAreSkippedRatherThanFatal() {
        String messy = String.join("\n",
                "common lineHeight=15 base=12 scaleW=256 scaleH=256",
                "page id=0 file=\"p_0.png\"",
                "char id=notANumber x=1 y=2 width=3 height=4 xadvance=5",
                "char x=1 y=2 width=3 height=4 xadvance=5",
                "char id=97 x=10 y=20 width=8 height=8 xoffset=0 yoffset=5 xadvance=9 page=0 chnl=15",
                "char id=98 x=20 y=20 width=oops height=11 xoffset=0 yoffset=2 xadvance=10",
                "garbage line with no equals signs at all",
                "   ",
                "trailingKey=");
        CoopBitmapFont font = CoopBitmapFont.parse(messy);

        assertNotNull(font.glyph('a'));
        // id parsed, but the unparsable width degrades to 0 instead of dropping the glyph.
        assertNotNull(font.glyph('b'));
        assertEquals(0, font.glyph('b').width());
        assertEquals(10, font.glyph('b').xAdvance());
    }

    @Test
    void descriptorWithoutPageSizeIsRejected() {
        String noSize = String.join("\n",
                "common lineHeight=15 base=12",
                "char id=97 x=1 y=2 width=3 height=4 xadvance=5");

        assertThrows(IllegalArgumentException.class, () -> CoopBitmapFont.parse(noSize));
    }

    @Test
    void descriptorWithoutGlyphsIsRejected() {
        String noChars = "common lineHeight=15 base=12 scaleW=256 scaleH=256";

        assertThrows(IllegalArgumentException.class, () -> CoopBitmapFont.parse(noChars));
    }

    @Test
    void nullDescriptorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CoopBitmapFont.parse(null));
    }

    @Test
    void pageFileResolvesAgainstTheDescriptorsDirectory() {
        assertEquals("graphics/fonts/insignia15LTaa_0.png",
                CoopBitmapFont.resolveSibling("graphics/fonts/insignia15LTaa.fnt", "insignia15LTaa_0.png"));
        assertEquals("page_0.png", CoopBitmapFont.resolveSibling("font.fnt", "page_0.png"));
    }
}
