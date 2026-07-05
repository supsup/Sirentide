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
        assertEquals(2, count(svg, "<line"), "x-axis + y-axis");
        assertEquals(6, count(svg, "<path"), "a category label + a value label per bar");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    @Test
    void tallestBarReachesFullPlotHeight() {
        // Tue = 8 is the max, so its bar spans the full 180px plot height.
        String svg = Sirentide.render("xychart\n \"a\" : 4\n \"b\" : 8\n");
        assertTrue(svg.contains("height=\"180\""), "the max-value bar reaches the plot top");
    }

    @Test
    void outputIsContractClean() {
        String svg = Sirentide.render("xychart\n \"A\" : 1\n \"B\" : 2\n");
        assertFalse(svg.contains("<script"), "no script");
        assertFalse(svg.contains("<style"), "no style");
        assertFalse(svg.contains("foreignObject"), "no foreignObject");
        assertFalse(svg.contains("href"), "no href");
    }

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
}
