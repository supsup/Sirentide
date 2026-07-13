package com.sirentide.layout;

import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Matrix;
import java.util.ArrayList;
import java.util.List;

/// Pure comparison / verdict-matrix layout: a rectangular grid of a row-label column + M data columns,
/// a header band over N verdict rows (plan sirentide-comparison-matrix-type). Each cell is a `#rrggbb`
/// {@link Rect} filled by its {@link Matrix.Verdict} from the closed palette, with the token baked to a
/// centered glyph {@link GlyphRun} in the cell's contrast colour; the row label is left-aligned. Grid
/// lines are drawn "for free" by a single border-colour background rect that the per-cell rects inset
/// into by {@link #BORDER_W} — so the whole diagram is rect + glyph-path only (sanitizer-clean, no new
/// element/attribute), deterministic, and grows the canvas to contain every cell.
///
/// Column widths are per-column (the wider of the header and its cells, capped), so a matrix stays
/// tight; row height is uniform. A bare `matrix` (no rows) yields a valid small empty canvas.
public final class MatrixLayout {

    private MatrixLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    private static final double MARGIN = 18;
    private static final double ROW_H = 30;
    private static final double HEADER_H = 30;
    private static final double PAD_X = 10;
    private static final double LABEL_SIZE = 12;
    private static final double MAX_LABEL_W = 240;   // row labels ellipsize past this
    private static final double MAX_CELL_W = 150;    // data cells ellipsize past this
    private static final double MIN_CELL_W = 54;
    private static final double MIN_LABEL_W = 60;
    private static final double BORDER_W = 1;        // gridline thickness (background bleed-through)

    private static final String BORDER = "#94a3b8";      // gridlines (slate)
    private static final String HEADER_FILL = "#e2e8f0"; // column-header band + corner
    private static final String LABEL_FILL = "#f1f5f9";  // row-label column cells

    // The closed verdict palette (index by Verdict.ordinal): PASS, FAIL, PARTIAL, NA.
    private static final String[] VERDICT_FILL = {
        "#dcfce7",   // PASS    — green
        "#fecaca",   // FAIL    — red
        "#fef9c3",   // PARTIAL — amber
        "#f1f5f9",   // NA      — neutral slate
    };

    public static LaidOut layout(Matrix m) {
        return layout(m, null);
    }

    /// The `math` arg is accepted for dispatch-signature parity with the other types; matrix cells are
    /// a closed verdict vocabulary (no `$…$` math), so it is unused. A null renderer changes nothing.
    public static LaidOut layout(Matrix m, com.sirentide.api.MathFragmentRenderer math) {
        List<Matrix.Row> rows = m.rows();
        int cols = m.columns().size();
        // With no explicit header, infer the column count from the (already-rectangularized) rows.
        if (cols == 0 && !rows.isEmpty()) {
            cols = rows.get(0).cells().size();
        }

        // Row-label column width: the widest (ellipsized) label, floored + padded.
        double labelW = MIN_LABEL_W;
        for (Matrix.Row r : rows) {
            labelW = Math.max(labelW, Math.min(MAX_LABEL_W, FONT.runWidth(r.label(), LABEL_SIZE)));
        }
        labelW += 2 * PAD_X;

        // Per-column widths: the wider of the header and any cell token, capped + padded.
        double[] colW = new double[cols];
        for (int j = 0; j < cols; j++) {
            double w = j < m.columns().size() ? FONT.runWidth(m.columns().get(j), LABEL_SIZE) : 0;
            for (Matrix.Row r : rows) {
                if (j < r.cells().size()) {
                    w = Math.max(w, FONT.runWidth(r.cells().get(j).text(), LABEL_SIZE));
                }
            }
            colW[j] = Math.max(MIN_CELL_W, Math.min(MAX_CELL_W, w) + 2 * PAD_X);
        }

        boolean hasHeader = !m.columns().isEmpty();
        double gridW = labelW;
        for (double w : colW) {
            gridW += w;
        }
        double gridH = (hasHeader ? HEADER_H : 0) + rows.size() * ROW_H;
        double canvasW = MARGIN + gridW + MARGIN;
        double canvasH = MARGIN + gridH + MARGIN;

        List<Shape> shapes = new ArrayList<>();
        // A blank matrix still returns a valid (tiny) canvas rather than a degenerate 0×0.
        if (cols == 0 && rows.isEmpty()) {
            return LaidOut.of(MARGIN * 2 + MIN_LABEL_W, MARGIN * 2 + ROW_H);
        }

        // The single border-colour backing rect: every cell insets into it by BORDER_W, so the bleed
        // is the gridline. One rect gives the whole grid its lines with no Line shapes.
        shapes.add(new Rect(MARGIN, MARGIN, gridW, gridH, BORDER));

        double y = MARGIN;
        // Header band: an empty corner over the label column, then the M column headers.
        if (hasHeader) {
            cell(shapes, MARGIN, y, labelW, HEADER_H, HEADER_FILL);
            double hx = MARGIN + labelW;
            for (int j = 0; j < cols; j++) {
                cell(shapes, hx, y, colW[j], HEADER_H, HEADER_FILL);
                String text = j < m.columns().size() ? m.columns().get(j) : "";
                centered(shapes, text, hx + colW[j] / 2, y + HEADER_H / 2, colW[j] - 2 * BORDER_W,
                    Colors.contrastFill(HEADER_FILL));
                hx += colW[j];
            }
            y += HEADER_H;
        }

        // Per-diagram anchor factory (plan sirentide-matrix-semantic-anchors): each DATA (verdict) cell
        // is wrapped in ONE `<g data-sirentide-role="cell">` so the play-through/FX layer can reveal the
        // grid. Seq runs in ROW-MAJOR reading order (top-to-bottom rows, left-to-right columns) — no
        // edges-before-nodes quirk here, the matrix simply reveals as you'd read it. The header band and
        // the row-label column are structural (like flowchart cluster frames), so they stay un-anchored;
        // an N-row × M-col matrix emits exactly N·M cell groups. The base id is COORDINATE-derived
        // (`r<row>c<col>`, always charset-legal), NEVER the cell/row/col text — so a hostile label can't
        // place an illegal char into an anchor id (mirrors FlowchartLayout's stable base ids).
        AnchorAssigner assigner = new AnchorAssigner();

        // Data rows: a left-aligned label cell, then the M verdict cells.
        int rowIdx = 0;
        for (Matrix.Row r : rows) {
            cell(shapes, MARGIN, y, labelW, ROW_H, LABEL_FILL);
            leftAligned(shapes, r.label(), MARGIN + PAD_X, y + ROW_H / 2, labelW - PAD_X - BORDER_W,
                Colors.contrastFill(LABEL_FILL));
            double cx = MARGIN + labelW;
            for (int j = 0; j < cols; j++) {
                Matrix.Cell c = j < r.cells().size() ? r.cells().get(j) : new Matrix.Cell("", Matrix.Verdict.NA);
                String fill = VERDICT_FILL[c.verdict().ordinal()];
                // Collect this cell's fill rect + centered token into ONE anchor group (row-major seq).
                List<Shape> cellShapes = new ArrayList<>();
                cell(cellShapes, cx, y, colW[j], ROW_H, fill);
                centered(cellShapes, c.text(), cx + colW[j] / 2, y + ROW_H / 2, colW[j] - 2 * BORDER_W,
                    Colors.contrastFill(fill));
                shapes.add(new Group(assigner.assign(SirentideRole.CELL, cellBaseId(rowIdx, j)), cellShapes));
                cx += colW[j];
            }
            y += ROW_H;
            rowIdx++;
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// The `data-sirentide-id` base for a data cell: its ROW/COLUMN coordinates (`r<row>c<col>`), NOT
    /// its text. Coordinates are ints, so the base is always charset-legal — a hostile cell/row/col
    /// label can therefore never inject an illegal char into the anchor id (the sanitizer would strip
    /// it anyway, but the coordinate base never presents one). Stable + narratable ("row 0, column 1").
    private static String cellBaseId(int row, int col) {
        return "r" + row + "c" + col;
    }

    /// One cell background: the fill rect inset by BORDER_W into the backing rect so the border colour
    /// shows as the gridline on all four sides.
    private static void cell(List<Shape> shapes, double x, double y, double w, double h, String fill) {
        shapes.add(new Rect(x + BORDER_W, y + BORDER_W, w - 2 * BORDER_W, h - 2 * BORDER_W, fill));
    }

    private static void centered(List<Shape> shapes, String text, double cx, double midY,
                                 double maxWidth, String fill) {
        if (text.isEmpty()) {
            return;
        }
        String fit = FONT.ellipsize(text, maxWidth, LABEL_SIZE);
        double w = FONT.runWidth(fit, LABEL_SIZE);
        double baseline = midY + LABEL_SIZE * 0.35;
        String d = FONT.textPathD(fit, cx - w / 2, baseline, LABEL_SIZE);
        if (!d.isEmpty()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }

    private static void leftAligned(List<Shape> shapes, String text, double x, double midY,
                                    double maxWidth, String fill) {
        if (text.isEmpty()) {
            return;
        }
        String fit = FONT.ellipsize(text, maxWidth, LABEL_SIZE);
        double baseline = midY + LABEL_SIZE * 0.35;
        String d = FONT.textPathD(fit, x, baseline, LABEL_SIZE);
        if (!d.isEmpty()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }
}
