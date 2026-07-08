package com.sirentide.layout;

import java.util.ArrayList;
import java.util.List;

/// Deterministic relationship-aware ordering for the grid layouts (class + ER diagrams). Reorders the
/// first-seen node sequence so that graph-adjacent nodes land in NEARBY grid slots — a light
/// reverse-Cuthill-McKee-style bandwidth reduction: pick a highest-degree seed, then greedily append
/// the still-unplaced node most connected to the already-placed set. Placing related nodes adjacent
/// makes relationship edges SHORT, so a straight centre-to-centre edge is far less likely to cross a
/// third box that is neither its source nor target.
///
/// This is a cheap crossing REDUCTION, not crossing MINIMIZATION — the latter is explicitly out of
/// scope (docs/DESIGN.md §7). It materially shortens edges (an edge now spans neighbouring grid slots
/// rather than the whole diagonal) so most straight edges route through the inter-box gap instead of
/// over an intervening box.
///
/// Deterministic: the result depends ONLY on `n`, the undirected edge set, and first-seen index — no
/// randomness, no hash-iteration order — so the byte-identical-bake invariant (§6) holds. Every
/// tie-break is a stable "prefer the lower first-seen index".
final class GridOrder {

    private GridOrder() {}

    /// Returns a permutation `perm` of `[0, n)`: `perm[slot]` is the ORIGINAL node index to place in
    /// grid slot `slot` (row-major). `edges` are undirected pairs of original node indices; a pair
    /// out of range or a self-loop is ignored. With no edges (or n <= 2, where no third box can be
    /// crossed) the identity first-seen order is returned unchanged.
    static int[] order(int n, int[][] edges) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        if (n <= 2 || edges.length == 0) {
            return perm;
        }

        // Undirected adjacency (multiplicity kept — deterministic, and a double edge legitimately
        // pulls two nodes closer). Degree = adjacency size.
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (int[] e : edges) {
            int a = e[0];
            int b = e[1];
            if (a < 0 || b < 0 || a >= n || b >= n || a == b) {
                continue;
            }
            adj.get(a).add(b);
            adj.get(b).add(a);
        }
        int[] degree = new int[n];
        for (int i = 0; i < n; i++) {
            degree[i] = adj.get(i).size();
        }

        boolean[] placed = new boolean[n];
        int[] conn = new int[n];   // count of edges from an unplaced node to the already-placed set
        int slot = 0;
        while (slot < n) {
            // Seed a fresh component: the unplaced node of highest degree; tie-break lowest index.
            int seed = -1;
            for (int i = 0; i < n; i++) {
                if (placed[i]) {
                    continue;
                }
                if (seed < 0 || degree[i] > degree[seed]) {
                    seed = i;
                }
            }
            slot = place(seed, perm, placed, conn, adj, slot);
            // Grow the component: keep appending the unplaced node most connected to the placed set,
            // tie-break higher degree then lower index — until no unplaced node touches the set (a
            // disconnected component remains, seeded on the next outer pass).
            while (true) {
                int next = -1;
                for (int i = 0; i < n; i++) {
                    if (placed[i] || conn[i] == 0) {
                        continue;
                    }
                    if (next < 0 || conn[i] > conn[next]
                        || (conn[i] == conn[next] && degree[i] > degree[next])) {
                        next = i;
                    }
                }
                if (next < 0) {
                    break;
                }
                slot = place(next, perm, placed, conn, adj, slot);
            }
        }
        return perm;
    }

    /// Places `node` into the next grid slot, marks it placed, and bumps the placed-set connection
    /// count of each of its still-unplaced neighbours. Returns the advanced slot cursor.
    private static int place(int node, int[] perm, boolean[] placed, int[] conn,
                             List<List<Integer>> adj, int slot) {
        perm[slot] = node;
        placed[node] = true;
        for (int nb : adj.get(node)) {
            if (!placed[nb]) {
                conn[nb]++;
            }
        }
        return slot + 1;
    }
}
