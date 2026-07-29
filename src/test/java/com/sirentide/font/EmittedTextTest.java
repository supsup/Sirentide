package com.sirentide.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// The sink stack's no-retention and suspension contracts (Marlow sirentide/733).
///
/// LOW: an UNARMED emission must neither allocate nor retain per-thread state — a render-only
/// pooled thread that never runs diagnostics must not carry a deque forever. HIGH mechanics
/// (the API half; the end-to-end discriminators live in FontCoverageDiagnosticsTest): a plain
/// render entering beneath an armed diagnostic frame suspends capture, and a top-level plain
/// render takes the no-allocation fast path.
class EmittedTextTest {

    @AfterEach
    void drainThreadState() {
        // Belt and braces: no test may leak frames into the next (disarm is safe when empty).
        while (EmittedText.hasThreadState()) {
            EmittedText.disarm();
        }
    }

    @Test
    void anUnarmedEmissionAllocatesAndRetainsNothing() {
        EmittedText.record("plain label text");
        assertFalse(EmittedText.hasThreadState(),
            "an unarmed emission must not attach a deque to a render-only thread");
    }

    @Test
    void aTopLevelPlainRenderLeavesNoThreadState() {
        Sirentide.render("flowchart TD\n  A[Ship it] --> B[Done]\n");
        assertFalse(EmittedText.hasThreadState(),
            "a completed top-level plain render must retain nothing on this thread");
    }

    @Test
    void aSuspensionFrameSwallowsPlainEmissionsAndRestoresTheOuterCapture() {
        EmittedText.arm();
        try {
            EmittedText.record("outer-before");
            boolean suspended = EmittedText.enterPlainRender();
            assertTrue(suspended, "beneath an armed frame the plain boundary must suspend");
            EmittedText.record("inner-plain");           // must NOT reach the outer sink
            EmittedText.exitPlainRender(suspended);
            EmittedText.record("outer-after");

            assertEquals("outer-before\nouter-after\n", EmittedText.collected(),
                "the outer capture must resume seamlessly and never contain inner-plain");
        } finally {
            EmittedText.disarm();
        }
        assertFalse(EmittedText.hasThreadState(), "last disarm clears the thread");
    }

    @Test
    void aTopLevelPlainBoundaryIsAFastPathThatPushesNothing() {
        boolean suspended = EmittedText.enterPlainRender();
        assertFalse(suspended, "nothing armed: the plain boundary must not push a frame");
        EmittedText.exitPlainRender(suspended);
        assertFalse(EmittedText.hasThreadState(),
            "the top-level fast path must not allocate thread state");
    }
}
