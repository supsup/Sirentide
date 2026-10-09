package com.sirentide.cli;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderOptions;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.parse.DslParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
/// `render --batch` (plan d9f29911): many raw-DSL sources per JVM, NUL-separated on stdin, one
/// NUL-terminated record per source on stdout — see {@link #runBatch}. `--math` (same plan) and
/// `--source-hash` (ruling sirentide/1129) are additive flags: without them, every byte this CLI
/// writes is unchanged. `--source-hash` writes only to stderr and never touches the SVG.
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
          sirentide render --batch [flags]      Many raw DSL sources in one JVM: NUL-separated on stdin, one
                                                NUL-terminated record per source on stdout, in order.

        Flags:
          -o PATH          write the SVG here instead of stdout (atomic replace)
          --png PATH       ALSO screenshot the baked SVG to a PNG, for a local file:// review
                           artifact in one step instead of a second manual pass
          --brewshot PATH  the BrewShot jar --png shells out to; or set SIRENTIDE_BREWSHOT_JAR.
                           Sirentide does NOT bundle it -- same posture as the math backend, the
                           host supplies the tool -- so --png without it is a loud usage error,
                           never a silent skip.
          --strict         treat ANY caveat as a failure (exit 1): a dropped statement, a dropped
                           or shortened label (xychart axis labels, pie outside labels),
                           untypeset math under --math, or a lint finding under --lint-overlap.
                           A render can succeed and still carry a caveat; it goes to stderr,
                           naming it, and the exit stays 0 by default, because the
                           bake really happens and really serves that SVG. CI is
                           where nobody reads stderr, so an unattended caller opts in here. The
                           SVG is still written -- it is exactly what /docs would serve, and a
                           rejected gate is worth inspecting.
          --math           typeset $...$ label runs with LatteX instead of baking them as raw text.
                           The LatteX jar is located at run time (--lattex PATH or
                           SIRENTIDE_LATTEX_JAR), never bundled; --math without it, or with a jar
                           that cannot render, is a loud usage error (exit 2). A $...$ run LatteX
                           cannot typeset falls back to its raw source and is reported as a caveat,
                           so --strict fails on it.
          --lattex PATH    the LatteX jar --math loads; or set SIRENTIDE_LATTEX_JAR.
          --lint-overlap   ALSO check every text run against text in OTHER diagram elements and
                           report each overlapping pair as a caveat (`text overlap: ...`), so
                           --strict fails on it. Off by default: without it no render gains a
                           lint caveat. Text is checked against text only, so an edge label on
                           its own stroke is never a finding; typeset math is not checked.
          --source-hash    ALSO print the SHA-256 of the RAW source bytes to stderr, as
                           `sirentide: source sha256:<64 lowercase hex>`. The SVG, -o file and
                           exit code are unchanged. See "Source hash" below for the exact bytes.

        --batch: a source is everything up to a NUL byte (a trailing NUL ends the last source; it
        does not start an empty one). Each record is the SVG `render -` would print for that source
        alone, or a `sirentide: error: ...` line when it does not render, so record N always answers
        source N; stderr names failures and caveats by 1-based record number. -o and --png do not
        combine with --batch (usage error). Exit 1 if any record failed (or, with --strict, carried
        a caveat); 2 on a usage error, empty stdin, or a stdout failure.

        Source hash (--source-hash): one stderr line per source, printed before that source's
        other diagnostics -- `sirentide: source sha256:<hex>`, or under --batch
        `sirentide: record N: source sha256:<hex>` (N 1-based, as in the batch caveat lines). It
        identifies the INPUT, so it prints for a source that does not render too; it never changes
        stdout, the -o file, the exit code, or --strict. What is hashed is the raw bytes as
        received, never a decoded or normalized copy: CRLF, a BOM and invalid UTF-8 all count, so
        a CRLF copy and an LF copy of one diagram hash differently.
          render -      every byte read from stdin:  sha256sum < diagram.dsl
                        (or: printf 'pie\\n  "A" : 1\\n' | sha256sum)
          --batch       record N's bytes, between its NULs (the NULs are not hashed); a blank
                        record hashes the empty input (e3b0c442...b855)
          render f.md   the fence body as the file holds it: the lines after the opener up to
                        the closer, each with any CR it ends in, joined by LF, WITHOUT the LF
                        that ends the last body line. To recompute, copy those bytes to a file
                        with no final newline and run sha256sum on it.
        No line prints when there is no source to identify: a usage error, no fence, or an
        unreadable or over-cap .md. A stdin over the source cap prints
        `sirentide: source sha256: unavailable ...` rather than the digest of a prefix.

        Provenance: the baked SVG carries no renderer, revision or source attribute. Which
        Sirentide built it is a property of the jar, not of the output: the jar's exact source
        revision is the Sirentide-Source-Revision line of its META-INF/MANIFEST.MF
        (unzip -p sirentide-<version>.jar META-INF/MANIFEST.MF). --source-hash identifies the
        SOURCE, on stderr; it never identifies the renderer that baked it.

        Exit codes: 0 = rendered (the SVG is what /docs would embed). 1 = fence found but it does
        not render — /docs would keep the fence verbatim with a visible caption; nothing written
        — OR --strict was passed and the render carried a caveat (a dropped statement, a dropped
        or shortened label, untypeset math, or a lint finding), where the SVG IS written.
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
        String lattexJar = System.getenv(LATTEX_JAR_ENV);
        boolean strict = false;
        boolean math = false;
        boolean lintOverlap = false;
        boolean sourceHash = false;
        for (int i = 2; i < args.length; i++) {
            String flag = args[i];
            // VALUELESS flags are matched before the needs-a-value arity check below; treating one
            // like -o would consume the next argument and silently eat a path.
            if ("--strict".equals(flag)) {
                strict = true;
                continue;
            }
            if ("--math".equals(flag)) {
                math = true;
                continue;
            }
            if ("--lint-overlap".equals(flag)) {
                lintOverlap = true;
                continue;
            }
            if ("--source-hash".equals(flag)) {
                sourceHash = true;
                continue;
            }
            if (!"-o".equals(flag) && !"--png".equals(flag) && !"--brewshot".equals(flag)
                && !"--lattex".equals(flag)) {
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
                case "--lattex" -> lattexJar = value;
                default -> brewshotJar = value;
            }
        }
        String source = args[1];
        boolean batch = BATCH.equals(source);
        // --batch writes records to STDOUT and has no single SVG to screenshot, so -o and --png are
        // refused rather than ignored: a --png that quietly produced no PNG is the defect writePng
        // names, and an -o that quietly wrote nothing is the same shape.
        if (batch && (outPath != null || pngPath != null)) {
            err.print(USAGE);
            err.println("sirentide: " + (outPath != null ? "-o" : "--png") + " cannot be combined with"
                + " --batch: --batch writes NUL-terminated records to stdout");
            return 2;
        }
        // RESOLVE THE MATH BACKEND BEFORE RENDERING (and before the screenshot backend is blamed),
        // for the same reason as the BrewShot check below: a missing jar costs an instant usage error,
        // not a bake that silently shows raw $...$ text.
        if (math && (lattexJar == null || lattexJar.isBlank())) {
            err.print(USAGE);
            err.println("sirentide: --math needs the LatteX jar: pass --lattex PATH or set "
                + LATTEX_JAR_ENV + ". Sirentide does not bundle it -- the host supplies the math backend.");
            return 2;
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
        LatteXBackend backend = null;
        if (math) {
            backend = LatteXBackend.load(lattexJar, err);
            if (backend == null) {
                return 2;
            }
        }
        // DEFAULT unless --lint-overlap: the default options ARE the pre-lint render, so without the
        // flag no render can gain a lint caveat (the opt-in rule of the ruling).
        RenderOptions options = lintOverlap ? new RenderOptions(true) : RenderOptions.DEFAULT;
        try {
            if (batch) {
                return runBatch(in, out, err, strict, backend, options, sourceHash);
            }
            // Every `$...$` source the backend could not typeset during THIS render; always empty
            // without --math (no renderer, so nothing is attempted and nothing is recorded).
            Set<String> untypeset = new LinkedHashSet<>();
            MathFragmentRenderer renderer = backend == null ? null : backend.recording(untypeset);

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
                byte[] rawBytes = readRawDslBytes(in);
                if (sourceHash) {
                    // THE BYTES READ, before the UTF-8 decode below: the decode replaces invalid
                    // sequences with U+FFFD, so a hash of the decoded string re-encoded would not
                    // be the input. Over the cap only a prefix was read, and a digest of a prefix
                    // would name an input nobody gave, so it says so instead.
                    if (rawBytes.length > DslParser.MAX_SOURCE_BYTES) {
                        err.println(CAVEAT_PREFIX + SOURCE_HASH_LABEL + " unavailable -- stdin is larger than the "
                            + DslParser.MAX_SOURCE_BYTES + "-byte source cap and was not read in full");
                    } else {
                        printSourceHash(sha256Hex(rawBytes), err, CAVEAT_PREFIX);
                    }
                }
                RenderResult rawResult = tryRenderWithDiagnostics(
                    new String(rawBytes, StandardCharsets.UTF_8), renderer, options);
                svg = rawDslSvgOrNull(rawResult, err);
                if (svg == null) {
                    return 1;
                }
                // CAVEATS ON STDIN (plan c880b12e). Until this line the stdin arm never read the caveat
                // detail, so `render - --strict` ignored EVERY caveat (dropped statements, pie label
                // drops, xychart label drops) while `render <file.md> --strict` failed on them: the same
                // flag meant different things on the two sources. Fixpoint's showcase audit (sirentide/1118)
                // hit it as an xychart losing 15 of 25 axis labels with exit 0. Both arms now share
                // {@link #reportCaveats}, so the equivalence is a property of the code, as with the refusal.
                strictFailed = reportCaveats(rawResult, untypeset, strict, err, CAVEAT_PREFIX);
            } else {

                String markdown;
                byte[] markdownBytes;
                try (InputStream fileIn = Files.newInputStream(Path.of(source))) {
                    byte[] bytes = fileIn.readNBytes(MAX_MARKDOWN_BYTES + 1);
                    if (bytes.length > MAX_MARKDOWN_BYTES) {
                        err.println("sirentide: cannot read '" + source + "': larger than the "
                            + MAX_MARKDOWN_BYTES + "-byte markdown cap");
                        return 2;
                    }
                    markdown = new String(bytes, StandardCharsets.UTF_8);
                    markdownBytes = bytes;
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
                if (sourceHash) {
                    String hex = fenceBodyHashOrNull(markdownBytes, markdown);
                    if (hex == null) {
                        err.println(CAVEAT_PREFIX + SOURCE_HASH_LABEL
                            + " unavailable -- the fence body could not be located in the file's bytes");
                    } else {
                        printSourceHash(hex, err, CAVEAT_PREFIX);
                    }
                }

                // Truthful render-check posture (review sirentide/471 B3): the /docs bake NEVER serves an
                // SVG for a fence that fails to render — SirentideDiagramConverter keeps the original
                // fence verbatim and prepends a visible caption. So a not-OK render here is a LOUD exit 1
                // with NOTHING written: writing the inert shell and exiting 0 would claim a bake outcome
                // /docs does not produce. The defensive catch mirrors the converter's tryRender
                // (RuntimeException + StackOverflowError -> degrade, never a crash).
                RenderResult result = tryRenderWithDiagnostics(fenceBody, renderer, options);
                if (result == null || result.diagnostics().outcome() != Outcome.OK || result.svg() == null) {
                    String reason = result == null ? "renderer failure" : result.diagnostics().message();
                    err.println("sirentide: diagram did not render — " + reason
                        + "; /docs would keep this fence verbatim with a visible caption (nothing written)");
                    return 1;
                }
                svg = result.svg();
                strictFailed = reportCaveats(result, untypeset, strict, err, CAVEAT_PREFIX);
            }

            // THE ONE WRITE TAIL, reached by both arms. Its ordering guarantee is the reason writePng
            // may assume the SVG is already on disk: see {@link #writePng}'s ORDER MATTERS note.
            int code = writeOutput(svg, outPath, out, err);
            if (code != 0) {
                return code;
            }

            // THE PNG IS STILL ATTEMPTED WHEN THE STRICT GATE HAS FAILED, for the same reason the SVG is
            // still written: you want to look at what your gate rejected. Its failure still prints to
            // stderr. PRECEDENCE GOVERNS THE NUMBER, NEVER THE REPORT.
            int pngCode = pngPath == null ? 0 : writePng(svg, pngPath, brewshotJar, err);

            // THE STRICT FAILURE WINS over a PNG failure [ruling: PROJECT/stafficy 25843, plan b07ea58c].
            // The principle generalises past this flag: A FAILURE WITH NO OBSERVABLE ARTIFACT MUST OUTRANK
            // A FAILURE WHOSE ABSENCE IS DIRECTLY OBSERVABLE. A missing PNG is one stat away; a swallowed
            // strict gate leaves nothing anywhere to test, and its only evidence went to stderr -- the
            // channel this project's own authoring guide calls invisible to a pipeline.
            //
            // WHAT THIS LINE REPAIRS: the strict return used to be guarded on `pngPath == null`, so
            // `--strict --png` on a dropping source printed "treating dropped statement(s) as a failure"
            // and then exited 0, indistinguishable from a clean render to any caller reading the code.
            // The flag whose entire purpose is to make a caveat fail a pipeline was silently disarmed by
            // an unrelated output flag. Neither flag was tested with the other: --strict only with -o,
            // --png only without --strict, so the intersection had no coverage at all.
            if (strictFailed) {
                return 1;
            }
            return pngCode;
        } finally {
            if (backend != null) {
                backend.close();
            }
        }
    }

    /// The source-slot spelling of batch mode: `render --batch`.
    static final String BATCH = "--batch";

    /// Environment variable naming the LatteX jar `--math` loads; `--lattex` overrides it. Read only
    /// when `--math` is given, so setting it once per shell never switches math on by itself.
    static final String LATTEX_JAR_ENV = "SIRENTIDE_LATTEX_JAR";

    /// The caveat-line prefix of the single-shot arms. `--batch` passes `sirentide: record N: ` so
    /// a caveat names the record it belongs to.
    private static final String CAVEAT_PREFIX = "sirentide: ";

    /// `render --batch`: render every NUL-separated source on `in`, writing one NUL-terminated record
    /// per source to `out`, in order and as each is produced (nothing is accumulated across records).
    ///
    /// ALIGNMENT IS THE CONTRACT. LatteX's batch skips blank records; this one does not, because a
    /// blank DSL is a legal (empty) diagram and skipping it would shift every later record onto the
    /// wrong source. A failure emits a `sirentide: error: ...` record IN ITS SLOT. The single exception
    /// is the empty tail after a final NUL, which is a terminator, not a source.
    ///
    /// EACH SVG RECORD IS THE SINGLE-SHOT BAKE: same renderer call and the same caveat seam
    /// ({@link #reportCaveats}), written with the same `PrintStream.print` as {@link #writeOutput}.
    ///
    /// OVERSIZED SOURCES. A source over {@link DslParser#MAX_SOURCE_BYTES} gets an error record and the
    /// batch CONTINUES: unlike LatteX (which stops, because finding the next record would mean reading
    /// past its cap into memory), the remainder of an oversized source is read and DISCARDED byte by
    /// byte up to its NUL, so memory stays bounded by one capped source and alignment survives.
    private static int runBatch(InputStream in, PrintStream out, PrintStream err, boolean strict,
                                LatteXBackend backend, RenderOptions options, boolean sourceHash) {
        InputStream src = new BufferedInputStream(in);
        int record = 0;
        boolean anyFailed = false;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // --source-hash only: the digest of an OVERSIZED record, which is fed the capped prefix once
        // and then every discarded byte, so the record is hashed in full while memory stays bounded.
        MessageDigest overflow = sourceHash ? sha256() : null;
        while (true) {
            buf.reset();
            boolean oversized = false;
            boolean sawAny = false;
            boolean terminated = false;
            try {
                int b;
                while ((b = src.read()) != -1) {
                    sawAny = true;
                    if (b == 0) {
                        terminated = true;
                        break;
                    }
                    if (buf.size() < DslParser.MAX_SOURCE_BYTES) {
                        buf.write(b);
                    } else {
                        if (overflow != null) {
                            if (!oversized) {
                                overflow.update(buf.toByteArray());
                            }
                            overflow.update((byte) b);
                        }
                        oversized = true;
                    }
                }
            } catch (IOException e) {
                err.println("sirentide: --batch: failed to read stdin after " + record + " record(s): "
                    + e.getMessage());
                return 2;
            }
            if (!sawAny && !terminated) {
                break; // EOF: the empty tail after the last NUL (or an empty stdin) is not a source
            }
            record++;
            String recordPrefix = "sirentide: record " + record + ": ";
            if (sourceHash) {
                printSourceHash(HexFormat.of().formatHex(
                    oversized ? overflow.digest() : sha256().digest(buf.toByteArray())), err, recordPrefix);
            }
            String body;
            if (oversized) {
                String reason = "source larger than the " + DslParser.MAX_SOURCE_BYTES + "-byte cap";
                err.println("sirentide: record " + record + ": diagram did not render — " + reason);
                body = "sirentide: error: " + reason;
                anyFailed = true;
            } else {
                String dsl = buf.toString(StandardCharsets.UTF_8);
                Set<String> untypeset = new LinkedHashSet<>();
                RenderResult result = tryRenderWithDiagnostics(dsl,
                    backend == null ? null : backend.recording(untypeset), options);
                if (result == null || result.diagnostics().outcome() != Outcome.OK || result.svg() == null) {
                    String reason = result == null ? "renderer failure" : result.diagnostics().message();
                    err.println("sirentide: record " + record + ": diagram did not render — " + reason);
                    body = "sirentide: error: " + reason;
                    anyFailed = true;
                } else {
                    body = result.svg();
                    if (reportCaveats(result, untypeset, strict, err, recordPrefix)) {
                        anyFailed = true;
                    }
                }
            }
            out.print(body);
            out.print('\0');
            out.flush();
            if (out.checkError()) {
                err.println("sirentide: error writing to stdout (record " + record + "; "
                    + (record - 1) + " complete record(s) before it)");
                return 2;
            }
        }
        if (record == 0) {
            err.println("sirentide: --batch got no sources on stdin");
            return 2;
        }
        return anyFailed ? 1 : 0;
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

    /// The M0 read-and-render shape of the legacy no-args path. Its READ ({@link #readRawDsl}) is
    /// shared byte-for-byte with the `render -` alias, which renders with its own `--math` renderer
    /// (null without the flag, i.e. this exact call). Bound to the parser's source cap (+1 to detect overflow): a runaway
    /// stdin degrades to the inert shell in `render()` rather than OOMing on `readAllBytes`.
    ///
    /// Renders through the DIAGNOSTICS api so the caller can refuse a non-render, rather than
    /// through the plain `render()` which cannot distinguish "baked a blank diagram" from "did not
    /// bake". See {@link #writeRawDslOrRefuse} for why.
    private static RenderResult renderRawDsl(InputStream in) throws IOException {
        return tryRenderWithDiagnostics(readRawDsl(in), null, RenderOptions.DEFAULT);
    }

    /// The bounded stdin read both raw-DSL arms share.
    private static String readRawDsl(InputStream in) throws IOException {
        return new String(readRawDslBytes(in), StandardCharsets.UTF_8);
    }

    /// The bytes of {@link #readRawDsl}, undecoded: at most the source cap + 1 (to detect overflow).
    private static byte[] readRawDslBytes(InputStream in) throws IOException {
        return in.readNBytes(DslParser.MAX_SOURCE_BYTES + 1);
    }

    /// The label of the `--source-hash` stderr line, after the arm's prefix.
    static final String SOURCE_HASH_LABEL = "source sha256:";

    /// `--source-hash` (ruling sirentide/1129): one STDERR line. Nothing is ever added to the SVG.
    private static void printSourceHash(String hex, PrintStream err, String prefix) {
        err.println(prefix + SOURCE_HASH_LABEL + hex);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /// The hash of the first captured fence body AS THE FILE HOLDS IT. {@link FenceExtractor} works on
    /// the decoded text, split on `\n`; the body is the same LINES cut out of the raw bytes split on
    /// 0x0A, joined with 0x0A. The line structure is the same in both: UTF-8 decoding maps each 0x0A
    /// byte to one `\n` and never produces `\n` from anything else (a malformed sequence becomes
    /// U+FFFD without consuming an ASCII byte). The newline counts are compared anyway, and a
    /// mismatch returns null (reported as unavailable) rather than a digest: a wrong one is worse
    /// than none.
    private static String fenceBodyHashOrNull(byte[] raw, String markdown) {
        int[] range = FenceExtractor.firstSirentideFenceLines(markdown);
        List<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\n') {
                starts.add(i + 1);
            }
        }
        long decodedNewlines = markdown.chars().filter(c -> c == '\n').count();
        if (range == null || decodedNewlines != starts.size() - 1) {
            return null;
        }
        int from = starts.get(range[0]);
        // End of the last body line, excluding its LF: the closer line's start minus one. An empty
        // body (closer right after the opener) is the empty input.
        int to = range[1] == range[0] ? from : starts.get(range[1]) - 1;
        MessageDigest d = sha256();
        d.update(raw, from, to - from);
        return HexFormat.of().formatHex(d.digest());
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

    /// THE SINGLE CAVEAT SEAM, shared by `render -`, `render <file.md>` (plan c880b12e) and every
    /// `--batch` record (plan d9f29911). Returns true when `--strict` turns a caveat into a failure.
    ///
    /// Two sources, reported separately so each line says what is actually missing:
    /// - the API's OK-with-caveat `detail` (dropped statements, pie and xychart label drops, font
    ///   coverage). With the default `prefix` its lines are byte-identical to what this seam printed
    ///   before `--math` existed.
    /// - `--math` only: `$...$` runs LatteX could not typeset, which Sirentide shows as raw source.
    ///   `untypeset` is always empty without `--math` (there is no renderer to fail), so the default
    ///   bake can never reach the second branch.
    ///
    /// AN `OK` RENDER CAN STILL HAVE LOST A LINE, and until this verb read the caveat it said nothing
    /// about it. The directive-shape rule DROPS an unknown directive-shaped statement and records a
    /// line-scoped caveat on an otherwise-OK render, precisely so a lost line is not lost silently —
    /// but the caveat lived only in the API. Through this CLI, which the authoring docs name as THE
    /// local check, the author saw exit 0, no output, and a diagram quietly missing their line. A
    /// caveat channel nothing reads is not a channel.
    ///
    /// Printed to stderr, and the exit stays 0: the render genuinely succeeded and /docs genuinely
    /// serves this SVG. Turning a dropped statement into a failure here would claim a bake outcome
    /// that does not happen, which is the same untruth the exit-1 arm exists to avoid — pointing the
    /// other way.
    ///
    /// --strict, ruled at sirentide/977: stderr is the right AUTHOR channel and the wrong CI channel,
    /// because CI is exactly where nobody reads stderr. A caveat that cannot gate anything in the one
    /// environment that runs unattended is recorded-but-unseeable one level up — the same distance
    /// this change closed at the API/render seam, reopened at the render/CI seam. Opt-in, so the
    /// default stays honest: a drop is not a failed bake. The SVG IS still written, unlike the exit-1
    /// arm — there the artifact would have been a lie about what /docs serves, here it is exactly
    /// what /docs serves and the caller wants to inspect what its gate rejected.
    private static boolean reportCaveats(RenderResult result, Set<String> untypeset, boolean strict,
                                         PrintStream err, String prefix) {
        boolean dropped = false;
        String caveat = result.diagnostics().detail();
        if (caveat != null && !caveat.isBlank()) {
            err.println(prefix + "rendered, with caveats — " + caveat);
            err.println("  the SVG is what /docs would embed; the named statement(s) are absent from it");
            dropped = true;
        }
        if (!untypeset.isEmpty()) {
            List<String> named = untypeset.stream().limit(MAX_UNTYPESET_REPORTED).map(l -> "$" + l + "$").toList();
            int more = untypeset.size() - named.size();
            err.println(prefix + "rendered, with caveats — " + untypeset.size()
                + " math run(s) did not typeset and are shown as raw source: " + String.join(", ", named)
                + (more > 0 ? " (and " + more + " more)" : ""));
            err.println("  --math: LatteX could not render the named LaTeX; fix it to typeset");
        }
        if ((dropped || !untypeset.isEmpty()) && strict) {
            err.println(dropped ? "  --strict: treating dropped statement(s) as a failure"
                : "  --strict: treating untypeset math as a failure");
            return true;
        }
        return false;
    }

    /// How many distinct untypeset `$...$` sources a caveat names before summarising the rest; bounds
    /// the stderr line on a label set full of malformed math (same discipline as the font caveat).
    private static final int MAX_UNTYPESET_REPORTED = 10;

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
    /// `math == null` with {@link RenderOptions#DEFAULT} is exactly the pre-flag call:
    /// {@link Sirentide#renderWithDiagnostics(String)} delegates to this overload with a null renderer
    /// and the default options, so the default bake is the same code path.
    private static RenderResult tryRenderWithDiagnostics(String dsl, MathFragmentRenderer math,
                                                         RenderOptions options) {
        try {
            return Sirentide.renderWithDiagnostics(dsl, math, options);
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
