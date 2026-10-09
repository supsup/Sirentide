package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The heatmap extensions of plan f4d69e44 (heatmap outlined cells and categorical palette):
/// the `!` outline suffix, the `palette:` categorical mode, the `ramp:` custom sequential ramp,
/// `bins:` stepped fills + stepped legend, and `hide:` for the row-label column / header band.
/// Every assertion here is about a fill, stroke, or rect count that the pre-feature renderer
/// could not produce, so each test is red against the pre-feature parser/layout.
class HeatmapExtensionsTest {

    // The default ramp's exact stops + the neutral NA / row-label fill + the header fill.
    private static final String LO = "#eff6ff";
    private static final String MID = "#93c5fd";
    private static final String HI = "#1e40af";
    private static final String NA = "#f1f5f9";
    private static final String HEADER = "#e2e8f0";

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /// Rects (cells, swatches, legend steps) filled with `hex` — glyph runs are paths, so a cell
    /// label drawn in the same colour never inflates the count.
    private static int rects(String svg, String hex) {
        Matcher m = Pattern.compile("<rect [^>]*fill=\"" + Pattern.quote(hex) + "\"").matcher(svg);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String desc(String svg) {
        Matcher m = Pattern.compile("<desc>(.*?)</desc>", Pattern.DOTALL).matcher(svg);
        return m.find() ? m.group(1) : "";
    }

    private static double width(String svg) {
        Matcher m = Pattern.compile("<svg[^>]* width=\"([0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg has a width");
        return Double.parseDouble(m.group(1));
    }

    // ---- outline (`!` suffix) ---------------------------------------------------------------

    @Test
    void anOutlineSuffixStrokesExactlyThatCellAndKeepsItsValue() {
        // Two identical values, one outlined: both keep the 0.5 mid-stop fill (the `!` is not part
        // of the value), and exactly ONE rect in the whole SVG carries a stroke.
        String svg = Sirentide.render("heatmap\ncols: a, b\n\"r\" : 0.5!, 0.5");
        assertEquals(2, rects(svg, MID), "both cells stay on the 0.5 stop");
        assertEquals(1, count(svg, "stroke="), "only the outlined cell is stroked");
        assertTrue(svg.contains("fill=\"" + MID + "\" stroke=\"#000000\""),
            "the outline is drawn ON the cell's own fill rect, black on a light fill");
    }

    @Test
    void anOutlineOnADarkCellFlipsToWhite() {
        // The outline follows the cell label's contrast rule, so it can never vanish into a dark fill.
        String svg = Sirentide.render("heatmap\ncols: a\n\"r\" : 1!");
        assertTrue(svg.contains("fill=\"" + HI + "\" stroke=\"#ffffff\""),
            "a dark outlined cell gets a white outline");
    }

    @Test
    void anOutlinedCellCoversTheSameAreaAsAnUnoutlinedOne() {
        // The stroke is inset by half its width, so the outlined rect's OUTER edge sits exactly where
        // the plain cell's fill edge sits: the gridline is never overpainted and no geometry moves.
        String plain = Sirentide.render("heatmap\ncols: a\n\"r\" : 0.5");
        String boxed = Sirentide.render("heatmap\ncols: a\n\"r\" : 0.5!");
        assertEquals(width(plain), width(boxed), "outlining moves no geometry");
        Matcher m = Pattern.compile("<rect x=\"([0-9.]+)\" y=\"([0-9.]+)\" width=\"([0-9.]+)\" "
            + "height=\"([0-9.]+)\" fill=\"" + MID + "\" stroke=\"[^\"]+\" stroke-width=\"([0-9.]+)\"")
            .matcher(boxed);
        assertTrue(m.find(), "outlined rect present");
        double sw = Double.parseDouble(m.group(5));
        Matcher p = Pattern.compile("<rect x=\"([0-9.]+)\" y=\"([0-9.]+)\" width=\"([0-9.]+)\" "
            + "height=\"([0-9.]+)\" fill=\"" + MID + "\"/>").matcher(plain);
        assertTrue(p.find(), "plain rect present");
        assertEquals(Double.parseDouble(p.group(1)), Double.parseDouble(m.group(1)) - sw / 2, 1e-6);
        assertEquals(Double.parseDouble(p.group(3)), Double.parseDouble(m.group(3)) + sw, 1e-6);
    }

    @Test
    void outlinesAreNamedInTheA11yDescription() {
        String svg = Sirentide.render("heatmap\ncols: a, b, c\n\"r\" : 0.1!, 0.2, 0.3!");
        assertTrue(desc(svg).contains("2 outlined cells"), desc(svg));
    }

    // ---- categorical palette --------------------------------------------------------------

    @Test
    void aPaletteColoursEachCellByItsCategoryNotByMagnitude() {
        String svg = Sirentide.render(
            "heatmap\ncols: a, b, c\npalette: A #ff0000, B #00ff00\n\"r\" : A, B, A");
        // Two A cells + one legend swatch; one B cell + one swatch.
        assertEquals(3, rects(svg, "#ff0000"), "A cells + A swatch");
        assertEquals(2, rects(svg, "#00ff00"), "B cell + B swatch");
        assertEquals(0, rects(svg, LO), "no magnitude ramp in categorical mode");
        assertEquals(0, rects(svg, HI), "no magnitude ramp in categorical mode");
    }

    @Test
    void anUnknownCategoryIsNeutralAndANumberIsNotACategory() {
        String svg = Sirentide.render("heatmap\ncols: a, b\npalette: A #ff0000\n\"r\" : Z, 1");
        assertEquals(1, rects(svg, "#ff0000"), "only the legend swatch is red");
        assertEquals(0, rects(svg, HI), "a number never reaches the ramp here");
        // Row-label cell + the two NA cells share the neutral fill.
        assertEquals(3, rects(svg, NA), "unmatched cells are neutral");
    }

    @Test
    void aCategoryWithoutAColourTakesTheDefaultCategoricalPaletteInOrder() {
        String svg = Sirentide.render("heatmap\ncols: a, b\npalette: A, B\n\"r\" : A, B");
        assertEquals(2, rects(svg, "#4e79a7"), "first category: palette[0]");
        assertEquals(2, rects(svg, "#f28e2b"), "second category: palette[1]");
    }

    @Test
    void anInvalidColourNeverReachesTheOutput() {
        // A non-hex colour token is dropped at parse (hex-only, like classDef); the category keeps
        // its default colour, and the hostile token appears nowhere in the SVG.
        String svg = Sirentide.render(
            "heatmap\ncols: a\npalette: A #zzzzzz, B #12345\n\"r\" : A");
        assertFalse(svg.contains("zzzzzz"), "an invalid hex is never emitted");
        assertFalse(svg.contains("#12345\""), "a 5-digit hex is never emitted");
        assertEquals(2, rects(svg, "#4e79a7"), "A falls back to palette[0]");
    }

    @Test
    void aShortHexIsCanonicalized() {
        String svg = Sirentide.render("heatmap\ncols: a\npalette: A #f00\n\"r\" : A");
        assertEquals(2, rects(svg, "#ff0000"), "#f00 expands to #ff0000");
    }

    @Test
    void theDisplayOverrideShowsTextOnTheCategoryFill() {
        // `text:CAT` shows `text`; `:CAT` shows nothing. Compare the glyph-path count of a cell with
        // shown text against one without: the empty display drops exactly that cell's glyph run.
        String shown = Sirentide.render("heatmap\npalette: A #ff0000\n\"r\" : x:A");
        String blank = Sirentide.render("heatmap\npalette: A #ff0000\n\"r\" : :A");
        assertEquals(2, rects(shown, "#ff0000"), "x:A is coloured by A");
        assertEquals(2, rects(blank, "#ff0000"), ":A is coloured by A");
        assertEquals(count(shown, "<path") - 1, count(blank, "<path"), ":A draws no cell text");
    }

    @Test
    void theCategoricalLegendAndA11yNameEachCategory() {
        String svg = Sirentide.render(
            "heatmap\ncols: a, b\npalette: \"part one\" #ff0000, two #00ff00\n\"r\" : part one, two");
        assertTrue(desc(svg).contains("Categories: part one, two."), desc(svg));
        assertEquals(2, rects(svg, "#ff0000"), "the quoted name matches its cells");
    }

    @Test
    void aCategoryNameIsALabelSurfaceSoMarkupInItIsRefused() {
        // The palette names are drawn as legend text, so they go through the same label-markup
        // policy as every other label: unsupported markup degrades the whole diagram to the shell.
        String refused = Sirentide.render("heatmap\npalette: \"a<br/>b\" #ff0000\n\"r\" : x");
        assertEquals(0, rects(refused, "#ff0000"), "the refused diagram draws no swatch");
        String clean = Sirentide.render("heatmap\npalette: \"ab\" #ff0000\n\"r\" : x");
        assertEquals(1, rects(clean, "#ff0000"), "control: the clean name draws its swatch");
    }

    // ---- custom ramp ----------------------------------------------------------------------

    @Test
    void aCustomRampReplacesTheDefaultStops() {
        String svg = Sirentide.render(
            "heatmap\ncols: a, b, c\nramp: #000000, #ffffff\n\"r\" : 0, 0.5, 1");
        assertTrue(rects(svg, "#000000") >= 1, "0 → first stop");
        assertTrue(rects(svg, "#ffffff") >= 1, "1 → last stop");
        assertTrue(rects(svg, "#808080") >= 1, "0.5 → the linear midpoint");
        assertEquals(0, rects(svg, HI), "the default ramp is gone");
    }

    @Test
    void aThreeStopCustomRampHitsItsMiddleStopExactly() {
        String svg = Sirentide.render(
            "heatmap\ncols: a\nramp: #ffffff, #ff0000, #000000\n\"r\" : 0.5");
        assertTrue(rects(svg, "#ff0000") >= 1, "0.5 is the middle of three stops");
    }

    @Test
    void aRampWithFewerThanTwoValidStopsKeepsTheDefault() {
        String svg = Sirentide.render("heatmap\ncols: a\nramp: #000000, nope\n\"r\" : 1");
        assertTrue(rects(svg, HI) >= 1, "one valid stop is not a ramp");
    }

    // ---- bins -----------------------------------------------------------------------------

    @Test
    void equalBinsQuantizeValuesAndStepTheLegend() {
        // 4 equal bins over the default ramp: bin k is the ramp at k/3, so 0.1 and 0.2 share bin 0
        // (the LO end), 0.9 is bin 3 (the HI end), and the legend is exactly 4 steps.
        String svg = Sirentide.render(
            "heatmap\ncols: a, b, c\nbins: 4\n\"r\" : 0.1, 0.2, 0.9");
        assertEquals(3, rects(svg, LO), "0.1, 0.2 + legend step 0 are bin 0");
        assertEquals(2, rects(svg, HI), "0.9 + legend step 3 are bin 3");
        String unbinned = Sirentide.render("heatmap\ncols: a, b, c\n\"r\" : 0.1, 0.2, 0.9");
        assertNotEquals(count(unbinned, "<rect"), count(svg, "<rect"), "legend has 4 steps, not 12");
        assertEquals(count(unbinned, "<rect") - 12 + 4, count(svg, "<rect"));
    }

    @Test
    void explicitThresholdsPutAValueOnTheEdgeIntoTheUpperBin() {
        String svg = Sirentide.render("heatmap\ncols: a, b\nbins: 0.5\n\"r\" : 0.49, 0.5");
        assertEquals(2, rects(svg, LO), "0.49 + legend step 0: lower bin");
        assertEquals(2, rects(svg, HI), "0.5 + legend step 1: upper bin");
    }

    @Test
    void binsComposeWithACustomRamp() {
        String svg = Sirentide.render(
            "heatmap\ncols: a, b, c\nramp: #000000, #ffffff\nbins: 3\n\"r\" : 0.1, 0.5, 0.9");
        assertEquals(2, rects(svg, "#000000"), "bin 0 → first stop (+ legend)");
        assertEquals(2, rects(svg, "#808080"), "bin 1 → the midpoint (+ legend)");
        assertEquals(2, rects(svg, "#ffffff"), "bin 2 → last stop (+ legend)");
    }

    // ---- hide headers ---------------------------------------------------------------------

    @Test
    void hideRowsDropsTheRowLabelColumn() {
        // Six columns so the grid, not the ramp legend row, sets the canvas width.
        String grid = "heatmap\ncols: a, b, c, d, e, f\n%s\"a long row label\" : 0.2, 0.4\n";
        String shown = Sirentide.render(String.format(grid, ""));
        String hidden = Sirentide.render(String.format(grid, "hide: rows\n"));
        assertEquals(7, rects(shown, HEADER), "corner + 6 headers");
        assertEquals(6, rects(hidden, HEADER), "no corner over a hidden column");
        assertEquals(1, rects(shown, NA) - 4, "one row-label cell (+ 4 NA pads)");
        assertEquals(4, rects(hidden, NA), "no row-label cell, only the 4 NA pads");
        assertTrue(width(hidden) < width(shown), "the canvas loses the label column");
    }

    @Test
    void hideColsDropsTheHeaderBandButKeepsTheColumnCount() {
        String hidden = Sirentide.render("heatmap\ncols: a, b, c\nhide: cols\n\"r\" : 0.2");
        assertEquals(0, rects(hidden, HEADER), "no header band");
        // The row is still padded to the declared 3 columns: 1 value + 2 NA pads + the label cell.
        assertEquals(3, rects(hidden, NA), "rectangularized to cols:");
        assertTrue(desc(hidden).contains("3 columns"), "a11y still knows the columns");
    }

    @Test
    void hideBothLeavesOnlyTheCells() {
        String svg = Sirentide.render("heatmap\ncols: a\nhide: rows, cols\n\"r\" : 0.5");
        assertEquals(0, rects(svg, HEADER), "no header band");
        assertEquals(0, rects(svg, NA), "no row-label cell");
        assertEquals(1, rects(svg, MID), "the one value cell remains");
    }

    // ---- composition: the motivating figures ------------------------------------------------

    @Test
    void theCycleAssignmentGridComposesPaletteOutlineAndHiddenHeaders() {
        String svg = Sirentide.render("heatmap\nhide: rows, cols\n"
            + "palette: C1 #4e79a7, C2 #f28e2b\n"
            + "\"H0\" : :C1, :C2!\n\"H1\" : :C2, :C1");
        assertEquals(1, count(svg, "stroke="), "one outlined cell");
        assertEquals(3, rects(svg, "#4e79a7"), "two C1 cells + the C1 swatch");
        assertTrue(desc(svg).contains("1 outlined cell."), desc(svg));
    }
}
