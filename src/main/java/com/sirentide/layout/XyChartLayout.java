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
    private static final String TEXT_FILL = "#334155";

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
        shapes.add(new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, 1));  // x-axis
        shapes.add(new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, 1));      // y-axis

        List<Slice> bars = chart.bars();
        double max = chart.maxValue();
        if (bars.isEmpty() || max <= 0) {
            return new LaidOut(W, H, shapes);
        }

        int n = bars.size();
        double slot = plotW / n;
        double barW = slot * 0.6;
        for (int i = 0; i < n; i++) {
            Slice b = bars.get(i);
            double h = (b.value() / max) * plotH;
            double x = plotLeft + slot * i + (slot - barW) / 2;
            double y = plotBottom - h;
            shapes.add(new Rect(x, y, barW, h, PALETTE[i % PALETTE.length]));

            double cx = x + barW / 2;
            centeredLabel(shapes, b.label(), cx, plotBottom + 14, LABEL_SIZE);   // category, below axis
            centeredLabel(shapes, num(b.value()), cx, y - 4, LABEL_SIZE - 1);     // value, atop the bar
        }
        return new LaidOut(W, H, shapes);
    }

    private static void centeredLabel(List<Shape> shapes, String text, double cx, double baseline, double size) {
        double w = FONT.runWidth(text, size);
        String d = FONT.textPathD(text, cx - w / 2, baseline, size);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, TEXT_FILL));
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
