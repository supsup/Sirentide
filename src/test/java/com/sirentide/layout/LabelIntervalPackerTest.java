package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LabelIntervalPackerTest {

    @Test
    void reusesTheEarliestFinishingRowRatherThanRowIndexFirstFit() {
        LabelIntervalPacker.Box[] boxes = {
            box(0, 10),
            box(1, 5),
            box(6, 7)
        };

        LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(boxes, 0);

        assertArrayEquals(new int[] {0, 1, 1}, packed.rows());
        assertEquals(2, packed.rowCount(), "maximum simultaneous interval count is two");
    }

    @Test
    void equalRightEdgesReuseTheSmallerStableRowIndex() {
        LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(
            new LabelIntervalPacker.Box[] {box(0, 10), box(1, 10), box(11, 12)}, 0);

        assertArrayEquals(new int[] {0, 1, 0}, packed.rows());
        assertEquals(2, packed.rowCount());
    }

    @Test
    void equalLeftEdgesKeepDeclarationOrderAndRequireOneRowEach() {
        LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(
            new LabelIntervalPacker.Box[] {box(2, 8), box(2, 6), box(2, 10)}, 0);

        assertArrayEquals(new int[] {0, 1, 2}, packed.rows());
        assertEquals(3, packed.rowCount());
    }

    @Test
    void exactGapBoundaryReusesTheEarliestFinishingRow() {
        LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(
            new LabelIntervalPacker.Box[] {box(0, 4), box(8, 9)}, 4);

        assertArrayEquals(new int[] {0, 0}, packed.rows(),
            "right edge + gap == next left edge is clean and reuses the row");
        assertEquals(1, packed.rowCount());
    }

    @Test
    void nonDisplayedRunsConsumeNoRow() {
        LabelIntervalPacker.Box[] boxes = {box(0, 4), null, box(1, 3)};

        LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(boxes, 0);

        assertArrayEquals(new int[] {0, -1, 1}, packed.rows());
        assertEquals(2, packed.rowCount());
        assertTrue(LabelIntervalPacker.rowsClean(boxes, packed.rows(), 0));
    }

    @Test
    void compatibilityGateReadsActualIntervalsAndExistingRows() {
        LabelIntervalPacker.Box[] boxes = {box(0, 4), box(3, 7), box(8, 9)};
        assertFalse(LabelIntervalPacker.rowsClean(boxes, new int[] {0, 0, 0}, 0));
        assertTrue(LabelIntervalPacker.rowsClean(boxes, new int[] {0, 1, 0}, 0));
        assertFalse(LabelIntervalPacker.rowsClean(boxes, new int[] {0, 1, 0}, 5));
    }

    @Test
    void verticalBaselinesSeparateActualRowEnvelopesInBothDirections() {
        LabelIntervalPacker.Box[] boxes = {
            new LabelIntervalPacker.Box(0, -8, 10, 2),
            new LabelIntervalPacker.Box(0, -12, 10, 3),
            new LabelIntervalPacker.Box(0, -6, 10, 1)
        };
        int[] rows = {0, 1, 2};

        double[] down = LabelIntervalPacker.baselinesDown(boxes, rows, 3, 20, 4);
        double[] up = LabelIntervalPacker.baselinesUp(boxes, rows, 3, -20, 4);

        assertTrue(LabelIntervalPacker.rowBandsDisjoint(boxes, rows, down));
        assertTrue(LabelIntervalPacker.rowBandsDisjoint(boxes, rows, up));
        assertTrue(down[0] < down[1] && down[1] < down[2]);
        assertTrue(up[0] > up[1] && up[1] > up[2]);
    }

    @Test
    void parserScaleWorstCaseRetainsOnlyLinearRowsAndAssignments() {
        int n = 10_000;
        LabelIntervalPacker.Box[] boxes = new LabelIntervalPacker.Box[n];
        for (int i = 0; i < n; i++) {
            boxes[i] = box(0, 1);
        }

        LabelIntervalPacker.Result packed = LabelIntervalPacker.pack(boxes, 0);

        assertEquals(n, packed.rows().length);
        assertEquals(n, packed.rowCount(), "every coincident interval needs exactly one stable row");
        assertEquals(n - 1, packed.rows()[n - 1]);
    }

    @Test
    void glyphPathBoundsUseTheEmittedCoordinatePairs() {
        LabelIntervalPacker.Box bounds = LabelIntervalPacker.pathBounds(
            "M 4 -2 L 9 3 Q 11 7 12 1 Z");
        assertEquals(new LabelIntervalPacker.Box(4, -2, 12, 7), bounds);
    }

    private static LabelIntervalPacker.Box box(double left, double right) {
        return new LabelIntervalPacker.Box(left, -8, right, 2);
    }
}
