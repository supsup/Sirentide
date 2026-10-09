package com.sirentide.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// A continuous-score grid: rows × columns of 0..1 magnitudes, each cell filled from a SINGLE-HUE
/// sequential ramp (light→dark) in {@code HeatmapLayout} — the case × arm × score-intensity surface
/// the continuity-eval's run data needed and neither {@link Matrix} (discrete verdict cells) nor
/// {@link XyChart} (one magnitude axis) could draw (plan sirentide-heatmap-type). Grammar, caps,
/// rectangularization, and per-cell anchors deliberately mirror {@link Matrix}; the ONLY new
/// dimension is that a cell's value is continuous, so its fill is interpolated, not vocabulary-picked.
///
/// The grammar (own-DSL, matrix-parity):
/// {@snippet :
///   heatmap
///   cols: bare, snapshot, card
///   scale: "diverged" --> "reproduced"
///   "values-boundary" : 0.60, 0.75, 0.95
///   "technique" : -, 40%, warm:0.8
/// }
/// `cols:`/`columns:` names the M column headers; every following `"label" : v1, v2, …` is a row.
/// A cell token is a decimal (`0.6`, `.6`, `1`) or percent (`86%`) clamped to [0,1]; `text:value`
/// (split on the LAST colon, like matrix) shows {@code text} on the value's fill; blank/`-`/`na`/
/// non-numeric → NA (neutral fill, no colour, never throws). `scale:` optionally names the legend's
/// low/high ends via the quadrant `-->` axis-end grammar; absent ends default to "0" / "1".
/// A row is padded/truncated to exactly M cells so the grid is rectangular.
///
/// ## Extensions (plan f4d69e44: outlined cells, categorical palette, custom ramp + bins, hidden headers)
///
/// Every extension is opt-in by a directive or a token suffix; a source that uses none of them
/// renders byte-identically to the pre-extension heatmap.
/// {@snippet :
///   heatmap
///   cols: L0, L1, L2
///   palette: C1 #4e79a7, C2 #f28e2b, "shared" #bab0ac, C4
///   hide: rows, cols
///   "H0" : :C1, :C2!, shared
/// }
/// - **Outline** — a cell token ending in `!` (`0.6!`, `4:0.125!`, `:C1!`, a bare `!`) draws an
///   outline around that cell; the `!` is stripped before the rest of the token is read, so the
///   value and shown text are unchanged. The outline is drawn on the cell's own fill rect, inset so
///   it covers exactly the plain cell's area, in the cell label's contrast colour (black on light
///   fills, white on dark), so it never vanishes into the fill.
/// - **`palette:`** — switches the heatmap to CATEGORICAL mode: a cell's value is a category NAME,
///   not a magnitude. Entries are comma-separated `name [colour]`; the comma split honours double
///   quotes, so `"A, B" #ff0000` is ONE category named `A, B` (and a quoted `"A, B"` cell names it).
///   The colour is HEX-ONLY via `SirentideContract.isHexColor`, `#rgb` canonicalized to `#rrggbb`.
///   **Name/colour rule:** a quoted name is everything inside its quotes and whatever follows is the
///   colour; in an UNQUOTED entry of two or more whitespace tokens the LAST token is ALWAYS the
///   colour and the rest is the name. So `C2 red` is category `C2` with an invalid colour (never a
///   category named "C2 red"), `big win #ff0000` is category `big win`, and a multi-word name with
///   NO colour must be quoted (`"big win"`): unquoted, `big win` is category `big` with the invalid
///   colour `win`. A missing colour is legitimate and takes the default categorical palette colour
///   at that entry's index; an INVALID one takes the same default AND is reported as a line-scoped
///   caveat, so a hostile colour token never reaches the output and never passes silently. A cell
///   token (or the value part of `text:value`, unquoted) that names a category gets that
///   category's fill; anything else — including a number — is NA (neutral), reported as a caveat
///   unless it is blank, `-` or `na`. `:C1` shows no text. The legend becomes one swatch + name per
///   category, and `ramp:`/`bins:` are ignored. A nameless entry, a duplicate name (the first
///   wins) and entries past 64 are skipped with a caveat.
/// - **`ramp:`** — 2..16 comma-separated hex stops, evenly spaced over 0..1 and
///   interpolated piecewise-linearly, replacing the default blue ramp (and its legend). Invalid stops
///   and stops past the 16th are dropped; fewer than two valid stops keep the default ramp; each of
///   those is a caveat. The continuous legend samples 12 steps, or 2 per segment for a ramp of more
///   than 7 stops (16 stops → 30), so every stop shows.
/// - **`bins:`** — stepped fills. `bins: N` (one integer, 2..64) makes N equal bins; otherwise the
///   tokens are interior THRESHOLDS (decimal or `NN%`, strictly inside (0,1), sorted + deduplicated):
///   `bins: 0.25, 0.5` is three bins. A value ON a threshold falls into the upper bin. Bin k of B is
///   filled with the ramp at k/(B-1), so the first and last bins carry the ramp's true ends, and the
///   legend shows B steps whose widths are proportional to the bins' value widths. A rejected token
///   (a count outside 2..64, a cut at or outside 0 or 1, a non-number) is a caveat; unsorted or
///   repeated cut points are sorted and deduplicated SILENTLY, because the bins are the same.
/// - **`hide:`** — `rows` (aliases `row`, `labels`) drops the row-label column; `cols` (aliases
///   `col`, `columns`, `header`, `headers`) drops the header band; `both`/`all` drops both. Hidden
///   headers still count columns (`cols:` still rectangularizes) and still reach the a11y text. An
///   unknown target is ignored with a caveat.
///
/// Every caveat above rides the render's existing OK-caveat channel (`Diagnostics.detail()`, as
/// `line N: <directive>: <what was rejected>`), so `sirentide render <file.md>` prints it and
/// `--strict` exits 1 on it. The fallback SVG is unchanged by the caveat; valid input has none.
///
/// Directives are recognized only on an UNQUOTED line start (like `cols:`/`scale:`); a row whose
/// label is the word `palette`, `ramp`, `bins` or `hide` must be quoted to stay a row.
public record Heatmap(List<String> columns, List<Row> rows, String textColor,
                      String lowLabel, String highLabel, List<Category> palette,
                      List<String> ramp, List<Double> thresholds,
                      boolean hideRowLabels, boolean hideColumnHeaders) implements Diagram {

    public Heatmap {
        columns = snapshot(columns);
        rows = snapshot(rows);
        palette = snapshot(palette);
        ramp = snapshot(ramp);
        thresholds = snapshot(thresholds);
    }

    /// The pre-extension shape: magnitude mode, default ramp, no bins, headers shown.
    public Heatmap(List<String> columns, List<Row> rows, String textColor,
                   String lowLabel, String highLabel) {
        this(columns, rows, textColor, lowLabel, highLabel, List.of(), List.of(), List.of(), false, false);
    }

    /// True when a `palette:` made this a categorical heatmap (cell values are category names).
    public boolean categorical() {
        return palette != null && !palette.isEmpty();
    }

    /// One categorical palette entry: the display name cells match against, and its canonical
    /// `#rrggbb` fill — or null, meaning "the default categorical colour at this entry's index".
    public record Category(String name, String color) {}

    /// One row: a left-aligned label plus exactly {@code columns.size()} value cells.
    public record Row(String label, List<Cell> cells) {

        public Row {
            cells = snapshot(cells);
        }
    }

    private static <T> List<T> snapshot(List<T> source) {
        return source == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(source));
    }

    /// One cell: the token as authored (shown centered), the clamped 0..1 magnitude driving its
    /// fill, and the NA flag (an NA cell's {@code value} is 0 by convention and never reaches the
    /// ramp — the flag, not the number, is the discriminator, so an authored literal `0` stays a
    /// real coldest-ramp value while `-` stays neutral).
    ///
    /// `category` is the matched palette entry's name in categorical mode (null otherwise, and null
    /// for an unmatched categorical cell, which is NA); `outlined` is the `!` suffix.
    public record Cell(String text, double value, boolean na, String category, boolean outlined) {

        /// A magnitude cell with no outline — the pre-extension shape.
        public Cell(String text, double value, boolean na) {
            this(text, value, na, null, false);
        }
    }
}
