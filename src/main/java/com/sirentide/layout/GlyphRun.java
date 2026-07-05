package com.sirentide.layout;

/// A laid-out run of text, already reduced to an SVG path `d` string (glyph outlines positioned +
/// scaled + y-flipped by the font-metrics oracle at layout time) plus a fill. Emit just wraps it
/// in `<path>` — so text is contract-clean geometry, no `<text>` element (docs/DESIGN.md §4/§6).
public record GlyphRun(String pathD, String fill) implements Shape {}
