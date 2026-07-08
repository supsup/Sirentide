package com.sirentide.a11y;

/// A deterministic accessibility payload for a baked SVG: a SHORT `title` (the diagram type/name,
/// surfaced as the SVG `<title>` and the accessible name) and a longer reading-order `desc` (the
/// `<desc>`, built from the diagram's own elements + roles). Both are RAW text (unescaped) — the
/// emitter XML-escapes them at the sink, since `<title>`/`<desc>` are the ONE place text lives as
/// text in Sirentide's otherwise glyph-path-baked output (they are not rendered visually).
///
/// A blank pair means "no a11y" — the {@link com.sirentide.ir.Empty} degrade target and the inert
/// shell carry nothing, so a bare/failed bake stays byte-identical to the pre-a11y inert shell.
public record A11y(String title, String desc) {

    /// The empty payload — both fields blank. The emitter emits NO role/title/desc for it.
    public static final A11y NONE = new A11y("", "");

    public A11y {
        title = title == null ? "" : title;
        desc = desc == null ? "" : desc;
    }

    /// True iff there is nothing to emit (both title and desc blank) — the emitter skips the whole
    /// a11y block (and `role="img"`) so the inert/empty shell is unchanged.
    public boolean isBlank() {
        return title.isBlank() && desc.isBlank();
    }
}
