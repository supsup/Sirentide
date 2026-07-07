package com.sirentide.parse;

import java.util.ArrayList;
import java.util.List;

/// Splits a label into alternating text and inline-math runs at unescaped `$…$` delimiters
/// (the moat feature, RFC sirentide/39). Pure and total — a label with no math returns exactly
/// one {@link Text} run equal to the input, so the common case is a single-element list and every
/// existing diagram is byte-identical when the feature is off.
///
/// Rules (mirroring the markdown-seam D-rule: loud, not silent):
/// - an UNESCAPED `$` opens a math run; the next UNESCAPED `$` closes it;
/// - `\$` is a literal dollar and is UNESCAPED into the surrounding {@link Text} run;
/// - an UNCLOSED `$` (no closing `$` before end of label) is NOT math — the `$` and the rest stay
///   literal text, so a stray dollar in prose never eats the tail;
/// - an EMPTY `$$` is NOT math — it stays the literal text `$$`.
public final class LabelRuns {

    private LabelRuns() {}

    /// One piece of a split label: literal {@link Text} or a {@link MathRun} carrying LaTeX source.
    public sealed interface Run permits Text, MathRun {}

    /// Literal text, already unescaped (`\$` → `$`).
    public record Text(String s) implements Run {}

    /// An inline-math run; `latex` is the source BETWEEN the delimiters (no `$`).
    public record MathRun(String latex) implements Run {}

    /// Split `label` into runs. Never returns an empty list for a non-empty label; a label with no
    /// unescaped `$` returns a single {@code Text(label)} (fast path, zero behaviour change).
    public static List<Run> split(String label) {
        List<Run> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        int n = label.length();
        while (i < n) {
            char c = label.charAt(i);
            if (c == '\\' && i + 1 < n && label.charAt(i + 1) == '$') {
                // \$ → literal dollar in the surrounding text run.
                text.append('$');
                i += 2;
                continue;
            }
            if (c == '$') {
                int close = indexOfUnescapedDollar(label, i + 1);
                if (close < 0 || close == i + 1) {
                    // Unclosed OR empty ($$): not math — keep the '$' literal and keep scanning
                    // from the next char so a later real pair can still open.
                    text.append('$');
                    i += 1;
                    continue;
                }
                if (text.length() > 0) {
                    runs.add(new Text(text.toString()));
                    text.setLength(0);
                }
                runs.add(new MathRun(label.substring(i + 1, close)));
                i = close + 1;
                continue;
            }
            text.append(c);
            i += 1;
        }
        if (text.length() > 0 || runs.isEmpty()) {
            runs.add(new Text(text.toString()));
        }
        return runs;
    }

    /// True iff `label` contains at least one well-formed `$…$` math run.
    public static boolean hasMath(String label) {
        for (Run r : split(label)) {
            if (r instanceof MathRun) {
                return true;
            }
        }
        return false;
    }

    /// Index of the next `$` at or after `from` that is not escaped by a preceding `\`, or -1.
    private static int indexOfUnescapedDollar(String s, int from) {
        for (int j = from; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\\') {
                j++;   // skip the escaped char
                continue;
            }
            if (c == '$') {
                return j;
            }
        }
        return -1;
    }
}
