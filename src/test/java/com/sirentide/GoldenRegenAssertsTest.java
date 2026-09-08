package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/// Plan aac2500e: `-Dsirentide.updateGolden=true` used to turn the golden tests into pure write
/// loops that asserted NOTHING and reported PASSED, so a green run under the regen flag was
/// indistinguishable from a green run that verified something.
///
/// These are the falsifiable control. The point is not that `assertRenderIsSane` works -- it is
/// that the WRITE IS GATED BY IT. A mutant restoring the pure-write branch leaves the helper intact
/// and simply stops calling it, which a test of the helper alone would never notice.
final class GoldenRegenAssertsTest {

    private static final String INERT = "<svg width=\"0\" height=\"0\"></svg>";
    private static final String LEAKED_LATEX = "<svg><text>$\\frac{a}{b}$</text></svg>";
    private static final String SANE = "<svg width=\"120\" height=\"40\"><g/></svg>";

    @Test
    void aRegeneratedGoldenIsNotWrittenWhenTheRenderIsInert() {
        AtomicInteger writes = new AtomicInteger();
        assertThrows(AssertionError.class,
            () -> GoldenSvgTest.regenerateGolden("probe", INERT, (n, s) -> writes.incrementAndGet()),
            "an inert 0x0 shell must not be accepted as a new expected value");
        assertEquals(0, writes.get(),
            "the write must be GATED by the assertion, not merely accompanied by it");
    }

    @Test
    void aRegeneratedGoldenIsNotWrittenWhenRawLatexLeaked() {
        // A COUNTING writer, never fail() inside the writer. My first version of this test used
        // fail(), and the mutation run caught that it could not distinguish the two outcomes: under
        // a pure-write mutant the writer IS called, fail() throws AssertionError, and
        // assertThrows(AssertionError.class) accepts that as the refusal it was meant to prove
        // absent. An assertion both branches satisfy distinguishes neither.
        AtomicInteger writes = new AtomicInteger();
        assertThrows(AssertionError.class,
            () -> GoldenSvgTest.regenerateGolden("probe", LEAKED_LATEX,
                (n, s) -> writes.incrementAndGet()),
            "unbaked LaTeX must not be accepted as a new expected value");
        assertEquals(0, writes.get(),
            "the write must be GATED by the assertion, not merely accompanied by it");
    }

    @Test
    void aSaneRenderIsWritten() {
        AtomicInteger writes = new AtomicInteger();
        // The positive control: without this, the two refusals above would also pass if
        // regenerateGolden refused EVERYTHING, which would be a different bug.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> GoldenSvgTest.regenerateGolden("probe", SANE, (n, s) -> writes.incrementAndGet()));
        assertEquals(1, writes.get(), "a sane render must still be written");
    }

    @Test
    void theRegenBannerNamesTheCountAndTheSkippedAssertion() {
        String banner = GoldenSvgTest.regenBanner(7);
        assertTrue(banner.contains("7"), "the banner must name HOW MANY goldens were rewritten");
        assertTrue(banner.contains("SKIPPED"),
            "the banner must name the assertion regeneration suspends -- the byte comparison -- so a "
                + "green regen run cannot be mistaken for a verifying run: " + banner);
    }
}
