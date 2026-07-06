package com.sirentide.ir;

import java.util.List;

/// A simple bar chart: labelled categories, each with a value. Layout is deterministic arithmetic
/// — value → bar height, category → evenly-spaced x position — no graph optimization. Reuses
/// {@link Slice} as the generic (label, value) datum.
public record XyChart(List<Slice> bars) implements Diagram {

    public XyChart {
        bars = List.copyOf(bars);
    }

    /// The largest bar value. Seeds the signed y-domain's top; NOT clamped to ≥ 0 here — the layout
    /// builds the domain as `[min(0, minValue), max(0, maxValue)]` so negatives descend below a zero
    /// baseline instead of silently blanking the chart.
    public double maxValue() {
        double m = Double.NEGATIVE_INFINITY;
        for (Slice b : bars) {
            m = Math.max(m, b.value());
        }
        return Double.isFinite(m) ? m : 0;
    }

    /// The smallest bar value (the signed y-domain's floor when negative). `0` for an empty chart.
    public double minValue() {
        double m = Double.POSITIVE_INFINITY;
        for (Slice b : bars) {
            m = Math.min(m, b.value());
        }
        return Double.isFinite(m) ? m : 0;
    }
}
