package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.math.LatteXMathFragmentRenderer;
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
/// group (rect + diamond — the two shapes these fixtures use; extend the parser before a fixture
/// uses an arc-based shape); edge segments are the {@code <line>}s inside
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

    // ------------------------------------------------------------------
    // LR discriminators (review sir292, Confluence): the fix is two-sided — clearRailY (TD) and
    // clearRailX (LR) — but every fixture above is TD, so the LR half shipped with zero coverage: a
    // transposition typo or a future "simplification" of clearRailX would return the defect with the
    // whole suite green. These are the reviewer's probe fixtures, committed verbatim as permanent
    // discriminators (the could-this-test-stop-testing class). Both proven RED with FlowchartLayout
    // reverted to base, GREEN at the fix — by the reviewer at my tip, re-proven by me pre-commit.
    // ------------------------------------------------------------------

    private static final String REPRO_A_LR =
        "flowchart LR\n  Root[Root] --> G{decide?}\n"
            + "  G -->|left| L[Left Branch]\n  L --> Root\n"
            + "  G -->|right| R[Right Branch]\n  R --> Root\n";

    private static final String REPRO_A_LR_MIRROR =
        "flowchart LR\n  Root[Root] --> G{decide?}\n"
            + "  G -->|right| R[Right Branch]\n  R --> Root\n"
            + "  G -->|left| L[Left Branch]\n  L --> Root\n";

    @Test
    void loopBackRailClearsSiblingFill_lr() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(REPRO_A_LR));
    }

    @Test
    void loopBackRailClearsSiblingFill_lrMirror() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(REPRO_A_LR_MIRROR));
    }

    // ------------------------------------------------------------------
    // NODE-SIZE-GROWTH discriminators (review sir297, Lattice). The prior fix DETOURED with a fixed
    // 240px offset scan; once a node grows past that (a many-row matrix in TD, a long formula in LR —
    // both legal DSL well within DslParser.MAX_LABEL_LEN=512), the straight rail crosses the sibling's
    // FAR edge, the scan can't reach it, and the production fallback re-emitted the KNOWN-COLLIDING
    // straight rail. These four fixtures reproduce the reviewer's exact TD (60-row matrix) and LR
    // (long formula) shapes through the REAL LatteX renderer (the only way to grow a node box), plus
    // the declaration-order mirror of each — so the obstacle-extent derivation + canvas-grow stays
    // covered against a future "simplification" that reintroduces a bounded scan. Proven RED at the
    // pre-fix HEAD (each produced a rail slicing the tall/wide sibling's interior), GREEN at the fix.
    // ------------------------------------------------------------------

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    /// A single-column `matrix` of `rows` rows — grows the node box TALL (each row ≈ one line height),
    /// past the old 240px detour cap by ~40 rows. 60 rows stays well under MAX_LABEL_LEN=512.
    private static String tallMatrix(int rows) {
        StringBuilder sb = new StringBuilder("\\begin{matrix} ");
        for (int i = 0; i < rows; i++) {
            sb.append("a");
            if (i < rows - 1) {
                sb.append(" \\\\ ");
            }
        }
        return sb.append(" \\end{matrix}").toString();
    }

    /// A `terms`-term `a + a + …` formula — grows the node box WIDE. 70 terms ≈ 277 chars, under 512.
    private static String wideFormula(int terms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms; i++) {
            sb.append("a");
            if (i < terms - 1) {
                sb.append(" + ");
            }
        }
        return sb.toString();
    }

    // TWO tall-matrix branches off one decision, BOTH looping back to Root: whichever barycenter puts
    // on the left has its out-right rail run past the tall sibling on its right, whose far edge is far
    // beyond the old 240px cap — a crossing guaranteed in BOTH declaration orders (mirror below).
    private static String tdTallDsl(boolean leftFirst) {
        String m = "[$" + tallMatrix(60) + "$]";
        String lft = "  G -->|left| L" + m + "\n  L --> Root\n";
        String rgt = "  G -->|right| R" + m + "\n  R --> Root\n";
        return "flowchart TD\n  Root[Root] --> G{decide?}\n" + (leftFirst ? lft + rgt : rgt + lft);
    }

    private static String lrWideDsl(boolean leftFirst) {
        String f = "[$" + wideFormula(70) + "$]";
        String lft = "  G -->|left| L" + f + "\n  L --> Root\n";
        String rgt = "  G -->|right| R" + f + "\n  R --> Root\n";
        return "flowchart LR\n  Root[Root] --> G{decide?}\n" + (leftFirst ? lft + rgt : rgt + lft);
    }

    @Test
    void loopBackRailClearsTallNodeFill_td() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(tdTallDsl(true), REAL));
    }

    @Test
    void loopBackRailClearsTallNodeFill_tdMirror() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(tdTallDsl(false), REAL));
    }

    @Test
    void loopBackRailClearsWideNodeFill_lr() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(lrWideDsl(true), REAL));
    }

    @Test
    void loopBackRailClearsWideNodeFill_lrMirror() {
        assertNoEdgeSegmentCrossesForeignNodeFill(Sirentide.render(lrWideDsl(false), REAL));
    }

    // ------------------------------------------------------------------
    // CANVAS-GROWTH margin discriminators (review sir311/313, Lattice): the growth pre-passes
    // (canvasH = max(canvasH, railMaxY + MARGIN) in TD; the canvasW mirror in LR) shipped with ZERO
    // coverage — deleting BOTH left all 10 collision tests green, because the collision oracle checks
    // fills, not containment: the detoured rail stayed clear of every box but sat 17px from the canvas
    // edge instead of the layout's 24px MARGIN.
    //
    // Empirical sweep with both growth assignments deleted, ALL public fixtures in this file: exactly
    // ONE triggers — REPRO_A_LR's beyond-rightmost detour candidate drops the right margin to 17.0px
    // (the reviewer's exact number). Both LR declaration orders are pinned here and are RED under the
    // canvasW-growth deletion. The TD HEIGHT-growth branch IS reachable — I first claimed it was not
    // (tall fixtures keep an inter-rank band above the obstacle that wins the upward tie-break), and
    // Lattice (sir317) refuted that with the fixture below: TWO self-loops in the sole rank. Both are
    // back edges in one rank, the above-obstacle candidate is rejected at the top margin, so routing
    // selects the below-content candidate and requires canvasH growth. Deleting only the TD canvasH
    // assignment drops the bottom margin to 17px (measured) — these two TD tests go RED under it.
    // ------------------------------------------------------------------

    /// FlowchartLayout.MARGIN — the containment contract every emitted element honors.
    private static final double LAYOUT_MARGIN = 24;

    // Lattice's sir317 fixture: two self-loops in the sole rank force the below-content detour
    // candidate, so the TD canvasH growth pre-pass is load-bearing here (unlike the tall-matrix
    // fixtures, whose above-obstacle band keeps them growth-free on the height axis).
    private static final String TD_SELFLOOPS = "flowchart TD\n  A[Alpha] --> A\n  B[Beta] --> B\n";
    private static final String TD_SELFLOOPS_MIRROR = "flowchart TD\n  B[Beta] --> B\n  A[Alpha] --> A\n";

    @Test
    void detourGrowthKeepsFullRightMargin_lr() {
        assertEdgeGeometryKeepsMargin(Sirentide.render(REPRO_A_LR), "width");
    }

    @Test
    void detourGrowthKeepsFullRightMargin_lrMirror() {
        assertEdgeGeometryKeepsMargin(Sirentide.render(REPRO_A_LR_MIRROR), "width");
    }

    @Test
    void detourGrowthKeepsFullBottomMargin_td() {
        assertEdgeGeometryKeepsMargin(Sirentide.render(TD_SELFLOOPS), "height");
    }

    @Test
    void detourGrowthKeepsFullBottomMargin_tdMirror() {
        assertEdgeGeometryKeepsMargin(Sirentide.render(TD_SELFLOOPS_MIRROR), "height");
    }

    /// Every edge segment endpoint keeps the layout's full MARGIN to the far canvas edge on the given
    /// axis — the containment claim canvas growth exists to preserve.
    private static void assertEdgeGeometryKeepsMargin(String svg, String axis) {
        double canvas = canvasDim(svg, axis);
        double max = 0;
        for (Seg s : edgeSegments(svg)) {
            max = Math.max(max, axis.equals("width")
                ? Math.max(s.x1(), s.x2())
                : Math.max(s.y1(), s.y2()));
        }
        assertTrue(canvas - max >= LAYOUT_MARGIN - 0.5,
            "outermost edge geometry (" + max + ") must keep the full " + LAYOUT_MARGIN
                + "px margin to the canvas " + axis + " (" + canvas
                + ") — growth must expand the canvas, never squeeze the rail (sir311)");
    }

    private static double canvasDim(String svg, String attr) {
        Matcher m = Pattern.compile("<svg[^>]*\\s" + attr + "=\"([-0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg root must carry a numeric " + attr + " attribute");
        return Double.parseDouble(m.group(1));
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
