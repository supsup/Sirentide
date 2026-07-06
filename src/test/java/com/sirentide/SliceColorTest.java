package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Per-item fill colours (plan d8d16ff6). A data line `"label" : value` may carry an OPTIONAL
/// trailing `#hex` token that overrides the default palette for THAT item only, across all four
/// diagram types (pie slice, xychart bar, timeline dot, gantt bar). An item without a token keeps
/// its palette-by-index colour (the original behaviour). A 3-digit shorthand is expanded to
/// canonical `#rrggbb`; an invalid trailing token degrades to the palette without failing the bake.
class SliceColorTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // Palette entry 0 is #4e79a7, entry 1 is #f28e2b — used to prove the un-tokened item keeps its
    // palette colour while the tokened item takes its override (a colour NOT in the palette).

    @Test
    void pieSliceTakesTheExplicitColorAndSiblingsKeepThePalette() {
        String svg = Sirentide.render("""
            pie
              "Reviews" : 40 #123456
              "Builds"  : 30
              "Docs"    : 30
            """);
        assertTrue(count(svg, "fill=\"#123456\"") >= 1, "the tokened slice's wedge uses the override");
        assertTrue(count(svg, "fill=\"#f28e2b\"") >= 1, "the un-tokened slice keeps its palette colour");
    }

    @Test
    void xychartBarTakesTheExplicitColorAndSiblingsKeepThePalette() {
        String svg = Sirentide.render("""
            xychart
              "Mon" : 5 #123456
              "Tue" : 8
            """);
        assertTrue(count(svg, "fill=\"#123456\"") >= 1, "the tokened bar uses the override");
        assertTrue(count(svg, "fill=\"#f28e2b\"") >= 1, "the un-tokened bar keeps its palette colour");
    }

    @Test
    void timelineDotTakesTheExplicitColor() {
        String svg = Sirentide.render("""
            timeline
              "Founded"  : 2020 #123456
              "Series A" : 2021
            """);
        assertTrue(count(svg, "fill=\"#123456\"") >= 1, "the tokened dot uses the override");
    }

    @Test
    void ganttRangeAndTrailingColorCoexist() {
        String svg = Sirentide.render("""
            gantt
              "Design" : 0-3 #abcdef
              "Build"  : 3-8
            """);
        assertEquals(2, count(svg, "<rect"), "both task ranges still parse into bars");
        assertTrue(count(svg, "fill=\"#abcdef\"") >= 1, "the tokened bar uses the override");
        assertTrue(count(svg, "fill=\"#f28e2b\"") >= 1, "the un-tokened bar keeps its palette colour");
    }

    @Test
    void ganttIsoDateRangeWithTrailingColorParsesBoth() {
        // ISO-date range uses the ` - ` delimiter; the trailing colour is peeled off the END first,
        // so the delimiter and the dashes inside the dates are untouched.
        String svg = Sirentide.render("""
            gantt
              "Phase" : 2020-01-01 - 2020-06-01 #abcdef
            """);
        assertEquals(1, count(svg, "<rect"), "the ISO date range still parses into one bar");
        assertTrue(count(svg, "fill=\"#abcdef\"") >= 1, "the trailing colour is applied to the bar");
    }

    @Test
    void threeDigitPerItemHexIsExpandedToSixDigit() {
        String svg = Sirentide.render("""
            pie
              "A" : 50 #333
              "B" : 50
            """);
        assertTrue(count(svg, "fill=\"#333333\"") >= 1, "3-digit per-item hex expands to 6-digit");
        assertEquals(0, count(svg, "fill=\"#333\""), "the short form is never emitted");
    }

    @Test
    void invalidTrailingTokenDegradesToPaletteWithoutThrowing() {
        String svg = Sirentide.render("""
            pie
              "A" : 50 notacolor
              "B" : 50
            """);
        assertTrue(svg.startsWith("<svg"), "an illegal trailing token never fails the bake");
        assertEquals(0, count(svg, "notacolor"), "the bad token never reaches the output");
        // Both slices still render — the value parses and each falls back to its palette colour.
        assertTrue(count(svg, "fill=\"#4e79a7\"") >= 1 && count(svg, "fill=\"#f28e2b\"") >= 1,
            "both slices degrade to the default palette");
    }

    @Test
    void malformedColorHexIsIgnoredNotEmitted() {
        // A `#`-prefixed token that is not a legal 3/6-digit hex is not a colour → palette fallback.
        String svg = Sirentide.render("pie\n  \"A\" : 50 #12x\n  \"B\" : 50\n");
        assertTrue(svg.startsWith("<svg"), "never fails the bake");
        assertEquals(0, count(svg, "#12x"), "the malformed hex never reaches output");
        assertTrue(count(svg, "fill=\"#4e79a7\"") >= 1, "the item degrades to its palette colour");
    }

    @Test
    void h1_perItemCurrentColorOrNoneNeverBlanksThePie() {
        // REGRESSION (H1, deep-review sirentide/14): per-item `currentColor`/`none` are legal in the
        // COLOR grammar but meaningless as a wedge fill — they used to reach contrastFill's
        // Integer.parseInt, throw, and collapse the WHOLE diagram to a 0x0 inert shell (siblings and
        // all). Per-item colours are now hex-only at the parse boundary, so both fall through to the
        // palette and the pie renders normally. The exact input Confluence reproduced against the jar.
        for (String bad : new String[] {"currentColor", "none"}) {
            String svg = Sirentide.render("pie\n  \"A\" : 60 " + bad + "\n  \"B\" : 40\n");
            assertTrue(svg.startsWith("<svg"), bad + ": still renders");
            assertEquals(0, count(svg, "width=\"0\" height=\"0\""), bad + ": NOT the 0x0 inert shell");
            assertTrue(count(svg, "<path") >= 2, bad + ": both wedges are drawn");
            assertEquals(0, count(svg, bad), bad + ": the meaningless token never reaches output");
            assertTrue(count(svg, "fill=\"#4e79a7\"") >= 1, bad + ": the item falls back to its palette colour");
        }
    }
}
