package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Point;
import com.sirentide.ir.QuadrantChart;
import java.util.List;
import java.util.ArrayList;

/// Pure quadrant-chart layout: a square plot split into four quadrants by a horizontal + vertical
/// axis crossing at the centre. Deterministic arithmetic, zero graph optimization (docs/DESIGN.md
/// §6). The unit square `[0,1]×[0,1]` maps affinely onto the plot: `x=0`→left edge, `x=1`→right
/// edge, `y=0`→BOTTOM edge, `y=1`→TOP edge (y measured UP, SVG-flipped once here).
///
/// QUADRANT NUMBERING (Mermaid): `quadrantLabels[0]`=Q1=top-right, `[1]`=Q2=top-left,
/// `[2]`=Q3=bottom-left, `[3]`=Q4=bottom-right. Each quadrant gets a soft LIGHT tint (a filled
/// {@link Rect}); its label and any point label sit on that tint and take a contrast-derived fill
/// ({@link Colors#contrastFill}) so they read on light AND dark host pages. The axis-end labels sit
/// OFF the plot on the page background and take the chart's `textColor` (default `currentColor`).
/// Points are full-circle {@link Wedge} discs coloured by {@link Colors#PALETTE} order; each point's
/// label rides to its right, ellipsized to the room left before the canvas edge so it never overruns.
public final class QuadrantChartLayout {

    private QuadrantChartLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    /// The square plot side, plus the four margins that hold the off-plot axis-end labels.
    private static final double PLOT = 260;
    private static final double MT = 24;    // top margin
    private static final double MB = 30;    // bottom margin (x-axis end labels)
    private static final double ML = 78;    // left margin (y-axis end labels)
    private static final double MR = 24;    // right margin

    private static final double LABEL_SIZE = 11;
    /// Axis border + crossing-axis stroke (mirrors the xychart/pie axis grey).
    private static final String AXIS_STROKE = "#94a3b8";
    /// The two centre axes are drawn a touch heavier than the border so the 2×2 split reads clearly.
    private static final double AXIS_WIDTH = 1.5;
    private static final double BORDER_WIDTH = 1;
    /// Point-disc radius (a touch larger than the scatter dot — it carries a label).
    private static final double DOT_R = 4;
    /// Gap between a disc and the start of its label, and the general in-frame clamp margin.
    private static final double GAP = 4;
    private static final double CLAMP_MARGIN = 2;

    /// The four quadrant tints — soft, light, DISTINCT fills (contract-clean `#rrggbb`). Indexed by
    /// Mermaid quadrant number − 1: `[0]`=Q1 top-right, `[1]`=Q2 top-left, `[2]`=Q3 bottom-left,
    /// `[3]`=Q4 bottom-right. Deliberately light so {@link Colors#contrastFill} yields a dark label
    /// ink that reads on either host theme.
    private static final String[] TINTS = {
        "#eaf2fb",   // Q1 top-right    — light blue
        "#eafbf1",   // Q2 top-left     — light green
        "#fdf2ec",   // Q3 bottom-left  — light orange
        "#f6eefb"    // Q4 bottom-right — light purple
    };

    /// Lays out the chart: tints → border + crossing axes → quadrant labels → point discs + labels →
    /// axis-end labels. A bare `quadrant` (no labels, no points) still yields a valid empty 2×2 grid.
    public static LaidOut layout(QuadrantChart q) {
        return layout(q, null);
    }

    /// Inline-math entry (plan sirentide-math-in-all-label-types): a `$…$` run in a POINT label, a
    /// QUADRANT label, or an AXIS-END label bakes through the shared {@link MathLabel} seam. A null
    /// `math` degrades every `$…$` to plain text — byte-identical to {@link #layout(QuadrantChart)}.
    public static LaidOut layout(QuadrantChart q, MathFragmentRenderer math) {
        double plotLeft = ML;
        double plotRight = ML + PLOT;
        double plotTop = MT;
        double plotBottom = MT + PLOT;
        double cx = plotLeft + PLOT / 2;      // axis crossing x (plot centre)
        double cy = plotTop + PLOT / 2;       // axis crossing y (plot centre)
        double half = PLOT / 2;
        double canvasW = ML + PLOT + MR;
        double canvasH = MT + PLOT + MB;

        List<Shape> shapes = new ArrayList<>();

        // 1. The four quadrant tints (Q2 top-left, Q1 top-right, Q3 bottom-left, Q4 bottom-right).
        shapes.add(new Rect(plotLeft, plotTop, half, half, TINTS[1]));   // Q2 top-left
        shapes.add(new Rect(cx, plotTop, half, half, TINTS[0]));         // Q1 top-right
        shapes.add(new Rect(plotLeft, cy, half, half, TINTS[2]));        // Q3 bottom-left
        shapes.add(new Rect(cx, cy, half, half, TINTS[3]));             // Q4 bottom-right

        // 2. The plot border (four edges) + the two crossing centre axes.
        shapes.add(new Line(plotLeft, plotTop, plotRight, plotTop, AXIS_STROKE, BORDER_WIDTH));
        shapes.add(new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, BORDER_WIDTH));
        shapes.add(new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, BORDER_WIDTH));
        shapes.add(new Line(plotRight, plotTop, plotRight, plotBottom, AXIS_STROKE, BORDER_WIDTH));
        shapes.add(new Line(cx, plotTop, cx, plotBottom, AXIS_STROKE, AXIS_WIDTH));       // vertical axis
        shapes.add(new Line(plotLeft, cy, plotRight, cy, AXIS_STROKE, AXIS_WIDTH));       // horizontal axis

        // 3. Quadrant labels, centred in each cell, contrast-filled against that cell's tint. Cell
        // centres: Q1 (0.75,0.25up)=(cx+half/2, plotTop+half/2), etc.
        double qxL = plotLeft + half / 2;   // left-column cell centre x
        double qxR = cx + half / 2;         // right-column cell centre x
        double qyT = plotTop + half / 2;    // top-row cell centre y
        double qyB = cy + half / 2;         // bottom-row cell centre y
        String[] labels = q.quadrantLabels();
        quadrantLabel(shapes, labels[0], qxR, qyT, TINTS[0], math);   // Q1 top-right
        quadrantLabel(shapes, labels[1], qxL, qyT, TINTS[1], math);   // Q2 top-left
        quadrantLabel(shapes, labels[2], qxL, qyB, TINTS[2], math);   // Q3 bottom-left
        quadrantLabel(shapes, labels[3], qxR, qyB, TINTS[3], math);   // Q4 bottom-right

        // 4. Points: a palette disc at (x,y) in the unit square (y flipped UP) + its label to the
        // right, ellipsized to the room before the canvas edge. The label's contrast fill keys off
        // the quadrant tint the disc lands in (all tints light → a dark, page-theme-agnostic ink).
        List<Point> points = q.points();
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            double px = plotLeft + p.x() * PLOT;
            double py = plotBottom - p.y() * PLOT;   // y UP: y=0 → bottom, y=1 → top
            String fill = Colors.PALETTE[i % Colors.PALETTE.length];
            shapes.add(new Wedge(px, py, DOT_R, 0, 2 * Math.PI, fill));
            String tint = TINTS[quadrantIndex(p.x(), p.y())];
            double originX = px + DOT_R + GAP;
            double baseline = py + LABEL_SIZE * 0.35;
            // A `$…$` point label (`$O(n)$`, `$E=mc^2$`) bakes through the shared MathLabel seam; a
            // math label SKIPS the room-ellipsize (a formula must not be cut mid-run) and rides right
            // of its disc on its composite width. A plain label is byte-identical to before.
            if (math != null && MathLabel.hasMath(p.label())) {
                MathLabel.Measured mm = MathLabel.measure(p.label(), LABEL_SIZE, FONT, math);
                MathLabel.emit(mm, originX, baseline, Colors.contrastFill(tint), LABEL_SIZE, FONT, shapes);
            } else {
                double room = canvasW - originX - CLAMP_MARGIN;
                String label = FONT.ellipsize(p.label(), room, LABEL_SIZE);
                if (!label.isBlank()) {
                    String d = FONT.textPathD(label, originX, baseline, LABEL_SIZE);
                    if (!d.isBlank()) {
                        shapes.add(new GlyphRun(d, Colors.contrastFill(tint)));
                    }
                }
            }
        }

        // 5. Axis-end labels on the page background (textColor). x ends sit in the bottom margin,
        // centred under each half; y ends sit in the left margin, right-aligned to the plot edge.
        String textColor = q.textColor();
        double xBaseline = plotBottom + MB * 0.6;
        centeredLabel(shapes, q.xLo(), plotLeft + PLOT * 0.25, xBaseline, half - GAP, textColor, math);
        centeredLabel(shapes, q.xHi(), plotLeft + PLOT * 0.75, xBaseline, half - GAP, textColor, math);
        double yRoom = plotLeft - GAP - CLAMP_MARGIN;   // room in the left margin
        rightLabel(shapes, q.yHi(), plotLeft - GAP, plotTop + LABEL_SIZE, yRoom, textColor, math);
        rightLabel(shapes, q.yLo(), plotLeft - GAP, plotBottom - GAP, yRoom, textColor, math);

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// The Mermaid quadrant index (0..3) for a unit-square point: `x≥0.5` is the RIGHT column,
    /// `y≥0.5` the TOP row. Q1 top-right=0, Q2 top-left=1, Q3 bottom-left=2, Q4 bottom-right=3.
    private static int quadrantIndex(double x, double y) {
        boolean right = x >= 0.5;
        boolean top = y >= 0.5;
        if (top) {
            return right ? 0 : 1;
        }
        return right ? 3 : 2;
    }

    /// A quadrant label centred in its cell, contrast-filled against the cell tint and ellipsized to
    /// the cell width so a long label can't spill across the axis into the neighbouring quadrant.
    private static void quadrantLabel(List<Shape> shapes, String text, double cx, double cy,
                                      String tint, MathFragmentRenderer math) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Pass the RAW text to the (math-aware) centred emitter — for plain text it ellipsizes to the
        // same cell width, so the bake is byte-identical; a `$…$` label routes through MathLabel.
        centeredLabel(shapes, text, cx, cy + LABEL_SIZE * 0.35, PLOT / 2 - GAP,
            Colors.contrastFill(tint), math);
    }

    /// A horizontally-centred glyph-run label at `(cx, baseline)`, ellipsized to `maxWidth`. A null
    /// or empty label draws nothing (an absent axis-end / quadrant label is simply omitted). A `$…$`
    /// label bakes through the shared MathLabel seam (math skips the ellipsize, centres on the
    /// composite width); a plain label is byte-identical to before.
    private static void centeredLabel(List<Shape> shapes, String text, double cx, double baseline,
                                      double maxWidth, String fill, MathFragmentRenderer math) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (math != null && MathLabel.hasMath(text)) {
            MathLabel.Measured mm = MathLabel.measure(text, LABEL_SIZE, FONT, math);
            MathLabel.emit(mm, cx - mm.width() / 2, baseline, fill, LABEL_SIZE, FONT, shapes);
            return;
        }
        String fit = FONT.ellipsize(text, maxWidth, LABEL_SIZE);
        double w = FONT.runWidth(fit, LABEL_SIZE);
        String d = FONT.textPathD(fit, cx - w / 2, baseline, LABEL_SIZE);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }

    /// A right-aligned glyph-run label whose text ENDS at `rightX` (the y-axis end labels sit in the
    /// left margin and read up to the plot edge), ellipsized to `maxWidth` so a long label can't run
    /// off the left edge of the canvas. A `$…$` label routes through MathLabel (math skips the
    /// ellipsize, right-aligns on the composite width); a plain label is byte-identical to before.
    private static void rightLabel(List<Shape> shapes, String text, double rightX, double baseline,
                                   double maxWidth, String fill, MathFragmentRenderer math) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (math != null && MathLabel.hasMath(text)) {
            MathLabel.Measured mm = MathLabel.measure(text, LABEL_SIZE, FONT, math);
            double originX = Math.max(CLAMP_MARGIN, rightX - mm.width());
            MathLabel.emit(mm, originX, baseline, fill, LABEL_SIZE, FONT, shapes);
            return;
        }
        String fit = FONT.ellipsize(text, maxWidth, LABEL_SIZE);
        double w = FONT.runWidth(fit, LABEL_SIZE);
        double originX = Math.max(CLAMP_MARGIN, rightX - w);
        String d = FONT.textPathD(fit, originX, baseline, LABEL_SIZE);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }
}
