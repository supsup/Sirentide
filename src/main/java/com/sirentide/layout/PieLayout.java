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
    /// Min vertical spacing between two spread outside labels (font size + breathing room), so their
    /// glyph boxes stay disjoint. Also the in-frame clamp margin.
    private static final double LABEL_BAND = LABEL_SIZE + 3;
    /// A slice label wider than this gets ellipsized (wrap-oracle wired in) rather than overrunning.
    private static final double MAX_INSIDE_LABEL = RADIUS;
    private static final String OUTSIDE_LABEL_FILL = "#334155";   // on the page background, not a slice
    private static final String LEADER_STROKE = "#94a3b8";

    // -- legend (left-side colour key) geometry ---------------------------------
    /// Width of the left key column: swatch + gap + (ellipsized) label/value text + paddings.
    private static final double KEY_WIDTH = 140;
    /// Gap between the key column and the pie face, so the two never overlap.
    private static final double KEY_GAP = 20;
    /// Height of one key row (font size + breathing room), rows evenly stacked.
    private static final double KEY_ROW_HEIGHT = 22;
    /// Side of the square colour swatch.
    private static final double SWATCH = 12;
    private static final double KEY_PAD_LEFT = 12;
    private static final double KEY_PAD_RIGHT = 8;
    private static final double KEY_PAD_TOP = 12;
    /// Gap between a row's swatch and its text.
    private static final double SWATCH_TEXT_GAP = 6;
    /// Max width the label+value text may occupy before it ellipsizes inside the key column.
    private static final double KEY_TEXT_MAX =
        KEY_WIDTH - KEY_PAD_LEFT - SWATCH - SWATCH_TEXT_GAP - KEY_PAD_RIGHT;

    /// A small fixed palette of contract-clean hex fills (mid-tone, readable on light or dark).
    private static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    public static LaidOut layout(Pie pie) {
        // Legend mode is a wholly separate layout path (left key + shifted, label-suppressed pie),
        // kept apart so the bare-pie path below stays byte-for-byte identical to before.
        if (pie.legend()) {
            return layoutLegend(pie);
        }
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
        List<Outside> outside = new ArrayList<>();
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
            // Wrap-oracle wired in: a long slice label that would overrun the wedge is clipped with
            // an ellipsis rather than spilling across neighbours (docs/DESIGN.md §4).
            String label = FONT.ellipsize(slices.get(i).label(), MAX_INSIDE_LABEL, LABEL_SIZE);
            if (sweep >= THIN_SLICE) {
                // Comfortable slice: a centered inside label, contrast-aware fill (black or white by
                // the slice colour's luminance — no more contrast-blind hardcoded white).
                placeLabel(shapes, label, cx + LABEL_RADIUS * Math.cos(mid),
                    cy + LABEL_RADIUS * Math.sin(mid), true, mid, contrastFill(fill));
            } else {
                // Thin slice: DEFER — anchor on the rim, collect it, and spread the whole set below
                // so two near-co-located tiny slices ("Tiny"/"Other") no longer stack their labels.
                outside.add(new Outside(
                    cx + RADIUS * Math.cos(mid), cy + RADIUS * Math.sin(mid),   // rim point (leader start)
                    cx + (RADIUS + LEADER_LEN) * Math.cos(mid),                 // leader-end x
                    cy + (RADIUS + LEADER_LEN) * Math.sin(mid),                 // leader-end y (spread below)
                    mid, slices.get(i).label()));
            }
            angle = next;
        }
        spreadOutside(shapes, outside);
        return new LaidOut(SIZE, SIZE, shapes);
    }

    /// A deferred outside label: the rim point the leader starts at, the leader-end anchor (whose y
    /// gets spread), the slice mid-angle (fixes which side the text sits on), and the raw text.
    private record Outside(double rimX, double rimY, double lx, double ly, double mid, String label) {}

    /// Spread outside labels so adjacent thin-slice labels don't stack. Split by side (the text sits
    /// left of the leader on the pie's left half, right on the right half), sort each side top-to-
    /// bottom, then greedily push each label down until it clears the previous one's band. The
    /// leader still connects the true slice rim to the pushed anchor, so the pointer stays honest.
    private static void spreadOutside(List<Shape> shapes, List<Outside> outside) {
        List<Outside> right = new ArrayList<>();
        List<Outside> left = new ArrayList<>();
        for (Outside o : outside) {
            (Math.cos(o.mid()) >= 0 ? right : left).add(o);
        }
        emitSide(shapes, spreadSide(right));
        emitSide(shapes, spreadSide(left));
    }

    /// Push a single side's labels apart in y (already sorted), clamped into the viewbox.
    private static List<Outside> spreadSide(List<Outside> side) {
        side.sort((a, b) -> Double.compare(a.ly(), b.ly()));
        double prevBottom = Double.NEGATIVE_INFINITY;
        List<Outside> out = new ArrayList<>();
        for (Outside o : side) {
            double ly = Math.max(o.ly(), prevBottom + LABEL_BAND);
            ly = Math.min(Math.max(ly, LABEL_BAND), SIZE - LABEL_BAND);   // keep the anchor in-frame
            out.add(new Outside(o.rimX(), o.rimY(), o.lx(), ly, o.mid(), o.label()));
            prevBottom = ly;
        }
        return out;
    }

    private static void emitSide(List<Shape> shapes, List<Outside> side) {
        for (Outside o : side) {
            shapes.add(new Line(o.rimX(), o.rimY(), o.lx(), o.ly(), LEADER_STROKE, 1));
            placeLabel(shapes, o.label(), o.lx(), o.ly(), false, o.mid(), OUTSIDE_LABEL_FILL);
        }
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

    /// Legend layout: a vertical colour KEY on the left (one swatch+label+value row per drawn
    /// slice) with the pie shifted to its right. The on-slice inside labels AND the outside leader
    /// labels are SUPPRESSED — the key replaces them (that is the whole point). The wedges
    /// themselves are unchanged; only their centre shifts right past the key column. The canvas
    /// widens to `KEY_WIDTH + KEY_GAP + pieDiameter`.
    private static LaidOut layoutLegend(Pie pie) {
        double total = pie.positiveTotal();
        List<Slice> slices = pie.slices();
        int drawn = 0;
        for (Slice s : slices) {
            if (s.value() > 0) {
                drawn++;
            }
        }
        double canvasW = KEY_WIDTH + KEY_GAP + SIZE;
        // Tall key sets (many slices) grow the canvas so rows never spill off the pie box.
        double keyBlockH = drawn * KEY_ROW_HEIGHT;
        double canvasH = Math.max(SIZE, keyBlockH + 2 * KEY_PAD_TOP);
        if (total <= 0) {
            return LaidOut.of(canvasW, canvasH);
        }
        double cx = KEY_WIDTH + KEY_GAP + SIZE / 2;   // pie shifted right of the key column
        double cy = canvasH / 2;
        double keyTop = (canvasH - keyBlockH) / 2;     // key block vertically centred
        double textX = KEY_PAD_LEFT + SWATCH + SWATCH_TEXT_GAP;

        List<Shape> shapes = new ArrayList<>();
        double angle = -Math.PI / 2; // 12 o'clock
        int row = 0;
        for (int i = 0; i < slices.size(); i++) {
            double value = slices.get(i).value();
            if (value <= 0) {
                continue;
            }
            double sweep = (value / total) * 2 * Math.PI;
            double next = angle + sweep;
            String fill = PALETTE[i % PALETTE.length];
            shapes.add(new Wedge(cx, cy, RADIUS, angle, next, fill));
            angle = next;

            // Key row: a colour swatch (same palette fill as the wedge) + label & value text. Text
            // uses the standard page-background fill (the row sits on white now, not on the slice),
            // and is ellipsized to the key column width so a long label can't overrun.
            double rowTop = keyTop + row * KEY_ROW_HEIGHT;
            shapes.add(new Rect(KEY_PAD_LEFT, rowTop + (KEY_ROW_HEIGHT - SWATCH) / 2,
                SWATCH, SWATCH, fill));
            String text = FONT.ellipsize(
                slices.get(i).label() + "  " + formatValue(value), KEY_TEXT_MAX, LABEL_SIZE);
            double baseline = rowTop + KEY_ROW_HEIGHT / 2 + LABEL_SIZE * 0.35;
            String d = FONT.textPathD(text, textX, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, OUTSIDE_LABEL_FILL));
            }
            row++;
        }
        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Deterministic value formatting for a key row: integer when whole, else up to 3 decimals —
    /// matching the emitter's number style so key values read the same as geometry.
    private static String formatValue(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
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
