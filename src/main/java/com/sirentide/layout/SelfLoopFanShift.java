package com.sirentide.layout;

import java.util.ArrayList;
import java.util.List;

/// Vertical corridor solver for a node's self-loop LABEL FAN (eye-pass finding, plan 64cf1bae;
/// shared by the class/ER twins exactly like {@link EdgeRouter}). A NEIGHBOUR edge can legally cross
/// the x-band where the fan rides — the reserved lane extent bounds BOXES, not edges — and a label
/// sitting on (or within a whisker of) that line reads as a label OF the neighbour edge:
/// misattribution, worse than crowding, and invisible to every pure-disjointness receipt (the g5
/// gallery shape passed all of them while the A→B edge threaded right between "refines itself" and
/// "delegates"). Non-overlap is not unambiguity.
///
/// The fan therefore shifts VERTICALLY AS A SET — ordering and line-pitch preserved, so the per-lane
/// x-stagger still associates each label with its lane — by the smallest |dy| such that EVERY label
/// keeps the caller's clearance from the y-interval of EVERY obstacle crossing its x-band (obstacle =
/// each non-loop edge segment, a bent route contributing both legs, and each box rectangle) while no
/// baseline rises past the canvas-top floor. The candidate scan (0 first, then every per-pair
/// boundary in (|dy|, dy) order) is exhaustive AND deterministic: the feasible set is the complement
/// of finitely many open intervals, so its minimal-|dy| point is either 0 or one of those boundaries.
/// When NO shift satisfies every constraint the fan drops BELOW every obstacle — the caller's growth
/// pass then grows the canvas under it — so a threaded fan is never silently accepted.
final class SelfLoopFanShift {

    private SelfLoopFanShift() {}

    /// The per-label canvas-top floor the emit passes enforce (baseline ≥ ascent + 2). The solver
    /// honours the same floor so a chosen shift is never re-clamped downstream — a downstream clamp
    /// would compress the fan and break the preserved ordering.
    private static final double TOP_FLOOR = 2;
    private static final double EPS = 1e-6;

    /// One label of the fan: its fixed x-band `[x0, x1]` (the staircase never moves horizontally),
    /// its ascent/descent about the baseline, and the UNSHIFTED baseline the layout would give it.
    record FanLabel(double x0, double x1, double asc, double desc, double baseline) {}

    /// The uniform vertical shift for the whole fan. `segments` are non-loop edge segments
    /// `{x1,y1,x2,y2}`; `boxes` are box rectangles `{x,y,w,h}`; `clearance` is the minimum clear
    /// corridor between a label's text box and any obstacle crossing its x-band. Returns 0 (bytes
    /// unchanged) whenever the untouched fan already clears every corridor.
    static double solve(List<FanLabel> fan, List<double[]> segments, List<double[]> boxes,
                        double clearance) {
        // Per-label obstacle y-intervals, pre-inflated by the clearance.
        List<List<double[]>> obstacles = new ArrayList<>();
        for (FanLabel f : fan) {
            List<double[]> obs = new ArrayList<>();
            for (double[] s : segments) {
                double[] y = yIntervalOverBand(s, f.x0(), f.x1());
                if (y != null) {
                    obs.add(new double[] {y[0] - clearance, y[1] + clearance});
                }
            }
            for (double[] b : boxes) {
                if (b[0] < f.x1() - EPS && b[0] + b[2] > f.x0() + EPS) {
                    obs.add(new double[] {b[1] - clearance, b[1] + b[3] + clearance});
                }
            }
            obstacles.add(obs);
        }
        if (feasible(fan, obstacles, 0)) {
            return 0;
        }
        List<Double> candidates = new ArrayList<>();
        for (int k = 0; k < fan.size(); k++) {
            FanLabel f = fan.get(k);
            for (double[] o : obstacles.get(k)) {
                candidates.add(o[0] - f.desc() - f.baseline());   // label bottom kisses corridor top
                candidates.add(o[1] + f.asc() - f.baseline());    // label top kisses corridor bottom
            }
        }
        candidates.sort((a, b) -> {
            int c = Double.compare(Math.abs(a), Math.abs(b));
            return c != 0 ? c : Double.compare(a, b);
        });
        for (double dy : candidates) {
            if (feasible(fan, obstacles, dy)) {
                return dy;
            }
        }
        // No shift satisfies everything: drop the whole fan BELOW every obstacle (never thread it).
        double dy = 0;
        for (int k = 0; k < fan.size(); k++) {
            FanLabel f = fan.get(k);
            for (double[] o : obstacles.get(k)) {
                dy = Math.max(dy, o[1] + f.asc() - f.baseline());
            }
        }
        return dy;
    }

    /// True when shifting every baseline by `dy` puts each label's text box entirely above or
    /// entirely below every one of ITS obstacle corridors, without any baseline rising past the
    /// canvas-top floor.
    private static boolean feasible(List<FanLabel> fan, List<List<double[]>> obstacles, double dy) {
        for (int k = 0; k < fan.size(); k++) {
            FanLabel f = fan.get(k);
            double b = f.baseline() + dy;
            if (b < f.asc() + TOP_FLOOR - EPS) {
                return false;
            }
            for (double[] o : obstacles.get(k)) {
                boolean above = b + f.desc() <= o[0] + EPS;
                boolean below = b - f.asc() >= o[1] - EPS;
                if (!above && !below) {
                    return false;
                }
            }
        }
        return true;
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
