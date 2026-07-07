package com.sirentide.layout;

import com.sirentide.ir.StateDiagram;

/// State-diagram layout: it does NOT re-implement graph layout — it drives {@link FlowchartLayout}'s
/// layered directed-graph engine (layering, cycle handling, edge routing, transition labels) through
/// a state-flavored {@link NodeStyler}. The only difference from a flowchart is how a node is DRAWN:
///
///   - a normal `state` → the same rect box the flowchart uses (fill inherited from the engine),
///   - `[*]`-as-source (`__start__`, shape `"start"`) → a small filled disc,
///   - `[*]`-as-target (`__end__`, shape `"end"`) → a bullseye (three stacked discs).
///
/// The pseudostates carry an EMPTY label (set by the parser), so the engine's label step draws no
/// text on them; a real state keeps its centered glyph label. Edge/transition labels ride the
/// engine's existing edge-label mechanism unchanged (FOLLOW-UP: rounded-corner state boxes — that
/// needs a rounded-rect {@link Path}, not attempted in M1).
public final class StateDiagramLayout {

    private StateDiagramLayout() {}

    private static final String PSEUDO_FILL = "#334155";   // start disc + bullseye rings
    private static final String RING_BG = "#ffffff";       // the bullseye's inner white ring
    private static final double START_R = 7;               // start pseudostate disc radius
    private static final double END_OUTER_R = 8;           // bullseye outer disc
    private static final double END_MID_R = 6;             // bullseye white ring
    private static final double END_INNER_R = 4;           // bullseye dark centre

    /// Reskin the flowchart engine's node boxes for state semantics. A full disc is a single
    /// two-arc {@link Wedge} (sweep 0→2π — the emitter draws the degenerate full circle as two
    /// semicircle arcs, alphabet-clean: only `<path>`). The bullseye is three concentric discs
    /// (dark r8, white r6, dark r4) stacked in paint order. Every disc is centred on the node box
    /// the engine sized/placed, so edge anchors (box top/bottom/side centres) are untouched.
    static final NodeStyler STYLER = (shapes, i, x, y, w, h, shape, fill) -> {
        double cx = x + w / 2;
        double cy = y + h / 2;
        switch (shape) {
            case "start" -> shapes.add(new Wedge(cx, cy, START_R, 0, 2 * Math.PI, PSEUDO_FILL));
            case "end" -> {
                shapes.add(new Wedge(cx, cy, END_OUTER_R, 0, 2 * Math.PI, PSEUDO_FILL));
                shapes.add(new Wedge(cx, cy, END_MID_R, 0, 2 * Math.PI, RING_BG));
                shapes.add(new Wedge(cx, cy, END_INNER_R, 0, 2 * Math.PI, PSEUDO_FILL));
            }
            // A normal state is the same rect the flowchart draws (never a diamond in a state diagram).
            default -> shapes.add(new Rect(x, y, w, h, fill));
        }
    };

    public static LaidOut layout(StateDiagram sd) {
        return FlowchartLayout.layout(sd.graph(), STYLER);
    }
}
