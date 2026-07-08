package com.sirentide.ir;

/// The five UML class-diagram relationship kinds, each with its own marker GLYPH at a specific end
/// (the fidelity crux of a class diagram — a wrong marker reads as "broken"). The `atLeft` flag says
/// which operand carries the marker: the whole/parent-side kinds mark the LEFT operand, the
/// arrow-side kinds mark the RIGHT operand. `dashed` is true only for a dependency (its edge line is
/// dashed). The DSL operator that mints each kind is noted.
///
///   - `INHERITANCE` (`<|--`) — a hollow TRIANGLE at the parent (LEFT) end (generalization).
///   - `COMPOSITION` (`*--`)  — a FILLED diamond at the whole (LEFT) end.
///   - `AGGREGATION` (`o--`)  — a HOLLOW diamond at the whole (LEFT) end.
///   - `ASSOCIATION` (`-->`)  — an open ARROW at the target (RIGHT) end.
///   - `DEPENDENCY`  (`..>`)  — a DASHED line + open arrow at the target (RIGHT) end.
public enum RelationKind {
    INHERITANCE(true, false),
    COMPOSITION(true, false),
    AGGREGATION(true, false),
    ASSOCIATION(false, false),
    DEPENDENCY(false, true);

    private final boolean atLeft;
    private final boolean dashed;

    RelationKind(boolean atLeft, boolean dashed) {
        this.atLeft = atLeft;
        this.dashed = dashed;
    }

    /// True iff the marker sits at the LEFT operand (whole/parent kinds); false → the RIGHT operand.
    public boolean markerAtLeft() {
        return atLeft;
    }

    /// True iff the edge line is drawn dashed (dependency only).
    public boolean dashed() {
        return dashed;
    }
}
