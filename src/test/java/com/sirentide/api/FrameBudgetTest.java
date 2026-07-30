package com.sirentide.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameBudgetTest {

    private static final String MULTI_STEP_UNICODE =
        "flowchart\nA[Café] --> B[東京]\nB --> C[Ship]\n";

    @Test
    void budgetAcceptsExactProducerLimits() {
        FrameBudget budget = assertDoesNotThrow(() ->
            new FrameBudget(Sirentide.MAX_FRAMES, Sirentide.MAX_TOTAL_OUTPUT_BYTES));

        assertEquals(Sirentide.MAX_FRAMES, budget.maxFrames());
        assertEquals(Sirentide.MAX_TOTAL_OUTPUT_BYTES, budget.maxUtf8Bytes());
    }

    @Test
    void budgetRejectsNonPositiveAndProducerLimitExcess() {
        assertThrows(IllegalArgumentException.class, () -> new FrameBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FrameBudget(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FrameBudget(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FrameBudget(1, -1));
        assertThrows(IllegalArgumentException.class, () ->
            new FrameBudget(Sirentide.MAX_FRAMES + 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
            new FrameBudget(1, (long) Sirentide.MAX_TOTAL_OUTPUT_BYTES + 1));
    }

    @Test
    void exactConsumerFrameAndUtf8BoundariesPreserveExistingFrameBytes() {
        FramesResult existing = Sirentide.renderFramesWithDiagnostics(MULTI_STEP_UNICODE);
        long exactUtf8Bytes = utf8Bytes(existing);

        assertTrue(existing.frames().size() > 1, "fixture must exercise emphasized frames");
        assertTrue(exactUtf8Bytes > existing.frames().stream().mapToLong(String::length).sum(),
            "fixture must distinguish exact UTF-8 bytes from Java character count");

        FrameBudget exact = new FrameBudget(existing.frames().size(), exactUtf8Bytes);
        FramesResult bounded = Sirentide.renderFramesWithDiagnostics(
            MULTI_STEP_UNICODE, null, exact);

        assertEquals(Outcome.OK, bounded.diagnostics().outcome());
        assertEquals(existing.frames(), bounded.frames(),
            "a sufficient consumer budget must not alter any existing frame byte");
    }

    @Test
    void oneFramePastConsumerCountFailsAtomicallyBeforeRetainingADeck() {
        FramesResult existing = Sirentide.renderFramesWithDiagnostics(MULTI_STEP_UNICODE);
        FrameBudget oneShort = new FrameBudget(
            existing.frames().size() - 1, Sirentide.MAX_TOTAL_OUTPUT_BYTES);

        FramesResult bounded = Sirentide.renderFramesWithDiagnostics(
            MULTI_STEP_UNICODE, null, oneShort);

        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, bounded.diagnostics().outcome());
        assertTrue(bounded.diagnostics().detail().startsWith("consumer-frame-count"));
        assertTrue(bounded.frames().isEmpty(), "a rejected consumer deck must retain no prefix");
    }

    @Test
    void frameCountRejectionWinsBeforeEmphasizedFrameByteBudgetEvaluation() {
        FramesResult bounded = Sirentide.renderFramesWithDiagnostics(
            MULTI_STEP_UNICODE, null, new FrameBudget(1, 1));

        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, bounded.diagnostics().outcome());
        assertTrue(bounded.diagnostics().detail().startsWith("consumer-frame-count"),
            "the pre-emphasis count gate must win over the deliberately impossible byte budget");
        assertTrue(bounded.frames().isEmpty());
    }

    @Test
    void oneUtf8BytePastConsumerBudgetFailsAtomically() {
        FramesResult existing = Sirentide.renderFramesWithDiagnostics(MULTI_STEP_UNICODE);
        long exactUtf8Bytes = utf8Bytes(existing);
        FrameBudget oneByteShort = new FrameBudget(existing.frames().size(), exactUtf8Bytes - 1);

        FramesResult bounded = Sirentide.renderFramesWithDiagnostics(
            MULTI_STEP_UNICODE, null, oneByteShort);

        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, bounded.diagnostics().outcome());
        assertTrue(bounded.diagnostics().detail().startsWith("consumer-utf8-bytes"));
        assertTrue(bounded.frames().isEmpty(), "a rejected consumer deck must retain no prefix");
    }

    @Test
    void singleFrameUsesTheSameExactUtf8Boundary() {
        String singleStep = "flowchart\nA[Café 東京]\n";
        FramesResult existing = Sirentide.renderFramesWithDiagnostics(singleStep);
        long exactUtf8Bytes = utf8Bytes(existing);

        FramesResult exact = Sirentide.renderFramesWithDiagnostics(
            singleStep, null, new FrameBudget(1, exactUtf8Bytes));
        FramesResult oneByteShort = Sirentide.renderFramesWithDiagnostics(
            singleStep, null, new FrameBudget(1, exactUtf8Bytes - 1));

        assertEquals(existing.frames(), exact.frames());
        assertEquals(Outcome.OK, exact.diagnostics().outcome());
        assertTrue(oneByteShort.frames().isEmpty());
        assertTrue(oneByteShort.diagnostics().detail().startsWith("consumer-utf8-bytes"));
    }

    @Test
    void prospectiveAggregateComparisonCannotOverflowLong() {
        assertTrue(Sirentide.wouldExceedFrameBudget(Long.MAX_VALUE - 4, 5, Long.MAX_VALUE));
        assertTrue(!Sirentide.wouldExceedFrameBudget(Long.MAX_VALUE - 5, 5, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
            () -> Sirentide.wouldExceedFrameBudget(-1, 0, 1));
    }

    private static long utf8Bytes(FramesResult result) {
        return result.frames().stream()
            .mapToLong(frame -> frame.getBytes(StandardCharsets.UTF_8).length)
            .sum();
    }
}
