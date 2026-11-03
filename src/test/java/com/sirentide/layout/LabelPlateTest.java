package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/// Plan 5ce905a8: the crossing test and plate geometry behind {@link LabelPlate}, one stroke kind at
/// a time, so a missing branch cannot hide behind another (every sequence bar sits on a lifeline).
class LabelPlateTest {

    private static final double[] LABEL = {100, 50, 160, 60};

    @Test
    void aRectUnderTheLabelCrosses() {
        assertTrue(LabelPlate.crossesPainted(LABEL, List.of(new Rect(120, 0, 8, 200, "#c7d2fe"))));
    }

    @Test
    void aLineThroughTheLabelCrossesIncludingASlantedOne() {
        assertTrue(LabelPlate.crossesPainted(LABEL, List.of(new Line(130, 0, 130, 200, "#cbd5e1", 1))));
        assertTrue(LabelPlate.crossesPainted(LABEL, List.of(new Line(90, 40, 170, 70, "#94a3b8", 1.5))));
    }

    @Test
    void aPathUnderTheLabelCrosses() {
        assertTrue(LabelPlate.crossesPainted(LABEL, List.of(new Path("M 150 55 L 140 51 L 140 59 Z", "#64748b"))));
    }

    @Test
    void aShapeInsideAGroupCrosses() {
        Anchor anchor = new AnchorAssigner().assign(com.sirentide.contract.SirentideRole.MESSAGE, "A-B");
        assertTrue(LabelPlate.crossesPainted(LABEL,
            List.of(new Group(anchor, List.of(new Line(130, 0, 130, 200, "#cbd5e1", 1))))));
    }

    /// Healthy negatives: a line that stops short, one that passes beside the box, a rect beside it
    /// and another label's glyphs never trigger a plate.
    @Test
    void nearbyStrokesAndOtherTextDoNotCross() {
        assertFalse(LabelPlate.crossesPainted(LABEL, List.of(
            new Line(130, 62, 130, 200, "#cbd5e1", 1),
            new Line(90, 40, 98, 70, "#cbd5e1", 1),
            new Rect(170, 0, 8, 200, "#c7d2fe"),
            new GlyphRun("M 110 52 L 150 52 L 150 58 Z", "currentColor"))));
    }

    @Test
    void inkBoxCoversEveryPointAndThePlatePadsIt() {
        double[] box = LabelPlate.inkBox(List.of("M 10 20 L 30 25 Q 31 18 12 19 Z", "M 40.5 22 L 41 30 Z"));
        assertArrayEquals(new double[] {10, 18, 41, 30}, box, 1e-9);
        Rect plate = LabelPlate.plate(box);
        assertEquals(8, plate.x(), 1e-9);
        assertEquals(16, plate.y(), 1e-9);
        assertEquals(35, plate.width(), 1e-9);
        assertEquals(16, plate.height(), 1e-9);
        assertEquals("#f5f7ff", plate.fill());
        assertEquals("#000000", LabelPlate.TEXT);
        assertNull(LabelPlate.inkBox(List.of("")));
    }

    /// Review 1335 F4: only absolute M/L/Q/C/Z paths are read as x,y pairs. An arc's radii and flag
    /// pair (here `5 5 0 0 1`) used to stretch the box to the origin; any path with another command
    /// (A, H, V, S, T or a relative one) is skipped and contributes nothing.
    @Test
    void inkBoxSkipsPathsWithCommandsOtherThanMLQCZ() {
        assertNull(LabelPlate.inkBox(List.of("M 100 100 A 5 5 0 0 1 110 100")));
        assertNull(LabelPlate.inkBox(List.of("M 100 100 H 120 V 130 Z")));
        assertNull(LabelPlate.inkBox(List.of("M 100 100 l 5 5 Z")));
        assertArrayEquals(new double[] {100, 50, 120, 60}, LabelPlate.inkBox(List.of(
            "M 100 100 A 5 5 0 0 1 110 100", "M 100 50 C 105 52 110 58 120 60 Z")), 1e-9);
    }

    /// Review 1335 F1: with nothing filled under it the plate is the plain padded rect.
    @Test
    void aPlateOverNoArrowheadIsThePlainRect() {
        Shape s = LabelPlate.plate(LABEL, List.of(new Line(90, 55, 170, 55, "#94a3b8", 1.5),
            new Path("M 300 300 L 310 296 L 310 304 Z", "#94a3b8")));
        assertEquals(LabelPlate.plate(LABEL), s);
    }

    /// Review 1335 F1, the paint-order rule for both layouts: inside the element's group the lines
    /// the ink crosses come first, then the plate, then the other lines, then the arrowheads.
    @Test
    void placePutsThePlateAboveCrossedLinesAndBelowTheRestAndTheArrowhead() {
        Line crossed = new Line(90, 55, 170, 55, "#94a3b8", 1.5);
        Line clear = new Line(90, 70, 170, 70, "#94a3b8", 1.5);
        Path head = new Path("M 180 70 L 170 66 L 170 74 Z", "#94a3b8");
        List<Shape> group = new java.util.ArrayList<>(List.of(crossed, clear, head));
        LabelPlate.place(group, 0, 2, LABEL, List.of());
        assertEquals(List.of(crossed, LabelPlate.plate(LABEL), clear, head), group);
    }

    /// Review 1335 F1: an earlier arrowhead reaching into the plate is cut out of it, including
    /// arrowheads that converge on one tip and overlap each other (a fan into one node). Read by
    /// non-zero winding at sample points, the way an SVG renderer fills the path.
    @Test
    void earlierArrowheadsAreCutOutOfThePlateEvenWhenTheyOverlap() {
        Anchor a = new AnchorAssigner().assign(com.sirentide.contract.SirentideRole.EDGE, "A-S");
        List<double[]> heads = List.of(
            new double[] {150, 64, 140, 60, 142, 54},
            new double[] {150, 64, 141, 58, 145, 53},
            new double[] {150, 64, 143, 57, 148, 54});
        List<Shape> under = new java.util.ArrayList<>();
        for (double[] h : heads) {
            under.add(new Group(a, List.of(new Path("M " + h[0] + " " + h[1] + " L " + h[2] + " " + h[3]
                + " L " + h[4] + " " + h[5] + " Z", "#94a3b8"))));
        }
        Shape s = LabelPlate.plate(LABEL, under);
        assertTrue(s instanceof Path p && p.structural() && "#f5f7ff".equals(p.fill()), "a cut plate: " + s);
        String d = ((Path) s).d();
        int n = 12;
        for (double[] h : heads) {
            for (int i = 1; i < n; i++) {
                for (int j = 1; i + j < n; j++) {
                    double u = i / (double) n;
                    double v = j / (double) n;
                    double x = u * h[0] + v * h[2] + (1 - u - v) * h[4];
                    double y = u * h[1] + v * h[3] + (1 - u - v) * h[5];
                    if (x > 98 && x < 162 && y > 48 && y < 62) {
                        assertEquals(0, winding(d, x, y), "arrowhead point " + x + "," + y + " is uncovered");
                    }
                }
            }
        }
        assertTrue(winding(d, 101, 51) != 0, "the rest of the plate is filled");
        assertTrue(winding(d, 130, 55) != 0, "the rest of the plate is filled");
    }

    /// Review 1337 F2: a line painted AFTER a label is broken where it would cross the label's ink,
    /// with the cut at the ink box grown by PAD plus the line's active-frame half width (1.5 for a
    /// 1.5px line), so even the doubled stroke ends clear of the ink.
    @Test
    void aLaterLineIsBrokenAroundAnEarlierLabelsInk() {
        Line through = new Line(130, 0, 130, 200, "#94a3b8", 1.5);
        Path head = new Path("M 130 210 L 126.5 200 L 133.5 200 Z", "#94a3b8");
        List<Shape> group = new java.util.ArrayList<>(List.of(through, head));
        LabelPlate.gap(group, List.of(LABEL));
        assertEquals(3, group.size(), "two pieces and the arrowhead: " + group);
        assertLine(new double[] {130, 0, 130, 46.5}, group.get(0));
        assertLine(new double[] {130, 63.5, 130, 200}, group.get(1));
        assertTrue(group.get(2) == head, "the arrowhead is kept, after the pieces");
    }

    private static void assertLine(double[] xy, Shape s) {
        assertTrue(s instanceof Line, "a line: " + s);
        Line l = (Line) s;
        assertArrayEquals(xy, new double[] {l.x1(), l.y1(), l.x2(), l.y2()}, 1e-9);
        assertEquals("#94a3b8", l.stroke());
        assertEquals(1.5, l.strokeWidth());
    }

    /// Healthy control for the gap, varied only in the line's distance from the ink: a line ending
    /// 2px below the ink box is out of reach even at the doubled 3px width and is kept as the same
    /// object, while one ending 1px below it reaches the ink at the doubled width and is cut back.
    @Test
    void aLaterLineOutOfReachIsKeptWholeAndOneWithinTheActiveReachIsCut() {
        Line clear = new Line(130, 62, 130, 200, "#94a3b8", 1.5);
        List<Shape> group = new java.util.ArrayList<>(List.of(clear));
        LabelPlate.gap(group, List.of(LABEL));
        assertEquals(1, group.size());
        assertTrue(group.get(0) == clear, "a line out of reach is not rebuilt");
        List<Shape> near = new java.util.ArrayList<>(List.of(new Line(130, 61, 130, 200, "#94a3b8", 1.5)));
        LabelPlate.gap(near, List.of(LABEL));
        assertEquals(1, near.size(), "one piece: " + near);
        assertLine(new double[] {130, 63.5, 130, 200}, near.get(0));
    }

    /// Review 1337 F2: an earlier label's ink box is cut out of a later plate
    /// that reaches over it, read by non-zero winding; the rest of the plate stays filled. With no
    /// earlier ink the same plate is the plain rect.
    @Test
    void anEarlierLabelsInkIsCutOutOfALaterPlate() {
        double[] earlier = {150, 40, 200, 49};
        Shape s = LabelPlate.plate(LABEL, List.of(), List.of(earlier));
        assertTrue(s instanceof Path p && p.structural() && "#f5f7ff".equals(p.fill()), "a cut plate: " + s);
        String d = ((Path) s).d();
        for (double x = 150.01; x < 162; x += 1) {
            for (double y = 48.01; y < 49; y += 0.25) {
                assertEquals(0, winding(d, x, y), "earlier ink point " + x + "," + y + " is uncovered");
            }
        }
        assertTrue(winding(d, 130, 55) != 0, "the rest of the plate is filled");
        assertTrue(winding(d, 155, 55) != 0, "the plate below the earlier ink is filled");
        assertEquals(LabelPlate.plate(LABEL), LabelPlate.plate(LABEL, List.of(), List.of()));
    }

    /// Review 1341: an earlier label's ink box is cut out of a later plate WHOLE, even where it
    /// reaches into the later label's own ink box (only the all-slots-blocked placement fallback can
    /// leave two ink boxes overlapping), so a later plate never erases earlier ink, not even in the
    /// whitespace between the later label's glyphs. On cbb61b57 the plate stayed whole under its
    /// own ink box, so `winding(d, 155, 52)` was 1, not 0.
    @Test
    void anEarlierLabelsInkIsCutOutWholeEvenUnderThePlatesOwnInkBox() {
        double[] earlier = {150, 40, 200, 55};
        String d = ((Path) LabelPlate.plate(LABEL, List.of(), List.of(earlier))).d();
        assertEquals(0, winding(d, 155, 52), "earlier ink inside this label's own ink box is not erased");
        assertEquals(0, winding(d, 161, 51), "earlier ink right of this label's ink box is not erased");
        assertEquals(0, winding(d, 155, 49), "earlier ink above this label's ink box is not erased");
        assertTrue(winding(d, 120, 55) != 0, "the rest of the plate is filled");
        assertTrue(winding(d, 155, 58) != 0, "the plate below the earlier ink is filled");
    }

    /// The flowchart's crossing reach: a line 1.2px below the ink box does not touch it at its
    /// static 1.5px width (half 0.75) and stays after the plate under the sequence rule, but does
    /// at the active frame's doubled width (half 1.5), so the flowchart rule moves it under the plate.
    @Test
    void theActiveReachMovesANearLineUnderThePlate() {
        Line near = new Line(90, 61.2, 170, 61.2, "#94a3b8", 1.5);
        List<Shape> sequenceRule = new java.util.ArrayList<>(List.of(near));
        LabelPlate.place(sequenceRule, 0, 1, LABEL, List.of());
        assertEquals(List.of(LabelPlate.plate(LABEL), near), sequenceRule);
        List<Shape> flowchartRule = new java.util.ArrayList<>(List.of(near));
        LabelPlate.place(flowchartRule, 0, 1, LABEL, List.of(), LabelPlate.ACTIVE_REACH, List.of());
        assertEquals(List.of(near, LabelPlate.plate(LABEL)), flowchartRule);
    }

    /// Review 1337 F1: the arrowhead boxes the flowchart placement avoids, groups included; a node
    /// silhouette (structural) and an open path are not arrowheads.
    @Test
    void arrowheadBoxesAreTheFilledTrianglesOnly() {
        Anchor a = new AnchorAssigner().assign(com.sirentide.contract.SirentideRole.EDGE, "A-B");
        List<double[]> boxes = LabelPlate.arrowheadBoxes(List.of(
            new Group(a, List.of(new Path("M 82 108 L 85.2 97.9 L 90.8 102.1 Z", "#94a3b8"))),
            Path.silhouette("M 0 0 L 10 0 L 5 8 Z", "#dbe4ff", null, 0),
            new Path("M 0 0 L 10 10", "#94a3b8")));
        assertEquals(1, boxes.size());
        assertArrayEquals(new double[] {82, 97.9, 90.8, 108}, boxes.get(0), 1e-9);
    }

    // ---- review 1381: the cut-out composition is bounded ----

    /// Ten copies of one earlier ink box (25 identical frame divider labels in the review's witness)
    /// are cut out ONCE: the plate is the rect ring plus one hole ring. On cced2495 every subset of
    /// the ten was emitted, 2^10 - 1 hole rings.
    @Test
    void identicalEarlierInkBoxesAreCutOutOnce() {
        double[] h = {120, 45, 140, 55};
        List<double[]> holes = java.util.Collections.nCopies(10, h);
        String d = ((Path) LabelPlate.plate(LABEL, List.of(), holes)).d();
        assertEquals(2, rings(d), "rect plus one hole: " + rings(d));
        assertEquals(0, winding(d, 130, 50), "the hole is uncovered");
        assertTrue(winding(d, 110, 55) != 0, "the plate beside it is filled");
    }

    /// Ten distinct, mutually overlapping earlier ink boxes (all contain one point) are split into
    /// disjoint pieces first, so the ring count stays within 1 + (2n + 1)^2, and the uncovered area
    /// is EXACTLY their union: on a 0.5px grid over the plate, winding is 0 inside some box and
    /// nonzero outside all of them. On cced2495 this was 2^10 rings.
    @Test
    void overlappingEarlierInkBoxesAreSplitDisjointWithTheSameCoverage() {
        List<double[]> holes = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            holes.add(new double[] {104 + 2 * i, 49 + 0.3 * i, 124 + 3 * i, 56 - 0.2 * i});   // all share [122, 51.7, 124, 54.2]
        }
        String d = ((Path) LabelPlate.plate(LABEL, List.of(), holes)).d();
        assertTrue(rings(d) <= 1 + 21 * 21, "bounded ring count: " + rings(d));
        assertTrue(rings(d) < 1023, "not one ring per overlapping subset: " + rings(d));
        assertSameCoverage(d, holes);
    }

    /// Healthy opposing control: one hole and two DISJOINT holes keep today's exact output (one ring
    /// per hole, the same rings as before the bound), with the same coverage check.
    @Test
    void oneOrDisjointHolesAreCutExactlyAsBefore() {
        double[] a = {104, 45, 114, 55};
        double[] b = {130, 52, 150, 70};
        String one = ((Path) LabelPlate.plate(LABEL, List.of(), List.of(a))).d();
        String two = ((Path) LabelPlate.plate(LABEL, List.of(), List.of(a, b))).d();
        assertEquals(2, rings(one));
        assertEquals(3, rings(two));
        assertTrue(two.startsWith(one), "the first hole's ring is unchanged by the second");
        assertSameCoverage(two, List.of(a, b));
    }

    /// Fourteen distinct arrowheads that all overlap at one tip need 2^14 - 1 inclusion-exclusion
    /// terms, past {@link LabelPlate#MAX_CUT_TERMS}: no plate is built (null), and place() leaves
    /// the group unchanged and reports false so the label is drawn unplated. On cced2495 the plate
    /// was built with every term.
    @Test
    void tooManyStackedArrowheadsLeaveThePlateOutAndPlaceReportsIt() {
        List<Shape> heads = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            heads.add(new Path("M 130 58 L " + (110 + i) + " 49 L " + (150 - i * 0.5) + " 49 Z", "#94a3b8"));
        }
        assertNull(LabelPlate.plate(LABEL, heads, List.of()));
        List<Shape> group = new java.util.ArrayList<>(List.of(new Line(0, 0, 1, 1, "#94a3b8", 1)));
        assertFalse(LabelPlate.place(group, 0, 1, LABEL, heads));
        assertEquals(1, group.size(), "the group is unchanged");
    }

    /// Healthy opposing control for the cap: three of the same converging arrowheads are composed
    /// as before (a cut plate, the tip uncovered) and place() reports true.
    @Test
    void afewStackedArrowheadsStillComposeAndPlaceReportsIt() {
        List<Shape> heads = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            heads.add(new Path("M 130 58 L " + (110 + i) + " 49 L " + (150 - i * 0.5) + " 49 Z", "#94a3b8"));
        }
        Shape s = LabelPlate.plate(LABEL, heads, List.of());
        assertTrue(s instanceof Path, "a cut plate: " + s);
        assertEquals(0, winding(((Path) s).d(), 130, 55), "the stacked tips are uncovered");
        assertTrue(winding(((Path) s).d(), 100, 60) != 0, "the corner is filled");
        List<Shape> group = new java.util.ArrayList<>(List.of(new Line(0, 0, 1, 1, "#94a3b8", 1)));
        assertTrue(LabelPlate.place(group, 0, 1, LABEL, heads));
    }

    private static int rings(String d) {
        return d.split("M ", -1).length - 1;
    }

    /// Over a 0.5px grid of the plate rect [98, 48, 162, 62] (offset off every edge), winding is 0
    /// exactly where the point lies inside some hole.
    private static void assertSameCoverage(String d, List<double[]> holes) {
        for (double x = 98.13; x < 162; x += 0.5) {
            for (double y = 48.13; y < 62; y += 0.5) {
                boolean inHole = false;
                for (double[] h : holes) {
                    inHole |= h[0] < x && x < h[2] && h[1] < y && y < h[3];
                }
                assertEquals(inHole, winding(d, x, y) == 0, "point " + x + "," + y + " in a hole: " + inHole);
            }
        }
    }

    private static int winding(String d, double x, double y) {
        int w = 0;
        for (String ring : d.split("(?=M )")) {
            java.util.List<Double> v = new java.util.ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?").matcher(ring);
            while (m.find()) {
                v.add(Double.parseDouble(m.group()));
            }
            for (int i = 0; i + 1 < v.size(); i += 2) {
                double ax = v.get(i);
                double ay = v.get(i + 1);
                double bx = v.get((i + 2) % v.size());
                double by = v.get((i + 3) % v.size());
                double c = (bx - ax) * (y - ay) - (x - ax) * (by - ay);
                if (ay <= y) {
                    if (by > y && c > 0) {
                        w++;
                    }
                } else if (by <= y && c < 0) {
                    w--;
                }
            }
        }
        return w;
    }

    /// A plated run is flagged so the frames emitter recolours it against the plate, while every
    /// two-argument run stays unflagged (plan 5ce905a8).
    @Test
    void aPlatedRunIsFlaggedAndOrdinaryRunsAreNot() {
        GlyphRun plated = LabelPlate.text("M 1 2 L 3 4 Z");
        assertTrue(plated.onPlate());
        assertEquals(LabelPlate.TEXT, plated.fill());
        assertFalse(new GlyphRun("M 1 2 L 3 4 Z", "currentColor").onPlate());
    }
}
