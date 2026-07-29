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

    /// THE VACUITY BATTERY. The compiler proves every diagram type has a `case`; it cannot
    /// prove that case COLLECTS anything. Fifteen branches were written in one pass, and a
    /// branch that quietly gathers nothing is indistinguishable from a type with no labels —
    /// this file's own subject, one level in.
    ///
    /// So each label-bearing type gets a fixture carrying a sentinel, and the sentinel must
    /// come back. A branch that forgets an `add(...)` fails here.
    ///
    /// The two types deliberately excluded are asserted EMPTY rather than skipped, so the
    /// exclusion is a checked claim and not an oversight.
    @Test
    @DisplayName("VACUITY BATTERY: every audited type actually yields its display labels")
    void everyAuditedTypeIsNonVacuous() {
        var sentinel = "SENTINEL";
        record Case(String name, Diagram diagram) {}
        List<Case> cases = List.of(
            new Case("Pie", new com.sirentide.ir.Pie(
                List.of(new com.sirentide.ir.Slice(sentinel, 1, null, null)), true, null)),
            new Case("Timeline", new com.sirentide.ir.Timeline(
                List.of(new com.sirentide.ir.Slice(sentinel, 1, null, null)), null)),
            new Case("XyChart", new com.sirentide.ir.XyChart(
                List.of(), List.of(), List.of(sentinel), "bar", true, null)),
            new Case("Gantt", new com.sirentide.ir.Gantt(
                List.of(new com.sirentide.ir.Task(sentinel, 0, 1, null)), null)),
            new Case("QuadrantChart", new com.sirentide.ir.QuadrantChart(
                sentinel, "b", "c", "d", null, List.of(), null)),
            new Case("ClassDiagram", new com.sirentide.ir.ClassDiagram(
                List.of(new com.sirentide.ir.ClassBox(sentinel, List.of(), List.of())),
                List.of(), null)),
            new Case("ErDiagram", new com.sirentide.ir.ErDiagram(
                List.of(new com.sirentide.ir.ErEntity(sentinel, List.of())), List.of(), null)),
            new Case("GitGraph", new com.sirentide.ir.GitGraph(
                List.of(new com.sirentide.ir.GitOp.Branch(sentinel)), null)),
            new Case("Journey", new com.sirentide.ir.Journey(sentinel, List.of(), null)),
            new Case("Mindmap", new com.sirentide.ir.Mindmap(
                new com.sirentide.ir.MindmapNode(sentinel, List.of()), null)),
            new Case("Sankey", new com.sirentide.ir.Sankey(
                List.of(new com.sirentide.ir.SankeyFlow(sentinel, "t", 1)), null)),
            new Case("Heatmap", new com.sirentide.ir.Heatmap(
                List.of(sentinel), List.of(), null, "lo", "hi")),
            new Case("TensorNetwork", new com.sirentide.ir.TensorNetwork(
                List.of(sentinel), false, null)),
            // StateDiagram was in NEITHER this battery nor the capability map. Marlow emptied
            // its case body at sirentide/697 and every test stayed green -- removing all state
            // labels from validation while the suite advertised itself as exhaustive.
            //
            // It could not be caught behaviourally either: state's surfaces are math-aware, so
            // a probe expecting OK is indistinguishable from "no labels collected at all".
            // Only a NON-VACUITY assertion separates those two, which is what this row is.
            new Case("StateDiagram", new com.sirentide.ir.StateDiagram(
                new Flowchart(List.of(new FlowNode("s1", sentinel, "rect", null)),
                    List.of(), "TD", null, null, List.of()))));

        for (Case c : cases) {
            List<String> texts = LabelSurfaces.of(c.diagram()).stream()
                .map(LabelSurfaces.Labeled::text).toList();
            assertTrue(texts.contains(sentinel),
                c.name() + " collected no display label — its case is present but vacuous. "
                    + "Got: " + texts);
        }
    }

    @Test
    @DisplayName("types with no display text are asserted EMPTY, not merely skipped")
    void typesWithoutDisplayTextYieldNothing() {
        // Asserting empty makes the exclusion a CHECKED claim. Skipping them would let a
        // genuine label surface hide behind "we decided it has none".
        assertTrue(LabelSurfaces.of(new com.sirentide.ir.Empty()).isEmpty());
        assertTrue(LabelSurfaces.of(new com.sirentide.ir.MathBlock("x^2")).isEmpty());
        assertTrue(LabelSurfaces.of(new com.sirentide.ir.Knot("trefoil", null)).isEmpty());
    }

    /// SUPERSEDED MODEL, kept as a record of three wrong granularities.
    ///
    /// This test asserted a per-LAYOUT-FILE partition by scanning each layout's source for
    /// `MathLabel`. Marlow retired that at sirentide/697: a single layout emits several label
    /// surfaces through different paths, so a file-level boolean cannot describe it, and this
    /// guard agreed with my own map while two bypasses were live.
    ///
    /// The live capability contract now lives in
    /// `SemanticAnchorTest.everyEmittedLabelSurfaceBehavesAsItsEmitterImplies`, which probes
    /// each surface through the PUBLIC API with a non-null renderer instead of describing the
    /// code. What remains here is non-vacuity: that each type yields labels at all.
    ///
    /// THE CAPABILITY GUARD, rebuilt as an EXHAUSTIVE PARTITION.
    ///
    /// The previous version asserted four named plain layouts and two named math layouts, and
    /// Marlow's verdict names exactly why that was worthless (sirentide/693): "checking only
    /// six names repeats the incompleteness problem". It could not see the four layouts that
    /// were ALREADY misclassified -- Journey, Sankey, Heatmap, TensorNetwork -- because they
    /// were in neither list. A spot-check cannot find what it does not enumerate.
    ///
    /// So this derives the answer instead of sampling it: for EVERY label-bearing diagram
    /// type, the surfaces LabelSurfaces produces must carry `mathAware` iff that type's layout
    /// routes through `MathLabel`. A new diagram type, or a layout that starts or stops
    /// rendering math, breaks this test rather than silently inheriting a default.
    ///
    /// I had this derivation available when I wrote the six-name version -- I had already
    /// scanned which layouts use MathLabel -- and hand-enumerated anyway. That is the whole
    /// error: a derivation in hand, and a list shipped.
    @Test
    @DisplayName("EXHAUSTIVE: every label-bearing type's mathAware matches its layout's MathLabel use")
    void everyLabelBearingTypeMatchesItsLayoutMathCapability() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/java/com/sirentide/layout");
        assertTrue(java.nio.file.Files.isDirectory(dir), "layout sources readable: " + dir);

        // surface-id prefix -> the layout that renders it. Every entry is checked against that
        // layout's ACTUAL MathLabel usage, so this map cannot drift silently: if it is wrong,
        // the assertion below fails.
        record Surface(String idPrefix, String layout, Diagram sample) {}
        List<Surface> surfaces = List.of(
            new Surface("node:",     "FlowchartLayout",      reproFlowchart()),
            new Surface("matrix.",   "MatrixLayout",         sampleMatrix()),
            new Surface("journey.",  "JourneyLayout",        sampleJourney()),
            new Surface("sankey.",   "SankeyLayout",         sampleSankey()),
            new Surface("heatmap.",  "HeatmapLayout",        sampleHeatmap()),
            new Surface("tensor.",   "TensorNetworkLayout",  sampleTensor()),
            new Surface("gitgraph.", "GitGraphLayout",       sampleGitGraph()),
            new Surface("mindmap",   "MindmapLayout",        sampleMindmap()),
            new Surface("pie",       "PieLayout",            samplePie()),
            new Surface("timeline",  "TimelineLayout",       sampleTimeline()),
            new Surface("gantt.",    "GanttLayout",          sampleGantt()),
            new Surface("sequence.", "SequenceLayout",       sampleSequence()),
            new Surface("quadrant.", "QuadrantChartLayout",  sampleQuadrant()),
            new Surface("class",     "ClassDiagramLayout",   sampleClassDiagram()),
            new Surface("er",        "ErDiagramLayout",      sampleErDiagram()),
            new Surface("xychart",   "XyChartLayout",        sampleXyChart()));

        for (Surface sf : surfaces) {
            boolean layoutRendersMath = java.nio.file.Files
                .readString(dir.resolve(sf.layout() + ".java")).contains("MathLabel");
            List<LabelSurfaces.Labeled> got = LabelSurfaces.of(sf.sample()).stream()
                .filter(l -> l.id().startsWith(sf.idPrefix())).toList();
            assertFalse(got.isEmpty(),
                sf.layout() + ": fixture produced no '" + sf.idPrefix() + "' labels, so this "
                    + "row proves nothing -- the map or the fixture is wrong");
            for (LabelSurfaces.Labeled l : got) {
                assertEquals(layoutRendersMath, l.mathAware(),
                    l.id() + " is rendered by " + sf.layout() + ", which "
                        + (layoutRendersMath ? "DOES" : "does NOT") + " route through MathLabel, "
                        + "so mathAware must be " + layoutRendersMath
                        + ". Misclassifying plain as math re-opens the dollar-wrap bypass; "
                        + "misclassifying math as plain deletes valid formulas.");
            }
        }
    }

    private static Diagram sampleMatrix() {
        return new com.sirentide.ir.Matrix(List.of("$c$"),
            List.of(new com.sirentide.ir.Matrix.Row("$r$",
                List.of(new com.sirentide.ir.Matrix.Cell("$x$", null)))), null);
    }

    private static Diagram sampleJourney() {
        return new com.sirentide.ir.Journey("$t$", List.of(), null);
    }

    private static Diagram sampleSankey() {
        return new com.sirentide.ir.Sankey(
            List.of(new com.sirentide.ir.SankeyFlow("$a$", "$b$", 1)), null);
    }

    private static Diagram sampleHeatmap() {
        return new com.sirentide.ir.Heatmap(List.of("$c$"), List.of(), null, "$lo$", "$hi$");
    }

    private static Diagram sampleTensor() {
        return new com.sirentide.ir.TensorNetwork(List.of("$core$"), false, null);
    }

    private static Diagram sampleGitGraph() {
        return new com.sirentide.ir.GitGraph(
            List.of(new com.sirentide.ir.GitOp.Branch("$b$")), null);
    }

    private static Diagram sampleMindmap() {
        return new com.sirentide.ir.Mindmap(
            new com.sirentide.ir.MindmapNode("$m$", List.of()), null);
    }

    private static Diagram samplePie() {
        return new com.sirentide.ir.Pie(
            List.of(new com.sirentide.ir.Slice("$p$", 1, null, null)), true, null);
    }

    private static Diagram sampleTimeline() {
        return new com.sirentide.ir.Timeline(
            List.of(new com.sirentide.ir.Slice("$e$", 1, null, null)), null);
    }

    private static Diagram sampleGantt() {
        return new com.sirentide.ir.Gantt(
            List.of(new com.sirentide.ir.Task("$g$", 0, 1, null)), null);
    }

    private static Diagram sampleSequence() {
        return new com.sirentide.ir.Sequence(List.of("$actor$"), List.of(), null, null,
            true, List.of(), List.of(), List.of());
    }

    private static Diagram sampleQuadrant() {
        return new com.sirentide.ir.QuadrantChart("$q$", "b", "c", "d", null, List.of(), null);
    }

    private static Diagram sampleClassDiagram() {
        return new com.sirentide.ir.ClassDiagram(
            List.of(new com.sirentide.ir.ClassBox("$C$", List.of(), List.of())), List.of(), null);
    }

    private static Diagram sampleErDiagram() {
        return new com.sirentide.ir.ErDiagram(
            List.of(new com.sirentide.ir.ErEntity("$E$", List.of())), List.of(), null);
    }

    private static Diagram sampleXyChart() {
        return new com.sirentide.ir.XyChart(List.of(), List.of(), List.of("$s$"), "bar", true, null);
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
