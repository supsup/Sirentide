package com.sirentide.api;

/// Opt-in switches for {@link Sirentide#renderWithDiagnostics(String, MathFragmentRenderer, RenderOptions)}.
/// Every switch defaults OFF, and {@link #DEFAULT} is exactly the pre-options render: same SVG bytes,
/// same diagnostics.
///
/// `lintOverlap` (item 4 of Fixpoint's ruling on the cli-math-batch rebuild): after a successful
/// bake, check every text run's bounding box against every other text run in a DIFFERENT anchored
/// group (or outside any group) and report each overlapping pair on the OK caveat channel
/// ({@link Diagnostics#detail()}, prefixed `text overlap:`). The SVG is never changed. See
/// {@link com.sirentide.layout.OverlapLint} for exactly what is and is not checked.
public record RenderOptions(boolean lintOverlap) {

    /// All switches off: the default render.
    public static final RenderOptions DEFAULT = new RenderOptions(false);
}
