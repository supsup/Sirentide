package com.sirentide.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// THE FLAG-PAIR MATRIX for `--strict` and `--png`, and the reason it exists is not the defect it
/// repairs but the SHAPE OF THE HOLE THAT HID IT.
///
/// `--strict` was tested only alongside `-o` (MainTest). `--png` was tested only WITHOUT `--strict`
/// (RenderPngTest). Both files are thorough about their own flag. Neither could see the defect,
/// because the defect lived in the INTERSECTION and no test crossed it: with `--png` set, the strict
/// return was guarded on `pngPath == null`, so a dropping source printed
/// "treating dropped statement(s) as a failure" to stderr and then exited 0 -- a gate announcing its
/// own failure and reporting success, which is verbatim the shape `writePng`'s javadoc calls this
/// project's signature defect.
///
/// So the deliverable is the MATRIX, not a regression test for the one cell. A regression test would
/// pin this bug; the matrix pins the CLASS, which is any future flag that silently disarms another.
/// Every cell below states the code AND the reason a caller cares about that exact number.
///
/// WHAT THIS FILE CANNOT COVER, stated rather than implied by absence: the cell where the PNG
/// SUCCEEDS. That needs a real BrewShot jar driving a real browser, which this suite deliberately
/// excludes (see RenderPngTest's header) and which is not hermetic. It does not weaken the
/// discrimination: under the pre-fix code the tail was `return writePng(...)`, which discarded
/// `strictFailed` WHATEVER writePng returned, so a cell that pins an exact code where writePng FAILS
/// kills the old behaviour just as surely -- old code returns 2 there, this file requires 1.
class StrictPngFlagMatrixTest {

    @TempDir
    Path tmp;

    /// Drops `mystyle`: the renderer does not recognise the statement, so it is silently omitted from
    /// the served diagram. This is the same fixture MainTest's strict arm uses, deliberately, so the
    /// matrix and the single-flag tests cannot drift apart on what "dropping" means.
    private static final String DROPPING = """
        ```sirentide
        flowchart TD
            A[Start] --> B[End]
            mystyle A fill:#f00
        ```
        """;

    private static final String CLEAN = """
        ```sirentide
        flowchart TD
            A[Start] --> B[End]
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

    private Path md(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    /// A jar path that cannot resolve, so `writePng` takes its exit-2 arm. Named for what it IS
    /// rather than what it is for, because a helper called `failingPng()` invites a reader to assume
    /// the failure is simulated somewhere in the test rather than produced by the real code path.
    private String unresolvableJar() {
        return tmp.resolve("no-such-brewshot.jar").toString();
    }

    // ---------------------------------------------------------------- neither flag

    @Test
    void neitherFlag_droppingSource_isExitZero() throws IOException {
        Captured c = run("render", md("a.md", DROPPING).toString(),
            "-o", tmp.resolve("a.svg").toString());
        assertEquals(0, c.exitCode(),
            "the DEFAULT must stay honest: a drop is not a failed bake, and the matrix is worthless "
                + "if the baseline cell has quietly become a gate: " + c.err());
    }

    // ---------------------------------------------------------------- --strict alone

    @Test
    void strictAlone_droppingSource_isExitOne() throws IOException {
        Captured c = run("render", md("b.md", DROPPING).toString(),
            "-o", tmp.resolve("b.svg").toString(), "--strict");
        assertEquals(1, c.exitCode(), "--strict gates a dropped statement: " + c.err());
    }

    @Test
    void strictAlone_cleanSource_isExitZero() throws IOException {
        Captured c = run("render", md("c.md", CLEAN).toString(),
            "-o", tmp.resolve("c.svg").toString(), "--strict");
        assertEquals(0, c.exitCode(), "a gate that fires on every render is not a gate: " + c.err());
    }

    // ---------------------------------------------------------------- --png alone

    @Test
    void pngAlone_droppingSource_reportsThePngFailureNotTheDrop() throws IOException {
        Captured c = run("render", md("d.md", DROPPING).toString(),
            "-o", tmp.resolve("d.svg").toString(),
            "--png", tmp.resolve("d.png").toString(), "--brewshot", unresolvableJar());
        assertEquals(2, c.exitCode(),
            "without --strict a drop is not a failure, so the only failure here is operational: " + c.err());
    }

    // ------------------------------------------------- BOTH FLAGS: the untested intersection

    @Test
    void bothFlags_droppingSource_theStrictFailureSurvivesThePngPath() throws IOException {
        // THE CELL THE DEFECT LIVED IN. Pre-fix, the tail returned writePng's code unconditionally and
        // discarded strictFailed, so this returned 2 here (and 0 when the PNG succeeded, which is the
        // form the bug was reported in). Asserting the EXACT code is what discriminates: a
        // "nonzero" assertion passes on the broken code too, because 2 is also nonzero.
        Path svg = tmp.resolve("e.svg");
        Captured c = run("render", md("e.md", DROPPING).toString(),
            "-o", svg.toString(),
            "--png", tmp.resolve("e.png").toString(), "--brewshot", unresolvableJar(), "--strict");

        assertEquals(1, c.exitCode(),
            "--strict must survive --png: a failure with no observable artifact outranks one whose "
                + "absence a caller can stat [ruling PROJECT/stafficy 25843]: " + c.err());
        assertTrue(c.err().contains("--strict"),
            "PRECEDENCE GOVERNS THE NUMBER, NEVER THE REPORT: the strict reason must still be said "
                + "even though it lost no precedence contest here: " + c.err());
        assertTrue(c.err().contains("BrewShot") || c.err().contains("brewshot"),
            "and the PNG failure must ALSO still be reported, or precedence has become suppression: "
                + c.err());
        assertTrue(Files.exists(svg),
            "the SVG is still written: the gate withholds an exit code, never the artifact you need "
                + "in order to see what the gate rejected");
    }

    @Test
    void bothFlags_cleanSource_thePngFailureIsNotMaskedByStrict() throws IOException {
        // THE OTHER DIRECTION, and without it the fix could be `if (strictFailed) return 1;` sitting
        // on top of a hardcoded 1 and this file would not notice. Strict did not fire, so the
        // operational failure must be the one that reaches the caller.
        Captured c = run("render", md("f.md", CLEAN).toString(),
            "-o", tmp.resolve("f.svg").toString(),
            "--png", tmp.resolve("f.png").toString(), "--brewshot", unresolvableJar(), "--strict");
        assertEquals(2, c.exitCode(),
            "with nothing dropped there is no strict failure to take precedence, so the PNG failure "
                + "must not be swallowed by the presence of --strict: " + c.err());
    }
}
