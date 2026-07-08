package com.sirentide.api;

/// A structured, author-facing report for one diagnostic bake (plan sirentide-render-diagnostics).
/// It never alters the SVG — it rides ALONGSIDE the (unchanged) baked output on
/// {@link RenderResult}, turning the render pipeline's single silent-swallow into a nameable signal.
///
/// - `outcome` — the {@link Outcome} classification (OK on success).
/// - `stage` — where the pipeline was when it was classified: `"parse"`, `"layout"`, or `"emit"`.
/// - `message` — a human-readable, author-directed sentence (safe to surface in a UI/log).
/// - `line` — the 1-based source line the problem localizes to, or `-1` when unknown. v1 reports
///   `-1` for every non-OK outcome: precise line/token attribution needs parser annotation that is
///   EXPLICITLY DEFERRED (the concurrent brace-math worker owns DslParser), so this stays a slot the
///   follow-up fills without a shape change here.
/// - `detail` — a lower-level diagnostic crumb (the caught throwable's type+message, or the branch
///   that fired); "" when there is nothing to add. For logs/bug reports, not end-user prose.
public record Diagnostics(Outcome outcome, String stage, String message, int line, String detail) {}
