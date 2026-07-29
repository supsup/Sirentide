package com.sirentide.ir;

import java.util.ArrayList;
import java.util.List;

/// The single finite-type Dynkin/Cartan catalog shared by the Dynkin diagram and root-system
/// projection renderers.
///
/// The original Dynkin slice encoded this information directly in its layout switch. That was a
/// perfectly good drawing oracle, but it was not reusable by a Weyl-reflection engine. This class
/// moves the canonical BONDS to one domain-level authority and derives the Cartan matrix from those
/// bonds. Consequently there is no second hand-written matrix table for the root-system type to
/// drift from: Dynkin geometry consumes {@link #bonds(char, int)}, while reflection and metric math
/// consume {@link #matrix(char, int)} derived from the exact same bond records.
///
/// The classical families are mathematically unbounded, but Sirentide's PUBLIC catalog is not:
/// {@link #MAX_RANK} preserves the established Dynkin-diagram boundary. Rejection happens in
/// {@link #isFiniteType(char, int)} before bond construction, matrix allocation, or any rank
/// arithmetic. More expensive consumers may impose a lower cap (the root-system renderer uses 24).
/// The closed-form count/label calculations additionally use checked arithmetic as defense in depth,
/// so a future boundary change cannot silently wrap.
///
/// Cartan convention (matching {@code DynkinCartanOracleTest}): for a multiple bond whose arrow points
/// to the short root S and whose other endpoint is the long root L,
/// {@code a[L][S] = -bondCount} and {@code a[S][L] = -1}. Thus the longer-root ROW carries the
/// more-negative entry. Diagonal entries are 2.
public final class DynkinCartan {

    private DynkinCartan() {}

    /// Inclusive supported-rank boundary for every public Dynkin/Cartan catalog consumer.
    public static final int MAX_RANK = 200;

    /// One canonical Dynkin bond. {@code arrowToward == -1} for a single bond; otherwise it is the
    /// index of the short-root endpoint (the arrow points long → short).
    public record Bond(int a, int b, int count, int arrowToward) {
        public Bond {
            if (a < 0 || b < 0 || a == b || count < 1 || count > 3) {
                throw new IllegalArgumentException("invalid Dynkin bond");
            }
            if (count == 1 && arrowToward != -1) {
                throw new IllegalArgumentException("a single bond has no arrow");
            }
            if (count > 1 && arrowToward != a && arrowToward != b) {
                throw new IllegalArgumentException("a multiple-bond arrow must point to an endpoint");
            }
        }
    }

    /// True iff the pair names an irreducible crystallographic finite Dynkin type in Sirentide's
    /// bounded public catalog. The mathematical A/B/C/D families continue beyond {@link #MAX_RANK},
    /// but unsupported ranks are rejected here before any catalog consumer can allocate from them.
    public static boolean isFiniteType(char rawFamily, int rank) {
        if (rank < 1 || rank > MAX_RANK) {
            return false;
        }
        char family = Character.toUpperCase(rawFamily);
        return switch (family) {
            case 'A' -> true;
            case 'B', 'C' -> rank >= 2;
            case 'D' -> rank >= 4;
            case 'E' -> rank >= 6 && rank <= 8;
            case 'F' -> rank == 4;
            case 'G' -> rank == 2;
            default -> false;
        };
    }

    /// Canonical bonds in stable drawing order. Invalid types yield an empty list.
    public static List<Bond> bonds(char rawFamily, int rank) {
        char family = Character.toUpperCase(rawFamily);
        if (!isFiniteType(family, rank)) {
            return List.of();
        }
        List<Bond> out = new ArrayList<>();
        switch (family) {
            case 'A' -> addChain(out, rank, rank - 1);
            case 'B', 'C' -> {
                addChain(out, rank, rank - 2);
                int shortRoot = family == 'B' ? rank - 1 : rank - 2;
                out.add(new Bond(rank - 2, rank - 1, 2, shortRoot));
            }
            case 'D' -> {
                // Spine 0…rank-3, then two terminal roots at rank-2/rank-1.
                addChain(out, rank, rank - 3);
                out.add(new Bond(rank - 3, rank - 2, 1, -1));
                out.add(new Bond(rank - 3, rank - 1, 1, -1));
            }
            case 'E' -> {
                // Main line 0…rank-2; branch rank-1 off node 2. Keep the branch last to preserve the
                // pre-refactor Dynkin anchor/drawing order.
                addChain(out, rank, rank - 2);
                out.add(new Bond(2, rank - 1, 1, -1));
            }
            case 'F' -> {
                out.add(new Bond(0, 1, 1, -1));
                out.add(new Bond(1, 2, 2, 2));   // roots 0/1 long, 2/3 short
                out.add(new Bond(2, 3, 1, -1));
            }
            case 'G' -> out.add(new Bond(0, 1, 3, 0));   // node 0 short, node 1 long
            default -> { /* guarded above */ }
        }
        return List.copyOf(out);
    }

    /// The Cartan matrix derived from {@link #bonds(char, int)}. Invalid types yield a 0×0 matrix.
    /// A fresh matrix is returned on every call, so callers cannot mutate shared catalog state.
    public static int[][] matrix(char family, int rank) {
        if (!isFiniteType(family, rank)) {
            return new int[0][0];
        }
        Math.multiplyExact(rank, rank);   // allocation-size defense if MAX_RANK ever moves
        int[][] a = new int[rank][rank];
        for (int i = 0; i < rank; i++) {
            a[i][i] = 2;
        }
        for (Bond bond : bonds(family, rank)) {
            int i = bond.a();
            int j = bond.b();
            if (bond.count() == 1) {
                a[i][j] = -1;
                a[j][i] = -1;
                continue;
            }
            int shortRoot = bond.arrowToward();
            int longRoot = shortRoot == i ? j : i;
            a[longRoot][shortRoot] = -bond.count();
            a[shortRoot][longRoot] = -1;
        }
        return a;
    }

    /// Number of roots in the finite root system. Invalid types return 0.
    public static int rootCount(char rawFamily, int rank) {
        char family = Character.toUpperCase(rawFamily);
        if (!isFiniteType(family, rank)) {
            return 0;
        }
        return switch (family) {
            case 'A' -> Math.multiplyExact(rank, Math.addExact(rank, 1));
            case 'B', 'C' -> Math.multiplyExact(2, Math.multiplyExact(rank, rank));
            case 'D' -> Math.multiplyExact(2, Math.multiplyExact(rank,
                Math.subtractExact(rank, 1)));
            case 'E' -> switch (rank) {
                case 6 -> 72;
                case 7 -> 126;
                case 8 -> 240;
                default -> 0;
            };
            case 'F' -> 48;
            case 'G' -> 12;
            default -> 0;
        };
    }

    /// Coxeter number h. Invalid types return 0.
    public static int coxeterNumber(char rawFamily, int rank) {
        char family = Character.toUpperCase(rawFamily);
        if (!isFiniteType(family, rank)) {
            return 0;
        }
        return switch (family) {
            case 'A' -> Math.addExact(rank, 1);
            case 'B', 'C' -> Math.multiplyExact(2, rank);
            case 'D' -> Math.multiplyExact(2, Math.subtractExact(rank, 1));
            case 'E' -> switch (rank) {
                case 6 -> 12;
                case 7 -> 18;
                case 8 -> 30;
                default -> 0;
            };
            case 'F' -> 12;
            case 'G' -> 6;
            default -> 0;
        };
    }

    public static String typeLabel(char family, int rank) {
        return String.valueOf(Character.toUpperCase(family)) + rank;
    }

    /// Compact Lie-algebra correspondence used by accessible descriptions.
    public static String algebraLabel(char rawFamily, int rank) {
        char family = Character.toUpperCase(rawFamily);
        if (!isFiniteType(family, rank)) {
            return "";
        }
        return switch (family) {
            case 'A' -> "sl" + Math.addExact(rank, 1);
            case 'B' -> "so" + Math.addExact(Math.multiplyExact(2, rank), 1);
            case 'C' -> "sp" + Math.multiplyExact(2, rank);
            case 'D' -> "so" + Math.multiplyExact(2, rank);
            case 'E', 'F', 'G' -> typeLabel(family, rank);
            default -> "";
        };
    }

    private static void addChain(List<Bond> out, int rank, int edgeCount) {
        for (int i = 0; i < edgeCount && i + 1 < rank; i++) {
            out.add(new Bond(i, i + 1, 1, -1));
        }
    }
}
