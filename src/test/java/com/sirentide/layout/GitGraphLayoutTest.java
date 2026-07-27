package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.GitGraph;
import com.sirentide.parse.DslParser;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /// Adjacent columns are 46px apart while a displayed commit ID may be wider. The legacy
    /// one-baseline lane therefore overprints adjacent long IDs. Audit each commit group's ACTUAL
    /// glyph path and require disjoint boxes. RED on current main.
    @Test
    void adjacentLongCommitIdsHaveDisjointRenderedGlyphBoxes() {
        GitGraph graph = (GitGraph) DslParser.parse("""
            gitGraph
              commit id: "aaaaaaaaaaaa"
              commit id: "bbbbbbbbbbbb"
              commit id: "cccccccccccc"
            """);
        LaidOut laid = GitGraphLayout.layout(graph);
        List<double[]> boxes = laid.shapes().stream()
            .filter(Group.class::isInstance)
            .map(Group.class::cast)
            .filter(g -> g.anchor().role() == com.sirentide.contract.SirentideRole.COMMIT)
            .map(g -> g.members().stream()
                .filter(GlyphRun.class::isInstance)
                .map(GlyphRun.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("labeled commit omitted its glyph path")))
            .map(g -> pathBounds(g.pathD()))
            .toList();

        assertEquals(3, boxes.size(), "one rendered ID path per commit");
        for (double[] box : boxes) {
            assertTrue(box[0] >= 2 - 1e-6 && box[2] <= laid.width() - 2 + 1e-6,
                "endpoint-clamped commit ID stays inside the canvas: "
                    + java.util.Arrays.toString(box));
        }
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                double[] a = boxes.get(i);
                double[] b = boxes.get(j);
                boolean overlap = a[0] < b[2] && b[0] < a[2]
                    && a[1] < b[3] && b[1] < a[3];
                assertTrue(!overlap, "GitGraph commit-ID boxes overlap at " + i + "-" + j
                    + ": " + java.util.Arrays.toString(a) + " vs "
                    + java.util.Arrays.toString(b));
            }
        }
    }

    @Test
    void crowdedEarlierLaneOffsetsLaterLaneAndKeepsConnectorsAttached() {
        String dsl = """
            gitGraph
              commit id: "aaaaaaaaaaaa"
              commit id: "bbbbbbbbbbbb"
              commit id: "cccccccccccc"
              branch develop
              commit id: "dev"
              checkout main
              merge develop
              commit id: "dddddddddddd"
            """;
        GitGraph graph = (GitGraph) DslParser.parse(dsl);
        LaidOut laid = GitGraphLayout.layout(graph);
        List<Shape> flat = Group.flatten(laid.shapes());
        List<Wedge> dots = dots(flat);
        double mainY = dots.get(0).cy();
        double developY = dots.stream().map(Wedge::cy).filter(y -> !near(y, mainY))
            .findFirst().orElseThrow();

        assertEquals(42, mainY, 1e-9, "the first crowded lane keeps its legacy baseline");
        assertTrue(developY > 90, "the later lane receives the crowded-main prefix offset: "
            + developY);
        List<Line> crossLane = lines(flat).stream()
            .filter(l -> near(l.x1(), l.x2())
                && (near(l.y1(), mainY) && near(l.y2(), developY)
                    || near(l.y1(), developY) && near(l.y2(), mainY)))
            .toList();
        assertEquals(2, crossLane.size(),
            "branch and merge connector endpoints consume the resolved lane baselines");

        List<Group> commitGroups = laid.shapes().stream()
            .filter(Group.class::isInstance).map(Group.class::cast)
            .filter(g -> g.anchor().role() == com.sirentide.contract.SirentideRole.COMMIT)
            .toList();
        assertEquals(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc"),
            commitGroups.subList(0, 3).stream().map(g -> g.anchor().id()).toList(),
            "packing never detaches a label from its commit anchor");
        List<double[]> mainLabelBoxes = commitGroups.subList(0, 3).stream()
            .map(g -> g.members().stream().filter(GlyphRun.class::isInstance)
                .map(GlyphRun.class::cast).findFirst().orElseThrow())
            .map(g -> pathBounds(g.pathD()))
            .toList();
        assertPairwiseDisjoint(mainLabelBoxes, "crowded main-lane IDs");
        assertEquals(mainLabelBoxes.get(0)[1], mainLabelBoxes.get(2)[1], 1e-9,
            "non-overlapping first/third IDs reuse the earliest-finishing row");
        assertTrue(mainLabelBoxes.get(1)[1] > mainLabelBoxes.get(0)[1],
            "the middle ID occupies the one additional row needed by the interval clique");
        assertTrue(laid.height() >= developY + 30,
            "resolved final lane and its labels remain inside the grown canvas");
        assertEquals(laid, GitGraphLayout.layout(graph), "GitGraph placement is deterministic");
    }

    @Test
    void crowdedLanesAccumulateEveryPrecedingPrefixOffset() {
        GitGraph graph = (GitGraph) DslParser.parse("""
            gitGraph
              commit id: "aaaaaaaaaaaa"
              commit id: "bbbbbbbbbbbb"
              commit id: "cccccccccccc"
              branch develop
              commit id: "dddddddddddd"
              commit id: "eeeeeeeeeeee"
              commit id: "ffffffffffff"
              branch feature
              commit id: "feature"
            """);

        LaidOut laid = GitGraphLayout.layout(graph);
        List<Double> laneYs = dots(Group.flatten(laid.shapes())).stream()
            .map(Wedge::cy).distinct().sorted().toList();

        assertEquals(3, laneYs.size(), "main, develop, and feature each draw one lane");
        assertTrue(laneYs.get(1) - laneYs.get(0) > 48,
            "develop includes crowded main's added label depth: " + laneYs);
        assertTrue(laneYs.get(2) - laneYs.get(1) > 48,
            "feature includes crowded develop's added label depth too: " + laneYs);
        assertTrue(laid.height() >= laneYs.get(2) + 30,
            "the accumulated final lane remains inside the grown canvas");
    }

    private static double[] pathBounds(String path) {
        Matcher m = Pattern.compile("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?").matcher(path);
        java.util.ArrayList<Double> values = new java.util.ArrayList<>();
        while (m.find()) {
            values.add(Double.parseDouble(m.group()));
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            double x = values.get(i);
            double y = values.get(i + 1);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private static void assertPairwiseDisjoint(List<double[]> boxes, String subject) {
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                double[] a = boxes.get(i);
                double[] b = boxes.get(j);
                boolean overlap = a[0] < b[2] && b[0] < a[2]
                    && a[1] < b[3] && b[1] < a[3];
                assertTrue(!overlap, subject + " overlap at " + i + "-" + j
                    + ": " + java.util.Arrays.toString(a) + " vs "
                    + java.util.Arrays.toString(b));
            }
        }
    }
}
