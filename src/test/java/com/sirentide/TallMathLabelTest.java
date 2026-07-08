package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// TALL-FRAGMENT box growth + multi-row math in labels (plan sirentide-tall-math-labels). Where the
/// inline-math seam ({@link com.sirentide.layout.MathLabel}) always MEASURED a fragment's
/// ascent/descent but the flowchart consumed only its WIDTH (a fixed-height node box), a node whose
/// label carries a TALL multi-row construct — a `$\begin{matrix}…$`, `$\begin{cases}…$`, a stacked
/// fraction — now GROWS its box to contain the taller `innerSvg`, keeping the fragment vertically
/// centered on the label baseline and fully inside the box.
///
/// The correctness crux is the byte-identical-for-short-labels guarantee: a plain-text or
/// single-line inline-`$x$` label renders EXACTLY as before (its box stays NODE_H). {@link GoldenSvgTest}
/// pins that for the NULL renderer (~40 goldens, no regen); this class pins it for the REAL LatteX
/// renderer — a short `$E=mc^2$` node's box is unchanged, only a genuinely tall fragment grows.
class TallMathLabelTest {

    private static final boolean UPDATE = Boolean.getBoolean("sirentide.updateGolden");
    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();
    private static final double NODE_H = 36;   // the fixed flowchart node height (FlowchartLayout.NODE_H)

    /// The GOLDEN fixture (receipt 1): a flowchart with a 2×2 matrix node, a 2×2 `cases` node, and a
    /// PLAIN-text sibling. The two math nodes grow taller than 36 px; the plain node stays 36. Baked
    /// through the REAL LatteX renderer (GoldenSvgTest's null-renderer path can't bake math), byte-
    /// pinned so any drift in the grown geometry is loud.
    private static final String GOLDEN_DSL =
        "flowchart TD\n"
            + "  A[Matrix $\\begin{matrix} a & b \\\\ c & d \\end{matrix}$] --> B[Plain node]\n"
            + "  A --> C[$\\begin{cases} x & a \\\\ y & b \\end{cases}$]\n";

    /// The 4-ROW matrix (ascent+descent ≈ 48 px, genuinely TALLER than the 36 px fixed box) — used for
    /// the layout + delete-mutant assertions, where the fragment must OVERFLOW a fixed box if growth
    /// is removed. A 2×2 matrix (≈ 21 px) still fits 36 px, so it would NOT witness the clip.
    private static final String TALL_LATEX =
        "\\begin{matrix} a & b \\\\ c & d \\\\ e & f \\\\ g & h \\end{matrix}";
    private static final String TALL_DSL =
        "flowchart TD\n  A[$" + TALL_LATEX + "$] --> B[Plain]\n";

    // -- receipt 1: the byte-pinned golden ----------------------------------------

    @Test
    void tallMathFlowchartMatchesGolden() throws Exception {
        String actual = Sirentide.render(GOLDEN_DSL, REAL);
        if (UPDATE) {
            Path dir = Path.of("src/test/resources/golden");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("flowchart-tallmath.svg"), actual, StandardCharsets.UTF_8);
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/golden/flowchart-tallmath.svg")) {
            assertNotNull(in, "missing golden /golden/flowchart-tallmath.svg — regen with "
                + "-Dsirentide.updateGolden=true");
            String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected, actual, "flowchart-tallmath.svg drifted — a tall-math layout change? "
                + "Regen with -Dsirentide.updateGolden=true and review the diff.");
        }
    }

    // -- receipt 2: ZERO-CHANGE pin for short labels ------------------------------

    @Test
    void shortMathLabelDoesNotGrowTheBox_realRenderer() {
        // A single-baseline `$E=mc^2$` label (ascent+descent well under one line height) must keep the
        // fixed 36 px box — byte-identical placement to the pre-growth engine. The whole diagram's
        // canvas height equals the plain-text sibling flowchart's, proving no vertical growth crept in.
        String mathDsl = "flowchart TD\n  A[Energy $E=mc^2$] --> B[Done]\n";
        String textDsl = "flowchart TD\n  A[Energy here] --> B[Done]\n";
        assertEquals(canvasHeight(Sirentide.render(textDsl, REAL)),
            canvasHeight(Sirentide.render(mathDsl, REAL)), 1e-9,
            "a short $E=mc^2$ label must not grow the node box — same canvas height as plain text");
        // Every node rect stays exactly NODE_H tall.
        for (double h : rectHeights(Sirentide.render(mathDsl, REAL))) {
            assertEquals(NODE_H, h, 1e-9, "short-math node box stays the fixed height");
        }
    }

    @Test
    void plainFlowchartByteIdenticalAcrossRendererPresence() {
        // No `$…$` at all → the renderer is never consulted → the growth path is never entered → the
        // real-renderer bake is byte-for-byte the null-renderer bake (the strongest zero-change pin).
        String dsl = "flowchart TD\n  A[Start] --> B[Process]\n  B --> C{Ready?}\n  C --> A\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl, REAL));
    }

    // -- receipt 3: the box grows to contain the fragment, centered ---------------
    // -- receipt 5: DELETE-MUTANT witness (this same test) ------------------------

    /// DELETE-MUTANT WITNESS. Forcing the node box back to the fixed NODE_H (ignoring the measured
    /// ascent/descent — the mutation that removes box growth) makes this 4-row matrix (ascent+descent
    /// ≈ 48 px) OVERFLOW the 36 px box: `baseline + descent` crosses the box bottom and the top edge,
    /// so both containment assertions below go RED. With growth the box is ≈ 69 px and the matrix sits
    /// centered with equal padding. Named so receipt 5's mutant has a witness that lives in the suite.
    @Test
    void tallMatrixBoxGrowsToContainAndCenterTheFragment() {
        String svg = Sirentide.render(TALL_DSL, REAL);
        Optional<MathFragment> frag = REAL.render(TALL_LATEX, 12.0);
        assertTrue(frag.isPresent(), "the 4-row matrix renders");
        double ascent = frag.get().heightPx();
        double descent = frag.get().depthPx();
        double ext = ascent + descent;

        // The matrix node is the TALLEST rect (its plain sibling stays NODE_H).
        double[] box = tallestRect(svg);   // {x, y, w, h}
        double rectTop = box[1];
        double rectH = box[3];
        double rectBottom = rectTop + rectH;

        // The single matrix fragment's baseline (the MathBox <g fill translate> y).
        double baseline = firstMathBoxBaseline(svg);
        double fragTop = baseline - ascent;
        double fragBottom = baseline + descent;

        // (a) the box GREW past the fixed height to make room.
        assertTrue(rectH > NODE_H + 1e-6,
            "the tall-matrix node box grew past NODE_H: rectH=" + rectH);
        // (b) the box is at least as tall as the fragment it holds.
        assertTrue(rectH >= ext - 1e-6,
            "box height >= fragment ascent+descent: rectH=" + rectH + " ext=" + ext);
        // (c) CONTAINED — the fragment ink never crosses either box edge. THESE are the assertions the
        //     delete-mutant (force boxH=NODE_H) breaks: a 48 px matrix in a 36 px box overflows.
        assertTrue(fragTop >= rectTop - 1e-6,
            "matrix top is inside the box (not clipped above): fragTop=" + fragTop + " rectTop=" + rectTop);
        assertTrue(fragBottom <= rectBottom + 1e-6,
            "matrix bottom is inside the box (not clipped below): fragBottom=" + fragBottom
                + " rectBottom=" + rectBottom);
        // (d) vertically CENTERED — equal padding above and below the ink.
        double padTop = fragTop - rectTop;
        double padBottom = rectBottom - fragBottom;
        assertTrue(Math.abs(padTop - padBottom) < 1e-6,
            "the matrix is vertically centered in the box: padTop=" + padTop + " padBottom=" + padBottom);
    }

    // -- helpers (SVG scraping) ---------------------------------------------------

    private static double canvasHeight(String svg) {
        Matcher m = Pattern.compile("<svg[^>]*\\sheight=\"([-0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg height present: " + svg.substring(0, Math.min(120, svg.length())));
        return Double.parseDouble(m.group(1));
    }

    private static List<Double> rectHeights(String svg) {
        List<Double> hs = new ArrayList<>();
        Matcher m = Pattern.compile("<rect [^>]*height=\"([-0-9.]+)\"").matcher(svg);
        while (m.find()) {
            hs.add(Double.parseDouble(m.group(1)));
        }
        return hs;
    }

    private static double[] tallestRect(String svg) {
        Matcher m = Pattern.compile(
            "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"")
            .matcher(svg);
        double[] best = null;
        while (m.find()) {
            double h = Double.parseDouble(m.group(4));
            if (best == null || h > best[3]) {
                best = new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3)), h};
            }
        }
        assertNotNull(best, "at least one node rect present");
        return best;
    }

    private static double firstMathBoxBaseline(String svg) {
        Matcher m = Pattern.compile(
            "<g fill=\"[^\"]+\" transform=\"translate\\(([-0-9.]+) ([-0-9.]+)\\)\"").matcher(svg);
        assertTrue(m.find(), "a MathBox <g fill translate> is present: "
            + svg.substring(0, Math.min(160, svg.length())));
        return Double.parseDouble(m.group(2));
    }
}
