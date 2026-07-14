package com.sirentide.ir;

/// One directed flowchart edge `from --> to`, referencing node ids, with an OPTIONAL `label`
/// (the mermaid-style `-->|yes|` annotation; `null` = an unlabeled edge). Layout draws every edge
/// (including a back-edge that would form a cycle); only DAG-forming edges feed layering.
///
/// `style` is the line's stroke style ({@link EdgeStyle}: SOLID / DOTTED / THICK) and `arrow` is
/// whether it carries an arrowhead. Together they encode the mermaid edge-operator vocabulary:
///   `-->` SOLID+arrow · `---` SOLID+open · `-.->` DOTTED+arrow · `-.-` DOTTED+open ·
///   `==>` THICK+arrow · `===` THICK+open.
/// The default is SOLID + arrow (the plain `-->` edge), so every pre-existing caller/golden is
/// byte-for-byte unchanged.
///
/// `stroke`/`strokeWidth` are the OPTIONAL per-edge styling a mermaid `linkStyle` directive sets
/// (plan sirentide-node-edge-styling). Both are `null` for an unstyled edge — the layout then draws
/// it in the built-in edge colour/width (byte-identical). A non-null `stroke` is a canonical
/// `#rrggbb` validated through the SAME {@link com.sirentide.contract.SirentideContract} colour
/// guard the node fills use (hex-only at the DSL boundary — `currentColor`/`none` are not
/// accepted there; a direct-IR caller passing them gets the fallback lightener in FUTURE
/// frames, so hex is the supported surface); `strokeWidth`
/// is a finite non-negative width (`null` → the style-derived default). Both are parse-boundary
/// validated and fail closed (a bad value drops to `null` → the default line colour/width).
public record FlowEdge(String from, String to, String label, EdgeStyle style, boolean arrow,
                       String stroke, Double strokeWidth) {

    public FlowEdge {
        if (style == null) {
            style = EdgeStyle.SOLID;
        }
    }

    /// Full edge sans linkStyle override (stroke/width null) — keeps the flowchart operator-scan
    /// caller (the 5-arg `-->` variant path) byte-for-byte unchanged.
    public FlowEdge(String from, String to, String label, EdgeStyle style, boolean arrow) {
        this(from, to, label, style, arrow, null, null);
    }

    /// Labeled edge with the default SOLID + arrowhead style — keeps every pre-edge-variant caller
    /// (the flowchart chain, the state-diagram transition builder) byte-for-byte unchanged.
    public FlowEdge(String from, String to, String label) {
        this(from, to, label, EdgeStyle.SOLID, true, null, null);
    }

    /// Unlabeled solid arrow — keeps every existing caller/test byte-for-byte unchanged.
    public FlowEdge(String from, String to) {
        this(from, to, null, EdgeStyle.SOLID, true, null, null);
    }

    /// This edge with a `linkStyle` stroke override applied (a copy — records are immutable). A `null`
    /// component leaves that facet at its default; a present one overrides. Used by the flowchart
    /// parser after it has mapped a `linkStyle <index|default>` directive to the drawn-edge order.
    public FlowEdge withStroke(String strokeOverride, Double widthOverride) {
        return new FlowEdge(from, to, label, style, arrow, strokeOverride, widthOverride);
    }
}
