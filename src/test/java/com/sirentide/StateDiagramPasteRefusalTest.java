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
import com.sirentide.ir.StateDiagram;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Four pasted-Mermaid state-diagram forms are REFUSED and NAMED (audit 2026-10-12 finding 3; lead
/// ruling "refuse first, fail closed").
///
/// WHAT THEY DID BEFORE, read off the parser by trace. The state parse takes any arrowless text
/// as a state id and knows exactly one operator, `-->`, so each of these drew a phantom state at
/// outcome OK:
///
///     state Foo  +  [*] --> Foo     nodes: "state Foo" | __start__ | Foo
///     direction LR                  an extra state named "direction LR"
///     A <--> B                      states "A <" and B
///     A ---> B                      ONE state named "A ---> B"
///
/// THE ASSERTIONS ARE ON NODE IDS, and that is the lesson this file exists to hold.
/// CompositeStateGuardTest pinned `state Foo` as "supported" by asserting the diagram TYPE and a
/// null detector result; both held while the phantom box was drawn, because a phantom does not
/// change the type. Every control below therefore asserts the exact id list and the exact edges.
class StateDiagramPasteRefusalTest {

    private static List<String> nodeIds(StateDiagram sd) {
        return sd.graph().nodes().stream().map(n -> n.id()).toList();
    }

    private static List<String> edges(StateDiagram sd) {
        return sd.graph().edges().stream()
            .map(e -> e.from() + "->" + e.to() + (e.label() == null ? "" : "[" + e.label() + "]"))
            .toList();
    }

    private static StateDiagram stateDiagram(String dsl) {
        Diagram d = DslParser.parse(dsl);
        return assertInstanceOf(StateDiagram.class, d,
            "expected a StateDiagram to inspect, got " + d.getClass().getSimpleName() + " for: " + dsl);
    }

    private static void assertRefusedNaming(String dsl, String token, int line) {
        assertInstanceOf(Empty.class, DslParser.parse(dsl),
            "the whole diagram must degrade, never render a phantom state: " + dsl);
        DslParser.UnsupportedConstruct u = DslParser.detectUnsupportedConstruct(dsl);
        assertTrue(u != null, "the construct must be NAMED, not silently degraded: " + dsl);
        assertEquals(token, u.token(), "the detector's token for: " + dsl);
        assertEquals(line, u.line(), "the 1-based physical line of the construct in: " + dsl);

        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome(),
            "a refusal must not read as success: " + r.diagnostics().message());
        assertEquals("parse", r.diagnostics().stage());
        assertEquals(line, r.diagnostics().line());
        assertTrue(r.diagnostics().message().contains("`" + token + "`"),
            "the author-facing message must name the construct `" + token + "`: "
                + r.diagnostics().message());
    }

    // -- the refusals, one form per test --------------------------------------------------------

    @Test
    void theStateKeywordDeclarationIsRefused() {
        // The audit's own input, and the exact fixture CompositeStateGuardTest called supported.
        assertRefusedNaming("stateDiagram-v2\n  state Foo\n  [*] --> Foo\n", "state", 2);
    }

    @Test
    void theStateKeywordAliasFormIsRefused() {
        // Mermaid's `state "Long name" as S`. Same keyword, and before this it was a box literally
        // named with the whole line, quotes included.
        assertRefusedNaming("stateDiagram-v2\n  [*] --> W\n  state \"Waiting room\" as W\n", "state", 3);
    }

    @Test
    void theDirectionStatementIsRefused() {
        assertRefusedNaming("stateDiagram-v2\ndirection LR\nA --> B\n", "direction", 2);
    }

    @Test
    void theBidirectionalTransitionIsRefused() {
        assertRefusedNaming("state\nA <--> B\n", "<-->", 2);
    }

    @Test
    void everyOverLongArrowSpellingIsRefusedAndNamedAsWritten() {
        // One spelling per assertion. None is enumerated in the parser: the operator residue is
        // the detector, and the token is the run as the author typed it.
        assertRefusedNaming("state\nA ---> B\n", "--->", 2);
        assertRefusedNaming("state\nA ----> B\n", "---->", 2);
        assertRefusedNaming("state\nA -->> B\n", "-->>", 2);
    }

    // -- the controls: nearby valid forms parse to the same nodes and edges as before -----------

    @Test
    void aBareNameDeclarationParsesToTheSameNodesAndEdgesAsBefore() {
        // THE POSITIVE CONTROL for the `state` keyword refusal: the supported spelling of the same
        // declaration. No phantom, and `Foo` is one state, declared once.
        String dsl = "stateDiagram-v2\n  Foo\n  [*] --> Foo\n";
        StateDiagram sd = stateDiagram(dsl);
        assertEquals(List.of("Foo", "__start__"), nodeIds(sd));
        assertEquals(List.of("__start__->Foo"), edges(sd));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome());
    }

    @Test
    void twoOneWayTransitionsParseToTheSameNodesAndEdgesAsBefore() {
        // THE POSITIVE CONTROL for the arrow refusals: what the bidirectional message tells the
        // author to write instead.
        String dsl = "state\nA --> B\nB --> A\n";
        StateDiagram sd = stateDiagram(dsl);
        assertEquals(List.of("A", "B"), nodeIds(sd));
        assertEquals(List.of("A->B", "B->A"), edges(sd));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome());
    }

    @Test
    void theSameTextInsideALabelIsContent() {
        // POSITION CLASS: every check reads the peeled STATEMENT, so the refused spellings are
        // ordinary text after the colon. A scanner reading the raw line refuses all three lines.
        String dsl = "state\nA --> B : direction LR\nIdle : state Foo\nC --> D : a ---> b\n";
        StateDiagram sd = stateDiagram(dsl);
        assertEquals(List.of("A", "B", "Idle", "C", "D"), nodeIds(sd));
        assertEquals(List.of("A->B[direction LR]", "C->D[a ---> b]"), edges(sd));
        assertEquals("state Foo", sd.graph().nodes().get(2).label(),
            "`Idle : state Foo` is a display name, not a keyword declaration");
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }

    @Test
    void aStateNamedLikeAKeywordIsStillAState() {
        // Both keyword refusals have two conjuncts, and each control here removes exactly one:
        //   - a bare `state` line has the keyword and NO rest: it is a state named `state`;
        //   - `state --> Other` has the keyword and a rest, but is NOT arrowless: a transition.
        String dsl = "stateDiagram-v2\nstate\nstate --> Other\n";
        StateDiagram sd = stateDiagram(dsl);
        assertEquals(List.of("state", "Other"), nodeIds(sd));
        assertEquals(List.of("state->Other"), edges(sd));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }

    @Test
    void directionFollowedByAnythingButADirectionTokenIsStillAStateName() {
        // NARROW ON PURPOSE, pinned so it is a decision. Only Mermaid's own statement trips the
        // refusal: `direction` plus one of TB, TD, BT, LR, RL. A multi-word state name that merely
        // begins with the word is a legal state here and stays one.
        String dsl = "state\ndirection finder\ndirection finder --> Done\n";
        StateDiagram sd = stateDiagram(dsl);
        assertEquals(List.of("direction finder", "Done"), nodeIds(sd));
        assertEquals(List.of("direction finder->Done"), edges(sd));
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }
}
