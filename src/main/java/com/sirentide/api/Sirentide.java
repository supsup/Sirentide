package com.sirentide.api;

import com.sirentide.emit.SvgEmitter;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Flowchart;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Sequence;
import com.sirentide.ir.StateDiagram;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.FlowchartLayout;
import com.sirentide.layout.GanttLayout;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.PieLayout;
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
        try {
            Diagram ir = com.sirentide.parse.DslParser.parse(dsl);
            LaidOut laid = layout(ir);
            String svg = SvgEmitter.emit(laid);
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

    /// Dispatch to each diagram type's pure layout. Exhaustive over the sealed IR.
    private static LaidOut layout(Diagram ir) {
        return switch (ir) {
            case Pie pie -> PieLayout.layout(pie);
            case XyChart chart -> XyChartLayout.layout(chart);
            case Timeline tl -> TimelineLayout.layout(tl);
            case Gantt gantt -> GanttLayout.layout(gantt);
            case Flowchart fc -> FlowchartLayout.layout(fc);
            case Sequence s -> SequenceLayout.layout(s);
            case StateDiagram sd -> StateDiagramLayout.layout(sd);
            case Empty ignored -> LaidOut.of(0, 0);
        };
    }
}
