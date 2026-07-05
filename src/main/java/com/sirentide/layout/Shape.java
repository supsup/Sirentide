package com.sirentide.layout;

/// A laid-out drawable primitive: pure geometry + fill, produced by layout, serialized by emit
/// (docs/DESIGN.md §4 — the layout/emit split). Sealed so the emitter's `switch` is exhaustive
/// and every shape maps to exactly one contract element. New primitives (Rect, Line, GlyphRun)
/// join as permitted records per milestone.
public sealed interface Shape permits Wedge, GlyphRun {}
