package com.sirentide.ir;

/// One weighted flow in a {@link Sankey} diagram: a `value` worth of quantity moving from the
/// `source` node to the `target` node. Parsed from a CSV-ish `source,target,value` body row; the
/// node set is DERIVED from the flows (a node is any string that appears as a source or a target),
/// so the IR carries only the flows. A flow's `value` is the band WIDTH driver at layout time.
///
/// The parser guarantees (docs/DESIGN.md §6, never fail the bake): `source`/`target` are non-empty,
/// `value` is finite and strictly positive, and `source != target` (a self-flow is dropped). So a
/// {@link SankeyLayout} can trust these without re-validating. `source`/`target` are `cap()`'d labels.
public record SankeyFlow(String source, String target, double value) {}
