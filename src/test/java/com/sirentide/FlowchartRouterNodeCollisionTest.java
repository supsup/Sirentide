package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The EDGES-VS-NODES containment class (plan e7144b77 #1 — Fixpoint): a flowchart edge polyline
/// must never route through the INTERIOR of a node box that is not that edge's own source/target.
/// The sibling twin of {@link GeometryEscapeTest} (labels-vs-canvas) and of the grid layouts'
/// {@link com.sirentide.layout.GridEdgeCrossingTest} (straight-edge-vs-third-box), but for the
/// back-edge RAIL: a loop-back edge leaves a node at its right-middle and runs out to the far-right
/// lane, and a straight rail at that mid-height slices clean through a SAME-RANK sibling's fill (the
/// sibling sits between the node and the lane). The node fill then masks the middle of the rail, so
/// the diagram visually asserts a FALSE data path.
///
/// <p>Every assertion parses emitted geometry, following the {@link FlowchartGeometryTest} idiom (no
/// layout re-implementation). Node boxes are the bounding box of each {@code data-sirentide-role="node"}
/// group (shape-agnostic — rect, diamond, stadium, …); edge segments are the {@code <line>}s inside
/// each {@code data-sirentide-role="edge"} group, so a node's OWN decoration lines (a subroutine's
/// inner bars, a cylinder rim) are never mistaken for an edge crossing.
///
/// <p>Non-vacuous / mutation-surviving: PRE-FIX both {@link #loopBackRailClearsSiblingFill} and its
/// {@link #loopBackRailClearsSiblingFill_mirror} FAIL — verified by reverting the FlowchartLayout
/// rail-detour (the straight out-right rail crosses the sibling diamond in BOTH declaration orders).
/// The {@link #normalEdgesStillAttachToTheirOwnBoxes} control fails if the detour is made so broad it
/// forbids legitimate border attachment.
class FlowchartRouterNodeCollisionTest {

    /// Interior inset — a rail that merely TOUCHES a box border (a legitimate attach point) is not a
    /// crossing; only passing through the shrunk interior is. Matches the layout's RAIL_OBSTACLE_INSET.
    private static final double INSET = 1.0;

    private record Box(double x1, double y1, double x2, double y2) {}

    private record Seg(double x1, double y1, double x2, double y2) {}

    // Two same-rank siblings (Left/Right) that BOTH loop back to the shared ancestor Root: whichever
    // sibling barycenter places on the left has its out-right rail cross the sibling on its right, so
    // a crossing is guaranteed in BOTH declaration orders (unlike a single loop-back, whose sibling
    // can land on the right and exit cleanly). Distinct-target variant is exercised separately below.
    private static final String REPRO_A =
        "flowchart TD\n  Root[Root] --> G{decide?}\n"
            + "  G -->|left| L[Left Branch]\n  L --> Root\n"
            + "  G -->|right| R[Right Branch]\n  R --> Root\n";

    // The MIRROR: the two branch declarations swapped. Pins that the fix is order-independent — the
    // left/right node placement flips, but neither rail may cross the other sibling's fill.
    private static final String REPRO_A_MIRROR =
        "flowchart TD\n  Root[Root] --> G{decide?}\n"
            + "  G -->|right| R[Right Branch]\n  R --> Root\n"
            + "  G -->|left| L[Left Branch]\n  L --> Root\n";

    // The plan's literal shape: two siblings off one decision, each with a loop-back/return edge to a
    // lower-rank target (S4->SW routed through the G5 diamond's centre pre-fix).
    private static final String REPRO_PLAN_SHAPE =
        "flowchart TD\n  SW[Start Work] --> G4{decide?}\n  SK[Start K] --> G4\n"
            + "  G4 -->|no| S4[Step 4]\n  S4 --> SW\n"
            + "  G4 -->|yes| G5{again?}\n  G5 -->|not yet| SK\n";

    @Test
    void loopBackRailClearsSiblingFill() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(REPRO_A));
    }

    @Test
    void loopBackRailClearsSiblingFill_mirror() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(REPRO_A_MIRROR));
    }

    @Test
    void loopBackRailClearsSiblingFill_planShape() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(REPRO_PLAN_SHAPE));
    }

    @Test
    void normalEdgesStillAttachToTheirOwnBoxes() {
        // A plain forward chain: the oracle must NOT fire (no false positive), AND the edges must
        // actually reach their boxes — every edge line's endpoint sits on a node-box border. This is
        // the guard that the detour is not so broad it pushes edges off their own attach points.
        String svg = Sirentide.render("flowchart TD\n  A[Alpha] --> B[Beta]\n  B --> C[Gamma]\n");
        assertNoEdgeSegmentCrossesForeignNodeFill(svg);

        List<Box> boxes = nodeBoxes(svg);
        List<Seg> segs = edgeSegments(svg);
        assertFalse(segs.isEmpty(), "the forward chain must emit edge lines");
        for (Seg s : segs) {
            boolean touches = touchesAnyBorder(s.x1(), s.y1(), boxes) || touchesAnyBorder(s.x2(), s.y2(), boxes);
            assertTrue(touches,
                "edge segment (" + s.x1() + "," + s.y1() + ")->(" + s.x2() + "," + s.y2()
                    + ") must attach to a node box border — the detour must not orphan edges");
        }
    }

    // ---- the oracle -------------------------------------------------------------------------------

    /// Assert no {@code role="edge"} line segment passes through the INTERIOR (shrunk by {@link #INSET})
    /// of ANY node box. Legitimate attach points sit ON a border and arrowheads extend OUTWARD, so a
    /// correct drawing never enters a node interior with an edge segment; the pre-fix rail did.
    private static void assertNoEdgeSegmentCrossesForeignNodeFill(String svg) {
        List<Box> boxes = nodeBoxes(svg);
        assertFalse(boxes.isEmpty(), "no node boxes parsed — the fixture changed?");
        List<Seg> segs = edgeSegments(svg);
        assertFalse(segs.isEmpty(), "no edge segments parsed — the fixture changed?");
        for (Seg s : segs) {
            for (Box b : boxes) {
                assertFalse(
                    segIntersectsRect(s.x1(), s.y1(), s.x2(), s.y2(),
                        b.x1() + INSET, b.y1() + INSET, b.x2() - INSET, b.y2() - INSET),
                    "edge segment (" + s.x1() + "," + s.y1() + ")->(" + s.x2() + "," + s.y2()
                        + ") routes through node fill [" + b.x1() + "," + b.y1() + ".." + b.x2() + ","
                        + b.y2() + "] — a back-edge rail slicing a sibling box (plan e7144b77 #1)");
            }
        }
    }

    private static boolean touchesAnyBorder(double x, double y, List<Box> boxes) {
        double tol = 0.75;
        for (Box b : boxes) {
            boolean inX = x >= b.x1() - tol && x <= b.x2() + tol;
            boolean inY = y >= b.y1() - tol && y <= b.y2() + tol;
            boolean onVert = (Math.abs(x - b.x1()) <= tol || Math.abs(x - b.x2()) <= tol) && inY;
            boolean onHoriz = (Math.abs(y - b.y1()) <= tol || Math.abs(y - b.y2()) <= tol) && inX;
            if (onVert || onHoriz) {
                return true;
            }
        }
        return false;
    }

    // ---- geometry parsing (group-scoped, shape-agnostic) ------------------------------------------

    private static final Pattern GROUP = Pattern.compile(
        "<g data-sirentide-role=\"(node|edge)\"[^>]*>(.*?)</g>", Pattern.DOTALL);
    private static final Pattern LINE = Pattern.compile(
        "<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" y2=\"([-0-9.]+)\"");
    // A node's PRIMARY box shape: a rect (x/y/width/height) OR a diamond silhouette path
    // (M cx top L right cy L cx bottom L left cy Z). The fixtures here use only rects + diamonds, so
    // these two exactly recover every node box; the diamond bbox is [left, top, right, bottom].
    private static final Pattern RECT = Pattern.compile(
        "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"");
    private static final Pattern DIAMOND = Pattern.compile(
        "<path d=\"M ([-0-9.]+) ([-0-9.]+) L ([-0-9.]+) ([-0-9.]+) L ([-0-9.]+) ([-0-9.]+) "
            + "L ([-0-9.]+) ([-0-9.]+) Z\"");

    /// The node boxes, parsed per {@code role="node"} group so a node's own decoration is never mixed
    /// into the edge scan. The primary box shape is a rect (its four attrs) or a diamond silhouette
    /// (bbox = [left, top, right, bottom]); the fixtures use only those two shapes.
    private static List<Box> nodeBoxes(String svg) {
        List<Box> out = new ArrayList<>();
        Matcher g = GROUP.matcher(svg);
        while (g.find()) {
            if (!"node".equals(g.group(1))) {
                continue;
            }
            String body = g.group(2);
            Matcher r = RECT.matcher(body);
            if (r.find()) {
                double x = num(r, 1);
                double y = num(r, 2);
                out.add(new Box(x, y, x + num(r, 3), y + num(r, 4)));
                continue;
            }
            Matcher d = DIAMOND.matcher(body);
            if (d.find()) {
                // groups: 1=cx 2=top 3=right 4=cy 5=cx 6=bottom 7=left 8=cy → bbox left/top/right/bottom
                out.add(new Box(num(d, 7), num(d, 2), num(d, 3), num(d, 6)));
            }
        }
        return out;
    }

    private static double num(Matcher m, int g) {
        return Double.parseDouble(m.group(g));
    }

    /// Every {@code <line>} inside a {@code role="edge"} group — the rails/segments an edge routes
    /// along. Arrowhead triangles ({@code <path>}) sit at borders and are excluded; the defect is a
    /// straight rail, so lines are the load-bearing geometry.
    private static List<Seg> edgeSegments(String svg) {
        List<Seg> out = new ArrayList<>();
        Matcher g = GROUP.matcher(svg);
        while (g.find()) {
            if (!"edge".equals(g.group(1))) {
                continue;
            }
            Matcher l = LINE.matcher(g.group(2));
            while (l.find()) {
                out.add(new Seg(Double.parseDouble(l.group(1)), Double.parseDouble(l.group(2)),
                    Double.parseDouble(l.group(3)), Double.parseDouble(l.group(4))));
            }
        }
        return out;
    }

    /// Liang-Barsky segment-vs-axis-aligned-rect overlap (inclusive) — the same test the layout uses
    /// to detect the crossing, re-derived here so the oracle is independent of production code.
    private static boolean segIntersectsRect(double x1, double y1, double x2, double y2,
                                             double xmin, double ymin, double xmax, double ymax) {
        if (xmax <= xmin || ymax <= ymin) {
            return false;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - xmin, xmax - x1, y1 - ymin, ymax - y1};
        double u1 = 0;
        double u2 = 1;
        for (int k = 0; k < 4; k++) {
            if (p[k] == 0) {
                if (q[k] < 0) {
                    return false;
                }
            } else {
                double t = q[k] / p[k];
                if (p[k] < 0) {
                    u1 = Math.max(u1, t);
                } else {
                    u2 = Math.min(u2, t);
                }
            }
        }
        return u1 <= u2;
    }
}
