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
        return runWithStdin("", args);
    }

    /// The same driver with a REAL stdin body.
    ///
    /// Its absence is why sirentide/905 survived this file. Every case above fed
    /// `new ByteArrayInputStream(new byte[0])`, so no test here could drive `render -` with actual
    /// DSL — and a blank stdin renders to the `Empty` IR, reports OK, and writes an empty SVG at
    /// exit 0, which is indistinguishable from the defect. The fixture could not express the
    /// failing case, so the seven tests below it were all honestly green while `render - --png`
    /// silently produced nothing. A narrow fixture does not announce itself as narrow.
    private Captured runWithStdin(String stdin, String... args) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Captured(code, outBuf.toString(StandardCharsets.UTF_8),
            errBuf.toString(StandardCharsets.UTF_8));
    }

    /// Raw DSL for the stdin arm — no fence, since `render -` does no fence extraction.
    private static final String RAW_DSL = """
        classDiagram
          class Order {
            +int id
          }
        """;

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

    // ---- the stdin arm: sirentide/905 -------------------------------------------------------

    /// THE REGRESSION. `render -` used to `return` before the write tail, so `--png` on stdin was
    /// accepted, validated, and then silently ignored: exit 0, an SVG, and no PNG.
    ///
    /// This is LOAD-BEARING and cheap, and it needs no real BrewShot jar. On the old code the
    /// unreadable-jar guard was unreachable from this arm, so the run exited 0; reaching the guard
    /// at all is the whole property under test. Asserting the REFUSAL rather than a successful
    /// screenshot is what keeps it runnable in CI — and it discriminates just as sharply, because
    /// exit 0 here is precisely the defect.
    @Test
    void stdinArmReachesTheScreenshotBackend_pngOnStdinCannotSilentlyExitZero() throws IOException {
        Path svg = tmp.resolve("out.svg");
        Path png = tmp.resolve("out.png");
        Captured c = runWithStdin(RAW_DSL, "render", "-", "-o", svg.toString(),
            "--png", png.toString(), "--brewshot", tmp.resolve("does-not-exist.jar").toString());

        assertEquals(2, c.exitCode(),
            "exit 0 here IS the defect: it means the stdin arm returned before writePng, so every "
                + "--png guard sat downstream of a return. stderr: " + c.err());
        assertTrue(c.err().contains("not readable"), c.err());
        assertFalse(Files.exists(png), "no PNG, and the failure said so");
        assertTrue(Files.size(svg) > 0,
            "the SVG still lands — the render genuinely succeeded, exactly as on the file arm");
    }

    /// The other half: with a backend that never gets consulted, the stdin arm must still behave
    /// like the file arm. Without this, the test above could pass on an implementation that simply
    /// refuses `--png` on stdin outright, which was the OTHER option offered in review and is a
    /// different contract from the one this branch chose.
    @Test
    void stdinArmStillRendersNormallyWhenPngIsNotAsked() throws IOException {
        Path svg = tmp.resolve("out.svg");
        Captured c = runWithStdin(RAW_DSL, "render", "-", "-o", svg.toString(), "--brewshot", "");
        assertEquals(0, c.exitCode(), c.err());
        assertTrue(Files.size(svg) > 0);
    }

    /// The equivalence the original comment ASSERTED, now pinned as a test. Both raw-DSL arms —
    /// no-args and `render -` — must refuse an unrenderable source identically. This is the
    /// property that made the old `return` look safe; pinning it means the shared seam can be
    /// refactored without the claim quietly going stale again.
    @Test
    void bothRawDslArmsRefuseAnUnrenderableSourceIdentically() throws IOException {
        String garbage = "!!! not a diagram !!!";
        Captured noArgs = runWithStdin(garbage);
        Captured dashArm = runWithStdin(garbage, "render", "-");

        assertEquals(1, noArgs.exitCode(), noArgs.err());
        assertEquals(noArgs.exitCode(), dashArm.exitCode(),
            "the two raw-DSL arms must refuse with the same exit code");
        assertEquals(noArgs.err(), dashArm.err(),
            "and with the same diagnostic — they share one refusal seam by construction");
    }
}
