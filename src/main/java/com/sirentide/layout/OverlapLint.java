package com.sirentide.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/// OPT-IN text-overlap lint (item 4 of Fixpoint's ruling on the cli-math-batch rebuild). Never run by
/// a default render: {@link com.sirentide.api.Sirentide} calls it only when
/// {@link com.sirentide.api.RenderOptions#lintOverlap()} is set, so no existing render can gain a
/// caveat from it.
///
/// WHAT IT CHECKS. Every baked text run ({@link GlyphRun}) gets the bounding box of its glyph outline
/// (all path points, absolute coordinates: on-curve points and quadratic controls, so the box can
/// only be slightly LARGER than the ink, never smaller). Two runs are a finding when their boxes
/// intersect by more than {@link #MIN_OVERLAP_PX} in BOTH directions AND they belong to DIFFERENT
/// owners: the nearest enclosing anchored {@link Group}, or, for a run outside any group (titles,
/// legends, axis ticks, captions), the run itself. Two runs inside ONE group (a node's label and a
/// second line of it, a bar's category and value label) are one element's own composition and are
/// never compared.
///
/// A selective second check compares sequence frame-label glyphs against activation-bar fills,
/// identified by {@link LaidOut}'s layout-only metadata. Both references must actually occur in the
/// scene; metadata cannot create ghost geometry. Repeated metadata identities count only once.
///
/// WHAT IT DOES NOT CHECK, by design. Other text against strokes or fills: an edge label sits ON its
/// edge stroke on purpose, and a label inside its node box is the point of the box. Only the explicit
/// frame-label/activation-bar category extends text-vs-text. Typeset math ({@link MathBox}):
/// its extent lives inside the fragment's own SVG, not in this layout's geometry, so a math
/// fragment is not linted (a raw-text math fallback IS, because it is a GlyphRun).
public final class OverlapLint {

    private OverlapLint() {}

    /// The overlap, in px on each axis, below which two boxes are treated as touching rather than
    /// colliding. Glyph boxes include quadratic control points and side-bearing-free extents, so two
    /// runs set flush on adjacent lines can graze by a fraction of a pixel without any ink touching.
    public static final double MIN_OVERLAP_PX = 1.0;

    /// One overlapping pair: the two owners (`role:id` for an anchored group, `text@x,y` for a lone
    /// run, or `frame-label:index` / `activation:index` for the selective category) and its intersection.
    public record Finding(String a, String b, double x, double y, double w, double h) {
        public String describe() {
            return a + " x " + b + String.format(Locale.ROOT, " (%.1fx%.1f px at %.1f,%.1f)", w, h, x, y);
        }
    }

    private record Box(String owner, int ownerIndex, double x0, double y0, double x1, double y1) {}

    /// Text-run pairs across different owners, then selective frame/activation pairs in metadata
    /// emission order. Pure and deterministic; the lint never changes the scene.
    public static List<Finding> findings(LaidOut laid) {
        List<Box> boxes = new ArrayList<>();
        int[] lone = {0};
        collect(laid.shapes(), null, -1, boxes, lone, new int[] {0});
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            Box p = boxes.get(i);
            for (int j = i + 1; j < boxes.size(); j++) {
                Box q = boxes.get(j);
                if (p.ownerIndex() == q.ownerIndex()) {
                    continue;
                }
                double x0 = Math.max(p.x0(), q.x0());
                double y0 = Math.max(p.y0(), q.y0());
                double w = Math.min(p.x1(), q.x1()) - x0;
                double h = Math.min(p.y1(), q.y1()) - y0;
                if (w > MIN_OVERLAP_PX && h > MIN_OVERLAP_PX) {
                    out.add(new Finding(p.owner(), q.owner(), x0, y0, w, h));
                }
            }
        }
        frameActivationFindings(laid, out);
        return out;
    }

    private static void frameActivationFindings(LaidOut laid, List<Finding> out) {
        if (laid.frameLabels().isEmpty() || laid.activationBars().isEmpty()) {
            return;
        }
        Set<Shape> present = Collections.newSetFromMap(new IdentityHashMap<>());
        collectLeaves(laid.shapes(), present);
        Set<GlyphRun> labelsSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Rect> barsSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<GlyphRun> labels = new ArrayList<>();
        List<Rect> bars = new ArrayList<>();
        for (GlyphRun label : laid.frameLabels()) {
            if (present.contains(label) && labelsSeen.add(label)) {
                labels.add(label);
            }
        }
        for (Rect bar : laid.activationBars()) {
            if (present.contains(bar) && barsSeen.add(bar)
                && Double.isFinite(bar.x()) && Double.isFinite(bar.y())
                && Double.isFinite(bar.width()) && Double.isFinite(bar.height())
                && bar.width() > 0 && bar.height() > 0
                && Double.isFinite(bar.x() + bar.width()) && Double.isFinite(bar.y() + bar.height())) {
                bars.add(bar);
            }
        }
        List<IndexedBar> indexed = new ArrayList<>();
        for (int j = 0; j < bars.size(); j++) {
            Rect bar = bars.get(j);
            double bottom = bar.y() + bar.height();
            if (bottom > bar.y()) {
                indexed.add(new IndexedBar(j, bar, bar.y(), bottom));
            }
        }
        indexed.sort(Comparator.comparingDouble(IndexedBar::middle).thenComparingInt(IndexedBar::index));
        BarIntervals intervals = BarIntervals.build(indexed);
        if (intervals == null) {
            return;
        }
        for (int i = 0; i < labels.size(); i++) {
            double[] b = bounds(labels.get(i).pathD());
            if (b == null) {
                continue;
            }
            int labelIndex = i;
            List<IndexedFinding> found = new ArrayList<>();
            intervals.query(b[1], b[3], candidate -> {
                Rect bar = candidate.bar();
                double x = Math.max(b[0], bar.x());
                double y = Math.max(b[1], bar.y());
                double w = Math.min(b[2], bar.x() + bar.width()) - x;
                double h = Math.min(b[3], bar.y() + bar.height()) - y;
                if (w > MIN_OVERLAP_PX && h > MIN_OVERLAP_PX) {
                    found.add(new IndexedFinding(candidate.index(), new Finding(
                        "frame-label:" + labelIndex, "activation:" + candidate.index(), x, y, w, h)));
                }
            });
            // The index queries geometry order; diagnostics retain the original metadata pair order.
            found.sort(Comparator.comparingInt(IndexedFinding::barIndex));
            for (IndexedFinding finding : found) {
                out.add(finding.finding());
            }
        }
    }

    private record IndexedFinding(int barIndex, Finding finding) {}

    private record IndexedBar(int index, Rect bar, double top, double bottom) {
        double middle() {
            return Math.max(top, Math.min(bottom, top / 2 + bottom / 2));
        }
    }

    /// A centered interval index avoids a frameLabels × emittedActivationSegments scan after a
    /// sequence splits its bars around many frame bands. Median-midpoint partitioning bounds tree
    /// depth; crossing lists are sorted by each endpoint. Queries visit only a logarithmic search
    /// boundary plus actual positive-y-overlap candidates. The exact >1px BOTH-axis predicate still
    /// runs above: this index conservatively prunes disjoint y intervals, never changes the rule.
    private record BarIntervals(double center, List<IndexedBar> byTop, List<IndexedBar> byBottom,
                                BarIntervals left, BarIntervals right) {
        static BarIntervals build(List<IndexedBar> midpointOrder) {
            if (midpointOrder.isEmpty()) {
                return null;
            }
            double center = midpointOrder.get(midpointOrder.size() / 2).middle();
            List<IndexedBar> left = new ArrayList<>();
            List<IndexedBar> right = new ArrayList<>();
            List<IndexedBar> crossing = new ArrayList<>();
            for (IndexedBar bar : midpointOrder) {
                if (bar.bottom() < center) {
                    left.add(bar);
                } else if (bar.top() > center) {
                    right.add(bar);
                } else {
                    crossing.add(bar);
                }
            }
            List<IndexedBar> byTop = new ArrayList<>(crossing);
            byTop.sort(Comparator.comparingDouble(IndexedBar::top).thenComparingInt(IndexedBar::index));
            crossing.sort(Comparator.comparingDouble(IndexedBar::bottom).reversed()
                .thenComparingInt(IndexedBar::index));
            return new BarIntervals(center, byTop, crossing, build(left), build(right));
        }

        void query(double top, double bottom, Consumer<IndexedBar> accept) {
            if (!(bottom > top)) {
                return;
            }
            if (bottom <= center) {
                for (IndexedBar bar : byTop) {
                    if (bar.top() >= bottom) {
                        break;
                    }
                    accept.accept(bar);
                }
                if (left != null) {
                    left.query(top, bottom, accept);
                }
            } else if (top >= center) {
                for (IndexedBar bar : byBottom) {
                    if (bar.bottom() <= top) {
                        break;
                    }
                    accept.accept(bar);
                }
                if (right != null) {
                    right.query(top, bottom, accept);
                }
            } else {
                byTop.forEach(accept);
                if (left != null) {
                    left.query(top, bottom, accept);
                }
                if (right != null) {
                    right.query(top, bottom, accept);
                }
            }
        }
    }

    private static void collectLeaves(List<Shape> shapes, Set<Shape> present) {
        for (Shape shape : shapes) {
            if (shape instanceof Group group) {
                collectLeaves(group.members(), present);
            } else {
                present.add(shape);
            }
        }
    }

    private static void collect(List<Shape> shapes, String owner, int ownerIndex, List<Box> boxes,
                                int[] lone, int[] groups) {
        for (Shape s : shapes) {
            if (s instanceof Group g) {
                int idx = groups[0]++;
                collect(g.members(), g.anchor().role().wire() + ":" + g.anchor().id(), idx, boxes, lone, groups);
            } else if (s instanceof GlyphRun run) {
                double[] b = bounds(run.pathD());
                if (b == null) {
                    continue;
                }
                if (owner != null) {
                    boxes.add(new Box(owner, ownerIndex, b[0], b[1], b[2], b[3]));
                } else {
                    // A run outside every group is its own owner. Negative indices keep it distinct
                    // from every group index and from every other lone run.
                    int idx = -2 - lone[0]++;
                    boxes.add(new Box(String.format(Locale.ROOT, "text@%.0f,%.0f", b[0], b[3]), idx,
                        b[0], b[1], b[2], b[3]));
                }
            }
        }
    }

    /// Bounding box `{minX, minY, maxX, maxY}` of an absolute `M/L/Q/Z` glyph path (the only commands
    /// {@link com.sirentide.font.FontMetrics#textPathD} writes): every number is one half of an x,y
    /// pair. Null for an empty path.
    static double[] bounds(String d) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int n = d.length();
        int i = 0;
        boolean isX = true;
        double x = 0;
        while (i < n) {
            char c = d.charAt(i);
            if (c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                int j = i + 1;
                while (j < n) {
                    char k = d.charAt(j);
                    if ((k >= '0' && k <= '9') || k == '.' || k == 'e' || k == 'E'
                        || ((k == '-' || k == '+') && (d.charAt(j - 1) == 'e' || d.charAt(j - 1) == 'E'))) {
                        j++;
                    } else {
                        break;
                    }
                }
                double v = Double.parseDouble(d.substring(i, j));
                if (isX) {
                    x = v;
                } else {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, v);
                    maxY = Math.max(maxY, v);
                }
                isX = !isX;
                i = j;
            } else {
                i++;
            }
        }
        return minX == Double.POSITIVE_INFINITY ? null : new double[] {minX, minY, maxX, maxY};
    }
}
