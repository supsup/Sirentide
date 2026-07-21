package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The GEOMETRY-ESCAPE class (Lattice's Sirentide review): a label rendered OUTSIDE the declared
 * SVG canvas. This is the byte-level twin of {@link BrewShotGalleryTest}'s browser containment
 * audit — it needs no Chrome, so it runs everywhere and pins the fix into the always-green suite.
 *
 * <p>For each of the three verified repro DSLs it renders via {@link Sirentide#render}, reads the
 * declared {@code width} off the root {@code <svg>} tag, and scans EVERY x coordinate the document
 * draws — parsed PER PATH COMMAND so an arc's radii/flags and every command's y value are never
 * misread as an x (M/L: x=arg0; Q: x=arg0,arg2; A: {@code rx ry rot large sweep x y} → x=arg5),
 * plus rect {@code x}/{@code x+width} and line {@code x1}/{@code x2}. Every x must sit within
 * {@code [0-TOL, width+TOL]}.
 *
 * <p>{@code TOL=3.0} absorbs glyph-ink overhang past the advance box (the width metrics measure the
 * advance, not the ink); the real escapes overran by &gt;10px, so the tolerance can't mask them.
 */
class GeometryEscapeTest {

    /** Glyph ink can overhang the advance box by a hair; the true escapes were >10px. */
    private static final double TOL = 3.0;

    private static final Pattern SVG_WIDTH =
        Pattern.compile("<svg\\b[^>]*\\bwidth=\"([0-9.]+)\"");
    private static final Pattern PATH_D = Pattern.compile("<path\\b[^>]*\\bd=\"([^\"]*)\"");
    private static final Pattern RECT =
        Pattern.compile("<rect\\b[^>]*\\bx=\"([0-9.eE+-]+)\"[^>]*\\bwidth=\"([0-9.eE+-]+)\"");
    private static final Pattern LINE =
        Pattern.compile("<line\\b[^>]*\\bx1=\"([0-9.eE+-]+)\"[^>]*\\bx2=\"([0-9.eE+-]+)\"");

    @Test
    void pieThinOutsideLabelStaysInCanvas() {
        assertContained(
            "pie\n\"quarter\" : 25\n\"right outside label that should clip\" : 1\n\"rest\" : 74");
    }

    @Test
    void timelineEndpointLabelsStayInCanvas() {
        assertContained(
            "timeline\n\"very long left endpoint label\" : 0\n\"very long right endpoint label\" : 10");
    }

    @Test
    void flowchartForwardEdgeLabelStaysInCanvas() {
        assertContained(
            "flowchart\nA --> C\nB -->|this forward label can escape left| C");
    }

    @Test
    void flowchartNarrowCanvasEdgeLabelStaysInCanvas() {
        // Two tiny nodes + an edge label far WIDER than the whole canvas: the parse-side
        // MAX_EDGE_LABEL_W cap is canvas-independent, so the label still exceeds canvasW and
        // clampLabelX's interval inverts, pinning x at CLAMP with w unchanged → x+w escapes the right
        // edge (the TD clamp-floor regression). The canvas-relative ellipsize must contain it.
        assertContained(
            "flowchart\nA -->|this is an extremely long edge label that far exceeds the canvas width| B");
    }

    @Test
    void tensorNetworkChainAndLongCoreLabelStayInCanvas() {
        // A 4-core MPS chain (bond edges + physical legs) plus an over-long core label that must
        // ellipsize INSIDE its disc rather than overrun the canvas edge.
        assertContained("tensornetwork\nmps A B C ThisCoreLabelIsFarTooLongToFitInsideOneDisc");
    }

    @Test
    void dynkinDiagramsStayInsideTheirCanvas() {
        // Every finite family: a line (A/B/C/F/G), a vertical FORK (D — terminals offset up/down), and
        // a below-baseline BRANCH (E) all exercise the grow-to-fit canvas + arrow-triangle geometry.
        for (String type : new String[] {"A5", "B4", "C4", "D5", "E8", "F4", "G2"}) {
            assertContained("dynkin\ntype: " + type);
        }
    }

    private static void assertContained(String dsl) {
        String svg = Sirentide.render(dsl);
        Matcher wm = SVG_WIDTH.matcher(svg);
        assertTrue(wm.find(), "no width on the root <svg>: " + svg);
        double width = Double.parseDouble(wm.group(1));

        for (double x : allX(svg)) {
            assertTrue(x >= -TOL && x <= width + TOL,
                "x=" + x + " escapes the [" + (-TOL) + ", " + (width + TOL)
                    + "] canvas (declared width " + width + ")\nDSL:\n" + dsl);
        }
    }

    /** Every x coordinate the SVG draws — path commands, rect x & x+width, line x1 & x2. */
    private static List<Double> allX(String svg) {
        List<Double> xs = new ArrayList<>();

        Matcher pm = PATH_D.matcher(svg);
        while (pm.find()) {
            xs.addAll(pathXs(pm.group(1)));
        }
        Matcher rm = RECT.matcher(svg);
        while (rm.find()) {
            double x = Double.parseDouble(rm.group(1));
            double w = Double.parseDouble(rm.group(2));
            xs.add(x);
            xs.add(x + w);
        }
        Matcher lm = LINE.matcher(svg);
        while (lm.find()) {
            xs.add(Double.parseDouble(lm.group(1)));
            xs.add(Double.parseDouble(lm.group(2)));
        }
        return xs;
    }

    /**
     * Per-command x extraction from an absolute-only path {@code d} string (the emitter emits M/L/Q/A/Z
     * absolute, space-separated). Reading positionally per command is what keeps an arc's radii/flags
     * and every command's y from being counted as an x.
     */
    private static List<Double> pathXs(String d) {
        List<Double> xs = new ArrayList<>();
        String[] tok = d.trim().split("\\s+");
        int i = 0;
        while (i < tok.length) {
            String t = tok[i];
            if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                char cmd = Character.toUpperCase(t.charAt(0));
                switch (cmd) {
                    case 'M', 'L' -> {           // x y  → x = arg0
                        if (i + 1 < tok.length) {
                            xs.add(Double.parseDouble(tok[i + 1]));
                        }
                        i += 3;
                    }
                    case 'Q' -> {                // cx cy x y  → x = arg0, arg2
                        if (i + 1 < tok.length) {
                            xs.add(Double.parseDouble(tok[i + 1]));
                        }
                        if (i + 3 < tok.length) {
                            xs.add(Double.parseDouble(tok[i + 3]));
                        }
                        i += 5;
                    }
                    case 'A' -> {                // rx ry rot large sweep x y  → x = arg5 ONLY
                        if (i + 6 < tok.length) {
                            xs.add(Double.parseDouble(tok[i + 6]));
                        }
                        i += 8;
                    }
                    case 'Z' -> i += 1;
                    default -> i += 1;           // unknown command — skip, don't guess coords
                }
            } else {
                i += 1;   // stray token (shouldn't happen with this emitter) — skip
            }
        }
        return xs;
    }
}
