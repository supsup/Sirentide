package com.sirentide.parse;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Task;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
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
        return switch (lines[0].strip()) {
            case "pie" -> new Pie(parseData(lines));
            case "xychart" -> new XyChart(parseData(lines));
            case "timeline" -> new Timeline(parseData(lines));
            case "gantt" -> parseGantt(lines);
            default -> new Empty();
        };
    }

    /// Parses gantt rows: `"Task" : start-end` (two numbers on a shared time axis). A malformed
    /// row is skipped (never fails the bake).
    private static Diagram parseGantt(String[] lines) {
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
            String range = line.substring(colon + 1).strip();
            int dash = range.indexOf('-');
            if (dash <= 0) {
                continue;
            }
            try {
                double start = Double.parseDouble(range.substring(0, dash).strip());
                double end = Double.parseDouble(range.substring(dash + 1).strip());
                // Non-finite (NaN / Infinity, incl. Double.parseDouble("1e400") == Infinity, which
                // throws NO exception) must not reach layout/emit — skip the row (never fail bake).
                if (!Double.isFinite(start) || !Double.isFinite(end)) {
                    continue;
                }
                tasks.add(new Task(label, start, end));
            } catch (NumberFormatException e) {
                // Skip a malformed row.
            }
        }
        return new Gantt(tasks);
    }

    /// Parses the shared `"label" : value` rows (used by both pie and xychart). A malformed row
    /// is skipped rather than failing the whole diagram (docs/DESIGN.md §6: never fail the bake).
    private static List<Slice> parseData(String[] lines) {
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
            try {
                double value = Double.parseDouble(line.substring(colon + 1).strip());
                // Non-finite (NaN / Infinity, incl. "1e400" == Infinity which throws NO exception)
                // must not reach layout/emit — skip the row (never fail the bake).
                if (!Double.isFinite(value)) {
                    continue;
                }
                data.add(new Slice(label, value));
            } catch (NumberFormatException e) {
                // Skip a malformed row.
            }
        }
        return data;
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
