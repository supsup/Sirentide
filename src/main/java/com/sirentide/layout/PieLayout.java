package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
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
    /// Max rendered width of a thin-slice OUTSIDE (leader-line) label before it ellipsizes. The inside
    /// path already caps at MAX_INSIDE_LABEL; this is its un-ellipsized sibling — a legal DSL of many
    /// thin slices with MAX_LABEL_LEN-char labels would otherwise build unbounded glyph paths (H2).
    private static final double MAX_OUTSIDE_LABEL = RADIUS;
    private static final String LEADER_STROKE = "#94a3b8";
    /// In-frame clamp margin: the min gap kept between any glyph box and the canvas edge, and the
    /// amount subtracted off the near edge when computing an outside label's available room.
    private static final double CLAMP_MARGIN = 2;

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

    public static LaidOut layout(Pie pie) {
        return layout(pie, null);
    }

    /// Inline-math entry (plan sirentide-math-in-all-label-types): a `$…$` run in a COMFORTABLE
    /// (non-thin) slice's inside label bakes through the shared {@link MathLabel} seam. A null `math`
    /// degrades every `$…$` to plain text — byte-identical to {@link #layout(Pie)}. Thin-slice OUTSIDE
    /// leader labels and the legend rows keep the plain-text path (geometry-awkward for a formula: a
    /// leader-spread label is ellipsized to sub-pixel room, a legend row concatenates label+value) —
    /// a `$…$` there degrades to its raw source, never throws.
    public static LaidOut layout(Pie pie, MathFragmentRenderer math) {
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
        // Off-slice (outside leader-line) label fill: the page-background text colour, defaulting to
        // `currentColor` (inherits the host page's text colour → legible on light AND dark). The
        // on-slice contrast labels below are DELIBERATELY untouched — they sit on a coloured slice.
        String textColor = pie.textColor();
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
            String fill = fillFor(slices.get(i), i);
            shapes.add(new Wedge(cx, cy, RADIUS, angle, next, fill));

            double mid = (angle + next) / 2;
            // Wrap-oracle wired in: a long slice label that would overrun the wedge is clipped with
            // an ellipsis rather than spilling across neighbours (docs/DESIGN.md §4).
            String rawLabel = slices.get(i).label();
            String label = FONT.ellipsize(rawLabel, MAX_INSIDE_LABEL, LABEL_SIZE);
            if (sweep >= THIN_SLICE) {
                // Comfortable slice: a centered inside label, contrast-aware fill (black or white by
                // the slice colour's luminance — no more contrast-blind hardcoded white). A `$…$`
                // label bakes through the MathLabel seam, centred on its composite width (math skips
                // the ellipsize); a plain label is byte-identical to before.
                double lx = cx + LABEL_RADIUS * Math.cos(mid);
                double ly = cy + LABEL_RADIUS * Math.sin(mid);
                if (math != null && MathLabel.hasMath(rawLabel)) {
                    MathLabel.Measured mm = MathLabel.measure(rawLabel, LABEL_SIZE, FONT, math);
                    MathLabel.emit(mm, lx - mm.width() / 2, ly + LABEL_SIZE * 0.35,
                        Colors.contrastFill(fill), LABEL_SIZE, FONT, shapes);
                } else {
                    placeLabel(shapes, label, lx, ly, Colors.contrastFill(fill));
                }
            } else {
                // Thin slice: DEFER — anchor on the rim, collect it, and spread the whole set below
                // so two near-co-located tiny slices ("Tiny"/"Other") no longer stack their labels.
                outside.add(new Outside(
                    cx + RADIUS * Math.cos(mid), cy + RADIUS * Math.sin(mid),   // rim point (leader start)
                    cx + (RADIUS + LEADER_LEN) * Math.cos(mid),                 // leader-end x
                    cy + (RADIUS + LEADER_LEN) * Math.sin(mid),                 // leader-end y (spread below)
                    mid, FONT.ellipsize(slices.get(i).label(), MAX_OUTSIDE_LABEL, LABEL_SIZE)));
            }
            angle = next;
        }
        spreadOutside(shapes, outside, textColor);
        return new LaidOut(SIZE, SIZE, shapes);
    }

    /// A deferred outside label: the rim point the leader starts at, the leader-end anchor (whose y
    /// gets spread), the slice mid-angle (fixes which side the text sits on), and the raw text.
    private record Outside(double rimX, double rimY, double lx, double ly, double mid, String label) {}

    /// Spread outside labels so adjacent thin-slice labels don't stack. Split by side (the text sits
    /// left of the leader on the pie's left half, right on the right half), sort each side top-to-
    /// bottom, then greedily push each label down until it clears the previous one's band. The
    /// leader still connects the true slice rim to the pushed anchor, so the pointer stays honest.
    private static void spreadOutside(List<Shape> shapes, List<Outside> outside, String textColor) {
        List<Outside> right = new ArrayList<>();
        List<Outside> left = new ArrayList<>();
        for (Outside o : outside) {
            (Math.cos(o.mid()) >= 0 ? right : left).add(o);
        }
        emitSide(shapes, spreadSide(right), textColor);
        emitSide(shapes, spreadSide(left), textColor);
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

    private static void emitSide(List<Shape> shapes, List<Outside> side, String textColor) {
        for (Outside o : side) {
            boolean right = Math.cos(o.mid()) >= 0;
            // Bound the outside label to the room between its leader-end anchor and the near canvas
            // edge, then ellipsize to fit. A thin slice with no horizontal room ellipsizes to EMPTY
            // (GEOMETRY-ESCAPE #1's honest outcome) — and then we DROP the leader too: a leader line
            // pointing at an empty label is dangling residue. The wedge itself already drew above.
            double avail = right ? SIZE - o.lx() - CLAMP_MARGIN - CLAMP_MARGIN
                                 : o.lx() - CLAMP_MARGIN - CLAMP_MARGIN;
            String label = FONT.ellipsize(o.label(), Math.max(0, avail), LABEL_SIZE);
            if (label.isBlank()) {
                continue;   // no room for even an ellipsis → no label AND no leader (drop the residue)
            }
            shapes.add(new Line(o.rimX(), o.rimY(), o.lx(), o.ly(), LEADER_STROKE, 1));
            placeOutside(shapes, label, o.lx(), o.ly(), right, textColor);
        }
    }

    /// Emit a CENTERED inside label as glyph paths — centred on the point (contrast-filled).
    private static void placeLabel(List<Shape> shapes, String label, double px, double py, String fill) {
        double baseline = py + LABEL_SIZE * 0.35;
        double w = FONT.runWidth(label, LABEL_SIZE);
        emitGlyphs(shapes, label, px - w / 2, baseline, fill);
    }

    /// Emit an ALREADY-BOUNDED outside label as glyph paths, anchored away from the pie (left-aligned
    /// on the right half, right-aligned on the left half) so the leader meets the text edge, not its
    /// middle. Callers ellipsize to the available room BEFORE calling — an empty label never reaches
    /// here (its leader is dropped in {@link #emitSide}).
    private static void placeOutside(List<Shape> shapes, String label, double px, double py,
                                     boolean right, String fill) {
        double baseline = py + LABEL_SIZE * 0.35;
        double w = FONT.runWidth(label, LABEL_SIZE);
        double originX = right ? px + CLAMP_MARGIN : px - CLAMP_MARGIN - w;
        // Final safety clamp: the whole glyph box stays in [CLAMP_MARGIN, SIZE-CLAMP_MARGIN-w].
        originX = Math.max(CLAMP_MARGIN, Math.min(originX, SIZE - CLAMP_MARGIN - w));
        emitGlyphs(shapes, label, originX, baseline, fill);
    }

    private static void emitGlyphs(List<Shape> shapes, String label, double originX,
                                   double baseline, String fill) {
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
        // Legend rows sit on the page background → page-background text colour (default currentColor).
        String textColor = pie.textColor();
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
            String fill = fillFor(slices.get(i), i);
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
                shapes.add(new GlyphRun(d, textColor));
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

    /// The wedge/swatch fill for a slice: its EXPLICIT per-item colour when the author supplied one
    /// (already a canonical `#rrggbb` from the parser), else the default palette entry by slice order.
    private static String fillFor(Slice s, int i) {
        return s.color() != null ? s.color() : Colors.PALETTE[i % Colors.PALETTE.length];
    }
}
