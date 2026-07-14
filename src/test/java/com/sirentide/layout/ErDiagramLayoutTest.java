package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.contract.SirentideContract;
import com.sirentide.contract.SirentideRole;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.ir.ErCardinality;
import com.sirentide.ir.ErDiagram;
import com.sirentide.parse.DslParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// The ER-diagram CROW-FOOT marker + table geometry pins (plan sirentide-er-diagram). The four
/// cardinality combos are the fidelity crux — a wrong combo (a bar where a fork belongs, a fork at the
/// wrong end) is a broken ER diagram — so each is pinned by its SHAPE SIGNATURE: a bar = 1 line, a
/// crow's-foot = 3 lines SHARING one convergence point, a hollow circle = a many-segment ring. The
/// {@code zeroOrManyEndEmitsACrowFootNotABar} test is the DELETE-MUTANT sentinel: swap the many-side
/// fork for a bar (or drop it) and it fails by name.
///
/// Lives in {@code com.sirentide.layout} to reach the package-private
/// {@link ErDiagramLayout#cardinalityMarker} builder directly — the tests assert geometry, never
/// re-implement layout.
class ErDiagramLayoutTest {

    private static final String MK = "#0f766e";

    private static List<Line> lines(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Line).map(s -> (Line) s).toList();
    }

    /// The largest set of Line endpoints that coincide at a single point — a crow's-foot's three
    /// prongs share ONE convergence point, so a fork yields 3 here; unrelated bar ticks yield 1.
    private static int maxSharedEndpoint(List<Line> ls) {
        int best = 0;
        for (Line pivot : ls) {
            for (double[] pt : new double[][] {{pivot.x1(), pivot.y1()}, {pivot.x2(), pivot.y2()}}) {
                int count = 0;
                for (Line l : ls) {
                    if (near(l.x1(), pt[0]) && near(l.y1(), pt[1])
                        || near(l.x2(), pt[0]) && near(l.y2(), pt[1])) {
                        count++;
                    }
                }
                best = Math.max(best, count);
            }
        }
        return best;
    }

    // ---- the three primitive glyphs -----------------------------------------------------------

    @Test
    void aBarIsExactlyOnePerpendicularLine() {
        List<Shape> m = ErDiagramLayout.barTick(20, 0, 1, 0, MK);
        List<Line> ls = lines(m);
        assertEquals(1, ls.size(), "a bar tick is a single line");
        // Perpendicular to a horizontal edge → a vertical tick (x1 == x2).
        assertTrue(near(ls.get(0).x1(), ls.get(0).x2()), "the bar is perpendicular to the edge");
    }

    @Test
    void aCrowFootIsThreeLinesSharingOneConvergencePoint() {
        List<Shape> m = ErDiagramLayout.crowFoot(0, 0, 1, 0, MK);
        List<Line> ls = lines(m);
        assertEquals(3, ls.size(), "a crow's-foot is three prongs");
        assertEquals(3, maxSharedEndpoint(ls),
            "all three prongs meet at ONE convergence point — the three-prong fork");
    }

    @Test
    void aHollowCircleIsAManySegmentClosedRing() {
        List<Shape> m = ErDiagramLayout.hollowCircle(30, 0, MK);
        List<Line> ls = lines(m);
        assertTrue(ls.size() >= 8, "the hollow ring is a many-segment polygon: " + ls.size());
        // Closed loop: the last segment's end coincides with the first segment's start.
        Line first = ls.get(0);
        Line last = ls.get(ls.size() - 1);
        assertTrue(near(last.x2(), first.x1()) && near(last.y2(), first.y1()), "the ring closes");
    }

    // ---- the four cardinality combos ----------------------------------------------------------

    @Test
    void zeroOrOneIsABarPlusAHollowCircle() {
        List<Line> ls = lines(ErDiagramLayout.cardinalityMarker(
            ErCardinality.ZERO_OR_ONE, 0, 0, 1, 0, MK));
        // 1 inner bar + a ring; no 3-line convergence (no fork).
        assertTrue(maxSharedEndpoint(ls) < 3, "zero-or-one has NO crow's-foot fork");
        assertTrue(ls.size() > 3, "a bar + a ring is more than the 3 lines of a bare fork: " + ls.size());
    }

    @Test
    void exactlyOneIsTwoBarsAndNothingElse() {
        List<Line> ls = lines(ErDiagramLayout.cardinalityMarker(
            ErCardinality.EXACTLY_ONE, 0, 0, 1, 0, MK));
        assertEquals(2, ls.size(), "exactly-one is a DOUBLE tick: two bars, no fork, no ring");
        assertTrue(maxSharedEndpoint(ls) < 3, "exactly-one has NO crow's-foot fork");
    }

    @Test
    void oneOrManyIsACrowFootPlusABar() {
        List<Line> ls = lines(ErDiagramLayout.cardinalityMarker(
            ErCardinality.ONE_OR_MANY, 0, 0, 1, 0, MK));
        assertEquals(4, ls.size(), "one-or-many = a 3-line fork + a 1-line bar");
        assertEquals(3, maxSharedEndpoint(ls), "the fork's three prongs converge (the MANY side)");
    }

    /// The DELETE-MUTANT sentinel: the ZERO-OR-MANY marker MUST carry a crow's-foot (a "many" fork), not
    /// a bar. If the inner symbol is swapped for a bar (or the fork is dropped), the three-prong
    /// convergence disappears and this test fails by name.
    @Test
    void zeroOrManyEndEmitsACrowFootNotABar() {
        List<Line> ls = lines(ErDiagramLayout.cardinalityMarker(
            ErCardinality.ZERO_OR_MANY, 0, 0, 1, 0, MK));
        assertEquals(3, maxSharedEndpoint(ls),
            "zero-or-many's inner symbol is a THREE-PRONG crow's-foot — a bar here is a broken diagram");
        // And it also carries the optional "zero" ring, so it is strictly more than a bare fork.
        assertTrue(ls.size() > 3, "zero-or-many = a fork + a hollow ring: " + ls.size());
    }

    // ---- markers land at the correct END, on the correct SIDE ---------------------------------

    @Test
    void theCrowFootFansTowardTheEntityBorderNotAwayFromIt() {
        // dir points AWAY from the entity (toward the other entity). The fork's convergence sits OUT
        // along dir, and its wide (spread) end sits AT the border — so a "many" reads as a fork opening
        // onto the table, never a fork pointing off into space.
        double tipX = 40, tipY = 25;
        List<Line> ls = lines(ErDiagramLayout.crowFoot(tipX, tipY, 1, 0, MK));
        // The convergence point is the shared endpoint; it must be farther along +x than the tip.
        double convX = ls.get(0).x1();   // crowFoot emits every prong starting at the convergence
        assertTrue(convX > tipX, "the fork converges OUTWARD (away from the entity), spread at the border");
        // Two prongs reach the spread points at tip ± perp; one reaches the tip itself.
        long atTip = ls.stream().filter(l -> near(l.x2(), tipX) && near(l.y2(), tipY)).count();
        assertEquals(1, atTip, "the middle prong meets the border attachment point");
    }

    @Test
    void bothEndsOfARelationGetTheirOwnMarkerCombo() {
        // End-to-end: CUSTOMER ||--o{ ORDER bakes a bar-combo at the CUSTOMER end and a crow's-foot at
        // the ORDER end — the asymmetric cardinality actually reaches the scene at the right ends.
        ErDiagram er = (ErDiagram) DslParser.parse(
            "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n");
        LaidOut laid = ErDiagramLayout.layout(er);
        List<Line> markerLines = Group.flatten(laid.shapes()).stream()
            .filter(s -> s instanceof Line l && MK.equals(l.stroke()))
            .map(s -> (Line) s)
            .toList();
        // A whole diagram has exactly one crow's-foot (the ORDER end): a set of 3 lines converging.
        assertEquals(3, maxSharedEndpoint(markerLines),
            "exactly one three-prong fork in the scene — the ORDER (many) end");
    }

    @Test
    void aPopulatedEntityTableHasAHeaderPlusRows() {
        // An entity with attributes draws a header band + a rows compartment: a top border, a
        // header/rows divider, and a bottom border = 3 horizontal lines (the divider strictly inside).
        ErDiagram er = (ErDiagram) DslParser.parse(
            "erDiagram\n  CUSTOMER {\n    string name PK\n    string email\n    int age\n  }\n");
        LaidOut laid = ErDiagramLayout.layout(er);
        List<Double> ys = Group.flatten(laid.shapes()).stream()
            .filter(s -> s instanceof Line l && near(l.y1(), l.y2()) && BORDER_STROKE(l))
            .map(s -> ((Line) s).y1())
            .sorted()
            .toList();
        assertEquals(3, ys.size(),
            "top border + header/rows divider + bottom border = 3 horizontal table lines");
        assertTrue(ys.get(0) < ys.get(1) && ys.get(1) < ys.get(2), "header sits above the rows");
    }

    @Test
    void anAttributelessEntityCollapsesToASingleNameBox() {
        ErDiagram er = (ErDiagram) DslParser.parse("erDiagram\n  LONER\n");
        LaidOut laid = ErDiagramLayout.layout(er);
        long horiz = Group.flatten(laid.shapes()).stream()
            .filter(s -> s instanceof Line l && near(l.y1(), l.y2()) && BORDER_STROKE(l)).count();
        assertEquals(2, horiz, "an attribute-less entity is a single box: top + bottom borders, no divider");
    }

    /// Bug-1 fix: an ER self-relation (`A ||--o{ A`) has both endpoints on the SAME table. The
    /// zero-length straight edge used to draw the crow-foot combos INSIDE the table at its center; the
    /// prior "fix" instead SKIPPED it, erasing a semantically-valid recursive relationship (e.g. EMPLOYEE
    /// manages EMPLOYEE) AND leaving a phantom empty edge group owning an anchor. It must now render a
    /// deterministic on-canvas self-LOOP routed off the table's right edge, with BOTH cardinality combos.
    /// This pins: (a) the edge group owns REAL leaf geometry (non-empty — no phantom group), (b) a
    /// crow-foot cardinality marker renders on the loop, (c) NO loop endpoint/line sits inside the table
    /// (the loop stays on/outside the right border, never at the center — the original bug), (d) the label
    /// renders, (e) the scene stays in the contract alphabet, (f) the loop stays inside the (grown)
    /// viewBox. Restore the center-drawing markers and (c) fails by name.
    @Test
    void aSelfRelationRoutesAnOnCanvasLoopWithItsMarkersOutsideTheTable() throws Exception {
        ErDiagram er = (ErDiagram) DslParser.parse("erDiagram\n  A ||--o{ A : loops\n");
        LaidOut laid = ErDiagramLayout.layout(er);

        // (f-anchor) exactly one edge group — now NON-EMPTY (owns the loop geometry, no phantom group).
        List<Group> edges = laid.shapes().stream()
            .filter(s -> s instanceof Group g && g.anchor().role() == SirentideRole.EDGE)
            .map(s -> (Group) s)
            .toList();
        assertEquals(1, edges.size(), "the self-relation produces exactly one edge group");
        Group edge = edges.get(0);
        assertFalse(edge.members().isEmpty(),
            "the self-loop group owns REAL leaf geometry — not a phantom empty group");

        // (a) real leaf geometry: the loop is built from the SAME Line edge primitive — ≥ 3 legs.
        List<Line> loopLegs = edge.members().stream()
            .filter(s -> s instanceof Line l && "#5eead4".equals(l.stroke())).map(s -> (Line) s).toList();
        assertTrue(loopLegs.size() >= 3, "a rectilinear self-loop routes ≥ 3 edge legs: " + loopLegs.size());

        // (b) a crow-foot cardinality marker (the ZERO_OR_MANY end's three-prong fork) renders on the
        // loop — the whole point vs erasure: the recursive relationship keeps its cardinality semantics.
        List<Line> markerLines = edge.members().stream()
            .filter(s -> s instanceof Line l && MK.equals(l.stroke())).map(s -> (Line) s).toList();
        assertEquals(3, maxSharedEndpoint(markerLines),
            "the ZERO_OR_MANY end's three-prong crow-foot renders on the loop");

        // (c) NO loop line sits INSIDE the table — every endpoint on/outside the border, none at center.
        Rect box = entityBoxRect(laid);
        double cx = box.x() + box.width() / 2;
        double cy = box.y() + box.height() / 2;
        List<Line> groupLines = edge.members().stream()
            .filter(s -> s instanceof Line).map(s -> (Line) s).toList();
        for (Line l : groupLines) {
            assertFalse(strictlyInside(box, l.x1(), l.y1()),
                "a loop endpoint is inside the table interior: " + l.x1() + "," + l.y1());
            assertFalse(strictlyInside(box, l.x2(), l.y2()),
                "a loop endpoint is inside the table interior: " + l.x2() + "," + l.y2());
            assertFalse(near(l.x1(), cx) && near(l.y1(), cy), "a loop line touches the table center");
            assertFalse(near(l.x2(), cx) && near(l.y2(), cy), "a loop line touches the table center");
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
        assertTrue(labels >= 1, "the relation label ': loops' renders near the loop");

        // (e) containment: the whole emitted scene parses and stays in the allowed element alphabet.
        assertSceneInAllowlist(laid);
    }

    /// The background {@link Rect} of the (sole) entity table — its geometry rectangle (x, y, w, h).
    private static Rect entityBoxRect(LaidOut laid) {
        return laid.shapes().stream()
            .filter(s -> s instanceof Group g && g.anchor().role() == SirentideRole.ENTITY)
            .flatMap(g -> ((Group) g).members().stream())
            .filter(s -> s instanceof Rect).map(s -> (Rect) s)
            .findFirst().orElseThrow();
    }

    private static boolean strictlyInside(Rect b, double x, double y) {
        return x > b.x() + 1e-6 && x < b.x() + b.width() - 1e-6
            && y > b.y() + 1e-6 && y < b.y() + b.height() - 1e-6;
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

    private static boolean BORDER_STROKE(Line l) {
        return "#0f766e".equals(l.stroke());
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }
}
