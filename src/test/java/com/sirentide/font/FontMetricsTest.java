package com.sirentide.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// Exercises the font-metrics oracle against the bundled STIX Two Math font. Verifies the sfnt
/// parse, pixel scaling, run-width additivity, line height, and greedy word-wrap.
class FontMetricsTest {

    private final FontMetrics fm = FontMetrics.bundled();

    @Test
    void fontParsesWithSaneUnitsPerEm() {
        // STIX Two Math is a 1000-upem font; any sane sfnt is a power-of-two or 1000.
        SfntMetrics sfnt = SfntMetrics.loadBundled();
        assertTrue(sfnt.unitsPerEm() > 0 && sfnt.unitsPerEm() <= 4096, "unitsPerEm sane");
        assertTrue(sfnt.numGlyphs() > 100, "a real font has many glyphs");
        assertTrue(sfnt.glyphId('A') != 0, "'A' is mapped to a real glyph");
        assertTrue(sfnt.advanceWidth(sfnt.glyphId('A')) > 0, "'A' has a positive advance");
    }

    @Test
    void runWidthIsPositiveAndScalesLinearlyWithSize() {
        double at16 = fm.runWidth("Sirentide", 16);
        double at32 = fm.runWidth("Sirentide", 32);
        assertTrue(at16 > 0, "positive width");
        assertEquals(at16 * 2, at32, 1e-9, "width scales linearly with font size");
    }

    @Test
    void runWidthIsAdditiveOverCharacters() {
        double a = fm.runWidth("A", 20);
        double aa = fm.runWidth("AA", 20);
        assertEquals(a * 2, aa, 1e-9, "two A's are twice one A");
    }

    @Test
    void lineHeightIsPositiveAndScales() {
        assertTrue(fm.lineHeight(16) > 0, "positive line height");
        assertEquals(fm.lineHeight(16) * 2, fm.lineHeight(32), 1e-9, "scales with size");
        // Sane range for a 16px line: not degenerate, not absurd (exact value is font-specific).
        assertTrue(fm.lineHeight(16) > 8 && fm.lineHeight(16) < 48, "line height in a sane range");
    }

    @Test
    void measureSingleLineHasOneLineAndMatchesRunWidth() {
        TextBox box = fm.measure("node label", 14);
        assertEquals(1, box.lines().size(), "single line");
        assertEquals(fm.runWidth("node label", 14), box.width(), 1e-9);
        assertEquals(fm.lineHeight(14), box.height(), 1e-9);
    }

    @Test
    void wrappingBreaksOnWidthAndEveryLineFitsExceptLongWords() {
        String text = "the quick brown fox jumps over the lazy dog";
        double maxWidth = fm.runWidth("the quick brown", 14); // ~3 words wide
        TextBox box = fm.measureWrapped(text, maxWidth, 14);

        assertTrue(box.lines().size() > 1, "wrapped into multiple lines");
        for (String line : box.lines()) {
            // Each line (all multi-word here) must fit the limit.
            assertTrue(fm.runWidth(line, 14) <= maxWidth + 1e-6, "line fits: '" + line + "'");
        }
        assertEquals(box.lines().size() * fm.lineHeight(14), box.height(), 1e-9);
        // Reassembling the wrapped lines recovers the words in order.
        assertEquals(text, String.join(" ", box.lines()));
    }

    @Test
    void ellipsizeClipsLongLabelWithinItsRegionWidth() {
        String longLabel = "Quarterly revenue by region and product line";
        double maxWidth = fm.runWidth("Quarterly", 12);   // room for ~one word
        String clipped = fm.ellipsize(longLabel, maxWidth, 12);
        assertTrue(clipped.endsWith("…"), "clipped with an ellipsis: '" + clipped + "'");
        assertTrue(clipped.length() < longLabel.length(), "actually shortened");
        assertTrue(fm.runWidth(clipped, 12) <= maxWidth + 1e-6,
            "the clipped label fits within the region: '" + clipped + "'");
    }

    @Test
    void ellipsizeLeavesAFittingLabelUntouched() {
        assertEquals("OK", fm.ellipsize("OK", 500, 12), "a label that already fits is unchanged");
        assertEquals("OK", fm.ellipsize("OK", Double.POSITIVE_INFINITY, 12), "no-limit is unchanged");
    }

    @Test
    void hugeWidthMeansNoWrap() {
        TextBox box = fm.measureWrapped("no wrapping happens here", Double.POSITIVE_INFINITY, 14);
        assertEquals(1, box.lines().size(), "infinite width = single line");
    }

    @Test
    void measurementIsDeterministic() {
        assertEquals(fm.runWidth("Sirentide → SVG", 18), fm.runWidth("Sirentide → SVG", 18));
    }

    // -- text as paths (glyph outlines) --

    @Test
    void glyphOutlineProducesRealContours() {
        SfntMetrics sfnt = SfntMetrics.loadBundled();
        var contours = sfnt.glyphContours(sfnt.glyphId('A'));
        assertTrue(!contours.isEmpty(), "'A' has a real outline");
        assertTrue(contours.get(0).points().size() > 2, "the contour has real points");
    }

    @Test
    void textRendersToAWellFormedPath() {
        String d = fm.textPathD("Sirentide", 0, 100, 24);
        assertTrue(!d.isBlank(), "non-empty path");
        assertTrue(d.startsWith("M "), "begins with a moveto");
        assertTrue(d.contains("Z"), "closes its contours");
        assertTrue(d.contains("Q ") || d.contains("L "), "draws curves or lines");
        assertTrue(!d.contains("NaN") && !d.contains("Infinity"), "no degenerate coordinates");
    }

    @Test
    void textPathIsDeterministic() {
        assertEquals(fm.textPathD("Reviews", 10, 50, 16), fm.textPathD("Reviews", 10, 50, 16));
    }

    @Test
    void moreGlyphsProduceMorePathData() {
        assertTrue(fm.textPathD("AAAA", 0, 100, 20).length() > fm.textPathD("A", 0, 100, 20).length(),
            "more glyphs => more path");
    }

    @Test
    void compositeAccentedGlyphsRender() {
        // Accented Latin letters are typically COMPOSITE glyphs (base + accent components, each
        // affine-transformed). Before composite support they returned empty; now at least one of
        // these must produce a real outline.
        SfntMetrics sfnt = SfntMetrics.loadBundled();
        int rendered = 0;
        for (int cp : new int[] {'é', 'ñ', 'ü', 'à', 'ç', 'ö', 'â', 'ê'}) {
            int gid = sfnt.glyphId(cp);
            if (gid != 0 && !sfnt.glyphContours(gid).isEmpty()) {
                rendered++;
            }
        }
        assertTrue(rendered > 0, "at least one accented (composite) glyph renders");
    }
}
