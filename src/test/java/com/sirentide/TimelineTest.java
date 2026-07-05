package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Sirentide's third diagram type: a timeline renders an axis (line), an event dot per row
/// (full-circle disc), and a label + value per event as glyph paths.
class TimelineTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void axisDotsAndLabels() {
        String svg = Sirentide.render("""
            timeline
              "Founded"  : 2020
              "Series A" : 2021
              "Launch"   : 2023
            """);
        assertEquals(1, count(svg, "<line"), "one horizontal axis");
        assertEquals(6, count(svg, " A "), "3 event dots, each a two-arc disc");
        assertEquals(9, count(svg, "<path"), "3 dots + 3 labels + 3 values");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    @Test
    void outputIsContractClean() {
        String svg = Sirentide.render("timeline\n \"A\" : 1\n \"B\" : 2\n");
        assertFalse(svg.contains("<script"), "no script");
        assertFalse(svg.contains("<style"), "no style");
        assertFalse(svg.contains("foreignObject"), "no foreignObject");
        assertFalse(svg.contains("href"), "no href");
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "timeline\n \"A\" : 1\n \"B\" : 2\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void emptyTimelineDrawsOnlyTheAxis() {
        String svg = Sirentide.render("timeline\n");
        assertEquals(1, count(svg, "<line"), "axis still drawn");
        assertEquals(0, count(svg, " A "), "no event dots");
    }
}
