package com.sirentide.layout;

/// A laid-out inline-math fragment: pre-rendered contract-clean SVG markup (`innerSvg`, a
/// `<g>`/`<path>` fragment) to be placed at pen `(x, y)` where `y` is the text BASELINE it shares
/// with the surrounding glyph runs. The emitter wraps it in one `<g transform="translate(x y)">`.
///
/// Unlike {@link GlyphRun}/{@link Path}, the geometry here is FOREIGN (produced by the math
/// renderer, not the font-metrics oracle), so it passes {@link com.sirentide.contract.FragmentGuard}
/// at layout time before it ever becomes a MathBox — the emitter trusts it is already
/// contract-clean (docs/DESIGN.md §4/§6: producer ⊆ contract).
///
/// `fill` is stamped on the wrapping `<g>` so a `currentColor` fragment inherits the LABEL's
/// contrast colour (F2, Conf pins sirentide/51) — otherwise `currentColor` math resolves to the
/// SVG default (black) and vanishes on a dark node while the text runs stay light.
public record MathBox(double x, double y, String fill, String innerSvg) implements Shape {}
