package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * THE README'S CAPABILITY CLAIMS, PINNED (crew RFC stafficy/16752 item 5, unanimous).
 *
 * <p>README.md advertised "the full mermaid node-shape set" while the flowchart carries 8 of
 * mermaid's 14, and "plus activation bars" while the parser's own comment says "Bars themselves
 * are unsupported (dropped decoration)". Two false claims in one sentence, shipping.
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
    /// Each entry pairs the mermaid FORM with the shape token that would appear in the a11y
    /// channel if it ever gained real support. Paired deliberately: the previous version looped
    /// over the forms but asserted only two tokens, so iterating the asymmetric form asserted
    /// nothing about asymmetric - the loop LOOKED parameterized and was not. Proven by mutation:
    /// simulating double-circle gaining support produced ZERO failures under the old assertion.
    /// double-circle is here because the README names four and the tripwire pinned three
    /// (Fixpoint, sirentide/1006 followup 2).
    private static final String[][] UNSUPPORTED_FORMS = {
        {"A[/Parallelogram/]", "shape=parallelogram"},
        {"A[\\Trapezoid/]", "shape=trapezoid"},
        {"A>Asymmetric]", "shape=asymmetric"},
        {"A(((Double Circle)))", "shape=doublecircle"},
    };

    @Test
    void readmeNoLongerClaimsTheFullMermaidShapeSet() throws IOException {
        String readme = readme();
        assertFalse(readme.contains("full mermaid node-shape set"),
            "README claimed the full mermaid shape set while carrying 8 of 14");
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
    void everyDocumentedShapeActuallyRenders() {
        // The claim is only worth pinning if the eight are real. Each renders its label, which
        // is the observable the a11y channel can see (Sirentide bakes glyph OUTLINES, so
        // grepping for <text> would return an empty list for every input and prove nothing).
        for (String[] shape : DOCUMENTED_SHAPES) {
            String a11y = a11yOf("flowchart TD\n    " + shape[1] + "\n");
            assertTrue(a11y.toLowerCase(java.util.Locale.ROOT)
                    .contains(shape[0].toLowerCase(java.util.Locale.ROOT)),
                "documented shape '" + shape[0] + "' must render its label: " + a11y);
        }
        assertEquals(8, DOCUMENTED_SHAPES.length,
            "the README says eight; if this list changes, the README changes with it");
    }

    @Test
    void theUnsupportedFormsAreStillUnsupported() {
        // The exclusion half of the claim. These are NOT asserted to fail — several currently
        // render as a rect with the delimiters inside the label, which is its own known defect
        // (crew RFC part 1, sirentide/948). What is asserted is that none has quietly gained
        // real support, which would make the README's exclusion list a new false claim.
        for (String[] pair : UNSUPPORTED_FORMS) {
            String form = pair[0];
            String shapeToken = pair[1];
            String a11y = a11yOf("flowchart TD\n    " + form + "\n");
            assertFalse(a11y.contains(shapeToken),
                "the unsupported form " + form + " gained support (" + shapeToken
                    + ") without the README being updated: " + a11y);
        }
    }

    private static String readme() throws IOException {
        Path p = Path.of("README.md");
        assertTrue(Files.exists(p), "README.md must be readable from the module root");
        return Files.readString(p);
    }

    private static String a11yOf(String dsl) {
        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("<(?:title|desc)[^>]*>([^<]*)</(?:title|desc)>")
            .matcher(String.valueOf(r.svg()));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1)).append(' ');
        }
        return sb.toString();
    }
}
