package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Release-doc version pin (plan sirentide-040-release-cut; LatteX's identical guard, adopted
/// after the 0.3.0 review found stale version-pinned recipes by hand across 7 doc sites — the
/// doc-drift channel, sir442). The user-facing run recipes hard-code the CURRENT version in jar
/// names ({@code sirentide-X.Y.Z.jar}) and Maven coordinates ({@code com.sirentide:sirentide:X.Y.Z});
/// a version bump that freshens {@code build.gradle.kts} but not these recipes ships instructions
/// pointing at a jar the build no longer produces. This pins every such reference to the
/// {@code build.gradle.kts} version so future drift reddens at build time.
///
/// Scope is deliberate: {@code RELEASE_NOTES.md} version headings are history and never matched,
/// and {@code docs/DESIGN.md} is EXCLUDED — its "vendored {@code sirentide-X.Y.Z.jar}" line names
/// the STAFFICY-side vendored jar, which lags each cut by design until the part-B re-vendor lands
/// (the sir448–451 precedent: that line moves in its own follow-up, not in the cut).
class ReleaseDocVersionPinTest {

    /// Docs carrying CURRENT-version run recipes (NOT RELEASE_NOTES = history; NOT docs/DESIGN.md
    /// = the deliberately-lagging vendored pin).
    private static final List<String> RECIPE_DOCS = List.of("README.md", "QUICKSTART.md", "SLOWSTART.md");

    /// {@code com.sirentide:sirentide:X.Y.Z} (group 1) OR {@code sirentide-X.Y.Z.jar} (group 2).
    private static final Pattern PINNED = Pattern.compile(
        "com\\.sirentide:sirentide:(\\d+\\.\\d+\\.\\d+)|sirentide-(\\d+\\.\\d+\\.\\d+)\\.jar");

    /// The recipe refs present at the 0.4.0 cut — a floor (>=, so adding recipes is fine).
    private static final int MIN_RECIPE_REFS = 1;

    @Test
    void everyCurrentVersionRecipeMatchesTheBuildVersion() throws IOException {
        String buildVersion = buildGradleVersion();
        int found = 0;
        List<String> mismatches = new ArrayList<>();
        for (String doc : RECIPE_DOCS) {
            String text = Files.readString(Path.of(doc));
            Matcher m = PINNED.matcher(text);
            while (m.find()) {
                String v = m.group(1) != null ? m.group(1) : m.group(2);
                found++;
                if (!v.equals(buildVersion)) {
                    mismatches.add(doc + ": \"" + m.group() + "\" pins " + v
                        + " but build.gradle is " + buildVersion);
                }
            }
        }
        assertTrue(mismatches.isEmpty(),
            "release-doc version drift — a version-pinned coordinate/jar recipe does not match "
            + "build.gradle.kts " + buildVersion + ": " + mismatches);
        assertTrue(found >= MIN_RECIPE_REFS,
            "expected >= " + MIN_RECIPE_REFS + " version-pinned coordinate/jar recipes across "
            + RECIPE_DOCS + " (non-vacuity floor); found " + found
            + " — did a doc rewrite drop the run recipes?");
    }

    private static String buildGradleVersion() throws IOException {
        String gradle = Files.readString(Path.of("build.gradle.kts"));
        Matcher m = Pattern.compile("(?m)^version\\s*=\\s*\"(\\d+\\.\\d+\\.\\d+)\"").matcher(gradle);
        assertTrue(m.find(), "could not read the version from build.gradle.kts");
        return m.group(1);
    }
}
