package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Snake;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.layout.SnakeGraphLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The snake graph (16th diagram type): a continued fraction `[a0; a1, …]` drawn as a connected strip
/// of unit squares. These tests assert on the EMITTED GEOMETRY (the placed {@link Rect}s and the
/// rendered `<rect>`s), not on layout internals, so they survive a refactor of the layout that keeps
/// the contract shape. Covers the three canonical fixtures (φ, √2, e), the direction convention (runs
/// alternate horizontal/vertical), canvas containment (nothing escapes), and the parse-side caps.
class SnakeGraphLayoutTest {

    // Mirrors SnakeGraphLayout's constants (UNIT=32, MARGIN=24).
    private static final double UNIT = 32;
    private static final double MARGIN = 24;
    private static final double EPS = 1e-6;

    private static Snake parse(String dsl) {
        return (Snake) DslParser.parse(dsl);
    }

    /// The laid-out square {@link Rect}s in strip order (flattening the per-run anchor groups). Each
    /// run emits its squares before its label glyph, and runs emit in index order, so this is exactly
    /// the strip sequence s0, s1, … .
    private static List<Rect> squares(LaidOut laid) {
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
    void phiIsAPureDiagonalStaircaseOfFiveSquares() {
        // φ = [1; 1, 1, 1, 1] — every run length 1, pure alternating → a 5-square diagonal staircase.
        LaidOut laid = SnakeGraphLayout.layout(parse("snake\ncf: 1, 1, 1, 1, 1\n"));
        List<Rect> sq = squares(laid);
        assertEquals(5, sq.size(), "square count == sum of quotients (1+1+1+1+1)");
        // Consecutive squares alternate a horizontal step (run 0,2,4 → +x, same row) with a vertical
        // step (run 1,3 → up = smaller SVG y, same column). Each step is exactly one UNIT.
        assertStrictStaircase(sq);
        // A pure diagonal: exactly one horizontal AND one vertical step between every pair, so the
        // strip is 3 cells wide and 3 tall (maxGx=2, maxGy=2).
        assertCanvas(laid, 3, 3);
    }

    @Test
    void sqrt2IsATwoUpTwoRightZigZagOfSevenSquares() {
        // √2 = [1; 2, 2, 2] — runs 1,2,2,2 alternating right/up/right/up → 7 squares.
        LaidOut laid = SnakeGraphLayout.layout(parse("snake\ncf: 1, 2, 2, 2\n"));
        List<Rect> sq = squares(laid);
        assertEquals(7, sq.size(), "square count == 1+2+2+2");
        assertStrictStaircase(sq);
        // Run lengths 1,2,2,2 with even=horizontal, odd=vertical → the runs' step directions must be
        // [ (start), V, V, H, H, V, V ] between the 7 squares (first square has no predecessor).
        assertRunDirections(sq, new int[] {1, 2, 2, 2});
        // Strip spans gx 0..2 (1 + 2 right steps) and gy 0..4 (2 + 2 up steps) → 3 wide, 5 tall.
        assertCanvas(laid, 3, 5);
    }

    @Test
    void eStartIsAnElevenSquareMixedRunStrip() {
        // e's start = [2; 1, 2, 1, 1, 4] — its characteristic mixed run pattern, 11 squares.
        int[] cf = {2, 1, 2, 1, 1, 4};
        LaidOut laid = SnakeGraphLayout.layout(parse("snake\ncf: 2, 1, 2, 1, 1, 4\n"));
        List<Rect> sq = squares(laid);
        assertEquals(sum(cf), sq.size(), "square count == 2+1+2+1+1+4 == 11");
        assertStrictStaircase(sq);
        assertRunDirections(sq, cf);
    }

    // ------------------------------------------------------------- containment ----

    @Test
    void everyCanonicalFixtureStaysInsideItsCanvas() {
        // The GeometryEscapeTest discipline for the new type: no drawn x/y escapes the declared canvas.
        for (String dsl : List.of(
                "snake\ncf: 1, 1, 1, 1, 1\n",
                "snake\ncf: 1, 2, 2, 2\n",
                "snake\ncf: 2, 1, 2, 1, 1, 4\n")) {
            assertContained(Sirentide.render(dsl));
        }
    }

    // ------------------------------------------------------------------- parse ----

    @Test
    void parsesQuotientsAndToleratesBracketAndSemicolonNotation() {
        // The classic `[a0; a1, …]` spelling parses identically to the comma list.
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
    void clampsEachQuotientAndBoundsTheSquareTotal() {
        // Each quotient is clamped to MAX_SNAKE_QUOTIENT (1000); a value past it saturates, it is not
        // dropped.
        assertEquals(1000, parse("snake\ncf: 999999\n").quotients().get(0));
        // The running square total is bounded by MAX_DATA_ROWS (10000): a second 1000-run fits (2000),
        // but once the sum would exceed 10000 the overflowing quotient (and the rest) is dropped.
        List<Integer> qs = parse("snake\ncf: 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, "
            + "1000, 1000\n").quotients();
        int total = qs.stream().mapToInt(Integer::intValue).sum();
        assertTrue(total <= 10000, "square total bounded by MAX_DATA_ROWS, was " + total);
        assertFalse(qs.size() > 10, "the 11th 1000-run would overflow 10000 and is dropped");
    }

    // ---------------------------------------------------------------- helpers ----

    /// Every consecutive pair of squares differs by EXACTLY one UNIT step, along EXACTLY one axis
    /// (a horizontal step keeps y and moves x by +UNIT; a vertical step keeps x and moves y by ±UNIT).
    /// This is the "connected strip of unit squares" invariant — no gaps, no diagonal jumps.
    private static void assertStrictStaircase(List<Rect> sq) {
        for (int i = 1; i < sq.size(); i++) {
            double dx = sq.get(i).x() - sq.get(i - 1).x();
            double dy = sq.get(i).y() - sq.get(i - 1).y();
            boolean horizontal = Math.abs(dy) < EPS && Math.abs(Math.abs(dx) - UNIT) < EPS;
            boolean vertical = Math.abs(dx) < EPS && Math.abs(Math.abs(dy) - UNIT) < EPS;
            assertTrue(horizontal ^ vertical,
                "square " + i + " must be exactly one UNIT step along one axis from its predecessor; "
                    + "dx=" + dx + " dy=" + dy);
        }
    }

    /// The step BETWEEN square i-1 and i must be HORIZONTAL when both squares belong to an even-index
    /// run, VERTICAL when both belong to an odd-index run, and (at a run boundary) match the NEW run's
    /// parity — i.e. the direction is fixed purely by which run the later square is in. Derives each
    /// square's run from the quotient lengths and checks the step axis against that run's parity.
    /// A horizontal run advances +x (right); a vertical run advances up (SVG y DECREASES).
    private static void assertRunDirections(List<Rect> sq, int[] cf) {
        int[] runOf = new int[sq.size()];
        int idx = 0;
        for (int run = 0; run < cf.length; run++) {
            for (int j = 0; j < cf[run]; j++) {
                runOf[idx++] = run;
            }
        }
        for (int i = 1; i < sq.size(); i++) {
            int run = runOf[i];
            double dx = sq.get(i).x() - sq.get(i - 1).x();
            double dy = sq.get(i).y() - sq.get(i - 1).y();
            if (run % 2 == 0) {
                assertTrue(Math.abs(dy) < EPS && Math.abs(dx - UNIT) < EPS,
                    "even run " + run + " must step RIGHT (+x) at square " + i + "; dx=" + dx + " dy=" + dy);
            } else {
                assertTrue(Math.abs(dx) < EPS && Math.abs(dy + UNIT) < EPS,
                    "odd run " + run + " must step UP (SVG y-UNIT) at square " + i + "; dx=" + dx + " dy=" + dy);
            }
        }
    }

    /// The canvas is `2*MARGIN + cols*UNIT` by `2*MARGIN + rows*UNIT`, and every square sits inside
    /// `[MARGIN, canvas-MARGIN]` on both axes (the strip is inset by exactly one MARGIN).
    private static void assertCanvas(LaidOut laid, int cols, int rows) {
        assertEquals(2 * MARGIN + cols * UNIT, laid.width(), EPS, "canvas width == 2*MARGIN + cols*UNIT");
        assertEquals(2 * MARGIN + rows * UNIT, laid.height(), EPS, "canvas height == 2*MARGIN + rows*UNIT");
        for (Rect r : squares(laid)) {
            assertTrue(r.x() >= MARGIN - EPS && r.x() + r.width() <= laid.width() - MARGIN + EPS,
                "square x-range [" + r.x() + "," + (r.x() + r.width()) + "] inside the strip");
            assertTrue(r.y() >= MARGIN - EPS && r.y() + r.height() <= laid.height() - MARGIN + EPS,
                "square y-range [" + r.y() + "," + (r.y() + r.height()) + "] inside the strip");
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
