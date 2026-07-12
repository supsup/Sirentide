package com.sirentide.layout;

/// A laid-out filled path (user coordinates) — its `d` string is the same contract-clean path-data
/// geometry a {@link GlyphRun} carries (command letters + numbers only, no markup), but for a
/// generic filled shape rather than a glyph outline. M1 uses it for flowchart arrowheads (a filled
/// triangle); future rounded node boxes reuse it. Emit wraps it in `<path fill="…">`, mirroring the
/// GlyphRun case exactly (docs/DESIGN.md §4/§6 — text/shapes are contract-clean geometry).
///
/// `stroke`/`strokeWidth` are an OPTIONAL border (plan sirentide-node-edge-styling): a `null` stroke
/// means NO border — the emitter writes neither attribute, so every pre-feature Path (arrowheads,
/// state boxes, node silhouettes) is byte-identical. A non-null stroke is a contract-clean colour
/// (parse-boundary-validated) drawn at `strokeWidth`. Used by a non-rect flowchart node that a
/// `classDef` gave a stroke; every other Path caller uses the fill-only {@link #Path(String, String)}.
public record Path(String d, String fill, String stroke, double strokeWidth) implements Shape {

    /// Fill-only construction (no stroke) — keeps every existing Path caller byte-for-byte unchanged.
    public Path(String d, String fill) {
        this(d, fill, null, 0);
    }
}
