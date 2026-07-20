package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Snake;
import java.util.ArrayList;
import java.util.List;

/// Pure snake-graph layout (plan sirentide-snake-graph-primitive): a continued fraction
/// `[a_1, a_2, …, a_n]` (positive integers) drawn as the canonical Çanakçı–Schiffler SQUARE snake
/// graph (arXiv 1608.06568, "Snake graphs and continued fractions"). Deterministic arithmetic, zero
/// graph optimization (docs/DESIGN.md §6).
///
/// CANONICAL MODEL (matches {@link Snake}'s javadoc; the exact rule, non-circular-verified by the
/// perfect-matching oracle in {@code SnakeGraphLayoutTest}):
///
///   * TILE COUNT — the snake has exactly `sum(a_i) − 1` unit-square tiles (NOT `sum(a_i)`). `[1]`
///     (a single quotient 1) has ZERO tiles and renders as a single edge.
///   * SIGN SEQUENCE — a sequence of `sum(a_i)` signs, block `i` being `a_i` copies of one sign with
///     the sign ALTERNATING between blocks (`a_1` of `−`, `a_2` of `+`, `a_3` of `−`, …). The
///     `sum(a_i) − 1` tiles sit in the gaps between consecutive signs.
///   * TURN RULE — tile-junction `j` (between tile `j` and `j+1`, `j = 0 … tiles−2`, using the FIRST
///     `tiles−1` gaps of the sign sequence) is a TURN where the two bounding signs are EQUAL (both
///     inside one block) and STRAIGHT where they DIFFER (a block boundary). So a block of `a_i` equal
///     signs contributes `a_i − 1` turns (a zig-zag), and each block boundary is a straight step.
///     Consequence: `[1, 1, …, 1]` (all blocks length 1 ⇒ every gap is a block boundary ⇒ every
///     junction straight) is a STRAIGHT snake of `n − 1` tiles — no turns.
///
/// This is the model whose PERFECT-MATCHING count equals the NUMERATOR of the continued fraction
/// (Çanakçı–Schiffler): e.g. `[1,1,1,1,1]` → 4 straight tiles → 8 matchings → numerator of `8/5`.
/// The old "sum(a_i) squares, even-index-right/odd-index-up staircase" model was neither canonical
/// nor matching-correct (review sir329) and has been replaced.
///
/// DIRECTION CONVENTION: tile `t_0` sits at grid cell `(0,0)`; each later tile steps ONE cell from
/// the previous — RIGHT (`+x`) while the current heading is horizontal, UP (`+y`, math coords) while
/// vertical — the heading flipping at every TURN. Every step is `+x`/`+y`, so the strip is a monotone
/// staircase anchored at the origin (`minGx = minGy = 0`), and — because each step raises `gx+gy` by
/// one — no cell is ever revisited (no self-overlap, ever).
///
/// EMIT: one contract-clean {@link Rect} per unit tile (light per-SEGMENT tint + a grey border) plus
/// one {@link GlyphRun} per maximal straight segment carrying that segment's tile count, contrast-
/// filled against the tint so it reads on light AND dark host pages. Math coords are y-FLIPPED once
/// here (SVG y grows DOWN); the canvas grows to fit the strip's bounding box plus a uniform
/// {@link #MARGIN}, so nothing ever escapes the canvas (the {@code GeometryEscapeTest} invariant).
/// Each maximal straight segment is wrapped in ONE `<g role="cell">` anchor group (its tiles + label),
/// `seq` = segment index, so a play-through reveals the snake one straight run at a time (plan
/// sirentide-play-through-frames).
public final class SnakeGraphLayout {

    private SnakeGraphLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    /// Unit-square side and the uniform canvas margin around the strip.
    private static final double UNIT = 32;
    private static final double MARGIN = 24;

    /// Segment-label type size and its ellipsize budget (a defensive cap — a tile count is at most 4
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

    /// Inline-math entry (parity with every other layout): the segment labels are plain integers, so a
    /// `$…$` seam is never triggered — the `math` argument is accepted and ignored, and this is
    /// byte-identical to {@link #layout(Snake)}.
    public static LaidOut layout(Snake snake, MathFragmentRenderer math) {
        List<Integer> qs = snake.quotients();
        if (qs.isEmpty()) {
            // A bare `snake` (no quotients) → a valid, empty, MARGIN-padded canvas (never throws).
            return LaidOut.of(2 * MARGIN, 2 * MARGIN);
        }

        long sum = 0;
        for (int a : qs) {
            sum += a;
        }
        int tiles = (int) (sum - 1);   // CANONICAL: sum(a_i) − 1 tiles.

        AnchorAssigner assigner = new AnchorAssigner();

        // [1] (sum == 1) → a single EDGE, ZERO tiles (the Çanakçı–Schiffler base case): the snake
        // graph of the continued fraction [1] is a single edge with exactly one perfect matching.
        if (tiles == 0) {
            double y = MARGIN + UNIT / 2;
            Line edge = new Line(MARGIN, y, MARGIN + UNIT, y, BORDER_STROKE, BORDER_WIDTH);
            Group g = new Group(assigner.assign(SirentideRole.CELL, "edge"), List.of(edge));
            return new LaidOut(2 * MARGIN + UNIT, 2 * MARGIN + UNIT, List.of(g));
        }

        // 1. Turn structure from the sign sequence. `change[k]` marks gap k (between sign k and k+1)
        //    as a block BOUNDARY (a sign change → a STRAIGHT step); the other gaps are inside a block
        //    (equal signs → a TURN). Only the first `tiles − 1` gaps drive the `tiles − 1` junctions.
        boolean[] change = new boolean[tiles];   // gap k, k = 0 … tiles-1
        int cum = 0;
        for (int i = 0; i < qs.size() - 1; i++) {   // internal block boundaries only (exclude the last)
            cum += qs.get(i);
            change[cum - 1] = true;
        }

        // 2. Walk the tiles: tile 0 at (0,0); each junction is a TURN where signs are EQUAL
        //    (!change), flipping the heading. Assign each tile to a maximal straight SEGMENT.
        List<int[]> cells = new ArrayList<>();   // {gx, gy}
        int[] segOf = new int[tiles];
        int gx = 0;
        int gy = 0;
        int maxGx = 0;
        int maxGy = 0;
        boolean horizontal = true;   // start heading: horizontal (right)
        int seg = 0;
        cells.add(new int[] {0, 0});
        segOf[0] = 0;
        for (int j = 0; j < tiles - 1; j++) {
            boolean turn = !change[j];   // EQUAL signs ⇒ turn; block boundary ⇒ straight
            if (turn) {
                horizontal = !horizontal;
                seg++;
            }
            if (horizontal) {
                gx += 1;
            } else {
                gy += 1;
            }
            cells.add(new int[] {gx, gy});
            segOf[j + 1] = seg;
            maxGx = Math.max(maxGx, gx);
            maxGy = Math.max(maxGy, gy);
        }
        int segments = seg + 1;

        int cols = maxGx + 1;
        int rows = maxGy + 1;
        double canvasW = 2 * MARGIN + cols * UNIT;
        double canvasH = 2 * MARGIN + rows * UNIT;

        // 3. Group the tiles by straight segment so each segment becomes ONE anchor group (its tiles +
        //    its tile-count label), and so the label can centre on the segment's centroid.
        List<List<int[]>> bySeg = new ArrayList<>();
        for (int s = 0; s < segments; s++) {
            bySeg.add(new ArrayList<>());
        }
        for (int t = 0; t < tiles; t++) {
            bySeg.get(segOf[t]).add(cells.get(t));
        }

        List<Shape> shapes = new ArrayList<>();
        for (int s = 0; s < segments; s++) {
            String tint = Colors.lighten(Colors.PALETTE[s % Colors.PALETTE.length], TINT);
            List<Shape> group = new ArrayList<>();
            double sumCx = 0;
            double sumCy = 0;
            for (int[] c : bySeg.get(s)) {
                double sx = MARGIN + c[0] * UNIT;
                double sy = MARGIN + (maxGy - c[1]) * UNIT;   // y FLIP: higher gy → higher on screen
                group.add(new Rect(sx, sy, UNIT, UNIT, tint, BORDER_STROKE, BORDER_WIDTH));
                sumCx += sx + UNIT / 2;
                sumCy += sy + UNIT / 2;
            }
            int n = bySeg.get(s).size();
            // The segment's tile count, centred on the segment's centroid, contrast-filled vs the tint.
            String text = Integer.toString(n);
            String fit = FONT.ellipsize(text, LABEL_MAX_W, LABEL_SIZE);
            double w = FONT.runWidth(fit, LABEL_SIZE);
            double cx = sumCx / n;
            double baseline = sumCy / n + LABEL_SIZE * 0.35;
            String d = FONT.textPathD(fit, cx - w / 2, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                group.add(new GlyphRun(d, Colors.contrastFill(tint)));
            }
            shapes.add(new Group(assigner.assign(SirentideRole.CELL, "seg" + s), group));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }
}
