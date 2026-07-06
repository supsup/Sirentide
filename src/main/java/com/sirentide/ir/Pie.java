package com.sirentide.ir;

import java.util.List;

/// A pie chart: proportional slices. The cheapest diagram to lay out — pure angular arithmetic,
/// zero graph optimization (docs/DESIGN.md §5/§6), which is why it is the M1 seam-prover.
public record Pie(List<Slice> slices) implements Diagram {

    public Pie {
        slices = List.copyOf(slices);
    }

    /// Sum of ALL slice values (signed). NOT the angular denominator — a negative value here would
    /// shrink the denominator while the layout skips the negative slice, corrupting every other
    /// slice's sweep past 360°. Use {@link #positiveTotal} for angles.
    public double total() {
        double t = 0;
        for (Slice s : slices) {
            t += s.value();
        }
        return t;
    }

    /// Sum of POSITIVE magnitudes only — the correct angular denominator. A negative (or zero) slice
    /// is not drawn on the pie face and must not enter the denominator, or it inflates the other
    /// slices' angles past a full turn.
    public double positiveTotal() {
        double t = 0;
        for (Slice s : slices) {
            if (s.value() > 0) {
                t += s.value();
            }
        }
        return t;
    }
}
