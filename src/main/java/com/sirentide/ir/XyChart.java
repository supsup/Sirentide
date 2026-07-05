package com.sirentide.ir;

import java.util.List;

/// A simple bar chart: labelled categories, each with a value. Layout is deterministic arithmetic
/// — value → bar height, category → evenly-spaced x position — no graph optimization. Reuses
/// {@link Slice} as the generic (label, value) datum.
public record XyChart(List<Slice> bars) implements Diagram {

    public XyChart {
        bars = List.copyOf(bars);
    }

    /// The largest bar value (the y-axis top; the denominator for each bar's height fraction).
    public double maxValue() {
        double m = 0;
        for (Slice b : bars) {
            m = Math.max(m, b.value());
        }
        return m;
    }
}
