package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
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

    // -- layering -------------------------------------------------------------

    /// Re-derives the layer of each node from the parsed graph using the SAME longest-path rule the
    /// layout uses, so the assertions read against node ids rather than pixels.
    private static Map<String, Integer> layers(Flowchart fc) {
        List<FlowNode> nodes = fc.nodes();
        Map<String, Integer> idx = new java.util.HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            idx.put(nodes.get(i).id(), i);
        }
        int n = nodes.size();
        List<int[]> edges = new java.util.ArrayList<>();
        for (var e : fc.edges()) {
            edges.add(new int[] {idx.get(e.from()), idx.get(e.to())});
        }
        List<List<Integer>> adj = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new java.util.ArrayList<>());
        }
        for (int ei = 0; ei < edges.size(); ei++) {
            adj.get(edges.get(ei)[0]).add(ei);
        }
        boolean[] back = new boolean[edges.size()];
        int[] state = new int[n];
        for (int s = 0; s < n; s++) {
            if (state[s] == 0) {
                classify(s, edges, adj, state, back);
            }
        }
        List<List<Integer>> preds = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            preds.add(new java.util.ArrayList<>());
        }
        for (int ei = 0; ei < edges.size(); ei++) {
            if (!back[ei]) {
                preds.get(edges.get(ei)[1]).add(edges.get(ei)[0]);
            }
        }
        int[] layer = new int[n];
        java.util.Arrays.fill(layer, -1);
        Function<Integer, Integer> lf = new Function<>() {
            @Override public Integer apply(Integer v) {
                if (layer[v] >= 0) {
                    return layer[v];
                }
                layer[v] = 0;
                int best = 0;
                for (int u : preds.get(v)) {
                    best = Math.max(best, apply(u) + 1);
                }
                layer[v] = best;
                return best;
            }
        };
        Map<String, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            out.put(nodes.get(i).id(), lf.apply(i));
        }
        return out;
    }

    private static void classify(int u, List<int[]> edges, List<List<Integer>> adj,
                                 int[] state, boolean[] back) {
        state[u] = 1;
        for (int ei : adj.get(u)) {
            int v = edges.get(ei)[1];
            if (state[v] == 1) {
                back[ei] = true;
            } else if (state[v] == 0) {
                classify(v, edges, adj, state, back);
            }
        }
        state[u] = 2;
    }

    @Test
    void chainLayersZeroOneTwo() {
        Map<String, Integer> l = layers(parse("flowchart\nA --> B\nB --> C\n"));
        assertEquals(0, l.get("A"));
        assertEquals(1, l.get("B"));
        assertEquals(2, l.get("C"));
    }

    @Test
    void diamondUsesLongestPath() {
        // A→B, A→C, B→D, C→D  ⇒ D at layer 2 (longest path A→B→D), not layer 1.
        Map<String, Integer> l = layers(parse("flowchart\nA --> B\nA --> C\nB --> D\nC --> D\n"));
        assertEquals(0, l.get("A"));
        assertEquals(1, l.get("B"));
        assertEquals(1, l.get("C"));
        assertEquals(2, l.get("D"));
    }

    @Test
    @Timeout(5)
    void cycleTerminatesAndLaysOut() {
        // The key robustness test: A→B→C→A is a cycle. The back-edge C→A is excluded from layering
        // (still drawn), so layering terminates. Assert it returns, all 3 nodes present, and renders.
        String dsl = "flowchart\nA --> B\nB --> C\nC --> A\n";
        Flowchart fc = parse(dsl);
        assertEquals(3, fc.nodes().size(), "all 3 cycle nodes present");
        Map<String, Integer> l = layers(fc);
        assertEquals(0, l.get("A"));
        assertEquals(1, l.get("B"));
        assertEquals(2, l.get("C"));
        String svg = Sirentide.render(dsl);
        assertNotNull(svg);
        assertTrue(svg.startsWith("<svg"), "cycle still renders a valid svg");
        assertEquals(3, count(svg, "<rect"), "one box per node");
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
}
