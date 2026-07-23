package com.sirentide.cli;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.parse.DslParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// CLI entry: two shapes atop the same bake.
///
/// - `sirentide` (no args): read a raw Sirentide DSL from stdin, write baked SVG to stdout — the
///   original M0 shape (mirrors LatteX's CLI).
/// - `sirentide render <file.md|-> [-o PATH]`: the render-check verb (plan
///   6eb098d6-sirentide-local-render-check-cli slice A). Extracts the first ```` ```sirentide ````
///   fence the Stafficy `/docs` bake would capture (exact scanner parity with
///   `SirentideDiagramConverter` — see {@link FenceExtractor}) and bakes it, so an author can
///   check what `/docs` WILL do with their fence without pushing to Stafficy. `-` in the
///   file-path slot is the legacy stdin-DSL path under the verb spelling (no fence extraction —
///   see {@link #run}). `-o PATH` writes the SVG to a file instead of stdout (atomically — see
///   {@link #writeOutput}).
///
/// ## Exit-code contract (review sirentide/471 B3 — truthful about what /docs would serve)
/// - `0` — the fence renders; the SVG written IS what the `/docs` bake would embed.
/// - `1` — a ```sirentide fence was found but its body does NOT render. `/docs` would NOT serve
///   an SVG for it: the bake keeps the original fence verbatim and prepends a visible caption
///   (`SirentideDiagramConverter#emitDiagram`). Nothing is written to stdout or `-o`; the
///   diagnostic reason goes to stderr. (The earlier "exit 0 + inert shell" posture claimed bake
///   parity it did not have — the inert shell is never what the page contains.)
/// - `2` — loud usage/IO error: no capturable fence, unreadable input, over-cap input, unwritable
///   `-o` destination, or a stdout write failure. Nothing (new) is written.
///
/// `--batch` (NUL-delimited, many-per-invocation, one JVM) lands with real rendering in M1 — it is
/// the amortization lever for many diagrams per page; still a documented stub.
public final class Main {

    private Main() {}

    /// Read bound for the markdown FILE path (the DSL stdin paths are bound by
    /// {@link DslParser#MAX_SOURCE_BYTES}). A docs page is orders of magnitude smaller than this;
    /// the cap exists so the whole-file read + line split can never allocate unboundedly ahead of
    /// the parser's own source cap (review sirentide/471, resource-bound correction). Over-cap is
    /// a LOUD exit 2, never a silent truncation.
    static final int MAX_MARKDOWN_BYTES = 8 * 1024 * 1024;

    private static final String USAGE = """
        Usage:
          sirentide                             Read a DSL source from stdin, bake to stdout (legacy M0 shape).
          sirentide render <file.md> [-o PATH]  Render the first ```sirentide fence the /docs bake would capture.
          sirentide render - [-o PATH]          Same as the legacy shape (raw DSL on stdin), verb spelling.

        Exit codes: 0 = rendered (the SVG is what /docs would embed). 1 = fence found but it does
        not render — /docs would keep the fence verbatim with a visible caption; nothing written.
        2 = loud error (no fence, unreadable/over-cap input, unwritable -o); nothing written.
        -o writes are atomic: the destination is replaced only after a complete render + write, so
        a failure never truncates or corrupts an existing file.
        """;

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /// The whole CLI, minus the process exit — factored out so tests can drive every path (exit
    /// code, stdout bytes, stderr diagnostic) without a `System.exit` call tearing down the test
    /// JVM. Returns the process exit code; never throws for an author-facing failure (bad file,
    /// missing fence, bad fence) — those are reported on `err` and reflected in the return code.
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0) {
            // M0 legacy path: single-shot stdin -> stdout DSL render, no diagnostics. Routed
            // through writeOutput so the stdout error check is shared (one write seam, review
            // sirentide/471 stream-error correction): a broken stdout consumer is now exit 2,
            // not a silent 0 — the SVG bytes themselves are unchanged.
            return writeOutput(renderRawDsl(in), null, out, err);
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
        try (InputStream fileIn = Files.newInputStream(Path.of(source))) {
            byte[] bytes = fileIn.readNBytes(MAX_MARKDOWN_BYTES + 1);
            if (bytes.length > MAX_MARKDOWN_BYTES) {
                err.println("sirentide: cannot read '" + source + "': larger than the "
                    + MAX_MARKDOWN_BYTES + "-byte markdown cap");
                return 2;
            }
            markdown = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("sirentide: cannot read '" + source + "': " + e.getMessage());
            return 2;
        }

        String fenceBody = FenceExtractor.extractFirstSirentideFence(markdown);
        if (fenceBody == null) {
            err.println("sirentide: no ```sirentide fence found in '" + source + "'"
                + " (a fence nested inside another fence is not captured — matching the /docs bake)");
            return 2;
        }

        // Truthful render-check posture (review sirentide/471 B3): the /docs bake NEVER serves an
        // SVG for a fence that fails to render — SirentideDiagramConverter keeps the original
        // fence verbatim and prepends a visible caption. So a not-OK render here is a LOUD exit 1
        // with NOTHING written: writing the inert shell and exiting 0 would claim a bake outcome
        // /docs does not produce. The defensive catch mirrors the converter's tryRender
        // (RuntimeException + StackOverflowError -> degrade, never a crash).
        RenderResult result = tryRenderWithDiagnostics(fenceBody);
        if (result == null || result.diagnostics().outcome() != Outcome.OK || result.svg() == null) {
            String reason = result == null ? "renderer failure" : result.diagnostics().message();
            err.println("sirentide: diagram did not render — " + reason
                + "; /docs would keep this fence verbatim with a visible caption (nothing written)");
            return 1;
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

    /// Renders via the diagnostics API, or returns null on an unexpected throw — the same
    /// defensive net as `SirentideDiagramConverter#tryRender` (Sirentide should not throw, but a
    /// render-check that crashes where the bake degrades would misreport the bake).
    private static RenderResult tryRenderWithDiagnostics(String dsl) {
        try {
            return Sirentide.renderWithDiagnostics(dsl);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /// Writes the baked `svg` to `outPath` when given, else to `out` (stdout). Returns the exit
    /// code: 0 on a complete write, 2 on any failure.
    ///
    /// ## `-o` destination policy (review sirentide/471 B1 — atomic, never-corrupting)
    /// The SVG is first written COMPLETELY to a sibling temp file (`.sirentide-*.svg.tmp`) in the
    /// destination's directory, closed, then moved onto the destination with
    /// `ATOMIC_MOVE + REPLACE_EXISTING` (falling back to a plain `REPLACE_EXISTING` move only if
    /// the filesystem cannot do an atomic move). Consequences, all deliberate:
    /// - An EXISTING destination is either fully replaced by the new SVG or left BYTE-IDENTICAL —
    ///   a failed render, an over-quota write, or an unwritable directory never truncates it.
    /// - The temp file is always deleted on failure (no `.tmp` litter).
    /// - A destination that is a DIRECTORY is a loud exit 2, untouched.
    /// - A destination that is a SYMLINK is REPLACED as a path entry (the move swaps the link
    ///   itself for a regular file); it is not written through to the link target.
    /// - `-o` naming the INPUT file is safe: the input was fully read before any write, and the
    ///   destination changes only at the final move.
    ///
    /// The stdout branch checks the stream's error state (PrintStream swallows IOException): a
    /// consumer that closed the pipe yields exit 2, never a silent success.
    private static int writeOutput(String svg, String outPath, PrintStream out, PrintStream err) {
        if (outPath == null) {
            out.print(svg);
            out.flush();
            if (out.checkError()) {
                err.println("sirentide: error writing to stdout");
                return 2;
            }
            return 0;
        }
        Path dest = Path.of(outPath);
        if (Files.isDirectory(dest)) {
            err.println("sirentide: cannot write '" + outPath + "': is a directory");
            return 2;
        }
        Path parent = dest.toAbsolutePath().getParent();
        if (parent == null) {
            err.println("sirentide: cannot write '" + outPath + "': no parent directory");
            return 2;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(parent, ".sirentide-", ".svg.tmp");
            Files.writeString(tmp, svg, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Documented fallback: same complete-write-then-move shape, minus atomicity —
                // only on filesystems that cannot atomically replace (the temp sibling still
                // guarantees the content moved is complete).
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return 0;
        } catch (IOException e) {
            err.println("sirentide: cannot write '" + outPath + "': " + e.getMessage());
            return 2;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the destination is already safe either way.
                }
            }
        }
    }
}
