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

    /// THE NATURAL POSITIVE, pinned at the CLI: the committed golden `sequence-blocks` (not a copy of
    /// its DSL) carries a real 1.8 px graze, the loop label "every retry" (an ungrouped frame label,
    /// named by its position `text@80,135`) into the message label "ping" (`message:Alice-Bob-1`).
    /// It is a KNOWN FINDING FILED FOR FOLLOW-UP, excluded by name from the clean-gallery sweep in
    /// OverlapLintTest#KNOWN_LINT_FINDINGS. When a layout fix removes the graze, this test goes red on
    /// purpose: retire it together with that exclusion and regenerate the golden under review.
    @Test
    void theSequenceBlocksGoldenFailsStrictOnlyUnderTheLint() throws Exception {
        String dsl = com.sirentide.GoldenSvgTest.fixtures().get("sequence-blocks");
        String golden = com.sirentide.GoldenSvgTest.golden("sequence-blocks");
        Captured lint = runWithStdin(dsl, "render", "-", "--strict", "--lint-overlap");
        assertEquals(1, lint.exitCode(), lint.err());
        assertTrue(lint.err().contains("text overlap: text@80,135 x message:Alice-Bob-1 (20.3x1.8 px"),
            "the finding names both runs: " + lint.err());
        assertEquals(golden, lint.out(), "the lint never changes the golden SVG, which is still written");
        Captured plain = runWithStdin(dsl, "render", "-", "--strict");
        assertEquals(0, plain.exitCode(), plain.err());
        assertEquals("", plain.err());
        assertEquals(golden, plain.out());
    }
}
