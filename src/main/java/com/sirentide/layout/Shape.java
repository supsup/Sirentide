package com.sirentide.layout;

/// A laid-out drawable primitive: pure geometry + fill, produced by layout, serialized by emit
/// (docs/DESIGN.md §4 — the layout/emit split). Sealed so the emitter's `switch` is exhaustive
/// and every shape maps to exactly one contract element. New primitives (Rect, Line, GlyphRun)
/// join as permitted records per milestone. {@link Group} is the ONE non-primitive: a semantic
/// wrapper carrying an {@link Anchor} around a run of leaf shapes (plan sirentide-semantic-anchor-g),
/// emitted as a `<g data-sirentide-*>` — additive only, never a geometry change.
public sealed interface Shape permits Wedge, GlyphRun, Rect, Line, Path, MathBox, Group {}
