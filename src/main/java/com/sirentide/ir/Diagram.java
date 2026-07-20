package com.sirentide.ir;

/// The immutable IR root — a sealed hierarchy every diagram type projects into (docs/DESIGN.md
/// §4, the single shared IR mermaid never built). Pure layout consumes a `Diagram`; pure emit
/// never sees it. New diagram types (xychart, sequence) join as permitted records.
public sealed interface Diagram
    permits Empty, Pie, XyChart, Timeline, Gantt, Flowchart, Sequence, StateDiagram, QuadrantChart,
        ClassDiagram, ErDiagram, MathBlock, GitGraph, Journey, Mindmap, Sankey, Matrix, YoungDiagram {}
