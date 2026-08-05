package com.sirentide.ir;

/// One UML relationship between two classes, `left OP right`, as authored left-to-right. `kind`
/// fixes both the marker GLYPH and which end carries it ({@link RelationKind#markerAtLeft}); the
/// optional `label` is the `: text` annotation (`null` when absent). `left`/`right` reference class
/// names (auto-vivified as empty classes when never declared with a `class {}` block — mermaid
/// semantics, so `Animal *-- Collar` renders Collar even without its own block). All strings are
/// parser-capped (§6/§7).
///
/// `leftMultiplicity`/`rightMultiplicity` carry the optional UML cardinality authored as a QUOTED
/// token adjacent to the operator (`User "1" --> "*" Order` → `"1"` / `"*"`), `null` when absent —
/// never an empty string, so "no annotation" and "an empty annotation" stay distinguishable. They
/// are peeled off the endpoint by the parser BEFORE the class name is taken: before plan 24d6b22f
/// they were absorbed into the name, minting phantom classes literally called `User "1"` that were
/// distinct boxes from the declared `User`. Cardinality is diagram CONTENT, not noise, so it is
/// carried here and verbalized by the a11y describer rather than discarded at the parse boundary.
///
/// RESIDUAL, deliberate: the cardinality is not yet drawn on the edge near its endpoint — that is
/// layout work (endpoint-anchored label placement with its own collision rules) and is scoped as a
/// follow-up. It is carried and described, never silently dropped.
public record ClassRelation(
    String left,
    String right,
    RelationKind kind,
    String label,
    String leftMultiplicity,
    String rightMultiplicity) {}
