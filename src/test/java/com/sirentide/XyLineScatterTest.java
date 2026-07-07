package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.XyChart;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;

/// Line + scatter render modes and multi-series for the xychart: the world's most-used chart type,
/// one render mode from the bar chart. Bars stay the byte-identical default (pinned by the xychart
/// golden); this covers the NEW shapes — parse (mode modifiers, multi-value rows, series names,
/// missing points) and render (discs, segment counts, grouped rects, the legend key).
class XyLineScatterTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- parse ----

    @Test
    void modeModifiersAreRecognizedOrderIndependently() {
        assertEquals("line", ((XyChart) DslParser.parse("xychart line\n\"a\" : 1 2\n")).mode());
        assertEquals("scatter", ((XyChart) DslParser.parse("xychart scatter\n\"a\" : 1 2\n")).mode());
        assertEquals("bars", ((XyChart) DslParser.parse("xychart\n\"a\" : 1 2\n")).mode());
        // Compose with color= (order-independent).
        XyChart c = (XyChart) DslParser.parse("xychart color=#334155 line legend\n\"a\" : 1 2\n");
        assertEquals("line", c.mode());
        assertTrue(c.legend(), "legend modifier composes with line + color=");
        assertEquals("#334155", c.textColor());
    }

    @Test
    void multiValueRowYieldsOneSeriesPerToken() {
        XyChart c = (XyChart) DslParser.parse("xychart line\n\"Jan\" : 5 8 3\n");
        assertTrue(c.series() != null, "multi-value → multi-series shape (series != null)");
        assertEquals(1, c.series().size(), "one category row");
        assertEquals(3, c.series().get(0).length, "three whitespace-split values → three series");
        assertEquals(5, c.series().get(0)[0]);
        assertEquals(8, c.series().get(0)[1]);
        assertEquals(3, c.series().get(0)[2]);
    }

    @Test
    void seriesRowNamesTheSeriesWhenFirstBodyLine() {
        XyChart c = (XyChart) DslParser.parse(
            "xychart line legend\nseries: Alpha, Beta, Gamma\n\"Jan\" : 5 8 3\n");
        assertEquals(java.util.List.of("Alpha", "Beta", "Gamma"), c.seriesNames());
        assertEquals(1, c.series().size(), "the series: row is not a data row");
    }

    @Test
    void missingTrailingValuesMeanAbsentPoints() {
        // series count = max row length = 2; the middle row has only 1 value → series 1 absent there.
        XyChart c = (XyChart) DslParser.parse("xychart line\n\"Jan\" : 5 8\n\"Feb\" : 3\n\"Mar\" : 9 2\n");
        assertEquals(2, c.series().get(0).length, "Jan has both series");
        assertEquals(1, c.series().get(1).length, "Feb drops the trailing series (a gap, not a zero)");
        assertEquals(2, c.series().get(2).length, "Mar has both series");
    }

    @Test
    void singleSeriesRowKeepsPerItemHexColor() {
        // A trailing #hex on a SINGLE-value row is still the per-item bar colour (legacy meaning);
        // single-series bars route through the legacy shape (series == null).
        XyChart c = (XyChart) DslParser.parse("xychart\n\"Mon\" : 5 #123456\n\"Tue\" : 8\n");
        assertNull(c.series(), "single-series bars → legacy shape");
        assertEquals("#123456", c.bars().get(0).color(), "trailing hex kept as per-item colour");
        assertNull(c.bars().get(1).color(), "un-tokened bar keeps the palette");
    }

    @Test
    void multiValueRowIgnoresPerItemColor() {
        // With more than one value there are no per-item colours — series colour by palette index.
        XyChart c = (XyChart) DslParser.parse("xychart\n\"Jan\" : 5 8 #123456\n");
        // The #123456 is a non-numeric trailing token after 2 values → dropped, 2 series parsed.
        assertEquals(2, c.series().get(0).length, "two numeric values, colour dropped");
    }

    // --------------------------------------------------------------- render ----

    @Test
    void lineModeEmitsDiscsAndSegments() {
        // 3 categories x 2 series, all present → 2 discs/series x ... = 6 discs; and
        // (3-1) adjacent pairs x 2 series = 4 connecting segments.
        String line = Sirentide.render("xychart line\n\"Jan\" : 5 8\n\"Feb\" : 3 4\n\"Mar\" : 9 2\n");
        // Discs are <path> (full-circle wedges); 6 of them, plus any glyph-path labels.
        assertTrue(count(line, "<path") >= 6, "at least one disc per present point");
        // Isolate the segment count against the scatter twin (same data → identical axis + ticks,
        // so the ONLY <line> difference is the connecting segments).
        String scatter = Sirentide.render("xychart scatter\n\"Jan\" : 5 8\n\"Feb\" : 3 4\n\"Mar\" : 9 2\n");
        assertEquals(4, count(line, "<line") - count(scatter, "<line"),
            "3 categories x 2 series all present → 4 connecting segments");
    }

    @Test
    void missingMiddlePointBreaksTheSegment() {
        // Feb drops series 1 → series 1 loses BOTH its segments (pair Jan-Feb and Feb-Mar), series 0
        // keeps its 2 → 2 segments total (was 4 when all present). Same domain (max/min unchanged).
        String line = Sirentide.render("xychart line\n\"Jan\" : 5 8\n\"Feb\" : 3\n\"Mar\" : 9 2\n");
        String scatter = Sirentide.render("xychart scatter\n\"Jan\" : 5 8\n\"Feb\" : 3\n\"Mar\" : 9 2\n");
        assertEquals(2, count(line, "<line") - count(scatter, "<line"),
            "a missing middle point breaks that series' two segments → 2 remain");
    }

    @Test
    void scatterModeEmitsNoSegments() {
        // Scatter draws discs only. Its <line> count is exactly the axis + tick lines — no segments.
        String scatter = Sirentide.render("xychart scatter\n\"Jan\" : 5 8\n\"Feb\" : 3 4\n\"Mar\" : 9 2\n");
        // The equivalent line chart has strictly MORE lines (the 4 segments) for the same axis/ticks.
        String line = Sirentide.render("xychart line\n\"Jan\" : 5 8\n\"Feb\" : 3 4\n\"Mar\" : 9 2\n");
        assertTrue(count(line, "<line") > count(scatter, "<line"),
            "scatter has no connecting segments; line adds them");
        // 6 discs present (2 series x 3 categories), all <path>.
        assertTrue(count(scatter, "<path") >= 6, "one disc per point, no segments");
    }

    @Test
    void groupedBarsEmitBarsTimesSeriesRects() {
        // Default (bars) mode, multi-series, NO legend → grouped rects only: 2 cats x 3 series = 6.
        String svg = Sirentide.render("xychart\n\"Jan\" : 5 8 3\n\"Feb\" : 4 2 6\n");
        assertEquals(6, count(svg, "<rect"), "grouped bars = categories x series rects");
    }

    @Test
    void legendAddsOneSwatchPerSeriesAndWidensCanvas() {
        String legend = Sirentide.render(
            "xychart legend\nseries: Alpha, Beta\n\"Jan\" : 5 8\n\"Feb\" : 4 2\n");
        String plain = Sirentide.render("xychart\n\"Jan\" : 5 8\n\"Feb\" : 4 2\n");
        // Legend adds 2 swatch rects on top of the 4 grouped bars.
        assertEquals(6, count(legend, "<rect"), "4 grouped bars + 2 legend swatches");
        assertEquals(4, count(plain, "<rect"), "no legend → grouped bars only");
        assertTrue(svgWidth(legend) > svgWidth(plain), "legend widens the canvas like the pie key");
    }

    @Test
    void singleSeriesIgnoresLegendModifier() {
        // One series + legend → nothing to key: no swatch column, canvas not widened.
        String svg = Sirentide.render("xychart legend\n\"Jan\" : 5\n\"Feb\" : 8\n");
        assertEquals(320, svgWidth(svg), "single-series legend is ignored — canvas unchanged");
    }

    @Test
    void barsGoldenPathStaysByteIdenticalShape() {
        // The pinned single-series bar chart still bakes to the exact legacy shape (series == null).
        String svg = Sirentide.render("xychart\n\"Mon\" : 5\n\"Tue\" : 8\n\"Wed\" : 3\n");
        assertEquals(3, count(svg, "<rect"), "one bar per row, legacy path");
        assertEquals(320, svgWidth(svg), "legacy canvas width unchanged");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    private static double svgWidth(String svg) {
        int i = svg.indexOf("width=\"") + 7;
        return Double.parseDouble(svg.substring(i, svg.indexOf('"', i)));
    }
}
