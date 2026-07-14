package com.sirentide.layout;

/// A laid-out filled rectangle (user coordinates) — e.g. an xychart bar. Emit serializes it to
/// a `<rect>` (in the M1 sirentide-output-contract alphabet).
///
/// `stroke`/`strokeWidth` are an OPTIONAL border (plan sirentide-node-edge-styling): a `null`
/// stroke means NO border — the emitter writes neither attribute, so every pre-feature Rect is
/// byte-identical. A non-null stroke is a contract-clean colour (parse-boundary-validated) drawn at
/// `strokeWidth` (a finite non-negative scalar). Used by a flowchart node that a `classDef` gave a
/// stroke; every other Rect caller uses the borderless {@link #Rect(double, double, double, double, String)}.
public record Rect(double x, double y, double width, double height, String fill,
                   String stroke, double strokeWidth) implements Shape {

    /// Borderless construction (no stroke) — keeps every existing Rect caller byte-for-byte unchanged.
    public Rect(double x, double y, double width, double height, String fill) {
        this(x, y, width, height, fill, null, 0);
    }
}
