package com.sirentide.cli;

import java.util.ArrayList;
import java.util.List;

/// Pulls the first top-level ```` ```sirentide ```` fenced code block's body out of a markdown
/// document, for the `sirentide render <file.md>` verb.
///
/// **This is a clean-room, MINIMAL extractor — it is NOT the same code as the docs-bake
/// converter.** The real `/docs` pipeline (`SirentideDiagramConverter`, in the separate Stafficy
/// repo, invoked at sync time — see docs/DESIGN.md §"Stafficy `/docs` integration") runs a real
/// markdown parser (flexmark) and extracts fences as part of a full document walk. This class
/// exists so the CLI can do the same job with zero new dependencies, on a plain `String`. DIVERGENCE
/// RISK: a construct flexmark treats specially (fences inside a blockquote, four-space-indented
/// "fences" that are actually indented code per CommonMark, a fence closed by a longer backtick
/// run than it opened with, tilde fences) is NOT modeled here — this is a line-oriented scan of the
/// EXACT grammar documented below, nothing more. Treat a mismatch between this tool's answer and
/// what `/docs` actually bakes as a `SirentideDiagramConverter`-vs-here drift, not a bug in either
/// alone.
///
/// Supported grammar (deliberately narrow — pinned by the test beside this file):
/// - An opening line whose content, after stripping leading/trailing whitespace, is EXACTLY
///   ```` ```sirentide ```` (three backticks, the literal word `sirentide`, nothing else — no info
///   string suffix, no extra backticks).
/// - A closing line whose stripped content is EXACTLY ` ``` ` (three backticks alone).
/// - The fence body is every line strictly between those two, joined with `\n`, verbatim (not
///   stripped) — indentation and blank lines inside the fence are preserved so the extracted text
///   is byte-identical to what a real markdown fence would hand a renderer.
/// - Only the FIRST such fence is returned; anything after its closing line is ignored (plan
///   6eb098d6 slice A only needs the first fence in a file).
/// - An opening line with no matching closing line before EOF (an unclosed fence) is treated the
///   SAME as "no fence found" — `null` — rather than guessing where the body ends.
final class FenceExtractor {

    private FenceExtractor() {}

    private static final String FENCE_OPEN = "```sirentide";
    private static final String FENCE_CLOSE = "```";

    /// Returns the body of the first top-level ```` ```sirentide ```` fence in `markdown`, or
    /// `null` if none is found (including an unclosed fence — see class doc). `markdown` is never
    /// mutated or re-parsed by anything other than this straight line scan.
    static String extractFirstSirentideFence(String markdown) {
        if (markdown == null) {
            return null;
        }
        List<String> body = null; // non-null once we've seen the opening fence line
        for (String line : markdown.lines().toArray(String[]::new)) {
            if (body == null) {
                if (line.strip().equals(FENCE_OPEN)) {
                    body = new ArrayList<>();
                }
                continue;
            }
            if (line.strip().equals(FENCE_CLOSE)) {
                return String.join("\n", body);
            }
            body.add(line);
        }
        return null; // opening never found, or found but never closed
    }
}
