package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// Braced LaTeX in flowchart labels (plan sirentide-flowchart-label-brace-math). Before this fix
/// DslParser.parseEndpoint treated ANY `{`/`}` inside a `[…]`/`{…}` node label as a nested DSL
/// delimiter and DROPPED the whole line, so the COMMON braced forms — `\frac{a}{b}`, `\sqrt{2}`,
/// `\textcolor{red}{x}`, a multi-token superscript `x^{2n}` — rendered an EMPTY diagram. The
/// math-span-aware brace handling lets `{`/`}` be LaTeX when they sit inside a well-formed `$…$`
/// span in the label, while keeping every non-math label's DSL structure unchanged.
///
/// The FAKE renderer (a fixed contract-clean fragment for any latex) proves the label REACHES a
/// math run: a `<g fill=… transform=translate(…)>` MathBox wrapper is emitted iff a {@link
/// com.sirentide.parse.LabelRuns.MathRun} was laid out — which only happens when the line was NOT
/// dropped. So "MathBox present" == "braced label survived the parser". (The REAL LatteX bake — a
/// genuine fraction bar `<rect>` + radical — is proven in {@link MathInLabelsRealRenderTest}.)
class BracedMathLabelTest {

    /// A deterministic fake fragment (width 40, ascent 12, descent 4) for any latex — proves WIRING
    /// (the label reached a math run), not the bake. Mirrors {@link MathInLabelsTest#FAKE}.
    private static final MathFragmentRenderer FAKE = (latex, size) ->
        Optional.of(new MathFragment(
            "<g><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>", 40, 12, 4));

    /// True iff `svg` carries a MathBox wrapper — the `<g fill=… transform=translate(…)>` a laid-out
    /// math run emits. Its presence means the braced label was NOT dropped and reached the renderer.
    private static boolean reachesMathRun(String svg) {
        return svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*");
    }

    /// True iff `svg` drew at least one node box — distinguishes a real diagram from the empty/inert
    /// shell a fully-dropped body degrades to.
    private static boolean hasNodeBox(String svg) {
        return svg.contains("<rect");
    }

    // -- 1. the braced common forms are NOT dropped + reach a math run --------------

    /// DELETE-MUTANT WITNESS. Revert the math-span guard in DslParser (so `{` again drops the line)
    /// and THIS test goes RED: the fraction label drops, the diagram has no node box and no MathBox.
    /// It is the named witness the plan asks for — braced `\frac{a}{b}` must survive the parser.
    @Test
    void bracedFractionLabelIsNotDropped_reachesMathRun() {
        String svg = Sirentide.render("flowchart TD\n  A[$\\frac{a}{b}$]\n", FAKE);
        assertTrue(hasNodeBox(svg), "the braced fraction node is NOT dropped (a node box exists): " + svg);
        assertTrue(reachesMathRun(svg), "the braced label reaches a math run (MathBox emitted): " + svg);
    }

    @Test
    void bracedSqrtLabel_reachesMathRun() {
        String svg = Sirentide.render("flowchart TD\n  A[$\\sqrt{2}$]\n", FAKE);
        assertTrue(hasNodeBox(svg), "\\sqrt{2} node not dropped: " + svg);
        assertTrue(reachesMathRun(svg), "\\sqrt{2} reaches a math run: " + svg);
    }

    @Test
    void bracedTextcolorLabel_reachesMathRun() {
        // Nested braces: \textcolor{red}{x} — two brace groups, both LaTeX inside the $…$ span.
        String svg = Sirentide.render("flowchart TD\n  A[$\\textcolor{red}{x}$]\n", FAKE);
        assertTrue(hasNodeBox(svg), "\\textcolor{red}{x} node not dropped: " + svg);
        assertTrue(reachesMathRun(svg), "\\textcolor{red}{x} reaches a math run: " + svg);
    }

    @Test
    void multiTokenSuperscriptLabel_reachesMathRun() {
        String svg = Sirentide.render("flowchart TD\n  A[$x^{2n}$]\n", FAKE);
        assertTrue(hasNodeBox(svg), "x^{2n} node not dropped: " + svg);
        assertTrue(reachesMathRun(svg), "x^{2n} reaches a math run: " + svg);
    }

    @Test
    void mixedProseAndBracedMathLabel_reachesMathRun() {
        // Text BEFORE and AFTER the braced span: only the $…$ interior braces are LaTeX.
        String svg = Sirentide.render("flowchart TD\n  A[Energy $\\frac{v^2}{r}$ total]\n", FAKE);
        assertTrue(hasNodeBox(svg), "mixed prose+braced-math node not dropped: " + svg);
        assertTrue(reachesMathRun(svg), "mixed label reaches a math run: " + svg);
    }

    @Test
    void bracedMathAcrossAnEdge_bothEndpointsSurvive() {
        // The primary shape of the bug: a full edge with braced math on BOTH sides.
        String svg = Sirentide.render("flowchart TD\n  A[$\\frac{a}{b}$] --> B[$\\sqrt{x+1}$]\n", FAKE);
        assertTrue(hasNodeBox(svg), "the edge renders node boxes (line not dropped): " + svg);
        assertTrue(reachesMathRun(svg), "the braced labels reach math runs: " + svg);
        // Two nodes -> at least two node boxes drawn (neither endpoint dropped).
        assertTrue(count(svg, "<rect") >= 2, "both endpoints drew a box: " + svg);
    }

    @Test
    void bracedMathInDiamondShape_reachesMathRun() {
        // The nested-shape case: a `{…}` DIAMOND whose label is braced math. The shape delimiter is
        // itself `{`/`}`, so the closer scan must skip the LaTeX braces inside the $…$ span and find
        // the OUTER `}` as the shape closer.
        String svg = Sirentide.render("flowchart TD\n  A{$\\frac{a}{b}$} --> B[ok]\n", FAKE);
        assertTrue(hasNodeBox(svg), "the braced diamond is not dropped: " + svg);
        assertTrue(reachesMathRun(svg), "the diamond's braced label reaches a math run: " + svg);
    }

    // -- 2. regression: non-math labels are UNCHANGED ------------------------------

    @Test
    void plainLabels_byteIdenticalWithAndWithoutRenderer() {
        // A brace-free, math-free flowchart is byte-identical whether or not a renderer is supplied —
        // the math-span seams are inert on a non-math label (GoldenSvgTest pins the exact bytes).
        String dsl = "flowchart TD\n  A[Start] --> B[End]\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl, FAKE),
            "a plain label is unaffected by the braced-math seams");
    }

    @Test
    void strayDollarInProse_isLiteral_notDroppedNotMath() {
        // An UNCLOSED `$` is NOT a math span (LabelRuns rule): the label stays literal prose, the line
        // is NOT dropped, and no MathBox is emitted (regression guard for the closer scan — a naive
        // toggle would have swallowed the `]` inside a phantom span and dropped this line).
        String svg = Sirentide.render("flowchart TD\n  A[Cost $5 and up]\n", FAKE);
        assertTrue(hasNodeBox(svg), "stray-dollar prose label still renders a node: " + svg);
        assertFalse(reachesMathRun(svg), "an unclosed $ is literal, not math (no MathBox): " + svg);
    }

    @Test
    void nonMathBraceOutsideSpan_stillDropsTheLine() {
        // A brace OUTSIDE any $…$ span is still a nested DSL delimiter -> the line drops (unchanged).
        // Here the second `[` is nested with no math anywhere, so the whole line is malformed.
        String svg = Sirentide.render("flowchart TD\n  A[lit {brace} here]\n", FAKE);
        assertFalse(hasNodeBox(svg), "a non-math braced label still drops (nested delimiter): " + svg);
    }

    // -- 3. malformed -> inert: never throws ---------------------------------------

    @Test
    void unbalancedBraceInsideMath_rendersWithoutThrowing() {
        // `$\frac{$` has a well-formed (balanced-$) span whose LaTeX is malformed. The PARSER accepts
        // it (braces are inside the span); the malformation is the renderer's to degrade — never a
        // parser throw. Proven with the FAKE (which always renders) AND the null renderer.
        assertDoesNotThrow(() -> Sirentide.render("flowchart TD\n  A[$\\frac{$]\n", FAKE));
        assertDoesNotThrow(() -> Sirentide.render("flowchart TD\n  A[$\\frac{$]\n", (MathFragmentRenderer) null));
    }

    @Test
    void unterminatedMathSpan_dropsTheLineWithoutThrowing() {
        // `A[$x` — an unterminated `[` whose only would-be closer is swallowed by an unclosed `$`.
        // The closer scan returns -1 (no `]` outside a span), the line drops, and nothing throws.
        assertDoesNotThrow(() -> {
            String svg = Sirentide.render("flowchart TD\n  A[$x\n", FAKE);
            assertFalse(hasNodeBox(svg), "the unterminated-delimiter line drops to an empty diagram: " + svg);
        });
    }

    private static int count(String s, String sub) {
        int c = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }
}
