package com.sirentide.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/// `render -` (raw DSL on stdin) must report caveats and honour `--strict` exactly as the
/// `render <file.md>` path does (plan c880b12e).
///
/// MEASURED BEFORE THIS EXISTED: the stdin arm routed through `rawDslSvgOrNull`, which checks the
/// OUTCOME but never reads the caveat DETAIL, so on stdin `--strict` was silently ignored for every
/// caveat (dropped statements, pie label drops, xychart label drops): Fixpoint's showcase audit
/// (sirentide/1118) saw `render - --strict` exit 0 on a chart that lost 15 of 25 axis labels. The
/// fence path already printed the caveat and failed under `--strict`; only the stdin arm skipped it.
class StdinStrictCaveatTest {

    private static String barker() {
        StringBuilder b = new StringBuilder("xychart\n");
        for (int lag = -12; lag <= 12; lag++) {
            b.append('"').append(lag).append("\" : ").append(lag == 0 ? 13 : Math.abs(lag) % 2).append('\n');
        }
        return b.toString();
    }

    private record Run(int code, String out, String err) {}

    private static Run run(String dsl, String... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(dsl.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void stdinStrictFailsWhenAChartLosesLabels() throws IOException {
        Run r = run(barker(), "render", "-", "--strict");
        assertEquals(1, r.code(), "--strict must fail a render that lost labels; stderr: " + r.err());
        assertTrue(r.err().contains("--strict"), "says why it failed: " + r.err());
        assertTrue(r.out().startsWith("<svg"), "the SVG is still written, as on the fence path");
    }

    @Test
    void stdinWithoutStrictReportsTheCaveatButSucceeds() throws IOException {
        Run r = run(barker(), "render", "-");
        assertEquals(0, r.code(), "a caveat is not a failed bake without --strict");
        assertTrue(r.err().contains("rendered, with caveats"), "the caveat reaches stderr: " + r.err());
        assertTrue(r.err().contains("-12"), "and names the lost label: " + r.err());
    }

    @Test
    void stdinStrictPassesAChartThatLosesNothing() throws IOException {
        Run r = run("xychart\n\"A\" : 1\n\"B\" : 2\n", "render", "-", "--strict");
        assertEquals(0, r.code(), "non-vacuity: --strict passes a clean render; stderr: " + r.err());
        assertFalse(r.err().contains("caveat"), "no caveat on a clean render: " + r.err());
    }

    @Test
    void stdinStrictAlsoGatesTheOlderPieDropCaveat() throws IOException {
        // The stdin gap was not xychart-specific: the pie thin-slice drop caveat was equally ignored.
        Run r = run("pie\n\"quarter\" : 25\n\"right outside label that should clip\" : 1\n\"rest\" : 74\n",
            "render", "-", "--strict");
        assertEquals(1, r.code(), "the pie drop caveat now gates stdin too; stderr: " + r.err());
    }
}
