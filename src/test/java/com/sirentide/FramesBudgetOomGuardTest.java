package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.FramesResult;
import com.sirentide.api.Outcome;
import com.sirentide.api.Sirentide;
import java.util.List;
import org.junit.jupiter.api.Test;

/// SIR-01 regression guard (independent audit, High severity). {@link Sirentide#renderFrames} caps
/// each frame individually ({@link Sirentide#MAX_OUTPUT_BYTES}) but — before this fix — had NO
/// aggregate byte budget and NO frame-count cap. Every frame re-emits the WHOLE scene, so a LEGAL
/// (in-budget-per-frame) input at the parser's message cap produces ~10k frames of up to ~5 MB each
/// (~50 GB retained) in one public-API call → OOM on legal input.
///
/// These pin the two new caps by OUTCOME (the loud/inert degrade the per-frame cap already uses) —
/// never by wall-clock or measured memory (flaky). Two distinct guards:
///   (1) {@link Sirentide#MAX_FRAMES} — the frame COUNT, checked before any frame is baked;
///   (2) {@link Sirentide#MAX_TOTAL_OUTPUT_BYTES} — the aggregate byte budget, the load-bearing one
///       (a play-through UNDER the frame cap can still retain gigabytes).
/// The contrast tests use the SAME frame count (152, under MAX_FRAMES) with and without long labels,
/// so the only thing that flips the outcome is the aggregate byte budget — proving IT is the guard.
class FramesBudgetOomGuardTest {

    /// The inert empty shell — the universal degrade target. Byte-identical to Sirentide.INERT_SHELL
    /// (package-private there); restated as the contract literal the way the sibling OOM-guard tests do.
    private static final String INERT_SHELL =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"></svg>";

    /// Mirror of the production caps ({@code Sirentide.MAX_FRAMES} / {@code MAX_TOTAL_OUTPUT_BYTES}),
    /// restated as literals (NOT referenced) so this test COMPILES against the pre-fix source too — the
    /// red-first proof needs a runtime assertion failure, not a missing-symbol compile error.
    private static final int MAX_FRAMES = 512;
    private static final int MAX_TOTAL_OUTPUT_BYTES = 50_000_000;   // 50 MB

    /// A sequence of {@code n} messages between two actors, each message carrying a {@code labelLen}-char
    /// label. Every message + actor head is a distinct seq-anchored step, so the play-through has
    /// ~{@code n}+2 frames — each a full re-emit of the whole (growing) scene. This is the LEGAL input
    /// shape the audit flagged: nothing malformed, just many steps.
    private static String sequence(int n, int labelLen) {
        StringBuilder sb = new StringBuilder("sequence\n");
        String label = "W".repeat(labelLen);
        for (int i = 0; i < n; i++) {
            sb.append("A ->> B : ").append(label).append(i).append('\n');
        }
        return sb.toString();
    }

    // ---- Guard 1: the frame-COUNT cap ---------------------------------------------------------

    /// A legal play-through whose step count exceeds {@link Sirentide#MAX_FRAMES} degrades to the
    /// single inert shell instead of baking (and retaining) hundreds of full-scene documents. 600
    /// messages → 602 distinct seq steps > 512. Pre-fix this returned 602 real frames; post-fix it
    /// aborts to the inert shell BEFORE emitting any frame.
    @Test
    void frameCountBeyondCapDegradesToInertShell() {
        List<String> frames = Sirentide.renderFrames(sequence(600, 0));
        assertEquals(1, frames.size(),
            "a play-through past the " + MAX_FRAMES + "-frame cap must degrade to ONE inert frame");
        assertEquals(INERT_SHELL, frames.get(0), "the degrade target is the inert shell");
    }

    // ---- Guard 2: the aggregate BYTE budget (the load-bearing one) -----------------------------

    /// A legal play-through with only 152 frames — WELL UNDER the frame-count cap — whose frames SUM
    /// past {@link Sirentide#MAX_TOTAL_OUTPUT_BYTES} (150 messages × 512-char labels ≈ 146 MB across
    /// the frames, ~1 MB each, each individual frame under the 5 MB per-frame cap) degrades to the
    /// inert shell. This isolates the AGGREGATE guard: the frame count and every per-frame length are
    /// legal, so ONLY the total-byte budget can produce the abort. Pre-fix: 152 real frames (~146 MB).
    @Test
    void aggregateByteBudgetBeyondCapDegradesToInertShell() {
        List<String> frames = Sirentide.renderFrames(sequence(150, 512));
        assertTrue(152 <= MAX_FRAMES, "the input's frame count stays under the frame-count cap by design");
        assertEquals(1, frames.size(),
            "frames summing past the " + MAX_TOTAL_OUTPUT_BYTES
                + "-byte aggregate budget must degrade to ONE inert frame — even under the frame cap");
        assertEquals(INERT_SHELL, frames.get(0), "the degrade target is the inert shell");
    }

    // ---- The diagnostics twin classifies both breaches as the KNOWN bounded degrade ------------

    /// {@link Sirentide#renderFramesWithDiagnostics} must (a) return frames byte-identical to
    /// {@link Sirentide#renderFrames} on every path — including these new degrades — and (b) classify
    /// the breach as {@link Outcome#OUTPUT_CAP_EXCEEDED} (a KNOWN, bounded degrade), not a RENDER_BUG.
    @Test
    void diagnosticsClassifyBudgetBreachesAsOutputCap() {
        for (String dsl : List.of(sequence(600, 0), sequence(150, 512))) {
            FramesResult r = Sirentide.renderFramesWithDiagnostics(dsl);
            assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, r.diagnostics().outcome(),
                "a budget breach is a known bounded degrade, not a renderer bug");
            assertEquals(Sirentide.renderFrames(dsl), r.frames(),
                "diagnostics frames must stay byte-identical to renderFrames on the degrade path");
            assertEquals(List.of(INERT_SHELL), r.frames(), "the degrade frames are the inert shell");
        }
    }

    // ---- The caps do NOT bite legal, in-budget play-throughs -----------------------------------

    /// A substantial but IN-BUDGET play-through — 152 frames (under the frame cap) summing to ~40 MB
    /// (under the aggregate budget) — still renders every frame, unchanged. Same frame COUNT as the
    /// aggregate-breach test above; the only difference is the per-frame size, so this proves the byte
    /// budget (not the count) is what discriminates, and that legal large play-throughs are untouched.
    @Test
    void substantialInBudgetPlayThroughStillRenders() {
        String dsl = sequence(150, 0);   // 152 frames, ~40 MB total — under BOTH caps
        List<String> frames = Sirentide.renderFrames(dsl);
        assertEquals(152, frames.size(), "an in-budget play-through renders one frame per seq step");
        for (String f : frames) {
            assertTrue(f.startsWith("<svg") && f.endsWith("</svg>"), "each frame is well-formed SVG");
            assertNotEquals(INERT_SHELL, f, "an in-budget frame is real content, not the inert shell");
        }
        // Deterministic: same dsl → same List (the caps introduce no state; byte-stable).
        assertEquals(frames, Sirentide.renderFrames(dsl), "in-budget play-through is deterministic");
    }

    /// The small, canonical play-through is entirely unperturbed by the caps: the 7-frame demo
    /// sequence still yields 7 real frames (the exact BYTES are pinned by GoldenSvgTest /
    /// PlayThroughFramesTest — this only guards that the cap logic doesn't accidentally degrade it).
    @Test
    void smallPlayThroughIsUnaffectedByTheCaps() {
        String demo = "sequence\nClient ->> Gateway : GET /token\nGateway ->> Auth : validate\n"
            + "Auth -->> Gateway : ok\nGateway -->> Client : 200 token";
        List<String> frames = Sirentide.renderFrames(demo);
        assertEquals(7, frames.size(), "the 4-message demo still plays through 7 frames");
        assertFalse(frames.contains(INERT_SHELL), "no frame degraded to the inert shell");
    }
}
