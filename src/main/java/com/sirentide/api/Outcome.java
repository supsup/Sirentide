package com.sirentide.api;

/// The classification of a {@link Sirentide#renderWithDiagnostics(String) diagnostic bake} — the
/// author-facing answer to "why did my diagram render empty?" (plan sirentide-render-diagnostics).
/// The safe inert-shell degrade is UNCHANGED; this enum is the side-channel signal that names which
/// of the render pipeline's silent-swallow branches fired.
///
/// - {@link #OK} — the bake succeeded; the SVG is real content. It MAY still ride a non-fatal
///   COVERAGE caveat in its {@code message}/{@code detail} (plan 933eed50 F1): source code points
///   outside the bundled label font's coverage bake as .notdef boxes — the render is unchanged, the
///   caveat just names the offending `U+XXXX` points. A fully-covered source carries no caveat. A pie
///   may likewise ride a dropped-thin-slice-label caveat (plan 86cee1d3) — a coloured wedge whose
///   outside label had no room; both OK caveats compose onto one {@link Diagnostics}, classified at
///   `stage` `"emit"` (the point of classification, not the layout-time fact each describes).
/// - {@link #PARSE_ERROR} — the source didn't parse into a recognized diagram (unknown type keyword
///   on line 1, or an over-cap / unparseable header) and degraded to the empty shell. ALSO (plan
///   650d6425) a recognized flowchart whose author-written body produced ZERO nodes and ZERO edges
///   because its statements were unparseable — or declared nothing renderable at all — so the
///   diagram rendered empty. That case carries a real 1-based {@link Diagnostics#line()}.
/// - {@link #OUTPUT_CAP_EXCEEDED} — a KNOWN, bounded degrade: the bake passed an output-size/frame
///   cap or a deterministic layout-work cap, so it degraded to the inert shell rather than build or
///   emit a runaway document.
/// - {@link #UNSUPPORTED_CONSTRUCT} — a construct the parser recognizes-but-cannot render. Populated
///   (plan 933eed50 F2) for FLOWCHART sources carrying an unsupported Mermaid token at a statement-
///   level position — a top-level `&` edge fan-out, a `~~~` invisible link, a `<br/>` in a label, or a
///   `style`/`click` directive. Such a source degrades to the SAME Empty inert-shell target as an
///   unknown type, but {@link com.sirentide.parse.DslParser#detectUnsupportedConstruct} splits it out
///   of {@link #PARSE_ERROR} by NAMING the offending token — with a real 1-based
///   {@link Diagnostics#line()}. (An unknown diagram TYPE still folds into PARSE_ERROR.) The same
///   channel carries the EMPTIED-DIAGRAM degrade (plan 650d6425) when the construct that emptied a
///   flowchart is recognized-but-unsupported — a bidirectional `<-->`/`<-.->`/`<==>` arrow, which
///   the parser drops whole rather than mint a phantom `A <` node from.
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
