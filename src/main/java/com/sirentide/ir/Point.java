package com.sirentide.ir;

/// A single plotted point in a {@link QuadrantChart}: a label plus its `(x,y)` position in the
/// unit square `[0,1]×[0,1]` (x → horizontal `Low..High`, y → vertical `Low..High`, y measured
/// UP). The parser clamps out-of-range coordinates into the unit square before construction (a
/// malformed point is dropped, never thrown — the inert-shell invariant, docs/DESIGN.md §6), so a
/// laid-out point always sits inside the plot square.
public record Point(String label, double x, double y) {}
