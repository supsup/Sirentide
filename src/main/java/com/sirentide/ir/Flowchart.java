package com.sirentide.ir;

import java.util.List;

/// A flowchart: a directed graph of {@link FlowNode}s and {@link FlowEdge}s, laid out in layers.
/// The fifth diagram type — the first with real graph structure (docs/DESIGN.md §5/§6), so layout
/// is a cycle-safe longest-path layering rather than the earlier types' pure arithmetic.
///
/// `direction` is the layout flow: `"TD"` (top-down, the M1 default + only implemented geometry) or
/// `"LR"` (left-right — PARSED into this field but layout still lays out TD; LR geometry is a
/// follow-up). `textColor` fills the EDGE labels (they sit on the page background) — defaults to
/// `currentColor` so they inherit the host page's text colour (legible on light AND dark). NODE
/// labels no longer use `textColor`: they contrast against the node's resolved box fill (dark text
/// on a light box, white on a dark one) so they stay legible on any theme.
///
/// `nodeColor` is the header `nodecolor=#hex` default box fill — a canonical `#rrggbb`, or `null`
/// for the built-in `#dbe4ff`. It applies to every node WITHOUT its own {@link FlowNode#color()};
/// a per-node colour always wins.
public record Flowchart(List<FlowNode> nodes, List<FlowEdge> edges, String direction, String textColor,
                        String nodeColor) implements Diagram {

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

    /// No header node colour — keeps a caller that supplies just direction+textColor unchanged.
    public Flowchart(List<FlowNode> nodes, List<FlowEdge> edges, String direction, String textColor) {
        this(nodes, edges, direction, textColor, null);
    }

    /// Default construction (TD, `currentColor` edge labels, default box fill) — keeps a caller that
    /// builds a Flowchart from just its nodes+edges unchanged.
    public Flowchart(List<FlowNode> nodes, List<FlowEdge> edges) {
        this(nodes, edges, "TD", "currentColor", null);
    }
}
