package com.sirentide.layout;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A BACKING PLATE for a free-standing label whose ink crosses a stroke or fill painted before it
/// (plan 5ce905a8). A sequence message label that runs over an activation bar or a lifeline, or a
/// flowchart edge label that sits across an edge line, used to draw its page-coloured text straight
/// onto that stroke: `#e2e8f0` text on a `#c7d2fe` bar is 1.21 to 1 on a dark docs page.
///
/// The label's own colour cannot be trusted against an arbitrary stroke, because the default text
/// colour is `currentColor` (whatever the host page says). So a label that crosses something gets
/// an opaque plate of {@link #FILL} under its whole ink box, and its glyphs switch to {@link #TEXT},
/// the contrast pick for that plate. Every pixel of such a label then sits on the plate, whatever the
/// page, the theme or the stroke underneath. A label that crosses nothing keeps its exact former
/// emission (no plate, its own colour), so diagrams without crossings bake byte-identically.
///
/// Scope: plain glyph labels only. A typeset math label ({@link MathBox}) has its extent inside the
/// fragment's own SVG, not in layout geometry, so it is not plated.
final class LabelPlate {

    private LabelPlate() {}

    /// The plate fill: a near-white indigo tint, distinct from every other sequence/flowchart fill so
    /// a test can key on it.
    static final String FILL = "#f5f7ff";

    /// The label colour on a plate: the luminance pick against {@link #FILL} (black, about 19 to 1).
    static final String TEXT = Colors.contrastFill(FILL);

    /// Padding added on every side of the ink box, so outline anti-aliasing stays on the plate.
    static final double PAD = 2;

    /// A label glyph run laid on a plate: {@link #TEXT}, flagged {@link GlyphRun#onPlate} so the
    /// play-through frames recolour it against the plate, not the page (plan 5ce905a8).
    static GlyphRun text(String pathD) {
        return new GlyphRun(pathD, TEXT, true);
    }

    private static final Pattern NUM = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");

    /// The union bounding box `[x0, y0, x1, y1]` of glyph path `d` strings. Glyph outlines from
    /// {@code FontMetrics.textPathD} carry only absolute coordinate PAIRS (M/L/Q/Z), so the box of
    /// all points (on-curve and quadratic controls) contains the ink. `null` when there is no point.
    static double[] inkBox(List<String> pathDs) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (String d : pathDs) {
            Matcher m = NUM.matcher(d);
            while (m.find()) {
                double x = Double.parseDouble(m.group());
                if (!m.find()) {
                    break;
                }
                double y = Double.parseDouble(m.group());
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
        }
        if (!(x0 <= x1 && y0 <= y1)) {
            return null;
        }
        return new double[] {x0, y0, x1, y1};
    }

    /// Whether any stroke or fill in `painted` (already emitted, so drawn UNDER the label) touches
    /// `box`. A {@link Line} counts with half its stroke width on every side; a {@link Rect} or a
    /// {@link Path} by its bounding box. Glyph runs (text against text is a separate lint), wedges and
    /// math boxes are not strokes under a sequence or flowchart label and are ignored.
    static boolean crossesPainted(double[] box, List<Shape> painted) {
        for (Shape s : painted) {
            if (s instanceof Group g) {
                if (crossesPainted(box, g.members())) {
                    return true;
                }
            } else if (s instanceof Line l) {
                if (segmentTouches(l, box)) {
                    return true;
                }
            } else if (s instanceof Rect r) {
                if (overlaps(box, r.x(), r.y(), r.x() + r.width(), r.y() + r.height())) {
                    return true;
                }
            } else if (s instanceof Path p && p.d() != null) {
                double[] pb = inkBox(List.of(p.d()));
                if (pb != null && overlaps(box, pb[0], pb[1], pb[2], pb[3])) {
                    return true;
                }
            }
        }
        return false;
    }

    /// The plate rectangle for an ink box: the box grown by {@link #PAD} on every side.
    static Rect plate(double[] box) {
        return new Rect(box[0] - PAD, box[1] - PAD, (box[2] - box[0]) + 2 * PAD,
            (box[3] - box[1]) + 2 * PAD, FILL);
    }

    private static boolean overlaps(double[] b, double x0, double y0, double x1, double y1) {
        return x0 < b[2] && b[0] < x1 && y0 < b[3] && b[1] < y1;
    }

    /// Liang-Barsky clip of the segment against `box` grown by half the stroke width.
    private static boolean segmentTouches(Line l, double[] box) {
        double h = Math.max(0, l.strokeWidth()) / 2;
        double minX = box[0] - h;
        double minY = box[1] - h;
        double maxX = box[2] + h;
        double maxY = box[3] + h;
        double dx = l.x2() - l.x1();
        double dy = l.y2() - l.y1();
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {l.x1() - minX, maxX - l.x1(), l.y1() - minY, maxY - l.y1()};
        double t0 = 0;
        double t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }
                if (t0 > t1) {
                    return false;
                }
            }
        }
        return Double.isFinite(t0) && Double.isFinite(t1);
    }
}
