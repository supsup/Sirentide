package com.sirentide.layout;

import com.sirentide.font.FontMetrics;
import com.sirentide.font.TextBox;
import java.util.ArrayList;
import java.util.List;

/// Appends a visible CAPTION band below any laid-out diagram (plan sirentide-caption-note-directive).
/// The caption — the `%% caption:`/`%% note:` config directive — is centered on the canvas, word-
/// wrapped to the canvas width, and rendered in the theme-adaptive `currentColor` fill as glyph
/// PATHS (like every other Sirentide label), so it is inert-by-construction and needs no sanitizer
/// change: it is just more `<path>`s. A null/blank caption returns the input {@link LaidOut}
/// UNCHANGED — so a diagram with no caption is byte-identical to the pre-feature bake.
public final class CaptionLayout {

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final double SIZE = 11;    // caption font size (a touch smaller than a 12px label)
    private static final double GAP = 12;      // vertical gap between the diagram bottom and the caption
    private static final double PAD_BELOW = 10;  // breathing room under the last caption line
    private static final double PAD_SIDE = 10;   // horizontal inset the wrap width leaves on each side
    /// Theme-adaptive: the page's text colour (dark on light, light on dark), the same de-emphasised
    /// fill the timeline/pie outside-labels use — so a caption reads on any theme without a bg swatch.
    private static final String FILL = "currentColor";

    private CaptionLayout() {
    }

    /// Return `laid` with a centered, wrapped caption band added below it (canvas height grown to fit),
    /// or `laid` unchanged when `caption` is null/blank.
    public static LaidOut withCaption(LaidOut laid, String caption) {
        if (caption == null || caption.isBlank()) {
            return laid;
        }
        double wrapWidth = Math.max(0, laid.width() - 2 * PAD_SIDE);
        TextBox tb = FONT.measureWrapped(caption, wrapWidth, SIZE);
        double lineH = FONT.lineHeight(SIZE);
        double cx = laid.width() / 2;
        double bandTop = laid.height() + GAP;

        List<Shape> shapes = new ArrayList<>(laid.shapes());
        List<String> lines = tb.lines();
        for (int k = 0; k < lines.size(); k++) {
            // measureWrapped only breaks on spaces, so a single word wider than wrapWidth (or any
            // over-long line) survives intact and would center with a NEGATIVE originX, clipping off
            // BOTH canvas edges. Ellipsize each line to wrapWidth (bounded output, matching how edge
            // labels truncate) so the centered line always fits: w <= wrapWidth ⇒ originX >= PAD_SIDE.
            // Lines already within wrapWidth are returned unchanged, so normal captions bake identically.
            String line = FONT.ellipsize(lines.get(k), wrapWidth, SIZE);
            double w = FONT.runWidth(line, SIZE);
            // Baseline of line k: its slot top + one ascent (~0.8·size), centered on cx.
            double baseline = bandTop + k * lineH + SIZE * 0.8;
            String d = FONT.textPathD(line, cx - w / 2, baseline, SIZE);
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, FILL));
            }
        }
        double newHeight = bandTop + lines.size() * lineH + PAD_BELOW;
        return new LaidOut(laid.width(), newHeight, shapes);
    }
}
