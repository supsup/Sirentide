package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.FlowCluster;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.Flowchart;
import com.sirentide.parse.DslParser;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// Sirentide's fifth diagram type: a flowchart — a layered top-down directed graph of node boxes +
/// arrowed edges. Tests parse (ids/labels/direction), the cycle-safe longest-path layering, and the
/// contract-clean render (rects + lines + arrow paths + glyph-path labels, no <text>).
class FlowchartTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static Flowchart parse(String dsl) {
        Diagram d = DslParser.parse(dsl);
        assertTrue(d instanceof Flowchart, "parses to a Flowchart, not " + d.getClass().getSimpleName());
        return (Flowchart) d;
    }

    private static Map<String, String> labelsById(Flowchart fc) {
        return fc.nodes().stream().collect(Collectors.toMap(FlowNode::id, FlowNode::label));
    }

    // -- parse ----------------------------------------------------------------

    @Test
    void parsesNodesLabelsAndEdges() {
        Flowchart fc = parse("flowchart\nA[Start] --> B[Process]\nB --> C[End]\n");
        assertEquals(List.of("A", "B", "C"),
            fc.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "3 nodes in first-seen order");
        Map<String, String> labels = labelsById(fc);
        assertEquals("Start", labels.get("A"));
        assertEquals("Process", labels.get("B"));   // label from B's first (bracketed) occurrence
        assertEquals("End", labels.get("C"));
        assertEquals(2, fc.edges().size(), "2 edges");
        assertEquals("TD", fc.direction(), "default direction");
    }

    @Test
    void edgeLabelsParseAndPlainEdgesStayUnlabeled() {
        // M1.2: the mermaid `-->|yes|` edge annotation.
        Flowchart fc = parse("flowchart\nA[Ship?] -->|yes| B[Deploy]\nA -->|no| C[Fix]\nC --> A\n");
        assertEquals("yes", fc.edges().get(0).label());
        assertEquals("no", fc.edges().get(1).label());
        assertEquals(null, fc.edges().get(2).label(), "an unlabeled edge has a null label");
        // A missing closing pipe is malformed → that line drops, the rest parse.
        Flowchart bad = parse("flowchart\nA -->|yes B\nA --> C\n");
        assertEquals(1, bad.edges().size(), "the malformed labeled line is dropped");
        assertEquals(null, bad.edges().get(0).label());
        // A labeled diagram renders more glyph shapes than its unlabeled twin (the label is drawn).
        String labeled = Sirentide.render("flowchart\nA[Go] -->|yes| B[Ship]\n");
        String plain = Sirentide.render("flowchart\nA[Go] --> B[Ship]\n");
        assertTrue(count(labeled, "<path") > count(plain, "<path"), "the edge label adds glyph paths");
    }

    @Test
    void diamondDecisionNodesParseAndRender() {
        // M1.3: `id{Label}` = a diamond decision node; `id[Label]`/bare = rect (default).
        Flowchart fc = parse("flowchart\nA{Tests green?} -->|yes| B[Ship]\nA -->|no| C[Fix]\n");
        assertEquals("diamond", fc.nodes().get(0).shape());
        assertEquals("Tests green?", fc.nodes().get(0).label());
        assertEquals("rect", fc.nodes().get(1).shape());
        assertEquals("rect", fc.nodes().get(2).shape(), "an undecorated node defaults to rect");
        // The diamond node draws as a <path>, not a <rect>: 3 nodes, only 2 rects.
        String svg = Sirentide.render("flowchart\nA{Q} --> B[Yes]\nA --> C[No]\n");
        assertEquals(2, count(svg, "<rect"), "the diamond isn't a rect");
        assertTrue(svg.startsWith("<svg"), "well-formed");
    }

    private static Map<String, String> shapesById(Flowchart fc) {
        return fc.nodes().stream().collect(Collectors.toMap(FlowNode::id, FlowNode::shape));
    }

    @Test
    void nodeShapesParseByDelimiterLongestFirst() {
        // Every mermaid node-shape delimiter maps to its shape, and the LONGEST delimiter wins at each
        // position: `([` is a stadium (not a rect that happens to start with `(`), `((` a circle (not a
        // rounded), `{{` a hexagon (not a diamond), `[[` a subroutine and `[(` a cylinder (not a rect).
        Flowchart fc = parse("flowchart\n  R[rect] --> D{diamond}\n  N(round) --> S([stadium])\n"
            + "  C((circle)) --> H{{hexagon}}\n  Y[(cylinder)] --> U[[subroutine]]\n");
        Map<String, String> sh = shapesById(fc);
        assertEquals("rect", sh.get("R"), "[..] is a rect");
        assertEquals("diamond", sh.get("D"), "{..} is a diamond");
        assertEquals("rounded", sh.get("N"), "(..) is a rounded box");
        assertEquals("stadium", sh.get("S"), "([..]) is a stadium — longest-first beats [..]");
        assertEquals("circle", sh.get("C"), "((..)) is a circle — longest-first beats (..)");
        assertEquals("hexagon", sh.get("H"), "{{..}} is a hexagon — longest-first beats {..}");
        assertEquals("cylinder", sh.get("Y"), "[(..)] is a cylinder — longest-first beats [..]");
        assertEquals("subroutine", sh.get("U"), "[[..]] is a subroutine — longest-first beats [..]");
        // Labels survive the longer delimiters intact.
        Map<String, String> lbl = labelsById(fc);
        assertEquals("stadium", lbl.get("S"));
        assertEquals("circle", lbl.get("C"));
        assertEquals("subroutine", lbl.get("U"));
    }

    @Test
    void malformedShapeDelimitersDropTheLineNotThrow() {
        // An UNCLOSED shape delimiter and a MISMATCHED pair are malformed → the whole line drops (inert),
        // never a throw and never a half-shape. Each bad line here registers no node; the good ones do.
        Flowchart fc = parse("flowchart\n  A([ok]) --> B[fine]\n"
            + "  C([unterminated\n  D((one)\n  E{{one}\n  F[(mismatch]\n  G([mismatch}\n");
        // Only A and B (the well-formed line) registered; every malformed line dropped whole.
        assertEquals(List.of("A", "B"),
            fc.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "malformed shape lines drop whole — only the well-formed A([ok]) --> B[fine] survives");
        assertEquals("stadium", shapesById(fc).get("A"));
        assertEquals(1, fc.edges().size(), "one edge from the single well-formed line");
    }

    @Test
    void rectAndDiamondShapesAreUnchangedByTheNewDelimiters() {
        // Zero-behaviour-change guard at the PARSE level: a lone `[` is still a rect and a lone `{` still
        // a diamond (the byte-identical goldens pin the RENDER; this pins the shape assignment).
        Flowchart fc = parse("flowchart\n  A[box] --> B{decide}\n");
        assertEquals("rect", shapesById(fc).get("A"));
        assertEquals("diamond", shapesById(fc).get("B"));
    }

    @Test
    void bareIdsUseIdAsLabel() {
        Flowchart fc = parse("flowchart\nA --> B\n");
        Map<String, String> labels = labelsById(fc);
        assertEquals("A", labels.get("A"));
        assertEquals("B", labels.get("B"));
    }

    @Test
    void directionLrIsParsedButFieldOnly() {
        assertEquals("LR", parse("flowchart LR\nA --> B\n").direction());
        assertEquals("TD", parse("flowchart TD\nA --> B\n").direction());
        assertEquals("TD", parse("flowchart\nA --> B\n").direction(), "no token → TD default");
    }

    @Test
    void loneNodeDeclarationRegisters() {
        Flowchart fc = parse("flowchart\nA[Solo]\n");
        assertEquals(1, fc.nodes().size());
        assertEquals("Solo", labelsById(fc).get("A"));
        assertEquals(0, fc.edges().size());
    }

    @Test
    void malformedLinesAreSkippedNotThrown() {
        Flowchart fc = parse("flowchart\nA --> B\nno arrow here is a lone node\n --> \nB --> C\n");
        // "no arrow here..." → a lone node (whole line as id); the empty-endpoint " --> " is dropped.
        assertEquals(2, fc.edges().size(), "only the two well-formed edges");
    }

    @Test
    void emptyBodyIsAFlowchartNotEmpty() {
        Flowchart fc = parse("flowchart\n");
        assertEquals(0, fc.nodes().size());
        assertEquals(0, fc.edges().size());
    }

    // -- operator-scan `-->` splitting + chained edges + malformed drops ------

    @Test
    void bracketEmbeddedArrowIsNotAnEdgeSeparator() {
        // The `-->` inside `[a-->b]` is part of the LABEL, not an edge operator: this is ONE edge
        // A→C with A labeled "a-->b" (the old blind indexOf minted a phantom node id "b] --> C").
        Flowchart fc = parse("flowchart\nA[a-->b] --> C\n");
        assertEquals(List.of("A", "C"),
            fc.nodes().stream().map(FlowNode::id).collect(Collectors.toList()), "exactly 2 nodes");
        assertEquals("a-->b", labelsById(fc).get("A"), "the bracket-embedded arrow stays in the label");
        assertEquals(1, fc.edges().size(), "one edge");
        assertEquals("A", fc.edges().get(0).from());
        assertEquals("C", fc.edges().get(0).to());
    }

    @Test
    void chainedEdgesExpandToConsecutiveHops() {
        // `A --> B --> C` (any length) becomes edges A→B and B→C.
        Flowchart fc = parse("flowchart\nA --> B --> C\n");
        assertEquals(List.of("A", "B", "C"),
            fc.nodes().stream().map(FlowNode::id).collect(Collectors.toList()), "3 nodes");
        assertEquals(2, fc.edges().size(), "2 edges from the chain");
        assertEquals("A", fc.edges().get(0).from());
        assertEquals("B", fc.edges().get(0).to());
        assertEquals("B", fc.edges().get(1).from());
        assertEquals("C", fc.edges().get(1).to());
    }

    @Test
    void chainedEdgeLabelsApplyPerHop() {
        // `A -->|yes| B -->|no| C` labels each hop independently: A-yes→B, B-no→C.
        Flowchart fc = parse("flowchart\nA -->|yes| B -->|no| C\n");
        assertEquals(2, fc.edges().size());
        assertEquals("yes", fc.edges().get(0).label());
        assertEquals("no", fc.edges().get(1).label());
    }

    @Test
    void unterminatedBracketDropsTheLine() {
        // `A[Start --> B[End]` — the operator scan sees the `-->` inside the (nested/unbalanced)
        // bracket, so there is no top-level arrow; the endpoint validator then drops it entirely
        // rather than canonicalizing a plausible A→B. 0 edges (and 0 nodes: nothing registers).
        Flowchart fc = parse("flowchart\nA[Start --> B[End]\n");
        assertEquals(0, fc.edges().size(), "the malformed line yields no edge");
        assertEquals(0, fc.nodes().size(), "and no node — the whole line drops");
    }

    @Test
    void trailingJunkAfterClosedDelimiterDropsTheLine() {
        // Non-whitespace after a CLOSED `]`/`}` in an endpoint is malformed → drop the whole line.
        Flowchart fc = parse("flowchart\nA[Start] junk --> B[End] also junk\n");
        assertEquals(0, fc.edges().size(), "both endpoints carry trailing junk → line dropped");
        assertEquals(0, fc.nodes().size(), "nothing registers from a dropped line");
    }

    @Test
    void oversizedUtf8SourceDegradesToInert() {
        // Lattice #4 repro: 600k `é` chars = 1.2 MB of UTF-8 (but only 600k UTF-16 code units, so the
        // cheap length() guard misses it). The true-byte cap must reject it → the inert empty shell.
        String huge = "pie\n  \"A\" : 1\n" + "é".repeat(600_000);
        String svg = Sirentide.render(huge);
        assertEquals(Sirentide.render(""), svg, "oversized UTF-8 source renders the inert empty shell");
        assertTrue(!svg.contains("<path"), "no pie wedge leaked from the over-cap source");
    }





    // -- subgraph / end clusters ---------------------------------------------

    private static FlowCluster clusterById(Flowchart fc, String id) {
        return fc.clusters().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void subgraphOpenCloseCollectsFirstSeenMembers() {
        // C and D are FIRST SEEN inside the subgraph → members; B was first seen OUTSIDE (A --> B) so
        // it is NOT a member even though referenced inside (first-seen wins). E is outside.
        Flowchart fc = parse("flowchart TD\n  A[Start] --> B[Work]\n  subgraph pipeline [Build Pipeline]\n"
            + "    B --> C[Compile]\n    C --> D[Test]\n  end\n  D --> E[Ship]\n");
        assertEquals(1, fc.clusters().size(), "one cluster");
        FlowCluster c = fc.clusters().get(0);
        assertEquals("pipeline", c.id());
        assertEquals("Build Pipeline", c.title(), "the [bracket] title, not the id");
        assertEquals(0, c.depth(), "outermost cluster is depth 0");
        assertEquals(List.of("C", "D"), c.memberNodeIds(),
            "only the nodes FIRST seen inside (B was declared outside)");
    }

    @Test
    void subgraphTitleDefaultsToIdWhenBare() {
        Flowchart fc = parse("flowchart\n  subgraph solo\n    A --> B\n  end\n");
        FlowCluster c = clusterById(fc, "solo");
        assertEquals("solo", c.title(), "a bare `subgraph id` uses the id as its title");
        assertEquals(List.of("A", "B"), c.memberNodeIds());
    }

    @Test
    void nestedSubgraphsStackWithIncreasingDepthAndTransitiveMembership() {
        Flowchart fc = parse("flowchart TD\n  subgraph outer [Outer]\n    A --> B\n"
            + "    subgraph inner [Inner]\n      B --> C\n    end\n  end\n");
        assertEquals(2, fc.clusters().size(), "two clusters");
        FlowCluster inner = clusterById(fc, "inner");
        FlowCluster outer = clusterById(fc, "outer");
        assertEquals(1, inner.depth(), "the nested cluster is depth 1");
        assertEquals(0, outer.depth(), "the enclosing cluster is depth 0");
        assertEquals(List.of("C"), inner.memberNodeIds(), "C is first-seen inside inner");
        assertEquals(List.of("A", "B", "C"), outer.memberNodeIds(),
            "outer's membership is TRANSITIVE — it also owns the inner cluster's nodes");
    }

    @Test
    void unclosedSubgraphClosesGracefullyAtEndOfInput() {
        // No `end` — the cluster must still close at EOF (never throws), spanning what it saw.
        Flowchart fc = parse("flowchart\n  subgraph p [Pipeline]\n    A --> B\n    B --> C\n");
        assertEquals(1, fc.clusters().size(), "the unclosed cluster is closed at EOF");
        assertEquals(List.of("A", "B", "C"), clusterById(fc, "p").memberNodeIds());
        assertEquals(2, fc.edges().size(), "the edges inside still parse");
    }

    @Test
    void strayEndIsIgnoredNotThrown() {
        Flowchart fc = parse("flowchart\n  A --> B\n  end\n  B --> C\n");
        assertEquals(0, fc.clusters().size(), "a stray `end` opens no cluster and never throws");
        assertEquals(2, fc.edges().size(), "the edges around the stray `end` still parse");
    }

    @Test
    void emptySubgraphYieldsAClusterWithNoMembersAndDrawsNoFrame() {
        // An empty subgraph is kept in the IR but carries no members → layout draws no frame (inert).
        Flowchart fc = parse("flowchart\n  A --> B\n  subgraph empty [Nothing]\n  end\n  B --> C\n");
        FlowCluster c = clusterById(fc, "empty");
        assertEquals(List.of(), c.memberNodeIds(), "no node was first-seen inside → empty membership");
        // Render must not throw and must produce a valid svg (the empty cluster contributes no frame).
        String svg = Sirentide.render("flowchart\n  A --> B\n  subgraph empty [Nothing]\n  end\n  B --> C\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "empty subgraph renders cleanly");
    }

    @Test
    void flowchartWithoutSubgraphsHasNoClusters() {
        Flowchart fc = parse("flowchart\n  A --> B\n  B --> C\n");
        assertEquals(List.of(), fc.clusters(), "no subgraph → an empty cluster list (additive)");
    }

    @Test
    @Timeout(5)
    void cycleTerminatesAndLaysOut() {
        // The key robustness test: A→B→C→A is a cycle. The back-edge C→A is excluded from layering
        // (still drawn), so layering terminates. Assert it returns, all 3 nodes present, and renders.
        String dsl = "flowchart\nA --> B\nB --> C\nC --> A\n";
        Flowchart fc = parse(dsl);
        assertEquals(3, fc.nodes().size(), "all 3 cycle nodes present");
        // Layer VALUES are pinned against emitted geometry in FlowchartGeometryTest (the
        // test-local layers() re-implementation was deleted after review sirentide/22 T1).
        String svg = Sirentide.render(dsl);
        assertNotNull(svg);
        assertTrue(svg.startsWith("<svg"), "cycle still renders a valid svg");
        assertEquals(3, count(svg, "<rect"), "one box per node");
    }

    // -- subgraph-id edge routing (plan ea20153b part 3) ----------------------

    private static FlowEdge edgeAt(Flowchart fc, int i) {
        return fc.edges().get(i);
    }

    @Test
    void anEdgeToASubgraphIdRoutesToItsFirstMemberNotAPhantom() {
        // `EPR --> PROJ` where PROJ is a subgraph id used to mint a SEPARATE empty "PROJ" node
        // (a phantom wearing the group's name). Now it routes INTO the cluster: the edge retargets
        // to PROJ's first member (PP), and no phantom "PROJ" node exists.
        Flowchart fc = parse("flowchart TD\n  EPR[Scaffold] --> PROJ\n  subgraph PROJ [Project]\n"
            + "    PP[Package] --> QQ[Queue]\n  end\n");
        assertFalse(labelsById(fc).containsKey("PROJ"), "no phantom node wearing the subgraph id");
        assertEquals(List.of("EPR", "PP", "QQ"),
            fc.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "only the real nodes remain");
        FlowCluster proj = clusterById(fc, "PROJ");
        assertEquals(List.of("PP", "QQ"), proj.memberNodeIds());
        // The EPR --> PROJ edge now points at PROJ's representative member PP.
        assertEquals("EPR", edgeAt(fc, 0).from());
        assertEquals("PP", edgeAt(fc, 0).to(), "routed to the cluster's first-seen member");
    }

    @Test
    void anEdgeFromASubgraphIdAlsoRoutes() {
        // Routing is symmetric — a subgraph id on the SOURCE side retargets too.
        Flowchart fc = parse("flowchart\n  subgraph GRP\n    X --> Y\n  end\n  GRP --> Z[After]\n");
        assertFalse(labelsById(fc).containsKey("GRP"), "no phantom source node");
        // Edges: X-->Y, then the routed GRP-->Z which becomes X-->Z (X is GRP's first member).
        FlowEdge routed = fc.edges().stream().filter(e -> e.to().equals("Z")).findFirst().orElseThrow();
        assertEquals("X", routed.from(), "the GRP source routed to its first member");
    }

    @Test
    void anEdgeToAnEmptySubgraphIsDropped() {
        // An empty subgraph has no representative member, so an edge to it DROPS whole
        // (loud-or-dropped, never a phantom) — the same convention as a malformed endpoint.
        Flowchart fc = parse("flowchart\n  A[Solo] --> EMPTY\n  subgraph EMPTY\n  end\n");
        assertFalse(labelsById(fc).containsKey("EMPTY"), "no phantom for the empty cluster");
        assertEquals(0, fc.edges().size(), "the edge to an empty cluster is dropped");
        assertTrue(labelsById(fc).containsKey("A"), "the real endpoint survives");
    }

    @Test
    void anEdgeToASubgraphMEMBERIsNotRemapped() {
        // Only a subgraph ID endpoint routes; an edge to a genuine MEMBER node keeps that exact
        // endpoint (X stays X). This scopes part 3 to id-routing and leaves member endpoints alone.
        Flowchart fc = parse("flowchart\n  subgraph G\n    X --> Y\n  end\n  Z[Ext] --> X\n");
        FlowEdge external = fc.edges().stream().filter(e -> e.from().equals("Z")).findFirst().orElseThrow();
        assertEquals("X", external.to(), "an edge to a real member is not rerouted");
    }

    // -- render ---------------------------------------------------------------

    @Test
    void rendersBoxesEdgesArrowsAndLabels() {
        String svg = Sirentide.render("flowchart\nA[Start] --> B[Process]\nB --> C[End]\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertEquals(3, count(svg, "<rect"), "one rect per node");
        assertEquals(2, count(svg, "<line"), "one line per edge");
        // paths = one arrowhead per edge (2) + one glyph-label per node (3) = 5.
        assertEquals(5, count(svg, "<path"), "arrowheads + labels");
        assertTrue(!svg.contains("<text"), "labels are glyph paths, never <text>");
    }

    @Test
    void singleNodeRenders() {
        String svg = Sirentide.render("flowchart\nA[Solo]\n");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertEquals(1, count(svg, "<rect"), "the single node box");
        assertEquals(0, count(svg, "<line"), "no edges");
    }

    @Test
    void emptyFlowchartRendersBlankButValid() {
        String svg = Sirentide.render("flowchart\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "valid blank svg");
        assertEquals(0, count(svg, "<rect"), "no nodes");
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "flowchart\nA[Start] --> B[Middle]\nB --> C[End]\nC --> A\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    // -- LR geometry ----------------------------------------------------------

    /// Pulls the intrinsic width/height off the svg root (`<svg … width="W" height="H" …>`).
    private static double[] svgSize(String svg) {
        java.util.regex.Matcher w = java.util.regex.Pattern.compile("<svg[^>]*\\swidth=\"([0-9.]+)\"").matcher(svg);
        java.util.regex.Matcher h = java.util.regex.Pattern.compile("<svg[^>]*\\sheight=\"([0-9.]+)\"").matcher(svg);
        assertTrue(w.find() && h.find(), "svg root has width+height");
        return new double[] {Double.parseDouble(w.group(1)), Double.parseDouble(h.group(1))};
    }

    @Test
    void lrFlowsWideWhileTdFlowsTall() {
        // The same chain lays out COLUMNS left→right in LR (wider than tall) but ROWS top→down in TD
        // (taller than wide). A real geometry assertion off the emitted canvas box, not vacuous.
        String chain = "A[One] --> B[Two]\nB --> C[Three]\nC --> D[Four]\n";
        double[] lr = svgSize(Sirentide.render("flowchart LR\n" + chain));
        double[] td = svgSize(Sirentide.render("flowchart TD\n" + chain));
        assertTrue(lr[0] > lr[1], "LR chain is wider than tall: " + lr[0] + "x" + lr[1]);
        assertTrue(td[1] > td[0], "TD chain is taller than wide: " + td[0] + "x" + td[1]);
        // And the axes really swapped: LR is wider than TD, TD is taller than LR.
        assertTrue(lr[0] > td[0] && td[1] > lr[1], "LR/TD are transposes, not scalings");
    }

    @Test
    @Timeout(5)
    void lrCycleTerminatesAndRendersAllNodes() {
        // Mirror of the TD cycle test for LR: the back-edge C→A is excluded from layering (still
        // drawn through a below-content lane), so layout terminates; assert all nodes render.
        String dsl = "flowchart LR\nA --> B\nB --> C\nC --> A\n";
        Flowchart fc = parse(dsl);
        assertEquals(3, fc.nodes().size(), "all 3 cycle nodes present");
        String svg = Sirentide.render(dsl);
        assertNotNull(svg);
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "cycle still renders a valid svg");
        assertEquals(3, count(svg, "<rect"), "one box per node");
    }

    @Test
    void lrLabeledEdgesRenderGlyphs() {
        // An LR labeled diagram draws more glyph <path>s than its unlabeled twin (the label is drawn).
        String labeled = Sirentide.render("flowchart LR\nA[Go] -->|yes| B[Ship]\n");
        String plain = Sirentide.render("flowchart LR\nA[Go] --> B[Ship]\n");
        assertTrue(count(labeled, "<path") > count(plain, "<path"), "the LR edge label adds glyph paths");
    }

    @Test
    void reservedDirectiveLinesDropInertNeverMintNodes() {
        // REGRESSION (playground silent-mint finding, plan 66572bcd slice 1): a directive-shaped
        // line whose keyword this parser does not implement (`style`, `click`, `direction`, …)
        // used to fall through to the lone-node path and mint a phantom node wearing its own
        // directive text as a name. Recognized-but-unimplemented directives now drop inert —
        // the loud-or-dropped forward-compat rule.
        Flowchart fc = parse("flowchart\nA[Start] --> B[End]\n"
            + "style A fill:#f9f,stroke:#333\n"
            + "click A callback \"tooltip\"\n"
            + "direction LR\n");
        assertEquals(List.of("A", "B"),
            fc.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "no phantom 'style …' / 'click …' / 'direction …' nodes");
        // Only the two-token directive SHAPE is reserved: a deliberate lone node whose id is a
        // bare directive keyword still parses as a node.
        Flowchart bare = parse("flowchart\nstyle\n");
        assertEquals(List.of("style"),
            bare.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "a bare keyword with no rest is still a node");
    }

    @Test
    void colonFormAccessibilityDirectivesDropInert() {
        // REGRESSION (Lattice 7141 finding 1): Mermaid's canonical accessibility directives are
        // COLON-form (`accTitle: text`), so splitKeyword left the colon glued to the keyword
        // (`accTitle:`) and the colonless reserved set missed them — they minted phantom nodes.
        // Both the spaced and tight colon forms now drop inert.
        Flowchart spaced = parse("flowchart\naccTitle: Accessible title\naccDescr: Accessible description\nA --> B\n");
        assertEquals(List.of("A", "B"),
            spaced.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "no 'accTitle: …' / 'accDescr: …' phantom nodes (spaced colon form)");
        Flowchart tight = parse("flowchart\naccTitle:Accessible title\naccDescr:desc\nA --> B\n");
        assertEquals(List.of("A", "B"),
            tight.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "tight colon form drops too");
        // A colon after a NON-reserved first token is not a directive — it still parses as a node.
        Flowchart node = parse("flowchart\nfoo: bar\n");
        assertEquals(List.of("foo: bar"),
            node.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "a non-reserved keyword before a colon is a node, not a directive");
    }

    @Test
    void bidirectionalArrowDropsTheLineNeverMintsLtNode() {
        // REGRESSION (playground silent-mint finding, plan 66572bcd slice 1): `A <--> B` scanned
        // the `-->` mid-token and minted a phantom head node named "A <". Until bidirectional
        // edges are a real feature the whole line DROPS — loud-not-silent, the same convention
        // as a malformed endpoint — across all three operator families.
        Flowchart fc = parse("flowchart\nA <--> B\nC <-.-> D\nE <==> F\nG --> H\n");
        assertEquals(List.of("G", "H"),
            fc.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "only the valid line's nodes exist — no 'A <' phantom");
        assertEquals(1, fc.edges().size(), "only G --> H drew");
        // REGRESSION (Lattice 7141 finding 2): whitespace between `<` and the operator (a TAB, or
        // several spaces) must still trip the guard — the old ' '-only skip let a tab restore the
        // 'A <' phantom. Cover a tab and a multi-space gap across the operator families.
        Flowchart ws = parse("flowchart\nA <\t--> B\nC <  -.-> D\nG --> H\n");
        assertEquals(List.of("G", "H"),
            ws.nodes().stream().map(FlowNode::id).collect(Collectors.toList()),
            "tab/space between '<' and the operator still drops — no 'A <' / 'C <' phantom");
        assertEquals(1, ws.edges().size(), "only G --> H drew");
        // A '<' inside a bracketed label span is untouched by the guard.
        Flowchart lbl = parse("flowchart\nA[x < y] --> B\n");
        assertEquals(2, lbl.nodes().size(), "label '<' does not trip the guard");
        assertEquals("x < y", labelsById(lbl).get("A"));
    }
}
