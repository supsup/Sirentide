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
public record FlowEdge(String from, String to, String label, EdgeStyle style, boolean arrow) {

    public FlowEdge {
        if (style == null) {
            style = EdgeStyle.SOLID;
        }
    }

    /// Labeled edge with the default SOLID + arrowhead style — keeps every pre-edge-variant caller
    /// (the flowchart chain, the state-diagram transition builder) byte-for-byte unchanged.
    public FlowEdge(String from, String to, String label) {
        this(from, to, label, EdgeStyle.SOLID, true);
    }

    /// Unlabeled solid arrow — keeps every existing caller/test byte-for-byte unchanged.
    public FlowEdge(String from, String to) {
        this(from, to, null, EdgeStyle.SOLID, true);
    }
}
