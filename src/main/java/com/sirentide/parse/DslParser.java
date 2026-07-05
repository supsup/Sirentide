package com.sirentide.parse;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
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

    public static Diagram parse(String src) {
        if (src == null || src.isBlank()) {
            return new Empty();
        }
        String[] lines = src.strip().split("\\R");
        return switch (lines[0].strip()) {
            case "pie" -> new Pie(parseData(lines));
            case "xychart" -> new XyChart(parseData(lines));
            case "timeline" -> new Timeline(parseData(lines));
            default -> new Empty();
        };
    }

    /// Parses the shared `"label" : value` rows (used by both pie and xychart). A malformed row
    /// is skipped rather than failing the whole diagram (docs/DESIGN.md §6: never fail the bake).
    private static List<Slice> parseData(String[] lines) {
        List<Slice> data = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = unquote(line.substring(0, colon).strip());
            try {
                data.add(new Slice(label, Double.parseDouble(line.substring(colon + 1).strip())));
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
}
