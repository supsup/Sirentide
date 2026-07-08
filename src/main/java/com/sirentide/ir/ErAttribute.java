package com.sirentide.ir;

/// One attribute row of an ER entity table: a `type`, a `name`, and an optional `key` marker
/// (`"PK"` primary / `"FK"` foreign / `"UK"` unique — `null` when the row carries no key). Authored
/// as `type name [PK|FK|UK]`; layout stacks the rows under the entity's name header band. All strings
/// are parser-capped (§6/§7). A row authored with a single token has an EMPTY `type` (the token is
/// the `name`), so a bare `id` still renders as a named row.
public record ErAttribute(String type, String name, String key) {

    public ErAttribute {
        if (type == null) {
            type = "";
        }
        if (name == null) {
            name = "";
        }
    }

    /// The row's display text: `type name` with the optional key appended (`string email` or
    /// `int id PK`). A blank type collapses to just the name (no leading space).
    public String display() {
        StringBuilder d = new StringBuilder();
        if (!type.isEmpty()) {
            d.append(type).append(' ');
        }
        d.append(name);
        if (key != null && !key.isEmpty()) {
            d.append("  ").append(key);
        }
        return d.toString();
    }
}
