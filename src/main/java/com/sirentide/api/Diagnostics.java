package com.sirentide.api;

/// A structured, author-facing report for one diagnostic bake (plan sirentide-render-diagnostics).
/// It never alters the SVG — it rides ALONGSIDE the (unchanged) baked output on
/// {@link RenderResult}, turning the render pipeline's single silent-swallow into a nameable signal.
///
/// - `outcome` — the {@link Outcome} classification (OK on success).
/// - `stage` — where the pipeline was when it was classified: `"parse"`, `"layout"`, or `"emit"`.
/// - `message` — a human-readable, author-directed sentence (safe to surface in a UI/log).
/// - `line` — the 1-based source line the problem localizes to, or `-1` when unknown. Most outcomes
///   report `-1` (line/token attribution for the exception/cap paths needs deeper annotation, still
///   deferred), EXCEPT {@link Outcome#UNSUPPORTED_CONSTRUCT}, which carries the real 1-based line of
///   the offending flowchart token (plan 933eed50 F2).
/// - `detail` — a lower-level diagnostic crumb (the caught throwable's type+message, the branch that
///   fired, the unsupported token, or the out-of-coverage `U+XXXX` code points on an OK caveat);
///   "" when there is nothing to add. For logs/bug reports, not end-user prose.
public record Diagnostics(Outcome outcome, String stage, String message, int line, String detail) {}
