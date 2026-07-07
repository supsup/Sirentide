package com.sirentide.math;

import com.lattex.api.LatteX;
import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import java.util.Optional;

/// The REAL, LatteX-backed {@link MathFragmentRenderer} — the demonstrator that proves the moat:
/// actual baked LaTeX (glyph outlines from the STIX math font, fraction/radical bars as `<rect>`)
/// rendered inside a Sirentide diagram label, not a fake stand-in. It closes the seam that
/// {@link com.sirentide.api.Sirentide#render(String, MathFragmentRenderer)} opens (RFC sirentide/39,
/// math-in-labels S2).
///
/// TEST-SCOPE ON PURPOSE. Sirentide core ships ZERO runtime dependencies (the hermetic bake, see
/// build.gradle.kts) — so LatteX is NOT a runtime/`implementation` dependency of Sirentide. A
/// downstream consumer that wants baked math supplies BOTH this renderer (or an equivalent) AND the
/// LatteX jar on its own classpath, then injects it at the seam. The core stays hermetic; the math
/// backend is the consumer's choice.
///
/// The mapping is a straight field copy: LatteX's `com.lattex.api.MathFragment` has the SAME record
/// shape as Sirentide's `com.sirentide.api.MathFragment` (innerSvg, widthPx, heightPx, depthPx) and
/// the SAME coordinate contract (origin = left end of the baseline, glyphs up = negative local y).
/// LatteX's `innerSvg` is already FragmentGuard-clean `{g,path,rect}` with numeric transforms — the
/// caller re-validates it through {@link com.sirentide.contract.FragmentGuard} regardless, so a
/// contract-violating fragment degrades to raw text exactly like a render failure.
///
/// Honors the interface contract's failure mode: ANY exception (LatteX throws
/// `MathSyntaxException` on malformed input like an unbalanced brace or an unknown command) maps to
/// {@link Optional#empty()} — malformed math degrades to its raw `$...$` source text, LOUD not
/// silent, and never propagates out of a bake.
public final class LatteXMathFragmentRenderer implements MathFragmentRenderer {

    @Override
    public Optional<MathFragment> render(String latex, double fontSizePx) {
        try {
            com.lattex.api.MathFragment f = LatteX.renderFragment(latex, fontSizePx);
            return Optional.of(new MathFragment(
                f.innerSvg(), f.widthPx(), f.heightPx(), f.depthPx()));
        } catch (RuntimeException | StackOverflowError e) {
            // Malformed / unsupported LaTeX (or any renderer bug) -> empty -> raw-text fallback.
            return Optional.empty();
        }
    }
}
