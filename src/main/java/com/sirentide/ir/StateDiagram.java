package com.sirentide.ir;

/// Sirentide's seventh diagram type: a mermaid-style state diagram. A state diagram IS a flowchart
/// with different node PRESENTATION plus `[*]` pseudostates — so rather than fork the graph engine it
/// wraps a {@link Flowchart} (the layered directed-graph layout, labeled edges, cycle handling) and
/// reskins its nodes at layout time (`__start__`/`__end__` become discs, normal states stay rects).
///
/// The wrapped graph carries the same node/edge structure the flowchart layout already understands:
/// each state is a {@link FlowNode} whose `shape` is `"state"` (a normal state), `"start"` (the
/// `[*]`-as-source pseudostate, id `__start__`), or `"end"` (the `[*]`-as-target pseudostate, id
/// `__end__`); each transition is a {@link FlowEdge} carrying its optional `: label`. Layout dispatch
/// is `case StateDiagram sd -> StateDiagramLayout.layout(sd)`, which drives the flowchart engine
/// through a state-flavored node styler (docs/DESIGN.md §5/§6).
public record StateDiagram(Flowchart graph) implements Diagram {

    public StateDiagram {
        if (graph == null) {
            graph = new Flowchart(java.util.List.of(), java.util.List.of());
        }
    }
}
