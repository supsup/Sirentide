package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.YoungDiagram;
import java.util.ArrayList;
import java.util.List;

/// Pure Young-diagram layout (plan sirentide-young-diagram-primitive): a partition `λ = [λ0, λ1, …]`
/// drawn (ENGLISH convention) as left-justified rows of unit boxes — the longest row on top, each row
/// left-aligned, stacked downward. Deterministic arithmetic, zero graph optimization (docs/DESIGN.md §6).
///
/// GEOMETRY: box `j` of row `i` (0-indexed, i from the TOP) sits at `(MARGIN + j*UNIT, MARGIN + i*UNIT)`
/// — so EVERY row shares the same left edge `x = MARGIN` (left-justification) and rows stack top-to-bottom
/// (row 0 highest on screen). Because the parse boundary hands us a weakly-decreasing partition, row 0 is
/// the widest, so `maxLen = rows.get(0)`; the canvas is `2*MARGIN + maxLen*UNIT` by `2*MARGIN + N*UNIT`
/// (N = row count), grown to contain every box plus a uniform {@link #MARGIN} — nothing escapes the canvas
/// (the {@code GeometryEscapeTest} invariant).
///
/// EMIT: one contract-clean {@link Rect} per unit box (a light per-row palette tint + a grey border) —
/// no labels (a plain Young diagram is boxes only; a future slice can bake hook lengths / tableau entries
/// via the threaded text colour). Each ROW is wrapped in ONE `<g role="cell">` anchor group, `seq` = row
/// index, so a play-through reveals the partition one row at a time (plan sirentide-play-through-frames).
public final class YoungDiagramLayout {

    private YoungDiagramLayout() {}

    /// Unit-box side and the uniform canvas margin around the diagram (mirrors the snake-graph strip).
    private static final double UNIT = 32;
    private static final double MARGIN = 24;

    /// The box border — the same axis grey the other types use, so the boxes read as a grid.
    private static final String BORDER_STROKE = "#94a3b8";
    private static final double BORDER_WIDTH = 1;

    /// How far each palette colour is lightened toward white for the box fill (a soft tint — matches the
    /// snake-graph square tint so the two box-grid types read as one family).
    private static final double TINT = 0.62;

    public static LaidOut layout(YoungDiagram young) {
        return layout(young, null);
    }

    /// Inline-math entry (parity with every other layout): the Young diagram carries no `$…$` label, so
    /// the `math` argument is accepted and ignored, and this is byte-identical to {@link #layout(YoungDiagram)}.
    public static LaidOut layout(YoungDiagram young, MathFragmentRenderer math) {
        List<Integer> rows = young.rows();
        if (rows.isEmpty()) {
            // A bare `young` (no parts) → a valid, empty, MARGIN-padded canvas (never throws).
            return LaidOut.of(2 * MARGIN, 2 * MARGIN);
        }

        // rows is weakly-decreasing (parse-normalized), so row 0 is the widest → the canvas width driver.
        int maxLen = rows.get(0);
        int numRows = rows.size();
        double canvasW = 2 * MARGIN + maxLen * UNIT;
        double canvasH = 2 * MARGIN + numRows * UNIT;

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();
        for (int i = 0; i < numRows; i++) {
            String tint = Colors.lighten(Colors.PALETTE[i % Colors.PALETTE.length], TINT);
            List<Shape> group = new ArrayList<>();
            int len = rows.get(i);
            double y = MARGIN + i * UNIT;
            for (int j = 0; j < len; j++) {
                double x = MARGIN + j * UNIT;   // left-justified: every row starts at x = MARGIN
                group.add(new Rect(x, y, UNIT, UNIT, tint, BORDER_STROKE, BORDER_WIDTH));
            }
            // Coordinate-derived (ROW index) base id — always charset-legal (never authored text), stable,
            // and narratable ("row 0"). Mirrors the snake/matrix coordinate-base anchoring discipline.
            shapes.add(new Group(assigner.assign(SirentideRole.CELL, "row" + i), group));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }
}
