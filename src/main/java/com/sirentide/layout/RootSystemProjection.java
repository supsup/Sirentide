package com.sirentide.layout;

import com.sirentide.ir.DynkinCartan;
import com.sirentide.ir.RootSystem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Deterministic finite-root-system mathematics for the {@code rootsystem} renderer.
///
/// <h2>Root closure</h2>
/// Roots are represented by integer coefficients in the simple-root basis. Starting from every
/// simple root, breadth-first closure applies the simple reflections
/// {@code s_i(v) = v - (sum_j v_j A[j][i]) alpha_i}. The Cartan matrix is the one derived from
/// {@link DynkinCartan}'s canonical bonds; no coordinate table or second Cartan authority exists.
/// The queue, reflection order, and final lexicographic sort are fixed, and hard root/work caps are
/// checked before growth.
///
/// <h2>Coxeter plane</h2>
/// Simple reflections 0…rank-1 form one deterministic Coxeter element C. Instead of a numerical
/// eigensolver, the exponent-1 real eigenspace is extracted exactly in concept by finite Fourier
/// averaging over C's h-step orbit:
/// {@code p = sum cos(2πk/h) C^k e_i}, {@code q = sum sin(2πk/h) C^k e_i}.
/// The first simple-root seed with a nonzero component is used. After orthonormalizing under the
/// symmetrized Cartan metric, dot products with p/q are the orthogonal Coxeter-plane projection.
/// This makes the h-fold symmetry structural, with no random choice, optimization, or data file.
///
/// <h2>Optional edges</h2>
/// {@code edges:minimal} joins all root pairs at the globally smallest nonzero ambient root-metric
/// distance. This is the root-polytope 1-skeleton for the supported simply-laced cases; that stronger
/// name is deliberately NOT applied generically because short roots of a non-simply-laced system need
/// not be vertices of the convex root polytope. Pair work and retained lines have independent hard
/// caps. If the line cap is exceeded, the complete point/ring projection remains and the edge plan
/// reports a declarative degrade to none; partial edge sets are never drawn.
public final class RootSystemProjection {

    private RootSystemProjection() {}

    private static final double EPS = 1e-10;
    private static final double RING_EPS = 1e-8;

    public record ProjectedRoot(double x, double y, long normTwice) {}

    public record Edge(int from, int to) {
        public Edge {
            if (from < 0 || to <= from) {
                throw new IllegalArgumentException("root edge indices must satisfy 0 <= from < to");
            }
        }
    }

    public record EdgeSummary(boolean degraded, int minimalEdgeCount, long pairWork) {}

    public record Projection(
        List<ProjectedRoot> roots,
        List<Edge> edges,
        /// Sorted DISTINCT projected radii, used for the visible concentric guide circles. This is
        /// not a one-entry-per-Coxeter-orbit list: separate h-cycles can project to the same radius
        /// (A3 has three Coxeter orbits but only two distinct radii, with multiplicities 8 and 4).
        List<Double> ringRadii,
        boolean edgesDegraded,
        int minimalEdgeCount,
        long reflectionWork,
        long edgePairWork
    ) {
        public Projection {
            roots = List.copyOf(roots);
            edges = List.copyOf(edges);
            ringRadii = List.copyOf(ringRadii);
        }
    }

    /// Complete deterministic projection. Invalid IR is an internal invariant break: the parser must
    /// have degraded it to Empty. The exception is caught at Sirentide's public bake guard.
    public static Projection project(RootSystem system) {
        requireValid(system);
        Generated generated = generate(system);
        Plane plane = coxeterPlane(generated.cartan(), generated.metricTwice(),
            system.coxeterNumber());

        List<ProjectedRoot> projected = new ArrayList<>(generated.roots().size());
        for (int i = 0; i < generated.roots().size(); i++) {
            int[] root = generated.roots().get(i);
            double x = snap(dot(root, generated.metricTwice(), plane.u()));
            double y = snap(dot(root, generated.metricTwice(), plane.v()));
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw limitFailure("non-finite Coxeter-plane coordinate");
            }
            projected.add(new ProjectedRoot(x, y, generated.norms()[i]));
        }

        List<Double> rings = ringRadii(projected);
        EdgePlan edgePlan = minimalEdges(system, generated);
        return new Projection(projected, edgePlan.edges(), rings, edgePlan.degraded(),
            edgePlan.count(), generated.reflectionWork(), edgePlan.pairWork());
    }

    /// The bounded minimal-edge disposition without doing the floating-point projection. Used by the
    /// accessible description so an edge-cap degrade is explicit in the emitted {@code <desc>}.
    public static EdgeSummary edgeSummary(RootSystem system) {
        requireValid(system);
        if (system.edges() == RootSystem.Edges.NONE) {
            return new EdgeSummary(false, 0, 0);
        }
        EdgePlan plan = minimalEdges(system, generate(system));
        return new EdgeSummary(plan.degraded(), plan.count(), plan.pairWork());
    }

    private static void requireValid(RootSystem system) {
        if (system == null || !system.valid()) {
            throw limitFailure("invalid or over-cap root-system IR reached layout");
        }
    }

    private record Generated(
        int[][] cartan,
        long[][] metricTwice,
        List<int[]> roots,
        long[][] covectors,
        long[] norms,
        long reflectionWork
    ) {}

    private static Generated generate(RootSystem system) {
        int rank = system.rank();
        int expected = system.rootCount();
        if (rank <= 0 || rank > RootSystem.MAX_RANK
                || expected <= 0 || expected > RootSystem.MAX_ROOTS) {
            throw limitFailure("rank/root preflight exceeded");
        }
        int[][] cartan = DynkinCartan.matrix(system.family(), rank);
        long[][] metric = symmetrizedMetricTwice(system);

        Set<VectorKey> seen = new LinkedHashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int i = 0; i < rank; i++) {
            int[] simple = new int[rank];
            simple[i] = 1;
            addRoot(seen, queue, simple);
        }

        long work = 0;
        while (!queue.isEmpty()) {
            int[] root = queue.removeFirst();
            for (int reflection = 0; reflection < rank; reflection++) {
                if (++work > RootSystem.MAX_REFLECTION_WORK) {
                    throw limitFailure("reflection closure exceeded MAX_REFLECTION_WORK="
                        + RootSystem.MAX_REFLECTION_WORK);
                }
                long factor = 0;
                for (int j = 0; j < rank; j++) {
                    factor += (long) root[j] * cartan[j][reflection];
                }
                int[] next = root.clone();
                long changed = (long) next[reflection] - factor;
                if (changed < Integer.MIN_VALUE || changed > Integer.MAX_VALUE) {
                    throw limitFailure("root coefficient overflow");
                }
                next[reflection] = (int) changed;
                VectorKey key = new VectorKey(next);
                if (!seen.contains(key)) {
                    if (seen.size() >= RootSystem.MAX_ROOTS) {
                        throw limitFailure("root closure exceeded MAX_ROOTS=" + RootSystem.MAX_ROOTS);
                    }
                    seen.add(key);
                    queue.addLast(next);
                }
            }
        }

        if (seen.size() != expected) {
            throw limitFailure("Cartan/Weyl closure produced " + seen.size()
                + " roots, expected " + expected + " for " + system.typeLabel());
        }
        List<int[]> roots = new ArrayList<>(seen.size());
        for (VectorKey key : seen) {
            roots.add(key.vector().clone());
        }
        roots.sort(RootSystemProjection::compareVectors);

        long[][] covectors = new long[roots.size()][rank];
        long[] norms = new long[roots.size()];
        for (int r = 0; r < roots.size(); r++) {
            int[] root = roots.get(r);
            for (int i = 0; i < rank; i++) {
                long sum = 0;
                for (int j = 0; j < rank; j++) {
                    sum += metric[i][j] * root[j];
                }
                covectors[r][i] = sum;
            }
            norms[r] = dot(root, covectors[r]);
            if (norms[r] <= 0) {
                throw limitFailure("non-positive root norm");
            }
        }
        return new Generated(cartan, metric, List.copyOf(roots), covectors, norms, work);
    }

    private static void addRoot(Set<VectorKey> seen, ArrayDeque<int[]> queue, int[] root) {
        VectorKey key = new VectorKey(root);
        if (seen.add(key)) {
            queue.addLast(root);
        }
    }

    /// Immutable array key (the reflection queue never mutates a vector after insertion).
    private static final class VectorKey {
        private final int[] vector;
        private final int hash;

        VectorKey(int[] vector) {
            this.vector = vector.clone();
            this.hash = Arrays.hashCode(this.vector);
        }

        int[] vector() {
            return vector;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof VectorKey k && Arrays.equals(vector, k.vector);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static int compareVectors(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            int c = Integer.compare(left[i], right[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /// Integer symmetric matrix equal to twice the Euclidean Gram matrix, up to one harmless global
    /// scale. Squared simple-root lengths propagate directly from the shared bond arrows: a long root
    /// has {@code bondCount ×} the short root's squared length.
    private static long[][] symmetrizedMetricTwice(RootSystem system) {
        int rank = system.rank();
        long[] lengths = new long[rank];
        lengths[0] = 6;   // divisible by both crystallographic ratios 2 and 3
        List<DynkinCartan.Bond> bonds = DynkinCartan.bonds(system.family(), rank);
        boolean changed;
        do {
            changed = false;
            for (DynkinCartan.Bond bond : bonds) {
                int a = bond.a();
                int b = bond.b();
                if (lengths[a] != 0 && lengths[b] == 0) {
                    lengths[b] = adjacentLength(lengths[a], a, bond);
                    changed = true;
                } else if (lengths[b] != 0 && lengths[a] == 0) {
                    lengths[a] = adjacentLength(lengths[b], b, bond);
                    changed = true;
                }
            }
        } while (changed);
        for (long length : lengths) {
            if (length <= 0) {
                throw limitFailure("disconnected Dynkin metric");
            }
        }

        int[][] cartan = DynkinCartan.matrix(system.family(), rank);
        long[][] metric = new long[rank][rank];
        for (int i = 0; i < rank; i++) {
            for (int j = 0; j < rank; j++) {
                metric[i][j] = (long) cartan[i][j] * lengths[j];
                if (metric[i][j] != (long) cartan[j][i] * lengths[i]) {
                    throw limitFailure("Cartan matrix failed symmetrization");
                }
            }
        }
        return metric;
    }

    private static long adjacentLength(long known, int knownIndex, DynkinCartan.Bond bond) {
        if (bond.count() == 1) {
            return known;
        }
        boolean knownIsShort = bond.arrowToward() == knownIndex;
        if (knownIsShort) {
            return known * bond.count();
        }
        if (known % bond.count() != 0) {
            throw limitFailure("non-integral crystallographic length ratio");
        }
        return known / bond.count();
    }

    private record Plane(double[] u, double[] v) {}

    private static Plane coxeterPlane(int[][] cartan, long[][] metric, int h) {
        int rank = cartan.length;
        if (rank == 1) {
            double[] u = {1};
            normalize(u, metric);
            return new Plane(u, new double[] {0});
        }
        double theta = 2.0 * StrictMath.PI / h;
        for (int seedIndex = 0; seedIndex < rank; seedIndex++) {
            double[] p = new double[rank];
            double[] q = new double[rank];
            double[] orbit = new double[rank];
            orbit[seedIndex] = 1;
            for (int k = 0; k < h; k++) {
                double angle = k * theta;
                double cs = StrictMath.cos(angle);
                double sn = StrictMath.sin(angle);
                for (int j = 0; j < rank; j++) {
                    p[j] += cs * orbit[j];
                    q[j] += sn * orbit[j];
                }
                orbit = applyCoxeter(orbit, cartan);
            }
            double pNorm = dot(p, metric, p);
            if (!(pNorm > EPS)) {
                continue;
            }
            normalize(p, metric);
            // Numerical cleanup only: Fourier p/q are exactly orthogonal in the invariant plane.
            double along = dot(q, metric, p);
            for (int j = 0; j < rank; j++) {
                q[j] -= along * p[j];
            }
            double qNorm = dot(q, metric, q);
            if (!(qNorm > EPS)) {
                continue;
            }
            normalize(q, metric);
            return new Plane(p, q);
        }
        throw limitFailure("could not extract exponent-1 Coxeter eigenspace");
    }

    private static double[] applyCoxeter(double[] input, int[][] cartan) {
        double[] out = input.clone();
        int rank = out.length;
        // Apply s_0, s_1, … in that deterministic order. Since each reflection mutates `out`, the
        // resulting matrix is S_(rank-1)…S_1 S_0 — a Coxeter element.
        for (int i = 0; i < rank; i++) {
            double factor = 0;
            for (int j = 0; j < rank; j++) {
                factor += out[j] * cartan[j][i];
            }
            out[i] -= factor;
        }
        return out;
    }

    private static void normalize(double[] v, long[][] metric) {
        double norm = StrictMath.sqrt(dot(v, metric, v));
        if (!(norm > EPS) || !Double.isFinite(norm)) {
            throw limitFailure("zero/non-finite Coxeter-plane basis vector");
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
    }

    private static double dot(int[] left, long[][] metric, double[] right) {
        double total = 0;
        for (int i = 0; i < left.length; i++) {
            if (left[i] == 0) {
                continue;
            }
            for (int j = 0; j < right.length; j++) {
                total += left[i] * metric[i][j] * right[j];
            }
        }
        return total;
    }

    private static double dot(double[] left, long[][] metric, double[] right) {
        double total = 0;
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                total += left[i] * metric[i][j] * right[j];
            }
        }
        return total;
    }

    private static long dot(int[] coefficients, long[] covector) {
        long total = 0;
        for (int i = 0; i < coefficients.length; i++) {
            total += (long) coefficients[i] * covector[i];
        }
        return total;
    }

    private static List<Double> ringRadii(List<ProjectedRoot> roots) {
        List<Double> all = new ArrayList<>(roots.size());
        for (ProjectedRoot root : roots) {
            double radius = StrictMath.hypot(root.x(), root.y());
            if (radius > EPS) {
                all.add(radius);
            }
        }
        all.sort(Comparator.naturalOrder());
        List<Double> distinct = new ArrayList<>();
        for (double radius : all) {
            if (distinct.isEmpty() || !sameRadius(radius, distinct.get(distinct.size() - 1))) {
                distinct.add(radius);
            }
        }
        return List.copyOf(distinct);
    }

    private static boolean sameRadius(double a, double b) {
        return StrictMath.abs(a - b) <= RING_EPS * StrictMath.max(1.0, StrictMath.max(a, b));
    }

    private record EdgePlan(List<Edge> edges, boolean degraded, int count, long pairWork) {}

    private static EdgePlan minimalEdges(RootSystem system, Generated generated) {
        if (system.edges() == RootSystem.Edges.NONE) {
            return new EdgePlan(List.of(), false, 0, 0);
        }
        int n = generated.roots().size();
        long pairs = (long) n * (n - 1) / 2;
        if (pairs * 2 > RootSystem.MAX_EDGE_PAIR_WORK) {
            // No partial scan and no partial line set: declaratively fall back to edges:none.
            return new EdgePlan(List.of(), true, -1, 0);
        }

        long pairWork = 0;
        long minDistance = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                pairWork++;
                long distance = distance(generated, i, j);
                if (distance > 0 && distance < minDistance) {
                    minDistance = distance;
                }
            }
        }
        if (minDistance == Long.MAX_VALUE) {
            return new EdgePlan(List.of(), false, 0, pairWork);
        }

        List<Edge> retained = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                pairWork++;
                if (distance(generated, i, j) == minDistance) {
                    count++;
                    if (retained.size() < RootSystem.MAX_MINIMAL_EDGES) {
                        retained.add(new Edge(i, j));
                    }
                }
            }
        }
        if (count > RootSystem.MAX_MINIMAL_EDGES) {
            return new EdgePlan(List.of(), true, count, pairWork);
        }
        return new EdgePlan(List.copyOf(retained), false, count, pairWork);
    }

    private static long distance(Generated generated, int i, int j) {
        int[] left = generated.roots().get(i);
        long dot = dot(left, generated.covectors()[j]);
        long distance = generated.norms()[i] + generated.norms()[j] - 2 * dot;
        if (distance < 0) {
            throw limitFailure("negative root distance");
        }
        return distance;
    }

    private static double snap(double value) {
        return StrictMath.abs(value) < 1e-12 ? 0.0 : value;
    }

    private static IllegalStateException limitFailure(String detail) {
        return new IllegalStateException("MAX_ROOT_SYSTEM_WORK: " + detail);
    }
}
