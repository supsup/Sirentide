package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.ir.StateDiagram;

/// State-diagram layout: it does NOT re-implement graph layout — it drives {@link FlowchartLayout}'s
/// layered directed-graph engine (layering, cycle handling, edge routing, transition labels) through
/// a state-flavored {@link NodeStyler}. The only difference from a flowchart is how a node is DRAWN:
///
///   - a normal `state` → a ROUNDED-rect {@link Path} (r≈8; softer than the flowchart's sharp rect,
///     fill inherited from the engine),
///   - `[*]`-as-source (`__start__`, shape `"start"`) → a small filled disc,
///   - `[*]`-as-target (`__end__`, shape `"end"`) → a bullseye (three stacked discs).
///
/// The pseudostates carry an EMPTY label (set by the parser), so the engine's label step draws no
/// text on them; a real state keeps its centered glyph label. Edge/transition labels ride the
/// engine's existing edge-label mechanism unchanged. The rounded box is a contract-clean `<path>`
/// (H/V sides + Q corners) so it needs no new emitter/contract surface.
public final class StateDiagramLayout {

    private StateDiagramLayout() {}

    private static final String PSEUDO_FILL = "#334155";   // start disc + bullseye rings
    private static final String RING_BG = "#ffffff";       // the bullseye's inner white ring
    private static final double START_R = 7;               // start pseudostate disc radius
    private static final double END_OUTER_R = 8;           // bullseye outer disc
    private static final double END_MID_R = 6;             // bullseye white ring
    private static final double END_INNER_R = 4;           // bullseye dark centre
    private static final double CORNER_R = 8;              // normal-state rounded-rect corner radius

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
            // A normal state is a ROUNDED-rect {@link Path} (r≈8) — softer than the flowchart's sharp
            // Rect, drawn as a contract-clean `<path>`: H/V straight sides + Q quadratic corners (all
            // in the path alphabet). Never a diamond in a state diagram; pseudostates stay discs above.
            default -> shapes.add(new Path(roundedRect(x, y, w, h), fill));
        }
    };

    /// A rounded-rectangle path for a normal state box: straight H/V sides with a Q quadratic at each
    /// corner (radius {@link #CORNER_R}, clamped so it never exceeds half the box's smaller side).
    ///   `M x+r,y  H x+w-r  Q x+w,y x+w,y+r  V y+h-r  Q x+w,y+h x+w-r,y+h
    ///    H x+r    Q x,y+h x,y+h-r          V y+r     Q x,y x+r,y  Z`
    private static String roundedRect(double x, double y, double w, double h) {
        double r = Math.min(CORNER_R, Math.min(w, h) / 2);
        return "M " + fmt(x + r) + " " + fmt(y)
            + " H " + fmt(x + w - r)
            + " Q " + fmt(x + w) + " " + fmt(y) + " " + fmt(x + w) + " " + fmt(y + r)
            + " V " + fmt(y + h - r)
            + " Q " + fmt(x + w) + " " + fmt(y + h) + " " + fmt(x + w - r) + " " + fmt(y + h)
            + " H " + fmt(x + r)
            + " Q " + fmt(x) + " " + fmt(y + h) + " " + fmt(x) + " " + fmt(y + h - r)
            + " V " + fmt(y + r)
            + " Q " + fmt(x) + " " + fmt(y) + " " + fmt(x + r) + " " + fmt(y)
            + " Z";
    }

    /// Deterministic 3-dp number formatting (byte-identical bakes, docs/DESIGN.md §6) — mirrors the
    /// flowchart engine's arrowhead formatter so state paths format identically.
    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            v = 0.0;
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }

    public static LaidOut layout(StateDiagram sd) {
        return layout(sd, null);
    }

    /// Inline-math entry (plan sirentide-math-in-all-label-types): threads `math` into the reused
    /// flowchart engine, so a state NAME carrying `$…$` (a node label) AND a transition label
    /// carrying `$…$` (an edge label) both bake through the SAME {@link MathLabel} seam the flowchart
    /// uses. A null `math` is byte-identical to {@link #layout(StateDiagram)}.
    public static LaidOut layout(StateDiagram sd, MathFragmentRenderer math) {
        return FlowchartLayout.layout(sd.graph(), STYLER, math);
    }
}
