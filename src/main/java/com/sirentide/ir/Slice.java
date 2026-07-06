package com.sirentide.ir;

/// One pie slice / bar / timeline dot: a label, its (non-negative) magnitude, and an OPTIONAL
/// explicit fill `color`. A `null` color means "use the layout's default palette by index" (the
/// original behaviour); a non-null color is a canonical `#rrggbb` (already normalized by the
/// parser) that overrides the palette for just this item.
public record Slice(String label, double value, String color) {

    /// Palette-default construction (no explicit colour) — keeps every existing caller/test that
    /// builds a `Slice` from just its label+value byte-for-byte unchanged.
    public Slice(String label, double value) {
        this(label, value, null);
    }
}
