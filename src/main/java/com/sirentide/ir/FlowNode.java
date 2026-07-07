package com.sirentide.ir;

/// One flowchart node: a stable `id` (its identity in edge declarations) plus a display `label`.
/// A bare `id` with no `[Label]` uses the id as its own label (set by the parser). Both are already
/// length-capped by the parser (DESIGN §6/§7).
public record FlowNode(String id, String label) {}
