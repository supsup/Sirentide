package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// THE PROPERTY OF THE DEFAULT PATH (sirentide room 1197): without the switch, nothing a test
/// generates is addressed under the tracked examples tree.
///
/// Both arms are asserted in every test below, because a one-armed test cannot tell "the default
/// goes to build/" from "everything goes to build/" (regeneration silently dead) or from
/// "everything goes to the tracked tree" (the defect this exists for).
///
/// The tracked directory is spelled here INDEPENDENTLY of GeneratedExamples on purpose. A test
/// that asked the helper where the tracked tree is would agree with any helper, including one
/// whose two roots had been swapped.
final class GeneratedExamplesTest {

    private static final Path TRACKED = Path.of("examples").toAbsolutePath();
    private static final Path BUILD = Path.of("build").toAbsolutePath();

    /// Runs `body` with the two switches set as given (null clears one) and restores both after.
    /// The suite is single-threaded (no junit-platform.properties enables parallel execution), so
    /// no other test observes the window.
    private static void withSwitches(String regenerate, String legacyShowcase, Runnable body) {
        String oldRegenerate = System.getProperty(GeneratedExamples.SWITCH);
        String oldLegacy = System.getProperty(GeneratedExamples.LEGACY_SHOWCASE_SWITCH);
        try {
            set(GeneratedExamples.SWITCH, regenerate);
            set(GeneratedExamples.LEGACY_SHOWCASE_SWITCH, legacyShowcase);
            body.run();
        } finally {
            set(GeneratedExamples.SWITCH, oldRegenerate);
            set(GeneratedExamples.LEGACY_SHOWCASE_SWITCH, oldLegacy);
        }
    }

    private static void set(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void withoutTheSwitchEveryOutputIsUnderTheBuildDirectoryAndNoneIsUnderTheTrackedTree() {
        for (String off : new String[] {null, "false", "", "yes"}) {
            withSwitches(off, off, () -> {
                assertFalse(GeneratedExamples.regenerating(), "switch value " + off);
                assertFalse(GeneratedExamples.regeneratingShowcase(), "switch value " + off);
                for (Path out : List.of(GeneratedExamples.galleryOutputDir(),
                        GeneratedExamples.showcaseOutputDir())) {
                    assertTrue(out.startsWith(BUILD),
                        "default output must be under the build directory, got " + out);
                    assertFalse(out.startsWith(TRACKED),
                        "default output must never be under the tracked examples tree, got " + out);
                }
            });
        }
    }

    @Test
    void underTheSwitchTheOutputIsTheTrackedTreeMirroredExactly() {
        withSwitches("true", null, () -> {
            assertTrue(GeneratedExamples.regenerating());
            assertEquals(TRACKED.resolve("gallery"), GeneratedExamples.galleryOutputDir());
            assertEquals(TRACKED, GeneratedExamples.showcaseOutputDir());
        });
        // The build-directory tree mirrors the tracked layout, so the two can be diffed directly.
        withSwitches(null, null, () -> {
            assertEquals(GeneratedExamples.buildRoot().resolve("gallery"),
                GeneratedExamples.galleryOutputDir());
            assertEquals(GeneratedExamples.buildRoot(), GeneratedExamples.showcaseOutputDir());
            assertTrue(GeneratedExamples.buildRoot().startsWith(BUILD));
        });
    }

    @Test
    void theOlderShowcaseFlagMovesTheHtmlPagesAndNeverTheHostDependentGallery() {
        withSwitches(null, "true", () -> {
            assertFalse(GeneratedExamples.regenerating());
            assertTrue(GeneratedExamples.regeneratingShowcase());
            assertEquals(TRACKED, GeneratedExamples.showcaseOutputDir());
            assertTrue(GeneratedExamples.galleryOutputDir().startsWith(BUILD),
                "the showcase flag must not redirect the gallery PNGs into the tracked tree");
        });
    }

    @Test
    void trackedReadsResolveInsideTheTrackedTreeInBothModes() {
        for (String mode : new String[] {null, "true"}) {
            withSwitches(mode, null, () -> {
                assertEquals(TRACKED.resolve("showcase.html"), GeneratedExamples.tracked("showcase.html"));
                assertEquals(TRACKED.resolve("gallery").resolve("GALLERY.md"),
                    GeneratedExamples.tracked("gallery", "GALLERY.md"));
            });
        }
    }

    @Test
    void theBannerNamesTheWriterTheHostAndTheBrowser() {
        String banner = GeneratedExamples.banner("SomeTest", "3 file(s)", "HeadlessChrome/1.2.3");
        assertTrue(banner.startsWith("SIRENTIDE EXAMPLES REGEN: SomeTest is writing 3 file(s)"), banner);
        assertTrue(banner.contains("host=" + System.getProperty("os.name")), banner);
        assertTrue(banner.contains(System.getProperty("os.arch")), banner);
        assertTrue(banner.contains("browser=HeadlessChrome/1.2.3"), banner);
        assertTrue(banner.contains("BREWSHOT_CHROME="), banner);
        assertTrue(banner.contains("LINUX render"), banner);
    }

    @Test
    void theBuildScriptForwardsTheSwitchTheHelperReads() throws Exception {
        // A switch the build never passes to the forked test JVM enables nothing, and would leave
        // the documented regeneration command a silent no-op.
        String build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(build.contains("systemProperty(\"" + GeneratedExamples.SWITCH + "\""),
            "build.gradle.kts must forward -D" + GeneratedExamples.SWITCH + " to the test JVM");
        assertTrue(build.contains("systemProperty(\"" + GeneratedExamples.LEGACY_SHOWCASE_SWITCH + "\""),
            "build.gradle.kts must keep forwarding -D" + GeneratedExamples.LEGACY_SHOWCASE_SWITCH);
    }

    // ---------------------------------------------------------------------------------------
    // THE SOURCE CENSUS, and exactly what it is.
    //
    // WHAT IT MATCHES: in every .java file under src/test/java except GeneratedExamples.java and
    // this file (both excluded by full relative path, not by basename), a double quote followed
    // immediately by the word examples and then a double quote or a slash. That is a string
    // literal that IS the tracked directory or begins a path into it, however it is then used:
    // a two-argument path factory, a one-argument one, a File, a resolve.
    //
    // WHAT IT CANNOT SEE, so that nobody reads green as more than it is:
    //   - a path computed without that literal (a concatenation, an absolute path, a constant
    //     from another class, a walk up from another directory);
    //   - a WRITE through GeneratedExamples.tracked(...), which hands out tracked paths for
    //     reading and cannot stop a caller writing to one;
    //   - anything outside src/test/java, including src/main code a test invokes.
    // It is a predicate over SPELLING. The golden census in this repository failed three times
    // for exactly that reason, and the answer there was to audit WRITES instead. The same answer
    // is given here: the examples write audit on the test task in build.gradle.kts hashes the
    // tracked tree before and after the test JVM and fails a default run that changed it. This
    // census is the cheap early signal; that audit is the property.
    // ---------------------------------------------------------------------------------------

    private static final Pattern NAMES_THE_TRACKED_TREE = Pattern.compile("\"examples[\"/]");

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");
    private static final List<String> MAY_NAME_IT = List.of(
        "com/sirentide/GeneratedExamples.java",
        "com/sirentide/GeneratedExamplesTest.java");

    @Test
    void onlyTheHelperSpellsTheTrackedExamplesDirectory() throws Exception {
        // Positive controls on the predicate itself, so a pattern that matches nothing cannot pass.
        assertTrue(NAMES_THE_TRACKED_TREE.matcher("Path.of(\"examples\", \"gallery\")").find());
        assertTrue(NAMES_THE_TRACKED_TREE.matcher("new File(\"examples/showcase.html\")").find());
        assertFalse(NAMES_THE_TRACKED_TREE.matcher("see examples/gallery in the docs").find());

        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(TEST_SOURCES)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path source : sources) {
            String relative = TEST_SOURCES.relativize(source).toString().replace('\\', '/');
            scanned.add(relative);
            boolean names = NAMES_THE_TRACKED_TREE
                .matcher(Files.readString(source, StandardCharsets.UTF_8)).find();
            if (MAY_NAME_IT.contains(relative)) {
                // Positive control on the WALK: the two exempt files really do carry the literal,
                // so a scan that read nothing, or read the wrong tree, fails here.
                assertTrue(names, relative + " is exempt because it names the tracked tree; it "
                    + "no longer does, so the exemption or the pattern is stale");
            } else if (names) {
                offenders.add(relative);
            }
        }
        // The three writers this change rerouted must be among the files actually read.
        for (String rerouted : List.of("com/sirentide/BrewShotGalleryTest.java",
                "com/sirentide/MathGalleryBrewShotTest.java", "com/sirentide/ShowcaseGenTest.java")) {
            assertTrue(scanned.contains(rerouted), "the census did not read " + rerouted);
        }
        assertTrue(scanned.containsAll(MAY_NAME_IT), "the census did not read its own exemptions");
        assertEquals(List.of(), offenders,
            "these test sources spell the tracked examples directory themselves; ask "
                + "GeneratedExamples for the output directory (or tracked(...) to READ a reference)");
    }
}
