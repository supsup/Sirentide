package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.RootSystem;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Mathematical oracle for the rootsystem slice. These assertions discriminate the actual
/// Weyl-closure/Coxeter-plane construction from a decorative regular polygon: exact root counts span
/// every supported family, rotation is checked against h for every projected point, and the rank-2
/// systems pin the full-plane ring geometry where the expected radii are theorem-level exact.
class RootSystemProjectionTest {

    @Test
    void weylReflectionClosureMatchesTheClosedFormAtEveryAdmittedRank() {
        // This is intentionally the full bounded catalog, not one representative per family. The
        // expected formula is typed independently here; project() additionally compares closure
        // cardinality against the production DynkinCartan catalog, so a drift on either side fails.
        for (String type : allSupportedTypes()) {
            assertRootCount(type, expectedRootCount(type));
        }
    }

    @Test
    void everyAdmittedProjectionHasItsCoxeterHFoldSymmetry() {
        for (String type : allSupportedTypes()) {
            RootSystem system = system(type, RootSystem.Edges.NONE);
            RootSystemProjection.Projection p = RootSystemProjection.project(system);
            double theta = 2 * StrictMath.PI / system.coxeterNumber();
            double cs = StrictMath.cos(theta);
            double sn = StrictMath.sin(theta);
            for (RootSystemProjection.ProjectedRoot root : p.roots()) {
                // Depending on the Fourier-plane orientation C acts by +theta or -theta. The whole
                // point multiset is closed under both because it is closed under C and C^-1.
                double plusX = cs * root.x() - sn * root.y();
                double plusY = sn * root.x() + cs * root.y();
                double minusX = cs * root.x() + sn * root.y();
                double minusY = -sn * root.x() + cs * root.y();
                assertTrue(containsPoint(p.roots(), plusX, plusY),
                    type + " must contain the +2π/h rotation of " + root);
                assertTrue(containsPoint(p.roots(), minusX, minusY),
                    type + " must contain the -2π/h rotation of " + root);
            }
        }
    }

    @Test
    void a2IsOneSixPointRingAndG2IsTwoSixPointRingsInSqrtThreeRatio() {
        RootSystemProjection.Projection a2 =
            RootSystemProjection.project(system("A2", RootSystem.Edges.NONE));
        assertEquals(6, a2.roots().size());
        assertEquals(1, a2.ringRadii().size(), "A2 is one regular hexagonal root orbit");
        assertEquals(6, countOnRing(a2, 0));

        RootSystemProjection.Projection g2 =
            RootSystemProjection.project(system("G2", RootSystem.Edges.NONE));
        assertEquals(12, g2.roots().size());
        assertEquals(2, g2.ringRadii().size(), "G2 has short-root and long-root hexagons");
        assertEquals(6, countOnRing(g2, 0));
        assertEquals(6, countOnRing(g2, 1));
        assertEquals(StrictMath.sqrt(3),
            g2.ringRadii().get(1) / g2.ringRadii().get(0), 1e-9,
            "the long/short G2 root-length ratio is sqrt(3)");
    }

    @Test
    void genericRingMultiplicityUsesTheStrongCorrectInvariant() {
        // It is NOT generally true that rank == number of distinct drawn rings: different Coxeter
        // root orbits can share a radius (A3 is a small counterexample). What is always true is that
        // every distinct-radius bucket is a union of complete h-cycles, hence its multiplicity is a
        // multiple of h. Pin that strongest valid generic statement across the family census.
        RootSystemProjection.Projection a3 =
            RootSystemProjection.project(system("A3", RootSystem.Edges.NONE));
        assertEquals(2, a3.ringRadii().size(),
            "counterexample: rank(A3)=3 but two Coxeter orbits share one radius, so only two rings");
        assertEquals(8, countOnRing(a3, 0),
            "A3's inner distinct-radius bucket merges two h=4 Coxeter orbits");
        assertEquals(4, countOnRing(a3, 1), "A3's outer bucket is one h=4 orbit");

        for (String type : allSupportedTypes()) {
            RootSystem system = system(type, RootSystem.Edges.NONE);
            RootSystemProjection.Projection p = RootSystemProjection.project(system);
            int total = 0;
            for (int ring = 0; ring < p.ringRadii().size(); ring++) {
                int count = countOnRing(p, ring);
                total += count;
                assertEquals(0, count % system.coxeterNumber(),
                    type + " ring " + ring + " must contain whole h-cycles");
            }
            assertEquals(p.roots().size(), total, type + " every projected root belongs to a ring");
        }
    }

    @Test
    void representativePerFamilyRingBucketsArePinnedExactly() {
        // There is no valid generic ceil(|Phi|/h) distinct-radius formula. Pin exact observed-radius
        // bucket multiplicities type by type, including the collision cases A3, D4, and E6.
        assertRingBuckets("A3", 8, 4);
        assertRingBuckets("B4", 8, 8, 8, 8);
        assertRingBuckets("C4", 8, 8, 8, 8);
        assertRingBuckets("D4", 18, 6);
        assertRingBuckets("E6", 24, 12, 24, 12);
        assertRingBuckets("E7", 18, 18, 18, 18, 18, 18, 18);
        assertRingBuckets("E8", 30, 30, 30, 30, 30, 30, 30, 30);
        assertRingBuckets("F4", 12, 12, 12, 12);
        assertRingBuckets("G2", 6, 6);
    }

    @Test
    void nonSimplyLacedRootLengthsFollowTheSharedDynkinArrows() {
        // B4 has 8 short coordinate roots and 24 long roots; C4 is dual, so those multiplicities
        // reverse. F4 and G2 split evenly. This catches a Cartan transpose or flipped-arrow bug that
        // root cardinality alone cannot discriminate.
        assertLengthMultiplicities("B4", 8, 24);
        assertLengthMultiplicities("C4", 24, 8);
        assertLengthMultiplicities("F4", 24, 24);
        assertLengthMultiplicities("G2", 6, 6);
    }

    @Test
    void e8ShowcaseHasExactlyEightCoxeterOrbitRings() {
        RootSystemProjection.Projection e8 =
            RootSystemProjection.project(system("E8", RootSystem.Edges.NONE));
        assertEquals(8, e8.ringRadii().size(),
            "E8 has eight distinct exponent-1 Coxeter-orbit radii");
        for (int ring = 0; ring < 8; ring++) {
            assertEquals(30, countOnRing(e8, ring),
                "each E8 ring is one full h=30 Coxeter orbit");
        }
    }

    @Test
    void a2MinimalRootPolytopeIsExactlyTheSixCycle() {
        RootSystemProjection.Projection p =
            RootSystemProjection.project(system("A2", RootSystem.Edges.MINIMAL));
        assertFalse(p.edgesDegraded());
        assertEquals(6, p.minimalEdgeCount());
        assertEquals(6, p.edges().size());
        assertTrue(p.edgePairWork() > 0, "edge census performs bounded, measured pair work");
    }

    @Test
    void e8MinimalGraphFitsTheShowcaseCapInFull() {
        RootSystemProjection.Projection p =
            RootSystemProjection.project(system("E8", RootSystem.Edges.MINIMAL));
        assertEquals(240, p.roots().size());
        assertEquals(6720, p.minimalEdgeCount(),
            "the E8 root polytope has 240 vertices of degree 56");
        assertFalse(p.edgesDegraded());
        assertEquals(6720, p.edges().size(), "the intended E8 showcase keeps the complete graph");
        assertTrue(p.edgePairWork() <= RootSystem.MAX_EDGE_PAIR_WORK);
        int[] degree = new int[p.roots().size()];
        for (RootSystemProjection.Edge edge : p.edges()) {
            degree[edge.from()]++;
            degree[edge.to()]++;
        }
        for (int i = 0; i < degree.length; i++) {
            assertEquals(56, degree[i],
                "E8's 6,720 links are the full uniform 56-neighbour graph; root " + i);
        }
    }

    @Test
    void a24MinimalGraphExceedsLineCapAndDegradesAllOrNone() {
        RootSystemProjection.Projection p =
            RootSystemProjection.project(system("A24", RootSystem.Edges.MINIMAL));
        assertEquals(600, p.roots().size());
        assertEquals(13_800, p.minimalEdgeCount(),
            "A_n has n(n+1)(n-1) minimal root-polytope edges");
        assertTrue(p.edgesDegraded());
        assertEquals(List.of(), p.edges(), "never draw a misleading partial A24 edge set");
        assertTrue(p.edgePairWork() <= RootSystem.MAX_EDGE_PAIR_WORK);
    }

    @Test
    void everyFixtureStaysInsideTheExplicitReflectionWorkBudget() {
        for (String type : List.of("A24", "B24", "C24", "D24", "E8", "F4", "G2")) {
            RootSystemProjection.Projection p =
                RootSystemProjection.project(system(type, RootSystem.Edges.NONE));
            assertTrue(p.reflectionWork() > 0);
            assertTrue(p.reflectionWork() <= RootSystem.MAX_REFLECTION_WORK,
                type + " reflection closure stays bounded: " + p.reflectionWork());
        }
    }

    private static void assertRootCount(String type, int expected) {
        RootSystemProjection.Projection p =
            RootSystemProjection.project(system(type, RootSystem.Edges.NONE));
        assertEquals(expected, p.roots().size(), type + " exact finite-root-system cardinality");
        assertEquals(expected, system(type, RootSystem.Edges.NONE).rootCount());
    }

    private static RootSystem system(String type, RootSystem.Edges edges) {
        Object parsed = DslParser.parse("rootsystem\ntype: " + type
            + "\nedges: " + edges.wire() + "\n");
        assertTrue(parsed instanceof RootSystem, type + " must parse as RootSystem, got " + parsed);
        return (RootSystem) parsed;
    }

    private static List<String> allSupportedTypes() {
        List<String> out = new ArrayList<>();
        addRanks(out, 'A', 1, RootSystem.MAX_RANK);
        addRanks(out, 'B', 2, RootSystem.MAX_RANK);
        addRanks(out, 'C', 2, RootSystem.MAX_RANK);
        addRanks(out, 'D', 4, RootSystem.MAX_RANK);
        addRanks(out, 'E', 6, 8);
        out.add("F4");
        out.add("G2");
        return List.copyOf(out);
    }

    private static void addRanks(List<String> out, char family, int first, int last) {
        for (int rank = first; rank <= last; rank++) {
            out.add(String.valueOf(family) + rank);
        }
    }

    private static int expectedRootCount(String type) {
        char family = type.charAt(0);
        int rank = Integer.parseInt(type.substring(1));
        return switch (family) {
            case 'A' -> rank * (rank + 1);
            case 'B', 'C' -> 2 * rank * rank;
            case 'D' -> 2 * rank * (rank - 1);
            case 'E' -> switch (rank) {
                case 6 -> 72;
                case 7 -> 126;
                case 8 -> 240;
                default -> throw new AssertionError("unsupported E rank in test catalog: " + rank);
            };
            case 'F' -> 48;
            case 'G' -> 12;
            default -> throw new AssertionError("unsupported family in test catalog: " + family);
        };
    }

    private static void assertLengthMultiplicities(String type, int expectedShort, int expectedLong) {
        RootSystemProjection.Projection p =
            RootSystemProjection.project(system(type, RootSystem.Edges.NONE));
        long min = p.roots().stream().mapToLong(RootSystemProjection.ProjectedRoot::normTwice)
            .min().orElseThrow();
        long max = p.roots().stream().mapToLong(RootSystemProjection.ProjectedRoot::normTwice)
            .max().orElseThrow();
        assertTrue(min < max, type + " must retain two root lengths");
        assertEquals(expectedShort,
            p.roots().stream().filter(root -> root.normTwice() == min).count(),
            type + " short-root multiplicity");
        assertEquals(expectedLong,
            p.roots().stream().filter(root -> root.normTwice() == max).count(),
            type + " long-root multiplicity");
    }

    private static void assertRingBuckets(String type, int... expected) {
        RootSystemProjection.Projection p =
            RootSystemProjection.project(system(type, RootSystem.Edges.NONE));
        assertEquals(expected.length, p.ringRadii().size(), type + " distinct-radius bucket count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], countOnRing(p, i),
                type + " exact multiplicity on ascending-radius bucket " + i);
        }
    }

    private static boolean containsPoint(List<RootSystemProjection.ProjectedRoot> roots,
                                         double x, double y) {
        for (RootSystemProjection.ProjectedRoot candidate : roots) {
            double dx = candidate.x() - x;
            double dy = candidate.y() - y;
            if (dx * dx + dy * dy < 1e-16) {
                return true;
            }
        }
        return false;
    }

    private static int countOnRing(RootSystemProjection.Projection p, int ringIndex) {
        double radius = p.ringRadii().get(ringIndex);
        int count = 0;
        for (RootSystemProjection.ProjectedRoot root : p.roots()) {
            double actual = StrictMath.hypot(root.x(), root.y());
            if (StrictMath.abs(actual - radius)
                    <= 1e-8 * StrictMath.max(1.0, StrictMath.max(actual, radius))) {
                count++;
            }
        }
        return count;
    }
}
