package com.sirentide.layout;

import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.Dynkin;
import java.util.ArrayList;
import java.util.List;

/// Pure Dynkin-diagram layout (plan 8e13b196): the finite-type semisimple-Lie-algebra classification
/// drawn as small circular NODES (simple roots) on a horizontal baseline joined by 1/2/3 parallel
/// BOND segments, a 2- or 3-bond carrying a small ARROW triangle at its midpoint pointing FROM the
/// longer root TO the shorter root. Deterministic arithmetic, zero graph optimization (DESIGN §6).
///
/// CANONICAL NODE NUMBERING (the contract the Cartan-matrix oracle in {@code DynkinCartanOracleTest}
/// reconstructs against, and which {@link com.sirentide.a11y.A11yDescriber} paraphrases). Node indices
/// are 0-based; NODE anchor groups emit in this index order, so index = emit order:
///   * A_n / B_n / C_n / F_4 / G_2 — nodes 0…n−1 left-to-right on the baseline.
///   * D_n — the SPINE is nodes 0…n−3 on the baseline; node n−3 is the fork centre; the two terminal
///     fork nodes n−2 (up) and n−1 (down) sit one step to the right of the fork centre.
///   * E_n — the MAIN LINE is nodes 0…n−2 on the baseline; the branch node n−1 hangs below main-line
///     node index 2 (the 3rd node from the left end), giving the T_{1,2,n−4} arms of E_6/E_7/E_8.
///
/// BONDS (`4·cos²θ`) + ARROWS (longer→shorter root):
///   * A_n — all single (θ=120°).
///   * B_n — last bond (n−2,n−1) DOUBLE, arrow → node n−1 (the last root is SHORT).
///   * C_n — last bond (n−2,n−1) DOUBLE, arrow → node n−2 (the last root is LONG); C_n is B_n with the
///     arrow flipped — the sole discriminator, which the oracle pins.
///   * D_n — all single; node n−3 is trivalent (spine + two fork terminals).
///   * E_n — all single; main-line node 2 is trivalent (line + branch).
///   * F_4 — middle bond (1,2) DOUBLE, arrow → node 2 (roots 0,1 long; 2,3 short).
///   * G_2 — the single bond (0,1) TRIPLE, arrow → node 0 (node 1 is LONG).
///
/// EMIT: bond edges FIRST (each an EDGE anchor group of its 1–3 parallel {@link Line}s plus, for a
/// multi-bond, one arrow {@link Path} triangle), then the node discs (each a NODE anchor group of one
/// full-circle {@link Wedge}). Bonds carry the lower `seq` range, so — exactly like the sibling graph
/// layouts (tensor-network, mindmap, sankey) — {@code Sirentide.renderFrames} plays the bonds in before
/// the nodes rather than collapsing a multi-node diagram to one static frame. The canvas grows to hold
/// every node centre ± the disc radius plus a uniform {@link #MARGIN}, so nothing escapes the canvas
/// (the {@code GeometryEscapeTest} invariant). No `$…$` math labels in this slice, so this layout takes
/// no {@link com.sirentide.api.MathFragmentRenderer}. Only contract-clean line/path/wedge geometry
/// reaches the emitter — the same sanitizer-survivable alphabet every other type emits.
public final class DynkinDiagramLayout {

    private DynkinDiagramLayout() {}

    /// Node disc radius, centre-to-centre spacing, and the uniform canvas margin.
    private static final double R = 6;
    private static final double SPACING = 44;
    private static final double MARGIN = 24;
    /// Vertical drop of the D_n fork terminals off the baseline (one up, one down) and of the E_n
    /// branch node below the main line.
    private static final double FORK_DY = 26;
    private static final double BRANCH_DY = 44;
    /// Perpendicular half-gap between the parallel segments of a multi-bond (a 2-bond straddles ±GAP;
    /// a 3-bond adds a centre line). Kept < R so the strands stay tucked under the discs at the ends.
    private static final double BOND_GAP = 2.5;
    /// Arrow-triangle length (along the bond axis) and half-width (perpendicular). Small enough to sit
    /// clear of the discs at the bond midpoint, big enough to read the direction.
    private static final double ARROW_LEN = 10;
    private static final double ARROW_HALF_W = 4;

    /// The node disc fill and the bond/arrow stroke — one consistent contract-clean pair (a Dynkin
    /// diagram is a single uniform figure, not a per-item palette).
    private static final String NODE_FILL = "#4e79a7";
    private static final String BOND_STROKE = "#334155";
    private static final double BOND_WIDTH = 1.4;

    /// A canonical bond: the two node indices it joins, its bond COUNT (1/2/3), and the node index the
    /// arrow points to (the SHORTER root) — or −1 for a single bond (no arrow, no length difference).
    private record Bond(int a, int b, int count, int arrowToward) {}

    public static LaidOut layout(Dynkin d) {
        int n = d.nodeCount();
        if (n == 0) {
            // An unknown/out-of-range type → a valid, empty, MARGIN-padded canvas (never throws).
            return LaidOut.of(2 * MARGIN, 2 * MARGIN);
        }

        // 1. Canonical node CENTRES (baseline coords, pre-normalization) + the canonical BOND list.
        double[][] c = new double[n][2];   // {x, y}
        for (int i = 0; i < n; i++) {
            c[i][0] = i * SPACING;
            c[i][1] = 0;
        }
        List<Bond> bonds = new ArrayList<>();
        char fam = d.family();
        switch (fam) {
            case 'A' -> {
                for (int i = 0; i < n - 1; i++) {
                    bonds.add(new Bond(i, i + 1, 1, -1));
                }
            }
            case 'B', 'C' -> {
                for (int i = 0; i < n - 2; i++) {
                    bonds.add(new Bond(i, i + 1, 1, -1));
                }
                // Last bond double: B_n arrow → the last (short) node; C_n arrow → node n−2 (last is long).
                int shortNode = fam == 'B' ? n - 1 : n - 2;
                bonds.add(new Bond(n - 2, n - 1, 2, shortNode));
            }
            case 'D' -> {
                // Spine 0…n−3 on the baseline; node n−3 forks to terminals n−2 (up) and n−1 (down),
                // one step to its right.
                for (int i = 0; i < n - 3; i++) {
                    bonds.add(new Bond(i, i + 1, 1, -1));
                }
                double forkX = (n - 2) * SPACING;
                c[n - 2][0] = forkX;
                c[n - 2][1] = -FORK_DY;
                c[n - 1][0] = forkX;
                c[n - 1][1] = FORK_DY;
                bonds.add(new Bond(n - 3, n - 2, 1, -1));
                bonds.add(new Bond(n - 3, n - 1, 1, -1));
            }
            case 'E' -> {
                // Main line 0…n−2 on the baseline; branch node n−1 hangs below main-line node 2.
                for (int i = 0; i < n - 2; i++) {
                    bonds.add(new Bond(i, i + 1, 1, -1));
                }
                c[n - 1][0] = 2 * SPACING;
                c[n - 1][1] = BRANCH_DY;
                bonds.add(new Bond(2, n - 1, 1, -1));
            }
            case 'F' -> {
                bonds.add(new Bond(0, 1, 1, -1));
                bonds.add(new Bond(1, 2, 2, 2));   // middle double, arrow → node 2 (short)
                bonds.add(new Bond(2, 3, 1, -1));
            }
            case 'G' -> {
                bonds.add(new Bond(0, 1, 3, 0));   // triple, arrow → node 0 (node 1 is long)
            }
            default -> { /* unreachable: nodeCount()==0 for an invalid family */ }
        }

        // 2. Normalize so the whole figure sits at (MARGIN, MARGIN) with a grow-to-fit canvas.
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] p : c) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        double offX = MARGIN + R - minX;
        double offY = MARGIN + R - minY;
        for (double[] p : c) {
            p[0] += offX;
            p[1] += offY;
        }
        double canvasW = (maxX - minX) + 2 * R + 2 * MARGIN;
        double canvasH = (maxY - minY) + 2 * R + 2 * MARGIN;

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();

        // 3. Bond edges FIRST (lower seq range → play before nodes; drawn behind the discs). Each is
        //    ONE `<g role="edge">` of its 1–3 parallel segments + an arrow triangle for a multi-bond.
        for (Bond bond : bonds) {
            double ax = c[bond.a()][0];
            double ay = c[bond.a()][1];
            double bx = c[bond.b()][0];
            double by = c[bond.b()][1];
            double dx = bx - ax;
            double dy = by - ay;
            double len = Math.hypot(dx, dy);
            double ux = dx / len;
            double uy = dy / len;
            double vx = -uy;   // perpendicular unit
            double vy = ux;

            List<Shape> members = new ArrayList<>();
            double[] offsets = bond.count() == 1 ? new double[] {0}
                : bond.count() == 2 ? new double[] {-BOND_GAP, BOND_GAP}
                : new double[] {-BOND_GAP, 0, BOND_GAP};
            for (double off : offsets) {
                members.add(new Line(ax + off * vx, ay + off * vy,
                    bx + off * vx, by + off * vy, BOND_STROKE, BOND_WIDTH));
            }
            if (bond.count() >= 2 && bond.arrowToward() >= 0) {
                // Apex points toward the SHORT root; base straddles the perpendicular near the midpoint.
                double dir = bond.arrowToward() == bond.b() ? 1 : -1;
                double mx = (ax + bx) / 2;
                double my = (ay + by) / 2;
                double apexX = mx + dir * (ARROW_LEN / 2) * ux;
                double apexY = my + dir * (ARROW_LEN / 2) * uy;
                double baseCx = mx - dir * (ARROW_LEN / 2) * ux;
                double baseCy = my - dir * (ARROW_LEN / 2) * uy;
                double b1x = baseCx + ARROW_HALF_W * vx;
                double b1y = baseCy + ARROW_HALF_W * vy;
                double b2x = baseCx - ARROW_HALF_W * vx;
                double b2y = baseCy - ARROW_HALF_W * vy;
                String pathD = "M " + fmt(apexX) + " " + fmt(apexY)
                    + " L " + fmt(b1x) + " " + fmt(b1y)
                    + " L " + fmt(b2x) + " " + fmt(b2y) + " Z";
                members.add(new Path(pathD, BOND_STROKE));
            }
            shapes.add(new Group(assigner.assign(SirentideRole.EDGE, "b" + bond.a() + "-" + bond.b()),
                members));
        }

        // 4. Node discs — each ONE `<g role="node">` of a single full-circle Wedge, in index order.
        for (int i = 0; i < n; i++) {
            Wedge disc = new Wedge(c[i][0], c[i][1], R, 0, 2 * Math.PI, NODE_FILL);
            shapes.add(new Group(assigner.assign(SirentideRole.NODE, "n" + i), List.of(disc)));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Match the emitter's coordinate formatting so the arrow-triangle `d` string is byte-clean (integer
    /// when whole, else a bounded decimal). Mirrors {@code SvgEmitter.fmt} well enough for path data —
    /// the emitter re-formats every numeric it appends, but building the `d` here keeps Path opaque.
    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(Math.round(v * 1000.0) / 1000.0);
    }
}
