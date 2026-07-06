package com.sirentide.ir;

import java.util.List;

/// A simple bar chart: labelled categories, each with a value. Layout is deterministic arithmetic
/// — value → bar height, category → evenly-spaced x position — no graph optimization. Reuses
/// {@link Slice} as the generic (label, value) datum.
/// `textColor` fills the off-slice page-background text (category labels, y-axis tick labels, the
/// per-bar value labels). Defaults to `currentColor` so it inherits the host page's text colour
/// (legible on light AND dark); the DSL `color=` modifier overrides it.
public record XyChart(List<Slice> bars, String textColor) implements Diagram {

    public XyChart {
        bars = List.copyOf(bars);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps existing callers/tests
    /// that build an `XyChart` from just its bars unchanged.
    public XyChart(List<Slice> bars) {
        this(bars, "currentColor");
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
