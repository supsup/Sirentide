package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Flowchart;
import com.sirentide.layout.FlowchartLayout;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.Path;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Per-shape LAYOUT geometry pins for the mermaid flowchart node shapes (`([stadium])`, `((circle))`,
/// `{{hexagon}}`, `[(cylinder)]`, `[[subroutine]]`, `(rounded)`) added on top of the existing rect +
/// diamond. Each test renders a SINGLE-node flowchart (no edges → the node's outline is the only
/// {@link Path} among the flattened leaves, and the only {@link Line}s are that shape's own chrome —
/// the subroutine bars / the cylinder lid rim) and asserts a DISTINGUISHING geometric feature, so a
/// shape collapsing to a plain rect (the delete-mutant) goes red here, not silently.
///
/// The layout API (not the SVG string) is inspected directly: {@link FlowchartLayout#layout} →
/// {@link Group#flatten} → filter by leaf type. The node NODE_H is 36 (mirrors FlowchartLayout).
class FlowchartShapeGeometryTest {

    private static final double NODE_H = 36;

    private static List<Shape> leaves(String dsl) {
        Diagram d = DslParser.parse(dsl);
        assertTrue(d instanceof Flowchart, "parses to a flowchart");
        return Group.flatten(FlowchartLayout.layout((Flowchart) d).shapes());
    }

    /// The single node-outline {@link Path} in a single-node (edge-free) chart — the label is a
    /// GlyphRun (not a Path) and there are no arrowheads, so exactly one Path exists for the shapes
    /// that draw their outline as a path (every shape but rect + subroutine).
    private static Path outlinePath(List<Shape> ls) {
        List<Path> paths = ls.stream().filter(Path.class::isInstance).map(Path.class::cast).toList();
        assertEquals(1, paths.size(), "exactly one outline path for an edge-free single-node chart");
        return paths.get(0);
    }

    private static List<Line> lines(List<Shape> ls) {
        return ls.stream().filter(Line.class::isInstance).map(Line.class::cast).toList();
    }

    private static List<Rect> rects(List<Shape> ls) {
        return ls.stream().filter(Rect.class::isInstance).map(Rect.class::cast).toList();
    }

    /// Count occurrences of a whitespace-delimited path command token (e.g. " L ", " A ", " Q ").
    private static int countTok(String d, String tok) {
        int n = 0;
        for (int i = d.indexOf(tok); i >= 0; i = d.indexOf(tok, i + 1)) {
            n++;
        }
        return n;
    }

    // -- byte-behaviour-unchanged rect + diamond ------------------------------

    @Test
    void rectStaysAPlainRectNotAPath() {
        List<Shape> ls = leaves("flowchart\n  A[Rect]\n");
        assertEquals(1, rects(ls).size(), "the rect node is a <rect>, unchanged");
        assertTrue(ls.stream().noneMatch(Path.class::isInstance), "a rect node emits no outline path");
        Rect r = rects(ls).get(0);
        assertEquals(NODE_H, r.height(), 0.001, "rect keeps NODE_H height");
    }

    @Test
    void diamondStaysAStraightRhombusPath() {
        Path p = outlinePath(leaves("flowchart\n  A{Decision}\n"));
        assertEquals(3, countTok(p.d(), " L "), "a diamond is M + 3 L (4 vertices)");
        assertTrue(!p.d().contains(" A ") && !p.d().contains(" Q "), "a diamond has no curves");
    }

    // -- new shapes -----------------------------------------------------------

    @Test
    void roundedBoxHasQuadraticCornersAndNoArcs() {
        Path p = outlinePath(leaves("flowchart\n  A(Rounded)\n"));
        assertEquals(4, countTok(p.d(), " Q "), "a rounded rect has a Q at each of its 4 corners");
        assertTrue(!p.d().contains(" A "), "a rounded rect uses quadratics, not arcs");
        assertTrue(rects(leaves("flowchart\n  A(Rounded)\n")).isEmpty(), "rounded is a path, not a <rect>");
    }

    @Test
    void hexagonIsASixPointStraightPolygon() {
        Path p = outlinePath(leaves("flowchart\n  A{{Hexagon}}\n"));
        // M + 5 L + Z = 6 vertices; no curves at all (this is what makes it a hexagon, not a pill).
        assertEquals(5, countTok(p.d(), " L "), "a hexagon is M + 5 L (6 points)");
        assertTrue(!p.d().contains(" A ") && !p.d().contains(" Q "), "a hexagon is straight-edged");
    }

    @Test
    void stadiumHasRoundedEndCapsAndIsWiderThanTall() {
        List<Shape> ls = leaves("flowchart\n  A([Stadium])\n");
        Path p = outlinePath(ls);
        assertEquals(2, countTok(p.d(), " A "), "a stadium has a semicircular cap on each short side");
        double capR = arcRadius(p.d());
        assertEquals(NODE_H / 2, capR, 0.001, "the cap radius is h/2 (fully-rounded ends)");
        assertTrue(lines(ls).isEmpty(), "a stadium is a single path — no chrome lines (unlike a cylinder)");
    }

    @Test
    void circleIsAnEqualAxisRing() {
        // A SHORT label sizes the box square (w == h == NODE_H), so the ellipse is a TRUE circle: its
        // two arc radii (rx == ry) are equal. This is the delete-mutant killer — a circle falling back
        // to a rect emits a <rect> (0 outline paths) and this test's outlinePath() assertion fails.
        List<Shape> ls = leaves("flowchart\n  A((C))\n");
        Path p = outlinePath(ls);
        assertEquals(2, countTok(p.d(), " A "), "a full ellipse is traced as two half-arcs");
        double[] radii = arcRadii(p.d());
        assertEquals(radii[0], radii[1], 4.0, "circle ≈ equal w/h ring (rx ≈ ry): " + radii[0] + "," + radii[1]);
        assertEquals(NODE_H / 2, radii[1], 0.001, "the vertical radius is h/2");
        assertTrue(lines(ls).isEmpty(), "a circle is a single path, no chrome lines");
    }

    @Test
    void cylinderHasAnArcSilhouetteAndAMultiSegmentLidRim() {
        List<Shape> ls = leaves("flowchart\n  A[(Database)]\n");
        Path p = outlinePath(ls);
        assertTrue(p.d().contains(" A "), "a cylinder's silhouette has the elliptical top/bottom arcs");
        // The visible lid rim rides multiple Line segments (a curve a fill-only path can't stroke) — the
        // feature that reads as a DB can and separates a cylinder from a stadium (which has no lines).
        assertTrue(lines(ls).size() >= 3,
            "the cylinder lid rim is a multi-segment line polyline: " + lines(ls).size());
    }

    @Test
    void subroutineIsARectWithTwoInnerVerticalBars() {
        List<Shape> ls = leaves("flowchart\n  A[[Subroutine]]\n");
        assertEquals(1, rects(ls).size(), "a subroutine's body is a <rect>");
        Rect box = rects(ls).get(0);
        List<Line> bars = lines(ls);
        assertEquals(2, bars.size(), "a subroutine has exactly two inner vertical bars");
        for (Line bar : bars) {
            assertEquals(bar.x1(), bar.x2(), 0.001, "each bar is vertical");
            assertTrue(bar.x1() > box.x() && bar.x1() < box.x() + box.width(),
                "each bar sits inside the box, in from the short side");
            assertEquals(box.height(), Math.abs(bar.y2() - bar.y1()), 0.001, "a bar spans the full box height");
        }
    }

    // -- arc-radius parse helpers (an `A rx ry x-rot large sweep ex ey` command) --

    /// The first arc's radius (rx, which == ry for the stadium/cylinder circular caps).
    private static double arcRadius(String d) {
        return arcRadii(d)[0];
    }

    /// The first arc command's {rx, ry} — the two numbers immediately following the first ` A ` token.
    private static double[] arcRadii(String d) {
        String[] t = d.trim().split("\\s+");
        for (int i = 0; i + 2 < t.length; i++) {
            if (t[i].equals("A")) {
                return new double[] {Double.parseDouble(t[i + 1]), Double.parseDouble(t[i + 2])};
            }
        }
        throw new AssertionError("no arc command in path: " + d);
    }
}
