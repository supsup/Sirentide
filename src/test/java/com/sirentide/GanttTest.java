package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Sirentide's fourth diagram type: a gantt chart renders one bar per task (span → x/width on a
/// shared time axis), a left-aligned label per task, and a baseline axis line.
class GanttTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void barsLabelsAndAxis() {
        String svg = Sirentide.render("""
            gantt
              "Design" : 0-3
              "Build"  : 3-8
              "Test"   : 7-10
            """);
        assertEquals(3, count(svg, "<rect"), "one bar per task");
        assertEquals(1, count(svg, "<line"), "the time axis");
        assertEquals(3, count(svg, "<path"), "one label per task");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    @Test
    void malformedRangeIsSkipped() {
        String svg = Sirentide.render("""
            gantt
              "ok"  : 0-2
              "bad" : notarange
            """);
        assertEquals(1, count(svg, "<rect"), "the malformed row is dropped");
    }

    // Contract-cleanliness is enforced by ContainmentTest's allowlist guard (covers gantt too).

    @Test
    void renderIsDeterministic() {
        String dsl = "gantt\n \"A\" : 0-3\n \"B\" : 2-5\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }
}
