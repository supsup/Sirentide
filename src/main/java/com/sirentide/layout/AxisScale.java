package com.sirentide.layout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/// A pure, SVG-free axis primitive: a data domain `[min, max]` and the arithmetic that maps a data
/// value onto a pixel span, plus "nice" (1-2-5) tick stops. This is the shared foundation the
/// three axis diagrams (timeline / gantt / xychart) project through, so an axis can no longer
/// silently misinform — the domain always carries BOTH ends (the missing `min` was the root of the
/// gantt invisible-sliver bug) and mapping is strictly proportional (the root of the timeline
/// even-by-index bug).
///
/// Pure arithmetic — no dependency, no SVG, deterministic (byte-identical bakes, docs/DESIGN.md §6).
/// Optional ISO-date domain support ({@link #parseDomainValue}) is JDK-only (`java.time`).
public final class AxisScale {

    private final double min;
    private final double max;

    /// A scale over the explicit domain `[min, max]`. If `min > max` the ends are swapped so the
    /// domain is always well-ordered; a degenerate `[x, x]` domain is allowed (projection collapses
    /// to the pixel midpoint — never a divide-by-zero).
    public AxisScale(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("AxisScale domain must be finite: [" + min + ", " + max + "]");
        }
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
    }

    /// A scale whose domain is the min AND max of the given values (both ends computed — the missing
    /// min was the invisible-sliver bug). Empty / all-non-finite input yields a `[0, 0]` domain.
    public static AxisScale of(double... values) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (Double.isFinite(v)) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        if (lo > hi) {   // no finite value seen
            return new AxisScale(0, 0);
        }
        return new AxisScale(lo, hi);
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /// The domain width (`max - min`); `0` for a degenerate `[x, x]` domain.
    public double span() {
        return max - min;
    }

    /// Proportional, min-normalized projection of a data `value` onto the pixel interval
    /// `[pixelStart, pixelEnd]`. `pixelStart` corresponds to `min`, `pixelEnd` to `max` — so passing
    /// `pixelStart > pixelEnd` (as an inverted y-axis does) is fine and expected. A degenerate
    /// domain projects everything to the pixel midpoint (no divide-by-zero).
    public double project(double value, double pixelStart, double pixelEnd) {
        double s = span();
        if (s == 0.0) {
            return (pixelStart + pixelEnd) / 2.0;
        }
        double t = (value - min) / s;
        return pixelStart + t * (pixelEnd - pixelStart);
    }

    /// "Nice" tick stops spanning the domain via the 1-2-5 rule, targeting ~5 intervals. Every stop
    /// lies within `[min, max]` (stops that would fall outside the data domain are dropped, so a
    /// caller drawing them against {@link #project} never places a tick off the plot). A degenerate
    /// domain yields the single stop `[min]`.
    public List<Double> ticks() {
        List<Double> out = new ArrayList<>();
        double s = span();
        if (s == 0.0) {
            out.add(min);
            return out;
        }
        double step = niceNum(s / 5.0, true);
        if (step <= 0 || !Double.isFinite(step)) {
            out.add(min);
            out.add(max);
            return out;
        }
        double start = Math.ceil(min / step) * step;
        // Iterate on an integer index to avoid floating-point drift accumulating across the range.
        for (int k = 0; k <= 1000; k++) {
            double tick = start + k * step;
            if (tick > max + step * 1e-9) {
                break;
            }
            // Snap away tiny -0.0 / floating dust so ticks format deterministically.
            out.add(Math.abs(tick) < step * 1e-9 ? 0.0 : tick);
        }
        if (out.isEmpty()) {
            out.add(min);
            out.add(max);
        }
        return out;
    }

    /// The 1-2-5 "nice number" helper. `round` picks the nearest nice value; otherwise the smallest
    /// nice value ≥ `x`.
    private static double niceNum(double x, boolean round) {
        if (x <= 0) {
            return 0;
        }
        double exp = Math.floor(Math.log10(x));
        double f = x / Math.pow(10, exp);
        double nf;
        if (round) {
            nf = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
        } else {
            nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        }
        return nf * Math.pow(10, exp);
    }

    /// Parse a domain value from a DSL token: a genuine ISO date (`YYYY-MM-DD` or `YYYY-MM`) maps to
    /// its epoch-day ({@link LocalDate#toEpochDay}) so a date axis projects proportionally in days;
    /// any other token parses as a plain number (so a bare year `2020` stays the number 2020, which
    /// keeps the value label human-readable AND keeps year-to-year gaps proportional). Throws
    /// {@link NumberFormatException} on an unparseable token so the caller can skip the row rather
    /// than silently misplace it (docs/DESIGN.md §6: loud, never silent).
    public static double parseDomainValue(String token) {
        if (token == null) {
            throw new NumberFormatException("null domain token");
        }
        String s = token.strip();
        boolean isDate = ISO_DATE.matcher(s).matches();
        boolean isYearMonth = ISO_YEAR_MONTH.matcher(s).matches();
        if (isDate || isYearMonth) {
            try {
                LocalDate d = isDate ? LocalDate.parse(s) : LocalDate.parse(s + "-01");
                return d.toEpochDay();
            } catch (RuntimeException e) {
                throw new NumberFormatException("unparseable ISO date: " + s);
            }
        }
        return Double.parseDouble(s);
    }

    private static final java.util.regex.Pattern ISO_DATE =
        java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final java.util.regex.Pattern ISO_YEAR_MONTH =
        java.util.regex.Pattern.compile("\\d{4}-\\d{2}");
}
