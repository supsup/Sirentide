package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// The golden-regeneration gate, in ONE place (plan aac2500e, needs-fix PROJECT/sirentide 1072).
///
/// It lives in its OWN class rather than inside GoldenSvgTest for a reason that is the whole point
/// of the repair: a CALL to `GoldenRegen.regenerateGolden(` is textually distinguishable from its
/// DECLARATION, which lets GoldenRegenSiteCensusTest assert that every class reading the regen flag
/// actually routes through this gate. While the gate lived beside its only caller, a census could
/// not tell "calls it" from "declares it", and the reviewer's M1 -- replacing the call site with a
/// raw write -- left the suite green.
///
/// F6: the flag name and the golden directory were typed in three classes each. They are here once.
final class GoldenRegen {

    /// The regen flag, spelled once. Also read by build.gradle.kts; the census test pins that too.
    static final String FLAG = "sirentide.updateGolden";

    /// The tracked golden directory, spelled once and PRIVATE (needs-fix 1077).
    ///
    /// Private is the load-bearing word. While it was package-private, any test could name the
    /// directory and write into it, and GoldenSvgTest carried its own duplicate writer that did
    /// exactly that -- routed through the gate, so not a defect, but it meant "the golden path is
    /// spelled here alone" was false and the directory could not be fenced. Now the only
    /// compile-legal way for another class to write a tracked golden is regenerateGolden, and the
    /// census can assert that nothing outside this file names the directory at all.
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    static boolean updating() {
        return Boolean.getBoolean(FLAG);
    }

    @FunctionalInterface
    interface GoldenWriter {
        void write(String name, String svg) throws Exception;
    }

    private GoldenRegen() {
    }

    /// THE LEDGER (needs-fix 1084). Every name this gate writes, recorded as it writes it.
    ///
    /// This exists because THREE ROUNDS of enumerating WRITERS failed. Each round pinned how a
    /// regen site spells itself -- the flag literal, then the flag constant, then the directory
    /// construct -- and each round the reviewer found another spelling: a two-argument Path.of, a
    /// different package, a class named GoldenRegen.java in a subpackage. Every such scan is a
    /// SPELLING predicate, and a test can always reach Files.writeString with a path it computed
    /// itself, so the next evasion is always the one nobody thought of.
    ///
    /// So the question changed from "does this file look like a regen site" to "was this write
    /// gated". The gate already knows what it wrote; it counts them for the banner. Anything that
    /// CHANGED on disk and is not in here was written by something that bypassed the gate,
    /// regardless of how it spelled the flag, resolved the path, named itself, or which package it
    /// sits in. The comparison is done by build.gradle.kts, which can snapshot the directory
    /// BEFORE the test JVM starts -- a boundary this class cannot observe, because a bypass in
    /// another package need never load GoldenRegen at all.
    private static final Set<String> RECORDED = ConcurrentHashMap.newKeySet();

    /// Where the ledger is published for the build to read. Under build/, so it is never tracked.
    private static final Path LEDGER = Path.of("build", "golden-regen-ledger.txt");

    /// THE GATE: assertion and write bound together, so the write cannot happen without the check.
    static void regenerateGolden(String name, String svg, GoldenWriter writer) throws Exception {
        assertRenderIsSane(name, svg);
        writer.write(name, svg);
    }

    /// Recorded HERE rather than in regenerateGolden, and the difference is load-bearing. The
    /// three-argument gate accepts an INJECTED writer, which the gate's own tests use to count
    /// calls without touching the tracked directory. Recording at the gate credited those to the
    /// ledger: 35 names for 34 goldens, a discrepancy I found by reconciling the counts rather
    /// than by reading the code. It is not cosmetic -- a ledger entry for a name never written
    /// tracked would let a bypass writing THAT name pass the audit unnoticed. The ledger must
    /// contain writes to the tracked directory and nothing else.
    ///
    /// Recorded AFTER the write returns, deliberately: a write that threw did not happen, and
    /// claiming it in the ledger would let a failed gated write mask a later bypass of the same
    /// name. The set is concurrent because nothing here promises single-threaded tests.
    private static void record(String name) {
        RECORDED.add(name);
        try {
            Files.createDirectories(LEDGER.getParent());
            Files.writeString(LEDGER, String.join("\n", sortedRecorded()) + "\n",
                StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The build treats a MISSING ledger as "nothing was gated", which fails closed: every
            // changed golden then reads as unrecorded. Losing the ledger cannot manufacture a pass.
        }
    }

    static List<String> sortedRecorded() {
        return RECORDED.stream().sorted().toList();
    }

    /// The ordinary gate, writing into the tracked golden directory.
    static void regenerateGolden(String name, String svg) throws Exception {
        regenerateGolden(name, svg, GoldenRegen::writeTrackedGolden);
    }

    private static void writeTrackedGolden(String name, String svg) throws Exception {
        Files.createDirectories(GOLDEN_DIR);
        Files.writeString(GOLDEN_DIR.resolve(name + ".svg"), svg, StandardCharsets.UTF_8);
        record(name);
    }

    /// Pre-write sanity. Each clause is separately bound by a fixture in GoldenRegenAssertsTest --
    /// the reviewer's M4/M5/M6 showed that deleting any single clause left the suite green, because
    /// every fixture tripped more than one.
    static void assertRenderIsSane(String name, String svg) {
        assertTrue(svg.contains("<svg"), name + ": expected a real render, got no <svg> at all");
        assertFalse(svg.contains("width=\"0\" height=\"0\""),
            name + ": expected a real render, got the inert 0x0 degrade shell");
        assertFalse(svg.contains("\\frac"),
            name + ": raw LaTeX command leaked into the render instead of baking");
        assertFalse(svg.contains("$"),
            name + ": raw math delimiter leaked into the render instead of baking");
    }

    /// The LOUD line. A green build must never be INDISTINGUISHABLE from a regen.
    static String regenBanner(int rewritten) {
        return "SIRENTIDE GOLDEN REGEN: rewrote " + rewritten + " golden(s) under -D" + FLAG
            + ". The byte-comparison assertion was SKIPPED for all " + rewritten
            + "; this run did NOT verify them against a prior expectation. Review the diff.";
    }

    /// Emitting the banner is its own act. The census pins that every site CALLS this; that is a
    /// different claim from this method actually printing, and for one round it was the only claim
    /// anyone made. GAP-C (needs-fix 1077): gutting this body to a comment left every call site
    /// intact, the suite GREEN, and a real regen emitting NOTHING -- which is word for word the
    /// M8 defect this comment used to say was fixed. The emission now has its own test that reads
    /// stderr, so the sentence above is true of the CALL and the test is true of the PRINT.
    static void announceRegen(int rewritten) {
        System.err.println(regenBanner(rewritten));
    }
}
