package com.sirentide.ir;

import java.util.List;

/// A categorical comparison / verdict matrix: rows × columns of small fixed-vocabulary verdicts, the
/// docs-audit "N items × M arms" house style (plan sirentide-comparison-matrix-type) that no other
/// closed Sirentide type expressed. Each {@link Cell} carries a display token plus a normalized
/// {@link Verdict} that maps to the (sanitizer-safe `#rrggbb`) cell palette in
/// {@code MatrixLayout}. Layout dispatch is `case Matrix mx -> MatrixLayout.layout(mx, math)`.
///
/// The grammar (own-DSL, not mermaid — mermaid has no matrix type):
/// {@snippet :
///   matrix
///   cols: snapshot, bare
///   "ID1 claim-on-no-signal" : pass, pass
///   "PC1 soft-intent" : partial, fail
/// }
/// `cols:` names the M column headers; every following `"label" : v1, v2, …` is a row whose cells are
/// verdict tokens (pass/fail/partial/na, plus the aliases match/yes→pass, diverge/no→fail, ~→partial,
/// blank/-/na→na). A row is padded/truncated to exactly M cells so the grid is rectangular.
public record Matrix(List<String> columns, List<Row> rows, String textColor) implements Diagram {

    /// One row: a left-aligned label plus exactly {@code columns.size()} verdict cells.
    public record Row(String label, List<Cell> cells) {}

    /// One cell: the token as authored (shown centered) plus the normalized verdict driving its fill.
    public record Cell(String text, Verdict verdict) {}

    /// The closed cell vocabulary — the only values that reach the palette (value-constrained, like
    /// the flowchart colour gate), so no free-form colour is ever introduced.
    public enum Verdict { PASS, FAIL, PARTIAL, NA }
}
