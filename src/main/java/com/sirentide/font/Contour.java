package com.sirentide.font;

import java.util.List;

/// One closed contour of a glyph outline — an ordered ring of {@link GlyphPoint}s (font units,
/// y-up). A glyph is one or more contours (e.g. the bowl and counter of an "o").
public record Contour(List<GlyphPoint> points) {

    public Contour {
        points = List.copyOf(points);
    }
}
