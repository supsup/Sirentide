package com.sirentide.ir;

/// The four entity-relationship crow-foot cardinalities — the ENTIRE semantic content of an ER
/// diagram, so a wrong marker at a wrong end reads as "broken" (the fidelity crux). Each decomposes
/// into TWO orthogonal facets that the marker glyph draws as two stacked symbols at the entity end:
///   - `many`  — the INNER symbol (nearest the entity): a CROW'S-FOOT (three-prong fork) when true,
///     a single BAR (tick) when false. This is the MAX side (one vs many).
///   - `optional` — the OUTER symbol (just beyond the inner one): a small CIRCLE when true, a BAR
///     when false. This is the MIN side (zero vs one).
///
/// The DSL tokens (a two-char cardinality glued to the `--`/`..` operator) map as:
///   - `|o` / `o|` → `ZERO_OR_ONE`   (bar + circle)      — many=false, optional=true
///   - `||`        → `EXACTLY_ONE`   (bar + bar, a double tick) — many=false, optional=false
///   - `}o` / `o{` → `ZERO_OR_MANY`  (crow's-foot + circle)     — many=true,  optional=true
///   - `}|` / `|{` → `ONE_OR_MANY`   (crow's-foot + bar)        — many=true,  optional=false
public enum ErCardinality {
    ZERO_OR_ONE(false, true),
    EXACTLY_ONE(false, false),
    ZERO_OR_MANY(true, true),
    ONE_OR_MANY(true, false);

    private final boolean many;
    private final boolean optional;

    ErCardinality(boolean many, boolean optional) {
        this.many = many;
        this.optional = optional;
    }

    /// True iff the INNER symbol (nearest the entity) is a crow's-foot fork (a "many" side); false →
    /// a single bar tick (a "one" side).
    public boolean many() {
        return many;
    }

    /// True iff the OUTER symbol is a circle (a "zero-or-…" optional side); false → a bar (a
    /// mandatory "…or-one"/"exactly-one" side).
    public boolean optional() {
        return optional;
    }
}
