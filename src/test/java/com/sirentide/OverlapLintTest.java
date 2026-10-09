package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderOptions;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.layout.Anchor;
import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.OverlapLint;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// The OPT-IN text-overlap lint (item 4 of Fixpoint's ruling on the cli-math-batch rebuild).
///
/// Its positive is Sirentide-native: a quadrant chart whose two close points put their labels on top
/// of each other (BrewShot capture: "Alpha point" and "Beta point" drawn through one another). It
/// fails with the switch and passes without it. Clean gallery fixtures pass with the switch.
///
/// The two other positives the ruling asked for could NOT be constructed honestly and are not faked:
/// an xychart line chart draws no per-point data labels, and its legend (`xychart line legend`) sits
/// in its own column with the canvas grown to fit every key row, so no crowding measured (2, 8, 12,
/// 16 and 20 series; 12 to 60 categories) put legend text on chart text; and a caption is laid out
/// GAP px below the diagram's own height, with journey's title ellipsized to the canvas, so no title
/// plus long caption measured at the narrowest canvas collided.
class OverlapLintTest {

    static final String QUADRANT_COLLIDING =
        "quadrantChart\n    title Q\n    Alpha point: [0.30, 0.60]\n    Beta point: [0.33, 0.61]\n";

    static final String FLOWCHART_EDGES =
        "flowchart TD\n  A[Start] --- B[Link]\n  B -.-> C[Retry]\n  C -.- D[Idle]\n"
            + "  D ==> E[Ship]\n  E === F[Done]\n  A -.->|maybe| D\n  C ==>|force| F\n";

    static final String FLOWCHART =
        "flowchart TD\n  A[Start] --> B{Ready?}\n  B -->|yes| C[Build] --> D[Ship]\n"
            + "  B -->|no| E[Fix] --> A\n";

    static final String SEQUENCE_BLOCKS =
        "sequence\n  Alice ->> Bob : hello\n  alt is available\n    Bob -->> Alice : yes\n"
            + "    loop every retry\n      Alice ->> Bob : ping\n    end\n"
            + "  else is busy\n    Bob -->> Alice : later\n  end\n"
            + "  par to Bob\n    Alice ->> Bob : a\n  and to Carol\n    Alice ->> Carol : b\n  end\n";

    private static final RenderOptions LINT = new RenderOptions(true);

    @Test
    void collidingQuadrantPointLabelsFailTheLint() {
        RenderResult r = Sirentide.renderWithDiagnostics(QUADRANT_COLLIDING, null, LINT);
        assertEquals(Outcome.OK, r.diagnostics().outcome(), "a lint finding is a caveat, not a failed bake");
        assertTrue(r.diagnostics().detail().contains("text overlap: point:Alphapoint x point:Betapoint"),
            r.diagnostics().detail());
        assertTrue(r.diagnostics().message().contains("Lint: 1 pair"), r.diagnostics().message());
    }

    @Test
    void withoutTheSwitchTheSameChartCarriesNoLintAndTheDefaultIsUnchanged() {
        RenderResult off = Sirentide.renderWithDiagnostics(QUADRANT_COLLIDING);
        assertFalse(off.diagnostics().detail().contains("text overlap"), off.diagnostics().detail());
        assertEquals(off, Sirentide.renderWithDiagnostics(QUADRANT_COLLIDING, null, RenderOptions.DEFAULT),
            "DEFAULT options are the pre-options render, record for record");
        assertEquals(off.svg(), Sirentide.renderWithDiagnostics(QUADRANT_COLLIDING, null, LINT).svg(),
            "the lint never changes the SVG");
    }

    @Test
    void cleanGalleryFiguresPassTheLint() {
        for (String dsl : List.of(FLOWCHART, FLOWCHART_EDGES)) {
            RenderResult r = Sirentide.renderWithDiagnostics(dsl, null, LINT);
            assertEquals("Rendered successfully.", r.diagnostics().message(), dsl);
            assertEquals("", r.diagnostics().detail(), dsl);
        }
    }

    @Test
    void edgeLabelsOnTheirStrokesAreNotFindings() {
        // flowchart-edges carries two edge labels ("maybe", "force") drawn across their own strokes.
        // The lint compares text with text only, so a label on its line is never a finding.
        RenderResult r = Sirentide.renderWithDiagnostics(FLOWCHART_EDGES, null, LINT);
        assertTrue(r.svg().contains("data-sirentide-role=\"edge\""), "control: the fixture has edges");
        assertFalse(r.diagnostics().detail().contains("text overlap"), r.diagnostics().detail());
    }

    @Test
    void theSequenceBlocksGoldenGrazesAndIsReported() {
        // MEASURED, not chosen: of the 32 golden fixtures, this is the one the lint flags. The `loop`
        // frame's label "every retry" sits 1.8 px into the "ping" message label below it, which the
        // BrewShot capture shows as descenders touching ascenders. A real (small) finding, kept as a
        // second native positive rather than tuned away.
        RenderResult r = Sirentide.renderWithDiagnostics(SEQUENCE_BLOCKS, null, LINT);
        assertTrue(r.diagnostics().detail().contains("x message:Alice-Bob-1"), r.diagnostics().detail());
        assertFalse(Sirentide.renderWithDiagnostics(SEQUENCE_BLOCKS).diagnostics().detail().contains("text overlap"));
    }

    /// The golden fixtures the lint is KNOWN to flag. Exactly one, and it is a real finding, not a
    /// false positive: `sequence-blocks` (loop label "every retry" 1.8 px into message label "ping").
    /// KNOWN FINDING, FILED FOR FOLLOW-UP: the layout and the golden are deliberately left as they are
    /// on this branch; the fix belongs to a layout change that regenerates the golden under review.
    /// When that lands, delete the entry here and the CLI pin in CliLintOverlapTest goes red, which is
    /// the signal to retire it too. Adding a name here hides a finding, so it needs the same review.
    static final Set<String> KNOWN_LINT_FINDINGS = Set.of("sequence-blocks");

    @Test
    void everyGoldenFixtureExceptTheKnownFindingPassesTheLint() {
        Map<String, String> fixtures = GoldenSvgTest.fixtures();
        assertEquals(32, fixtures.size(), "control: the sweep reaches the whole golden gallery");
        assertTrue(fixtures.keySet().containsAll(KNOWN_LINT_FINDINGS), "every exclusion names a real fixture");
        int checked = 0;
        for (Map.Entry<String, String> e : fixtures.entrySet()) {
            String detail = Sirentide.renderWithDiagnostics(e.getValue(), null, LINT).diagnostics().detail();
            if (KNOWN_LINT_FINDINGS.contains(e.getKey())) {
                // Excluded from the CLEAN assertion, never from the check: it must still fire, so a
                // stale exclusion cannot sit here hiding nothing.
                assertTrue(detail.contains("text overlap"), e.getKey() + " no longer fires; retire the exclusion");
                continue;
            }
            assertFalse(detail.contains("text overlap"), e.getKey() + ": " + detail);
            checked++;
        }
        assertEquals(fixtures.size() - KNOWN_LINT_FINDINGS.size(), checked);
    }

    // --- the rule, on synthetic geometry ------------------------------------------------------

    private static GlyphRun box(double x0, double y0, double x1, double y1) {
        return new GlyphRun("M" + x0 + " " + y0 + " L" + x1 + " " + y0 + " L" + x1 + " " + y1 + " Z", "#000000");
    }

    private static Group group(String id, Shape... members) {
        return new Group(new Anchor(SirentideRole.NODE, id, 0), List.of(members));
    }

    @Test
    void twoRunsInOneGroupAreOneElementAndNeverCompared() {
        LaidOut laid = new LaidOut(100, 100, List.of(group("a", box(0, 0, 20, 20), box(5, 5, 25, 25))));
        assertEquals(List.of(), OverlapLint.findings(laid));
    }

    @Test
    void runsInDifferentGroupsAreCompared() {
        LaidOut laid = new LaidOut(100, 100, List.of(group("a", box(0, 0, 20, 20)), group("b", box(5, 5, 25, 25))));
        List<OverlapLint.Finding> f = OverlapLint.findings(laid);
        assertEquals(1, f.size());
        assertEquals("node:a", f.get(0).a());
        assertEquals("node:b", f.get(0).b());
        assertEquals(15.0, f.get(0).w(), 1e-9);
    }

    @Test
    void twoUngroupedRunsAreDistinctOwners() {
        LaidOut laid = new LaidOut(100, 100, List.of(box(0, 0, 20, 20), box(5, 5, 25, 25)));
        assertEquals(1, OverlapLint.findings(laid).size());
    }

    @Test
    void textOverAShapeIsNotAFinding() {
        LaidOut laid = new LaidOut(100, 100, List.of(new Rect(0, 0, 50, 50, "#ffffff"), group("a", box(5, 5, 25, 25))));
        assertEquals(List.of(), OverlapLint.findings(laid));
    }

    @Test
    void aGrazeUnderOnePixelIsTouchingNotColliding() {
        LaidOut laid = new LaidOut(100, 100, List.of(box(0, 0, 20, 20), box(0, 19.5, 20, 40)));
        assertEquals(List.of(), OverlapLint.findings(laid), "0.5 px of overlap is under MIN_OVERLAP_PX");
        LaidOut deeper = new LaidOut(100, 100, List.of(box(0, 0, 20, 20), box(0, 18.5, 20, 40)));
        assertEquals(1, OverlapLint.findings(deeper).size(), "1.5 px is a finding");
    }

    @Test
    void theBoundsOfARealGlyphRunCoverItsPoints() {
        String svg = Sirentide.render("flowchart TD\n  A[Start]\n");
        int at = svg.indexOf("<path d=\"");
        assertTrue(at >= 0, "control: a glyph path exists");
        String d = svg.substring(at + 9, svg.indexOf('"', at + 9));
        LaidOut laid = new LaidOut(100, 100, List.of(new GlyphRun(d, "#000000"), new GlyphRun(d, "#000000")));
        List<OverlapLint.Finding> f = OverlapLint.findings(laid);
        assertEquals(1, f.size(), "a run fully overlaps an identical copy of itself");
        assertNotNull(f.get(0));
        assertTrue(f.get(0).w() > 5 && f.get(0).h() > 5, "a real word has a real box: " + f.get(0).describe());
    }
}
