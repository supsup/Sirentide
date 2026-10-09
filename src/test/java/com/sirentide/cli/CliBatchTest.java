package com.sirentide.cli;

import static com.sirentide.cli.CliRun.LATTEX_JAR;
import static com.sirentide.cli.CliRun.runWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.cli.CliRun.Captured;
import com.sirentide.math.LatteXMathFragmentRenderer;
import com.sirentide.parse.DslParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `render --batch` (plan d9f29911 item 2): many raw-DSL sources per JVM. Sources are NUL-separated
/// on stdin (a DSL is multi-line, so a newline cannot delimit it, unlike LatteX's one-expression-per-
/// line default), and each produces exactly ONE NUL-terminated record on stdout, in order.
///
/// THE ORACLE IS THE SINGLE-SHOT RENDER: every SVG record must be byte-identical to what
/// `render -` produces for that source alone, so batching is an amortization, never a second bake.
/// Alignment is the other load-bearing property: record N always answers source N (a failure emits
/// an error record in its slot rather than shifting the rest).
class CliBatchTest {

    @TempDir
    Path tmp;

    private static final String PIE = "pie\n  \"A\" : 60\n  \"B\" : 40\n";
    private static final String FLOW = "flowchart TD\n  A[Start] --> B[End]\n";
    private static final String DROPPING = "flowchart TD\n    A[Start] --> B[End]\n    mystyle A fill:#f00\n";
    private static final String BAD = "notadiagram\n";
    private static final String MATH = "flowchart TD\n    A[\"$\\sqrt{2}$\"] --> B\n";

    private static byte[] nulJoin(boolean trailingNul, String... sources) {
        String s = String.join("\0", sources) + (trailingNul ? "\0" : "");
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /// Splits stdout into records and asserts every record was NUL-TERMINATED (the last byte is NUL).
    private static List<String> records(byte[] out) {
        assertTrue(out.length > 0 && out[out.length - 1] == 0, "every record is NUL-terminated");
        String s = new String(out, 0, out.length - 1, StandardCharsets.UTF_8);
        return new ArrayList<>(Arrays.asList(s.split("\0", -1)));
    }

    @Test
    void eachRecordIsByteIdenticalToTheSingleShotRender() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE, FLOW), "render", "--batch");
        assertEquals(0, c.exitCode(), c.err());
        assertEquals(List.of(Sirentide.render(PIE), Sirentide.render(FLOW)), records(c.outBytes()));
        assertEquals("", c.err());
    }

    @Test
    void aTrailingNulDoesNotMintAnExtraRecord() throws IOException {
        Captured c = runWithStdin(nulJoin(true, PIE, FLOW), "render", "--batch");
        assertEquals(0, c.exitCode());
        assertEquals(2, records(c.outBytes()).size());
    }

    @Test
    void aFailingSourceIsIsolatedInItsOwnSlotAndTheBatchExits1() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE, BAD, FLOW), "render", "--batch");
        assertEquals(1, c.exitCode(), "any record that does not render fails the batch");
        List<String> r = records(c.outBytes());
        assertEquals(3, r.size(), "alignment: one record per source, the failure in its own slot");
        assertEquals(Sirentide.render(PIE), r.get(0));
        assertTrue(r.get(1).startsWith("sirentide: error: "), r.get(1));
        assertEquals(Sirentide.render(FLOW), r.get(2), "the batch continued past the failure");
        assertTrue(c.err().contains("record 2"), "stderr names the 1-based record: " + c.err());
    }

    @Test
    void anEmptyStdinIsALoudExit2() throws IOException {
        Captured c = runWithStdin(new byte[0], "render", "--batch");
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("--batch got no sources"), c.err());
        assertEquals(0, c.outBytes().length);
    }

    @Test
    void anOversizedSourceGetsAnErrorRecordAndTheBatchContinuesAligned() throws IOException {
        String huge = "pie\n" + "x".repeat(DslParser.MAX_SOURCE_BYTES + 10);
        Captured c = runWithStdin(nulJoin(false, PIE, huge, FLOW), "render", "--batch");
        assertEquals(1, c.exitCode());
        List<String> r = records(c.outBytes());
        assertEquals(3, r.size());
        assertTrue(r.get(1).contains("larger than"), r.get(1));
        assertEquals(Sirentide.render(FLOW), r.get(2));
    }

    // --- flag combinations --------------------------------------------------------------------

    @Test
    void batchWithMinusOIsAUsageError() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE), "render", "--batch", "-o", tmp.resolve("o.svg").toString());
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("--batch writes NUL-terminated records to stdout"), c.err());
        assertEquals(0, c.outBytes().length);
    }

    @Test
    void batchWithPngIsAUsageErrorNotASilentSkip() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE), "render", "--batch",
            "--png", tmp.resolve("o.png").toString(), "--brewshot", tmp.resolve("b.jar").toString());
        assertEquals(2, c.exitCode(), "a --png that silently produced no PNG is this project's signature defect");
        assertTrue(c.err().contains("--png"), c.err());
        assertEquals(0, c.outBytes().length);
    }

    @Test
    void batchCaveatIsReportedPerRecordAndExit0WithoutStrict() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE, DROPPING), "render", "--batch");
        assertEquals(0, c.exitCode());
        assertTrue(c.err().contains("record 2: rendered, with caveats"), c.err());
        assertEquals(Sirentide.render(DROPPING), records(c.outBytes()).get(1));
    }

    @Test
    void batchStrictFailsOnACaveatAndStillEmitsEveryRecord() throws IOException {
        Captured c = runWithStdin(nulJoin(false, DROPPING, PIE), "render", "--batch", "--strict");
        assertEquals(1, c.exitCode(), "--strict must not be disarmed by --batch");
        List<String> r = records(c.outBytes());
        assertEquals(List.of(Sirentide.render(DROPPING), Sirentide.render(PIE)), r);
        assertTrue(c.err().contains("--strict"), c.err());
    }

    @Test
    void batchStrictOnCleanSourcesIsExit0() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE, FLOW), "render", "--batch", "--strict");
        assertEquals(0, c.exitCode(), c.err());
    }

    @Test
    void batchMathTypesetsEveryRecordLikeTheApi() throws IOException {
        CliRun.assertLatteXJarPresent();
        Captured c = runWithStdin(nulJoin(false, MATH, PIE), "render", "--batch",
            "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(0, c.exitCode(), c.err());
        LatteXMathFragmentRenderer r = new LatteXMathFragmentRenderer();
        assertEquals(List.of(Sirentide.render(MATH, r), Sirentide.render(PIE, r)), records(c.outBytes()));
    }

    @Test
    void batchSourceHashStampsEachRecordWithItsOwnSource() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE, FLOW), "render", "--batch", "--source-hash");
        assertEquals(0, c.exitCode(), c.err());
        List<String> r = records(c.outBytes());
        assertEquals(CliSourceHashTest.expectHashed(Sirentide.render(PIE), PIE), r.get(0));
        assertEquals(CliSourceHashTest.expectHashed(Sirentide.render(FLOW), FLOW), r.get(1));
    }

    @Test
    void aBrokenStdoutIsExit2() throws IOException {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream broken = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        }, true, StandardCharsets.UTF_8);
        int code = Main.run(new String[] {"render", "--batch"},
            new ByteArrayInputStream(nulJoin(false, PIE, FLOW)), broken,
            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        assertEquals(2, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("error writing to stdout"));
    }

    @Test
    void batchTakesNoPositionalAfterTheFlagSlot() throws IOException {
        Captured c = runWithStdin(nulJoin(false, PIE), "render", "--batch", "extra.md");
        assertEquals(2, c.exitCode());
        assertTrue(c.err().contains("bad arguments after the file path"), c.err());
    }
}
