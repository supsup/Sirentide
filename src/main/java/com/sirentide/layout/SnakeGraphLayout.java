package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Snake;
import java.util.ArrayList;
import java.util.List;

/// Pure snake-graph layout (plan sirentide-snake-graph-primitive): a continued fraction
/// `[a0; a1, a2, …]` drawn as a connected strip of unit squares. Deterministic arithmetic, zero
/// graph optimization (docs/DESIGN.md §6).
///
/// DIRECTION CONVENTION (matches {@link Snake}'s javadoc): the squares are generated in a single
/// order `s0, s1, …, s_{N-1}` (N = sum of quotients). Run `i` holds `a_i` squares; EVEN-index runs
/// advance RIGHT (+x, one cell per square), ODD-index runs advance UP (+y in MATH coords). `s0` sits
/// at grid cell `(0,0)`; each later square steps ONE cell from the previous in the CURRENT run's
/// direction (so a run boundary is just a direction change between two adjacent squares — no cell is
/// ever shared/overlapped). All steps are `+x`/`+y`, so the strip is a monotone staircase anchored at
/// the origin; `minGx = minGy = 0` always.
///
/// EMIT: one contract-clean {@link Rect} per unit square (light per-run tint + a grey border) plus
/// one {@link GlyphRun} per run carrying that run's quotient value, contrast-filled against the tint
/// so it reads on light AND dark host pages. Math coords are y-FLIPPED once here (SVG y grows DOWN);
/// the canvas grows to fit the strip's bounding box plus a uniform {@link #MARGIN}, so nothing ever
/// escapes the canvas (the {@code GeometryEscapeTest} invariant). Each run is wrapped in ONE
/// `<g role="cell">` anchor group (its squares + label), `seq` = run index, so a play-through reveals
/// the continued fraction one quotient at a time (plan sirentide-play-through-frames).
public final class SnakeGraphLayout {

    private SnakeGraphLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    /// Unit-square side and the uniform canvas margin around the strip.
    private static final double UNIT = 32;
    private static final double MARGIN = 24;

    /// Quotient-label type size and its ellipsize budget (a defensive cap — the max quotient is 4
    /// digits, comfortably inside the strip interior, so a label never approaches the canvas edge).
    private static final double LABEL_SIZE = 14;
    private static final double LABEL_MAX_W = UNIT * 1.6;

    /// The square border — the same axis grey the other types use, so the grid reads as a strip.
    private static final String BORDER_STROKE = "#94a3b8";
    private static final double BORDER_WIDTH = 1;

    /// How far each palette colour is lightened toward white for the square fill (a soft tint that
    /// {@link Colors#contrastFill} still yields a dark, page-theme-agnostic label ink against).
    private static final double TINT = 0.62;

    public static LaidOut layout(Snake snake) {
        return layout(snake, null);
    }

    /// Inline-math entry (parity with every other layout): the run labels are plain integers, so a
    /// `$…$` seam is never triggered — the `math` argument is accepted and ignored, and this is
    /// byte-identical to {@link #layout(Snake)}.
    public static LaidOut layout(Snake snake, MathFragmentRenderer math) {
        List<Integer> qs = snake.quotients();
        if (qs.isEmpty()) {
            // A bare `snake` (no quotients) → a valid, empty, MARGIN-padded canvas (never throws).
            return LaidOut.of(2 * MARGIN, 2 * MARGIN);
        }

        // 1. Generate the square cells in strip order (math coords, y UP). Even run → step +x, odd
        //    run → step +y; s0 at (0,0), each later square one cell from the previous in-direction.
        List<int[]> cells = new ArrayList<>();   // {gx, gy, run}
        int gx = 0;
        int gy = 0;
        int maxGx = 0;
        int maxGy = 0;
        boolean first = true;
        for (int run = 0; run < qs.size(); run++) {
            boolean horizontal = (run % 2 == 0);
            int len = qs.get(run);
            for (int j = 0; j < len; j++) {
                if (first) {
                    first = false;   // s0 sits at the origin, no step
                } else if (horizontal) {
                    gx += 1;
                } else {
                    gy += 1;
                }
                cells.add(new int[] {gx, gy, run});
                maxGx = Math.max(maxGx, gx);
                maxGy = Math.max(maxGy, gy);
            }
        }

        int cols = maxGx + 1;
        int rows = maxGy + 1;
        double canvasW = 2 * MARGIN + cols * UNIT;
        double canvasH = 2 * MARGIN + rows * UNIT;

        // 2. Group the cells by run so each run becomes ONE anchor group (its squares + its label),
        //    and so the label can centre on the run's centroid.
        List<List<int[]>> byRun = new ArrayList<>();
        for (int r = 0; r < qs.size(); r++) {
            byRun.add(new ArrayList<>());
        }
        for (int[] c : cells) {
            byRun.get(c[2]).add(c);
        }

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();
        for (int run = 0; run < qs.size(); run++) {
            String tint = Colors.lighten(Colors.PALETTE[run % Colors.PALETTE.length], TINT);
            List<Shape> group = new ArrayList<>();
            double sumCx = 0;
            double sumCy = 0;
            for (int[] c : byRun.get(run)) {
                double sx = MARGIN + c[0] * UNIT;
                double sy = MARGIN + (maxGy - c[1]) * UNIT;   // y FLIP: higher gy → higher on screen
                group.add(new Rect(sx, sy, UNIT, UNIT, tint, BORDER_STROKE, BORDER_WIDTH));
                sumCx += sx + UNIT / 2;
                sumCy += sy + UNIT / 2;
            }
            int n = byRun.get(run).size();
            // The run's quotient value, centred on the run's centroid, contrast-filled against the tint.
            String text = Integer.toString(qs.get(run));
            String fit = FONT.ellipsize(text, LABEL_MAX_W, LABEL_SIZE);
            double w = FONT.runWidth(fit, LABEL_SIZE);
            double cx = sumCx / n;
            double baseline = sumCy / n + LABEL_SIZE * 0.35;
            String d = FONT.textPathD(fit, cx - w / 2, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                group.add(new GlyphRun(d, Colors.contrastFill(tint)));
            }
            shapes.add(new Group(assigner.assign(SirentideRole.CELL, "q" + run), group));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }
}
