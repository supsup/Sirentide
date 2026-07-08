package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Mindmap;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The mindmap GEOMETRY pins (plan sirentide-mindmap). An LR layered tree: depth maps to an x-column
/// (boxes left-aligned per depth); leaves take evenly-spaced y-slots in declaration order; each
/// internal node's y CENTRES on its children (midpoint of first & last child); an elbow connector
/// wires each parent's right edge to its child's left edge. The canvas contains every box.
///
/// Lives in {@code com.sirentide.layout} to reach {@link MindmapLayout#layout(Mindmap)} directly — the
/// tests assert on the laid-out {@link Rect}/{@link Line} geometry, never re-implement layout.
class MindmapLayoutTest {

    /// The example tree: Root → {A → {A1, A2}, B → {B1}}. Three leaves (A1, A2, B1), three depths.
    private static final String DSL =
        "mindmap\n  root Root\n    A\n      A1\n      A2\n    B\n      B1\n";

    private static List<Shape> shapes(String dsl) {
        Mindmap m = (Mindmap) DslParser.parse(dsl);
        return Group.flatten(MindmapLayout.layout(m).shapes());
    }

    private static List<Rect> rects(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Rect).map(s -> (Rect) s).toList();
    }

    private static List<Line> lines(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Line).map(s -> (Line) s).toList();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /// The node boxes grouped by their left x (= depth column), each group's boxes sorted top→down.
    private static List<List<Rect>> columns(List<Rect> boxes) {
        List<Double> xs = new ArrayList<>();
        for (Rect r : boxes) {
            if (xs.stream().noneMatch(x -> near(x, r.x()))) {
                xs.add(r.x());
            }
        }
        xs.sort(Double::compare);
        List<List<Rect>> cols = new ArrayList<>();
        for (double x : xs) {
            List<Rect> col = new ArrayList<>(boxes.stream().filter(r -> near(r.x(), x)).toList());
            col.sort((a, b) -> Double.compare(a.y(), b.y()));
            cols.add(col);
        }
        return cols;
    }

    private static double cy(Rect r) {
        return r.y() + r.height() / 2;
    }

    @Test
    void depthMapsToAnXColumnWithLeftAlignedBoxes() {
        List<List<Rect>> cols = columns(rects(shapes(DSL)));
        assertEquals(3, cols.size(), "three depths → three x-columns: " + cols.size());
        assertEquals(1, cols.get(0).size(), "depth 0 is just the root");
        assertEquals(2, cols.get(1).size(), "depth 1 is A + B");
        assertEquals(3, cols.get(2).size(), "depth 2 is A1 + A2 + B1");
        // Columns march strictly left→right; every box in a column shares its left x.
        assertTrue(cols.get(0).get(0).x() < cols.get(1).get(0).x(), "depth 0 left of depth 1");
        assertTrue(cols.get(1).get(0).x() < cols.get(2).get(0).x(), "depth 1 left of depth 2");
        for (List<Rect> col : cols) {
            double x0 = col.get(0).x();
            assertTrue(col.stream().allMatch(r -> near(r.x(), x0)), "a column shares one left x");
        }
    }

    /// DELETE-MUTANT SENTINEL #1 (receipt #6): a parent's y CENTRES on its children — the midpoint of
    /// its first and last child's y. Break the centering in {@link MindmapLayout#assignY} (e.g. pin a
    /// parent to a constant y, or to its first child only) and this fails BY NAME.
    @Test
    void aParentYCentersOnItsChildren() {
        List<List<Rect>> cols = columns(rects(shapes(DSL)));
        Rect root = cols.get(0).get(0);
        Rect a = cols.get(1).get(0);            // depth-1, topmost → A
        Rect b = cols.get(1).get(1);            // depth-1, bottom → B
        Rect a1 = cols.get(2).get(0);           // depth-2, top → A1
        Rect a2 = cols.get(2).get(1);           // depth-2, mid → A2
        Rect b1 = cols.get(2).get(2);           // depth-2, bottom → B1
        // A centres on A1..A2; B centres on its single child B1; Root centres on A..B.
        assertTrue(near(cy(a), (cy(a1) + cy(a2)) / 2), "A centres on A1,A2: " + cy(a));
        assertTrue(near(cy(b), cy(b1)), "B (single child) sits at B1's y: " + cy(b) + " vs " + cy(b1));
        assertTrue(near(cy(root), (cy(a) + cy(b)) / 2), "Root centres on A,B: " + cy(root));
    }

    @Test
    void leavesTakeEvenlySpacedYSlotsInDeclarationOrder() {
        // The three leaf boxes (deepest column) advance in equal y steps, top→down in declaration order.
        List<Rect> leaves = columns(rects(shapes(DSL))).get(2);
        assertEquals(3, leaves.size());
        double step = cy(leaves.get(1)) - cy(leaves.get(0));
        assertTrue(step > 0, "leaves advance downward");
        assertTrue(near(cy(leaves.get(2)) - cy(leaves.get(1)), step), "leaf y-slots are evenly spaced");
    }

    /// DELETE-MUTANT SENTINEL #2 (receipt #6): an elbow connector reaches EACH parent→child — every
    /// non-root box has an incoming segment ending exactly at its LEFT-middle, and its parent has an
    /// outgoing segment leaving its RIGHT-middle. Drop the edge loop in {@link MindmapLayout} and no
    /// such segment exists → this fails BY NAME.
    @Test
    void anEdgeConnectsEachParentToChild() {
        List<Shape> shapes = shapes(DSL);
        List<Rect> boxes = rects(shapes);
        List<Line> segs = lines(shapes);
        List<List<Rect>> cols = columns(boxes);
        // Every non-root box (depths 1..2) must have a connector segment ENDING at its left-middle.
        for (int d = 1; d < cols.size(); d++) {
            for (Rect child : cols.get(d)) {
                double left = child.x();
                double mid = cy(child);
                boolean incoming = segs.stream().anyMatch(l ->
                    near(l.x2(), left) && near(l.y2(), mid));
                assertTrue(incoming, "a connector reaches the child's left edge at ("
                    + left + "," + mid + ")");
            }
        }
        // And the root has an OUTGOING segment leaving its right-middle (it is a parent of A + B).
        Rect root = cols.get(0).get(0);
        double rootRight = root.x() + root.width();
        boolean outgoing = segs.stream().anyMatch(l -> near(l.x1(), rootRight) && near(l.y1(), cy(root)));
        assertTrue(outgoing, "the root has a connector leaving its right edge at ("
            + rootRight + "," + cy(root) + ")");
    }

    @Test
    void theCanvasContainsEveryBox() {
        Mindmap m = (Mindmap) DslParser.parse(DSL);
        LaidOut laid = MindmapLayout.layout(m);
        double w = laid.width();
        double h = laid.height();
        for (Rect r : rects(Group.flatten(laid.shapes()))) {
            assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.width() <= w && r.y() + r.height() <= h,
                "box escapes the canvas: " + r + " canvas " + w + "x" + h);
        }
    }

    @Test
    void aSingleRootLaysOutOneBoxAndNoEdges() {
        List<Shape> shapes = shapes("mindmap\n  Solo\n");
        assertEquals(1, rects(shapes).size(), "one node box");
        assertTrue(lines(shapes).isEmpty(), "no connectors on a root-only mindmap");
    }

    @Test
    void anEmptyMindmapLaysOutAMinimalInertCanvasNotAThrow() {
        Mindmap m = (Mindmap) DslParser.parse("mindmap\n");
        LaidOut laid = MindmapLayout.layout(m);
        assertTrue(laid.width() > 0 && laid.height() > 0, "a real (non-zero) canvas");
        assertTrue(rects(Group.flatten(laid.shapes())).isEmpty(), "no node boxes on an empty mindmap");
    }
}
