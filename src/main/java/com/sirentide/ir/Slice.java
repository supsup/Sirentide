package com.sirentide.ir;

/// One pie slice / bar / timeline dot: a label, its (non-negative) magnitude, an OPTIONAL explicit
/// fill `color`, and an OPTIONAL `valueLabel`. A `null` color means "use the layout's default
/// palette by index"; a non-null color is a canonical `#rrggbb`. A `null` `valueLabel` means "format
/// the value numerically" (the original behaviour); a non-null one is the display string to show
/// INSTEAD of the raw number — the timeline uses it to show `2020-01-15` rather than the epoch-day
/// `18276` its ISO-date placement value became (A2). It never affects placement, only the label.
public record Slice(String label, double value, String color, String valueLabel) {

    /// Palette-default construction (no explicit colour / no value-label) — keeps every existing
    /// caller/test that builds a `Slice` from just its label+value byte-for-byte unchanged.
    public Slice(String label, double value) {
        this(label, value, null, null);
    }

    /// Colour without an explicit value-label (the per-item-colour callers).
    public Slice(String label, double value, String color) {
        this(label, value, color, null);
    }
}
