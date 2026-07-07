package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Two coupled fixes:
///
/// (A) CONTRAST-DERIVED box labels — a label drawn ON a filled shape (flowchart/state node box,
/// sequence actor head) fills with the CONTRAST of the box colour (dark on a light box, white on a
/// dark one), never the page-theme `currentColor` that rendered white-on-light and vanished under a
/// dark theme. Edge/message labels sit on the page background and KEEP `currentColor`.
///
/// (B) AUTHOR node colours — a per-node trailing `#hex` (`A[Start] #22c55e`) and a header
/// `nodecolor=#hex` default. Fill resolution is per-node ?? header ?? built-in `#dbe4ff`; the label
/// contrasts against whichever wins, so any author colour stays legible.
class NodeColorTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // -- (A) contrast-derived labels ------------------------------------------------------------

    @Test
    void defaultBoxLabelUsesTheDarkContrastFillNotCurrentColor() {
        // The default box fill #dbe4ff is light → its label must be the DARK contrast value #000000.
        // No edge labels here, so NO glyph run may fall back to currentColor.
        String svg = Sirentide.render("flowchart\nA[Start] --> B[End]\n");
        assertTrue(count(svg, "fill=\"#000000\"") >= 2,
            "both node labels use the dark contrast fill for the light #dbe4ff box");
        assertEquals(0, count(svg, "fill=\"currentColor\""),
            "no node label falls back to currentColor (the invisible-on-dark bug)");
    }

    @Test
    void darkAuthorBoxGetsTheLightContrastLabel() {
        // A dark author box (#1a2233) flips the label to the LIGHT contrast value #ffffff.
        String svg = Sirentide.render("flowchart\nA[X] #1a2233\n");
        assertTrue(count(svg, "fill=\"#ffffff\"") >= 1,
            "a dark box gets a white label automatically");
        assertEquals(0, count(svg, "fill=\"#000000\""), "no dark label on a dark box");
    }

    @Test
    void flowchartEdgeLabelsStayCurrentColor() {
        // Node labels contrast; the EDGE label (yes) sits on the page background → keeps currentColor.
        String svg = Sirentide.render("flowchart\nA[Start] -->|yes| B[End]\n");
        assertTrue(count(svg, "fill=\"currentColor\"") == 1,
            "exactly the one edge label keeps currentColor");
        assertTrue(count(svg, "fill=\"#000000\"") >= 2, "both node labels contrast-fill dark");
    }

    // -- (B) author node colours ----------------------------------------------------------------

    @Test
    void perNodeColorAppliesToOnlyThatNodesBox() {
        String svg = Sirentide.render("flowchart\nA[Start] #22c55e --> B[End]\n");
        assertTrue(count(svg, "fill=\"#22c55e\"") >= 1, "A's rect takes the author colour");
        assertTrue(count(svg, "fill=\"#dbe4ff\"") >= 1, "B keeps the default box fill");
        // The green box (#22c55e, luminance just under threshold) gets a white contrast label.
        assertTrue(count(svg, "fill=\"#ffffff\"") >= 1, "A's label contrasts against its green box");
    }

    @Test
    void headerNodeColorRecolorsEveryDefaultNode() {
        String svg = Sirentide.render("flowchart nodecolor=#334155\nA[X] --> B[Y]\n");
        assertEquals(2, count(svg, "fill=\"#334155\""), "both default nodes take the header nodecolor");
        assertEquals(0, count(svg, "fill=\"#dbe4ff\""), "the built-in default is fully overridden");
    }

    @Test
    void perNodeColorBeatsHeaderNodeColor() {
        String svg = Sirentide.render("flowchart nodecolor=#334155\nA[X] #22c55e --> B[Y]\n");
        assertTrue(count(svg, "fill=\"#22c55e\"") >= 1, "A takes its per-node colour");
        assertTrue(count(svg, "fill=\"#334155\"") >= 1, "B falls back to the header nodecolor");
    }

    @Test
    void threeDigitPerNodeHexExpandsToSixDigit() {
        String svg = Sirentide.render("flowchart\nA[X] #f80 --> B[Y]\n");
        assertTrue(count(svg, "fill=\"#ff8800\"") >= 1, "a 3-digit per-node hex expands to 6-digit");
        assertEquals(0, count(svg, "#f80\""), "the short form is never emitted");
    }

    @Test
    void invalidTrailingTokenStillDropsTheLine() {
        // The parser-hardening pin stays green: only a hex colour may follow a closed delimiter; any
        // other trailing junk drops the WHOLE line (never a plausible-but-wrong node).
        String svg = Sirentide.render("flowchart\nA[Start] junk\n");
        assertTrue(svg.startsWith("<svg"), "never fails the bake");
        assertEquals(0, count(svg, "<rect"), "the junk-suffixed line drops → no node box");
        // Sanity: the same node WITH a valid colour token does register.
        String ok = Sirentide.render("flowchart\nA[Start] #22c55e\n");
        assertEquals(1, count(ok, "<rect"), "a valid colour suffix keeps the node");
    }

    @Test
    void bareIdWithHexIsAmbiguousAndDrops() {
        // A trailing #hex on a BARE (delimiter-less) id is ambiguous with a multi-word id → drop.
        String svg = Sirentide.render("flowchart\nA #22c55e\n");
        assertEquals(0, count(svg, "<rect"), "a bare id + hex drops the line");
        assertEquals(0, count(svg, "#22c55e"), "the ambiguous colour never reaches output");
        // A bare id ALONE still registers (unchanged behaviour).
        String bare = Sirentide.render("flowchart\nA\n");
        assertEquals(1, count(bare, "<rect"), "a bare id alone is still a node");
    }

    // -- sequence actor heads -------------------------------------------------------------------

    @Test
    void sequenceActorHeadLabelsContrastWithTheHeadFill() {
        // Default head fill #dbe4ff is light → head labels use the dark contrast; the MESSAGE label
        // stays currentColor (page background).
        String svg = Sirentide.render("sequence\nAlice ->> Bob : Request\n");
        assertTrue(count(svg, "fill=\"#000000\"") >= 2, "both actor-head labels contrast dark");
        assertTrue(count(svg, "fill=\"currentColor\"") >= 1, "the message label keeps currentColor");
    }

    @Test
    void sequenceHeaderNodeColorRecolorsTheHeads() {
        String svg = Sirentide.render("sequence nodecolor=#334155\nAlice ->> Bob : Request\n");
        assertEquals(2, count(svg, "fill=\"#334155\""), "both actor heads take the header nodecolor");
        assertTrue(count(svg, "fill=\"#ffffff\"") >= 2, "their labels flip to white on the dark heads");
    }

    // -- state diagram --------------------------------------------------------------------------

    @Test
    void stateHeaderNodeColorRecolorsStateBoxes() {
        // #1a2233 is distinct from the pseudostate disc fill (#334155), so the count isolates the two
        // real state boxes; the pseudostate disc keeps its own fixed fill.
        String svg = Sirentide.render("state nodecolor=#1a2233\n[*] --> Idle\nIdle --> Running : go\n");
        assertEquals(2, count(svg, "fill=\"#1a2233\""), "both state boxes take the header nodecolor");
        assertTrue(count(svg, "fill=\"#ffffff\"") >= 2, "state labels contrast white on the dark boxes");
    }

    @Test
    void statePerNodeColorRidesTheEndpointParse() {
        String svg = Sirentide.render("state\nIdle #22c55e --> Running\n");
        assertTrue(count(svg, "fill=\"#22c55e\"") >= 1, "the per-state #hex colours Idle's box");
        assertTrue(count(svg, "fill=\"#dbe4ff\"") >= 1, "Running keeps the default state box fill");
    }
}
