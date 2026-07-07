package com.sirentide.ir;

/// One flowchart node: a stable `id` (its identity in edge declarations), a display `label`, and a
/// `shape` — `"rect"` (the `[Label]` box, default) or `"diamond"` (the `{Label}` decision node,
/// M1.3). A bare `id` uses the id as its own label (set by the parser). All parser-capped (§6/§7).
public record FlowNode(String id, String label, String shape) {

    /// Rect-shaped construction — keeps every existing caller/test byte-for-byte unchanged.
    public FlowNode(String id, String label) {
        this(id, label, "rect");
    }
}
