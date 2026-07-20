package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Snake;
import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.layout.SnakeGraphLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The snake graph (16th diagram type): a continued fraction `[a_1, a_2, …]` drawn as the canonical
/// Çanakçı–Schiffler SQUARE snake graph (arXiv 1608.06568). These tests assert on the EMITTED GEOMETRY
/// (the placed {@link Rect} tiles) and — the load-bearing part — on the SEMANTIC ORACLE that proves
/// the model is canonical: the number of PERFECT MATCHINGS of the emitted snake graph equals the
/// NUMERATOR of the continued fraction. That discriminator fails on a wrong tile model (it is exactly
/// what the previous ad-hoc "sum(a_i) squares" staircase lacked — review sir329).
///
/// Invariants covered: tile count = `sum(a_i) − 1`; `[1,1,1,1,1]` is a STRAIGHT 4-tile snake;
/// `[1]` is a 0-tile single edge; the matching-count = numerator oracle over several fixtures; canvas
/// containment; and the parse-side caps.
class SnakeGraphLayoutTest {

    // Mirrors SnakeGraphLayout's constants (UNIT=32, MARGIN=24).
    private static final double UNIT = 32;
    private static final double MARGIN = 24;
    private static final double EPS = 1e-6;

    private static Snake parse(String dsl) {
        return (Snake) DslParser.parse(dsl);
    }

    /// The laid-out tile {@link Rect}s in strip order (flattening the per-segment anchor groups). The
    /// segments partition the tiles CONTIGUOUSLY in strip order (and the snake emits ONLY tiles — no
    /// labels), so the filtered Rect sequence is exactly the strip sequence t0, t1, … .
    private static List<Rect> tiles(LaidOut laid) {
        List<Rect> out = new ArrayList<>();
        for (Shape s : Group.flatten(laid.shapes())) {
            if (s instanceof Rect r) {
                out.add(r);
            }
        }
        return out;
    }

    private static int sum(int... a) {
        int t = 0;
        for (int x : a) {
            t += x;
        }
        return t;
    }

    // -------------------------------------------------------- canonical shapes ----

    @Test
    void tileCountIsSumOfQuotientsMinusOne() {
        // The canonical invariant: sum(a_i) − 1 tiles, NOT sum(a_i).
        assertEquals(4, tiles(SnakeGraphLayout.layout(parse("snake\ncf: 1, 1, 1, 1, 1\n"))).size(),
            "[1,1,1,1,1] → 5−1 = 4 tiles");
        assertEquals(6, tiles(SnakeGraphLayout.layout(parse("snake\ncf: 1, 2, 2, 2\n"))).size(),
            "[1,2,2,2] → 7−1 = 6 tiles");
        assertEquals(10, tiles(SnakeGraphLayout.layout(parse("snake\ncf: 2, 1, 2, 1, 1, 4\n"))).size(),
            "[2,1,2,1,1,4] → 11−1 = 10 tiles");
    }

    @Test
    void allOnesIsAStraightSnakeNoTurns() {
        // φ = [1;1,1,1,1] → every sign-sequence block has length 1 → every junction is a block
        // boundary → STRAIGHT. So a straight 4-tile line, NOT a staircase.
        List<Rect> t = tiles(SnakeGraphLayout.layout(parse("snake\ncf: 1, 1, 1, 1, 1\n")));
        assertEquals(4, t.size());
        assertStrictStaircase(t);
        double y0 = t.get(0).y();
        for (int i = 0; i < t.size(); i++) {
            assertEquals(y0, t.get(i).y(), EPS, "straight snake: every tile shares one row (no turn)");
            assertEquals(MARGIN + i * UNIT, t.get(i).x(), EPS, "tile " + i + " steps one UNIT right");
        }
    }

    @Test
    void singleQuotientOneIsAZeroTileSingleEdge() {
        // [1] → sum 1 → sum − 1 = 0 tiles: a single edge (one perfect matching), never a tile.
        LaidOut laid = SnakeGraphLayout.layout(parse("snake\ncf: 1\n"));
        assertTrue(tiles(laid).isEmpty(), "[1] emits ZERO tiles");
        long lines = Group.flatten(laid.shapes()).stream().filter(s -> s instanceof Line).count();
        assertEquals(1, lines, "[1] emits exactly one edge (a <line>)");
    }

    @Test
    void snakeCarriesNoVisibleLabels() {
        // review sir344: a snake renders as a BARE tinted tile strip — NO text/GlyphRun. The old
        // per-segment tile-COUNT labels read misleadingly as partial quotients (√2 = [1,2,2,2] showed
        // "2,2,2", dropping the leading 1). The strip's matching count recovers only the CF NUMERATOR
        // (the oracle below), NOT the authored quotient list — so the quotients are stated in the a11y
        // description instead. This pins the label-free contract so it can't silently regress.
        for (String dsl : List.of(
                "snake\ncf: 1, 1, 1, 1, 1\n",   // φ — a straight strip
                "snake\ncf: 1, 2, 2, 2\n",      // √2 — the golden
                "snake\ncf: 2, 1, 2, 1, 1, 4\n" // e-start — several segments
        )) {
            long glyphs = Group.flatten(SnakeGraphLayout.layout(parse(dsl)).shapes()).stream()
                .filter(s -> s instanceof GlyphRun)
                .count();
            assertEquals(0, glyphs, "the snake emits ZERO text labels for " + dsl);
        }
    }

    // --------------------------------------------------- THE SEMANTIC ORACLE ------
    // The number of perfect matchings of the emitted snake graph MUST equal the numerator of the
    // continued fraction (Çanakçı–Schiffler). The matching count is computed INDEPENDENTLY of the
    // numerator (a brute-force dimer enumeration over the emitted tile geometry vs. the continuant
    // recurrence for the numerator) so the assertion is non-circular. This is what discriminates the
    // canonical model from a wrong one.

    @Test
    void perfectMatchingCountEqualsContinuedFractionNumerator() {
        assertMatchesNumerator(new int[] {1, 1, 1, 1, 1}, 8);      // φ convergent 8/5
        assertMatchesNumerator(new int[] {1, 2, 2, 2}, 17);       // √2 convergent 17/12
        assertMatchesNumerator(new int[] {2, 1, 2, 1, 1, 4}, 87); // e-start convergent 87/32
        assertMatchesNumerator(new int[] {1, 2, 2}, 7);
        assertMatchesNumerator(new int[] {3, 3}, 10);
        assertMatchesNumerator(new int[] {2, 3, 1, 2, 3}, 84);    // the paper's worked example, 84/37
    }

    private void assertMatchesNumerator(int[] cf, int expectedNumerator) {
        // 1. Numerator via the continuant recurrence p_k = a_k p_{k-1} + p_{k-2}, p_0=1, p_{-1}=0
        //    (fully independent of the geometry).
        long num = numerator(cf);
        assertEquals(expectedNumerator, num, "continuant numerator of " + java.util.Arrays.toString(cf));

        // 2. Perfect-matching count of the EMITTED tile geometry (independent brute-force dimer count).
        StringBuilder dsl = new StringBuilder("snake\ncf: ");
        for (int i = 0; i < cf.length; i++) {
            dsl.append(i > 0 ? ", " : "").append(cf[i]);
        }
        dsl.append('\n');
        List<Rect> t = tiles(SnakeGraphLayout.layout(parse(dsl.toString())));
        assertEquals(sum(cf) - 1, t.size(), "tile count = sum − 1 for " + java.util.Arrays.toString(cf));
        long matchings = perfectMatchings(t);

        // 3. THE ORACLE: matchings == numerator.
        assertEquals(num, matchings,
            "perfect-matching count of the emitted snake graph must equal the CF numerator for "
                + java.util.Arrays.toString(cf) + " (got " + matchings + " matchings vs numerator " + num
                + ") — a non-canonical tile model fails HERE");
    }

    /// The numerator of `[a_1,…,a_n]` via the continuant recurrence (independent of any geometry).
    private static long numerator(int[] a) {
        long pPrev2 = 0;   // p_{-1}
        long pPrev1 = 1;   // p_0
        for (int x : a) {
            long p = (long) x * pPrev1 + pPrev2;
            pPrev2 = pPrev1;
            pPrev1 = p;
        }
        return pPrev1;
    }

    /// Count perfect matchings (dimer coverings) of the snake graph formed by the emitted tiles, by
    /// brute-force enumeration over the grid graph — the union of every tile's 4 corner vertices and 4
    /// boundary edges. Deliberately implemented over the RAW EMITTED GEOMETRY and with ZERO reference
    /// to the continued fraction, so equality with the numerator is a genuine (non-circular) proof that
    /// the emitted shape is the canonical Çanakçı–Schiffler snake.
    private static long perfectMatchings(List<Rect> tiles) {
        // Recover integer grid cells (col,row) from the emitted rects (screen coords / UNIT).
        Map<Long, Integer> vid = new HashMap<>();
        List<Set<Integer>> adjTmp = new ArrayList<>();
        Set<Long> edgeKeys = new HashSet<>();
        for (Rect r : tiles) {
            int c = (int) Math.round((r.x() - MARGIN) / UNIT);
            int rw = (int) Math.round((r.y() - MARGIN) / UNIT);
            int[][] corners = {{c, rw}, {c + 1, rw}, {c, rw + 1}, {c + 1, rw + 1}};
            int[] id = new int[4];
            for (int i = 0; i < 4; i++) {
                long key = ((long) corners[i][0] << 20) ^ (corners[i][1] & 0xFFFFF);
                Integer got = vid.get(key);
                if (got == null) {
                    got = vid.size();
                    vid.put(key, got);
                    adjTmp.add(new HashSet<>());
                }
                id[i] = got;
            }
            // tile boundary edges: bottom (0-1), left (0-2), right (1-3), top (2-3)
            addEdge(adjTmp, edgeKeys, id[0], id[1]);
            addEdge(adjTmp, edgeKeys, id[0], id[2]);
            addEdge(adjTmp, edgeKeys, id[1], id[3]);
            addEdge(adjTmp, edgeKeys, id[2], id[3]);
        }
        int n = vid.size();
        if (n % 2 != 0) {
            return 0;
        }
        int[][] adj = new int[n][];
        for (int i = 0; i < n; i++) {
            int[] a = new int[adjTmp.get(i).size()];
            int k = 0;
            for (int v : adjTmp.get(i)) {
                a[k++] = v;
            }
            adj[i] = a;
        }
        return countMatchings(0, adj, new HashMap<>(), (1 << n) - 1);
    }

    private static void addEdge(List<Set<Integer>> adj, Set<Long> seen, int u, int v) {
        long key = u < v ? ((long) u << 32) | v : ((long) v << 32) | u;
        if (seen.add(key)) {
            adj.get(u).add(v);
            adj.get(v).add(u);
        }
    }

    /// Bitmask DP: match the lowest still-free vertex to each free neighbour and recurse. Memoized on
    /// the covered-vertex mask. Only reachable (sparse) masks are visited, so this is fast for the
    /// small test snakes.
    private static long countMatchings(int mask, int[][] adj, Map<Integer, Long> memo, int full) {
        if (mask == full) {
            return 1;
        }
        Long cached = memo.get(mask);
        if (cached != null) {
            return cached;
        }
        int i = 0;
        while ((mask & (1 << i)) != 0) {
            i++;
        }
        long total = 0;
        for (int j : adj[i]) {
            if ((mask & (1 << j)) == 0) {
                total += countMatchings(mask | (1 << i) | (1 << j), adj, memo, full);
            }
        }
        memo.put(mask, total);
        return total;
    }

    // ------------------------------------------------------------- containment ----

    @Test
    void everyCanonicalFixtureStaysInsideItsCanvas() {
        // The GeometryEscapeTest discipline for the new type: no drawn x/y escapes the declared canvas.
        for (String dsl : List.of(
                "snake\ncf: 1\n",
                "snake\ncf: 1, 1, 1, 1, 1\n",
                "snake\ncf: 1, 2, 2, 2\n",
                "snake\ncf: 3, 3\n",
                "snake\ncf: 2, 3, 1, 2, 3\n",
                "snake\ncf: 2, 1, 2, 1, 1, 4\n")) {
            assertContained(Sirentide.render(dsl));
        }
    }

    // ------------------------------------------------------------------- parse ----

    @Test
    void parsesQuotientsAndToleratesBracketAndSemicolonNotation() {
        // The classic `[a1; a2, …]` spelling parses identically to the comma list.
        assertEquals(List.of(1, 2, 2, 2), parse("snake\ncf: [1; 2, 2, 2]\n").quotients());
        assertEquals(List.of(1, 2, 2, 2), parse("snake\ncf: 1, 2, 2, 2\n").quotients());
        // A bare list line (no `cf:` prefix) is accepted too.
        assertEquals(List.of(3, 1, 4), parse("snake\n3, 1, 4\n").quotients());
    }

    @Test
    void dropsNonPositiveAndUnparseableTokensNeverThrowing() {
        // 0, negatives, and non-numeric tokens are dropped; the positive quotients survive in order.
        assertEquals(List.of(2, 3), parse("snake\ncf: 2, 0, -5, foo, 3\n").quotients());
    }

    @Test
    void bareSnakeIsAnEmptyButValidSnakeNotEmptyDegrade() {
        Snake s = parse("snake\n");
        assertTrue(s.quotients().isEmpty(), "no cf line → empty quotient list");
        // Still a real (non-inert) bake: a valid <svg> shell, never the 0x0 Empty degrade.
        String svg = Sirentide.render("snake\n");
        assertTrue(svg.contains("<svg"), "empty snake still bakes a valid svg shell");
    }

    @Test
    void clampsEachQuotientAndBoundsTheQuotientSum() {
        // Each quotient is clamped to MAX_SNAKE_QUOTIENT (1000); a value past it saturates, not dropped.
        assertEquals(1000, parse("snake\ncf: 999999\n").quotients().get(0));
        // The running quotient sum is bounded by MAX_DATA_ROWS (10000): a second 1000-run fits (2000),
        // but once the sum would exceed 10000 the overflowing quotient (and the rest) is dropped.
        List<Integer> qs = parse("snake\ncf: 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, "
            + "1000, 1000\n").quotients();
        int total = qs.stream().mapToInt(Integer::intValue).sum();
        assertTrue(total <= 10000, "quotient sum bounded by MAX_DATA_ROWS, was " + total);
        assertFalse(qs.size() > 10, "the 11th 1000-run would overflow 10000 and is dropped");
    }

    // ---------------------------------------------------------------- helpers ----

    /// Every consecutive pair of tiles differs by EXACTLY one UNIT step, along EXACTLY one axis
    /// (a horizontal step keeps y and moves x by +UNIT; a vertical step keeps x and moves y by ±UNIT).
    /// This is the "connected strip of unit squares" invariant — no gaps, no diagonal jumps.
    private static void assertStrictStaircase(List<Rect> sq) {
        for (int i = 1; i < sq.size(); i++) {
            double dx = sq.get(i).x() - sq.get(i - 1).x();
            double dy = sq.get(i).y() - sq.get(i - 1).y();
            boolean horizontal = Math.abs(dy) < EPS && Math.abs(Math.abs(dx) - UNIT) < EPS;
            boolean vertical = Math.abs(dx) < EPS && Math.abs(Math.abs(dy) - UNIT) < EPS;
            assertTrue(horizontal ^ vertical,
                "tile " + i + " must be exactly one UNIT step along one axis from its predecessor; "
                    + "dx=" + dx + " dy=" + dy);
        }
    }

    // -- byte-level canvas containment (the GeometryEscapeTest mechanism, X and Y) ---------------

    private static final Pattern SVG_WH =
        Pattern.compile("<svg\\b[^>]*\\bwidth=\"([0-9.]+)\"[^>]*\\bheight=\"([0-9.]+)\"");
    private static final Pattern RECT =
        Pattern.compile("<rect\\b[^>]*\\bx=\"([0-9.eE+-]+)\"[^>]*\\by=\"([0-9.eE+-]+)\"[^>]*"
            + "\\bwidth=\"([0-9.eE+-]+)\"[^>]*\\bheight=\"([0-9.eE+-]+)\"");
    private static final Pattern PATH_D = Pattern.compile("<path\\b[^>]*\\bd=\"([^\"]*)\"");
    private static final double TOL = 3.0;   // glyph ink can overhang the advance box by a hair

    private static void assertContained(String svg) {
        Matcher wm = SVG_WH.matcher(svg);
        assertTrue(wm.find(), "no width/height on the root <svg>: " + svg);
        double width = Double.parseDouble(wm.group(1));
        double height = Double.parseDouble(wm.group(2));

        Matcher rm = RECT.matcher(svg);
        while (rm.find()) {
            double x = Double.parseDouble(rm.group(1));
            double y = Double.parseDouble(rm.group(2));
            double w = Double.parseDouble(rm.group(3));
            double h = Double.parseDouble(rm.group(4));
            assertInside(x, width, "rect x");
            assertInside(x + w, width, "rect x+width");
            assertInside(y, height, "rect y");
            assertInside(y + h, height, "rect y+height");
        }
        Matcher pm = PATH_D.matcher(svg);
        while (pm.find()) {
            for (double[] xy : pathPoints(pm.group(1))) {
                assertInside(xy[0], width, "path x");
                assertInside(xy[1], height, "path y");
            }
        }
    }

    private static void assertInside(double v, double bound, String what) {
        assertTrue(v >= -TOL && v <= bound + TOL,
            what + "=" + v + " escapes the [" + (-TOL) + ", " + (bound + TOL) + "] canvas");
    }

    /// Per-command (x,y) extraction from the emitter's absolute-only path `d` (M/L: x y; Q: cx cy x y;
    /// A: rx ry rot large sweep x y; Z: none). Mirrors GeometryEscapeTest's positional reader.
    private static List<double[]> pathPoints(String d) {
        List<double[]> pts = new ArrayList<>();
        String[] tok = d.trim().split("\\s+");
        int i = 0;
        while (i < tok.length) {
            String t = tok[i];
            if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                switch (Character.toUpperCase(t.charAt(0))) {
                    case 'M', 'L' -> {
                        if (i + 2 < tok.length) {
                            pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
                        }
                        i += 3;
                    }
                    case 'Q' -> {
                        if (i + 4 < tok.length) {
                            pts.add(new double[] {Double.parseDouble(tok[i + 1]), Double.parseDouble(tok[i + 2])});
                            pts.add(new double[] {Double.parseDouble(tok[i + 3]), Double.parseDouble(tok[i + 4])});
                        }
                        i += 5;
                    }
                    case 'A' -> {
                        if (i + 7 < tok.length) {
                            pts.add(new double[] {Double.parseDouble(tok[i + 6]), Double.parseDouble(tok[i + 7])});
                        }
                        i += 8;
                    }
                    case 'Z' -> i += 1;
                    default -> i += 1;
                }
            } else {
                i += 1;
            }
        }
        return pts;
    }
}
