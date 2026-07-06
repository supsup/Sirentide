package com.sirentide.layout;

import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
import java.util.ArrayList;
import java.util.List;

/// Pure pie layout: slice magnitudes → angular {@link Wedge}s. Deterministic arithmetic, zero
/// graph optimization (docs/DESIGN.md §6). Starts at 12 o'clock and sweeps clockwise. Colours
/// come from a small fixed palette, assigned by slice order (deterministic). Labels
/// (text-as-paths) arrive once the glyph-outline reader lands — M0 draws the wedges.
public final class PieLayout {

    private PieLayout() {}

    private static final double SIZE = 240;
    private static final double RADIUS = 100;
    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final double LABEL_SIZE = 11;
    private static final double LABEL_RADIUS = RADIUS * 0.62;   // inside-wedge label radius
    private static final double LEADER_LEN = 14;               // outside-label leader length
    /// Below this sweep a centered inside-label would collide with its neighbours (two 1% slices
    /// used to stack labels on the same point) — move it outside on a leader line instead.
    private static final double THIN_SLICE = Math.toRadians(15);
    private static final String OUTSIDE_LABEL_FILL = "#334155";   // on the page background, not a slice
    private static final String LEADER_STROKE = "#94a3b8";

    /// A small fixed palette of contract-clean hex fills (mid-tone, readable on light or dark).
    private static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    public static LaidOut layout(Pie pie) {
        // Denominator = POSITIVE magnitudes only. Summing negatives (the old `total()`) shrank the
        // denominator while the loop skipped the negative slice, inflating every other slice's sweep
        // past a full 360° turn. A negative value now cannot corrupt any other slice's angle.
        double total = pie.positiveTotal();
        double cx = SIZE / 2;
        double cy = SIZE / 2;
        if (total <= 0) {
            return LaidOut.of(SIZE, SIZE);
        }
        List<Shape> shapes = new ArrayList<>();
        double angle = -Math.PI / 2; // 12 o'clock
        List<Slice> slices = pie.slices();
        for (int i = 0; i < slices.size(); i++) {
            double value = slices.get(i).value();
            if (value <= 0) {
                continue;
            }
            double sweep = (value / total) * 2 * Math.PI;
            double next = angle + sweep;
            String fill = PALETTE[i % PALETTE.length];
            shapes.add(new Wedge(cx, cy, RADIUS, angle, next, fill));

            double mid = (angle + next) / 2;
            String label = slices.get(i).label();
            if (sweep >= THIN_SLICE) {
                // Comfortable slice: a centered inside label, contrast-aware fill (black or white by
                // the slice colour's luminance — no more contrast-blind hardcoded white).
                placeLabel(shapes, label, cx + LABEL_RADIUS * Math.cos(mid),
                    cy + LABEL_RADIUS * Math.sin(mid), true, mid, contrastFill(fill));
            } else {
                // Thin slice: a short leader line out past the rim to a label on the background, so
                // two tiny slices no longer co-locate their labels at the centre.
                double ex = cx + RADIUS * Math.cos(mid);
                double ey = cy + RADIUS * Math.sin(mid);
                double lx = cx + (RADIUS + LEADER_LEN) * Math.cos(mid);
                double ly = cy + (RADIUS + LEADER_LEN) * Math.sin(mid);
                shapes.add(new Line(ex, ey, lx, ly, LEADER_STROKE, 1));
                placeLabel(shapes, label, lx, ly, false, mid, OUTSIDE_LABEL_FILL);
            }
            angle = next;
        }
        return new LaidOut(SIZE, SIZE, shapes);
    }

    /// Emit a label as glyph paths. Inside labels centre on the point; outside labels anchor away
    /// from the pie (left-aligned on the right half, right-aligned on the left half) so the leader
    /// meets the text edge, not its middle.
    private static void placeLabel(List<Shape> shapes, String label, double px, double py,
                                   boolean centered, double mid, String fill) {
        double w = FONT.runWidth(label, LABEL_SIZE);
        double baseline = py + LABEL_SIZE * 0.35;
        double originX;
        if (centered) {
            originX = px - w / 2;
        } else if (Math.cos(mid) >= 0) {
            originX = px + 2;                 // right half: text to the right of the leader
        } else {
            originX = px - 2 - w;             // left half: text to the left of the leader
        }
        String d = FONT.textPathD(label, originX, baseline, LABEL_SIZE);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }

    /// Pick a black or white label fill by the slice colour's perceptual luminance, so the label
    /// stays legible on both light and dark slice colours (the old hardcoded white vanished on the
    /// palette's light slices).
    private static String contrastFill(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.55 ? "#000000" : "#ffffff";
    }
}
