package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Node + edge styling beyond fill (plan sirentide-node-edge-styling — the mermaid-parity gap):
///
/// (A) `classDef` now reads `stroke`, `stroke-width`, and `color` (label colour) in addition to
/// `fill`, and plumbs them onto a `class`-assigned node's box + label.
///
/// (B) A `linkStyle <index|default> stroke:…,stroke-width:…` directive colours/widths a specific
/// edge (by drawn-edge index) or every un-indexed edge (`default`).
///
/// SECURITY (the load-bearing half): every style value is validated at the PARSE boundary and fails
/// CLOSED — colours through the SHARED {@link com.sirentide.contract.SirentideContract} hex guard
/// (the same one per-node fills use), stroke-widths through a bounded finite non-negative gate. A
/// non-conforming value (a `red;onload=…` smuggle, a `url(#x)` reference, an oversized/negative
/// width) is DROPPED — the box/edge falls back to the default and nothing hostile reaches an SVG
/// attribute. The drop-tests below carry a POSITIVE control in the SAME fixture so they can never go
/// vacuously green (a mixed-fixture containment negative needs a positive instance beside it).
class NodeEdgeStylingTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // -- (A) classDef stroke / stroke-width / color ---------------------------------------------

    @Test
    void classDefStrokeWidthAndColorStyleTheAssignedNodeBox() {
        // The assigned node's rect takes the classDef fill AND a stroke border at the given width, and
        // its LABEL takes the `color:` override instead of the auto-contrast. Mutant (drop the new
        // classDef parsing) → no stroke/stroke-width attrs and the label reverts to #000000: RED.
        String svg = Sirentide.render(
            "flowchart TD\n  classDef deny fill:#fdecea,stroke:#ff0000,stroke-width:2px,color:#0000ff\n"
                + "  A[req] --> D[denied]\n  class D deny\n");
        assertTrue(svg.contains("fill=\"#fdecea\" stroke=\"#ff0000\" stroke-width=\"2\""),
            "the assigned node box carries fill + stroke + stroke-width together: " + svg);
        assertTrue(count(svg, "fill=\"#0000ff\"") >= 1,
            "the classDef color: overrides the node label colour");
    }

    @Test
    void bareStrokeWithoutWidthGetsTheDefaultBorderWidth() {
        // A `stroke:` with no `stroke-width:` still draws a visible 1px border (mermaid parity).
        String svg = Sirentide.render(
            "flowchart TD\n  classDef edge stroke:#334155\n  A[x] --> B[y]\n  class B edge\n");
        assertTrue(svg.contains("stroke=\"#334155\" stroke-width=\"1\""),
            "a bare stroke defaults to a 1px border width: " + svg);
    }

    @Test
    void classDefStrokeRidesANonRectShapePath() {
        // A non-rect node (a diamond `{…}`) is a <path>; the classDef stroke must plumb onto the path,
        // not just the rect case. Pins the path-side stroke plumbing (Path stroke widening).
        String svg = Sirentide.render(
            "flowchart TD\n  classDef hot stroke:#ff0000,stroke-width:2px\n  A{Q?} --> B[ok]\n  class A hot\n");
        assertTrue(svg.contains("<path d=\"M ") && svg.contains("stroke=\"#ff0000\" stroke-width=\"2\""),
            "the diamond node's <path> carries the classDef stroke: " + svg);
    }

    @Test
    void threeDigitClassStrokeExpandsToSixDigit() {
        // Stroke colours route through the SAME normalizeColor as fills → a 3-digit hex expands.
        String svg = Sirentide.render(
            "flowchart TD\n  classDef w stroke:#f00\n  A[x] --> B[y]\n  class B w\n");
        assertTrue(svg.contains("stroke=\"#ff0000\""), "a 3-digit class stroke expands to 6-digit");
        assertEquals(0, count(svg, "stroke=\"#f00\""), "the short form is never emitted");
    }

    @Test
    void unassignedNodesKeepNoBorder() {
        // Only the classed node gets a border; the control node stays borderless (byte-identical box).
        String svg = Sirentide.render(
            "flowchart TD\n  classDef edge stroke:#334155\n  A[x] --> B[y]\n  class B edge\n");
        // Exactly ONE node stroke (B); node A's rect has fill only. (The edge line stroke is separate,
        // asserted by count below: 1 node stroke + 1 edge line stroke = 2 total stroke attributes.)
        assertEquals(2, count(svg, "stroke=\""), "one node border + one edge line, nothing else");
    }

    // -- (B) linkStyle per-edge colour + width --------------------------------------------------

    @Test
    void linkStyleColorsAndWidthsASpecificEdgeByIndex() {
        // linkStyle 0 recolours+widens ONLY edge 0 (line AND its arrowhead); edge 1 keeps the default
        // #94a3b8/1.5. Mutant (drop linkStyle parsing) → edge 0 stays default: RED.
        String svg = Sirentide.render(
            "flowchart TD\n  A[a] --> B[b]\n  B --> C[c]\n  linkStyle 0 stroke:#ff0000,stroke-width:3px\n");
        assertTrue(svg.contains("stroke=\"#ff0000\" stroke-width=\"3\""),
            "edge 0's line takes the linkStyle colour + width: " + svg);
        assertTrue(svg.contains("<path d=\"M 46 108") && svg.contains("fill=\"#ff0000\""),
            "edge 0's arrowhead is recoloured to match its line");
        assertTrue(svg.contains("stroke=\"#94a3b8\" stroke-width=\"1.5\""),
            "edge 1 keeps the built-in default colour + width");
    }

    @Test
    void linkStyleDefaultAppliesToEveryUnindexedEdge() {
        // `default` colours every edge WITHOUT an index-specific override; the explicit index wins.
        String svg = Sirentide.render(
            "flowchart TD\n  A[a] --> B[b]\n  B --> C[c]\n"
                + "  linkStyle default stroke:#00ff00\n  linkStyle 0 stroke:#ff0000\n");
        assertTrue(svg.contains("stroke=\"#ff0000\""), "edge 0's index override wins over default");
        assertTrue(svg.contains("stroke=\"#00ff00\""), "edge 1 picks up the default linkStyle");
        assertEquals(0, count(svg, "stroke=\"#94a3b8\""),
            "no edge keeps the built-in colour once a default is set");
    }

    @Test
    void linkStyleIsNonVacuous() {
        // The SAME chart with vs without the linkStyle differs — proof the directive is doing the work
        // (removing linkStyle parsing collapses the two, failing this).
        String base = "flowchart TD\n  A[a] --> B[b]\n";
        String styled = Sirentide.render(base + "  linkStyle 0 stroke:#ff0000,stroke-width:4px\n");
        String plain = Sirentide.render(base);
        assertTrue(styled.contains("stroke=\"#ff0000\" stroke-width=\"4\""), "styled edge is red/4px");
        assertEquals(0, count(plain, "stroke=\"#ff0000\""), "the un-styled chart has no red edge");
    }

    // -- SECURITY: malformed values are dropped (fail-closed) at the parse boundary ---------------

    @Test
    void malformedClassDefStrokeAndWidthAreDroppedNoInjection() {
        // A hostile stroke (`red;onload=alert(1)` — not a hex colour) and an oversized width (`9e9px`,
        // finite but past the bound) MUST both be dropped: node D falls back to a borderless default
        // box, and NONE of the hostile tokens reach the output. POSITIVE control in the same fixture:
        // node E's VALID classDef stroke DOES draw — so a green pass means the guard drops the bad one
        // while still admitting the good one (not a blanket "styling is off").
        String svg = Sirentide.render(
            "flowchart TD\n"
                + "  classDef bad stroke:red;onload=alert(1),stroke-width:9e9px\n"
                + "  classDef ok stroke:#00aa00,stroke-width:2px\n"
                + "  A[a] --> D[d]\n  D --> E[e]\n  class D bad\n  class E ok\n");
        assertTrue(svg.startsWith("<svg"), "the malformed classDef never fails the bake");
        // Nothing hostile smuggled through into ANY attribute.
        assertTrue(!svg.contains("onload"), "no onload handler in output: " + svg);
        assertTrue(!svg.contains("alert"), "no alert payload in output");
        assertTrue(!svg.contains(">red") && !svg.contains("\"red"), "the bare 'red' colour never emitted");
        assertTrue(!svg.contains("9e9") && !svg.contains("9000000000"),
            "the oversized stroke-width never reaches an attribute");
        // The ONLY strokes present are: node E's valid border (#00aa00) + the two edge lines (#94a3b8).
        // Node D contributed ZERO stroke attributes (the malformed classDef was fully dropped).
        assertTrue(svg.contains("stroke=\"#00aa00\" stroke-width=\"2\""),
            "the VALID control classDef still styles node E (guard is not a blanket off-switch): " + svg);
        assertEquals(0, count(svg, "stroke=\"red"), "no raw stroke value leaked");
        // One node border (E) + two edge lines = 3 total stroke attributes; D adds none.
        assertEquals(3, count(svg, "stroke=\""), "the dropped classDef added no stroke attribute");
    }

    @Test
    void malformedLinkStyleValuesAreDroppedNoInjection() {
        // A `url(#x)` reference (not a hex colour) and a NEGATIVE width MUST both drop → edge 0 keeps
        // the built-in colour/width and no `url(` leaks. POSITIVE control: edge 1's VALID linkStyle
        // does colour it, so the guard drops only the bad directive.
        String svg = Sirentide.render(
            "flowchart TD\n  A[a] --> B[b]\n  B --> C[c]\n"
                + "  linkStyle 0 stroke:url(#x),stroke-width:-4\n"
                + "  linkStyle 1 stroke:#00aa00,stroke-width:2px\n");
        assertTrue(svg.startsWith("<svg"), "the malformed linkStyle never fails the bake");
        assertTrue(!svg.contains("url("), "no url() reference smuggled into output: " + svg);
        assertTrue(svg.contains("stroke=\"#00aa00\" stroke-width=\"2\""),
            "the VALID control linkStyle still styles edge 1 (guard is not a blanket off-switch)");
        // Edge 0 fell back to the built-in default (its line + arrowhead), proving the bad override dropped.
        assertTrue(svg.contains("stroke=\"#94a3b8\" stroke-width=\"1.5\""),
            "edge 0 keeps the built-in colour/width after its hostile linkStyle is dropped");
    }

    @Test
    void nonFiniteStrokeWidthsAreDroppedToTheDefaultBorder() {
        // The parse contract promises FINITE-only widths. NaN / Infinity / -Infinity / 1e400 (which
        // overflows a double to +Inf) must each be dropped at the parse boundary — the value never
        // reaches an attribute — while the valid stroke falls back to the DEFAULT border width (1).
        // Pins the finite-only guard so it cannot silently regress (Lattice #6-review finding 3).
        for (String w : new String[] {"NaN", "Infinity", "-Infinity", "1e400"}) {
            String svg = Sirentide.render(
                "flowchart TD\n"
                    + "  classDef c stroke:#00aa00,stroke-width:" + w + "px\n"
                    + "  A[a] --> B[b]\n  class A c\n");
            assertTrue(svg.startsWith("<svg"), "non-finite width never fails the bake: " + w);
            String lower = svg.toLowerCase(java.util.Locale.ROOT);
            assertTrue(!lower.contains("nan") && !lower.contains("infinity") && !lower.contains("1e400"),
                "the non-finite width '" + w + "' never reaches an attribute: " + svg);
            assertTrue(svg.contains("stroke=\"#00aa00\" stroke-width=\"1\""),
                "a valid stroke with a dropped non-finite width falls back to the default border width: " + svg);
        }
    }

    @Test
    void styledBorderParticipatesInPlayThroughEmphasis() {
        // A styled node border must DIM in future frames and ACCENT + thicken in its active frame,
        // exactly like a <line> edge stroke — not stay a constant bright border while the fill/label
        // change (the bug: appendStroke ignoring the frame state, Lattice #6-review finding 1).
        // Emphasis.ACCENT = #e8590c, ACTIVE_WIDTH_MULT = 2 → a 2px border thickens to 4 when active.
        String dsl = "flowchart TD\n"
            + "  classDef hot stroke:#ff0000,stroke-width:2px\n"
            + "  A[a] --> B[b]\n  class A hot\n";
        // Static render carries the exact authored border, UN-emphasized (byte-identical baseline).
        assertTrue(Sirentide.render(dsl).contains("stroke=\"#ff0000\" stroke-width=\"2\""),
            "the static render carries the exact authored border");
        java.util.List<String> frames = Sirentide.renderFrames(dsl);
        // Active frame for node A: border promoted to the play-through accent AND thickened 2x.
        assertTrue(frames.stream().anyMatch(f -> f.contains("stroke=\"#e8590c\" stroke-width=\"4\"")),
            "some frame accents + thickens node A's border in its active step: " + frames);
        // FUTURE dimming pinned to the EXACT tint (Lattice re-review, seq 216): #ff0000
        // lightened by the frame de-emphasis is #ff9e9e at the authored 2px. Scoped to
        // NODE A's OWN anchor group (the frame legitimately carries the accent on the
        // active EDGE) — some frame's A-group must carry the future tint and NEITHER the
        // bright authored border NOR the active accent. A mutant that accents ACTIVE but
        // leaves FUTURE bright red now fails here (the old not-all-frames-bright
        // assertion was implied by the active assertion and pinned nothing).
        assertTrue(frames.stream().map(f -> nodeGroup(f, "a")).anyMatch(g ->
                g.contains("stroke=\"#ff9e9e\" stroke-width=\"2\"")
                && !g.contains("stroke=\"#ff0000\"")
                && !g.contains("stroke=\"#e8590c\"")),
            "some frame dims node A's OWN border to the exact FUTURE tint #ff9e9e/2px with"
                + " no authored-bright or active stroke in A's group: " + frames);
    }

    /// A node's own anchor group within one frame — the semantic-anchor contract
    /// (data-sirentide-role/id, where id is the LABEL-derived anchor id) makes
    /// per-node frame assertions scope-able.
    private static String nodeGroup(String svg, String nodeId) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "<g data-sirentide-role=\"node\" data-sirentide-id=\"" + nodeId + "\"[^>]*>.*?</g>",
            java.util.regex.Pattern.DOTALL).matcher(svg);
        return m.find() ? m.group() : "";
    }

    @Test
    void pathShapedNodeBorderParticipatesInPlayThroughEmphasis() {
        // Same contract for a PATH-outlined shape (diamond): the classDef border on a
        // decision node must dim to the exact FUTURE tint and accent+thicken when active
        // — the original review asked for frame regressions on BOTH rect and path frames
        // (Lattice re-review, seq 216: the branch had only a static diamond assertion).
        String dsl = "flowchart TD\n"
            + "  classDef hot stroke:#ff0000,stroke-width:2px\n"
            + "  A[a] --> D{d}\n  class D hot\n";
        assertTrue(Sirentide.render(dsl).contains("stroke=\"#ff0000\" stroke-width=\"2\""),
            "the static render carries the exact authored diamond border");
        java.util.List<String> frames = Sirentide.renderFrames(dsl);
        assertTrue(frames.stream().map(f -> nodeGroup(f, "d")).anyMatch(g ->
                g.contains("stroke=\"#e8590c\" stroke-width=\"4\"")),
            "some frame accents + thickens the diamond's border in its active step: " + frames);
        assertTrue(frames.stream().map(f -> nodeGroup(f, "d")).anyMatch(g ->
                g.contains("stroke=\"#ff9e9e\" stroke-width=\"2\"")
                && !g.contains("stroke=\"#ff0000\"")
                && !g.contains("stroke=\"#e8590c\"")),
            "some frame dims the diamond's OWN border to the exact FUTURE tint #ff9e9e/2px"
                + " with no authored-bright or active stroke in D's group: " + frames);
    }
}
