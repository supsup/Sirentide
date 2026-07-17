package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.EdgeStyle;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.Flowchart;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;

/// Flowchart EDGE VARIANTS (plan flowchart-edge-types): the mermaid edge-operator vocabulary beyond
/// the plain `-->` solid arrow — `---` open link, `-.->` dotted arrow, `-.-` dotted open, `==>` thick
/// arrow, `===` thick open — each decoding to a ({@link EdgeStyle}, arrow) pair on the {@link FlowEdge}
/// and rendering per-style (dotted = short segments, thick = wider stroke, open = no arrowhead). Tests
/// the operator scan + LONGEST-FIRST disambiguation (the correctness crux), the label/chain carry, the
/// malformed→inert degrade, and the per-style emission.
class FlowchartEdgeTypeTest {

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

    private static FlowEdge only(String dsl) {
        Flowchart fc = parse(dsl);
        assertEquals(1, fc.edges().size(), "exactly one edge for: " + dsl);
        return fc.edges().get(0);
    }

    // -- parse: each operator maps to the right (style, arrow) --------------------------------------

    @Test
    void solidArrowIsSolidWithHead() {
        FlowEdge e = only("flowchart\nA --> B\n");
        assertEquals(EdgeStyle.SOLID, e.style());
        assertTrue(e.arrow(), "`-->` carries an arrowhead");
    }

    @Test
    void openLinkIsSolidNoHead() {
        FlowEdge e = only("flowchart\nA --- B\n");
        assertEquals(EdgeStyle.SOLID, e.style());
        assertFalse(e.arrow(), "`---` is an open link — no arrowhead");
    }

    @Test
    void dottedArrowIsDottedWithHead() {
        FlowEdge e = only("flowchart\nA -.-> B\n");
        assertEquals(EdgeStyle.DOTTED, e.style());
        assertTrue(e.arrow(), "`-.->` is a dotted arrow");
    }

    @Test
    void dottedOpenIsDottedNoHead() {
        FlowEdge e = only("flowchart\nA -.- B\n");
        assertEquals(EdgeStyle.DOTTED, e.style());
        assertFalse(e.arrow(), "`-.-` is a dotted open link");
    }

    @Test
    void thickArrowIsThickWithHead() {
        FlowEdge e = only("flowchart\nA ==> B\n");
        assertEquals(EdgeStyle.THICK, e.style());
        assertTrue(e.arrow(), "`==>` is a thick arrow");
    }

    @Test
    void thickOpenIsThickNoHead() {
        FlowEdge e = only("flowchart\nA === B\n");
        assertEquals(EdgeStyle.THICK, e.style());
        assertFalse(e.arrow(), "`===` is a thick open link");
    }

    // -- the DISAMBIGUATION crux (longest/most-specific first) --------------------------------------

    @Test
    void dottedArrowIsNotMisscannedAsSolidArrowWithStrayDot() {
        // The classic bug: `-.->` read as `-->` preceded by a stray `.`, minting a phantom `.` node
        // and a SOLID edge. Assert it is ONE dotted-arrow A->B with NO phantom node.
        Flowchart fc = parse("flowchart\nA -.-> B\n");
        assertEquals(2, fc.nodes().size(), "exactly A and B — no phantom node from a mis-scanned `.`");
        FlowEdge e = fc.edges().get(0);
        assertEquals("A", e.from());
        assertEquals("B", e.to());
        assertEquals(EdgeStyle.DOTTED, e.style(), "not mis-scanned as SOLID `-->`");
        assertTrue(e.arrow());
    }

    @Test
    void thickArrowIsDistinctFromSolidArrow() {
        assertEquals(EdgeStyle.THICK, only("flowchart\nA ==> B\n").style());
        assertEquals(EdgeStyle.SOLID, only("flowchart\nA --> B\n").style());
    }

    @Test
    void openLinkIsDistinctFromSolidArrow() {
        assertFalse(only("flowchart\nA --- B\n").arrow(), "`---` is open");
        assertTrue(only("flowchart\nA --> B\n").arrow(), "`-->` has a head");
    }

    @Test
    void twoDashNodeIdIsNotAnOpenLink() {
        // `A--B` (two dashes, no spaces) is NOT the three-dash `---` open link — it stays a lone node
        // id exactly as before this feature (a `---` needs three consecutive dashes). Byte-safe.
        Flowchart fc = parse("flowchart\nA--B\n");
        assertEquals(1, fc.nodes().size(), "`A--B` is one node id, not an edge");
        assertEquals(0, fc.edges().size());
    }

    // -- operator + label + chain ------------------------------------------------------------------

    @Test
    void variantsCarryPerHopStyleAcrossAChain() {
        // A mixed chain: each hop keeps its OWN operator's (style, arrow) + its own label.
        Flowchart fc = parse("flowchart\nA -.->|no| B ==>|x| C --- D\n");
        assertEquals(3, fc.edges().size());
        FlowEdge e0 = fc.edges().get(0);
        assertEquals(EdgeStyle.DOTTED, e0.style());
        assertTrue(e0.arrow());
        assertEquals("no", e0.label());
        FlowEdge e1 = fc.edges().get(1);
        assertEquals(EdgeStyle.THICK, e1.style());
        assertTrue(e1.arrow());
        assertEquals("x", e1.label());
        FlowEdge e2 = fc.edges().get(2);
        assertEquals(EdgeStyle.SOLID, e2.style());
        assertFalse(e2.arrow(), "the final `---` hop is an open link");
        assertEquals(null, e2.label());
    }

    @Test
    void openLinkStillCarriesALabel() {
        FlowEdge e = only("flowchart\nA ---|link| B\n");
        assertEquals(EdgeStyle.SOLID, e.style());
        assertFalse(e.arrow());
        assertEquals("link", e.label(), "a label on an open link still parses");
    }

    // -- malformed -> inert -------------------------------------------------------------------------

    @Test
    void garbledOperatorMintsNoEdge() {
        // A bare `-.` (no closing dash) is not a valid operator: the line has NO top-level edge, so it
        // degrades to a lone-node declaration and mints NO edge — never throws (DESIGN §6).
        Flowchart fc = parse("flowchart\nA -. B\nC --> D\n");
        assertEquals(1, fc.edges().size(), "only the well-formed C->D edge; the `-.` line minted none");
        assertEquals("C", fc.edges().get(0).from());
        assertEquals("D", fc.edges().get(0).to());
    }

    @Test
    void variantWithUnterminatedLabelDropsWholeLine() {
        // A missing closing pipe on a variant operator drops the whole line (never half-drawn), the
        // rest parse — the same malformed→inert rule the plain `-->` label uses.
        Flowchart fc = parse("flowchart\nA -.->|no B\nA --> C\n");
        assertEquals(1, fc.edges().size(), "the unterminated-pipe dotted line drops");
        assertEquals("C", fc.edges().get(0).to());
    }

    // -- layout: per-style emission ----------------------------------------------------------------

    @Test
    void thickEdgeHasWiderStrokeThanSolid() {
        String thick = Sirentide.render("flowchart\nA[x] ==> B[y]\n");
        String solid = Sirentide.render("flowchart\nA[x] --> B[y]\n");
        assertTrue(thick.contains("stroke-width=\"3\""), "a thick edge draws at width 3:\n" + thick);
        assertFalse(solid.contains("stroke-width=\"3\""), "a solid edge is not width 3");
        assertTrue(solid.contains("stroke-width=\"1.5\""), "a solid edge draws at width 1.5");
    }

    /// MUTANT SENTINEL (receipt #6): a dotted edge is drawn as MANY short `<line>` pieces; an open
    /// solid link is exactly ONE line. Making the DOTTED style emit a single solid line (the mutant)
    /// collapses the count to 1 and reds THIS test.
    @Test
    void dottedEdgeEmitsMultipleShortSegments() {
        String dotted = Sirentide.render("flowchart\nA[x] -.- B[y]\n");
        String solid = Sirentide.render("flowchart\nA[x] --- B[y]\n");
        assertEquals(1, count(solid, "<line"), "an open solid link is a single line");
        assertTrue(count(dotted, "<line") > 1,
            "a dotted edge is multiple dashed segments, got " + count(dotted, "<line") + ":\n" + dotted);
    }

    @Test
    void anOverCapDottedEdgeCollapsesToOneSolidLine() {
        // Robustness plan fe8c5bbc #1: a dotted segment emits one <line> per EDGE_DASH+GAP stride, so
        // a canvas-spanning dotted edge (× up to MAX_EDGES) floods the shape list before the 5 MB emit
        // cap can fire. Past MAX_DASH_PIECES a segment draws ONE solid line. Forcing the cap tiny (a
        // genuinely canvas-spanning edge would need a giant graph), the SAME dotted-open edge that is
        // many <line> pieces above collapses to exactly one — mutation-surviving vs the piece test.
        int saved = com.sirentide.layout.FlowchartLayout.MAX_DASH_PIECES;
        com.sirentide.layout.FlowchartLayout.MAX_DASH_PIECES = 2;
        try {
            String svg = Sirentide.render("flowchart\nA[x] -.- B[y]\n");
            assertEquals(1, count(svg, "<line"),
                "an over-cap dotted edge collapses to one solid line, not many pieces: " + count(svg, "<line"));
        } finally {
            com.sirentide.layout.FlowchartLayout.MAX_DASH_PIECES = saved;
        }
    }

    @Test
    void openEdgeEmitsNoArrowheadPath() {
        // An arrowhead is a <path> filled with the edge stroke colour (#94a3b8). An OPEN link emits
        // none; an arrow edge emits exactly one. (Lines use stroke=, not fill=, so this counts heads.)
        String open = Sirentide.render("flowchart\nA[x] --- B[y]\n");
        String arrow = Sirentide.render("flowchart\nA[x] --> B[y]\n");
        assertEquals(0, count(open, "fill=\"#94a3b8\""), "an open link has NO arrowhead path");
        assertEquals(1, count(arrow, "fill=\"#94a3b8\""), "a solid arrow has exactly one arrowhead path");
    }

    @Test
    void solidArrowRenderIsUnchanged() {
        // Zero-behaviour-change pin at the render level: an explicit SOLID+arrow edge still draws at
        // width 1.5 with exactly one arrowhead (the byte-golden covers the full bake).
        String svg = Sirentide.render("flowchart\nA[x] --> B[y]\n");
        assertTrue(svg.contains("stroke-width=\"1.5\""), "solid edge still width 1.5");
        assertEquals(1, count(svg, "fill=\"#94a3b8\""), "solid edge still one arrowhead");
    }
}
