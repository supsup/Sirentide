package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.FragmentGuard;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// The math-fragment SEAM guard (SIR-02 structural + SIR-08 metric), red-first.
///
/// A {@link MathFragment} is FOREIGN markup+metrics entering the trusted emitter. Two verified gaps:
///
/// SIR-02 (structural): {@link FragmentGuard#isClean} validated tokens against an element/attribute
/// allowlist but kept NO element stack — so a structurally-malformed fragment (unbalanced tags, a
/// `</g>…<g>` containment escape that closes the emitter's own wrapper group, a bad self-close) was
/// ACCEPTED. The escape lets a foreign `<path>` draw OUTSIDE the emitter's translate/fill wrapper
/// (visual defacement — NOT RCE; the allowlist still blocks script/href/on*). The fix adds a
/// stack-based balance+containment check; a violation REJECTS to the pre-existing raw-text fallback.
///
/// SIR-08 (metric): {@link com.sirentide.layout.MathLabel#measure} consumed `widthPx/heightPx/
/// depthPx` UNVALIDATED, so NaN / negative / 1e308 metrics were accepted → a ~9.2e15-px viewBox
/// (rasterizer DoS) or zero/negative dims (invisible/distorted output). The fix validates every
/// metric finite, non-negative, and font-size-bounded at the same seam, rejecting to the SAME
/// raw-text fallback.
///
/// The fallback is the EXISTING one: a rejected fragment makes {@code MathLabel.renderGuarded}
/// return null, so {@code MathLabel.measure} degrades the run to its raw `$…$` source text — the
/// exact path a contract-violating fragment already took (see {@link MathInLabelsTest}
/// #rejectedFragment_fallsBackToRawText_noG). No new failure mode is invented.
class FragmentSeamGuardTest {

    // ---- structural fixtures (SIR-02) -------------------------------------------

    /// A well-formed, contract-clean fragment: one balanced wrapper group with a self-closed path —
    /// the shape the real LatteX renderer emits. Must STAY clean (accepted) before AND after the fix.
    private static final String CLEAN = "<g><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>";

    /// UNBALANCED: an opening `<g>` that is never closed. Token-clean (only g/path, allowed attrs),
    /// so the old allowlist scan accepted it; the stack check leaves `g` open at EOF → reject.
    private static final String UNBALANCED = "<g><path d=\"M0 0\" fill=\"currentColor\"/>";

    /// CONTAINMENT ESCAPE: closes a `</g>` it never opened (the emitter's OWN wrapper), draws a
    /// foreign path OUTSIDE that wrapper, then opens a fresh `<g>` for the wrapper's real `</g>` to
    /// close. Every token is on the allowlist, so the old scan accepted it — the whole point of SIR-02.
    private static final String ESCAPE = "</g><path d=\"M99 99\" fill=\"currentColor\"/><g>";

    /// BAD SELF-CLOSE: a tag that is BOTH a closing tag and self-closed (`</g/>`) — structurally
    /// nonsensical. The old scan saw a closing `g` with a blank body and accepted it.
    private static final String BAD_SELF_CLOSE = "<g><path d=\"M0 0\" fill=\"currentColor\"/></g/>";

    /// NO ELEMENT: bracket-free text with zero tags. The old tail-scan returned true (nothing to
    /// reject); a trusted fragment must carry at least one element.
    private static final String NO_ELEMENT = "not markup at all";

    // ---- SIR-02 structural: guard verdict red→green -----------------------------

    @Test
    void cleanFragment_isAccepted_invariantAcrossTheFix() {
        // The determinism anchor: a well-formed fragment stays clean, so its emitted bytes are
        // unchanged. If THIS ever flips, the golden suite churns — stop and report.
        assertTrue(FragmentGuard.isClean(CLEAN), "a balanced, contract-clean fragment must stay clean");
    }

    @Test
    void unbalancedFragment_isRejected() {
        // RED at c179eaa: isClean returns TRUE (no balance check). GREEN after the stack validator.
        assertFalse(FragmentGuard.isClean(UNBALANCED),
            "an unbalanced fragment (unclosed <g>) must be rejected");
    }

    @Test
    void containmentEscapeFragment_isRejected() {
        // RED at c179eaa: isClean returns TRUE — the </g><g> escape passes the token allowlist.
        assertFalse(FragmentGuard.isClean(ESCAPE),
            "a </g>…<g> containment escape (closes the emitter wrapper) must be rejected");
    }

    @Test
    void badSelfCloseFragment_isRejected() {
        assertFalse(FragmentGuard.isClean(BAD_SELF_CLOSE),
            "a </g/> tag (closing AND self-closed) must be rejected");
    }

    @Test
    void elementlessFragment_isRejected() {
        assertFalse(FragmentGuard.isClean(NO_ELEMENT),
            "a fragment with no element must be rejected");
    }

    // ---- SIR-02 structural: end-to-end containment (escape never reaches output) --

    /// A renderer that returns the containment-escape fragment. If accepted, the foreign `<path>`
    /// draws outside the MathBox wrapper.
    private static final MathFragmentRenderer ESCAPING = (latex, size) ->
        Optional.of(new MathFragment(ESCAPE, 40, 12, 4));

    @Test
    void containmentEscape_fallsBackToRawText_noForeignPathEscapes() {
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n", ESCAPING);
        assertFalse(reachesMathRun(svg),
            "the escaping fragment must degrade to raw text — no MathBox wrapper: " + svg);
        assertFalse(svg.contains("M99 99"),
            "the foreign path must never reach output (containment held): " + svg);
        assertTrue(svg.contains("<rect"), "the flowchart still renders its node box: " + svg);
    }

    // ---- SIR-08 metric: end-to-end red→green ------------------------------------

    /// A renderer returning a CLEAN fragment but with hostile metrics — the SIR-08 seam.
    private static MathFragmentRenderer withMetrics(double w, double h, double d) {
        return (latex, size) -> Optional.of(new MathFragment(CLEAN, w, h, d));
    }

    @Test
    void cleanFragmentWithValidMetrics_reachesMathRun_invariant() {
        // Determinism anchor for SIR-08: a valid-metric clean fragment is emitted as a MathBox
        // (unchanged) both before and after the fix.
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n", withMetrics(40, 12, 4));
        assertTrue(reachesMathRun(svg), "a valid-metric clean fragment is emitted as a MathBox: " + svg);
    }

    @Test
    void nanMetric_fallsBackToRawText() {
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n", withMetrics(Double.NaN, 12, 4));
        assertFalse(reachesMathRun(svg), "a NaN metric must degrade to raw text (no MathBox): " + svg);
        assertTrue(svg.contains("<rect"), "the flowchart still renders: " + svg);
    }

    @Test
    void negativeMetric_fallsBackToRawText() {
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n", withMetrics(40, -12, 4));
        assertFalse(reachesMathRun(svg), "a negative metric must degrade to raw text (no MathBox): " + svg);
    }

    @Test
    void hugeMetric_fallsBackToRawText() {
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n", withMetrics(1e308, 12, 4));
        assertFalse(reachesMathRun(svg),
            "a 1e308 metric (viewBox-DoS) must degrade to raw text (no MathBox): " + svg);
    }

    @Test
    void infiniteMetric_fallsBackToRawText() {
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n",
            withMetrics(40, Double.POSITIVE_INFINITY, 4));
        assertFalse(reachesMathRun(svg), "an infinite metric must degrade to raw text (no MathBox): " + svg);
    }

    // ---- direct metric-guard verdict (colocated helper) -------------------------

    @Test
    void metricsClean_boundsTheValues() {
        double fs = 16;
        assertTrue(FragmentGuard.metricsClean(40, 12, 4, fs), "small finite non-negative metrics are clean");
        assertTrue(FragmentGuard.metricsClean(0, 0, 0, fs), "zero metrics are clean (a zero-advance fragment)");
        assertFalse(FragmentGuard.metricsClean(Double.NaN, 12, 4, fs), "NaN is not clean");
        assertFalse(FragmentGuard.metricsClean(40, Double.POSITIVE_INFINITY, 4, fs), "Inf is not clean");
        assertFalse(FragmentGuard.metricsClean(40, -1, 4, fs), "negative is not clean");
        assertFalse(FragmentGuard.metricsClean(1e308, 12, 4, fs), "1e308 exceeds the font-size bound");
    }

    // ---- helper -----------------------------------------------------------------

    /// True iff `svg` carries a MathBox wrapper — the `<g fill=… transform=translate(…)>` a laid-out
    /// math run emits (same signature {@link BracedMathLabelTest} keys on).
    private static boolean reachesMathRun(String svg) {
        return svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*");
    }
}
