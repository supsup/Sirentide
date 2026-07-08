package com.sirentide.ir;

import java.util.List;

/// One UML class: a `name` plus its two member compartments — `attributes` (fields) and `methods`
/// (a member whose text carries `()`). Each member string is the FULL display text as authored,
/// including any leading visibility marker (`+`/`-`/`#`/`~`), e.g. `+String name` or `+eat() void`.
/// The parser classifies each member line into attributes vs methods; layout stacks the three
/// compartments (name / attributes / methods) top-to-bottom. All strings are parser-capped (§6/§7).
///
/// EMPTY-COMPARTMENT RULE (documented, layout's choice): a class with NO members at all collapses to
/// a SINGLE name box (no dividers); a class with ANY member renders all three compartments so the
/// name/attribute/method structure is always visible (an empty compartment shows as a thin band).
public record ClassBox(String name, List<String> attributes, List<String> methods) {

    public ClassBox {
        attributes = List.copyOf(attributes);
        methods = List.copyOf(methods);
    }

    /// True iff this class carries at least one attribute or method (drives the collapse rule).
    public boolean hasMembers() {
        return !attributes.isEmpty() || !methods.isEmpty();
    }
}
