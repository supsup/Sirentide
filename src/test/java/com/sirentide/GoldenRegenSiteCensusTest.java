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

    /// A file is a REGENERATION SITE if it reads the regen flag BY ANY ROUTE.
    ///
    /// This predicate is the needs-fix-1077 repair and it is the whole finding. The first version
    /// asked `contains(GoldenRegen.FLAG)` -- the literal property name -- and NONE of the three
    /// real sites reads the flag that way. All three call GoldenRegen.updating(). They matched the
    /// census only because their DOC COMMENTS and error messages happen to contain the flag name,
    /// so the enumeration keyed on PROSE while believing it keyed on code.
    ///
    /// Two consequences, both demonstrated by the reviewer rather than argued. A fourth site
    /// calling updating() and writing directly was INVISIBLE: green at 1196/0/0/0 while writing an
    /// unverified tracked golden with no banner. And deleting a doc comment from a real site would
    /// have DROPPED it from the census, turning a comment edit into a false alarm.
    ///
    /// My own F6 consolidation is what made the invisible route the idiomatic one. Centralising the
    /// flag so nobody retypes it is correct, and it removed the very token this census was reading.
    /// The fix created the blind spot it was built to close, which is why the predicate now names
    /// the CALL as well as the literal.
    private static boolean readsTheRegenFlag(String src) {
        // A RAW Boolean.getBoolean of the golden flag necessarily spells the flag, so the literal
        // clause already covers it. I briefly matched any Boolean.getBoolean( at all, which pulled
        // in ShowcaseGenTest -- a structurally identical regen site for a DIFFERENT artifact under
        // a DIFFERENT flag. That was a false positive for this census, and it is reported as a
        // class-sweep finding rather than silently widened away.
        return src.contains("GoldenRegen.updating(")   // the idiomatic route, and the one that hid
            || src.contains(GoldenRegen.FLAG);         // the literal, or any raw read of it
    }

    private static List<Path> testSources() throws IOException {
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<Path> regenSites() throws IOException {
        List<Path> sites = new ArrayList<>();
        for (Path p : testSources()) {
            if (NOT_SITES.contains(p.getFileName().toString())) {
                continue;
            }
            if (readsTheRegenFlag(Files.readString(p, StandardCharsets.UTF_8))) {
                sites.add(p);
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
    void nothingOutsideTheGateNamesTheGoldenDirectory() throws Exception {
        // THE BEHAVIOURAL HALF (needs-fix 1077). The predicate above still reads source text, and a
        // site that reads no flag at all but writes a golden anyway would slip past it. This closes
        // that from the other end: GoldenRegen.GOLDEN_DIR is now private, so no other class CAN
        // name the directory, and this asserts nobody re-spells the literal to get around it.
        //
        // It only became assertable once GoldenSvgTest stopped carrying its own duplicate writer.
        // That writer was not a defect -- it was injected through the gate -- but while it existed
        // the directory had two spellings and could not be fenced at all.
        String construct = "Path.of(\"src/test/resources/golden";
        List<String> offenders = new ArrayList<>();
        for (Path p : testSources()) {
            if (p.getFileName().toString().equals("GoldenRegen.java")) {
                continue;
            }
            if (Files.readString(p, StandardCharsets.UTF_8).contains(construct)) {
                offenders.add(p.getFileName().toString());
            }
        }
        assertEquals(List.of(), offenders,
            "only GoldenRegen may name the tracked golden directory; a class that spells the path "
                + "itself can write an unverified golden without passing the gate, which is the "
                + "defect this plan exists to close: " + offenders);

        // POSITIVE CONTROL on the scan itself: the construct must be findable where it DOES live,
        // or this test passes by looking in the wrong place.
        assertTrue(Files.readString(TEST_SOURCES.resolve("GoldenRegen.java"), StandardCharsets.UTF_8)
                .contains(construct),
            "the scan found the directory construct nowhere at all, including in GoldenRegen -- "
                + "the probe is broken rather than the tree being clean");
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
