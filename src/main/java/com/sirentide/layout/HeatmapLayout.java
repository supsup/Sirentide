package com.sirentide.layout;

import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Heatmap;
import java.util.ArrayList;
import java.util.List;

/// Pure continuous-score heatmap layout (plan sirentide-heatmap-type): MatrixLayout's grid — a
/// row-label column + M data columns, a header band, a single border-colour backing rect whose
/// bleed-through is the gridline — with ONE new dimension: each data cell's fill is interpolated
/// from a SINGLE-HUE sequential ramp (light→dark blue) by its 0..1 value, instead of picked from a
/// closed verdict vocabulary. Sequential-not-rainbow is deliberate (a magnitude gets one hue; a
/// multi-hue ramp misorders perceived magnitude), and blue is deliberately disjoint from the matrix
/// verdict palette so verdict semantics never bleed into magnitude. Cell text keeps the
/// {@link Colors#contrastFill} rule, so the ramp's dark end flips its label to white by itself.
///
/// Below the grid sits a compact ramp legend: {@link #RAMP_STEPS} sampled fill rects (rect+glyph
/// only — no SVG gradient element, so the output contract's element alphabet is unchanged) between
/// the low/high end labels (`scale:` directive; "0"/"1" when absent), drawn in the page text colour
/// like other types' axis labels. Data cells are anchored exactly like matrix's (role `cell`,
/// coordinate base ids `r<row>c<col>`, row-major seq); the header band, row-label column, and
/// legend are structural and stay un-anchored — an N×M heatmap emits exactly N·M cell groups.
public final class HeatmapLayout {

    private HeatmapLayout() {}

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

    private static final String BORDER = "#94a3b8";      // gridlines (slate) — matrix parity
    private static final String HEADER_FILL = "#e2e8f0"; // column-header band + corner
    private static final String LABEL_FILL = "#f1f5f9";  // row-label column cells
    private static final String NA_FILL = "#f1f5f9";     // NA cells: neutral, never on the ramp

    // The sequential ramp — ONE hue (blue), light→dark, interpolated piecewise-linearly through a
    // mid stop so the light half doesn't wash out. Values are clamped 0..1 at parse, so the lerp
    // input is always in range.
    private static final String RAMP_LO = "#eff6ff";   // 0.0 — near-white blue
    private static final String RAMP_MID = "#93c5fd";  // 0.5 — mid blue
    private static final String RAMP_HI = "#1e40af";   // 1.0 — deep blue (labels flip to white)

    // Ramp legend geometry: a sampled-step bar (each step one fill rect) between the end labels.
    private static final int RAMP_STEPS = 12;
    private static final double RAMP_W = 180;
    private static final double RAMP_H = 12;
    private static final double RAMP_GAP = 14;          // grid-bottom → legend gap
    private static final double RAMP_LABEL_MAX_W = 140; // legend end labels ellipsize past this

    public static LaidOut layout(Heatmap m) {
        return layout(m, null);
    }

    /// The `math` arg is accepted for dispatch-signature parity with the other types; heatmap cells
    /// are numeric/plain tokens (no `$…$` math), so it is unused. A null renderer changes nothing.
    public static LaidOut layout(Heatmap m, com.sirentide.api.MathFragmentRenderer math) {
        List<Heatmap.Row> rows = m.rows();
        int cols = m.columns().size();
        // With no explicit header, infer the column count from the (already-rectangularized) rows.
        if (cols == 0 && !rows.isEmpty()) {
            cols = rows.get(0).cells().size();
        }

        // Row-label column width: the widest (ellipsized) label, floored + padded.
        double labelW = MIN_LABEL_W;
        for (Heatmap.Row r : rows) {
            labelW = Math.max(labelW, Math.min(MAX_LABEL_W, FONT.runWidth(r.label(), LABEL_SIZE)));
        }
        labelW += 2 * PAD_X;

        // Per-column widths: the wider of the header and any cell token, capped + padded.
        double[] colW = new double[cols];
        for (int j = 0; j < cols; j++) {
            double w = j < m.columns().size() ? FONT.runWidth(m.columns().get(j), LABEL_SIZE) : 0;
            for (Heatmap.Row r : rows) {
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
        double canvasH = MARGIN + gridH + RAMP_GAP + RAMP_H + MARGIN;

        List<Shape> shapes = new ArrayList<>();
        // A blank heatmap still returns a valid (tiny) canvas rather than a degenerate 0×0.
        if (cols == 0 && rows.isEmpty()) {
            return LaidOut.of(MARGIN * 2 + MIN_LABEL_W, MARGIN * 2 + ROW_H);
        }

        // The single border-colour backing rect: every cell insets into it by BORDER_W, so the
        // bleed is the gridline. One rect gives the whole grid its lines with no Line shapes.
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

        // Per-diagram anchor factory — matrix's exact scheme: each DATA cell is ONE
        // `<g data-sirentide-role="cell">` group, row-major seq, COORDINATE-derived base id
        // (`r<row>c<col>`, always charset-legal — a hostile label can't reach the anchor id).
        AnchorAssigner assigner = new AnchorAssigner();

        // Data rows: a left-aligned label cell, then the M value cells on the ramp.
        int rowIdx = 0;
        for (Heatmap.Row r : rows) {
            cell(shapes, MARGIN, y, labelW, ROW_H, LABEL_FILL);
            leftAligned(shapes, r.label(), MARGIN + PAD_X, y + ROW_H / 2, labelW - PAD_X - BORDER_W,
                Colors.contrastFill(LABEL_FILL));
            double cx = MARGIN + labelW;
            for (int j = 0; j < cols; j++) {
                Heatmap.Cell c = j < r.cells().size() ? r.cells().get(j) : new Heatmap.Cell("", 0, true);
                String fill = c.na() ? NA_FILL : rampFill(c.value());
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

        // Ramp legend: low label, the sampled-step bar, high label — a reading line under the grid.
        // Steps are butted (no inset): the bar reads as one continuous ramp, not 12 cells.
        double ly = MARGIN + gridH + RAMP_GAP;
        String lo = m.lowLabel() != null ? m.lowLabel() : "0";
        String hi = m.highLabel() != null ? m.highLabel() : "1";
        String loFit = FONT.ellipsize(lo, RAMP_LABEL_MAX_W, LABEL_SIZE);
        double loW = FONT.runWidth(loFit, LABEL_SIZE);
        double lx = MARGIN;
        double baseline = ly + RAMP_H / 2 + LABEL_SIZE * 0.35;
        text(shapes, loFit, lx, baseline, m.textColor());
        lx += loW + PAD_X;
        double stepW = RAMP_W / RAMP_STEPS;
        for (int s = 0; s < RAMP_STEPS; s++) {
            // Sample each step at its centre so the first/last steps show the ramp's true ends.
            double v = (s + 0.5) / RAMP_STEPS;
            shapes.add(new Rect(lx + s * stepW, ly, stepW, RAMP_H, rampFill(v)));
        }
        lx += RAMP_W + PAD_X;
        String hiFit = FONT.ellipsize(hi, RAMP_LABEL_MAX_W, LABEL_SIZE);
        text(shapes, hiFit, lx, baseline, m.textColor());
        // The legend row may be wider than the grid (long end labels); grow the canvas to hold it.
        canvasW = Math.max(canvasW, lx + FONT.runWidth(hiFit, LABEL_SIZE) + MARGIN);

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// The ramp fill for a clamped 0..1 value: piecewise-linear RGB through the three single-hue
    /// stops. Private colour math on a closed input range — the only fills this can produce sit on
    /// the lo→mid→hi line, so no free-form colour ever enters the output.
    private static String rampFill(double v) {
        return v <= 0.5
            ? lerpHex(RAMP_LO, RAMP_MID, v / 0.5)
            : lerpHex(RAMP_MID, RAMP_HI, (v - 0.5) / 0.5);
    }

    /// Linear per-channel interpolation between two `#rrggbb` fills at t∈[0,1]. Both inputs are
    /// class constants (never user data), so parsing here cannot throw on hostile input.
    private static String lerpHex(String a, String b, double t) {
        int ra = Integer.parseInt(a, 1, 3, 16), ga = Integer.parseInt(a, 3, 5, 16), ba = Integer.parseInt(a, 5, 7, 16);
        int rb = Integer.parseInt(b, 1, 3, 16), gb = Integer.parseInt(b, 3, 5, 16), bb = Integer.parseInt(b, 5, 7, 16);
        int r = (int) Math.round(ra + (rb - ra) * t);
        int g = (int) Math.round(ga + (gb - ga) * t);
        int bl = (int) Math.round(ba + (bb - ba) * t);
        return String.format("#%02x%02x%02x", r, g, bl);
    }

    /// The `data-sirentide-id` base for a data cell: its ROW/COLUMN coordinates (`r<row>c<col>`),
    /// NOT its text — always charset-legal, stable, narratable (matrix's exact rule).
    private static String cellBaseId(int row, int col) {
        return "r" + row + "c" + col;
    }

    /// One cell background: the fill rect inset by BORDER_W into the backing rect so the border
    /// colour shows as the gridline on all four sides.
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

    /// A legend label at an explicit baseline, in the page text colour (the legend sits on the
    /// canvas background, not on a filled cell — same rule as other types' axis labels).
    private static void text(List<Shape> shapes, String text, double x, double baseline, String fill) {
        if (text.isEmpty()) {
            return;
        }
        String d = FONT.textPathD(text, x, baseline, LABEL_SIZE);
        if (!d.isEmpty()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }
}
