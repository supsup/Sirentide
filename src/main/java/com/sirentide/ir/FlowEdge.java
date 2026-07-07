package com.sirentide.ir;

/// One directed flowchart edge `from --> to`, referencing node ids, with an OPTIONAL `label`
/// (the mermaid-style `-->|yes|` annotation; `null` = an unlabeled edge). Layout draws every edge
/// (including a back-edge that would form a cycle); only DAG-forming edges feed layering.
public record FlowEdge(String from, String to, String label) {

    /// Unlabeled edge — keeps every existing caller/test byte-for-byte unchanged.
    public FlowEdge(String from, String to) {
        this(from, to, null);
    }
}
