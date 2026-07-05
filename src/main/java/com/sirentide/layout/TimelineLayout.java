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

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final String AXIS_STROKE = "#cbd5e1";
    private static final String LABEL_FILL = "#334155";
    private static final String VALUE_FILL = "#64748b";

    private static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    public static LaidOut layout(Timeline timeline) {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Line(MARGIN, AXIS_Y, W - MARGIN, AXIS_Y, AXIS_STROKE, 2));

        List<Slice> events = timeline.events();
        int n = events.size();
        if (n == 0) {
            return new LaidOut(W, H, shapes);
        }
        double plotW = W - 2 * MARGIN;
        for (int i = 0; i < n; i++) {
            Slice e = events.get(i);
            double x = MARGIN + (i + 0.5) * (plotW / n);
            shapes.add(new Wedge(x, AXIS_Y, DOT_R, 0, 2 * Math.PI, PALETTE[i % PALETTE.length]));
            centeredLabel(shapes, e.label(), x, AXIS_Y - 14, 11, LABEL_FILL);   // above the line
            centeredLabel(shapes, num(e.value()), x, AXIS_Y + 24, 10, VALUE_FILL);  // below the line
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
