package com.sirentide.layout;

import com.sirentide.ir.Pie;
import com.sirentide.ir.Slice;
import java.util.ArrayList;
import java.util.List;

/// Pure pie layout: slice magnitudes → angular {@link Wedge}s. Deterministic arithmetic, zero
/// graph optimization (docs/DESIGN.md §6). Starts at 12 o'clock and sweeps clockwise. Colours
/// come from a small fixed palette, assigned by slice order (deterministic). Labels
/// (text-as-paths) arrive once the glyph-outline reader lands — M0 draws the wedges.
public final class PieLayout {

    private PieLayout() {}

    private static final double SIZE = 240;
    private static final double RADIUS = 100;

    /// A small fixed palette of contract-clean hex fills (mid-tone, readable on light or dark).
    private static final String[] PALETTE = {
        "#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2",
        "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac"
    };

    public static LaidOut layout(Pie pie) {
        double total = pie.total();
        double cx = SIZE / 2;
        double cy = SIZE / 2;
        if (total <= 0) {
            return LaidOut.of(SIZE, SIZE);
        }
        List<Shape> shapes = new ArrayList<>();
        double angle = -Math.PI / 2; // 12 o'clock
        List<Slice> slices = pie.slices();
        for (int i = 0; i < slices.size(); i++) {
            double value = slices.get(i).value();
            if (value <= 0) {
                continue;
            }
            double next = angle + (value / total) * 2 * Math.PI;
            shapes.add(new Wedge(cx, cy, RADIUS, angle, next, PALETTE[i % PALETTE.length]));
            angle = next;
        }
        return new LaidOut(SIZE, SIZE, shapes);
    }
}
