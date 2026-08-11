package com.sirentide.ir;

/// A finite crystallographic root system rendered in its deterministic Coxeter (Petrie) plane.
///
/// The authored value is deliberately small: a finite Dynkin type plus whether the bounded
/// ambient-minimal-distance root links are requested. Roots, distinct-radius guide rings, links, and
/// projection geometry are derived by {@code RootSystemProjection}; none are accepted as free-form
/// author geometry.
public record RootSystem(char family, int rank, Edges edges, String textColor) implements Diagram {

    /// The shared Dynkin/Cartan catalog safely supports the existing Dynkin-diagram boundary of 200.
    /// Root closure and the O(|Φ|²) edge census are much more expensive, so this renderer deliberately
    /// owns a lower cap.
    public static final int MAX_RANK = 24;

    /// Hard cap immediately above the largest admitted family at {@link #MAX_RANK}: B24/C24 have
    /// 1,152 roots. Kept separate from rank so an incorrect count/catalog change fails closed.
    public static final int MAX_ROOTS = 1_200;

    /// Maximum simple-reflection applications during closure. At the admitted maximum the exact
    /// deterministic work is at most MAX_ROOTS × MAX_RANK = 28,800.
    public static final int MAX_REFLECTION_WORK = 50_000;

    /// Two complete pair passes (minimum-distance discovery + edge census) over 1,200 roots require
    /// 1,438,800 pair inspections. This hard cap leaves a small explicit ceiling above that.
    public static final int MAX_EDGE_PAIR_WORK = 1_500_000;

    /// A projected line soup above this is not readable. The point/ring figure still renders, while
    /// requested minimal edges degrade declaratively to none.
    public static final int MAX_MINIMAL_EDGES = 10_000;

    public enum Edges {
        NONE("none"),
        MINIMAL("minimal");

        private final String wire;

        Edges(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public RootSystem {
        family = Character.toUpperCase(family);
        if (edges == null) {
            edges = Edges.MINIMAL;
        }
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Mathematical validity plus Sirentide's explicit rank/root budgets.
    public boolean valid() {
        if (!DynkinCartan.isFiniteType(family, rank) || rank > MAX_RANK) {
            return false;
        }
        int roots = DynkinCartan.rootCount(family, rank);
        return roots > 0 && roots <= MAX_ROOTS;
    }

    public int rootCount() {
        return valid() ? DynkinCartan.rootCount(family, rank) : 0;
    }

    public int coxeterNumber() {
        return valid() ? DynkinCartan.coxeterNumber(family, rank) : 0;
    }

    public String typeLabel() {
        return DynkinCartan.typeLabel(family, rank);
    }
}
