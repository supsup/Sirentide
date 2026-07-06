package com.sirentide.layout;

import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Timeline;
import java.util.ArrayList;
import java.util.List;

/// Pure timeline layout: events placed evenly along a horizontal axis, each a coloured dot with
/// its label above and its value (e.g. a year) below. Deterministic arithmetic — no optimization.
/// The dot is a full-circle {@link Wedge}; the axis a {@link Line}; the labels glyph paths.
public final class TimelineLayout {

    private TimelineLayout() {}

    private static final double W = 480;
    private static final double H = 160;
    private static final double MARGIN = 44;
    private static final double AXIS_Y = 80;
    private static final double DOT_R = 5;
    private static final double TOP_SIZE = 11;      // category-label font size
    private static final double VALUE_SIZE = 10;    // year/value font size
    private static final double ROW_STAGGER = 13;   // vertical offset for the alternate label row
    private static final double LABEL_GAP = 4;       // min horizontal clearance between same-row labels

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final String AXIS_STROKE = "#cbd5e1";

    private static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    public static LaidOut layout(Timeline timeline) {
        List<Shape> shapes = new ArrayList<>();
        // Both the event (top) and value/year (bottom) labels sit on the page background → the
        // page-background text colour, default `currentColor` (legible on light AND dark).
        String textColor = timeline.textColor();
        shapes.add(new Line(MARGIN, AXIS_Y, W - MARGIN, AXIS_Y, AXIS_STROKE, 2));

        List<Slice> events = timeline.events();
        int n = events.size();
        if (n == 0) {
            return new LaidOut(W, H, shapes);
        }
        double plotLeft = MARGIN;
        double plotRight = W - MARGIN;
        // Place each event PROPORTIONALLY to its value (year/date), not evenly by index — a 1-year
        // gap and a 19-year gap must render 1:19, not identical. The domain carries both ends so the
        // mapping is min-normalized (AxisScale). A single event / all-equal values → domain [x,x],
        // which projects to the axis midpoint (no divide-by-zero).
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = events.get(i).value();
        }
        AxisScale axis = AxisScale.of(values);
        double[] xs = new double[n];
        String[] topText = new String[n];
        String[] botText = new String[n];
        for (int i = 0; i < n; i++) {
            Slice e = events.get(i);
            xs[i] = axis.project(e.value(), plotLeft, plotRight);
            topText[i] = e.label();
            botText[i] = num(e.value());
            // Explicit per-item colour (canonical `#rrggbb` from the parser) overrides the palette.
            String fill = e.color() != null ? e.color() : PALETTE[i % PALETTE.length];
            shapes.add(new Wedge(xs[i], AXIS_Y, DOT_R, 0, 2 * Math.PI, fill));
        }
        // DE-COLLISION: labels of events close in value share nearly the same x and their boxes
        // overlap ("Founded"/"Series A" in the screenshot). Measure each label's width and, where an
        // adjacent label would overlap the one before it, push it to a SECOND row (2-row vertical
        // stagger) — the simplest readable fix. Top labels rise, bottom values drop.
        int[] topRows = assignRows(xs, topText, TOP_SIZE);
        int[] botRows = assignRows(xs, botText, VALUE_SIZE);
        for (int i = 0; i < n; i++) {
            centeredLabel(shapes, topText[i], xs[i],
                AXIS_Y - 14 - topRows[i] * ROW_STAGGER, TOP_SIZE, textColor);       // above the line
            centeredLabel(shapes, botText[i], xs[i],
                AXIS_Y + 24 + botRows[i] * ROW_STAGGER, VALUE_SIZE, textColor);     // below the line
        }
        return new LaidOut(W, H, shapes);
    }

    /// Greedy 2-row assignment: walk the labels in x order; keep each row's right edge. Place a
    /// label on the first row whose last label clears it (left edge past that row's right edge +
    /// gap); if neither row is clear, use the row with the smaller right edge. Guarantees any two
    /// labels that overlap horizontally land on different rows, so their 2-D boxes stay disjoint.
    private static int[] assignRows(double[] centers, String[] texts, double size) {
        int n = centers.length;
        int[] rows = new int[n];
        double[] rowRight = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < n; i++) {
            double half = FONT.runWidth(texts[i], size) / 2;
            double left = centers[i] - half;
            double right = centers[i] + half;
            int row = -1;
            for (int r = 0; r < 2; r++) {
                if (left >= rowRight[r] + LABEL_GAP) {
                    row = r;
                    break;
                }
            }
            if (row < 0) {
                row = rowRight[0] <= rowRight[1] ? 0 : 1;   // least-bad: least-extended row
            }
            rows[i] = row;
            rowRight[row] = Math.max(rowRight[row], right);
        }
        return rows;
    }

    private static void centeredLabel(List<Shape> shapes, String text, double cx, double baseline,
                                      double size, String fill) {
        double w = FONT.runWidth(text, size);
        String d = FONT.textPathD(text, cx - w / 2, baseline, size);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
