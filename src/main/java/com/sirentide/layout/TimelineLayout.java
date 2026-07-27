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
    private static final double LABEL_GAP = 4;      // horizontal + vertical clearance between labels
    /// Max rendered width of an event (top) label before it ellipsizes. Without this a legal DSL of
    /// MAX_DATA_ROWS rows × MAX_LABEL_LEN-char labels builds multi-GB of glyph-path data (H2 OOM).
    /// ~120px (a quarter of the 480px canvas) matches the other layouts' established label-width cap
    /// (Gantt LABEL_COL slot, XyChart per-bar slot) and keeps two same-row labels legibly disjoint.
    private static final double MAX_LABEL_W = 120;
    /// In-frame clamp margin: the min gap kept between any glyph box and the canvas edge.
    private static final double CLAMP_MARGIN = 2;
    /// Maximum aggregate characters of Timeline label paths + guarded fragment markup that layout
    /// may materialize before the emitter's whole-document cap can run. Math-renderer failures
    /// deliberately degrade to raw `$…$` text (without ellipsizing a formula), so parser-scale legal
    /// input could otherwise retain gigabytes of fallback glyph paths in the LaidOut scene. This
    /// mirrors the 5 MB output choke point and throws the already-classified MAX_LAYOUT_WORK signal.
    private static final long MAX_TIMELINE_LABEL_WORK = 5_000_000;

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final String AXIS_STROKE = "#cbd5e1";

    public static LaidOut layout(Timeline timeline) {
        return layout(timeline, null);
    }

    /// Inline-math entry (plan sirentide-math-in-all-label-types): a `$…$` run in an EVENT label
    /// bakes through the shared {@link MathLabel} seam. A null `math` degrades every `$…$` to plain
    /// text — byte-identical to {@link #layout(Timeline)}.
    public static LaidOut layout(Timeline timeline, MathFragmentRenderer math) {
        // Both the event (top) and value/year (bottom) labels sit on the page background → the
        // page-background text colour, default `currentColor` (legible on light AND dark).
        String textColor = timeline.textColor();
        List<Slice> events = timeline.events();
        int n = events.size();
        if (n == 0) {
            AnchorAssigner assigner = new AnchorAssigner();
            return new LaidOut(W, H, List.of(new Group(assigner.assign(SirentideRole.AXIS, "time"),
                List.<Shape>of(new Line(MARGIN, AXIS_Y, W - MARGIN, AXIS_Y, AXIS_STROKE, 2)))));
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
        String[] fills = new String[n];
        LabelWorkBudget labelWork = new LabelWorkBudget();
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
                // Charge BEFORE retaining this measured result. At a renderer miss, a single legal
                // label can still yield a large raw glyph path, but the previous retained measures
                // stay below the cap and only this one bounded (MAX_LABEL_LEN) path is transient.
                labelWork.charge(measuredWork(mm, TOP_SIZE));
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
            fills[i] = e.color() != null ? e.color() : Colors.PALETTE[i % Colors.PALETTE.length];
        }

        // Build the EXACT post-clamp emitted label boxes at baseline zero. Horizontal bounds do not
        // change when a row moves vertically; keeping the baseline relative lets the same box drive
        // both compatibility validation and deterministic row spacing. Math is measured/rendered once
        // through its guarded seam; its box is the union of emitted text outlines + fragment metrics.
        LabelSpec[] topLabels = new LabelSpec[n];
        LabelSpec[] botLabels = new LabelSpec[n];
        for (int i = 0; i < n; i++) {
            topLabels[i] = labelSpec(topText[i], topMeasures[i], xs[i], TOP_SIZE,
                labelWork, topMeasures[i] != null);
            botLabels[i] = labelSpec(botText[i], null, xs[i], VALUE_SIZE,
                labelWork, false);
        }
        LabelIntervalPacker.Box[] topBoxes = boxes(topLabels);
        LabelIntervalPacker.Box[] botBoxes = boxes(botLabels);

        // COMPATIBILITY GATE: calculate the legacy two-row assignment first and keep the old
        // placement/canvas byte-for-byte whenever its ACTUAL emitted intervals + row envelopes are
        // clean. Only a failing band enters the unbounded-row interval partitioner.
        int[] legacyTopRows = assignRows(xs, topW);
        int[] legacyBotRows = assignRows(xs, botW);
        double[] legacyTopBaselines = {-14, -14 - ROW_STAGGER};
        double[] legacyBotBaselines = {24, 24 + ROW_STAGGER};
        boolean topClean = LabelIntervalPacker.rowsClean(topBoxes, legacyTopRows, LABEL_GAP)
            && LabelIntervalPacker.rowBandsDisjoint(topBoxes, legacyTopRows, legacyTopBaselines)
            && clearsAboveAxis(topBoxes, legacyTopRows, legacyTopBaselines);
        boolean botClean = LabelIntervalPacker.rowsClean(botBoxes, legacyBotRows, LABEL_GAP)
            && LabelIntervalPacker.rowBandsDisjoint(botBoxes, legacyBotRows, legacyBotBaselines)
            && clearsBelowAxis(botBoxes, legacyBotRows, legacyBotBaselines);

        int[] topRows;
        double[] topBaselines;
        if (topClean) {
            topRows = legacyTopRows;
            topBaselines = legacyTopBaselines;
        } else {
            LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(topBoxes, LABEL_GAP);
            topRows = packed.rows();
            topBaselines = LabelIntervalPacker.baselinesUp(
                topBoxes, topRows, packed.rowCount(),
                firstBaselineAboveAxis(topBoxes, topRows, -14), LABEL_GAP);
        }
        int[] botRows;
        double[] botBaselines;
        if (botClean) {
            botRows = legacyBotRows;
            botBaselines = legacyBotBaselines;
        } else {
            LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(botBoxes, LABEL_GAP);
            botRows = packed.rows();
            botBaselines = LabelIntervalPacker.baselinesDown(
                botBoxes, botRows, packed.rowCount(),
                firstBaselineBelowAxis(botBoxes, botRows, 24), LABEL_GAP);
        }

        // A failing top band can grow upward past y=0: shift the axis and every axis-relative shape
        // down just enough. Bottom growth extends the canvas only; numeric canvas growth never
        // allocates by pixel area. Clean current diagrams keep AXIS_Y/H exactly.
        double topMin = placedMinY(topBoxes, topRows, topBaselines);
        double axisShift = topMin == Double.POSITIVE_INFINITY
            ? 0 : Math.max(0, CLAMP_MARGIN - (AXIS_Y + topMin));
        double axisY = AXIS_Y + axisShift;
        double bottomMax = placedMaxY(botBoxes, botRows, botBaselines);
        double height = H + axisShift;
        if (bottomMax != Double.NEGATIVE_INFINITY) {
            height = Math.max(height, axisY + bottomMax + CLAMP_MARGIN);
        }
        // A guarded math fragment may legitimately be wider than the legacy 480 px canvas. Its
        // trusted metrics are numeric placement state, not a pixel allocation: grow only the canvas
        // edge needed to contain the already-clamped emitted box. Ordinary labels stay at W exactly.
        double labelMaxX = Math.max(maxX(topBoxes), maxX(botBoxes));
        double width = labelMaxX == Double.NEGATIVE_INFINITY
            ? W : Math.max(W, labelMaxX + CLAMP_MARGIN);

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();
        shapes.add(new Group(assigner.assign(SirentideRole.AXIS, "time"),
            List.<Shape>of(new Line(MARGIN, axisY, W - MARGIN, axisY, AXIS_STROKE, 2))));
        // Each event DOT remains the anchored element, in the exact legacy emit order. Deferred
        // labels stay bare, as before.
        for (int i = 0; i < n; i++) {
            shapes.add(new Group(assigner.assign(SirentideRole.EVENT, events.get(i).label()),
                List.<Shape>of(new Wedge(xs[i], axisY, DOT_R, 0, 2 * Math.PI, fills[i]))));
        }
        for (int i = 0; i < n; i++) {
            topLabels[i].emit(shapes, axisY + baseline(topRows[i], topBaselines), textColor);
            botLabels[i].emit(shapes, axisY + baseline(botRows[i], botBaselines), textColor);
        }
        return new LaidOut(width, height, shapes);
    }

    /// Legacy greedy 2-row assignment: walk the labels in X ORDER; keep each row's right edge. Place a
    /// label on the first row whose last label clears it (left edge past that row's right edge +
    /// gap); if neither row is clear, use the row with the smaller right edge. The least-bad fallback
    /// can overlap when three or more labels converge; the compatibility gate above detects that from
    /// actual emitted boxes and invokes {@link LabelIntervalPacker} only then.
    ///
    /// The greedy packing is only correct if the labels ARRIVE left-to-right. Events are placed by
    /// VALUE (AxisScale), so their centre-x follows declaration order only when the input happens to
    /// be value-sorted; a legal out-of-order declaration (e.g. `Launch:2023` before `Founded:2020`)
    /// otherwise processed a right-hand label before a left-hand one, defeating the greedy clear-check
    /// and letting two labels OVERLAP on the same row (SIR-10). Fix: process a STABLE sort of the
    /// indices by centre-x (declaration order breaks centre ties), and write each result back by its
    /// ORIGINAL index — so the returned array's ordering is unchanged, only the row VALUES differ.
    ///
    /// Package-visible so the correctness-fix test can assert the disjoint-row invariant directly on
    /// crafted centres/widths.
    static int[] assignRows(double[] centers, double[] widths) {
        int n = centers.length;
        int[] rows = new int[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // Arrays.sort on a boxed array is a guaranteed-stable merge sort → equal centres keep
        // declaration order, so the assignment stays deterministic + byte-stable.
        java.util.Arrays.sort(order, (a, b) -> Double.compare(centers[a], centers[b]));
        double[] rowRight = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int k = 0; k < n; k++) {
            int i = order[k];
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
            rows[i] = row;                                   // WRITE BACK by original index
            rowRight[row] = Math.max(rowRight[row], right);
        }
        return rows;
    }

    private record LabelSpec(String text, MathLabel.Measured measured, double originX, double size,
                             LabelIntervalPacker.Box relativeBox) {
        void emit(List<Shape> shapes, double baseline, String fill) {
            if (measured != null) {
                MathLabel.emit(measured, originX, baseline, fill, size, FONT, shapes);
                return;
            }
            String path = FONT.textPathD(text, originX, baseline, size);
            if (!path.isBlank()) {
                shapes.add(new GlyphRun(path, fill));
            }
        }
    }

    private static LabelSpec labelSpec(String text, MathLabel.Measured measured,
                                       double centerX, double size, LabelWorkBudget labelWork,
                                       boolean alreadyCharged) {
        double width = measured != null ? measured.width() : FONT.runWidth(text, size);
        // Exact legacy clamp: a clean diagram must retain the same origin and path bytes.
        double originX = Math.max(CLAMP_MARGIN,
            Math.min(centerX - width / 2, W - CLAMP_MARGIN - width));
        LabelIntervalPacker.Box box;
        if (measured != null) {
            box = mathBounds(measured, originX, size);
        } else {
            String path = FONT.textPathD(text, originX, 0, size);
            if (!alreadyCharged) {
                labelWork.charge(path.length());
            }
            box = LabelIntervalPacker.pathBounds(path);
        }
        return new LabelSpec(text, measured, originX, size, box);
    }

    private static long measuredWork(MathLabel.Measured measured, double size) {
        long work = 0;
        for (MathLabel.Resolved run : measured.runs()) {
            long runWork = run.fragment() != null
                ? run.fragment().innerSvg().length()
                : FONT.textPathD(run.text(), 0, 0, size).length();
            work = Math.addExact(work, runWork);
        }
        return work;
    }

    private static LabelIntervalPacker.Box mathBounds(MathLabel.Measured measured,
                                                       double originX, double size) {
        LabelIntervalPacker.Box union = null;
        double penX = originX;
        for (MathLabel.Resolved run : measured.runs()) {
            LabelIntervalPacker.Box box;
            if (run.fragment() != null) {
                box = new LabelIntervalPacker.Box(penX, -run.fragment().heightPx(),
                    penX + run.fragment().widthPx(), run.fragment().depthPx());
            } else {
                box = LabelIntervalPacker.pathBounds(
                    FONT.textPathD(run.text(), penX, 0, size));
            }
            if (box != null) {
                union = union == null ? box : union.union(box);
            }
            penX += run.advance();
        }
        return union;
    }

    private static LabelIntervalPacker.Box[] boxes(LabelSpec[] labels) {
        LabelIntervalPacker.Box[] boxes = new LabelIntervalPacker.Box[labels.length];
        for (int i = 0; i < labels.length; i++) {
            boxes[i] = labels[i].relativeBox();
        }
        return boxes;
    }

    private static double baseline(int row, double[] baselines) {
        return row >= 0 ? baselines[row] : baselines[0];
    }

    private static double placedMinY(LabelIntervalPacker.Box[] boxes, int[] rows,
                                     double[] baselines) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null) {
                min = Math.min(min, boxes[i].minY() + baselines[rows[i]]);
            }
        }
        return min;
    }

    private static double placedMaxY(LabelIntervalPacker.Box[] boxes, int[] rows,
                                     double[] baselines) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null) {
                max = Math.max(max, boxes[i].maxY() + baselines[rows[i]]);
            }
        }
        return max;
    }

    private static boolean clearsAboveAxis(LabelIntervalPacker.Box[] boxes, int[] rows,
                                           double[] baselines) {
        double max = placedMaxY(boxes, rows, baselines);
        return max == Double.NEGATIVE_INFINITY || max + LABEL_GAP <= -DOT_R;
    }

    private static boolean clearsBelowAxis(LabelIntervalPacker.Box[] boxes, int[] rows,
                                           double[] baselines) {
        double min = placedMinY(boxes, rows, baselines);
        return min == Double.POSITIVE_INFINITY || min >= DOT_R + LABEL_GAP;
    }

    private static double firstBaselineAboveAxis(LabelIntervalPacker.Box[] boxes, int[] rows,
                                                 double legacyBaseline) {
        double rowMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null && rows[i] == 0) {
                rowMax = Math.max(rowMax, boxes[i].maxY());
            }
        }
        return rowMax == Double.NEGATIVE_INFINITY ? legacyBaseline
            : Math.min(legacyBaseline, -DOT_R - LABEL_GAP - rowMax);
    }

    private static double firstBaselineBelowAxis(LabelIntervalPacker.Box[] boxes, int[] rows,
                                                 double legacyBaseline) {
        double rowMin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null && rows[i] == 0) {
                rowMin = Math.min(rowMin, boxes[i].minY());
            }
        }
        return rowMin == Double.POSITIVE_INFINITY ? legacyBaseline
            : Math.max(legacyBaseline, DOT_R + LABEL_GAP - rowMin);
    }

    private static double maxX(LabelIntervalPacker.Box[] boxes) {
        double max = Double.NEGATIVE_INFINITY;
        for (LabelIntervalPacker.Box box : boxes) {
            if (box != null) {
                max = Math.max(max, box.maxX());
            }
        }
        return max;
    }

    private static final class LabelWorkBudget {
        private long used;

        void charge(long amount) {
            if (amount < 0 || used > MAX_TIMELINE_LABEL_WORK - amount) {
                throw new IllegalStateException(
                    "MAX_LAYOUT_WORK exceeded: timeline label materialization passed "
                        + "MAX_TIMELINE_LABEL_WORK (" + MAX_TIMELINE_LABEL_WORK
                        + " path/fragment characters)");
            }
            used += amount;
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
