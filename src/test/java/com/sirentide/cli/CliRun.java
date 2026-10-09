package com.sirentide.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Shared driver for the `--math` / `--batch` / `--source-hash` CLI tests: runs {@link Main#run}
/// in-process and captures exit code, stdout BYTES (batch records are NUL-terminated, so a String
/// view alone would hide the delimiter), and stderr.
final class CliRun {

    private CliRun() {}

    /// The LatteX jar the build already vendors for its test-scope math proofs. The CLI loads it at
    /// RUN time from a path, the same way it locates BrewShot, so the core stays dependency-free.
    static final Path LATTEX_JAR = Path.of("libs/lattex-0.6.0.jar").toAbsolutePath();

    record Captured(int exitCode, byte[] outBytes, String err) {
        String out() {
            return new String(outBytes, StandardCharsets.UTF_8);
        }
    }

    static Captured run(String... args) throws IOException {
        return runWithStdin(new byte[0], args);
    }

    static Captured runWithStdin(String stdin, String... args) throws IOException {
        return runWithStdin(stdin.getBytes(StandardCharsets.UTF_8), args);
    }

    static Captured runWithStdin(byte[] stdin, String... args) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(stdin),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Captured(code, outBuf.toByteArray(), errBuf.toString(StandardCharsets.UTF_8));
    }

    static Path md(Path dir, String name, String fenceBody) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "# Doc\n\n```sirentide\n" + fenceBody + "\n```\n", StandardCharsets.UTF_8);
        return p;
    }

    static void assertLatteXJarPresent() {
        assertTrue(Files.isRegularFile(LATTEX_JAR), "the vendored LatteX jar must exist: " + LATTEX_JAR);
    }
}
