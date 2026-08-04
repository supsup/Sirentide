package com.sirentide.layout;

/// The GLOBAL, cross-diagram layout-time work budget (robustness plan fe8c5bbc, slice 2).
///
/// WHY A GLOBAL ONE EXISTS. The 5 MB output cap ({@code Sirentide.MAX_OUTPUT_BYTES}, mirrored
/// incrementally inside {@code SvgEmitter}) is the only backstop on scene size, and it can only fire
/// once EMIT starts — i.e. after layout has already built and RETAINED the whole shape list. So it
/// structurally cannot catch a blow-up that happens DURING layout. Slice 1 closed the individual
/// blow-up PATHS it could name (per-segment dash pieces in {@link FlowchartLayout#MAX_DASH_PIECES},
/// column relaxation in {@link SankeyLayout#MAX_COLUMN_RELAXATION_WORK}, timeline label
/// materialization, sequence notes, root-system projection). A per-path cap bounds ONE path; it says
/// nothing about the SUM. A legal 14 KB flowchart of 500 nodes and 1000 dotted forward edges, EVERY
/// segment of which sits comfortably under {@code MAX_DASH_PIECES}, still retains ~550,000 {@link
/// Line} shapes (~30 MB) before the emitter is ever entered — see
/// {@code GlobalLayoutBudgetTest.compositeDottedFlowchartUnderEveryPerPathCapTripsTheGlobalBudget}.
/// This budget is the AGGREGATE fence those per-path caps cannot be.
///
/// WHERE IT IS CHARGED. At SHAPE CONSTRUCTION — every {@link Shape} record's compact constructor
/// calls {@link #charge}. That is the one funnel every layout's geometry and every retained glyph
/// path passes through, so the budget covers all 25 dispatch cases and all 43 layout classes without
/// editing any of them (and without touching {@link FlowchartLayout}'s hot code, which two unmerged
/// branches also edit). It is a WORK budget, not a retention budget: a shape that is built and then
/// discarded still charges, because building it was the work.
///
/// WHERE IT IS SCOPED. {@code Sirentide.layout(Diagram, MathFragmentRenderer)} — the single dispatch
/// seam every public entry point (`render`, `renderFrames`, and both `*WithDiagnostics` twins) routes
/// through — brackets the whole switch in {@link #enterLayout()} / {@link #exitLayout(Scope)}. Outside
/// that bracket the budget is UNARMED and {@link #charge} is a null-check no-op, so a direct
/// `FlowchartLayout.layout(...)` call from a test or an embedder is unchanged, and a pooled render
/// thread retains no state between bakes (the {@link ThreadLocal} is removed when the outermost scope
/// exits). Nesting is safe by save-and-restore: a {@code MathFragmentRenderer} callback may legally
/// re-enter `render` on this thread (see the plain-render boundary in `Sirentide`), and that inner
/// bake gets its OWN fresh scope while the outer one is restored afterwards.
///
/// WHY THE LIMIT IS THE OUTPUT CAP. Each weight below is a strict LOWER BOUND on the bytes that shape
/// costs the emitter (`<line …/>` is at least 63 bytes and is charged 48; a {@link GlyphRun} costs at
/// least `21 + pathD.length()` and is charged `16 + pathD.length()`). So for any scene,
/// `chargedWork <= emittedBytes`. Therefore a scene that trips a budget of {@code MAX_OUTPUT_BYTES}
/// would ALSO have blown the 5 MB emit cap and degraded to the inert shell anyway: the budget changes
/// no successful bake's bytes, it only moves an already-doomed one's abort EARLIER — before the
/// gigabytes are retained. That derivation is what makes the constant defensible rather than
/// arbitrary, and it is pinned by
/// {@code GlobalLayoutBudgetTest.everyShapeWeightIsALowerBoundOnItsEmittedBytes}.
///
/// SCOPE NOT COVERED (stated so it is not mistaken for coverage): `CaptionLayout.withCaption` runs
/// AFTER the dispatch returns and is therefore unbudgeted — its output is bounded by one wrapped
/// caption band of `MAX_LABEL_LEN` text. Emit-side and frame-side aggregation stay owned by
/// {@code MAX_OUTPUT_BYTES} / {@code MAX_TOTAL_OUTPUT_BYTES}.
public final class LayoutWorkBudget {

    /// The global cap on a single layout's shape work, in "lower-bound emitted bytes" (see the class
    /// doc for the derivation). Deliberately EQUAL to {@code Sirentide.MAX_OUTPUT_BYTES} — duplicated
    /// here rather than imported to avoid a layout↔api package cycle, exactly as
    /// {@code SvgEmitter.MAX_OUTPUT_BYTES} is. Because every weight under-counts the real emitted
    /// bytes, tripping this is a PROOF that the bake could not have fit in the output cap.
    public static final long MAX_LAYOUT_SHAPE_WORK = 5_000_000L;

    /// `<line x1=".." y1=".." x2=".." y2=".." stroke=".." stroke-width=".."/>` — 57 literal chars
    /// plus five numbers and a colour, so ≥ 63 emitted bytes.
    static final int WEIGHT_LINE = 48;
    /// `<rect x=".." y=".." width=".." height=".." fill=".."/>` — 45 literal chars plus four numbers
    /// and a colour, so ≥ 50 emitted bytes.
    static final int WEIGHT_RECT = 40;
    /// A wedge emits `<path d="M… L… A…"/>` — the arc path alone carries seven numbers.
    static final int WEIGHT_WEDGE = 32;
    /// `<g data-sirentide-role=".." data-sirentide-id=".." data-sirentide-seq="..">` + `</g>` —
    /// ≥ 76 emitted bytes. The members charge themselves.
    static final int WEIGHT_GROUP = 32;
    /// The fixed part of a `d`/`innerSvg`-bearing shape (`<path d="` + `" fill="` + colour + `"/>`
    /// ≥ 21); the variable part is charged as the path string's exact length.
    static final int WEIGHT_PATH_BASE = 16;

    /// Nullable by design (no-retention, mirroring {@code EmittedText}): an unarmed shape
    /// construction reads null and allocates nothing.
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private LayoutWorkBudget() {}

    /// One armed layout's running total against one limit. Opaque to callers — the only thing they do
    /// with a `Scope` is hand the displaced one back to {@link #exitLayout(Scope)}.
    public static final class Scope {
        private final long limit;
        private long used;

        private Scope(long limit) {
            this.limit = limit;
        }

        /// Work charged so far, for tests and for the breach message.
        long used() {
            return used;
        }
    }

    /// Arm a fresh budget scope on this thread at the production limit. Returns the scope this call
    /// DISPLACED (null at top level) — pass it to {@link #exitLayout(Scope)} in a finally.
    public static Scope enterLayout() {
        return enterLayout(MAX_LAYOUT_SHAPE_WORK);
    }

    /// {@link #enterLayout()} with an EXPLICIT limit. The exact allow/deny boundary tests use this so
    /// they measure the budget MECHANISM against their own supplied number rather than re-asserting
    /// the production constant — the same shape {@link SankeyLayout#relaxColumns} takes its budget in.
    static Scope enterLayout(long limit) {
        Scope displaced = CURRENT.get();
        CURRENT.set(new Scope(limit));
        return displaced;
    }

    /// Restore the scope {@link #enterLayout()} displaced, removing the ThreadLocal entirely when the
    /// outermost scope exits so nothing outlives a bake on a pooled thread.
    public static void exitLayout(Scope displaced) {
        if (displaced == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(displaced);
        }
    }

    /// Charge `amount` lower-bound emitted bytes against this thread's armed scope. A no-op when
    /// nothing is armed. Overflow-safe (`used > limit - amount` rather than `used + amount > limit`).
    /// The breach message carries BOTH the shared `MAX_LAYOUT_WORK` token every layout budget throws
    /// — which `Sirentide.classifyFailure` maps to `Outcome.OUTPUT_CAP_EXCEEDED` at stage `layout`,
    /// not `RENDER_BUG` — and this budget's own named constant, so the diagnostic detail says WHICH
    /// budget aborted.
    static void charge(long amount) {
        Scope scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        if (amount < 0 || scope.used > scope.limit - amount) {
            throw new IllegalStateException(
                "MAX_LAYOUT_WORK exceeded: global layout shape/glyph work passed "
                    + "MAX_LAYOUT_SHAPE_WORK (" + scope.limit
                    + " lower-bound emitted bytes) after " + scope.used + " charged");
        }
        scope.used += amount;
    }

    /// Test probe: work charged so far on this thread, or -1 when unarmed.
    static long charged() {
        Scope scope = CURRENT.get();
        return scope == null ? -1 : scope.used();
    }

    /// Test probe for the no-retention contract: does THIS thread still hold budget state?
    static boolean hasThreadState() {
        return CURRENT.get() != null;
    }
}
