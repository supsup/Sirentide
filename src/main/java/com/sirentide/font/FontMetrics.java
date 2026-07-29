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

    /// Distance the glyphs rise ABOVE the baseline at the given pixel size (font ascender), a
    /// positive number. Used to size a composite label around an inline-math fragment.
    public double ascent(double fontSizePx) {
        return sfnt.ascenderUnits() * fontSizePx / unitsPerEm;
    }

    /// Distance the glyphs fall BELOW the baseline at the given pixel size, a positive number
    /// (the `hhea` descender is negative; this returns its magnitude).
    public double descent(double fontSizePx) {
        return -sfnt.descenderUnits() * fontSizePx / unitsPerEm;
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

    /// Truncate `text` with a trailing ellipsis ("…") so it fits within `maxWidthPx`. If the text
    /// already fits (or the limit is non-positive/infinite) it is returned unchanged; if not even
    /// the ellipsis fits, returns "". This is the M1 label-overflow baseline — a long label that
    /// would run out of its region gets clipped legibly instead of overrunning its neighbours
    /// (docs/DESIGN.md §4; the built-but-unused wrap oracle wired in). Deterministic: a pure
    /// function of (text, width, size), so bakes stay byte-identical (§6).
    public String ellipsize(String text, double maxWidthPx, double fontSizePx) {
        if (maxWidthPx <= 0 || Double.isInfinite(maxWidthPx) || runWidth(text, fontSizePx) <= maxWidthPx) {
            return text;
        }
        String ellipsis = "…";
        double budget = maxWidthPx - runWidth(ellipsis, fontSizePx);
        if (budget < 0) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        double w = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            double cw = advance(cp, fontSizePx);
            if (w + cw > budget) {
                break;
            }
            kept.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        return kept.append(ellipsis).toString();
    }

    // -- text as paths (glyph outlines) -----------------------------------------

    /// Render a run of text as an SVG path `d` string: each glyph's outline positioned along the
    /// baseline, scaled to `fontSizePx`, and flipped into SVG's y-down space (font design coords
    /// are y-up). The caller wraps it in `<path fill="…">`. This is text=paths (docs/DESIGN.md
    /// §4/§6) — deterministic, no `<text>`, no view-time font dependency. `originX`/`baselineY`
    /// are the pen start in user (pixel) coordinates.
    public String textPathD(String text, double originX, double baselineY, double fontSizePx) {
        guardPlainGlyphs(text);
        double scale = fontSizePx / unitsPerEm;
        StringBuilder d = new StringBuilder();
        double penX = originX;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int gid = sfnt.glyphId(cp);
            appendGlyph(d, sfnt.glyphContours(gid), penX, baselineY, scale);
            penX += sfnt.advanceWidth(gid) * scale;
        }
        return d.toString().trim();
    }

    /// THE AUTHORITATIVE TAG GATE. Every string that becomes plain glyph contours passes here
    /// first, after ellipsization, wrapping, synthesis and every other transformation, and
    /// immediately before any contour is appended.
    ///
    /// ## Why the gate is here and not on a surface map
    ///
    /// The earlier design classified SURFACES up front — this label is math-aware, that one is
    /// plain — and validated the SOURCE label. Marlow broke that four times running
    /// (sirentide/676, 680, 685, 693, 697, 699, and three more at 701), and the last one showed
    /// the design was wrong rather than incomplete: Class/ER **ellipsize the label and only then
    /// ask whether it has math**, so the string validation approved is not the string the
    /// emitter renders. A legal complete `$…$` run is exempted, loses its closing delimiter to
    /// the ellipsis, keeps the complete tag, and is emitted as plain glyphs. That is a
    /// time-of-check/time-of-use gap, and no amount of better classification closes it.
    ///
    /// The census that settled it: 98 plain-glyph emission call sites across 19 layout files.
    /// With ~2 findings per review round, patching enumerated paths does not converge.
    ///
    /// Here there is nothing between the check and the use. Ruling: Marlow, sirentide/703.
    ///
    /// ## No math-aware exemption, deliberately
    ///
    /// `offendingTag(text, false)` — at this point the string is DEFINITIVELY being emitted as
    /// plain glyphs, whatever the surface once claimed. Successful math fragments never reach
    /// here (they go through `FragmentGuard`); literal runs from `MathLabel.emit` and failed
    /// math that degraded to glyphs DO reach here, and must be checked as plain. Applying a
    /// math exemption at the emission point would reintroduce the dollar-wrap bypass at the one
    /// place it is provably wrong.
    ///
    /// ## No allowlist
    ///
    /// Every string arriving here is treated as plain and validated. A safe unknown string
    /// renders; a tag-shaped literal refuses. Identity falls back to `plain-glyph` when the
    /// caller carried none — incomplete attribution must not become permission.
    private static void guardPlainGlyphs(String text) {
        String tag = com.sirentide.parse.LabelMarkup.offendingTag(text, false);
        if (tag != null) {
            // Same typed exception as the early pass, so `renderWithDiagnostics` classifies it
            // as PARSE_ERROR at stage `parse` on the exception TYPE rather than on where it was
            // thrown. Discovery during layout must not reclassify this as an ordinary layout
            // failure (Marlow, sirentide/703).
            throw new com.sirentide.parse.LabelMarkupException("plain-glyph", tag);
        }
    }

    /// Appends one glyph's contours as transformed path commands — the TrueType quadratic
    /// reconstruction (a held off-curve control point; an implied on-curve midpoint between two
    /// consecutive off-curve points), each point mapped into user space.
    private void appendGlyph(StringBuilder d, List<Contour> contours,
                             double penX, double baselineY, double scale) {
        for (Contour contour : contours) {
            List<GlyphPoint> pts = contour.points();
            int n = pts.size();
            if (n == 0) {
                continue;
            }
            int startIdx = firstOnCurve(pts);
            if (startIdx < 0) {
                appendAllOffCurve(d, pts, penX, baselineY, scale);
                continue;
            }
            GlyphPoint start = pts.get(startIdx);
            cmd(d, 'M', start, penX, baselineY, scale);
            GlyphPoint pending = null;   // a held off-curve control point
            for (int i = 1; i <= n; i++) {
                GlyphPoint cur = pts.get((startIdx + i) % n);
                if (cur.onCurve()) {
                    if (pending == null) {
                        cmd(d, 'L', cur, penX, baselineY, scale);
                    } else {
                        quad(d, pending, cur, penX, baselineY, scale);
                        pending = null;
                    }
                } else if (pending == null) {
                    pending = cur;
                } else {
                    quad(d, pending, midpoint(pending, cur), penX, baselineY, scale);
                    pending = cur;
                }
            }
            if (pending != null) {
                quad(d, pending, start, penX, baselineY, scale);
            }
            d.append("Z ");
        }
    }

    private void appendAllOffCurve(StringBuilder d, List<GlyphPoint> pts,
                                   double penX, double baselineY, double scale) {
        int n = pts.size();
        cmd(d, 'M', midpoint(pts.get(0), pts.get(n - 1)), penX, baselineY, scale);
        for (int i = 0; i < n; i++) {
            GlyphPoint control = pts.get(i);
            quad(d, control, midpoint(control, pts.get((i + 1) % n)), penX, baselineY, scale);
        }
        d.append("Z ");
    }

    private void cmd(StringBuilder d, char op, GlyphPoint p, double penX, double baselineY, double scale) {
        d.append(op).append(' ').append(fmt(penX + p.x() * scale))
            .append(' ').append(fmt(baselineY - p.y() * scale)).append(' ');
    }

    private void quad(StringBuilder d, GlyphPoint c, GlyphPoint e,
                      double penX, double baselineY, double scale) {
        d.append("Q ").append(fmt(penX + c.x() * scale)).append(' ').append(fmt(baselineY - c.y() * scale))
            .append(' ').append(fmt(penX + e.x() * scale)).append(' ').append(fmt(baselineY - e.y() * scale))
            .append(' ');
    }

    private static GlyphPoint midpoint(GlyphPoint a, GlyphPoint b) {
        return new GlyphPoint((a.x() + b.x()) / 2, (a.y() + b.y()) / 2, true);
    }

    private static int firstOnCurve(List<GlyphPoint> pts) {
        for (int i = 0; i < pts.size(); i++) {
            if (pts.get(i).onCurve()) {
                return i;
            }
        }
        return -1;
    }

    private static String fmt(double v) {
        double r = Math.round(v * 100.0) / 100.0;   // 2-dp, deterministic (byte-identical bakes)
        return r == Math.rint(r) && !Double.isInfinite(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
