package com.sirentide.ir;

/// The empty diagram — no content. Bakes to a minimal SVG shell. Also the degrade target when
/// the DSL head is unrecognized (docs/DESIGN.md §6: degrade, never throw).
public record Empty() implements Diagram {}
