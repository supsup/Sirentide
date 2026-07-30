package com.sirentide.layout;

import java.util.ArrayList;
import java.util.List;

/// Vertical placement of a node's self-loop LABEL COLUMN — one baseline per labeled lane, solved
/// together (eye-pass finding, plan 64cf1bae; shared by the class/ER twins exactly like
/// {@link EdgeRouter}). Every one of a node's loop labels rides the SAME x column just past the
/// outermost lane leg, so the only thing left to decide is each label's y, and the decisions are
/// coupled: a label must clear the neighbour edges crossing ITS x-band, must stay in its loop's
/// order, and must not overprint the label above it.
///
/// WHY IT IS NOT ONE NUMBER. The retired shape ({@code SelfLoopFanShift}) returned a single scalar
/// dy and both twins added it to every labeled lane. That preserved order and pitch, but it also
/// moved labels that had nothing to avoid: the labels share an x ORIGIN, not an x EXTENT, so an
/// 8px label and a 120px label in the same column reach into completely different parts of the
/// canvas and a crossing edge can conflict with one and miss the other entirely. Under a whole-fan
/// shift the 8px label was dragged 18px off the leg it is supposed to ride purely because its
/// sibling was in trouble (Marlow sirentide/770). Corridor avoidance is therefore PER LABEL.
///
/// CONSTRAINT PRECEDENCE, hard to soft — the contract this solver implements:
///
///   1. CORRIDOR (hard). A label's occupied band `[baseline − ascent, baseline + descent]` must
///      never come within `clearance` of the y-interval any obstacle SWEEPS over that label's
///      x-band (obstacle = each non-loop edge segment, a bent route contributing both legs, and
///      each box rectangle). A label sitting on a crossing edge reads as a label OF that edge —
///      misattribution, worse than crowding, and invisible to every pure-disjointness receipt.
///   2. ORDER (hard). Baselines stay strictly monotone in lane order: the outermost lane's leg is
///      highest on the canvas (smallest y) and so is its label.
///   3. DISJOINTNESS (hard). Adjacent labels are separated by the upper label's DESCENT + the lower
///      label's ASCENT + `bandGap`, from each label's own measured metrics (math included).
///   4. LEG ALIGNMENT (soft). Each baseline sits as close as the three hard constraints allow to
///      its ideal — its own loop's top leg, optically centred. A label whose own corridor is clear
///      AND whose ideal survives its neighbours' FINAL positions sits EXACTLY on its leg.
///
/// The ideals handed in are the METRIC-FLOORED ones ({@code loopLabelBaselines}, degradation 2):
/// degradation 2 is solved first and its output is what degradation 1 is minimal WITH RESPECT TO.
/// On any sizing-grown box the floor is inert and the floored ideal IS the leg ideal, which is what
/// makes clause 4 above an exact statement about legs and not about a derived stack.
///
/// TWO PASSES, deterministic, no randomness anywhere:
///
///   PASS 1, FORWARD (leg order, outermost lane first). Each label takes the position NEAREST its
///     ideal that is (a) at or below its predecessor's disjointness floor, (b) at or below the
///     canvas-top ascent floor, and (c) outside every one of its forbidden baseline intervals. The
///     feasible set is `[floor, ∞)` minus finitely many OPEN intervals, so the nearest point is
///     either the clamped ideal or one of those interval boundaries — a finite, exhaustive
///     candidate scan. Both directions are considered; the smaller |deviation| wins, and a tie goes
///     to the LOWER position (further below the previous label), which is the tie-break that
///     preserves order rather than crowding it. There is always a feasible position (everything
///     below the last interval qualifies), so unlike the retired scalar solve there is no
///     "no shift works, drop the whole fan" branch.
///   PASS 2, BACKWARD RELAX (bottom label upward). Each label is re-placed nearest its ideal within
///     the slack its now-final neighbours leave — BOTH of them this time, the label below as well
///     as the label above — never entering a forbidden interval, and only accepted when it is
///     strictly closer to the ideal than where it already sits. This pass is what makes the
///     postcondition a computed fact instead of an argument about pass 1: after it, no label is
///     further from its ideal than order + disjointness + corridor force it to be. Against pass 1's
///     nearest-feasible choice it is a FIXPOINT (pass 2's feasible set is a SUBSET of pass 1's and
///     pass 1 already took the nearest point of the superset), and it is deliberately kept on the
///     live path so that a future change to pass 1 — another obstacle class, a wider clearance, a
///     different tie-break — cannot silently leave a label parked further from its leg than the
///     contract permits.
///
/// A CASCADE is permitted and is contract, not a bug: when a conflicted label's only escape is
/// DOWNWARD, constraint 3 carries that move to every label below it. What the contract forbids is
/// moving a label that neither a corridor nor a neighbour actually binds.
final class SelfLoopLabelColumn {

    private SelfLoopLabelColumn() {}

    /// The per-label canvas-top floor the emit passes enforce (baseline ≥ ascent + 2). The solver
    /// honours the same floor so a chosen baseline is never re-clamped downstream — a downstream
    /// clamp would compress the column and break the order this solver established.
    private static final double TOP_FLOOR = 2;
    private static final double EPS = 1e-6;

    /// One label of a node's column: its LANE (which fixes leg order — a higher lane index means a
    /// higher leg, i.e. a smaller y), its fixed x-band `[x0, x1]` (the column never moves
    /// horizontally), its own measured ascent/descent about the baseline, and its metric-floored
    /// IDEAL baseline.
    record LoopLabel(int lane, double x0, double x1, double asc, double desc, double ideal) {}

    /// The FINAL baseline of every label, in the order the labels were handed in. `segments` are
    /// non-loop edge segments `{x1,y1,x2,y2}`; `boxes` are box rectangles `{x,y,w,h}`; `clearance`
    /// is the minimum clear corridor between a label's occupied band and any obstacle crossing its
    /// x-band; `bandGap` is the disjointness gap between adjacent occupied bands. A column whose
    /// labels are all unconflicted comes back EXACTLY as it went in (bytes unchanged).
    static double[] place(List<LoopLabel> column, List<double[]> segments, List<double[]> boxes,
                          double clearance, double bandGap) {
        int m = column.size();
        double[] baseline = new double[m];
        int[] order = legOrder(column);
        List<List<double[]>> forbidden = new ArrayList<>();
        for (LoopLabel f : column) {
            forbidden.add(forbiddenBaselines(f, segments, boxes, clearance));
        }
        // PASS 1 — forward, in leg order.
        for (int k = 0; k < m; k++) {
            int i = order[k];
            LoopLabel cur = column.get(i);
            double floor = disjointnessFloor(column, order, baseline, k, bandGap);
            baseline[i] = nearest(cur.ideal(), floor, Double.POSITIVE_INFINITY, forbidden.get(i));
        }
        // PASS 2 — backward relax, bottom label upward.
        for (int k = m - 1; k >= 0; k--) {
            int i = order[k];
            LoopLabel cur = column.get(i);
            double floor = disjointnessFloor(column, order, baseline, k, bandGap);
            double ceiling = Double.POSITIVE_INFINITY;
            if (k < m - 1) {
                LoopLabel below = column.get(order[k + 1]);
                ceiling = baseline[order[k + 1]] - below.asc() - cur.desc() - bandGap;
            }
            if (ceiling < floor) {
                continue;   // no slack at all — pass 1's position is the only feasible one
            }
            double relaxed = nearest(cur.ideal(), floor, ceiling, forbidden.get(i));
            if (!Double.isNaN(relaxed)
                && Math.abs(relaxed - cur.ideal()) < Math.abs(baseline[i] - cur.ideal()) - EPS) {
                baseline[i] = relaxed;
            }
        }
        return baseline;
    }

    /// Label indices in LEG ORDER: descending lane, because lane k's top leg sits one attach step
    /// ABOVE lane k−1's. This is the same walk the metric floor uses, so the two halves of the
    /// contract are stated over the same sequence. Lanes are unique within a node, so the order is
    /// total and the whole solve is deterministic; the insertion sort is exact on the handful of
    /// lanes a node ever has.
    private static int[] legOrder(List<LoopLabel> column) {
        int m = column.size();
        int[] order = new int[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        for (int i = 1; i < m; i++) {
            int v = order[i];
            int j = i - 1;
            while (j >= 0 && column.get(order[j]).lane() < column.get(v).lane()) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
        return order;
    }

    /// The lowest baseline label `order[k]` may take: the canvas-top ascent floor, and — once there
    /// is a label above it — the previous label's descent + this label's ascent + the band gap.
    private static double disjointnessFloor(List<LoopLabel> column, int[] order, double[] baseline,
                                            int k, double bandGap) {
        LoopLabel cur = column.get(order[k]);
        double floor = cur.asc() + TOP_FLOOR;
        if (k > 0) {
            LoopLabel above = column.get(order[k - 1]);
            floor = Math.max(floor, baseline[order[k - 1]] + above.desc() + cur.asc() + bandGap);
        }
        return floor;
    }

    /// The point of `[lo, hi]` minus the OPEN forbidden intervals that is nearest `target`, or NaN
    /// when that set is empty. The feasible set is a finite union of closed intervals whose
    /// endpoints are `lo`, `hi`, and the forbidden boundaries, so scanning exactly those candidates
    /// is exhaustive. Ties (equal |deviation| either side) go to the LARGER baseline — further
    /// below the label above, never crowding it.
    private static double nearest(double target, double lo, double hi, List<double[]> forbidden) {
        List<Double> candidates = new ArrayList<>();
        candidates.add(Math.min(Math.max(target, lo), hi));
        candidates.add(lo);
        if (!Double.isInfinite(hi)) {
            candidates.add(hi);
        }
        double below = lo;
        for (double[] f : forbidden) {
            candidates.add(f[0]);
            candidates.add(f[1]);
            below = Math.max(below, f[1]);
        }
        candidates.add(below);   // always feasible when hi is unbounded: clear of every corridor
        double best = Double.NaN;
        for (double c : candidates) {
            if (c < lo - EPS || c > hi + EPS || blocked(c, forbidden)) {
                continue;
            }
            if (Double.isNaN(best)) {
                best = c;
                continue;
            }
            double dc = Math.abs(c - target);
            double db = Math.abs(best - target);
            if (dc < db - EPS || (Math.abs(dc - db) <= EPS && c > best)) {
                best = c;
            }
        }
        return best;
    }

    private static boolean blocked(double y, List<double[]> forbidden) {
        for (double[] f : forbidden) {
            if (y > f[0] + EPS && y < f[1] - EPS) {
                return true;
            }
        }
        return false;
    }

    /// The BASELINE positions this label may not take. For each obstacle crossing the label's
    /// x-band, the y-interval it sweeps over the crossing part, inflated by `clearance` and then by
    /// the label's own descent (above) and ascent (below) — a baseline inside the result is exactly
    /// a baseline whose occupied band comes within the clearance of that obstacle.
    private static List<double[]> forbiddenBaselines(LoopLabel f, List<double[]> segments,
                                                     List<double[]> boxes, double clearance) {
        List<double[]> out = new ArrayList<>();
        for (double[] s : segments) {
            double[] y = yIntervalOverBand(s, f.x0(), f.x1());
            if (y != null) {
                out.add(new double[] {y[0] - clearance - f.desc(), y[1] + clearance + f.asc()});
            }
        }
        for (double[] b : boxes) {
            if (b[0] < f.x1() - EPS && b[0] + b[2] > f.x0() + EPS) {
                out.add(new double[] {b[1] - clearance - f.desc(),
                    b[1] + b[3] + clearance + f.asc()});
            }
        }
        return out;
    }

    /// The y-interval a segment sweeps over the part of its x-span inside `[x0, x1]`, or null when
    /// the segment never enters the band. A vertical segment inside the band contributes its full
    /// y-span.
    private static double[] yIntervalOverBand(double[] s, double x0, double x1) {
        double lo = Math.max(Math.min(s[0], s[2]), x0);
        double hi = Math.min(Math.max(s[0], s[2]), x1);
        if (hi < lo - EPS) {
            return null;
        }
        double dx = s[2] - s[0];
        if (Math.abs(dx) < EPS) {
            return new double[] {Math.min(s[1], s[3]), Math.max(s[1], s[3])};
        }
        double ya = s[1] + (lo - s[0]) / dx * (s[3] - s[1]);
        double yb = s[1] + (hi - s[0]) / dx * (s[3] - s[1]);
        return new double[] {Math.min(ya, yb), Math.max(ya, yb)};
    }
}
