package com.sirentide.parse;

import java.util.List;

/// The ONE shared label-validation seam (Marlow's contract, sirentide/667): every
/// label-bearing construct routes through here, so the behaviour cannot drift between
/// flowchart nodes and the other diagram types.
///
/// ## What this exists to prevent
///
/// `A[TRUE NEGATIVE<br/>safe to act on]` rendered the characters `<br/>` as VISIBLE TEXT
/// inside the node box. Exit code 0, empty stderr, well-formed SVG, non-degenerate viewBox,
/// every label string present — every automated check passed. Only a human looking at the
/// picture could tell. The entire cost of that defect was in DETECTION, which is why the fix
/// is a diagnostic rather than a feature.
///
/// ## Why this is structural rather than a character check
///
/// The obvious fix — reject any label containing `<` or `>` — is WRONG, and Marlow caught it
/// in my own proposal: ordinary literal comparisons like `x < y` must remain legal, and they
/// are common in real diagrams. A one-literal `<br/>` check is equally wrong from the other
/// side, because it passes `<br>`, `<b>`, `<span style=...>` and every other tag.
///
/// So the test is SHAPE: an angle bracket that opens something tag-like — an optional
/// solidus, an ASCII name, then attributes or a close. `x < y` has a space and a
/// non-name character after the bracket and is untouched.
///
/// ## Why math is exempt for free
///
/// {@link LabelRuns#split} already separates literal {@link LabelRuns.Text} from
/// {@link LabelRuns.MathRun}. Scanning ONLY Text runs means inline math — where `<` is
/// ordinary LaTeX and tag-shaped sequences may legitimately appear — is exempt
/// STRUCTURALLY, not by a special case that could rot out of sync.
public final class LabelMarkup {

    private LabelMarkup() {}

    /// Longest offending token echoed into a diagnostic. A hostile label must not be able to
    /// pump an unbounded string into an error message: the diagnostic is itself an output
    /// surface, and Marlow's contract calls for a BOUNDED, control-sanitized echo.
    static final int MAX_ECHO = 32;

    /// Validates every DISPLAY label in `diagram`, throwing on the first offending tag.
    ///
    /// This is the one policy call site Marlow's ruling asks for (sirentide/671): the seam sits
    /// after the parser has built the IR, so it knows which fields are display text and which
    /// are identifiers. {@link LabelSurfaces} decides that split; this method decides only what
    /// counts as markup.
    ///
    /// Throwing rather than returning a result is deliberate and reuses machinery that already
    /// exists. {@code Sirentide.render} catches RuntimeException and degrades to the byte-stable
    /// INERT SHELL, preserving the never-throw contract (DESIGN §6/§7), and
    /// {@code renderWithDiagnostics} classifies it as {@link com.sirentide.api.Outcome#PARSE_ERROR}
    /// at stage `parse` — provided the call happens BEFORE the stage advances past parse, which
    /// is why the call site sits immediately after {@code DslParser.parse}.
    ///
    /// @throws LabelMarkupException on the first display label containing tag-shaped markup
    public static void validate(com.sirentide.ir.Diagram diagram) {
        for (LabelSurfaces.Labeled labeled : LabelSurfaces.of(diagram)) {
            String tag = offendingTag(labeled.text());
            if (tag != null) {
                // The token arrives already bounded and control-sanitized from offendingTag, so
                // a hostile label cannot pump or escape the diagnostic it lands in.
                throw new LabelMarkupException(labeled.id(), tag);
            }
        }
    }

    /// The first markup-shaped tag in a literal run of `label`, or `null` when there is none.
    ///
    /// Returned already bounded and control-sanitized, so callers may place it directly into a
    /// diagnostic message without re-checking it.
    public static String offendingTag(String label) {
        if (label == null || label.indexOf('<') < 0) {
            return null;                 // fast path: no bracket at all, nothing to scan
        }
        for (LabelRuns.Run run : LabelRuns.split(label)) {
            if (!(run instanceof LabelRuns.Text text)) {
                continue;                // math runs are not markup surfaces
            }
            String found = scanLiteral(text.s());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /// Scans one literal run for a tag-shaped sequence: `<`, an optional `/`, an ASCII letter,
    /// then name characters, then anything up to the closing `>`.
    private static String scanLiteral(String s) {
        for (int i = s.indexOf('<'); i >= 0; i = s.indexOf('<', i + 1)) {
            int j = i + 1;
            if (j < s.length() && s.charAt(j) == '/') {
                j++;                     // closing tag
            }
            if (j >= s.length() || !isNameStart(s.charAt(j))) {
                continue;                // "x < y", "a <- b", "3 <5" — not tag-shaped
            }
            int close = s.indexOf('>', j);
            if (close < 0) {
                continue;                // an unclosed bracket is not a tag
            }
            return sanitize(s.substring(i, close + 1));
        }
        return null;
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /// Bounds the echo and strips control characters, so a hostile label cannot inject
    /// newlines or terminal escapes into a diagnostic that gets printed, logged or pasted.
    private static String sanitize(String tag) {
        StringBuilder out = new StringBuilder(Math.min(tag.length(), MAX_ECHO));
        for (int i = 0; i < tag.length() && out.length() < MAX_ECHO; i++) {
            char c = tag.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        if (tag.length() > MAX_ECHO) {
            out.append("...");
        }
        return out.toString();
    }
}
