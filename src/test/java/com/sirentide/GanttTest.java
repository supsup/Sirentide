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

    @Test
    void h3_degenerateDomainStillDrawsTheTasks() {
        // REGRESSION (H3, deep-review sirentide/14): a degenerate time domain (single milestone,
        // all-equal instants, or a lone reversed range) is NOT empty — the data is present and must
        // render. The old guard bailed on `domainMax <= domainMin` → a blank chart with data.
        // Now: bail only on n==0; AxisScale collapses/swaps the domain and each task gets its
        // minVisibleW marker bar. Loud-not-silent.
        record Case(String name, String dsl, int tasks) {}
        for (Case c : new Case[] {
            new Case("single milestone", "gantt\n  \"M\" : 5-5\n", 1),
            new Case("all-equal instants", "gantt\n  \"A\" : 5-5\n  \"B\" : 5-5\n", 2),
            new Case("lone reversed range", "gantt\n  \"R\" : 8-3\n", 1),
        }) {
            String svg = Sirentide.render(c.dsl());
            assertTrue(svg.startsWith("<svg"), c.name() + ": renders");
            assertEquals(c.tasks(), count(svg, "<rect"), c.name() + ": a marker bar per task (not blank)");
            assertEquals(c.tasks(), count(svg, "<path"), c.name() + ": a label per task");
            assertEquals(1, count(svg, "<line"), c.name() + ": the axis line");
        }
    }

    // Contract-cleanliness is enforced by ContainmentTest's allowlist guard (covers gantt too).

    @Test
    void renderIsDeterministic() {
        String dsl = "gantt\n \"A\" : 0-3\n \"B\" : 2-5\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }
}
