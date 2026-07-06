package com.sirentide.layout;

import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.Task;
import java.util.ArrayList;
import java.util.List;

/// Pure gantt layout: each task is a horizontal bar whose x/width map its [start, end] span onto
/// a shared time axis, stacked one row per task. Deterministic arithmetic, no optimization. Task
/// labels are left-aligned glyph paths; a baseline time axis is a line.
public final class GanttLayout {

    private GanttLayout() {}

    private static final double W = 480;
    private static final double LABEL_COL = 100;   // left column for task labels
    private static final double MR = 20;
    private static final double TOP = 24;
    private static final double ROW_H = 30;
    private static final double BOTTOM = 24;

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final double LABEL_SIZE = 11;
    private static final String AXIS_STROKE = "#cbd5e1";
    private static final String LABEL_FILL = "#334155";

    private static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    public static LaidOut layout(Gantt gantt) {
        List<Task> tasks = gantt.tasks();
        int n = tasks.size();
        double height = TOP + n * ROW_H + BOTTOM;
        double plotLeft = LABEL_COL;
        double plotRight = W - MR;
        double plotW = plotRight - plotLeft;

        List<Shape> shapes = new ArrayList<>();
        double domainMin = gantt.start();
        double domainMax = gantt.end();
        // Degenerate/empty axis: no finite span to place bars against.
        if (n == 0 || domainMax <= domainMin) {
            return new LaidOut(W, Math.max(height, 60), shapes);
        }
        // Min-normalized time axis: `x = project(start)`, NOT `start / maxEnd`. Without the min the
        // domain silently started at 0, so absolute-date tasks (start ≫ 0) collapsed to sub-pixel
        // slivers crammed at the right edge — an empty-looking chart. AxisScale carries both ends.
        AxisScale axis = new AxisScale(domainMin, domainMax);
        double minVisibleW = 3;   // a zero/negative-span task still shows as a visible marker bar
        for (int i = 0; i < n; i++) {
            Task t = tasks.get(i);
            double rowY = TOP + i * ROW_H;
            double barY = rowY + 6;
            double barH = ROW_H - 12;
            double x = axis.project(t.start(), plotLeft, plotRight);
            double w = axis.project(t.end(), plotLeft, plotRight) - x;
            // A malformed `end <= start` yields a non-positive width. Draw a visible min-width marker
            // bar at the start instead of an invisible zero-width one (loud-not-silent, DESIGN §6).
            if (w < minVisibleW) {
                w = minVisibleW;
            }
            shapes.add(new Rect(x, barY, w, barH, PALETTE[i % PALETTE.length]));

            double baseline = barY + barH * 0.5 + LABEL_SIZE * 0.35;   // vertically centred on the bar
            String d = FONT.textPathD(t.label(), 12, baseline, LABEL_SIZE);   // left-aligned
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, LABEL_FILL));
            }
        }
        double axisY = TOP + n * ROW_H + 6;
        shapes.add(new Line(plotLeft, axisY, plotRight, axisY, AXIS_STROKE, 1));
        return new LaidOut(W, height, shapes);
    }
}
