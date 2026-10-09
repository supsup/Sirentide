package com.sirentide.cli;

import static com.sirentide.cli.CliRun.LATTEX_JAR;
import static com.sirentide.cli.CliRun.md;
import static com.sirentide.cli.CliRun.run;
import static com.sirentide.cli.CliRun.runWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.cli.CliRun.Captured;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--source-hash` (plan d9f29911 item 3): an OPT-IN `data-sirentide-source="sha256:<hex>"` on the
/// root `<svg>`, so a baked file names the exact DSL it came from, as the jar manifest names its
/// source revision.
///
/// The oracle is computed INDEPENDENTLY here (JDK SHA-256 over the UTF-8 bytes of the DSL the bake
/// rendered), and the rest of the SVG must be untouched: removing the one attribute must give back
/// the default bake byte for byte. The default (no flag) must carry no such attribute at all.
class CliSourceHashTest {

    @TempDir
    Path tmp;

    private static final String BODY = "flowchart TD\n    A[Start] --> B[End]";
    private static final String DROPPING_BODY = "flowchart TD\n    A[Start] --> B[End]\n    mystyle A fill:#f00";

    private static final Pattern ATTR = Pattern.compile(" data-sirentide-source=\"sha256:([0-9a-f]{64})\"");

    static String sha256(String dsl) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(dsl.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /// The expected hashed form of `svg`, built independently of Main: the attribute is the LAST one
    /// on the root tag, immediately before the root tag's closing `>`.
    static String expectHashed(String svg, String dsl) {
        int end = svg.indexOf('>');
        return svg.substring(0, end) + " data-sirentide-source=\"sha256:" + sha256(dsl) + "\"" + svg.substring(end);
    }

    private static String body(Path md) throws IOException {
        return FenceExtractor.extractFirstSirentideFence(Files.readString(md, StandardCharsets.UTF_8));
    }

    @Test
    void theDefaultBakeCarriesNoSourceAttribute() throws IOException {
        Path md = md(tmp, "d.md", BODY);
        Captured c = run("render", md.toString());
        assertFalse(c.out().contains("data-sirentide-source"), "opt-in: the default bake is unchanged");
    }

    @Test
    void theFlagStampsTheFenceBodysSha256OnTheRootAndChangesNothingElse() throws IOException {
        Path md = md(tmp, "d.md", BODY);
        Captured c = run("render", md.toString(), "--source-hash");
        assertEquals(0, c.exitCode(), c.err());
        String plain = Sirentide.render(body(md));
        assertEquals(expectHashed(plain, body(md)), c.out());
        Matcher m = ATTR.matcher(c.out());
        assertTrue(m.find());
        assertTrue(m.start() < c.out().indexOf('>'), "the attribute is on the ROOT tag");
        assertEquals(plain, m.replaceFirst(""), "removing the attribute gives back the default bake");
    }

    @Test
    void theHashFollowsTheSourceNotTheFile() throws IOException {
        Path a = md(tmp, "a.md", BODY);
        Path b = md(tmp, "b.md", BODY.replace("End", "Finish"));
        Matcher ma = ATTR.matcher(run("render", a.toString(), "--source-hash").out());
        Matcher mb = ATTR.matcher(run("render", b.toString(), "--source-hash").out());
        assertTrue(ma.find() && mb.find());
        assertFalse(ma.group(1).equals(mb.group(1)), "a one-word change in the source changes the hash");
    }

    @Test
    void theStdinArmHashesTheRawDsl() throws IOException {
        String dsl = BODY + "\n";
        Captured c = runWithStdin(dsl, "render", "-", "--source-hash");
        assertEquals(0, c.exitCode());
        assertEquals(expectHashed(Sirentide.render(dsl), dsl), c.out());
    }

    @Test
    void theBlankInertShellIsStampedToo() throws IOException {
        Captured c = runWithStdin("", "render", "-", "--source-hash");
        assertEquals(0, c.exitCode());
        assertEquals(expectHashed(Sirentide.render(""), ""), c.out());
    }

    @Test
    void aSourceThatDoesNotRenderWritesNothingEvenWithTheFlag() throws IOException {
        Path out = tmp.resolve("o.svg");
        Captured c = runWithStdin("notadiagram\n", "render", "-", "--source-hash", "-o", out.toString());
        assertEquals(1, c.exitCode());
        assertFalse(Files.exists(out));
    }

    // --- flag combinations --------------------------------------------------------------------

    @Test
    void hashWithStrictOnADroppingSourceWritesTheStampedSvgAndExits1() throws IOException {
        Path md = md(tmp, "drop.md", DROPPING_BODY);
        Path out = tmp.resolve("o.svg");
        Captured c = run("render", md.toString(), "--source-hash", "--strict", "-o", out.toString());
        assertEquals(1, c.exitCode());
        assertEquals(expectHashed(Sirentide.render(body(md)), body(md)), Files.readString(out, StandardCharsets.UTF_8));
    }

    @Test
    void hashWithStrictAndAFailingPngKeepsStrictPrecedence() throws IOException {
        Path md = md(tmp, "drop.md", DROPPING_BODY);
        Captured c = run("render", md.toString(), "--source-hash", "--strict",
            "--png", tmp.resolve("x.png").toString(), "--brewshot", tmp.resolve("none.jar").toString());
        assertEquals(1, c.exitCode());
        assertTrue(c.err().contains("BrewShot jar not readable"), c.err());
    }

    @Test
    void hashWithAFailingPngOnACleanSourceIsThePngCode() throws IOException {
        Path md = md(tmp, "d.md", BODY);
        Captured c = run("render", md.toString(), "--source-hash",
            "--png", tmp.resolve("x.png").toString(), "--brewshot", tmp.resolve("none.jar").toString());
        assertEquals(2, c.exitCode());
        assertTrue(ATTR.matcher(c.out()).find(), "the SVG still reached stdout before the PNG attempt");
    }

    @Test
    void hashWithMathStampsTheTypesetBake() throws IOException {
        CliRun.assertLatteXJarPresent();
        Path md = md(tmp, "m.md", "flowchart TD\n    A[\"$\\sqrt{2}$\"] --> B");
        Captured c = run("render", md.toString(), "--math", "--lattex", LATTEX_JAR.toString(), "--source-hash");
        assertEquals(0, c.exitCode(), c.err());
        assertEquals(expectHashed(Sirentide.render(body(md), new LatteXMathFragmentRenderer()), body(md)), c.out());
    }
}
