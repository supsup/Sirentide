package com.sirentide.layout;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.MathBlock;
import java.util.ArrayList;
import java.util.List;

/// Pure layout for the standalone display-math block (plan sirentide-mathblock): ONE LaTeX
/// expression, rendered FULL-SIZE through the shared {@link MathFragmentRenderer} seam and placed
/// centered on the canvas with uniform padding. Deterministic; the fragment's glyphs are already
/// baked to paths by the renderer, so the whole canvas stays inside the emitter contract.
///
/// SIZE: the block bakes at {@link #DISPLAY_SIZE} px — bigger than an inline label run (11 px in a
/// pie, 16 px in a flowchart node) — so a `mathblock` reads as DISPLAY math (its own line, full
/// size), not inline-in-a-label. That is the whole point of the type.
///
/// DEGRADE (null renderer, an unrenderable expression, or a fragment that fails the contract guard):
/// the raw LaTeX SOURCE is baked as plain-text glyph paths at {@link #DEGRADE_SIZE} — LOUD not
/// silent, the same "the author sees their own source" philosophy the inline `$…$` runs use, and
/// deterministic (so the null-renderer bake is a stable golden). An EMPTY body degrades to the inert
/// empty canvas. NEVER throws.
public final class MathBlockLayout {

    private MathBlockLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    /// Display font size for the baked expression — larger than any inline-label size so the block
    /// reads as full-size display math. Deterministic.
    private static final double DISPLAY_SIZE = 28;

    /// Font size for the plain-text DEGRADE (the raw LaTeX source when no fragment could be baked).
    /// Deliberately smaller than {@link #DISPLAY_SIZE} so a fallback (source-as-text) reads visibly
    /// different from a real typeset bake.
    private static final double DEGRADE_SIZE = 16;

    /// Uniform padding (px) around the fragment on all four sides.
    private static final double PAD = 24;

    /// Page-background fill for the block — the default text colour. A `currentColor` fragment
    /// inherits it via the wrapping `<g fill>` (F2, same as an inline MathBox).
    private static final String FILL = "currentColor";

    /// Lay out a display-math block. `math` renders the whole body as ONE fragment; a null renderer
    /// (or any failure) takes the plain-text degrade. Never throws (malformed → text or inert).
    public static LaidOut layout(MathBlock block, MathFragmentRenderer math) {
        String latex = block.latex();
        if (latex == null || latex.isBlank()) {
            return LaidOut.of(0, 0);   // empty body → the inert empty canvas
        }
        MathFragment frag = MathLabel.renderFragment(latex, DISPLAY_SIZE, math);
        if (frag != null) {
            return placeFragment(frag);
        }
        return degradeToText(latex);
    }

    /// Place a baked fragment centered with padding. The fragment's origin is the LEFT END OF ITS
    /// BASELINE (the seam contract): glyphs rise `heightPx` above and fall `depthPx` below it, so the
    /// canvas is `width + 2·PAD` by `height + depth + 2·PAD` and the baseline sits at `PAD + height`.
    /// With a full-width fragment, x-origin `PAD` IS the horizontal centre.
    private static LaidOut placeFragment(MathFragment frag) {
        double w = frag.widthPx();
        double above = frag.heightPx();
        double below = frag.depthPx();
        double canvasW = w + 2 * PAD;
        double canvasH = above + below + 2 * PAD;
        double baselineY = PAD + above;
        List<Shape> shapes = new ArrayList<>();
        // Reuse the inline MathBox: the emitter wraps innerSvg in one `<g fill translate>` on the
        // baseline (FragmentGuard already ran inside renderFragment).
        shapes.add(new MathBox(PAD, baselineY, FILL, frag.innerSvg()));
        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Degrade path: bake the raw LaTeX source as plain-text glyph paths, centered with padding. A
    /// non-blank body always yields a visible canvas here; only a source whose every glyph is blank
    /// collapses to the inert shell (defensive — the parser already dropped a blank body upstream).
    private static LaidOut degradeToText(String latex) {
        double w = FONT.runWidth(latex, DEGRADE_SIZE);
        double above = FONT.ascent(DEGRADE_SIZE);
        double below = FONT.descent(DEGRADE_SIZE);
        double canvasW = w + 2 * PAD;
        double canvasH = above + below + 2 * PAD;
        double baselineY = PAD + above;
        String d = FONT.textPathD(latex, PAD, baselineY, DEGRADE_SIZE);
        if (d.isBlank()) {
            return LaidOut.of(0, 0);
        }
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new GlyphRun(d, FILL));
        return new LaidOut(canvasW, canvasH, shapes);
    }
}
