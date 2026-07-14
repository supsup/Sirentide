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
/// (parse-boundary-validated) drawn at `strokeWidth`.
///
/// `structural` marks a NODE SILHOUETTE (a flowchart diamond/stadium/circle/hexagon/rounded/cylinder
/// body — built via {@link #silhouette}): its fill is STRUCTURAL in play-through frames
/// ({@link com.sirentide.emit.Emphasis#box} — dims when FUTURE, keeps its fill when ACTIVE) exactly
/// like a node {@link Rect}, so the accent lands on the label/border, never on the fill under the
/// label (an accented silhouette fill + accented label is 1:1 contrast — unreadable). A non-structural
/// Path (arrowhead, glyph-like filled marker) keeps the ACCENTABLE fill (active → accent).
public record Path(String d, String fill, String stroke, double strokeWidth, boolean structural)
    implements Shape {

    /// Fill-only construction (no stroke, accentable) — keeps every existing Path caller
    /// byte-for-byte unchanged.
    public Path(String d, String fill) {
        this(d, fill, null, 0, false);
    }

    /// A flowchart NODE SILHOUETTE with an optional `classDef` border: structural fill (see class
    /// doc), plus the same optional parse-validated stroke/width a node Rect carries.
    public static Path silhouette(String d, String fill, String stroke, double strokeWidth) {
        return new Path(d, fill, stroke, strokeWidth, true);
    }
}
