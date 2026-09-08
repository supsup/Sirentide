package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/// THE CENSUS THAT MAKES THE CALL SITES HOLD (needs-fix PROJECT/sirentide 1072, F1 + F2 + M3 + M8).
///
/// The first version of this branch bound the assertion and the write into one unit and then tested
/// THE UNIT. The reviewer showed that buys nothing against the actual regression: replacing the
/// CALL with a raw write left the suite green, because a test that invokes a helper directly can
/// never notice that production stopped invoking it. That is the same helper-versus-guard failure
/// the branch's own rationale describes, one level up from where it was applied.
///
/// A per-site test cannot fix that either -- it would have the same blind spot at the next site.
/// The only instrument that does is an ENUMERATION over the sites themselves, which is the shape
/// this repo already uses for its type censuses: it goes red when a site is added, when a site
/// stops routing through the gate, and when a site stops announcing. F1 was a THIRD site nobody
/// enumerated; this is what would have caught it.
final class GoldenRegenSiteCensusTest {

    // THE SPELLING CENSUS IS DELETED (needs-fix 1084). It enumerated WRITERS and asserted each one
    // routed through the gate. Three rounds of that failed, and the reviewer's fourth pass showed
    // why it was never going to work:
    //
    //     a  updating() + Path.of("src/test/resources/golden")        caught
    //     b  literal flag + the same path                             caught
    //     c2 Boolean.getBoolean(GoldenRegen.FLAG)
    //        + Path.of("src/test/resources", "golden")                GREEN, wrote an unverified
    //                                                                 golden, dir 34 -> 35
    //     d  literal flag, package com.rogue                          GREEN
    //     e  literal flag, class named GoldenRegen.java in a
    //        subpackage (both guards exempted BY BASENAME)            GREEN
    //
    // One route of five. And the predicate was UNASSERTED on top of that: deleting either clause
    // left it green at 3 of 3, because all three real sites still carry the flag literal in prose,
    // so either clause alone reached the count. Reverting the whole previous repair reddened
    // nothing.
    //
    // Every version of this was a SPELLING predicate, and a test can always reach Files.writeString
    // with a path it computed itself, so each round closed the routes demonstrated and left the
    // ones nobody thought of. The replacement asks whether a write was GATED, not whether a file
    // LOOKS like a regen site: build.gradle.kts snapshots the golden directory before the test JVM
    // starts and diffs it after, against a ledger GoldenRegen publishes of what it wrote.
    //
    // THE TRADE, stated rather than glossed. The census ran on EVERY build; the audit only has
    // teeth on a run where goldens actually change, which is a regen run. So a bypass can now sit
    // in the tree unnoticed until someone regenerates. That is later, and it is the moment the
    // bypass would first do harm -- and it is measured against a scan that missed four routes out
    // of five on every one of those earlier runs.
    //
    // Deletion proven lossless in the same way as the last one: routes a and b, the only two the
    // census ever caught, both FAIL the build under the audit with the census gone.

    @Test
    void theBuildScriptReadsTheSameFlagTheSitesDo() throws Exception {
        // F6: the flag was typed in three test classes plus build.gradle.kts, unbound. The classes
        // now share GoldenRegen.FLAG; this pins the build script to the same literal so the two
        // cannot drift into a state where the property enables nothing.
        String build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(build.contains(GoldenRegen.FLAG),
            "build.gradle.kts must pass through the same regen flag the sites read");
    }
}
