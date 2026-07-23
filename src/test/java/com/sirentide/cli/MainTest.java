package com.sirentide.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sirentide.api.Sirentide;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Receipts for the `sirentide render <file.md>` verb (plan
/// 6eb098d6-sirentide-local-render-check-cli slice A; corrections from review sirentide/471).
/// Drives {@link Main#run} directly (never {@link Main#main}) so a bad-input test case can assert
/// an exit code WITHOUT a `System.exit` call tearing down the test JVM — see the javadoc on `run`.
///
/// Exit-code contract under test: 0 = rendered (bake would embed this SVG); 1 = fence found but
/// it does not render (bake would keep the fence verbatim + caption; NOTHING written); 2 = loud
/// usage/IO error (nothing new written, existing destinations untouched).
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

    /// A stdout stand-in whose consumer is gone: every byte "written" throws, which PrintStream
    /// swallows into its error flag — exactly what a closed pipe looks like to the CLI.
    private static PrintStream brokenStdout() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        }, true, StandardCharsets.UTF_8);
    }

    /// Asserts `dir` holds no leftover `.sirentide-*.svg.tmp` sibling from the atomic-write path.
    private static void assertNoTempLitter(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> litter = s.filter(p -> p.getFileName().toString().startsWith(".sirentide-")).toList();
            assertTrue(litter.isEmpty(), "temp-file litter left behind: " + litter);
        }
    }

    // --- legacy no-args stdin path (SVG bytes byte-for-byte unchanged) --------------------------

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
        assertNoTempLitter(tmp);
    }

    // --- bake-parity extraction at the CLI surface (review sirentide/471 B2) --------------------

    @Test
    void aFenceNestedInsideAnOuterFenceIsNoFenceFoundExit2() throws IOException {
        // Review 471's discriminator input: /docs leaves this literal, so the render-check must
        // refuse loudly instead of rendering an SVG the page will never contain.
        Path md = writeMd("nested.md", "~~~\n```sirentide\nflowchart TD\n  A --> B\n```\n~~~\n");
        Captured c = run("render", md.toString());
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("no ```sirentide fence found"), "stderr: " + c.err);
        assertEquals("", c.out);
    }

    @Test
    void aFourBacktickSirentideOpenerRendersLikeTheBakeWould() throws IOException {
        // Review 471's opposite-direction discriminator: the bake's scanner captures this.
        Path md = writeMd("four.md", "````sirentide\npie\n  \"A\" : 1\n```\n");
        Captured c = run("render", md.toString());
        assertEquals(0, c.exitCode, "stderr: " + c.err);
        assertEquals(Sirentide.render("pie\n  \"A\" : 1"), c.out);
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

    // --- render <file>: unreadable / over-cap file → exit 2 -------------------------------------

    @Test
    void unreadableFileIsExit2() throws IOException {
        Path missing = tmp.resolve("does-not-exist.md");
        Captured c = run("render", missing.toString());
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("cannot read"), "stderr: " + c.err);
        assertEquals("", c.out);
    }

    @Test
    void aMarkdownFileOverTheCapIsExit2NotATruncatedParse() throws IOException {
        byte[] big = new byte[Main.MAX_MARKDOWN_BYTES + 1];
        Arrays.fill(big, (byte) 'x');
        Path md = tmp.resolve("big.md");
        Files.write(md, big);
        Captured c = run("render", md.toString());
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("markdown cap"), "stderr: " + c.err);
        assertEquals("", c.out);
    }

    // --- render <file.md>: a fence that does not render → exit 1, NOTHING written (471 B3) ------

    @Test
    void anUnrenderableFenceIsExit1WithNothingWritten() throws IOException {
        // /docs would NOT serve an SVG for this fence — SirentideDiagramConverter keeps the fence
        // verbatim and prepends a visible caption. The old "exit 0 + inert shell" posture claimed
        // bake parity it did not have; the truthful contract is loud and writes nothing.
        Path md = writeMd("bad.md", """
            ```sirentide
            not-a-real-diagram-type
            ```
            """);
        Captured c = run("render", md.toString());
        assertEquals(1, c.exitCode, "an unrenderable fence is a LOUD failed render-check");
        assertEquals("", c.out, "the inert shell is never what the page would contain — write nothing");
        assertTrue(c.err.contains("did not render"), "stderr: " + c.err);
        assertTrue(c.err.contains("verbatim"), "stderr must say what /docs WOULD do: " + c.err);
        assertEquals(1, c.err.lines().count(), "exactly one diagnostic line");
    }

    @Test
    void anUnrenderableFenceWithMinusOLeavesAnExistingDestinationByteIdentical() throws IOException {
        Path md = writeMd("bad.md", "```sirentide\nnot-a-real-diagram-type\n```\n");
        Path dest = tmp.resolve("out.svg");
        String sentinel = "<svg>the previous good artifact</svg>";
        Files.writeString(dest, sentinel, StandardCharsets.UTF_8);
        Captured c = run("render", md.toString(), "-o", dest.toString());
        assertEquals(1, c.exitCode);
        assertEquals(sentinel, Files.readString(dest, StandardCharsets.UTF_8),
            "a failed render-check must never touch the destination");
        assertNoTempLitter(tmp);
    }

    // --- -o atomic-write policy (review sirentide/471 B1) ---------------------------------------

    @Test
    void minusOReplacesAnExistingDestinationCompletely() throws IOException {
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        Path dest = tmp.resolve("out.svg");
        Files.writeString(dest, "old stale artifact that must be fully replaced", StandardCharsets.UTF_8);
        Captured c = run("render", md.toString(), "-o", dest.toString());
        assertEquals(0, c.exitCode);
        assertEquals(Sirentide.render("pie\n  \"A\" : 1"), Files.readString(dest, StandardCharsets.UTF_8));
        assertNoTempLitter(tmp);
    }

    @Test
    void aFailedMinusOWriteLeavesAnExistingDestinationByteIdentical() throws IOException {
        // Failure injection: the destination's directory is made unwritable, so the temp-sibling
        // creation (the FIRST write step) fails. Review 471's real-jar probe showed the old
        // direct-truncate write destroyed the previous file on a reported failure; the atomic
        // temp+move path must leave it byte-identical.
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "needs POSIX permissions to inject the write failure");
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        Path dir = Files.createDirectory(tmp.resolve("outdir"));
        Path dest = dir.resolve("out.svg");
        String sentinel = "<svg>the previous good artifact</svg>";
        Files.writeString(dest, sentinel, StandardCharsets.UTF_8);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir,
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assumeFalse(Files.isWritable(dir), "directory still writable (running as root?)");
            Captured c = run("render", md.toString(), "-o", dest.toString());
            assertEquals(2, c.exitCode);
            assertTrue(c.err.contains("cannot write"), "stderr: " + c.err);
            assertEquals(sentinel, Files.readString(dest, StandardCharsets.UTF_8),
                "a failed -o must leave the existing destination byte-identical");
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
        assertNoTempLitter(dir);
    }

    @Test
    void minusODestinationThatIsADirectoryIsExit2AndUntouched() throws IOException {
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        Path dir = Files.createDirectory(tmp.resolve("dest-dir"));
        Captured c = run("render", md.toString(), "-o", dir.toString());
        assertEquals(2, c.exitCode);
        assertTrue(c.err.contains("is a directory"), "stderr: " + c.err);
        assertTrue(Files.isDirectory(dir), "the directory must survive untouched");
        assertNoTempLitter(dir);
    }

    @Test
    void minusONamingTheInputFileIsSafeFullReplaceNeverInterleaved() throws IOException {
        // Documented collision policy: the input is fully read before any write, and the
        // destination changes only at the final atomic move — so in-place is safe (if unusual).
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        Captured c = run("render", md.toString(), "-o", md.toString());
        assertEquals(0, c.exitCode);
        assertEquals(Sirentide.render("pie\n  \"A\" : 1"), Files.readString(md, StandardCharsets.UTF_8),
            "the input file is cleanly replaced by the complete SVG, never a partial interleave");
        assertNoTempLitter(tmp);
    }

    // --- stdout write failures are loud (review sirentide/471, stream-error correction) ---------

    @Test
    void aBrokenStdoutConsumerIsExit2ForTheLegacyPath() throws IOException {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(new String[0],
            new ByteArrayInputStream("pie\n  \"A\" : 1\n".getBytes(StandardCharsets.UTF_8)),
            brokenStdout(), new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        assertEquals(2, code, "a swallowed stdout write failure must not exit 0");
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("error writing to stdout"));
    }

    @Test
    void aBrokenStdoutConsumerIsExit2ForTheRenderVerb() throws IOException {
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"render", md.toString()},
            new ByteArrayInputStream(new byte[0]),
            brokenStdout(), new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        assertEquals(2, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("error writing to stdout"));
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
