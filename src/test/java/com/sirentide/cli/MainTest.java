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
import java.nio.file.AtomicMoveNotSupportedException;
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

    /// Marlow's MEDIUM finding (sirentide/680): the fence path had no tag-shaped case, so the
    /// render-check's behaviour on an invalid display label was unproven at the CLI boundary —
    /// the boundary the /docs bake actually goes through.
    ///
    /// Asserts the full contract, not just the exit code: exit 1, an ACTIONABLE stderr naming
    /// the label and the token, and NO destination mutation. That last one matters most —
    /// writing a shell and exiting 0 would claim a bake outcome /docs does not produce.
    @Test
    void aTagShapedLabelFailsTheFenceLoudlyAndWritesNothing() throws IOException {
        Path md = writeMd("bad.md", """
            # Title

            ```sirentide
            flowchart TD
              A[TRUE NEGATIVE<br/>safe to act on]
            ```
            """);
        Path out = md.resolveSibling("should-not-exist.svg");
        Captured c = run("render", md.toString(), "-o", out.toString());

        assertEquals(1, c.exitCode, "a fence that cannot render is a LOUD exit 1");
        assertFalse(Files.exists(out),
            "nothing may be written: /docs keeps the fence verbatim rather than embedding a shell");
        assertTrue(c.err.contains("node:A"), "stderr names the stable label identity: " + c.err);
        assertTrue(c.err.contains("<br/>"), "stderr names the bounded token: " + c.err);
        assertEquals("", c.out, "no SVG on stdout for a failed fence");
    }

    /// POSITIVE CONTROL for the case above: an ordinary comparison must still render through
    /// the same path, or the fence check would be indistinguishable from "brackets are banned".
    @Test
    void anOrdinaryComparisonStillRendersThroughTheFence() throws IOException {
        Path md = writeMd("ok.md", """
            ```sirentide
            flowchart TD
              A[x < y is legal]
            ```
            """);
        Captured c = run("render", md.toString());
        assertEquals(0, c.exitCode, "a legal comparison renders: " + c.err);
        assertTrue(c.out.length() > 100, "a real SVG came back");
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
    void anOkRenderThatDROPPEDAStatementSaysSoAndStillExitsZero() throws IOException {
        // The directive-shape rule drops an unknown directive-shaped statement and records a
        // line-scoped caveat on an otherwise-OK render, so a lost line is not lost silently.
        // The caveat lived only in the API: through this verb — which the authoring docs name as
        // THE local check — the author got exit 0, no output, and a diagram quietly missing their
        // line. A caveat channel nothing reads is not a channel.
        Path md = writeMd("dropped.md", """
            ```sirentide
            flowchart TD
                A[Start] --> B[End]
                mystyle A fill:#f00
            ```
            """);
        Captured c = run("render", md.toString(), "-o", tmp.resolve("out.svg").toString());
        assertEquals(0, c.exitCode,
            "the render genuinely succeeded and /docs genuinely serves this SVG — a drop is not a failure");
        assertTrue(c.err.contains("rendered, with caveats"), "stderr: " + c.err);
        assertTrue(c.err.contains("mystyle A fill:#f00"),
            "the caveat must name the statement that vanished, not merely that one did: " + c.err);
        assertTrue(Files.exists(tmp.resolve("out.svg")), "an OK render still writes its SVG");
    }

    @Test
    void strictPromotesADroppedStatementToExitOneAndStillWritesTheSvg() throws IOException {
        // Ruled at sirentide/977. stderr is the right AUTHOR channel and the wrong CI channel,
        // because CI is exactly where nobody reads stderr — a caveat that cannot gate anything
        // in the one environment that runs unattended is recorded-but-unseeable one level up.
        Path out = tmp.resolve("strict.svg");
        Path md = writeMd("strict-dropped.md", """
            ```sirentide
            flowchart TD
                A[Start] --> B[End]
                mystyle A fill:#f00
            ```
            """);
        Captured c = run("render", md.toString(), "-o", out.toString(), "--strict");
        assertEquals(1, c.exitCode, "--strict gates on a dropped statement");
        assertTrue(c.err.contains("mystyle A fill:#f00"), "the gate must name the line: " + c.err);
        assertTrue(c.err.contains("--strict"), "and say the flag is why: " + c.err);
        // UNLIKE the exit-1 unrenderable arm, the SVG IS written. There the artifact would be a
        // lie about what /docs serves; here it is exactly what /docs serves, and a caller whose
        // gate just rejected something wants to look at what was rejected.
        assertTrue(Files.exists(out), "--strict gates the exit code, it does not withhold the artifact");
    }

    @Test
    void strictDoesNotManufactureAFailureOnACleanRender() throws IOException {
        // THE CONTROL Fixpoint required with the flag, and the reason is the same one that makes
        // the silence control load-bearing: a gate that fires on every render is not a gate.
        Path md = writeMd("strict-clean.md", """
            ```sirentide
            flowchart TD
                A[Start] --> B[End]
            ```
            """);
        Captured c = run("render", md.toString(), "-o", tmp.resolve("sc.svg").toString(), "--strict");
        assertEquals(0, c.exitCode, "--strict on a diagram that lost nothing is exit 0: " + c.err);
        assertEquals("", c.err, "and silent: " + c.err);
    }

    @Test
    void theDefaultIsUNCHANGEDByTheExistenceOfStrict() throws IOException {
        // The third arm, because "strict works" and "the default still works" are different
        // claims and only one of them is about the flag.
        Path md = writeMd("default-dropped.md", """
            ```sirentide
            flowchart TD
                A[Start] --> B[End]
                mystyle A fill:#f00
            ```
            """);
        Captured c = run("render", md.toString(), "-o", tmp.resolve("d.svg").toString());
        assertEquals(0, c.exitCode, "without --strict a drop is still not a failure");
        assertTrue(c.err.contains("rendered, with caveats"), "but it is still SAID: " + c.err);
    }

    @Test
    void aCleanRenderStaysSILENT() throws IOException {
        // THE CONTROL for the test above, and the reason it is not optional: a warning that fires
        // on every render is noise an author learns to ignore, which would cost more than the
        // silence it replaced. Asserting empty stderr is what makes the caveat MEAN something.
        Path md = writeMd("clean.md", """
            ```sirentide
            flowchart TD
                A[Start] --> B[End]
            ```
            """);
        Captured c = run("render", md.toString(), "-o", tmp.resolve("clean.svg").toString());
        assertEquals(0, c.exitCode);
        assertEquals("", c.err, "a diagram that lost nothing must say nothing: " + c.err);
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

    // --- atomic-only move: no non-atomic fallback (review sirentide/490 B1) ---------------------

    @Test
    void anAtomicMoveIncapableFilesystemFailsClosedLeavingTheDestinationByteIdentical()
        throws IOException {
        // The mover seam forces the AtomicMoveNotSupportedException a POSIX temp dir can never
        // produce. The old code retried with a plain REPLACE_EXISTING move, whose Java contract
        // leaves the destination UNDEFINED on an I/O failure — silently voiding the documented
        // unconditional never-corrupts promise. The fixed contract: refuse loudly (exit 2), leave
        // the existing destination byte-identical, delete the completed temp sibling.
        Path dest = tmp.resolve("out.svg");
        String sentinel = "<svg>the previous good artifact</svg>";
        Files.writeString(dest, sentinel, StandardCharsets.UTF_8);
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.writeOutput("<svg>new render that must NOT land non-atomically</svg>",
            dest.toString(),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8),
            (completedTmp, d) -> {
                throw new AtomicMoveNotSupportedException(completedTmp.toString(), d.toString(),
                    "test filesystem cannot atomic-move");
            });
        assertEquals(2, code, "no atomic replace available must be a LOUD failure, not a fallback");
        String err = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("refusing a non-atomic overwrite"), "stderr: " + err);
        assertEquals("", outBuf.toString(StandardCharsets.UTF_8));
        assertEquals(sentinel, Files.readString(dest, StandardCharsets.UTF_8),
            "the existing destination must remain byte-identical");
        assertNoTempLitter(tmp);
    }

    @Test
    void theProductionMoverAtomicallyReplacesAnExistingDestination() throws IOException {
        // Pins Main.ATOMIC_REPLACE itself (the seam's production value): a completed sibling
        // lands over an existing destination and the source entry is gone.
        Path src = Files.writeString(tmp.resolve("completed.tmp"), "new", StandardCharsets.UTF_8);
        Path dest = Files.writeString(tmp.resolve("dest.svg"), "old", StandardCharsets.UTF_8);
        Main.ATOMIC_REPLACE.move(src, dest);
        assertEquals("new", Files.readString(dest, StandardCharsets.UTF_8));
        assertFalse(Files.exists(src), "the moved-from sibling must be gone");
    }

    // --- symlink destination policy: replaced as a path entry (review sirentide/490 B1 edge) ----

    private Path symlinkOrSkip(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "filesystem does not support symlinks: " + e);
            throw new AssertionError("unreachable");
        }
    }

    @Test
    void minusODestinationSymlinkToADirectoryIsReplacedAsAPathEntryNotRefused() throws IOException {
        // Files.isDirectory follows links by default, so the old check refused a symlink-to-dir
        // even though the documented policy is "a symlink destination is replaced as a path
        // entry". The check is now NOFOLLOW_LINKS: the policy keys on the entry, not its target.
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        Path dir = Files.createDirectory(tmp.resolve("real-dir"));
        Files.writeString(dir.resolve("marker.txt"), "survives", StandardCharsets.UTF_8);
        Path link = symlinkOrSkip(tmp.resolve("link.svg"), dir);
        Captured c = run("render", md.toString(), "-o", link.toString());
        assertEquals(0, c.exitCode, "stderr: " + c.err);
        assertFalse(Files.isSymbolicLink(link), "the link entry is swapped for a regular file");
        assertEquals(Sirentide.render("pie\n  \"A\" : 1"), Files.readString(link, StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(dir), "the link's old target directory must survive");
        assertEquals("survives", Files.readString(dir.resolve("marker.txt"), StandardCharsets.UTF_8));
        assertNoTempLitter(tmp);
    }

    @Test
    void minusODestinationSymlinkToAFileIsReplacedAsAPathEntryNotWrittenThrough() throws IOException {
        Path md = writeMd("doc.md", "```sirentide\npie\n  \"A\" : 1\n```\n");
        Path target = Files.writeString(tmp.resolve("target.svg"), "original target bytes",
            StandardCharsets.UTF_8);
        Path link = symlinkOrSkip(tmp.resolve("link.svg"), target);
        Captured c = run("render", md.toString(), "-o", link.toString());
        assertEquals(0, c.exitCode, "stderr: " + c.err);
        assertFalse(Files.isSymbolicLink(link), "the link entry is swapped for a regular file");
        assertEquals(Sirentide.render("pie\n  \"A\" : 1"), Files.readString(link, StandardCharsets.UTF_8));
        assertEquals("original target bytes", Files.readString(target, StandardCharsets.UTF_8),
            "the link target must never be written through");
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
