package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.XyChart;
import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.Shape;
import com.sirentide.layout.Wedge;
import com.sirentide.layout.XyChartLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/// Where a numeric-x chart DRAWS its points, read back from the drawn shapes (review sirentide/1136,
/// P15/P13/P5/P6). The axis map is taken from the drawn TICKS, not from the projection under test:
/// each x tick mark is a short vertical stroke under the x axis and its value is the tick label the
/// layout drew there (drawnTicks, in the same ascending order), so (value, px) pairs fix the line
/// px = a + b * value. Every disc's centre must sit on that line at its declared x, and a disc whose
/// x IS a tick value must share that tick's px. The same holds on the y axis. A projection that
/// moved every point (P15: points mapped onto [plotLeft, plotRight - 2]) keeps every other test
/// green and fails here.
class XyChartNumericGeometryTest {

    private static final double EPS = 1e-6;

    static Stream<String> charts() {
        return Stream.of(
            // non-uniform x: points at 1, 2, 3 crowd the left while 100 and 1000 spread out
            "xychart line numeric\n1 : 3\n2 : 5\n3 : 4\n100 : 9\n1000 : 2\n",
            // an all-negative range
            "xychart line numeric\n-50 : 1\n-40 : 4\n-20 : -3\n-10 : 2\n-3 : 0\n",
            // a tiny range, labelled in plain decimals
            "xychart line numeric\n0 : 1\n0.0005 : 2\n0.001 : 0.5\n0.0014 : 3\n",
            // mixed sign through zero, with points ON tick values
            "xychart scatter numeric\n-2 : 1\n0 : 0\n2 : 4\n4 : -1\n6 : 2\n",
            // two series with a legend (the plot shifts right by the key)
            "xychart line numeric legend\nseries: a, b\n0 : 1 2\n10 : 3 1\n20 : 2 2\n30 : 5 0\n",
            XyChartNumericTest.BARKER,
            XyChartNumericTest.series140());
    }

    @ParameterizedTest
    @MethodSource("charts")
    void everyPointSitsAtItsDeclaredXAndYOnTheDrawnAxes(String src) {
        XyChart chart = (XyChart) DslParser.parse(src);
        LaidOut laid = XyChartLayout.layout(chart);
        Frame f = Frame.of(laid);
        XyChartLayout.DrawnTicks ticks = XyChartLayout.drawnTicks(chart);
        assertEquals(ticks.x().size(), f.xTickPx.size(), "control: one drawn x label per x tick mark");
        assertEquals(ticks.y().size(), f.yTickPy.size(), "control: one drawn y label per y tick mark");
        assertTrue(ticks.x().size() >= 2 && ticks.y().size() >= 2, "control: two ticks fix each axis");
        Map1 xMap = Map1.fit(ticks.x(), f.xTickPx);
        Map1 yMap = Map1.fit(ticks.y(), f.yTickPy);

        List<double[]> expected = expectedPoints(chart);
        assertEquals(expected.size(), f.discs.size(), "control: one disc per present point");
        int onTick = 0;
        for (int k = 0; k < expected.size(); k++) {
            double x = expected.get(k)[0];
            double y = expected.get(k)[1];
            Wedge w = f.discs.get(k);
            assertEquals(xMap.px(x), w.cx(), EPS, "disc " + k + " (x=" + x + ") is off its x in " + src);
            assertEquals(yMap.px(y), w.cy(), EPS, "disc " + k + " (y=" + y + ") is off its y in " + src);
            for (int t = 0; t < ticks.x().size(); t++) {
                if (Double.parseDouble(ticks.x().get(t)) == x) {
                    assertEquals(f.xTickPx.get(t), w.cx(), EPS, "disc at x=" + x + " vs the tick labelled "
                        + ticks.x().get(t));
                    onTick++;
                }
            }
            // An end point is inset from both axes: never drawn on the y axis or on the x axis.
            assertTrue(w.cx() - w.r() > f.plotLeft, "disc " + k + " touches the y axis in " + src);
            assertTrue(w.cy() + w.r() < f.plotBottom, "disc " + k + " touches the x axis in " + src);
            assertTrue(w.cx() + w.r() < f.plotRight && w.cy() - w.r() > 0, "disc " + k + " leaves the plot");
        }
        assertTrue(onTick > 0, "control: some point shares an x with a drawn tick in " + src);
        for (double px : f.xTickPx) {
            assertTrue(px >= f.plotLeft - EPS && px <= f.plotRight + EPS, "an x tick lies off the x axis");
        }
    }

    @Test
    void fifteenDigitYLabelsAreDrawnWholeInsideTheCanvas() {
        // The left margin grows to the widest y label; at a fixed 40px a 15-digit label starts left of
        // the canvas edge and is clipped off it (review P13).
        String src = "xychart line numeric\n0 : 123456789012345\n1 : 987654321012345\n2 : 500000000000000\n";
        XyChart chart = (XyChart) DslParser.parse(src);
        XyChartLayout.DrawnTicks ticks = XyChartLayout.drawnTicks(chart);
        assertTrue(ticks.y().stream().anyMatch(l -> l.length() >= 15), "control: a 15-digit y label: " + ticks.y());
        LaidOut laid = XyChartLayout.layout(chart);
        List<GlyphRun> runs = new ArrayList<>();
        collect(laid.shapes(), runs, GlyphRun.class);
        assertTrue(runs.size() >= ticks.y().size(), "control: the y labels were drawn");
        for (GlyphRun g : runs) {
            double[] box = inkBox(g.pathD());
            assertTrue(box[0] >= 0 && box[2] <= laid.width() && box[1] >= 0 && box[3] <= laid.height(),
                "a label's ink leaves the " + laid.width() + "x" + laid.height() + " canvas: x " + box[0]
                    + ".." + box[2] + ", y " + box[1] + ".." + box[3]);
        }
    }

    // ---- reading the drawn shapes -------------------------------------------------------------

    /// The (x, y) of every point the layout should draw, in its emit order: series by series, rows in
    /// ascending x, skipping a series with no value at a row.
    static List<double[]> expectedPoints(XyChart chart) {
        double[] xs = chart.xValues();
        List<double[]> series = chart.series();
        int count = 0;
        for (double[] row : series) {
            count = Math.max(count, row.length);
        }
        if (chart.seriesNames() != null) {
            count = Math.max(count, chart.seriesNames().size());
        }
        List<double[]> out = new ArrayList<>();
        for (int s = 0; s < count; s++) {
            for (int i = 0; i < xs.length; i++) {
                double[] row = series.get(i);
                if (s < row.length && Double.isFinite(row[s])) {
                    out.add(new double[] {xs[i], row[s]});
                }
            }
        }
        return out;
    }

    /// The axis frame as drawn: the spines (inside their anchor groups), the tick marks, the discs.
    static final class Frame {
        double plotLeft;
        double plotRight;
        double plotBottom;
        final List<Double> xTickPx = new ArrayList<>();
        final List<Double> yTickPy = new ArrayList<>();
        final List<Wedge> discs = new ArrayList<>();
        final List<Line> segments = new ArrayList<>();

        static Frame of(LaidOut laid) {
            Frame f = new Frame();
            List<Line> top = new ArrayList<>();
            for (Shape s : laid.shapes()) {
                if (s instanceof Group g && g.anchor() != null && g.members().size() == 1
                        && g.members().get(0) instanceof Line spine) {
                    if (spine.x1() == spine.x2()) {
                        f.plotLeft = spine.x1();          // the y axis
                    } else {
                        f.plotBottom = spine.y1();        // the x axis
                        f.plotRight = spine.x2();
                    }
                } else if (s instanceof Line l) {
                    top.add(l);
                }
            }
            for (Line l : top) {
                if (l.strokeWidth() == 1 && l.x1() == l.x2() && l.y1() == f.plotBottom && l.y2() == f.plotBottom + 4) {
                    f.xTickPx.add(l.x1());
                } else if (l.strokeWidth() == 1 && l.y1() == l.y2() && l.x2() == f.plotLeft && l.x1() == f.plotLeft - 4) {
                    f.yTickPy.add(l.y1());
                } else {
                    f.segments.add(l);
                }
            }
            collect(laid.shapes(), f.discs, Wedge.class);
            return f;
        }
    }

    /// px = a + b * value, fixed by the first and last drawn tick and checked against every tick.
    record Map1(double a, double b) {
        static Map1 fit(List<String> labels, List<Double> px) {
            double v0 = Double.parseDouble(labels.get(0));
            double v1 = Double.parseDouble(labels.get(labels.size() - 1));
            double b = (px.get(px.size() - 1) - px.get(0)) / (v1 - v0);
            Map1 m = new Map1(px.get(0) - b * v0, b);
            for (int i = 0; i < labels.size(); i++) {
                assertEquals(px.get(i), m.px(Double.parseDouble(labels.get(i))), EPS,
                    "control: the drawn ticks are evenly mapped (tick " + labels.get(i) + ")");
            }
            return m;
        }

        double px(double value) {
            return a + b * value;
        }
    }

    /// The bounding box of a glyph path's points (control points included): {minX, minY, maxX, maxY}.
    static double[] inkBox(String d) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        List<Double> nums = new ArrayList<>();
        for (String tok : d.trim().split("\\s+")) {
            if (!tok.isEmpty() && !Character.isLetter(tok.charAt(0))) {
                nums.add(Double.parseDouble(tok));
            }
        }
        for (int i = 0; i + 1 < nums.size(); i += 2) {
            box[0] = Math.min(box[0], nums.get(i));
            box[2] = Math.max(box[2], nums.get(i));
            box[1] = Math.min(box[1], nums.get(i + 1));
            box[3] = Math.max(box[3], nums.get(i + 1));
        }
        return box;
    }

    static <T> void collect(List<Shape> shapes, List<T> out, Class<T> type) {
        for (Shape s : shapes) {
            if (type.isInstance(s)) {
                out.add(type.cast(s));
            } else if (s instanceof Group g) {
                collect(g.members(), out, type);
            }
        }
    }
}
