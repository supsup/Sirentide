package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.Dynkin;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The load-bearing SEMANTIC ORACLE for the Dynkin-diagram type (plan 8e13b196), the analogue of the
/// snake graph's perfect-matching-count test. It INDEPENDENTLY reconstructs the CARTAN MATRIX from the
/// EMITTED GEOMETRY — the node discs' centres (in NODE-anchor emit order = canonical index order), each
/// bond's parallel-segment COUNT, and each multi-bond's ARROW DIRECTION recovered from the emitted
/// triangle — and asserts it EQUALS the known-correct textbook Cartan matrix for that type.
///
/// The reconstruction rule (standard): off-diagonal `a_ij ∈ {0,-1,-2,-3}` from the bond count on edge
/// (i,j); a single bond gives `a_ij = a_ji = -1`; a c-bond with the arrow pointing to node S (the
/// SHORTER root) and away from node L (the LONGER root) gives `a_{L,S} = -c` (the longer-root ROW gets
/// the more-negative entry) and `a_{S,L} = -1`. Diagonal is 2.
///
/// This FAILS on a wrong bond count, a flipped arrow, or a wrong branch topology. The discriminating
/// fixture set includes A_n; **B_n vs C_n (identical bond structure, differing ONLY in arrow
/// direction — flip one arrow and B_3's reconstruction becomes C_3's matrix → RED)**; D_n (fork
/// topology); E_8; F_4; and G_2. A second, fully-independent oracle checks the reconstruction's
/// DETERMINANT against the known lattice-index value for each type (A_n=n+1, B/C=2, D=4, E_8=1, F_4=1,
/// G_2=1) — a Cartan-matrix invariant that does not depend on this test's hand-typed matrices.
class DynkinCartanOracleTest {

    private static final double EPS = 1e-6;

    // -------------------------------------------------------- the fixtures --------

    @Test
    void a3ReconstructsToTheStandardCartanMatrix() {
        assertCartan("dynkin\ntype: A3\n", new int[][] {
            {2, -1, 0},
            {-1, 2, -1},
            {0, -1, 2}}, 4);   // det A_n = n+1
    }

    @Test
    void a4ReconstructsToTheStandardCartanMatrix() {
        assertCartan("dynkin\ntype: A4\n", new int[][] {
            {2, -1, 0, 0},
            {-1, 2, -1, 0},
            {0, -1, 2, -1},
            {0, 0, -1, 2}}, 5);
    }

    /// B_3 vs C_3 — the CRITICAL discriminator: identical bond structure (a single then a double),
    /// differing ONLY in the double bond's arrow direction. The reconstructions are transposes of each
    /// other in the (1,2)/(2,1) corner; a flipped B_3 arrow reconstructs C_3's matrix.
    @Test
    void b3ReconstructsToTheStandardCartanMatrix() {
        assertCartan("dynkin\ntype: B3\n", new int[][] {
            {2, -1, 0},
            {-1, 2, -2},   // node 1 is LONG → its row carries the -2
            {0, -1, 2}}, 2);   // det B_n = 2
    }

    @Test
    void c3ReconstructsToTheStandardCartanMatrix() {
        assertCartan("dynkin\ntype: C3\n", new int[][] {
            {2, -1, 0},
            {-1, 2, -1},
            {0, -2, 2}}, 2);   // det C_n = 2; the -2 is now in node 2's (long) row — the arrow flip
    }

    @Test
    void b4AndC4DifferOnlyInTheLastArrow() {
        assertCartan("dynkin\ntype: B4\n", new int[][] {
            {2, -1, 0, 0},
            {-1, 2, -1, 0},
            {0, -1, 2, -2},
            {0, 0, -1, 2}}, 2);
        assertCartan("dynkin\ntype: C4\n", new int[][] {
            {2, -1, 0, 0},
            {-1, 2, -1, 0},
            {0, -1, 2, -1},
            {0, 0, -2, 2}}, 2);
    }

    @Test
    void d4ReconstructsToTheStandardForkCartanMatrix() {
        // Node 1 is the trivalent fork centre (joins spine node 0 and the two terminals 2, 3).
        assertCartan("dynkin\ntype: D4\n", new int[][] {
            {2, -1, 0, 0},
            {-1, 2, -1, -1},
            {0, -1, 2, 0},
            {0, -1, 0, 2}}, 4);   // det D_n = 4
    }

    @Test
    void d5ReconstructsToTheStandardForkCartanMatrix() {
        // Node 2 is the trivalent fork centre (joins spine node 1 and terminals 3, 4).
        assertCartan("dynkin\ntype: D5\n", new int[][] {
            {2, -1, 0, 0, 0},
            {-1, 2, -1, 0, 0},
            {0, -1, 2, -1, -1},
            {0, 0, -1, 2, 0},
            {0, 0, -1, 0, 2}}, 4);
    }

    @Test
    void e8ReconstructsToTheStandardBranchedCartanMatrix() {
        // Main line 0-1-2-3-4-5-6; branch node 7 hangs off node 2 (arms 2,4,1 from the trivalent node).
        assertCartan("dynkin\ntype: E8\n", new int[][] {
            {2, -1, 0, 0, 0, 0, 0, 0},
            {-1, 2, -1, 0, 0, 0, 0, 0},
            {0, -1, 2, -1, 0, 0, 0, -1},
            {0, 0, -1, 2, -1, 0, 0, 0},
            {0, 0, 0, -1, 2, -1, 0, 0},
            {0, 0, 0, 0, -1, 2, -1, 0},
            {0, 0, 0, 0, 0, -1, 2, 0},
            {0, 0, -1, 0, 0, 0, 0, 2}}, 1);   // det E_8 = 1
    }

    @Test
    void f4ReconstructsToTheStandardCartanMatrix() {
        // Middle bond (1,2) double, arrow → node 2 (roots 0,1 long; 2,3 short).
        assertCartan("dynkin\ntype: F4\n", new int[][] {
            {2, -1, 0, 0},
            {-1, 2, -2, 0},
            {0, -1, 2, -1},
            {0, 0, -1, 2}}, 1);   // det F_4 = 1
    }

    @Test
    void g2ReconstructsToTheStandardCartanMatrix() {
        // Triple bond, arrow → node 0 (node 1 is LONG → row 1 carries the -3).
        assertCartan("dynkin\ntype: G2\n", new int[][] {
            {2, -1},
            {-3, 2}}, 1);   // det G_2 = 1
    }

    /// The FLIP SENTINEL, stated as an executable claim: B_3 and C_3 reconstruct to DIFFERENT matrices
    /// (transposes in the double-bond corner), and each is the other's arrow-flip. If the layout drew
    /// both arrows the same way, one of the two per-type asserts above would already be RED — this pins
    /// the discriminator directly so the intent can't rot.
    @Test
    void bnVersusCnIsExactlyAnArrowFlip() {
        int[][] b3 = reconstruct("dynkin\ntype: B3\n");
        int[][] c3 = reconstruct("dynkin\ntype: C3\n");
        // Same underlying graph → equal except the (1,2)/(2,1) corner is swapped.
        assertEquals(-2, b3[1][2]);
        assertEquals(-1, b3[2][1]);
        assertEquals(-1, c3[1][2]);
        assertEquals(-2, c3[2][1]);
        // Everything else identical.
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (!((i == 1 && j == 2) || (i == 2 && j == 1))) {
                    assertEquals(b3[i][j], c3[i][j], "B3/C3 differ ONLY in the double-bond corner");
                }
            }
        }
    }

    // ------------------------------------------------ the reconstruction ----------

    private void assertCartan(String dsl, int[][] expected, int expectedDet) {
        int[][] recon = reconstruct(dsl);
        assertEquals(expected.length, recon.length, "node count: " + dsl);
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], recon[i],
                "Cartan row " + i + " reconstructed from geometry must equal the textbook matrix for "
                    + dsl.replace("\n", " ") + " — a wrong bond count, flipped arrow, or wrong branch "
                    + "topology fails HERE. Got row " + java.util.Arrays.toString(recon[i]));
        }
        // Independent invariant: the determinant = the known lattice index for the type.
        assertEquals(expectedDet, det(recon),
            "reconstructed Cartan determinant must equal the type's lattice index for " + dsl);
    }

    /// Reconstruct the Cartan matrix PURELY from the emitted geometry (no reference to the input type).
    private static int[][] reconstruct(String dsl) {
        LaidOut laid = DynkinDiagramLayout.layout((Dynkin) DslParser.parse(dsl));

        // Pass 1: node centres in NODE-anchor emit order = canonical index order.
        List<double[]> centers = new ArrayList<>();
        for (Shape s : laid.shapes()) {
            if (s instanceof Group g && g.anchor().role() == SirentideRole.NODE) {
                Wedge disc = (Wedge) g.members().get(0);
                centers.add(new double[] {disc.cx(), disc.cy()});
            }
        }
        int n = centers.size();
        int[][] a = new int[n][n];
        for (int i = 0; i < n; i++) {
            a[i][i] = 2;
        }

        // Pass 2: each EDGE group → a bond. Count = its Line segments; the endpoints resolve to the
        // two nearest node centres; the arrow (a Path triangle) fixes which is the shorter root.
        for (Shape s : laid.shapes()) {
            if (!(s instanceof Group g) || g.anchor().role() != SirentideRole.EDGE) {
                continue;
            }
            List<Line> lines = new ArrayList<>();
            Path arrow = null;
            for (Shape m : g.members()) {
                if (m instanceof Line l) {
                    lines.add(l);
                } else if (m instanceof Path p) {
                    arrow = p;
                }
            }
            int count = lines.size();
            Line probe = lines.get(0);
            int p = nearest(centers, probe.x1(), probe.y1());
            int q = nearest(centers, probe.x2(), probe.y2());

            if (count == 1) {
                a[p][q] = -1;
                a[q][p] = -1;
                continue;
            }
            // Multi-bond: recover the arrow direction from the triangle.
            double[][] tri = triangle(arrow.d());
            double px = centers.get(p)[0];
            double py = centers.get(p)[1];
            double qx = centers.get(q)[0];
            double qy = centers.get(q)[1];
            double ux = qx - px;
            double uy = qy - py;
            double ul = Math.hypot(ux, uy);
            ux /= ul;
            uy /= ul;
            double[] proj = new double[3];
            for (int k = 0; k < 3; k++) {
                proj[k] = (tri[k][0] - px) * ux + (tri[k][1] - py) * uy;
            }
            // The two BASE vertices share a projection; the APEX is the outlier. Find the base pair as
            // the two closest projections; the remaining vertex is the apex.
            int apex = apexIndex(proj);
            double baseProj = (proj[0] + proj[1] + proj[2] - proj[apex]) / 2.0;
            // Apex projection larger than the base ⇒ arrow points toward q (u runs p→q). That node is
            // the SHORTER root; the OTHER is the longer root whose row carries the more-negative entry.
            int shortN;
            int longN;
            if (proj[apex] > baseProj) {
                shortN = q;
                longN = p;
            } else {
                shortN = p;
                longN = q;
            }
            a[longN][shortN] = -count;
            a[shortN][longN] = -1;
        }
        return a;
    }

    private static int nearest(List<double[]> centers, double x, double y) {
        int best = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < centers.size(); i++) {
            double dx = centers.get(i)[0] - x;
            double dy = centers.get(i)[1] - y;
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /// The apex vertex index: the outlier of the three axial projections (the two base vertices share a
    /// projection, so the apex is the one NOT in the closest pair).
    private static int apexIndex(double[] proj) {
        double d01 = Math.abs(proj[0] - proj[1]);
        double d02 = Math.abs(proj[0] - proj[2]);
        double d12 = Math.abs(proj[1] - proj[2]);
        if (d01 <= d02 && d01 <= d12) {
            return 2;   // base = {0,1}
        }
        if (d02 <= d01 && d02 <= d12) {
            return 1;   // base = {0,2}
        }
        return 0;       // base = {1,2}
    }

    /// Parse the arrow triangle's three vertices from an "M x y L x y L x y Z" path `d`.
    private static double[][] triangle(String d) {
        String[] tok = d.trim().split("\\s+");
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        while (i < tok.length) {
            char cmd = tok[i].charAt(0);
            if (cmd == 'M' || cmd == 'L') {
                pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
                i += 3;
            } else {
                i += 1;   // Z, or a stray token
            }
        }
        return new double[][] {pts.get(0), pts.get(1), pts.get(2)};
    }

    /// Integer determinant by cofactor expansion (n ≤ 8 here — trivial, exact, no floating error).
    private static long det(int[][] m) {
        int n = m.length;
        if (n == 1) {
            return m[0][0];
        }
        long total = 0;
        for (int col = 0; col < n; col++) {
            int[][] sub = new int[n - 1][n - 1];
            for (int r = 1; r < n; r++) {
                int cc = 0;
                for (int c = 0; c < n; c++) {
                    if (c == col) {
                        continue;
                    }
                    sub[r - 1][cc++] = m[r][c];
                }
            }
            total += (long) ((col % 2 == 0) ? 1 : -1) * m[0][col] * det(sub);
        }
        return total;
    }
}
