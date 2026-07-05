package com.sirentide.layout;

import com.sirentide.ir.Diagram;

/// Pure layout: IR → coordinates. Deterministic, no DOM, no SVG string — that is the emitter's
/// job (docs/DESIGN.md §4). The own font-metrics oracle feeds label/box sizing here in M1.
/// M0: a trivial engine that produces an empty canvas; real per-type layout (pie arithmetic,
/// xychart axis mapping, sequence grid) lands in M1.
public interface LayoutEngine {

    LaidOut layout(Diagram diagram);

    /// The M0 default: empty diagrams lay out to a zero canvas. Replaced per diagram type in M1.
    static LayoutEngine m0Default() {
        return diagram -> LaidOut.of(0, 0);
    }
}
