package com.sirentide.ir;

/// One directed flowchart edge `from --> to`, referencing node ids. Layout draws every edge
/// (including a back-edge that would form a cycle); only DAG-forming edges feed layering.
public record FlowEdge(String from, String to) {}
