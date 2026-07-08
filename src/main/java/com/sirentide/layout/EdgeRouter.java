package com.sirentide.layout;

import java.util.List;

/// Deterministic edge de-crossing for the grid layouts (class + ER). Relationship-aware placement
/// ({@link GridOrder}) makes most edges short, but a high-degree HUB can still force one straight edge
/// to skip over a third box (e.g. two of the hub's neighbours share a row with a non-neighbour between
/// them). This router resolves that residual: when the straight border-to-border segment would pass
/// through the INTERIOR of a third box, it finds a SINGLE detour waypoint that bends the edge around
/// the obstruction (a two-segment polyline), else it leaves the edge straight.
///
/// The waypoint is offset PERPENDICULAR to the edge from its midpoint, searched at increasing
/// magnitudes and both signs — the smallest offset that (a) stays inside the canvas margin
/// (containment: the bend never escapes) and (b) clears EVERY third box on both half-segments wins.
/// A horizontal hub-skip therefore bends down (or up) through the inter-row gap, cleanly around the
/// intervening box. If nothing clears within the cap the edge stays straight (an honest residual —
/// crossing REDUCTION, not a guaranteed minimization; DESIGN §7).
///
/// Deterministic: the search order (magnitude ascending, `+` sign before `-`) and the geometry depend
/// only on the inputs — no randomness — so the byte-identical-bake invariant (§6) holds.
final class EdgeRouter {

    private EdgeRouter() {}

    private static final double MARGIN = 24;     // matches the layouts' canvas margin (containment)
    private static final double STEP = 8;        // offset search granularity
    private static final double MAX_OFFSET = 140; // cap — beyond this, keep the edge straight
    private static final double INTERIOR_INSET = 1.0; // shrink a box to its interior for the cross test

    /// The straight border attach points of an edge and, when a detour is needed, its single bend
    /// waypoint (`hasBend`). When `!hasBend` the edge is the straight `(sx,sy)-(ex,ey)`.
    record Route(double sx, double sy, double ex, double ey, boolean hasBend, double wx, double wy) {}

    /// Computes the route for an edge whose border attach points are `(sx,sy)` (source) and `(ex,ey)`
    /// (target). `others` are the rectangles `[x,y,w,h]` of every box that is NEITHER endpoint — the
    /// boxes an edge must not cross. Returns a straight route when the direct segment is already clear
    /// or no in-canvas detour resolves it; otherwise a single-bend route around the obstruction.
    static Route route(double sx, double sy, double ex, double ey,
                       List<double[]> others, double canvasW, double canvasH) {
        if (!crossesAny(sx, sy, ex, ey, others)) {
            return new Route(sx, sy, ex, ey, false, 0, 0);
        }
        double dx = ex - sx;
        double dy = ey - sy;
        double len = Math.hypot(dx, dy);
        if (!Double.isFinite(len) || len < 1e-6) {
            return new Route(sx, sy, ex, ey, false, 0, 0);
        }
        double px = -dy / len;   // unit perpendicular
        double py = dx / len;
        double midX = (sx + ex) / 2;
        double midY = (sy + ey) / 2;
        for (double off = STEP; off <= MAX_OFFSET; off += STEP) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                double wx = midX + sign * off * px;
                double wy = midY + sign * off * py;
                if (wx < MARGIN || wx > canvasW - MARGIN || wy < MARGIN || wy > canvasH - MARGIN) {
                    continue;   // the bend would escape the canvas — reject (containment)
                }
                if (!crossesAny(sx, sy, wx, wy, others) && !crossesAny(wx, wy, ex, ey, others)) {
                    return new Route(sx, sy, ex, ey, true, wx, wy);
                }
            }
        }
        return new Route(sx, sy, ex, ey, false, 0, 0);   // no in-canvas detour — honest residual
    }

    /// True if the segment passes through the interior (rect shrunk by {@link #INTERIOR_INSET}) of any
    /// rectangle in `others`.
    private static boolean crossesAny(double x1, double y1, double x2, double y2, List<double[]> others) {
        for (double[] b : others) {
            if (segIntersectsRect(x1, y1, x2, y2,
                b[0] + INTERIOR_INSET, b[1] + INTERIOR_INSET,
                b[0] + b[2] - INTERIOR_INSET, b[1] + b[3] - INTERIOR_INSET)) {
                return true;
            }
        }
        return false;
    }

    /// Liang-Barsky segment-vs-axis-aligned-rect overlap test (inclusive). True if any portion of the
    /// segment lies within `[xmin,xmax] x [ymin,ymax]`.
    private static boolean segIntersectsRect(double x1, double y1, double x2, double y2,
                                             double xmin, double ymin, double xmax, double ymax) {
        if (xmax <= xmin || ymax <= ymin) {
            return false;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - xmin, xmax - x1, y1 - ymin, ymax - y1};
        double u1 = 0;
        double u2 = 1;
        for (int k = 0; k < 4; k++) {
            if (p[k] == 0) {
                if (q[k] < 0) {
                    return false;
                }
            } else {
                double t = q[k] / p[k];
                if (p[k] < 0) {
                    u1 = Math.max(u1, t);
                } else {
                    u2 = Math.min(u2, t);
                }
            }
        }
        return u1 <= u2;
    }
}
