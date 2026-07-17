package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Sirentide's second diagram type: a bar chart renders DSL → IR → arithmetic layout → axes
/// (lines) + bars (rects) + labels (glyph paths), end to end.
class XyChartTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void barsAxesAndLabels() {
        String svg = Sirentide.render("""
            xychart
              "Mon" : 5
              "Tue" : 8
              "Wed" : 3
            """);
        assertEquals(3, count(svg, "<rect"), "one bar per row");
        // x-axis + y-axis + one nice tick mark per y-scale stop (the y-scale that used to be absent).
        assertTrue(count(svg, "<line") > 2, "axes PLUS y-axis tick marks (a real y-scale now)");
        // per-bar category + value labels, PLUS a numeric label per y-tick.
        assertTrue(count(svg, "<path") > 6, "bar labels plus y-scale tick labels");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    @Test
    void tallestBarReachesFullPlotHeight() {
        // Tue = 8 is the max, so its bar spans the full 180px plot height.
        String svg = Sirentide.render("xychart\n \"a\" : 4\n \"b\" : 8\n");
        assertTrue(svg.contains("height=\"180\""), "the max-value bar reaches the plot top");
    }

    // Contract-cleanliness is enforced by ContainmentTest's allowlist guard (covers xychart too).

    @Test
    void renderIsDeterministic() {
        String dsl = "xychart\n \"A\" : 3\n \"B\" : 7\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void emptyXychartStillDrawsAxes() {
        String svg = Sirentide.render("xychart\n");
        assertEquals(2, count(svg, "<line"), "axes render even with no bars");
        assertEquals(0, count(svg, "<rect"), "no bars");
    }

    @Test
    void negativeValueDoesNotEmitInvalidRect() {
        // A mixed-sign dataset now uses a SIGNED domain with a zero baseline: the negative bar
        // DESCENDS below the baseline (a positive-height rect placed lower), it is not clamped to
        // zero (which used to silently blank an all-negative chart). Never an invalid negative rect.
        String svg = Sirentide.render("xychart\n \"A\" : 5\n \"B\" : -3\n");
        assertFalse(svg.contains("height=\"-"), "no negative-height rect");
        assertEquals(2, count(svg, "<rect"), "both bars drawn (the negative one descends, not blank)");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "still well-formed");
    }

    @Test
    void aPathologicallyManySeriesChartIsSeriesCapped() {
        // Robustness plan fe8c5bbc #5: each numeric value token in a row is a SERIES, and both the
        // per-row token count and the `series:` name row were uncapped — `"r" : 1 1 …×N` grew
        // maxSeries to N → N legend rows + N bars. N=500 sits above MAX_SERIES (100) and below the
        // 5 MB emit cap; in bars mode each bar is exactly one <rect> (no structural rects), so the
        // bar count IS the series count. Without the cap there'd be 500 bars; with it, MAX_SERIES.
        int n = 500;
        StringBuilder src = new StringBuilder("xychart\n\"r\" :");
        for (int i = 0; i < n; i++) {
            src.append(" 1");
        }
        String svg = Sirentide.render(src.toString());
        assertEquals(com.sirentide.parse.DslParser.MAX_SERIES, count(svg, "<rect"),
            "the many-series chart is capped to exactly MAX_SERIES bars, not " + n);
    }
}
