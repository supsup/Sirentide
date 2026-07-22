package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Semantic STATUS roles for flowchart nodes (plan fa3ccf16 wish A). A security diagram says "this
/// node is a danger sink / an admitted service / a neutral gate" by assigning ONE of the four
/// built-in `status-*` classes with the EXISTING `class <id> <name>` shape — no hand-picked hex.
///
/// Three channels, so status is NEVER color-only (the dataviz status rule):
///   (1) a CLOSED pastel FILL from the house verdict palette (danger/warn/ok/neutral),
///   (2) a SECONDARY, non-color BORDER-STROKE-WIDTH severity ramp (danger thickest → neutral
///       thinnest) drawn on the box's existing rect/path border — survives grayscale / CVD, and
///   (3) the a11y `<desc>` SPEAKS the status word ("Host root (danger)").
///
/// SCOPE (mirror evidence): the parser's `class <id> <name>` assignment is id-based, a perfect mirror
/// for node status via built-in classes. The only per-EDGE styling directive (`linkStyle <index> …`)
/// is INDEX-based, not the `statusEdge <from> <to>` from/to shape the plan sketched — so a from/to
/// edge-status directive would be an INVENTED grammar. Per the design pin ("a clean smaller slice
/// beats an invented grammar"), this slice ships NODE statuses only.
class StatusRolesTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) {
            return "";
        }
        int j = s.indexOf(b, i + a.length());
        return j < 0 ? "" : s.substring(i + a.length(), j);
    }

    // -- PARSE: the four built-in status classes resolve via the existing assignment shape ----------

    @Test
    void allFourBuiltinStatusClassesFillAndBorderTheirNodes() {
        // Each `class <id> status-<role>` picks up its CLOSED palette fill + same-family border at the
        // role's severity width — no classDef needed. Mutant (drop the STATUS_CLASSES seed) → the boxes
        // fall back to the default fill with no border: RED.
        String svg = Sirentide.render(
            "flowchart TD\n  D[d] --> W[w]\n  W --> O[o]\n  O --> N[n]\n"
                + "  class D status-danger\n  class W status-warn\n"
                + "  class O status-ok\n  class N status-neutral\n");
        assertTrue(svg.contains("fill=\"#fecaca\" stroke=\"#dc2626\" stroke-width=\"3\""),
            "danger node: pastel-red fill + red border at the thickest (severity) width: " + svg);
        assertTrue(svg.contains("fill=\"#fef9c3\" stroke=\"#ca8a04\" stroke-width=\"2\""),
            "warn node: amber fill + amber border at medium width: " + svg);
        assertTrue(svg.contains("fill=\"#dcfce7\" stroke=\"#16a34a\" stroke-width=\"1.25\""),
            "ok node: green fill + green border at a thin width: " + svg);
        assertTrue(svg.contains("fill=\"#f1f5f9\" stroke=\"#64748b\" stroke-width=\"0.75\""),
            "neutral node: slate fill + slate border at the thinnest width: " + svg);
    }

    @Test
    void statusBorderWidthEncodesTheSeverityRampAsANonColorChannel() {
        // The SECONDARY, non-color channel: border stroke-width is a strict severity ramp
        // danger(3) > warn(2) > ok(1.25) > neutral(0.75). Distinguishable in grayscale when the pastel
        // fills flatten. Mutant (collapse the widths to one value) → the ramp vanishes: RED.
        String svg = Sirentide.render(
            "flowchart TD\n  D[d] --> W[w]\n  W --> O[o]\n  O --> N[n]\n"
                + "  class D status-danger\n  class W status-warn\n"
                + "  class O status-ok\n  class N status-neutral\n");
        assertEquals(1, count(svg, "stroke-width=\"3\""), "exactly one thickest (danger) border");
        assertEquals(1, count(svg, "stroke-width=\"2\""), "exactly one medium (warn) border");
        assertEquals(1, count(svg, "stroke-width=\"1.25\""), "exactly one thin (ok) border");
        assertEquals(1, count(svg, "stroke-width=\"0.75\""), "exactly one thinnest (neutral) border");
    }

    // -- REVERT-PROOF: the exact fill mapping (delete/replace-mutant anchor) -------------------------

    @Test
    void statusFillMappingIsExactlyTheHouseVerdictPalette() {
        // NAMED exact-site mutant-catcher for the closed fill vocabulary. A mutation to ANY one of the
        // four status fills (or a swap between two roles) flips exactly one of these asserts: RED.
        assertEquals("#fecaca", statusFill("status-danger"), "danger fill is the house FAIL red");
        assertEquals("#fef9c3", statusFill("status-warn"), "warn fill is the house PARTIAL amber");
        assertEquals("#dcfce7", statusFill("status-ok"), "ok fill is the house PASS green");
        assertEquals("#f1f5f9", statusFill("status-neutral"), "neutral fill is the house NA slate");
    }

    /// Render a single node under `cls` and read back the fill its box was drawn with.
    private static String statusFill(String cls) {
        String svg = Sirentide.render("flowchart TD\n  X[x]\n  class X " + cls + "\n");
        // `<rect x=… width=… height=… fill="#rrggbb" stroke=…` — pull the fill on the node rect.
        String marker = "height=\"";
        int h = svg.indexOf(marker);
        String fillTag = "fill=\"";
        int f = svg.indexOf(fillTag, h);
        return svg.substring(f + fillTag.length(), f + fillTag.length() + 7);
    }

    // -- PARSE: an unknown status-* name falls to existing unknown-class behavior --------------------

    @Test
    void unknownStatusNameFallsToTheExistingUnknownClassBehavior() {
        // `status-bogus` is not a built-in and has no classDef → the SAME no-op an unknown class name
        // always was: the node keeps the DEFAULT box (no status fill, no border, no spoken word).
        String svg = Sirentide.render("flowchart TD\n  X[x]\n  class X status-bogus\n");
        assertFalse(svg.contains("#fecaca") || svg.contains("#fef9c3")
                || svg.contains("#dcfce7") || svg.contains("#f1f5f9"),
            "an unknown status-* name applies NO status fill: " + svg);
        assertFalse(between(svg, "<desc>", "</desc>").contains("("),
            "an unknown status-* name speaks NO status word: " + svg);
    }

    // -- A11Y: the desc speaks the status word (the non-color a11y channel) --------------------------

    @Test
    void descSpeaksEachStatusWord() {
        String svg = Sirentide.render(
            "flowchart TD\n  D[Danger sink] --> O[Admitted]\n  O --> W[Warned]\n  W --> N[Gate]\n"
                + "  class D status-danger\n  class O status-ok\n"
                + "  class W status-warn\n  class N status-neutral\n");
        String desc = between(svg, "<desc>", "</desc>");
        assertTrue(desc.contains("Danger sink (danger)"), "danger node speaks (danger): " + desc);
        assertTrue(desc.contains("Admitted (ok)"), "ok node speaks (ok): " + desc);
        assertTrue(desc.contains("Warned (warn)"), "warn node speaks (warn): " + desc);
        assertTrue(desc.contains("Gate (neutral)"), "neutral node speaks (neutral): " + desc);
    }

    @Test
    void anUnstyledFlowchartDescCarriesNoStatusWords() {
        // Additive guarantee: with no status assignment the desc is exactly as before (no `(word)`).
        String svg = Sirentide.render("flowchart TD\n  A[Start] --> B[End]\n");
        assertFalse(between(svg, "<desc>", "</desc>").contains("("),
            "a status-free flowchart speaks no status parenthetical: " + svg);
    }

    // -- CONTRAST + OVERRIDE: a dark fill flips the label; the spoken word survives a fill override ---

    @Test
    void lightStatusFillsGetBlackLabelsAndADarkOverrideFlipsToWhiteWhileStillSpeakingTheWord() {
        // The four built-in fills are light pastels → auto-contrast picks a BLACK label.
        String light = Sirentide.render("flowchart TD\n  X[x]\n  class X status-danger\n");
        assertTrue(light.contains("fill=\"#000000\""),
            "a light status fill gets a black (contrast) label: " + light);

        // An author may OVERRIDE a built-in status class's fill with a dark hex. The label auto-flips
        // to WHITE (contrast), AND the spoken word stays bound to the class NAME (still "danger").
        String dark = Sirentide.render(
            "flowchart TD\n  X[x]\n  classDef status-danger fill:#450a0a\n  class X status-danger\n");
        assertTrue(dark.contains("fill=\"#450a0a\""), "the override fill is applied: " + dark);
        assertTrue(dark.contains("fill=\"#ffffff\""),
            "a dark status fill flips the label to white: " + dark);
        assertTrue(between(dark, "<desc>", "</desc>").contains("(danger)"),
            "the spoken status word survives a fill override (bound to the class name): " + dark);
    }

    // -- END-TO-END: the plan's origin-shaped security diagram (a HOST-ROOT danger node) -------------

    @Test
    void originSecurityDiagramMarksTheHostRootAsDangerAcrossAllThreeChannels() {
        String dsl = securityDiagramDsl();
        String svg = Sirentide.render(dsl);
        // (1) fill + (2) severity border on the danger sink, and (3) the spoken word — all present.
        assertTrue(svg.contains("fill=\"#fecaca\" stroke=\"#dc2626\" stroke-width=\"3\""),
            "the Host root box carries the danger fill + thickest border: " + svg);
        assertTrue(between(svg, "<desc>", "</desc>").contains("Host root (danger)"),
            "the desc names the Host root as a danger node: " + svg);
        // The admitted service is ok, the refused-to sink is danger — both roles present.
        assertTrue(svg.contains("fill=\"#dcfce7\""), "the admitted service is an ok node: " + svg);
    }

    /// The plan's origin case: untrusted input, a firewall gate, an admitted app path, and a refused
    /// hop to Host root (the danger sink) — also the shape rendered to the demo SVG.
    private static String securityDiagramDsl() {
        return "flowchart TD\n"
            + "NET[Untrusted network] --> FW{Firewall rule}\n"
            + "FW -->|admitted| APP[App service]\n"
            + "FW -->|refused| HOSTROOT[Host root]\n"
            + "APP --> DATA[(User records)]\n"
            + "class NET status-warn\n"
            + "class FW status-neutral\n"
            + "class APP status-ok\n"
            + "class HOSTROOT status-danger\n"
            + "class DATA status-ok\n";
    }
}
