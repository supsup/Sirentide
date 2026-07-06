package com.sirentide.ir;

import java.util.List;

/// A timeline: an ordered sequence of events, each a (label, value) — the value read as a moment
/// (e.g. a year). Layout places them evenly along a horizontal axis (arithmetic, no optimization).
/// Reuses {@link Slice} as the generic (label, value) datum.
/// `textColor` fills the off-slice page-background text (event labels + value/year labels).
/// Defaults to `currentColor` so it inherits the host page's text colour (legible on light AND
/// dark); the DSL `color=` modifier overrides it.
public record Timeline(List<Slice> events, String textColor) implements Diagram {

    public Timeline {
        events = List.copyOf(events);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps existing callers/tests
    /// that build a `Timeline` from just its events unchanged.
    public Timeline(List<Slice> events) {
        this(events, "currentColor");
    }
}
