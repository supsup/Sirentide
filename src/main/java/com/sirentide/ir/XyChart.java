package com.sirentide.ir;

import java.util.List;

/// A cartesian chart over labelled categories. Three render modes share the same axis/tick
/// machinery: `bars` (the default — value → bar height), `line` (a disc per point + connecting
/// segments), and `scatter` (discs only). Layout is deterministic arithmetic — no graph
/// optimization.
///
/// SHAPE. The single-series bar chart is the back-compatible core: `bars` (a {@link Slice} per
/// category — label, value, optional per-item colour) with `series == null`. That legacy shape
/// bakes BYTE-IDENTICALLY to before (the xychart golden is the proof) and every existing
/// `new XyChart(bars[, textColor])` caller keeps compiling via the convenience constructors.
/// MULTI-SERIES / line / scatter opt in by supplying `series`: a `double[]` PER CATEGORY holding
/// that category's per-series values (aligned with `bars` by index, which then carries only the
/// category LABELS). A row's array length is its present-series count — series `s` has a point at
/// category `i` iff `s < series.get(i).length` (a shorter row = trailing series ABSENT there, a
/// gap that BREAKS a line rather than being drawn as zero). `seriesNames` (nullable) labels the
/// legend (`Series 1..N` when null); `legend` opts into the left colour key (ignored for a single
/// series — nothing to key). `mode` is one of `bars`|`line`|`scatter`.
/// `textColor` fills the off-plot page-background text (category labels, y-axis tick labels, the
/// per-bar value labels, legend text). Defaults to `currentColor` so it inherits the host page's
/// text colour (legible on light AND dark); the DSL `color=` modifier overrides it.
public record XyChart(List<Slice> bars, List<double[]> series, List<String> seriesNames,
                      String mode, boolean legend, String textColor) implements Diagram {

    public XyChart {
        bars = List.copyOf(bars);
        // `series`/`seriesNames` stay NULLABLE — null `series` is the legacy single-series bar path
        // (byte-identical). When present, defensively copy the outer list (the `double[]`s are
        // treated as immutable by convention, like every other Slice-derived value in the IR).
        series = series == null ? null : List.copyOf(series);
        seriesNames = seriesNames == null ? null : List.copyOf(seriesNames);
        if (mode == null || mode.isBlank()) {
            mode = "bars";
        }
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps existing callers/tests
    /// that build an `XyChart` from just its bars unchanged.
    public XyChart(List<Slice> bars) {
        this(bars, "currentColor");
    }

    /// Legacy single-series BAR construction (no explicit series grid) — `series`/`seriesNames`
    /// null, `mode` bars, `legend` off. Keeps `new XyChart(bars, textColor)` compiling and its
    /// output byte-identical.
    public XyChart(List<Slice> bars, String textColor) {
        this(bars, null, null, "bars", false, textColor);
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
