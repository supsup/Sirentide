package com.sirentide.layout;

/// Pure layout output: coordinates only, never SVG (docs/DESIGN.md §4 — the layout/emit split).
/// M0 skeleton: just the canvas size. M1 adds the placed elements (with positions + measured
/// label boxes from the font-metrics oracle).
public record LaidOut(double width, double height) {

    public static LaidOut of(double width, double height) {
        return new LaidOut(width, height);
    }
}
