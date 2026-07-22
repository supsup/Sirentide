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

    private static double num(Matcher m, int g) {
        return Double.parseDouble(m.group(g));
    }
}
