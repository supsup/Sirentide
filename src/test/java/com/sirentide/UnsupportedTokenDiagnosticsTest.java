package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Empty;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;

/// Receipts for the unsupported-Mermaid-token detector (plan 933eed50 F2). Each BrewShot-verified
/// repro — a token the DSL does not support at a statement-level flowchart position — must degrade
/// the WHOLE diagram to the inert shell (never a misleading partial, never a literal WRONG node) AND
/// surface an {@link Outcome#UNSUPPORTED_CONSTRUCT} diagnostic that NAMES the offending token. The
/// FALSE-POSITIVE negatives pin the moat: the same sigil INSIDE a label span is legal content and
/// still bakes real output. The theme: silent-WRONG output becomes a LOUD signal.
class UnsupportedTokenDiagnosticsTest {

    /// A degraded flowchart source: parses to {@link Empty}, its `render` SVG is the inert shell (no
    /// glyph paths), `renderWithDiagnostics` classifies UNSUPPORTED_CONSTRUCT with the byte-identical
    /// SVG, and both the message and detail name the token.
    private static void assertDegradesNaming(String dsl, String token, int line) {
        assertTrue(DslParser.parse(dsl) instanceof Empty,
            "parse degrades to the inert Empty for token " + token);
        String rendered = Sirentide.render(dsl);
        assertFalse(rendered.contains("<path"), "the inert shell carries no glyph paths: " + rendered);

        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome(),
            "token " + token + " → UNSUPPORTED_CONSTRUCT");
        assertEquals("parse", r.diagnostics().stage());
        assertEquals(rendered, r.svg(), "diagnostic SVG is byte-identical to render (inert shell)");
        assertTrue(r.diagnostics().detail().contains(token),
            "detail names the token '" + token + "': " + r.diagnostics().detail());
        assertTrue(r.diagnostics().message().contains(token),
            "the author-facing message names the token '" + token + "': " + r.diagnostics().message());
        assertEquals(line, r.diagnostics().line(), "the 1-based line of the offending token");
    }

    /// A LEGAL flowchart source: real content bakes (glyph paths present) and diagnostics is OK — the
    /// detector did NOT false-positive on a sigil that lives inside a label span.
    private static void assertRendersReal(String dsl) {
        String rendered = Sirentide.render(dsl);
        assertTrue(rendered.contains("<path"), "legal content bakes real glyph paths: " + rendered);
        assertNotEquals(Outcome.UNSUPPORTED_CONSTRUCT,
            Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome(),
            "a sigil inside a label span must not trip the detector");
    }

    // -- the repros: one per unsupported token ------------------------------------------------------

    @Test
    void ampersandFanOutDegradesAndIsNamed() {
        // `A & B --> C` used to mint ONE literal node named "A & B" (the silent-WRONG merge).
        assertDegradesNaming("flowchart TD\nA & B --> C\n", "&", 2);
    }

    @Test
    void tildeInvisibleLinkDegradesAndIsNamed() {
        assertDegradesNaming("flowchart TD\nA ~~~ B\n", "~~~", 2);
    }

    @Test
    void brTagInLabelDegradesAndIsNamed() {
        // A `<br/>` in a label baked as literal glyphs rather than a line break.
        assertDegradesNaming("flowchart TD\nA[Line1<br/>Line2] --> B[End]\n", "<br/>", 2);
        // The `<br>` and `<br />` spellings trip too.
        assertTrue(DslParser.parse("flowchart TD\nA[a<br>b] --> B\n") instanceof Empty);
        assertTrue(DslParser.parse("flowchart TD\nA[a<br />b] --> B\n") instanceof Empty);
    }

    @Test
    void styleDirectiveDegradesAndIsNamed() {
        assertDegradesNaming("flowchart TD\nA[x] --> B[y]\nstyle A fill:#f9f,stroke:#333\n", "style", 3);
    }

    @Test
    void clickDirectiveDegradesAndIsNamed() {
        assertDegradesNaming("flowchart TD\nA[x] --> B[y]\nclick A callback \"tooltip\"\n", "click", 3);
    }

    // -- classDef / class ARE supported (verify they are NOT flagged) -------------------------------

    @Test
    void classDefAndClassAreSupportedNotUnsupported() {
        // classDef/class are FIRST-CLASS SirentideDSL features (semantic colour classes) — the
        // detector must NOT treat them as unsupported tokens (plan 933eed50 F2 verification).
        String dsl = "flowchart LR\nclassDef ok fill:#bbf7d0\nA[x] --> B[y]\nclass A ok\n";
        assertRendersReal(dsl);
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome(),
            "a classDef/class diagram renders OK");
    }

    // -- the false-positive moat: a sigil INSIDE a label span is legal content ----------------------

    @Test
    void ampersandInsideLabelRendersFine() {
        assertRendersReal("flowchart TD\nA[Tom & Jerry] --> B[End]\n");
    }

    @Test
    void tildeInsideLabelRendersFine() {
        assertRendersReal("flowchart TD\nA[wave ~~~ pattern] --> B[End]\n");
    }

    @Test
    void ampersandInsideEdgeLabelPipeRendersFine() {
        assertRendersReal("flowchart TD\nA -->|yes & no| B\n");
    }

    @Test
    void lessThanAndBraveWordInLabelDoNotTripBrDetector() {
        // A bare `<` (not `<br…>`) and a `<brave>`-style word (no `/`/`>` right after `br`) are legal
        // label content — only the precise `<br>`/`<br/>`/`<br />` tag trips.
        assertRendersReal("flowchart TD\nA[a < b] --> B[End]\n");
        assertRendersReal("flowchart TD\nA[<brave> heart] --> B[End]\n");
    }
}
