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
