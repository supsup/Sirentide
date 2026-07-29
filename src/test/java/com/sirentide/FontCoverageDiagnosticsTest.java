package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
