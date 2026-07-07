package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Point;
import com.sirentide.ir.QuadrantChart;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.QuadrantChartLayout;
import com.sirentide.layout.Shape;
import com.sirentide.layout.Wedge;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The quadrant chart (8th diagram type): a 2×2 positioning matrix. Covers the parse shape
/// (axis-end + quadrant labels + `[x,y]` points), point positioning through the affine unit-square
/// mapping (the y-flip), the out-of-range clamp, the empty-body-still-renders invariant, and the
/// malformed→inert degrade. The byte-exact render is pinned by GoldenSvgTest; the containment guard
/// (ContainmentTest) proves producer ⊆ contract.
class QuadrantChartTest {

    // Plot geometry mirrors QuadrantChartLayout's constants (ML=78, MT=24, PLOT=260).
    private static final double PLOT = 260, ML = 78, MT = 24;
    private static final double PLOT_LEFT = ML, PLOT_TOP = MT, PLOT_BOTTOM = MT + PLOT;
    private static final double CENTER_X = ML + PLOT / 2;      // 208
    private static final double CENTER_Y = MT + PLOT / 2;      // 154

    private static QuadrantChart parse(String dsl) {
        return (QuadrantChart) DslParser.parse(dsl);
    }

    /// The centre of the i-th point disc (a full-circle Wedge) in layout order.
    private static Wedge discAt(LaidOut laid, int index) {
        int seen = 0;
        for (Shape s : laid.shapes()) {
            if (s instanceof Wedge w) {
                if (seen == index) {
                    return w;
                }
                seen++;
            }
        }
        throw new AssertionError("no disc at index " + index);
    }

    // ---------------------------------------------------------------- parse ----

    @Test
    void parsesAxisEndsQuadrantLabelsAndPoints() {
        QuadrantChart q = parse(
            "quadrant\n  x-axis \"Low Reach\" --> \"High Reach\"\n"
                + "  y-axis \"Low Impact\" --> \"High Impact\"\n"
                + "  quadrant-1 \"Major project\"\n  quadrant-2 \"Quick win\"\n"
                + "  quadrant-3 \"Deprioritize\"\n  quadrant-4 \"Fill-in\"\n"
                + "  \"Feature A\" : [0.3, 0.6]\n  \"Feature B\" : [0.75, 0.8]\n");
        assertEquals("Low Reach", q.xLo());
        assertEquals("High Reach", q.xHi());
        assertEquals("Low Impact", q.yLo());
        assertEquals("High Impact", q.yHi());
        // Mermaid numbering: [0]=Q1 top-right, [1]=Q2 top-left, [2]=Q3 bottom-left, [3]=Q4 bottom-right.
        assertEquals("Major project", q.quadrantLabels()[0]);
        assertEquals("Quick win", q.quadrantLabels()[1]);
        assertEquals("Deprioritize", q.quadrantLabels()[2]);
        assertEquals("Fill-in", q.quadrantLabels()[3]);
        assertEquals(2, q.points().size());
        Point a = q.points().get(0);
        assertEquals("Feature A", a.label());
        assertEquals(0.3, a.x());
        assertEquals(0.6, a.y());
    }

    @Test
    void axisWithoutArrowSetsOnlyTheLowEnd() {
        QuadrantChart q = parse("quadrant\n  x-axis \"Reach\"\n");
        assertEquals("Reach", q.xLo());
        assertNull(q.xHi(), "no --> means only the low end is set");
    }

    // -------------------------------------------------------------- position ---

    @Test
    void pointLandsInTopRightQuadrant() {
        // Feature B at [0.75, 0.8]: x>0.5 (right of centre) AND y>0.5 → TOP-RIGHT (Q1). The y-flip
        // (y=1 → top) is load-bearing here; the delete-mutant breaks it and this test goes RED.
        QuadrantChart q = parse("quadrant\n  \"Feature B\" : [0.75, 0.8]\n");
        Wedge disc = discAt(QuadrantChartLayout.layout(q), 0);
        assertTrue(disc.cx() > CENTER_X, "x=0.75 places the disc RIGHT of the vertical axis");
        assertTrue(disc.cy() < CENTER_Y, "y=0.8 places the disc ABOVE the horizontal axis (y flipped up)");
        // Exact affine mapping: x=0.75 → 78+0.75*260=273, y=0.8 → 284-0.8*260=76.
        assertEquals(273, disc.cx());
        assertEquals(76, disc.cy());
    }

    @Test
    void pointAtOriginLandsBottomLeftAtTheCorner() {
        // [0,0] → the plot's BOTTOM-LEFT corner (x=0 → left edge, y=0 → bottom edge).
        QuadrantChart q = parse("quadrant\n  \"Origin\" : [0, 0]\n");
        Wedge disc = discAt(QuadrantChartLayout.layout(q), 0);
        assertEquals(PLOT_LEFT, disc.cx());
        assertEquals(PLOT_BOTTOM, disc.cy());
    }

    @Test
    void outOfRangeCoordinatesAreClampedIntoTheUnitSquare() {
        QuadrantChart q = parse("quadrant\n  \"Hi\" : [1.8, -0.5]\n");
        Point p = q.points().get(0);
        assertEquals(1.0, p.x(), "x>1 clamps to 1");
        assertEquals(0.0, p.y(), "y<0 clamps to 0");
        // And the clamped disc still sits on the plot boundary, never off-canvas.
        Wedge disc = discAt(QuadrantChartLayout.layout(q), 0);
        assertEquals(ML + PLOT, disc.cx());       // x=1 → right edge
        assertEquals(PLOT_BOTTOM, disc.cy());     // y=0 → bottom edge
    }

    // ----------------------------------------------------- degrade invariants --

    @Test
    void bareQuadrantStillRendersAValidGrid() {
        // A body-less quadrant is a valid EMPTY 2×2 grid (round-trips to QuadrantChart, NOT Empty).
        QuadrantChart q = parse("quadrant\n");
        assertTrue(q.points().isEmpty());
        LaidOut laid = QuadrantChartLayout.layout(q);
        // The four tints + the border/axis lines still draw (a non-degenerate canvas).
        assertTrue(laid.width() > 0 && laid.height() > 0, "empty quadrant keeps a real canvas");
        assertFalse(laid.shapes().isEmpty(), "the tints + axes still render with no data");
        String svg = Sirentide.render("quadrant\n");
        assertTrue(svg.contains("<rect"), "the quadrant tints render on a bare body");
        assertFalse(svg.contains("width=\"0\""), "a bare quadrant is NOT the inert shell");
    }

    @Test
    void malformedRowsAreSkippedNeverThrow() {
        QuadrantChart q = parse(
            "quadrant\n  \"good\" : [0.4, 0.4]\n  no colon here\n  \"bad\" : [nope]\n"
                + "  \"short\" : [0.5]\n  \"long\" : [0.1, 0.2, 0.3]\n");
        assertEquals(1, q.points().size(), "only the well-formed point survives");
        assertEquals("good", q.points().get(0).label());
    }

    @Test
    void nonFiniteCoordinateNeverLeaksToOutput() {
        // "1e400" parses to Infinity WITHOUT throwing — it must be dropped, never emitted.
        String svg = Sirentide.render("quadrant\n  \"Overflow\" : [1e400, 0.5]\n");
        assertFalse(svg.contains("Infinity") || svg.contains("NaN"), "no non-finite literal: " + svg);
    }

    @Test
    void unknownTypeIsNotAQuadrant() {
        assertFalse(DslParser.parse("quadrantfoo\n") instanceof QuadrantChart,
            "only the exact `quadrant` type token routes to the quadrant parser");
    }
}
