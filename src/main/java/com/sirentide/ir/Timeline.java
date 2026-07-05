package com.sirentide.ir;

import java.util.List;

/// A timeline: an ordered sequence of events, each a (label, value) — the value read as a moment
/// (e.g. a year). Layout places them evenly along a horizontal axis (arithmetic, no optimization).
/// Reuses {@link Slice} as the generic (label, value) datum.
public record Timeline(List<Slice> events) implements Diagram {

    public Timeline {
        events = List.copyOf(events);
    }
}
