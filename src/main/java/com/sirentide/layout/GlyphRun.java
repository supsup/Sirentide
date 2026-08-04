package com.sirentide.layout;

/// A laid-out run of text, already reduced to an SVG path `d` string (glyph outlines positioned +
/// scaled + y-flipped by the font-metrics oracle at layout time) plus a fill. Emit just wraps it
/// in `<path>` — so text is contract-clean geometry, no `<text>` element (docs/DESIGN.md §4/§6).
public record GlyphRun(String pathD, String fill) implements Shape {

    /// Charges the global layout-time work budget ({@link LayoutWorkBudget}, plan fe8c5bbc slice 2).
    /// A glyph run is THE retention primitive the H2 timeline OOM was made of, so it is charged its
    /// exact path length on top of the fixed `<path …/>` overhead. A no-op when no layout scope is
    /// armed, so direct construction (tests, embedders) is unchanged.
    public GlyphRun {
        LayoutWorkBudget.charge(
            LayoutWorkBudget.WEIGHT_PATH_BASE + (pathD == null ? 0 : pathD.length()));
    }
}
