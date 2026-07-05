package com.sirentide.font;

import java.util.ArrayList;
import java.util.List;

/// The font-metrics oracle: turns label text into pixel measurements for layout — deterministic,
/// no DOM, no browser (docs/DESIGN.md §4). Wraps the raw sfnt {@link SfntMetrics} with the
/// text-run / line-height / word-wrap / box-sizing layout actually needs (LatteX's font layer
/// only measures single math glyphs; this is the genuinely-new text-run work).
///
/// All measurements scale from design units by `fontSizePx / unitsPerEm`. Output is a pure
/// function of (text, size, wrap width) — byte-identical bakes depend on it (§6).
public final class FontMetrics {

    private final SfntMetrics sfnt;
    private final double unitsPerEm;

    private FontMetrics(SfntMetrics sfnt) {
        this.sfnt = sfnt;
        this.unitsPerEm = sfnt.unitsPerEm();
    }

    private static final class Holder {
        static final FontMetrics BUNDLED = new FontMetrics(SfntMetrics.loadBundled());
    }

    /// The oracle over the bundled label font (STIX Two Math). Loaded once.
    public static FontMetrics bundled() {
        return Holder.BUNDLED;
    }

    /// Advance width of a single code point at the given pixel size.
    public double advance(int codePoint, double fontSizePx) {
        return sfnt.advanceWidth(sfnt.glyphId(codePoint)) * fontSizePx / unitsPerEm;
    }

    /// Width of a single (unwrapped) run of text at the given pixel size. Iterates by code
    /// point so astral characters (surrogate pairs) count once.
    public double runWidth(String text, double fontSizePx) {
        double w = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            w += advance(cp, fontSizePx);
            i += Character.charCount(cp);
        }
        return w;
    }

    /// The default single-line height at the given pixel size (ascender − descender + lineGap).
    public double lineHeight(double fontSizePx) {
        return sfnt.lineHeightUnits() * fontSizePx / unitsPerEm;
    }

    /// Measure an unwrapped, single-line label.
    public TextBox measure(String text, double fontSizePx) {
        return new TextBox(runWidth(text, fontSizePx), lineHeight(fontSizePx), List.of(text));
    }

    /// Measure a label greedily word-wrapped to `maxWidthPx`. Breaks on ASCII spaces only; a
    /// single word wider than the limit gets its own line (no mid-word breaking in M0). A
    /// non-positive or infinite `maxWidthPx` means "no wrap" — one line.
    public TextBox measureWrapped(String text, double maxWidthPx, double fontSizePx) {
        if (maxWidthPx <= 0 || Double.isInfinite(maxWidthPx) || text.indexOf(' ') < 0) {
            return measure(text, fontSizePx);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        double lineWidth = 0;
        double spaceWidth = advance(' ', fontSizePx);
        for (String word : text.split(" ", -1)) {
            double wordWidth = runWidth(word, fontSizePx);
            if (line.isEmpty()) {
                line.append(word);
                lineWidth = wordWidth;
            } else if (lineWidth + spaceWidth + wordWidth <= maxWidthPx) {
                line.append(' ').append(word);
                lineWidth += spaceWidth + wordWidth;
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
                lineWidth = wordWidth;
            }
        }
        lines.add(line.toString());
        double widest = 0;
        for (String l : lines) {
            widest = Math.max(widest, runWidth(l, fontSizePx));
        }
        return new TextBox(widest, lines.size() * lineHeight(fontSizePx), lines);
    }
}
