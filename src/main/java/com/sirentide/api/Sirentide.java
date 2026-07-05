package com.sirentide.api;

import com.sirentide.emit.SvgEmitter;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Pie;
import com.sirentide.ir.XyChart;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.PieLayout;
import com.sirentide.layout.XyChartLayout;

/// Public entry point. The bake pipeline: DSL → parse → IR → layout (→ coordinates) → emit
/// (→ SVG string). Zero runtime dependency, deterministic, sanitizer-clean output
/// (docs/DESIGN.md §2/§4).
///
/// M1 (in progress): the first diagram type — `pie` — renders end to end. xychart + a minimal
/// linear sequence (with the play-through) follow, all projecting into the shared IR.
public final class Sirentide {

    private Sirentide() {}

    /// Bake a Sirentide DSL source into a self-contained SVG string.
    public static String render(String dsl) {
        Diagram ir = com.sirentide.parse.DslParser.parse(dsl);
        LaidOut laid = layout(ir);
        return SvgEmitter.emit(laid);
    }

    /// Dispatch to each diagram type's pure layout. Exhaustive over the sealed IR.
    private static LaidOut layout(Diagram ir) {
        return switch (ir) {
            case Pie pie -> PieLayout.layout(pie);
            case XyChart chart -> XyChartLayout.layout(chart);
            case Empty ignored -> LaidOut.of(0, 0);
        };
    }
}
