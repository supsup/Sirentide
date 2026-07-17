package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// The comparison / verdict matrix type (plan sirentide-comparison-matrix-type): `cols:` headers +
/// `"row" : v1, v2` verdict cells, each cell filled from the CLOSED four-value palette
/// (pass/fail/partial/na → green/red/amber/neutral), token baked to a glyph path. Covers the palette
/// mapping (the load-bearing claim), alias normalization, rectangularization, the fail-closed unknown
/// token, and the a11y description.
class MatrixTest {

    private static final String PASS = "#dcfce7";
    private static final String FAIL = "#fecaca";
    private static final String PARTIAL = "#fef9c3";
    private static final String NA = "#f1f5f9";

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void verdictCellsMapToTheClosedPalette() {
        // One row carrying all four verdicts → the SVG must carry all four distinct palette fills.
        // This is the load-bearing claim: dropping/merging the palette map (mutant) collapses the
        // colours and fails these four assertions.
        String svg = Sirentide.render(
            "matrix\ncols: a, b, c, d\n\"row\" : pass, fail, partial, na");
        assertTrue(count(svg, "fill=\"" + PASS + "\"") >= 1, "a pass cell fills green " + PASS);
        assertTrue(count(svg, "fill=\"" + FAIL + "\"") >= 1, "a fail cell fills red " + FAIL);
        assertTrue(count(svg, "fill=\"" + PARTIAL + "\"") >= 1, "a partial cell fills amber " + PARTIAL);
        // NA also fills the row-label column, so >=2; the point is it's the neutral, not a colour.
        assertTrue(count(svg, "fill=\"" + NA + "\"") >= 1, "an na cell fills neutral " + NA);
    }

    @Test
    void verdictAliasesNormalizeToTheSameFills() {
        // match/yes → pass (green); diverge/no → fail (red); ~ → partial (amber). The docs house style
        // uses match/diverge, so the aliases are load-bearing for the real conversions.
        String svg = Sirentide.render(
            "matrix\ncols: x, y\n\"r1\" : match, diverge\n\"r2\" : yes, no\n\"r3\" : ~, na");
        assertTrue(count(svg, "fill=\"" + PASS + "\"") >= 2, "match + yes both map to green");
        assertTrue(count(svg, "fill=\"" + FAIL + "\"") >= 2, "diverge + no both map to red");
        assertTrue(count(svg, "fill=\"" + PARTIAL + "\"") >= 1, "~ maps to amber");
    }

    @Test
    void unknownTokenIsNeutralNeverANewColour() {
        // A fail-closed guarantee: an unrecognized cell token becomes NA (neutral), so no free-form
        // colour is ever introduced by the cell vocabulary (the value-constraint the sanitizer story
        // rests on). The only non-palette, non-header colours would be a bug.
        String svg = Sirentide.render("matrix\ncols: a\n\"row\" : wat");
        assertTrue(count(svg, "fill=\"" + NA + "\"") >= 1, "an unknown token falls to the neutral fill");
        assertEquals(0, count(svg, "fill=\"" + PASS + "\""), "unknown never becomes pass-green");
        assertEquals(0, count(svg, "fill=\"" + FAIL + "\""), "unknown never becomes fail-red");
    }

    @Test
    void shortRowIsRectangularizedToTheHeaderColumnCount() {
        // A row with fewer cells than the header pads to M with neutral cells, so every row is M wide
        // (the grid can't go ragged). With 3 columns and a 1-cell row, the row gets 2 pad NA cells.
        String svg = Sirentide.render("matrix\ncols: a, b, c\n\"only-one\" : pass");
        // pass green (1) + two padded NA data cells; plus the NA-filled row-label column cell.
        assertTrue(count(svg, "fill=\"" + PASS + "\"") == 1, "the single authored pass cell");
        assertTrue(count(svg, "fill=\"" + NA + "\"") >= 3,
            "two padded NA data cells + the row-label cell keep the row rectangular");
    }

    @Test
    void textColonVerdictShowsTheTextButColoursByTheVerdict() {
        // The `display text:verdict` cell shape (11-operate-clone-replay's descriptive cells): the word
        // before the last colon is shown, the token after picks the fill. So "HELD:pass" is a green
        // cell reading "HELD"; "would proceed:fail" is a red cell reading "would proceed".
        String svg = Sirentide.render(
            "matrix\ncols: clone, decoy\n\"O1\" : HELD:pass, would proceed:fail");
        assertTrue(count(svg, "fill=\"" + PASS + "\"") >= 1, "HELD:pass fills the cell green");
        assertTrue(count(svg, "fill=\"" + FAIL + "\"") >= 1, "would proceed:fail fills the cell red");
        // The a11y desc carries the normalized verdicts, proving the colour came from the token, not
        // the (unrecognized-as-a-verdict) display text.
        assertTrue(svg.contains("O1: pass, fail"), "the verdict, not the display text, drives the fill");
    }

    @Test
    void blankMatrixStillRendersAValidSvg() {
        // A bare `matrix` (no cols, no rows) must bake a well-formed (small) SVG, never a crash or a
        // degenerate 0x0 (the never-throw bake contract).
        String svg = Sirentide.render("matrix");
        assertTrue(svg.startsWith("<svg") && svg.trim().endsWith("</svg>"), "well-formed empty matrix");
        assertTrue(svg.contains("width=") && svg.contains("height="), "has explicit width/height");
    }

    @Test
    void matrixEmitsAComparisonMatrixA11yDescription() {
        // The a11y desc reads the grid aloud: dimensions, columns, then each row's verdicts. A screen
        // reader gets the whole matrix without seeing colour.
        String svg = Sirentide.render(
            "matrix\ncols: snapshot, bare\n\"PC1 soft-intent\" : partial, diverge");
        assertTrue(svg.contains("Comparison matrix with 1 row and 2 columns"),
            "desc names the dimensions");
        assertTrue(svg.contains("snapshot") && svg.contains("bare"), "desc names the columns");
        assertTrue(svg.contains("partial, fail"), "desc reads the row's normalized verdicts (diverge→fail)");
    }

    @Test
    void aPathologicallyWideMatrixIsColumnCapped() {
        // Robustness plan fe8c5bbc #4: rows are bounded by MAX_DATA_ROWS but the `cols:` header token
        // count AND each row's cell count were uncapped, so `cols: c,c,…×N` + an N-cell row forced an
        // N-wide grid that OOMs in layout before the 5 MB emit cap can fire. N=1000 here sits well
        // above MAX_COLUMNS (200) and well below the emit cap, so the assertion pins the COLUMN cap
        // specifically: without it there'd be 1000 pass cells; with it, exactly MAX_COLUMNS.
        int n = 1000;
        StringBuilder src = new StringBuilder("matrix\ncols:");
        StringBuilder row = new StringBuilder("\n\"r\" :");
        for (int i = 0; i < n; i++) {
            src.append(" c,");
            row.append(" pass,");
        }
        String svg = Sirentide.render(src.append(row).toString());
        // The load-bearing, mutation-surviving assertion: exactly MAX_COLUMNS pass cells. Without the
        // cap there'd be N (or a degraded/truncated count once the ~5 MB emit cap fires) — never 200.
        assertEquals(com.sirentide.parse.DslParser.MAX_COLUMNS, count(svg, "fill=\"" + PASS + "\""),
            "the wide matrix is column-capped to exactly MAX_COLUMNS pass cells, not " + n);
    }
}
