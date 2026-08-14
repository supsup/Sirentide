package com.sirentide.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/**
 * `%%` COMMENTS IN THE DIAGRAM BODY (plan 66572bcd, the percent-percent case Fixpoint asked me to
 * fold in as a named member of the silent-mint family).
 *
 * <p>Mermaid's comment syntax is `%% text`. Sirentide honors `%%` in the PREAMBLE as its config
 * channel, and left a body `%%` to the type parser — so the most-copied line in any mermaid
 * snippet became a DRAWN NODE wearing the comment as its name, at {@code outcome=OK}.
 *
 * <p>MEASURED BEFORE THE FIX, so the blast radius is a fact rather than an assumption: flowchart,
 * stateDiagram-v2 and mindmap all leaked the comment into the render; sequence and pie already
 * dropped it. That is why the fix is at the shared body seam and not in one type parser.
 */
class BodyCommentTest {

    @Test
    void instrumentSeesNodeNamesWhenNodesExist() {
        // The positive control. Every "the comment did not leak" assertion below reads the a11y
        // channel; if that channel were empty for all inputs, absence would prove nothing.
        assertTrue(a11yOf("flowchart TD\n    A[One] --> B[Two]\n").contains("One"),
            "the a11y channel must SHOW real node names, else absence is meaningless");
    }

    @Test
    void aBodyCommentDoesNotMintANodeInAnyTypeThatLeakedIt() {
        // One case per LEAKING type, measured. Sequence and pie are covered by the control below
        // rather than here — they never leaked, so pinning them here would prove nothing about
        // this change.
        String[][] cases = {
            {"flowchart TD\n    %% a comment\n    A[One] --> B[Two]\n", "One"},
            {"stateDiagram-v2\n    %% a comment\n    [*] --> Idle\n", "Idle"},
            {"mindmap\n  root\n    %% a comment\n    child\n", "child"},
        };
        for (String[] c : cases) {
            String a11y = a11yOf(c[0]);
            assertFalse(a11y.contains("a comment"),
                "the comment text must never appear as content: " + a11y);
            assertTrue(a11y.contains(c[1]),
                "and the REST of the diagram must still render: " + a11y);
        }
    }

    @Test
    void aCommentIsIntentionalSyntaxSoItCarriesNoDroppedStatementCaveat() {
        // THE INTERACTION THAT MATTERS, and the reason the fix sits at the shared seam rather
        // than inside parseFlowchart. The dropped-statement caveat reads the census; if the
        // census saw the comment while the parse did not, every commented diagram would report a
        // phantom "1 statement was dropped". A comment is not a lost statement.
        RenderResult r = Sirentide.renderWithDiagnostics(
            "flowchart TD\n    %% a comment\n    A[One] --> B[Two]\n");
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertFalse(r.diagnostics().message().contains("dropped"),
            "a comment must not read as a loss: " + r.diagnostics().message());
        assertFalse(r.diagnostics().detail().contains("a comment"),
            "and must not appear in the dropped list: " + r.diagnostics().detail());
    }

    @Test
    void lineNumbersAreUnshiftedBecauseCommentsAreBlankedNotRemoved() {
        // Blanking rather than deleting is load-bearing: diagnostics report PHYSICAL 1-based
        // lines. With a comment on line 2, the bad statement on line 3 must still be reported as
        // line 3. Deleting the comment line would have reported it as line 2 — a diagnostic that
        // points at the wrong line is worse than one that points nowhere.
        RenderResult r = Sirentide.renderWithDiagnostics(
            "flowchart TD\n    %% a comment\n    A ~~~ B\n");
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome());
        assertEquals(3, r.diagnostics().line(),
            "the tilde is on physical line 3 and must be reported as line 3");
    }

    @Test
    void theConfigPreambleStillWorksAndIsNotConfusedWithABodyComment() {
        // `%%` means two different things either side of the type header, and this fix must not
        // blur them: config in the preamble, comment in the body. If the preamble were also
        // blanked, every configured diagram would silently lose its settings.
        String a11y = a11yOf("%% title: My Title\nflowchart TD\n    A[One] --> B[Two]\n");
        assertTrue(a11y.contains("My Title"),
            "a preamble directive is still CONFIG, not a comment: " + a11y);
    }

    @Test
    void aCommentOnlyBodyReadsAsLEGITIMATELYEmptyNotAsAnEmptiedOne() {
        // THE DEGENERATE CASE, and a JUDGEMENT CALL I want reviewed rather than assumed.
        //
        // I first wrote this expecting PARSE_ERROR — "the author wrote something and got a blank
        // picture". It returns OK, and on reflection OK is the defensible answer: once comments
        // are not statements, a comment-only body DECLARES NOTHING, which is exactly the
        // "legitimately empty" case the empty-graph honesty work (650d6425) deliberately allows
        // to keep reporting success. Its census sees zero statements, so its control fires and
        // OK stands. Calling this PARSE_ERROR would mean saying "your statements failed" about
        // an author who wrote no statements.
        //
        // The counter-argument, stated because it is real: the author sees text in their editor
        // and gets a blank diagram with "Rendered successfully." If the reviewer prefers that
        // case to speak, the honest channel is an OK CAVEAT ("this body contains only comments"),
        // never PARSE_ERROR — the render did succeed.
        RenderResult r = Sirentide.renderWithDiagnostics("flowchart TD\n    %% only a comment\n");
        assertFalse(String.valueOf(r.svg()).contains("only a comment"),
            "the comment must not render, whatever the verdict is");
        assertEquals(Outcome.OK, r.diagnostics().outcome(),
            "a body of only comments declares nothing, like an empty body: "
                + r.diagnostics().message());
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
