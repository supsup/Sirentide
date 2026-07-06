package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Gantt;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Task;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.AxisScale;
import com.sirentide.layout.GanttLayout;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.PieLayout;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.layout.TimelineLayout;
import com.sirentide.layout.Wedge;
import com.sirentide.layout.XyChartLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The four "axes lie" correctness regressions (plan ca4e54f5) — each asserts the FIX arithmetically
/// on the pure layout output, so a re-introduction of the silent-misinform bug fails the build. The
/// layout API is the load-bearing surface here (the emitted SVG merely serializes it).
class CorrectnessRegressionTest {

    private static List<Wedge> wedges(LaidOut laid) {
        List<Wedge> out = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            if (s instanceof Wedge w) {
                out.add(w);
            }
        }
        return out;
    }

    private static List<Rect> rects(LaidOut laid) {
        List<Rect> out = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            if (s instanceof Rect r) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<Line> lines(LaidOut laid) {
        List<Line> out = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            if (s instanceof Line l) {
                out.add(l);
            }
        }
        return out;
    }

    // --- Bug #1: timeline axis was placed evenly by index, not proportionally by value ----------

    @Test
    void timelineGapsAreProportionalToValueNotIndex() {
        Timeline tl = new Timeline(List.of(
            new Slice("Founded", 2000), new Slice("A", 2001), new Slice("Launch", 2020)));
        List<Wedge> dots = wedges(TimelineLayout.layout(tl));
        assertEquals(3, dots.size(), "one dot per event");
        double gap2000to2001 = dots.get(1).cx() - dots.get(0).cx();
        double gap2001to2020 = dots.get(2).cx() - dots.get(1).cx();
        // The value gaps are 1 year and 19 years, so the pixel gaps must be ~1:19 (was 1:1 by index).
        double ratio = gap2001to2020 / gap2000to2001;
        assertTrue(ratio > 18.5 && ratio < 19.5, "2001->2020 gap must be ~19x the 2000->2001 gap, got " + ratio);
    }

    // --- Bug #2: gantt had no axis min-normalization → absolute-date bars became invisible slivers -

    @Test
    void ganttAbsoluteDateBarsAreVisibleNotCrammedRight() {
        // Absolute years 2020..2023 (start >> 0): the old `start / maxEnd` crammed a ~0.2px bar at
        // the right edge. Min-normalization ([2020,2023]) gives real, left-anchored widths.
        Gantt g = new Gantt(List.of(new Task("Design", 2020, 2021), new Task("Build", 2021, 2023)));
        List<Rect> bars = rects(GanttLayout.layout(g));
        assertEquals(2, bars.size(), "one bar per task");
        Rect first = bars.get(0);
        assertTrue(first.width() > 20, "the first bar has a real, visible width, got " + first.width());
        assertTrue(first.x() < 200, "the first bar is anchored to the LEFT, not crammed at the right edge, x=" + first.x());
        // The two spans are 1yr and 2yr → the second bar is ~2x the width of the first.
        assertTrue(bars.get(1).width() > first.width() * 1.5, "wider span -> wider bar");
    }

    @Test
    void ganttMalformedEndBeforeStartStaysVisible() {
        Gantt g = new Gantt(List.of(new Task("Good", 0, 5), new Task("Backwards", 8, 4)));
        List<Rect> bars = rects(GanttLayout.layout(g));
        assertEquals(2, bars.size(), "the backwards task is still drawn");
        assertTrue(bars.get(1).width() >= 3, "end<start clamps to a VISIBLE min-width marker, not zero-width");
    }

    // --- Bug #3: xychart clamped negatives to zero → all-negative blanked silently ---------------

    @Test
    void xychartNegativeBarDescendsBelowBaseline() {
        XyChart c = new XyChart(List.of(new Slice("A", 5), new Slice("B", -3)));
        List<Rect> bars = rects(XyChartLayout.layout(c));
        assertEquals(2, bars.size(), "both bars drawn");
        // Reconstruct the zero baseline the same way the layout does.
        double plotTop = 20, plotBottom = 200;
        AxisScale axis = new AxisScale(-3, 5);
        double baselineY = axis.project(0, plotBottom, plotTop);
        Rect pos = bars.get(0);   // value 5
        Rect neg = bars.get(1);   // value -3
        // Positive bar: sits ON the baseline and rises (its bottom edge is at the baseline).
        assertTrue(Math.abs((pos.y() + pos.height()) - baselineY) < 1e-6, "positive bar rests on the baseline");
        assertTrue(pos.y() < baselineY, "positive bar rises above the baseline");
        // Negative bar: descends BELOW the baseline (its bottom edge is below the baseline).
        assertTrue(neg.y() + neg.height() > baselineY + 1, "negative bar descends below the baseline");
        assertTrue(neg.height() > 1, "the negative bar has real height (not clamped to zero)");
    }

    @Test
    void xychartAllNegativeIsNotBlank() {
        XyChart c = new XyChart(List.of(new Slice("A", -2), new Slice("B", -5)));
        List<Rect> bars = rects(XyChartLayout.layout(c));
        assertEquals(2, bars.size(), "all-negative data still renders bars (was a silent blank)");
        for (Rect r : bars) {
            assertTrue(r.height() > 1, "each descending bar has real height, got " + r.height());
        }
    }

    // --- Bug #4: pie summed negatives in the denominator + co-located thin-slice labels ----------

    @Test
    void pieNegativeDoesNotPushAnySliceOverAFullTurn() {
        // Old denominator = 10 + 10 - 5 = 15 → each positive slice swept 240°, summing to 480°.
        // Positive-only denominator = 20 → 180° each, summing to exactly 360°.
        Pie p = new Pie(List.of(new Slice("A", 10), new Slice("B", 10), new Slice("Refund", -5)));
        List<Wedge> ws = wedges(PieLayout.layout(p));
        assertEquals(2, ws.size(), "the negative slice is not drawn");
        double totalSweep = 0;
        for (Wedge w : ws) {
            double sweep = w.a1() - w.a0();
            assertTrue(sweep <= 2 * Math.PI + 1e-9, "no single slice exceeds a full turn, got " + Math.toDegrees(sweep));
            totalSweep += sweep;
        }
        assertTrue(totalSweep <= 2 * Math.PI + 1e-6, "total sweep does not exceed 360deg, got " + Math.toDegrees(totalSweep));
    }

    @Test
    void pieThinSlicesGetSeparateOutsideLabelsNotColocated() {
        // Two 1% slices: their centered inside-labels used to stack on ~the same point. Now each
        // gets a leader line to an OUTSIDE label anchored at its own mid-angle.
        Pie p = new Pie(List.of(new Slice("Tiny1", 1), new Slice("Tiny2", 1), new Slice("Big", 98)));
        LaidOut laid = PieLayout.layout(p);
        List<Line> leaders = lines(laid);
        assertEquals(2, leaders.size(), "each thin slice gets its own leader line, the big slice gets none");
        // The two leaders terminate at DISTINCT points → the labels are not co-located.
        Line a = leaders.get(0);
        Line b = leaders.get(1);
        double dist = Math.hypot(a.x2() - b.x2(), a.y2() - b.y2());
        assertTrue(dist > 5, "the two thin-slice labels sit at distinct positions, gap=" + dist);
    }
}
