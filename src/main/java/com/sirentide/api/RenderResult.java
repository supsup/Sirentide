package com.sirentide.api;

/// The result of a diagnostic bake: the baked `svg` — BYTE-IDENTICAL to what
/// {@link Sirentide#render(String)} produces for the same input (the inert shell on any failure) —
/// PLUS the structured {@link Diagnostics} that explains it (plan sirentide-render-diagnostics).
/// The `svg` field is the contract the safe path already guaranteed; `diagnostics` is the pure
/// side-channel added on top, so a caller that only wants the string can keep calling `render`.
public record RenderResult(String svg, Diagnostics diagnostics) {}
