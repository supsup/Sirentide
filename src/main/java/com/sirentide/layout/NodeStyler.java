package com.sirentide.layout;

import java.util.List;

/// The node-presentation seam that lets {@link FlowchartLayout}'s layered-graph engine be REUSED by
/// a second diagram type (the state diagram) WITHOUT forking it. The engine owns everything up to and
/// including node coordinates (layering, cycle handling, edge routing, labels); only the final "how a
/// node is drawn at (x,y,w,h)" step is parameterized through here.
///
/// The default flowchart styler ({@link FlowchartLayout#DEFAULT_STYLER}) emits a rect (or a diamond
/// `<path>` for a decision node) — byte-for-byte the code that used to be inline, so every flowchart
/// golden is unchanged. The state styler ({@link StateDiagramLayout}) reskins the same boxes: normal
/// states stay rects, but the `start`/`end` pseudostates become discs/bullseyes.
///
/// `i` is the node index (unused by the current stylers but carried for a future per-node style map);
/// `shape` is the {@link com.sirentide.ir.FlowNode} shape string; `fill` is the engine's default node
/// fill (a styler may honour or ignore it).
interface NodeStyler {
    void emitNode(List<Shape> shapes, int i, double x, double y, double w, double h,
                  String shape, String fill);
}
