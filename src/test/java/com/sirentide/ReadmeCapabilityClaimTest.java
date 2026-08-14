package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Flowchart;
import com.sirentide.parse.DslParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * THE README'S CAPABILITY CLAIMS, PINNED (crew RFC stafficy/16752 item 5, unanimous).
 *
 * <p>README.md advertised "the full mermaid node-shape set" while the flowchart carries only the
 * eight named below, and "plus activation bars" while the parser's own comment says "Bars
 * themselves are unsupported (dropped decoration)". Two false claims in one sentence, shipping.
 *
 * <p>This javadoc used to say "8 of mermaid's 14". That is the same defect one layer down —
 * followup F1 (sirentide/1009) removed exactly that denominator from DslParser's alias comment,
 * because the README correction deliberately claims no total: fourteen is a count of mermaid's
 * set that this parser cannot verify and that moves when mermaid moves. Naming the eight we
 * support is verifiable; naming a denominator is not.
 *
 * <p><strong>Correcting the prose was not the fix.</strong> Nothing tested it, which is why it
 * drifted — {@code ContractDocDriftTest} exists but never reads README. This file is the
 * tripwire: it asserts the SHAPES STILL BEHAVE AS DOCUMENTED, so adding a ninth shape or
 * implementing activation bars fails here and the failure is the reminder to update the README
 * in the same change. The repo already has this genre — a staged release note once claimed 25
 * diagram types where the sealed IR permits 23, and earned a test for it.
 */
class ReadmeCapabilityClaimTest {

    /// The shapes the README names. Each is asserted to actually RENDER below, so this list
    /// cannot drift into fiction the way the prose did.
    private static final String[][] DOCUMENTED_SHAPES = {
        {"rect", "A[Rect]"},
        {"rounded", "A(Rounded)"},
        {"stadium", "A([Stadium])"},
        {"subroutine", "A[[Subroutine]]"},
        {"cylinder", "A[(Cylinder)]"},
        {"circle", "A((Circle))"},
        {"diamond", "A{Diamond}"},
        {"hexagon", "A{{Hexagon}}"},
    };

    /// mermaid forms the README now explicitly says are NOT supported. If one of these starts
    /// rendering as its own shape, the README's exclusion list is stale and this fires.
    /// Each entry pairs the mermaid FORM with the IR shape token it WOULD carry if it ever gained
    /// real support. The token is used in the failure message and as the thing whose ABSENCE from
    /// FlowNode.shape() is the assertion — an observable that actually exists.
    ///
    /// It did not always. These were `shape=parallelogram` etc. and were compared against the a11y
    /// channel, where the substring `shape=` never appears for any input — so the whole control
    /// was unfalsifiable. That was the SECOND vacuous version of this list (the first looped the
    /// forms while asserting only two tokens), which is why the pairing is no longer the point:
    /// the point is that FlowNode.shape() is a value the renderer really produces and really
    /// changes when support lands.
    private static final String[][] UNSUPPORTED_FORMS = {
        {"A[/Parallelogram/]", "parallelogram"},
        {"A[\\Trapezoid/]", "trapezoid"},
        {"A>Asymmetric]", "asymmetric"},
        {"A(((Double Circle)))", "doublecircle"},
    };

    @Test
    void readmeNoLongerClaimsTheFullMermaidShapeSet() throws IOException {
        String readme = readme();
        assertFalse(readme.contains("full mermaid node-shape set"),
            "README claimed the full mermaid shape set while carrying only the eight named here");
        assertTrue(readme.contains("**eight**") || readme.contains("eight of mermaid"),
            "and it must state the real number rather than removing the claim silently");
    }

    @Test
    void readmeNoLongerClaimsActivationBars() throws IOException {
        String readme = readme();
        assertFalse(readme.contains("plus activation bars"),
            "the parser's own comment says bars are unsupported dropped decoration");
        assertTrue(readme.contains("consumed but not drawn"),
            "and the honest replacement must say what actually happens to the sigils");
    }

    @Test
    void everyDocumentedShapeActuallyRendersAsThatShape() {
        // Followup F2 (sirentide/1009). This asserted only that each shape's LABEL appeared in
        // the a11y channel — and every entry's label IS its own shape name (`rect` <- `A[Rect]`),
        // so `A[[Subroutine]]` silently degrading to a RECT labeled "Subroutine" passed. It read
        // as a shape control and was a label control.
        //
        // The fix proposed at 1009 was to key on the `shape=` tokens the negative list uses. I
        // MEASURED before building on that, and it was not available: `shape=` appears NOWHERE in
        // the rendered SVG, for any input, supported or not — the a11y channel carries labels
        // only. That measurement also condemned the negative list (below).
        //
        // The real observable is the parsed IR: FlowNode.shape() carries the token. Asserting it
        // closes exactly the hole named at 1009 — a subroutine degrading to a rect now FAILS,
        // because shape() reads "rect" where "subroutine" is documented.
        for (String[] shape : DOCUMENTED_SHAPES) {
            Flowchart fc = flowchartOf(shape[1]);
            assertEquals(1, fc.nodes().size(),
                "documented shape '" + shape[0] + "' must parse to exactly one node");
            assertEquals(shape[0], fc.nodes().get(0).shape(),
                "documented shape '" + shape[0] + "' must render AS that shape, not merely carry"
                    + " its label — from " + shape[1]);
        }
        assertEquals(8, DOCUMENTED_SHAPES.length,
            "the README says eight; if this list changes, the README changes with it");
    }

    @Test
    void theUnsupportedFormsHaveNotGainedTheirOwnShape() {
        // THIS TEST WAS VACUOUS, and it is the same defect it was written to catch.
        //
        // It asserted `assertFalse(a11y.contains("shape=parallelogram"))` for each form. Measured:
        // the string `shape=` occurs NOWHERE in the SVG for ANY input. So every one of those four
        // assertions was assertFalse(false) — unconditionally true, unfalsifiable, for every
        // input, forever. Had parallelogram gained full support the test would still have passed.
        //
        // Worse, that is the SECOND time this control has been vacuous in the same way. The prior
        // version "looped over the forms but asserted only two tokens", and the repair paired each
        // form with its token — which looks like the fix and is not, because the needle it pairs
        // is one the haystack never contains. A pairing does not make a loop load-bearing; a
        // reachable observable does. The mutation proof cited for the repair (simulating
        // double-circle support -> zero failures) applied unchanged to the repair itself, and
        // nobody re-ran it afterwards. [[reviewer-mutations-become-committed-fixtures]]
        //
        // The reachable observable is the IR. An unsupported form does not get its own shape; it
        // degrades to a `rect` still wearing its delimiters in the label — and if it ever gained
        // real support, shape() would change and this fires. Double-circle is a DIFFERENT arm and
        // is asserted separately rather than folded in: it does not degrade, it fails to parse.
        for (String[] pair : UNSUPPORTED_FORMS) {
            String form = pair[0];
            String wouldBeShape = pair[1];
            Flowchart fc = flowchartOf(form);
            if (fc.nodes().isEmpty()) {
                // The parse-failure arm (double-circle). Nothing minted is also "not supported",
                // but it is a different fact and must not silently satisfy the degrade assertion.
                continue;
            }
            assertEquals("rect", fc.nodes().get(0).shape(),
                "the unsupported form " + form + " gained its own shape (expected the "
                    + wouldBeShape + " degrade to a rect) without the README being updated");
            assertTrue(carriesRawDelimiters(form, fc.nodes().get(0).label()),
                "the unsupported form " + form + " stopped leaking its delimiters into the label"
                    + " (" + fc.nodes().get(0).label() + ") — it may have gained real support");
        }
    }

    @Test
    void theDoubleCircleFormDoesNotParseAtAll() {
        // The arm the loop above deliberately skips, asserted on its own so "0 nodes" can never
        // stand in for "degraded to a rect". Pinned separately because a single mixed assertion
        // over both behaviours is exactly how the vacuous version survived review.
        Flowchart fc = flowchartOf("A(((Double Circle)))");
        assertEquals(0, fc.nodes().size(),
            "double-circle mints no node today; if it starts minting one, the README's exclusion"
                + " list needs revisiting");
    }

    /// True when the node's label still contains the form's own delimiter characters — the tell
    /// that the parser fell back to a rect wearing the raw token rather than understanding it.
    private static boolean carriesRawDelimiters(String form, String label) {
        for (char c : new char[] {'/', '\\', '>', '[', ']'}) {
            if (form.indexOf(c) >= 0 && label.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String readme() throws IOException {
        Path p = Path.of("README.md");
        assertTrue(Files.exists(p), "README.md must be readable from the module root");
        return Files.readString(p);
    }

    /// Parses ONE flowchart body line to IR. The IR is used rather than the rendered SVG because
    /// the SVG carries no shape identity at all (measured: no `shape=` anywhere, and Sirentide
    /// bakes glyph OUTLINES so there is no `<text>` to read either) — the a11y channel can only
    /// tell you a label, which is precisely how both halves of this file went vacuous.
    private static Flowchart flowchartOf(String bodyLine) {
        Diagram d = DslParser.parse("flowchart TD\n    " + bodyLine + "\n");
        assertTrue(d instanceof Flowchart,
            "expected a Flowchart from '" + bodyLine + "', got " + d.getClass().getSimpleName());
        return (Flowchart) d;
    }
}
