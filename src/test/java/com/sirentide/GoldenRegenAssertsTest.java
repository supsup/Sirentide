package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        // GAP-B (needs-fix 1077). The line above pinned position 2 at exactly ONE value, and 34 is
        // the real golden count -- precisely the constant a hard-coder reaches for. So changing
        // only the second use to a literal 34 stayed GREEN, and a live regen then printed
        // self-contradicting banners: "rewrote 32 golden(s) ... SKIPPED for all 34". Position 1 was
        // bound at two values and position 2 at one; the ASYMMETRY was the bug, and it is the same
        // two-uses-one-assertion shape I had just finished fixing at position 1.
        assertTrue(GoldenRegen.regenBanner(7).contains("SKIPPED for all 7"),
            "the skipped-count must vary with the argument at a SECOND value too, or a literal "
                + "equal to the real golden count survives here");
        assertTrue(GoldenRegen.regenBanner(34).contains("SKIPPED"),
            "the banner must name the assertion regeneration suspends");
    }

    @Test
    void announcingActuallyPrintsTheBannerAndDoesNotMerelyBuildIt() {
        // GAP-C (needs-fix 1077), and the doc comment on announceRegen used to claim this was
        // already covered. It was not: the census pins that every site CALLS announceRegen, which
        // is a different claim from announceRegen printing anything. Gutting its body to a comment
        // left all three call sites intact, the suite GREEN, and a real regen emitting ZERO
        // banners -- word for word the M8 defect the comment said was fixed.
        //
        // "Only the string BUILDER was tested and never that anything printed it" was still true
        // of the successor to the fix for exactly that sentence.
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String emitted;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            GoldenRegen.announceRegen(7);
        } finally {
            System.setErr(original);
            emitted = captured.toString(StandardCharsets.UTF_8);
        }

        assertTrue(emitted.contains("rewrote 7 golden(s)"),
            "announceRegen must PRINT the banner, not merely be able to build one: " + emitted);
        assertTrue(emitted.contains("SKIPPED for all 7"),
            "the printed banner must carry the count at both positions: " + emitted);
    }

    @Test
    void aGatedWriteToAnINJECTEDwriterIsNotRecordedAsATrackedGolden() {
        // THE LEDGER RECORDS TRACKED WRITES, NOT GATED CALLS (needs-fix 1084).
        //
        // The build's write audit diffs the golden directory against this ledger, so a name in the
        // ledger is a promise that the gate wrote THAT file. The three-argument gate accepts an
        // injected writer, which the tests above use to count calls without touching the directory
        // at all; recording at the gate credited those to the ledger and it read 35 names for 34
        // goldens.
        //
        // That is not cosmetic. A ledger entry for a name never written would let a BYPASS writing
        // that same name pass the audit unnoticed -- the audit would see the file change, find the
        // name recorded, and clear it. So the over-count was a hole in the new guard, not an
        // untidiness in it.
        //
        // I found it by reconciling 35 against 34 rather than by reading the code, which is why
        // this test exists: the next person to move record() back to the gate should get a RED,
        // not an arithmetic puzzle.
        assertDoesNotThrow(() ->
            GoldenRegen.regenerateGolden("ledger-probe", SANE, (n, s) -> { }));
        assertFalse(GoldenRegen.sortedRecorded().contains("ledger-probe"),
            "a gated write through an INJECTED writer touched no tracked golden, so recording it "
                + "would promise the audit a file that does not exist: "
                + GoldenRegen.sortedRecorded());
    }
}
