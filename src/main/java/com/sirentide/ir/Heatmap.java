package com.sirentide.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// A continuous-score grid: rows × columns of 0..1 magnitudes, each cell filled from a SINGLE-HUE
/// sequential ramp (light→dark) in {@code HeatmapLayout} — the case × arm × score-intensity surface
/// the continuity-eval's run data needed and neither {@link Matrix} (discrete verdict cells) nor
/// {@link XyChart} (one magnitude axis) could draw (plan sirentide-heatmap-type). Grammar, caps,
/// rectangularization, and per-cell anchors deliberately mirror {@link Matrix}; the ONLY new
/// dimension is that a cell's value is continuous, so its fill is interpolated, not vocabulary-picked.
///
/// The grammar (own-DSL, matrix-parity):
/// {@snippet :
///   heatmap
///   cols: bare, snapshot, card
///   scale: "diverged" --> "reproduced"
///   "values-boundary" : 0.60, 0.75, 0.95
///   "technique" : -, 40%, warm:0.8
/// }
/// `cols:`/`columns:` names the M column headers; every following `"label" : v1, v2, …` is a row.
/// A cell token is a decimal (`0.6`, `.6`, `1`) or percent (`86%`) clamped to [0,1]; `text:value`
/// (split on the LAST colon, like matrix) shows {@code text} on the value's fill; blank/`-`/`na`/
/// non-numeric → NA (neutral fill, no colour, never throws). `scale:` optionally names the legend's
/// low/high ends via the quadrant `-->` axis-end grammar; absent ends default to "0" / "1".
/// A row is padded/truncated to exactly M cells so the grid is rectangular.
public record Heatmap(List<String> columns, List<Row> rows, String textColor,
                      String lowLabel, String highLabel) implements Diagram {

    public Heatmap {
        columns = snapshot(columns);
        rows = snapshot(rows);
    }

    /// One row: a left-aligned label plus exactly {@code columns.size()} value cells.
    public record Row(String label, List<Cell> cells) {

        public Row {
            cells = snapshot(cells);
        }
    }

    private static <T> List<T> snapshot(List<T> source) {
        return source == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(source));
    }

    /// One cell: the token as authored (shown centered), the clamped 0..1 magnitude driving its
    /// fill, and the NA flag (an NA cell's {@code value} is 0 by convention and never reaches the
    /// ramp — the flag, not the number, is the discriminator, so an authored literal `0` stays a
    /// real coldest-ramp value while `-` stays neutral).
    public record Cell(String text, double value, boolean na) {}
}
