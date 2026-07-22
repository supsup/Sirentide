package com.sirentide.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Receipts for the `sirentide render <file.md>` verb (plan
/// 6eb098d6-sirentide-local-render-check-cli slice A). Drives {@link Main#run} directly (never
/// {@link Main#main}) so a bad-input test case can assert an exit code WITHOUT a `System.exit` call
/// tearing down the test JVM — see the javadoc on `run`.
class MainTest {

    @TempDir
    Path tmp;

    private static final class Captured {
        final int exitCode;
        final String out;
        final String err;

        Captured(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }
    }

    private Captured run(String... args) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(new byte[0]),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Captured(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    private Captured runWithStdin(String stdin, String... args) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Captured(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    private Path writeMd(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // --- legacy no-args stdin path (must be byte-for-byte unchanged) ---------------------------

    @Test
    void noArgsRendersRawStdinDslLikeBeforeTheVerbExisted() throws IOException {
        String dsl = "pie\n  \"A\" : 60\n  \"B\" : 40\n";
        Captured c = runWithStdin(dsl);
        assertEquals(0, c.exitCode);
        assertEquals(Sirentide.render(dsl), c.out);
        assertEquals("", c.err);
    }

    // --- render <file.md>: fence found, happy path ---------------------------------------------

    @Test
    void rendersTheFirstFenceToStdout() throws IOException {
        Path md = writeMd("doc.md", """
            # Title

            ```sirentide
            pie
              "A" : 60
              "B" : 40
            ```
            """);
        Captured c = run("render", md.toString());
        assertEquals(0, c.exitCode);
        assertEquals(Sirentide.render("pie\n  \"A\" : 60\n  \"B\" : 40"), c.out);
        assertEquals("", c.err, "a clean parse prints no stderr diagnostic");
    }

    @Test
    void minusOWritesToAFileInsteadOfStdout() throws IOException {
        Path md = writeMd("doc.md", """
            ```sirentide
            pie
              "A" : 1
            ```
            """);
        Path out = tmp.resolve("out.svg");
        Captured c = run("render", md.toString(), "-o", out.toString());
        assertEquals(0, c.exitCode);
        assertEquals("", c.out, "with -o, nothing goes to stdout");
        assertEquals(Sirentide.render("pie\n  \"A\" : 1"), Files.readString(out, StandardCharsets.UTF_8));
    }

    // --- render <file.md>: no sirentide fence in the file → loud error, exit 2 -----------------

    @Test
    void noFenceInFileIsALoudErrorExit2AndWritesNothing() throws IOException {
        Path md = writeMd("nofence.md", "# Title\n\njust prose, no fence at all\n");
        Path out = tmp.resolve("should-not-exist.svg");
        Captured c = run("render", md.toString(), "-o", out.toString());
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("no ```sirentide fence found"), "stderr: " + c.err);
        assertEquals("", c.out);
        assertFalse(Files.exists(out), "no fence means nothing is written, not even a partial file");
    }

    // --- render <file>: unreadable file → exit 2 -------------------------------------------------

    @Test
    void unreadableFileIsExit2() throws IOException {
        Path missing = tmp.resolve("does-not-exist.md");
        Captured c = run("render", missing.toString());
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("cannot read"), "stderr: " + c.err);
        assertEquals("", c.out);
    }

    // --- render <file.md>: a fence body that fails to parse degrades, matching the bake --------

    @Test
    void unparseableFenceDegradesToTheInertShellButExitsZeroWithAStderrDiagnostic() throws IOException {
        // An unrecognized diagram-type keyword resolves to the Empty degrade target (PARSE_ERROR in
        // the diagnostics channel) — never a thrown exception, per the bake's own invariant.
        Path md = writeMd("bad.md", """
            ```sirentide
            not-a-real-diagram-type
            ```
            """);
        Captured c = run("render", md.toString());
        assertEquals(0, c.exitCode, "a degraded bake still exits 0 — it matches what /docs would bake");
        assertEquals(Sirentide.render("not-a-real-diagram-type"), c.out,
            "stdout carries the SAME degraded SVG the bake API would produce");
        assertFalse(c.err.isBlank(), "a one-line diagnostic must reach stderr");
        assertEquals(1, c.err.lines().count(), "exactly one diagnostic line");
    }

    // --- render -: verb-spelled alias of the legacy stdin shape --------------------------------

    @Test
    void renderDashIsTheLegacyStdinShapeNoFenceExtraction() throws IOException {
        String dsl = "pie\n  \"A\" : 1\n";
        Captured c = runWithStdin(dsl, "render", "-");
        assertEquals(0, c.exitCode);
        assertEquals(Sirentide.render(dsl), c.out);
        assertEquals("", c.err);
    }

    // --- bad invocations -------------------------------------------------------------------------

    @Test
    void unknownCommandIsExit2WithUsage() throws IOException {
        Captured c = run("frobnicate");
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("Usage:"), "stderr: " + c.err);
    }

    @Test
    void renderWithNoFileArgumentIsExit2WithUsage() throws IOException {
        Captured c = run("render");
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("Usage:"), "stderr: " + c.err);
    }

    @Test
    void trailingGarbageAfterFilePathIsExit2() throws IOException {
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        Captured c = run("render", md.toString(), "garbage");
        assertEquals(2, c.exitCode);
    }
}
