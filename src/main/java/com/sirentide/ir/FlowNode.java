package com.sirentide.ir;

/// One flowchart node: a stable `id` (its identity in edge declarations), a display `label`, a
/// `shape` — `"rect"` (the `[Label]` box, default) or `"diamond"` (the `{Label}` decision node,
/// M1.3) — and an OPTIONAL author `color` (a canonical `#rrggbb` from the parser, `null` = use the
/// layout's default box fill). A per-node colour beats the header `nodecolor=` default, which beats
/// the built-in `#dbe4ff`. A bare `id` uses the id as its own label (set by the parser). All
/// parser-capped (§6/§7).
///
/// `stroke`/`strokeWidth`/`textColor` are the OPTIONAL classDef-driven styling (plan
/// sirentide-node-edge-styling). All THREE are `null` for a node without a styled `class` — the box
/// then draws borderless with a contrast-derived label, exactly as before (byte-identical). A
/// non-null `stroke` is a canonical `#rrggbb` (or `currentColor`/`none`) border colour parse-validated
/// through the SAME {@link com.sirentide.contract.SirentideContract} colour guard as `color`;
/// `strokeWidth` is a finite non-negative border width (`null` → the layout's default when a stroke is
/// set); `textColor` overrides the auto-contrast label colour with an author `#hex`. Every value is
/// validated at the parse boundary and fails closed (a bad value drops to `null` → the default).
public record FlowNode(String id, String label, String shape, String color,
                       String stroke, Double strokeWidth, String textColor) {

    /// Fill-only construction (no stroke/textColor styling) — keeps every colour-carrying caller
    /// (the state-diagram wrapper, the a11y describer's rebuild) byte-for-byte unchanged.
    public FlowNode(String id, String label, String shape, String color) {
        this(id, label, shape, color, null, null, null);
    }

    /// Colourless construction (default box fill) — keeps every existing shape-carrying caller/test
    /// byte-for-byte unchanged.
    public FlowNode(String id, String label, String shape) {
        this(id, label, shape, null, null, null, null);
    }

    /// Rect-shaped, colourless construction — keeps every existing caller/test byte-for-byte unchanged.
    public FlowNode(String id, String label) {
        this(id, label, "rect", null, null, null, null);
    }
}
