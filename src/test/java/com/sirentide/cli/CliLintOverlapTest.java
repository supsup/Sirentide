package com.sirentide.cli;

import static com.sirentide.cli.CliRun.md;
import static com.sirentide.cli.CliRun.run;
import static com.sirentide.cli.CliRun.runWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.cli.CliRun.Captured;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--lint-overlap`: the CLI face of {@link com.sirentide.api.RenderOptions#lintOverlap()}. OPT-IN:
/// without the flag no render gains a lint caveat, so `--strict` can only fail on a lint finding
/// when the flag is set.
class CliLintOverlapTest {

    @TempDir
    Path tmp;

    private static final String COLLIDING =
        "quadrantChart\n    title Q\n    Alpha point: [0.30, 0.60]\n    Beta point: [0.33, 0.61]\n";
    private static final String CLEAN =
        "flowchart TD\n  A[Start] --> B{Ready?}\n  B -->|yes| C[Build] --> D[Ship]\n  B -->|no| E[Fix] --> A\n";

    @Test
    void withTheFlagStrictFailsOnACollision() throws IOException {
        Captured c = runWithStdin(COLLIDING, "render", "-", "--lint-overlap", "--strict");
        assertEquals(1, c.exitCode(), c.err());
        assertTrue(c.err().contains("text overlap: point:Alphapoint x point:Betapoint"), c.err());
        assertEquals(Sirentide.render(COLLIDING), c.out(), "the SVG is unchanged and still written");
    }

    @Test
    void withoutTheFlagStrictPassesTheSameSource() throws IOException {
        Captured c = runWithStdin(COLLIDING, "render", "-", "--strict");
        assertEquals(0, c.exitCode(), c.err());
        assertEquals("", c.err(), "no lint unless asked for");
        assertEquals(Sirentide.render(COLLIDING), c.out());
    }

    @Test
    void withTheFlagButNoStrictTheFindingIsACaveatAtExit0() throws IOException {
        Captured c = runWithStdin(COLLIDING, "render", "-", "--lint-overlap");
        assertEquals(0, c.exitCode());
        assertTrue(c.err().contains("rendered, with caveats — text overlap:"), c.err());
    }

    @Test
    void theFenceArmLintsToo() throws IOException {
        Path f = md(tmp, "q.md", COLLIDING.strip());
        Captured c = run("render", f.toString(), "--strict", "--lint-overlap");
        assertEquals(1, c.exitCode(), c.err());
    }

    @Test
    void aCleanFigurePassesWithTheFlagAndItsBytesAreTheDefault() throws IOException {
        Captured lint = runWithStdin(CLEAN, "render", "-", "--lint-overlap", "--strict");
        Captured plain = runWithStdin(CLEAN, "render", "-");
        assertEquals(0, lint.exitCode(), lint.err());
        assertEquals("", lint.err());
        assertEquals(plain.out(), lint.out());
    }

    @Test
    void batchCarriesTheFlagPerRecord() throws IOException {
        byte[] in = (CLEAN + "\0" + COLLIDING).getBytes(StandardCharsets.UTF_8);
        Captured c = runWithStdin(in, "render", "--batch", "--lint-overlap", "--strict");
        assertEquals(1, c.exitCode(), c.err());
        assertTrue(c.err().contains("record 2: rendered, with caveats — text overlap"), c.err());
        assertFalse(c.err().contains("record 1:"), c.err());
        Captured off = runWithStdin(in, "render", "--batch", "--strict");
        assertEquals(0, off.exitCode(), off.err());
    }
}
