package com.sirentide.api;

import com.sirentide.emit.Emphasis;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Flowchart;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.Pie;
import com.sirentide.ir.QuadrantChart;
import com.sirentide.ir.Sequence;
import com.sirentide.ir.StateDiagram;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.FlowchartLayout;
import com.sirentide.layout.GanttLayout;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.PieLayout;
import com.sirentide.layout.QuadrantChartLayout;
import com.sirentide.layout.SequenceLayout;
import com.sirentide.layout.StateDiagramLayout;
import com.sirentide.layout.TimelineLayout;
import com.sirentide.layout.XyChartLayout;

/// Public entry point. The bake pipeline: DSL → parse → IR → layout (→ coordinates) → emit
/// (→ SVG string). Zero runtime dependency, deterministic, sanitizer-clean output
/// (docs/DESIGN.md §2/§4).
///
/// M1 (in progress): the first diagram type — `pie` — renders end to end. xychart + a minimal
/// linear sequence (with the play-through) follow, all projecting into the shared IR.
public final class Sirentide {

    private Sirentide() {}

    /// The inert, contract-clean empty SVG shell — the universal degrade target. Byte-identical to
    /// what the emitter produces for {@link Empty}. Kept as a literal so it can be returned even
    /// when the emitter itself is the thing that threw.
    static final String INERT_SHELL =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"></svg>";

    /// Cap on total baked output. Past this the bake degrades to the inert shell rather than
    /// returning a runaway document (DESIGN §6/§7: malformed/oversized → inert, never throw). The
    /// emitter ({@link SvgEmitter}) enforces the same cap INCREMENTALLY during construction so a
    /// runaway layout can't build a multi-GB buffer before this post-emit check ever runs (H2).
    public static final int MAX_OUTPUT_BYTES = 5_000_000;   // 5 MB of SVG

    /// Bake a Sirentide DSL source into a self-contained SVG string. Honors the "malformed →
    /// inert, never throw" invariant (DESIGN §6/§7): ANY unexpected failure in parse/layout/emit,
    /// or an over-cap output, degrades to the inert empty shell instead of propagating.
    public static String render(String dsl) {
        return render(dsl, null);
    }

    /// Bake with inline-math support: `$…$` runs in a flowchart node label are rendered through
    /// `math` (RFC sirentide/39). A `null` renderer is exactly {@link #render(String)} — the
    /// feature is off and `$` renders as a literal glyph, byte-identical to the pre-feature output.
    /// Same malformed→inert invariant; a throwing renderer is caught per-fragment (degrades that
    /// run to raw text) and never propagates a bake.
    public static String render(String dsl, com.sirentide.api.MathFragmentRenderer math) {
        try {
            // The leading config block (%% title/theme/direction) — read independently of the body
            // parse; DiagramConfig.DEFAULT (no config) threads a byte-identical bake (no title
            // override, Theme.DEFAULT = no bg rect + no colour remap → option A).
            com.sirentide.ir.DiagramConfig config = com.sirentide.parse.DslParser.parseConfig(dsl);
            Diagram ir = com.sirentide.parse.DslParser.parse(dsl);
            LaidOut laid = layout(ir, math);
            // Deterministic accessibility, baked in: a root role="img" + a <title>/<desc> built
            // purely from the IR (roles + label text, fixed order — no timestamps/random). A blank
            // payload (the Empty degrade target) emits nothing, so the inert shell is unchanged. The
            // config `title` (when present) overrides the derived accessible name.
            com.sirentide.a11y.A11y a11y =
                com.sirentide.a11y.A11yDescriber.describe(ir, config.title());
            String svg = SvgEmitter.emit(laid, a11y, config.theme());
            if (svg.length() > MAX_OUTPUT_BYTES) {
                return INERT_SHELL;
            }
            return svg;
        } catch (RuntimeException | StackOverflowError e) {
            // Last-resort bake guard: never let a renderer bug surface as a thrown bake. OutOfMemoryError
            // is DELIBERATELY NOT caught — swallowing it into the inert shell leaves the JVM in an
            // undefined state and can corrupt concurrent bakes on the same worker, so we fail fast on
            // genuine heap exhaustion. The emitter's incremental MAX_OUTPUT_BYTES cap plus the label
            // ellipsization in every layout keep normal operation from ever reaching that point (H2).
            return INERT_SHELL;
        }
    }

    /// Bake a diagram into a PLAY-THROUGH: N static SVG frames, one per distinct `data-sirentide-seq`
    /// step, in seq order (plan sirentide-play-through-frames). This is the first CONSUMER of the
    /// semantic anchors — the seq step-ordering already stamped on every element's `<g>` is inert data
    /// until something reads it; this reads it into a slideshow. Frame `k` EMPHASIZES the step whose
    /// seq == k and de-emphasizes the rest (cumulative "playing forward": steps before k are shown
    /// done/normal, step k is accented, steps after k are dimmed), all IN-ALPHABET (fill/stroke tint,
    /// no alpha) — so each frame is a standalone CSP-clean bake (no script/style/animation/:target),
    /// a static SVG a doc can flip through. See {@link #renderFrames(String, MathFragmentRenderer)}.
    public static java.util.List<String> renderFrames(String dsl) {
        return renderFrames(dsl, null);
    }

    /// The play-through bake with inline-math support (mirrors {@link #render(String,
    /// MathFragmentRenderer)}). LAYOUT RUNS ONCE — the geometry is identical across every frame; only
    /// the per-group emphasis (fill/stroke) is re-emitted, so the frames are deterministic and
    /// byte-stable (same dsl → same List). Returns one frame per distinct seq present, in ascending
    /// seq order; a diagram with NO seq anchors (or exactly one) yields a SINGLE frame that is
    /// BYTE-IDENTICAL to {@link #render(String, MathFragmentRenderer)} (there is nothing to play
    /// through). Honors the same never-throw invariant as `render`: ANY failure degrades to a single
    /// frame == the guarded `render` output, never a propagated bake.
    public static java.util.List<String> renderFrames(String dsl, com.sirentide.api.MathFragmentRenderer math) {
        try {
            com.sirentide.ir.DiagramConfig config = com.sirentide.parse.DslParser.parseConfig(dsl);
            Diagram ir = com.sirentide.parse.DslParser.parse(dsl);
            // Layout ONCE — every frame re-emits THIS scene with a different emphasis map, so the
            // geometry can never drift between frames (only fills/strokes differ). Deterministic.
            LaidOut laid = layout(ir, math);
            com.sirentide.a11y.A11y a11y =
                com.sirentide.a11y.A11yDescriber.describe(ir, config.title());

            // The no-emphasis bake — BYTE-IDENTICAL to render(dsl, math) (same emit, null emphasis).
            String base = SvgEmitter.emit(laid, a11y, config.theme());
            if (base.length() > MAX_OUTPUT_BYTES) {
                return java.util.List.of(INERT_SHELL);
            }

            // The distinct seq values present on the anchored groups, ascending = the play order.
            java.util.TreeSet<Integer> seqs = new java.util.TreeSet<>();
            collectSeqs(laid.shapes(), seqs);
            // No seq (or a single step) → nothing to play through: one frame == the static render.
            if (seqs.size() <= 1) {
                return java.util.List.of(base);
            }

            java.util.List<String> frames = new java.util.ArrayList<>(seqs.size());
            for (int k : seqs) {
                String svg = SvgEmitter.emit(laid, a11y, config.theme(), Emphasis.cumulative(k));
                // Emphasis is a pure recolour of the ONE scene, so an emphasized frame can only shrink
                // or hold the byte length vs the (already-capped) base — the guard is defensive.
                if (svg.length() > MAX_OUTPUT_BYTES) {
                    return java.util.List.of(INERT_SHELL);
                }
                frames.add(svg);
            }
            return java.util.List.copyOf(frames);
        } catch (RuntimeException | StackOverflowError e) {
            // Never throw (DESIGN §6/§7): degrade to a single frame == the guarded static render. A
            // malformed/no-seq source thus always yields exactly [render(dsl, math)].
            return java.util.List.of(render(dsl, math));
        }
    }

    /// Collect the distinct `seq` values stamped on the anchor {@link com.sirentide.layout.Group}s in a
    /// laid-out scene (groups never nest, but recurse defensively). These are the play-through steps.
    private static void collectSeqs(java.util.List<com.sirentide.layout.Shape> shapes,
                                    java.util.TreeSet<Integer> into) {
        for (com.sirentide.layout.Shape s : shapes) {
            if (s instanceof com.sirentide.layout.Group g) {
                into.add(g.anchor().seq());
                collectSeqs(g.members(), into);
            }
        }
    }

    /// The pipeline stages, used both as the {@link Diagnostics#stage()} value and to localize which
    /// choke point a caught throwable escaped from (parse vs layout vs emit).
    private static final String STAGE_PARSE = "parse";
    private static final String STAGE_LAYOUT = "layout";
    private static final String STAGE_EMIT = "emit";

    /// Diagnostic twin of {@link #render(String)} — same guarded pipeline, same byte-identical SVG,
    /// PLUS a structured {@link Diagnostics} that says WHY (plan sirentide-render-diagnostics). See
    /// {@link #renderWithDiagnostics(String, MathFragmentRenderer)}.
    public static RenderResult renderWithDiagnostics(String dsl) {
        return renderWithDiagnostics(dsl, null);
    }

    /// Diagnostic twin of {@link #render(String, MathFragmentRenderer)}: it runs the IDENTICAL
    /// parse→layout→emit pipeline and returns a {@link RenderResult} whose `svg` is byte-identical to
    /// what `render` produces (the inert shell on any failure), plus a {@link Diagnostics} that
    /// classifies the outcome. It exists because `render`'s single `catch (RuntimeException |
    /// StackOverflowError)` plus the output-cap degrade collapse EVERY parse-typo, unsupported
    /// construct, output-cap hit, and genuine renderer bug into one indistinguishable blank; an author
    /// whose diagram renders empty gets no signal. This channel adds the signal WITHOUT touching the
    /// sacred inert-shell bake.
    ///
    /// The safety invariant holds here too: this method NEVER throws. It classifies AT the existing
    /// choke points — the pipeline's `catch` (mapping the caught throwable + the stage it escaped to
    /// an {@link Outcome}) and the output-cap branch (a KNOWN degrade, distinct from a failure).
    ///
    /// v1 SCOPING (deliberate): classification keys on the exception TYPE/message and which STAGE
    /// threw, plus a non-blank source that degraded to the {@link Empty} target (an unrecognized
    /// diagram type). Precise line/token attribution — and splitting UNSUPPORTED_CONSTRUCT out of
    /// PARSE_ERROR — needs deep {@code DslParser} annotation that is EXPLICITLY DEFERRED (a concurrent
    /// worker owns that file), so {@link Diagnostics#line()} is `-1` when unknown and an unknown type
    /// folds into {@link Outcome#PARSE_ERROR}. See the record javadocs for the follow-up slots.
    public static RenderResult renderWithDiagnostics(String dsl, com.sirentide.api.MathFragmentRenderer math) {
        String stage = STAGE_PARSE;
        try {
            com.sirentide.ir.DiagramConfig config = com.sirentide.parse.DslParser.parseConfig(dsl);
            Diagram ir = com.sirentide.parse.DslParser.parse(dsl);
            stage = STAGE_LAYOUT;
            LaidOut laid = layout(ir, math);
            stage = STAGE_EMIT;
            // Thread the a11y payload + config theme/title exactly as render() does, so
            // renderWithDiagnostics().svg() stays byte-identical to render().
            com.sirentide.a11y.A11y a11y =
                com.sirentide.a11y.A11yDescriber.describe(ir, config.title());
            String svg = SvgEmitter.emit(laid, a11y, config.theme());
            if (svg.length() > MAX_OUTPUT_BYTES) {
                // The post-emit cap branch — the exact degrade `render` takes. The emitter's
                // incremental guard normally throws FIRST (classified in the catch below); this
                // covers the defensive path where a single shape crossed the cap in one append.
                return new RenderResult(INERT_SHELL, new Diagnostics(
                    Outcome.OUTPUT_CAP_EXCEEDED, STAGE_EMIT,
                    "The baked SVG exceeded the " + MAX_OUTPUT_BYTES + "-byte output cap, so it "
                        + "degraded to the empty shell. Reduce the number of rows/nodes or the label sizes.",
                    -1, "post-emit length " + svg.length() + " > MAX_OUTPUT_BYTES"));
            }
            // The bake SUCCEEDED and `svg` is byte-identical to `render`. One nuance: a NON-BLANK
            // source that resolved to the Empty degrade target means the parser did not recognize the
            // input (an unknown type keyword on line 1, or an over-input-cap source) — the author sees
            // an intentional-looking empty shell but their DSL was never understood. Surface that as a
            // parse-level signal. `svg` is still returned unchanged (== render's output for Empty).
            if (ir instanceof Empty && dsl != null && !dsl.isBlank()) {
                return new RenderResult(svg, new Diagnostics(
                    Outcome.PARSE_ERROR, STAGE_PARSE,
                    "The diagram source was not recognized: line 1's diagram-type keyword is unknown "
                        + "(or the source exceeded the input cap), so it degraded to the empty shell. "
                        + "Check the diagram type on the first line.",
                    -1, "parse resolved to the Empty degrade target for non-blank input"));
            }
            return new RenderResult(svg, new Diagnostics(
                Outcome.OK, STAGE_EMIT, "Rendered successfully.", -1, ""));
        } catch (RuntimeException | StackOverflowError e) {
            // Mirror render's last-resort guard (returns INERT_SHELL) and additionally classify from
            // the caught throwable + the stage it escaped. OutOfMemoryError stays UN-caught here too.
            return new RenderResult(INERT_SHELL, classifyFailure(stage, e));
        }
    }

    /// Maps a throwable caught by the bake guard — plus the pipeline stage it escaped — to a
    /// {@link Diagnostics}. The emitter's incremental output-cap surfaces as an
    /// {@link IllegalStateException} naming {@code MAX_OUTPUT_BYTES}: a KNOWN, bounded degrade
    /// ({@link Outcome#OUTPUT_CAP_EXCEEDED}), NOT a renderer bug — so it is distinguished from a
    /// genuine failure. A throwable from parse is a PARSE_ERROR (the hand-written parser is designed
    /// not to throw, so this is defensive); anything unexpected from layout/emit is a
    /// {@link Outcome#RENDER_BUG}, localized by stage.
    private static Diagnostics classifyFailure(String stage, Throwable e) {
        String msg = e.getMessage();
        String detail = e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
        if (STAGE_EMIT.equals(stage) && msg != null && msg.contains("MAX_OUTPUT_BYTES")) {
            return new Diagnostics(Outcome.OUTPUT_CAP_EXCEEDED, STAGE_EMIT,
                "The baked SVG exceeded the " + MAX_OUTPUT_BYTES + "-byte output cap, so it degraded "
                    + "to the empty shell. Reduce the number of rows/nodes or the label sizes.",
                -1, detail);
        }
        if (STAGE_PARSE.equals(stage)) {
            return new Diagnostics(Outcome.PARSE_ERROR, STAGE_PARSE,
                "The diagram source could not be parsed, so it degraded to the empty shell. Check the "
                    + "syntax on the first line.",
                -1, detail);
        }
        return new Diagnostics(Outcome.RENDER_BUG, stage,
            "The renderer hit an unexpected failure during " + stage + ", so it degraded to the empty "
                + "shell. This is a renderer bug, not a problem with your diagram — please report it.",
            -1, detail);
    }

    /// Dispatch to each diagram type's pure layout. Exhaustive over the sealed IR. EVERY
    /// label-bearing type consumes `math`, routing any `$…$` run in its labels through the shared
    /// {@link com.sirentide.layout.MathLabel} seam (plan sirentide-math-in-all-label-types). A `null`
    /// `math` is the plain-text degrade path for every type — byte-identical to the pre-feature bake.
    private static LaidOut layout(Diagram ir, com.sirentide.api.MathFragmentRenderer math) {
        return switch (ir) {
            case Pie pie -> PieLayout.layout(pie, math);
            case XyChart chart -> XyChartLayout.layout(chart, math);
            case Timeline tl -> TimelineLayout.layout(tl, math);
            case Gantt gantt -> GanttLayout.layout(gantt, math);
            case Flowchart fc -> FlowchartLayout.layout(fc, math);
            case Sequence s -> SequenceLayout.layout(s, math);
            case StateDiagram sd -> StateDiagramLayout.layout(sd, math);
            case QuadrantChart q -> QuadrantChartLayout.layout(q, math);
            case com.sirentide.ir.ClassDiagram cd -> com.sirentide.layout.ClassDiagramLayout.layout(cd, math);
            case com.sirentide.ir.ErDiagram er -> com.sirentide.layout.ErDiagramLayout.layout(er, math);
            // A standalone display-math block bakes its whole body full-size through `math`; a null
            // renderer degrades to the raw LaTeX source as plain-text glyphs (never throws).
            case com.sirentide.ir.MathBlock mb -> com.sirentide.layout.MathBlockLayout.layout(mb, math);
            // A git commit graph: lanes per branch, dots per commit, elbow branch/merge connectors.
            case com.sirentide.ir.GitGraph gg -> com.sirentide.layout.GitGraphLayout.layout(gg, math);
            // A user-journey satisfaction map: tasks along x, score on a 1..5 y-axis, a connecting
            // line, section-header brackets, and per-task actor labels.
            case com.sirentide.ir.Journey j -> com.sirentide.layout.JourneyLayout.layout(j, math);
            // A mindmap: an indentation-defined hierarchy tree, laid out left→right by depth with
            // parent nodes centred on their children and elbow parent→child connectors.
            case com.sirentide.ir.Mindmap m -> com.sirentide.layout.MindmapLayout.layout(m, math);
            // A sankey: weighted flows between nodes placed in depth columns, each flow a filled band
            // whose width tracks its value.
            case com.sirentide.ir.Sankey s -> com.sirentide.layout.SankeyLayout.layout(s, math);
            case Empty ignored -> LaidOut.of(0, 0);
        };
    }
}
