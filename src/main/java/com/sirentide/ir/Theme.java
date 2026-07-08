package com.sirentide.ir;

import java.util.Locale;

/// A render THEME — the small, closed set of palettes a diagram can bake under (config `%% theme:`).
/// A theme carries two things the emit stage consumes:
///
///   - {@link #background()} — the self-contained BACKGROUND fill. A themed SVG carries its OWN
///     `<rect>` covering the whole viewBox in this colour, so the diagram reads regardless of the
///     host page's background (the Stafficy `/docs` dark-page problem: a transparent diagram lets a
///     dark page show through and its dark structural text vanishes). `null` = NO background rect —
///     the diagram stays transparent exactly as before (the {@link #DEFAULT} light look).
///   - {@link #foreground()} — the explicit colour a page-inheriting `currentColor` structural value
///     (off-slice/axis label text, the display-math fill) resolves to under this theme, so the SVG is
///     SELF-CONTAINED and does not depend on the host page's text colour. `null` = leave
///     `currentColor` untouched (the {@link #DEFAULT} behaviour — it inherits the page as it always
///     did, so the default bake is byte-identical).
///
/// DEFAULT is deliberately a NO-OP (both fields `null`): no background rect + no colour remap ⇒ a
/// no-config / `theme: default` diagram bakes BYTE-IDENTICAL to the pre-theming renderer (option A —
/// themes are additive + opt-in, so all existing goldens stay green without regeneration).
///
/// PALETTE NOTE: the shared node/slice fill palette ({@code Colors.PALETTE}) is deliberately mid-tone
/// and dual-readable (legible on light AND dark), and per-shape label contrast is baked at layout
/// time against each fill — so a theme flips only the two PAGE-LEVEL structural colours here
/// (background + `currentColor` text), never a shape fill (remapping a baked fill would desync its
/// already-computed contrast label). A per-theme FILL palette threaded through every layout is a
/// documented follow-up.
public enum Theme {

    /// The current light look — transparent background, page-inheriting `currentColor` text. A NO-OP
    /// theme (both fields null) so a no-config bake is byte-identical to the pre-theming renderer.
    DEFAULT(null, null),

    /// A dark card — an opaque near-black background with light foreground text, so the diagram reads
    /// on a dark host page (or standalone) without any media query or second render.
    DARK("#1e1e1e", "#e6e6e6"),

    /// A neutral, opaque LIGHT card — a self-contained off-white background with dark foreground text,
    /// so the diagram forces its own light surface regardless of a coloured host page.
    NEUTRAL("#f5f5f5", "#1a1a1a");

    private final String background;
    private final String foreground;

    Theme(String background, String foreground) {
        this.background = background;
        this.foreground = foreground;
    }

    /// The self-contained background fill (`#rrggbb`), or `null` for NO background rect (transparent).
    public String background() {
        return background;
    }

    /// The explicit colour a page-inheriting `currentColor` resolves to under this theme, or `null` to
    /// leave `currentColor` untouched (the default, page-inheriting behaviour).
    public String foreground() {
        return foreground;
    }

    /// Resolve a layout COLOUR under this theme: a structural `currentColor` (the page-inheriting
    /// text/line fill) becomes this theme's explicit {@link #foreground()} so the SVG reads
    /// self-contained; EVERY other value (a `#rrggbb` fill, `none`) passes through unchanged so a
    /// shape's baked contrast label stays exactly as the layout computed it. Under {@link #DEFAULT}
    /// (foreground null) this is the identity — the default bake is byte-identical.
    public String resolve(String color) {
        if (foreground != null && "currentColor".equals(color)) {
            return foreground;
        }
        return color;
    }

    /// Map a config `%% theme:` token to a theme, case-insensitively. An unknown/blank/`null` token
    /// degrades to {@link #DEFAULT} (inert — an unrecognized theme never throws + never fails the
    /// bake, DESIGN §6).
    public static Theme fromToken(String token) {
        if (token == null) {
            return DEFAULT;
        }
        return switch (token.strip().toLowerCase(Locale.ROOT)) {
            case "dark" -> DARK;
            case "neutral" -> NEUTRAL;
            case "default" -> DEFAULT;
            default -> DEFAULT;
        };
    }
}
