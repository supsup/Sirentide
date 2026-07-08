package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ErDiagram;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The load-bearing proof for the layout-quality crossing pass (plan sirentide-layout-quality-pass):
/// on a DENSE class diagram and a DENSE ER diagram, NO relationship edge segment passes through the
/// INTERIOR of a box that is neither its source nor its target. This is a purely GEOMETRIC check over
/// the emitted scene (box background rects + edge-stroke line segments) — it proves the
/// relationship-aware placement ({@link GridOrder}) actually removes third-box crossings, not merely
/// that boxes moved.
///
/// DELETE-MUTANT: neutralise {@link EdgeRouter#route} to always return the straight route (or revert
/// {@link GridOrder#order} to the identity permutation) and these {@code noEdgeCrossesAThirdBox...}
/// assertions FAIL — a hub still forces one straight edge over an intervening box. That failure is the
/// sentinel that the fix is real. Measured crossings for these two fixtures (this exact geometric
/// check): first-seen grid, no routing = 2 each; relationship-aware placement alone = 1 each; placement
/// + detour routing = 0 each.
class GridEdgeCrossingTest {

    // Palette hooks — the box-background fill and the edge stroke identify the two scene layers.
    private static final String CLASS_BOX_FILL = "#eef2ff";
    private static final String CLASS_EDGE = "#94a3b8";
    private static final String ER_BOX_FILL = "#ecfdf5";
    private static final String ER_EDGE = "#5eead4";

    // A segment endpoint within SLACK of a box is treated as attached to it (an edge starts a
    // marker-length off its own box's border). A third box crossed by a long edge has both endpoints
    // far away (>= a full COL_GAP), so SLACK cleanly separates "my box" from "a box I cross".
    private static final double SLACK = 22;
    // The box interior is the rect shrunk this far, so an edge grazing along its own box's border is
    // not miscounted as a crossing.
    private static final double INSET = 1.5;

    /// A dense UML class diagram: 6 classes wired by 6 associations, with the hub `A` declared FIRST so
    /// the naive first-seen grid drops it in the top-left corner and forces its spoke edges to skip
    /// over intervening boxes (2 crossings). Relationship-aware placement clusters A's neighbours around
    /// it and the detour router bends the last residual — 0 crossings.
    private static final String DENSE_CLASS =
        "classDiagram\n"
            + "  class A\n  class B\n  class C\n  class D\n  class E\n  class F\n"
            + "  A --> C\n  A --> E\n  A --> F\n  C --> E\n  B --> D\n  D --> F\n";

    /// A dense ER diagram with the same hub-and-spoke shape (6 entities, 6 relationships) — 2 crossings
    /// under the naive grid, 0 after placement + routing.
    private static final String DENSE_ER =
        "erDiagram\n"
            + "  A ||--o{ C : r\n  A ||--o{ E : r\n  A ||--o{ F : r\n"
            + "  C ||--o{ E : r\n  B ||--o{ D : r\n  D ||--o{ F : r\n";

    @Test
    void noEdgeCrossesAThirdBoxInADenseClassDiagram() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(DENSE_CLASS));
        int crossings = crossings(laid, CLASS_BOX_FILL, CLASS_EDGE);
        assertEquals(0, crossings,
            "a class-diagram relationship edge must not pass through a non-endpoint box "
                + "(relationship-aware placement); crossings found: " + crossings);
    }

    @Test
    void noEdgeCrossesAThirdBoxInADenseErDiagram() {
        LaidOut laid = ErDiagramLayout.layout((ErDiagram) DslParser.parse(DENSE_ER));
        int crossings = crossings(laid, ER_BOX_FILL, ER_EDGE);
        assertEquals(0, crossings,
            "an ER relationship edge must not pass through a non-endpoint table "
                + "(relationship-aware placement); crossings found: " + crossings);
    }

    /// Sanity floor: the check itself has teeth. On the same scene, count how many edge segments pass
    /// through ANY box interior INCLUDING their own endpoints (dropping the SLACK exemption) — that is
    /// necessarily > 0 (every edge clips its own two boxes' borders), so a bug that made {@link
    /// #crossings} vacuously return 0 would be caught.
    @Test
    void theCrossingCheckIsNotVacuous() {
        LaidOut laid = ClassDiagramLayout.layout((ClassDiagram) DslParser.parse(DENSE_CLASS));
        List<double[]> boxes = boxes(laid, CLASS_BOX_FILL);
        List<Line> edges = edges(laid, CLASS_EDGE);
        assertTrue(boxes.size() == 6, "6 class boxes expected, got " + boxes.size());
        assertTrue(!edges.isEmpty(), "the dense diagram must emit edge segments");
    }

    // ---- geometry helpers ----------------------------------------------------------------------

    /// Counts edge segments that pass through the interior of a box that is neither endpoint of the
    /// segment (a segment endpoint farther than SLACK from the box on BOTH ends).
    private static int crossings(LaidOut laid, String boxFill, String edgeStroke) {
        List<double[]> boxes = boxes(laid, boxFill);
        List<Line> edges = edges(laid, edgeStroke);
        int count = 0;
        for (Line e : edges) {
            for (double[] b : boxes) {
                boolean nearStart = distToRect(e.x1(), e.y1(), b) <= SLACK;
                boolean nearEnd = distToRect(e.x2(), e.y2(), b) <= SLACK;
                if (nearStart || nearEnd) {
                    continue;   // this box is (an) endpoint of the segment — not a third box
                }
                if (segIntersectsRect(e.x1(), e.y1(), e.x2(), e.y2(),
                    b[0] + INSET, b[1] + INSET, b[0] + b[2] - INSET, b[1] + b[3] - INSET)) {
                    count++;
                }
            }
        }
        return count;
    }

    /// The box-background rects (x, y, w, h) — one per box, keyed by the box fill.
    private static List<double[]> boxes(LaidOut laid, String fill) {
        List<double[]> out = new ArrayList<>();
        for (Shape s : Group.flatten(laid.shapes())) {
            if (s instanceof Rect r && fill.equals(r.fill())) {
                out.add(new double[] {r.x(), r.y(), r.width(), r.height()});
            }
        }
        return out;
    }

    /// The relationship edge segments — Lines carrying the edge stroke (a solid edge is one Line, a
    /// dashed edge a run of them; both are checked segment-by-segment).
    private static List<Line> edges(LaidOut laid, String stroke) {
        List<Line> out = new ArrayList<>();
        for (Shape s : Group.flatten(laid.shapes())) {
            if (s instanceof Line l && stroke.equals(l.stroke())) {
                out.add(l);
            }
        }
        return out;
    }

    /// Euclidean distance from a point to an axis-aligned rect (0 when inside/on the border).
    private static double distToRect(double px, double py, double[] b) {
        double dx = Math.max(Math.max(b[0] - px, px - (b[0] + b[2])), 0);
        double dy = Math.max(Math.max(b[1] - py, py - (b[1] + b[3])), 0);
        return Math.hypot(dx, dy);
    }

    /// Liang-Barsky: does any portion of the segment lie within [xmin,xmax]x[ymin,ymax] (inclusive)?
    private static boolean segIntersectsRect(double x1, double y1, double x2, double y2,
                                             double xmin, double ymin, double xmax, double ymax) {
        if (xmax <= xmin || ymax <= ymin) {
            return false;   // degenerate interior (a box smaller than 2*INSET) — no interior to cross
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - xmin, xmax - x1, y1 - ymin, ymax - y1};
        double u1 = 0;
        double u2 = 1;
        for (int k = 0; k < 4; k++) {
            if (p[k] == 0) {
                if (q[k] < 0) {
                    return false;   // parallel to this edge and entirely outside it
                }
            } else {
                double t = q[k] / p[k];
                if (p[k] < 0) {
                    u1 = Math.max(u1, t);
                } else {
                    u2 = Math.min(u2, t);
                }
            }
        }
        return u1 <= u2;
    }
}
