package com.sirentide.parse;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Task;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import com.sirentide.contract.SirentideContract;
import com.sirentide.layout.AxisScale;
import java.util.ArrayList;
import java.util.List;

/// The Sirentide DSL parser (its own language, not a mermaid subset — docs/DESIGN.md §8). M0
/// recognizes the empty diagram and `pie`. Malformed input degrades to the empty diagram or
/// skips the bad row — it never throws (§6: never fail the bake).
///
/// Pie form:
/// ```
/// pie
///   "Reviews" : 40
///   "Builds"  : 30
/// ```
public final class DslParser {

    private DslParser() {}

    /// Input-trust caps (DESIGN §6/§7: malformed/oversized → inert, never throw, never OOM). Past
    /// a cap the parser degrades to an inert/empty diagram rather than allocating unboundedly.
    /// `MAX_SOURCE_BYTES` is also the read bound the CLI enforces on stdin.
    public static final int MAX_SOURCE_BYTES = 1_000_000;   // 1 MB of DSL source
    public static final int MAX_DATA_ROWS = 10_000;         // rows past this are dropped
    public static final int MAX_LABEL_LEN = 512;            // labels are truncated to this

    public static Diagram parse(String src) {
        if (src == null || src.isBlank()) {
            return new Empty();
        }
        // Oversized source degrades to inert (never parse a runaway input into millions of shapes).
        if (src.length() > MAX_SOURCE_BYTES) {
            return new Empty();
        }
        String[] lines = src.strip().split("\\R");
        // The header is a TYPE token plus optional whitespace-split MODIFIER tokens (e.g.
        // `pie legend`). Bare `pie`/`xychart`/… stay exactly as before (a lone type token, no
        // modifiers). Unknown/malformed modifiers are simply ignored — the diagram still bakes
        // (DESIGN §6: never fail the bake).
        String[] header = lines[0].strip().split("\\s+");
        String type = header[0];
        // The off-slice text colour (page-background labels). `color=<value>` overrides the default;
        // an unparseable/illegal colour falls back to the default (never fails the bake).
        String textColor = parseTextColor(header);
        return switch (type) {
            // pie/xychart values are magnitudes → plain numeric parse. `pie legend` (or the `pie
            // key` alias) opts into the left-side colour key; a bare `pie` is legend-off.
            case "pie" -> new Pie(parseData(lines, false), hasLegendModifier(header), textColor);
            case "xychart" -> new XyChart(parseData(lines, false), textColor);
            // timeline values are moments → date-aware parse (bare years stay numeric, ISO dates
            // map to epoch-day) so events place proportionally in time, not evenly by index.
            case "timeline" -> new Timeline(parseData(lines, true), textColor);
            case "gantt" -> parseGantt(lines, textColor);
            default -> new Empty();
        };
    }

    /// The off-slice text fill from an optional `color=<value>` header modifier. The value must
    /// match the SirentideContract COLOR grammar (`#rrggbb` | `currentColor` | `none`); an invalid
    /// or unparseable colour degrades to the default `currentColor` rather than failing the bake
    /// (DESIGN §6). Order-independent with `legend`/`key` — it is just another header token.
    private static String parseTextColor(String[] header) {
        for (int i = 1; i < header.length; i++) {
            String tok = header[i];
            if (tok.startsWith("color=")) {
                String value = tok.substring("color=".length());
                if (SirentideContract.isColor(value)) {
                    // Normalize so a 3-digit `color=#333` reaches the emitter as `#333333`.
                    return SirentideContract.normalizeColor(value);
                }
                // Illegal colour → stop looking and use the default (a typo never fails the bake).
                return "currentColor";
            }
        }
        return "currentColor";
    }

    /// True iff a pie header carries the `legend` modifier (alias `key`). Any other modifier token
    /// is ignored (degrade gracefully → plain pie), so a typo never fails the bake.
    private static boolean hasLegendModifier(String[] header) {
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals("legend") || header[i].equals("key")) {
                return true;
            }
        }
        return false;
    }

    /// Parses gantt rows: `"Task" : start-end` (two numbers on a shared time axis). A malformed
    /// row is skipped (never fails the bake).
    private static Diagram parseGantt(String[] lines, String textColor) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i < lines.length && tasks.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = cap(unquote(line.substring(0, colon).strip()));
            // Peel an OPTIONAL trailing `#hex` colour token off the END first, BEFORE the range
            // split — so `"Design" : 0-3 #4e79a7` gives range=`0-3` (the numeric dash still splits
            // cleanly) and colour=`#4e79a7`, and an ISO `2020-01-01 - 2020-06-01 #abcdef` keeps its
            // ` - ` delimiter intact. A non-colour trailing token is left on the range (palette).
            String[] pc = peelColor(line.substring(colon + 1).strip());
            String range = pc[0];
            String color = pc[1];
            // Split start from end. ISO date ranges use a ` - ` delimiter (so the dashes INSIDE a
            // `2020-01-01` date are not mistaken for the range separator — the old first-dash split
            // broke absolute dates); the bare-numeric `start-end` form still splits on its lone dash.
            String startTok;
            String endTok;
            int sep = range.indexOf(" - ");
            if (sep >= 0) {
                startTok = range.substring(0, sep).strip();
                endTok = range.substring(sep + 3).strip();
            } else {
                int dash = range.indexOf('-', 1);   // skip a leading sign; find the numeric separator
                if (dash <= 0) {
                    continue;
                }
                startTok = range.substring(0, dash).strip();
                endTok = range.substring(dash + 1).strip();
            }
            try {
                double start = AxisScale.parseDomainValue(startTok);
                double end = AxisScale.parseDomainValue(endTok);
                // Non-finite (NaN / Infinity, incl. Double.parseDouble("1e400") == Infinity, which
                // throws NO exception) must not reach layout/emit — skip the row (never fail bake).
                if (!Double.isFinite(start) || !Double.isFinite(end)) {
                    continue;
                }
                tasks.add(new Task(label, start, end, color));
            } catch (NumberFormatException e) {
                // Skip a malformed / unparseable-date row (loud-not-silent: dropped, never misplaced).
            }
        }
        return new Gantt(tasks, textColor);
    }

    /// Parses the shared `"label" : value` rows (used by both pie and xychart). A malformed row
    /// is skipped rather than failing the whole diagram (docs/DESIGN.md §6: never fail the bake).
    private static List<Slice> parseData(String[] lines, boolean dateAware) {
        List<Slice> data = new ArrayList<>();
        for (int i = 1; i < lines.length && data.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = cap(unquote(line.substring(0, colon).strip()));
            // A data value is a SINGLE token, optionally followed by a trailing colour token:
            // `"Reviews" : 40 #4e79a7` → value=`40`, colour=`#4e79a7`. Split off the first token as
            // the value; if a trailing token follows AND matches the COLOR grammar it becomes the
            // per-item fill (normalized to canonical `#rrggbb`). A trailing token that is NOT a colour
            // (a typo) is simply IGNORED — the value still parses → palette fallback, never fails bake.
            String[] tok = line.substring(colon + 1).strip().split("\\s+", 2);
            String valueTok = tok[0];
            String color = null;
            if (tok.length == 2) {
                String tail = tok[1].strip();
                if (SirentideContract.isHexColor(tail)) {   // per-item fill is hex-only (H1): currentColor/none fall through to palette
                    color = SirentideContract.normalizeColor(tail);
                }
            }
            try {
                // Date-aware rows (timeline) map ISO dates to epoch-day for proportional placement;
                // a bare year / plain number parses as its numeric value either way.
                double value = dateAware
                    ? AxisScale.parseDomainValue(valueTok)
                    : Double.parseDouble(valueTok);
                // Non-finite (NaN / Infinity, incl. "1e400" == Infinity which throws NO exception)
                // must not reach layout/emit — skip the row (never fail the bake).
                if (!Double.isFinite(value)) {
                    continue;
                }
                // An ISO-date token became an opaque epoch-day for placement; remember the original
                // so the timeline shows `2020-01-15`, not the epoch number `18276` (A2). A bare
                // year / plain number keeps a null valueLabel and formats numerically as before.
                String valueLabel = (dateAware && AxisScale.isDateToken(valueTok)) ? valueTok : null;
                data.add(new Slice(label, value, color, valueLabel));
            } catch (NumberFormatException e) {
                // Skip a malformed row.
            }
        }
        return data;
    }

    /// Peels an OPTIONAL trailing colour token off a stripped data value (the text after the `:`).
    /// Returns `{valuePart, colorOrNull}`: if the LAST whitespace-separated token matches the
    /// SirentideContract COLOR grammar it is removed and returned (normalized to canonical `#rrggbb`,
    /// so a 3-digit `#333` becomes `#333333` on the IR); the leading value part is returned stripped.
    /// If there is no trailing colour token (or the input is a single token), the value is returned
    /// unchanged with a `null` colour — an invalid/unparseable trailing token is NEVER treated as a
    /// colour (it stays on the value, whose numeric parse then rejects it → palette fallback, no throw).
    private static String[] peelColor(String tok) {
        int start = tok.length();
        while (start > 0 && !Character.isWhitespace(tok.charAt(start - 1))) {
            start--;
        }
        // start > 0 means there is at least one whitespace char before the final token.
        if (start > 0) {
            String tail = tok.substring(start);
            if (SirentideContract.isHexColor(tail)) {   // per-item fill is hex-only (H1): currentColor/none fall through to palette
                return new String[] {tok.substring(0, start).strip(), SirentideContract.normalizeColor(tail)};
            }
        }
        return new String[] {tok, null};
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /// Truncates an over-long label to the trust cap so a pathological label can't blow up the
    /// glyph run / output size (DESIGN §6/§7: bounded, inert degrade — never throw).
    private static String cap(String label) {
        return label.length() > MAX_LABEL_LEN ? label.substring(0, MAX_LABEL_LEN) : label;
    }
}
