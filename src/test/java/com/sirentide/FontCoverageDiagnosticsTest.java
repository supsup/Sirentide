package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.font.FontMetrics;
import org.junit.jupiter.api.Test;

/// Receipts for the out-of-coverage glyph signal (plan 933eed50 F1). A code point the bundled label
/// font cannot render bakes as a silent .notdef tofu box today; this surfaces it. The load-bearing
/// invariant: the RENDER IS UNCHANGED (same geometry, byte-identical SVG, still OK) — the diagnostics
/// channel merely NAMES the offending `U+XXXX` code points. A fully-covered (Latin) source produces
/// NO such signal. The theme: silent-WRONG (an unexplained box) becomes a LOUD signal.
class FontCoverageDiagnosticsTest {

    private static final String EMOJI = "flowchart TD\n  A[Ship 🚀] --> B[Done]\n";  // 🚀 = U+1F680
    private static final String LATIN = "flowchart TD\n  A[Ship it] --> B[Done]\n";

    @Test
    void emojiLabelStillRendersUnchangedAndNamesTheCodePoint() {
        // (1) The bake is UNCHANGED: real content, byte-identical to render — geometry is not touched.
        String rendered = Sirentide.render(EMOJI);
        assertTrue(rendered.contains("<path"), "the emoji flowchart still bakes real glyph paths");
        RenderResult r = Sirentide.renderWithDiagnostics(EMOJI);
        assertEquals(rendered, r.svg(), "diagnostics never alter the SVG (byte-identical to render)");

        // (2) It STILL classifies OK (the render genuinely succeeded — real content); the coverage
        // caveat rides in the message + detail, naming the out-of-coverage code point.
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.OK, d.outcome(), "a successful bake stays OK; the caveat is additive");
        assertTrue(d.detail().contains("U+1F680"), "detail names the out-of-coverage code point: " + d.detail());
        assertTrue(d.message().contains("U+1F680"), "the author-facing message names it: " + d.message());
        assertTrue(d.message().toLowerCase(java.util.Locale.ROOT).contains("box"),
            "the message explains the boundary (renders as boxes): " + d.message());
    }

    @Test
    void pureLatinLabelProducesNoCoverageSignal() {
        RenderResult r = Sirentide.renderWithDiagnostics(LATIN);
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.OK, d.outcome());
        assertEquals("Rendered successfully.", d.message(), "a fully-covered source carries no caveat");
        assertEquals("", d.detail(), "no out-of-coverage code points → empty detail");
        assertFalse(d.message().contains("U+"), "no U+ code points named for pure Latin");
    }

    /// Marlow sirentide/706 Finding 1 (HIGH): coverage warnings must correspond to glyph text
    /// ACTUALLY EMITTED, never to source text that does not render. The old okDiagnostics scanned the
    /// raw DSL, so a rocket in a COMMENT produced a U+1F680 coverage detail while the SVG stayed
    /// byte-identical to the ASCII control — a warning about glyphs that do not exist.
    @Test
    void emojiInAnIgnoredCommentProducesNoCoverageSignal() {
        String withComment = "%% ignored: 🚀\nflowchart TD\n  A[Ship it] --> B[Done]\n";
        assertEquals(Sirentide.render(LATIN).replace("flowchart", "flowchart"),
            Sirentide.render(LATIN), "self-check: control renders");
        RenderResult r = Sirentide.renderWithDiagnostics(withComment);
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.OK, d.outcome());
        assertFalse(d.detail().contains("U+1F680"),
            "a comment never bakes as glyphs, so it must not warn: " + d.detail());
        assertEquals("", d.detail(), "no rendered out-of-coverage text → empty detail");
    }

    /// Finding 1, second reproducer: `accDescr` feeds the ARIA description, not glyph emission —
    /// an emoji there must not generate a coverage caveat either.
    @Test
    void emojiInAccDescrProducesNoCoverageSignal() {
        String withAccDescr = "flowchart TD\n  accDescr: launch 🚀\n  A[Ship it] --> B[Done]\n";
        Diagnostics d = Sirentide.renderWithDiagnostics(withAccDescr).diagnostics();
        assertFalse(d.detail().contains("U+1F680"),
            "accDescr is a11y metadata, never glyphs — no coverage warning: " + d.detail());
    }

    /// The POSITIVE control that keeps the two tests above honest: the same emoji in a RENDERED label
    /// still warns (already pinned by emojiLabelStillRendersUnchangedAndNamesTheCodePoint, restated
    /// here so the ignored-source pair cannot pass by the caveat being dead entirely).
    @Test
    void renderedEmojiStillWarnsWhileIgnoredEmojiDoesNot() {
        assertTrue(Sirentide.renderWithDiagnostics(EMOJI).diagnostics().detail().contains("U+1F680"),
            "the caveat mechanism itself must stay alive");
    }

    // ------------------------------------------------------------------
    // Marlow sirentide/712 HIGH 1: the coverage corpus must be the text that ACTUALLY reaches
    // glyph emission — not a parallel re-derivation from pre-layout IR. Both directions below
    // were reviewer-reproduced exact-output histories at tip 8de63118.
    // ------------------------------------------------------------------

    /**
     * FALSE-POSITIVE DISCRIMINATOR (reviewer's, red before the fix): a flowchart edge label of
     * 400 ASCII characters followed by a rocket. FlowchartLayout ellipsizes the label before
     * emission, so the rocket is never baked — the SVG is byte-identical to the same label
     * ending in ASCII 'x'. A coverage caveat naming U+1F680 here warns about a glyph that
     * does not exist in the output.
     */
    @Test
    void emojiEllipsizedOutOfAnEdgeLabelProducesNoCoverageSignal() {
        String pad = "a".repeat(400);
        String control = "flowchart LR\n  A -->|" + pad + "x| B\n";
        String probe = "flowchart LR\n  A -->|" + pad + "🚀| B\n";
        assertEquals(Sirentide.render(control), Sirentide.render(probe),
            "byte-identity control: the differing suffix is past the emitted ellipsis");
        Diagnostics d = Sirentide.renderWithDiagnostics(probe).diagnostics();
        assertEquals(Outcome.OK, d.outcome());
        assertFalse(d.detail().contains("U+1F680"),
            "no rocket glyph was emitted, so no coverage signal may name it: " + d.detail());
        assertFalse(d.message().contains("U+1F680"),
            "the author-facing message must not warn about un-emitted text: " + d.message());
    }

    /**
     * The reviewer-required NON-FLOW ellipsization control: the repair must be a shared
     * emission-seam property, not a flow-only patch. A timeline event label long enough to
     * ellipsize behaves identically.
     */
    @Test
    void emojiEllipsizedOutOfATimelineLabelProducesNoCoverageSignal() {
        String pad = "a".repeat(400);
        String control = "timeline\n  \"" + pad + "x\" : 2020\n";
        String probe = "timeline\n  \"" + pad + "🚀\" : 2020\n";
        assertEquals(Sirentide.render(control), Sirentide.render(probe),
            "byte-identity control: the timeline label ellipsizes before the differing suffix");
        Diagnostics d = Sirentide.renderWithDiagnostics(probe).diagnostics();
        assertEquals(Outcome.OK, d.outcome());
        assertFalse(d.detail().contains("U+1F680"),
            "non-flow layouts share the emission seam: " + d.detail());
    }

    /**
     * FALSE-NEGATIVE DISCRIMINATOR (reviewer's, red before the fix): a math run whose fragment
     * fails the FragmentGuard degrades to plain glyphs — the rocket really does bake as a tofu
     * box — but the pre-layout walk dropped every MathRun when a renderer was present, so the
     * feature's core promise (name emitted tofu) was violated exactly where the degrade
     * happens. The SVG-identity control pins that the diagnostics twin renders the same bytes.
     */
    @Test
    void emojiInAMathRunThatFailsTheFragmentGuardStillProducesCoverageSignal() {
        String dsl = "flowchart LR\n  A[$🚀$]\n";
        com.sirentide.api.MathFragmentRenderer rejected = (latex, fontSizePx) ->
            java.util.Optional.of(new com.sirentide.api.MathFragment("<script>x</script>", 10, 10, 0));
        RenderResult r = Sirentide.renderWithDiagnostics(dsl, rejected);
        assertEquals(Sirentide.render(dsl, rejected), r.svg(),
            "identity control: diagnostics never alter the SVG");
        Diagnostics d = r.diagnostics();
        assertEquals(Outcome.OK, d.outcome(), "the degraded bake still succeeds");
        assertTrue(d.detail().contains("U+1F680"),
            "the guard-degraded raw $…$ run bakes the rocket as tofu — the signal must name it: "
                + d.detail());
    }

    /**
     * MARLOW DISCRIMINATOR (sirentide/720, replayed at 729; red before the sink stack): a
     * MathFragmentRenderer is an application callback with NO non-reentrancy restriction, so
     * an inner renderWithDiagnostics call must not erase the outer render's emission capture.
     * Both required directions: the OUTER sink resumes after the inner render (outer detail
     * still names its own rocket), and INNER glyphs never contaminate the outer corpus (the
     * saucer stays out of the outer detail — and vice versa).
     */
    @Test
    void nestedDiagnosticsRenderDoesNotEraseOrContaminateTheOuterCapture() {
        String innerDsl = "flowchart TD\n  A[probe 🛸]\n";   // U+1F6F8 FLYING SAUCER
        java.util.concurrent.atomic.AtomicReference<Diagnostics> inner =
            new java.util.concurrent.atomic.AtomicReference<>();
        com.sirentide.api.MathFragmentRenderer nesting = (latex, fontSizePx) -> {
            inner.set(Sirentide.renderWithDiagnostics(innerDsl).diagnostics());
            return java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<g><path d=\"M0 0\"/></g>", 10, 10, 0));
        };
        String outerDsl = "flowchart TD\n  A[$x$ 🚀]\n";     // live math run, then U+1F680

        RenderResult outer = Sirentide.renderWithDiagnostics(outerDsl, nesting);

        assertEquals(Sirentide.render(outerDsl, nesting), outer.svg(),
            "identity control: the nested render must not perturb the outer bake");
        assertEquals(Outcome.OK, outer.diagnostics().outcome());
        assertTrue(inner.get().detail().contains("U+1F6F8"),
            "control: the inner render names its own saucer: " + inner.get().detail());
        assertTrue(outer.diagnostics().detail().contains("U+1F680"),
            "the OUTER sink must resume after the inner render — its rocket was emitted: "
                + outer.diagnostics().detail());
        assertFalse(outer.diagnostics().detail().contains("U+1F6F8"),
            "inner glyphs must never contaminate the outer corpus: " + outer.diagnostics().detail());
        assertFalse(inner.get().detail().contains("U+1F680"),
            "and the outer corpus must not leak inward: " + inner.get().detail());
    }

    /**
     * Marlow sirentide/733 HIGH, his probe promoted verbatim: a {@code MathFragmentRenderer}
     * callback that calls ORDINARY {@code Sirentide.render} (not the diagnostics twin) on the
     * same thread must not inject the inner render's glyphs into the outer diagnostic corpus.
     * The 720/729 repair only pushed a fresh frame when the nested call was itself a
     * diagnostics entrypoint — an equally legal plain re-entry appended to the still-armed
     * outer sink, so a clean outer SVG reported out-of-coverage for glyphs that exist only in
     * an unrelated inner render.
     */
    @Test
    void plainRenderInsideMathCallbackDoesNotContaminateTheOuterDiagnostics() {
        String innerDsl = "flowchart TD\n  A[probe 🛸]\n";   // U+1F6F8 FLYING SAUCER
        java.util.concurrent.atomic.AtomicReference<String> innerSvg =
            new java.util.concurrent.atomic.AtomicReference<>();
        com.sirentide.api.MathFragmentRenderer nesting = (latex, fontSizePx) -> {
            innerSvg.set(Sirentide.render(innerDsl));       // ORDINARY render — no diagnostics
            return java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<g><path d=\"M0 0\"/></g>", 10, 10, 0));
        };
        String outerDsl = "flowchart TD\n  A[$x$ plain]\n";  // no unsupported glyphs of its own

        RenderResult outer = Sirentide.renderWithDiagnostics(outerDsl, nesting);

        // POSITIVE CONTROL: the nested plain render really ran and produced a real bake.
        // Without it, a callback that silently failed would pass the contamination
        // assertions below vacuously.
        assertNotNull(innerSvg.get(), "the nested plain render must have run");
        assertTrue(innerSvg.get().contains("<svg"),
            "control: the nested plain render must produce a real bake");
        assertEquals(Outcome.OK, outer.diagnostics().outcome(),
            "outer diagram has no unsupported glyphs of its own: " + outer.diagnostics());
        assertFalse(outer.diagnostics().detail().contains("U+1F6F8"),
            "an inner PLAIN render's glyphs must never reach the outer diagnostic corpus: "
                + outer.diagnostics().detail());
    }

    /** The frames twin of the plain-re-entry discriminator — BOTH diagnostic twins scope. */
    @Test
    void plainRenderInsideMathCallbackDoesNotContaminateTheOuterFramesDiagnostics() {
        String innerDsl = "flowchart TD\n  A[probe 🛸]\n";
        com.sirentide.api.MathFragmentRenderer nesting = (latex, fontSizePx) -> {
            Sirentide.render(innerDsl);
            return java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<g><path d=\"M0 0\"/></g>", 10, 10, 0));
        };
        com.sirentide.api.FramesResult outer =
            Sirentide.renderFramesWithDiagnostics("flowchart TD\n  A[$x$ plain]\n", nesting);

        assertEquals(Outcome.OK, outer.diagnostics().outcome(),
            "outer frames diagram is clean: " + outer.diagnostics());
        assertFalse(outer.diagnostics().detail().contains("U+1F6F8"),
            "frames twin: inner plain glyphs must not contaminate: "
                + outer.diagnostics().detail());
    }

    @Test
    void fontCoverageOracleAgreesWithGlyphLookup() {
        FontMetrics fm = FontMetrics.bundled();
        assertTrue(fm.covers('A'), "Latin 'A' is covered");
        assertTrue(fm.covers('='), "a math symbol is covered");
        assertFalse(fm.covers(0x1F680), "the rocket emoji is out of coverage");
        // Control chars are SKIPPED (structural, never a glyph), so a newline is not reported.
        assertEquals(java.util.List.of(0x1F680),
            fm.uncoveredCodePoints("ok 🚀\n", 10), "only the emoji is reported, not the newline");
        assertEquals(java.util.List.of(), fm.uncoveredCodePoints("plain latin", 10),
            "a covered string reports nothing");
    }
}
