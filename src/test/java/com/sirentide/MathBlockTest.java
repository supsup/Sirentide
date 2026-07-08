package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.a11y.A11y;
import com.sirentide.a11y.A11yDescriber;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.MathBlock;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;

/// The standalone display-math `mathblock` type (plan sirentide-mathblock), at the PARSE + degrade +
/// a11y layer (no math renderer). The whole body is ONE raw LaTeX expression — NOT `$…$`-split — so
/// these pin: the body joins into `MathBlock.latex`, an empty body is inert, and a null/failed
/// renderer degrades the raw source to plain-text glyphs WITHOUT throwing. The REAL typeset bake is
/// proven in {@link MathBlockRealRenderTest}.
class MathBlockTest {

    // -- parse: the body is the raw expression -----------------------------------

    @Test
    void oneLineBodyBecomesTheLatex() {
        MathBlock mb = assertInstanceOf(MathBlock.class,
            DslParser.parse("mathblock\n  \\frac{a}{b}\n"));
        assertEquals("\\frac{a}{b}", mb.latex(), "the single body line IS the raw latex");
    }

    @Test
    void multiLineBodyIsJoinedWithSpaces() {
        // A multi-line expression joins with single spaces (LaTeX treats a newline as whitespace),
        // blank lines dropped — the whole body is one expression.
        MathBlock mb = assertInstanceOf(MathBlock.class,
            DslParser.parse("mathblock\n  \\sum_{i=1}^{n} i\n\n  = \\frac{n(n+1)}{2}\n"));
        assertEquals("\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", mb.latex(),
            "multi-line body joins with single spaces");
    }

    @Test
    void bodyIsNotDollarSplit() {
        // A mathblock body is ALL math — no `$` delimiters. A stray `$` is just a literal char of the
        // expression, never a run delimiter (contrast the inline-label path).
        MathBlock mb = assertInstanceOf(MathBlock.class,
            DslParser.parse("mathblock\n  a $ b\n"));
        assertEquals("a $ b", mb.latex(), "the raw body is kept verbatim, not $-split");
    }

    @Test
    void emptyBodyIsAnEmptyMathBlock() {
        MathBlock mb = assertInstanceOf(MathBlock.class, DslParser.parse("mathblock\n"));
        assertTrue(mb.latex().isEmpty(), "a bodyless mathblock has empty latex");
    }

    // -- degrade (null renderer): raw source as text, never a throw ---------------

    @Test
    void nullRendererDegradesToTextGlyphsNotInert() {
        // No renderer injected: the raw LaTeX source bakes as plain-text glyph paths (loud, not
        // silent), a real sized canvas — NOT the inert shell.
        String svg = Sirentide.render("mathblock\n  \\frac{a}{b}\n");   // one-arg = null renderer
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "not the inert shell: " + svg);
        assertTrue(svg.contains("<path"), "the source baked to glyph paths: " + svg);
        // No math fragment was baked (no renderer) -> no MathBox wrapper.
        assertFalse(svg.contains("transform=\"translate("),
            "the null-renderer degrade emits NO MathBox: " + svg);
    }

    @Test
    void emptyBodyDegradesToInertWithoutThrowing() {
        String svg = Sirentide.render("mathblock\n");   // must not throw
        assertTrue(svg.contains("width=\"0\" height=\"0\""), "empty body -> inert empty canvas: " + svg);
    }

    @Test
    void malformedLatexDegradesWithoutThrowing() {
        // With a renderer that ALWAYS fails (empty), even a well-formed request degrades to text; and
        // with the null renderer a malformed expression is just text too. Neither throws.
        String svg1 = Sirentide.render("mathblock\n  \\frac{a\n", (latex, size) -> java.util.Optional.empty());
        String svg2 = Sirentide.render("mathblock\n  \\undefinedcmd{x\n");
        for (String svg : new String[] {svg1, svg2}) {
            assertFalse(svg.contains("width=\"0\" height=\"0\""),
                "malformed math still bakes its source as text, not inert: " + svg);
            assertTrue(svg.contains("<path"), "the source baked to glyph paths: " + svg);
        }
    }

    // -- a11y: a non-empty, non-leaking description -------------------------------

    @Test
    void a11yDescIsNonEmptyAndDoesNotLeakLatex() {
        A11y a = A11yDescriber.describe(new MathBlock("\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}"));
        assertFalse(a.desc().isBlank(), "the mathblock desc is non-empty");
        assertEquals("Math block", a.title());
        // The moat: no LaTeX source leaks into the a11y text (mirrors how `$…$` is stripped elsewhere).
        assertFalse(a.desc().contains("\\sum"), "no raw LaTeX leaks into the desc: " + a.desc());
        assertFalse(a.desc().contains("\\frac"), "no raw LaTeX leaks into the desc: " + a.desc());
    }

    @Test
    void a11yDescRidesTheBakedSvg() {
        String svg = Sirentide.render("mathblock\n  \\frac{a}{b}\n");
        assertTrue(svg.contains("<desc>") && svg.contains("</desc>"), "a <desc> is baked: " + svg);
        assertTrue(svg.contains("role=\"img\""), "role=img is baked: " + svg);
    }
}
