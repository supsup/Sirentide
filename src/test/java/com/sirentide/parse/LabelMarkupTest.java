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

    /// Marlow's finding 3 (sirentide/676), as his exact discriminator.
    ///
    /// The scanner used to accept `<`, an optional `/`, ONE ascii letter, and then any later
    /// `>`. That is bracket-pairing, not tag grammar, so `0<x+y>1` was reported as the tag
    /// `<x+y>` -- ordinary mathematical comparison prose rejected as markup.
    ///
    /// My original positive controls could not catch it: every one put a SPACE or a
    /// non-letter after the bracket (`x < y`, `a <- b`, `3<5`), and only a letter directly
    /// after `<` reaches the name branch at all. So the controls tested the arm that was
    /// already safe.
    @Test
    @DisplayName("POSITIVE CONTROL: variable comparisons with a letter straight after < stay legal")
    void variableComparisonsAreNotMarkup() {
        assertNull(LabelMarkup.offendingTag("0<x+y>1"), "Marlow's exact discriminator");
        assertNull(LabelMarkup.offendingTag("a<b+c>d"));
        assertNull(LabelMarkup.offendingTag("n<x*y>m"));
        assertNull(LabelMarkup.offendingTag("f<g=h>i"));
        // the name may run several chars and still fail at a non-boundary
        assertNull(LabelMarkup.offendingTag("lo<hi+1>mid"));
        // ...while a name that TERMINATES legally is still caught
        assertEquals("<br/>", LabelMarkup.offendingTag("0<br/>1"));
        assertEquals("<b>", LabelMarkup.offendingTag("0<b>1"));
        assertEquals("<span x=1>", LabelMarkup.offendingTag("0<span x=1>1"));
    }

    /// THE ELEMENT NAME IS A GRAMMAR, NOT A CHARACTER SET I HAPPENED TO LIST.
    ///
    /// The detector accepted `<b>`, `<h1>` and `<x-custom>` but let `<svg:rect/>` and `<_priv>`
    /// through as ordinary text, because `isNameChar` enumerated letters, digits and hyphen — the
    /// characters the tags I had in front of me used. That is the failure mode Marlow's
    /// falsification clause (e) names exactly: "the validator accepts a tag VARIANT it was not
    /// literally written against."
    ///
    /// A namespaced element is not an exotic variant. SVG — the renderer's own OUTPUT format —
    /// is XML, and `<svg:rect/>`, `<xhtml:br/>` are ordinary qualified names in it. The published
    /// grammar is XML NCName/QName, so the scanner now implements THAT rather than a longer list.
    @Test
    @DisplayName("tag VARIANTS: qualified, underscored and dotted element names are caught")
    void qualifiedAndUnderscoredTagNamesAreCaught() {
        // A QName: prefix, colon, local name. The renderer emits SVG, which is XML.
        assertEquals("<svg:rect/>", LabelMarkup.offendingTag("a<svg:rect/>b"));
        assertEquals("<xhtml:br/>", LabelMarkup.offendingTag("a<xhtml:br/>b"));
        assertEquals("<ns:tag attr=1>", LabelMarkup.offendingTag("a<ns:tag attr=1>b"));
        // `_` is an XML NCNameStartChar; `_` and `.` are NCNameChars.
        assertEquals("<_priv>", LabelMarkup.offendingTag("a<_priv>b"));
        assertEquals("<x_y>", LabelMarkup.offendingTag("a<x_y>b"));
        assertEquals("<v1.2>", LabelMarkup.offendingTag("a<v1.2>b"));
        assertEquals("<a.b:c_d>", LabelMarkup.offendingTag("a<a.b:c_d>b"));
        // closing form of the same shape
        assertEquals("</svg:rect>", LabelMarkup.offendingTag("a</svg:rect>b"));
    }

    /// POSITIVE CONTROL that keeps the widening above honest, and the reason the rule is QName
    /// rather than "a colon is a name character".
    ///
    /// Angle brackets around a URI are the ordinary plaintext convention (RFC 3986 §Appendix C),
    /// and `<http://example.com>` contains a colon directly after an ASCII name. Treating the
    /// colon as one more name character would refuse every bracketed URL — trading the
    /// under-detection this plan closes for the over-rejection Marlow's clause (b) forbids.
    ///
    /// The QName production rules them out STRUCTURALLY: what follows the colon must itself start
    /// an NCName, and `/`, a digit or a scheme-specific `//` does not.
    @Test
    @DisplayName("POSITIVE CONTROL: angle-bracketed URIs and ratios stay legal")
    void angleBracketedUrisAreNotMarkup() {
        assertNull(LabelMarkup.offendingTag("see <http://example.com> now"));
        assertNull(LabelMarkup.offendingTag("<https://x.example/p?q=1>"));
        assertNull(LabelMarkup.offendingTag("<file:///tmp/x>"));
        assertNull(LabelMarkup.offendingTag("mail <mailto:bob@x.com>"));
        assertNull(LabelMarkup.offendingTag("<tel:15551234>"));
        assertNull(LabelMarkup.offendingTag("<doi:10.1000/xyz>"));
        assertNull(LabelMarkup.offendingTag("ratio <3:1>"));
        // a bare colon with nothing name-shaped after it is not a QName either
        assertNull(LabelMarkup.offendingTag("<a:>"));
        assertNull(LabelMarkup.offendingTag("<a::b>"));
        // and the pre-existing controls must survive the widening unchanged
        assertNull(LabelMarkup.offendingTag("x < y"));
        assertNull(LabelMarkup.offendingTag("0<x+y>1"));
        assertNull(LabelMarkup.offendingTag("<u,v> inner product"));
        assertNull(LabelMarkup.offendingTag("<a|b> braket"));
    }

    @Test
    @DisplayName("a label with no bracket at all is untouched")
    void plainLabelsPass() {
        assertNull(LabelMarkup.offendingTag("TRUE NEGATIVE"));
        assertNull(LabelMarkup.offendingTag(""));
        assertNull(LabelMarkup.offendingTag(null));
    }
}
