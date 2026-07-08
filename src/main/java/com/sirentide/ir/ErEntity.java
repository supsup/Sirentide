package com.sirentide.ir;

import java.util.List;

/// One ER entity — a titled TABLE: a `name` header band over zero or more {@link ErAttribute} rows.
/// An entity authored with NO `{ }` block (or referenced only by a relationship) has an empty
/// `attributes` list and renders as a plain name box; an entity with a block renders the header plus
/// one row per attribute. `left`/`right` relationship references auto-vivify an attribute-less entity
/// (mermaid semantics). All strings are parser-capped (§6/§7).
public record ErEntity(String name, List<ErAttribute> attributes) {

    public ErEntity {
        attributes = List.copyOf(attributes);
    }

    /// True iff this entity carries at least one attribute row (drives the header-only collapse).
    public boolean hasAttributes() {
        return !attributes.isEmpty();
    }
}
