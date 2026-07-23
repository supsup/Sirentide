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
/// non-null `stroke` is a canonical `#rrggbb` border colour parse-validated through the SAME
/// {@link com.sirentide.contract.SirentideContract} colour guard as `color` (the DSL boundary is
/// deliberately hex-only; `currentColor`/`none` are not accepted there, and a direct-IR caller
/// passing them is off the supported surface: `currentColor` theme-resolves first, so only under
/// the DEFAULT theme does it reach the FUTURE-frame fallback lightener — hex is the contract);
/// `strokeWidth` is a finite non-negative border width (`null` → the layout's default when a stroke is
/// set); `textColor` overrides the auto-contrast label colour with an author `#hex`. Every value is
/// validated at the parse boundary and fails closed (a bad value drops to `null` → the default).
///
/// `status` is the OPTIONAL SEMANTIC status ROLE (plan fa3ccf16 wish A): one of `"danger"`, `"warn"`,
/// `"ok"`, `"neutral"` when the node was assigned a built-in `status-*` class, else `null`. It is a
/// PRESENTATION-INDEPENDENT token: the fill/stroke/width above carry the *visual* status encoding (a
/// closed pastel palette + a stroke-width severity channel), while `status` is the word the a11y
/// describer speaks ("HOSTROOT (danger)") so status is NEVER color-only. It is tied to the class NAME,
/// not the resolved fill — an author who overrides a `status-*` classDef's fill still gets the spoken
/// word. `null` for every unclassed / non-status node → the desc is byte-identical to before.
public record FlowNode(String id, String label, String shape, String color,
                       String stroke, Double strokeWidth, String textColor, String status) {

    /// Presentation-only construction (no semantic status) — keeps every classDef-styling caller
    /// (and the parser's node-styling path before status roles) byte-for-byte unchanged.
    public FlowNode(String id, String label, String shape, String color,
                    String stroke, Double strokeWidth, String textColor) {
        this(id, label, shape, color, stroke, strokeWidth, textColor, null);
    }

    /// Fill-only construction (no stroke/textColor/status styling) — keeps every colour-carrying caller
    /// (the state-diagram wrapper, the a11y describer's rebuild) byte-for-byte unchanged.
    public FlowNode(String id, String label, String shape, String color) {
        this(id, label, shape, color, null, null, null, null);
    }

    /// Colourless construction (default box fill) — keeps every existing shape-carrying caller/test
    /// byte-for-byte unchanged.
    public FlowNode(String id, String label, String shape) {
        this(id, label, shape, null, null, null, null, null);
    }

    /// Rect-shaped, colourless construction — keeps every existing caller/test byte-for-byte unchanged.
    public FlowNode(String id, String label) {
        this(id, label, "rect", null, null, null, null, null);
    }
}
