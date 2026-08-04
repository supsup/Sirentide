package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Flowchart;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Red-first discriminators for the GLOBAL layout-time work budget — plan fe8c5bbc SLICE 2
/// ({@link LayoutWorkBudget}).
///
/// THE ACCEPTANCE PROBLEM THESE TESTS EXIST TO SOLVE. Slice 1 already caps the individual blow-up
/// paths it could name (per-segment dash pieces, sankey column relaxation, timeline label
/// materialization, sequence notes, root-system projection), and every pathological input the plan
/// originally listed is caught by one of THOSE caps — so none of them can tell slice 1 and slice 2
/// apart. A test that only re-runs them would be green before this change and green after it, which
/// is no evidence at all. The discriminator below is therefore built to a stricter spec: an input on
/// which slice 1's per-path cap is PROVABLY INERT (raising {@link FlowchartLayout#MAX_DASH_PIECES} to
/// {@code Integer.MAX_VALUE} changes nothing, because no segment ever reaches it) and whose AGGREGATE
/// still runs away — because a per-path cap bounds one path and says nothing about their sum.
class GlobalLayoutBudgetTest {

    private static final String INERT_SHELL =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"></svg>";

    /// Mirrors {@code Sirentide.MAX_OUTPUT_BYTES} / {@code SvgEmitter.MAX_OUTPUT_BYTES}. The budget's
    /// limit is defined independently at DOUBLE this (see {@link LayoutWorkBudget}'s class doc for the
    /// two-sided breach proof; the old `limit == cap` derivation was refuted at sirentide/812).
    private static final int MAX_OUTPUT_BYTES = 5_000_000;

    /// The composite falsifier's shape: a 500-node LR chain (the parser's {@code MAX_NODES}) plus
    /// forward-skip DOTTED edges spanning 70 ranks each, filling the parser's {@code MAX_EDGES}. At the
    /// resulting ~99.5 px node pitch a 70-rank span is ~6 965 px = ~995 dash pieces, which sits UNDER
    /// the 1 000-piece per-segment cap — deliberately, so slice 1 never fires (asserted below).
    private static final int NODES = 500;
    private static final int SKIP = 70;
    private static final int MAX_EDGES = 1000;

    private static String compositeDottedFlowchart() {
        StringBuilder dsl = new StringBuilder("flowchart LR\n");
        for (int i = 0; i < NODES - 1; i++) {
            dsl.append('n').append(i).append(" --> n").append(i + 1).append('\n');
        }
        int dotted = MAX_EDGES - (NODES - 1);
        int written = 0;
        while (written < dotted) {
            for (int i = 0; i + SKIP < NODES && written < dotted; i++, written++) {
                dsl.append('n').append(i).append(" -.-> n").append(i + SKIP).append('\n');
            }
        }
        return dsl.toString();
    }

    private static int leafShapeCount(Flowchart fc) {
        return Group.flatten(FlowchartLayout.layout(fc, (com.sirentide.api.MathFragmentRenderer) null).shapes()).size();
    }

    // ---- THE DISCRIMINATOR ---------------------------------------------------------------------

    /// RED AT BASE / GREEN AT TIP, on an input slice 1 provably does not cover.
    ///
    /// (a) NON-COVERAGE. Laying the diagram out with {@link FlowchartLayout#MAX_DASH_PIECES} at its
    ///     production value and again at {@code Integer.MAX_VALUE} yields the IDENTICAL leaf-shape
    ///     count. The per-segment cap therefore never collapsed a single segment on this input: it is
    ///     inert here, and no other slice-1 cap is even on this code path (no sankey relaxation, no
    ///     timeline labels, no sequence notes, no root system). A third layout at
    ///     {@code MAX_DASH_PIECES = 1} produces a much SMALLER count, which proves the equality above
    ///     is a real measurement of the cap and not two readings of a cap-free path.
    ///
    /// (b) THE RUNAWAY. Unbudgeted (a direct layout call arms no scope) that legal ~14 KB source
    ///     retains more than half a MILLION {@link Line} shapes — tens of MB of live layout state —
    ///     and the 5 MB emit cap cannot help, because it only starts checking once emit begins, i.e.
    ///     after all of it has already been built. That is the OOM window slice 2 exists to close, and
    ///     at base it is exactly what a plain {@code render} walks into.
    ///
    /// (c) THE FENCE. Through the public API the budget aborts the layout instead, and the abort is
    ///     classified as the KNOWN bounded degrade it is — {@link Outcome#OUTPUT_CAP_EXCEEDED} at
    ///     stage `layout`, detail naming {@code MAX_LAYOUT_SHAPE_WORK} — with the bake returning the
    ///     byte-identical inert shell the never-throw contract requires.
    ///
    /// AT BASE (7bec809) (a) and (b) pass unchanged and (c) FAILS: with no global budget the bake
    /// still degrades to the inert shell, but only after the full retention, and the diagnostic reads
    /// stage `emit` / `MAX_OUTPUT_BYTES`. Asserting the SHELL alone would have been vacuous — the base
    /// returns the same 85 bytes — so the stage and the named constant are the load-bearing
    /// assertions, and the retention measurement in (b) is what makes the fence worth having.
    @Test
    void compositeDottedFlowchartUnderEveryPerPathCapTripsTheGlobalBudget() {
        String dsl = compositeDottedFlowchart();
        assertTrue(dsl.length() < DslParser.MAX_SOURCE_BYTES,
            "the source stays legal input (" + dsl.length() + " bytes), so this is not an input-cap case");
        Diagram ir = DslParser.parse(dsl);
        assertTrue(ir instanceof Flowchart, "the corpus must actually parse as a flowchart");
        Flowchart fc = (Flowchart) ir;

        // (a) slice 1's per-path cap is INERT on this input.
        int production = FlowchartLayout.MAX_DASH_PIECES;
        int atProductionCap;
        int uncapped;
        int atCapOfOne;
        try {
            FlowchartLayout.MAX_DASH_PIECES = production;
            atProductionCap = leafShapeCount(fc);
            FlowchartLayout.MAX_DASH_PIECES = Integer.MAX_VALUE;
            uncapped = leafShapeCount(fc);
            FlowchartLayout.MAX_DASH_PIECES = 1;
            atCapOfOne = leafShapeCount(fc);
        } finally {
            FlowchartLayout.MAX_DASH_PIECES = production;
        }
        assertEquals(uncapped, atProductionCap,
            "slice 1's per-segment dash cap must never fire on this input — every segment is under it, "
                + "so the aggregate blow-up below is a gap the per-path cap structurally cannot close");
        assertTrue(atCapOfOne < atProductionCap / 2,
            "positive control: MAX_DASH_PIECES genuinely governs this path (a cap of 1 collapses it to "
                + atCapOfOne + " shapes), so the equality above is a measurement, not a no-op");

        // (b) the unbudgeted runaway the emit cap cannot reach.
        assertTrue(atProductionCap > 500_000,
            "the aggregate must actually run away for this to be a falsifier, was " + atProductionCap);
        long dashLines = Group.flatten(FlowchartLayout.layout(fc, (com.sirentide.api.MathFragmentRenderer) null).shapes()).stream()
            .filter(Line.class::isInstance).count();
        assertTrue(dashLines * LayoutWorkBudget.WEIGHT_LINE > 2L * LayoutWorkBudget.MAX_LAYOUT_SHAPE_WORK,
            "the scene's " + dashLines + " RETAINED Line shapes alone are worth more than double the "
                + "10 MB work budget (and four times the 5 MB emit cap) — all of it built and held "
                + "before the emitter is ever entered, so the trip below is forced by retained work "
                + "with margin, not by any charge/emit accounting subtlety");

        // (c) the global budget fences it, with the diagnostic the plan pins.
        RenderResult result = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, result.diagnostics().outcome(),
            "a named layout budget is a KNOWN bounded degrade, never a RENDER_BUG");
        assertEquals("layout", result.diagnostics().stage(),
            "the abort must be attributed to LAYOUT — attributing it to emit is the base behaviour, "
                + "and means the scene was fully retained before anything noticed");
        assertTrue(result.diagnostics().detail().contains("MAX_LAYOUT_SHAPE_WORK"),
            "the detail must name the exact budget that aborted: " + result.diagnostics().detail());
        assertEquals(INERT_SHELL, result.svg(), "the degrade target is the literal inert shell");
        assertEquals(Sirentide.render(dsl), result.svg(),
            "the diagnostic bake stays byte-identical to the guarded render");
        assertEquals(List.of(INERT_SHELL), Sirentide.renderFrames(dsl),
            "the play-through entry point routes through the same seam and takes the same degrade");
    }

    // ---- The behaviour change this budget makes to an ALREADY-degrading input -------------------

    /// A 10 000-slice pie was already over the output cap and already degraded to the inert shell —
    /// but only AFTER layout had built ~35 MB of shape work for the emitter to choke on. The budget
    /// moves that abort to layout. The BYTES do not change (same inert shell, same
    /// `render`/`renderWithDiagnostics` identity, same OUTPUT_CAP_EXCEEDED outcome); only the stage
    /// the degrade is attributed to does, and only for scenes that could never have fitted the cap.
    /// This is the one publicly observable change slice 2 makes to existing behaviour, so it is pinned
    /// rather than left implicit — the complement of the still-live emit-stage band pinned by
    /// {@code RenderDiagnosticsTest.outputCapDslReportsCapExceeded}.
    @Test
    void tenThousandSlicePieNowAbortsAtLayoutWithTheSameBytes() {
        StringBuilder dsl = new StringBuilder("pie\n");
        for (int i = 0; i < 10_000; i++) {
            dsl.append("  \"A").append(i).append("\" : 1\n");
        }
        String source = dsl.toString();
        RenderResult result = Sirentide.renderWithDiagnostics(source);

        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, result.diagnostics().outcome(),
            "the outcome is UNCHANGED by slice 2 — it was a known cap degrade before and still is");
        assertEquals(INERT_SHELL, result.svg(), "the degrade BYTES are unchanged");
        assertEquals(Sirentide.render(source), result.svg(), "render/diagnostics byte identity holds");
        assertEquals("layout", result.diagnostics().stage(),
            "what DOES change: the abort now happens before the ~35 MB of shape work is retained");
        assertTrue(result.diagnostics().detail().contains("MAX_LAYOUT_SHAPE_WORK"),
            "and it names the budget responsible: " + result.diagnostics().detail());
    }

    // ---- The mechanism, measured against an EXPLICIT budget --------------------------------------

    /// The exact allow/deny boundary, parameterized by a budget the test supplies itself — the same
    /// shape {@link SankeyLayout#relaxColumns} takes its limit in. Measuring the MECHANISM against a
    /// supplied number rather than re-asserting the production constant keeps this test honest about
    /// what it proves: that the counter admits the charge that exactly reaches the limit and rejects
    /// the very next unit, independently of where the production limit happens to sit.
    @Test
    void theExactBudgetIsSpentAndTheNextChargeIsRefused() {
        LayoutWorkBudget.Scope displaced = LayoutWorkBudget.enterLayout(3 * LayoutWorkBudget.WEIGHT_LINE);
        try {
            new Line(0, 0, 1, 1, "#000000", 1);
            new Line(0, 0, 1, 1, "#000000", 1);
            new Line(0, 0, 1, 1, "#000000", 1);
            assertEquals(3L * LayoutWorkBudget.WEIGHT_LINE, LayoutWorkBudget.charged(),
                "three lines spend the budget exactly, with nothing left over");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new Line(0, 0, 1, 1, "#000000", 1),
                "the first charge past the supplied budget aborts");
            assertTrue(refused.getMessage().contains("MAX_LAYOUT_WORK"),
                "the breach carries the shared token Sirentide.classifyFailure keys on");
            assertTrue(refused.getMessage().contains("MAX_LAYOUT_SHAPE_WORK"),
                "and this budget's own named constant, so the detail says WHICH budget aborted");
        } finally {
            LayoutWorkBudget.exitLayout(displaced);
        }
    }

    /// THE RETAINED-SHAPE LEG OF THE DERIVATION, PINNED. Every per-shape weight UNDER-counts the
    /// bytes that shape costs the emitter WHEN IT IS RETAINED — so any scene that fits the 5 MB
    /// output cap carries less than 5 MB of lower-bound work in retained shapes, which is one half of
    /// the two-sided breach proof in {@link LayoutWorkBudget}'s class doc (the other half — discarded
    /// construction — is deliberately NOT covered by this bound; sirentide/812 refuted the old
    /// scene-wide `chargedWork <= emittedBytes` reading, and the work-budget semantics for discards
    /// are pinned by {@code transientConstructionAloneTripsTheProductionBudget} below). This measures
    /// both sides for every shape in the sealed hierarchy: what the budget charges, and what
    /// {@link SvgEmitter} really writes (by differencing an emit with and without the shape). Raise
    /// any weight above its true emitted cost and this fails — which is the mutation that would let
    /// the budget reject a diagram that WOULD have rendered.
    @Test
    void everyShapeWeightIsALowerBoundOnItsEmittedBytes() {
        String shortPath = "M0 0L1 1";
        assertWeightIsLowerBound("Line", () -> new Line(0, 0, 1, 1, "#000000", 1));
        assertWeightIsLowerBound("Rect", () -> new Rect(0, 0, 1, 1, "#000000"));
        assertWeightIsLowerBound("Wedge", () -> new Wedge(0, 0, 1, 0, 1, "#000000"));
        assertWeightIsLowerBound("GlyphRun", () -> new GlyphRun(shortPath, "#000000"));
        assertWeightIsLowerBound("Path", () -> new Path(shortPath, "#000000"));
        assertWeightIsLowerBound("MathBox", () -> new MathBox(0, 0, "#000000", "<path d=\"M0 0\"/>"));
        assertWeightIsLowerBound("Group", () -> new Group(new Anchor(SirentideRole.NODE, "n", 0), List.of()));
    }

    /// THE DISCARD LEG, PINNED — the discriminator sirentide/812 required. This is a WORK budget:
    /// construction charges whether or not the shape is retained, so transient construction ALONE
    /// trips the PRODUCTION limit even though nothing is retained and the emitted output (an empty
    /// scene, bytes below any cap) is as small as output gets. Under charge-at-retained-state
    /// semantics this loop would never trip — which is exactly what the count assertion
    /// discriminates: the budget refuses the FIRST construction past the limit, at the position the
    /// production constant and the Line weight jointly predict, so the charge provably happened at
    /// construction time for every discarded shape, not at retention or emit. This is the documented
    /// behavior change of the work-bound semantics (see the class doc): a hypothetical bake doing
    /// this much discarded construction — ~208k thrown-away Lines, an entire output cap's worth
    /// twice over — is aborted as the CPU/allocation runaway it is, small output notwithstanding.
    /// No current layout can reach this band (every construction site retains, per the census in
    /// the class doc); the funnel is exercised directly because the funnel IS the production charge
    /// point every future discard site must pass through.
    @Test
    void transientConstructionAloneTripsTheProductionBudget() {
        long expectedConstructions = LayoutWorkBudget.MAX_LAYOUT_SHAPE_WORK / LayoutWorkBudget.WEIGHT_LINE;
        LayoutWorkBudget.Scope displaced = LayoutWorkBudget.enterLayout(); // the PRODUCTION limit
        try {
            long constructed = 0;
            IllegalStateException breach = null;
            while (breach == null) {
                try {
                    new Line(0, 0, 1, 1, "#000000", 1); // built, never stored anywhere: pure discard
                    constructed++;
                } catch (IllegalStateException e) {
                    breach = e;
                }
            }
            assertEquals(expectedConstructions, constructed,
                "the budget must refuse the first DISCARDED construction past the production limit — "
                    + "charge-at-retention semantics would never have tripped here at all");
            assertTrue(breach.getMessage().contains("MAX_LAYOUT_SHAPE_WORK"),
                "the breach names the work budget: " + breach.getMessage());
            assertTrue((expectedConstructions + 1) * LayoutWorkBudget.WEIGHT_LINE > 2L * MAX_OUTPUT_BYTES,
                "and the trip fires only once ATTEMPTED construction exceeds two whole output caps' "
                    + "worth (the charged total sits within one shape-weight below it), so no scene "
                    + "that could ever have fitted the cap is in reach of this band");
        } finally {
            LayoutWorkBudget.exitLayout(displaced);
        }
    }

    private static void assertWeightIsLowerBound(String name, java.util.function.Supplier<Shape> make) {
        Shape shape;
        long charged;
        // Arm ONLY around construction: the charge is read from the budget itself, never recomputed
        // here from the weights (a re-derivation would agree with a wrong weight by construction).
        LayoutWorkBudget.Scope displaced = LayoutWorkBudget.enterLayout(Long.MAX_VALUE);
        try {
            shape = make.get();
            charged = LayoutWorkBudget.charged();
        } finally {
            LayoutWorkBudget.exitLayout(displaced);
        }
        int empty = SvgEmitter.emit(LaidOut.of(10, 10)).length();
        int withShape = SvgEmitter.emit(new LaidOut(10, 10, List.of(shape))).length();
        int emitted = withShape - empty;
        assertTrue(charged > 0, name + " must charge something");
        assertTrue(charged <= emitted,
            name + " charges " + charged + " but only emits " + emitted + " bytes — the weight must be "
                + "a LOWER bound, otherwise the budget can reject a scene that would have rendered");
    }

    // ---- Scoping: no leakage, no clobbering, no effect when unarmed ------------------------------

    /// NO RETENTION and NO SIDE EFFECT WHEN UNARMED. A completed bake leaves no budget state on this
    /// thread (a pooled render thread must not carry a spent budget into the next diagram), and a
    /// direct layout call outside the dispatch seam is entirely unbudgeted — so an embedder or a
    /// per-type layout test that calls {@code FlowchartLayout.layout(...)} straight is unaffected.
    @Test
    void theBudgetIsScopedToTheDispatchSeamAndLeavesNoThreadState() {
        assertFalse(LayoutWorkBudget.hasThreadState(), "no budget state before a bake");
        Sirentide.render("pie\n  \"A\" : 1\n  \"B\" : 2\n");
        assertFalse(LayoutWorkBudget.hasThreadState(), "no budget state survives a completed bake");
        Sirentide.render(compositeDottedFlowchart());
        assertFalse(LayoutWorkBudget.hasThreadState(),
            "no budget state survives a bake that ABORTED on the budget either");

        // Unarmed: the falsifier's full runaway is built without complaint by a direct layout call.
        assertEquals(-1L, LayoutWorkBudget.charged(), "charged() reports unarmed");
        Flowchart fc = (Flowchart) DslParser.parse(compositeDottedFlowchart());
        assertTrue(leafShapeCount(fc) > 500_000,
            "a direct layout call is unbudgeted — the public API is the enforcement boundary");
    }

    /// NESTING. A {@link com.sirentide.api.MathFragmentRenderer} is an application callback with no
    /// non-reentrancy contract: it may legally call `render` again on this thread (the same hazard
    /// {@code EmittedText}'s plain-render boundary exists for). The inner bake must get its OWN budget
    /// and must NOT consume, reset, or clobber the outer one. Measured by charging an outer scope,
    /// running a whole nested render inside it, and checking the outer total is untouched.
    @Test
    void aNestedRenderGetsItsOwnScopeAndRestoresTheOuterOne() {
        LayoutWorkBudget.Scope displaced = LayoutWorkBudget.enterLayout(10_000);
        try {
            new Line(0, 0, 1, 1, "#000000", 1);
            long before = LayoutWorkBudget.charged();
            assertEquals((long) LayoutWorkBudget.WEIGHT_LINE, before);

            String nested = Sirentide.render("pie\n  \"A\" : 1\n  \"B\" : 2\n");
            assertTrue(nested.length() > INERT_SHELL.length(),
                "the nested bake really rendered (its own fresh budget was ample), was " + nested);

            assertEquals(before, LayoutWorkBudget.charged(),
                "the outer scope is restored with its running total intact — a nested render neither "
                    + "spends the outer budget nor resets it");
            assertEquals(Sirentide.render("pie\n  \"A\" : 1\n  \"B\" : 2\n"), nested,
                "and the nested bake is byte-identical to the same render at top level");
        } finally {
            LayoutWorkBudget.exitLayout(displaced);
        }
        assertFalse(LayoutWorkBudget.hasThreadState(), "the outermost exit removes the ThreadLocal");
    }
}
