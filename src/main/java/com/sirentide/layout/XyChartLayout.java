package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.EmittedText;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Slice;
import com.sirentide.ir.XyChart;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Pure xychart layout: values → geometry, categories → evenly-spaced columns. Deterministic
/// arithmetic, no graph optimization. Three render modes share the axis/tick machinery:
/// - `bars` (default): each value a filled rect rising from a signed zero baseline. A SINGLE-series
///   bar chart (the `series == null` legacy shape) takes {@link #layoutBars}, byte-identical to
///   before (the xychart golden is the proof).
/// - `line`: a small filled disc per point + N-1 connecting {@link Line} segments per series
///   (contract-clean — no polyline, no stroked path). A missing point BREAKS the segment.
/// - `scatter`: the discs only, no segments.
/// - NUMERIC x (`xValues != null`, plan c880b12e): line or scatter with rows at their x on a
///   continuous axis, ticks from {@link NumericAxis} on both axes ({@link #layoutNumeric}). An `na`
///   gap (NaN in its row) gets no disc and no segment to or from it, so the line breaks there.
/// Multi-series (or any line/scatter) also supports an optional left colour KEY (mirrors the pie
/// legend geometry). Axes are `<line>`, discs are full-circle {@link Wedge}s, labels glyph paths
/// (docs/DESIGN.md §4/§6).
public final class XyChartLayout {

    private XyChartLayout() {}

    private static final double W = 320;
    private static final double H = 240;
    private static final double ML = 40;   // left margin (y-axis)
    private static final double MR = 20;
    private static final double MT = 20;
    private static final double MB = 40;   // bottom margin (x-axis + category labels)

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final double LABEL_SIZE = 11;
    private static final String AXIS_STROKE = "#94a3b8";

    // -- line / scatter geometry ------------------------------------------------
    /// Radius of a line-mode point disc.
    private static final double LINE_DOT_R = 3.0;
    /// Radius of a scatter-mode point disc (a touch larger — it stands alone with no segment).
    private static final double SCATTER_DOT_R = 3.5;
    /// Stroke width of a line-mode connecting segment.
    private static final double SEGMENT_WIDTH = 1.5;
    /// Inner gap between two grouped bars sharing a category slot.
    private static final double GROUP_GAP = 2.0;

    // -- legend (left colour key) geometry — mirrors PieLayout's constants ------
    private static final double KEY_WIDTH = 140;
    private static final double KEY_GAP = 20;
    private static final double KEY_ROW_HEIGHT = 22;
    private static final double SWATCH = 12;
    private static final double KEY_PAD_LEFT = 12;
    private static final double KEY_PAD_RIGHT = 8;
    private static final double KEY_PAD_TOP = 12;
    private static final double SWATCH_TEXT_GAP = 6;
    private static final double KEY_TEXT_MAX =
        KEY_WIDTH - KEY_PAD_LEFT - SWATCH - SWATCH_TEXT_GAP - KEY_PAD_RIGHT;

    /// Dispatches on the chart shape: the legacy single-series bar path (`series == null`, unchanged
    /// output) vs the multi-series / line / scatter path.
    public static LaidOut layout(XyChart chart) {
        return layout(chart, null);
    }

    /// Inline-math entry (plan sirentide-math-in-all-label-types): a `$…$` run in a CATEGORY (x-axis)
    /// label bakes through the shared {@link MathLabel} seam. A null `math` degrades every `$…$` to
    /// plain text — byte-identical to {@link #layout(XyChart)}. Numeric tick/value labels never carry
    /// math, so they stay on the plain path.
    public static LaidOut layout(XyChart chart, MathFragmentRenderer math) {
        return layout(chart, math, new LossRecorder());
    }

    /// The one layout body. Every category label it draws passes through {@link #emitCategory}, which
    /// records the label's fate in `losses`; {@link #categoryLabelLosses} reads that record, so the
    /// caveat is a by-product of drawing rather than a second computation that could drift from it.
    private static LaidOut layout(XyChart chart, MathFragmentRenderer math, LossRecorder losses) {
        if (chart.series() == null) {
            return layoutBars(chart, math, losses);
        }
        if (chart.xValues() != null) {
            return layoutNumeric(chart, losses);
        }
        return layoutMulti(chart, math, losses);
    }

    /// The original single-series bar layout — UNCHANGED so its bake stays byte-identical (guarded
    /// by the xychart golden). Values → bar heights over a signed `[min(0,·), max(0,·)]` domain.
    private static LaidOut layoutBars(XyChart chart, MathFragmentRenderer math, LossRecorder losses) {
        double plotLeft = ML;
        double plotRight = W - MR;
        double plotTop = MT;
        double plotBottom = H - MB;
        double plotW = plotRight - plotLeft;

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();

        // Off-slice text fill (category, y-tick, and per-bar value labels): the page-background text
        // colour, default `currentColor` so it inherits the host page's colour (light AND dark).
        String textColor = chart.textColor();
        List<Slice> bars = chart.bars();
        if (bars.isEmpty()) {
            // Axes only (no data): keep the classic bottom x-axis + left y-axis.
            shapes.add(axis(assigner, "x",
                new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, 1)));
            shapes.add(axis(assigner, "y",
                new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1)));
            return new LaidOut(W, H, shapes);
        }

        // A SIGNED y-domain with a zero baseline: `[min(0, minValue), max(0, maxValue)]`. Positive
        // bars rise from the baseline, NEGATIVE bars DESCEND below it (never clamped to zero — that
        // silently blanked an all-negative chart). `project(v, plotBottom, plotTop)` maps the domain
        // min to the plot bottom and the max to the top, so higher values sit higher.
        AxisScale axis = new AxisScale(Math.min(0, chart.minValue()), Math.max(0, chart.maxValue()));
        double baselineY = axis.project(0, plotBottom, plotTop);

        // y-axis (full height) + the zero baseline as the x-axis (which may sit mid-plot for
        // mixed-sign data, at the top for all-negative, at the bottom for all-positive).
        shapes.add(axis(assigner, "y",
            new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1)));      // y-axis
        shapes.add(axis(assigner, "x",
            new Line(plotLeft, baselineY, plotRight, baselineY, AXIS_STROKE, 1)));    // x-axis (zero)

        // y-axis scale: nice 1-2-5 tick marks + numeric labels (was missing entirely — no y-scale).
        for (double tick : axis.ticks()) {
            double ty = axis.project(tick, plotBottom, plotTop);
            shapes.add(new Line(plotLeft - 4, ty, plotLeft, ty, AXIS_STROKE, 1));           // tick mark
            String tlabel = num(tick);
            double tw = FONT.runWidth(tlabel, LABEL_SIZE - 2);
            String td = FONT.textPathD(tlabel, plotLeft - 6 - tw, ty + (LABEL_SIZE - 2) * 0.35, LABEL_SIZE - 2);
            if (!td.isBlank()) {
                shapes.add(new GlyphRun(td, textColor));
            }
        }

        int n = bars.size();
        double slot = plotW / n;
        double barW = slot * 0.6;
        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): each bar → ONE `<g role="bar">`
        // wrapping its rect + category label + value label (all emitted contiguously per bar). seq runs
        // 2..N+1 in bar order (after the y/x axes); id from the category label.
        for (int i = 0; i < n; i++) {
            Slice b = bars.get(i);
            double barEndY = axis.project(b.value(), plotBottom, plotTop);
            double y = Math.min(baselineY, barEndY);
            double h = Math.abs(baselineY - barEndY);
            double x = plotLeft + slot * i + (slot - barW) / 2;
            // Explicit per-item colour (canonical `#rrggbb` from the parser) overrides the palette.
            String fill = b.color() != null ? b.color() : Colors.PALETTE[i % Colors.PALETTE.length];
            List<Shape> bg = new ArrayList<>();
            bg.add(new Rect(x, y, barW, h, fill));

            double cx = x + barW / 2;
            double categoryBaseline = plotBottom + 14;
            // Category label below the axis, ellipsized to its column slot so a long name doesn't
            // run into its neighbours (wrap-oracle wired in; docs/DESIGN.md §4).
            emitCategory(bg, b.label(), cx, categoryBaseline, slot, textColor, math, losses);
            // Value label at the bar's OUTER end: above a positive bar, below a descending one. For a
            // NEGATIVE bar the outer (bottom) end can reach the axis, so CLAMP the value label up to
            // stay clear of the category label below the axis (no stacked overlap).
            double valueSize = LABEL_SIZE - 1;
            double valueY = b.value() >= 0
                ? barEndY - 4
                : Math.min(barEndY + valueSize, categoryBaseline - valueSize - 2);
            centeredLabel(bg, num(b.value()), cx, valueY, valueSize, textColor);
            shapes.add(new Group(assigner.assign(SirentideRole.BAR, b.label()), bg));
        }
        return new LaidOut(W, H, shapes);
    }

    /// Multi-series / line / scatter layout. Shares AxisScale, ticks, and category columns with the
    /// bar path but plots per-series: grouped rects (`bars`), or a disc-per-point plus per-series
    /// connecting segments (`line`) / discs alone (`scatter`). An optional left colour KEY (when
    /// `legend` is set AND there is more than one series) widens the canvas like the pie legend.
    private static LaidOut layoutMulti(XyChart chart, MathFragmentRenderer math, LossRecorder losses) {
        String textColor = chart.textColor();
        String mode = chart.mode();
        List<Slice> bars = chart.bars();          // category labels only
        List<double[]> series = chart.series();
        int nCat = bars.size();

        int seriesCount = 0;
        for (double[] row : series) {
            seriesCount = Math.max(seriesCount, row.length);
        }

        boolean showLegend = chart.legend() && seriesCount > 1;
        double keyShift = showLegend ? KEY_WIDTH + KEY_GAP : 0;

        double canvasW = keyShift + W;
        double canvasH = H;
        if (showLegend) {
            // Tall series sets grow the canvas so key rows never spill past the plot box.
            double keyBlockH = seriesCount * KEY_ROW_HEIGHT;
            canvasH = Math.max(H, keyBlockH + 2 * KEY_PAD_TOP);
        }

        double plotLeft = keyShift + ML;
        double plotRight = canvasW - MR;
        double plotTop = MT;
        double plotBottom = canvasH - MB;
        double plotW = plotRight - plotLeft;

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();

        if (nCat == 0 || seriesCount == 0) {
            shapes.add(axis(assigner, "y",
                new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1)));
            shapes.add(axis(assigner, "x",
                new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, 1)));
            return new LaidOut(canvasW, canvasH, shapes);
        }

        // Domain = min/max across ALL series values. Grouped bars force a zero baseline so bars grow
        // from zero (signed — negatives descend); line/scatter fit the pure data range with the
        // x-axis drawn at the plot bottom as a reference edge.
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double[] row : series) {
            for (double v : row) {
                if (Double.isFinite(v)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
        }
        if (lo > hi) {   // no finite value seen
            lo = 0;
            hi = 0;
        }
        boolean grouped = mode.equals("bars");
        AxisScale axis;
        if (grouped) {
            // Grouped bars keep the SIGNED zero-baseline domain — unchanged, byte-identical output.
            axis = new AxisScale(Math.min(0, lo), Math.max(0, hi));
        } else {
            // Line / scatter: PAD the value domain by 5% of the span on each end so a min-value point
            // doesn't sit ON the plot floor and a max-value point ON the ceiling (dots visually clipped
            // against the axis edge). Bars never pad (they grow from a fixed zero baseline, above).
            // DEGENERATE span (all values equal, or a single point → span 0): there is no span to take
            // 5% of, so pad by max(1.0, half the |value|). The 1.0 floor keeps a near-zero value clear
            // of the edges; half the magnitude keeps a large single value visibly inset rather than
            // pinned to the plot midpoint's neighbours. Ticks stay within the PADDED [min,max].
            double span = hi - lo;
            double pad = span > 0 ? 0.05 * span : Math.max(1.0, 0.5 * Math.abs(lo));
            axis = new AxisScale(lo - pad, hi + pad);
        }
        double baselineY = grouped ? axis.project(0, plotBottom, plotTop) : plotBottom;

        // y-axis (full height) + the baseline x-axis.
        shapes.add(axis(assigner, "y",
            new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1)));
        shapes.add(axis(assigner, "x",
            new Line(plotLeft, baselineY, plotRight, baselineY, AXIS_STROKE, 1)));

        for (double tick : axis.ticks()) {
            double ty = axis.project(tick, plotBottom, plotTop);
            shapes.add(new Line(plotLeft - 4, ty, plotLeft, ty, AXIS_STROKE, 1));
            String tlabel = num(tick);
            double tw = FONT.runWidth(tlabel, LABEL_SIZE - 2);
            String td = FONT.textPathD(tlabel, plotLeft - 6 - tw, ty + (LABEL_SIZE - 2) * 0.35, LABEL_SIZE - 2);
            if (!td.isBlank()) {
                shapes.add(new GlyphRun(td, textColor));
            }
        }

        double slot = plotW / nCat;
        if (grouped) {
            layoutGroupedBars(shapes, assigner, series, bars, seriesCount, axis,
                plotLeft, plotBottom, plotTop, baselineY, slot, textColor, math, losses);
        } else {
            layoutPoints(shapes, assigner, series, bars, seriesCount, axis, mode,
                plotLeft, plotBottom, plotTop, slot, textColor, math, losses);
        }

        if (showLegend) {
            layoutKey(shapes, chart, seriesCount, canvasH);
        }
        return new LaidOut(canvasW, canvasH, shapes);
    }

    // -- numeric x axis (plan c880b12e, numeric-x slice) ---------------------------------------
    /// Canvas of a numeric-x chart (before any legend shift): wider than the category chart's 320 so a
    /// dense series has room. A numeric chart is a NEW render, so these never move a category byte.
    private static final double NUM_W = 480;
    private static final double NUM_H = 300;
    private static final double NUM_MR = 24;
    private static final double NUM_MB = 40;
    /// Fraction of the x span added at each end so an end point is not drawn on the y axis.
    private static final double NUM_X_PAD = 0.03;
    /// Same for y (matches the category line/scatter 5%).
    private static final double NUM_Y_PAD = 0.05;
    private static final double TICK_SIZE = LABEL_SIZE - 2;

    /// A numeric-x line or scatter chart: rows at their x on a continuous axis. Both axes take their
    /// ticks from {@link NumericAxis} (nice 1/2/5 steps, thinned to fit, plain decimals); the left
    /// margin grows to the widest y label. Disc size shrinks with point density so a dense series
    /// stays a readable line of points rather than a smear: radius is 35% (line) or 45% (scatter) of
    /// the MEAN x spacing, so evenly spaced neighbours never overlap (unevenly spaced ones can, where
    /// they really are that close), floored at 1px (line, where the segments carry the shape) or
    /// 1.5px (scatter), and capped at the category sizes. Every drawn tick label is recorded, and so is any label dropped (the caveat reads it).
    private static LaidOut layoutNumeric(XyChart chart, LossRecorder rec) {
        String textColor = chart.textColor();
        boolean line = chart.mode().equals("line");
        List<Slice> rows = chart.bars();
        List<double[]> series = chart.series();
        double[] xs = chart.xValues();
        int n = rows.size();
        int seriesCount = 0;
        for (double[] row : series) {
            seriesCount = Math.max(seriesCount, row.length);
        }
        if (chart.seriesNames() != null) {
            seriesCount = Math.max(seriesCount, chart.seriesNames().size());
        }
        boolean showLegend = chart.legend() && seriesCount > 1;
        double keyShift = showLegend ? KEY_WIDTH + KEY_GAP : 0;
        double canvasW = keyShift + NUM_W;
        double canvasH = showLegend ? Math.max(NUM_H, seriesCount * KEY_ROW_HEIGHT + 2 * KEY_PAD_TOP) : NUM_H;
        double plotTop = MT;
        double plotBottom = canvasH - NUM_MB;

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();
        if (n == 0 || seriesCount == 0) {
            double plotLeft = keyShift + ML;
            shapes.add(axis(assigner, "y", new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1)));
            shapes.add(axis(assigner, "x",
                new Line(plotLeft, plotBottom, canvasW - NUM_MR, plotBottom, AXIS_STROKE, 1)));
            return new LaidOut(canvasW, canvasH, shapes);
        }

        // An `na` gap is NaN in its row: it has no value, so it takes no part in the y domain (and
        // pointY gives it no point, so no segment reaches it: the line breaks there).
        double ylo = Double.POSITIVE_INFINITY;
        double yhi = Double.NEGATIVE_INFINITY;
        for (double[] row : series) {
            for (double v : row) {
                if (!Double.isNaN(v)) {
                    ylo = Math.min(ylo, v);
                    yhi = Math.max(yhi, v);
                }
            }
        }
        if (ylo > yhi) {   // every value is a gap
            ylo = 0;
            yhi = 0;
        }
        double[] yd = NumericAxis.pad(ylo, yhi, NUM_Y_PAD);
        AxisScale yAxis = new AxisScale(yd[0], yd[1]);
        NumericAxis.Fit yFit = NumericAxis.fitVertical(yd[0], yd[1], plotBottom, plotTop, TICK_SIZE);
        double widestY = 0;
        for (NumericAxis.Placed p : yFit.placed()) {
            widestY = Math.max(widestY, FONT.runWidth(p.tick().label(), TICK_SIZE));
        }
        double plotLeft = keyShift + Math.max(ML, widestY + 10);
        double plotRight = canvasW - NUM_MR;

        double[] xd = NumericAxis.pad(xs[0], xs[n - 1], NUM_X_PAD);
        AxisScale xAxis = new AxisScale(xd[0], xd[1]);
        NumericAxis.Fit xFit = NumericAxis.fitHorizontal(xd[0], xd[1], plotLeft, plotRight, keyShift, canvasW,
            label -> FONT.runWidth(label, TICK_SIZE));

        shapes.add(axis(assigner, "y", new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1)));
        shapes.add(axis(assigner, "x", new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, 1)));
        for (NumericAxis.Placed p : yFit.placed()) {
            double ty = p.px();
            shapes.add(new Line(plotLeft - 4, ty, plotLeft, ty, AXIS_STROKE, 1));
            String tlabel = p.tick().label();
            double tw = FONT.runWidth(tlabel, TICK_SIZE);
            String td = FONT.textPathD(tlabel, plotLeft - 6 - tw, ty + TICK_SIZE * 0.35, TICK_SIZE);
            if (!td.isBlank()) {
                shapes.add(new GlyphRun(td, textColor));
            }
            rec.drawnY.add(tlabel);
        }
        for (NumericAxis.Placed p : xFit.placed()) {
            shapes.add(new Line(p.px(), plotBottom, p.px(), plotBottom + 4, AXIS_STROKE, 1));
            if (!p.labelled()) {
                continue;
            }
            String td = FONT.textPathD(p.tick().label(), p.labelLeft(), plotBottom + 16, TICK_SIZE);
            if (!td.isBlank()) {
                shapes.add(new GlyphRun(td, textColor));
            }
            rec.drawnX.add(p.tick().label());
        }
        rec.droppedTicks.addAll(xFit.dropped());
        rec.droppedTicks.addAll(yFit.dropped());

        double[] px = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = xAxis.project(xs[i], plotLeft, plotRight);
        }
        double spacing = n > 1 ? (px[n - 1] - px[0]) / (n - 1) : Double.POSITIVE_INFINITY;
        double dotR = line
            ? Math.min(LINE_DOT_R, Math.max(1.0, 0.35 * spacing))
            : Math.min(SCATTER_DOT_R, Math.max(1.5, 0.45 * spacing));
        for (int s = 0; s < seriesCount; s++) {
            String col = Colors.PALETTE[s % Colors.PALETTE.length];
            if (line) {
                for (int i = 0; i + 1 < n; i++) {
                    Double y0 = pointY(series.get(i), s, yAxis, plotBottom, plotTop);
                    Double y1 = pointY(series.get(i + 1), s, yAxis, plotBottom, plotTop);
                    if (y0 != null && y1 != null) {
                        shapes.add(new Line(px[i], y0, px[i + 1], y1, col, SEGMENT_WIDTH));
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                Double y = pointY(series.get(i), s, yAxis, plotBottom, plotTop);
                if (y != null) {
                    shapes.add(new Group(assigner.assign(SirentideRole.BAR, rows.get(i).label()),
                        List.<Shape>of(new Wedge(px[i], y, dotR, 0, 2 * Math.PI, col))));
                }
            }
        }
        if (showLegend) {
            layoutKey(shapes, chart, seriesCount, canvasH);
        }
        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Grouped bars: each category slot is divided among the series with a {@link #GROUP_GAP} inner
    /// gap. Series colour by palette index; a missing value = no bar for that series there.
    private static void layoutGroupedBars(List<Shape> shapes, AnchorAssigner assigner,
                                          List<double[]> series, List<Slice> bars,
                                          int seriesCount, AxisScale axis, double plotLeft,
                                          double plotBottom, double plotTop, double baselineY,
                                          double slot, String textColor, MathFragmentRenderer math,
                                          LossRecorder losses) {
        double groupW = slot * 0.6;
        double barW = Math.max(0.5, (groupW - (seriesCount - 1) * GROUP_GAP) / seriesCount);
        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): each category COLUMN → ONE
        // `<g role="bar">` wrapping its series rects + the category label (all emitted contiguously per
        // column). seq follows the two axis groups in column order; id comes from the category label.
        // (A grouped column holds one bar per series; the group is the column, matching the
        // single-series case where a column IS one bar.)
        for (int i = 0; i < bars.size(); i++) {
            double[] row = series.get(i);
            double slotLeft = plotLeft + slot * i + (slot - groupW) / 2;
            List<Shape> cg = new ArrayList<>();
            for (int s = 0; s < row.length; s++) {
                double v = row[s];
                if (!Double.isFinite(v)) {
                    continue;
                }
                double endY = axis.project(v, plotBottom, plotTop);
                double y = Math.min(baselineY, endY);
                double h = Math.abs(baselineY - endY);
                double x = slotLeft + s * (barW + GROUP_GAP);
                cg.add(new Rect(x, y, barW, h, Colors.PALETTE[s % Colors.PALETTE.length]));
            }
            double cx = plotLeft + slot * i + slot / 2;
            emitCategory(cg, bars.get(i).label(), cx, plotBottom + 14, slot, textColor, math, losses);
            shapes.add(new Group(assigner.assign(SirentideRole.BAR, bars.get(i).label()), cg));
        }
    }

    /// Line / scatter points. Each present point is a full-circle {@link Wedge} disc; `line` mode
    /// also draws a {@link Line} segment between each pair of CONSECUTIVE categories where the series
    /// is present at BOTH — a missing point leaves a gap that breaks the line (never bridged, never
    /// zeroed). Segments are drawn before discs so a disc sits on top of its segment ends.
    private static void layoutPoints(List<Shape> shapes, AnchorAssigner assigner,
                                     List<double[]> series, List<Slice> bars,
                                     int seriesCount, AxisScale axis, String mode, double plotLeft,
                                     double plotBottom, double plotTop, double slot, String textColor,
                                     MathFragmentRenderer math, LossRecorder losses) {
        boolean line = mode.equals("line");
        double dotR = line ? LINE_DOT_R : SCATTER_DOT_R;
        int nCat = bars.size();
        double[] px = new double[nCat];
        for (int i = 0; i < nCat; i++) {
            px[i] = plotLeft + slot * (i + 0.5);   // category column centre
        }
        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): each PRESENT point disc → ONE
        // `<g role="bar">` (id = its category label, uniquified across series). Connecting SEGMENTS
        // (line mode) and category labels stay bare — a segment spans two points and belongs to neither,
        // exactly as a pie leader line stays bare. Wrapping each disc in place preserves emit order.
        for (int s = 0; s < seriesCount; s++) {
            String col = Colors.PALETTE[s % Colors.PALETTE.length];
            if (line) {
                for (int i = 0; i + 1 < nCat; i++) {
                    Double y0 = pointY(series.get(i), s, axis, plotBottom, plotTop);
                    Double y1 = pointY(series.get(i + 1), s, axis, plotBottom, plotTop);
                    if (y0 != null && y1 != null) {
                        shapes.add(new Line(px[i], y0, px[i + 1], y1, col, SEGMENT_WIDTH));
                    }
                }
            }
            for (int i = 0; i < nCat; i++) {
                Double y = pointY(series.get(i), s, axis, plotBottom, plotTop);
                if (y != null) {
                    shapes.add(new Group(assigner.assign(SirentideRole.BAR, bars.get(i).label()),
                        List.<Shape>of(new Wedge(px[i], y, dotR, 0, 2 * Math.PI, col))));
                }
            }
        }
        for (int i = 0; i < nCat; i++) {
            emitCategory(shapes, bars.get(i).label(), px[i], plotBottom + 14, slot, textColor, math, losses);
        }
    }

    /// The category (x-axis) labels this chart cannot show in full (plan c880b12e). `dropped` are labels
    /// that drew NOTHING (not even an ellipsis fitted the column slot); `shortened` are labels drawn
    /// ellipsized. Both lists are in category order.
    ///
    /// ONE SOURCE OF TRUTH (review F1): this LAYS THE CHART OUT and returns what {@link #emitCategory}
    /// recorded while drawing. It used to replay the slot arithmetic and the ellipsize call in parallel,
    /// guarded only by an agreement test on the single-series bar path, so a slot change in the
    /// line/scatter path drifted the drawing away from the caveat with every test green. Now there is
    /// no second computation to drift: whatever slot a drawing path hands to `emitCategory` is the slot
    /// the caveat reports on. The layout runs with glyph-emission capture SUSPENDED, so this second
    /// pass contributes nothing to the font-coverage corpus of the render that asked.
    ///
    /// `math` IS THE RENDERER THE REAL LAYOUT GOT (F2 of the cli-math-batch rebuild ruling), because
    /// whether a `$…$` label CAN be lost depends on it, and {@link #emitCategory} decides that on
    /// `math != null` alone. WITHOUT a renderer a `$…$` label is drawn through the plain ellipsize like
    /// any other label, so it is recorded like any other: 30 `$x_{i}$` labels that the default bake drew
    /// as nothing used to pass `--strict` (the 05e243d and F1 skip). WITH one it is typeset and never
    /// ellipsized, so it is not a slot loss (a run that fails to typeset is drawn as its raw source,
    /// un-ellipsized: the CLI names that as untypeset math).
    ///
    /// The second pass does NOT call the caller's renderer: it lays out with {@link #ROUTE_AS_MATH},
    /// a renderer that typesets nothing. That is exact, not an approximation: the renderer reaches
    /// nothing in the layout but the `math != null` branch in `emitCategory` (the slot never depends on
    /// it), and on that branch nothing is recorded whatever the renderer returns. Passing the real one
    /// would typeset every math label a second time (a LatteX call per run) for a result it cannot change.
    /// Empty when there are no category labels drawn (an empty chart, or a multi-series chart with no
    /// values).
    public static LabelLosses categoryLabelLosses(XyChart chart, MathFragmentRenderer math) {
        LossRecorder losses = new LossRecorder();
        boolean suspended = EmittedText.enterPlainRender();
        try {
            layout(chart, math == null ? null : ROUTE_AS_MATH, losses);
        } finally {
            EmittedText.exitPlainRender(suspended);
        }
        return new LabelLosses(List.copyOf(losses.dropped), List.copyOf(losses.shortened),
            List.copyOf(losses.droppedTicks));
    }

    /// The tick labels a NUMERIC-x chart draws, axis by axis, in ascending value order (plan c880b12e).
    /// Empty lists for a category chart, which has no numeric x ticks (its y ticks are not recorded).
    public record DrawnTicks(List<String> x, List<String> y) {
        public DrawnTicks {
            x = List.copyOf(x);
            y = List.copyOf(y);
        }
    }

    /// Lays the chart out and returns the tick labels {@link #layoutNumeric} DREW, read from the same
    /// record the draw writes (one source of truth, as for {@link #categoryLabelLosses}). Glyph capture
    /// is suspended, so this pass adds nothing to a render's font-coverage corpus.
    public static DrawnTicks drawnTicks(XyChart chart) {
        LossRecorder rec = new LossRecorder();
        boolean suspended = EmittedText.enterPlainRender();
        try {
            layout(chart, null, rec);
        } finally {
            EmittedText.exitPlainRender(suspended);
        }
        return new DrawnTicks(rec.drawnX, rec.drawnY);
    }

    /// Stands in for an active renderer in {@link #categoryLabelLosses}' pass: non-null, so a `$…$`
    /// label takes the same typeset branch the real render took, and empty, so nothing is typeset.
    private static final MathFragmentRenderer ROUTE_AS_MATH = (latex, fontSizePx) -> Optional.empty();

    /// The fate of each category label drawn through the plain ellipsize, written by
    /// {@link #emitCategory} as it draws.
    private static final class LossRecorder {
        private final List<String> dropped = new ArrayList<>();
        private final List<String> shortened = new ArrayList<>();
        /// Numeric-axis tick labels left off because they could not be drawn whole (see NumericAxis).
        private final List<String> droppedTicks = new ArrayList<>();
        /// Numeric-axis tick labels drawn, per axis (read by {@link #drawnTicks}).
        private final List<String> drawnX = new ArrayList<>();
        private final List<String> drawnY = new ArrayList<>();

        /// `raw` is the label as authored, `drawn` what the slot let through ellipsize.
        void record(String raw, String drawn) {
            if (drawn.isEmpty() && !raw.isEmpty()) {
                dropped.add(raw);
            } else if (!drawn.equals(raw)) {
                shortened.add(raw);
            }
        }
    }

    /// Category labels lost to their column slot: drawn as nothing, or drawn ellipsized. On a
    /// NUMERIC-x chart there are no category labels; `droppedTicks` names any numeric tick label that
    /// could not be drawn whole (empty unless NumericAxis took its one loss path).
    public record LabelLosses(List<String> dropped, List<String> shortened, List<String> droppedTicks) {
        public boolean isEmpty() {
            return dropped.isEmpty() && shortened.isEmpty() && droppedTicks.isEmpty();
        }
    }

    /// Emit a category (x-axis) label centred at `cx`. A `$…$` label bakes through the shared
    /// {@link MathLabel} seam (math skips the slot-ellipsize, centres on the composite width); a plain
    /// label is ellipsized to its column slot and centred — byte-identical to the pre-feature bake —
    /// and its fate (kept, shortened, dropped) goes to `losses`, the record {@link #categoryLabelLosses}
    /// reports from. EVERY label on the plain branch is recorded, a `$…$` one included (without a
    /// renderer it is drawn as plain text and can be lost like any other); a label on the typeset
    /// branch is never ellipsized and is not recorded (see categoryLabelLosses).
    private static void emitCategory(List<Shape> shapes, String raw, double cx, double baseline,
                                     double slot, String textColor, MathFragmentRenderer math,
                                     LossRecorder losses) {
        if (math != null && MathLabel.hasMath(raw)) {
            MathLabel.Measured mm = MathLabel.measure(raw, LABEL_SIZE, FONT, math);
            MathLabel.emit(mm, cx - mm.width() / 2, baseline, textColor, LABEL_SIZE, FONT, shapes);
        } else {
            String cat = FONT.ellipsize(raw, slot - 2, LABEL_SIZE);
            centeredLabel(shapes, cat, cx, baseline, LABEL_SIZE, textColor);
            losses.record(raw, cat);
        }
    }

    /// The projected y of series `s` at a category row, or `null` when the series has NO point there
    /// (a shorter row = trailing series absent; a non-finite value is likewise absent).
    private static Double pointY(double[] row, int s, AxisScale axis, double plotBottom, double plotTop) {
        if (s >= row.length || !Double.isFinite(row[s])) {
            return null;
        }
        return axis.project(row[s], plotBottom, plotTop);
    }

    /// The left colour KEY: one swatch + series name per series, vertically centred, mirroring the
    /// pie legend. Series names default to `Series 1..N` when the DSL did not name them.
    private static void layoutKey(List<Shape> shapes, XyChart chart, int seriesCount, double canvasH) {
        String textColor = chart.textColor();
        List<String> names = chart.seriesNames();
        double keyBlockH = seriesCount * KEY_ROW_HEIGHT;
        double keyTop = (canvasH - keyBlockH) / 2;
        double textX = KEY_PAD_LEFT + SWATCH + SWATCH_TEXT_GAP;
        for (int s = 0; s < seriesCount; s++) {
            String col = Colors.PALETTE[s % Colors.PALETTE.length];
            double rowTop = keyTop + s * KEY_ROW_HEIGHT;
            shapes.add(new Rect(KEY_PAD_LEFT, rowTop + (KEY_ROW_HEIGHT - SWATCH) / 2,
                SWATCH, SWATCH, col));
            String name = (names != null && s < names.size()) ? names.get(s) : "Series " + (s + 1);
            String text = FONT.ellipsize(name, KEY_TEXT_MAX, LABEL_SIZE);
            double baseline = rowTop + KEY_ROW_HEIGHT / 2 + LABEL_SIZE * 0.35;
            String d = FONT.textPathD(text, textX, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, textColor));
            }
        }
    }

    private static void centeredLabel(List<Shape> shapes, String text, double cx, double baseline,
                                      double size, String fill) {
        double w = FONT.runWidth(text, size);
        String d = FONT.textPathD(text, cx - w / 2, baseline, size);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }

    /// Wrap one physical chart-axis line in its semantic group. Tick marks and labels remain in their
    /// original positions in the flat emit stream; the anchor targets the stable axis spine itself.
    private static Group axis(AnchorAssigner assigner, String id, Line line) {
        return new Group(assigner.assign(SirentideRole.AXIS, id), List.<Shape>of(line));
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
