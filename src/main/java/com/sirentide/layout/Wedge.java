package com.sirentide.layout;

/// A filled pie wedge: centre `(cx,cy)`, radius `r`, and the `[a0,a1]` angular sweep in radians
/// (SVG y-down space, measured clockwise from the +x axis), plus a fill colour. Emit turns this
/// into a single `<path>` (moveto centre → lineto arc-start → arc → close).
public record Wedge(double cx, double cy, double r, double a0, double a1, String fill)
    implements Shape {}
