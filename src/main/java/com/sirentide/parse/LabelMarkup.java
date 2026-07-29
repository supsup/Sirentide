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
    /// ## The config caption is part of the input, not a separate concern
    ///
    /// `DiagramConfig.caption` is a visible annotation for EVERY diagram type, and
    /// `CaptionLayout.withCaption` turns it into glyph paths. The first version of this seam
    /// validated only the parsed `Diagram` and every entry point then applied the caption
    /// AFTERWARD, so `%% caption: unsafe&lt;br/&gt;` rendered with an OK diagnostic -- the
    /// original defect, on a surface the ruling named explicitly, reached through a field I
    /// simply never looked at. Marlow's discriminator at sirentide/676.
    ///
    /// So the unit of validation is the whole RENDER INPUT (diagram + config), not the IR
    /// alone. Taking both parameters is what stops a future visible field being added beside
    /// the diagram and silently bypassing the policy again.
    ///
    /// @throws LabelMarkupException on the first display label containing tag-shaped markup
    public static void validate(com.sirentide.ir.Diagram diagram,
            com.sirentide.ir.DiagramConfig config) {
        for (LabelSurfaces.Labeled labeled : LabelSurfaces.of(diagram)) {
            String tag = offendingTag(labeled.text(), labeled.mathAware());
            if (tag != null) {
                // The token arrives already bounded and control-sanitized from offendingTag, so
                // a hostile label cannot pump or escape the diagnostic it lands in.
                throw new LabelMarkupException(labeled.id(), tag);
            }
        }
        if (config != null) {
            // The caption is PLAIN: CaptionLayout renders it through FontMetrics glyph paths
            // with no math renderer, so `$…$` there is literal text, not a formula.
            String tag = offendingTag(config.caption(), false);
            if (tag != null) {
                // Stable identity "caption", per the ruling: it is a single named field, so
                // there is no index to carry and the name IS the location.
                throw new LabelMarkupException("caption", tag);
            }
        }
    }

    /// The first markup-shaped tag in a literal run of `label`, or `null` when there is none.
    ///
    /// Returned already bounded and control-sanitized, so callers may place it directly into a
    /// diagnostic message without re-checking it.
    /// PLAIN-surface scan: the whole authored string. Kept as the one-argument form because
    /// plain is the safe default and most surfaces are plain.
    public static String offendingTag(String label) {
        return offendingTag(label, false);
    }

    /// @param mathAware whether the SURFACE this label renders on actually interprets `$…$`.
    ///
    /// ## Why this parameter exists
    ///
    /// The first version skipped `MathRun`s unconditionally and I called that "structural
    /// rather than a special case". It was structural about the LABEL and blind about the
    /// SURFACE. On a plain surface `$…$` carries no math semantics — the dollars and everything
    /// between them are emitted as ordinary glyphs — so skipping that span just handed an
    /// attacker a delimiter. Marlow proved it three ways at sirentide/680; wrapping the tag in
    /// dollars restored OK/emit with a non-inert SVG on caption, GitGraph and mindmap.
    ///
    /// Math-aware surfaces still skip math runs, which keeps the inline-math positive control
    /// real rather than removing the feature to make the guard easy.
    public static String offendingTag(String label, boolean mathAware) {
        if (label == null || label.indexOf('<') < 0) {
            return null;                 // fast path: no bracket at all, nothing to scan
        }
        if (!mathAware) {
            // PLAIN surface: there are no math runs here, only text that happens to contain
            // dollar signs. Scan all of it.
            return scanLiteral(label);
        }
        for (LabelRuns.Run run : LabelRuns.split(label)) {
            if (!(run instanceof LabelRuns.Text text)) {
                continue;                // a real math run on a math-rendering surface
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
    /// Scans one literal run for a tag-shaped sequence.
    ///
    /// ## The name must END somewhere, and that is the whole fix
    ///
    /// The first version accepted `<`, an optional `/`, one ASCII letter, and then ANY later
    /// `>`. That is not tag grammar, it is bracket-pairing, and Marlow caught what it costs
    /// (sirentide/676): `0<x+y>1` was reported as the tag `<x+y>`. That is ordinary
    /// mathematical comparison prose, and rejecting it turns an under-detecting renderer into
    /// an over-rejecting one -- the exact trade the positive controls exist to prevent.
    ///
    /// My controls missed it because every one of them put a SPACE or a non-letter after the
    /// bracket (`x < y`, `a <- b`, `3<5`). None used a letter directly after `<` inside a
    /// comparison, which is the only shape that reaches the name branch at all.
    ///
    /// So the name is now consumed properly and must TERMINATE at a real tag boundary:
    /// whitespace, `/`, or `>`. `<x+y>` stops at `+`, which is not a boundary, so it is not a
    /// tag. `<br/>`, `<b>`, `<span class="x">` and `</b>` all terminate legally.
    private static String scanLiteral(String s) {
        for (int i = s.indexOf('<'); i >= 0; i = s.indexOf('<', i + 1)) {
            int j = i + 1;
            if (j < s.length() && s.charAt(j) == '/') {
                j++;                     // closing tag
            }
            if (j >= s.length() || !isNameStart(s.charAt(j))) {
                continue;                // "x < y", "a <- b", "3 <5" -- not tag-shaped
            }
            int nameEnd = j;
            while (nameEnd < s.length() && isNameChar(s.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd >= s.length() || !isTagBoundary(s.charAt(nameEnd))) {
                continue;                // "0<x+y>1" -- the name never terminates as a tag
            }
            int close = s.indexOf('>', nameEnd);
            if (close < 0) {
                continue;                // an unclosed bracket is not a tag
            }
            return sanitize(s.substring(i, close + 1));
        }
        return null;
    }

    /// Characters that may continue an element name once it has started. Deliberately narrow:
    /// letters, digits and hyphen cover HTML and custom-element names without admitting the
    /// operators that appear in comparison prose.
    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9') || c == '-';
    }

    /// What may legally FOLLOW a complete element name: attributes (whitespace), a
    /// self-closing solidus, or the tag close. Anything else -- `+`, `*`, `=`, a digit-run
    /// after an operator -- means the span was never a tag.
    private static boolean isTagBoundary(char c) {
        return Character.isWhitespace(c) || c == '/' || c == '>';
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
