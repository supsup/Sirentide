package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.FrameBudget;
import com.sirentide.api.FramesResult;
import com.sirentide.api.Outcome;
import com.sirentide.api.Sirentide;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/// Receipts for the play-through diagnostics twin (plan sirentide-fence-diagnostics).
/// `renderFramesWithDiagnostics` runs the SAME guarded pipeline as `renderFrames` and returns a
/// byte-identical frames list plus the {@link com.sirentide.api.Diagnostics} classification that
/// `renderWithDiagnostics` established. These pin: frames byte-identity against `renderFrames` on
/// every path (success, single-frame, degrade), the classification at each choke point, and the
/// never-throw invariant.
class FramesDiagnosticsTest {

    /// A multi-step diagram (three seq-anchored flowchart hops) — the real play-through case.
    private static final String MULTI_STEP =
        "flowchart\nA[One] --> B[Two]\nB --> C[Three]\n";

    private static final String SINGLE_STEP = "pie\n  \"All\" : 100\n";

    /// THE byte-identity pin (and the delete-mutant target): on success the frames list is
    /// element-for-element IDENTICAL to `renderFrames` — the diagnostics ride alongside the real
    /// bake, never a reimplementation that could drift from it.
    @Test
    void multiStepFramesAreByteIdenticalToRenderFrames() {
        FramesResult r = Sirentide.renderFramesWithDiagnostics(MULTI_STEP);
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals(Sirentide.renderFrames(MULTI_STEP), r.frames(),
            "frames must equal renderFrames(dsl) exactly");
        assertTrue(r.frames().size() > 1, "a multi-step diagram plays through several frames");
    }

    /// A diagram with nothing to play through yields the single static frame — reported OK with a
    /// message that SAYS it's single-frame, so a fence consumer can tell "one frame by design"
    /// apart from "one frame because everything degraded".
    @Test
    void singleStepDiagramReportsOkSingleFrame() {
        FramesResult r = Sirentide.renderFramesWithDiagnostics(SINGLE_STEP);
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals(Sirentide.renderFrames(SINGLE_STEP), r.frames());
        assertEquals(1, r.frames().size());
        assertTrue(r.diagnostics().message().contains("single frame"),
            "the single-frame nuance is named for the author");
    }

    /// An unrecognized diagram-type keyword classifies PARSE_ERROR (stage=parse) while the frames
    /// stay exactly what `renderFrames` degrades to for the same input.
    @Test
    void unknownTypeReportsParseErrorWithDegradedFrames() {
        String dsl = "not-a-diagram\n  foo : bar\n";
        FramesResult r = Sirentide.renderFramesWithDiagnostics(dsl);
        assertEquals(Outcome.PARSE_ERROR, r.diagnostics().outcome());
        assertEquals("parse", r.diagnostics().stage());
        assertFalse(r.diagnostics().message().isBlank());
        assertEquals(Sirentide.renderFrames(dsl), r.frames(),
            "degrade frames must equal renderFrames(dsl) exactly");
    }

    /// The output-cap degrade classifies OUTPUT_CAP_EXCEEDED — a KNOWN, bounded degrade kept
    /// distinct from a failure — with the inert-shell frame `renderFrames` also produces.
    @Test
    void outputCapClassifiesKnownDegrade() {
        StringBuilder big = new StringBuilder("pie\n");
        for (int i = 0; i < 10_000; i++) {
            big.append("  \"slice ").append(i).append("\" : 1\n");
        }
        String dsl = big.toString();
        FramesResult r = Sirentide.renderFramesWithDiagnostics(dsl);
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, r.diagnostics().outcome());
        assertEquals(Sirentide.renderFrames(dsl), r.frames());
    }

    /// A dropping thin-slice-label pie has no seq-anchored steps, so it takes the frames path's
    /// single-frame branch (the SAME `pieDropCaveat` call `renderWithDiagnostics` makes) — pinned
    /// here because that branch classifies OK-with-caveat at STAGE_EMIT, not the layout-time stage
    /// the dropped fact describes (Marlow sirentide/762: the public `Diagnostics.stage` contract
    /// keys on the classification point, and a successful bake always classifies at emit).
    private static final String DROPPING_PIE =
        "pie\n\"quarter\" : 25\n\"right outside label that should clip\" : 1\n\"rest\" : 74";

    @Test
    void droppingPieOnFramesPathClassifiesAtStageEmit() {
        FramesResult r = Sirentide.renderFramesWithDiagnostics(DROPPING_PIE);
        assertEquals(Outcome.OK, r.diagnostics().outcome());
        assertEquals("emit", r.diagnostics().stage(),
            "the drop caveat classifies at emit, not layout, on the frames path too");
        assertTrue(r.diagnostics().message().contains("dropped"),
            "the drop caveat still rides the message: " + r.diagnostics().message());
        assertEquals(Sirentide.renderFrames(DROPPING_PIE), r.frames(),
            "frames stay byte-identical to renderFrames even with the caveat riding the diagnostics");
    }

    @Test
    void sourceAndFontCaveatsComposeIdenticallyOnSingleAndMultiFramePaths() {
        // Causal mutant: omit droppedStatementCaveat from the shared policy (or bypass the policy
        // on either frames branch). The independent expected detail pins composition and line
        // attribution; parity alone would pass if both diagnostic APIs lost the same caveat.
        for (boolean multi : new boolean[] {false, true}) {
            for (boolean drop : new boolean[] {false, true}) {
                for (boolean font : new boolean[] {false, true}) {
                    String dsl = "flowchart TD\nA[" + (font ? "漢字" : "Start") + "]"
                        + (multi ? " --> B[End]" : "") + "\n"
                        + (drop ? "mystyle A fill:#f00\n" : "");
                    String expectedDetail = drop
                        ? "dropped statement(s): 1; line 3: mystyle A fill:#f00" : "";
                    if (font) {
                        expectedDetail += (drop ? "; " : "")
                            + "out-of-coverage code points: U+6F22, U+5B57";
                    }
                    Diagnostics still = Sirentide.renderWithDiagnostics(dsl).diagnostics();
                    FramesResult deck = Sirentide.renderFramesWithDiagnostics(dsl);

                    assertEquals(Outcome.OK, still.outcome(), dsl);
                    assertEquals(expectedDetail, still.detail(), dsl);
                    assertEquals(drop ? 3 : -1, still.line(), dsl);
                    assertEquals(still.detail(), deck.diagnostics().detail(), dsl);
                    assertEquals(still.line(), deck.diagnostics().line(), dsl);
                    assertEquals(still.outcome(), deck.diagnostics().outcome(), dsl);
                    assertEquals(still.stage(), deck.diagnostics().stage(), dsl);
                    assertEquals(multi, deck.frames().size() > 1, "branch control: " + dsl);
                    assertEquals(Sirentide.renderFrames(dsl), deck.frames(), dsl);
                    if (!multi) {
                        assertTrue(deck.diagnostics().message().contains("single frame"), dsl);
                        assertEquals(Sirentide.render(dsl), deck.frames().getFirst(), dsl);
                    } else {
                        assertEquals(still, deck.diagnostics(), dsl);
                    }
                }
            }
        }
    }

    @Test
    void framesAlsoKeepNumericRowAndHeatmapInputCaveats() {
        String[] sources = {
            "xychart line numeric\n1 : 1\nabc : 2\n3 : 3\n",
            "heatmap\nhide: nope\n\"r\" : 0.5\n"
        };
        String[] kinds = {"xychart numeric row issue(s): 1", "heatmap input issue(s): 1"};
        for (int i = 0; i < sources.length; i++) {
            Diagnostics still = Sirentide.renderWithDiagnostics(sources[i]).diagnostics();
            FramesResult deck = Sirentide.renderFramesWithDiagnostics(sources[i]);
            assertEquals(Outcome.OK, still.outcome());
            assertTrue(still.detail().contains(kinds[i]), still.detail());
            assertEquals(still.detail(), deck.diagnostics().detail());
            assertEquals(still.line(), deck.diagnostics().line());
            assertEquals(Outcome.OK, deck.diagnostics().outcome());
            assertEquals(Sirentide.renderFrames(sources[i]), deck.frames());
        }
    }

    @Test
    void consumerBudgetFailureOwnsTheVerdictEvenWhenTheSourceHasCombinedCaveats() {
        for (boolean multi : new boolean[] {false, true}) {
            String dsl = "flowchart TD\nA[漢字]" + (multi ? " --> B[End]" : "")
                + "\nmystyle A fill:#f00\n";
            FramesResult deck = Sirentide.renderFramesWithDiagnostics(dsl);
            long bytes = deck.frames().stream()
                .mapToLong(frame -> frame.getBytes(StandardCharsets.UTF_8).length).sum();
            assertTrue(deck.diagnostics().detail().contains("dropped statement(s)"));
            assertTrue(deck.diagnostics().detail().contains("out-of-coverage"));
            FramesResult exact = Sirentide.renderFramesWithDiagnostics(dsl, null,
                new FrameBudget(deck.frames().size(), bytes));
            assertEquals(deck, exact, "sufficient budgets preserve the caveats and every frame");

            FramesResult shortBytes = Sirentide.renderFramesWithDiagnostics(dsl, null,
                new FrameBudget(deck.frames().size(), bytes - 1));
            assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, shortBytes.diagnostics().outcome());
            assertTrue(shortBytes.frames().isEmpty(), "no partial deck survives the cap");
            assertTrue(shortBytes.diagnostics().detail().startsWith("consumer-utf8-bytes"));
            assertFalse(shortBytes.diagnostics().detail().contains("dropped statement(s)"));
            if (multi) {
                FramesResult shortCount = Sirentide.renderFramesWithDiagnostics(dsl, null,
                    new FrameBudget(deck.frames().size() - 1, 1));
                assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, shortCount.diagnostics().outcome());
                assertTrue(shortCount.frames().isEmpty());
                assertTrue(shortCount.diagnostics().detail().startsWith("consumer-frame-count"),
                    "count rejection precedes both byte evaluation and successful caveat policy");
            }
        }
    }

    @Test
    void unsupportedAndUnreadableEmptiedBodiesKeepTheirFailureClassifications() {
        String[] bodies = {"A <--> B", "mystyle A fill:#f00"};
        Outcome[] outcomes = {Outcome.UNSUPPORTED_CONSTRUCT, Outcome.PARSE_ERROR};
        for (int i = 0; i < bodies.length; i++) {
            String dsl = "flowchart TD\n" + bodies[i] + "\n";
            Diagnostics still = Sirentide.renderWithDiagnostics(dsl).diagnostics();
            FramesResult deck = Sirentide.renderFramesWithDiagnostics(dsl);
            assertEquals(outcomes[i], still.outcome(), bodies[i]);
            assertEquals(2, still.line());
            assertEquals("parse", still.stage());
            assertEquals(still, deck.diagnostics());
            assertEquals(Sirentide.renderFrames(dsl), deck.frames());
        }
    }

    /// The never-throw invariant holds on hostile input, same as the whole api surface.
    @Test
    void neverThrowsOnHostileInput() {
        for (String dsl : new String[] {null, "", "\u0000\u0007", "pie\n\"\u0000\" : \u0007",
                "flowchart\nA[ ] --> B[\"</svg><script>\"]\n"}) {
            FramesResult r = Sirentide.renderFramesWithDiagnostics(dsl);
            assertTrue(r.frames().size() >= 1, "always at least one frame");
            assertEquals(Sirentide.renderFrames(dsl), r.frames(),
                "every degrade stays byte-identical to renderFrames");
        }
    }
}
