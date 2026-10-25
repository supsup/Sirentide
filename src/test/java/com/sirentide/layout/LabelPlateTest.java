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
