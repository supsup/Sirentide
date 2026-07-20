package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.TensorNetwork;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The tensor-network (Penrose graphical notation) GEOMETRY pins (plan sirentide-tensornetwork-
/// primitive). A `mps A B C D` chain lays out N tensor-core discs on a horizontal midline, a BOND
/// edge (horizontal segment at core-centre height) between each adjacent pair, and one dangling
/// PHYSICAL leg (vertical segment) per core. An `mpo` chain adds a SECOND vertical leg per core.
///
/// Lives in {@code com.sirentide.layout} to reach {@link TensorNetworkLayout#layout(TensorNetwork)}
/// directly and assert on the laid-out {@link Wedge}/{@link Line} geometry — never re-implementing
/// layout. Containment is checked byte-level against the emitted SVG in
/// {@link com.sirentide.GeometryEscapeTest}; here we pin the counts + arrangement.
class TensorNetworkLayoutTest {

    private static final String MPS4 = "tensornetwork\n  mps A B C D\n";

    private static List<Shape> shapes(String dsl) {
        TensorNetwork tn = (TensorNetwork) DslParser.parse(dsl);
        return Group.flatten(TensorNetworkLayout.layout(tn).shapes());
    }

    private static List<Wedge> cores(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Wedge).map(s -> (Wedge) s).toList();
    }

    private static List<Line> lines(List<Shape> shapes) {
        return shapes.stream().filter(s -> s instanceof Line).map(s -> (Line) s).toList();
    }

    private static List<Line> horizontal(List<Line> ls) {
        return ls.stream().filter(l -> near(l.y1(), l.y2())).toList();
    }

    private static List<Line> vertical(List<Line> ls) {
        return ls.stream().filter(l -> near(l.x1(), l.x2())).toList();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    @Test
    void mpsChainHasOneCorePerLabelInAStrictLeftToRightRow() {
        List<Wedge> cores = cores(shapes(MPS4));
        assertEquals(4, cores.size(), "mps A B C D → 4 tensor-core discs: " + cores.size());
        // Cores advance monotonically in x and share ONE horizontal midline (a straight chain).
        double y0 = cores.get(0).cy();
        for (int i = 0; i < cores.size(); i++) {
            assertTrue(near(cores.get(i).cy(), y0),
                "every core sits on the same midline y=" + y0 + ": " + cores.get(i).cy());
            if (i > 0) {
                assertTrue(cores.get(i).cx() > cores.get(i - 1).cx(),
                    "cores advance left→right: " + cores.get(i).cx());
            }
        }
    }

    @Test
    void mpsChainHasNMinus1BondEdgesConnectingAdjacentCoreCentres() {
        List<Shape> sh = shapes(MPS4);
        List<Wedge> cores = cores(sh);
        double midY = cores.get(0).cy();
        // A bond is a HORIZONTAL segment at the core-centre height whose endpoints are two adjacent
        // core centres. Physical legs are vertical (excluded by the y1==y2 filter).
        List<Line> bonds = horizontal(lines(sh)).stream()
            .filter(l -> near(l.y1(), midY))
            .toList();
        assertEquals(3, bonds.size(), "4 cores → 3 bond edges: " + bonds.size());
        // Each bond spans exactly one adjacent core-centre pair (endpoints ARE core centres).
        List<Double> cx = cores.stream().map(Wedge::cx).sorted().toList();
        for (int i = 0; i < bonds.size(); i++) {
            Line b = bonds.get(i);
            double lo = Math.min(b.x1(), b.x2());
            double hi = Math.max(b.x1(), b.x2());
            assertTrue(near(lo, cx.get(i)) && near(hi, cx.get(i + 1)),
                "bond " + i + " connects adjacent core centres " + cx.get(i) + "→" + cx.get(i + 1)
                    + " but is " + lo + "→" + hi);
        }
    }

    @Test
    void mpsChainHasOnePhysicalLegPerCoreGoingDownFromItsCentre() {
        List<Shape> sh = shapes(MPS4);
        List<Wedge> cores = cores(sh);
        double midY = cores.get(0).cy();
        List<Line> legs = vertical(lines(sh));
        assertEquals(4, legs.size(), "4 cores → 4 physical legs: " + legs.size());
        // Every leg is at a core's x, roots BELOW the disc, and extends further DOWN (larger y).
        List<Double> coreX = cores.stream().map(Wedge::cx).toList();
        for (Line leg : legs) {
            assertTrue(coreX.stream().anyMatch(x -> near(x, leg.x1())),
                "leg x=" + leg.x1() + " sits at a core centre");
            double top = Math.min(leg.y1(), leg.y2());
            double bot = Math.max(leg.y1(), leg.y2());
            assertTrue(top > midY, "physical leg hangs BELOW the core midline: top=" + top);
            assertTrue(bot > top, "leg has positive downward length");
        }
    }

    /// DELETE-MUTANT SENTINEL: a 1-core chain has ZERO bonds (the `n-1` bond loop must not run). If
    /// the bond loop were `i <= n-1` or unconditional, a lone core would sprout a spurious bond.
    @Test
    void singleCoreChainHasZeroBondEdges() {
        List<Shape> sh = shapes("tensornetwork\n  mps A\n");
        List<Wedge> cores = cores(sh);
        assertEquals(1, cores.size(), "mps A → 1 core");
        double midY = cores.get(0).cy();
        List<Line> bonds = horizontal(lines(sh)).stream().filter(l -> near(l.y1(), midY)).toList();
        assertEquals(0, bonds.size(), "a 1-core chain has no bond edges: " + bonds.size());
        // It still has its one physical leg (the core is not degenerate).
        assertEquals(1, vertical(lines(sh)).size(), "1 core → 1 physical leg");
    }

    @Test
    void everyCoreLabelIsBakedAsAGlyphRun() {
        // Each single-letter core label bakes to one non-blank glyph-run path (labels A..D present).
        long glyphRuns = shapes(MPS4).stream().filter(s -> s instanceof GlyphRun).count();
        assertEquals(4, glyphRuns, "one baked label per core: " + glyphRuns);
    }

    /// The OPTIONAL mpo variant: each core gains a SECOND (operator) vertical leg — so an N-core mpo
    /// has 2N vertical legs (one up, one down per core) vs the mps N.
    @Test
    void mpoChainAddsASecondVerticalLegPerCore() {
        List<Shape> mps = shapes("tensornetwork\n  mps A B C\n");
        List<Shape> mpo = shapes("tensornetwork\n  mpo A B C\n");
        assertEquals(3, vertical(lines(mps)).size(), "3-core mps → 3 legs");
        assertEquals(6, vertical(lines(mpo)).size(), "3-core mpo → 6 legs (up+down per core)");
        // The extra legs straddle the midline: some legs root ABOVE it (the operator up-legs).
        List<Wedge> cores = cores(mpo);
        double midY = cores.get(0).cy();
        long above = vertical(lines(mpo)).stream()
            .filter(l -> Math.min(l.y1(), l.y2()) < midY - 1e-6).count();
        long below = vertical(lines(mpo)).stream()
            .filter(l -> Math.max(l.y1(), l.y2()) > midY + 1e-6).count();
        assertEquals(3, above, "3 operator up-legs above the midline");
        assertEquals(3, below, "3 physical down-legs below the midline");
    }
}
