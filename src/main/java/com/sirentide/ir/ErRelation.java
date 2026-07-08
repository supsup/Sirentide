package com.sirentide.ir;

/// One ER relationship between two entities, `left <leftCard>--<rightCard> right` as authored
/// left-to-right. Each end carries its OWN crow-foot {@link ErCardinality} (`leftCard` at the left
/// entity, `rightCard` at the right entity) — the faithful marker combo at each end is the whole
/// point of the type. `identifying` is true for the solid `--` operator (an identifying relationship)
/// and false for the dashed `..` operator (non-identifying); layout draws the edge line solid or
/// dashed accordingly. The optional `label` is the `: text` verb annotation (`null` when absent).
/// `left`/`right` reference entity names (auto-vivified as attribute-less entities when never given
/// a `{ }` block — mermaid semantics). All strings are parser-capped (§6/§7).
public record ErRelation(String left, String right, ErCardinality leftCard, ErCardinality rightCard,
                         boolean identifying, String label) {}
