package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Theme-adaptive off-slice text (plan 6d029933). All text drawn on the PAGE background — pie
/// legend/outside labels, xychart category/tick/value labels, timeline event+year labels, gantt
/// task labels — defaults to `currentColor` so it inherits the host page's text colour (legible on
/// light AND dark). An optional `color=<value>` header modifier overrides it; an illegal colour
/// falls back to the default without failing the bake. The ON-slice pie contrast labels (black/
/// white by slice luminance) sit on a coloured slice and are DELIBERATELY untouched.
class TextColorTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void bareDiagramOffSliceTextDefaultsToCurrentColor() {
        String svg = Sirentide.render("xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n");
        assertTrue(count(svg, "fill=\"currentColor\"") >= 1,
            "off-slice labels inherit the page colour via currentColor");
        assertEquals(0, count(svg, "#334155"), "the old fixed dark-slate fill is gone");
    }

    @Test
    void colorModifierOverridesOffSliceText() {
        String svg = Sirentide.render("xychart color=#ff8800\n  \"Mon\" : 5\n  \"Tue\" : 8\n");
        assertTrue(count(svg, "fill=\"#ff8800\"") >= 1, "off-slice text takes the color= override");
        assertEquals(0, count(svg, "fill=\"currentColor\""),
            "no off-slice label falls back to the default once overridden");
    }

    @Test
    void invalidColorFallsBackToCurrentColorWithoutThrowing() {
        String svg = Sirentide.render("xychart color=notacolor\n  \"Mon\" : 5\n  \"Tue\" : 8\n");
        assertTrue(svg.startsWith("<svg"), "an illegal colour never fails the bake");
        assertTrue(count(svg, "fill=\"currentColor\"") >= 1, "it degrades to the currentColor default");
        assertEquals(0, count(svg, "notacolor"), "the bad token never reaches the output");
    }

    @Test
    void threeDigitHexIsExpandedToSixDigit() {
        // The contract COLOR grammar now ACCEPTS a 3-digit `#rgb` shorthand for INPUT and EXPANDS it
        // to canonical `#rrggbb` before it reaches the emitter — so `color=#333` renders `#333333`
        // (never the out-of-grammar short form).
        String svg = Sirentide.render("xychart color=#333\n  \"Mon\" : 5\n  \"Tue\" : 8\n");
        assertTrue(count(svg, "fill=\"#333333\"") >= 1, "3-digit hex expands to 6-digit on the wire");
        assertEquals(0, count(svg, "fill=\"#333\""), "the short hex is never emitted");
        assertEquals(0, count(svg, "fill=\"currentColor\""), "the override replaces the default");
    }

    @Test
    void onSlicePieContrastLabelsAreUnchangedByTheOverride() {
        // "Big" fills a comfortable slice → an ON-slice contrast label (black/white by luminance),
        // which must ignore the color= override. The thin slices spill OUTSIDE onto the override.
        String svg = Sirentide.render("""
            pie color=#ff8800
              "Big"   : 96
              "Tiny1" : 1
              "Tiny2" : 1
              "Small" : 2
            """);
        assertTrue(count(svg, "fill=\"#ffffff\"") + count(svg, "fill=\"#000000\"") >= 1,
            "the on-slice contrast label stays black/white, not the override");
        assertTrue(count(svg, "fill=\"#ff8800\"") >= 1,
            "the outside (off-slice) labels DO take the override");
    }

    @Test
    void colorModifierIsOrderIndependentWithLegend() {
        String a = Sirentide.render("pie legend color=#00ff00\n  \"A\" : 1\n  \"B\" : 1\n");
        String b = Sirentide.render("pie color=#00ff00 legend\n  \"A\" : 1\n  \"B\" : 1\n");
        // Both parse legend (two swatch rects) AND the colour override (green legend text).
        assertEquals(2, count(a, "<rect"), "legend still parsed when color= precedes it");
        assertEquals(2, count(b, "<rect"), "legend still parsed when color= follows it");
        assertTrue(count(a, "fill=\"#00ff00\"") >= 1 && count(b, "fill=\"#00ff00\"") >= 1,
            "the legend text takes the override regardless of modifier order");
    }

    @Test
    void currentColorAndNoneAreAcceptedExplicitly() {
        String cc = Sirentide.render("timeline color=currentColor\n  \"Founded\" : 2020\n");
        assertTrue(count(cc, "fill=\"currentColor\"") >= 1, "explicit currentColor is honoured");
        String none = Sirentide.render("gantt color=none\n  \"Design\" : 0-3\n");
        assertTrue(count(none, "fill=\"none\"") >= 1, "`none` is a legal contract colour");
    }
}
