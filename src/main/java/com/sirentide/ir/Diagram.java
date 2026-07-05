package com.sirentide.ir;

import java.util.List;

/// The immutable intermediate representation every diagram type projects into
/// (docs/DESIGN.md §4 — the single shared IR mermaid never built). Pure layout consumes it;
/// pure emit never sees it. M0 skeleton: a diagram kind + its labels. Real per-type element
/// shapes (slices, bars, actors, messages, nodes) land in M1+ as sealed subtypes.
public record Diagram(Kind kind, List<String> labels) {

    public enum Kind { EMPTY, PIE, XYCHART, SEQUENCE }

    /// Defensive copy — the IR is immutable by construction (byte-identical bakes, §6).
    public Diagram {
        labels = List.copyOf(labels);
    }

    public static Diagram empty() {
        return new Diagram(Kind.EMPTY, List.of());
    }
}
