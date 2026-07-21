package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.Knot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Pure knot-diagram layout (plan sirentide-knot-diagram-primitive): a classical knot drawn as ONE
/// closed smooth curve with transverse crossings, each UNDER strand BROKEN by a small gap so the
/// over/under reads unambiguously. Deterministic arithmetic, zero graph optimization
/// (docs/DESIGN.md §6) — a hard-coded LOOKUP, not a solver.
///
/// BUILT-INS: `unknot` (0 crossings) and `trefoil` (3 crossings) are textbook smooth parametrizations;
/// `figure8` (4₁) is a HAND-BUILT planar embedding (plan 5f48185e) — 4₁ is not a Lissajous knot, so no
/// smooth 3D projection realizes it, and its curve was found by searching harmonic families for a
/// reduced, alternating, interleaved 4-crossing realization (the Gauss-code-realization problem).
///
/// MODEL. Each built-in knot is a deterministic PARAMETRIC curve `(x(t), y(t))`, `t ∈ [0, 2π)`, whose
/// planar projection has a fixed set of double-point CROSSINGS (0 for the unknot, 3 for the trefoil).
/// A companion `z(t)` decides over/under at each crossing (the higher-z pass is OVER); the
/// crossing table below is the OFFLINE-COMPUTED result (crossing `(x,y)` in curve space + the two
/// `t`-parameters of its passes + which pass is under) — verified once, then frozen. The curve is
/// SAMPLED into a dense polyline; sampling STARTS at an OVER pass so every maximal run between gaps
/// (an "arc") contains exactly one over-crossing (holds for these ALTERNATING knots), and the closed
/// curve wraps cleanly. At each crossing's UNDER pass a `t`-window is SKIPPED, breaking the strand
/// into `crossings` separate arcs — the visible gap. So the emitted geometry is a set of open stroked
/// polyline {@link Path}s (fill `none`); a crossing's OVER pass passes THROUGH its centre (a vertex
/// within a hair of it), its UNDER pass leaves a gap around it. No `$…$` labels this slice.
///
/// EMIT + ANCHORS (plan sirentide-semantic-anchor-g). The knot shadow is a 4-valent graph: crossings
/// are its (implicit, un-anchored) nodes, and each maximal strand ARC between two under-gaps is an
/// {@link SirentideRole#EDGE}. Each arc is wrapped in ONE `<g role="edge">` (id = the crossing pair it
/// bridges, `overId-gapId`, uniquified), `seq` = the arc's traversal index — so a multi-crossing knot
/// (trefoil: 3 arcs) yields >1 play-through frame (SemanticAnchorTest). The unknot (0 crossings) is a
/// single un-broken loop in ONE edge group. The canvas grows to fit the curve's bounding box plus a
/// uniform {@link #MARGIN} (GeometryEscapeTest containment). Math coords are y-FLIPPED once here (SVG y
/// grows DOWN).
///
/// The GAUSS-CODE oracle ({@code KnotGaussCodeOracleTest}) reconstructs the code from THIS emitted
/// geometry — walking the arcs in emit order, reading each crossing's over/under from whether the
/// strand reaches its centre (over) or stops a gap short (under) — and asserts it equals the knot's
/// KNOWN code. That is the non-circular semantic discriminator: flip one crossing's under pass and the
/// reconstructed code (and the alternating property) breaks.
public final class KnotDiagramLayout {

    private KnotDiagramLayout() {}

    /// Curve-space → pixel scale, uniform page margin, strand stroke, and the sampling density.
    private static final double SCALE = 36;
    private static final double MARGIN = 30;
    private static final String STRAND_STROKE = "#1f2937";
    private static final double STRAND_WIDTH = 5;
    /// Samples across the full `2π` period (dense ⇒ smooth, and the closest sample to a crossing
    /// centre lands within ~1px so the over-pass reads as passing THROUGH it).
    private static final int SAMPLES = 360;
    /// Default half-width (in `t`) of the SKIP window at an under crossing — the visible strand break.
    /// Per-spec (a knot whose crossings sit closer in `t` needs a narrower window so a gap never eats a
    /// neighbouring crossing's OVER pass); {@link Spec#gapT} carries the value.
    private static final double GAP_T = 0.17;
    /// figure-8: two crossings sit ~0.126 apart in `t` (X0's under vs X3's over), so the window must be
    /// narrower than the trefoil's or a gap would blank a neighbour's over pass.
    private static final double FIGURE8_GAP_T = 0.09;

    // ---- the frozen crossing table (offline-computed from z(t); see class javadoc) ---------------
    // Each row: {crossingId, underT}. The crossing id order + labels are chosen so the emit-order
    // traversal reconstructs the canonical Gauss code (trefoil O1U2O3U1O2U3). `underT` is the parameter
    // of that crossing's UNDER pass (where the gap goes). SAMPLING_START is an OVER pass, so every arc
    // between two under-gaps holds exactly one over-crossing.
    //
    /// Trefoil: x = sin t + 2 sin 2t, y = cos t − 2 cos 2t, z = −sin 3t. Under passes (curve space):
    /// c1 @ (0,1.5) t≈4.460, c2 @ (−1.299,−0.75) t≈2.365, c3 @ (1.299,−0.75) t≈0.271. Sampling starts
    /// at c1's OVER pass (t≈1.823) so the emit-order traversal reads O1 U2 O3 U1 O2 U3.
    private static final double TREFOIL_START = 1.8230;
    private static final int[] TREFOIL_IDS = {1, 2, 3};
    private static final double[] TREFOIL_UNDER_T = {4.4601, 2.3649, 0.2712};

    /// Figure-eight (4₁): x = sin t − 1.5 sin 2t + 1.5 sin 3t, y = cos t + 2 cos 2t − 0.5 cos 3t — a
    /// hand-built REDUCED ALTERNATING INTERLEAVED 4-crossing embedding (plan 5f48185e; 4₁ is not
    /// Lissajous, so this was searched, not projected). Traversal (from START, an over pass) visits the
    /// four crossings in the order that reconstructs O1 U4 O2 U1 O3 U2 O4 U3. Under passes (curve space,
    /// = the double points): id1 t≈3.079, id2 t≈4.580, id3 t≈5.621, id4 t≈0.887. Crossings sit ~0.126
    /// apart in `t` at the top, so this spec uses the narrower {@link #FIGURE8_GAP_T}.
    private static final double FIGURE8_START = 0.6125;
    private static final int[] FIGURE8_IDS = {1, 2, 3, 4};
    private static final double[] FIGURE8_UNDER_T = {3.0788, 4.5801, 5.6206, 0.8868};

    public static LaidOut layout(Knot knot) {
        return layout(knot, null);
    }

    /// Inline-math entry (parity with every other layout): a knot carries no text, so the `math`
    /// argument is accepted and ignored — byte-identical to {@link #layout(Knot)}.
    public static LaidOut layout(Knot knot, MathFragmentRenderer math) {
        String type = knot.type() == null ? "" : knot.type().toLowerCase(Locale.ROOT);
        Spec spec = specOf(type);
        if (spec == null) {
            // Defensive: the parser degrades an unknown type to Empty, so this is unreachable for a
            // real Knot — but never throw (DESIGN §6): a bare MARGIN-padded canvas.
            return LaidOut.of(2 * MARGIN, 2 * MARGIN);
        }

        // 1. Sample the parametric curve over one period, starting at an OVER pass.
        double[][] pts = new double[SAMPLES][2];   // curve-space (x,y)
        double[] ts = new double[SAMPLES];
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < SAMPLES; i++) {
            double t = spec.start + (2 * Math.PI * i) / SAMPLES;
            ts[i] = t;
            double x = spec.x(t);
            double y = spec.y(t);
            pts[i][0] = x;
            pts[i][1] = y;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        // Pad the bounds by the stroke half-width (in curve units) so a round cap never overhangs.
        double halfStroke = (STRAND_WIDTH / 2) / SCALE;
        minX -= halfStroke;
        maxX += halfStroke;
        minY -= halfStroke;
        maxY += halfStroke;

        double canvasW = 2 * MARGIN + (maxX - minX) * SCALE;
        double canvasH = 2 * MARGIN + (maxY - minY) * SCALE;
        final double fMinX = minX;
        final double fMaxY = maxY;   // y-FLIP: higher curve-y → higher on screen

        AnchorAssigner assigner = new AnchorAssigner();

        // 2. Unknot: no crossings → one un-broken closed loop in a single EDGE group.
        if (spec.underT.length == 0) {
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < SAMPLES; i++) {
                double px = MARGIN + (pts[i][0] - fMinX) * SCALE;
                double py = MARGIN + (fMaxY - pts[i][1]) * SCALE;
                d.append(i == 0 ? "M " : "L ").append(fmt(px)).append(' ').append(fmt(py)).append(' ');
            }
            d.append('Z');
            Path loop = new Path(d.toString(), "none", STRAND_STROKE, STRAND_WIDTH, false);
            Group g = new Group(assigner.assign(SirentideRole.EDGE, "loop"), List.of(loop));
            return new LaidOut(canvasW, canvasH, List.of(g));
        }

        // 3. Crossing knots: mark the SKIP (gap) samples — those within GAP_T of any under pass — and
        //    split the ring into maximal runs (arcs) at the gaps. Sampling started at an over pass, so
        //    the run wrapping through index 0 is one arc (last run + first run joined).
        boolean[] gap = new boolean[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            for (double u : spec.underT) {
                double d = Math.abs(wrapPi(ts[i] - u));
                if (d < spec.gapT()) {
                    gap[i] = true;
                    break;
                }
            }
        }
        // Collect index-runs of consecutive non-gap samples.
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            if (gap[i]) {
                if (!cur.isEmpty()) {
                    runs.add(cur);
                    cur = new ArrayList<>();
                }
            } else {
                cur.add(i);
            }
        }
        if (!cur.isEmpty()) {
            runs.add(cur);
        }
        // Join the wrap: index 0 is an over pass (never a gap), so the first and last runs are the two
        // halves of ONE arc — prepend the last run to the first (its samples come earlier in t).
        if (runs.size() > 1 && !gap[0] && !gap[SAMPLES - 1]) {
            List<Integer> last = runs.remove(runs.size() - 1);
            List<Integer> first = runs.get(0);
            last.addAll(first);
            runs.set(0, last);
        }

        // Crossing centres in pixels (for the arc-id derivation + the oracle accessor).
        double[][] cross = crossPx(spec, fMinX, fMaxY);

        // 4. Emit one EDGE anchor group per arc, in traversal (emit) order. Each arc bridges its
        //    over-crossing (the one it passes through) to its END-gap crossing (the gap it stops at).
        List<Shape> shapes = new ArrayList<>();
        for (List<Integer> run : runs) {
            StringBuilder d = new StringBuilder();
            for (int k = 0; k < run.size(); k++) {
                int i = run.get(k);
                double px = MARGIN + (pts[i][0] - fMinX) * SCALE;
                double py = MARGIN + (fMaxY - pts[i][1]) * SCALE;
                d.append(k == 0 ? "M " : "L ").append(fmt(px)).append(' ').append(fmt(py)).append(' ');
            }
            Path arc = new Path(d.toString().trim(), "none", STRAND_STROKE, STRAND_WIDTH, false);
            String id = arcId(run, pts, ts, spec, cross, fMinX, fMaxY);
            shapes.add(new Group(assigner.assign(SirentideRole.EDGE, id), List.of(arc)));
        }
        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// A crossing centre in PIXELS: `{id, x, y}` — the geometry-space location of every crossing, used
    /// by the oracle to match arc endpoints/interiors (over vs under) against a known point. Deliberately
    /// carries NO over/under (that is what the oracle recovers from the gap geometry). Empty for the
    /// unknot / an unknown type. Deterministic (recomputes the same bounds transform the layout uses).
    public static List<double[]> crossingCentresPx(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        Spec spec = specOf(t);
        if (spec == null || spec.underT.length == 0) {
            return List.of();
        }
        double[] b = boundsOf(spec);
        double[][] c = crossPx(spec, b[0], b[3]);
        List<double[]> out = new ArrayList<>();
        for (double[] row : c) {
            out.add(new double[] {row[0], row[1], row[2]});
        }
        return out;
    }

    // ---- internals -------------------------------------------------------------------------------

    /// The bounds transform inputs `{minX, maxX, minY, maxY}` (stroke-padded), recomputed exactly as
    /// {@link #layout} does so a caller of {@link #crossingCentresPx} gets pixel-identical centres.
    private static double[] boundsOf(Spec spec) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < SAMPLES; i++) {
            double t = spec.start + (2 * Math.PI * i) / SAMPLES;
            double x = spec.x(t);
            double y = spec.y(t);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        double h = (STRAND_WIDTH / 2) / SCALE;
        return new double[] {minX - h, maxX + h, minY - h, maxY + h};
    }

    /// Crossing centres in pixels: `{id, px, py}` per crossing, from the crossing's curve-space `(x,y)`
    /// (evaluated at its under-pass `t`, which is the exact double point) through the layout transform.
    private static double[][] crossPx(Spec spec, double minX, double maxY) {
        double[][] out = new double[spec.underT.length][3];
        for (int c = 0; c < spec.underT.length; c++) {
            double t = spec.underT[c];
            out[c][0] = spec.ids[c];
            out[c][1] = MARGIN + (spec.x(t) - minX) * SCALE;
            out[c][2] = MARGIN + (maxY - spec.y(t)) * SCALE;
        }
        return out;
    }

    /// The anchor id for an arc: `<overId>-<endGapId>` — the crossing it passes OVER and the crossing
    /// its far end gaps AROUND. Derived from geometry (nearest crossing to an interior vs the last
    /// sample), so it reads like the tensor bond `left-right` id. Falls back to `arc` if degenerate.
    private static String arcId(List<Integer> run, double[][] pts, double[] ts, Spec spec,
                                double[][] cross, double minX, double maxY) {
        // over crossing = the one whose centre is closest to any interior sample of this arc.
        int overId = nearestCrossId(run, pts, cross, minX, maxY, true);
        // end-gap crossing = the one closest to the arc's LAST sample (the gap it stops short of).
        int lastI = run.get(run.size() - 1);
        double lx = MARGIN + (pts[lastI][0] - minX) * SCALE;
        double ly = MARGIN + (maxY - pts[lastI][1]) * SCALE;
        int gapId = nearestCrossIdToPoint(lx, ly, cross);
        if (overId < 0 || gapId < 0) {
            return "arc";
        }
        return overId + "-" + gapId;
    }

    private static int nearestCrossId(List<Integer> run, double[][] pts, double[][] cross,
                                      double minX, double maxY, boolean interior) {
        double best = Double.POSITIVE_INFINITY;
        int bestId = -1;
        int from = interior ? 1 : 0;
        int to = interior ? run.size() - 1 : run.size();
        for (int k = Math.max(from, 0); k < Math.max(to, 0); k++) {
            int i = run.get(k);
            double px = MARGIN + (pts[i][0] - minX) * SCALE;
            double py = MARGIN + (maxY - pts[i][1]) * SCALE;
            for (double[] c : cross) {
                double d = Math.hypot(px - c[1], py - c[2]);
                if (d < best) {
                    best = d;
                    bestId = (int) c[0];
                }
            }
        }
        return bestId;
    }

    private static int nearestCrossIdToPoint(double px, double py, double[][] cross) {
        double best = Double.POSITIVE_INFINITY;
        int bestId = -1;
        for (double[] c : cross) {
            double d = Math.hypot(px - c[1], py - c[2]);
            if (d < best) {
                best = d;
                bestId = (int) c[0];
            }
        }
        return bestId;
    }

    /// Wrap an angular difference into `(-π, π]` — so proximity to an under `t` is measured cyclically.
    private static double wrapPi(double d) {
        return ((d + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI;
    }

    private static Spec specOf(String type) {
        return switch (type) {
            case Knot.UNKNOT -> new Spec(Kind.UNKNOT, 0, new int[0], new double[0], GAP_T);
            case Knot.TREFOIL -> new Spec(Kind.TREFOIL, TREFOIL_START, TREFOIL_IDS, TREFOIL_UNDER_T, GAP_T);
            case Knot.FIGURE8 -> new Spec(Kind.FIGURE8, FIGURE8_START, FIGURE8_IDS, FIGURE8_UNDER_T,
                FIGURE8_GAP_T);
            default -> null;
        };
    }

    private enum Kind { UNKNOT, TREFOIL, FIGURE8 }

    /// A frozen knot spec: its parametric curve (via {@link #x}/{@link #y}), sampling start (an over
    /// pass), crossing ids, each crossing's under-pass `t`, and the gap half-width (in `t`).
    private record Spec(Kind kind, double start, int[] ids, double[] underT, double gapT) {
        double x(double t) {
            return switch (kind) {
                case UNKNOT -> Math.cos(t);
                case TREFOIL -> Math.sin(t) + 2 * Math.sin(2 * t);
                case FIGURE8 -> Math.sin(t) - 1.5 * Math.sin(2 * t) + 1.5 * Math.sin(3 * t);
            };
        }

        double y(double t) {
            return switch (kind) {
                case UNKNOT -> Math.sin(t);
                case TREFOIL -> Math.cos(t) - 2 * Math.cos(2 * t);
                case FIGURE8 -> Math.cos(t) + 2 * Math.cos(2 * t) - 0.5 * Math.cos(3 * t);
            };
        }
    }

    /// Deterministic geometry formatting — the same integer-when-whole rule the emitter uses, so the
    /// path data stays compact and byte-stable (matches the SvgEmitter `fmt`).
    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            return "0";
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        if (r == Math.rint(r) && !Double.isInfinite(r)) {
            return Long.toString((long) r);
        }
        return Double.toString(r);
    }
}
