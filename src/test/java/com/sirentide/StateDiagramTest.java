package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.StateDiagram;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Path;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.layout.StateDiagramLayout;
import com.sirentide.layout.Wedge;
import com.sirentide.parse.DslParser;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// Sirentide's seventh diagram type: a mermaid-style state diagram. It REUSES the flowchart layered
/// directed-graph engine (layering, cycle handling, edge labels) through a node styler — so these
/// tests pin the state-specific surface: the `[*]` pseudostate semantics, the mermaid `: label`
/// transition grammar, and the disc/bullseye pseudostate geometry — plus prove the flowchart engine's
/// output is unchanged by the styler seam (the flowchart goldens in GoldenSvgTest are the byte proof).
class StateDiagramTest {

    /// The canonical lifecycle: `[*]` as a source is start, as a target is end; a labeled forward
    /// transition and a cycle (Idle↔Running).
    private static final String LIFECYCLE =
        "state\n[*] --> Idle\nIdle --> Running : start\nRunning --> Idle : stop\nRunning --> [*]\n";

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static StateDiagram parse(String dsl) {
        Diagram d = DslParser.parse(dsl);
        assertTrue(d instanceof StateDiagram,
            "parses to a StateDiagram, not " + d.getClass().getSimpleName());
        return (StateDiagram) d;
    }

    private static List<FlowNode> nodes(StateDiagram sd) {
        return sd.graph().nodes();
    }

    private static Map<String, String> shapeById(StateDiagram sd) {
        return nodes(sd).stream().collect(Collectors.toMap(FlowNode::id, FlowNode::shape));
    }

    private static long countShapes(LaidOut laid, Class<? extends Shape> type) {
        return laid.shapes().stream().filter(type::isInstance).count();
    }

    // -- parse ----------------------------------------------------------------

    @Test
    void parsesStatesTransitionsAndPseudostates() {
        StateDiagram sd = parse(LIFECYCLE);
        // Nodes in first-seen order: __start__ (from the first [*] source), Idle, Running, __end__.
        assertEquals(List.of("__start__", "Idle", "Running", "__end__"),
            nodes(sd).stream().map(FlowNode::id).collect(Collectors.toList()));
        Map<String, String> shapes = shapeById(sd);
        assertEquals("start", shapes.get("__start__"), "[*] as SOURCE is the start pseudostate");
        assertEquals("end", shapes.get("__end__"), "[*] as TARGET is the end pseudostate");
        assertEquals("state", shapes.get("Idle"));
        assertEquals("state", shapes.get("Running"));
        // Pseudostates carry an EMPTY label (a disc, no text); real states default to their id.
        Map<String, String> labels =
            nodes(sd).stream().collect(Collectors.toMap(FlowNode::id, FlowNode::label));
        assertEquals("", labels.get("__start__"));
        assertEquals("", labels.get("__end__"));
        assertEquals("Idle", labels.get("Idle"));
    }

    @Test
    void transitionLabelsRideTheRightHopAfterTheColon() {
        StateDiagram sd = parse(LIFECYCLE);
        List<FlowEdge> edges = sd.graph().edges();
        assertEquals(4, edges.size(), "one edge per transition");
        // __start__ --> Idle (no label); Idle --> Running : start; Running --> Idle : stop; Running --> __end__
        assertEquals("__start__", edges.get(0).from());
        assertEquals("Idle", edges.get(0).to());
        assertNull(edges.get(0).label(), "the [*]-->Idle transition has no label");
        assertEquals("start", edges.get(1).label(), "the colon-tail label rides Idle-->Running");
        assertEquals("stop", edges.get(2).label());
        assertEquals("__end__", edges.get(3).to());
        assertNull(edges.get(3).label());
    }

    @Test
    void statesAndEndAreDistinctPseudostatesPerRole() {
        // The SAME [*] token is __end__ as a target and __start__ as a source (mermaid semantics):
        // `A --> [*]` ends, `[*] --> B` starts — two distinct reserved nodes in one diagram.
        StateDiagram sd = parse("state\nA --> [*]\n[*] --> B\n");
        Map<String, String> shapes = shapeById(sd);
        assertEquals("end", shapes.get("__end__"));
        assertEquals("start", shapes.get("__start__"));
        List<FlowEdge> edges = sd.graph().edges();
        assertEquals("__end__", edges.get(0).to(), "A --> [*] targets the end pseudostate");
        assertEquals("__start__", edges.get(1).from(), "[*] --> B sources the start pseudostate");
    }

    @Test
    void chainedTransitionLabelsTheLastHopOnly() {
        // Mermaid: on `A --> B --> C : go` the label applies to the LAST hop (B-->C) only.
        StateDiagram sd = parse("state\nA --> B --> C : go\n");
        List<FlowEdge> edges = sd.graph().edges();
        assertEquals(2, edges.size(), "chain expands to two hops");
        assertNull(edges.get(0).label(), "the first hop A-->B is unlabeled");
        assertEquals("go", edges.get(1).label(), "the colon-tail label rides the last hop B-->C");
    }

    @Test
    void bareStateDeclarationWithDisplayName() {
        // `S : display name` (no arrow) is a bare state decl — the tail is the DISPLAY NAME, not an
        // edge label; the first display name wins over the default id.
        StateDiagram sd = parse("state\nIdle : Waiting for work\nIdle --> Busy\n");
        Map<String, String> labels =
            nodes(sd).stream().collect(Collectors.toMap(FlowNode::id, FlowNode::label));
        assertEquals("Waiting for work", labels.get("Idle"), "bare decl sets the display name");
        assertEquals("Busy", labels.get("Busy"), "an undeclared state defaults to its id");
        assertEquals(0, sd.graph().edges().stream()
            .filter(e -> e.label() != null).count(), "the display name is NOT an edge label");
    }

    @Test
    void malformedRowsDropNotThrow() {
        // A bare `[*]` has no role → dropped; an empty endpoint drops the whole transition line.
        StateDiagram sd = parse("state\n[*]\nA --> \nA --> B\n");
        assertEquals(List.of("A", "B"), nodes(sd).stream().map(FlowNode::id).collect(Collectors.toList()),
            "only the well-formed A --> B survives; bare [*] and empty-endpoint rows drop");
        assertEquals(1, sd.graph().edges().size());
    }

    @Test
    void statediagramIsAnAliasOfState() {
        assertTrue(DslParser.parse("statediagram\nA --> B\n") instanceof StateDiagram,
            "`statediagram` is accepted as an alias of `state`");
    }

    @Test
    void emptyBodyIsAStateDiagramNotEmpty() {
        StateDiagram sd = parse("state\n");
        assertEquals(0, nodes(sd).size());
        assertEquals(0, sd.graph().edges().size());
    }

    // -- render / layout geometry ---------------------------------------------

    @Test
    void startDiscAndEndBullseyeRenderAsStackedDiscs() {
        LaidOut laid = StateDiagramLayout.layout(parse(LIFECYCLE));
        // The start pseudostate = ONE disc; the end bullseye = THREE stacked discs → 4 Wedges total.
        assertEquals(4, countShapes(laid, Wedge.class),
            "1 start disc + 3 bullseye discs (dark/white/dark)");
        // Normal states are ROUNDED-rect Paths now (Q-corner boxes), NOT sharp Rects — none anywhere.
        assertEquals(0, countShapes(laid, Rect.class), "no sharp rects — states are rounded paths");
    }

    /// Each normal `state` box is a rounded-rect {@link Path} carrying Q (quadratic corner) commands
    /// and NO {@link Rect} — the flowchart's sharp Rect was reskinned to a soft box. Arrowhead paths
    /// are straight triangles (M/L/Z, no Q), so filtering on Q isolates exactly the state boxes.
    @Test
    void normalStatesAreRoundedRectPathsNotRects() {
        LaidOut laid = StateDiagramLayout.layout(parse(LIFECYCLE));
        long roundedBoxes = laid.shapes().stream()
            .filter(s -> s instanceof Path)
            .map(s -> ((Path) s).d())
            .filter(d -> d.contains(" Q "))
            .count();
        assertEquals(2, roundedBoxes,
            "each of the 2 normal states (Idle, Running) is a rounded-rect path with Q corners");
        assertEquals(0, countShapes(laid, Rect.class),
            "no sharp rects for a state box — rounded paths replace them");
    }

    @Test
    void rendersWellFormedGlyphLabelledSvgNoText() {
        String svg = Sirentide.render(LIFECYCLE);
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertTrue(!svg.contains("<text"), "transition labels are glyph paths, never <text>");
        // The two labeled transitions add glyph <path>s over an unlabeled twin.
        String labeled = Sirentide.render("state\nA --> B : go\n");
        String plain = Sirentide.render("state\nA --> B\n");
        assertTrue(count(labeled, "<path") > count(plain, "<path"),
            "the transition label adds glyph paths");
    }

    @Test
    void pseudostateDiscsAreFullTwoArcCircles() {
        // The discs are contract-clean two-arc <path>s (the emitter draws a full-circle Wedge as two
        // semicircle arcs). A start disc uses radius 7 → the path carries `A 7 7`.
        String svg = Sirentide.render("state\n[*] --> A\n");
        assertTrue(svg.contains("A 7 7"), "start disc is a radius-7 two-arc circle: " + svg);
    }

    @Test
    @Timeout(5)
    void cycleTerminatesAndLaysOutAllNodes() {
        // Idle↔Running is a cycle; the engine's back-edge handling must terminate and still render
        // both states (the styler seam doesn't touch layering).
        LaidOut laid = StateDiagramLayout.layout(parse(LIFECYCLE));
        long roundedBoxes = laid.shapes().stream()
            .filter(s -> s instanceof Path).map(s -> ((Path) s).d())
            .filter(d -> d.contains(" Q ")).count();
        assertEquals(2, roundedBoxes, "both cyclic states render as rounded-rect paths");
        String svg = Sirentide.render(LIFECYCLE);
        assertNotNull(svg);
        assertTrue(svg.startsWith("<svg"), "the cycle still renders a valid svg");
    }

    @Test
    void renderIsDeterministic() {
        assertEquals(Sirentide.render(LIFECYCLE), Sirentide.render(LIFECYCLE));
    }
}
