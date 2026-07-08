package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Slice;
import com.sirentide.ir.XyChart;
import java.util.ArrayList;
import java.util.List;

/// Pure xychart layout: values → geometry, categories → evenly-spaced columns. Deterministic
/// arithmetic, no graph optimization. Three render modes share the axis/tick machinery:
/// - `bars` (default): each value a filled rect rising from a signed zero baseline. A SINGLE-series
///   bar chart (the `series == null` legacy shape) takes {@link #layoutBars}, byte-identical to
///   before (the xychart golden is the proof).
/// - `line`: a small filled disc per point + N-1 connecting {@link Line} segments per series
///   (contract-clean — no polyline, no stroked path). A missing point BREAKS the segment.
/// - `scatter`: the discs only, no segments.
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
        if (chart.series() == null) {
            return layoutBars(chart, math);
        }
        return layoutMulti(chart, math);
    }

    /// The original single-series bar layout — UNCHANGED so its bake stays byte-identical (guarded
    /// by the xychart golden). Values → bar heights over a signed `[min(0,·), max(0,·)]` domain.
    private static LaidOut layoutBars(XyChart chart, MathFragmentRenderer math) {
        double plotLeft = ML;
        double plotRight = W - MR;
        double plotTop = MT;
        double plotBottom = H - MB;
        double plotW = plotRight - plotLeft;

        List<Shape> shapes = new ArrayList<>();

        // Off-slice text fill (category, y-tick, and per-bar value labels): the page-background text
        // colour, default `currentColor` so it inherits the host page's colour (light AND dark).
        String textColor = chart.textColor();
        List<Slice> bars = chart.bars();
        if (bars.isEmpty()) {
            // Axes only (no data): keep the classic bottom x-axis + left y-axis.
            shapes.add(new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, 1));
            shapes.add(new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1));
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
        shapes.add(new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1));      // y-axis
        shapes.add(new Line(plotLeft, baselineY, plotRight, baselineY, AXIS_STROKE, 1));    // x-axis (zero)

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
        for (int i = 0; i < n; i++) {
            Slice b = bars.get(i);
            double barEndY = axis.project(b.value(), plotBottom, plotTop);
            double y = Math.min(baselineY, barEndY);
            double h = Math.abs(baselineY - barEndY);
            double x = plotLeft + slot * i + (slot - barW) / 2;
            // Explicit per-item colour (canonical `#rrggbb` from the parser) overrides the palette.
            String fill = b.color() != null ? b.color() : Colors.PALETTE[i % Colors.PALETTE.length];
            shapes.add(new Rect(x, y, barW, h, fill));

            double cx = x + barW / 2;
            double categoryBaseline = plotBottom + 14;
            // Category label below the axis, ellipsized to its column slot so a long name doesn't
            // run into its neighbours (wrap-oracle wired in; docs/DESIGN.md §4).
            emitCategory(shapes, b.label(), cx, categoryBaseline, slot, textColor, math);
            // Value label at the bar's OUTER end: above a positive bar, below a descending one. For a
            // NEGATIVE bar the outer (bottom) end can reach the axis, so CLAMP the value label up to
            // stay clear of the category label below the axis (no stacked overlap).
            double valueSize = LABEL_SIZE - 1;
            double valueY = b.value() >= 0
                ? barEndY - 4
                : Math.min(barEndY + valueSize, categoryBaseline - valueSize - 2);
            centeredLabel(shapes, num(b.value()), cx, valueY, valueSize, textColor);
        }
        return new LaidOut(W, H, shapes);
    }

    /// Multi-series / line / scatter layout. Shares AxisScale, ticks, and category columns with the
    /// bar path but plots per-series: grouped rects (`bars`), or a disc-per-point plus per-series
    /// connecting segments (`line`) / discs alone (`scatter`). An optional left colour KEY (when
    /// `legend` is set AND there is more than one series) widens the canvas like the pie legend.
    private static LaidOut layoutMulti(XyChart chart, MathFragmentRenderer math) {
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

        if (nCat == 0 || seriesCount == 0) {
            shapes.add(new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1));
            shapes.add(new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, 1));
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
        shapes.add(new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1));
        shapes.add(new Line(plotLeft, baselineY, plotRight, baselineY, AXIS_STROKE, 1));

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
            layoutGroupedBars(shapes, series, bars, seriesCount, axis,
                plotLeft, plotBottom, plotTop, baselineY, slot, textColor, math);
        } else {
            layoutPoints(shapes, series, bars, seriesCount, axis, mode,
                plotLeft, plotBottom, plotTop, slot, textColor, math);
        }

        if (showLegend) {
            layoutKey(shapes, chart, seriesCount, canvasH);
        }
        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Grouped bars: each category slot is divided among the series with a {@link #GROUP_GAP} inner
    /// gap. Series colour by palette index; a missing value = no bar for that series there.
    private static void layoutGroupedBars(List<Shape> shapes, List<double[]> series, List<Slice> bars,
                                          int seriesCount, AxisScale axis, double plotLeft,
                                          double plotBottom, double plotTop, double baselineY,
                                          double slot, String textColor, MathFragmentRenderer math) {
        double groupW = slot * 0.6;
        double barW = Math.max(0.5, (groupW - (seriesCount - 1) * GROUP_GAP) / seriesCount);
        for (int i = 0; i < bars.size(); i++) {
            double[] row = series.get(i);
            double slotLeft = plotLeft + slot * i + (slot - groupW) / 2;
            for (int s = 0; s < row.length; s++) {
                double v = row[s];
                if (!Double.isFinite(v)) {
                    continue;
                }
                double endY = axis.project(v, plotBottom, plotTop);
                double y = Math.min(baselineY, endY);
                double h = Math.abs(baselineY - endY);
                double x = slotLeft + s * (barW + GROUP_GAP);
                shapes.add(new Rect(x, y, barW, h, Colors.PALETTE[s % Colors.PALETTE.length]));
            }
            double cx = plotLeft + slot * i + slot / 2;
            emitCategory(shapes, bars.get(i).label(), cx, plotBottom + 14, slot, textColor, math);
        }
    }

    /// Line / scatter points. Each present point is a full-circle {@link Wedge} disc; `line` mode
    /// also draws a {@link Line} segment between each pair of CONSECUTIVE categories where the series
    /// is present at BOTH — a missing point leaves a gap that breaks the line (never bridged, never
    /// zeroed). Segments are drawn before discs so a disc sits on top of its segment ends.
    private static void layoutPoints(List<Shape> shapes, List<double[]> series, List<Slice> bars,
                                     int seriesCount, AxisScale axis, String mode, double plotLeft,
                                     double plotBottom, double plotTop, double slot, String textColor,
                                     MathFragmentRenderer math) {
        boolean line = mode.equals("line");
        double dotR = line ? LINE_DOT_R : SCATTER_DOT_R;
        int nCat = bars.size();
        double[] px = new double[nCat];
        for (int i = 0; i < nCat; i++) {
            px[i] = plotLeft + slot * (i + 0.5);   // category column centre
        }
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
                    shapes.add(new Wedge(px[i], y, dotR, 0, 2 * Math.PI, col));
                }
            }
        }
        for (int i = 0; i < nCat; i++) {
            emitCategory(shapes, bars.get(i).label(), px[i], plotBottom + 14, slot, textColor, math);
        }
    }

    /// Emit a category (x-axis) label centred at `cx`. A `$…$` label bakes through the shared
    /// {@link MathLabel} seam (math skips the slot-ellipsize, centres on the composite width); a plain
    /// label is ellipsized to its column slot and centred — byte-identical to the pre-feature bake.
    private static void emitCategory(List<Shape> shapes, String raw, double cx, double baseline,
                                     double slot, String textColor, MathFragmentRenderer math) {
        if (math != null && MathLabel.hasMath(raw)) {
            MathLabel.Measured mm = MathLabel.measure(raw, LABEL_SIZE, FONT, math);
            MathLabel.emit(mm, cx - mm.width() / 2, baseline, textColor, LABEL_SIZE, FONT, shapes);
        } else {
            String cat = FONT.ellipsize(raw, slot - 2, LABEL_SIZE);
            centeredLabel(shapes, cat, cx, baseline, LABEL_SIZE, textColor);
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

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
