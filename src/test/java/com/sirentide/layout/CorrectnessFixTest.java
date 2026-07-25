package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.a11y.A11y;
import com.sirentide.a11y.A11yDescriber;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Task;
import java.util.ArrayList;
import java.util.List;
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
}
