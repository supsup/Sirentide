package com.sirentide.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/// The RAW-DSL exit-code guard (plan 8a991947, slice 2 remainder).
///
/// MEASURED FIRST, through the real CLI:
///
///     notadiagram      -> exit 0, 85-byte `width="0" height="0"` shell, stderr EMPTY
///     composite state  -> exit 0, 85-byte shell,                       stderr EMPTY
///     flowchart TD…    -> exit 0, 2308 bytes
///
/// A caller that pipes DSL in and checks the exit code is told the bake succeeded, and the
/// artifact it gets is a valid but blank SVG. Nothing anywhere says otherwise.
///
/// THIS IS A ONE-ARM-OF-A-TWO-ARM CONDITION, and the other arm already has the right answer
/// written down. The `render <file.md>` path calls `tryRenderWithDiagnostics`, refuses on a
/// not-OK outcome, and exits 1 naming the reason — with a comment calling it the "truthful
/// render-check posture (review sirentide/471 B3): writing the inert shell and exiting 0
/// would claim a bake outcome /docs does not produce". The two raw-DSL paths (bare stdin,
/// and its `render -` alias) skip that check entirely and write whatever comes back. So the
/// fix is not a new policy, it is applying the existing one to the arm that was missed.
///
/// THE PREDICATE IS `outcome != OK`, NOT `IR == Empty`, and that distinction is the whole
/// design. Measured:
///
///     flowchart TD    (empty body)  -> Flowchart,    OK
///     stateDiagram-v2 (empty body)  -> StateDiagram, OK
///     BLANK SOURCE                  -> Empty,        OK   <-- the trap
///
/// A blank source legitimately bakes to a blank shell and must stay exit 0. Keying on the IR
/// type would fail it, so the negative controls below pin that explicitly — an Empty IR with
/// an OK outcome is an honest success, and only the diagnostic channel can tell them apart.
///
/// This also completes the composite-state guard landed at e732ac5d: that guard stopped the
/// parser fabricating a transition, but the CLI still reported success for the degraded
/// diagram. A degrade nobody is told about is only half a fix.
class RawDslExitCodeTest {

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

    private Captured runWithStdin(String stdin, String... args) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Captured(code, outBuf.toString(StandardCharsets.UTF_8),
            errBuf.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // The guard, on BOTH raw-DSL arms. They are documented as "deliberately identical",
    // so each gets its own assertion rather than one standing in for the other.
    // ------------------------------------------------------------------

    @Test
    void unknownHeaderExitsNonZeroOnBareStdin() throws IOException {
        Captured c = runWithStdin("notadiagram\n  A --> B\n");
        assertEquals(1, c.exitCode,
            "an unrecognized diagram type wrote a blank SVG and claimed success. out=" + c.out);
        assertTrue(c.err.contains("did not render") || c.err.contains("not recognized"),
            "the refusal must NAME the reason, not just fail: stderr=[" + c.err + "]");
        assertEquals("", c.out, "nothing may be written when the bake did not happen");
    }

    @Test
    void unknownHeaderExitsNonZeroOnTheRenderDashAlias() throws IOException {
        // `render -` is documented as deliberately identical to the bare-stdin path. If only
        // one arm is fixed, the CLI's own stated equivalence becomes false.
        Captured c = runWithStdin("notadiagram\n  A --> B\n", "render", "-");
        assertEquals(1, c.exitCode, "the `render -` alias must match bare stdin. out=" + c.out);
        assertEquals("", c.out);
    }

    @Test
    void aDegradedCompositeStateExitsNonZero() throws IOException {
        // Connects the composite-state guard (landed e732ac5d) to the CLI. That guard converted
        // a fabricated transition into an inert shell; without this, the shell still ships at
        // exit 0 and the caller never learns the diagram was refused.
        Captured c = runWithStdin("stateDiagram-v2\n  [*] --> Active\n"
            + "  state Active {\n    [*] --> Idle\n  }\n");
        assertEquals(1, c.exitCode, "a degraded diagram must not report success. out=" + c.out);
        assertTrue(c.err.contains("composite"),
            "the reason should carry the guard's own message: stderr=[" + c.err + "]");
    }

    // ------------------------------------------------------------------
    // Negative controls. These pin that the predicate is the DIAGNOSTIC OUTCOME and not the
    // IR type — the naive `Empty -> exit 1` rule passes every test above and fails these.
    // ------------------------------------------------------------------

    @Test
    void aBlankSourceIsAnHonestSuccess() throws IOException {
        // THE TRAP. A blank source parses to the Empty IR but reports outcome OK: baking
        // nothing from nothing is not a failure. A guard keyed on the IR type would refuse it.
        Captured c = runWithStdin("");
        assertEquals(0, c.exitCode,
            "a blank source legitimately bakes to a blank shell — this is the control that "
                + "catches a guard keyed on `IR == Empty` instead of on the outcome. stderr=["
                + c.err + "]");
        assertTrue(c.out.contains("<svg"), "and it still writes its shell: " + c.out);
    }

    @Test
    void aValidDiagramWithAnEmptyBodyStillSucceeds() throws IOException {
        Captured c = runWithStdin("flowchart TD\n");
        assertEquals(0, c.exitCode,
            "a well-formed header with no body is a valid empty diagram, not a refusal. stderr=["
                + c.err + "]");
        assertTrue(c.out.contains("<svg"));
    }

    @Test
    void anOrdinaryDiagramIsUnaffected() throws IOException {
        Captured c = runWithStdin("flowchart TD\n  A --> B\n");
        assertEquals(0, c.exitCode, "stderr=[" + c.err + "]");
        assertTrue(c.out.contains("<svg") && c.out.length() > 500,
            "the ordinary path must still emit a real diagram, not a shell: " + c.out.length()
                + " bytes");
    }
}
