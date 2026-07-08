package com.sirentide.ir;

import java.util.List;

/// Sirentide's fifteenth diagram type: a mermaid-style `sankey-beta` — WEIGHTED FLOWS between nodes.
/// Each body row is CSV-ish `source,target,value`; a FLOW from source→target draws as a band whose
/// WIDTH is proportional to its value. The IR carries only the ordered {@link SankeyFlow} list; the
/// NODES are DERIVED (first-seen order over each flow's source then target) and the COLUMN placement,
/// node heights, and band geometry are all computed at layout time
/// ({@link com.sirentide.layout.SankeyLayout}).
///
/// LAYOUT (the documented approach): nodes are placed in COLUMNS by depth — a source-only node is
/// leftmost (column 0), a node's column is 1 + the max column of its in-neighbours (a longest-path
/// pass, cycle-broken by a bounded relaxation), so a sink lands rightmost. A node's HEIGHT is
/// max(sum of inflows, sum of outflows) · scale; the scale is chosen so the heaviest column fits the
/// canvas. Each flow takes a vertical slot on its source's right edge (cumulative outflow offset) and
/// on its target's left edge (cumulative inflow offset), in declaration order, and draws as a filled
/// straight-sided quadrilateral `<path>` coloured by a LIGHTER TINT of its source node's palette
/// colour (the contract fill is `#rrggbb`, no alpha — so a tint, not opacity).
///
/// An EMPTY sankey (a bare `sankey` body, or every row malformed) has no flows — it still round-trips
/// as a sankey and lays out to a minimal inert canvas, never the 0×0 shell (docs/DESIGN.md §6). The
/// malformed decisions live in the parser and never fail the bake: a row without exactly three
/// comma-fields, a non-numeric / non-positive value, a missing source or target, or a self-flow
/// (`A,A`) is dropped; flows are capped at `MAX_DATA_ROWS`.
///
/// `textColor` fills page-background text (carried for dispatch parity; node labels contrast against
/// their box fill, like the flowchart). Default `currentColor`. Layout dispatch is
/// `case Sankey s -> SankeyLayout.layout(s, math)`.
public record Sankey(List<SankeyFlow> flows, String textColor) implements Diagram {

    public Sankey {
        flows = List.copyOf(flows);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps a caller that builds a sankey
    /// from just its flows unchanged.
    public Sankey(List<SankeyFlow> flows) {
        this(flows, "currentColor");
    }
}
