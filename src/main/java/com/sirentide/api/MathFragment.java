package com.sirentide.api;

/// A rendered inline-math fragment: contract-clean SVG markup plus the box metrics Sirentide's
/// layout needs to place and size it inside a label (the moat feature, RFC sirentide/39).
///
/// COORDINATE CONTRACT (the seam every {@link MathFragmentRenderer} MUST honor):
/// the fragment's local origin `(0,0)` is the LEFT END OF THE BASELINE. In SVG's y-down space
/// glyphs extend UP to `heightPx` ABOVE the baseline (negative local y) and DOWN to `depthPx`
/// BELOW it (positive local y); `widthPx` is the horizontal advance. So when a caller places the
/// fragment at pen `(x, baselineY)` via `translate(x baselineY)`, its baseline lands exactly on
/// `baselineY` — the same baseline the surrounding text glyphs sit on. This is what lets a
/// `$...$` run compose inline with text runs on one shared baseline.
///
/// `innerSvg` is a fragment (NOT a full `<svg>` document) of `<g>`/`<path>` markup carrying its
/// own numeric `transform`s — it is embedded verbatim inside a single wrapping
/// `<g transform="translate(x baselineY)">…</g>`. It MUST already be contract-clean; the caller
/// re-validates it through {@link com.sirentide.contract.FragmentGuard} before trusting it.
public record MathFragment(String innerSvg, double widthPx, double heightPx, double depthPx) {}
