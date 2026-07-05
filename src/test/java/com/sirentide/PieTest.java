package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(3, count(svg, "fill=\"#ffffff\""), "one (white) label path per slice");
        assertTrue(svg.contains("viewBox="), "has a viewBox");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    @Test
    void outputIsContractClean() {
        String svg = Sirentide.render("pie\n \"A\" : 1\n \"B\" : 1\n");
        assertFalse(svg.contains("<script"), "no script");
        assertFalse(svg.contains("<style"), "no style");
        assertFalse(svg.contains("foreignObject"), "no foreignObject");
        assertFalse(svg.contains("href"), "no href");
        assertFalse(svg.contains(" on"), "no on* handlers");
    }

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
