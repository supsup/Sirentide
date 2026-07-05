package com.sirentide.font;

import java.util.List;

/// The measured size of a (possibly wrapped) run of label text, in pixels. This is what the
/// font-metrics oracle hands to layout so it can size node boxes, columns, and axis gutters
/// without a DOM (docs/DESIGN.md §4). `width` is the widest line; `height` spans all lines.
public record TextBox(double width, double height, List<String> lines) {

    public TextBox {
        lines = List.copyOf(lines);
    }
}
