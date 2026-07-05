package com.sirentide.ir;

import java.util.List;

/// A pie chart: proportional slices. The cheapest diagram to lay out — pure angular arithmetic,
/// zero graph optimization (docs/DESIGN.md §5/§6), which is why it is the M1 seam-prover.
public record Pie(List<Slice> slices) implements Diagram {

    public Pie {
        slices = List.copyOf(slices);
    }

    /// Sum of slice magnitudes (the denominator for each slice's angular fraction).
    public double total() {
        double t = 0;
        for (Slice s : slices) {
            t += s.value();
        }
        return t;
    }
}
