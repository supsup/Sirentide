package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.a11y.A11y;
import com.sirentide.a11y.A11yDescriber;
import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Red-first correctness pins for the three audited findings (SIR-11a/11b, SIR-10, SIR-09). Each test
/// asserts a property the PRE-fix code violated:
///   - SIR-11a: the a11y branch count == the RENDERED lane count for a >MAX_LANES gitGraph;
///   - SIR-11b: the a11y label preserves an ESCAPED literal dollar exactly as the visual LabelRuns path;
///   - SIR-10: no two timeline labels land on the same row when their x-intervals overlap (out-of-order);
///   - SIR-09: a reversed Gantt task's bar stays on-canvas (its endpoints are inside the axis domain).
///
/// In {@code com.sirentide.layout} to reach the laid-out {@link Wedge}/{@link Rect} geometry and the
/// package-visible {@link TimelineLayout#assignRows} seam directly.
class CorrectnessFixTest {

    // ---- SIR-11a: gitGraph a11y branch count must match the rendered lane count ----------------

    /// A >MAX_LANES gitGraph: 45 explicit branches, each committed to, plus implicit main. The layout
    /// caps lanes at {@link GitGraphReplay#MAX_LANES} (40); the a11y describer used to run a SECOND,
    /// cap-less replay and announce ~46 branches. Sharing the one capped replay makes the spoken count
    /// equal the drawn lane count. RED before the fix (a11y said 46, render drew 40).
    @Test
    void gitGraphA11yBranchCountEqualsRenderedLaneCount() {
        List<GitOp> ops = new ArrayList<>();
        ops.add(new GitOp.Commit(null));              // a commit on implicit main
        for (int i = 1; i <= 45; i++) {
            ops.add(new GitOp.Branch("b" + i));       // open + switch to a new branch
            ops.add(new GitOp.Commit(null));          // and commit to it (so the lane is drawn)
        }
        GitGraph gg = new GitGraph(ops);

        // RENDERED lane count: distinct commit-dot lane-y values in the laid-out geometry.
        List<Shape> shapes = Group.flatten(GitGraphLayout.layout(gg).shapes());
        long renderedLanes = shapes.stream()
            .filter(s -> s instanceof Wedge).map(s -> ((Wedge) s).cy())
            .distinct().count();

        // SPOKEN branch count: parsed from the a11y desc ("… across N branches.").
        String desc = A11yDescriber.describe(gg).desc();
        int spokenBranches = parseCount(desc, "across (\\d+) branch");

        assertEquals(GitGraphReplay.MAX_LANES, renderedLanes,
            "the render caps at MAX_LANES lanes: " + renderedLanes);
        assertEquals(renderedLanes, spokenBranches,
            "the a11y branch count must equal the rendered lane count (SIR-11a): desc=" + desc);
    }

    // ---- SIR-11b: a11y label honours \$ literal dollar exactly as the visual LabelRuns path --------

    /// `cost \$5 and $x$` renders visually with the price PRESERVED (LabelRuns unescapes `\$`→`$` and
    /// strips only the well-formed `$x$` math run). The old a11y used a naive `\$[^$]*\$` regex that
    /// mis-paired the escaped dollar and corrupted the text to `cost \x$`. Sharing LabelRuns makes the
    /// desc text identical to what a sighted reader sees. RED before the fix.
    @Test
    void gitA11yLabelHonoursEscapedDollarLikeTheVisualPath() {
        Pie pie = new Pie(List.of(new Slice("cost \\$5 and $x$", 5)));
        String desc = A11yDescriber.describe(pie).desc();

        assertTrue(desc.contains("cost $5 and"),
            "the escaped literal dollar / price is preserved (matches the visual): " + desc);
        assertFalse(desc.contains("\\x"),
            "no regex-mangled 'cost \\x$' corruption survives: " + desc);
        assertFalse(desc.contains("$x$"),
            "the well-formed $math$ run is still stripped (no LaTeX leak): " + desc);
    }

    // ---- SIR-10: out-of-order timeline labels never share an overlapping row ----------------------

    /// Three labels whose DECLARATION order (index 0,1,2 at x 200,100,130) differs from their X order
    /// (100,130,200). Two of them (x 100 and 130, each width 60) overlap in x, so the greedy packer
    /// MUST place them on different rows. The pre-fix declaration-order walk processed the x=200 label
    /// first and pushed the overlapping pair onto the SAME row. RED before the fix.
    @Test
    void timelineAssignRowsSeparatesOverlappingLabelsRegardlessOfDeclarationOrder() {
        double[] centers = {200, 100, 130};   // declaration order (NOT x order)
        double[] widths = {60, 60, 60};
        int[] rows = TimelineLayout.assignRows(centers, widths);

        for (int i = 0; i < centers.length; i++) {
            for (int j = i + 1; j < centers.length; j++) {
                boolean xOverlap = Math.abs(centers[i] - centers[j]) < (widths[i] + widths[j]) / 2;
                if (xOverlap) {
                    assertTrue(rows[i] != rows[j],
                        "labels " + i + " and " + j + " overlap in x but share row " + rows[i]
                            + " (SIR-10): rows=" + java.util.Arrays.toString(rows));
                }
            }
        }
    }

    /// Three equal-valued events emit at the same x. The legacy two-row fallback assigns rows
    /// [0, 1, 0], so the first and third ACTUAL glyph boxes overprint. The generalized packer must
    /// allocate the third row and keep every displayed top label disjoint. RED on current main.
    @Test
    void timelineThreeEqualXLabelsHaveDisjointRenderedGlyphBoxes() {
        com.sirentide.ir.Timeline timeline = (com.sirentide.ir.Timeline)
            com.sirentide.parse.DslParser.parse("""
                timeline
                  "Alpha milestone" : 1
                  "Bravo milestone" : 1
                  "Charlie marker"  : 1
                """);

        List<GlyphRun> labels = Group.flatten(TimelineLayout.layout(timeline).shapes()).stream()
            .filter(GlyphRun.class::isInstance)
            .map(GlyphRun.class::cast)
            .toList();
        assertEquals(6, labels.size(), "three event labels plus three values");

        // Timeline emits top then bottom for each event. Audit the emitted top-label paths, not
        // advance-width estimates.
        List<double[]> top = List.of(
            pathBounds(labels.get(0).pathD()),
            pathBounds(labels.get(2).pathD()),
            pathBounds(labels.get(4).pathD()));
        assertPairwiseDisjoint(top, "equal-x Timeline top labels");
    }

    @Test
    void timelineEqualXPermutationsKeepDeclarationAssociationAndStableRows() {
        String[] labels = {"Wide WWW marker", "thin iii note", "mixed zigzag"};
        int[][] permutations = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
            {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
        };
        double[] expectedWidths = new double[labels.length];
        for (int i = 0; i < labels.length; i++) {
            List<double[]> single = timelineTopBoxes("timeline\n  \"" + labels[i] + "\" : 1\n");
            expectedWidths[i] = single.get(0)[2] - single.get(0)[0];
        }

        for (int[] permutation : permutations) {
            StringBuilder dsl = new StringBuilder("timeline\n");
            for (int index : permutation) {
                dsl.append("  \"").append(labels[index]).append("\" : 1\n");
            }
            LaidOut laid = TimelineLayout.layout((com.sirentide.ir.Timeline)
                com.sirentide.parse.DslParser.parse(dsl.toString()));
            List<double[]> boxes = timelineTopBoxes(laid);
            assertPairwiseDisjoint(boxes, "permuted equal-x Timeline labels");

            for (int i = 0; i < permutation.length; i++) {
                assertEquals(expectedWidths[permutation[i]], boxes.get(i)[2] - boxes.get(i)[0], 1e-6,
                    "emitted label " + i + " stays associated with declaration " + permutation[i]);
            }
            // The contract sorts ACTUAL left edges first (declaration only breaks equal-left ties).
            // All three intervals overlap, so that order must map to rows 0/1/2, growing upward.
            List<double[]> byLeft = boxes.stream()
                .sorted(java.util.Comparator.comparingDouble(b -> b[0]))
                .toList();
            assertTrue(byLeft.get(0)[1] > byLeft.get(1)[1]
                    && byLeft.get(1)[1] > byLeft.get(2)[1],
                "stable left-edge order maps to outward rows: " + byLeft.stream()
                    .map(b -> List.of(b[0], b[1])).toList());
            assertEquals(laid, TimelineLayout.layout((com.sirentide.ir.Timeline)
                com.sirentide.parse.DslParser.parse(dsl.toString())),
                "the same authored permutation lays out byte-structurally identically");
        }
    }

    @Test
    void timelinePackedEndpointLabelsStayClampedInsideTheCanvas() {
        String dsl = """
            timeline
              "Left alpha label long"   : 0
              "Left bravo label long"   : 0
              "Left charlie label long" : 0
              "Right delta label long"  : 100
              "Right echo label long"   : 100
              "Right foxtrot label"     : 100
            """;
        LaidOut laid = TimelineLayout.layout((com.sirentide.ir.Timeline)
            com.sirentide.parse.DslParser.parse(dsl));

        for (double[] box : glyphBoxes(laid)) {
            assertTrue(box[0] >= 2 - 1e-6 && box[2] <= laid.width() - 2 + 1e-6,
                "post-clamp glyph box stays in the horizontal canvas: "
                    + java.util.Arrays.toString(box));
        }
    }

    @Test
    void separatedTallMathTimelineRetainsExactLegacySvgBytes() throws Exception {
        MathFragmentRenderer guarded = (latex, size) -> Optional.of(new MathFragment(
            "<path d=\"M0 -18 L20 -18 L20 2 L0 2 Z\" fill=\"currentColor\"/>",
            20, 18, 2));
        String svg = Sirentide.render("""
            timeline
              "AAAAAAAAAAAA" : 0
              "BBBBBBBBBBBB" : 1
              "$x$"          : 100
            """, guarded);

        assertEquals("a82e2afdb58619ab20649412df11370a281b3425b43ea1e247e67bfdd720e9f8",
            sha256(svg), "a clean legacy Timeline remains byte-identical");
    }

    @Test
    void timelineCombiningMarkOverhangUsesTheActualGlyphBoxForEndpointClamp() {
        String overhanging = "\u20e4" + "W".repeat(100);
        String dsl = "timeline\n  \"" + overhanging + "\" : 0\n  \"Right\" : 100\n";

        LaidOut laid = TimelineLayout.layout((com.sirentide.ir.Timeline)
            com.sirentide.parse.DslParser.parse(dsl));
        double[] firstTop = timelineTopBoxes(laid).get(0);

        assertTrue(firstTop[0] >= 2 - 1e-6,
            "actual left ink overhang stays inside the clamp margin: "
                + java.util.Arrays.toString(firstTop));
        assertTrue(firstTop[2] <= laid.width() - 2 + 1e-6,
            "correcting the left overhang cannot create a right escape: "
                + java.util.Arrays.toString(firstTop));
    }

    @Test
    void timelineManyCoincidentLabelsShiftAxisGrowCanvasAndStayContained() {
        StringBuilder dsl = new StringBuilder("timeline\n");
        for (int i = 0; i < 20; i++) {
            dsl.append("  \"Coincident marker ").append(i).append("\" : 7\n");
        }
        LaidOut laid = TimelineLayout.layout((com.sirentide.ir.Timeline)
            com.sirentide.parse.DslParser.parse(dsl.toString()));

        List<double[]> glyphs = glyphBoxes(laid);
        assertEquals(40, glyphs.size(), "20 top labels plus 20 displayed values");
        assertPairwiseDisjoint(timelineTopBoxes(laid), "20-row Timeline top band");
        assertPairwiseDisjoint(timelineBottomBoxes(laid), "20-row Timeline bottom band");
        for (double[] box : glyphs) {
            assertTrue(box[0] >= -1e-6 && box[1] >= -1e-6
                    && box[2] <= laid.width() + 1e-6 && box[3] <= laid.height() + 1e-6,
                "packed Timeline glyph stays contained: " + java.util.Arrays.toString(box)
                    + " in " + laid.width() + "x" + laid.height());
        }
        List<Shape> flat = Group.flatten(laid.shapes());
        Line axis = flat.stream().filter(Line.class::isInstance).map(Line.class::cast)
            .findFirst().orElseThrow();
        assertTrue(axis.y1() > 80, "the top band shifts the legacy axis down: " + axis.y1());
        assertTrue(laid.height() > 160, "the bottom band grows the legacy canvas: " + laid.height());
        assertTrue(flat.stream().filter(Wedge.class::isInstance).map(Wedge.class::cast)
            .allMatch(dot -> Math.abs(dot.cy() - axis.y1()) < 1e-9),
            "every event dot follows the shifted axis baseline");
    }

    @Test
    void timelineTallMathLabelClearsTheAxisAndBottomBand() {
        double fragmentWidth = 40;
        double fragmentHeight = 60;
        double fragmentDepth = 50;
        com.sirentide.api.MathFragmentRenderer tall = (latex, size) ->
            java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<path d=\"M0 -60 L40 -60 L40 50 L0 50 Z\" fill=\"currentColor\"/>",
                fragmentWidth, fragmentHeight, fragmentDepth));
        com.sirentide.ir.Timeline timeline = (com.sirentide.ir.Timeline)
            com.sirentide.parse.DslParser.parse("timeline\n  \"$x$\" : 7\n");

        LaidOut laid = TimelineLayout.layout(timeline, tall);
        List<Shape> flat = Group.flatten(laid.shapes());
        MathBox math = flat.stream().filter(MathBox.class::isInstance).map(MathBox.class::cast)
            .findFirst().orElseThrow();
        Wedge dot = flat.stream().filter(Wedge.class::isInstance).map(Wedge.class::cast)
            .findFirst().orElseThrow();
        GlyphRun value = flat.stream().filter(GlyphRun.class::isInstance).map(GlyphRun.class::cast)
            .findFirst().orElseThrow();
        double[] valueBox = pathBounds(value.pathD());
        double mathTop = math.y() - fragmentHeight;
        double mathBottom = math.y() + fragmentDepth;

        assertTrue(mathTop >= 2 - 1e-6, "the tall fragment stays above the top canvas edge");
        assertTrue(mathBottom + 4 <= dot.cy() - dot.r() + 1e-6,
            "the top band clears the event dot by the layout gap");
        assertTrue(mathBottom <= valueBox[1] + 1e-6,
            "the tall top label cannot overprint the displayed value below the axis");
        assertTrue(valueBox[3] <= laid.height() + 1e-6,
            "the bottom value stays inside the shifted canvas");
    }

    @Test
    void timelineWideMathFragmentGrowsTheCanvasToContainItsDeclaredBox() {
        double fragmentWidth = 600;
        com.sirentide.api.MathFragmentRenderer wide = (latex, size) ->
            java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<path d=\"M0 -8 L600 -8 L600 2 L0 2 Z\" fill=\"currentColor\"/>",
                fragmentWidth, 8, 2));
        com.sirentide.ir.Timeline timeline = (com.sirentide.ir.Timeline)
            com.sirentide.parse.DslParser.parse("timeline\n  \"$wide$ tail\" : 7\n");

        LaidOut laid = TimelineLayout.layout(timeline, wide);
        List<Shape> flat = Group.flatten(laid.shapes());
        MathBox math = flat.stream()
            .filter(MathBox.class::isInstance).map(MathBox.class::cast)
            .findFirst().orElseThrow();
        double[] trailingText = flat.stream()
            .filter(GlyphRun.class::isInstance).map(GlyphRun.class::cast)
            .map(g -> pathBounds(g.pathD()))
            .max(java.util.Comparator.comparingDouble(box -> box[0]))
            .orElseThrow();

        assertTrue(laid.width() >= math.x() + fragmentWidth + 2 - 1e-6,
            "the trusted fragment metrics remain inside the horizontal canvas");
        assertTrue(trailingText[0] >= math.x() + fragmentWidth - 1e-6,
            "the selected glyph run is the text emitted after the math fragment");
        assertTrue(laid.width() >= trailingText[2] + 2 - 1e-6,
            "canvas growth consumes the whole composite text-and-fragment union");
        assertTrue(laid.width() > 480, "only an overwide label grows the legacy canvas");
    }

    // ---- SIR-09: a reversed Gantt task's bar stays on-canvas ------------------------------------

    /// A reversed task R(100→0) alongside A(0→50). The naive domain min(starts)..max(ends) = [0,50]
    /// excluded R's start=100, so AxisScale extrapolated R's bar to x≈820 — off the 480px canvas
    /// (invisible). Aggregating the domain over BOTH endpoints of EVERY task = [0,100] keeps R on
    /// canvas. RED before the fix.
    @Test
    void reversedGanttTaskStaysOnCanvas() {
        Gantt gantt = new Gantt(List.of(new Task("A", 0, 50), new Task("R", 100, 0)));

        // Domain now spans both endpoints of every task.
        assertTrue(gantt.start() <= 0 && gantt.end() >= 100,
            "domain covers every endpoint (SIR-09): [" + gantt.start() + ", " + gantt.end() + "]");

        // Render-level: every bar rect lies within the [0, W] canvas.
        LaidOut laid = GanttLayout.layout(gantt);
        double w = laid.width();
        List<Rect> rects = Group.flatten(laid.shapes()).stream()
            .filter(s -> s instanceof Rect).map(s -> (Rect) s).toList();
        assertEquals(2, rects.size(), "two task bars");
        for (Rect r : rects) {
            assertTrue(r.x() >= 0 && r.x() + r.width() <= w + 1e-6,
                "bar within [0, " + w + "] canvas — not extrapolated off-screen (SIR-09): "
                    + "x=" + r.x() + " w=" + r.width());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static int parseCount(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        assertTrue(m.find(), "count pattern /" + regex + "/ not found in: " + s);
        return Integer.parseInt(m.group(1));
    }

    private static double[] pathBounds(String path) {
        Matcher m = Pattern.compile("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?").matcher(path);
        List<Double> values = new ArrayList<>();
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

    private static List<double[]> glyphBoxes(LaidOut laid) {
        return Group.flatten(laid.shapes()).stream()
            .filter(GlyphRun.class::isInstance)
            .map(GlyphRun.class::cast)
            .map(g -> pathBounds(g.pathD()))
            .toList();
    }

    private static List<double[]> timelineTopBoxes(String dsl) {
        return timelineTopBoxes(TimelineLayout.layout((com.sirentide.ir.Timeline)
            com.sirentide.parse.DslParser.parse(dsl)));
    }

    private static List<double[]> timelineTopBoxes(LaidOut laid) {
        List<double[]> all = glyphBoxes(laid);
        List<double[]> top = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 2) {
            top.add(all.get(i));
        }
        return top;
    }

    private static List<double[]> timelineBottomBoxes(LaidOut laid) {
        List<double[]> all = glyphBoxes(laid);
        List<double[]> bottom = new ArrayList<>();
        for (int i = 1; i < all.size(); i += 2) {
            bottom.add(all.get(i));
        }
        return bottom;
    }

    private static void assertPairwiseDisjoint(List<double[]> boxes, String subject) {
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                double[] a = boxes.get(i);
                double[] b = boxes.get(j);
                boolean overlaps = a[0] < b[2] && b[0] < a[2]
                    && a[1] < b[3] && b[1] < a[3];
                assertFalse(overlaps, subject + " overlap at " + i + "-" + j
                    + ": " + java.util.Arrays.toString(a) + " vs "
                    + java.util.Arrays.toString(b));
            }
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
