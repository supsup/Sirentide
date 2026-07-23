package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The DISCONNECTED-SUBGRAPH-OVERLAP containment class (plan ffee1a55 — Fixpoint): two or more
/// subgraph clusters that share NO connecting edge are independent layout COMPONENTS, and the layered
/// engine assigned them overlapping coordinates — the second subgraph's title band + box rendered
/// INSIDE the first subgraph's frame (nothing separated disconnected components). The fix packs each
/// disconnected component into its own band (vertical for LR, side-by-side for TD) with a gap, so the
/// cluster frames are provably disjoint.
///
/// <p>Every assertion parses EMITTED geometry (no layout re-implementation), the {@link
/// FlowchartGeometryTest} / {@link FlowchartRouterNodeCollisionTest} idiom. A cluster FRAME is a
/// stroke-only border (four {@code <line>}s) plus a filled title BAND ({@code <rect fill="#eef2ff"}),
/// all emitted at the TOP LEVEL (before any {@code <g role=…>} group — frames draw under the content),
/// so the frame geometry is exactly the SVG prefix up to the first group.
///
/// <p>Non-vacuous: the repro/TD/three-component cases FAIL pre-fix (the frames overlap — verified by
/// rendering from main's jar: the two repro frames were [24,24,417.48,102] and [39.726,88,430.932,166],
/// overlapping by 14px). The CONNECTED regression case pins that two subgraphs joined by an edge stay a
/// SINGLE component laid out by graph flow (same band, side by side) — it would FAIL if the packing
/// wrongly separated a connected pair.
class FlowchartSubgraphOverlapTest {

    /// The frame separation the packer guarantees between disconnected components' frame boxes
    /// (FlowchartLayout.COMPONENT_GAP). Assertions allow a hair of float slack below it.
    private static final double COMPONENT_GAP = 28.0;

    private record Box(double left, double top, double right, double bottom) {}

    // ---- the plan's minimal repro (LR) + variants ------------------------------------------------

    private static final String REPRO_LR =
        "flowchart LR\n"
            + "  subgraph RO [READ-ONLY rungs 1-3]\n"
            + "    A[replay: claude + snapshot] -->|scope fence: read-only| B[REAL Stafficy: live tree]\n"
            + "  end\n"
            + "  subgraph OP [OPERATE rung 4]\n"
            + "    C[replay: real fixpoint] -->|provenance fence: clone-minted| D[CLONE Stafficy: sealed copy]\n"
            + "  end\n";

    @Test
    void disconnectedSubgraphFramesDoNotOverlap_lrRepro() {
        List<Box> frames = clusterFrames(Sirentide.render(REPRO_LR));
        assertEquals(2, frames.size(), "the two disconnected subgraphs must each draw a frame");
        assertPairwiseDisjointWithGap(frames);
    }

    @Test
    void disconnectedSubgraphFramesDoNotOverlap_tdVariant() {
        String td = "flowchart TD\n"
            + "  subgraph RO [Read Only]\n    A[a] --> B[b]\n  end\n"
            + "  subgraph OP [Operate]\n    C[c] --> D[d]\n  end\n";
        List<Box> frames = clusterFrames(Sirentide.render(td));
        assertEquals(2, frames.size(), "two disconnected subgraphs, two frames (TD)");
        assertPairwiseDisjointWithGap(frames);
    }

    @Test
    void threeDisconnectedComponentsAllPacked() {
        String three = "flowchart LR\n"
            + "  subgraph P [P]\n    A[a]-->B[b]\n  end\n"
            + "  subgraph Q [Q]\n    C[c]-->D[d]\n  end\n"
            + "  subgraph R [R]\n    E[e]-->F[f]\n  end\n";
        List<Box> frames = clusterFrames(Sirentide.render(three));
        assertEquals(3, frames.size(), "three disconnected subgraphs, three frames");
        assertPairwiseDisjointWithGap(frames);
    }

    @Test
    void multiSubgraphSingleComponentStillOnePackedUnit() {
        // A component that CONTAINS two subgraphs (joined internally A->...->E crosses both) plus a
        // second disconnected component: the two-subgraph component packs as ONE unit, clear of the
        // other. Exercises "components with multiple subgraphs".
        String dsl = "flowchart LR\n"
            + "  subgraph OUT [Outer]\n    A[a] --> B[b]\n"
            + "    subgraph IN [Inner]\n      B --> C[c]\n    end\n  end\n"
            + "  subgraph SEP [Separate]\n    X[x] --> Y[y]\n  end\n";
        List<Box> frames = clusterFrames(Sirentide.render(dsl));
        assertEquals(3, frames.size(), "outer + inner (nested) + separate = three frames");
        // The SEPARATE component's frame must clear BOTH frames of the other component. Rather than
        // guess which band is which, assert the whole set has no non-nested overlap: the only allowed
        // overlap is the nested inner-inside-outer pair (containment, not a component collision).
        assertNoUnrelatedFrameOverlap(frames);
    }

    @Test
    void freeNodesDoNotOverlapClusterFrame() {
        // One subgraph plus free nodes that belong to no subgraph (the adjacent case the plan flags):
        // an ISOLATED free node (no edge) AND a free edge pair. None may straddle the cluster frame.
        String dsl = "flowchart LR\n"
            + "  subgraph G [Grp]\n    A[a] --> B[b]\n  end\n"
            + "  F[free isolated]\n"
            + "  P[free one] --> Q[free two]\n";
        String svg = Sirentide.render(dsl);
        List<Box> frames = clusterFrames(svg);
        assertEquals(1, frames.size(), "exactly one subgraph → one frame");
        assertNoNodeStraddlesFrame(svg, frames);
    }

    // ---- BACK-EDGE lanes must translate with a packed component (the follow-up defect) ------------

    /// FlowchartLayout.BACK_LANE_GAP — a component's first lane sits exactly this far past its far edge.
    private static final double BACK_LANE_GAP = 18.0;

    @Test
    void packedComponentBackEdgeRoutesInItsOwnBand_lr() {
        // Charles's verification repro: the cycle is the SECOND of two disconnected components, so the
        // packer translates it down — pre-fix the back-edge lane stayed at the GLOBAL pre-pack content
        // bottom, which lands inside the packed component's own boxes: the back edge collapsed onto the
        // forward edge, label clipped. The lane must sit in the SECOND component's band, below ITS nodes
        // (the single-component control rendering, translated).
        String dsl = "flowchart LR\n"
            + "  subgraph A1 [First component]\n    A[start] --> B[finish]\n  end\n"
            + "  subgraph C1 [Second with cycle]\n    C[work] --> D[review]\n    D -->|redo| C\n  end\n";
        String svg = Sirentide.render(dsl);
        List<Box> frames = clusterFrames(svg);
        assertEquals(2, frames.size(), "two disconnected subgraphs, two frames");
        frames.sort((a, b) -> Double.compare(a.top(), b.top()));
        Box first = frames.get(0);
        Box second = frames.get(1);
        double secondNodesBottom = maxBottomOfNodesIn(svg, second);
        List<double[]> back = edgeLines(svg, "D-C");
        assertFalse(back.isEmpty(), "no <line> geometry parsed for the D-C back edge");
        // 1) the whole back-edge polyline stays in the second component's band: nothing routes back up
        //    into (or above) the first component's frame.
        double backMinY = back.stream().mapToDouble(s -> Math.min(s[1], s[3])).min().orElseThrow();
        assertTrue(backMinY > first.bottom(),
            "the packed component's back edge escaped its band — min y " + backMinY
                + " is not below the FIRST component's frame bottom " + first.bottom()
                + " (back-edge lines=" + dump(back) + ")");
        // 2) the LANE (the lowest horizontal run) sits BELOW the second component's node boxes, in its
        //    own lane slot — not collapsed onto the forward edge at node mid-height.
        double laneY = maxHorizontalRunY(back);
        assertTrue(laneY > secondNodesBottom + BACK_LANE_GAP - 0.5,
            "back-edge lane y=" + laneY + " does not sit a full lane gap below the second component's "
                + "nodes (bottom=" + secondNodesBottom + ") — the lane did not translate with the "
                + "packed component (back-edge lines=" + dump(back) + ")");
        // 3) …and therefore never overlaps the forward edge's y (C->D runs at node mid-height).
        for (double[] fwd : edgeLines(svg, "C-D")) {
            assertTrue(laneY > Math.max(fwd[1], fwd[3]) + BACK_LANE_GAP - 0.5,
                "back-edge lane y=" + laneY + " hugs the forward C-D edge (y=" + fwd[1] + ".." + fwd[3]
                    + ") instead of routing in its own lane below the boxes");
        }
    }

    @Test
    void twoComponentsBothWithCycles_lanesDisjointFromEachOtherAndFrames_lr() {
        // BOTH components carry a back edge: each component's lane must sit in ITS OWN band — below its
        // own nodes, clear of the other component's frame and of the other lane. Pre-fix both lanes
        // stacked below the GLOBAL content bottom, cutting through the packed second component.
        String dsl = "flowchart LR\n"
            + "  subgraph A1 [First with cycle]\n    A[start] --> B[finish]\n    B -->|again| A\n  end\n"
            + "  subgraph C1 [Second with cycle]\n    C[work] --> D[review]\n    D -->|redo| C\n  end\n";
        String svg = Sirentide.render(dsl);
        List<Box> frames = clusterFrames(svg);
        assertEquals(2, frames.size(), "two disconnected subgraphs, two frames");
        frames.sort((a, b) -> Double.compare(a.top(), b.top()));
        Box first = frames.get(0);
        Box second = frames.get(1);
        double lane1 = maxHorizontalRunY(edgeLines(svg, "B-A"));
        double lane2 = maxHorizontalRunY(edgeLines(svg, "D-C"));
        // Each lane below its OWN component's nodes.
        double firstNodesBottom = maxBottomOfNodesIn(svg, first);
        double secondNodesBottom = maxBottomOfNodesIn(svg, second);
        assertTrue(lane1 > firstNodesBottom + BACK_LANE_GAP - 0.5,
            "first component's lane y=" + lane1 + " is not a lane gap below its nodes (bottom="
                + firstNodesBottom + ")");
        assertTrue(lane2 > secondNodesBottom + BACK_LANE_GAP - 0.5,
            "second component's lane y=" + lane2 + " is not a lane gap below its nodes (bottom="
                + secondNodesBottom + ")");
        // Lanes clear of BOTH frames' boxes (a lane inside a frame band is the defect).
        for (double lane : new double[] {lane1, lane2}) {
            for (Box f : frames) {
                assertFalse(lane >= f.top() && lane <= f.bottom(),
                    "back-edge lane y=" + lane + " runs INSIDE cluster frame " + f
                        + " — lanes must stay outside every component frame");
            }
        }
        // The first component's lane stays ABOVE the second component's frame (its own band), and the
        // second's below that frame — so the two lanes are provably disjoint.
        assertTrue(lane1 < second.top(),
            "first component's lane y=" + lane1 + " reaches into the second component's band (frame top="
                + second.top() + ") — it must stay local to its component");
        assertTrue(lane2 > second.bottom(),
            "second component's lane y=" + lane2 + " is not below its own frame (bottom="
                + second.bottom() + ")");
    }

    @Test
    void twoComponentsBothWithCycles_lanesDisjointFromEachOtherAndFrames_td() {
        // The TD transpose: components pack side by side, lanes are VERTICAL runs to the RIGHT of each
        // component. Each lane must sit right of ITS component's nodes, clear of both frames, and the
        // first component's lane must not reach into the second component's band.
        String dsl = "flowchart TD\n"
            + "  subgraph A1 [First with cycle]\n    A[start] --> B[finish]\n    B -->|again| A\n  end\n"
            + "  subgraph C1 [Second with cycle]\n    C[work] --> D[review]\n    D -->|redo| C\n  end\n";
        String svg = Sirentide.render(dsl);
        List<Box> frames = clusterFrames(svg);
        assertEquals(2, frames.size(), "two disconnected subgraphs, two frames (TD)");
        frames.sort((a, b) -> Double.compare(a.left(), b.left()));
        Box first = frames.get(0);
        Box second = frames.get(1);
        double lane1 = maxVerticalRunX(edgeLines(svg, "B-A"));
        double lane2 = maxVerticalRunX(edgeLines(svg, "D-C"));
        double firstNodesRight = maxRightOfNodesIn(svg, first);
        double secondNodesRight = maxRightOfNodesIn(svg, second);
        assertTrue(lane1 > firstNodesRight + BACK_LANE_GAP - 0.5,
            "first component's lane x=" + lane1 + " is not a lane gap right of its nodes (right="
                + firstNodesRight + ")");
        assertTrue(lane2 > secondNodesRight + BACK_LANE_GAP - 0.5,
            "second component's lane x=" + lane2 + " is not a lane gap right of its nodes (right="
                + secondNodesRight + ")");
        for (double lane : new double[] {lane1, lane2}) {
            for (Box f : frames) {
                assertFalse(lane >= f.left() && lane <= f.right(),
                    "back-edge lane x=" + lane + " runs INSIDE cluster frame " + f
                        + " — lanes must stay outside every component frame (TD)");
            }
        }
        assertTrue(lane1 < second.left(),
            "first component's lane x=" + lane1 + " reaches into the second component's band (frame left="
                + second.left() + ") — it must stay local to its component (TD)");
        assertTrue(lane2 > second.right(),
            "second component's lane x=" + lane2 + " is not right of its own frame (right="
                + second.right() + ") (TD)");
    }

    // ---- the CONNECTED regression: two subgraphs WITH a connecting edge stay ONE component --------

    @Test
    void connectedSubgraphsStayFlowLaidNotComponentBanded() {
        // Y --> Z joins the two subgraphs into ONE component: they must lay out by GRAPH FLOW (side by
        // side in the same horizontal band, as before the fix), NOT be pushed into separate vertical
        // bands by the disconnected-component packer. This is the pin that the packer never fires on a
        // connected pair (the byte-for-byte connected golden lives in GoldenSvgTest.flowchart-subgraph).
        String dsl = "flowchart LR\n"
            + "  subgraph A [Alpha]\n    X[x] --> Y[y]\n  end\n"
            + "  subgraph B [Beta]\n    Z[z] --> W[w]\n  end\n"
            + "  Y --> Z\n";
        List<Box> frames = clusterFrames(Sirentide.render(dsl));
        assertEquals(2, frames.size(), "two subgraphs, two frames");
        Box a = frames.get(0);
        Box b = frames.get(1);
        // Same band: their y-ranges OVERLAP (a component-banded layout would make them Y-disjoint).
        boolean yOverlap = a.top() < b.bottom() && b.top() < a.bottom();
        assertTrue(yOverlap,
            "connected subgraphs must share a horizontal band (y-ranges overlap) — they were pushed "
                + "into separate vertical bands: A=" + a + " B=" + b + ". The packer must not fire on a "
                + "single connected component.");
        // And they are separated horizontally by the graph flow (Y->Z spans a rank), so still disjoint.
        assertFalse(overlaps(a, b),
            "connected subgraph frames still must not overlap (flow separates them in X): " + a + " / " + b);
    }

    // ---- assertions (fail LOUD with the actual boxes) --------------------------------------------

    /// Every pair of frames is DISJOINT, and separated by at least COMPONENT_GAP along the axis on which
    /// they are separated (they may abut freely on the other axis — a taller component is fine). Fails
    /// loud with both boxes and the measured gap.
    private static void assertPairwiseDisjointWithGap(List<Box> frames) {
        for (int i = 0; i < frames.size(); i++) {
            for (int j = i + 1; j < frames.size(); j++) {
                Box a = frames.get(i);
                Box b = frames.get(j);
                assertFalse(overlaps(a, b),
                    "disconnected cluster frames overlap — a disconnected subgraph rendered inside "
                        + "another's frame (plan ffee1a55): " + a + "  vs  " + b);
                // The gap on the axis where they are actually separated (>0). One of the two must hold.
                double gapX = Math.max(b.left() - a.right(), a.left() - b.right());
                double gapY = Math.max(b.top() - a.bottom(), a.top() - b.bottom());
                double sepGap = Math.max(gapX, gapY);
                assertTrue(sepGap >= COMPONENT_GAP - 0.5,
                    "disconnected cluster frames must be separated by the full component gap ("
                        + COMPONENT_GAP + "px) but were " + sepGap + "px apart: " + a + "  vs  " + b);
            }
        }
    }

    /// No frame overlaps another EXCEPT a nested containment pair (one frame fully inside the other —
    /// legitimate for a subgraph-in-a-subgraph). Any partial overlap between frames of DIFFERENT
    /// components is the defect.
    private static void assertNoUnrelatedFrameOverlap(List<Box> frames) {
        for (int i = 0; i < frames.size(); i++) {
            for (int j = i + 1; j < frames.size(); j++) {
                Box a = frames.get(i);
                Box b = frames.get(j);
                if (!overlaps(a, b)) {
                    continue;
                }
                assertTrue(contains(a, b) || contains(b, a),
                    "cluster frames overlap without a nesting containment — a disconnected component's "
                        + "frame collided with another's (plan ffee1a55): " + a + "  vs  " + b);
            }
        }
    }

    /// No node box PARTIALLY overlaps a cluster frame: every node is either fully INSIDE a frame (a
    /// member) or fully DISJOINT from every frame (a free node in its own band). A free node straddling
    /// a frame border is the defect.
    private static void assertNoNodeStraddlesFrame(String svg, List<Box> frames) {
        List<Box> nodes = nodeBoxes(svg);
        assertFalse(nodes.isEmpty(), "no node boxes parsed — the fixture changed?");
        for (Box node : nodes) {
            for (Box frame : frames) {
                boolean inside = contains(frame, node);
                boolean disjoint = !overlaps(frame, node);
                assertTrue(inside || disjoint,
                    "node box " + node + " partially overlaps cluster frame " + frame
                        + " — a free node must not straddle a subgraph frame (plan ffee1a55)");
            }
        }
    }

    private static boolean overlaps(Box a, Box b) {
        return a.left() < b.right() && b.left() < a.right()
            && a.top() < b.bottom() && b.top() < a.bottom();
    }

    /// True when `outer` fully contains `inner` (with a hair of slack for the shared-border nested case).
    private static boolean contains(Box outer, Box inner) {
        double t = 0.5;
        return inner.left() >= outer.left() - t && inner.right() <= outer.right() + t
            && inner.top() >= outer.top() - t && inner.bottom() <= outer.bottom() + t;
    }

    // ---- geometry parsing ------------------------------------------------------------------------

    // A cluster title BAND: a filled rect with the pale-indigo band fill (FlowchartLayout.CLUSTER_BAND_FILL).
    // Its x/y/width fix the frame's left, top and right; the frame is CLUSTER_BAND_H (14) tall at the top.
    private static final Pattern BAND = Pattern.compile(
        "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"14\" fill=\"#eef2ff\"");
    private static final Pattern LINE = Pattern.compile(
        "<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" y2=\"([-0-9.]+)\" stroke=\"#94a3b8\"");
    private static final Pattern NODE_GROUP = Pattern.compile(
        "<g data-sirentide-role=\"node\"[^>]*>(.*?)</g>", Pattern.DOTALL);
    private static final Pattern RECT = Pattern.compile(
        "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"");

    /// Every subgraph cluster frame, reconstructed from the TOP-LEVEL geometry (the SVG prefix before
    /// the first `<g …>` group — cluster frames draw under the content, so they are all emitted first).
    /// The band rect gives left/top/right; the frame's BOTTOM is the left border line (vertical, x == left)
    /// whose top == the band top — pairing by top disambiguates frames that share a left edge (stacked
    /// LR components all start at the same x).
    private static List<Box> clusterFrames(String svg) {
        int firstGroup = svg.indexOf("<g ");
        String top = firstGroup < 0 ? svg : svg.substring(0, firstGroup);
        List<double[]> lines = new ArrayList<>();
        Matcher l = LINE.matcher(top);
        while (l.find()) {
            lines.add(new double[] {num(l, 1), num(l, 2), num(l, 3), num(l, 4)});
        }
        List<Box> out = new ArrayList<>();
        Matcher m = BAND.matcher(top);
        while (m.find()) {
            double left = num(m, 1);
            double bandTop = num(m, 2);
            double right = left + num(m, 3);
            double bottom = Double.NaN;
            for (double[] s : lines) {
                boolean vertical = Math.abs(s[0] - s[2]) < 0.01;
                if (!vertical || Math.abs(s[0] - left) > 0.01) {
                    continue;   // not the left border line
                }
                double lo = Math.min(s[1], s[3]);
                double hi = Math.max(s[1], s[3]);
                if (Math.abs(lo - bandTop) < 0.5) {   // this frame's left border (top == band top)
                    bottom = hi;
                    break;
                }
            }
            assertFalse(Double.isNaN(bottom),
                "could not find the bottom border for the frame with band top=" + bandTop);
            out.add(new Box(left, bandTop, right, bottom));
        }
        return out;
    }

    /// The node boxes, parsed per `role="node"` group so a node's own decoration lines are never mixed
    /// in. These fixtures use only rect nodes, so the rect's four attrs recover every box.
    private static List<Box> nodeBoxes(String svg) {
        List<Box> out = new ArrayList<>();
        Matcher g = NODE_GROUP.matcher(svg);
        while (g.find()) {
            Matcher r = RECT.matcher(g.group(1));
            if (r.find()) {
                double x = num(r, 1);
                double y = num(r, 2);
                out.add(new Box(x, y, x + num(r, 3), y + num(r, 4)));
            }
        }
        return out;
    }

    /// The `<line>` segments `[x1,y1,x2,y2]` of ONE edge's `<g role="edge" id=…>` group (the exact
    /// emitted polyline of that edge — connectors, lane runs; the arrowhead is a `<path>`, excluded).
    private static List<double[]> edgeLines(String svg, String edgeId) {
        Pattern grp = Pattern.compile(
            "<g data-sirentide-role=\"edge\" data-sirentide-id=\"" + Pattern.quote(edgeId)
                + "\"[^>]*>(.*?)</g>", Pattern.DOTALL);
        List<double[]> out = new ArrayList<>();
        Matcher g = grp.matcher(svg);
        if (g.find()) {
            Matcher l = LINE.matcher(g.group(1));
            while (l.find()) {
                out.add(new double[] {num(l, 1), num(l, 2), num(l, 3), num(l, 4)});
            }
        }
        return out;
    }

    /// The LANE of an LR back edge: the y of its lowest HORIZONTAL run (the along-the-lane segment —
    /// always the polyline's max-y horizontal line). Fails loud when the edge has no horizontal run.
    private static double maxHorizontalRunY(List<double[]> lines) {
        double y = Double.NEGATIVE_INFINITY;
        for (double[] s : lines) {
            if (Math.abs(s[1] - s[3]) < 0.01) {
                y = Math.max(y, s[1]);
            }
        }
        assertTrue(y > Double.NEGATIVE_INFINITY,
            "no horizontal lane run found in back-edge lines " + dump(lines));
        return y;
    }

    /// The LANE of a TD back edge: the x of its rightmost VERTICAL run (transpose of
    /// {@link #maxHorizontalRunY}).
    private static double maxVerticalRunX(List<double[]> lines) {
        double x = Double.NEGATIVE_INFINITY;
        for (double[] s : lines) {
            if (Math.abs(s[0] - s[2]) < 0.01) {
                x = Math.max(x, s[0]);
            }
        }
        assertTrue(x > Double.NEGATIVE_INFINITY,
            "no vertical lane run found in back-edge lines " + dump(lines));
        return x;
    }

    /// Max BOTTOM of the node boxes inside `frame` — the "below its nodes" datum for an LR lane.
    private static double maxBottomOfNodesIn(String svg, Box frame) {
        double bottom = Double.NEGATIVE_INFINITY;
        for (Box nodeBox : nodeBoxes(svg)) {
            if (contains(frame, nodeBox)) {
                bottom = Math.max(bottom, nodeBox.bottom());
            }
        }
        assertTrue(bottom > Double.NEGATIVE_INFINITY, "no node boxes found inside frame " + frame);
        return bottom;
    }

    /// Max RIGHT of the node boxes inside `frame` — the "right of its nodes" datum for a TD lane.
    private static double maxRightOfNodesIn(String svg, Box frame) {
        double right = Double.NEGATIVE_INFINITY;
        for (Box nodeBox : nodeBoxes(svg)) {
            if (contains(frame, nodeBox)) {
                right = Math.max(right, nodeBox.right());
            }
        }
        assertTrue(right > Double.NEGATIVE_INFINITY, "no node boxes found inside frame " + frame);
        return right;
    }

    private static String dump(List<double[]> lines) {
        StringBuilder sb = new StringBuilder("[");
        for (double[] s : lines) {
            sb.append('(').append(s[0]).append(',').append(s[1]).append(")-(")
                .append(s[2]).append(',').append(s[3]).append(") ");
        }
        return sb.append(']').toString();
    }

    private static double num(Matcher m, int g) {
        return Double.parseDouble(m.group(g));
    }
}
