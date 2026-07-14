package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ErDiagram;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// SELF-LOOP GEOMETRY receipts (Lattice re-review, sirentide seq 217). The prior tests bounded only
/// LINE endpoints, so a loop's LABEL (glyphs, up to MAX_LABEL_W wide) could escape the viewBox,
/// run through the neighbor box, and stacked self-relations could overpaint each other — all while
/// the suite stayed green. These receipts bound the FULL leaf geometry (every line endpoint, rect
/// corner, and glyph/marker path coordinate) instead:
///
///   1. a sole class/entity with a LONG-labeled self-loop keeps every coordinate inside the viewBox
///      (finding 1 — the old canvas growth reserved only the legs; repro: viewBox 162, label to 236);
///   2. a labeled self-loop never intersects the NEXT box in the row (finding 2 — the row cursor now
///      reserves the whole lane; repro: label x=142..233 through B at x=160);
///   3. multiple self-relations on one node take DISTINCT lanes — no two edge legs coincide, so a
///      later FUTURE group can never overpaint an earlier ACTIVE group in play-through (finding 3;
///      frames share the one layout's geometry, so static disjointness IS playback disjointness —
///      belt: the active frame's accent must actually appear);
///   4. the class marker follows the AUTHORED operand (finding 4): a whole/parent kind
///      (markerAtLeft, `A <|-- A`) caps the TOP attach, an arrow kind (`A --> A`) the BOTTOM —
///      mirroring both the straight-edge rule and the ER left-card-at-top mapping;
///   5. a TALL math label's ascent/descent participate in canvas growth (finding 1, vertical).
///
/// Every containment negative carries a POSITIVE control in the same fixture (the label/marker/loop
/// actually rendered), so none can go vacuously green.
class SelfLoopGeometryTest {

    private static final String EDGE = "#94a3b8";      // class relationship edge line
    private static final String ER_EDGE = "#5eead4";   // ER relationship edge line
    private static final String MK = "#475569";        // class marker glyph colour

    // -- 1) long label stays inside the viewBox (class + ER) -------------------------------------

    // The WORST-case label: long enough to hit the MAX_LABEL_W ellipsize ceiling (240/260px), i.e.
    // wider than the whole pre-fix canvas — the exact shape that used to floor the "impossible
    // clamp" at x=2 and overflow the viewBox (seq 217 finding 1's repro).
    private static final String LONG_LABEL =
        "recursive relationship with retry and exponential backoff semantics";

    @Test
    void soleClassSelfLoopWithALongLabelStaysInsideTheViewBox() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A --> A : " + LONG_LABEL + "\n"));
        // POSITIVE control: the label's glyphs actually rendered in the edge group.
        assertTrue(edgeGroups(laid).get(0).members().stream().anyMatch(s -> s instanceof GlyphRun),
            "the long label renders as glyphs on the loop");
        assertAllGeometryInside(laid);
    }

    @Test
    void soleEntitySelfLoopWithALongLabelStaysInsideTheViewBox() {
        LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  A ||--o{ A : " + LONG_LABEL + "\n"));
        assertTrue(edgeGroups(laid).get(0).members().stream().anyMatch(s -> s instanceof GlyphRun),
            "the long label renders as glyphs on the loop");
        assertAllGeometryInside(laid);
    }

    // -- 2) the label lane is RESERVED — a neighbor box never intersects it ----------------------

    @Test
    void classSelfLoopLabelNeverRunsThroughTheNeighborBox() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  class B\n  A --> A : recursive relationship\n  A --> B\n"));
        // POSITIVE control: A's loop label rendered, and B's box exists in the same row.
        Group loop = edgeGroups(laid).get(0);
        assertTrue(loop.members().stream().anyMatch(s -> s instanceof GlyphRun),
            "A's loop label renders");
        List<Rect> boxes = boxRects(laid, SirentideRole.CLASS);
        assertEquals(2, boxes.size(), "both class boxes placed");
        Rect b = boxes.get(1);
        // NO coordinate of the loop group (legs, marker, label glyphs) falls inside B's box.
        for (double[] p : groupPoints(loop)) {
            assertFalse(inside(b, p[0], p[1]),
                "loop geometry at " + p[0] + "," + p[1] + " runs through the neighbor box at x="
                    + b.x() + ".." + (b.x() + b.width()));
        }
        assertAllGeometryInside(laid);
    }

    @Test
    void erSelfLoopLabelNeverRunsThroughTheNeighborTable() {
        LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  A ||--o{ A : recursive relationship\n  A ||--|| B : uses\n"));
        Group loop = edgeGroups(laid).get(0);
        assertTrue(loop.members().stream().anyMatch(s -> s instanceof GlyphRun),
            "A's loop label renders");
        List<Rect> boxes = boxRects(laid, SirentideRole.ENTITY);
        assertEquals(2, boxes.size(), "both entity tables placed");
        Rect b = boxes.get(1);
        for (double[] p : groupPoints(loop)) {
            assertFalse(inside(b, p[0], p[1]),
                "loop geometry at " + p[0] + "," + p[1] + " runs through the neighbor table at x="
                    + b.x() + ".." + (b.x() + b.width()));
        }
        assertAllGeometryInside(laid);
    }

    // -- 3) multiple self-relations take DISTINCT lanes (no overpaint, playback-safe) -------------

    @Test
    void multipleClassSelfRelationsTakeDistinctLanes() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A --> A : first\n  A <|-- A : second\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(2, loops.size(), "two self-relations → two edge groups");
        // No two EDGE legs across the two groups coincide (the old geometry reused identical
        // x/attach/out for every loop → total overpaint). Frames re-emit this one layout with
        // recolour only, so leg disjointness here is exactly playback disjointness.
        Set<String> first = legSignatures(loops.get(0), EDGE);
        Set<String> second = legSignatures(loops.get(1), EDGE);
        assertFalse(first.isEmpty() || second.isEmpty(), "both loops route real legs");
        for (String sig : second) {
            assertFalse(first.contains(sig), "lane 1 leg coincides with a lane 0 leg: " + sig);
        }
        // Each loop's own VERTICAL leg sits at a distinct outward x.
        double out0 = verticalLegX(loops.get(0), EDGE);
        double out1 = verticalLegX(loops.get(1), EDGE);
        assertTrue(out1 > out0, "the second lane nests strictly further out: " + out0 + " vs " + out1);
        // Both labels render, VERTICALLY SEPARATED by at least a line slot (stacked upward above
        // the lane-0 exit leg — attach-independent, so a SHORT box clamping the attach nudges
        // together can never collapse the two labels; caught by eye on the BrewShot capture).
        double top0 = labelTopY(loops.get(0));
        double top1 = labelTopY(loops.get(1));
        assertTrue(Math.abs(top0 - top1) >= 10,
            "stacked loop labels sit at least a line apart: " + top0 + " vs " + top1);
        assertAllGeometryInside(laid);
        // Playback belt: the first loop's active frame really accents ITS legs (visible, not
        // overpainted — with disjoint geometry the accent coordinates exist in exactly one group).
        List<String> frames = Sirentide.renderFrames(
            "classDiagram\n  class A\n  A --> A : first\n  A <|-- A : second\n");
        assertTrue(frames.get(0).contains("stroke=\"#e8590c\""),
            "frame 0 accents the first loop");
    }

    @Test
    void multipleErSelfRelationsTakeDistinctLanes() {
        LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  A ||--o{ A : first\n  A ||--|| A : second\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(2, loops.size(), "two self-relations → two edge groups");
        Set<String> first = legSignatures(loops.get(0), ER_EDGE);
        Set<String> second = legSignatures(loops.get(1), ER_EDGE);
        assertFalse(first.isEmpty() || second.isEmpty(), "both loops route real legs");
        for (String sig : second) {
            assertFalse(first.contains(sig), "lane 1 leg coincides with a lane 0 leg: " + sig);
        }
        assertTrue(verticalLegX(loops.get(1), ER_EDGE) > verticalLegX(loops.get(0), ER_EDGE),
            "the second lane nests strictly further out");
        assertAllGeometryInside(laid);
    }

    // -- 3b) THREE+ lanes: no positive-length collinear overlap, labels pairwise separated --------
    // Lattice r3 (seq 227): legSignatures compares WHOLE-line strings, so two legs sharing an
    // origin and y but differing in outer endpoint have different signatures while still
    // overpainting most of their length. This oracle rejects ANY positive-length collinear
    // intersection between different edge groups — the actual playback-overpaint condition
    // (frames re-emit the one layout recolour-only, so static disjointness is playback
    // disjointness). The sizing pass now grows a multi-lane box so attach nudges never clamp
    // two lanes together; these fixtures fail on the clamp-collapsed geometry.

    @Test
    void threeClassSelfLoopLanesNeverRunCollinearLegs() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A --> A\n  A --> A\n  A --> A\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(3, loops.size(), "three self-relations → three edge groups");
        assertNoCollinearOverlapAcrossGroups(loops, EDGE);
        assertAllGeometryInside(laid);
    }

    @Test
    void threeErSelfLoopLanesNeverRunCollinearLegs() {
        LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  A ||--o{ A : first\n  A ||--|| A : second\n  A ||--o| A : third\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(3, loops.size(), "three self-relations → three edge groups");
        assertNoCollinearOverlapAcrossGroups(loops, ER_EDGE);
        assertAllGeometryInside(laid);
    }

    @Test
    void fourClassSelfLoopLabelsStayPairwiseSeparated() {
        // Lattice r3's four-lane probe: labels collapsed at the ascent floor (lane 2 and lane 3
        // glyph boxes overpainting almost completely). With the multi-lane box growth the label
        // stack never reaches the floor — every pair of loop-label glyph boxes must be DISJOINT.
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A --> A : first\n  A --> A : second\n"
                + "  A --> A : third\n  A --> A : fourth\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(4, loops.size(), "four self-relations → four edge groups");
        List<double[]> boxes = new ArrayList<>();   // {minX, minY, maxX, maxY} per label
        for (Group g : loops) {
            List<double[]> pts = new ArrayList<>();
            g.members().stream().filter(s -> s instanceof GlyphRun)
                .forEach(s -> pathPoints(((GlyphRun) s).pathD(), pts));
            assertFalse(pts.isEmpty(), "every loop label renders glyphs");
            double minX = pts.stream().mapToDouble(p -> p[0]).min().orElseThrow();
            double minY = pts.stream().mapToDouble(p -> p[1]).min().orElseThrow();
            double maxX = pts.stream().mapToDouble(p -> p[0]).max().orElseThrow();
            double maxY = pts.stream().mapToDouble(p -> p[1]).max().orElseThrow();
            boxes.add(new double[] {minX, minY, maxX, maxY});
        }
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                double[] a = boxes.get(i);
                double[] b = boxes.get(j);
                boolean disjoint = a[2] < b[0] || b[2] < a[0] || a[3] < b[1] || b[3] < a[1];
                assertTrue(disjoint, "loop labels " + i + " and " + j + " overlap: "
                    + java.util.Arrays.toString(a) + " vs " + java.util.Arrays.toString(b));
            }
        }
        assertNoCollinearOverlapAcrossGroups(loops, EDGE);
        assertAllGeometryInside(laid);
    }

    /// Rejects any POSITIVE-LENGTH collinear intersection between edge-coloured legs of DIFFERENT
    /// groups (axis-aligned segments — the only leg shapes a rectilinear loop emits). Two legs on
    /// the same horizontal/vertical line (within 1px — legs are 1.5px-wide strokes) must not share
    /// more than a point of their spans.
    private static void assertNoCollinearOverlapAcrossGroups(List<Group> groups, String edgeStroke) {
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                for (Shape sa : groups.get(i).members()) {
                    if (!(sa instanceof Line a) || !edgeStroke.equals(a.stroke())) {
                        continue;
                    }
                    for (Shape sb : groups.get(j).members()) {
                        if (!(sb instanceof Line b) || !edgeStroke.equals(b.stroke())) {
                            continue;
                        }
                        double overlap = collinearOverlapLen(a, b);
                        assertTrue(overlap <= 0.01, "groups " + i + " and " + j
                            + " share a collinear leg run of length " + overlap + ": " + a + " vs " + b);
                    }
                }
            }
        }
    }

    /// Length of the collinear overlap between two axis-aligned segments (0 when not collinear or
    /// merely touching at a point).
    private static double collinearOverlapLen(Line a, Line b) {
        boolean aH = near(a.y1(), a.y2(), 1e-9);
        boolean bH = near(b.y1(), b.y2(), 1e-9);
        boolean aV = near(a.x1(), a.x2(), 1e-9);
        boolean bV = near(b.x1(), b.x2(), 1e-9);
        if (aH && bH && near(a.y1(), b.y1(), 1.0)) {
            double lo = Math.max(Math.min(a.x1(), a.x2()), Math.min(b.x1(), b.x2()));
            double hi = Math.min(Math.max(a.x1(), a.x2()), Math.max(b.x1(), b.x2()));
            return Math.max(0, hi - lo);
        }
        if (aV && bV && near(a.x1(), b.x1(), 1.0)) {
            double lo = Math.max(Math.min(a.y1(), a.y2()), Math.min(b.y1(), b.y2()));
            double hi = Math.min(Math.max(a.y1(), a.y2()), Math.max(b.y1(), b.y2()));
            return Math.max(0, hi - lo);
        }
        return 0;
    }

    // -- 4) class marker ownership follows the authored operand ----------------------------------

    @Test
    void classSelfLoopMarkerHonorsTheAuthoredOperandSide() {
        // INHERITANCE (`A <|-- A`) is a markerAtLeft kind → the hollow triangle caps the TOP attach
        // (the LEFT operand's end — the exact mapping ER uses for the left cardinality). ASSOCIATION
        // (`B --> B`) marks the right operand → the open arrow caps the BOTTOM attach. Before this
        // fix EVERY kind capped the bottom, so the inheritance case fails by name on the old code.
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  class B\n  A <|-- A\n  B --> B\n"));
        List<Rect> boxes = boxRects(laid, SirentideRole.CLASS);
        List<Group> loops = edgeGroups(laid);
        assertEquals(2, loops.size(), "two self-relations → two edge groups");

        // Lane-0 attach points (0.3·h and 0.7·h) for each box.
        Rect a = boxes.get(0);
        Rect b = boxes.get(1);
        double aTop = a.y() + a.height() * 0.3;
        double aBottom = a.y() + a.height() * 0.7;
        double bTop = b.y() + b.height() * 0.3;
        double bBottom = b.y() + b.height() * 0.7;

        // The inheritance triangle (3 marker-coloured lines) has its TIP exactly on A's right
        // border at the TOP attach — and touches the bottom attach nowhere…
        List<Line> triangle = markerLines(loops.get(0));
        assertEquals(3, triangle.size(), "inheritance → hollow triangle (3 marker lines)");
        double aRight = a.x() + a.width();
        assertTrue(hasEndpointAt(triangle, aRight, aTop),
            "the whole/parent marker's tip sits on the border at the TOP attach ("
                + aRight + "," + aTop + "): " + triangle);
        assertTrue(triangle.stream().noneMatch(l -> near(l.y1(), aBottom, 1) || near(l.y2(), aBottom, 1)),
            "no triangle line sits at the bottom attach (the old always-bottom bug)");

        // …and the association arrow (2 marker-coloured lines) has its tip at B's BOTTOM attach.
        List<Line> arrow = markerLines(loops.get(1));
        assertEquals(2, arrow.size(), "association → open arrow (2 marker lines)");
        double bRight = b.x() + b.width();
        assertTrue(hasEndpointAt(arrow, bRight, bBottom),
            "the arrow marker's tip sits on the border at the BOTTOM attach ("
                + bRight + "," + bBottom + "): " + arrow);
        assertTrue(arrow.stream().noneMatch(l -> near(l.y1(), bTop, 1) || near(l.y2(), bTop, 1)),
            "no arrow line sits at the top attach");
    }

    // -- 5) tall math labels: ascent/descent participate in canvas growth ------------------------

    @Test
    void tallMathSelfLoopLabelGrowsTheCanvasForItsDescent() {
        // A fake fragment with an exaggerated DESCENT (60px below the baseline at label size): the
        // old growth ignored label metrics entirely, so the fragment fell past the canvas bottom.
        com.sirentide.api.MathFragmentRenderer fake = (latex, size) ->
            java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<g transform=\"scale(0.5 0.5)\"><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>",
                40, 12, 60));
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A --> A : $x$\n"), fake);
        // POSITIVE control: the fragment actually landed as a MathBox in the loop group.
        MathBox mb = edgeGroups(laid).get(0).members().stream()
            .filter(s -> s instanceof MathBox).map(s -> (MathBox) s)
            .findFirst().orElseThrow(() -> new AssertionError("the math label rendered as a MathBox"));
        // Baseline sits below the top edge by at least the ascent, and the canvas grew to hold the
        // full descent below the baseline (the vertical half of finding 1).
        assertTrue(mb.y() >= 12, "the label baseline clears its ascent below the top edge: " + mb.y());
        assertTrue(laid.height() >= mb.y() + 60,
            "the canvas holds the fragment's 60px descent: height=" + laid.height()
                + " baseline=" + mb.y());
    }

    // -- helpers ----------------------------------------------------------------------------------

    /// All EDGE-role groups, in emit (= relation) order.
    private static List<Group> edgeGroups(LaidOut laid) {
        return laid.shapes().stream()
            .filter(s -> s instanceof Group g && g.anchor().role() == SirentideRole.EDGE)
            .map(s -> (Group) s)
            .toList();
    }

    /// The background rects of every CLASS/ENTITY box, in emit order.
    private static List<Rect> boxRects(LaidOut laid, SirentideRole role) {
        List<Rect> out = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            if (s instanceof Group g && g.anchor().role() == role) {
                g.members().stream().filter(m -> m instanceof Rect).map(m -> (Rect) m)
                    .findFirst().ifPresent(out::add);
            }
        }
        return out;
    }

    /// EDGE-coloured legs of a loop group as coordinate signatures (dash segments included).
    private static Set<String> legSignatures(Group g, String edgeStroke) {
        Set<String> sigs = new HashSet<>();
        for (Shape s : g.members()) {
            if (s instanceof Line l && edgeStroke.equals(l.stroke())) {
                sigs.add(l.x1() + "," + l.y1() + "→" + l.x2() + "," + l.y2());
            }
        }
        return sigs;
    }

    /// The x of the loop's outermost VERTICAL leg (an edge-coloured line with x1 == x2).
    private static double verticalLegX(Group g, String edgeStroke) {
        return g.members().stream()
            .filter(s -> s instanceof Line l && edgeStroke.equals(l.stroke()) && near(l.x1(), l.x2(), 1e-6))
            .mapToDouble(s -> ((Line) s).x1())
            .max().orElseThrow(() -> new AssertionError("the loop has a vertical leg"));
    }

    /// The top edge (min y) of a loop group's label glyphs — for label-separation pins.
    private static double labelTopY(Group g) {
        List<double[]> pts = new ArrayList<>();
        g.members().stream().filter(s -> s instanceof GlyphRun)
            .forEach(s -> pathPoints(((GlyphRun) s).pathD(), pts));
        return pts.stream().mapToDouble(p -> p[1]).min()
            .orElseThrow(() -> new AssertionError("the loop label rendered glyphs"));
    }

    /// Marker-coloured lines of a loop group.
    private static List<Line> markerLines(Group g) {
        return g.members().stream()
            .filter(s -> s instanceof Line l && MK.equals(l.stroke()))
            .map(s -> (Line) s)
            .toList();
    }

    /// EVERY coordinate a group's leaf geometry touches: line endpoints, rect corners, and every
    /// point in a glyph/marker path's `d` (absolute M/L/Q commands — coordinate pairs throughout).
    private static List<double[]> groupPoints(Group g) {
        List<double[]> pts = new ArrayList<>();
        collect(g, pts);
        return pts;
    }

    private static void collect(Shape s, List<double[]> pts) {
        switch (s) {
            case Line l -> {
                pts.add(new double[] {l.x1(), l.y1()});
                pts.add(new double[] {l.x2(), l.y2()});
            }
            case Rect r -> {
                pts.add(new double[] {r.x(), r.y()});
                pts.add(new double[] {r.x() + r.width(), r.y() + r.height()});
            }
            case GlyphRun gr -> pathPoints(gr.pathD(), pts);
            case Path p -> pathPoints(p.d(), pts);
            case MathBox b -> pts.add(new double[] {b.x(), b.y()});
            case Group grp -> grp.members().forEach(m -> collect(m, pts));
            default -> { }
        }
    }

    /// Parses an absolute M/L/Q/Z path (the only commands the font oracle and marker builders emit)
    /// into its coordinate pairs. An unexpected command letter fails loudly rather than skipping.
    private static void pathPoints(String d, List<double[]> pts) {
        List<Double> nums = new ArrayList<>();
        for (String tok : d.trim().split("[\\s,]+")) {
            if (tok.isEmpty()) {
                continue;
            }
            if (tok.equals("M") || tok.equals("L") || tok.equals("Q") || tok.equals("Z")) {
                continue;
            }
            if (tok.length() == 1 && Character.isLetter(tok.charAt(0))) {
                fail("unexpected path command '" + tok + "' in: " + d);
            }
            nums.add(Double.parseDouble(tok));
        }
        assertEquals(0, nums.size() % 2, "path coordinates come in x,y pairs: " + d);
        for (int i = 0; i < nums.size(); i += 2) {
            pts.add(new double[] {nums.get(i), nums.get(i + 1)});
        }
    }

    /// Bounds EVERY leaf coordinate in the scene (all groups + strays) by the viewBox.
    private static void assertAllGeometryInside(LaidOut laid) {
        List<double[]> pts = new ArrayList<>();
        laid.shapes().forEach(s -> collect(s, pts));
        assertFalse(pts.isEmpty(), "the scene has real geometry");
        for (double[] p : pts) {
            assertTrue(p[0] >= 0 && p[0] <= laid.width(),
                "x=" + p[0] + " escapes the viewBox width " + laid.width());
            assertTrue(p[1] >= 0 && p[1] <= laid.height(),
                "y=" + p[1] + " escapes the viewBox height " + laid.height());
        }
    }

    private static boolean inside(Rect b, double x, double y) {
        return x > b.x() + 1e-6 && x < b.x() + b.width() - 1e-6
            && y > b.y() + 1e-6 && y < b.y() + b.height() - 1e-6;
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    /// True iff some line has an endpoint at exactly (x, y) — the marker-tip test (tips are placed
    /// verbatim at the border attach, so exact-within-epsilon is the right strength).
    private static boolean hasEndpointAt(List<Line> lines, double x, double y) {
        return lines.stream().anyMatch(l ->
            (near(l.x1(), x, 1e-6) && near(l.y1(), y, 1e-6))
                || (near(l.x2(), x, 1e-6) && near(l.y2(), y, 1e-6)));
    }
}
