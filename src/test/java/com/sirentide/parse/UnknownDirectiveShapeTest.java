package com.sirentide.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/**
 * The forward-compat SHAPE RULE (plan 66572bcd; ruling sirentide/923 option A, as amended by my
 * 943 and 944). A directive-SHAPED line whose keyword this parser has never met must DROP and be
 * NAMED, never mint a lone node wearing its own directive text as a name.
 *
 * <p><strong>The measurement channel is the a11y description, deliberately.</strong> Sirentide
 * bakes glyph OUTLINES, not {@code <text>} elements, so a probe that greps for {@code <text>}
 * returns an empty node list for EVERY input — including ones that certainly have nodes — and a
 * uniform negative across a set known to vary is a broken instrument reporting all-clear. Node
 * names live in {@code <title>}/{@code <desc>}. {@link #instrumentSeesNodesWhenNodesExist} is the
 * positive control that keeps every "no phantom node" assertion below honest.
 *
 * <p><strong>Why the rule is two conditions and not the ruling's three.</strong> The ruling's
 * first conjunct was "not a valid node under parseEndpoint". Measured: parseEndpoint returns a
 * VALID node for the condemned line, so an ALL-of gate containing that conjunct can never fire.
 * See {@link DslParser#isUnknownDirectiveShape} for the full reasoning; the tests here pin the
 * BEHAVIOUR that reasoning produces, so the rule cannot be quietly widened or narrowed later.
 */
class UnknownDirectiveShapeTest {

    // ---- the positive control for the instrument itself ------------------------------------

    @Test
    void instrumentSeesNodesWhenNodesExist() {
        // If this fails, every "no phantom node" assertion below is worthless: they would be
        // reading an empty string and calling it clean.
        String a11y = a11yOf("flowchart TD\n    A[One] --> B[Two]\n");
        assertTrue(a11y.contains("One") && a11y.contains("Two"),
            "the a11y channel must SHOW real nodes, else absence proves nothing: " + a11y);
    }

    // ---- the defect ------------------------------------------------------------------------

    @Test
    void anUnknownDirectiveWithACssPayloadDropsInsteadOfMintingAPhantomNode() {
        // Before this rule the a11y read "One, Two, quuxStyle zork fill:#f00" — the directive
        // minted as a third node, under outcome=OK, silently.
        String dsl = "flowchart TD\n    A[One] --> B[Two]\n    quuxStyle zork fill:#f00\n";
        String a11y = a11yOf(dsl);
        assertFalse(a11y.contains("quuxStyle"),
            "the directive text must never appear as a node name: " + a11y);
        assertTrue(a11y.contains("One") && a11y.contains("Two"),
            "and the REST of the diagram must still render — line-scoped, not the inert shell: "
                + a11y);
    }

    // ---- the seven refuting behaviours, pinned as CONTROLS (ruling's requirement) -----------
    // Each of these is a family the earlier whitespace-at-top-level design condemned wrongly.
    // They are the reason the rule is narrow, so they are pinned one family at a time rather
    // than asserted as a group: a single combined case would leave the others unproven.

    @Test
    void control1_aMultiWordBareLineIsANodeNotADirective() {
        // No colon anywhere → no key:value payload → admitted. This is the family the corpus
        // refutation turned on (FlowchartTest:203 pins the divergence from mermaid).
        assertRendersNode("flowchart TD\n    Two Words Bare\n", "Two Words Bare");
    }

    @Test
    void control2_aValidNodeWithATrailingColourIsNotCondemned() {
        // `A[Start] #22c55e` — the first token carries a `[` delimiter, so it is a delimited node
        // however the rest reads.
        assertRendersNode("flowchart TD\n    A[Start] #22c55e\n", "Start");
    }

    @Test
    void control3_theSupportedPerNodeColourFormSurvives() {
        // The ruling named "per-node colour forms" as a refuting family. The form this parser
        // actually supports is `classDef <name> …` + `class <id> <name>` — NOT mermaid's
        // `A[One]:::danger`, which drops on unmodified main today (verified against the base
        // before writing this: PARSE_ERROR, 0 nodes, and my own 650d6425 census names it). Pinning
        // the unsupported spelling would have made this control pass or fail for reasons that have
        // nothing to do with the shape rule.
        //
        // `class A danger` is the interesting case for THIS rule: it has the directive SHAPE — a
        // bare first word plus a rest — and is admitted twice over, by the allowlist in front and
        // by carrying no key:value payload. A control that is admitted for two independent reasons
        // is weaker than one, so control7 covers the payload-less path on its own.
        String a11y = a11yOf("flowchart TD\n"
            + "    classDef danger fill:#ff0000\n"
            + "    A[One] --> B[Two]\n"
            + "    class A danger\n");
        assertTrue(a11y.contains("One") && a11y.contains("Two"),
            "the supported per-node colour form must leave both nodes rendering: " + a11y);
        assertFalse(a11y.contains("danger"),
            "and neither directive line mints a node: " + a11y);
    }

    @Test
    void control4_delimitedMultiWordLabelsAreNodes() {
        // One case per delimiter shape: the span walk handles each separately, so a single case
        // would leave the others unproven.
        for (String node : new String[] {"A[Two Words]", "A{Two Words}", "A(Two Words)"}) {
            assertRendersNode("flowchart TD\n    " + node + "\n", "Two Words");
        }
    }

    // NOTE ON THE SHAPE OF CONTROLS 5 AND 6, because the first version of both was VACUOUS and a
    // surviving mutant is what said so. I originally wrote them with an arrow
    // (`A["Ratio 3:4"] --> B[End]`). The shape rule only runs on ARROWLESS lines, so those inputs
    // never reached the payload scan at all: disabling the scan's span-skip entirely left both
    // tests green. They asserted a true thing about a code path they did not exercise. Both now
    // use the arrowless, bare-first-word form that actually reaches it — and are exercised
    // through the parse-level predicate rather than the render, because a colon in a LABEL and a
    // colon in a PAYLOAD are only distinguishable at the point that scans for one.

    @Test
    void control5_aColonInsideAQuotedSpanIsContentNotAPayload() {
        // Found by probing rather than by inspection: before the quoted-span arm, this exact
        // input was CONDEMNED. The keyword check treated `"` as a delimiter and the payload scan
        // did not — an asymmetry invisible until something reached it.
        assertNotCondemned("foo", "\"a:b\"");
    }

    @Test
    void control6_aColonInsideABracketOrPipeSpanIsContentNotAPayload() {
        assertNotCondemned("foo", "A[key:value]");
        assertNotCondemned("foo", "|a:b|");
        assertNotCondemned("foo", "A{key:value}");
        assertNotCondemned("foo", "A(key:value)");
    }

    @Test
    void control6b_aTopLevelKeyValueOutsideEverySpanIsStillCaught() {
        // The other half of control 6, and the reason it is not vacuous: the span arms must skip
        // spans WITHOUT swallowing a real top-level payload sitting beside one.
        assertCondemned("quuxStyle", "zork fill:#f00");
        assertCondemned("quuxStyle", "A[label] fill:#f00");
    }

    @Test
    void control7_aBareSingleTokenStaysANodeIncludingBareStyle() {
        // No rest at all → not a directive shape. `style` with no rest is a legal node, and that
        // is a rule this codebase already decided; the shape rule must not quietly revoke it.
        assertRendersNode("flowchart TD\n    style\n", "style");
        assertRendersNode("flowchart TD\n    A\n", "A");
    }

    // ---- the allowlist keeps its own behaviour (ruling answer 1c) ---------------------------

    @Test
    void aKnownKeywordKeepsItsOwnTreatmentAndDoesNotFallToTheShapeRule() {
        // `classDef` is KNOWN: the parser consumes it as styling. It must not be reported as an
        // unknown directive, because "we do not know this keyword" would be a false statement
        // about a keyword we do know — two honest messages, never one message for both cases.
        String dsl = "flowchart TD\n    classDef danger fill:#ff0000\n    A[One] --> B[Two]\n";
        String a11y = a11yOf(dsl);
        assertTrue(a11y.contains("One") && a11y.contains("Two"),
            "a known styling directive leaves the diagram intact: " + a11y);
        assertFalse(a11y.contains("classDef"), "and never mints: " + a11y);
    }

    // ---- the residual, pinned HONESTLY rather than hidden (ruling's close) -------------------

    @Test
    void aPayloadLessDirectiveStillMintsAndThatIsTheStatedResidual() {
        // NOT a bug report against this rule — the deliberate cost of the narrowness the corpus
        // refutation forced, recorded as a test so the residual is visible rather than folklore.
        // `animate fast` has no key:value payload, so it is indistinguishable from a legal
        // multi-word bare node (control 1) by shape alone. Closing this is plan (B)'s job
        // (version-skew between the vendored jar and the body it renders); if (B) ever lands,
        // THIS TEST IS THE ONE THAT SHOULD FAIL, and its failure is the signal to delete it.
        String a11y = a11yOf("flowchart TD\n    A[One] --> B[Two]\n    animate fast\n");
        assertTrue(a11y.contains("animate fast"),
            "payload-less directive keywords still mint — the stated residual: " + a11y);
    }

    // ---- ordering vs the parse-stage sigil scan ---------------------------------------------

    @Test
    void aParseStageSigilStillWinsOverTheShapeRule() {
        // The ruling asked the shape check to be ORDERED after the firstUnsupportedSigil scan so
        // `A ~~~ B` degrades under its own name. On this channel that ordering is structural
        // rather than sequential: the sigil scan runs at PARSE and fails the diagram closed, so a
        // body carrying both never reaches the emit-stage drop at all. "It cannot happen" is
        // still a claim, so it is pinned here rather than asserted in prose.
        RenderResult r = Sirentide.renderWithDiagnostics(
            "flowchart TD\n    A[One] --> B[Two]\n    A ~~~ B\n    quuxStyle zork fill:#f00\n");
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome(),
            "the parse-stage sigil owns the verdict when both are present");
        assertTrue(r.diagnostics().detail().contains("~~~"),
            "and it is named as the tilde, not as a directive: " + r.diagnostics().detail());
    }

    // ---- helpers -----------------------------------------------------------------------------

    /// The parse-level predicate, reached by reflection because it is private and the render
    /// cannot distinguish "admitted by the shape rule" from "dropped for some other reason" —
    /// both look like an absent node. A control that cannot tell WHY it passed is not a control.
    private static boolean condemns(String keyword, String rest) {
        try {
            java.lang.reflect.Method m = DslParser.class
                .getDeclaredMethod("isUnknownDirectiveShape", String[].class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, (Object) new String[] {keyword, rest});
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the shape-rule predicate moved; this control is blind", e);
        }
    }

    private static void assertNotCondemned(String keyword, String rest) {
        assertFalse(condemns(keyword, rest),
            "must be admitted, not condemned: |" + keyword + "| + |" + rest + "|");
    }

    private static void assertCondemned(String keyword, String rest) {
        assertTrue(condemns(keyword, rest),
            "must be condemned: |" + keyword + "| + |" + rest + "|");
    }

    private static void assertRendersNode(String dsl, String expected) {
        String a11y = a11yOf(dsl);
        assertTrue(a11y.contains(expected),
            "must still render as a node containing '" + expected + "': " + a11y);
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
