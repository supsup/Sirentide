package com.sirentide.layout;

/// A laid-out filled path (user coordinates) — its `d` string is the same contract-clean path-data
/// geometry a {@link GlyphRun} carries (command letters + numbers only, no markup), but for a
/// generic filled shape rather than a glyph outline. M1 uses it for flowchart arrowheads (a filled
/// triangle); future rounded node boxes reuse it. Emit wraps it in `<path fill="…">`, mirroring the
/// GlyphRun case exactly (docs/DESIGN.md §4/§6 — text/shapes are contract-clean geometry).
public record Path(String d, String fill) implements Shape {}
