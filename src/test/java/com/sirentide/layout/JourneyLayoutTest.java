package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Journey;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The journey GEOMETRY pins (plan sirentide-journey). Tasks lay out in declaration order along the
/// x-axis; each task's score maps onto the 1..5 satisfaction y-axis (HIGHER score = HIGHER on the axis
/// = SMALLER y); a single LINE connects the task points in order; each section brackets its task span.
///
/// Lives in {@code com.sirentide.layout} to reach {@link JourneyLayout#layout(Journey)} directly — the
/// tests assert on the laid-out {@link Wedge}/{@link Line} geometry, never re-implement layout.
class JourneyLayoutTest {

    /// The task example: two sections (Go to work: 3 tasks, Do work: 2 tasks), scores 5,3,4 / 5,2.
    private static final String DSL =
        "journey\n  title My working day\n  section Go to work\n    Make tea: 5: Me\n"
            + "    Commute: 3: Me, Cat\n    Arrive: 4: Me\n  section Do work\n    Code: 5: Me\n"
            + "    Meetings: 2: Me, Boss\n";

    private static List<Shape> shapes(String dsl) {
        Journey j = (Journey) DslParser.parse(dsl);
        return Group.flatten(JourneyLayout.layout(j).shapes());
    }

    private static List<Wedge> dots(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Wedge).map(s -> (Wedge) s).toList();
    }

    private static List<Line> lines(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Line).map(s -> (Line) s).toList();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    @Test
    void tasksAdvanceInDeclarationOrderAlongTheXAxis() {
        // Five task dots (3 + 2) at strictly increasing x, in declaration order across both sections.
        List<Double> xs = dots(shapes(DSL)).stream().map(Wedge::cx).toList();
        assertEquals(5, xs.size(), "3 + 2 = 5 task dots: " + xs);
        for (int i = 1; i < xs.size(); i++) {
            assertTrue(xs.get(i) > xs.get(i - 1), "task x advances monotonically: " + xs);
        }
    }

    /// DELETE-MUTANT SENTINEL #1 (receipt #6): the score→y map — a HIGHER satisfaction score sits
    /// HIGHER on the axis (a SMALLER y). Task scores are 5,3,4,5,2; assert the dots' y ordering is the
    /// INVERSE of the score ordering (score 5 → min y, score 2 → max y). If the score→y mapping is
    /// broken (constant y, or inverted so higher score sinks), this test fails by name.
    @Test
    void higherScoreSitsHigherOnTheAxis() {
        List<Wedge> dots = dots(shapes(DSL));
        int[] scores = {5, 3, 4, 5, 2};
        // The two score-5 tasks must share the TOPMOST (min) y; the score-2 task the BOTTOMMOST (max).
        double yFor5 = dots.get(0).cy();   // Make tea, score 5
        double yFor2 = dots.get(4).cy();   // Meetings, score 2
        assertTrue(near(dots.get(3).cy(), yFor5), "both score-5 tasks share one y: "
            + dots.get(3).cy() + " vs " + yFor5);
        assertTrue(yFor5 < yFor2, "score 5 sits ABOVE (smaller y than) score 2: " + yFor5 + " vs " + yFor2);
        // Monotone: a strictly higher score → a strictly smaller (or equal-for-equal-score) y.
        for (int i = 0; i < scores.length; i++) {
            for (int k = 0; k < scores.length; k++) {
                if (scores[i] > scores[k]) {
                    assertTrue(dots.get(i).cy() < dots.get(k).cy(),
                        "score " + scores[i] + " sits higher than score " + scores[k]);
                }
            }
        }
    }

    /// DELETE-MUTANT SENTINEL #2 (receipt #6): the connecting satisfaction LINE hits EACH task point —
    /// for every consecutive task pair there is a Line whose endpoints are the two task dot centres. If
    /// the connecting-line loop is dropped, no such segment exists and this fails by name.
    @Test
    void theConnectingLineHitsEachTaskPointInOrder() {
        List<Shape> shapes = shapes(DSL);
        List<Wedge> dots = dots(shapes);
        List<Line> segs = lines(shapes);
        for (int i = 0; i + 1 < dots.size(); i++) {
            double x0 = dots.get(i).cx();
            double y0 = dots.get(i).cy();
            double x1 = dots.get(i + 1).cx();
            double y1 = dots.get(i + 1).cy();
            boolean found = segs.stream().anyMatch(l ->
                near(l.x1(), x0) && near(l.y1(), y0) && near(l.x2(), x1) && near(l.y2(), y1));
            assertTrue(found, "a segment connects task " + i + " → " + (i + 1)
                + " at (" + x0 + "," + y0 + ")→(" + x1 + "," + y1 + ")");
        }
    }

    @Test
    void aSectionHeaderBracketsItsTaskSpan() {
        List<Shape> shapes = shapes(DSL);
        List<Wedge> dots = dots(shapes);
        List<Line> segs = lines(shapes);
        // The first section (Go to work) spans task dots 0..2; its bracket is a HORIZONTAL line sitting
        // ABOVE the topmost task dot, whose x-extent brackets those three dots.
        double topY = dots.stream().mapToDouble(Wedge::cy).min().orElseThrow();
        double firstX = dots.get(0).cx();
        double thirdX = dots.get(2).cx();
        boolean bracketed = segs.stream().anyMatch(l ->
            near(l.y1(), l.y2())              // horizontal
                && l.y1() < topY              // above the plot / topmost dot
                && l.x1() < firstX && l.x2() > thirdX);   // spans the section's first..last task
        assertTrue(bracketed, "the first section brackets its three tasks with a header line above them");
    }

    @Test
    void emptyJourneyLaysOutAMinimalInertCanvasNotAThrow() {
        Journey j = (Journey) DslParser.parse("journey\n");
        LaidOut laid = JourneyLayout.layout(j);
        assertTrue(laid.width() > 0 && laid.height() > 0, "a real (non-zero) canvas");
        assertTrue(dots(Group.flatten(laid.shapes())).isEmpty(), "no task dots on an empty journey");
    }

    @Test
    void outOfRangeScoreClampsSoItsDotStaysOnTheAxis() {
        // Score 9 clamps to 5 → the same top y as a real score-5 task; never above the plot top.
        List<Wedge> dots = dots(shapes(
            "journey\n  section S\n    Top: 5: Me\n    Over: 9: Me\n"));
        assertTrue(near(dots.get(0).cy(), dots.get(1).cy()),
            "a clamped-to-5 task shares the score-5 y (stays on the axis): " + dots);
    }
}
