package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/// Plan aac2500e, repaired under needs-fix PROJECT/sirentide 1072.
///
/// EACH SANITY CLAUSE GETS A FIXTURE THAT TRIPS ONLY THAT CLAUSE. The reviewer's M4/M5/M6 deleted
/// each clause in turn and the suite stayed green every time, because every fixture tripped more
/// than one: an inert shell also lacked real content, and the one LaTeX fixture satisfied both
/// halves of a disjunction. A clause no fixture isolates is not bound by this suite.
final class GoldenRegenAssertsTest {

    // Each fixture is sane in EVERY respect except the one it is named for.
    private static final String SANE = "<svg width=\"120\" height=\"40\"><g/></svg>";
    private static final String NO_SVG_TAG = "<html><body>rendered nothing</body></html>";
    private static final String INERT_0X0 = "<svg width=\"0\" height=\"0\"><g/></svg>";
    private static final String LEAKED_COMMAND = "<svg width=\"120\" height=\"40\">\\frac{a}{b}</svg>";
    private static final String LEAKED_DELIMITER = "<svg width=\"120\" height=\"40\">$x$</svg>";

    private static int writesFor(String svg) {
        AtomicInteger writes = new AtomicInteger();
        assertThrows(AssertionError.class,
            () -> GoldenRegen.regenerateGolden("probe", svg, (n, s) -> writes.incrementAndGet()));
        return writes.get();
    }

    @Test
    void aRenderWithNoSvgTagIsRefusedAndNotWritten() {
        assertEquals(0, writesFor(NO_SVG_TAG), "the write must be GATED by the assertion");
    }

    @Test
    void anInert0x0ShellIsRefusedAndNotWritten() {
        assertEquals(0, writesFor(INERT_0X0), "the write must be GATED by the assertion");
    }

    @Test
    void aLeakedLatexCommandIsRefusedAndNotWritten() {
        // Trips the \frac clause ONLY -- carries no $ -- so deleting that clause reds this test.
        assertEquals(0, writesFor(LEAKED_COMMAND), "the write must be GATED by the assertion");
    }

    @Test
    void aLeakedMathDelimiterIsRefusedAndNotWritten() {
        // Trips the $ clause ONLY -- carries no \frac -- so deleting that clause reds this test.
        assertEquals(0, writesFor(LEAKED_DELIMITER), "the write must be GATED by the assertion");
    }

    @Test
    void aSaneRenderIsStillWritten() {
        // Positive control: without it, refusing EVERYTHING would satisfy all four tests above.
        AtomicInteger writes = new AtomicInteger();
        assertDoesNotThrow(
            () -> GoldenRegen.regenerateGolden("probe", SANE, (n, s) -> writes.incrementAndGet()));
        assertEquals(1, writes.get(), "a sane render must still be written");
    }

    @Test
    void theRegenBannerNamesTheCountAtMoreThanOneValue() {
        // M7: the banner ignored its argument and stayed green, because only regenBanner(7) was
        // ever asserted. Two distinct counts bind the parameter to the output.
        // Assert WHERE the count appears, not merely that it appears. A bare contains("34")
        // was satisfied by the SECOND use of the count in the same sentence, so hard-coding the
        // first one survived -- an assertion satisfied by a part it was not aiming at.
        assertTrue(GoldenRegen.regenBanner(7).contains("rewrote 7 golden(s)"));
        assertTrue(GoldenRegen.regenBanner(34).contains("rewrote 34 golden(s)"),
            "the announced count must come from the ARGUMENT, not a constant");
        assertTrue(GoldenRegen.regenBanner(34).contains("SKIPPED for all 34"),
            "the skipped-count must come from the argument too -- it is a second use of the same "
                + "value and was the one silently satisfying the weaker assertion");
        assertTrue(GoldenRegen.regenBanner(34).contains("SKIPPED"),
            "the banner must name the assertion regeneration suspends");
    }
}
