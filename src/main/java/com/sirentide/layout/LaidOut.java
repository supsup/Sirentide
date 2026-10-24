package com.sirentide.layout;

import java.util.List;

/// Pure layout output — a scene: a canvas size plus the placed, coloured {@link Shape}s. Still
/// no SVG (that is emit's job; docs/DESIGN.md §4). Deterministic and immutable so bakes are
/// byte-identical (§6). The two optional metadata lists identify actual emitted frame-label and
/// activation-bar objects for selective overlap lint; they add no SVG elements or wire roles.
public record LaidOut(double width, double height, List<Shape> shapes,
                      List<GlyphRun> frameLabels, List<Rect> activationBars) {

    public LaidOut {
        shapes = List.copyOf(shapes);
        frameLabels = List.copyOf(frameLabels);
        activationBars = List.copyOf(activationBars);
    }

    /// Existing layouts have no selective frame/activation metadata.
    public LaidOut(double width, double height, List<Shape> shapes) {
        this(width, height, shapes, List.of(), List.of());
    }

    /// An empty canvas of the given size (no shapes).
    public static LaidOut of(double width, double height) {
        return new LaidOut(width, height, List.of());
    }
}
