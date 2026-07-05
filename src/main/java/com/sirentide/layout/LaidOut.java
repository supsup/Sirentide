package com.sirentide.layout;

import java.util.List;

/// Pure layout output — a scene: a canvas size plus the placed, coloured {@link Shape}s. Still
/// no SVG (that is emit's job; docs/DESIGN.md §4). Deterministic and immutable so bakes are
/// byte-identical (§6).
public record LaidOut(double width, double height, List<Shape> shapes) {

    public LaidOut {
        shapes = List.copyOf(shapes);
    }

    /// An empty canvas of the given size (no shapes).
    public static LaidOut of(double width, double height) {
        return new LaidOut(width, height, List.of());
    }
}
