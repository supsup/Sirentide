package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
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
    /// Max rendered width of an event (top) label before it ellipsizes. Without this a legal DSL of
    /// MAX_DATA_ROWS rows × MAX_LABEL_LEN-char labels builds multi-GB of glyph-path data (H2 OOM).
    /// ~120px (a quarter of the 480px canvas) matches the other layouts' established label-width cap
    /// (Gantt LABEL_COL slot, XyChart per-bar slot) and keeps two same-row labels legibly disjoint.
    private static final double MAX_LABEL_W = 120;
    /// In-frame clamp margin: the min gap kept between any glyph box and the canvas edge.
    private static final double CLAMP_MARGIN = 2;

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final String AXIS_STROKE = "#cbd5e1";

    public static LaidOut layout(Timeline timeline) {
        return layout(timeline, null);
    }

    /// Inline-math entry (plan sirentide-math-in-all-label-types): a `$…$` run in an EVENT label
    /// bakes through the shared {@link MathLabel} seam. A null `math` degrades every `$…$` to plain
    /// text — byte-identical to {@link #layout(Timeline)}.
    public static LaidOut layout(Timeline timeline, MathFragmentRenderer math) {
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
        // Composite measure for an event label carrying `$…$` AND a renderer; null for plain labels
        // (which keep the ellipsize path, byte-identical). topW/botW carry the widths the row
        // de-collision measures — composite for a math label, ellipsized runWidth otherwise.
        MathLabel.Measured[] topMeasures = new MathLabel.Measured[n];
        double[] topW = new double[n];
        double[] botW = new double[n];
        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): each event's DOT is wrapped in
        // ONE `<g role="event">` (seq in event order, id from the event label). Only the dot rides in
        // the group — the top/bottom labels are laid out later (de-collision needs the full set) and
        // stay bare, exactly as a pie thin-slice's deferred outside label stays bare; the dot IS the
        // event's anchor point. Grouping the dot in place preserves emit order (dots emit here, labels
        // in the pass below).
        AnchorAssigner assigner = new AnchorAssigner();
        for (int i = 0; i < n; i++) {
            Slice e = events.get(i);
            xs[i] = axis.project(e.value(), plotLeft, plotRight);
            // Ellipsize the event label to a bounded width (parity with Gantt/XyChart/Pie). The raw
            // label is up to MAX_LABEL_LEN (512) chars; without this cap a full MAX_DATA_ROWS sheet
            // of long labels builds ~GBs of glyph paths (H2). The value below is a bounded number.
            // A `$…$` event label SKIPS the ellipsize (a formula must not be cut mid-run) and bakes
            // through the MathLabel seam on its composite width.
            if (math != null && MathLabel.hasMath(e.label())) {
                MathLabel.Measured mm = MathLabel.measure(e.label(), TOP_SIZE, FONT, math);
                topMeasures[i] = mm;
                topText[i] = e.label();
                topW[i] = mm.width();
            } else {
                topText[i] = FONT.ellipsize(e.label(), MAX_LABEL_W, TOP_SIZE);
                topW[i] = FONT.runWidth(topText[i], TOP_SIZE);
            }
            // Show the author's date token (A2) when the value came from an ISO date — its numeric
            // form is an opaque epoch-day. A bare year / plain number has a null valueLabel → num().
            botText[i] = e.valueLabel() != null ? e.valueLabel() : num(e.value());
            botW[i] = FONT.runWidth(botText[i], VALUE_SIZE);
            // Explicit per-item colour (canonical `#rrggbb` from the parser) overrides the palette.
            String fill = e.color() != null ? e.color() : Colors.PALETTE[i % Colors.PALETTE.length];
            shapes.add(new Group(assigner.assign(SirentideRole.EVENT, e.label()),
                List.<Shape>of(new Wedge(xs[i], AXIS_Y, DOT_R, 0, 2 * Math.PI, fill))));
        }
        // DE-COLLISION: labels of events close in value share nearly the same x and their boxes
        // overlap ("Founded"/"Series A" in the screenshot). Measure each label's width and, where an
        // adjacent label would overlap the one before it, push it to a SECOND row (2-row vertical
        // stagger) — the simplest readable fix. Top labels rise, bottom values drop.
        int[] topRows = assignRows(xs, topW);
        int[] botRows = assignRows(xs, botW);
        for (int i = 0; i < n; i++) {
            double topBaseline = AXIS_Y - 14 - topRows[i] * ROW_STAGGER;            // above the line
            if (topMeasures[i] != null) {
                // Math event label: centre on the composite width, clamped in-frame like the plain path.
                double w = topMeasures[i].width();
                double originX = Math.max(CLAMP_MARGIN, Math.min(xs[i] - w / 2, W - CLAMP_MARGIN - w));
                MathLabel.emit(topMeasures[i], originX, topBaseline, textColor, TOP_SIZE, FONT, shapes);
            } else {
                centeredLabel(shapes, topText[i], xs[i], topBaseline, TOP_SIZE, textColor);
            }
            centeredLabel(shapes, botText[i], xs[i],
                AXIS_Y + 24 + botRows[i] * ROW_STAGGER, VALUE_SIZE, textColor);     // below the line
        }
        return new LaidOut(W, H, shapes);
    }

    /// Greedy 2-row assignment: walk the labels in x order; keep each row's right edge. Place a
    /// label on the first row whose last label clears it (left edge past that row's right edge +
    /// gap); if neither row is clear, use the row with the smaller right edge. Guarantees any two
    /// labels that overlap horizontally land on different rows, so their 2-D boxes stay disjoint.
    private static int[] assignRows(double[] centers, double[] widths) {
        int n = centers.length;
        int[] rows = new int[n];
        double[] rowRight = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < n; i++) {
            double half = widths[i] / 2;
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
        // Center on cx, then clamp both ends into [CLAMP_MARGIN, W-CLAMP_MARGIN-w] so an endpoint's
        // wide label (near x=MARGIN or x=W-MARGIN) can't overhang the canvas edge (GEOMETRY-ESCAPE
        // #2). Row de-collision above still runs on the true centers — only the emitted origin clamps.
        double originX = Math.max(CLAMP_MARGIN, Math.min(cx - w / 2, W - CLAMP_MARGIN - w));
        String d = FONT.textPathD(text, originX, baseline, size);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
