package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.YoungDiagram;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.layout.YoungDiagramLayout;
import com.sirentide.parse.DslParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The Young diagram (a new diagram type, plan sirentide-young-diagram-primitive): a partition
/// `λ = [λ0, λ1, …]` drawn (English convention) as left-justified rows of unit boxes, the longest row on
/// top and each row stacked downward. These tests assert on the EMITTED GEOMETRY (the placed {@link Rect}
/// boxes and the rendered `<rect>`s), not on layout internals, so they survive a layout refactor that
/// keeps the contract shape. Covers the box count (== sum of parts), left-justification (every row shares
/// the same left x), the weakly-decreasing row-length invariant, a single-box control, the documented
/// non-decreasing-input sort, canvas containment (nothing escapes), and the parse-side caps.
class YoungDiagramLayoutTest {

    // Mirrors YoungDiagramLayout's constants (UNIT=32, MARGIN=24).
    private static final double UNIT = 32;
    private static final double MARGIN = 24;
    private static final double EPS = 1e-6;

    private static YoungDiagram parse(String dsl) {
        return (YoungDiagram) DslParser.parse(dsl);
    }

    /// The laid-out box {@link Rect}s (flattening the per-row anchor groups). Groups emit in row order
    /// (top row first), each row's boxes left-to-right, so this is the reading-order box sequence.
    private static List<Rect> boxes(LaidOut laid) {
        List<Rect> out = new ArrayList<>();
        for (Shape s : Group.flatten(laid.shapes())) {
            if (s instanceof Rect r) {
                out.add(r);
            }
        }
        return out;
    }

    /// Group box counts by their row (shared y), keyed top-to-bottom (ascending y = ascending row index).
    private static List<Integer> rowLengths(List<Rect> boxes) {
        Map<Long, Integer> byRow = new LinkedHashMap<>();
        for (Rect r : boxes) {
            long key = Math.round(r.y() / UNIT);   // rows are UNIT apart, anchored at MARGIN
            byRow.merge(key, 1, Integer::sum);
        }
        // LinkedHashMap preserves first-seen order; boxes emit top row first, so keys are already y-order.
        return new ArrayList<>(byRow.values());
    }

    private static int sum(int... a) {
        int t = 0;
        for (int x : a) {
            t += x;
        }
        return t;
    }

    // ------------------------------------------------------------ canonical shapes ----

    @Test
    void staircase321HasSixBoxesLeftJustifiedAndWeaklyDecreasing() {
        LaidOut laid = YoungDiagramLayout.layout(parse("young\nrows: 3, 2, 1\n"));
        List<Rect> bs = boxes(laid);
        assertEquals(sum(3, 2, 1), bs.size(), "box count == sum of parts (3+2+1 == 6)");
        assertLeftJustified(bs);
        assertWeaklyDecreasing(rowLengths(bs));
        assertEquals(List.of(3, 2, 1), rowLengths(bs), "row lengths top-to-bottom are 3, 2, 1");
        // Canvas = 2*MARGIN + maxLen*UNIT by 2*MARGIN + numRows*UNIT (maxLen=3, numRows=3).
        assertCanvas(laid, 3, 3);
    }

    @Test
    void partition442HasTenBoxesWithARepeatedTopRow() {
        LaidOut laid = YoungDiagramLayout.layout(parse("young\nrows: 4, 4, 2\n"));
        List<Rect> bs = boxes(laid);
        assertEquals(sum(4, 4, 2), bs.size(), "box count == 4+4+2 == 10");
        assertLeftJustified(bs);
        // Two equal-length rows are weakly-decreasing (4 == 4), not strictly — the invariant must allow it.
        assertWeaklyDecreasing(rowLengths(bs));
        assertEquals(List.of(4, 4, 2), rowLengths(bs), "row lengths top-to-bottom are 4, 4, 2");
        assertCanvas(laid, 4, 3);
    }

    @Test
    void singleBoxControl() {
        LaidOut laid = YoungDiagramLayout.layout(parse("young\nrows: 1\n"));
        List<Rect> bs = boxes(laid);
        assertEquals(1, bs.size(), "young([1]) is exactly one box");
        assertEquals(List.of(1), rowLengths(bs));
        assertCanvas(laid, 1, 1);
        // The lone box sits at the top-left inset corner.
        assertEquals(MARGIN, bs.get(0).x(), EPS);
        assertEquals(MARGIN, bs.get(0).y(), EPS);
    }

    // --------------------------------------------------- non-decreasing degrade (documented) ----

    @Test
    void nonDecreasingInputIsSortedDescendingNotRejected() {
        // Documented degrade: a non-weakly-decreasing authored list is SORTED to the canonical partition
        // (never thrown/emptied). `2, 3, 1` → the partition 3, 2, 1.
        assertEquals(List.of(3, 2, 1), parse("young\nrows: 2, 3, 1\n").rows(),
            "non-decreasing input is canonicalized by descending sort");
        LaidOut laid = YoungDiagramLayout.layout(parse("young\nrows: 2, 3, 1\n"));
        List<Rect> bs = boxes(laid);
        assertEquals(6, bs.size(), "box count is order-independent (still 2+3+1 == 6)");
        assertLeftJustified(bs);
        assertWeaklyDecreasing(rowLengths(bs));
        assertEquals(List.of(3, 2, 1), rowLengths(bs), "sorted shape renders as the 3,2,1 staircase");
    }

    // ------------------------------------------------------------------- parse ----

    @Test
    void parsesPartsAndToleratesBracketSemicolonAndBareListNotation() {
        // `rows:`/`parts:` prefixes, the math `[λ0; λ1, …]` spelling, and a bare list all parse identically.
        assertEquals(List.of(3, 2, 1), parse("young\nrows: 3, 2, 1\n").rows());
        assertEquals(List.of(3, 2, 1), parse("young\nparts: 3, 2, 1\n").rows());
        assertEquals(List.of(3, 2, 1), parse("young\nrows: [3; 2, 1]\n").rows());
        assertEquals(List.of(3, 2, 1), parse("young\n3 2 1\n").rows());
    }

    @Test
    void dropsNonPositiveAndUnparseableTokensNeverThrowing() {
        // 0, negatives, and non-numeric tokens are dropped; the positive parts survive (then sorted desc).
        assertEquals(List.of(3, 2), parse("young\nrows: 2, 0, -5, foo, 3\n").rows());
    }

    @Test
    void bareYoungIsAnEmptyButValidYoungNotEmptyDegrade() {
        YoungDiagram y = parse("young\n");
        assertTrue(y.rows().isEmpty(), "no rows line → empty partition");
        String svg = Sirentide.render("young\n");
        assertTrue(svg.contains("<svg"), "empty young still bakes a valid svg shell");
    }

    @Test
    void clampsEachPartAndBoundsTheBoxTotal() {
        // Each part is clamped to MAX_YOUNG_PART (1000); a value past it saturates, it is not dropped.
        assertEquals(1000, parse("young\nrows: 999999\n").rows().get(0));
        // The running box total is bounded by MAX_DATA_ROWS (10000): parts are kept until the sum would
        // exceed it, so a very tall partition drops its overflowing tail rather than OOMing.
        List<Integer> rows = parse("young\nrows: 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, "
            + "1000, 1000\n").rows();
        int total = rows.stream().mapToInt(Integer::intValue).sum();
        assertTrue(total <= 10000, "box total bounded by MAX_DATA_ROWS, was " + total);
    }

    // ------------------------------------------------------------- containment ----

    @Test
    void everyFixtureStaysInsideItsCanvas() {
        for (String dsl : List.of(
                "young\nrows: 3, 2, 1\n",
                "young\nrows: 4, 4, 2\n",
                "young\nrows: 1\n",
                "young\nrows: 5, 3, 3, 1\n")) {
            assertContained(Sirentide.render(dsl));
        }
    }

    // ---------------------------------------------------------------- helpers ----

    /// Left-justification: EVERY row shares the same left edge — the minimum box x is MARGIN, and every
    /// row contains a box exactly at x == MARGIN (its leftmost box). So the rows form a common left wall.
    private static void assertLeftJustified(List<Rect> boxes) {
        Map<Long, Double> minXByRow = new LinkedHashMap<>();
        for (Rect r : boxes) {
            long key = Math.round(r.y() / UNIT);
            minXByRow.merge(key, r.x(), Math::min);
            assertTrue(r.x() >= MARGIN - EPS, "no box left of the canvas inset margin; x=" + r.x());
        }
        for (double minX : minXByRow.values()) {
            assertEquals(MARGIN, minX, EPS, "each row's leftmost box sits on the shared left edge x=MARGIN");
        }
    }

    /// Row lengths (top-to-bottom) must be WEAKLY decreasing — each row no longer than the one above it
    /// (equal is allowed, e.g. [4,4,2]). This is the partition / English-convention invariant.
    private static void assertWeaklyDecreasing(List<Integer> lengths) {
        for (int i = 1; i < lengths.size(); i++) {
            assertTrue(lengths.get(i) <= lengths.get(i - 1),
                "row " + i + " (len " + lengths.get(i) + ") must be <= row " + (i - 1)
                    + " (len " + lengths.get(i - 1) + ")");
        }
    }

    /// The canvas is `2*MARGIN + maxLen*UNIT` by `2*MARGIN + numRows*UNIT`, and every box sits inside
    /// `[MARGIN, canvas-MARGIN]` on both axes (the diagram is inset by exactly one MARGIN).
    private static void assertCanvas(LaidOut laid, int maxLen, int numRows) {
        assertEquals(2 * MARGIN + maxLen * UNIT, laid.width(), EPS, "canvas width == 2*MARGIN + maxLen*UNIT");
        assertEquals(2 * MARGIN + numRows * UNIT, laid.height(), EPS, "canvas height == 2*MARGIN + numRows*UNIT");
        for (Rect r : boxes(laid)) {
            assertTrue(r.x() >= MARGIN - EPS && r.x() + r.width() <= laid.width() - MARGIN + EPS,
                "box x-range [" + r.x() + "," + (r.x() + r.width()) + "] inside the inset");
            assertTrue(r.y() >= MARGIN - EPS && r.y() + r.height() <= laid.height() - MARGIN + EPS,
                "box y-range [" + r.y() + "," + (r.y() + r.height()) + "] inside the inset");
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
