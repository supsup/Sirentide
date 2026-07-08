package com.sirentide.layout;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.FragmentGuard;
import com.sirentide.font.FontMetrics;
import com.sirentide.parse.LabelRuns;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Composes a label that mixes text and inline-math `$…$` runs into laid-out shapes on ONE shared
/// baseline (the moat feature, RFC sirentide/39). Text runs become {@link GlyphRun}s via the font
/// oracle; successfully-rendered math runs become {@link MathBox}es. A math run that fails to
/// render — no renderer, renderer returns empty, OR the returned fragment fails
/// {@link FragmentGuard} — DEGRADES to a literal text run of its raw `$…$` source (loud, not
/// silent: the author sees the un-rendered formula rather than a blank).
///
/// This is intentionally FLOWCHART-NODE-SCOPED for the first slice: edge labels and the other
/// diagram types keep their plain-text paths until later slices thread a renderer through them.
public final class MathLabel {

    private MathLabel() {}

    /// A measured composite label: the resolved run list (text + rendered fragments, math failures
    /// already degraded to text), the total advance width, and the vertical extent above/below the
    /// baseline. `ascent`/`descent` are the max of the text metrics and every fragment's box. A
    /// consumer sizes the label WIDTH from {@link #width}; a TALL fragment (multi-row math — a matrix,
    /// cases, a stacked fraction) whose ascent+descent exceeds one line HEIGHT grows the box vertically
    /// too, via {@link #isTall}/{@link #boxHeight}/{@link #baselineInBox} (plan sirentide-tall-math-
    /// labels — the flowchart node box now consumes the height, not just {@link #width}).
    public record Measured(List<Resolved> runs, double width, double ascent, double descent) {}

    /// A run resolved to geometry: either literal `text` (rendered as glyphs) or a `fragment`
    /// (rendered as a MathBox). Exactly one is non-null. `advance` is this run's horizontal width.
    public record Resolved(String text, MathFragment fragment, double advance) {}

    /// True iff the label contains at least one well-formed `$…$` math run.
    public static boolean hasMath(String label) {
        return LabelRuns.hasMath(label);
    }

    /// Split, render each math run ONCE (guarded), and measure the composite. A null `math`
    /// renderer degrades every math run to text — so a null renderer produces the exact same runs
    /// as a label with no `$` at all (the zero-behaviour-change guarantee).
    public static Measured measure(String label, double fontSizePx, FontMetrics fm, MathFragmentRenderer math) {
        List<Resolved> resolved = new ArrayList<>();
        double width = 0;
        double ascent = fm.ascent(fontSizePx);
        double descent = fm.descent(fontSizePx);
        for (LabelRuns.Run run : LabelRuns.split(label)) {
            switch (run) {
                case LabelRuns.Text t -> {
                    double w = fm.runWidth(t.s(), fontSizePx);
                    resolved.add(new Resolved(t.s(), null, w));
                    width += w;
                }
                case LabelRuns.MathRun mr -> {
                    MathFragment frag = renderGuarded(mr.latex(), fontSizePx, math);
                    if (frag != null) {
                        resolved.add(new Resolved(null, frag, frag.widthPx()));
                        width += frag.widthPx();
                        ascent = Math.max(ascent, frag.heightPx());
                        descent = Math.max(descent, frag.depthPx());
                    } else {
                        // Degrade to the raw source INCLUDING the delimiters, so the author sees
                        // exactly what they typed.
                        String raw = "$" + mr.latex() + "$";
                        double w = fm.runWidth(raw, fontSizePx);
                        resolved.add(new Resolved(raw, null, w));
                        width += w;
                    }
                }
            }
        }
        return new Measured(resolved, width, ascent, descent);
    }

    /// True iff this measured label carries a fragment TALLER than one text line at `fontSizePx` —
    /// its combined ascent+descent (already the max of the text metrics and every fragment box, see
    /// {@link #measure}) exceeds the font line height, so a fixed-height label box would clip it. A
    /// short label (plain text, an inline `$x$`, `$E=mc^2$`) returns false, so a consumer keeps its
    /// EXACT fixed-height path — the byte-identical-for-short-labels guarantee. This is the gate every
    /// box-growth consumer keys on (plan sirentide-tall-math-labels): grow ONLY when this is true.
    public static boolean isTall(Measured m, double fontSizePx, FontMetrics fm) {
        return m.ascent() + m.descent() > fm.lineHeight(fontSizePx);
    }

    /// The label-box height a consumer should use for a measured label inside a box whose fixed
    /// height is `fixedH` (e.g. a flowchart node's 36 px): `fixedH` UNCHANGED when the fragment fits
    /// one line (so short labels never perturb an existing bake), else the fragment's full
    /// ascent+descent PLUS the same vertical breathing room a text line gets inside `fixedH`
    /// (`fixedH − lineHeight`), so a grown math box stays node-shaped rather than hugging the glyphs.
    /// A tall fragment (ascent+descent > lineHeight) therefore always yields a height STRICTLY greater
    /// than `fixedH`, so a consumer can treat `result != fixedH` as "this box grew". Never shrinks.
    public static double boxHeight(Measured m, double fixedH, double fontSizePx, FontMetrics fm) {
        double lineH = fm.lineHeight(fontSizePx);
        double ext = m.ascent() + m.descent();
        if (ext <= lineH) {
            return fixedH;   // fits one line → no growth → byte-identical fixed-height path
        }
        double slack = Math.max(0, fixedH - lineH);   // the breathing room a text line has in fixedH
        return ext + slack;                            // > fixedH since ext > lineH
    }

    /// The absolute baseline y for a measured label CENTERED in a box of height `boxH` whose top is
    /// `boxTop` — the vertical placement that pairs with {@link #boxHeight}. The fragment ink is
    /// centered in the box (equal padding above the ascent and below the descent), so it is fully
    /// CONTAINED: `boxH >= ascent+descent` (guaranteed by {@link #boxHeight}) means the ink never
    /// crosses either box edge. Used only on the GROWN path; the fixed-height path keeps its own
    /// text-baseline heuristic so short labels stay byte-identical.
    public static double baselineInBox(Measured m, double boxTop, double boxH) {
        return boxTop + (boxH - (m.ascent() + m.descent())) / 2 + m.ascent();
    }

    /// Emit a measured label left-to-right from pen `(originX, baselineY)`: text runs as one
    /// `<path>` of glyphs each, fragments as a MathBox on the baseline. Advances the pen by each
    /// run's width so text and math sit contiguously on the shared baseline.
    public static void emit(Measured m, double originX, double baselineY, String fill,
            double fontSizePx, FontMetrics fm, List<Shape> out) {
        double penX = originX;
        for (Resolved r : m.runs()) {
            if (r.fragment() != null) {
                // Stamp the label's contrast fill on the wrapper so a currentColor fragment
                // inherits it (F2) — same fill the text runs use, one shared label colour.
                out.add(new MathBox(penX, baselineY, fill, r.fragment().innerSvg()));
            } else if (!r.text().isEmpty()) {
                String d = fm.textPathD(r.text(), penX, baselineY, fontSizePx);
                if (!d.isEmpty()) {
                    out.add(new GlyphRun(d, fill));
                }
            }
            penX += r.advance();
        }
    }

    /// Render one LaTeX expression through the seam and re-validate it through the contract guard,
    /// returning null on ANY failure path (see {@link #renderGuarded}). The PUBLIC entry the display
    /// {@code mathblock} type uses to bake its whole body as ONE fragment WITHOUT the `$…$` splitting
    /// — the caller places the returned fragment via a {@link MathBox} and degrades to text on null.
    public static MathFragment renderFragment(String latex, double fontSizePx, MathFragmentRenderer math) {
        return renderGuarded(latex, fontSizePx, math);
    }

    /// Render one math run and re-validate it through the contract guard. Returns null on ANY
    /// failure path — null renderer, renderer threw, empty optional, or a fragment that escapes the
    /// contract — so the caller degrades it to text. A throwing renderer is caught here (kinder than
    /// dropping the whole bake to the inert shell).
    private static MathFragment renderGuarded(String latex, double fontSizePx, MathFragmentRenderer math) {
        if (math == null) {
            return null;
        }
        Optional<MathFragment> out;
        try {
            out = math.render(latex, fontSizePx);
        } catch (RuntimeException e) {
            return null;
        }
        if (out == null || out.isEmpty()) {
            return null;
        }
        MathFragment frag = out.get();
        return FragmentGuard.isClean(frag.innerSvg()) ? frag : null;
    }
}
