package com.sirentide.cli;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.parse.DslParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// CLI entry: two shapes atop the same bake.
///
/// - `sirentide` (no args): read a raw Sirentide DSL from stdin, write baked SVG to stdout — the
///   original M0 shape (mirrors LatteX's CLI), unchanged byte-for-byte.
/// - `sirentide render <file.md|-> [-o PATH]`: the render-check verb (plan
///   6eb098d6-sirentide-local-render-check-cli slice A). Extracts the first top-level
///   ```` ```sirentide ```` fence from a markdown file (see {@link FenceExtractor}) and bakes it,
///   so an author can check what `/docs` WOULD render for their fence without pushing to Stafficy.
///   `-` in the file-path slot is the legacy stdin-DSL path under the verb spelling (no fence
///   extraction — see {@link #run}). `-o PATH` writes the SVG to a file instead of stdout.
///
/// `--batch` (NUL-delimited, many-per-invocation, one JVM) lands with real rendering in M1 — it is
/// the amortization lever for many diagrams per page; still a documented stub.
public final class Main {

    private Main() {}

    private static final String USAGE = """
        Usage:
          sirentide                             Read a DSL source from stdin, bake to stdout (legacy M0 shape).
          sirentide render <file.md> [-o PATH]  Render the first ```sirentide fence in a markdown file.
          sirentide render - [-o PATH]          Same as the legacy shape (raw DSL on stdin), verb spelling.

        render-check posture: a fence that fails to parse degrades to the inert SVG shell (exit 0,
        one-line diagnostic on stderr) — matching what /docs bakes for a bad fence. A file with no
        ```sirentide fence, or a file that can't be read, is a loud error (exit 2, nothing written).
        """;

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /// The whole CLI, minus the process exit — factored out so tests can drive every path (exit
    /// code, stdout bytes, stderr diagnostic) without a `System.exit` call tearing down the test
    /// JVM. Returns the process exit code; never throws for an author-facing failure (bad file,
    /// missing fence, bad fence) — those are reported on `err` and reflected in the return code,
    /// per the bake's own "malformed input degrades, never throws" invariant (DESIGN §6/§7).
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0) {
            // M0 legacy path, unchanged: single-shot stdin -> stdout DSL render, no diagnostics.
            out.print(renderRawDsl(in));
            return 0;
        }
        if (!"render".equals(args[0])) {
            err.print(USAGE);
            err.println("sirentide: unknown command '" + args[0] + "'");
            return 2;
        }
        if (args.length < 2) {
            err.print(USAGE);
            err.println("sirentide: 'render' needs a file path (or '-' for stdin)");
            return 2;
        }
        String outPath = null;
        if (args.length == 4 && "-o".equals(args[2])) {
            outPath = args[3];
        } else if (args.length != 2) {
            err.print(USAGE);
            err.println("sirentide: bad arguments after the file path");
            return 2;
        }

        String source = args[1];
        if ("-".equals(source)) {
            // The verb-spelled alias of the legacy shape: raw DSL on stdin, no fence extraction, no
            // diagnostics — deliberately identical to the args.length == 0 path above.
            return writeOutput(renderRawDsl(in), outPath, out, err);
        }

        String markdown;
        try {
            markdown = Files.readString(Path.of(source), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("sirentide: cannot read '" + source + "': " + e.getMessage());
            return 2;
        }

        String fenceBody = FenceExtractor.extractFirstSirentideFence(markdown);
        if (fenceBody == null) {
            err.println("sirentide: no ```sirentide fence found in '" + source + "'");
            return 2;
        }

        // Bake-parity posture (plan 6eb098d6 slice A, point 3): the bake NEVER throws on a bad
        // fence body — it degrades to the inert shell (DESIGN §6/§7), and this CLI mirrors that
        // exactly rather than inventing a stricter local failure mode. `renderWithDiagnostics`
        // already exists for precisely this "why did it degrade" signal (plan
        // sirentide-render-diagnostics); reusing it means the CLI's diagnostic message can never
        // drift from what the diagnostics channel already promises.
        RenderResult result = Sirentide.renderWithDiagnostics(fenceBody);
        if (result.diagnostics().outcome() != Outcome.OK) {
            err.println("sirentide: " + result.diagnostics().message());
        }
        return writeOutput(result.svg(), outPath, out, err);
    }

    /// The M0 read shape, factored out so both the legacy no-args path and the `render -` alias
    /// share it byte-for-byte. Bound to the parser's source cap (+1 to detect overflow): a runaway
    /// stdin degrades to the inert shell in `render()` rather than OOMing on `readAllBytes`.
    private static String renderRawDsl(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(DslParser.MAX_SOURCE_BYTES + 1);
        String dsl = new String(bytes, StandardCharsets.UTF_8);
        return Sirentide.render(dsl);
    }

    /// Writes the baked `svg` to `outPath` (creating/truncating the file) when given, else to
    /// `out`. A write failure (bad `-o` path) is symmetric with the unreadable-input-file case:
    /// exit 2, no partial/garbled output claimed as success.
    private static int writeOutput(String svg, String outPath, PrintStream out, PrintStream err) {
        if (outPath == null) {
            out.print(svg);
            return 0;
        }
        try {
            Files.writeString(Path.of(outPath), svg, StandardCharsets.UTF_8);
            return 0;
        } catch (IOException e) {
            err.println("sirentide: cannot write '" + outPath + "': " + e.getMessage());
            return 2;
        }
    }
}
