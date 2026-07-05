package com.sirentide.layout;

/// A laid-out straight line segment (user coordinates) — e.g. an xychart axis. Emit serializes it
/// to a `<line>` with a numeric stroke (M1 sirentide-output-contract alphabet).
public record Line(double x1, double y1, double x2, double y2, String stroke, double strokeWidth)
    implements Shape {}
