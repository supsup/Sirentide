package com.sirentide.ir;

/// One flowchart node: a stable `id` (its identity in edge declarations), a display `label`, a
/// `shape` — `"rect"` (the `[Label]` box, default) or `"diamond"` (the `{Label}` decision node,
/// M1.3) — and an OPTIONAL author `color` (a canonical `#rrggbb` from the parser, `null` = use the
/// layout's default box fill). A per-node colour beats the header `nodecolor=` default, which beats
/// the built-in `#dbe4ff`. A bare `id` uses the id as its own label (set by the parser). All
/// parser-capped (§6/§7).
public record FlowNode(String id, String label, String shape, String color) {

    /// Colourless construction (default box fill) — keeps every existing shape-carrying caller/test
    /// byte-for-byte unchanged.
    public FlowNode(String id, String label, String shape) {
        this(id, label, shape, null);
    }

    /// Rect-shaped, colourless construction — keeps every existing caller/test byte-for-byte unchanged.
    public FlowNode(String id, String label) {
        this(id, label, "rect", null);
    }
}
