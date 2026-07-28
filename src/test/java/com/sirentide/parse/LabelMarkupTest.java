package com.sirentide.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Marlow's required controls (sirentide/667) for the label-markup detector.
///
/// The POSITIVE controls here are not decoration — they are what stops this becoming an
/// over-rejecting validator. My own proposed fix was "reject any label containing angle
/// brackets", which he caught: it would have made `x < y` and inline math illegal. A
/// detector without those controls trades an under-detecting renderer for an over-rejecting
/// one, and looks equally green either way.
class LabelMarkupTest {

    @Test
    @DisplayName("the exact repro is detected, and the echoed token is the tag")
    void detectsTheReproTag() {
        assertEquals("<br/>", LabelMarkup.offendingTag("TRUE NEGATIVE<br/>safe to act on"));
    }

    @Test
    @DisplayName("structural, not a one-literal check: tag VARIANTS are all caught")
    void detectsTagVariantsNotJustTheOneIHit() {
        // Fixing only the literal I happened to hit is the narrow-fix failure mode, and it is
        // the one I actually proposed before Marlow widened it.
        assertEquals("<br>", LabelMarkup.offendingTag("a<br>b"));
        assertEquals("<BR/>", LabelMarkup.offendingTag("a<BR/>b"));
        assertEquals("<b>", LabelMarkup.offendingTag("a<b>bold</b>"));
        assertEquals("</b>", LabelMarkup.offendingTag("plain</b>"));
        assertEquals("<span class=\"x\">", LabelMarkup.offendingTag("a<span class=\"x\">b"));
    }

    @Test
    @DisplayName("POSITIVE CONTROL: ordinary angle-bracket prose stays legal")
    void ordinaryComparisonsAreNotMarkup() {
        assertNull(LabelMarkup.offendingTag("x < y"));
        assertNull(LabelMarkup.offendingTag("a <- b"));
        assertNull(LabelMarkup.offendingTag("n <= 3 and m > 4"));
        assertNull(LabelMarkup.offendingTag("3<5"));
        assertNull(LabelMarkup.offendingTag("cost < 10ms"));
        // an unclosed bracket is not a tag
        assertNull(LabelMarkup.offendingTag("a <b c"));
    }

    @Test
    @DisplayName("POSITIVE CONTROL: inline math is exempt structurally, not by special case")
    void inlineMathIsExempt() {
        // `<` inside $...$ is ordinary LaTeX. LabelRuns splits it into a MathRun, which this
        // detector does not scan at all — so the exemption cannot rot out of sync with the
        // math syntax the way a hand-written carve-out would.
        assertNull(LabelMarkup.offendingTag("$a < b$"));
        assertNull(LabelMarkup.offendingTag("rate $\\alpha<\\beta$ holds"));
        // ...but markup in the LITERAL part of a mixed label is still caught
        assertEquals("<br/>", LabelMarkup.offendingTag("$a<b$ then<br/>done"));
    }

    @Test
    @DisplayName("the echoed token is BOUNDED and control-sanitized")
    void hostileLabelsCannotPumpTheDiagnostic() {
        String hostile = "a<span " + "x".repeat(500) + ">b";
        String tag = LabelMarkup.offendingTag(hostile);
        assertTrue(tag.length() <= LabelMarkup.MAX_ECHO + 3,
            "echo must be bounded, was " + tag.length());

        // A diagnostic is an output surface: it gets printed, logged and pasted. A hostile
        // label must not be able to inject newlines or terminal escapes into it.
        String sanitized = LabelMarkup.offendingTag("a<b\nc\u001b[31md>e");
        assertTrue(sanitized.indexOf('\n') < 0, "newline must not survive: " + sanitized);
        assertTrue(sanitized.indexOf('\u001b') < 0, "escape must not survive: " + sanitized);
    }

    @Test
    @DisplayName("a label with no bracket at all is untouched")
    void plainLabelsPass() {
        assertNull(LabelMarkup.offendingTag("TRUE NEGATIVE"));
        assertNull(LabelMarkup.offendingTag(""));
        assertNull(LabelMarkup.offendingTag(null));
    }
}
