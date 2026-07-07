package com.sirentide.ir;

import java.util.List;

/// A flowchart: a directed graph of {@link FlowNode}s and {@link FlowEdge}s, laid out in layers.
/// The fifth diagram type — the first with real graph structure (docs/DESIGN.md §5/§6), so layout
/// is a cycle-safe longest-path layering rather than the earlier types' pure arithmetic.
///
/// `direction` is the layout flow: `"TD"` (top-down, the M1 default + only implemented geometry) or
/// `"LR"` (left-right — PARSED into this field but layout still lays out TD; LR geometry is a
/// follow-up). `textColor` fills the node labels — defaults to `currentColor` so labels inherit the
/// host page's text colour (legible on light AND dark), matching the other types' convention.
public record Flowchart(List<FlowNode> nodes, List<FlowEdge> edges, String direction, String textColor)
    implements Diagram {

    public Flowchart {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        if (direction == null || !direction.equals("LR")) {
            direction = "TD";   // TD is the default; only TD and LR are recognized
        }
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction (TD, `currentColor` labels) — keeps a caller that builds a Flowchart from
    /// just its nodes+edges unchanged.
    public Flowchart(List<FlowNode> nodes, List<FlowEdge> edges) {
        this(nodes, edges, "TD", "currentColor");
    }
}
