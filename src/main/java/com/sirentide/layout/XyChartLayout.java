package com.sirentide.layout;

import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Slice;
import com.sirentide.ir.XyChart;
import java.util.ArrayList;
import java.util.List;

/// Pure xychart (bar chart) layout: values → bar heights, categories → evenly-spaced columns.
/// Deterministic arithmetic, no graph optimization. Draws the x/y axes as lines, each bar as a
/// filled rect, and category + value labels as glyph paths (docs/DESIGN.md §4/§6).
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

    private static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    public static LaidOut layout(XyChart chart) {
        double plotLeft = ML;
        double plotRight = W - MR;
        double plotTop = MT;
        double plotBottom = H - MB;
        double plotW = plotRight - plotLeft;
        double plotH = plotBottom - plotTop;

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
            String fill = b.color() != null ? b.color() : PALETTE[i % PALETTE.length];
            shapes.add(new Rect(x, y, barW, h, fill));

            double cx = x + barW / 2;
            double categoryBaseline = plotBottom + 14;
            // Category label below the axis, ellipsized to its column slot so a long name doesn't
            // run into its neighbours (wrap-oracle wired in; docs/DESIGN.md §4).
            String category = FONT.ellipsize(b.label(), slot - 2, LABEL_SIZE);
            centeredLabel(shapes, category, cx, categoryBaseline, LABEL_SIZE, textColor);
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
