package com.sirentide.cli;

import static com.sirentide.cli.CliRun.LATTEX_JAR;
import static com.sirentide.cli.CliRun.md;
import static com.sirentide.cli.CliRun.run;
import static com.sirentide.cli.CliRun.runWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.cli.CliRun.Captured;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--math`: the CLI face of the {@link com.sirentide.api.MathFragmentRenderer} seam (plan d9f29911
/// item 1). Before it, `$\sqrt{2}$` through the CLI baked as its raw source text and was then
/// ellipsized; only a Java API caller who supplied a renderer got typeset math.
///
/// THE ORACLE IS THE API, byte for byte: `render --math` must produce exactly what
/// `Sirentide.render(dsl, new LatteXMathFragmentRenderer())` produces, so the CLI cannot drift into
/// a second, subtly different math path. And the no-`--math` output must still equal the plain
/// `Sirentide.render(dsl)`, so the flag is additive.
///
/// Combination cells with `--strict` and `--png` live here too, not in a later file: a past defect
/// in this CLI lived ONLY in the intersection of two flags each tested alone (StrictPngFlagMatrixTest).
class CliMathFlagTest {

    @TempDir
    Path tmp;

    private static final String MATH_BODY =
        "flowchart TD\n    A[\"$\\sqrt{2}$\"] --> B[\"ratio $\\frac{a}{b}$\"]";

    /// Unbalanced brace: LatteX throws, the adapter returns empty, and Sirentide falls back to the
    /// raw `$...$` text. That fallback is LOUD in the picture; the CLI makes it loud in the channel.
    private static final String MALFORMED_BODY = "flowchart TD\n    A[\"bad $\\frac{a$\"] --> B[ok]";

    @BeforeAll
    static void jar() {
        CliRun.assertLatteXJarPresent();
    }

    private static String body(Path md) throws IOException {
        return FenceExtractor.extractFirstSirentideFence(Files.readString(md, StandardCharsets.UTF_8));
    }

    @Test
    void mathFlagTypesetsExactlyWhatTheApiDoesWithLatteX() throws IOException {
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(0, c.exitCode(), c.err());
        assertEquals("", c.err(), "a clean math render prints nothing to stderr");
        String api = Sirentide.render(body(md), new LatteXMathFragmentRenderer());
        assertEquals(api, c.out(), "CLI --math must be byte-identical to the API with the LatteX renderer");
        assertNotEquals(Sirentide.render(body(md)), c.out(), "--math must change the bake");
        assertFalse(c.out().contains("\\sqrt"), "no raw LaTeX source survives");
        assertTrue(c.out().matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
            "a MathBox wrapper carries the typeset fragment");
    }

    @Test
    void withoutMathFlagTheBakeIsTheUnchangedPlainRender() throws IOException {
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--lattex", LATTEX_JAR.toString());
        assertEquals(0, c.exitCode());
        assertEquals(Sirentide.render(body(md)), c.out(),
            "--lattex alone configures the backend; it does not switch math on");
    }

    @Test
    void mathOnTheStdinArmMatchesTheApi() throws IOException {
        String dsl = MATH_BODY + "\n";
        Captured c = runWithStdin(dsl, "render", "-", "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(0, c.exitCode(), c.err());
        assertEquals(Sirentide.render(dsl, new LatteXMathFragmentRenderer()), c.out());
    }

    @Test
    void mathWithNoBackendIsALoudUsageErrorAndWritesNothing() throws IOException {
        assumeTrue(System.getenv("SIRENTIDE_LATTEX_JAR") == null, "env names a jar; cell not expressible");
        Path md = md(tmp, "m.md", MATH_BODY);
        Path out = tmp.resolve("out.svg");
        Captured c = run("render", md.toString(), "--math", "-o", out.toString());
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("--math needs the LatteX jar"), c.err());
        assertFalse(Files.exists(out), "a usage error renders nothing");
        assertEquals(0, c.outBytes().length);
    }

    @Test
    void mathWithAJarThatHoldsNoLatteXIsExit2NotARawTextBake() throws IOException {
        Path notLattex = tmp.resolve("empty.jar");
        Files.write(notLattex, new byte[] {'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--math", "--lattex", notLattex.toString());
        assertEquals(2, c.exitCode(), "a backend that cannot load must not degrade to raw text at exit 0");
        assertTrue(c.err().contains("cannot load LatteX"), c.err());
        assertEquals(0, c.outBytes().length);
    }

    @Test
    void mathWithAMissingJarPathIsExit2() throws IOException {
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--math", "--lattex", tmp.resolve("nope.jar").toString());
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("LatteX jar not readable"), c.err());
    }

    @Test
    void lattexNeedsAValue() throws IOException {
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--math", "--lattex");
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("--lattex needs a value"), c.err());
    }

    // --- the caveat channel: math that did NOT typeset ---------------------------------------

    @Test
    void untypesetMathIsACaveatOnStderrButStillExit0() throws IOException {
        Path md = md(tmp, "bad.md", MALFORMED_BODY);
        Captured c = run("render", md.toString(), "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(0, c.exitCode(), "the bake happened; a fallback is a caveat, not a failure");
        assertEquals(Sirentide.render(body(md), new LatteXMathFragmentRenderer()), c.out());
        assertTrue(c.err().contains("did not typeset"), c.err());
        assertTrue(c.err().contains("\\frac{a"), "the caveat names the failing source: " + c.err());
    }

    @Test
    void untypesetMathFailsStrictAndTheSvgIsStillWritten() throws IOException {
        Path md = md(tmp, "bad.md", MALFORMED_BODY);
        Path out = tmp.resolve("out.svg");
        Captured c = run("render", md.toString(), "--math", "--lattex", LATTEX_JAR.toString(),
            "--strict", "-o", out.toString());
        assertEquals(1, c.exitCode(), c.err());
        assertTrue(c.err().contains("--strict"), c.err());
        assertEquals(Sirentide.render(body(md), new LatteXMathFragmentRenderer()),
            Files.readString(out, StandardCharsets.UTF_8), "the rejected SVG is still on disk to inspect");
    }

    @Test
    void cleanMathPassesStrict() throws IOException {
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--strict", "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(0, c.exitCode(), c.err());
        assertEquals("", c.err());
    }

    @Test
    void untypesetMathFailsStrictOnTheStdinArmToo() throws IOException {
        Captured c = runWithStdin(MALFORMED_BODY + "\n", "render", "-", "--strict",
            "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(1, c.exitCode(), "the stdin arm must not disarm --strict: " + c.err());
    }

    // --- with --png (no hermetic PNG success cell; see StrictPngFlagMatrixTest's header) ------

    @Test
    void mathStrictPngStrictFailureOutranksThePngFailure() throws IOException {
        Path md = md(tmp, "bad.md", MALFORMED_BODY);
        Path out = tmp.resolve("out.svg");
        Captured c = run("render", md.toString(), "--math", "--lattex", LATTEX_JAR.toString(),
            "--strict", "-o", out.toString(), "--png", tmp.resolve("x.png").toString(),
            "--brewshot", tmp.resolve("no-brewshot.jar").toString());
        assertEquals(1, c.exitCode(), "strict outranks the PNG failure, as for dropped statements");
        assertTrue(c.err().contains("BrewShot jar not readable"), "the PNG failure is still reported");
        assertTrue(Files.exists(out));
    }

    @Test
    void cleanMathWithAFailingPngIsThePngCode() throws IOException {
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--math", "--lattex", LATTEX_JAR.toString(),
            "--strict", "--png", tmp.resolve("x.png").toString(),
            "--brewshot", tmp.resolve("no-brewshot.jar").toString());
        assertEquals(2, c.exitCode(), "--math must not mask a PNG failure");
    }

    @Test
    void mathBackendIsResolvedBeforeThePngBackendCanBeBlamed() throws IOException {
        assumeTrue(System.getenv("SIRENTIDE_LATTEX_JAR") == null, "env names a jar; cell not expressible");
        Path md = md(tmp, "m.md", MATH_BODY);
        Captured c = run("render", md.toString(), "--math", "--png", tmp.resolve("x.png").toString(),
            "--brewshot", tmp.resolve("no-brewshot.jar").toString());
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("--math needs the LatteX jar"), c.err());
        assertEquals(0, c.outBytes().length, "usage errors render nothing");
    }
}
