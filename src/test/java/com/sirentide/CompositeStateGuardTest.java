package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.StateDiagram;
import com.sirentide.parse.DslParser;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// The COMPOSITE-STATE guard (plan 8a991947, slice 2).
///
/// MEASURED BEFORE IT WAS WRITTEN. A Mermaid composite state — nested states inside
/// `state X { … }` — parsed into this, at outcome OK:
///
///     nodes: __start__ | Active | "state Active {" | Idle | Running | "}" | __end__
///     edges: __start__->Active | __start__->Idle | Idle->Running | Active->__end__
///
/// Three failures, and the third is the one that matters:
///
///  1. `state Active {` becomes a literal box — the whole line, brace included, is a node
///     label. This is exactly the shape the flowchart detector was built for (`A & B --> C`
///     minting one node named `A & B`).
///  2. `}` becomes a box labelled `}`.
///  3. THE NESTED `[*]` FUSES WITH THE OUTER ONE. The inner initial pseudostate re-uses
///     `__start__`, producing the edge `__start__ -> Idle` — a start arrow into a state the
///     author never started from. That is not noise, it is a FALSE STRUCTURAL CLAIM about
///     the diagram's semantics, rendered confidently at exit 0.
///
/// So this is the silent-wrong-output class, not the missing-feature class: a reader cannot
/// tell the difference between this and a diagram that was authored that way.
///
/// THE DISPOSITION FOLLOWS THE ESTABLISHED PRECEDENT rather than inventing one.
/// `parseFlowchart` degrades the WHOLE diagram to the inert shell when
/// `firstUnsupportedFlowToken` fires, and `detectUnsupportedConstruct` NAMES the token on
/// the `UNSUPPORTED_CONSTRUCT` channel. Composite states get the same two-part treatment,
/// because a partially-correct diagram is worse than none: the misleading part is
/// indistinguishable from the correct part.
class CompositeStateGuardTest {

    private static final String COMPOSITE = """
        stateDiagram-v2
          [*] --> Active
          state Active {
            [*] --> Idle
            Idle --> Running
          }
          Active --> [*]
        """;

    private static final String FLAT = """
        stateDiagram-v2
          [*] --> Active
          Active --> [*]
        """;

    private static List<String> nodeIds(StateDiagram sd) {
        return sd.graph().nodes().stream().map(n -> n.id()).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // The guard: name it, and degrade rather than render a misleading partial.
    // ------------------------------------------------------------------

    @Test
    void compositeStateIsNamedOnTheDiagnosticChannel() {
        DslParser.UnsupportedConstruct u = DslParser.detectUnsupportedConstruct(COMPOSITE);
        assertNotNull(u, "a composite state must be NAMED, not silently mangled into literal boxes");
        assertTrue(u.token().contains("state") && u.token().contains("{"),
            "the diagnostic must name the composite opener so the author can find it: " + u.token());
        assertEquals(3, u.line(),
            "the PHYSICAL 1-based line of `state Active {` in the raw source, counted from the top");
        assertTrue(u.message() != null && !u.message().isBlank(),
            "an unsupported construct must carry an author-facing sentence, not just a token");
    }

    @Test
    void compositeStateDegradesToTheInertShellRatherThanPhantomBoxes() {
        Diagram d = DslParser.parse(COMPOSITE);
        assertInstanceOf(Empty.class, d,
            "a composite state must degrade the WHOLE diagram, matching parseFlowchart's precedent. "
                + "Rendering the supported subset emits boxes named `state Active {` and `}`, plus a "
                + "`__start__ -> Idle` edge the author never wrote — and nothing distinguishes those "
                + "from real content. Got: " + describe(d));
    }

    // ------------------------------------------------------------------
    // Negative controls. Each of these passed BEFORE the guard and must pass after —
    // a degrade that fires on a supported diagram is far worse than the defect.
    // ------------------------------------------------------------------

    @Test
    void flatStateDiagramIsUntouched() {
        Diagram d = DslParser.parse(FLAT);
        StateDiagram sd = assertInstanceOf(StateDiagram.class, d,
            "an ordinary state diagram must still parse — this is the control that catches a guard "
                + "keyed too broadly (e.g. on the word `state` appearing anywhere)");
        assertEquals(List.of("__start__", "Active", "__end__"), nodeIds(sd));
        assertEquals(2, sd.graph().edges().size());
        assertNull(DslParser.detectUnsupportedConstruct(FLAT),
            "a supported state diagram must not be NAMED as unsupported either");
    }

    @Test
    void aBareStateKeywordDeclarationIsStillSupported() {
        // `state Foo` WITHOUT a brace is legal Mermaid for declaring a state. Only the
        // composite BLOCK is unsupported, so keying on the `state` keyword alone would
        // wrongly kill this. This is the discriminator between "the keyword" and "the block".
        String src = "stateDiagram-v2\n  state Foo\n  [*] --> Foo\n";
        Diagram d = DslParser.parse(src);
        assertInstanceOf(StateDiagram.class, d,
            "`state Foo` with no brace is a declaration, not a composite block: " + describe(d));
        assertNull(DslParser.detectUnsupportedConstruct(src));
    }

    @Test
    void aBraceInsideATransitionLabelIsLegalContent() {
        // POSITION CLASS, mirroring the flowchart detector's rule that a sigil inside a
        // bracketed/quoted span is content. A brace in a `: label` tail is text the author
        // typed, not a composite opener, and a detector that scans the raw line for `{`
        // would trip on it.
        String src = "stateDiagram-v2\n  [*] --> Idle\n  Idle --> Running : retry {3}\n";
        Diagram d = DslParser.parse(src);
        assertInstanceOf(StateDiagram.class, d,
            "a brace inside a transition LABEL must not read as a composite opener: " + describe(d));
        assertNull(DslParser.detectUnsupportedConstruct(src));
    }

    @Test
    void flowchartDetectionIsUnchanged() {
        // The guard must not disturb the detector's existing, hard-scoped flowchart arm.
        String src = "flowchart TD\n  A & B --> C\n";
        DslParser.UnsupportedConstruct u = DslParser.detectUnsupportedConstruct(src);
        assertNotNull(u, "the pre-existing flowchart detection must still fire");
        assertTrue(u.token().contains("&"), "and must still name its own token: " + u.token());
    }

    private static String describe(Diagram d) {
        if (d instanceof StateDiagram sd) {
            return "StateDiagram nodes=" + nodeIds(sd) + " edges="
                + sd.graph().edges().stream().map(e -> e.from() + "->" + e.to()).toList();
        }
        return d.getClass().getSimpleName();
    }
}
