package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.XyChartLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// An xychart category label that does not fit its column slot is NAMED, not silent (plan c880b12e).
///
/// Before this, {@link XyChartLayout} ellipsized each category label to its slot and, when not even
/// the ellipsis fitted, drew NOTHING: the Barker autocorrelation chart (25 lags) silently lost every
/// two-character label (-12..-1, 10..12) while `render --strict` exited 0. Found by Fixpoint's showcase
/// audit (sirentide/1118), reproduced by Confluence before filing. The labels are glyph outlines, not
/// `<text>`, so no text search of the SVG can see the loss; the signal has to come from the layout.
class XyChartLabelLossTest {

    /// The audit's chart, verbatim in shape: 25 single-series bars, labels -12..12.
    static final String BARKER;

    static {
        StringBuilder b = new StringBuilder("xychart\n");
        int[] r = {1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 13, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1};
        for (int lag = -12; lag <= 12; lag++) {
            b.append('"').append(lag).append("\" : ").append(r[lag + 12]).append('\n');
        }
        BARKER = b.toString();
    }

    @Test
    void theBarkerChartNamesEveryDroppedLagLabelOnAnOkCaveat() {
        RenderResult r = Sirentide.renderWithDiagnostics(BARKER);
        assertEquals(Outcome.OK, r.diagnostics().outcome(), "the bake still SUCCEEDED (OK, with caveat)");
        String msg = r.diagnostics().message();
        assertTrue(msg.contains("15 x-axis labels"), "the caveat counts the drops: " + msg);
        for (String lag : List.of("-12", "-1", "10", "12")) {
            assertTrue(msg.contains(lag), "the caveat names dropped lag " + lag + ": " + msg);
        }
        assertTrue(r.diagnostics().detail().startsWith("xychart category-label drop:"),
            "the detail crumb records the drop: " + r.diagnostics().detail());
    }

    @Test
    void theReplayAgreesWithWhatTheSvgActuallyDrew() {
        // The replay must not drift from the layout. Cross-check it against the SVG STRUCTURE, which
        // shares no code with the replay: in the single-series bar path each `<g role="bar">` holds
        // its rect, its category-label path and its value-label path, so a dropped category label
        // leaves its group ONE path short.
        String svg = Sirentide.render(BARKER);
        Matcher g = Pattern.compile("<g data-sirentide-role=\"bar\" data-sirentide-id=\"([^\"]*)\"[^>]*>(.*?)</g>",
            Pattern.DOTALL).matcher(svg);
        List<String> drawnWithoutLabel = new ArrayList<>();
        int groups = 0;
        while (g.find()) {
            groups++;
            int paths = g.group(2).split("<path", -1).length - 1;
            if (paths == 1) {
                drawnWithoutLabel.add(g.group(1));
            }
        }
        assertEquals(25, groups, "control: every bar group was found");
        XyChart chart = (XyChart) DslParser.parse(BARKER);
        assertEquals(drawnWithoutLabel, XyChartLayout.categoryLabelLosses(chart).dropped(),
            "the replay names exactly the bars the SVG drew without a label");
        assertEquals(15, drawnWithoutLabel.size(), "control: the drop really happens (15 of 25)");
    }

    @Test
    void theCaveatDoesNotAlterTheSvgBytes() {
        assertEquals(Sirentide.render(BARKER), Sirentide.renderWithDiagnostics(BARKER).svg(),
            "renderWithDiagnostics(barker).svg() is byte-identical to render(barker)");
    }

    @Test
    void aTruncatedLabelIsNamedAsShortened() {
        // A label wider than its slot that still fits an ellipsis is drawn SHORTENED ("Wednesd…"), so
        // the author cannot read it in full either: named separately from a full drop.
        String dsl = "xychart\n\"Monday\" : 1\n\"Wednesday afternoon session\" : 2\n\"Fri\" : 3";
        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertTrue(r.diagnostics().message().contains("shortened"), r.diagnostics().message());
        assertTrue(r.diagnostics().message().contains("Wednesday afternoon session"), r.diagnostics().message());
        assertFalse(r.diagnostics().message().contains("Monday"), "a label that fits is not named");
    }

    @Test
    void aMultiSeriesLineChartWithCrowdedCategoriesIsCoveredToo() {
        StringBuilder b = new StringBuilder("xychart line\n");
        for (int i = 100; i < 160; i++) {
            b.append('"').append(i).append("\" : ").append(i % 7).append(' ').append(i % 5).append('\n');
        }
        RenderResult r = Sirentide.renderWithDiagnostics(b.toString());
        assertTrue(r.diagnostics().detail().startsWith("xychart category-label drop:"), r.diagnostics().detail());
    }

    // -- every drawing path, checked against SVG STRUCTURE (review F1, plan c880b12e follow-up) --------
    //
    // The first agreement check above covers only the single-series bar path. A mutant that tripled the
    // slot handed to the line/scatter category labels SURVIVED the full suite: nothing looked at what
    // those paths drew. The checks below read each path's SVG with no help from the layout code: the
    // plot box comes from the two axis lines, the n columns from the category count, and every category
    // label is a currentColor glyph path lying wholly BELOW the plot (tick labels sit left of it, value
    // labels above the x-axis, the key beside it). Two properties, one per conjunct of "the caveat is
    // the truth about the drawing":
    //   1. FITS: every drawn label lies inside its own column, so a slot fed wider (or narrower) than
    //      the column shows up in the SVG itself, independent of anything the caveat says;
    //   2. AGREES: the columns the SVG left without a label are exactly the labels named as dropped.

    /// A chart in one drawing path with the Barker chart's lag labels (`n` = 25 gives -12..12). At 25
    /// columns a one-character lag fits its slot and a two- or three-character one does not, so the
    /// drawing MIXES kept and dropped labels, which both properties need to be non-vacuous.
    private static String lags(String header, int n, String values) {
        StringBuilder b = new StringBuilder(header).append('\n');
        for (int i = 0; i < n; i++) {
            int lag = i - n / 2;
            b.append('"').append(lag).append("\" : ")
                .append(values.replace("#", Integer.toString(1 + Math.floorMod(lag, 5)))).append('\n');
        }
        return b.toString();
    }

    @Test
    void groupedBarsDrawOnlyWhatTheCaveatSays() {
        assertDrawingAgrees(lags("xychart", 25, "# 2 3"), 25);
    }

    @Test
    void aLineChartDrawsOnlyWhatTheCaveatSays() {
        assertDrawingAgrees(lags("xychart line", 25, "# 2"), 25);
    }

    @Test
    void aScatterChartDrawsOnlyWhatTheCaveatSays() {
        assertDrawingAgrees(lags("xychart scatter", 25, "# 2"), 25);
    }

    @Test
    void singleSeriesBarsDrawOnlyWhatTheCaveatSays() {
        assertDrawingAgrees(BARKER, 25);
    }

    @Test
    void aComfortableLineChartDrawsEveryLabelInsideItsColumn() {
        // Non-vacuity for FITS: with nothing dropped, every column carries a label and all of them fit.
        assertDrawingAgrees(lags("xychart line", 6, "# 2"), 6);
    }

    @Test
    void eachMultiSeriesPathNamesAShortenedLabel() {
        // The SHORTENED half of the caveat through every multi-series path (the bar path is pinned by
        // aTruncatedLabelIsNamedAsShortened). A shortened label still draws, so structure cannot see it;
        // the exact list is pinned instead.
        for (String header : List.of("xychart", "xychart line", "xychart scatter")) {
            String dsl = header + "\n\"Mon\" : 1 2\n\"Wednesday afternoon session\" : 2 3\n\"Fri\" : 3 1\n";
            XyChart chart = (XyChart) DslParser.parse(dsl);
            XyChartLayout.LabelLosses losses = XyChartLayout.categoryLabelLosses(chart);
            assertEquals(List.of("Wednesday afternoon session"), losses.shortened(), header);
            assertEquals(List.of(), losses.dropped(), header);
        }
    }

    @Test
    void anEmptyCategoryLabelIsNotNamedAsDropped() {
        // An empty label draws nothing because there is nothing to draw, not because its slot was too
        // narrow: naming it would send the author hunting for a loss that did not happen.
        XyChart chart = (XyChart) DslParser.parse("xychart\n\"\" : 1\n\"A\" : 2\n");
        assertEquals("", chart.bars().get(0).label(), "control: the fixture really has an empty label");
        assertTrue(XyChartLayout.categoryLabelLosses(chart).isEmpty(),
            "an empty label is neither dropped nor shortened");
    }

    @Test
    void aMathLabelIsNotReportedEvenWhenItsPlainSpellingWouldDrop() {
        // TODAY'S BEHAVIOUR, pinned on purpose: a `$…$` label is skipped. Spelled as plain text, every
        // multi-character Barker lag below would drop (see the bar-path checks), so this fails the moment
        // math labels start being recorded. The math-label follow-up is expected to replace this test
        // with the behaviour it chooses, deliberately rather than by accident.
        StringBuilder b = new StringBuilder("xychart\n");
        for (int lag = -12; lag <= 12; lag++) {
            b.append("\"$").append(lag).append("$\" : 1\n");
        }
        assertTrue(XyChartLayout.categoryLabelLosses((XyChart) DslParser.parse(b.toString())).isEmpty(),
            "a $...$ label is not recorded as lost");
    }

    @Test
    void theLossPassAddsNothingToTheFontCoverageCorpus() {
        // categoryLabelLosses LAYS THE CHART OUT a second time, without a math renderer. Unsuspended, that
        // pass would feed the raw `$中$` (plain text, since it has no renderer) into the glyph-emission
        // corpus of the render that asked, and the coverage caveat would report U+4E2D for a label the
        // real render drew as math. The render below draws `中` only inside the math fragment.
        com.sirentide.api.MathFragmentRenderer fake = (latex, size) -> java.util.Optional.of(
            new com.sirentide.api.MathFragment("<g><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>", 20, 12, 4));
        RenderResult r = Sirentide.renderWithDiagnostics("xychart\n\"$中$\" : 1\n\"B\" : 2\n", fake);
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals("Rendered successfully.", r.diagnostics().message(),
            "no coverage caveat for a code point the real render never emitted as a glyph");
        RenderResult plain = Sirentide.renderWithDiagnostics("xychart\n\"中\" : 1\n\"B\" : 2\n");
        assertTrue(plain.diagnostics().detail().contains("U+4E2D"),
            "control: the same code point drawn as plain text IS reported: " + plain.diagnostics().detail());
    }

    /// FITS and AGREES (see above) for one chart of `n` categories, from its SVG alone.
    private static void assertDrawingAgrees(String dsl, int n) {
        String svg = Sirentide.render(dsl);
        double plotLeft = attr(svg, "data-sirentide-id=\"y\"", "x1");
        double plotBottom = attr(svg, "data-sirentide-id=\"y\"", "y2");
        double plotRight = attr(svg, "data-sirentide-id=\"x\"", "x2");
        double slot = (plotRight - plotLeft) / n;
        XyChart chart = (XyChart) DslParser.parse(dsl);
        assertEquals(n, chart.bars().size(), "control: the fixture has n categories");

        String[] drawn = new String[n];
        Matcher p = Pattern.compile("<path d=\"([^\"]*)\" fill=\"currentColor\"/>").matcher(svg);
        int labels = 0;
        while (p.find()) {
            double[] box = bbox(p.group(1));   // minX, minY, maxX, maxY
            if (box[1] <= plotBottom) {
                continue;   // not below the plot: a tick, value or key label
            }
            labels++;
            int col = (int) Math.floor(((box[0] + box[2]) / 2 - plotLeft) / slot);
            assertTrue(col >= 0 && col < n, "a category label outside every column: " + col);
            double left = plotLeft + col * slot;
            assertTrue(box[0] >= left - 1e-6 && box[2] <= left + slot + 1e-6,
                "FITS: column " + col + " (" + chart.bars().get(col).label() + ") spans [" + left + ", "
                    + (left + slot) + "] but its label ink spans [" + box[0] + ", " + box[2] + "]");
            assertTrue(drawn[col] == null, "one label per column, column " + col);
            drawn[col] = p.group(1);
        }
        List<String> unlabelled = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (drawn[i] == null) {
                unlabelled.add(chart.bars().get(i).label());
            }
        }
        assertTrue(labels > 0, "control: the SVG drew some category labels");
        if (n == 25) {
            assertEquals(15, unlabelled.size(), "control: the crowded fixture really drops (15 of 25)");
        } else {
            assertEquals(List.of(), unlabelled, "control: the comfortable fixture drops nothing");
        }
        assertEquals(unlabelled, XyChartLayout.categoryLabelLosses(chart).dropped(),
            "AGREES: the caveat names exactly the columns the SVG drew without a label");
    }

    /// A numeric attribute of the first element after `marker`.
    private static double attr(String svg, String marker, String name) {
        int at = svg.indexOf(marker);
        assertTrue(at >= 0, "control: found " + marker);
        Matcher m = Pattern.compile(" " + name + "=\"([-0-9.]+)\"").matcher(svg);
        assertTrue(m.find(at), "control: " + name + " after " + marker);
        return Double.parseDouble(m.group(1));
    }

    /// Ink bounding box of a glyph path. Glyph outlines use only M/L/Q/Z, whose operands are all
    /// (x, y) pairs, so the numbers alternate x, y; any other command fails loudly rather than being
    /// misread.
    private static double[] bbox(String d) {
        assertTrue(d.matches("[MLQZ0-9.\\- ]*"), "a glyph path uses only M/L/Q/Z: " + d);
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        Matcher num = Pattern.compile("-?[0-9.]+").matcher(d);
        int k = 0;
        while (num.find()) {
            double v = Double.parseDouble(num.group());
            int axis = k++ % 2;
            box[axis] = Math.min(box[axis], v);
            box[axis + 2] = Math.max(box[axis + 2], v);
        }
        return box;
    }

    @Test
    void aComfortableChartCarriesNoCaveat() {
        RenderResult r = Sirentide.renderWithDiagnostics("xychart\n\"A\" : 1\n\"B\" : 2\n\"C\" : 3");
        assertEquals("Rendered successfully.", r.diagnostics().message(),
            "a chart that loses no label keeps the plain OK message");
    }
}
