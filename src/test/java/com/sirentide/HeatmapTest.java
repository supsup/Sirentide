package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// The continuous-score heatmap type (plan sirentide-heatmap-type): matrix's grid grammar with
/// 0..1 magnitude cells filled from a SINGLE-HUE sequential ramp (light→dark blue) plus a sampled
/// ramp legend. Covers the ramp mapping at its three exact stops (the load-bearing claim), the
/// percent/display-override/clamp token shapes, the NA fail-closed neutral, rectangularization,
/// the contrast flip on the dark end, the legend's presence, the a11y description, and the
/// column cap.
class HeatmapTest {

    // The ramp's three exact stops (piecewise-linear between them) + the neutral NA fill.
    private static final String LO = "#eff6ff";
    private static final String MID = "#93c5fd";
    private static final String HI = "#1e40af";
    private static final String NA = "#f1f5f9";

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void valuesMapOntoTheSequentialRampStops() {
        // 0 / 0.5 / 1 are the ramp's exact stops — the load-bearing claim: dropping or reordering
        // the piecewise lerp (mutant) moves at least one of these three fills.
        String svg = Sirentide.render(
            "heatmap\ncols: a, b, c\n\"row\" : 0, 0.5, 1");
        assertTrue(count(svg, "fill=\"" + LO + "\"") >= 1, "value 0 fills the light end " + LO);
        assertTrue(count(svg, "fill=\"" + MID + "\"") >= 1, "value 0.5 fills the mid stop " + MID);
        assertTrue(count(svg, "fill=\"" + HI + "\"") >= 1, "value 1 fills the dark end " + HI);
    }

    @Test
    void percentTokensAndClampingReachTheSameRamp() {
        // `50%` is 0.5; out-of-range honest values clamp to the ramp ends instead of vanishing:
        // 1.7 → the dark end, -0.3 → the light end (clamp-not-refuse keeps them visible).
        String svg = Sirentide.render(
            "heatmap\ncols: a, b, c\n\"row\" : 50%, 1.7, -0.3");
        assertTrue(count(svg, "fill=\"" + MID + "\"") >= 1, "50% parses to the 0.5 mid stop");
        assertTrue(count(svg, "fill=\"" + HI + "\"") >= 1, "1.7 clamps to the dark end");
        assertTrue(count(svg, "fill=\"" + LO + "\"") >= 1, "-0.3 clamps to the light end");
    }

    @Test
    void nonNumericTokenIsNeutralNeverOnTheRamp() {
        // Fail-closed like matrix's unknown verdict: `-`, `na`, and free text are NA (neutral) —
        // no colour is ever introduced by an unparseable token, and 0 stays a REAL ramp value
        // (the NA flag, not the number, discriminates).
        String svg = Sirentide.render("heatmap\ncols: a, b, c\n\"row\" : -, na, wat");
        assertEquals(0, count(svg, "fill=\"" + LO + "\""), "an NA cell never reads as a literal 0");
        assertEquals(0, count(svg, "fill=\"" + HI + "\""), "an NA cell never reaches the ramp");
        assertTrue(count(svg, "fill=\"" + NA + "\"") >= 3, "all three tokens fall to the neutral fill");
    }

    @Test
    void displayOverrideShowsTheTextButColoursByTheValue() {
        // The `display text:value` shape (matrix parity, split on the LAST colon): "warm:1" is a
        // dark-end cell reading "warm". The a11y desc carries the authored token, proving the
        // display text survived while the value drove the fill.
        String svg = Sirentide.render("heatmap\ncols: a\n\"row\" : warm:1");
        assertTrue(count(svg, "fill=\"" + HI + "\"") >= 1, "warm:1 fills the dark end");
        assertTrue(svg.contains("row: warm"), "the a11y desc reads the display text");
    }

    @Test
    void darkCellsFlipTheirLabelToWhite() {
        // The contrastFill rule composes with the ramp unchanged: a value-1 cell (deep blue) bakes
        // its token in white; a value-0 cell (near-white) bakes in black.
        String svg = Sirentide.render("heatmap\ncols: a, b\n\"row\" : 0, 1");
        assertTrue(count(svg, "fill=\"#ffffff\"") >= 1, "the dark-end cell's label flips to white");
        assertTrue(count(svg, "fill=\"#000000\"") >= 1, "the light-end cell's label stays black");
    }

    @Test
    void shortRowIsRectangularizedToTheHeaderColumnCount() {
        // Matrix parity: with 3 columns and a 1-cell row, the row pads with 2 NA cells.
        String svg = Sirentide.render("heatmap\ncols: a, b, c\n\"only-one\" : 1");
        assertEquals(1, count(svg, "fill=\"" + HI + "\""), "the single authored value-1 cell");
        assertTrue(count(svg, "fill=\"" + NA + "\"") >= 3,
            "two padded NA data cells + the row-label cell keep the row rectangular");
    }

    @Test
    void theRampLegendIsAlwaysDrawn() {
        // 1 backing + 2 header cells (corner + 1 column) + 1 row-label + 1 data cell + the 12
        // sampled legend steps = 17 rects. Pinning the exact count is what makes the legend
        // load-bearing: dropping it (mutant) is -12.
        String svg = Sirentide.render("heatmap\ncols: a\n\"r\" : 0.5");
        assertEquals(17, count(svg, "<rect"), "grid rects + exactly 12 legend step rects");
    }

    @Test
    void everyDataCellIsAnchoredRowMajorAndNothingElseIs() {
        // Matrix's exact anchor scheme: an N×M heatmap emits exactly N·M `role="cell"` groups with
        // coordinate-derived ids — the header band, label column, and LEGEND stay un-anchored (the
        // legend is structural; anchoring it would make the FX layer reveal chrome).
        String svg = Sirentide.render(
            "heatmap\ncols: a, b, c\n\"r1\" : 0.1, 0.5, 0.9\n\"r2\" : -, 0.4, 1");
        assertEquals(6, count(svg, "data-sirentide-role=\"cell\""), "exactly N·M anchored cells");
        assertTrue(svg.contains("data-sirentide-id=\"r0c0\"") && svg.contains("data-sirentide-id=\"r1c2\""),
            "ids are coordinate-derived (r<row>c<col>), not label-derived");
    }

    @Test
    void scaleDirectiveNamesTheLegendEndsInTheA11yDesc() {
        // `scale: "diverged" --> "reproduced"` labels the legend's ends; the a11y desc speaks them
        // (the visual glyphs are paths, so the desc is where the labels are assertable — and where
        // a screen reader actually gets them).
        String svg = Sirentide.render(
            "heatmap\ncols: bare, card\nscale: \"diverged\" --> \"reproduced\"\n\"PC1\" : 0.60, 0.95");
        assertTrue(svg.contains("scale diverged to reproduced"), "desc names both scale ends");
    }

    @Test
    void heatmapEmitsAHeatmapA11yDescription() {
        // The a11y desc reads the grid aloud with the AUTHORED tokens (what a sighted reader sees
        // in the cells), so a screen reader gets the magnitudes without seeing colour.
        String svg = Sirentide.render(
            "heatmap\ncols: bare, snapshot, card\n\"values-boundary\" : 0.60, 0.75, 0.95");
        assertTrue(svg.contains("Heatmap with 1 row and 3 columns"), "desc names the dimensions");
        assertTrue(svg.contains("values-boundary: 0.60, 0.75, 0.95"), "desc reads the authored tokens");
    }

    @Test
    void blankHeatmapStillRendersAValidSvg() {
        // A bare `heatmap` must bake a well-formed (small) SVG, never a crash or a degenerate 0x0
        // (the never-throw bake contract).
        String svg = Sirentide.render("heatmap");
        assertTrue(svg.startsWith("<svg") && svg.trim().endsWith("</svg>"), "well-formed empty heatmap");
        assertTrue(svg.contains("width=") && svg.contains("height="), "has explicit width/height");
    }

    @Test
    void aPathologicallyWideHeatmapIsColumnCapped() {
        // Matrix's cap discipline (robustness fe8c5bbc #4) applies unchanged: a 1000-column header
        // + 1000-cell row is bounded to exactly MAX_COLUMNS data cells, never an OOM-wide grid.
        int n = 1000;
        StringBuilder src = new StringBuilder("heatmap\ncols:");
        StringBuilder row = new StringBuilder("\n\"r\" :");
        for (int i = 0; i < n; i++) {
            src.append(" c,");
            row.append(" 1,");
        }
        String svg = Sirentide.render(src.append(row).toString());
        assertEquals(com.sirentide.parse.DslParser.MAX_COLUMNS, count(svg, "fill=\"" + HI + "\""),
            "the wide heatmap is column-capped to exactly MAX_COLUMNS value-1 cells, not " + n);
    }
}
