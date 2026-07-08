package com.sirentide.api;

/// The classification of a {@link Sirentide#renderWithDiagnostics(String) diagnostic bake} — the
/// author-facing answer to "why did my diagram render empty?" (plan sirentide-render-diagnostics).
/// The safe inert-shell degrade is UNCHANGED; this enum is the side-channel signal that names which
/// of the render pipeline's silent-swallow branches fired.
///
/// - {@link #OK} — the bake succeeded; the SVG is real content.
/// - {@link #PARSE_ERROR} — the source didn't parse into a recognized diagram (unknown type keyword
///   on line 1, or an over-cap / unparseable header) and degraded to the empty shell.
/// - {@link #OUTPUT_CAP_EXCEEDED} — a KNOWN, bounded degrade: the baked SVG passed the
///   {@link Sirentide#MAX_OUTPUT_BYTES} cap (the emitter's incremental guard or the post-emit
///   check), so it degraded to the inert shell rather than emit a runaway document.
/// - {@link #UNSUPPORTED_CONSTRUCT} — reserved for a construct the parser recognizes-but-cannot-yet
///   render. v1 CANNOT distinguish this from {@link #PARSE_ERROR} without deeper parser annotation
///   (the parser degrades an unknown type to the same Empty target), so it is folded into
///   PARSE_ERROR for now and kept here as the taxonomy slot the follow-up will populate.
/// - {@link #RENDER_BUG} — an UNEXPECTED throwable escaped layout or emit and was caught by the
///   last-resort bake guard. This is a renderer defect, not an author mistake — the very class of
///   failure this channel exists to stop converting into an indistinguishable blank.
public enum Outcome {
    OK,
    PARSE_ERROR,
    OUTPUT_CAP_EXCEEDED,
    UNSUPPORTED_CONSTRUCT,
    RENDER_BUG
}
