package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Flowchart;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Two pasted-Mermaid flowchart forms are REFUSED and NAMED (audit 2026-10-12 finding 1; lead
/// ruling "refuse first, fail closed: a pasted Mermaid form that renders wrong at OK is worse than
/// a refusal that names the construct").
///
/// WHAT THEY DID BEFORE, read off the parser by trace:
///
///     graph TD;        nodes: A | "B;" | B | "C;"     edges: A -> "B;" , B -> "C;"
///     A-->B;           (a broken chain, every target wearing a semicolon; and `graph LR;`
///     B-->C;            silently rendered top-down, because the header token was `LR;`)
///
///     flowchart TD     nodes: "A -- yes" | B          edges: "A -- yes" -> B  (no label)
///     A -- yes --> B
///
/// Both at outcome OK. Neither is the missing-feature class: the picture is finished-looking and
/// the node names are wrong, which is the `A & B --> C` defect the unsupported-token channel was
/// built for, so they join that channel rather than the line-scoped dropped-statement caveat.
///
/// EVERY REFUSAL HERE HAS A CONTROL ASSERTING NODE IDS AND EDGES, not merely the diagram type. A
/// refusal keyed too broadly (any `;` anywhere, any `--` anywhere) passes every refusal test in
/// this file and is caught only by the controls.
class MermaidFlowchartPasteRefusalTest {

    private static List<String> nodeIds(Flowchart fc) {
        return fc.nodes().stream().map(n -> n.id()).toList();
    }

    private static List<String> edges(Flowchart fc) {
        return fc.edges().stream()
            .map(e -> e.from() + "->" + e.to() + (e.label() == null ? "" : "[" + e.label() + "]"))
            .toList();
    }

    private static Flowchart flowchart(String dsl) {
        Diagram d = DslParser.parse(dsl);
        return assertInstanceOf(Flowchart.class, d,
            "expected a Flowchart to inspect, got " + d.getClass().getSimpleName() + " for: " + dsl);
    }

    /// The refusal, end to end: the parse degrades to Empty (so no wrongly named node can be
    /// drawn), the detector names the token on its 1-based physical line, and the diagnostics
    /// entry reports UNSUPPORTED_CONSTRUCT with a message that names the construct.
    private static void assertRefusedNaming(String dsl, String token, String named, int line) {
        assertInstanceOf(Empty.class, DslParser.parse(dsl),
            "the whole diagram must degrade, never render with wrong node names: " + dsl);
        DslParser.UnsupportedConstruct u = DslParser.detectUnsupportedConstruct(dsl);
        assertTrue(u != null, "the construct must be NAMED, not silently degraded: " + dsl);
        assertEquals(token, u.token(), "the detector's token for: " + dsl);
        assertEquals(line, u.line(), "the 1-based physical line of the construct in: " + dsl);

        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome(),
            "a refusal must not read as success: " + r.diagnostics().message());
        assertEquals("parse", r.diagnostics().stage());
        assertEquals(line, r.diagnostics().line());
        assertTrue(r.diagnostics().message().contains(named),
            "the author-facing message must name the construct " + named + ": "
                + r.diagnostics().message());
        assertTrue(r.diagnostics().detail().contains(token),
            "the detail must carry the token '" + token + "': " + r.diagnostics().detail());
    }

    private static void assertNotRefused(String dsl) {
        assertNull(DslParser.detectUnsupportedConstruct(dsl),
            "a supported flowchart must not be named as unsupported: " + dsl);
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome(),
            "and must still render OK: " + dsl);
    }

    // -- `;` statement terminators ------------------------------------------------------------------

    @Test
    void theCanonicalMermaidPasteIsRefusedAtItsHeader() {
        // The audit's own input. Line 1 is the header: `graph TD;` is refused before the body is
        // read, which is also what stops `graph LR;` from rendering top-down.
        assertRefusedNaming("graph TD;\nA-->B;\nB-->C;\n", ";", "`;`", 1);
    }

    @Test
    void aHeaderTerminatorAloneIsRefused() {
        // Discriminates the HEADER check from the body check: this body has no `;` at all, so a
        // parser that only scans body statements renders it, top-down, at OK.
        assertRefusedNaming("graph LR;\nA --> B\n", ";", "`;`", 1);
    }

    @Test
    void aBodyTerminatorAloneIsRefusedAtItsOwnLine() {
        // Discriminates the BODY check from the header check: the header is clean.
        assertRefusedNaming("flowchart TD\nA --> B\nB --> C;\n", ";", "`;`", 3);
    }

    @Test
    void aSeparatorBetweenTwoStatementsOnOneLineIsRefused() {
        // Mermaid's other use of `;`. Before: one node literally named `B; B`.
        assertRefusedNaming("flowchart TD\nA --> B; B --> C\n", ";", "`;`", 2);
    }

    @Test
    void aTerminatedLoneNodeDeclarationIsRefused() {
        // An arrowless NODE line is a statement too. Before: a node named `Solo;`.
        assertRefusedNaming("flowchart TD\nA --> B\nSolo;\n", ";", "`;`", 3);
    }

    @Test
    void theSameChainWithoutTerminatorsParsesToTheSameNodesAndEdgesAsBefore() {
        // THE POSITIVE CONTROL for every `;` refusal above: the nearby valid form.
        String dsl = "flowchart LR\nA --> B\nB --> C\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("A", "B", "C"), nodeIds(fc));
        assertEquals(List.of("A->B", "B->C"), edges(fc));
        assertEquals("LR", fc.direction(), "and the header direction is honoured");
        assertNotRefused(dsl);
    }

    @Test
    void aSemicolonInsideALabelSpanIsContent() {
        // POSITION CLASS, the same rule the `&` detector follows: inside `[]`, `()` or `||` a `;`
        // is label text. A character guard (refuse any `;` on the line) fails here.
        String dsl = "flowchart TD\nA[wait; then go] -->|a; b| B(x; y)\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("A", "B"), nodeIds(fc));
        assertEquals(List.of("A->B[a; b]"), edges(fc));
        assertEquals("wait; then go", fc.nodes().get(0).label());
        assertEquals("x; y", fc.nodes().get(1).label());
        assertNotRefused(dsl);
    }

    @Test
    void aSemicolonOnAClassDefLineKeepsItsExistingTreatment() {
        // SCOPE, pinned so it is a decision and not an accident. A `;` can only mint a node name on
        // a node/edge statement. On a `classDef` line it is a malformed property value, which the
        // colour gate already drops while the bake continues (NodeEdgeStylingTest pins that with a
        // hostile `stroke:red;onload=...`). Refusing it here would turn that security fixture's
        // surviving diagram into the inert shell.
        String dsl = "flowchart TD\nclassDef bad stroke:red;x\nA --> B\nclass A bad\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("A", "B"), nodeIds(fc));
        assertEquals(List.of("A->B"), edges(fc));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }

    // -- inline edge labels -------------------------------------------------------------------------

    @Test
    void theInlineSolidEdgeLabelIsRefused() {
        // The audit's own input, and the sibling OperatorResidueNoPhantomTest names in a comment
        // without asserting on. Before: node `A -- yes`, unlabelled edge to B, outcome OK.
        assertRefusedNaming("flowchart TD\nA -- yes --> B\n", "-- text -->", "`-- text -->`", 2);
    }

    @Test
    void everySpellingOfTheInlineLabelIsRefusedOneAtATime() {
        // One spelling per assertion, per the convention UnknownDirectiveShapeTest states: a
        // combined case would leave the others unproven.
        assertRefusedNaming("flowchart TD\nA -- yes --- B\n", "-- text -->", "`-- text -->`", 2);
        assertRefusedNaming("flowchart TD\nA--yes-->B\n", "-- text -->", "`-- text -->`", 2);
        assertRefusedNaming("flowchart TD\nA == yes ==> B\n", "== text ==>", "`== text ==>`", 2);
        assertRefusedNaming("flowchart TD\nA -. yes .-> B\n", "-. text .->", "`-. text .->`", 2);
        // After a first, legal hop: the opener does not have to lead the line.
        assertRefusedNaming("flowchart TD\nA --> B -- no --> C\n", "-- text -->", "`-- text -->`", 2);
    }

    @Test
    void thePipeLabelFormParsesToTheSameNodesAndEdgesAsBefore() {
        // THE POSITIVE CONTROL for the inline-label refusals: the supported spelling of the same
        // edge, in all three operator families.
        String dsl = "flowchart TD\nA -->|yes| B\nB ==>|force| C\nC -.->|maybe| D\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("A", "B", "C", "D"), nodeIds(fc));
        assertEquals(List.of("A->B[yes]", "B->C[force]", "C->D[maybe]"), edges(fc));
        assertNotRefused(dsl);
    }

    @Test
    void aHalfOperatorWithNoClosingOperatorIsStillIdText() {
        // THE DETECTOR IS THE PAIR, never the half. `A--B` has always been a legal bare node id
        // (matchEdgeOp's own note), and FlowchartEdgeTypeTest pins `A -. B` as a lone node. A
        // guard that trips on the opener alone refuses both.
        String dsl = "flowchart TD\nA--B\nx==y\nP -. Q\nC --> D\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("A--B", "x==y", "P -. Q", "C", "D"), nodeIds(fc));
        assertEquals(List.of("C->D"), edges(fc));
        assertNotRefused(dsl);
    }

    @Test
    void aHalfOperatorInsideALabelSpanIsContent() {
        // The bracket and pipe cases ride the span walk every operator scan already shares. The
        // PAREN case is the one that needed its own guard: that walk does not treat `(` as a span,
        // so without the depth count a round node whose label holds a dash pair is refused.
        String dsl = "flowchart TD\nA[x -- y] --> B\nC(p -- q) --> D\nE -->|m -- n| F\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("A", "B", "C", "D", "E", "F"), nodeIds(fc));
        assertEquals(List.of("A->B", "C->D", "E->F[m -- n]"), edges(fc));
        assertEquals("p -- q", fc.nodes().get(2).label());
        assertNotRefused(dsl);
    }

    // -- the narrowed rules (repair round after the adversarial review) ---------------------------
    //
    // Each pair below is one refusal that fired wider than its own stated reason. The first test
    // of a pair pins the form that is STILL refused, the second the form that now goes back to
    // what the parser did before this branch, and the second names that older behaviour in its
    // message so a later reader can tell a deliberate return from an accident.

    @Test
    void theDottedInlineLabelIsStillRefusedWhenItClosesOnAnArrow() {
        // The still-refused half of the dotted-closer pair: `.->` is the closing operator.
        assertRefusedNaming("flowchart TD\nC --> D\nA -. yes .-> B\n", "-. text .->", "`-. text .->`", 3);
    }

    @Test
    void aDottedHalfAndALaterDotDashWithNoArrowIsNotAnInlineLabel() {
        // The closer must be the operator `.->`, not the punctuation pair `.-`. The first version
        // of the rule refused this whole diagram on a line with no arrowhead anywhere on it.
        String dsl = "flowchart TD\nP -. Q .- R\nC --> D\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("P -. Q .- R", "C", "D"), nodeIds(fc),
            "OLD BEHAVIOUR RESTORED: before this branch `P -. Q .- R` was one lone node wearing "
                + "its own text (no operator on the line), and it is that again, not a refusal");
        assertEquals(List.of("C->D"), edges(fc));
        assertNotRefused(dsl);
    }

    @Test
    void anInlineLabelBesideAnHtmlCommentIsStillRefused() {
        // The still-refused half of the comment pair: the comment line is exempt, a real inline
        // label on another line of the same diagram is not, and it is the one that is named.
        assertRefusedNaming("flowchart TD\n<!-- note -->\nA -- yes --> B\n", "-- text -->",
            "`-- text -->`", 3);
    }

    @Test
    void anHtmlCommentLineIsADroppedLineNotABlankDiagram() {
        // `<!--` ends in the two dashes the inline-label rule reads as an opener, and `-->` is a
        // solid operator, so the first version of the rule named a comment as an inline edge label
        // and blanked the diagram.
        String dsl = "flowchart TD\nA --> B\n<!-- note -->\n";
        Flowchart fc = flowchart(dsl);
        assertEquals(List.of("A", "B"), nodeIds(fc),
            "OLD BEHAVIOUR RESTORED: before this branch a `<!-- note -->` line was DROPPED whole "
                + "(an operator with an empty endpoint) and the rest of the diagram rendered");
        assertEquals(List.of("A->B"), edges(fc));
        assertNull(DslParser.detectUnsupportedConstruct(dsl),
            "one unreadable line is not an unsupported construct: " + dsl);

        DslParser.FlowchartBodyCensus census = DslParser.flowchartBodyCensus(dsl);
        assertEquals(1, census.droppedTotal(), "the comment line is the one dropped statement");
        assertEquals(3, census.dropped().get(0).line(), "named on its own physical line");
        assertTrue(census.dropped().get(0).reason().contains("endpoint"),
            "dropped for its empty endpoint: " + census.dropped().get(0).reason());

        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.OK, r.diagnostics().outcome(),
            "the diagram renders; the drop is a caveat: " + r.diagnostics().message());
        assertTrue(r.diagnostics().message().contains("1 statement was dropped"),
            "and the caveat says a statement was dropped: " + r.diagnostics().message());
    }

    @Test
    void theExistingUnsupportedTokensStillWinOnTheirOwnLines() {
        // The new arms must not disturb the detector's established tokens.
        DslParser.UnsupportedConstruct u =
            DslParser.detectUnsupportedConstruct("flowchart TD\nA & B --> C\n");
        assertTrue(u != null && u.token().equals("&"), "the `&` fan-out still names itself");
    }
}
