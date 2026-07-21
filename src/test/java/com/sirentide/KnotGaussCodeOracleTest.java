package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Knot;
import com.sirentide.layout.Group;
import com.sirentide.layout.KnotDiagramLayout;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Path;
import com.sirentide.layout.Shape;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The KNOT semantic ORACLE (the snake matching-count / dynkin Cartan analogue): reconstruct the GAUSS
/// CODE from the EMITTED GEOMETRY and assert it equals the knot's KNOWN code. This is the discriminator
/// that proves the diagram is a valid double-point knot projection with the correct over/under
/// structure — it FAILS on a missing/extra crossing, a flipped over/under (a moved gap), or a
/// non-closed curve.
///
/// HOW THE RECONSTRUCTION READS GEOMETRY (non-circular w.r.t. over/under). The layout emits one open
/// stroked strand ARC per maximal run between under-gaps, in traversal (emit) order. Walking the arcs:
///   * each arc passes THROUGH exactly one crossing (a strand vertex lands within {@link #OVER_EPS} px
///     of that crossing's centre) — the OVER pass; and
///   * each arc's far END stops a GAP short of the next crossing (its last vertex is {@code >
///     }{@link #GAP_MIN} px from any crossing centre) — the UNDER pass.
/// The over/under of every encounter is thus DERIVED from whether the strand reaches the crossing or
/// stops short — NOT from any stored flag. Flip one crossing's under pass in the layout table and a
/// crossing gets two pass-throughs while another gets two gaps: the reconstructed code (and the
/// alternating property) breaks. {@link #reconstruct} is fed the arcs + the crossing CENTRES only
/// (positions, no over/under), exactly as the snake oracle recomputes matchings from raw tile geometry.
///
/// The KNOWN codes are the canonical alternating Gauss codes these built-in traversals realize:
/// trefoil O1U2O3U1O2U3; unknot the empty code. (The figure-eight (4₁) is deferred to a follow-up —
/// review round 2 — so it is not exercised here.)
class KnotGaussCodeOracleTest {

    /// A strand vertex within this many px of a crossing centre counts as passing THROUGH it (OVER).
    /// The sampling density puts the closest sample ~1px from the exact double point.
    private static final double OVER_EPS = 4.0;
    /// An arc endpoint FARTHER than this from every crossing centre is a genuine GAP (UNDER pass), not
    /// a pass-through. The gap is ~15–22px; the over pass is ~1px — a wide, unambiguous separation.
    private static final double GAP_MIN = 6.0;

    // -- the KNOWN Gauss code (canonical, alternating) ---------------------------------------------
    //    (figure8 (4₁) is deferred to a follow-up — review round 2 — so only the trefoil is asserted.)

    private static final String TREFOIL_CODE = "O1U2O3U1O2U3";

    // -- THE ORACLE --------------------------------------------------------------------------------

    @Test
    void trefoilReconstructsItsCanonicalGaussCode() {
        assertEquals(TREFOIL_CODE, reconstruct(Knot.TREFOIL),
            "the trefoil's emitted geometry must reconstruct the canonical Gauss code O1U2O3U1O2U3");
    }

    @Test
    void unknotHasTheEmptyGaussCode() {
        assertEquals("", reconstruct(Knot.UNKNOT), "the unknot (0 crossings) reconstructs the empty code");
    }

    // -- independent cross-checks (count / double-visit / alternating) -----------------------------

    @Test
    void crossingCountIsZeroOrThree() {
        assertEquals(0, KnotDiagramLayout.crossingCentresPx(Knot.UNKNOT).size(), "unknot: 0 crossings");
        assertEquals(3, KnotDiagramLayout.crossingCentresPx(Knot.TREFOIL).size(), "trefoil: 3 crossings");
    }

    @Test
    void everyCrossingIdIsVisitedExactlyTwice() {
        for (String type : List.of(Knot.TREFOIL)) {
            List<int[]> visits = visits(type);   // {id, over?1:0} in traversal order
            int n = KnotDiagramLayout.crossingCentresPx(type).size();
            int[] count = new int[n + 1];
            for (int[] v : visits) {
                count[v[0]]++;
            }
            for (int id = 1; id <= n; id++) {
                assertEquals(2, count[id],
                    type + ": crossing " + id + " must be a valid DOUBLE point (visited exactly twice)");
            }
        }
    }

    @Test
    void overUnderStrictlyAlternatesAlongTheTraversal() {
        for (String type : List.of(Knot.TREFOIL)) {
            List<int[]> visits = visits(type);
            for (int i = 0; i < visits.size(); i++) {
                assertNotEquals(visits.get(i)[1], visits.get((i + 1) % visits.size())[1],
                    type + ": over/under must strictly alternate along the traversal (alternating knot) "
                        + "at visit " + i);
            }
        }
    }

    // -- NON-VACUITY: a mutated (over/under-flipped) diagram must break the oracle ------------------

    /// Simulate a FLIPPED crossing by CLOSING one of the trefoil's gaps (append the gapped crossing
    /// centre to the two arcs that stop short of it, so that crossing now reads as passed-THROUGH on
    /// BOTH passes — an invalid double point / a flipped over/under). The reconstruction must no longer
    /// match the canonical code — proving the oracle is a real discriminator, not a tautology.
    @Test
    void closingAGapBreaksTheReconstructedCode() {
        List<double[][]> arcs = arcs(Knot.TREFOIL);
        List<double[]> cross = KnotDiagramLayout.crossingCentresPx(Knot.TREFOIL);
        // Sanity: the honest arcs reconstruct the canonical code.
        assertEquals(TREFOIL_CODE, reconstruct(arcs, cross), "baseline honest reconstruction");

        // Pick crossing id 2 and pull BOTH arc endpoints that gap around it onto its exact centre —
        // i.e. fill the gap (the under strand now reaches the crossing on its second pass too).
        double[] c2 = cross.stream().filter(c -> (int) c[0] == 2).findFirst().orElseThrow();
        double[] c2xy = {c2[1], c2[2]};
        List<double[][]> mutated = new ArrayList<>();
        for (double[][] arc : arcs) {
            double[][] m = arc;
            // If this arc's END gaps around c2 (c2 is the nearest crossing to its last vertex), extend
            // it THROUGH c2 — filling the gap so c2 now reads as passed-through on that pass too.
            if (nearestCross(arc[arc.length - 1], cross) == 2) {
                m = append(m, c2xy);
            }
            if (nearestCross(arc[0], cross) == 2) {
                m = prepend(c2xy, m);
            }
            mutated.add(m);
        }
        String broken = reconstructOrError(mutated, cross);
        assertNotEquals(TREFOIL_CODE, broken,
            "closing crossing 2's gap (a flipped over/under) MUST change the reconstructed code — got "
                + broken);
    }

    // -- reconstruction (geometry only) ------------------------------------------------------------

    /// The reconstructed Gauss code string ("O1U2…") for a built-in knot, from its EMITTED geometry.
    private static String reconstruct(String type) {
        return reconstruct(arcs(type), KnotDiagramLayout.crossingCentresPx(type));
    }

    /// Reconstruct the code from arcs (in emit order) + crossing centres. Per arc: the OVER crossing is
    /// the one an interior vertex reaches (< OVER_EPS); the UNDER crossing is the one the arc's last
    /// vertex stops a GAP short of (> GAP_MIN). Asserts those geometric facts (so a non-gap or a
    /// non-pass-through is caught), then emits O<id>/U<id> per encounter.
    private static String reconstruct(List<double[][]> arcs, List<double[]> cross) {
        StringBuilder code = new StringBuilder();
        for (int[] v : visits(arcs, cross)) {
            code.append(v[1] == 1 ? 'O' : 'U').append(v[0]);
        }
        return code.toString();
    }

    /// Like {@link #reconstruct(List, List)} but returns "INVALID:<reason>" instead of throwing when a
    /// geometric assertion is violated — used by the mutation test, where the corruption may make an
    /// encounter neither a clean pass-through nor a clean gap.
    private static String reconstructOrError(List<double[][]> arcs, List<double[]> cross) {
        try {
            return reconstruct(arcs, cross);
        } catch (AssertionError | RuntimeException e) {
            return "INVALID:" + e.getMessage();
        }
    }

    private static List<int[]> visits(String type) {
        return visits(arcs(type), KnotDiagramLayout.crossingCentresPx(type));
    }

    /// The ordered list of crossing-visits {id, over?1:0} reconstructed from the arc geometry.
    private static List<int[]> visits(List<double[][]> arcs, List<double[]> cross) {
        List<int[]> out = new ArrayList<>();
        for (double[][] arc : arcs) {
            // OVER: nearest crossing to any INTERIOR vertex; it must be a pass-through (< OVER_EPS).
            int overId = -1;
            double overDist = Double.POSITIVE_INFINITY;
            for (int k = 1; k < arc.length - 1; k++) {
                for (double[] c : cross) {
                    double d = dist(arc[k], c);
                    if (d < overDist) {
                        overDist = d;
                        overId = (int) c[0];
                    }
                }
            }
            if (overId < 0) {
                continue;   // an unknot loop (no crossings) contributes no encounters
            }
            assertTrue(overDist < OVER_EPS,
                "an arc must pass THROUGH its over-crossing (nearest interior vertex " + overDist
                    + "px from a centre, must be < " + OVER_EPS + ")");
            out.add(new int[] {overId, 1});

            // UNDER: nearest crossing to the arc's LAST vertex; it must be a GAP (> GAP_MIN).
            double[] last = arc[arc.length - 1];
            int gapId = -1;
            double gapDist = Double.POSITIVE_INFINITY;
            for (double[] c : cross) {
                double d = dist(last, c);
                if (d < gapDist) {
                    gapDist = d;
                    gapId = (int) c[0];
                }
            }
            assertTrue(gapDist > GAP_MIN,
                "an arc's far end must GAP short of its under-crossing (last vertex " + gapDist
                    + "px from the nearest centre, must be > " + GAP_MIN + ")");
            out.add(new int[] {gapId, 0});
        }
        return out;
    }

    // -- arc extraction (public geometry) ----------------------------------------------------------

    /// The emitted strand ARCS (each a polyline of (x,y) vertices), in emit (traversal) order — one per
    /// anchor group. Parsed from the public {@link Path} `d` strings (absolute M/L only), exactly the
    /// geometry a browser draws.
    private static List<double[][]> arcs(String type) {
        LaidOut laid = KnotDiagramLayout.layout(new Knot(type, null));
        List<double[][]> arcs = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            for (Shape leaf : (s instanceof Group g) ? g.members() : List.of(s)) {
                if (leaf instanceof Path p) {
                    arcs.add(parse(p.d()));
                }
            }
        }
        return arcs;
    }

    /// Parse an absolute M/L (Z-terminated) path `d` into its vertex list.
    private static double[][] parse(String d) {
        String[] tok = d.trim().split("\\s+");
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        while (i < tok.length) {
            char c = tok[i].length() == 1 ? Character.toUpperCase(tok[i].charAt(0)) : ' ';
            if ((c == 'M' || c == 'L') && i + 2 < tok.length) {
                pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
                i += 3;
            } else {
                i += 1;   // Z or stray
            }
        }
        return pts.toArray(new double[0][]);
    }

    private static int nearestCross(double[] p, List<double[]> cross) {
        int id = -1;
        double best = Double.POSITIVE_INFINITY;
        for (double[] c : cross) {
            double d = dist(p, c);
            if (d < best) {
                best = d;
                id = (int) c[0];
            }
        }
        return id;
    }

    private static double dist(double[] p, double[] c) {
        // c may be {id,x,y} (crossing) or {x,y} (a vertex); handle both.
        double cx = c.length >= 3 ? c[1] : c[0];
        double cy = c.length >= 3 ? c[2] : c[1];
        return Math.hypot(p[0] - cx, p[1] - cy);
    }

    private static double[][] append(double[][] a, double[] p) {
        double[][] out = new double[a.length + 1][];
        System.arraycopy(a, 0, out, 0, a.length);
        out[a.length] = p;
        return out;
    }

    private static double[][] prepend(double[] p, double[][] a) {
        double[][] out = new double[a.length + 1][];
        out[0] = p;
        System.arraycopy(a, 0, out, 1, a.length);
        return out;
    }
}
