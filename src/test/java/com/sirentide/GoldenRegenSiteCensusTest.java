package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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

    private static final Path TEST_SOURCES = Path.of("src/test/java/com/sirentide");

    /// Classes that legitimately mention the flag WITHOUT being regeneration sites: the gate itself
    /// and the tests of the gate. Listed explicitly so a new exemption is a deliberate edit here.
    private static final List<String> NOT_SITES =
        List.of("GoldenRegen.java", "GoldenRegenAssertsTest.java", "GoldenRegenSiteCensusTest.java");

    private static List<Path> regenSites() throws IOException {
        List<Path> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (NOT_SITES.contains(p.getFileName().toString())) {
                    continue;
                }
                if (Files.readString(p, StandardCharsets.UTF_8).contains(GoldenRegen.FLAG)) {
                    sites.add(p);
                }
            }
        }
        return sites;
    }

    @Test
    void everyRegenSiteRoutesThroughTheGateAndAnnounces() throws Exception {
        List<Path> sites = regenSites();

        // POSITIVE CONTROL. Without it an empty scan -- a moved source root, a renamed flag --
        // would pass this test by finding nothing, which is the failure mode a census invites.
        assertEquals(3, sites.size(),
            "expected the three known regeneration sites; a change here means a site was ADDED or "
                + "REMOVED and this census is the place that decision gets made: " + sites);

        for (Path site : sites) {
            String src = Files.readString(site, StandardCharsets.UTF_8);
            String name = site.getFileName().toString();
            assertTrue(src.contains("GoldenRegen.regenerateGolden("),
                name + " reads " + GoldenRegen.FLAG + " but does not route its write through "
                    + "GoldenRegen.regenerateGolden. A regeneration that skips the gate writes an "
                    + "unverified golden and reports PASSED -- the defect this plan exists to fix.");
            assertTrue(src.contains("GoldenRegen.announceRegen("),
                name + " regenerates without announcing it. A green build must never be "
                    + "indistinguishable from a regen.");
        }
    }

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
