package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.FramesResult;
import com.sirentide.api.Outcome;
import com.sirentide.api.Sirentide;
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
