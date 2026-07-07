package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The Sugiyama edge-routing pins (M2): VIRTUAL waypoints route long edges AROUND intermediate boxes,
/// and BARYCENTER sweeps untangle within-layer crossings. Both are proven mutant-observably:
///
/// - NO-SEGMENT-THROUGH-BOX: a long forward edge (`A-->B-->C-->D` plus the layer-skipping `A-->D`)
///   must not cross any node box's interior. Pre-fix the `A-->D` diagonal grazes B and C; post-fix
///   the waypoints bend it clear. MUTANT — disable virtual-node splitting (route `A-->D` straight):
///   the diagonal passes through B and C and this test FAILS (observed, see the class report).
/// - CROSSING-COUNT: a 2-layer bipartite tangle (`A-->Y`, `B-->X` with X/Y declared so first-seen
///   order crosses them) must have ZERO edge-segment crossings after the sweeps. First-seen order has
///   1 crossing. MUTANT — skip the barycenter sweeps: the crossing survives and this test FAILS.
///
/// Both parse emitted geometry (rects + line segments) — no layout re-implementation.
class EdgeRoutingTest {

    private record Rect(double x, double y, double w, double h) { }
    private record Seg(double x1, double y1, double x2, double y2) { }

    /// Interior shrink for the through-box test: an edge legitimately ANCHORS on its src/dst box
    /// boundary, so the check is against the box shrunk by EPS — a grazing endpoint on the boundary is
    /// outside the shrunk interior, but a real pass-through (the pre-fix diagonal went >20px deep)
    /// still penetrates it.
    private static final double EPS = 1.0;

    @Test
    void longEdgeRoutesAroundIntermediateBoxesNotThroughThem() {
        // `state`-style chain A→B→C→D plus the long A→D (spans 3 layers). Rendered as a flowchart so
        // the boxes are <rect> (same engine the state diagram rides — the routing is shared).
        String svg = Sirentide.render("flowchart\nA --> B\nB --> C\nC --> D\nA --> D\n");
        List<Rect> boxes = rects(svg);
        List<Seg> segs = segments(svg);
        assertEquals(4, boxes.size(), "A,B,C,D each a box");

        List<String> violations = new ArrayList<>();
        for (int si = 0; si < segs.size(); si++) {
            Seg s = segs.get(si);
            for (int bi = 0; bi < boxes.size(); bi++) {
                Rect b = boxes.get(bi);
                if (segmentEntersRectInterior(s, b, EPS)) {
                    violations.add("segment#" + si + " (" + s.x1 + "," + s.y1 + ")->(" + s.x2
                        + "," + s.y2 + ") passes through box#" + bi
                        + " [" + b.x + "," + b.y + "," + (b.x + b.w) + "," + (b.y + b.h) + "]");
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "no edge segment may cross a node box interior — the virtual waypoints must bend the long "
                + "edge around B and C:\n  " + String.join("\n  ", violations));
    }

    @Test
    void barycenterSweepsUntangleABipartiteCrossing() {
        // X and Y are declared FIRST (lone nodes → first-seen indices 0,1), so the initial layer-1
        // order is [X,Y]; then A→Y and B→X. In first-seen order A(left)→Y(right) and B(right)→X(left)
        // CROSS. The barycenter sweeps must reorder layer 1 to [Y,X] so both edges run straight down.
        String svg = Sirentide.render("flowchart\nX\nY\nA --> Y\nB --> X\n");
        List<Seg> segs = segments(svg);
        assertEquals(2, segs.size(), "two forward edges, one segment each");
        assertEquals(0, crossingCount(segs),
            "the barycenter sweeps must untangle the A→Y / B→X crossing (first-seen order has 1)");
    }

    @Test
    void routedRenderIsByteDeterministic() {
        String dsl = "flowchart\nA --> B\nB --> C\nC --> D\nA --> D\nA --> C\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl), "routed layout is deterministic");
    }

    // ---- geometry helpers (attribute-level, no layout knowledge) ----

    private static List<Rect> rects(String svg) {
        List<Rect> out = new ArrayList<>();
        Matcher m = Pattern.compile(
            "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"")
            .matcher(svg);
        while (m.find()) {
            out.add(new Rect(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    private static List<Seg> segments(String svg) {
        List<Seg> out = new ArrayList<>();
        Matcher m = Pattern.compile(
            "<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" y2=\"([-0-9.]+)\"")
            .matcher(svg);
        while (m.find()) {
            out.add(new Seg(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    /// Does the segment pass through the rect shrunk by `eps` on every side? Liang-Barsky clip of the
    /// parametric segment against the shrunk box; a clipped sub-interval of positive length means the
    /// segment enters the strict interior (an endpoint merely touching the original boundary does not).
    private static boolean segmentEntersRectInterior(Seg s, Rect b, double eps) {
        double xmin = b.x + eps, xmax = b.x + b.w - eps;
        double ymin = b.y + eps, ymax = b.y + b.h - eps;
        if (xmin >= xmax || ymin >= ymax) {
            return false;   // box too small to have an interior after the shrink
        }
        double dx = s.x2 - s.x1;
        double dy = s.y2 - s.y1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {s.x1 - xmin, xmax - s.x1, s.y1 - ymin, ymax - s.y1};
        double t0 = 0.0;
        double t1 = 1.0;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;   // parallel and outside this edge
                }
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) {
                        return false;
                    }
                    if (r > t0) {
                        t0 = r;
                    }
                } else {
                    if (r < t0) {
                        return false;
                    }
                    if (r < t1) {
                        t1 = r;
                    }
                }
            }
        }
        return t1 - t0 > 1e-6;   // a positive-length portion lies inside the shrunk box
    }

    /// Count pairs of segments that PROPERLY cross (intersect at a point interior to both). Shared
    /// endpoints (a fan from one node) are not crossings.
    private static int crossingCount(List<Seg> segs) {
        int c = 0;
        for (int i = 0; i < segs.size(); i++) {
            for (int j = i + 1; j < segs.size(); j++) {
                if (properlyIntersect(segs.get(i), segs.get(j))) {
                    c++;
                }
            }
        }
        return c;
    }

    private static int orient(double ax, double ay, double bx, double by, double cx, double cy) {
        double v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (v > 1e-9) {
            return 1;
        }
        if (v < -1e-9) {
            return -1;
        }
        return 0;
    }

    private static boolean properlyIntersect(Seg a, Seg b) {
        int o1 = orient(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1);
        int o2 = orient(a.x1, a.y1, a.x2, a.y2, b.x2, b.y2);
        int o3 = orient(b.x1, b.y1, b.x2, b.y2, a.x1, a.y1);
        int o4 = orient(b.x1, b.y1, b.x2, b.y2, a.x2, a.y2);
        // strict straddle both ways = a proper interior crossing (0 => touching/collinear, not counted)
        return o1 != 0 && o2 != 0 && o3 != 0 && o4 != 0 && o1 != o2 && o3 != o4;
    }
}
