package com.sirentide.layout;

/// A laid-out run of text, already reduced to an SVG path `d` string (glyph outlines positioned +
/// scaled + y-flipped by the font-metrics oracle at layout time) plus a fill. Emit just wraps it
/// in `<path>` — so text is contract-clean geometry, no `<text>` element (docs/DESIGN.md §4/§6).
///
/// `onPlate` marks a label laid on a {@link LabelPlate} backing plate (plan 5ce905a8). Play-through
/// frames recolour such a run with the plate-safe accent and dim instead of the page-level ones, so
/// it keeps 4.5 to 1 against the plate in every frame state. Every other run is `false`; the
/// two-argument constructor keeps every existing caller unchanged.
public record GlyphRun(String pathD, String fill, boolean onPlate) implements Shape {

    /// Charges the global layout-time work budget ({@link LayoutWorkBudget}, plan fe8c5bbc slice 2).
    /// A glyph run is THE retention primitive the H2 timeline OOM was made of, so it is charged its
    /// exact path length on top of the fixed `<path …/>` overhead. A no-op when no layout scope is
    /// armed, so direct construction (tests, embedders) is unchanged.
    public GlyphRun {
        LayoutWorkBudget.charge(
            LayoutWorkBudget.WEIGHT_PATH_BASE + (pathD == null ? 0 : pathD.length()));
    }

    /// A run that is not on a backing plate.
    public GlyphRun(String pathD, String fill) {
        this(pathD, fill, false);
    }
}
