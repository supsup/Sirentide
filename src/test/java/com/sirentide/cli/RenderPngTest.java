package com.sirentide.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `sirentide render --png` — the local review artifact in one step (plan 6eb098d6 slice B).
///
/// WHAT THESE PIN, and what they deliberately do not. The happy path needs a real BrewShot jar,
/// which drags a browser in and is not a dependency this project takes — so it is verified BY HAND
/// against the real jar and NOT asserted here. What IS asserted is every way the feature can fail,
/// because the failure modes are where the value is: a `--png` that silently produced no PNG would
/// be this codebase's signature defect (a surface reporting success while establishing nothing),
/// and that is exactly the shape a test can catch cheaply.
///
/// ENV INDEPENDENCE. `System.getenv` cannot be mocked, and a developer with SIRENTIDE_BREWSHOT_JAR
/// exported would silently flip the meaning of the "no backend resolved" cases. So those tests pass
/// `--brewshot ""` — the blank spelling reaches the same "nothing resolved" branch as an unset
/// variable, and it does so deterministically on every machine.
class RenderPngTest {

    @TempDir
    Path tmp;

    private static final String FENCE = """
        ```sirentide
        classDiagram
          class Order {
            +int id
          }
        ```
        """;

    private record Captured(int exitCode, String out, String err) {}

    private Captured run(String... args) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(new byte[0]),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Captured(code, outBuf.toString(StandardCharsets.UTF_8),
            errBuf.toString(StandardCharsets.UTF_8));
    }

    private Path md() throws IOException {
        Path p = tmp.resolve("doc.md");
        Files.writeString(p, FENCE, StandardCharsets.UTF_8);
        return p;
    }

    // ---- the failure that matters most ------------------------------------------------------

    /// A `--png` with no backend resolved must REFUSE, not render-and-skip. The silent-skip version
    /// hands the author an SVG, exit 0, and no screenshot — and they find out when they go looking
    /// for a file that is not there.
    @Test
    void pngWithoutABackendIsALoudUsageErrorAndWritesNOTHING() throws IOException {
        Path svg = tmp.resolve("out.svg");
        Path png = tmp.resolve("out.png");
        Captured c = run("render", md().toString(), "-o", svg.toString(),
            "--png", png.toString(), "--brewshot", "");

        assertEquals(2, c.exitCode(), "no backend resolved is a usage error: " + c.err());
        assertTrue(c.err().contains("--png needs the BrewShot jar"), c.err());
        assertTrue(c.err().contains("SIRENTIDE_BREWSHOT_JAR"),
            "the message must name the env var, or the author cannot act on it: " + c.err());
        assertFalse(Files.exists(png), "no PNG");
        assertFalse(Files.exists(svg),
            "and NO SVG either — the check runs BEFORE rendering, so the author is not left with a "
                + "half-done render they have to reason about");
    }

    @Test
    void anUnreadableBrewshotJarIsRefusedByPath() throws IOException {
        Path svg = tmp.resolve("out.svg");
        Captured c = run("render", md().toString(), "-o", svg.toString(),
            "--png", tmp.resolve("out.png").toString(),
            "--brewshot", tmp.resolve("does-not-exist.jar").toString());

        assertEquals(2, c.exitCode(), c.err());
        assertTrue(c.err().contains("not readable"), c.err());
    }

    /// A file that EXISTS but is not a runnable jar. This is the one case that actually drives the
    /// subprocess, so it is the only test here that proves the shell-out is wired at all — without
    /// it, every assertion above passes for an implementation that never launches anything.
    @Test
    void aBrewshotThatFailsIsReportedWithItsExitCodeAndOutput() throws IOException {
        Path notAJar = tmp.resolve("not-a.jar");
        Files.writeString(notAJar, "this is not a jar", StandardCharsets.UTF_8);
        Path svg = tmp.resolve("out.svg");
        Captured c = run("render", md().toString(), "-o", svg.toString(),
            "--png", tmp.resolve("out.png").toString(), "--brewshot", notAJar.toString());

        assertEquals(2, c.exitCode(), c.err());
        assertTrue(c.err().contains("BrewShot failed"), c.err());
        assertTrue(Files.exists(svg),
            "the SVG SURVIVES a screenshot failure — the render genuinely succeeded and throwing it "
                + "away would lose good work over a missing side artifact");
    }

    // ---- the flag loop must not have broken the shapes that already worked -------------------

    @Test
    void theTwoPreExistingArgShapesStillParse() throws IOException {
        Captured bare = run("render", md().toString());
        assertEquals(0, bare.exitCode(), bare.err());
        assertTrue(bare.out().contains("<svg"), "bare render still goes to stdout");

        Path svg = tmp.resolve("out.svg");
        Captured toFile = run("render", md().toString(), "-o", svg.toString());
        assertEquals(0, toFile.exitCode(), toFile.err());
        assertTrue(Files.size(svg) > 0, "-o still writes the SVG");
    }

    @Test
    void anUnknownFlagStillExitsTwoWithTheSameMessage() throws IOException {
        Captured c = run("render", md().toString(), "--nope", "x");
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("bad arguments after the file path"),
            "the pre-existing rejection message is unchanged: " + c.err());
    }

    /// The new arity hazard the flag loop introduces: a recognised flag with nothing after it.
    /// Before this change the parser could not reach that state.
    @Test
    void aTrailingFlagWithNoValueIsRefused() throws IOException {
        Captured c = run("render", md().toString(), "--png");
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("--png needs a value"), c.err());
    }

    @Test
    void pngIsOptional_aPlainRenderNeverConsultsTheBackend() throws IOException {
        // The negative control for the whole feature: with no --png, a missing backend is
        // irrelevant. If this ever fails, --png stopped being opt-in.
        Path svg = tmp.resolve("out.svg");
        Captured c = run("render", md().toString(), "-o", svg.toString(), "--brewshot", "");
        assertEquals(0, c.exitCode(), c.err());
        assertTrue(Files.size(svg) > 0);
    }
}
