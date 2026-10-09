package com.sirentide.cli;

import static com.sirentide.cli.CliRun.LATTEX_JAR;
import static com.sirentide.cli.CliRun.runWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.cli.CliRun.Captured;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// F2 at the CLI: an xychart whose `$...$` category labels are lost must gate `--strict` when no math
/// renderer is active, and must NOT be reported as a slot loss when `--math` typesets them.
///
/// MEASURED BEFORE THIS EXISTED (05e243d jar): 30 categories labelled `$x_{i}$` through
/// `render - --strict` drew no category labels, printed nothing to stderr, and exited 0.
class CliMathLabelLossTest {

    @BeforeAll
    static void jar() {
        CliRun.assertLatteXJarPresent();
    }

    private static String math30() {
        StringBuilder b = new StringBuilder("xychart\n");
        for (int i = 1; i <= 30; i++) {
            b.append("\"$x_{").append(i).append("}$\" : ").append(i % 7 + 1).append('\n');
        }
        return b.toString();
    }

    @Test
    void thirtyMathLabelsWithoutMathFailStrictOnStdin() throws IOException {
        Captured c = runWithStdin(math30(), "render", "-", "--strict");
        assertEquals(1, c.exitCode(), "the lost labels must gate --strict; stderr: " + c.err());
        assertTrue(c.err().contains("xychart category-label drop"), c.err());
        assertTrue(c.err().contains("$x_{30}$"), "names the lost label by its source: " + c.err());
        assertEquals(Sirentide.render(math30()), c.out(), "the default SVG bytes are unchanged");
    }

    @Test
    void thirtyMathLabelsWithoutMathAreACaveatButExit0WithoutStrict() throws IOException {
        Captured c = runWithStdin(math30(), "render", "-");
        assertEquals(0, c.exitCode());
        assertTrue(c.err().contains("rendered, with caveats"), c.err());
    }

    @Test
    void thirtyMathLabelsWithMathTypesetAndPassStrict() throws IOException {
        Captured c = runWithStdin(math30(), "render", "-", "--strict", "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(0, c.exitCode(), "typeset labels are not slot losses; stderr: " + c.err());
        assertEquals("", c.err());
        assertEquals(Sirentide.render(math30(), new LatteXMathFragmentRenderer()), c.out());
    }

    @Test
    void aMathLabelThatFailsToTypesetIsTheUntypesetCaveatNotASlotLoss() throws IOException {
        String dsl = "xychart\n\"$\\frac{a$\" : 1\n\"$x_{2}$\" : 2\n";
        Captured c = runWithStdin(dsl, "render", "-", "--strict", "--math", "--lattex", LATTEX_JAR.toString());
        assertEquals(1, c.exitCode(), c.err());
        assertTrue(c.err().contains("did not typeset"), c.err());
        assertTrue(c.err().contains("\\frac{a"), c.err());
        assertFalse(c.err().contains("xychart category-label drop"), "not double-reported: " + c.err());
        assertTrue(c.err().contains("--strict: treating untypeset math as a failure"), c.err());
    }
}
