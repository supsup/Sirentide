package com.sirentide.ir;

/// One UML relationship between two classes, `left OP right`, as authored left-to-right. `kind`
/// fixes both the marker GLYPH and which end carries it ({@link RelationKind#markerAtLeft}); the
/// optional `label` is the `: text` annotation (`null` when absent). `left`/`right` reference class
/// names (auto-vivified as empty classes when never declared with a `class {}` block — mermaid
/// semantics, so `Animal *-- Collar` renders Collar even without its own block). All strings are
/// parser-capped (§6/§7).
public record ClassRelation(String left, String right, RelationKind kind, String label) {}
