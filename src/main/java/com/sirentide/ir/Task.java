package com.sirentide.ir;

/// One gantt task: a label and a `[start, end]` span on a shared (unit-less) time axis.
public record Task(String label, double start, double end) {

    /// Duration (never negative — a malformed `end < start` clamps to zero-width).
    public double duration() {
        return Math.max(0, end - start);
    }
}
