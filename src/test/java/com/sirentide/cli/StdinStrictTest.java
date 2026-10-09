package com.sirentide.cli;

import static com.sirentide.cli.CliRun.runWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.cli.CliRun.Captured;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/// `render - --strict` used to be a silent no-op. The flag loop accepted `--strict` on the stdin arm,
/// but only the fence arm read the caveat channel, so a dropping source piped in printed NOTHING and
/// exited 0 -- the same "flag accepted, gate disarmed" shape StrictPngFlagMatrixTest pins for `--png`,
/// on a different axis (the source slot, not an output flag). Measured before the fix with the
/// identity harness: `render - --strict` on the `mystyle` fixture -> exit 0, empty stderr.
///
/// The legacy no-args shape is deliberately NOT changed: it parses no flags, so it cannot be asked
/// for --strict, and its stderr stays exactly what it was.
class StdinStrictTest {

    private static final String DROPPING = "flowchart TD\n    A[Start] --> B[End]\n    mystyle A fill:#f00\n";

    @Test
    void strictOnTheStdinArmFailsADroppingSource() throws IOException {
        Captured c = runWithStdin(DROPPING, "render", "-", "--strict");
        assertEquals(1, c.exitCode(), "stderr was: " + c.err());
        assertEquals(Sirentide.render(DROPPING), c.out(), "the SVG is still written, as on the fence arm");
        assertTrue(c.err().contains("--strict: treating dropped statement(s) as a failure"), c.err());
    }

    @Test
    void withoutStrictTheStdinArmReportsTheCaveatAndExits0() throws IOException {
        Captured c = runWithStdin(DROPPING, "render", "-");
        assertEquals(0, c.exitCode());
        assertTrue(c.err().contains("rendered, with caveats"), c.err());
    }

    @Test
    void theLegacyNoArgsShapeIsUntouched() throws IOException {
        Captured c = runWithStdin(DROPPING);
        assertEquals(0, c.exitCode());
        assertEquals("", c.err(), "the legacy shape's stderr is unchanged");
        assertEquals(Sirentide.render(DROPPING), c.out());
    }
}
