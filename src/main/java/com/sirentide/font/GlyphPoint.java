package com.sirentide.font;

/// One point of a TrueType glyph contour, in font design units (y-up). `onCurve` false means a
/// quadratic Bézier control point; two consecutive off-curve points imply an on-curve point at
/// their midpoint (the TrueType `glyf` convention).
public record GlyphPoint(int x, int y, boolean onCurve) {}
