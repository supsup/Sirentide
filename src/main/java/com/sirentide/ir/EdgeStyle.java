package com.sirentide.ir;

/// The stroke STYLE of a flowchart edge line, orthogonal to whether it carries an arrowhead
/// ({@link FlowEdge#arrow()}). Maps from the DSL edge operator (see the flowchart parser):
///   • SOLID  — a single continuous line (`-->` solid arrow, `---` open link).
///   • DOTTED — a dashed line drawn as short deterministic segments, since the `<line>` output
///     contract has no `stroke-dasharray` (`-.->` dotted arrow, `-.-` dotted open).
///   • THICK  — a wider-stroke continuous line (`==>` thick arrow, `===` thick open).
/// SOLID is the default so the pre-existing `-->` edge is byte-identical.
public enum EdgeStyle {
    SOLID,
    DOTTED,
    THICK
}
