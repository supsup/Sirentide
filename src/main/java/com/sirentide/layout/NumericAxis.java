package com.sirentide.layout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/// The continuous axis of an `xychart … numeric` chart (plan c880b12e, numeric-x slice): tick
/// SELECTION, tick THINNING and tick FORMATTING, kept apart from {@link AxisScale} so the category
/// chart's ticks (and every byte they bake) stay exactly as they were.
///
/// SELECTION. Ticks sit on integer multiples of a nice step `m x 10^e` with `m` in {1, 2, 5}. The
/// candidates are tried FINEST FIRST, starting at the first step that yields no more than
/// {@link #MAX_TICKS} ticks over the domain.
///
/// THINNING. A candidate is accepted only when it FITS: horizontally, adjacent ticks are at least
/// {@link #MIN_X_SPACING} px apart and every label, centred on its tick (and nudged inside the label
/// band at the two ends), clears its neighbour by {@link #LABEL_GAP} px; vertically, adjacent ticks
/// are at least {@link #MIN_Y_SPACING} px apart. The first fitting candidate wins, so the axis keeps
/// the finest step whose labels can all be drawn in full. Labels are never ellipsized and never
/// dropped by a fitting candidate: a tick that is not wanted is simply never chosen.
///
/// The ONE loss path: when not even a single-tick candidate fits (a label wider than the whole label
/// band), the coarsest candidate's ticks are placed and each label that cannot be drawn whole is left
/// off and returned in {@link Fit#dropped()}, which the xychart caveat reports. It takes a label
/// wider than the canvas, which no in-range numeric value produces at the default canvas; it exists
/// so the impossible case is loud rather than silent.
///
/// FORMATTING. A tick is `k x m x 10^e` with integer `k`, so its label is built EXACTLY in
/// {@link BigDecimal} and printed with {@link BigDecimal#toPlainString()} after stripping trailing
/// zeros: `0.0005`, never `5.0E-4`; `200000000000`, never `2.0E11`; `1000000000000000.2` without the
/// float dust a `double` sum would carry. Pure and deterministic.
final class NumericAxis {

    private NumericAxis() {}

    /// Minimum px between adjacent x ticks, so the axis never reads as a comb.
    static final double MIN_X_SPACING = 24;
    /// Minimum clear px between adjacent x tick labels.
    static final double LABEL_GAP = 8;
    /// Minimum px between adjacent y ticks.
    static final double MIN_Y_SPACING = 32;
    /// The finest candidate yields at most this many ticks (bounds the search and the work).
    static final int MAX_TICKS = 200;
    /// A step is never finer than this fraction of the domain's largest magnitude, so `k` (the
    /// multiple a tick sits on) stays far inside the range a double counts exactly.
    private static final double MIN_RELATIVE_STEP = 1e-14;
    private static final long[] MANTISSAS = {1, 2, 5};

    /// One tick: its value (for projection) and its exact plain-decimal label.
    record Tick(double value, String label) {}

    /// A tick placed on the axis: `px` is its pixel position along the axis; `labelLeft` and
    /// `labelWidth` are where its label is drawn horizontally (meaningful on the x axis); `labelled`
    /// is false only for a tick whose label was dropped (see the class note).
    record Placed(Tick tick, double px, double labelLeft, double labelWidth, boolean labelled) {}

    /// The chosen ticks in ascending value order, and the labels that could not be drawn (empty
    /// unless the one loss path above was taken).
    record Fit(List<Placed> placed, List<String> dropped) {
        Fit {
            placed = List.copyOf(placed);
            dropped = List.copyOf(dropped);
        }
    }

    /// The exact plain-decimal label of `k x mantissa x 10^exponent`, trailing zeros stripped, `0`
    /// for zero (never `-0`, never `0.000`). Never E-notation.
    static String format(long k, long mantissa, int exponent) {
        BigDecimal v = BigDecimal.valueOf(Math.multiplyExact(k, mantissa)).scaleByPowerOfTen(exponent);
        if (v.signum() == 0) {
            return "0";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    /// The domain `[lo, hi]` widened by `frac` of its span at each end, so an extreme point sits
    /// inside the plot rather than on its edge. A DEGENERATE domain (one value, or values that agree
    /// to one part in 10^12 of their magnitude, below any pixel the axis can show) has no span to
    /// take a fraction of: it is centred and widened by half its magnitude, or by 1 around zero.
    static double[] pad(double lo, double hi, double frac) {
        double span = hi - lo;
        double mag = Math.max(Math.abs(lo), Math.abs(hi));
        if (!(span > mag * 1e-12)) {
            double c = (lo + hi) / 2;
            double p = c != 0 ? 0.5 * Math.abs(c) : 1.0;
            return new double[] {c - p, c + p};
        }
        return new double[] {lo - frac * span, hi + frac * span};
    }

    /// Thinned ticks for a HORIZONTAL axis over `[lo, hi]` mapped onto `[px0, px1]`, with every
    /// label kept inside the band `[minLeft, maxRight]`. `width` measures a label.
    static Fit fitHorizontal(double lo, double hi, double px0, double px1, double minLeft, double maxRight,
                             ToDoubleFunction<String> width) {
        List<Tick> lastNonEmpty = null;
        for (Candidate c : candidates(lo, hi)) {
            List<Tick> ticks = c.ticks();
            if (ticks.isEmpty()) {
                break;
            }
            lastNonEmpty = ticks;
            List<Placed> placed = placeHorizontal(ticks, lo, hi, px0, px1, minLeft, maxRight, width);
            if (placed != null && spacedAtLeast(placed, MIN_X_SPACING)) {
                return new Fit(placed, List.of());
            }
            if (ticks.size() <= 1) {
                break;   // a single label that does not fit cannot be helped by a coarser step
            }
        }
        if (lastNonEmpty == null) {
            return new Fit(List.of(), List.of());
        }
        return greedyHorizontal(lastNonEmpty, lo, hi, px0, px1, minLeft, maxRight, width);
    }

    /// Thinned ticks for a VERTICAL axis over `[lo, hi]` mapped onto `[pxBottom, pxTop]`. Labels
    /// stack vertically, so only the spacing is tested (the caller sizes the margin to the widest).
    static Fit fitVertical(double lo, double hi, double pxBottom, double pxTop, double fontSize) {
        double minSpacing = Math.max(MIN_Y_SPACING, fontSize + 4);
        AxisScale scale = new AxisScale(lo, hi);
        List<Placed> last = null;
        for (Candidate c : candidates(lo, hi)) {
            List<Tick> ticks = c.ticks();
            if (ticks.isEmpty()) {
                break;
            }
            List<Placed> placed = new ArrayList<>(ticks.size());
            for (Tick t : ticks) {
                placed.add(new Placed(t, scale.project(t.value(), pxBottom, pxTop), 0, 0, true));
            }
            last = placed;
            if (spacedAtLeast(placed, minSpacing)) {
                return new Fit(placed, List.of());
            }
        }
        return new Fit(last == null ? List.of() : last, List.of());
    }

    private static boolean spacedAtLeast(List<Placed> placed, double min) {
        for (int i = 0; i + 1 < placed.size(); i++) {
            if (Math.abs(placed.get(i + 1).px() - placed.get(i).px()) < min) {
                return false;
            }
        }
        return true;
    }

    /// Every tick labelled, or null when some label cannot be drawn whole without crowding.
    private static List<Placed> placeHorizontal(List<Tick> ticks, double lo, double hi, double px0, double px1,
                                                double minLeft, double maxRight, ToDoubleFunction<String> width) {
        AxisScale scale = new AxisScale(lo, hi);
        List<Placed> out = new ArrayList<>(ticks.size());
        double prevRight = Double.NEGATIVE_INFINITY;
        for (Tick t : ticks) {
            double px = scale.project(t.value(), px0, px1);
            double w = width.applyAsDouble(t.label());
            if (w > maxRight - minLeft) {
                return null;
            }
            double left = Math.min(Math.max(px - w / 2, minLeft), maxRight - w);
            if (left < prevRight + LABEL_GAP) {
                return null;
            }
            out.add(new Placed(t, px, left, w, true));
            prevRight = left + w;
        }
        return out;
    }

    /// The loss path: every tick placed, a label drawn only where it fits whole and clear of the last
    /// drawn label, every other label named in `dropped`.
    private static Fit greedyHorizontal(List<Tick> ticks, double lo, double hi, double px0, double px1,
                                        double minLeft, double maxRight, ToDoubleFunction<String> width) {
        AxisScale scale = new AxisScale(lo, hi);
        List<Placed> out = new ArrayList<>(ticks.size());
        List<String> dropped = new ArrayList<>();
        double prevRight = Double.NEGATIVE_INFINITY;
        for (Tick t : ticks) {
            double px = scale.project(t.value(), px0, px1);
            double w = width.applyAsDouble(t.label());
            double left = Math.min(Math.max(px - w / 2, minLeft), maxRight - w);
            boolean fits = w <= maxRight - minLeft && left >= prevRight + LABEL_GAP;
            out.add(new Placed(t, px, left, w, fits));
            if (fits) {
                prevRight = left + w;
            } else {
                dropped.add(t.label());
            }
        }
        return new Fit(out, dropped);
    }

    /// One candidate step `mantissa x 10^exponent` over a domain.
    private record Candidate(double lo, double hi, long mantissa, int exponent) {
        List<Tick> ticks() {
            BigDecimal step = BigDecimal.valueOf(mantissa).scaleByPowerOfTen(exponent);
            double s = step.doubleValue();
            long kLo = (long) Math.ceil(lo / s - 1e-9);
            long kHi = (long) Math.floor(hi / s + 1e-9);
            List<Tick> out = new ArrayList<>();
            for (long k = kLo; k <= kHi && out.size() <= MAX_TICKS; k++) {
                double d = BigDecimal.valueOf(k).multiply(step).doubleValue();
                if (d < lo || d > hi) {
                    continue;   // the 1e-9 slack never admits a tick outside the domain
                }
                out.add(new Tick(d == 0 ? 0.0 : d, format(k, mantissa, exponent)));
            }
            return out;
        }
    }

    /// The candidate steps, finest first: 1, 2, 5 x 10^e for rising e, from the first step that gives
    /// at most {@link #MAX_TICKS} ticks (and is not finer than {@link #MIN_RELATIVE_STEP} of the
    /// magnitude). Bounded: 40 decades is far past any step at which the domain holds one tick.
    private static List<Candidate> candidates(double lo, double hi) {
        double span = hi - lo;
        double mag = Math.max(Math.abs(lo), Math.abs(hi));
        double raw = Math.max(span / MAX_TICKS, mag * MIN_RELATIVE_STEP);
        if (!(raw > 0) || !Double.isFinite(raw)) {
            raw = 1;
        }
        int e0 = (int) Math.floor(Math.log10(raw));
        List<Candidate> all = new ArrayList<>();
        for (int e = e0; e < e0 + 40; e++) {
            for (long m : MANTISSAS) {
                double step = m * Math.pow(10, e);
                if (step >= raw * (1 - 1e-12)) {
                    all.add(new Candidate(lo, hi, m, e));
                }
            }
        }
        return all;
    }
}
