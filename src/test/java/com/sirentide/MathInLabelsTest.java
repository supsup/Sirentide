package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.FragmentGuard;
import com.sirentide.parse.LabelRuns;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Math-in-labels S1 (RFC sirentide/39): the `$…$` splitter, the fragment guard, the composite
/// node-label wiring, and the load-bearing ZERO-BEHAVIOUR-CHANGE pin (no renderer → byte-identical
/// to the pre-feature output). A FAKE renderer stands in for LatteX (a later slice).
class MathInLabelsTest {

    /// A deterministic fake fragment: a fixed contract-clean `<g><path/></g>`, width 40, ascent 12,
    /// descent 4. Fails (empty) for the sentinel latex "FAIL" so the raw-text fallback is testable.
    private static final MathFragmentRenderer FAKE = (latex, size) ->
        "FAIL".equals(latex) ? Optional.empty()
            : Optional.of(new MathFragment(
                "<g><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>", 40, 12, 4));

    // -- splitter -----------------------------------------------------------------

    @Test
    void split_plainText_isOneTextRun() {
        List<LabelRuns.Run> runs = LabelRuns.split("Start");
        assertEquals(1, runs.size());
        assertEquals(new LabelRuns.Text("Start"), runs.get(0));
    }

    @Test
    void split_oneMathRun_withSurroundingText() {
        List<LabelRuns.Run> runs = LabelRuns.split("Energy $E=mc^2$ here");
        assertEquals(List.of(
            new LabelRuns.Text("Energy "),
            new LabelRuns.MathRun("E=mc^2"),
            new LabelRuns.Text(" here")), runs);
    }

    @Test
    void split_twoMathRuns() {
        List<LabelRuns.Run> runs = LabelRuns.split("$a$ and $b$");
        assertEquals(List.of(
            new LabelRuns.MathRun("a"),
            new LabelRuns.Text(" and "),
            new LabelRuns.MathRun("b")), runs);
    }

    @Test
    void split_escapedDollar_isLiteral() {
        List<LabelRuns.Run> runs = LabelRuns.split("cost \\$5 today");
        assertEquals(1, runs.size());
        assertEquals(new LabelRuns.Text("cost $5 today"), runs.get(0));
        assertFalse(LabelRuns.hasMath("cost \\$5 today"));
    }

    @Test
    void split_unclosedDollar_staysLiteral() {
        List<LabelRuns.Run> runs = LabelRuns.split("price is $5 and up");
        assertEquals(1, runs.size());
        assertEquals(new LabelRuns.Text("price is $5 and up"), runs.get(0));
        assertFalse(LabelRuns.hasMath("price is $5 and up"));
    }

    @Test
    void split_emptyDollars_stayLiteral() {
        List<LabelRuns.Run> runs = LabelRuns.split("a$$b");
        assertEquals(1, runs.size());
        assertEquals(new LabelRuns.Text("a$$b"), runs.get(0));
    }

    // -- fragment guard -----------------------------------------------------------

    @Test
    void guard_acceptsCleanFragment() {
        assertTrue(FragmentGuard.isClean(
            "<g transform=\"translate(1 2) scale(0.5 0.5)\"><path d=\"M0 0L1 1\" fill=\"#4e79a7\"/></g>"));
        assertTrue(FragmentGuard.isClean("<path d=\"M0 0L10 0\" fill=\"currentColor\"/>"));
    }

    @Test
    void guard_rejectsScript() {
        assertFalse(FragmentGuard.isClean("<g><script>alert(1)</script></g>"));
    }

    @Test
    void guard_rejectsUrlInFill() {
        assertFalse(FragmentGuard.isClean("<path d=\"M0 0\" fill=\"url(#x)\"/>"));
    }

    @Test
    void guard_rejectsRotateTransform() {
        // rotate() is not in the numeric translate/scale/matrix grammar.
        assertFalse(FragmentGuard.isClean("<g transform=\"rotate(45 5 5)\"><path d=\"M0 0\" fill=\"none\"/></g>"));
    }

    @Test
    void guard_rejectsExtraAttribute() {
        assertFalse(FragmentGuard.isClean("<path d=\"M0 0\" fill=\"none\" onclick=\"x()\"/>"));
        assertFalse(FragmentGuard.isClean("<path d=\"M0 0\" fill=\"none\" href=\"x\"/>"));
    }

    @Test
    void guard_rejectsForeignElement() {
        // rect is now ADMITTED (S2: fraction/radical bars) — use genuinely-foreign elements here.
        assertFalse(FragmentGuard.isClean("<circle cx=\"0\" cy=\"0\" r=\"1\"/>"));
        assertFalse(FragmentGuard.isClean("<g><foreignObject/></g>"));
    }

    @Test
    void guard_acceptsCleanRect() {
        // A fraction/radical bar: numeric geometry + optional legal fill (LatteX rule-emit).
        assertTrue(FragmentGuard.isClean(
            "<g transform=\"translate(0 0)\"><rect x=\"0\" y=\"-4.2\" width=\"8\" height=\"0.6\" fill=\"#ff0000\"/></g>"));
        assertTrue(FragmentGuard.isClean("<rect x=\"1\" y=\"2\" width=\"3\" height=\"4\"/>"));
    }

    @Test
    void guard_rejectsRectWithNonNumericOrForeignAttr() {
        // geometry must be a finite number (no url(), calc(), expressions)...
        assertFalse(FragmentGuard.isClean("<rect x=\"0\" y=\"0\" width=\"url(#x)\" height=\"1\"/>"));
        // ...and rect carries no rx/ry/stroke/on* — only x/y/width/height/fill.
        assertFalse(FragmentGuard.isClean("<rect x=\"0\" y=\"0\" width=\"1\" height=\"1\" onclick=\"x()\"/>"));
    }

    // -- Conf F3 pins (RFC sirentide/49): the belt-and-suspenders branches were vacuously
    //    covered — every hostile case also tripped the element allowlist, so the stray-bracket
    //    and null/blank checks could be deleted with the suite still green. Pin them directly so
    //    a future mutant can't no-op them (these guard MALFORMED input, not injection — a real
    //    <script> still hits the element allowlist).

    @Test
    void guard_rejectsStrayTailBracket() {
        // well-formed path with an extra '>' hanging off the end — must not slip through
        assertFalse(FragmentGuard.isClean("<path d=\"M0 0\" fill=\"none\"/>>"));
        // a lone '<' with no tag, and a bare '>' between otherwise-clean tags
        assertFalse(FragmentGuard.isClean("<path d=\"M0 0\" fill=\"none\"/> < <path d=\"M1 1\" fill=\"none\"/>"));
    }

    @Test
    void guard_rejectsNullAndBlank() {
        assertFalse(FragmentGuard.isClean(null));
        assertFalse(FragmentGuard.isClean(""));
        assertFalse(FragmentGuard.isClean("   "));
    }

    // -- zero-behaviour-change pin (the load-bearing one) -------------------------

    @Test
    void noRenderer_dollarsRenderAsPlainText_byteIdentical() {
        String dsl = "flowchart TD\n  A[Energy $E=mc^2$] --> B[Done]\n";
        String off = Sirentide.render(dsl);            // feature off (no renderer)
        String nul = Sirentide.render(dsl, null);      // explicit null renderer
        assertEquals(off, nul, "null renderer must equal the no-renderer overload");
        // No MATH `<g fill=… translate>` when the feature is off — $ is a literal glyph. (Semantic-
        // anchor `<g data-sirentide-*>` wrappers ARE present now and are identical in both renders,
        // already pinned byte-for-byte by the assertEquals above.)
        assertFalse(off.contains("<g fill="), "no MathBox <g> when the feature is off: " + off);
    }

    @Test
    void existingFlowchartUnaffectedByRendererPresence() {
        // A label with NO math is byte-identical whether or not a renderer is supplied.
        String dsl = "flowchart TD\n  A[Start] --> B[Process]\n  B --> C[End]\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl, FAKE));
    }

    // -- composite wiring (fake renderer) ----------------------------------------

    @Test
    void mathNode_emitsMathBox_afterPrecedingText() {
        String dsl = "flowchart TD\n  A[E $x$] --> B[plain]\n";
        String svg = Sirentide.render(dsl, FAKE);
        // The wrapper carries the label fill (F2) then the numeric translate.
        assertTrue(svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
            "a MathBox <g fill … translate> is emitted: " + svg);
        // The fragment's inner markup passes through verbatim.
        assertTrue(svg.contains("<path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>"),
            "fragment inner markup embedded verbatim: " + svg);
        // F2: the wrapper stamps a CONCRETE contrast colour (not currentColor), so the
        // currentColor fragment inherits the label colour instead of resolving to black.
        Matcher fill = Pattern.compile("<g fill=\"(#[0-9a-f]{6})\" transform=\"translate").matcher(svg);
        assertTrue(fill.find(), "MathBox wrapper carries a concrete contrast fill: " + svg);
    }

    @Test
    void mathNode_growsBoxVsTextOnly() {
        // The fake fragment (40px) is wider than the glyphs it replaces, so the node with math has a
        // wider box than the same node without it. Compare the first <rect> width in each.
        double wMath = firstRectWidth(Sirentide.render("flowchart TD\n  A[$x$]\n", FAKE));
        double wText = firstRectWidth(Sirentide.render("flowchart TD\n  A[x]\n", FAKE));
        assertTrue(wMath > wText, "math label (fragment 40px) grows the box: math=" + wMath + " text=" + wText);
    }

    @Test
    void mathBox_translatesToNodeBaseline() {
        // The MathBox y must equal the node label baseline (box centre + LABEL_SIZE*0.35), not 0.
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n", FAKE);
        Matcher m = Pattern.compile("<g fill=\"[^\"]+\" transform=\"translate\\(([-0-9.]+) ([-0-9.]+)\\)\"").matcher(svg);
        assertTrue(m.find(), "MathBox present: " + svg);
        double y = Double.parseDouble(m.group(2));
        assertTrue(y > 0, "MathBox sits on the (positive) baseline, not the origin: y=" + y);
    }

    // -- rejecting/failed fragment → raw-text fallback ----------------------------

    @Test
    void failedRender_fallsBackToRawText_noG() {
        // FAIL sentinel → renderer returns empty → the raw "$FAIL$" renders as ordinary glyphs.
        String svg = Sirentide.render("flowchart TD\n  A[$FAIL$]\n", FAKE);
        assertFalse(svg.contains("<g fill="), "no MathBox when the render fails: " + svg);
    }

    @Test
    void rejectedFragment_fallsBackToRawText_noG() {
        // A renderer that returns a CONTRACT-VIOLATING fragment must be treated as a failure.
        MathFragmentRenderer hostile = (latex, size) ->
            Optional.of(new MathFragment("<script>x</script>", 40, 12, 4));
        String svg = Sirentide.render("flowchart TD\n  A[$x$]\n", hostile);
        assertFalse(svg.contains("<g fill="), "guard rejects the hostile fragment → raw-text fallback: " + svg);
        assertFalse(svg.contains("<script"), "hostile markup never reaches output: " + svg);
    }

    @Test
    void throwingRenderer_doesNotDropTheBake() {
        // A renderer that throws must NOT collapse the whole bake to the inert shell — the fragment
        // degrades to raw text and the flowchart still renders.
        MathFragmentRenderer boom = (latex, size) -> { throw new RuntimeException("boom"); };
        String svg = Sirentide.render("flowchart TD\n  A[$x$] --> B[ok]\n", boom);
        assertTrue(svg.contains("<rect"), "the flowchart still renders (not the inert shell): " + svg);
        assertFalse(svg.contains("<g fill="), "the throwing fragment degraded to text: " + svg);
    }

    private static double firstRectWidth(String svg) {
        Matcher m = Pattern.compile("<rect[^>]*width=\"([0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "a node rect exists: " + svg);
        return Double.parseDouble(m.group(1));
    }
}
