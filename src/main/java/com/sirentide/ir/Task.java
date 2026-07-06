package com.sirentide.ir;

/// One gantt task: a label, a `[start, end]` span on a shared (unit-less) time axis, and an
/// OPTIONAL explicit fill `color`. A `null` color means "use the layout's default palette by
/// index"; a non-null color is a canonical `#rrggbb` (normalized by the parser) overriding it.
public record Task(String label, double start, double end, String color) {

    /// Palette-default construction (no explicit colour) — keeps every existing caller/test that
    /// builds a `Task` from just its label+span unchanged.
    public Task(String label, double start, double end) {
        this(label, start, end, null);
    }

    /// Duration (never negative — a malformed `end < start` clamps to zero-width).
    public double duration() {
        return Math.max(0, end - start);
    }
}
