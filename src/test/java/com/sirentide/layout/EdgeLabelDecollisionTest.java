package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/// Convergent-edge label de-collision (label-legibility plan ea20153b part 2, re-derived on today's
/// FlowchartLayout). Direct unit coverage of {@link FlowchartLayout.LabelDecollider} — the
/// rendered-box, per-target stacking pass that replaces the withdrawn v2 y-only rule.
///
/// The withdrawal (Fixpoint sirentide 271/273, Lattice 276) argued convergent labels are x-separated
/// by construction; the guard branch confluence/flowchart-label-guard @ 277f3f1c MEASURED overprint
/// in both axes and sirentide/514 RETRACTED the premise. So this de-collision keys on the ACTUAL
/// rendered box (both axes), not an anchor point: a same-target box that overprints an already-placed
/// one is stacked a line down; a box that is already disjoint (distinct target, or x-separated, or
/// far in y) is left EXACTLY where it was — the byte-identity guarantee (end-to-end byte-identity of
/// the non-colliding corpus is pinned by GoldenSvgTest's unchanged flowchart goldens).
class EdgeLabelDecollisionTest {

    private static final double STACK = 10 * 1.5;   // EDGE_LABEL_STACK = EDGE_LABEL_SIZE(10) * 1.5

    private static FlowchartLayout.LabelDecollider decollider() {
        return new FlowchartLayout.LabelDecollider(List.of());   // no node obstacles
    }

    // box helper [x0, y0, x1, y1]
    private static double[] box(double x0, double y0, double x1, double y1) {
        return new double[] {x0, y0, x1, y1};
    }

    @Test
    void firstLabelForATargetKeepsItsNaturalPosition() {
        FlowchartLayout.LabelDecollider d = decollider();
        assertEquals(0.0, d.resolve(5, box(10, 20, 40, 30)),
            "the first label to a target places at its natural box — dy==0 (byte-identical)");
    }

    @Test
    void aSecondOverprintingLabelToTheSameTargetStacksOneLineBelow() {
        FlowchartLayout.LabelDecollider d = decollider();
        d.resolve(5, box(10, 20, 40, 30));                       // places first
        double dy = d.resolve(5, box(15, 22, 45, 32));           // overlaps in BOTH axes
        assertEquals(STACK, dy,
            "a second same-target label whose box overprints the first is pushed one line down");
        // Monotonic fan-down: a third overprinting one clears BOTH already-placed boxes.
        double dy3 = d.resolve(5, box(16, 24, 46, 34));
        assertEquals(2 * STACK, dy3, "each further convergent label stacks another line down");
    }

    @Test
    void aSameTargetLabelSeparatedInXIsNotMoved() {
        // The sirentide/514 subtlety inverted: boxes that DO NOT overlap in x are already disjoint, so
        // no stack fires even though they share a target and a y-band (dy==0, byte-identical).
        FlowchartLayout.LabelDecollider d = decollider();
        d.resolve(5, box(10, 20, 40, 30));
        assertEquals(0.0, d.resolve(5, box(50, 20, 80, 30)),
            "a same-target label x-clear of the first keeps its own y — no spurious stacking");
    }

    @Test
    void aSameTargetLabelFarInYIsNotMoved() {
        FlowchartLayout.LabelDecollider d = decollider();
        d.resolve(5, box(10, 20, 40, 30));
        assertEquals(0.0, d.resolve(5, box(10, 200, 40, 210)),
            "a same-target label far below the first (no y-overlap) keeps its own y");
    }

    @Test
    void labelsToDifferentTargetsNeverInteract() {
        FlowchartLayout.LabelDecollider d = decollider();
        d.resolve(5, box(10, 20, 40, 30));
        assertEquals(0.0, d.resolve(6, box(10, 20, 40, 30)),
            "a same-box label to a DIFFERENT target is untouched (byte-identical placement)");
    }

    @Test
    void stackingSkipsASlotOccupiedByANode() {
        // Node-avoidance: when the natural first stack slot would drop the label INTO a node box, the
        // pass takes the NEXT slot instead (never shoves a label onto a node). obstacle = [x, y, w, h].
        // First slot for the second label is y+STACK (35..47); park a node across it so it must skip.
        List<double[]> nodes = List.of(new double[] {5, 35, 60, 15});   // covers [x 5..65, y 35..50]
        FlowchartLayout.LabelDecollider withNode =
            new FlowchartLayout.LabelDecollider(nodes);
        withNode.resolve(5, box(10, 20, 40, 30));
        double dy = withNode.resolve(5, box(15, 22, 45, 32));
        assertEquals(2 * STACK, dy,
            "the first stack slot lands on a node, so the label takes the next clear slot");
        // Positive control (mixed-fixture rule): the SAME collision WITHOUT the node stacks to the
        // first slot — so the skip above is caused by the node, not a vacuous always-2*STACK.
        FlowchartLayout.LabelDecollider noNode = decollider();
        noNode.resolve(5, box(10, 20, 40, 30));
        assertEquals(STACK, noNode.resolve(5, box(15, 22, 45, 32)),
            "with no node in the way the same collision stacks to the FIRST slot");
    }

    @Test
    void mixedFixtureCollidingLabelStacksWhileNonCollidingStaysPut() {
        // Mixed-fixture rule: a genuinely-colliding case beside a non-colliding one in the SAME
        // decollider, proving the pass is SELECTIVE (fires on overprint, inert otherwise) rather than
        // blanket. Target 5 gets a colliding pair (must stack); target 7 gets an x-separated pair to
        // the same y-band (must NOT stack) — the non-colliding negative that keeps the positive honest.
        FlowchartLayout.LabelDecollider d = decollider();
        d.resolve(5, box(10, 20, 40, 30));
        assertEquals(STACK, d.resolve(5, box(15, 22, 45, 32)),
            "colliding same-target label (positive): stacks");
        d.resolve(7, box(100, 20, 130, 30));
        assertEquals(0.0, d.resolve(7, box(140, 20, 170, 30)),
            "x-separated same-target label (negative, same fixture): unmoved — dy==0");
    }
}
