package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
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
    private static final String ER_MK = "#0f766e";
    private static final String ACCENT = "#e8590c";      // Emphasis.ACCENT (play-through active)
    private static final double ACTIVE_WIDTH_MULT = 2.0; // Emphasis.ACTIVE_WIDTH_MULT     // ER cardinality marker glyph colour

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
            boxes.add(labelBbox(g));   // GlyphRun AND MathBox — see labelBbox (sirentide/768 F2)
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

    // -- 3c) each self-loop label is ASSOCIATED WITH ITS OWN LOOP (Marlow sirentide/761) -------------
    // The defect properties, each a receipt. On the OLD placement every loop label stacked into a
    // detached staircase BLOCK beyond the outermost leg, centred on the box middle: nothing tied a
    // label to the loop it names, so a reader could only guess. The fix is Y-ASSOCIATION — one
    // constant label column past the outermost leg (x cannot associate: a label runs up to MAX_LABEL_W,
    // many lane pitches wide, so an "inside its own lane" band would cross the outer legs), with each
    // baseline riding ITS OWN loop's top horizontal leg.

    /// PROPERTY 1 — per-loop Y-ASSOCIATION (Marlow sirentide/761), pinned against the EMITTED artifact:
    /// the expected baseline is derived from the loop's OWN top-leg Line shape plus the bundled font's
    /// ascent, never from the layout's baseline helper (that would be a tautology — the helper could
    /// place labels anywhere and still "agree with itself"). Two halves, both load-bearing:
    ///
    ///   a. every label's x-band starts past the OUTERMOST vertical leg — clear of every lane line
    ///      whatever the label's width (this half also held on the old staircase);
    ///   b. every label's BASELINE sits on its OWN loop's top horizontal leg, optically centred
    ///      (leg y + ascent·0.35) — the half the old placement failed, where every baseline keyed off
    ///      the box CENTRE and lane k's label could sit a whole stack away from lane k's leg.
    ///
    /// The baseline is recovered from the artifact by translation: the emitted glyph run for a label
    /// is that same string's outline placed at the baseline, so `emitted glyph top − the outline's own
    /// top at baseline 0` IS the baseline the layout chose. Held for the class AND ER twins, which
    /// must not drift.
    @Test
    void eachClassSelfLoopLabelBaselineRidesItsOwnLoopsTopLeg() {
        assertLabelsRideTheirOwnTopLegs(
            ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
                "classDiagram\n  class A\n  A --> A : first\n  A --> A : second\n"
                    + "  A --> A : third\n")),
            EDGE, List.of("first", "second", "third"), ClassDiagramLayout.EDGE_LABEL_SIZE);
    }

    @Test
    void eachErSelfLoopLabelBaselineRidesItsOwnLoopsTopLeg() {
        assertLabelsRideTheirOwnTopLegs(
            ErDiagramLayout.layout((ErDiagram) DslParser.parse(
                "erDiagram\n  A ||--o{ A : first\n  A ||--|| A : second\n  A ||--o| A : third\n")),
            ER_EDGE, List.of("first", "second", "third"), ErDiagramLayout.EDGE_LABEL_SIZE);
    }

    /// The property-1 oracle. `labels` are the authored (un-ellipsized, so re-measurable) loop labels
    /// in lane order — group i is lane i, relations emit in authored order.
    private static void assertLabelsRideTheirOwnTopLegs(LaidOut laid, String edgeStroke,
                                                        List<String> labels, double labelSize) {
        List<Group> loops = edgeGroups(laid);
        assertEquals(labels.size(), loops.size(), "one edge group per self-relation");
        double asc = FontMetrics.bundled().ascent(labelSize);
        // The METRIC FLOOR's minimum separation for two plain labels of this size: the upper
        // label's descent + the lower label's ascent + the band gap. Property 1 claims exact
        // association, which only holds while the attach step exceeds THIS — so the check below is
        // stated against the floor actually in force, not a stale constant.
        double band = FontMetrics.bundled().descent(labelSize) + asc + 2;
        double outermostLeg = loops.stream()
            .mapToDouble(g -> verticalLegX(g, edgeStroke)).max().orElseThrow();
        double prevBaseline = Double.NaN;
        double firstMinX = Double.NaN;
        for (int k = 0; k < loops.size(); k++) {
            Group g = loops.get(k);
            double[] b = labelBbox(g);   // positive control: throws if the lane rendered no glyphs
            // (a) clear of EVERY leg — the label column sits past the outermost lane line.
            assertTrue(b[0] > outermostLeg + 1e-6,
                "lane " + k + " label starts past the outermost leg (minX=" + b[0]
                    + " > " + outermostLeg + ")");
            // …and it is ONE column: every lane's label starts at the same x, up to the first glyph's
            // own left side bearing (a couple of px). The retired x-staircase offset each lane by a
            // full SELF_LOOP_LANE (14px) — far outside this tolerance — so it fails here.
            if (k == 0) {
                firstMinX = b[0];
            } else {
                assertTrue(Math.abs(b[0] - firstMinX) < 3.0,
                    "every loop label shares ONE column past the outermost leg: lane " + k
                        + " starts at " + b[0] + ", lane 0 at " + firstMinX);
            }
            // (b) the association: this label's baseline rides THIS loop's own top leg.
            double legY = topLegY(g, edgeStroke);
            double baseline = emittedBaseline(g, labels.get(k), labelSize);
            assertEquals(legY + asc * 0.35, baseline, 0.5,
                "lane " + k + " label must ride ITS OWN loop's top leg at y=" + legY
                    + " (baseline " + baseline + ")");
            if (k > 0) {
                // Lane k's top leg sits an attach-step ABOVE lane k−1's, so its label rides higher —
                // by strictly MORE than one occupied band here, which is exactly why the metric
                // floor (pinned separately) stays INERT on a real, sizing-grown box and the
                // association above is exact rather than degraded.
                assertTrue(prevBaseline - baseline > band + 1e-6,
                    "lane " + k + " rides above lane " + (k - 1) + " by more than one occupied band: "
                        + prevBaseline + " → " + baseline);
            }
            prevBaseline = baseline;
        }
        assertAllGeometryInside(laid);
    }

    /// The BASELINE the layout gave a loop group's label, recovered from the EMITTED glyph outline:
    /// the emitter draws `label` as {@code FontMetrics.textPathD(label, originX, baseline, size)}, and
    /// that path is a pure translation of the same string's outline at baseline 0 — so the emitted
    /// glyph top minus the baseline-0 outline's top is the baseline. Derived from the artifact + the
    /// shared font, NOT from the layout's placement code.
    private static double emittedBaseline(Group g, String label, double size) {
        List<double[]> ref = new ArrayList<>();
        pathPoints(FontMetrics.bundled().textPathD(label, 0, 0, size), ref);
        assertFalse(ref.isEmpty(), "the label '" + label + "' has a measurable outline");
        double refTop = ref.stream().mapToDouble(p -> p[1]).min().orElseThrow();
        return labelBbox(g)[1] - refTop;
    }

    /// The y of a self-loop's TOP horizontal leg: the highest edge-coloured horizontal Line in the
    /// group (a rectilinear loop emits exactly two — the top attach's run out, the bottom's run back).
    private static double topLegY(Group g, String edgeStroke) {
        return g.members().stream()
            .filter(s -> s instanceof Line l && edgeStroke.equals(l.stroke())
                && near(l.y1(), l.y2(), 1e-6))
            .mapToDouble(s -> ((Line) s).y1())
            .min().orElseThrow(() -> new AssertionError("the loop has a horizontal leg"));
    }

    /// PROPERTY 1b — DEGRADATION 2, the METRIC FLOOR, pinned directly on the shared computation. On a
    /// short box every lane's attach CLAMPS to the same border inset, so all the ideals coincide and
    /// only the floor can separate the labels; on a real (sizing-grown) box the floor is inert and
    /// association is exact, which property 1 pins. The expectation is built from the MEASURED
    /// ascent/descent + the band gap, never from the helper, so the helper cannot agree with itself.
    ///
    /// The retired fixed slot budgeted a flat {@code EDGE_LABEL_SIZE + 2} per lane whatever the label
    /// measured (Marlow sirentide/768 F2). Two halves here:
    ///   a. UNIFORM metrics — consecutive baselines sit exactly {@code descent + ascent + 2} apart,
    ///      lane k above lane k−1 (leg order), so the occupied bands touch with a 2px gap;
    ///   b. a TALL lane — one label with a 20px ascent and 20px descent pushes ONLY the lane below it
    ///      further down, by that label's own descent + the lower label's ascent + 2. A fixed slot
    ///      cannot express this, so this half fails by construction on the retired floor.
    ///
    /// Non-vacuity for (a): the one-lane call lands exactly where the four-lane call puts its
    /// outermost (unfloored) lane — proof the ideals really did collide and the separation below is
    /// the FLOOR at work, not the attach step.
    @Test
    void collidingSelfLoopLabelIdealsDegradeToDisjointMetricBands() {
        double asc = FontMetrics.bundled().ascent(ClassDiagramLayout.EDGE_LABEL_SIZE);
        double desc = FontMetrics.bundled().descent(ClassDiagramLayout.EDGE_LABEL_SIZE);
        assertFlooredStack(ClassDiagramLayout.loopLabelBaselines(0, 12, uniformMetrics(4, asc, desc)),
            ClassDiagramLayout.loopLabelBaselines(0, 12, uniformMetrics(1, asc, desc))[0],
            asc, desc, "class");
        assertFlooredStack(ErDiagramLayout.loopLabelBaselines(0, 12, uniformMetrics(4, asc, desc)),
            ErDiagramLayout.loopLabelBaselines(0, 12, uniformMetrics(1, asc, desc))[0],
            asc, desc, "ER");
        // (b) the tall-lane half: lane 3 (outermost, top of the stack) carries a 20/20 fragment.
        double[][] tall = uniformMetrics(4, asc, desc);
        tall[3] = new double[] {20, 20};
        assertTallLanePushesTheLaneBelow(ClassDiagramLayout.loopLabelBaselines(0, 12, tall),
            asc, desc, "class");
        assertTallLanePushesTheLaneBelow(ErDiagramLayout.loopLabelBaselines(0, 12, tall),
            asc, desc, "ER");
    }

    /// `laneCount` lanes, each label measuring the same ascent/descent — the shape the retired
    /// fixed-slot floor implicitly assumed for every label.
    private static double[][] uniformMetrics(int laneCount, double asc, double desc) {
        double[][] m = new double[laneCount][];
        for (int k = 0; k < laneCount; k++) {
            m[k] = new double[] {asc, desc};
        }
        return m;
    }

    private static void assertFlooredStack(double[] baselines, double soleLaneBaseline,
                                           double asc, double desc, String what) {
        double band = desc + asc + 2;   // upper label's descent + lower label's ascent + the gap
        assertEquals(4, baselines.length, what + ": one baseline per lane");
        assertEquals(soleLaneBaseline, baselines[baselines.length - 1], 1e-9,
            what + ": on a short box every lane's ideal clamps to the same y — the outermost lane "
                + "sits on that shared ideal, so the separation below really is the FLOOR at work");
        for (int k = 1; k < baselines.length; k++) {
            assertEquals(band, baselines[k - 1] - baselines[k], 1e-9,
                what + ": collided lanes " + (k - 1) + " and " + k + " sit exactly one OCCUPIED-BAND "
                    + "apart (descent + ascent + gap), lane " + k + " above (leg order)");
        }
    }

    /// Lane 3 measures 20/20 while lanes 0-2 measure the plain font. The floor must charge lane 2 the
    /// TALL label's descent (20) plus its own ascent plus the gap — strictly more than the uniform
    /// separation the other pairs get, and more than any fixed slot could budget.
    private static void assertTallLanePushesTheLaneBelow(double[] baselines, double asc, double desc,
                                                          String what) {
        assertEquals(20 + asc + 2, baselines[2] - baselines[3], 1e-9,
            what + ": the 20px-descent lane pushes the lane below it by ITS OWN descent, not a slot");
        assertEquals(desc + asc + 2, baselines[1] - baselines[2], 1e-9,
            what + ": the untouched pairs keep the plain-metric separation");
        assertEquals(desc + asc + 2, baselines[0] - baselines[1], 1e-9,
            what + ": the untouched pairs keep the plain-metric separation");
    }

    /// PROPERTY 2 — pairwise label disjointness, the ER manages/uses RED: a self-loop label and a
    /// right-NEIGHBOUR edge's own midpoint label used to physically overlap at the top-right. Centring
    /// the loop label on the table's vertical middle drops it clear below the neighbour's centre-band
    /// label. Both labels render (glyph boxes exist) — the disjointness is the load-bearing half.
    @Test
    void erSelfLoopLabelDoesNotCollideWithANeighborEdgeLabel() {
        LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  EMPLOYEE ||--o{ EMPLOYEE : manages\n  EMPLOYEE ||--|| DESK : uses\n"));
        List<Group> edges = edgeGroups(laid);   // group 0 = self-loop (manages), 1 = neighbour (uses)
        assertEquals(2, edges.size(), "two relationships → two edge groups");
        double[] manages = labelBbox(edges.get(0));
        double[] uses = labelBbox(edges.get(1));
        assertTrue(footprintsDisjoint(manages, uses),
            "the self-loop label and the neighbour edge label overlap: "
                + java.util.Arrays.toString(manages) + " vs " + java.util.Arrays.toString(uses));
        assertAllGeometryInside(laid);
    }

    /// PROPERTY 3 — labels disjoint from every MARKER footprint. The marker-stack case (diamond + two
    /// triangles stacked on A's border) with three labels: each label's glyph box clears every marker
    /// footprint (marker lines + filled diamond paths), because the labels ride lanes OUT past the
    /// markers (which cap at x1 + markerLength ≤ x1 + SELF_LOOP_OUT).
    @Test
    void selfLoopLabelsNeverTouchAMarkerFootprint() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A <|-- A : inherits\n  A <|-- A : also\n  A *-- A : owns\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(3, loops.size(), "three self-relations → three edge groups");
        List<double[]> markers = new ArrayList<>();
        for (Group g : loops) {
            double[] mf = markerFootprint(g, MK);   // positive control: each marker rendered
            assertTrue(mf != null, "each loop rendered its marker footprint");
            markers.add(mf);
        }
        for (int k = 0; k < loops.size(); k++) {
            double[] lab = labelBbox(loops.get(k));
            for (int m = 0; m < markers.size(); m++) {
                assertTrue(footprintsDisjoint(lab, markers.get(m)),
                    "lane " + k + " label overlaps marker " + m + ": "
                        + java.util.Arrays.toString(lab) + " vs "
                        + java.util.Arrays.toString(markers.get(m)));
            }
        }
        assertAllGeometryInside(laid);
    }

    /// PROPERTY 4 — labels disjoint from every LANE LINE and every BOX, with canvas GROWTH. A labeled
    /// multi-lane loop WITH a right neighbour: every label glyph sits past all legs (min label x >
    /// every lane-line x — legs all live at x ≤ the outermost leg), no glyph lands inside the loop's
    /// own box or the neighbour box, and every coordinate stays inside the grown viewBox.
    @Test
    void selfLoopLabelsClearLaneLinesAndEveryBoxWithinTheGrownCanvas() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  class B\n  A --> A : alpha\n  A --> A : beta\n  A --> B\n"));
        List<Group> loops = edgeGroups(laid);
        List<Rect> boxes = boxRects(laid, SirentideRole.CLASS);
        assertEquals(2, boxes.size(), "both class boxes placed");
        List<double[]> labelPts = new ArrayList<>();
        for (Group g : loops) {
            g.members().stream().filter(s -> s instanceof GlyphRun)
                .forEach(s -> pathPoints(((GlyphRun) s).pathD(), labelPts));
        }
        assertFalse(labelPts.isEmpty(), "loop labels rendered glyphs");   // positive control
        // No label glyph falls inside ANY box (own node or the neighbour).
        for (double[] p : labelPts) {
            for (Rect b : boxes) {
                assertFalse(inside(b, p[0], p[1]),
                    "a loop label glyph at " + p[0] + "," + p[1] + " falls inside a box at x="
                        + b.x() + ".." + (b.x() + b.width()));
            }
        }
        // Every label glyph sits strictly past EVERY self-loop LANE line. A rectilinear loop's lane
        // is bounded on the right by its VERTICAL leg (x1 == x2), and every horizontal leg ends at
        // x ≤ that vertical leg — so the outermost vertical leg is the rightmost lane coordinate. (The
        // A→B neighbour edge is a straight, non-vertical line and carries no lane leg, so it is
        // correctly excluded.) The label column sits beyond it, so none can cross or sit on a loop leg.
        double maxLaneX = Double.NEGATIVE_INFINITY;
        for (Group g : loops) {
            for (Shape s : g.members()) {
                if (s instanceof Line l && EDGE.equals(l.stroke()) && Math.abs(l.x1() - l.x2()) < 1e-6) {
                    maxLaneX = Math.max(maxLaneX, l.x1());
                }
            }
        }
        double minLabelX = labelPts.stream().mapToDouble(p -> p[0]).min().orElseThrow();
        assertTrue(minLabelX > maxLaneX + 1e-6,
            "every loop label clears every self-loop lane leg: min label x " + minLabelX
                + " must exceed the outermost vertical leg x " + maxLaneX);
        assertAllGeometryInside(laid);   // the canvas grew to hold the label column
    }

    /// PROPERTY 6 — labels keep a CLEAR CORRIDOR from every NON-LOOP edge segment crossing their
    /// x-band (eye-pass finding, plan 64cf1bae). The g5 gallery shape (class-self-loops-stacked,
    /// Charles's named "optimize text placement" case) PASSED every disjointness receipt above while
    /// FAILING the eye: the straight A→B neighbour edge threaded RIGHT BETWEEN "refines itself" and
    /// "delegates", so both read as labels ON the A→B edge — misattribution, worse than crowding.
    /// Non-overlap is not unambiguity. The pin: for every self-loop label, every non-loop edge
    /// segment crossing the label's x-band keeps at least SELF_LOOP_EDGE_CLEARANCE of vertical gap
    /// from the label's glyph box (the layouts shift the whole fan — ordering preserved — to honour
    /// it; the clearance constant is IMPORTED from the layout so oracle and corridor cannot drift).
    /// The ER twin (the g4 er-self-loop shape) is held to the same corridor: "manages" used to sit
    /// touching the uses edge. RED on the pre-fix placement for both fixtures.
    @Test
    void selfLoopLabelFanKeepsClearOfNeighbourEdgeCorridors() {
        // The exact g5 fixture.
        LaidOut classLaid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  class B\n  A <|-- A : refines itself\n"
                + "  A --> A : delegates\n  A --> B\n"));
        assertLoopLabelsClearNonLoopEdges(classLaid, EDGE,
            ClassDiagramLayout.SELF_LOOP_EDGE_CLEARANCE);
        assertAllGeometryInside(classLaid);
        // The ER twin (the g4 shape).
        LaidOut erLaid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  EMPLOYEE ||--o{ EMPLOYEE : manages\n  EMPLOYEE ||--|| DESK : uses\n"));
        assertLoopLabelsClearNonLoopEdges(erLaid, ER_EDGE,
            ErDiagramLayout.SELF_LOOP_EDGE_CLEARANCE);
        assertAllGeometryInside(erLaid);
    }

    /// The property-6 oracle: every SELF-LOOP label's glyph box keeps ≥ `clearance` of vertical gap
    /// from the y-interval of every NON-LOOP edge segment (dash segments included; a bent route's
    /// legs are each a segment) over the part of that segment inside the label's x-band. Segments
    /// outside the band constrain nothing (the corridor is about what passes THROUGH the label's
    /// column). Carries its own positive control: at least one non-loop segment must actually cross
    /// some label's x-band — the fan shifts vertically, so the crossing survives the fix and the
    /// clearance assertions can never go vacuously green.
    private static void assertLoopLabelsClearNonLoopEdges(LaidOut laid, String edgeStroke,
                                                          double clearance) {
        List<Group> edges = edgeGroups(laid);
        List<Group> loops = edges.stream().filter(SelfLoopGeometryTest::isSelfLoopGroup).toList();
        List<Group> nonLoops = edges.stream().filter(g -> !isSelfLoopGroup(g)).toList();
        assertFalse(loops.isEmpty(), "the fixture renders at least one self-loop");
        assertFalse(nonLoops.isEmpty(), "the fixture renders a non-loop neighbour edge");
        boolean crossed = false;
        for (Group loop : loops) {
            double[] lab = labelBbox(loop);   // positive control: the loop label rendered glyphs
            for (Group other : nonLoops) {
                for (Shape s : other.members()) {
                    if (!(s instanceof Line l) || !edgeStroke.equals(l.stroke())) {
                        continue;   // markers/labels are covered by properties 2-4; this pins EDGES
                    }
                    double xLo = Math.max(Math.min(l.x1(), l.x2()), lab[0]);
                    double xHi = Math.min(Math.max(l.x1(), l.x2()), lab[2]);
                    if (xHi < xLo) {
                        continue;   // this segment never enters the label's x-band
                    }
                    crossed = true;
                    double[] yr = segmentYRange(l, xLo, xHi);
                    double gap = Math.max(yr[0] - lab[3], lab[1] - yr[1]);
                    assertTrue(gap >= clearance - 1e-6,
                        "a non-loop edge segment passes within " + gap + "px (< " + clearance
                            + ") of a self-loop label over x=[" + xLo + ".." + xHi + "]: label "
                            + java.util.Arrays.toString(lab) + " vs segment (" + l.x1() + ","
                            + l.y1() + ")-(" + l.x2() + "," + l.y2() + ")");
                }
            }
        }
        assertTrue(crossed,
            "positive control: some non-loop segment crosses a label's x-band in this fixture");
    }

    /// The y-range a segment sweeps over `[xLo, xHi]` (a sub-range of its x-span). A vertical
    /// segment contributes its full y-span.
    private static double[] segmentYRange(Line l, double xLo, double xHi) {
        double dx = l.x2() - l.x1();
        if (Math.abs(dx) < 1e-9) {
            return new double[] {Math.min(l.y1(), l.y2()), Math.max(l.y1(), l.y2())};
        }
        double ya = l.y1() + (xLo - l.x1()) / dx * (l.y2() - l.y1());
        double yb = l.y1() + (xHi - l.x1()) / dx * (l.y2() - l.y1());
        return new double[] {Math.min(ya, yb), Math.max(ya, yb)};
    }

    /// A self-loop's edge group id is `left + "-" + right` with left == right (`A-A`,
    /// `EMPLOYEE-EMPLOYEE`) — the two halves around the middle dash are equal.
    ///
    /// …EXCEPT that {@link AnchorAssigner} uniquifies a repeated id by appending `-<k>`, so a node's
    /// SECOND self-relation is `A-A-1`. Matching only the bare form silently dropped every loop but
    /// the first — which is exactly how a two-lane fan could be half-checked and still look green
    /// (the corridor oracle below asserted only that SOME loop existed). So try the stripped form
    /// too, and never treat a plain `-<k>` id as decisive on its own.
    private static boolean isSelfLoopGroup(Group g) {
        String id = g.anchor().id();
        return halvesEqual(id) || halvesEqual(id.replaceFirst("-\\d+$", ""));
    }

    private static boolean halvesEqual(String id) {
        int h = id.length() / 2;
        return id.length() % 2 == 1 && id.charAt(h) == '-'
            && id.substring(0, h).equals(id.substring(h + 1));
    }

    /// The bounding box {minX, minY, maxX, maxY} of a loop group's LABEL ink (a positive control in
    /// itself — throws if the group rendered no label).
    ///
    /// BOTH label shapes are observed. Selecting GlyphRun only made every sweep built on this helper
    /// structurally BLIND to a MATH label — a `$…$` fragment emits a {@link MathBox}, not glyphs, so
    /// two colliding math labels produced NO points at all and the disjointness loops ran over an
    /// empty set (Marlow sirentide/768 F2). A MathBox contributes its pen point `(x, baseline)`; its
    /// full occupied band is not recoverable from the shape alone (the ink lives inside opaque
    /// renderer markup), which is why the band receipt above reads the fragment's declared
    /// ascent/descent instead. Here the pen point is enough to make the instrument non-blind.
    private static double[] labelBbox(Group g) {
        List<double[]> pts = new ArrayList<>();
        for (Shape s : g.members()) {
            if (s instanceof GlyphRun gr) {
                pathPoints(gr.pathD(), pts);
            } else if (s instanceof MathBox b) {
                pts.add(new double[] {b.x(), b.y()});
            }
        }
        assertFalse(pts.isEmpty(), "the loop group rendered a label");
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    /// Two bboxes are DISJOINT when they share no interior (a shared edge at the touch tolerance is not
    /// an overlap) — the negation of {@link #footprintsOverlap}.
    private static boolean footprintsDisjoint(double[] a, double[] b) {
        return !footprintsOverlap(a, b, 1e-6);
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

    // -- 4b) marker FOOTPRINTS disjoint across groups (sirentide 275) -----------------------------
    // The old collinear-leg oracle skipped marker-coloured lines AND every filled Path, so adjacent
    // self-loop MARKER glyphs that overprinted were invisible to it. These pin the actual finding: with
    // the footprint-derived attach pitch no two same-side markers overlap, so no later FUTURE relation
    // can repaint part of an earlier ACTIVE one. Each carries a positive control (the marker rendered).

    @Test
    void multipleClassInheritanceSelfLoopMarkersDoNotOverlap() {
        // Three same-side inheritance self-loops → three 16px triangles at the TOP attach. The old flat
        // 12px pitch (< 16) overprinted them; the derived pitch (2·MAX_MARKER_HALF + clearance) separates.
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A <|-- A\n  A <|-- A\n  A <|-- A\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(3, loops.size(), "three self-relations → three edge groups");
        for (Group g : loops) { // POSITIVE control: every group rendered its 3-line triangle (never vacuous)
            assertEquals(3, markerLines(g).size(), "inheritance renders a 3-line triangle marker");
        }
        Rect boxA = boxRects(laid, SirentideRole.CLASS).get(0);
        assertMarkerFootprintsDisjointAcrossGroups(loops, MK, boxA.y() + boxA.height() / 2);
        assertAllGeometryInside(laid);
    }

    @Test
    void multipleClassCompositionSelfLoopMarkersDoNotOverlap() {
        // Filled-path branch: two composition self-loops → two filled 14px diamond Paths. The old oracle
        // never examined filled Paths at all; this exercises path-vs-path footprint disjointness.
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A *-- A\n  A *-- A\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(2, loops.size(), "two self-relations → two edge groups");
        for (Group g : loops) { // POSITIVE control: each rendered a filled diamond Path
            assertTrue(g.members().stream().anyMatch(s -> s instanceof Path),
                "composition renders a filled diamond Path marker");
        }
        Rect boxA = boxRects(laid, SirentideRole.CLASS).get(0);
        assertMarkerFootprintsDisjointAcrossGroups(loops, MK, boxA.y() + boxA.height() / 2);
        assertAllGeometryInside(laid);
    }

    @Test
    void multipleErSelfLoopMarkersDoNotOverlap() {
        // ER: two same-box self-loops → 18px crow-feet + 14px bars at adjacent attaches, both over the old
        // 12px pitch. The derived pitch (2·MAX_MARKER_HALF + clearance, MAX=crow-foot 9) separates them.
        LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  A ||--o{ A : first\n  A ||--o{ A : second\n"));
        List<Group> loops = edgeGroups(laid);
        assertEquals(2, loops.size(), "two self-relations → two edge groups");
        for (Group g : loops) { // POSITIVE control: each rendered its cardinality marker
            assertTrue(markerFootprint(g, ER_MK) != null, "each ER self-loop rendered a cardinality marker");
        }
        Rect boxA = boxRects(laid, SirentideRole.ENTITY).get(0);
        assertMarkerFootprintsDisjointAcrossGroups(loops, ER_MK, boxA.y() + boxA.height() / 2, true);
        assertAllGeometryInside(laid);
    }

    @Test
    void theMarkerFootprintOracleActuallyDetectsAnOverlap() {
        // Non-vacuity of the guard itself: two 16px-tall footprints only 12px apart (the OLD pitch) MUST
        // register as overlapping, and the derived pitch (>= 2·MAX_MARKER_HALF) MUST separate them — so
        // the render-based tests above can never pass vacuously because the detector silently never fires.
        double[] lower = {100, 0, 110, 16};        // y in [0, 16]
        double[] twelveApart = {100, 12, 110, 28}; // y in [12, 28] → 4px overlap with `lower`
        assertTrue(footprintsOverlap(lower, twelveApart, 1e-6),
            "the oracle detects a 12px (old-pitch) overlap of 16px markers");
        double derived = 2 * ClassDiagramLayout.MAX_MARKER_HALF; // the pitch clears at least this
        double[] derivedApart = {100, derived, 110, derived + 16};
        assertFalse(footprintsOverlap(lower, derivedApart, 1e-6),
            "2·MAX_MARKER_HALF separation removes the overlap the derived pitch is built to clear");
    }

    @Test
    void stackedSelfLoopMarkersStayDisjointAcrossPlaybackFrames() {
        // Requirement 3 (Lattice 275): pin a REAL playback frame where no later FUTURE marker intersects
        // the ACTIVE one. Frame GEOMETRY is invariant — renderFrames recolours the one layout, SvgEmitter
        // only changes fills/strokes by group state — so the static marker disjointness proven above IS the
        // playback-overpaint guarantee (the same principle the leg oracle records at
        // assertNoCollinearOverlapAcrossGroups: "static disjointness is playback disjointness"). This pins
        // both halves: (a) a real frame recolours the marker groups active-vs-future, and (b) the markers
        // are geometrically disjoint, so the recolour can never overpaint one marker with another.
        String dsl = "classDiagram\n  class A\n  A <|-- A\n  A <|-- A\n";
        // (a) real playback: frame 0 accents the ACTIVE group (the static render carries no accent).
        List<String> frames = Sirentide.renderFrames(dsl);
        assertTrue(frames.get(0).contains("#e8590c"), "frame 0 accents the active self-loop marker group");
        assertFalse(Sirentide.render(dsl).contains("#e8590c"), "the static render has no active accent");
        // (a2) MARKER-SPECIFIC recolour (Lattice 281 F2): "the frame contains the accent SOMEWHERE" is
        // satisfied by an accented leg or label even if marker recolouring broke entirely. Pin it to the
        // marker groups themselves: in frame 0 the ACTIVE group's own <g> must carry the accent AND the
        // doubled stroke width, and the FUTURE group's <g> must NOT carry the accent at all.
        String activeGroup = groupBlock(frames.get(0), 0);
        String futureGroup = groupBlock(frames.get(0), 1);
        assertTrue(activeGroup.contains(ACCENT),
            "frame 0: the ACTIVE self-loop group must carry the accent on its own shapes");
        assertFalse(futureGroup.contains(ACCENT),
            "frame 0: a FUTURE self-loop group must NOT carry the active accent");
        // sir288 F3: "accent SOMEWHERE in the group + width-2 SOMEWHERE in the group" is satisfiable
        // by an accented, promoted LEG while marker recolouring is entirely broken — proven by
        // exact-tip mutation (marker Lines stripped of the ACTIVE accent survived the full suite).
        // The claim is about the MARKERS, so it is asserted on the marker elements THEMSELVES,
        // per element: frame geometry is invariant across frames (only colours/widths change), so
        // each marker line in a group's ACTIVE render pairs by coordinates with the SAME line in a
        // non-active render of the SAME group — and on that pair, ACTIVE must carry the accent AND
        // exactly DOUBLE the width the non-active render paints (base widths differ per primitive,
        // so a fixed "2" is wrong; the ratio is the contract). Both groups are held to it: group 0
        // via frame0-ACTIVE vs frame1-DONE, group 1 via frame1-ACTIVE vs frame0-FUTURE — and the
        // non-active side must never carry the accent. (Markers are the short lines: every class
        // marker footprint is <= 17px while the shortest leg is SELF_LOOP_OUT = 30px.)
        assertMarkerLinesPromotedPerElement(groupBlock(frames.get(0), 0), groupBlock(frames.get(1), 0),
            "group 0 (frame 0 ACTIVE vs frame 1 DONE)");
        assertMarkerLinesPromotedPerElement(groupBlock(frames.get(1), 1), groupBlock(frames.get(0), 1),
            "group 1 (frame 1 ACTIVE vs frame 0 FUTURE)");

        // (b) geometry (identical in every frame): the two triangle footprints are disjoint, measured as
        // PAINTED bounds at the ACTIVE 2x stroke — the width a viewer actually sees during play-through.
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(dsl));
        List<Group> loops = edgeGroups(laid);
        Rect boxA = boxRects(laid, SirentideRole.CLASS).get(0);
        assertMarkerFootprintsDisjointAcrossGroups(loops, MK, boxA.y() + boxA.height() / 2);
        assertPaintedMarkersDisjointAtActiveWidth(loops, MK, boxA.y() + boxA.height() / 2);
    }

    /// Lattice 281 F2, the aggregation gap: seq 275 required cross-group marker coverage for AGGREGATION
    /// as well as inheritance/composition/ER. Aggregation (`o--`) draws a hollow diamond, a different
    /// primitive from the inheritance triangle, so it needs its own case rather than inheriting the
    /// triangle's proof. Repeated on ONE class so the two markers land on the same side.
    @Test
    void repeatedAggregationSelfLoopMarkersStayDisjoint() {
        String dsl = "classDiagram\n  class A\n  A o-- A : first\n  A o-- A : second\n";
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(dsl));
        List<Group> loops = edgeGroups(laid);
        assertEquals(2, loops.size(), "two aggregation self-relations → two edge groups");
        for (Group g : loops) { // POSITIVE control: each rendered its diamond
            assertTrue(markerFootprint(g, MK) != null, "each aggregation self-loop rendered its marker");
        }
        Rect boxA = boxRects(laid, SirentideRole.CLASS).get(0);
        assertMarkerFootprintsDisjointAcrossGroups(loops, MK, boxA.y() + boxA.height() / 2);
        assertPaintedMarkersDisjointAtActiveWidth(loops, MK, boxA.y() + boxA.height() / 2);
        assertAllGeometryInside(laid);
    }

    /// Disjointness measured at the ACTIVE playback stroke width, where the painted marks are fattest.
    private static void assertPaintedMarkersDisjointAtActiveWidth(
            List<Group> groups, String markerColor, double splitY) {
        for (double[] band : new double[][] {
                {Double.NEGATIVE_INFINITY, splitY}, {splitY, Double.POSITIVE_INFINITY}}) {
            List<double[]> boxes = new ArrayList<>();
            for (Group g : groups) {
                boxes.add(paintedMarkerFootprint(g, markerColor, band[0], band[1], ACTIVE_WIDTH_MULT));
            }
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (boxes.get(i) == null || boxes.get(j) == null) {
                        continue;
                    }
                    assertFalse(footprintsOverlap(boxes.get(i), boxes.get(j), 1e-6),
                        "markers of groups " + i + " and " + j + " overlap once PAINTED at the active "
                            + ACTIVE_WIDTH_MULT + "x stroke: "
                            + java.util.Arrays.toString(boxes.get(i)) + " vs "
                            + java.util.Arrays.toString(boxes.get(j)));
                }
            }
        }
    }

    /// The `<g data-sirentide-seq=k>` block for step k, so a frame assertion can be made about ONE
    /// group's own shapes rather than about the whole document.
    /// sir288 F3(b): the composition marker is a FILLED {@code <path>}, a different colour sink from
    /// line strokes — the line assertions above cannot see it, and an exact-tip mutation that left the
    /// filled diamond unaccented survived the full suite. A real playback frame must accent the ACTIVE
    /// group's marker path FILL itself and leave the FUTURE group's diamond unaccented. The diamond is
    /// identified structurally (the only 4-vertex closed path in a self-loop group — label glyphs are
    /// many-command outlines), so an accented LABEL cannot satisfy this vacuously.
    @Test
    void playbackAccentsTheFilledCompositionMarkerPathItself() {
        String dsl = "classDiagram\n  class A\n  A *-- A : first\n  A *-- A : second\n";
        List<String> frames = Sirentide.renderFrames(dsl);
        String activeGroup = groupBlock(frames.get(0), 0);
        String futureGroup = groupBlock(frames.get(0), 1);
        List<String> activeDiamonds = diamondPaths(activeGroup);
        List<String> futureDiamonds = diamondPaths(futureGroup);
        assertFalse(activeDiamonds.isEmpty(), "the ACTIVE composition loop emits its filled diamond");
        assertFalse(futureDiamonds.isEmpty(), "the FUTURE composition loop emits its filled diamond");
        for (String el : activeDiamonds) {
            assertTrue(el.contains("fill=\"" + ACCENT + "\""),
                "frame 0: the ACTIVE composition diamond's own fill must take the accent: " + el);
        }
        for (String el : futureDiamonds) {
            assertFalse(el.contains(ACCENT),
                "frame 0: a FUTURE composition diamond must stay unaccented: " + el);
        }
    }

    /// The 4-vertex closed marker paths in a group block ({@code M .. L .. L .. L .. Z}).
    private static List<String> diamondPaths(String group) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<path [^>]*/>").matcher(group);
        while (m.find()) {
            String el = m.group();
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("d=\"([^\"]+)\"").matcher(el);
            if (d.find()) {
                String path = d.group(1);
                long vertices = path.chars().filter(ch -> ch == 'L').count();
                if (path.trim().endsWith("Z") && vertices == 3) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    /// Every {@code <line>} element in a group block whose painted length is marker-scale (< 20px —
    /// see the caller's leg-vs-marker separation argument), keyed by its coordinate signature.
    private static java.util.Map<String, String> markerLengthLines(String group) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" y2=\"([-0-9.]+)\"[^>]*/>")
            .matcher(group);
        while (m.find()) {
            double dx = Double.parseDouble(m.group(3)) - Double.parseDouble(m.group(1));
            double dy = Double.parseDouble(m.group(4)) - Double.parseDouble(m.group(2));
            if (Math.hypot(dx, dy) < 20) {
                out.put(m.group(1) + "," + m.group(2) + "," + m.group(3) + "," + m.group(4), m.group());
            }
        }
        return out;
    }

    /// sir288 F3, the per-element promotion contract: pair each marker line of a group's ACTIVE render
    /// with the coordinate-identical line in a NON-ACTIVE render of the same group, then assert on the
    /// pair — ACTIVE carries the accent and exactly 2x the non-active width; non-active carries no
    /// accent. Any marker line that loses its promotion (while a leg keeps the group-level accent
    /// alive) fails HERE, on its own element.
    private static void assertMarkerLinesPromotedPerElement(String activeRender, String otherRender,
                                                            String what) {
        java.util.Map<String, String> active = markerLengthLines(activeRender);
        java.util.Map<String, String> other = markerLengthLines(otherRender);
        assertFalse(active.isEmpty(), what + ": the active render has marker-length lines");
        assertEquals(active.keySet(), other.keySet(),
            what + ": frame geometry is invariant — the same marker lines exist in both renders");
        for (var e : active.entrySet()) {
            String activeEl = e.getValue();
            String otherEl = other.get(e.getKey());
            assertTrue(activeEl.contains(ACCENT),
                what + ": an ACTIVE marker line must itself carry the accent: " + activeEl);
            assertFalse(otherEl.contains(ACCENT),
                what + ": a non-active marker line must not carry the accent: " + otherEl);
            assertEquals(strokeWidthOf(otherEl) * 2, strokeWidthOf(activeEl), 1e-6,
                what + ": ACTIVE promotes the SAME element to exactly double its base width: "
                    + activeEl + " vs " + otherEl);
        }
    }

    private static double strokeWidthOf(String lineElement) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("stroke-width=\"([-0-9.]+)\"").matcher(lineElement);
        return m.find() ? Double.parseDouble(m.group(1)) : 1.0;
    }

    private static String groupBlock(String svg, int seq) {
        String open = "data-sirentide-seq=\"" + seq + "\"";
        int at = svg.indexOf(open);
        assertTrue(at >= 0, "frame has no group for seq " + seq);
        int start = svg.lastIndexOf("<g", at);
        int end = svg.indexOf("</g>", at);
        assertTrue(start >= 0 && end > start, "malformed group block for seq " + seq);
        return svg.substring(start, end);
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

    /// PROPERTY 7 — the COMPOSED contract, now with PER-LABEL selectivity (Marlow sirentide/768 F1,
    /// re-cut for sirentide/770 amendment v3). Properties 1 and 6 each held alone: association was
    /// pinned on a loop-ONLY fixture where nothing binds, and clearance was pinned without ever
    /// asking where the labels ended up relative to their legs. Between them the composition was
    /// unpinned. (The corridor oracle was itself half-blind: it matched loop groups by the bare `A-A`
    /// anchor id, so a node's SECOND self-relation — uniquified to `A-A-1` — was silently skipped.
    /// Fixed in isSelfLoopGroup.)
    ///
    /// The ROUND-4 version of this receipt asserted a UNIFORM offset — every label the same distance
    /// off its leg, "the fan moved as a SET". That was an honest statement of what the solver did and
    /// a wrong statement of what the contract should be: it pinned the very behaviour Marlow's 770
    /// discriminator rejects. It is replaced, not relaxed, and the replacement is STRICTLY STRONGER
    /// on this same fixture — a uniform offset now FAILS it.
    ///
    /// Asserted on the FULLY laid-out artifact — the exact `class-self-loops-stacked` gallery DSL
    /// (two self-loops plus the crossing A→B neighbour) and its ER analogue — with WHICH labels
    /// conflict DERIVED from the emitted shapes, never hardcoded:
    ///
    ///   a. CLEARANCE (hard): no label's box comes within SELF_LOOP_EDGE_CLEARANCE of the neighbour
    ///      edge's swept band over its x-band;
    ///   b. SELECTIVITY: a label whose OWN corridor is clear at its leg ideal AND whose ideal
    ///      survives the disjointness floor left by the label above it sits EXACTLY on that ideal;
    ///      every other label cleared its corridor and stayed adjacent-feasible;
    ///   c. ORDER: label order matches leg order — the outer lane's leg is higher and so is its label;
    ///   d. DISJOINTNESS: adjacent occupied bands stay apart by descent + ascent + the band gap.
    ///
    /// NON-VACUITY runs BOTH ways on this fixture: at least one label must be at its exact ideal
    /// (selectivity is live, not a fan that happened to need no shift) and at least one must have
    /// moved (the corridor degradation is live, not a fixture where nothing binds).
    @Test
    void theStackedFixtureComposesLegAssociationWithPerLabelCorridorClearance() {
        // The exact class-self-loops-stacked gallery DSL.
        assertPerLabelSelectivityOnTheStack(ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
                "classDiagram\n  class A\n  class B\n  A <|-- A : refines itself\n"
                    + "  A --> A : delegates\n  A --> B\n")),
            EDGE, SirentideRole.CLASS, ClassDiagramLayout.SELF_LOOP_EDGE_CLEARANCE,
            ClassDiagramLayout.EDGE_LABEL_SIZE, List.of("refines itself", "delegates"), "class");
        // The ER analogue: the stacked-loop gallery shape with a crossing neighbour edge added.
        assertPerLabelSelectivityOnTheStack(ErDiagramLayout.layout((ErDiagram) DslParser.parse(
                "erDiagram\n  A ||--o{ A : first\n  A ||--o{ A : second\n  A ||--|| B : uses\n")),
            ER_EDGE, SirentideRole.ENTITY, ErDiagramLayout.SELF_LOOP_EDGE_CLEARANCE,
            ErDiagramLayout.EDGE_LABEL_SIZE, List.of("first", "second"), "ER");
    }

    /// The composed oracle. `labels` are the authored loop labels in LANE order (group i = lane i);
    /// LEG order is the reverse, since lane k's top leg sits an attach step above lane k−1's.
    ///
    /// The corridor each label is judged against is derived from its own INK bounding box, which is a
    /// SUBSET of the advance box the layout reserves. That conservatism runs in the receipt's favour:
    /// an ink-derived "this label is blocked at its ideal" is a fortiori true of the wider advance
    /// box, and an ink-derived "this label is clear" makes the exact-ideal assertion in (b) apply to
    /// MORE labels, never fewer. If the two ever disagreed the receipt would go RED, which is the
    /// direction drift should fail in.
    private static void assertPerLabelSelectivityOnTheStack(LaidOut laid, String edgeStroke,
                                                            SirentideRole boxRole, double clearance,
                                                            double labelSize, List<String> labels,
                                                            String what) {
        // (a) the HARD corridor constraint, on the emitted artifact.
        assertLoopLabelsClearNonLoopEdges(laid, edgeStroke, clearance);

        List<Group> loops = edgeGroups(laid).stream()
            .filter(SelfLoopGeometryTest::isSelfLoopGroup).toList();
        int m = labels.size();
        assertEquals(m, loops.size(), what + ": every self-relation renders as its own loop group");
        double asc = FontMetrics.bundled().ascent(labelSize);
        double desc = FontMetrics.bundled().descent(labelSize);
        double lift = asc * 0.35;
        double[] legY = new double[m];
        double[] baseline = new double[m];
        double[] ideal = new double[m];
        List<List<double[]>> ban = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            legY[k] = topLegY(loops.get(k), edgeStroke);
            baseline[k] = emittedBaseline(loops.get(k), labels.get(k), labelSize);
            ideal[k] = legY[k] + lift;
            double[] ink = labelBbox(loops.get(k));
            assertNoBoxCrossesTheLabelColumn(laid, boxRole, ink[0],
                ink[0] + FontMetrics.bundled().runWidth(labels.get(k), labelSize),
                what + " lane " + k);
            ban.add(forbiddenBaselines(laid, edgeStroke, clearance, ink[0], ink[2], asc, desc));
        }
        // (b) SELECTIVITY, walked in LEG order (outermost lane first) so each label is judged against
        // the FINAL position of the label above it, exactly as the solver committed them.
        int atIdeal = 0;
        int movedOff = 0;
        for (int k = m - 1; k >= 0; k--) {
            double floor = asc + 2;                       // the canvas-top ascent floor
            if (k < m - 1) {
                floor = Math.max(floor, baseline[k + 1] + desc + asc + 2);   // BAND_GAP = 2
            }
            boolean corridorClear = !blocked(ideal[k], ban.get(k));
            if (corridorClear && ideal[k] >= floor - 1e-6) {
                assertEquals(ideal[k], baseline[k], 0.5,
                    what + ": lane " + k + " (\"" + labels.get(k) + "\") has a CLEAR corridor at its "
                        + "leg ideal " + ideal[k] + " and room under lane " + (k + 1) + ", so it must "
                        + "ride its own leg exactly — it emitted " + baseline[k] + ". A whole-fan "
                        + "shift is exactly what this forbids (Marlow sirentide/770).");
                atIdeal++;
            } else {
                assertFalse(blocked(baseline[k], ban.get(k)),
                    what + ": lane " + k + " (\"" + labels.get(k) + "\") is bound at its ideal "
                        + ideal[k] + ", so its emitted baseline " + baseline[k] + " must CLEAR its "
                        + "corridor " + banToString(ban.get(k)));
                assertTrue(baseline[k] >= floor - 1e-6,
                    what + ": lane " + k + " must stay below the disjointness floor " + floor
                        + " (emitted " + baseline[k] + ")");
                movedOff++;
            }
        }
        assertTrue(atIdeal > 0,
            what + ": NON-VACUITY — this fixture must contain a label whose own corridor is clear, "
                + "so that per-label SELECTIVITY is actually exercised");
        assertTrue(movedOff > 0,
            what + ": NON-VACUITY — this fixture must contain a CONFLICTED label, so that the "
                + "corridor degradation is actually exercised");
        // (c) order and (d) disjointness, on the metric bands rather than glyph bboxes.
        for (int k = 1; k < m; k++) {
            assertTrue(legY[k] < legY[k - 1] - 1e-6,
                what + ": lane " + k + "'s top leg sits above lane " + (k - 1) + "'s");
            assertTrue(baseline[k] < baseline[k - 1] - 1e-6,
                what + ": lane " + k + "'s label must stay ABOVE lane " + (k - 1) + "'s — label order "
                    + "must match leg order whatever the corridor cost");
            assertTrue(baseline[k] + desc <= baseline[k - 1] - asc + 1e-6,
                what + ": lanes " + (k - 1) + " and " + k + " occupy overlapping bands ("
                    + (baseline[k] + desc) + " vs " + (baseline[k - 1] - asc) + ")");
        }
        for (int i = 0; i < loops.size(); i++) {
            for (int j = i + 1; j < loops.size(); j++) {
                assertTrue(footprintsDisjoint(labelBbox(loops.get(i)), labelBbox(loops.get(j))),
                    what + ": loop labels " + i + " and " + j + " overlap");
            }
        }
        assertAllGeometryInside(laid);
    }

    /// DEGRADATION 2 — the METRIC FLOOR, pinned on the case the retired fixed slot could not see
    /// (Marlow sirentide/768 F2). The old floor budgeted a flat {@code EDGE_LABEL_SIZE + 2} = 12px
    /// per lane regardless of what the label MEASURED, so two loop labels whose fragments rise 20px
    /// above the baseline and fall 20px below it emitted OVERLAPPING occupied bands — Marlow's probe
    /// read [32.667, 72.667] against [14.667, 54.667], a 22px overlap — while every receipt in this
    /// file stayed green, because the pairwise-disjointness sweep selected GlyphRun only and a math
    /// label emits a MathBox.
    ///
    /// The oracle is the AUTHORITATIVE occupied band, not a glyph bbox: a MathBox is placed at the
    /// pen `(x, baseline)` and the fragment's own contract says it rises `heightPx` above that
    /// baseline and falls `depthPx` below it, so `[y − heightPx, y + depthPx]` is exactly the ink the
    /// fragment claims. Both twins, because they must not drift. Non-vacuity: each lane's fragment
    /// must actually have landed as a MathBox, and the fixture's 40px band is WIDER than the class
    /// attach step (18px) and the ER one (20px), so nothing but a metrics-aware floor can separate
    /// them.
    @Test
    void twoTallMathSelfLoopLabelsEmitDisjointOccupiedBands() {
        double asc = 20;
        double desc = 20;
        com.sirentide.api.MathFragmentRenderer fake = (latex, size) ->
            java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<g transform=\"scale(0.5 0.5)\"><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>",
                40, asc, desc));
        assertMathLoopLabelBandsDisjoint(ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
            "classDiagram\n  class A\n  A --> A : $x$\n  A --> A : $y$\n"), fake), asc, desc, "class");
        assertMathLoopLabelBandsDisjoint(ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  A ||--o{ A : $x$\n  A ||--|| A : $y$\n"), fake), asc, desc, "ER");
    }

    /// The degradation-2 oracle: every self-loop label's occupied band, read off its emitted MathBox
    /// and the fragment's declared ascent/descent, is pairwise DISJOINT — and the canvas holds it.
    private static void assertMathLoopLabelBandsDisjoint(LaidOut laid, double asc, double desc,
                                                         String what) {
        List<Group> loops = edgeGroups(laid).stream()
            .filter(SelfLoopGeometryTest::isSelfLoopGroup).toList();
        assertEquals(2, loops.size(), what + ": two self-relations → two loop groups");
        List<double[]> bands = new ArrayList<>();
        for (int k = 0; k < loops.size(); k++) {
            MathBox mb = loops.get(k).members().stream()
                .filter(s -> s instanceof MathBox).map(s -> (MathBox) s)
                .findFirst().orElseThrow(() -> new AssertionError(
                    what + ": lane's math label rendered as a MathBox"));   // positive control
            assertTrue(laid.height() >= mb.y() + desc - 1e-6,
                what + ": the canvas holds lane " + k + "'s descent: height=" + laid.height()
                    + " band bottom=" + (mb.y() + desc));
            bands.add(new double[] {mb.y() - asc, mb.y() + desc});
        }
        for (int i = 0; i < bands.size(); i++) {
            for (int j = i + 1; j < bands.size(); j++) {
                double[] a = bands.get(i);
                double[] b = bands.get(j);
                assertTrue(a[1] <= b[0] + 1e-6 || b[1] <= a[0] + 1e-6,
                    what + ": loop labels " + i + " and " + j + " occupy OVERLAPPING bands "
                        + java.util.Arrays.toString(a) + " vs " + java.util.Arrays.toString(b)
                        + " — the floor must consume each label's real ascent+descent");
            }
        }
    }

    // -- 5b) corridor avoidance is PER-LABEL (Marlow sirentide/770, amendment v3) -----------------

    /// The exaggerated-metrics fragment body every fake renderer below hands back — the ink is
    /// irrelevant, the DECLARED box is what the layout measures.
    private static final String FRAG =
        "<g transform=\"scale(0.5 0.5)\"><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>";

    /// PROPERTY 7 — CORRIDOR AVOIDANCE IS PER-LABEL: an UNCONFLICTED label keeps its own leg
    /// (Marlow sirentide/770, amendment v3 — his discriminator, promoted verbatim to a receipt).
    ///
    /// The retired solver shifted the node's whole fan by ONE scalar dy, so a single conflicted label
    /// dragged every SIBLING off its leg — including labels whose own corridor was already clear.
    /// Marlow's construction isolates exactly that: both loop labels ride the SAME x column, so the
    /// only thing that differs is how far right each one REACHES. The 120px label (lane 0) reaches
    /// into the part of the column where the A→B edge sweeps; the 8px label (lane 1, the OUTERMOST
    /// lane) stops 8px in, where the same edge passes nowhere near its band. One is conflicted, one
    /// is not, and there is no whole-fan dy that can be right for both.
    ///
    /// What is asserted, all of it derived from the EMITTED artifact (legs from the loop's horizontal
    /// Lines exactly as property 1's oracle reads them; corridors from the neighbour edge's own
    /// emitted Line; baselines from the MathBox pen point, which IS the baseline by the fragment
    /// coordinate contract):
    ///
    ///   NON-VACUITY, both directions — the wide label's leg ideal is really INSIDE its forbidden
    ///     band and the narrow label's really is OUTSIDE its own. Without this the receipt could go
    ///     green on a fixture where nothing conflicts at all.
    ///   a. the CLEAR label sits EXACTLY on its own leg (≤ 0.5px) — the round-5 failure, inverted;
    ///   b. the CONFLICTED label clears its corridor, and does so MINIMALLY: its baseline lands ON
    ///      the corridor's lower escape boundary (its band top exactly kissing the clearance), and
    ///      the upward escape is unavailable at ANY distance because it would put the wide label
    ///      above the clear one's disjointness floor — i.e. the hard ORDER/DISJOINTNESS constraints,
    ///      not slack, are what sent it down;
    ///   c. ORDER and pairwise DISJOINTNESS survive.
    ///
    /// RED on the whole-fan solver: the clear label emitted 18.14px (class) / 16.78px (ER) off the
    /// leg it is supposed to ride, because its sibling's conflict moved the set.
    @Test
    void anUnconflictedSelfLoopLabelKeepsItsLegWhileItsSiblingClearsTheCorridor() {
        // `$w$` measures 120px wide, `$n$` 8px; both declare a box shorter than the text metrics, so
        // ascent/descent are the plain label metrics and WIDTH is the only difference between them.
        com.sirentide.api.MathFragmentRenderer widths = (latex, size) -> java.util.Optional.of(
            new com.sirentide.api.MathFragment(FRAG, latex.trim().equals("n") ? 8 : 120, 2, 1));
        assertPerLabelCorridorSelectivity(ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
                "classDiagram\n  class A\n  class B\n  A --> A : $w$\n  A --> A : $n$\n  A --> B\n"),
                widths),
            EDGE, SirentideRole.CLASS, ClassDiagramLayout.SELF_LOOP_EDGE_CLEARANCE,
            ClassDiagramLayout.EDGE_LABEL_SIZE, "class");
        assertPerLabelCorridorSelectivity(ErDiagramLayout.layout((ErDiagram) DslParser.parse(
                "erDiagram\n  A ||--o{ A : $w$\n  A ||--o{ A : $n$\n  A ||--|| B : uses\n"), widths),
            ER_EDGE, SirentideRole.ENTITY, ErDiagramLayout.SELF_LOOP_EDGE_CLEARANCE,
            ErDiagramLayout.EDGE_LABEL_SIZE, "ER");
    }

    /// Marlow's discriminator as an oracle. Lane 0 carries the 120px label, lane 1 the 8px one.
    private static void assertPerLabelCorridorSelectivity(LaidOut laid, String edgeStroke,
                                                          SirentideRole boxRole, double clearance,
                                                          double labelSize, String what) {
        List<Group> loops = edgeGroups(laid).stream()
            .filter(SelfLoopGeometryTest::isSelfLoopGroup).toList();
        assertEquals(2, loops.size(), what + ": two self-relations → two loop groups");
        double asc = FontMetrics.bundled().ascent(labelSize);
        double desc = FontMetrics.bundled().descent(labelSize);
        double lift = asc * 0.35;
        MathBox wide = mathLabel(loops.get(0), what + " lane 0 (the 120px label)");
        MathBox narrow = mathLabel(loops.get(1), what + " lane 1 (the 8px label)");
        double wideIdeal = topLegY(loops.get(0), edgeStroke) + lift;
        double narrowIdeal = topLegY(loops.get(1), edgeStroke) + lift;
        assertTrue(narrowIdeal < wideIdeal - 1e-6,
            what + ": lane 1 is the OUTER lane — its leg (and so its ideal) sits above lane 0's");
        assertEquals(wide.x(), narrow.x(), 1e-6,
            what + ": both labels ride the SAME x column — width is the only difference");
        // The corridor oracle below reads EDGE segments only, so it is sound exactly when no BOX
        // rectangle reaches into either label's x-band. Checked, never assumed.
        assertNoBoxCrossesTheLabelColumn(laid, boxRole, wide.x(), wide.x() + 120, what);
        List<double[]> wideBan = forbiddenBaselines(laid, edgeStroke, clearance,
            wide.x(), wide.x() + 120, asc, desc);
        List<double[]> narrowBan = forbiddenBaselines(laid, edgeStroke, clearance,
            narrow.x(), narrow.x() + 8, asc, desc);
        // NON-VACUITY, both directions: one label really is conflicted, the other really is not.
        assertTrue(blocked(wideIdeal, wideBan),
            what + ": NON-VACUITY — the 120px label's leg ideal " + wideIdeal + " must actually fall "
                + "inside its forbidden band " + banToString(wideBan));
        assertFalse(blocked(narrowIdeal, narrowBan),
            what + ": NON-VACUITY — the 8px label's OWN corridor must actually be CLEAR at its leg "
                + "ideal " + narrowIdeal + " (forbidden " + banToString(narrowBan) + ")");
        // (a) the clear label rides its own leg EXACTLY — the whole point of amendment v3.
        assertEquals(narrowIdeal, narrow.y(), 0.5,
            what + ": the 8px label's own corridor is CLEAR, so it must sit exactly on ITS leg ideal "
                + narrowIdeal + " — it emitted " + narrow.y() + " ("
                + Math.abs(narrow.y() - narrowIdeal) + "px off, dragged there by its sibling's "
                + "conflict under a whole-fan shift)");
        // (b) the conflicted label cleared its corridor, MINIMALLY.
        assertFalse(blocked(wide.y(), wideBan),
            what + ": the 120px label's emitted baseline " + wide.y() + " must clear its corridor "
                + banToString(wideBan));
        double floor = narrow.y() + desc + asc + 2;   // SELF_LOOP_LABEL_BAND_GAP = 2
        assertTrue(wide.y() >= floor - 1e-6,
            what + ": the 120px label must stay adjacent-feasible below the 8px one (floor " + floor
                + ", emitted " + wide.y() + ")");
        assertTrue(escapeUp(wideIdeal, wideBan) < floor - 1e-6,
            what + ": MINIMALITY premise — every corridor-clearing position ABOVE the ideal ("
                + escapeUp(wideIdeal, wideBan) + " and up) would break disjointness with the clear "
                + "label (floor " + floor + "), so DOWN is the only feasible direction");
        double down = escapeDown(wideIdeal, wideBan);
        assertEquals(down, wide.y(), 0.5,
            what + ": the 120px label must move the MINIMUM that clears its corridor — the lower "
                + "escape boundary " + down + ", not further (emitted " + wide.y() + ")");
        assertTrue(Math.abs(wide.y() - wideIdeal) <= (down - wideIdeal) + 1.0,
            what + ": the conflicted label's deviation is bounded by the corridor-clearing minimum");
        // (c) order + disjointness.
        assertTrue(narrow.y() + desc <= wide.y() - asc + 1e-6,
            what + ": the two occupied bands must stay disjoint (" + (narrow.y() + desc) + " vs "
                + (wide.y() - asc) + ")");
        assertAllGeometryInside(laid);
    }

    /// PROPERTY 7b — the CASCADE is contract (amendment v3). Per-label selectivity does NOT mean
    /// per-label independence: constraint 3 (disjointness) is HARD and outranks constraint 4 (leg
    /// alignment), so when a conflicted label's only escape is DOWNWARD it carries every label below
    /// it down with it. That is permitted — and therefore has to be PINNED, or "minimal" would be an
    /// unfalsifiable word. What is forbidden is moving the neighbour one px further than the cascade
    /// forces.
    ///
    /// The fixture makes the escape direction unambiguous rather than incidental: lane 1 carries a
    /// 120px-wide fragment declaring a 40px ascent AND a 40px descent, so its occupied band is TALLER
    /// than the corridor it has to miss. Clearing upward would need a baseline above the canvas-top
    /// ascent floor itself — arithmetically unavailable — so the only feasible side is below, and the
    /// 8px label on lane 0 must follow even though its OWN corridor never touched it.
    ///
    /// Pinned: (i) lane 1 really is blocked at its ideal and really has no upward escape; (ii) it
    /// lands ON the corridor's lower escape boundary; (iii) lane 0 lands at EXACTLY
    /// {@code lane1 + lane1.descent + lane0.ascent + bandGap} — the cascade minimum, to 0.5px;
    /// (iv) that position is genuinely off lane 0's own leg (the cascade is real) and is NOT itself
    /// blocked by lane 0's own corridor (so the number is the DISJOINTNESS cascade and nothing else);
    /// (v) the canvas grew to hold the whole thing.
    @Test
    void clearingAConflictedLabelDownwardCascadesToItsNeighbourByExactlyTheMinimum() {
        // `$t$`: 120px wide with a 40/40 box — a band taller than the corridor. `$n$`: 8px, plain.
        com.sirentide.api.MathFragmentRenderer fake = (latex, size) -> java.util.Optional.of(
            latex.trim().equals("t")
                ? new com.sirentide.api.MathFragment(FRAG, 120, 40, 40)
                : new com.sirentide.api.MathFragment(FRAG, 8, 2, 1));
        assertDownwardCascadeIsExactlyMinimal(ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
                "classDiagram\n  class A\n  class B\n  A --> A : $n$\n  A --> A : $t$\n  A --> B\n"),
                fake),
            EDGE, SirentideRole.CLASS, ClassDiagramLayout.SELF_LOOP_EDGE_CLEARANCE,
            ClassDiagramLayout.EDGE_LABEL_SIZE, "class");
        assertDownwardCascadeIsExactlyMinimal(ErDiagramLayout.layout((ErDiagram) DslParser.parse(
                "erDiagram\n  A ||--o{ A : $n$\n  A ||--o{ A : $t$\n  A ||--|| B : uses\n"), fake),
            ER_EDGE, SirentideRole.ENTITY, ErDiagramLayout.SELF_LOOP_EDGE_CLEARANCE,
            ErDiagramLayout.EDGE_LABEL_SIZE, "ER");
    }

    /// The cascade oracle. Lane 0 carries the 8px/plain label, lane 1 the 120px 40/40 one.
    private static void assertDownwardCascadeIsExactlyMinimal(LaidOut laid, String edgeStroke,
                                                              SirentideRole boxRole, double clearance,
                                                              double labelSize, String what) {
        List<Group> loops = edgeGroups(laid).stream()
            .filter(SelfLoopGeometryTest::isSelfLoopGroup).toList();
        assertEquals(2, loops.size(), what + ": two self-relations → two loop groups");
        double asc = FontMetrics.bundled().ascent(labelSize);
        double desc = FontMetrics.bundled().descent(labelSize);
        double lift = asc * 0.35;
        MathBox small = mathLabel(loops.get(0), what + " lane 0 (the 8px label)");
        MathBox tall = mathLabel(loops.get(1), what + " lane 1 (the 40/40 label)");
        double smallIdeal = topLegY(loops.get(0), edgeStroke) + lift;
        double tallIdeal = topLegY(loops.get(1), edgeStroke) + lift;
        assertNoBoxCrossesTheLabelColumn(laid, boxRole, tall.x(), tall.x() + 120, what);
        List<double[]> tallBan = forbiddenBaselines(laid, edgeStroke, clearance,
            tall.x(), tall.x() + 120, 40, 40);
        List<double[]> smallBan = forbiddenBaselines(laid, edgeStroke, clearance,
            small.x(), small.x() + 8, asc, desc);
        // (i) lane 1 is conflicted and has NO upward escape — its band is taller than the corridor,
        // so clearing upward would need a baseline above the canvas-top ascent floor.
        assertTrue(blocked(tallIdeal, tallBan),
            what + ": NON-VACUITY — the 40/40 label's ideal " + tallIdeal + " must fall inside its "
                + "forbidden band " + banToString(tallBan));
        assertTrue(escapeUp(tallIdeal, tallBan) < 40 + 2 - 1e-6,
            what + ": the upward escape " + escapeUp(tallIdeal, tallBan) + " must be unreachable "
                + "above the canvas-top ascent floor " + (40 + 2) + ", so DOWN is the only side");
        // (ii) it lands ON the lower escape boundary — the minimum move that clears the corridor.
        double down = escapeDown(tallIdeal, tallBan);
        assertEquals(down, tall.y(), 0.5,
            what + ": the 40/40 label must clear its corridor by the MINIMUM — the lower escape "
                + "boundary " + down + " (emitted " + tall.y() + ")");
        // (iii) lane 0 follows by EXACTLY the disjointness minimum, not one px more.
        double cascadeMinimum = tall.y() + 40 + asc + 2;   // tall descent + own ascent + BAND_GAP
        assertEquals(cascadeMinimum, small.y(), 0.5,
            what + ": the 8px label must move exactly the CASCADE MINIMUM " + cascadeMinimum
                + " forced by disjointness with the label above it — it emitted " + small.y());
        // (iv) the cascade is real, and it is the cascade and not the neighbour's own corridor.
        assertTrue(small.y() > smallIdeal + 1,
            what + ": NON-VACUITY — the 8px label must actually be pushed off its leg ideal "
                + smallIdeal + " (emitted " + small.y() + ")");
        assertFalse(blocked(small.y(), smallBan),
            what + ": the 8px label's landing spot must not be inside its OWN corridor "
                + banToString(smallBan) + " — otherwise (iii) would be pinning the wrong mechanism");
        assertEquals(small.y(), escapeDown(cascadeMinimum, smallBan), 0.5,
            what + ": the 8px label's own corridor must not push it below the cascade minimum");
        // (v) order, disjointness, and the grown canvas.
        assertTrue(tall.y() + 40 <= small.y() - asc + 1e-6,
            what + ": the occupied bands must stay disjoint (" + (tall.y() + 40) + " vs "
                + (small.y() - asc) + ")");
        assertTrue(laid.height() >= small.y() + desc,
            what + ": the canvas grew to hold the cascaded stack (height " + laid.height()
                + ", band bottom " + (small.y() + desc) + ")");
        assertAllGeometryInside(laid);
    }

    /// The MathBox a loop group emitted for its `$…$` label. Its pen `(x, y)` IS the label's origin
    /// and BASELINE by the {@link com.sirentide.api.MathFragment} coordinate contract, so this reads
    /// the placed baseline off the artifact without going near the layout's placement code.
    private static MathBox mathLabel(Group g, String what) {
        return g.members().stream().filter(s -> s instanceof MathBox).map(s -> (MathBox) s)
            .findFirst().orElseThrow(() -> new AssertionError(
                what + ": the math label rendered as a MathBox"));
    }

    /// The BASELINE positions a label of the given x-band and metrics may NOT take: for every
    /// NON-LOOP edge segment crossing `[x0, x1]`, the y-interval that segment SWEEPS over the
    /// crossing part, inflated by `clearance` and then by the label's own ascent/descent (a baseline
    /// inside the inflated interval is exactly a baseline whose occupied band comes within the
    /// clearance of the segment). Derived from the emitted Lines — the layout's own corridor
    /// computation is never consulted, so oracle and solver cannot agree with each other by
    /// construction.
    private static List<double[]> forbiddenBaselines(LaidOut laid, String edgeStroke,
                                                     double clearance, double x0, double x1,
                                                     double asc, double desc) {
        List<double[]> out = new ArrayList<>();
        for (Group g : edgeGroups(laid)) {
            if (isSelfLoopGroup(g)) {
                continue;
            }
            for (Shape s : g.members()) {
                if (!(s instanceof Line l) || !edgeStroke.equals(l.stroke())) {
                    continue;
                }
                double xLo = Math.max(Math.min(l.x1(), l.x2()), x0);
                double xHi = Math.min(Math.max(l.x1(), l.x2()), x1);
                if (xHi < xLo) {
                    continue;
                }
                double[] yr = segmentYRange(l, xLo, xHi);
                out.add(new double[] {yr[0] - clearance - desc, yr[1] + clearance + asc});
            }
        }
        return out;
    }

    /// True when `y` sits strictly inside one of the forbidden intervals.
    private static boolean blocked(double y, List<double[]> ban) {
        for (double[] b : ban) {
            if (y > b[0] + 1e-9 && y < b[1] - 1e-9) {
                return true;
            }
        }
        return false;
    }

    /// The smallest baseline ≥ `y` that clears every forbidden interval (walking DOWN out of the
    /// union component containing `y`).
    private static double escapeDown(double y, List<double[]> ban) {
        double cur = y;
        for (int i = 0; i <= ban.size(); i++) {
            boolean moved = false;
            for (double[] b : ban) {
                if (cur > b[0] + 1e-9 && cur < b[1] - 1e-9) {
                    cur = b[1];
                    moved = true;
                }
            }
            if (!moved) {
                return cur;
            }
        }
        return cur;
    }

    /// The largest baseline ≤ `y` that clears every forbidden interval (walking UP out of the union).
    private static double escapeUp(double y, List<double[]> ban) {
        double cur = y;
        for (int i = 0; i <= ban.size(); i++) {
            boolean moved = false;
            for (double[] b : ban) {
                if (cur > b[0] + 1e-9 && cur < b[1] - 1e-9) {
                    cur = b[0];
                    moved = true;
                }
            }
            if (!moved) {
                return cur;
            }
        }
        return cur;
    }

    private static String banToString(List<double[]> ban) {
        StringBuilder sb = new StringBuilder("{");
        for (double[] b : ban) {
            sb.append('[').append(b[0]).append(", ").append(b[1]).append(']');
        }
        return sb.append('}').toString();
    }

    /// The edge-only corridor oracle is sound only while no BOX rectangle reaches into the label
    /// column (the solver treats boxes as obstacles too). Assert it rather than assume it.
    private static void assertNoBoxCrossesTheLabelColumn(LaidOut laid, SirentideRole role,
                                                         double x0, double x1, String what) {
        for (Rect b : boxRects(laid, role)) {
            assertTrue(b.x() >= x1 - 1e-9 || b.x() + b.width() <= x0 + 1e-9,
                what + ": a box at x=" + b.x() + ".." + (b.x() + b.width()) + " reaches into the "
                    + "label column [" + x0 + ", " + x1 + "] — the edge-only corridor oracle would "
                    + "be incomplete");
        }
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

    // -- 6) a self-loop's own two ends never intersect each other (Lattice seq 281 F1) -------------

    /// The collision class every other test here is structurally BLIND to.
    ///
    /// The rest of this file compares markers across DIFFERENT groups and splits the top and bottom
    /// bands by the table midpoint. That instrument cannot see two ends of the SAME relationship
    /// colliding, and worse, it MIS-reports: a bottom crow-foot's upper prong reaches above the
    /// midpoint, so the band split files part of the bottom marker under "top" and the comparison
    /// becomes meaningless. My first attempt at this regression test used that instrument and passed
    /// with the production fix disabled — vacuously green.
    ///
    /// So this asserts the property directly on painted geometry, with no bands and no bboxes: NO TWO
    /// marker segments in the group may properly cross. Segments that share an endpoint are exempt —
    /// a crow-foot's three prongs converge at a point and a hollow ring chains end-to-end, both legal.
    /// A bbox oracle could not express this either: an exactly-one bar's bbox and a crow-foot's bbox
    /// legitimately overlap in x while the strokes stay disjoint.
    @Test
    void aSelfLoopsOwnEndMarkersNeverCrossEachOther() {
        // Every cardinality combo — ONE_OR_MANY included (sir288 F2: it was missing while the test
        // claimed the menu) — and each case carries the EXPECTED marker-segment count PER END, derived
        // from the authored cardinality's primitive anatomy (bar = 1 line, crow = 3, ring = 12).
        //
        // sir288 F2, why per-end: the old form pooled both ends and asserted >= 4 total — one
        // ZERO_OR_MANY end alone contributes 15 segments, so suppressing the OTHER end's marker
        // entirely stayed green, and the cross-end intersection loop then had nothing to catch. The
        // authored marker must be present AT ITS OWN END before cross-end disjointness means anything.
        record EndCounts(String dsl, int topSegments, int bottomSegments) {}
        EndCounts[] cases = {
            new EndCounts("erDiagram\n  A ||--o{ A\n", 2, 15),                 // the exact F1 repro
            new EndCounts("erDiagram\n  A ||--|| A\n", 2, 2),                  // bar+bar at both ends
            new EndCounts("erDiagram\n  A }o--o{ A\n", 15, 15),                // crow+ring at both ends
            new EndCounts("erDiagram\n  A |o--o| A\n", 13, 13),                // bar+ring at both ends
            new EndCounts("erDiagram\n  A }|--|{ A\n", 4, 4),                  // ONE_OR_MANY: crow+bar
            new EndCounts("erDiagram\n  A ||--o{ A : first\n  A ||--o{ A : second\n", 2, 15),
            new EndCounts("erDiagram\n  A }o--o{ A : one\n  A }o--o{ A : two\n  A }o--o{ A : three\n",
                15, 15),
        };
        for (EndCounts c : cases) {
            LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(c.dsl()));
            List<Group> loops = edgeGroups(laid);
            assertFalse(loops.isEmpty(), "the diagram rendered at least one self-loop: " + c.dsl());
            for (Group g : loops) {
                // The two attach heights come from the loop's own HORIZONTAL edge legs (exit at the
                // top attach, return at the bottom); the vertical lane leg is excluded by y1 == y2.
                // Dash segments ride their leg's y, so dashed loops partition identically. Nearest-
                // attach assignment is exact: per-end marker ink stays within MAX_MARKER_HALF of its
                // attach while the sizing floor keeps the attaches >= 2·MAX_MARKER_HALF apart — this
                // is NOT the discredited midpoint band-split (a crow prong crossing the box midline
                // still sits nearer its own attach than the other end's).
                double ay = Double.POSITIVE_INFINITY;
                double by = Double.NEGATIVE_INFINITY;
                for (Shape s : g.members()) {
                    if (s instanceof Line l && !ER_MK.equals(l.stroke())
                            && Math.abs(l.y1() - l.y2()) < 1e-6) {
                        ay = Math.min(ay, l.y1());
                        by = Math.max(by, l.y1());
                    }
                }
                assertTrue(ay < by, "a loop has two distinct horizontal legs: " + c.dsl());
                List<Line> top = new ArrayList<>();
                List<Line> bottom = new ArrayList<>();
                for (Shape s : g.members()) {
                    if (s instanceof Line l && ER_MK.equals(l.stroke())) {
                        double midY = (l.y1() + l.y2()) / 2;
                        (Math.abs(midY - ay) <= Math.abs(midY - by) ? top : bottom).add(l);
                    }
                }
                // The load-bearing half: the AUTHORED marker is present at EACH end, pinned by exact
                // primitive anatomy. Deleting or swapping either end's marker changes its own end's
                // count and fails HERE — no pooled total another end can satisfy.
                assertEquals(c.topSegments(), top.size(),
                    "top (left-cardinality) marker segments in: " + c.dsl());
                assertEquals(c.bottomSegments(), bottom.size(),
                    "bottom (right-cardinality) marker segments in: " + c.dsl());
                List<Line> marks = new ArrayList<>(top);
                marks.addAll(bottom);
                for (int i = 0; i < marks.size(); i++) {
                    for (int j = i + 1; j < marks.size(); j++) {
                        Line a = marks.get(i);
                        Line b = marks.get(j);
                        if (sharesEndpoint(a, b)) {
                            continue; // crow-foot prongs / ring chain — legal by construction
                        }
                        assertFalse(segmentsCross(a, b),
                            "two marker segments of the same self-loop cross: ("
                                + a.x1() + "," + a.y1() + ")-(" + a.x2() + "," + a.y2() + ") x ("
                                + b.x1() + "," + b.y1() + ")-(" + b.x2() + "," + b.y2() + ")  in: "
                                + c.dsl());
                    }
                }
            }
            assertAllGeometryInside(laid);
        }
    }

    /// sir288 F1 pin: loop-containment growth must PRESERVE the single-band invariant for empty nodes
    /// (headerH == h for an attribute-less entity, nameH == h for a memberless class). The regression
    /// grew the box and left the name band behind, rendering a phantom rows-colored body the layout's
    /// own docs say an empty node never has. Pinned on emitted geometry: the node group's name-band
    /// rect (second rect) must span the full box rect (first rect). The populated control pins the
    /// other direction — a REAL rows compartment must survive the same growth un-swallowed.
    @Test
    void loopGrowthKeepsAnEmptyNodeSingleBand() {
        assertNameBandSpansBox(
            ErDiagramLayout.layout((ErDiagram) DslParser.parse("erDiagram\n  A ||--o{ A\n")),
            SirentideRole.ENTITY, "attribute-less entity, single loop (the exact repro)");
        assertNameBandSpansBox(
            ErDiagramLayout.layout((ErDiagram) DslParser.parse(
                "erDiagram\n  A ||--o{ A : one\n  A ||--o{ A : two\n")),
            SirentideRole.ENTITY, "attribute-less entity, two lanes");
        assertNameBandSpansBox(
            ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(
                "classDiagram\n  class A\n  A <|-- A\n  A <|-- A\n")),
            SirentideRole.CLASS, "memberless class, two lanes (the class analog)");

        // POSITIVE control: a populated entity under the same growth keeps its real rows band.
        LaidOut populated = ErDiagramLayout.layout((ErDiagram) DslParser.parse(
            "erDiagram\n  A ||--o{ A\n  A {\n    int id\n  }\n"));
        boolean sawPopulated = false;
        for (Shape s : populated.shapes()) {
            if (s instanceof Group g && g.anchor().role() == SirentideRole.ENTITY) {
                List<Rect> rects = nodeRects(g);
                assertTrue(rects.get(1).height() < rects.get(0).height() - 1e-6,
                    "a populated entity keeps a rows compartment below its header");
                sawPopulated = true;
            }
        }
        assertTrue(sawPopulated, "the populated control actually rendered");
    }

    private static void assertNameBandSpansBox(LaidOut laid, SirentideRole role, String what) {
        boolean saw = false;
        for (Shape s : laid.shapes()) {
            if (s instanceof Group g && g.anchor().role() == role) {
                List<Rect> rects = nodeRects(g);
                assertEquals(rects.get(0).height(), rects.get(1).height(), 1e-6,
                    what + ": the name band must span the whole grown box — no phantom compartment");
                assertEquals(rects.get(0).y(), rects.get(1).y(), 1e-6,
                    what + ": box and name band share the same top");
                saw = true;
            }
        }
        assertTrue(saw, what + ": the node actually rendered");
    }

    private static List<Rect> nodeRects(Group g) {
        List<Rect> rects = new ArrayList<>();
        for (Shape m : g.members()) {
            if (m instanceof Rect r) {
                rects.add(r);
            }
        }
        assertTrue(rects.size() >= 2, "a node group carries its box rect + name-band rect");
        return rects;
    }

    private static boolean sharesEndpoint(Line a, Line b) {
        return samePoint(a.x1(), a.y1(), b.x1(), b.y1()) || samePoint(a.x1(), a.y1(), b.x2(), b.y2())
            || samePoint(a.x2(), a.y2(), b.x1(), b.y1()) || samePoint(a.x2(), a.y2(), b.x2(), b.y2());
    }

    private static boolean samePoint(double ax, double ay, double bx, double by) {
        return Math.abs(ax - bx) < 1e-6 && Math.abs(ay - by) < 1e-6;
    }

    /// True when the two segments PROPERLY cross (interiors intersect), by the standard orientation
    /// test. Collinear touching is not a crossing; a shared endpoint is filtered by the caller.
    private static boolean segmentsCross(Line a, Line b) {
        double d1 = orient(a.x1(), a.y1(), a.x2(), a.y2(), b.x1(), b.y1());
        double d2 = orient(a.x1(), a.y1(), a.x2(), a.y2(), b.x2(), b.y2());
        double d3 = orient(b.x1(), b.y1(), b.x2(), b.y2(), a.x1(), a.y1());
        double d4 = orient(b.x1(), b.y1(), b.x2(), b.y2(), a.x2(), a.y2());
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
            && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        double v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        return Math.abs(v) < 1e-9 ? 0 : v;
    }

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

    /// A group's marker FOOTPRINT (bbox {minX,minY,maxX,maxY}) over marker leaves whose y falls in
    /// [yLo, yHi), or null if none. Marker leaves = marker-coloured Lines (triangle/crow-foot/bar/arrow/
    /// ring) + every filled Path (composition diamond, arrowhead). Edge legs (EDGE/ER_EDGE) and labels
    /// (GlyphRun/MathBox) are excluded. The y-band exists because a self-loop caps BOTH ends (an ER loop
    /// has a top AND a bottom marker); banding at the box centre keeps a top marker from being merged with
    /// its own bottom marker (which would make the outer lane's bbox enclose the inner and false-fail).
    private static double[] markerFootprint(Group g, String markerColor, double yLo, double yHi) {
        List<double[]> pts = new ArrayList<>();
        for (Shape s : g.members()) {
            if (s instanceof Line l && markerColor.equals(l.stroke())) {
                addIfInBand(pts, l.x1(), l.y1(), yLo, yHi);
                addIfInBand(pts, l.x2(), l.y2(), yLo, yHi);
            } else if (s instanceof Path p) {
                List<double[]> pp = new ArrayList<>();
                pathPoints(p.d(), pp);
                for (double[] q : pp) {
                    addIfInBand(pts, q[0], q[1], yLo, yHi);
                }
            }
        }
        if (pts.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    /// The whole-group marker footprint (any y) — for a positive "the marker rendered" control.
    private static double[] markerFootprint(Group g, String markerColor) {
        return markerFootprint(g, markerColor, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private static void addIfInBand(List<double[]> pts, double x, double y, double yLo, double yHi) {
        if (y >= yLo && y < yHi) {
            pts.add(new double[] {x, y});
        }
    }

    /// Two footprints OVERLAP when their bboxes intersect beyond a touch tolerance (adjacent markers may
    /// just touch at the stroke-clearance boundary without overlapping). Uniform over line-vs-line,
    /// line-vs-path, and path-vs-path since both operands are already reduced to a bbox.
    private static boolean footprintsOverlap(double[] a, double[] b, double tol) {
        return a[0] < b[2] - tol && b[0] < a[2] - tol   // x overlap
            && a[1] < b[3] - tol && b[1] < a[3] - tol;  // y overlap
    }

    /// No two edge groups' SAME-SIDE marker footprints overlap — the sirentide 275 invariant the old
    /// collinear-leg oracle never checked. Split at `splitY` (the box centre): a self-loop caps both
    /// ends, and only same-side markers of adjacent lanes can collide (top-vs-bottom are a box-height
    /// apart). Checks the top band and the bottom band independently.
    private static void assertMarkerFootprintsDisjointAcrossGroups(
            List<Group> groups, String markerColor, double splitY) {
        assertMarkerFootprintsDisjointAcrossGroups(groups, markerColor, splitY, false);
    }

    /// `requireBothBands` (Lattice 281 F2): when every group is KNOWN to emit a marker at both
    /// attaches — an ER relationship always emits both cardinality combos — assert each expected
    /// marker is actually PRESENT before comparing. Without it the pairwise loop below silently
    /// `continue`s past a null footprint, so a fixture that stopped rendering one end entirely would
    /// still pass "nothing overlaps". A whole-group positive control does not cover this, because it
    /// cannot tell which band the marker it found was in.
    private static void assertMarkerFootprintsDisjointAcrossGroups(
            List<Group> groups, String markerColor, double splitY, boolean requireBothBands) {
        assertBandDisjoint(groups, markerColor, Double.NEGATIVE_INFINITY, splitY, "top", requireBothBands);
        assertBandDisjoint(groups, markerColor, splitY, Double.POSITIVE_INFINITY, "bottom", requireBothBands);
    }

    private static void assertBandDisjoint(
            List<Group> groups, String markerColor, double yLo, double yHi, String side,
            boolean requireMarker) {
        List<double[]> boxes = new ArrayList<>();
        for (Group g : groups) {
            boxes.add(paintedMarkerFootprint(g, markerColor, yLo, yHi, 1.0));
        }
        if (requireMarker) {
            for (int i = 0; i < boxes.size(); i++) {
                assertTrue(boxes.get(i) != null,
                    "edge group " + i + " rendered no " + side + "-attach marker at all — the "
                        + "disjointness below would be vacuous for it");
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                double[] bi = boxes.get(i);
                double[] bj = boxes.get(j);
                if (bi == null || bj == null) {
                    continue;
                }
                assertFalse(footprintsOverlap(bi, bj, 1e-6),
                    side + "-attach marker footprints of edge groups " + i + " and " + j + " overlap: "
                        + java.util.Arrays.toString(bi) + " vs " + java.util.Arrays.toString(bj));
            }
        }
    }

    /// The PAINTED footprint (Lattice 281 F2): the centerline bbox inflated by half the stroke width,
    /// because a stroke is painted centred on its path — two markers whose CENTERLINES clear each other
    /// by less than their combined half-widths still visibly overlap on screen. `widthMultiplier` models
    /// a playback state: an ACTIVE group is emitted at {@code Emphasis.ACTIVE_WIDTH_MULT} (2x) stroke,
    /// so the frame a viewer actually sees is fatter than the static geometry the old oracle measured.
    private static double[] paintedMarkerFootprint(
            Group g, String markerColor, double yLo, double yHi, double widthMultiplier) {
        double[] box = null;
        for (Shape s : g.members()) {
            if (s instanceof Line l && markerColor.equals(l.stroke())) {
                double pad = (l.strokeWidth() * widthMultiplier) / 2.0;
                box = mergePoint(box, l.x1(), l.y1(), pad, yLo, yHi);
                box = mergePoint(box, l.x2(), l.y2(), pad, yLo, yHi);
            } else if (s instanceof Path pth) {
                List<double[]> pp = new ArrayList<>();
                pathPoints(pth.d(), pp);
                for (double[] q : pp) {
                    box = mergePoint(box, q[0], q[1], 0, yLo, yHi);
                }
            }
        }
        return box;
    }

    private static double[] mergePoint(double[] box, double x, double y, double pad,
                                       double yLo, double yHi) {
        if (y < yLo || y >= yHi) {
            return box;
        }
        if (box == null) {
            return new double[] {x - pad, y - pad, x + pad, y + pad};
        }
        box[0] = Math.min(box[0], x - pad);
        box[1] = Math.min(box[1], y - pad);
        box[2] = Math.max(box[2], x + pad);
        box[3] = Math.max(box[3], y + pad);
        return box;
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
