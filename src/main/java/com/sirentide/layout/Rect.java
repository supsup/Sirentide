package com.sirentide.layout;

/// A laid-out filled rectangle (user coordinates) — e.g. an xychart bar. Emit serializes it to
/// a `<rect>` (in the M1 sirentide-output-contract alphabet).
public record Rect(double x, double y, double width, double height, String fill) implements Shape {}
