package com.sirentide.layout;

/// Shared colour helpers for the layout engines. Consolidates two things that were previously
/// copy-pasted across the per-item-coloured layouts:
///
///   - {@link #PALETTE} — the one small fixed palette pie/xychart/timeline/gantt all index into by
///     item order (four byte-identical copies collapsed to this single source of truth), and
///   - {@link #contrastFill} — the luminance-driven black/white label picker (extracted from
///     PieLayout so flowchart/state node labels and sequence actor-head labels contrast against
///     their BOX fill, not the page theme — text ON a filled shape must read against that fill).
///
/// Package-private: a layout-internal utility, never emitted.
final class Colors {

    private Colors() {}

    /// A small fixed palette of contract-clean hex fills (mid-tone, readable on light or dark).
    static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    /// Pick a black or white label fill by the fill colour's perceptual luminance, so a label drawn
    /// ON a filled shape stays legible on both light and dark fills (a hardcoded white vanished on
    /// the palette's light fills; a page-theme `currentColor` label vanished on a light box under a
    /// dark theme).
    ///
    /// Defense-in-depth (H1): the parse boundary already restricts per-shape fills to hex, so this
    /// only ever sees `#rrggbb` — but a non-hex value must NEVER throw here (a swallowed
    /// {@code NumberFormatException} once collapsed the whole diagram to a 0x0 inert shell). Falls
    /// back to a legible default on anything that isn't a clean 6-digit hex.
    static String contrastFill(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
            return "#ffffff";
        }
        int r, g, b;
        try {
            r = Integer.parseInt(hex.substring(1, 3), 16);
            g = Integer.parseInt(hex.substring(3, 5), 16);
            b = Integer.parseInt(hex.substring(5, 7), 16);
        } catch (NumberFormatException e) {
            return "#ffffff";
        }
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.55 ? "#000000" : "#ffffff";
    }
}
