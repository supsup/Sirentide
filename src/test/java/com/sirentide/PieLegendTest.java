package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// The opt-in `pie legend` (left colour key) mode. The legend lists each drawn slice once as a
/// swatch + label + value on the left, and SUPPRESSES the on-slice / leader-line labels — so a
/// busy or thin-sliced pie stays readable. Bare `pie` must be untouched (guarded here + by the
/// pie golden, which did NOT regen).
class PieLegendTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static double svgWidth(String svg) {
        int i = svg.indexOf("width=\"") + 7;
        return Double.parseDouble(svg.substring(i, svg.indexOf('"', i)));
    }

    @Test
    void oneSwatchRectPerDrawnSlice() {
        String svg = Sirentide.render("""
            pie legend
              "Reviews" : 40
              "Builds"  : 30
              "Docs"    : 20
              "Ops"     : 10
            """);
        // A plain pie emits NO <rect> (wedges are <path>), so every rect here is a key swatch.
        assertEquals(4, count(svg, "<rect"), "one colour swatch per drawn slice");
        // The four wedges are still drawn (as <path>), unchanged.
        assertTrue(count(svg, "<path") >= 4, "four wedge paths still present");
    }

    @Test
    void legendSuppressesLeaderLinesEvenForThinSlices() {
        // Two 1% slivers would normally spawn outside leader lines; the legend replaces them.
        String svg = Sirentide.render("""
            pie legend
              "Big"   : 96
              "Tiny1" : 1
              "Tiny2" : 1
              "Small" : 2
            """);
        assertEquals(0, count(svg, "<line"), "legend mode emits zero leader lines");
        assertEquals(4, count(svg, "<rect"), "one swatch per slice, no on-slice labels");
    }

    @Test
    void legendCanvasIsWiderThanABarePie() {
        String bare = Sirentide.render("pie\n  \"A\" : 1\n  \"B\" : 1\n");
        String legend = Sirentide.render("pie legend\n  \"A\" : 1\n  \"B\" : 1\n");
        assertTrue(svgWidth(legend) > svgWidth(bare),
            "legend widens the canvas to fit the left key column");
    }

    @Test
    void keyAliasAlsoEnablesLegend() {
        String svg = Sirentide.render("pie key\n  \"A\" : 1\n  \"B\" : 1\n");
        assertEquals(2, count(svg, "<rect"), "`pie key` is an alias for `pie legend`");
    }

    @Test
    void longLegendLabelIsEllipsizedWithinTheColumn() {
        String svg = Sirentide.render("""
            pie legend
              "An extremely long legend label that cannot possibly fit the key column" : 5
              "B" : 5
            """);
        // The wrap oracle inserts the ellipsis glyph rather than overrunning the key width.
        assertTrue(svg.contains("<path"), "still renders");
        // Row count is honest: two swatches for two slices even with a truncated label.
        assertEquals(2, count(svg, "<rect"), "long label still gets its own swatch row");
    }

    @Test
    void barePieUnaffectedByTheModifierPlumbing() {
        // Regression: the plain pie output must be identical shape-wise (wedges + inside labels,
        // zero rects, zero leader lines here). The byte-level guard is the untouched pie golden.
        String svg = Sirentide.render("pie\n  \"A\" : 40\n  \"B\" : 30\n  \"C\" : 30\n");
        assertEquals(0, count(svg, "<rect"), "bare pie emits no swatch rects");
        assertEquals(6, count(svg, "<path"), "3 wedges + 3 inside labels, unchanged");
    }

    @Test
    void unknownModifierDegradesToPlainPie() {
        // A typo'd modifier must not fail the bake — it renders the plain pie (no key).
        String svg = Sirentide.render("pie legned\n  \"A\" : 40\n  \"B\" : 60\n");
        assertEquals(0, count(svg, "<rect"), "unknown modifier ignored → plain pie, no swatches");
        assertTrue(svg.startsWith("<svg"), "still a valid svg");
    }
}
