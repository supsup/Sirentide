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
///
/// Plan f4d69e44 extensions (grammar in {@link Heatmap}), each gated so a source that uses none of
/// them takes the original code path shape-for-shape (byte-identical output):
/// - an OUTLINED cell's fill rect carries a stroke, inset by half the stroke width so its outer edge
///   is the plain cell's edge (no gridline overpaint, no geometry change), in the label's contrast
///   colour;
/// - CATEGORICAL mode fills a cell from its category's colour and replaces the ramp legend with a
///   wrapped row of swatch + name entries;
/// - a custom `ramp:` replaces the three default stops; `bins:` quantizes a value to its bin's
///   colour (bin k of B → ramp at k/(B-1)) and draws a B-step legend with value-proportional widths;
/// - `hide:` drops the row-label column (width 0) and/or the header band.
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

    private static final double OUTLINE_W = 2;            // outlined-cell stroke width
    private static final double SWATCH_GAP = 4;           // categorical legend: swatch → name
    private static final double ENTRY_GAP = 14;           // categorical legend: entry → entry
    private static final double LEGEND_ROW_GAP = 6;       // categorical legend: wrapped-row gap
    private static final double MIN_LEGEND_WRAP_W = 240;  // categorical legend never wraps narrower

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

        // Row-label column width: the widest (ellipsized) label, floored + padded — or 0 when
        // `hide: rows` drops the column.
        double labelW = MIN_LABEL_W;
        for (Heatmap.Row r : rows) {
            labelW = Math.max(labelW, Math.min(MAX_LABEL_W, FONT.runWidth(r.label(), LABEL_SIZE)));
        }
        labelW += 2 * PAD_X;
        boolean showLabels = !m.hideRowLabels();
        if (!showLabels) {
            labelW = 0;
        }
        Fills fills = new Fills(m);

        // Per-column widths: the wider of the header and any cell token, capped + padded.
        double[] colW = new double[cols];
        for (int j = 0; j < cols; j++) {
            double w = j < m.columns().size() && !m.hideColumnHeaders()
                ? FONT.runWidth(m.columns().get(j), LABEL_SIZE) : 0;
            for (Heatmap.Row r : rows) {
                if (j < r.cells().size()) {
                    w = Math.max(w, FONT.runWidth(r.cells().get(j).text(), LABEL_SIZE));
                }
            }
            colW[j] = Math.max(MIN_CELL_W, Math.min(MAX_CELL_W, w) + 2 * PAD_X);
        }

        boolean hasHeader = !m.columns().isEmpty() && !m.hideColumnHeaders();
        double gridW = labelW;
        for (double w : colW) {
            gridW += w;
        }
        double gridH = (hasHeader ? HEADER_H : 0) + rows.size() * ROW_H;
        double canvasW = MARGIN + gridW + MARGIN;
        // The categorical legend may wrap to several rows; every other legend is one RAMP_H row.
        List<double[]> swatchRows = m.categorical() ? swatchLayout(m, Math.max(gridW, MIN_LEGEND_WRAP_W)) : null;
        double legendH = swatchRows == null ? RAMP_H
            : swatchRows.stream().mapToDouble(e -> e[1]).max().orElse(0) * (RAMP_H + LEGEND_ROW_GAP) + RAMP_H;
        double canvasH = MARGIN + gridH + RAMP_GAP + legendH + MARGIN;

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
            if (showLabels) {
                cell(shapes, MARGIN, y, labelW, HEADER_H, HEADER_FILL);
            }
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
            if (showLabels) {
                cell(shapes, MARGIN, y, labelW, ROW_H, LABEL_FILL);
                leftAligned(shapes, r.label(), MARGIN + PAD_X, y + ROW_H / 2, labelW - PAD_X - BORDER_W,
                    Colors.contrastFill(LABEL_FILL));
            }
            double cx = MARGIN + labelW;
            for (int j = 0; j < cols; j++) {
                Heatmap.Cell c = j < r.cells().size() ? r.cells().get(j) : new Heatmap.Cell("", 0, true);
                String fill = c.na() ? NA_FILL : fills.of(c);
                List<Shape> cellShapes = new ArrayList<>();
                if (c.outlined()) {
                    outlinedCell(cellShapes, cx, y, colW[j], ROW_H, fill);
                } else {
                    cell(cellShapes, cx, y, colW[j], ROW_H, fill);
                }
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
        if (swatchRows != null) {
            categoryLegend(shapes, m, fills, swatchRows, ly);
            double right = swatchRows.stream().mapToDouble(e -> e[0] + e[2]).max().orElse(MARGIN);
            canvasW = Math.max(canvasW, right + MARGIN);
            return new LaidOut(canvasW, canvasH, shapes);
        }
        String lo = m.lowLabel() != null ? m.lowLabel() : "0";
        String hi = m.highLabel() != null ? m.highLabel() : "1";
        String loFit = FONT.ellipsize(lo, RAMP_LABEL_MAX_W, LABEL_SIZE);
        double loW = FONT.runWidth(loFit, LABEL_SIZE);
        double lx = MARGIN;
        double baseline = ly + RAMP_H / 2 + LABEL_SIZE * 0.35;
        text(shapes, loFit, lx, baseline, m.textColor());
        lx += loW + PAD_X;
        if (!cuts(m).isEmpty()) {
            // Stepped legend: one rect per bin, as wide as the bin's share of 0..1, in its bin colour.
            List<Double> t = cuts(m);
            for (int k = 0; k <= t.size(); k++) {
                double from = k == 0 ? 0 : t.get(k - 1);
                double to = k == t.size() ? 1 : t.get(k);
                shapes.add(new Rect(lx + from * RAMP_W, ly, (to - from) * RAMP_W, RAMP_H, fills.bin(k)));
            }
        } else {
            int steps = fills.legendSteps();
            double stepW = RAMP_W / steps;
            for (int s = 0; s < steps; s++) {
                // Sample each step at its centre so the first/last steps show the ramp's true ends.
                double v = (s + 0.5) / steps;
                shapes.add(new Rect(lx + s * stepW, ly, stepW, RAMP_H, fills.ramp(v)));
            }
        }
        lx += RAMP_W + PAD_X;
        String hiFit = FONT.ellipsize(hi, RAMP_LABEL_MAX_W, LABEL_SIZE);
        text(shapes, hiFit, lx, baseline, m.textColor());
        // The legend row may be wider than the grid (long end labels); grow the canvas to hold it.
        canvasW = Math.max(canvasW, lx + FONT.runWidth(hiFit, LABEL_SIZE) + MARGIN);

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Resolves a non-NA cell's fill for one heatmap: categorical (palette colour), binned (bin
    /// colour), or continuous (ramp, default or custom). The default ramp with no bins is EXACTLY
    /// {@link #rampFill}, the pre-extension path.
    private static final class Fills {
        private final Heatmap m;
        private final List<String> stops;   // null → the default three-stop ramp

        Fills(Heatmap m) {
            this.m = m;
            this.stops = m.ramp() != null && m.ramp().size() >= 2 ? m.ramp() : null;
        }

        String of(Heatmap.Cell c) {
            if (m.categorical()) {
                return category(c.category());
            }
            if (!cuts(m).isEmpty()) {
                return bin(binOf(c.value()));
            }
            return ramp(c.value());
        }

        /// How many steps the continuous legend samples: {@link #RAMP_STEPS} for the default ramp
        /// (the pre-extension legend, unchanged) and for any custom ramp it already samples at least
        /// twice per segment (up to 7 stops); a longer custom ramp gets 2 samples per segment
        /// (16 stops → 30), so no stop's segment is aliased out of the legend.
        int legendSteps() {
            return stops == null ? RAMP_STEPS : Math.max(RAMP_STEPS, 2 * (stops.size() - 1));
        }

        /// The bin index of a value: how many thresholds it reaches (a value ON a cut goes up).
        int binOf(double v) {
            int k = 0;
            for (double t : cuts(m)) {
                if (v >= t) {
                    k++;
                }
            }
            return k;
        }

        /// Bin k of B is the ramp at k/(B-1): the first and last bins carry the ramp's true ends.
        String bin(int k) {
            int b = cuts(m).size() + 1;
            return ramp((double) k / (b - 1));
        }

        String ramp(double v) {
            if (stops == null) {
                return rampFill(v);
            }
            int segs = stops.size() - 1;
            double pos = Math.max(0, Math.min(1, v)) * segs;
            int i = Math.min(segs - 1, (int) Math.floor(pos));
            return lerpHex(stops.get(i), stops.get(i + 1), pos - i);
        }

        /// A category's colour: its parse-validated hex, else the default categorical palette at the
        /// entry's index. A name not in the palette never reaches here (the parser made it NA).
        String category(String name) {
            List<Heatmap.Category> p = m.palette();
            for (int i = 0; i < p.size(); i++) {
                if (p.get(i).name().equals(name)) {
                    String hex = p.get(i).color();
                    return hex != null ? hex : Colors.PALETTE[i % Colors.PALETTE.length];
                }
            }
            return NA_FILL;
        }
    }

    /// The bin thresholds, null-safe (an IR built with a null list means "no bins").
    private static List<Double> cuts(Heatmap m) {
        return m.thresholds() == null ? List.of() : m.thresholds();
    }

    /// Lays the categorical legend entries out left-to-right, wrapping past `wrapW`. Each entry is
    /// `{x, row, width}` where width covers swatch + gap + (ellipsized) name.
    private static List<double[]> swatchLayout(Heatmap m, double wrapW) {
        List<double[]> out = new ArrayList<>();
        double x = MARGIN;
        int row = 0;
        for (Heatmap.Category c : m.palette()) {
            String fit = FONT.ellipsize(c.name(), RAMP_LABEL_MAX_W, LABEL_SIZE);
            double w = RAMP_H + SWATCH_GAP + FONT.runWidth(fit, LABEL_SIZE);
            if (x > MARGIN && x + w > MARGIN + wrapW) {
                x = MARGIN;
                row++;
            }
            out.add(new double[] {x, row, w});
            x += w + ENTRY_GAP;
        }
        return out;
    }

    private static void categoryLegend(List<Shape> shapes, Heatmap m, Fills fills,
                                       List<double[]> layout, double ly) {
        for (int i = 0; i < layout.size(); i++) {
            Heatmap.Category c = m.palette().get(i);
            double[] e = layout.get(i);
            double y = ly + e[1] * (RAMP_H + LEGEND_ROW_GAP);
            shapes.add(new Rect(e[0], y, RAMP_H, RAMP_H, fills.category(c.name())));
            String fit = FONT.ellipsize(c.name(), RAMP_LABEL_MAX_W, LABEL_SIZE);
            text(shapes, fit, e[0] + RAMP_H + SWATCH_GAP, y + RAMP_H / 2 + LABEL_SIZE * 0.35, m.textColor());
        }
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

    /// An OUTLINED cell: the same fill rect as {@link #cell}, stroked. SVG centres a stroke on the
    /// rect edge, so the rect is inset by half the stroke width — its OUTER stroke edge lands
    /// exactly on the plain cell's edge, covering the same area (no gridline overpaint). The stroke
    /// takes the label's contrast colour so it reads on both light and dark fills.
    private static void outlinedCell(List<Shape> shapes, double x, double y, double w, double h, String fill) {
        double in = BORDER_W + OUTLINE_W / 2;
        shapes.add(new Rect(x + in, y + in, w - 2 * in, h - 2 * in, fill,
            Colors.contrastFill(fill), OUTLINE_W));
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
