package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// Sirentide's first real diagram: a pie renders DSL → IR → arithmetic layout → contract-clean
/// SVG wedges, end to end.
class PieTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void oneWedgeAndOneLabelPerPositiveSlice() {
        String svg = Sirentide.render("""
            pie
              "Reviews" : 40
              "Builds"  : 30
              "Docs"    : 30
            """);
        assertEquals(6, count(svg, "<path"), "3 wedges + 3 label paths");
        // Labels are contrast-aware now (black OR white by slice luminance), not hardcoded white:
        // #4e79a7 (dark) → white; #f28e2b, #59a14f (light) → black. So both fills appear.
        assertTrue(count(svg, "fill=\"#ffffff\"") >= 1 && count(svg, "fill=\"#000000\"") >= 1,
            "labels use contrast-picked black AND white, not a single hardcoded colour");
        assertEquals(0, count(svg, "<line"), "no thin slices here → no leader lines");
        assertTrue(svg.contains("viewBox="), "has a viewBox");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    // Contract-cleanliness is now enforced by ContainmentTest's allowlist guard (see that class),
    // not a per-type denylist here.

    @Test
    void singleSliceRendersAFullDisc() {
        String svg = Sirentide.render("pie\n \"All\" : 5\n");
        // Full-circle form draws two arcs (no degenerate centre wedge); the label adds no arcs.
        assertEquals(2, count(svg, " A "), "disc drawn as two semicircle arcs");
        assertEquals(1, count(svg, "fill=\"#ffffff\""), "one label for the one slice");
    }

    @Test
    void malformedRowsDegradeAndAreSkipped() {
        String svg = Sirentide.render("""
            pie
              "good" : 10
              this line has no colon
              "bad"  : notanumber
            """);
        assertEquals(1, count(svg, "fill=\"#ffffff\""), "one label => one valid slice; bad rows skipped");
    }

    @Test
    void thinSliceWhoseOutsideLabelEmptiesDrawsNoDanglingLeader() {
        // A 1% thin slice whose outside label has only ~2px of room ellipsizes to EMPTY (the
        // geometry-escape honest outcome). Its leader line must be DROPPED too — a leader pointing at
        // an empty label is dangling residue. The three wedges (arcs) are unchanged.
        String svg = Sirentide.render(
            "pie\n\"quarter\" : 25\n\"right outside label that should clip\" : 1\n\"rest\" : 74");
        assertEquals(3, count(svg, " A "), "3 wedges drawn (one arc each), unchanged");
        assertEquals(0, count(svg, "<line"),
            "the emptied outside label draws NO leader (dangling residue dropped)");
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "pie\n \"A\" : 3\n \"B\" : 7\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    // -- thin-slice outside label DROP is named, not silent (plan 64cf1bae) --------------------------
    // A very thin slice's outside leader-label has no horizontal room, so PieLayout drops the leader
    // AND the text (a coloured wedge with no visible name). renderWithDiagnostics now NAMES the
    // dropped slice on an OK-with-caveat and points at `pie legend` — WITHOUT touching the SVG bytes.
    private static final String DROP_DSL =
        "pie\n\"quarter\" : 25\n\"right outside label that should clip\" : 1\n\"rest\" : 74";

    @Test
    void aDroppedThinSliceLabelIsNamedOnAnOkCaveatSuggestingLegend() {
        RenderResult r = Sirentide.renderWithDiagnostics(DROP_DSL);
        assertEquals(Outcome.OK, r.diagnostics().outcome(), "the bake still SUCCEEDED (OK, with caveat)");
        String msg = r.diagnostics().message();
        assertTrue(msg.contains("right outside label that should clip"),
            "the caveat NAMES the dropped slice: " + msg);
        assertTrue(msg.contains("pie legend"), "the caveat points at `pie legend`: " + msg);
        assertTrue(r.diagnostics().detail().contains("right outside label that should clip"),
            "the detail crumb records the drop: " + r.diagnostics().detail());
    }

    @Test
    void theDropCaveatDoesNotAlterTheSvgBytes() {
        // (b) byte-identical SVG for the exact input — the caveat rides ALONGSIDE, never in the bake.
        assertEquals(Sirentide.render(DROP_DSL), Sirentide.renderWithDiagnostics(DROP_DSL).svg(),
            "renderWithDiagnostics(drop).svg() is byte-identical to render(drop)");
    }

    @Test
    void legendModeShowsEveryLabelSoThereIsNoCaveat() {
        // The answer the caveat suggests: `pie legend` puts EVERY label (including the would-be-dropped
        // one) in the side key, so no label is lost and the outcome is a clean OK.
        RenderResult r = Sirentide.renderWithDiagnostics("pie legend\n" + DROP_DSL.substring(4));
        assertEquals(Outcome.OK, r.diagnostics().outcome(), "legend renders clean");
        assertFalse(r.diagnostics().message().contains("dropped"),
            "legend mode drops nothing, so no drop caveat: " + r.diagnostics().message());
    }

    @Test
    void aComfortablePieCarriesNoDropCaveat() {
        // Non-vacuity: an all-comfortable pie (every slice gets an inside label) must NOT trip the
        // caveat — the signal fires only on an actual drop.
        RenderResult r = Sirentide.renderWithDiagnostics(
            "pie\n\"Reviews\" : 40\n\"Builds\" : 25\n\"Docs\" : 20\n\"Design\" : 15");
        assertEquals("Rendered successfully.", r.diagnostics().message(),
            "a pie that drops nothing keeps the plain OK message");
    }

    @Test
    void unrecognizedDslDegradesToEmptyShell() {
        String svg = Sirentide.render("flowchart TD; A-->B");
        assertEquals(0, count(svg, "<path"), "unknown diagram type degrades to an empty shell");
        assertTrue(svg.startsWith("<svg"), "still a valid svg");
    }
}
