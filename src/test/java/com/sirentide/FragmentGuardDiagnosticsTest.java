package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.FramesResult;
import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// Plan 43fd0785 acceptance 1: a fragment the guard refuses "degrades to literal `$x$` text WITH A
/// DIAGNOSTIC". Before this, a refusal was visible only as the raw `$x$` in the picture; the
/// diagnostics twin said "Rendered successfully." with an empty detail.
///
/// The caveat rides the same OK carrier as the font-coverage note (one per render, composed, never
/// shadowing another caveat) and names the reason CLASS and the refused fragment's LENGTH. It never
/// echoes the fragment: a {@link MathFragmentRenderer} is foreign code, so its output is treated as
/// attacker-controlled. Each test's one-line comment names the mutant that turns it red.
class FragmentGuardDiagnosticsTest {

    private static final String DSL = "flowchart TD\n  A[$x$]\n";

    private static final String CLEAN = "<g><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>";

    /// Distinctive markers inside each refused fragment, asserted ABSENT from the diagnostics.
    private static final String ALLOWLIST_FRAGMENT = "<circle cx=\"7\" cy=\"7\" r=\"3\"/>";
    private static final String STRUCTURE_FRAGMENT = "<g><path d=\"M4 4L5 5\" fill=\"none\"/>";
    private static final String PARSE_FRAGMENT = "<g><path d=\"M8 8\" d=\"M9 9\"/></g>";

    private static MathFragmentRenderer fixed(String innerSvg) {
        return (latex, size) -> Optional.of(new MathFragment(innerSvg, 40, 12, 4));
    }

    private static Diagnostics diagnose(MathFragmentRenderer math) {
        RenderResult r = Sirentide.renderWithDiagnostics(DSL, math);
        assertEquals(Outcome.OK, r.diagnostics().outcome(), "a refusal is a caveat on OK, not a failure");
        assertEquals(Sirentide.render(DSL, math), r.svg(), "identity control: diagnostics never alter the SVG");
        return r.diagnostics();
    }

    private static void assertRefusalNamed(Diagnostics d, String reason, int length, String fragment) {
        // The message carries the author-facing note; the detail carries the machine-readable list.
        assertTrue(d.message().contains("failed the fragment guard"), "message names the guard: " + d.message());
        assertTrue(d.message().contains("raw $...$ source text"), "message says what the author sees: " + d.message());
        assertTrue(d.message().contains(reason + " x1"), "message counts by reason: " + d.message());
        assertEquals("fragment guard refused: " + reason + " (" + length + " chars)", d.detail());
        // Never the fragment text (attacker-controlled): neither whole nor a distinctive piece.
        for (String s : new String[] {fragment, "circle", "M4 4", "M8 8", "M9 9", "d=", "<", ">"}) {
            assertFalse(d.message().contains(s), "message must not echo fragment text [" + s + "]: " + d.message());
            assertFalse(d.detail().contains(s), "detail must not echo fragment text [" + s + "]: " + d.detail());
        }
    }

    // ---- one test per guard layer ------------------------------------------------

    @Test
    void allowlistRefusal_isNamedWithoutEchoingTheFragment() {
        // Mutant: drop the Refusals.record call in MathLabel.renderGuarded -> no caveat, detail "".
        Diagnostics d = diagnose(fixed(ALLOWLIST_FRAGMENT));
        assertRefusalNamed(d, "allowlist", ALLOWLIST_FRAGMENT.length(), ALLOWLIST_FRAGMENT);
    }

    @Test
    void structureRefusal_isNamedWithoutEchoingTheFragment() {
        // Mutant: return ALLOWLIST instead of STRUCTURE for an unbalanced stack -> wrong reason.
        Diagnostics d = diagnose(fixed(STRUCTURE_FRAGMENT));
        assertRefusalNamed(d, "structure", STRUCTURE_FRAGMENT.length(), STRUCTURE_FRAGMENT);
    }

    @Test
    void wellFormednessRefusal_isNamedWithoutEchoingTheFragment() {
        // Mutant: drop the hardened parse from refusal() -> the fragment is ACCEPTED, no caveat.
        Diagnostics d = diagnose(fixed(PARSE_FRAGMENT));
        assertRefusalNamed(d, "well-formedness", PARSE_FRAGMENT.length(), PARSE_FRAGMENT);
    }

    @Test
    void metricsRefusal_isNamedWithTheFragmentLength() {
        // Mutant: drop the metricsClean arm in renderGuarded's reason mapping -> no caveat.
        Diagnostics d = diagnose((latex, size) -> Optional.of(new MathFragment(CLEAN, Double.NaN, 12, 4)));
        assertRefusalNamed(d, "metrics", CLEAN.length(), CLEAN);
    }

    // ---- controls -----------------------------------------------------------------

    @Test
    void acceptedFragment_carriesNoGuardCaveat() {
        // Mutant: record on ACCEPT as well (or compose the caveat unconditionally) -> fails.
        Diagnostics d = diagnose(fixed(CLEAN));
        assertEquals("Rendered successfully.", d.message());
        assertEquals("", d.detail());
    }

    @Test
    void twoRefusals_areCountedAndListedInOrder() {
        // Mutant: cap the sink at one entry, or lose the per-reason count -> fails.
        String dsl = "flowchart TD\n  A[$a$ and $b$]\n";
        int[] calls = {0};
        MathFragmentRenderer alternating = (latex, size) -> Optional.of(new MathFragment(
            calls[0]++ == 0 ? ALLOWLIST_FRAGMENT : PARSE_FRAGMENT, 40, 12, 4));
        Diagnostics d = Sirentide.renderWithDiagnostics(dsl, alternating).diagnostics();
        assertTrue(d.message().contains("2 math fragments failed the fragment guard"), d.message());
        assertTrue(d.message().contains("allowlist x1, well-formedness x1"), d.message());
        assertEquals("fragment guard refused: allowlist (" + ALLOWLIST_FRAGMENT.length()
            + " chars); well-formedness (" + PARSE_FRAGMENT.length() + " chars)", d.detail());
    }

    @Test
    void refusalCaveat_composesWithTheFontCoverageNote() {
        // Mutant: have withFragmentGuardCaveat REPLACE the detail instead of appending -> the
        // coverage code point vanishes (or vice versa).
        Diagnostics d = Sirentide.renderWithDiagnostics("flowchart LR\n  A[$🚀$]\n",
            fixed(ALLOWLIST_FRAGMENT)).diagnostics();
        assertTrue(d.detail().contains("fragment guard refused: allowlist"), d.detail());
        assertTrue(d.detail().contains("U+1F680"), "the tofu in the degraded raw text is still named: " + d.detail());
    }

    @Test
    void framesDiagnostics_carryTheGuardCaveatToo() {
        // Mutant: arm the sink only in renderWithDiagnostics -> the frames twin stays silent.
        FramesResult f = Sirentide.renderFramesWithDiagnostics(DSL, fixed(ALLOWLIST_FRAGMENT));
        assertEquals(Outcome.OK, f.diagnostics().outcome());
        assertTrue(f.diagnostics().detail().contains("fragment guard refused: allowlist"), f.diagnostics().detail());
    }

    // ---- scoping: the sink follows the glyph tap's frame rules (Marlow sirentide/733) -----

    @Test
    void plainRenderLeavesNoSinkBehind() {
        // Mutant: make record() allocate a sink when unarmed -> the refusal leaks into the NEXT
        // diagnostics render on this thread and the accepted fragment gains a caveat.
        Sirentide.render(DSL, fixed(ALLOWLIST_FRAGMENT));
        Diagnostics d = diagnose(fixed(CLEAN));
        assertEquals("", d.detail(), "a plain render's refusal must not leak: " + d.detail());
    }

    @Test
    void plainRenderInsideTheRendererCallbackDoesNotContaminateTheOuterDiagnostics() {
        // Mutant: drop Refusals.enterPlainRender/exitPlainRender from render() -> the nested plain
        // render's refusal is recorded into the OUTER armed frame and the outer gains a caveat.
        MathFragmentRenderer nesting = (latex, size) -> {
            Sirentide.render(DSL, fixed(ALLOWLIST_FRAGMENT));   // refused, but in a PLAIN render
            return Optional.of(new MathFragment(CLEAN, 40, 12, 4));
        };
        Diagnostics d = diagnose(nesting);
        assertEquals("Rendered successfully.", d.message(), d.detail());
        assertEquals("", d.detail());
    }
}
