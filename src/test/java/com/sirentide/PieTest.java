package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Sirentide's first real diagram: a pie renders DSL → IR → arithmetic layout → contract-clean
/// SVG wedges, end to end.
class PieTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void oneWedgeAndOneLabelPerPositiveSlice() {
        String svg = Sirentide.render("""
            pie
              "Reviews" : 40
              "Builds"  : 30
              "Docs"    : 30
            """);
        assertEquals(6, count(svg, "<path"), "3 wedges + 3 label paths");
        // Labels are contrast-aware now (black OR white by slice luminance), not hardcoded white:
        // #4e79a7 (dark) → white; #f28e2b, #59a14f (light) → black. So both fills appear.
        assertTrue(count(svg, "fill=\"#ffffff\"") >= 1 && count(svg, "fill=\"#000000\"") >= 1,
            "labels use contrast-picked black AND white, not a single hardcoded colour");
        assertEquals(0, count(svg, "<line"), "no thin slices here → no leader lines");
        assertTrue(svg.contains("viewBox="), "has a viewBox");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    // Contract-cleanliness is now enforced by ContainmentTest's allowlist guard (see that class),
    // not a per-type denylist here.

    @Test
    void singleSliceRendersAFullDisc() {
        String svg = Sirentide.render("pie\n \"All\" : 5\n");
        // Full-circle form draws two arcs (no degenerate centre wedge); the label adds no arcs.
        assertEquals(2, count(svg, " A "), "disc drawn as two semicircle arcs");
        assertEquals(1, count(svg, "fill=\"#ffffff\""), "one label for the one slice");
    }

    @Test
    void malformedRowsDegradeAndAreSkipped() {
        String svg = Sirentide.render("""
            pie
              "good" : 10
              this line has no colon
              "bad"  : notanumber
            """);
        assertEquals(1, count(svg, "fill=\"#ffffff\""), "one label => one valid slice; bad rows skipped");
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "pie\n \"A\" : 3\n \"B\" : 7\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void unrecognizedDslDegradesToEmptyShell() {
        String svg = Sirentide.render("flowchart TD; A-->B");
        assertEquals(0, count(svg, "<path"), "unknown diagram type degrades to an empty shell");
        assertTrue(svg.startsWith("<svg"), "still a valid svg");
    }
}
