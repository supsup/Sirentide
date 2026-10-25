package com.sirentide.layout;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A BACKING PLATE under EVERY plain-text flowchart edge label and sequence message label (plan
/// 5ce905a8, widened to every such label by plan 3e1104cf). A sequence message label that runs over
/// an activation bar or a lifeline, or a flowchart edge label that sits across an edge line, used to
/// draw its page-coloured text straight onto that stroke: `#e2e8f0` text on a `#c7d2fe` bar is 1.21
/// to 1 on a dark docs page. A label that crossed nothing first kept its own colour on the bare
/// page; on a dark host page that read badly too (plan 3e1104cf: `stop or removal unknown` in the
/// lane-live-acceptance flowchart, moved clear of every line by the decollider), and in the
/// play-through frames its page-level accent and dim are below 4.5 to 1 on a light page.
///
/// The label's own colour cannot be trusted against an arbitrary stroke or page, because the
/// default text colour is `currentColor` (whatever the host page says). So every plain edge and
/// message label gets an opaque plate of {@link #FILL} under its whole ink box, whether or not the
/// ink crosses anything, and its glyphs switch to {@link #TEXT}, the contrast pick for that plate.
/// Every label glyph then sits on its own plate whatever the page colour, in the bake and in every
/// frame. ({@link #crossesPainted} no longer decides whether a label is plated; {@link #place}
/// still decides which of the element's own lines go under the plate.)
///
/// The placement and plate rules below compare BOUNDING BOXES (the label's ink box, an
/// arrowhead's triangle box, a line's stroke), not rendered pixels; the final-composition test
/// (LabelBackingPlateTest) also samples points inside the actual glyph outlines. For a plain
/// flowchart edge label:
///   - BEHIND the glyphs: the plate covers every line, bar and fill painted before the label, so
///     none of them shows behind its ink box. An earlier group's filled arrowhead is cut out of the
///     plate ({@link #plate(double[], List, List)}) rather than covered, and an earlier label's ink
///     box is cut out of a later label's plate, so no plate is painted over an arrowhead or over
///     any part of an earlier label (review 1341: not even the whitespace between its glyphs).
///   - NO ARROWHEAD under or over the glyphs (review 1337 F1): an edge label's ink box is placed
///     clear of every arrowhead in the diagram, its own included
///     ({@code FlowchartLayout.LabelDecollider}), so an active frame never draws the accented
///     label over its own accented head (`#c2410c` on `#e8590c` is 1.45 to 1).
///   - NOTHING LATER over the glyphs (review 1337 F2): a later edge's line that would reach the
///     ink box, even at the active frame's doubled stroke width, is broken around it ({@link #gap}),
///     a later arrowhead is avoided by the placement above, a later label's ink box is placed at
///     least {@link #PAD} clear of it (so two edge labels' ink boxes never overlap and a later
///     plate cannot reach its ink), and a later plate has its ink box cut out as a safety net.
///
/// Paint order inside an edge or message group, the same in both layouts ({@link #place}): the
/// element's lines that its label's INK crosses, then the plate, then its other lines and its
/// arrowheads, then the label glyphs last. The plate sits ABOVE every line the ink crosses (a
/// glyph drawn over its own line accented to 3px in a play-through frame reads at about 1.45 to 1)
/// and BELOW every arrowhead.
///
/// NOT covered, on purpose or as follow-up:
///   - Sequence diagrams get the plate and the paint order, but not the placement or the gaps: a
///     sequence label can still sit on an arrowhead (then that arrowhead, not the plate, is behind
///     those glyphs), and a later message, note or frame is not broken around an earlier label. An
///     earlier reply's open-V arrowhead is two strokes, not a filled triangle, so it is not cut out
///     (LabelPlateArrowheadGoldenTest checks no plate covers one in any golden or frame).
///   - Only plain-text flowchart edge labels (state diagram transitions included: they are laid
///     out by FlowchartLayout) and sequence message labels (normal and self messages) are plated.
///     Sequence block frames: alt/else/loop/par frame tab and branch-divider labels (plan
///     9d56f599) are outside this plate. So are other layouts' labels (class, ER and the rest)
///     and typeset math labels ({@link MathBox}, whose extent is inside the
///     fragment's own SVG, not layout geometry): they are not plated, placed or protected, and in
///     a play-through frame they still take the page-level accent and dim. Node, actor-head, note
///     and cluster labels sit on their own filled box and are not plated either.
///   - A sequence plate is not cut around an EARLIER label's ink (the flowchart's holes): message
///     rows are stacked apart so no checked diagram needs it, but a plate's {@link #PAD} could
///     reach glyphs painted just before it (a frame tab label, say) without the ink crossing them.
///   - A flowchart node is not broken around an edge label (no checked diagram has one over a
///     label), and the arrowhead and label placement gives up after the decollider's last stack
///     slot (then the label may still overlap an arrowhead or an earlier label; the earlier label
///     is never erased, see {@link #plate(double[], List, List)}). A typeset math label is not an
///     obstacle to later labels. Moving a label off another can put it well away from its edge.
final class LabelPlate {

    private LabelPlate() {}

    /// The plate fill: a near-white indigo tint, distinct from every other sequence/flowchart fill so
    /// a test can key on it.
    static final String FILL = "#f5f7ff";

    /// The label colour on a plate: the luminance pick against {@link #FILL} (black, about 19 to 1).
    static final String TEXT = Colors.contrastFill(FILL);

    /// Padding added on every side of the ink box, so outline anti-aliasing stays on the plate.
    static final double PAD = 2;

    /// How far a stroke reaches in the widest frame state, as a multiple of its static width: the
    /// play-through ACTIVE state doubles every stroke of the active step
    /// ({@code com.sirentide.emit.Emphasis.ACTIVE_WIDTH_MULT}, 2.0; pinned equal by
    /// {@code LabelBackingPlateTest.theActiveReachIsTheFramesStrokeMultiplier}). The flowchart
    /// crossing and gap tests use it, so a line that clears a label's ink box at 1.5px still clears
    /// it when it is drawn at 3px.
    static final double ACTIVE_REACH = 2.0;


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
    /// lines, arrowheads. Earlier filled arrowheads are cut out of the plate. Static stroke widths,
    /// no label cut-outs: the sequence layout's rule.
    static void place(List<Shape> group, int from, int headAt, double[] ink, List<Shape> painted) {
        place(group, from, headAt, ink, painted, 1.0, List.of());
    }

    /// {@link #place(List, int, int, double[], List)} with each own line's reach scaled by `reach`
    /// (the flowchart passes {@link #ACTIVE_REACH}, so a line that would touch the ink only at the
    /// active frame's doubled width also goes under the plate), and with the ink boxes in `holes`
    /// (earlier labels') cut out of the plate (review 1337 F2).
    static void place(List<Shape> group, int from, int headAt, double[] ink, List<Shape> painted,
                      double reach, List<double[]> holes) {
        List<Shape> crossed = new java.util.ArrayList<>();
        List<Shape> rest = new java.util.ArrayList<>();
        for (Shape s : group.subList(from, headAt)) {
            (s instanceof Line l && segmentTouches(l, ink, reach) ? crossed : rest).add(s);
        }
        List<Shape> under = new java.util.ArrayList<>(painted);
        if (group != painted) {
            under.addAll(group.subList(0, from));
        }
        under.addAll(crossed);
        Shape plate = plate(ink, under, holes);
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
        return plate(box, under, List.of());
    }

    /// {@link #plate(double[], List)} with every box in `holes` (an EARLIER label's ink box, review
    /// 1337 F2) cut out too, the same way as an arrowhead, so a later plate is never painted over an
    /// earlier label's glyphs, its whitespace and counters included (review 1341). The hole is the
    /// earlier ink box exactly and WHOLE. The flowchart placement keeps a later label's ink box at
    /// least {@link #PAD} clear of every earlier label's ({@code FlowchartLayout.emitEdgeLabels}),
    /// so normally the hole lies in this plate's padding only. If placement gave up (every stack
    /// slot blocked) and the two ink boxes still overlap, the hole reaches under this label's own
    /// glyphs: the earlier label is still never erased, and inside the overlap this label's glyphs
    /// sit on whatever is under the earlier label (its plate, if it has one).
    static Shape plate(double[] box, List<Shape> under, List<double[]> holes) {
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
        for (double[] h : holes) {
            double x0 = h[0];
            double y0 = h[1];
            double x1 = h[2];
            double y1 = h[3];
            if (!(x0 < rx1 && rx0 < x1 && y0 < ry1 && ry0 < y1)) {
                continue;
            }
            List<double[]> poly = clip(List.of(new double[] {x0, y0}, new double[] {x1, y0},
                new double[] {x1, y1}, new double[] {x0, y1}), rect);
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

    /// The bounding box `[x0, y0, x1, y1]` of every filled arrowhead (a closed three-point M/L
    /// path, as {@link #plate(double[], List)} cuts out) in `shapes`, groups included. The flowchart
    /// label placement keeps an edge label's ink box clear of all of them (review 1337 F1).
    static List<double[]> arrowheadBoxes(List<Shape> shapes) {
        List<double[]> out = new java.util.ArrayList<>();
        for (double[] t : triangles(shapes, new java.util.ArrayList<>())) {
            out.add(new double[] {Math.min(t[0], Math.min(t[2], t[4])), Math.min(t[1], Math.min(t[3], t[5])),
                Math.max(t[0], Math.max(t[2], t[4])), Math.max(t[1], Math.max(t[3], t[5]))});
        }
        return out;
    }

    /// Breaks every {@link Line} of `group` around the label ink boxes in `inks` (review 1337 F2).
    /// `group` is painted AFTER those labels, so an unbroken line would be drawn over their glyphs.
    /// A line whose stroke reaches an ink box at {@link #ACTIVE_REACH} times its width is cut where
    /// it runs through that box grown by {@link #PAD} plus that reach, so even the active frame's
    /// doubled stroke ends clear of the ink. The pieces keep the line's colour, width and position
    /// in the group; a line that reaches no ink box is kept as the same object.
    static void gap(List<Shape> group, List<double[]> inks) {
        if (inks.isEmpty()) {
            return;
        }
        List<Shape> out = new java.util.ArrayList<>(group.size());
        boolean changed = false;
        for (Shape s : group) {
            if (s instanceof Line l) {
                List<Line> pieces = gapLine(l, inks);
                changed |= pieces.size() != 1 || pieces.get(0) != l;
                out.addAll(pieces);
            } else {
                out.add(s);
            }
        }
        if (changed) {
            group.clear();
            group.addAll(out);
        }
    }

    private static List<Line> gapLine(Line l, List<double[]> inks) {
        double reach = Math.max(0, l.strokeWidth()) * ACTIVE_REACH / 2;
        List<double[]> keep = new java.util.ArrayList<>();
        keep.add(new double[] {0, 1});
        boolean cut = false;
        for (double[] ink : inks) {
            if (clipInterval(l, ink, reach) == null) {
                continue;
            }
            double[] c = clipInterval(l, ink, PAD + reach);
            if (c == null) {
                continue;
            }
            cut = true;
            List<double[]> next = new java.util.ArrayList<>();
            for (double[] k : keep) {
                if (c[1] <= k[0] || c[0] >= k[1]) {
                    next.add(k);
                    continue;
                }
                if (c[0] > k[0]) {
                    next.add(new double[] {k[0], c[0]});
                }
                if (c[1] < k[1]) {
                    next.add(new double[] {c[1], k[1]});
                }
            }
            keep = next;
        }
        if (!cut) {
            return List.of(l);
        }
        double dx = l.x2() - l.x1();
        double dy = l.y2() - l.y1();
        double len = Math.hypot(dx, dy);
        List<Line> out = new java.util.ArrayList<>();
        for (double[] k : keep) {
            if ((k[1] - k[0]) * len > 1e-3) {
                out.add(new Line(l.x1() + dx * k[0], l.y1() + dy * k[0], l.x1() + dx * k[1],
                    l.y1() + dy * k[1], l.stroke(), l.strokeWidth()));
            }
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
        return segmentTouches(l, box, 1.0);
    }

    /// The same, with the stroke's half width scaled by `reach` ({@link #ACTIVE_REACH} for the
    /// active frame's doubled stroke).
    private static boolean segmentTouches(Line l, double[] box, double reach) {
        return clipInterval(l, box, Math.max(0, l.strokeWidth()) * reach / 2) != null;
    }

    /// The parameter interval `[t0, t1]` (0 at the line's start, 1 at its end) of the part of the
    /// segment inside `box` grown by `grow` on every side, or `null` when it misses.
    private static double[] clipInterval(Line l, double[] box, double grow) {
        double minX = box[0] - grow;
        double minY = box[1] - grow;
        double maxX = box[2] + grow;
        double maxY = box[3] + grow;
        double dx = l.x2() - l.x1();
        double dy = l.y2() - l.y1();
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {l.x1() - minX, maxX - l.x1(), l.y1() - minY, maxY - l.y1()};
        double t0 = 0;
        double t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return null;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }
                if (t0 > t1) {
                    return null;
                }
            }
        }
        return Double.isFinite(t0) && Double.isFinite(t1) ? new double[] {t0, t1} : null;
    }
}
