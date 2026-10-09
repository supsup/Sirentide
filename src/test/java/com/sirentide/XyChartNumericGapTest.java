package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.Wedge;
import com.sirentide.layout.XyChartLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// The explicit `na` gap token of a numeric-x xychart (ruling R2 of review sirentide/1136). Stated
/// intent is silent and draws a gap: the series gets no point at that x and its line is broken there.
/// An unexplained omission (a short row) stays caveated, and `na` where no gap can be meant (the only
/// series, or the x) is refused with a caveat.
class XyChartNumericGapTest {

    /// Series a has a gap at x = 3, series b at x = 5; both are drawn everywhere else.
    static final String TWO_GAPS = "xychart line numeric legend\nseries: a, b\n"
        + "1 : 1 2\n2 : 2 3\n3 : na 4\n4 : 3 3\n5 : 4 na\n6 : 2 1\n";

    @Test
    void naIsAGapInTheIrAndIsSilent() {
        XyChart c = (XyChart) DslParser.parse(TWO_GAPS);
        assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6}, c.xValues(), "the gap rows keep their x");
        assertTrue(Double.isNaN(c.series().get(2)[0]), "series a at x=3 is a gap");
        assertEquals(4, c.series().get(2)[1], "series b at x=3 is drawn");
        assertTrue(Double.isNaN(c.series().get(4)[1]), "series b at x=5 is a gap");
        Diagnostics d = Sirentide.renderWithDiagnostics(TWO_GAPS).diagnostics();
        assertEquals(Outcome.OK, d.outcome());
        assertEquals("", d.detail(), "na is stated intent: no caveat: " + d.message());
    }

    @Test
    void theLineIsBrokenAtTheGapAndNoSegmentSpansIt() {
        XyChart chart = (XyChart) DslParser.parse(TWO_GAPS);
        LaidOut laid = XyChartLayout.layout(chart);
        XyChartNumericGeometryTest.Frame f = XyChartNumericGeometryTest.Frame.of(laid);
        // Series a's discs: x = 1, 2, 4, 5, 6 (5 of them); series b's: x = 1, 2, 3, 4, 6.
        assertEquals(10, f.discs.size(), "one disc per present point, none at a gap");
        List<Wedge> a = f.discs.subList(0, 5);
        List<Wedge> b = f.discs.subList(5, 10);
        String colA = a.get(0).fill();
        String colB = b.get(0).fill();
        assertFalse(colA.equals(colB), "control: the series are told apart by colour");
        // The gap x positions, read from the OTHER series' disc there (not from the projection).
        double gapA = b.get(2).cx();   // series b's point at x = 3
        double gapB = a.get(3).cx();   // series a's point at x = 5
        for (Wedge w : a) {
            assertTrue(Math.abs(w.cx() - gapA) > 1, "series a has no point at its gap");
        }
        for (Wedge w : b) {
            assertTrue(Math.abs(w.cx() - gapB) > 1, "series b has no point at its gap");
        }
        List<Line> segA = segments(f, colA);
        List<Line> segB = segments(f, colB);
        // a: 1-2 | gap at 3 | 4-5, 5-6  -> 3 segments in two runs; b: 1-2, 2-3, 3-4 | gap at 5 | none
        assertEquals(3, segA.size(), "series a: 1-2 and 4-5-6, nothing into or out of x=3: " + segA);
        assertEquals(3, segB.size(), "series b: 1-2-3-4, nothing into or out of x=5: " + segB);
        assertNoSegmentReaches(segA, gapA, "series a");
        assertNoSegmentReaches(segB, gapB, "series b");
        // Control: each series is drawn on both sides of, and INTO, the other's gap x (a gap is per
        // series, not per x): two of its segments end at that x.
        assertEquals(2, segB.stream().filter(l -> endsAt(l, gapA)).count(), "series b runs into and out of x=3");
        assertEquals(2, segA.stream().filter(l -> endsAt(l, gapB)).count(), "series a runs into and out of x=5");
        // The two runs of series a are separate: the segment ending left of the gap and the one
        // starting right of it share no endpoint.
        double leftEnd = segA.stream().filter(l -> Math.max(l.x1(), l.x2()) < gapA)
            .mapToDouble(l -> Math.max(l.x1(), l.x2())).max().orElseThrow();
        double rightStart = segA.stream().filter(l -> Math.min(l.x1(), l.x2()) > gapA)
            .mapToDouble(l -> Math.min(l.x1(), l.x2())).min().orElseThrow();
        assertEquals(a.get(1).cx(), leftEnd, 1e-9, "the left run ends at x = 2");
        assertEquals(a.get(2).cx(), rightStart, 1e-9, "the right run starts at x = 4");
    }

    @Test
    void aGapPointStillSitsOnTheAxisMapAndInsideTheCanvas() {
        // The geometric oracle of XyChartNumericGeometryTest, over a chart with gaps.
        new XyChartNumericGeometryTest().everyPointSitsAtItsDeclaredXAndYOnTheDrawnAxes(TWO_GAPS);
        LaidOut laid = XyChartLayout.layout((XyChart) DslParser.parse(TWO_GAPS));
        for (Line l : XyChartNumericGeometryTest.Frame.of(laid).segments) {
            for (double v : new double[] {l.x1(), l.y1(), l.x2(), l.y2()}) {
                assertTrue(Double.isFinite(v) && v >= 0, "segment coordinate " + v);
            }
            assertTrue(Math.max(l.x1(), l.x2()) <= laid.width() && Math.max(l.y1(), l.y2()) <= laid.height());
        }
    }

    @Test
    void aGapTakesNoPartInTheYDomain() {
        // Without the gap the y ticks are those of [1, 3]; a NaN folded into min/max would poison them.
        XyChartLayout.DrawnTicks gap = XyChartLayout.drawnTicks((XyChart) DslParser.parse(
            "xychart line numeric\nseries: a, b\n1 : 1 2\n2 : na 3\n3 : 2 1\n"));
        XyChartLayout.DrawnTicks none = XyChartLayout.drawnTicks((XyChart) DslParser.parse(
            "xychart line numeric\nseries: a, b\n1 : 1 2\n2 : 3 3\n3 : 2 1\n"));
        assertFalse(gap.y().isEmpty(), "control: y ticks drawn");
        assertEquals(none.y(), gap.y());
        assertEquals(none.x(), gap.x());
    }

    @Test
    void aRowOfOnlyGapsKeepsItsXAndDrawsNothingThere() {
        String src = "xychart line numeric\nseries: a, b\n1 : 1 2\n2 : na na\n3 : 2 1\n";
        assertEquals("", Sirentide.renderWithDiagnostics(src).diagnostics().detail());
        XyChart c = (XyChart) DslParser.parse(src);
        assertArrayEquals(new double[] {1, 2, 3}, c.xValues());
        XyChartNumericGeometryTest.Frame f = XyChartNumericGeometryTest.Frame.of(XyChartLayout.layout(c));
        assertEquals(4, f.discs.size());
        assertEquals(0, f.segments.size(), "both lines are broken at x = 2");
        String allGaps = "xychart line numeric\nseries: a, b\n1 : na na\n";
        assertEquals("", Sirentide.renderWithDiagnostics(allGaps).diagnostics().detail());
        assertTrue(Sirentide.render(allGaps).startsWith("<svg"), "an all-gap chart still renders");
    }

    @Test
    void aScatterGapLeavesOnlyThatPointOut() {
        String src = "xychart scatter numeric\nseries: a, b\n1 : 1 2\n2 : na 3\n";
        assertEquals("", Sirentide.renderWithDiagnostics(src).diagnostics().detail());
        assertEquals(3, XyChartNumericGeometryTest.Frame.of(
            XyChartLayout.layout((XyChart) DslParser.parse(src))).discs.size());
    }

    @Test
    void anImplicitShortRowIsStillCaveated() {
        Diagnostics d = Sirentide.renderWithDiagnostics(
            "xychart line numeric\nseries: a, b\n1 : 1 2\n2 : 3\n").diagnostics();
        assertEquals(4, d.line());
        assertTrue(d.detail().contains("1 of 2 series values"), d.detail());
    }

    // ---- where `na` cannot mean a gap: refused, named with its line --------------------------

    static Stream<Arguments> refused() {
        return Stream.of(
            Arguments.of("xychart line numeric\n1 : 1\n2 : na\n3 : 3\n", 3, "the chart has one series"),
            Arguments.of("xychart line numeric\nseries: a\n1 : 1\n2 : na\n", 4, "the chart has one series"),
            Arguments.of("xychart scatter numeric\n1 : na\n", 2, "the chart has one series"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2\nna : 3 4\n", 4, "cannot stand for an x"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2\n\"na\" : 3 4\n", 4, "cannot stand for an x"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2\n2 : NA 3\n", 4, "lowercase `na`"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2\n2 : Na 3\n", 4, "lowercase `na`"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2\n2 : - 3\n", 4, "`-` (series 1), which is not a number"),
            Arguments.of("xychart line numeric\nseries: a, b\n1 : 1 2\n2 : 3 n/a\n", 4, "not a number"));
    }

    @ParameterizedTest
    @MethodSource("refused")
    void naWhereNoGapCanBeMeantIsRefusedWithItsLine(String src, int line, String fragment) {
        RenderResult r = Sirentide.renderWithDiagnostics(src);
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.OK, d.outcome(), "the chart still renders");
        assertEquals(line, d.line(), d.message());
        assertTrue(d.detail().contains("line " + line + ":") && d.detail().contains("the row was dropped"),
            d.detail());
        assertTrue(d.detail().contains(fragment), fragment + " in " + d.detail());
        assertEquals(Sirentide.render(src), r.svg(), "the caveat never changes the SVG");
    }

    @Test
    void aRefusedSingleSeriesGapRowIsDroppedAndClaimsNoX() {
        // The refused row is dropped before the duplicate check sees it, so a later real row at the
        // same x is drawn, with only the one caveat.
        String src = "xychart line numeric\n1 : 1\n2 : na\n2 : 5\n3 : 3\n";
        XyChart c = (XyChart) DslParser.parse(src);
        assertArrayEquals(new double[] {1, 2, 3}, c.xValues());
        assertArrayEquals(new double[] {5}, c.series().get(1));
        Diagnostics d = Sirentide.renderWithDiagnostics(src).diagnostics();
        assertTrue(d.message().contains("1 numeric xychart row was"), d.message());
        assertFalse(d.detail().contains("repeats x"), d.detail());
    }

    @Test
    void anExtraNaPastANamedSingleSeriesIsAnExtraValueNotAGap() {
        Diagnostics d = Sirentide.renderWithDiagnostics("xychart line numeric\nseries: a\n1 : 1 na\n2 : 2\n")
            .diagnostics();
        assertTrue(d.detail().contains("extra value was dropped"), d.detail());
        assertFalse(d.detail().contains("one series"), d.detail());
    }

    private static List<Line> segments(XyChartNumericGeometryTest.Frame f, String colour) {
        List<Line> out = new ArrayList<>();
        for (Line l : f.segments) {
            if (l.stroke().equals(colour)) {
                out.add(l);
            }
        }
        return out;
    }

    private static boolean endsAt(Line l, double x) {
        return Math.abs(l.x1() - x) < 1e-9 || Math.abs(l.x2() - x) < 1e-9;
    }

    private static void assertNoSegmentReaches(List<Line> segs, double gapX, String what) {
        for (Line l : segs) {
            assertTrue(Math.max(l.x1(), l.x2()) < gapX - 1 || Math.min(l.x1(), l.x2()) > gapX + 1,
                what + ": segment " + l + " reaches or spans the gap at px " + gapX);
        }
    }
}
