package com.sirentide.api;

import com.sirentide.emit.SvgEmitter;
import com.sirentide.ir.Diagram;
import com.sirentide.layout.LayoutEngine;

/// Public entry point. The bake pipeline: DSL → parse → IR → layout (→ coordinates) → emit
/// (→ SVG string). Zero runtime dependency, deterministic, sanitizer-clean output
/// (docs/DESIGN.md §2/§4).
///
/// M0 skeleton: an empty diagram bakes to a minimal, contract-clean `<svg>` shell — proving the
/// parse→IR→layout→emit pipeline end to end. Real per-type parsers + layout + emit land in M1
/// (pie, xychart, minimal sequence), building to Confluence's sirentide-output-contract.
public final class Sirentide {

    private Sirentide() {}

    /// Bake a Sirentide DSL source into a self-contained SVG string.
    public static String render(String dsl) {
        Diagram ir = parse(dsl);
        var laid = LayoutEngine.m0Default().layout(ir);
        return SvgEmitter.m0Default().emit(laid);
    }

    /// M0: recognizes only the empty diagram. Real per-type parsers (own DSL, effects +
    /// sequencing + math-labels first-class) land in M1.
    private static Diagram parse(String dsl) {
        return Diagram.empty();
    }
}
