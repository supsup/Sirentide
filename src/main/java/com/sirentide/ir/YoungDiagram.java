package com.sirentide.ir;

import java.util.List;

/// A Young diagram (plan sirentide-young-diagram-primitive). A partition `λ = [λ0, λ1, …]` — a
/// WEAKLY-DECREASING list of POSITIVE integers — renders (the ENGLISH convention) as left-justified
/// rows of unit boxes: the LONGEST row on top, each row left-aligned, stacked DOWNWARD. So
/// `young([3, 2, 1])` is a staircase (3 boxes, then 2, then 1); `young([4, 4, 2])` is rows of 4, 4, 2.
/// The total number of boxes equals the SUM of the parts (the "size" |λ| of the partition).
///
/// INVARIANT (established at the parse boundary — see {@code DslParser.parseYoung}, and mirrored by
/// {@link com.sirentide.layout.YoungDiagramLayout}): `rows` is already normalized to weakly-decreasing
/// order — a non-decreasing authored list is SORTED DESCENDING (documented degrade, never a throw), and
/// each part is positive and cap-clamped, the row COUNT cap-bounded. An empty list (a bare `young` with
/// no `rows:`) is valid and bakes an empty canvas.
///
/// `textColor` is unused by the current box-only layout (a Young diagram carries no labels) but is
/// threaded for parity with every other type and future cell labels (hook lengths, tableau entries); it
/// defaults to `currentColor`.
public record YoungDiagram(List<Integer> rows, String textColor) implements Diagram {

    public YoungDiagram {
        rows = List.copyOf(rows);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
