package com.sirentide.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.FlowCluster;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.Flowchart;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Controls for the display-label seam required by Marlow's ruling (sirentide/671).
///
/// The exhaustiveness requirement itself is enforced by the COMPILER, not by anything here —
/// `LabelSurfaces.of` switches over a sealed hierarchy with no `default`, so omitting a type
/// fails the build. Verified by mutation: deleting the `Matrix` case produces
/// `error: the switch statement does not cover all possible input values`.
///
/// What that compile-time check CANNOT catch is a switch that is exhaustive and vacuous —
/// every branch present, every branch returning nothing. That is what these tests are for.
class LabelSurfacesTest {

    @Test
    @DisplayName("the switch is NON-VACUOUS: flowchart display labels are actually collected")
    void collectsFlowchartDisplayLabels() {
        List<LabelSurfaces.Labeled> found = LabelSurfaces.of(reproFlowchart());

        // An exhaustive switch whose branches all return empty compiles perfectly and
        // validates nothing, so presence is the load-bearing assertion here.
        assertFalse(found.isEmpty(), "flowchart must yield display labels");
        assertTrue(found.stream().anyMatch(l -> l.text().equals("TRUE NEGATIVE<br/>safe to act on")),
            "the node label must be collected: " + found);
        assertTrue(found.stream().anyMatch(l -> l.text().equals("edge caption")),
            "the edge label must be collected: " + found);
        assertTrue(found.stream().anyMatch(l -> l.text().equals("Outer title")),
            "the cluster TITLE must be collected: " + found);
    }

    @Test
    @DisplayName("IDENTIFIERS ARE NOT LABELS — this is the wiring error Marlow named")
    void identifiersAreNeverCollected() {
        List<LabelSurfaces.Labeled> found = LabelSurfaces.of(reproFlowchart());
        List<String> texts = found.stream().map(LabelSurfaces.Labeled::text).toList();

        // Ruling point 2: in `subgraph outer<unsafe> [Outer title]`, `outer<unsafe>` is an
        // IDENTIFIER bound for Anchor.sanitizeId, and `Outer title` is the display label.
        // If the validator ever rejects that source, it has been wired to an id or to `cap`
        // rather than to a label surface — and THIS is the assertion that catches it.
        assertFalse(texts.contains("outer<unsafe>"),
            "cluster ID must never be collected as a display label: " + texts);
        assertFalse(texts.contains("n1"), "node ID must never be collected: " + texts);
        assertFalse(texts.contains("n2"), "node ID must never be collected: " + texts);

        // ...and the id still appears as IDENTITY on the label it names, which is how a
        // diagnostic stays locatable without treating the id as validatable text.
        assertTrue(found.stream().anyMatch(l -> l.id().equals("cluster:outer<unsafe>")),
            "the cluster id is the label's stable identity: " + found);
    }

    @Test
    @DisplayName("labels carry a STABLE identity, never the label text")
    void identitiesAreStableAndNotTheText() {
        List<LabelSurfaces.Labeled> found = LabelSurfaces.of(reproFlowchart());
        for (LabelSurfaces.Labeled l : found) {
            assertFalse(l.id().contains(l.text()),
                "identity must not embed the unbounded label text: " + l);
            assertFalse(l.id().isBlank(), "every label needs a locatable identity: " + l);
        }
        assertTrue(found.stream().anyMatch(l -> l.id().equals("node:n1")), found.toString());
        assertTrue(found.stream().anyMatch(l -> l.id().equals("edge:n1->n2")), found.toString());
    }

    /// THE INCOMPLETENESS IS THE POINT OF THIS TEST, and it is expected to be RED until the
    /// remaining diagram types are audited.
    ///
    /// Returning an empty label list for an un-audited type is indistinguishable from a type
    /// that genuinely has no display text — the silent-zero shape this entire plan exists to
    /// remove. So the gap is declared in `LabelSurfaces.UNAUDITED` and asserted here, which
    /// makes it a failing fact rather than a quiet absence.
    ///
    /// This test failing is CORRECT while the audit is unfinished. It must go green before the
    /// slice is handed for review, and it must not be weakened to get there.
    @Test
    @DisplayName("every Diagram type is audited for display-vs-identifier fields")
    void everyDiagramTypeIsAudited() {
        assertEquals(List.of(), LabelSurfaces.UNAUDITED.stream().sorted().toList(),
            "these diagram types have not been audited for display-label fields; until they "
                + "are, LabelSurfaces.of returns an empty list for them, which is "
                + "indistinguishable from having no labels at all");
    }

    /// The exact repro from the defect this plan closes, plus an identifier that MUST survive:
    /// `A[TRUE NEGATIVE<br/>safe to act on]` rendered `<br/>` as visible text with exit 0.
    private static Diagram reproFlowchart() {
        return new Flowchart(
            List.of(
                new FlowNode("n1", "TRUE NEGATIVE<br/>safe to act on", "rect", null),
                new FlowNode("n2", "plain label", "rect", null)),
            List.of(new FlowEdge("n1", "n2", "edge caption")),
            "TD",
            null,
            null,
            List.of(new FlowCluster("outer<unsafe>", "Outer title", List.of("n1"), 0)));
    }
}
