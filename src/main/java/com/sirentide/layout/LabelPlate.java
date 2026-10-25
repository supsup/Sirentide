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
/// the contrast pick for that plate. Every pixel of such a label then sits on the plate, so nothing
/// painted BEFORE the label (bars, lifelines, frames, edge and message lines) shows behind its
/// glyphs, whatever the page, the theme or the stroke underneath. A label that crosses nothing keeps
/// its exact former emission (no plate, its own colour), so diagrams without crossings bake
/// byte-identically.
///
/// Paint order inside an edge or message group, the same in both layouts (plan 5ce905a8, review
/// 1335 F1, {@link #place}): the element's lines that its label's INK crosses, then the plate, then
/// its other lines and its arrowheads, then the label glyphs last. The plate has to sit ABOVE every
/// line the ink crosses (a flowchart edge label crosses its own edge line, and a glyph drawn over
/// that line accented to 3px in a play-through frame reads at about 1.45 to 1), and BELOW every
/// arrowhead. A line that only reaches the padding stays on top, which keeps a sequence message's
/// own line drawn over the plate. Arrowheads of LATER groups are drawn after the plate anyway; a
/// filled arrowhead an EARLIER group already painted is cut out of the plate
/// ({@link #plate(double[], List)}), so no plate pixel is ever painted over an arrowhead. Two
/// limits: a label whose ink overlaps an arrowhead now has that arrowhead, not the plate, behind
/// those glyphs (label placement does not avoid arrowheads; follow-up), and an earlier reply's
/// open-V arrowhead is two strokes, not a filled triangle, so it is not cut out
/// (LabelPlateArrowheadGoldenTest checks no plate covers one in any golden or frame).
///
/// NOT covered (review 1335 F2): shapes painted AFTER the label, that is a later edge's or
/// message's lines, arrowheads and plate, a flowchart node or a sequence note, can still be drawn
/// over its glyphs. LabelBackingPlateTest pins the crossings that exist in the checked diagrams.
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

    /// Any path command letter other than absolute M, L, Q, C and Z (review 1335 F4).
    private static final Pattern OTHER_COMMAND = Pattern.compile("[A-DF-Za-df-z&&[^MLQCZ]]");

    /// The union bounding box `[x0, y0, x1, y1]` of glyph path `d` strings. Glyph outlines from
    /// {@code FontMetrics.textPathD} carry only absolute coordinate PAIRS (M/L/Q/Z), so the box of
    /// all points (on-curve and quadratic or cubic controls) contains the ink. A path that uses any
    /// other command (H/V/A/S/T or a relative lower-case one) is SKIPPED: its numbers are not all x,y
    /// pairs (an arc's radii and flags would stretch the box towards the origin), so it contributes
    /// nothing (review 1335 F4). `null` when no point is read.
    static double[] inkBox(List<String> pathDs) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (String d : pathDs) {
            if (d == null || OTHER_COMMAND.matcher(d).find()) {
                continue;
            }
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

    /// Lays the plate for `ink` into an element's shape list (plan 5ce905a8, review 1335 F1).
    /// `group[from, headAt)` are the element's own lines and `group[headAt, end)` its arrowheads;
    /// `painted` is everything drawn before the group. The own lines the ink crosses are moved in
    /// front of the plate, the rest stay after it, so the order becomes: crossed lines, plate, other
    /// lines, arrowheads. Earlier filled arrowheads are cut out of the plate.
    static void place(List<Shape> group, int from, int headAt, double[] ink, List<Shape> painted) {
        List<Shape> crossed = new java.util.ArrayList<>();
        List<Shape> rest = new java.util.ArrayList<>();
        for (Shape s : group.subList(from, headAt)) {
            (s instanceof Line l && segmentTouches(l, ink) ? crossed : rest).add(s);
        }
        List<Shape> under = new java.util.ArrayList<>(painted);
        if (group != painted) {
            under.addAll(group.subList(0, from));
        }
        under.addAll(crossed);
        Shape plate = plate(ink, under);
        List<Shape> span = group.subList(from, headAt);
        span.clear();
        span.addAll(crossed);
        span.add(plate);
        span.addAll(rest);
    }

    /// The plate rectangle for an ink box: the box grown by {@link #PAD} on every side.
    static Rect plate(double[] box) {
        return new Rect(box[0] - PAD, box[1] - PAD, (box[2] - box[0]) + 2 * PAD,
            (box[3] - box[1]) + 2 * PAD, FILL);
    }

    /// The plate for an ink box that is painted AFTER `under` (plan 5ce905a8, review 1335 F1). It is
    /// {@link #plate(double[])} unless a filled arrowhead in `under` (any closed three-point M/L path,
    /// searched inside groups too) reaches into the plate: then the plate is a structural
    /// {@link Path} of the same rectangle with the arrowheads cut out under the non-zero fill rule
    /// (the contract has no fill-rule attribute), so the earlier arrowheads stay visible. Each
    /// triangle is clipped to the rectangle and wound against it; arrowheads that converge on one
    /// tip overlap, so every k-way overlap is added back with the alternating winding of
    /// inclusion-exclusion, which leaves winding 0 (unfilled) at every point of every arrowhead.
    static Shape plate(double[] box, List<Shape> under) {
        Rect r = plate(box);
        double rx0 = r.x();
        double ry0 = r.y();
        double rx1 = r.x() + r.width();
        double ry1 = r.y() + r.height();
        List<double[]> rect = List.of(new double[] {rx0, ry0}, new double[] {rx1, ry0},
            new double[] {rx1, ry1}, new double[] {rx0, ry1});
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<List<double[]>> cuts = new java.util.ArrayList<>();
        for (double[] t : triangles(under, new java.util.ArrayList<>())) {
            if (!seen.add(java.util.Arrays.toString(t))) {
                continue;
            }
            List<double[]> poly = clip(positive(List.of(new double[] {t[0], t[1]},
                new double[] {t[2], t[3]}, new double[] {t[4], t[5]})), rect);
            if (poly.size() >= 3 && signedArea(poly) > 1e-6) {
                cuts.add(poly);
            }
        }
        if (cuts.isEmpty()) {
            return r;
        }
        StringBuilder d = new StringBuilder();
        ring(d, rect);
        overlaps(d, cuts, 0, null, 0);
        return new Path(d.toString().trim(), FILL, null, 0, true);
    }

    /// Inclusion-exclusion over the cut polygons: every non-empty intersection of k of them is
    /// emitted reverse-wound for odd k and forward-wound for even k (all inputs are positive).
    private static void overlaps(StringBuilder d, List<List<double[]>> cuts, int from,
                                 List<double[]> acc, int k) {
        for (int i = from; i < cuts.size(); i++) {
            List<double[]> next = acc == null ? cuts.get(i) : clip(acc, cuts.get(i));
            if (next.size() < 3 || signedArea(next) <= 1e-6) {
                continue;
            }
            List<double[]> ringPts = new java.util.ArrayList<>(next);
            if ((k + 1) % 2 == 1) {
                java.util.Collections.reverse(ringPts);
            }
            ring(d, ringPts);
            overlaps(d, cuts, i + 1, next, k + 1);
        }
    }

    private static void ring(StringBuilder d, List<double[]> pts) {
        for (int i = 0; i < pts.size(); i++) {
            d.append(i == 0 ? " M " : " L ").append(fmt(pts.get(i)[0])).append(' ').append(fmt(pts.get(i)[1]));
        }
        d.append(" Z");
    }

    private static List<double[]> positive(List<double[]> poly) {
        List<double[]> out = new java.util.ArrayList<>(poly);
        if (signedArea(out) < 0) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    private static final Pattern TRIANGLE = Pattern.compile(
        "M (-?[0-9.]+) (-?[0-9.]+) L (-?[0-9.]+) (-?[0-9.]+) L (-?[0-9.]+) (-?[0-9.]+) Z");

    /// Every filled triangle (an arrowhead: `M x y L x y L x y Z`) in `shapes`, groups included.
    private static List<double[]> triangles(List<Shape> shapes, List<double[]> out) {
        for (Shape s : shapes) {
            if (s instanceof Group g) {
                triangles(g.members(), out);
            } else if (s instanceof Path p && !p.structural() && p.d() != null) {
                Matcher m = TRIANGLE.matcher(p.d());
                if (m.matches()) {
                    double[] t = new double[6];
                    for (int i = 0; i < 6; i++) {
                        t[i] = Double.parseDouble(m.group(i + 1));
                    }
                    out.add(t);
                }
            }
        }
        return out;
    }

    /// Sutherland-Hodgman clip of convex polygon `subject` by convex polygon `clipper`, both wound
    /// positively (signed area > 0 in SVG coordinates).
    private static List<double[]> clip(List<double[]> subject, List<double[]> clipper) {
        List<double[]> poly = new java.util.ArrayList<>(subject);
        for (int e = 0; e < clipper.size() && !poly.isEmpty(); e++) {
            double[] c0 = clipper.get(e);
            double[] c1 = clipper.get((e + 1) % clipper.size());
            List<double[]> in = poly;
            poly = new java.util.ArrayList<>();
            for (int i = 0; i < in.size(); i++) {
                double[] a = in.get(i);
                double[] b = in.get((i + 1) % in.size());
                double sa = side(c0, c1, a);
                double sb = side(c0, c1, b);
                if (sa >= 0) {
                    poly.add(a);
                }
                if ((sa >= 0) != (sb >= 0)) {
                    double k = sa / (sa - sb);
                    poly.add(new double[] {a[0] + k * (b[0] - a[0]), a[1] + k * (b[1] - a[1])});
                }
            }
        }
        return poly;
    }

    /// Positive when `p` is on the inner side of the directed edge c0 to c1 of a positive polygon.
    private static double side(double[] c0, double[] c1, double[] p) {
        return (c1[0] - c0[0]) * (p[1] - c0[1]) - (c1[1] - c0[1]) * (p[0] - c0[0]);
    }

    private static double signedArea(List<double[]> poly) {
        double a = 0;
        for (int i = 0; i < poly.size(); i++) {
            double[] p = poly.get(i);
            double[] q = poly.get((i + 1) % poly.size());
            a += p[0] * q[1] - q[0] * p[1];
        }
        return a / 2;
    }

    /// Deterministic 3-dp number formatting, the same as the layouts' arrowhead path data.
    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            v = 0.0;
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
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
