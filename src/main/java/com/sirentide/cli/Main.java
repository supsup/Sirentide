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
import java.nio.file.LinkOption;
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
          sirentide render <file.md> [flags]    Render the first ```sirentide fence the /docs bake would capture.
          sirentide render - [flags]            Same as the legacy shape (raw DSL on stdin), verb spelling.

        Flags:
          -o PATH          write the SVG here instead of stdout (atomic replace)
          --png PATH       ALSO screenshot the baked SVG to a PNG, for a local file:// review
                           artifact in one step instead of a second manual pass
          --brewshot PATH  the BrewShot jar --png shells out to; or set SIRENTIDE_BREWSHOT_JAR.
                           Sirentide does NOT bundle it -- same posture as the math backend, the
                           host supplies the tool -- so --png without it is a loud usage error,
                           never a silent skip.
          --strict         treat a DROPPED statement as a failure (exit 1). A render can succeed
                           and still drop a statement Sirentide does not recognise; the caveat
                           naming that line goes to stderr and the exit stays 0 by default,
                           because the bake really happens and really serves that SVG. CI is
                           where nobody reads stderr, so an unattended caller opts in here. The
                           SVG is still written -- it is exactly what /docs would serve, and a
                           rejected gate is worth inspecting.

        Exit codes: 0 = rendered (the SVG is what /docs would embed). 1 = fence found but it does
        not render — /docs would keep the fence verbatim with a visible caption; nothing written
        — OR --strict was passed and the render dropped a statement, where the SVG IS written.
        2 = loud error (no fence, unreadable/over-cap input, unwritable -o); nothing written.
        -o writes are atomic: the destination is replaced only after a complete render + write, so
        a failure never truncates or corrupts an existing file. A filesystem that cannot replace
        atomically is a loud exit 2 with the destination untouched (never a non-atomic overwrite).
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
            // M0 legacy path: single-shot stdin -> stdout DSL render. Routed through
            // writeRawDslOrRefuse so a source that does NOT render is a loud exit 1 rather than a
            // blank SVG at exit 0 (plan 8a991947) — and through writeOutput beneath it so the
            // stdout error check stays shared (one write seam, review sirentide/471): a broken
            // stdout consumer is exit 2. A successful bake's SVG bytes are unchanged.
            return writeRawDslOrRefuse(renderRawDsl(in), null, out, err);
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
        // Flag loop, replacing the old exact-arity check. The two shapes that check accepted --
        // `render <file>` and `render <file> -o OUT` -- still parse identically; anything it
        // rejected still exits 2 with the same message, which is what the arity tests pin.
        String outPath = null;
        String pngPath = null;
        boolean strictFailed = false;
        String brewshotJar = System.getenv(BREWSHOT_JAR_ENV);
        boolean strict = false;
        for (int i = 2; i < args.length; i++) {
            String flag = args[i];
            // --strict is VALUELESS, so it is matched before the needs-a-value arity check below;
            // treating it like -o would consume the next argument and silently eat a path.
            if ("--strict".equals(flag)) {
                strict = true;
                continue;
            }
            if (!"-o".equals(flag) && !"--png".equals(flag) && !"--brewshot".equals(flag)) {
                err.print(USAGE);
                err.println("sirentide: bad arguments after the file path");
                return 2;
            }
            if (i + 1 >= args.length) {
                err.print(USAGE);
                err.println("sirentide: " + flag + " needs a value");
                return 2;
            }
            String value = args[++i];
            switch (flag) {
                case "-o" -> outPath = value;
                case "--png" -> pngPath = value;
                default -> brewshotJar = value;
            }
        }
        // RESOLVE THE SCREENSHOT BACKEND BEFORE RENDERING, so a missing jar costs the author an
        // instant usage error instead of a render they then discover produced no PNG.
        if (pngPath != null && (brewshotJar == null || brewshotJar.isBlank())) {
            err.print(USAGE);
            err.println("sirentide: --png needs the BrewShot jar: pass --brewshot PATH or set "
                + BREWSHOT_JAR_ENV + ". Sirentide does not bundle it -- same posture as the math"
                + " backend, the host supplies the tool.");
            return 2;
        }

        String source = args[1];
        String svg;
        if ("-".equals(source)) {
            // The verb-spelled alias of the legacy shape: raw DSL on stdin, no fence extraction.
            // The REFUSAL is shared with the args.length == 0 path above by CONSTRUCTION — both go
            // through rawDslSvgOrNull, so the stated equivalence is enforced by the single seam
            // rather than asserted in a comment that a later edit can silently falsify.
            //
            // The TAIL is deliberately not shared with that path, and cannot diverge from it: the
            // no-args shape parses no flags at all, so there is no input it can express on which
            // `-o` or `--png` handling could differ. Returning here instead — which is what this
            // arm did until sirentide/905 — put writeOutput AND writePng downstream of a return,
            // so every --png guard was unreachable on stdin and `render - --png` exited 0 having
            // written no PNG: verbatim the failure {@link #writePng} names as this project's
            // signature defect.
            svg = rawDslSvgOrNull(renderRawDsl(in), err);
            if (svg == null) {
                return 1;
            }
        } else {

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
            svg = result.svg();

            // AN `OK` RENDER CAN STILL HAVE LOST A LINE, and until now this verb said nothing
            // about it. The directive-shape rule DROPS an unknown directive-shaped statement and
            // records a line-scoped caveat on an otherwise-OK render, precisely so a lost line is
            // not lost silently — but the caveat lived only in the API. Through this CLI, which
            // the authoring docs name as THE local check, the author saw exit 0, no output, and a
            // diagram quietly missing their line. A caveat channel nothing reads is not a channel.
            //
            // Printed to stderr, and the exit stays 0: the render genuinely succeeded and /docs
            // genuinely serves this SVG. Turning a dropped statement into a failure here would
            // claim a bake outcome that does not happen, which is the same untruth the exit-1 arm
            // above exists to avoid — pointing the other way.
            String caveat = result.diagnostics().detail();
            if (caveat != null && !caveat.isBlank()) {
                err.println("sirentide: rendered, with caveats — " + caveat);
                err.println("  the SVG is what /docs would embed; the named statement(s) are absent from it");
                // --strict, ruled at sirentide/977: stderr is the right AUTHOR channel and the
                // wrong CI channel, because CI is exactly where nobody reads stderr. A caveat
                // that cannot gate anything in the one environment that runs unattended is
                // recorded-but-unseeable one level up — the same distance this change closed at
                // the API/render seam, reopened at the render/CI seam.
                //
                // Opt-in, so the default stays honest: a drop is not a failed bake. The SVG IS
                // still written, unlike the exit-1 arm above — there the artifact would have
                // been a lie about what /docs serves, here it is exactly what /docs serves and
                // the caller wants to inspect what its gate rejected.
                if (strict) {
                    err.println("  --strict: treating dropped statement(s) as a failure");
                    strictFailed = true;
                }
            }
        }

        // THE ONE WRITE TAIL, reached by both arms. Its ordering guarantee is the reason writePng
        // may assume the SVG is already on disk: see {@link #writePng}'s ORDER MATTERS note.
        int code = writeOutput(svg, outPath, out, err);
        if (code == 0 && strictFailed && pngPath == null) {
            return 1;
        }
        if (code != 0 || pngPath == null) {
            return code;
        }
        return writePng(svg, pngPath, brewshotJar, err);
    }

    /// Environment variable naming the BrewShot jar, so an author sets it once per shell instead of
    /// passing `--brewshot` on every render-check. `--brewshot` overrides it.
    static final String BREWSHOT_JAR_ENV = "SIRENTIDE_BREWSHOT_JAR";

    /// Screenshot the baked SVG to a PNG (plan 6eb098d6 slice B).
    ///
    /// WHY SHELL OUT RATHER THAN DEPEND. Sirentide does not take BrewShot as a dependency, for the
    /// same reason it does not take LatteX: `build.gradle.kts` states that the host supplies the
    /// heavy tools and "Sirentide never depends on LatteX at runtime". A screenshot backend is the
    /// same shape of thing -- it drags in a browser -- so it is located at RUN time and its absence
    /// is a loud usage error, never a silent skip. A `--png` that quietly produced no PNG would be
    /// this project's signature defect: a surface reporting success while establishing nothing.
    ///
    /// ORDER MATTERS. This runs only after {@link #writeOutput} returned 0, so the SVG is on disk
    /// (or stdout) before the screenshot is attempted, and a render that did NOT happen -- exit 1,
    /// nothing written -- never reaches here. The PNG can therefore never be newer evidence than
    /// the SVG it claims to depict.
    private static int writePng(String svg, String pngPath, String brewshotJar, PrintStream err) {
        Path html = null;
        try {
            if (!Files.isReadable(Path.of(brewshotJar))) {
                err.println("sirentide: BrewShot jar not readable: '" + brewshotJar + "'");
                return 2;
            }
            // A minimal wrapper, no external references: BrewShot loads it over file:// and the SVG
            // is inline, so nothing is fetched and the shot cannot depend on network state.
            html = Files.createTempFile("sirentide-render-", ".html");
            Files.writeString(html, "<!doctype html><html><body style=\"margin:0;background:#fff\">"
                + svg + "</body></html>", StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("java", "-jar", brewshotJar,
                html.toAbsolutePath().toString(), "-o", pngPath)
                .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                err.println("sirentide: BrewShot failed (exit " + exit + "): " + output.strip());
                return 2;
            }
            // TRUST THE FILE, NOT THE EXIT CODE. A zero exit from a subprocess is a claim about the
            // subprocess, not about the artifact -- and a zero-byte PNG at exit 0 is exactly the
            // shape of failure this CLI already refuses for blank SVGs.
            Path png = Path.of(pngPath);
            if (!Files.isRegularFile(png) || Files.size(png) == 0) {
                err.println("sirentide: BrewShot exited 0 but wrote no PNG at '" + pngPath + "'");
                return 2;
            }
            return 0;
        } catch (IOException e) {
            err.println("sirentide: cannot write PNG '" + pngPath + "': " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("sirentide: interrupted while screenshotting");
            return 2;
        } finally {
            if (html != null) {
                try {
                    Files.deleteIfExists(html);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing a successful render over.
                }
            }
        }
    }

    /// The M0 read shape, factored out so both the legacy no-args path and the `render -` alias
    /// share it byte-for-byte. Bound to the parser's source cap (+1 to detect overflow): a runaway
    /// stdin degrades to the inert shell in `render()` rather than OOMing on `readAllBytes`.
    ///
    /// Renders through the DIAGNOSTICS api so the caller can refuse a non-render, rather than
    /// through the plain `render()` which cannot distinguish "baked a blank diagram" from "did not
    /// bake". See {@link #writeRawDslOrRefuse} for why.
    private static RenderResult renderRawDsl(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(DslParser.MAX_SOURCE_BYTES + 1);
        String dsl = new String(bytes, StandardCharsets.UTF_8);
        return tryRenderWithDiagnostics(dsl);
    }

    /// Write a raw-DSL bake, or refuse it LOUDLY — the truthful render-check posture the
    /// `render <file.md>` path has always had (review sirentide/471 B3), now applied to the two
    /// raw-DSL arms that skipped it.
    ///
    /// MEASURED BEFORE THIS EXISTED: `notadiagram` and a composite-state diagram each produced
    /// exit 0, an 85-byte `width="0" height="0"` shell, and EMPTY stderr. A caller checking the
    /// exit code was told the bake succeeded and handed a valid, blank SVG. That is the same
    /// claim the fence path already refuses to make.
    ///
    /// THE PREDICATE IS THE OUTCOME, NOT THE IR TYPE, and that is load-bearing rather than
    /// stylistic. A BLANK source parses to the `Empty` IR and reports `OK` — baking nothing from
    /// nothing is an honest success — while `flowchart TD` with no body is a real `Flowchart`,
    /// also OK. Keying on `Empty` would refuse a legitimate blank input; only the diagnostic
    /// channel separates "nothing to draw" from "could not read this".
    private static int writeRawDslOrRefuse(RenderResult result, String outPath,
                                           PrintStream out, PrintStream err) {
        String svg = rawDslSvgOrNull(result, err);
        if (svg == null) {
            return 1;
        }
        return writeOutput(svg, outPath, out, err);
    }

    /// THE SINGLE RAW-DSL REFUSAL SEAM, shared by the no-args legacy path and `render -`.
    ///
    /// It exists so the two arms cannot drift: before sirentide/905 their equivalence was asserted
    /// only by a comment, and the `render -` arm was changed (to reach `-o`) without the comment
    /// becoming false, which is exactly how the unreachable-`--png` defect got in. Extracting the
    /// predicate makes "these two refuse identically" a property of the code rather than a claim
    /// about it. Returns the SVG on an honest bake, or null having already reported the reason.
    private static String rawDslSvgOrNull(RenderResult result, PrintStream err) {
        if (result == null || result.diagnostics().outcome() != Outcome.OK || result.svg() == null) {
            String reason = result == null ? "renderer failure" : result.diagnostics().message();
            err.println("sirentide: diagram did not render — " + reason + " (nothing written)");
            return null;
        }
        return result.svg();
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

    /// How the completed temp sibling is placed onto the destination — the ONLY move seam in the
    /// class, injectable so a test can force {@link AtomicMoveNotSupportedException} and prove the
    /// fail-closed branch (review sirentide/490 B1). Production is {@link #ATOMIC_REPLACE}.
    @FunctionalInterface
    interface Mover {
        void move(Path completedTmp, Path dest) throws IOException;
    }

    /// The production mover: `ATOMIC_MOVE + REPLACE_EXISTING`, and NOTHING else — deliberately no
    /// plain-`REPLACE_EXISTING` retry. For a non-atomic `Files.move` the Java contract leaves the
    /// state of both files UNDEFINED on an I/O failure (the destination may be incomplete), which
    /// would silently void the unconditional never-corrupts promise in {@link #USAGE} and
    /// `QUICKSTART.md` exactly on the filesystems where it matters (review sirentide/490 B1; the
    /// prior fallback was the same class of bug as review 471's direct truncating write).
    static final Mover ATOMIC_REPLACE = (completedTmp, dest) ->
        Files.move(completedTmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

    /// Writes the baked `svg` to `outPath` when given, else to `out` (stdout). Returns the exit
    /// code: 0 on a complete write, 2 on any failure.
    ///
    /// ## `-o` destination policy (review sirentide/471 B1; atomic-only per review sirentide/490 B1)
    /// The SVG is first written COMPLETELY to a sibling temp file (`.sirentide-*.svg.tmp`) in the
    /// destination's directory, closed, then moved onto the destination with
    /// `ATOMIC_MOVE + REPLACE_EXISTING` — atomically or not at all. A filesystem that cannot
    /// atomically replace ({@link AtomicMoveNotSupportedException}) is a loud exit 2 with the
    /// destination untouched; there is deliberately NO non-atomic fallback (see
    /// {@link #ATOMIC_REPLACE}). Consequences, all deliberate:
    /// - An EXISTING destination is either fully replaced by the new SVG or left BYTE-IDENTICAL —
    ///   a failed render, an over-quota write, an unwritable directory, or an
    ///   atomic-move-incapable filesystem never truncates it.
    /// - The temp file is always deleted on failure (no `.tmp` litter).
    /// - A destination whose PATH ENTRY is a directory is a loud exit 2, untouched. The check is
    ///   `NOFOLLOW_LINKS`: the policy keys on the entry, not what a link points at.
    /// - A destination that is a SYMLINK — even one pointing at a directory — is REPLACED as a
    ///   path entry (the move swaps the link itself for a regular file); it is not written
    ///   through to the link target, and the target is untouched.
    /// - `-o` naming the INPUT file is safe: the input was fully read before any write, and the
    ///   destination changes only at the final move.
    ///
    /// The stdout branch checks the stream's error state (PrintStream swallows IOException): a
    /// consumer that closed the pipe yields exit 2, never a silent success.
    private static int writeOutput(String svg, String outPath, PrintStream out, PrintStream err) {
        return writeOutput(svg, outPath, out, err, ATOMIC_REPLACE);
    }

    /// Seam-injected variant of {@link #writeOutput(String, String, PrintStream, PrintStream)} —
    /// package-private so a test can substitute a `Mover` that throws
    /// {@link AtomicMoveNotSupportedException} (unreachable on a POSIX temp dir) and prove the
    /// fail-closed contract. Production callers always go through the 4-arg overload.
    static int writeOutput(String svg, String outPath, PrintStream out, PrintStream err, Mover mover) {
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
        if (Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
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
            mover.move(tmp, dest);
            return 0;
        } catch (AtomicMoveNotSupportedException e) {
            // Fail closed (review sirentide/490 B1): a non-atomic replacement could leave the
            // destination incomplete on failure — refuse it; the finally block removes the
            // completed temp sibling and the existing destination stays byte-identical.
            err.println("sirentide: cannot write '" + outPath + "': filesystem does not support"
                + " atomic replace — refusing a non-atomic overwrite (existing file untouched)");
            return 2;
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
