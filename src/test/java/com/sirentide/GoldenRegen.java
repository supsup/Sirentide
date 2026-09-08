package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /// The tracked golden directory, spelled once.
    static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    static boolean updating() {
        return Boolean.getBoolean(FLAG);
    }

    @FunctionalInterface
    interface GoldenWriter {
        void write(String name, String svg) throws Exception;
    }

    private GoldenRegen() {
    }

    /// THE GATE: assertion and write bound together, so the write cannot happen without the check.
    static void regenerateGolden(String name, String svg, GoldenWriter writer) throws Exception {
        assertRenderIsSane(name, svg);
        writer.write(name, svg);
    }

    /// The ordinary gate, writing into the tracked golden directory.
    static void regenerateGolden(String name, String svg) throws Exception {
        regenerateGolden(name, svg, GoldenRegen::writeTrackedGolden);
    }

    static void writeTrackedGolden(String name, String svg) throws Exception {
        Files.createDirectories(GOLDEN_DIR);
        Files.writeString(GOLDEN_DIR.resolve(name + ".svg"), svg, StandardCharsets.UTF_8);
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

    /// Emitting the banner is its own act, and the census pins that every site does it -- the
    /// reviewer's M8 deleted both emissions and the suite stayed green, because only the string
    /// BUILDER was tested and never that anything printed it.
    static void announceRegen(int rewritten) {
        System.err.println(regenBanner(rewritten));
    }
}
