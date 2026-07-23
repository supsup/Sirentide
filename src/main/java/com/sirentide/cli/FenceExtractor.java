package com.sirentide.cli;

import java.util.ArrayList;
import java.util.List;

/// Pulls the first top-level ```` ```sirentide ```` fenced code block's body out of a markdown
/// document, for the `sirentide render <file.md>` verb.
///
/// **BAKE-PARITY CONTRACT (review sirentide/471 B2).** This class is a line-for-line behavioral
/// mirror of the fence-scanning state machine in Stafficy's `SirentideDiagramConverter` — the
/// pre-flexmark pass that captures ```` ```sirentide ```` fences at docs-sync time (Stafficy main
/// `37f1e8a47c011b7ad9c5a9405d27311155521cc8`, converter lines 81–149). The converter is itself a
/// plain line scanner (NOT a flexmark document walk), so exact parity is achievable and REQUIRED:
/// the whole point of the render-check verb is predicting what `/docs` will do with a fence.
/// `fenceTokenIfFenceLine` and `backtickFenceInfo` below are verbatim ports of the converter's
/// same-named helpers; the scan loop preserves its state transitions exactly. If the converter's
/// scanner changes, this class (and `FenceExtractorTest`, the cross-repository contract fixture
/// pinned to the commit above) must be re-synced — treat any mismatch as a drift BUG here, never
/// as acceptable local flavor.
///
/// Mirrored semantics (each pinned by `FenceExtractorTest`):
/// - Lines are split on `\n` only (`split("\n", -1)`, exactly like the converter) — a `\r` from a
///   CRLF file stays on the line, and therefore inside a captured body, byte-for-byte as the bake
///   would see it.
/// - An opener is any line whose stripped-leading text starts with ` ``` ` (three OR MORE
///   backticks — a four-backtick ```` ````sirentide ```` opener is captured, matching the
///   converter) and whose info-string (text after the backtick run, trimmed) is EXACTLY
///   `sirentide`. `~~~sirentide` is NOT a diagram — the converter passes tilde fences through
///   verbatim.
/// - Any other fence line (```` ```java ````, bare ```` ``` ````, `~~~`) opens a pass-over region
///   closed by the next line bearing the SAME fence token; a ```` ```sirentide ```` inside such a
///   region is ordinary fenced content and is NOT captured. Inside a bare-backtick outer fence, a
///   ```` ```sirentide ```` line bears the closing token and CLOSES the outer fence (consumed as
///   the closer, not an opener) — converter parity, deliberately not CommonMark.
/// - While capturing, ANY backtick fence line closes the capture (the converter checks the fence
///   token only, so ```` ```java ```` closes a sirentide fence); a `~~~` line is body.
/// - Body lines are joined with `\n`, verbatim (never stripped).
/// - Only the FIRST captured fence is returned (the converter renders every fence; this verb
///   checks one — plan 6eb098d6 slice A scopes the check to the first fence in the file).
/// - An opener with no closing line before EOF is `null` — the converter flushes an unclosed
///   capture verbatim (renders nothing), so "unclosed" and "no fence" are the same answer here.
final class FenceExtractor {

    private FenceExtractor() {}

    /// The info-string that marks a fence as a Sirentide diagram (exact match, post-trim) —
    /// mirrors `SirentideDiagramConverter.SIRENTIDE_INFO`.
    private static final String SIRENTIDE_INFO = "sirentide";

    /// Returns the body of the first ```` ```sirentide ```` fence the Stafficy bake would capture
    /// in `markdown`, or `null` if the bake would capture none (no fence, nested-only fences, or
    /// an unclosed fence — see class doc).
    static String extractFirstSirentideFence(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return null;
        }
        String[] lines = markdown.split("\n", -1);

        // A NON-sirentide fence we are passing over (```java, bare ```, ~~~) — converter parity.
        boolean inFence = false;
        String fenceMarker = null;

        // The top-level ```sirentide fence being captured.
        boolean capturing = false;
        List<String> captureBody = new ArrayList<>();

        for (String line : lines) {
            String fence = fenceTokenIfFenceLine(line);

            if (capturing) {
                // The sirentide fence opened with backticks, so any backtick fence line closes it.
                if ("```".equals(fence)) {
                    return String.join("\n", captureBody);
                }
                captureBody.add(line);
                continue;
            }

            if (inFence) {
                if (fence != null && fence.equals(fenceMarker)) {
                    inFence = false;
                    fenceMarker = null;
                }
                continue;
            }

            if (fence != null) {
                // A top-level backtick fence whose info-string is exactly `sirentide` is CAPTURED;
                // every other fence opens a verbatim pass-over region.
                if ("```".equals(fence) && SIRENTIDE_INFO.equals(backtickFenceInfo(line))) {
                    capturing = true;
                } else {
                    inFence = true;
                    fenceMarker = fence;
                }
            }
            // Ordinary non-fence line at top level: nothing to do.
        }
        // Opening never found, or found but never closed (the converter flushes an unclosed
        // capture verbatim — it renders nothing, so the render-check answer is "no fence").
        return null;
    }

    /// The info-string of a backtick fence opener — the text after the run of backticks, trimmed
    /// (e.g. `sirentide` for a ```` ```sirentide ```` line, `""` for a bare ```` ``` ````). Only
    /// called on a line already known to be a ``` fence. Verbatim port of
    /// `SirentideDiagramConverter#backtickFenceInfo`.
    private static String backtickFenceInfo(String line) {
        String t = line.stripLeading();
        int i = 0;
        while (i < t.length() && t.charAt(i) == '`') {
            i++;
        }
        return t.substring(i).trim();
    }

    /// Returns the fence token (`"```"` or `"~~~"`) if `line` opens/closes a fenced code block,
    /// else null. Verbatim port of `SirentideDiagramConverter#fenceTokenIfFenceLine`.
    private static String fenceTokenIfFenceLine(String line) {
        String t = line.stripLeading();
        if (t.startsWith("```")) {
            return "```";
        }
        if (t.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }
}
