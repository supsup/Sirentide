package com.sirentide.cli;

import static com.sirentide.cli.CliRun.LATTEX_JAR;
import static com.sirentide.cli.CliRun.run;
import static com.sirentide.cli.CliRun.runWithStdin;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.cli.CliRun.Captured;
import com.sirentide.parse.DslParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--source-hash` (ruling sirentide/1129): the SHA-256 of the RAW source bytes goes to STDERR, and
/// NOTHING is added to the SVG. Every SVG byte with the flag equals the SVG without it.
///
/// THE ORACLE IS AN INDEPENDENT DIGEST of bytes this test builds itself, never a string the CLI
/// decoded: the CLI decodes UTF-8 with replacement, so a hash of the decoded string re-encoded would
/// silently change for invalid UTF-8 (0xE9 becomes EF BF BD). The fixtures carry invalid UTF-8, a BOM
/// and CRLF on purpose, because those are exactly the inputs a decode/re-encode does not preserve.
class CliSourceHashTest {

    @TempDir
    Path tmp;

    private static final String FLOW = "flowchart TD\n  A[Start] --> B[End]\n";
    private static final String PIE = "pie\n  \"A\" : 60\n  \"B\" : 40\n";
    private static final String DROPPING = "flowchart TD\n    A[Start] --> B[End]\n    mystyle A fill:#f00\n";
    private static final String BAD = "notadiagram\n";
    private static final String MATH = "flowchart TD\n    A[\"$\\sqrt{2}$\"] --> B\n";

    private static final Pattern SINGLE = Pattern.compile("(?m)^sirentide: source sha256:([0-9a-f]{64})$");

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    /// The one `source sha256:` line of a single render; fails unless there is exactly one.
    private static String singleHash(Captured c) {
        Matcher m = SINGLE.matcher(c.err());
        assertTrue(m.find(), "a source line on stderr: " + c.err());
        String h = m.group(1);
        assertFalse(m.find(), "exactly one source line: " + c.err());
        return h;
    }

    private Path mdFile(String name, byte[] bytes) throws IOException {
        Path p = tmp.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private Path mdOf(String name, String body) throws IOException {
        return mdFile(name, utf8("# Doc\n\n```sirentide\n" + body + "\n```\n"));
    }

    // --- default output is untouched -------------------------------------------------------------

    @Test
    void withoutTheFlagNoArmPrintsASourceLine() throws IOException {
        // DROPPING has a caveat, so stderr is NOT empty: the absence is checked on a live channel.
        Captured stdin = runWithStdin(DROPPING, "render", "-");
        assertTrue(stdin.err().contains("rendered, with caveats"), "control: stderr is in use: " + stdin.err());
        assertFalse(stdin.err().contains("sha256"), stdin.err());
        Captured md = run("render", mdOf("d.md", DROPPING.strip()).toString());
        assertTrue(md.err().contains("rendered, with caveats"), "control: " + md.err());
        assertFalse(md.err().contains("sha256"), md.err());
        Captured batch = runWithStdin(utf8(FLOW + "\0" + DROPPING + "\0" + BAD), "render", "--batch");
        assertTrue(batch.err().contains("record 2:"), "control: " + batch.err());
        assertFalse(batch.err().contains("sha256"), batch.err());
        Captured legacy = runWithStdin(FLOW);
        assertFalse(legacy.err().contains("sha256"), legacy.err());
    }

    @Test
    void theSvgBytesAreIdenticalWithAndWithoutTheFlag() throws IOException {
        for (String src : List.of(FLOW, PIE, DROPPING)) {
            Captured off = runWithStdin(src, "render", "-");
            Captured on = runWithStdin(src, "render", "-", "--source-hash");
            assertTrue(off.outBytes().length > 0, "control: an SVG was written");
            assertArrayEquals(off.outBytes(), on.outBytes(), "stdin arm: " + src);
            assertEquals(off.exitCode(), on.exitCode());
            Path f = mdOf("f.md", src.strip());
            assertArrayEquals(run("render", f.toString()).outBytes(),
                run("render", f.toString(), "--source-hash").outBytes(), "fence arm: " + src);
        }
        byte[] in = utf8(FLOW + "\0" + "\0" + BAD + "\0" + PIE);
        Captured off = runWithStdin(in, "render", "--batch");
        Captured on = runWithStdin(in, "render", "--batch", "--source-hash");
        assertArrayEquals(off.outBytes(), on.outBytes(), "batch records");
        assertEquals(off.exitCode(), on.exitCode());
    }

    @Test
    void theSvgBytesAreIdenticalWithAndWithoutTheFlagUnderMath() throws IOException {
        CliRun.assertLatteXJarPresent();
        String jar = LATTEX_JAR.toString();
        Captured off = runWithStdin(MATH, "render", "-", "--math", "--lattex", jar);
        Captured on = runWithStdin(MATH, "render", "-", "--math", "--lattex", jar, "--source-hash");
        assertEquals(0, off.exitCode(), off.err());
        assertArrayEquals(off.outBytes(), on.outBytes());
        Path f = mdOf("m.md", MATH.strip());
        assertArrayEquals(run("render", f.toString(), "--math", "--lattex", jar).outBytes(),
            run("render", f.toString(), "--math", "--lattex", jar, "--source-hash").outBytes());
        byte[] in = utf8(MATH + "\0" + FLOW);
        assertArrayEquals(runWithStdin(in, "render", "--batch", "--math", "--lattex", jar).outBytes(),
            runWithStdin(in, "render", "--batch", "--math", "--lattex", jar, "--source-hash").outBytes());
    }

    // --- what is hashed ------------------------------------------------------------------------

    @Test
    void theStdinHashIsTheDigestOfTheRawBytes() throws IOException {
        byte[] invalidUtf8 = concat(utf8("flowchart TD\n  A[caf"), new byte[] {(byte) 0xE9}, utf8("] --> B\n"));
        byte[] bom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, utf8(FLOW));
        for (byte[] src : List.of(utf8(FLOW), invalidUtf8, bom, new byte[0])) {
            Captured c = runWithStdin(src, "render", "-", "--source-hash");
            assertEquals(sha256(src), singleHash(c), "stdin bytes " + Arrays.toString(src));
        }
        // Non-vacuity of the invalid-UTF-8 row: the decoded string re-encoded is NOT the input, so
        // only a hash of the raw bytes can pass the row above.
        assertNotEquals(sha256(invalidUtf8),
            sha256(new String(invalidUtf8, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void crlfAndLfCopiesOfOneSourceHashDifferently() throws IOException {
        byte[] lf = utf8(FLOW);
        byte[] crlf = utf8(FLOW.replace("\n", "\r\n"));
        String hLf = singleHash(runWithStdin(lf, "render", "-", "--source-hash"));
        String hCrlf = singleHash(runWithStdin(crlf, "render", "-", "--source-hash"));
        assertNotEquals(hLf, hCrlf, "the RAW bytes are hashed, so line endings are part of the identity");
        assertEquals(sha256(crlf), hCrlf);
        assertEquals(sha256(lf), hLf);
    }

    @Test
    void theFenceHashIsTheDigestOfTheFenceBodyAsItAppearsInTheFile() throws IOException {
        // CRLF line endings, a raw 0xE9 (invalid UTF-8), a BOM-shaped sequence inside the body, and a
        // TRUNCATED three-byte sequence (E2 82) right before a bare LF: the case where a decoder that
        // swallowed the LF into the malformed sequence would shift every later line.
        byte[] file = concat(utf8("﻿# Doc\r\n\r\n```sirentide\r\nflowchart TD\r\n  %% c"),
            new byte[] {(byte) 0xE2, (byte) 0x82}, utf8("\n  A[caf"),
            new byte[] {(byte) 0xE9}, utf8("] --> B[﻿x]\r\n```\r\ntail\r\n"));
        // The independent slice: from just after the opener line's LF to just before the LF that
        // precedes the closer line. The body's own CRs stay (the extractor splits on LF only).
        int opener = indexOf(file, utf8("```sirentide\r\n"));
        int start = opener + "```sirentide\r\n".length();
        int closer = indexOf(file, utf8("\n```\r\n"));
        byte[] body = Arrays.copyOfRange(file, start, closer);
        assertEquals('\r', body[body.length - 1], "control: the last body line keeps its CR");
        Captured c = run("render", mdFile("crlf.md", file).toString(), "--source-hash");
        assertEquals(0, c.exitCode(), c.err());
        assertEquals(sha256(body), singleHash(c));
        // And an LF file with an empty body hashes the empty string.
        Captured empty = run("render", mdFile("empty.md", utf8("```sirentide\n```\n")).toString(), "--source-hash");
        assertEquals(sha256(new byte[0]), singleHash(empty), empty.err());
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("needle not found");
    }

    // --- batch ---------------------------------------------------------------------------------

    @Test
    void batchPrintsOneLinePerRecordWithItsOneBasedNumber() throws IOException {
        // Record 2 is BLANK (a legal empty diagram) and record 3 FAILS: both still get their line.
        byte[] crlfFlow = utf8(FLOW.replace("\n", "\r\n"));
        List<byte[]> sources = List.of(utf8(PIE), new byte[0], utf8(BAD), crlfFlow);
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        for (byte[] s : sources) {
            in.writeBytes(s);
            in.write(0);
        }
        Captured c = runWithStdin(in.toByteArray(), "render", "--batch", "--source-hash");
        assertEquals(1, c.exitCode(), "record 3 failed: " + c.err());
        Matcher m = Pattern.compile("(?m)^sirentide: record (\\d+): source sha256:([0-9a-f]{64})$").matcher(c.err());
        int n = 0;
        while (m.find()) {
            n++;
            assertEquals(String.valueOf(n), m.group(1), c.err());
            assertEquals(sha256(sources.get(n - 1)), m.group(2), "record " + n);
        }
        assertEquals(sources.size(), n, "one source line per record, no more: " + c.err());
        assertFalse(SINGLE.matcher(c.err()).find(), "no unnumbered line in batch mode");
        // Each record's source line comes before that record's own diagnostics.
        assertTrue(c.err().indexOf("record 3: source sha256:") < c.err().indexOf("record 3: diagram did not render"),
            c.err());
    }

    @Test
    void anOversizedBatchRecordIsHashedInFull() throws IOException {
        byte[] big = new byte[DslParser.MAX_SOURCE_BYTES + 10];
        Arrays.fill(big, (byte) 'a');
        byte[] in = concat(big, new byte[] {0}, utf8(FLOW));
        Captured c = runWithStdin(in, "render", "--batch", "--source-hash");
        assertTrue(c.err().contains("record 1: source sha256:" + sha256(big)), c.err());
        assertTrue(c.err().contains("record 2: source sha256:" + sha256(utf8(FLOW))), c.err());
    }

    // --- interaction with the exit code and the other channels -----------------------------------

    @Test
    void theHashGoesToStderrOnlyAndNeverMovesTheExitCode() throws IOException {
        for (String[] flags : List.of(new String[] {}, new String[] {"--strict"})) {
            for (String src : List.of(FLOW, DROPPING, BAD)) {
                String[] off = concatArgs(new String[] {"render", "-"}, flags);
                String[] on = concatArgs(off, new String[] {"--source-hash"});
                Captured a = runWithStdin(src, off);
                Captured b = runWithStdin(src, on);
                assertEquals(a.exitCode(), b.exitCode(), src + " " + Arrays.toString(flags));
                assertFalse(b.out().contains("sha256"), "stdout never carries it");
                String line = "sirentide: source sha256:" + sha256(utf8(src)) + "\n";
                assertTrue(b.err().startsWith(line), "the source line comes first: " + b.err());
                assertEquals(a.err(), b.err().substring(line.length()),
                    "apart from the source line, stderr is unchanged (caveat and --strict semantics too)");
            }
        }
    }

    @Test
    void aFenceThatDoesNotRenderStillPrintsItsHash() throws IOException {
        Captured c = run("render", mdOf("bad.md", BAD.strip()).toString(), "--source-hash");
        assertEquals(1, c.exitCode(), c.err());
        assertEquals(sha256(utf8(BAD.strip())), singleHash(c));
        assertTrue(c.err().contains("did not render"), c.err());
    }

    @Test
    void noSourceMeansNoHash() throws IOException {
        // No fence (exit 2) and a usage error: there is no source to identify.
        Path none = mdFile("none.md", utf8("# just prose\n"));
        Captured noFence = run("render", none.toString(), "--source-hash");
        assertEquals(2, noFence.exitCode());
        assertFalse(noFence.err().contains("sha256"), noFence.err());
        Captured usage = runWithStdin(FLOW, "render", "-", "--source-hash", "--bogus");
        assertEquals(2, usage.exitCode());
        // The USAGE text itself documents the line, so the check is for a line that STARTS with it.
        assertFalse(Pattern.compile("(?m)^sirentide: source sha256:").matcher(usage.err()).find(), usage.err());
    }

    @Test
    void anOverCapStdinSaysTheHashIsUnavailableRatherThanHashingAPrefix() throws IOException {
        byte[] big = new byte[DslParser.MAX_SOURCE_BYTES + 10];
        Arrays.fill(big, (byte) 'a');
        Captured off = runWithStdin(big, "render", "-");
        Captured c = runWithStdin(big, "render", "-", "--source-hash");
        assertEquals(off.exitCode(), c.exitCode());
        assertFalse(SINGLE.matcher(c.err()).find(), "no digest of a truncated prefix: " + c.err());
        assertTrue(c.err().startsWith("sirentide: source sha256: unavailable"), c.err());
    }

    @Test
    void usageNamesTheFlagAndHowToRecomputeIt() throws IOException {
        Captured c = run("render");
        assertTrue(c.err().contains("--source-hash"), c.err());
        assertTrue(c.err().contains("sha256sum"), c.err());
        assertFalse(c.err().contains("would identify the SOURCE"),
            "the provenance paragraph no longer speaks conditionally about a hash");
    }

    private static String[] concatArgs(String[] a, String[] b) {
        String[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
