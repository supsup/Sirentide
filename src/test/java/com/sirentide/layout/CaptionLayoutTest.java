package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/// Caption-band overflow pins (plan sirentide-caption-note-directive). {@link CaptionLayout} centers a
/// caption on the canvas and word-wraps it — but `measureWrapped` breaks on SPACES only, so a long
/// SINGLE word (no spaces) survives as one over-wide line, centers with a NEGATIVE originX, and clips
/// off BOTH canvas edges. The fix ellipsizes each line to the wrap width so the centered caption always
/// fits inside `[0, width]`. The {@code aLongSingleWordCaptionStaysWithinTheDiagramBounds} test is the
/// regression sentinel: revert the ellipsize and the caption glyphs spill past x=0 (and past width).
class CaptionLayoutTest {

    /// Collect every X coordinate emitted in a glyph path `d` string. Tokens are space-separated:
    /// `M x y`, `L x y`, `Q cx cy ex ey`, `Z`. The X of each point is the first number of its pair.
    private static List<Double> xsOf(String d) {
        java.util.List<Double> xs = new java.util.ArrayList<>();
        String[] t = d.trim().split("\\s+");
        int i = 0;
        while (i < t.length) {
            switch (t[i]) {
                case "M", "L" -> {
                    xs.add(Double.parseDouble(t[i + 1]));
                    i += 3;
                }
                case "Q" -> {
                    xs.add(Double.parseDouble(t[i + 1]));
                    xs.add(Double.parseDouble(t[i + 3]));
                    i += 5;
                }
                case "Z" -> i += 1;
                default -> i += 1;   // a bare number (defensive) — skip
            }
        }
        return xs;
    }

    @Test
    void aLongSingleWordCaptionStaysWithinTheDiagramBounds() {
        double width = 120;
        LaidOut base = LaidOut.of(width, 80);
        // A single no-space word far wider than the wrap width (120 - 2*10 = 100px): measureWrapped
        // cannot break it, so pre-fix it centered off both edges.
        String caption = "Supercalifragilisticexpialidocious".repeat(2);
        LaidOut out = CaptionLayout.withCaption(base, caption);

        List<GlyphRun> runs = out.shapes().stream()
            .filter(s -> s instanceof GlyphRun).map(s -> (GlyphRun) s).toList();
        assertTrue(!runs.isEmpty(), "the caption emitted at least one glyph run");

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        for (GlyphRun r : runs) {
            for (double x : xsOf(r.pathD())) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
        }
        // Pre-fix minX was strongly negative (tens of px) and maxX ran past `width`. Post-fix the
        // ellipsized line is centered within [0, width]; allow a sub-pixel glyph side-bearing slack.
        assertTrue(minX >= -1.0,
            "the caption's left edge stays on-canvas (minX=" + minX + ") — it does not clip off the left");
        assertTrue(maxX <= width + 1.0,
            "the caption's right edge stays on-canvas (maxX=" + maxX + ") — it does not clip off the right");
    }
}
