package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.GitGraph;
import com.sirentide.parse.DslParser;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// The gitGraph LANE + CONNECTOR geometry pins (plan sirentide-gitgraph). Commits advance along the
/// time axis in declaration order; each branch gets its own PARALLEL lane (a perpendicular y offset);
/// a merge draws an elbow CONNECTOR between the two lanes; each branch is a DISTINCT palette colour.
///
/// Lives in {@code com.sirentide.layout} to reach {@link GitGraphLayout#layout(GitGraph)} directly —
/// the tests assert on the laid-out {@link Wedge}/{@link Line} geometry, never re-implement layout.
class GitGraphLayoutTest {

    /// The example graph: main (2 commits) → branch develop (2 commits) → merge back into main → a
    /// final main commit. Exercises every geometry the receipts pin.
    private static final String DSL =
        "gitGraph\n  commit\n  commit id: \"fix\"\n  branch develop\n  checkout develop\n"
            + "  commit\n  commit\n  checkout main\n  merge develop\n  commit\n";

    private static List<Shape> shapes(String dsl) {
        GitGraph gg = (GitGraph) DslParser.parse(dsl);
        return Group.flatten(GitGraphLayout.layout(gg).shapes());
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
    void commitsAdvanceInDeclarationOrderAlongTheTimeAxis() {
        // Every commit dot sits at a strictly increasing x — the shared time axis advances one column
        // per commit (including the merge commit) in declaration order.
        List<Double> xs = dots(shapes(DSL)).stream().map(Wedge::cx).toList();
        assertEquals(6, xs.size(), "2 main + 2 develop + 1 merge + 1 final = 6 commit dots: " + xs);
        for (int i = 1; i < xs.size(); i++) {
            assertTrue(xs.get(i) > xs.get(i - 1),
                "commit x advances monotonically in declaration order: " + xs);
        }
    }

    /// DELETE-MUTANT SENTINEL #1 (receipt #6): the develop branch MUST get its OWN lane — a distinct
    /// perpendicular y offset from main. If the lane assignment is broken (e.g. every branch mapped to
    /// lane 0, all dots sharing one y), this test fails by name.
    @Test
    void developGetsItsOwnLanePerpendicularToMain() {
        List<Wedge> dots = dots(shapes(DSL));
        // Group dot y-values by fill (branch colour). Two distinct branch colours → two distinct lanes.
        Map<String, List<Double>> byColor = dots.stream()
            .collect(Collectors.groupingBy(Wedge::fill, Collectors.mapping(Wedge::cy, Collectors.toList())));
        assertEquals(2, byColor.size(), "two branch colours → two lanes: " + byColor);
        // Each branch's dots share ONE lane y; the two lane ys differ (a perpendicular offset).
        List<Double> laneYs = byColor.values().stream()
            .map(ys -> {
                double y0 = ys.get(0);
                assertTrue(ys.stream().allMatch(y -> near(y, y0)), "a branch's dots share one lane y: " + ys);
                return y0;
            })
            .toList();
        assertNotEquals(laneYs.get(0), laneYs.get(1),
            "main and develop occupy DISTINCT lanes (a perpendicular offset): " + laneYs);
    }

    /// DELETE-MUTANT SENTINEL #2 (receipt #6): a merge MUST draw a connector between the two lanes — a
    /// vertical segment rising from the merged (develop) lane into the merge commit on the active (main)
    /// lane, at the merge commit's column. If the merge connector is dropped, this test fails by name.
    @Test
    void mergeDrawsAConnectorBetweenTheTwoLanes() {
        List<Shape> shapes = shapes(DSL);
        List<Wedge> dots = dots(shapes);
        // The two lane ys, and the merge commit x (the LAST-but-one main-lane dot: merge, then final).
        double mainY = dots.get(0).cy();   // col 0 is on main
        double devY = dots.stream().map(Wedge::cy).filter(y -> !near(y, mainY)).findFirst().orElseThrow();
        // A vertical cross-lane connector: x1==x2, endpoints on the two distinct lane ys.
        List<Line> crossLane = lines(shapes).stream()
            .filter(l -> near(l.x1(), l.x2())
                && (near(l.y1(), mainY) && near(l.y2(), devY) || near(l.y1(), devY) && near(l.y2(), mainY)))
            .toList();
        // TWO exist: the branch-point DROP (main→develop) and the merge RISE (develop→main).
        assertEquals(2, crossLane.size(),
            "a branch-drop AND a merge-rise vertical connector span the two lanes: " + crossLane);
        // The merge rise sits at the LARGER x (the merge commit's column comes after the branch point).
        double mergeX = crossLane.stream().mapToDouble(Line::x1).max().orElse(-1);
        double branchX = crossLane.stream().mapToDouble(Line::x1).min().orElse(-1);
        assertTrue(mergeX > branchX, "the merge connector is to the RIGHT of the branch point: "
            + mergeX + " vs " + branchX);
        // And a merge commit dot actually lands on the main lane at that merge column x.
        assertTrue(dots.stream().anyMatch(w -> near(w.cx(), mergeX) && near(w.cy(), mainY)),
            "a merge commit dot lands on the active (main) lane at the merge column");
    }

    @Test
    void eachBranchIsADistinctPaletteColour() {
        List<Wedge> dots = dots(shapes(DSL));
        long distinctColours = dots.stream().map(Wedge::fill).distinct().count();
        assertEquals(2, distinctColours, "main and develop use two distinct palette colours");
        // Concretely, the first two palette entries (deterministic branch-declaration order).
        assertEquals(Colors.PALETTE[0], dots.get(0).fill(), "main is palette[0]");
        assertTrue(dots.stream().anyMatch(w -> w.fill().equals(Colors.PALETTE[1])),
            "develop is palette[1] (the second lane declared)");
    }

    @Test
    void aCommitBeforeAnyBranchLandsOnImplicitMain() {
        // No `branch` at all → every commit shares ONE lane (implicit main), one colour.
        List<Wedge> dots = dots(shapes("gitGraph\n  commit\n  commit\n  commit\n"));
        assertEquals(3, dots.size());
        double y0 = dots.get(0).cy();
        assertTrue(dots.stream().allMatch(w -> near(w.cy(), y0)), "all on the implicit main lane");
        assertEquals(1, dots.stream().map(Wedge::fill).distinct().count(), "one branch colour");
    }

    @Test
    void unknownBranchMergeAndSelfMergeAreInert() {
        // merge of an unknown branch, and a self-merge, add NO merge commit — the dot count stays at
        // the two real commits (never throws, never draws a spurious merge).
        List<Wedge> dots = dots(shapes(
            "gitGraph\n  commit\n  merge ghost\n  merge main\n  commit\n"));
        assertEquals(2, dots.size(), "no merge commit from an unknown/self merge: " + dots.size());
    }
}
