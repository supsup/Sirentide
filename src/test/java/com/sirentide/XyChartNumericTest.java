package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Shape;
import com.sirentide.layout.Wedge;
import com.sirentide.layout.XyChartLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/// The numeric x axis for xychart (plan c880b12e, numeric-x slice): `xychart line numeric` puts the
/// row keys on a CONTINUOUS axis with nice, thinned ticks, so a 25-lag or 140-point series no longer
/// drops category labels. Every malformed numeric row is named on the OK caveat with its line.
class XyChartNumericTest {

    /// The audit's Barker chart (lags -12..12) in numeric mode.
    static final String BARKER;

    static {
        StringBuilder b = new StringBuilder("xychart line numeric\n");
        int[] r = {1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 13, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1};
        for (int lag = -12; lag <= 12; lag++) {
            b.append('"').append(lag).append("\" : ").append(r[lag + 12]).append('\n');
        }
        BARKER = b.toString();
    }

    static String series140() {
        StringBuilder b = new StringBuilder("xychart line numeric\n");
        for (int i = 0; i < 140; i++) {
            b.append(i).append(" : ").append(Math.sin(i / 9.0) * 40 + i * 0.3).append('\n');
        }
        return b.toString();
    }

    // ---- parse ------------------------------------------------------------------------------

    @Test
    void theNumericModifierCarriesXValuesOnTheIr() {
        XyChart c = (XyChart) DslParser.parse("xychart line numeric\n0.5 : 1 2\n\"1.5\" : 3 4\n");
        assertArrayEquals(new double[] {0.5, 1.5}, c.xValues());
        assertEquals("line", c.mode());
        assertEquals(List.of("0.5", "1.5"), c.bars().stream().map(s -> s.label()).toList());
    }

    @Test
    void numericWithoutAModeIsALine() {
        XyChart c = (XyChart) DslParser.parse("xychart numeric\n1 : 1\n2 : 3\n");
        assertEquals("line", c.mode());
        assertNotNull(c.xValues());
    }

    @Test
    void theCategoryChartCarriesNoXValues() {
        assertNull(((XyChart) DslParser.parse("xychart line\n\"1\" : 1\n\"2\" : 3\n")).xValues());
    }

    // ---- acceptance -------------------------------------------------------------------------

    @Test
    void theBarkerChartInNumericModeDrawsEveryTickItChoosesWithNoCaveat() {
        RenderResult r = Sirentide.renderWithDiagnostics(BARKER);
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals("", r.diagnostics().detail(), "no caveat: " + r.diagnostics().message());
        XyChart chart = (XyChart) DslParser.parse(BARKER);
        XyChartLayout.DrawnTicks t = XyChartLayout.drawnTicks(chart);
        assertEquals(List.of("-12", "-10", "-8", "-6", "-4", "-2", "0", "2", "4", "6", "8", "10", "12"), t.x());
        assertTrue(XyChartLayout.categoryLabelLosses(chart, null).isEmpty());
    }

    @Test
    void theCategoryBarkerStillReportsItsDropsUnchanged() {
        RenderResult r = Sirentide.renderWithDiagnostics(XyChartLabelLossTest.BARKER);
        assertTrue(r.diagnostics().message().contains("15 x-axis labels"), r.diagnostics().message());
    }

    @Test
    void a140PointSeriesRendersWithoutCaveatAndItsDiscsDoNotOverlap() {
        String src = series140();
        RenderResult r = Sirentide.renderWithDiagnostics(src);
        assertEquals("", r.diagnostics().detail(), r.diagnostics().message());
        LaidOut laid = XyChartLayout.layout((XyChart) DslParser.parse(src));
        List<Wedge> discs = new ArrayList<>();
        collect(laid.shapes(), discs);
        assertEquals(140, discs.size(), "control: one disc per point");
        discs.sort((a, b) -> Double.compare(a.cx(), b.cx()));
        for (int i = 0; i + 1 < discs.size(); i++) {
            double gap = discs.get(i + 1).cx() - discs.get(i).cx();
            assertTrue(discs.get(i).r() + discs.get(i + 1).r() <= gap + 1e-9,
                "discs " + i + " and " + (i + 1) + " smear: r=" + discs.get(i).r() + " gap=" + gap);
        }
        assertFalse(XyChartLayout.drawnTicks((XyChart) DslParser.parse(src)).x().isEmpty());
    }

    @Test
    void noTickInNumericModeUsesENotation() {
        String src = "xychart line numeric\n0.0001 : 0.00047\n0.0005 : 0.0021\n0.0014 : 0.00003\n";
        XyChartLayout.DrawnTicks t = XyChartLayout.drawnTicks((XyChart) DslParser.parse(src));
        assertFalse(t.x().isEmpty() || t.y().isEmpty(), "control: both axes drew ticks");
        for (String l : concat(t.x(), t.y())) {
            assertFalse(l.contains("E"), "tick " + l + " in " + t);
        }
        assertTrue(t.x().contains("0.0005") || t.x().contains("0.001"), "plain decimals: " + t.x());
        String huge = "xychart scatter numeric\n0.000000001 : 1\n1000000000000 : 2\n";
        XyChartLayout.DrawnTicks h = XyChartLayout.drawnTicks((XyChart) DslParser.parse(huge));
        for (String l : concat(h.x(), h.y())) {
            assertFalse(l.contains("E"), "tick " + l + " in " + h);
        }
        assertEquals("", Sirentide.renderWithDiagnostics(huge).diagnostics().detail());
    }

    @Test
    void numericLayoutStaysInsideItsCanvas() {
        for (String src : List.of(BARKER, series140(),
                "xychart scatter numeric legend\nseries: a, b\n-1000000 : 0.00001 5\n1000000000000 : 0.00002 7\n")) {
            LaidOut laid = XyChartLayout.layout((XyChart) DslParser.parse(src));
            List<Wedge> discs = new ArrayList<>();
            collect(laid.shapes(), discs);
            for (Wedge w : discs) {
                assertTrue(w.cx() - w.r() >= 0 && w.cx() + w.r() <= laid.width()
                    && w.cy() - w.r() >= 0 && w.cy() + w.r() <= laid.height(), "disc outside canvas in " + src);
            }
        }
    }

    // ---- valid edge inputs: no caveat ---------------------------------------------------------

    static Stream<String> validSources() {
        return Stream.of(
            "xychart line numeric\n5 : 1\n",                                   // single point
            "xychart line numeric\n-3 : 1\n-2 : 4\n-1 : 9\n",                  // negative x
            "xychart scatter numeric\n1 : 1\n1 : 2\n1 : 3\n",                  // scatter: all-equal x
            "xychart scatter numeric\n3 : 1\n1 : 2\n2 : 3\n",                  // scatter: unsorted
            "xychart line numeric\n0.000000001 : 1\n1000000000000 : 2\n",      // huge range
            "xychart line numeric legend\nseries: a, b\n1 : 1 2\n2 : 3 4\n",   // named series
            "xychart line numeric\n");                                         // empty body
    }

    @ParameterizedTest
    @MethodSource("validSources")
    void aValidNumericSourceCarriesNoCaveat(String src) {
        Diagnostics d = Sirentide.renderWithDiagnostics(src).diagnostics();
        assertEquals(Outcome.OK, d.outcome());
        assertEquals("", d.detail(), d.message());
    }

    // ---- malformed rows: a line-scoped caveat each --------------------------------------------

    static Stream<Arguments> malformed() {
        return Stream.of(
            Arguments.of("xychart line numeric\n1 : 1\nabc : 2\n3 : 3\n", 3, "not a number"),
            Arguments.of("xychart line numeric\n1 : 1\nNaN : 2\n", 3, "not finite"),
            Arguments.of("xychart line numeric\n1 : 1\nInfinity : 2\n", 3, "not finite"),
            Arguments.of("xychart line numeric\n1 : 1\n2 : NaN\n", 3, "not finite"),
            Arguments.of("xychart line numeric\n1 : 1\n2 : 1e400\n", 3, "not finite"),
            Arguments.of("xychart line numeric\n1 : 1\n2 : x\n", 3, "not a number"),
            Arguments.of("xychart line numeric\n1 : 1\n1 : 2\n", 3, "repeats x"),
            Arguments.of("xychart line numeric\n2 : 1\n1 : 2\n", 3, "out of order"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2\n2 : 3\n", 4, "1 of 2"),
            Arguments.of("xychart line numeric\n1 : 1 2\n2 : 3\n", 3, "1 of 2"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2 3\n", 3, "extra"),
            Arguments.of("xychart line numeric\n1 : 1\n2 :\n", 3, "no y value"),
            Arguments.of("xychart line numeric\n1 : 1\njust text\n", 3, "no `:`"),
            Arguments.of("xychart line numeric\n1 : 1\n2000000000000000 : 2\n", 3, "supported magnitude"),
            Arguments.of("xychart line numeric\n1 : 1\nseries: a\n", 3, "first row"),
            Arguments.of("xychart line numeric\n1 : 1\n1 : 2\n1 : 3\n", 3, "repeats x"),
            Arguments.of("%% title: t\nxychart line numeric\n1 : 1\nfoo : 2\n", 4, "not a number"));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void aMalformedNumericRowIsNamedWithItsLine(String src, int line, String fragment) {
        RenderResult r = Sirentide.renderWithDiagnostics(src);
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.OK, d.outcome(), "the chart still renders");
        assertEquals(line, d.line(), "line-scoped: " + d.message());
        assertTrue(d.detail().contains("line " + line + ":"), d.detail());
        assertTrue(d.message().contains(fragment) || d.detail().contains(fragment),
            "names the problem (" + fragment + "): " + d.message() + " | " + d.detail());
        assertEquals(Sirentide.render(src), r.svg(), "the caveat never changes the SVG");
    }

    @Test
    void manyBadRowsAreCountedExactlyAndListedBounded() {
        StringBuilder b = new StringBuilder("xychart line numeric\n0 : 0\n");
        for (int i = 0; i < 12; i++) {
            b.append("bad").append(i).append(" : 1\n");
        }
        Diagnostics d = Sirentide.renderWithDiagnostics(b.toString()).diagnostics();
        assertTrue(d.message().contains("12 numeric xychart rows"), d.message());
        assertTrue(d.detail().contains("more not listed"), d.detail());
    }

    @Test
    void anUnsortedLineIsDrawnSortedByX() {
        XyChart c = (XyChart) DslParser.parse("xychart line numeric\n3 : 1\n1 : 2\n2 : 3\n");
        assertArrayEquals(new double[] {1, 2, 3}, c.xValues());
        assertArrayEquals(new double[] {2}, c.series().get(0));
    }

    @Test
    void aDuplicateXOnALineKeepsTheFirstRow() {
        XyChart c = (XyChart) DslParser.parse("xychart line numeric\n1 : 5\n1 : 9\n2 : 3\n");
        assertArrayEquals(new double[] {1, 2}, c.xValues());
        assertArrayEquals(new double[] {5}, c.series().get(0));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void collect(List<Shape> shapes, List<Wedge> out) {
        for (Shape s : shapes) {
            if (s instanceof Wedge w) {
                out.add(w);
            } else if (s instanceof Group g) {
                collect(g.members(), out);
            }
        }
    }
}
