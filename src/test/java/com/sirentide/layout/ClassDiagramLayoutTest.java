package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.contract.SirentideContract;
import com.sirentide.contract.SirentideRole;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.ir.RelationKind;
import com.sirentide.parse.DslParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// The class-diagram MARKER-GLYPH + compartment geometry pins (plan sirentide-class-diagram). The
/// five UML markers are the fidelity crux — a wrong shape at a wrong end reads as "broken" — so each
/// is pinned by its SHAPE SIGNATURE (a hollow triangle = 3 line segments, a filled diamond = a
/// 4-vertex path, a hollow diamond = 4 line segments, an open arrow = 2 line segments). The
/// {@code compositionMarkerIsAFilledFourPointDiamond} test is the DELETE-MUTANT sentinel: swap the
/// composition diamond for a triangle (or drop it) and it fails by name.
///
/// Lives in {@code com.sirentide.layout} to reach the package-private {@link ClassDiagramLayout#marker}
/// builder directly — the tests assert geometry, never re-implement layout.
class ClassDiagramLayoutTest {

    private static final String MK = "#475569";

    /// Vertices in a path `d`: one per `M`/`L` command (a closed polygon of k points is `M … L … Z`).
    private static int vertices(String d) {
        int v = 0;
        for (String tok : d.trim().split("\\s+")) {
            if (tok.equals("M") || tok.equals("L")) {
                v++;
            }
        }
        return v;
    }

    private static long lines(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Line).count();
    }

    private static long paths(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Path).count();
    }

    @Test
    void inheritanceMarkerIsAHollowTriangleOfThreeLines() {
        List<Shape> m = ClassDiagramLayout.marker(RelationKind.INHERITANCE, 0, 0, 1, 0, MK);
        assertEquals(3, lines(m), "a hollow triangle is a 3-segment stroked outline");
        assertEquals(0, paths(m), "the triangle is hollow — no filled path");
    }

    /// The DELETE-MUTANT sentinel: the composition marker MUST be a FILLED 4-vertex diamond path. If it
    /// is swapped for a triangle (3 vertices) or dropped, this test fails by name.
    @Test
    void compositionMarkerIsAFilledFourPointDiamond() {
        List<Shape> m = ClassDiagramLayout.marker(RelationKind.COMPOSITION, 0, 0, 1, 0, MK);
        assertEquals(1, paths(m), "composition is a single filled diamond path");
        assertEquals(0, lines(m), "the filled diamond carries no separate outline lines");
        Path p = (Path) m.get(0);
        assertEquals(4, vertices(p.d()),
            "a diamond has FOUR vertices — a 3-vertex path would be a triangle (a wrong marker)");
        assertNotEquals("none", p.fill(), "the composition diamond is FILLED, not hollow");
        assertEquals(MK, p.fill());
    }

    @Test
    void aggregationMarkerIsAHollowDiamondOfFourLines() {
        List<Shape> m = ClassDiagramLayout.marker(RelationKind.AGGREGATION, 0, 0, 1, 0, MK);
        assertEquals(4, lines(m), "a hollow diamond is a 4-segment stroked outline");
        assertEquals(0, paths(m), "the aggregation diamond is hollow — no filled path");
    }

    @Test
    void associationAndDependencyMarkersAreOpenTwoLineArrows() {
        List<Shape> assoc = ClassDiagramLayout.marker(RelationKind.ASSOCIATION, 0, 0, 1, 0, MK);
        assertEquals(2, lines(assoc), "an open arrow is two barb segments");
        assertEquals(0, paths(assoc));
        List<Shape> dep = ClassDiagramLayout.marker(RelationKind.DEPENDENCY, 0, 0, 1, 0, MK);
        assertEquals(2, lines(dep), "dependency shares the open arrowhead (its edge line is dashed)");
        assertEquals(0, paths(dep));
    }

    @Test
    void everyMarkerTipSitsExactlyAtTheAnchorPoint() {
        // The tip (the point that touches the box border) must be the anchor for EVERY kind, so the
        // marker reads as attached to the box, not floating. Check the shared tip across the families.
        double tipX = 40, tipY = 25;
        // Triangle: two of its three lines share the tip endpoint.
        List<Shape> tri = ClassDiagramLayout.marker(RelationKind.INHERITANCE, tipX, tipY, 1, 0, MK);
        long tipTouches = tri.stream().filter(s -> s instanceof Line l
            && ((near(l.x1(), tipX) && near(l.y1(), tipY)) || (near(l.x2(), tipX) && near(l.y2(), tipY))))
            .count();
        assertEquals(2, tipTouches, "the triangle's two adjacent edges meet at the tip");
        // Filled diamond: the path starts at the tip (M tipX tipY …).
        Path dia = (Path) ClassDiagramLayout.marker(RelationKind.COMPOSITION, tipX, tipY, 1, 0, MK).get(0);
        assertTrue(dia.d().startsWith("M " + fmt(tipX) + " " + fmt(tipY)),
            "the diamond's leading vertex is the tip: " + dia.d());
    }

    @Test
    void aPopulatedClassBoxHasThreeCompartmentsInOrder() {
        // A class with attributes AND methods draws name / attributes / methods — two interior horizontal
        // divider lines between them, at increasing y, spanning the box width.
        LaidOut laid = ClassDiagramLayout.layout((com.sirentide.ir.ClassDiagram) DslParser.parse(
            "classDiagram\n  class Animal {\n    +String name\n    +int age\n    +eat() void\n  }\n"));
        // Box border = 4 lines; the 2 dividers are interior horizontal lines strictly inside the box.
        // Collect all horizontal lines (y1 == y2), sort by y: the top border, div1, div2, bottom border.
        List<Line> horiz = Group.flatten(laid.shapes()).stream()
            .filter(s -> s instanceof Line l && near(l.y1(), l.y2()))
            .map(s -> (Line) s)
            .sorted((a, b) -> Double.compare(a.y1(), b.y1()))
            .toList();
        assertEquals(4, horiz.size(),
            "top border + name/attr divider + attr/method divider + bottom border = 4 horizontal lines");
        double topY = horiz.get(0).y1();
        double div1 = horiz.get(1).y1();
        double div2 = horiz.get(2).y1();
        double botY = horiz.get(3).y1();
        assertTrue(topY < div1 && div1 < div2 && div2 < botY,
            "compartments stack in order: name < attr-divider < method-divider < bottom");
    }

    @Test
    void aMemberlessClassCollapsesToASingleNameBoxWithNoDividers() {
        LaidOut laid = ClassDiagramLayout.layout((com.sirentide.ir.ClassDiagram) DslParser.parse(
            "classDiagram\n  class Loner\n"));
        long horiz = Group.flatten(laid.shapes()).stream()
            .filter(s -> s instanceof Line l && near(l.y1(), l.y2())).count();
        assertEquals(2, horiz, "a memberless class is a single box: only top + bottom borders, no dividers");
    }

    @Test
    void compositionEdgePlacesExactlyOneFilledDiamondInTheScene() {
        // End-to-end: a composition-only diagram bakes exactly ONE filled diamond path (the marker) —
        // the whole-end marker actually reaches the scene at the correct end.
        LaidOut laid = ClassDiagramLayout.layout((com.sirentide.ir.ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  class B\n  A *-- B\n"));
        List<Path> diamonds = Group.flatten(laid.shapes()).stream()
            .filter(s -> s instanceof Path p && vertices(p.d()) == 4)
            .map(s -> (Path) s)
            .toList();
        assertEquals(1, diamonds.size(), "one composition edge → one filled diamond marker in the scene");
    }

    /// Bug-1 fix: a self-relation (`A <|-- A`, the inheritance operator) has both endpoints on the SAME
    /// box. The zero-length straight edge used to draw the UML marker INSIDE the box at its center; the
    /// prior "fix" instead SKIPPED it, erasing a semantically-valid recursive relationship AND leaving a
    /// phantom empty edge group owning an anchor. It must now render a deterministic on-canvas self-LOOP
    /// routed off the box's right edge. This pins: (a) the edge group owns REAL leaf geometry (non-empty
    /// — no phantom group), (b) the UML marker renders on the loop, (c) NO loop endpoint/line sits inside
    /// the box (the loop stays on/outside the right border, never at the center — the original bug), (d)
    /// the label renders, (e) the whole scene stays in the contract alphabet, (f) the loop stays inside
    /// the (grown) viewBox. Restore the center-drawing marker and (c) fails by name.
    @Test
    void aSelfRelationRoutesAnOnCanvasLoopWithItsMarkerOutsideTheBox() throws Exception {
        LaidOut laid = ClassDiagramLayout.layout((com.sirentide.ir.ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A <|-- A : recurses\n"));

        // (f-anchor) the self-relation STILL gets exactly one edge group — but now it is NON-EMPTY (it
        // owns the loop's real leaf geometry, so the phantom-empty-group + orphan-anchor problem is gone).
        List<Group> edges = laid.shapes().stream()
            .filter(s -> s instanceof Group g && g.anchor().role() == SirentideRole.EDGE)
            .map(s -> (Group) s)
            .toList();
        assertEquals(1, edges.size(), "the self-relation produces exactly one edge group");
        Group edge = edges.get(0);
        assertFalse(edge.members().isEmpty(),
            "the self-loop group owns REAL leaf geometry — not a phantom empty group");

        // (a) real leaf geometry: the loop is built from the SAME Line edge primitive — a rectilinear
        // loop is at least three legs (out, down, back).
        List<Line> loopLegs = edge.members().stream()
            .filter(s -> s instanceof Line l && EDGE_STROKE(l)).map(s -> (Line) s).toList();
        assertTrue(loopLegs.size() >= 3, "a rectilinear self-loop routes ≥ 3 edge legs: " + loopLegs.size());

        // (b) the UML marker (inheritance → hollow triangle = 3 marker-coloured lines) renders on the loop.
        List<Line> markerLines = edge.members().stream()
            .filter(s -> s instanceof Line l && MK.equals(l.stroke())).map(s -> (Line) s).toList();
        assertEquals(3, markerLines.size(),
            "the inheritance hollow-triangle marker (3 lines) renders at the loop's return endpoint");

        // (c) NO loop line sits INSIDE the box — every endpoint is on/outside the box boundary, and in
        // particular NONE is at the box center (where the degenerate marker used to be drawn).
        Rect box = classBoxRect(laid);
        double cx = box.x() + box.width() / 2;
        double cy = box.y() + box.height() / 2;
        List<Line> groupLines = edge.members().stream()
            .filter(s -> s instanceof Line).map(s -> (Line) s).toList();
        for (Line l : groupLines) {
            assertFalse(strictlyInside(box, l.x1(), l.y1()),
                "a loop endpoint is inside the box interior: " + l.x1() + "," + l.y1());
            assertFalse(strictlyInside(box, l.x2(), l.y2()),
                "a loop endpoint is inside the box interior: " + l.x2() + "," + l.y2());
            assertFalse(near(l.x1(), cx) && near(l.y1(), cy), "a loop line touches the box center");
            assertFalse(near(l.x2(), cx) && near(l.y2(), cy), "a loop line touches the box center");
        }

        // (f) the loop stays inside the (grown) viewBox — every endpoint within [0,w] × [0,h].
        for (Line l : groupLines) {
            assertTrue(l.x1() >= 0 && l.x1() <= laid.width() && l.x2() >= 0 && l.x2() <= laid.width(),
                "loop x escapes the viewBox width " + laid.width() + ": " + l.x1() + "," + l.x2());
            assertTrue(l.y1() >= 0 && l.y1() <= laid.height() && l.y2() >= 0 && l.y2() <= laid.height(),
                "loop y escapes the viewBox height " + laid.height() + ": " + l.y1() + "," + l.y2());
        }

        // (d) the relation LABEL renders as a glyph run in the edge group.
        long labels = edge.members().stream().filter(s -> s instanceof GlyphRun).count();
        assertTrue(labels >= 1, "the relation label ': recurses' renders near the loop");

        // (e) containment: the whole emitted scene parses and stays in the allowed element alphabet.
        assertSceneInAllowlist(laid);
    }

    /// The background {@link Rect} of the (sole) class box — its geometry rectangle (x, y, w, h).
    private static Rect classBoxRect(LaidOut laid) {
        return laid.shapes().stream()
            .filter(s -> s instanceof Group g && g.anchor().role() == SirentideRole.CLASS)
            .flatMap(g -> ((Group) g).members().stream())
            .filter(s -> s instanceof Rect).map(s -> (Rect) s)
            .findFirst().orElseThrow();
    }

    private static boolean strictlyInside(Rect b, double x, double y) {
        return x > b.x() + 1e-6 && x < b.x() + b.width() - 1e-6
            && y > b.y() + 1e-6 && y < b.y() + b.height() - 1e-6;
    }

    private static boolean EDGE_STROKE(Line l) {
        return "#94a3b8".equals(l.stroke());
    }

    /// Emit the scene and assert every element tag is inside {@link SirentideContract#ALLOWED_ELEMENTS}
    /// — the SVG parses (well-formed) and stays in the allowed alphabet (containment stays clean).
    private static void assertSceneInAllowlist(LaidOut laid) throws Exception {
        String svg = SvgEmitter.emit(laid);
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Element root = f.newDocumentBuilder()
            .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        walkAllowlist(root);
    }

    private static void walkAllowlist(Element el) {
        assertTrue(SirentideContract.ALLOWED_ELEMENTS.contains(el.getTagName()),
            "element <" + el.getTagName() + "> is outside the contract alphabet");
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                walkAllowlist((Element) k);
            }
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static String fmt(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
