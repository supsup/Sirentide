package com.sirentide.api;

import java.util.Optional;

/// The seam between Sirentide (which owns label layout) and a math renderer (which owns turning
/// LaTeX into glyph paths — LatteX, a later slice). Sirentide never depends on the renderer's
/// internals; it only asks for a {@link MathFragment} per `$...$` run.
///
/// A renderer returns {@link Optional#empty()} when the math cannot be rendered (parse error,
/// unsupported construct, over-cap). The caller then falls back to showing the raw `$...$` source
/// as ordinary text — LOUD, not silent: the author sees their own typo rather than a blank. A
/// `null` renderer (the {@link Sirentide#render(String)} default) turns the feature fully off, so
/// `$` in a label renders as a literal dollar glyph and behaviour is byte-identical to before.
@FunctionalInterface
public interface MathFragmentRenderer {

    /// Render one LaTeX source string at the given pixel size, or empty if it cannot be rendered.
    Optional<MathFragment> render(String latex, double fontSizePx);
}
