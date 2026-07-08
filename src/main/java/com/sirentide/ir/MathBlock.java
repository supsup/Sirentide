package com.sirentide.ir;

/// A standalone display-math block — one full-size LaTeX expression baked centered on the canvas
/// (plan sirentide-mathblock). Unlike an inline `$…$` run inside a label, the WHOLE body is the
/// expression (no `$` delimiters, no surrounding text), rendered at display size through the shared
/// {@link com.sirentide.api.MathFragmentRenderer} seam. This is the purest moat demo: paste an
/// equation, get a CSP-clean SVG of typeset math baked to glyph paths.
///
/// `latex` is the RAW LaTeX source (the joined body lines), capped at the parser's label length.
/// An empty `latex` (a `mathblock` with no body) is the inert degrade target; a `latex` the injected
/// renderer cannot handle degrades to its own source baked as plain-text glyphs — never a throw.
public record MathBlock(String latex) implements Diagram {}
