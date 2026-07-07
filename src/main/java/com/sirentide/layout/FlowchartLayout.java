package com.sirentide.layout;

import com.sirentide.font.FontMetrics;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.Flowchart;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Pure flowchart layout: a layered top-down (TD) drawing of a directed graph. The first diagram
/// type with real graph structure, so this is a genuine layout engine (not the earlier types' pure
/// arithmetic): a cycle-safe longest-path layering (docs/DESIGN.md §5/§6).
///
/// Robustness is the point (DESIGN §6 — never throw, always terminate). Back-edges (an edge that
/// would close a cycle) are detected by a DFS and EXCLUDED from layering but STILL DRAWN, so a
/// cyclic graph lays out in bounded work instead of looping forever.
///
/// M1 scope: TD only, straight edges, rectangular nodes. FOLLOW-UP: `LR` geometry, orthogonal /
/// routed edges, node-shape variants, and crossing-minimization within a layer.
public final class FlowchartLayout {

    private FlowchartLayout() {}

    private static final double MARGIN = 24;
    private static final double NODE_H = 36;
    private static final double LAYER_GAP = 48;    // vertical gap between layers
    private static final double NODE_GAP = 28;     // horizontal gap between nodes in a layer
    private static final double PAD_X = 14;         // horizontal padding inside a node box
    private static final double MIN_BOX_W = 44;
    private static final double MAX_LABEL_W = 180;  // labels ellipsize past this
    private static final double MIN_W = 120;        // minimal blank-canvas width (0 nodes)
    private static final double MIN_H = 60;

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final double LABEL_SIZE = 12;

    private static final String NODE_FILL = "#dbe4ff";
    private static final String EDGE_STROKE = "#94a3b8";
    private static final String ARROW_FILL = "#94a3b8";
    private static final double EDGE_WIDTH = 1.5;
    private static final double ARROW_LEN = 10;     // arrowhead length (px back from the dst anchor)
    private static final double ARROW_HALF_W = 3.5; // arrowhead half-width (perpendicular)
    private static final double BACK_LANE_GAP = 18; // spacing between right-side back-edge lanes (M1.1)
    private static final double EDGE_LABEL_SIZE = 10;   // edge-label font size (M1.2, `-->|yes|`)
    private static final double MAX_EDGE_LABEL_W = 120; // edge labels ellipsize past this width
    private static final double EDGE_LABEL_GAP = 5;     // gap between an edge line and its label
    private static final double CLAMP_MARGIN = 2;       // min gap kept between a glyph box and the canvas edge

    /// One laid-out edge: endpoint node indices `u`→`v`, the (already-ellipsized) `label` (`null`
    /// when unlabeled), and `dataIdx` — the edge's index into {@code fc.edges()} BEFORE the
    /// unknown-endpoint filter, so downstream (Confluence fx-readiness) can map a drawn edge back to
    /// its source authoring row. Replaces the old parallel `List<int[]>` + `List<String>` pair.
    private record Edge(int u, int v, String label, int dataIdx) {}

    /// Shared in-frame clamp: keep a label's whole glyph box in [CLAMP_MARGIN, canvasW-CLAMP_MARGIN-w].
    /// The outside-of-edge origin rule keeps its natural value; the clamp only engages at the boundary
    /// (GEOMETRY-ESCAPE #3: a left-going forward edge subtracting the full label width went negative).
    private static double clampLabelX(double x, double w, double canvasW) {
        return Math.max(CLAMP_MARGIN, Math.min(x, canvasW - CLAMP_MARGIN - w));
    }

    /// Bound an edge label to the CANVAS width before placing it. Parse-side ellipsization caps the
    /// label at MAX_EDGE_LABEL_W — a canvas-INDEPENDENT bound — so when the canvas is NARROWER than the
    /// label (two tiny nodes + a long `-->|label|`), the label is still wider than canvasW-2·CLAMP.
    /// Then {@link #clampLabelX}'s [CLAMP, canvasW-CLAMP-w] interval INVERTS (max < min) and pins x to
    /// CLAMP with w unchanged → x+w escapes off the right edge (the TD clamp-floor regression). Fix:
    /// re-ellipsize to the canvas-relative bound (`min(MAX_EDGE_LABEL_W, canvasW-2·CLAMP)`, since the
    /// label already ≤ MAX_EDGE_LABEL_W) so the width itself fits before clamping. May return "" when
    /// even an ellipsis won't fit a hair-thin canvas — the caller then skips the glyph (edge still
    /// draws). Shared by TD + LR, forward + back edges.
    private static String boundLabelToCanvas(String label, double canvasW) {
        return FONT.ellipsize(label, canvasW - 2 * CLAMP_MARGIN, EDGE_LABEL_SIZE);
    }

    /// The default node styler: a rect box, or a diamond `<path>` for a `{Label}` decision node —
    /// byte-for-byte the emission that used to be inline in both the TD and LR passes, so every
    /// flowchart golden is unchanged by the styler seam. State diagrams pass a different styler
    /// ({@link StateDiagramLayout}) to reskin the SAME boxes (discs for pseudostates) without forking
    /// this engine.
    static final NodeStyler DEFAULT_STYLER = (shapes, i, x, y, w, h, shape, fill) -> {
        if ("diamond".equals(shape)) {
            double cx = x + w / 2;
            double cy = y + h / 2;
            String d = "M " + fmt(cx) + " " + fmt(y)
                + " L " + fmt(x + w) + " " + fmt(cy)
                + " L " + fmt(cx) + " " + fmt(y + h)
                + " L " + fmt(x) + " " + fmt(cy)
                + " Z";
            shapes.add(new Path(d, fill));
        } else {
            shapes.add(new Rect(x, y, w, h, fill));
        }
    };

    public static LaidOut layout(Flowchart fc) {
        return layout(fc, DEFAULT_STYLER);
    }

    /// The parameterized engine: identical layered-graph layout for every caller, with only the
    /// final node-drawing step delegated to `styler` (DESIGN §5 — one graph engine, two presentations).
    /// Package-private so a sibling layout (state diagram) can drive it without exposing the seam.
    static LaidOut layout(Flowchart fc, NodeStyler styler) {
        List<FlowNode> nodes = fc.nodes();
        int n = nodes.size();
        // 0 nodes → a small blank-but-valid canvas (a bare `flowchart` still round-trips as one).
        if (n == 0) {
            return LaidOut.of(MIN_W, MIN_H);
        }

        // id → index (first-seen order). A duplicate id can't occur — the parser deduped on it.
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(nodes.get(i).id(), i);
        }

        // Edge endpoints as indices, label + source-row index carried in the Edge record; drop any
        // edge whose endpoint isn't a known node (defensive — the parser already guards this, but
        // layout must tolerate a stray reference, never throw). dataIdx is the PRE-filter index.
        List<Edge> edges = new ArrayList<>();
        List<FlowEdge> fcEdges = fc.edges();
        for (int di = 0; di < fcEdges.size(); di++) {
            FlowEdge e = fcEdges.get(di);
            Integer u = index.get(e.from());
            Integer v = index.get(e.to());
            if (u != null && v != null) {
                String lbl = e.label() == null ? null
                    : FONT.ellipsize(e.label(), MAX_EDGE_LABEL_W, EDGE_LABEL_SIZE);
                edges.add(new Edge(u, v, lbl, di));
            }
        }

        // -- back-edge detection (DFS): an edge to a node currently ON the recursion stack (GRAY) is
        // a back-edge → it closes a cycle. Excluded from layering (below) but still drawn. This is
        // what makes layering TERMINATE on ANY input: the graph fed to layering is a DAG.
        List<List<Integer>> adj = new ArrayList<>();   // per source: edge indices out of it
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (int ei = 0; ei < edges.size(); ei++) {
            adj.get(edges.get(ei).u()).add(ei);
        }
        boolean[] isBack = new boolean[edges.size()];
        int[] state = new int[n];   // 0=white(unseen), 1=gray(on stack), 2=black(done)
        for (int s = 0; s < n; s++) {
            if (state[s] == 0) {
                dfsClassify(s, edges, adj, state, isBack);
            }
        }

        // -- longest-path layering over the DAG (forward = non-back edges). layer(v)=0 if it has no
        // forward in-edge, else max(layer(u)+1) over forward predecessors. Memoized recursion over
        // predecessors terminates because the forward graph is acyclic (back-edges removed).
        List<List<Integer>> preds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            preds.add(new ArrayList<>());
        }
        for (int ei = 0; ei < edges.size(); ei++) {
            if (!isBack[ei]) {
                preds.get(edges.get(ei).v()).add(edges.get(ei).u());
            }
        }
        int[] layer = new int[n];
        Arrays.fill(layer, -1);
        int maxLayer = 0;
        for (int v = 0; v < n; v++) {
            int lv = layerOf(v, preds, layer);
            maxLayer = Math.max(maxLayer, lv);
        }
        int layerCount = maxLayer + 1;

        // Node box sizes (ellipsized label → width). Group node indices by layer, in first-seen order.
        String[] labels = new String[n];
        double[] boxW = new double[n];
        List<List<Integer>> byLayer = new ArrayList<>();
        for (int i = 0; i < layerCount; i++) {
            byLayer.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            String label = FONT.ellipsize(nodes.get(i).label(), MAX_LABEL_W, LABEL_SIZE);
            labels[i] = label;
            double lw = FONT.runWidth(label, LABEL_SIZE);
            // A DIAMOND (M1.3) must CONTAIN its centered label: with height fixed at NODE_H
            // (half-diagonal b = NODE_H/2) and the label box ~±6px tall, the rhombus containment
            // condition (w/2)/a + 6/b <= 1 needs a >= 0.75·labelW — so the diamond is 1.5× wider
            // than the text (+ padding). Its top/bottom/side VERTICES land exactly on the rect's
            // edge anchors, so edge routing needs no change at all.
            boolean diamond = "diamond".equals(nodes.get(i).shape());
            boxW[i] = Math.max(MIN_BOX_W, (diamond ? 1.5 * lw : lw) + 2 * PAD_X);
            byLayer.get(layer[i]).add(i);   // first-seen order preserved (i ascends)
        }

        // Resolve each node's BOX fill once (direction-independent): the author's per-node colour
        // wins, else the header `nodecolor=` default, else the built-in NODE_FILL. This fill is what
        // the box is drawn with AND what its label contrasts against — an author's dark box gets a
        // white label automatically (Colors.contrastFill), so a custom colour is always legible.
        String headerFill = fc.nodeColor();   // canonical #rrggbb or null (→ NODE_FILL)
        String[] nodeFill = new String[n];
        for (int i = 0; i < n; i++) {
            String perNode = nodes.get(i).color();
            nodeFill[i] = perNode != null ? perNode : (headerFill != null ? headerFill : NODE_FILL);
        }

        // Layering/box-sizing above is direction-INDEPENDENT. TD (below) draws layers as ROWS flowing
        // top→down; LR draws them as COLUMNS flowing left→right — a genuinely different coordinate +
        // emission pass (glyph paths can't be transposed after the fact), so it forks here. The TD
        // path stays byte-identical (all existing goldens unchanged).
        if ("LR".equals(fc.direction())) {
            return layoutLr(fc, nodes, n, layerCount, byLayer, boxW, labels, nodeFill, edges, isBack, styler);
        }

        // Canvas width = widest layer + margins.
        double maxLayerWidth = 0;
        for (List<Integer> row : byLayer) {
            double lw = 0;
            for (int idx = 0; idx < row.size(); idx++) {
                lw += boxW[row.get(idx)];
            }
            if (row.size() > 1) {
                lw += (row.size() - 1) * NODE_GAP;
            }
            maxLayerWidth = Math.max(maxLayerWidth, lw);
        }
        double contentW = maxLayerWidth + 2 * MARGIN;
        // Back-edges route through vertical LANES reserved to the RIGHT of the content (M1.1):
        // the straight up-line overlapped the forward chain, so a cycle looked like a plain chain.
        // One lane per back-edge, so multiple cycles don't overdraw each other.
        int backCount = 0;
        double maxBackLabelW = 0;   // a labeled back-edge's label sits RIGHT of its lane — widen for it
        for (int ei = 0; ei < isBack.length; ei++) {
            if (isBack[ei]) {
                backCount++;
                String bl = edges.get(ei).label();
                if (bl != null) {
                    maxBackLabelW = Math.max(maxBackLabelW,
                        FONT.runWidth(bl, EDGE_LABEL_SIZE) + EDGE_LABEL_GAP);
                }
            }
        }
        double canvasW = contentW + backCount * BACK_LANE_GAP + maxBackLabelW;
        double canvasH = layerCount * (NODE_H + LAYER_GAP) - LAYER_GAP + 2 * MARGIN;

        // Assign coordinates: each layer centered horizontally on the CONTENT width (the back-edge
        // lanes extend the canvas to the right without shifting the graph), laid left→right.
        double[] nx = new double[n];
        double[] ny = new double[n];
        for (int L = 0; L < layerCount; L++) {
            List<Integer> row = byLayer.get(L);
            double lw = 0;
            for (int idx : row) {
                lw += boxW[idx];
            }
            if (row.size() > 1) {
                lw += (row.size() - 1) * NODE_GAP;
            }
            double startX = (contentW - lw) / 2;
            double y = MARGIN + L * (NODE_H + LAYER_GAP);
            double cursor = startX;
            for (int idx : row) {
                nx[idx] = cursor;
                ny[idx] = y;
                cursor += boxW[idx] + NODE_GAP;
            }
        }

        // -- emit order matters (readability + the containment audit): edges UNDER nodes, then boxes,
        // then labels on top.
        List<Shape> shapes = new ArrayList<>();

        // 1) edges: forward = a straight line src-bottom-center → dst-top-center + a triangle
        // arrowhead (Path); BACK-edges = an orthogonal detour through a right-side lane (M1.1) —
        // out the source's right side, up the lane, back into the target's right side, so a cycle
        // reads as a visible loop instead of overdrawing the forward chain.
        int laneIdx = 0;
        for (int ei = 0; ei < edges.size(); ei++) {
            Edge e = edges.get(ei);
            int u = e.u();
            int v = e.v();
            if (isBack[ei]) {
                double laneX = contentW - MARGIN + BACK_LANE_GAP * (++laneIdx);
                double sy = ny[u] + NODE_H / 2;        // source right-middle
                double sx = nx[u] + boxW[u];
                double ty = ny[v] + NODE_H / 2;        // target right-middle
                double tx = nx[v] + boxW[v];
                shapes.add(new Line(sx, sy, laneX, sy, EDGE_STROKE, EDGE_WIDTH));      // out right
                shapes.add(new Line(laneX, sy, laneX, ty, EDGE_STROKE, EDGE_WIDTH));   // up the lane
                shapes.add(new Line(laneX, ty, tx + ARROW_LEN, ty, EDGE_STROKE, EDGE_WIDTH)); // back in
                // Left-pointing arrowhead, tip on the target's right edge.
                String bd = "M " + fmt(tx) + " " + fmt(ty)
                    + " L " + fmt(tx + ARROW_LEN) + " " + fmt(ty - ARROW_HALF_W)
                    + " L " + fmt(tx + ARROW_LEN) + " " + fmt(ty + ARROW_HALF_W)
                    + " Z";
                shapes.add(new Path(bd, ARROW_FILL));
                // Edge label (M1.2): beside the lane's vertical run (the canvas was widened for it).
                String bl = e.label() == null ? null : boundLabelToCanvas(e.label(), canvasW);
                if (bl != null && !bl.isBlank()) {
                    double lblY = (sy + ty) / 2 + EDGE_LABEL_SIZE * 0.35;
                    double lblX = clampLabelX(laneX + EDGE_LABEL_GAP,
                        FONT.runWidth(bl, EDGE_LABEL_SIZE), canvasW);
                    String ld = FONT.textPathD(bl, lblX, lblY, EDGE_LABEL_SIZE);
                    if (!ld.isBlank()) {
                        shapes.add(new GlyphRun(ld, fc.textColor()));
                    }
                }
                continue;
            }
            double scx = nx[u] + boxW[u] / 2;
            double sBottom = ny[u] + NODE_H;
            double dcx = nx[v] + boxW[v] / 2;
            double dTop = ny[v];
            double dx = dcx - scx;
            double dy = dTop - sBottom;
            double len = Math.hypot(dx, dy);
            if (!Double.isFinite(len) || len < 1e-6) {
                continue;   // degenerate anchor pair — skip (never emit NaN geometry)
            }
            double ux = dx / len;
            double uy = dy / len;
            // Arrowhead: tip at the dst anchor, base ARROW_LEN back along the edge, ARROW_HALF_W wide.
            double baseCx = dcx - ARROW_LEN * ux;
            double baseCy = dTop - ARROW_LEN * uy;
            double px = -uy;   // unit perpendicular
            double py = ux;
            // The line stops at the arrow base so it doesn't overshoot the filled triangle.
            shapes.add(new Line(scx, sBottom, baseCx, baseCy, EDGE_STROKE, EDGE_WIDTH));
            String d = "M " + fmt(dcx) + " " + fmt(dTop)
                + " L " + fmt(baseCx + ARROW_HALF_W * px) + " " + fmt(baseCy + ARROW_HALF_W * py)
                + " L " + fmt(baseCx - ARROW_HALF_W * px) + " " + fmt(baseCy - ARROW_HALF_W * py)
                + " Z";
            shapes.add(new Path(d, ARROW_FILL));
            // Edge label (M1.2): on the OUTSIDE of the edge at its midpoint — a right-going edge's
            // label sits right of the line, a left-going one's left of it (right-aligned). Keeps a
            // fan-out's labels ("yes"/"no", "approve"/"request changes") from colliding mid-canvas.
            String fl = e.label() == null ? null : boundLabelToCanvas(e.label(), canvasW);
            if (fl != null && !fl.isBlank()) {
                double flW = FONT.runWidth(fl, EDGE_LABEL_SIZE);
                double midX = (scx + baseCx) / 2;
                double lblX = dx >= 0
                    ? midX + EDGE_LABEL_GAP
                    : midX - EDGE_LABEL_GAP - flW;
                lblX = clampLabelX(lblX, flW, canvasW);
                double lblY = (sBottom + baseCy) / 2 + EDGE_LABEL_SIZE * 0.35;
                String ld = FONT.textPathD(fl, lblX, lblY, EDGE_LABEL_SIZE);
                if (!ld.isBlank()) {
                    shapes.add(new GlyphRun(ld, fc.textColor()));
                }
            }
        }

        // 2) node boxes via the STYLER seam (default = rect / diamond `<path>` for a decision node;
        // the four diamond vertices are the box's top/bottom-center + left/right-middle, i.e. exactly
        // the anchors the edges already attach to). A state diagram substitutes disc/bullseye discs
        // for its pseudostates here without any change to the layering/edge geometry above.
        for (int i = 0; i < n; i++) {
            styler.emitNode(shapes, i, nx[i], ny[i], boxW[i], NODE_H, nodes.get(i).shape(), nodeFill[i]);
        }

        // 3) centered labels (glyph paths — never <text>). A node label sits ON its box, so it fills
        // with the CONTRAST of the box colour (dark on a light box, white on a dark one) — never the
        // page-theme textColor, which vanished white-on-light under a dark theme. Edge labels (above)
        // keep textColor: they sit on the page background, not on a box.
        for (int i = 0; i < n; i++) {
            double cx = nx[i] + boxW[i] / 2;
            double baseline = ny[i] + NODE_H / 2 + LABEL_SIZE * 0.35;
            double w = FONT.runWidth(labels[i], LABEL_SIZE);
            String d = FONT.textPathD(labels[i], cx - w / 2, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, Colors.contrastFill(nodeFill[i])));
            }
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// LEFT-RIGHT geometry: layers become COLUMNS, flow runs left→right. A full mirror of the TD
    /// pass with the axes swapped — columns (not rows), vertical centering within the tallest column,
    /// forward edges out the RIGHT side into the next column's LEFT side, and back-edges detouring
    /// through horizontal lanes BELOW the content (mirror of TD's right-side vertical lanes). The
    /// diamond path is untouched: its left/right vertices already land on the LR side anchors.
    private static LaidOut layoutLr(Flowchart fc, List<FlowNode> nodes, int n, int layerCount,
                                    List<List<Integer>> byLayer, double[] boxW, String[] labels,
                                    String[] nodeFill, List<Edge> edges, boolean[] isBack,
                                    NodeStyler styler) {
        // -- columns: colW[L] = widest box in layer L; colX marches left→right by colW + LAYER_GAP.
        double[] colW = new double[layerCount];
        for (int L = 0; L < layerCount; L++) {
            double w = 0;
            for (int idx : byLayer.get(L)) {
                w = Math.max(w, boxW[idx]);
            }
            colW[L] = w;
        }
        double[] colX = new double[layerCount];
        double maxColumnStackH = 0;   // tallest column's stacked height → the vertical-centering datum
        for (int L = 0; L < layerCount; L++) {
            colX[L] = L == 0 ? MARGIN : colX[L - 1] + colW[L - 1] + LAYER_GAP;
            int cnt = byLayer.get(L).size();
            double stackH = cnt == 0 ? 0 : cnt * NODE_H + (cnt - 1) * NODE_GAP;
            maxColumnStackH = Math.max(maxColumnStackH, stackH);
        }
        double contentW = (layerCount == 0 ? MARGIN : colX[layerCount - 1] + colW[layerCount - 1]) + MARGIN;
        double contentH = maxColumnStackH + 2 * MARGIN;

        // Assign coordinates: within a column, nodes stack top→down in first-seen order, and the whole
        // stack is CENTERED VERTICALLY on the tallest column (mirror of TD's per-row horizontal
        // centering). Each node is centered horizontally within its column's width.
        double[] nx = new double[n];
        double[] ny = new double[n];
        for (int L = 0; L < layerCount; L++) {
            List<Integer> col = byLayer.get(L);
            int cnt = col.size();
            double stackH = cnt == 0 ? 0 : cnt * NODE_H + (cnt - 1) * NODE_GAP;
            double startY = (contentH - stackH) / 2;
            double cursor = startY;
            for (int idx : col) {
                nx[idx] = colX[L] + (colW[L] - boxW[idx]) / 2;
                ny[idx] = cursor;
                cursor += NODE_H + NODE_GAP;
            }
        }

        // Back-edges route through horizontal LANES reserved BELOW the content (mirror of TD's
        // right-side vertical lanes); a labeled back-edge's label sits just below its lane, so the
        // canvas grows DOWNWARD to fit the tallest such label.
        int backCount = 0;
        double maxBackLabelH = 0;
        for (int ei = 0; ei < isBack.length; ei++) {
            if (isBack[ei]) {
                backCount++;
                if (edges.get(ei).label() != null) {
                    maxBackLabelH = Math.max(maxBackLabelH, EDGE_LABEL_GAP + EDGE_LABEL_SIZE);
                }
            }
        }
        double canvasW = contentW;
        double canvasH = contentH + backCount * BACK_LANE_GAP + maxBackLabelH;

        // -- emit order (readability + containment audit): edges under nodes, then boxes, then labels.
        List<Shape> shapes = new ArrayList<>();
        String textColor = fc.textColor();

        // 1) edges. forward = right-middle → left-middle straight line + triangle arrowhead; back =
        // a detour DOWN out the source's bottom, along a lane below the content, UP into the target's
        // bottom with an up-pointing arrowhead.
        int laneIdx = 0;
        for (int ei = 0; ei < edges.size(); ei++) {
            Edge e = edges.get(ei);
            int u = e.u();
            int v = e.v();
            if (isBack[ei]) {
                double laneY = contentH - MARGIN + BACK_LANE_GAP * (++laneIdx);
                double sx = nx[u] + boxW[u] / 2;       // source bottom-middle
                double sy = ny[u] + NODE_H;
                double tx = nx[v] + boxW[v] / 2;        // target bottom-middle
                double ty = ny[v] + NODE_H;
                shapes.add(new Line(sx, sy, sx, laneY, EDGE_STROKE, EDGE_WIDTH));      // down out
                shapes.add(new Line(sx, laneY, tx, laneY, EDGE_STROKE, EDGE_WIDTH));   // along the lane
                shapes.add(new Line(tx, laneY, tx, ty + ARROW_LEN, EDGE_STROKE, EDGE_WIDTH)); // up in
                // Up-pointing arrowhead, tip on the target's bottom edge.
                String bd = "M " + fmt(tx) + " " + fmt(ty)
                    + " L " + fmt(tx - ARROW_HALF_W) + " " + fmt(ty + ARROW_LEN)
                    + " L " + fmt(tx + ARROW_HALF_W) + " " + fmt(ty + ARROW_LEN)
                    + " Z";
                shapes.add(new Path(bd, ARROW_FILL));
                // Edge label: just below the lane's horizontal run (the canvas was grown for it).
                String bl = e.label() == null ? null : boundLabelToCanvas(e.label(), canvasW);
                if (bl != null && !bl.isBlank()) {
                    double lblX = clampLabelX((sx + tx) / 2 + EDGE_LABEL_GAP,
                        FONT.runWidth(bl, EDGE_LABEL_SIZE), canvasW);
                    double lblY = laneY + EDGE_LABEL_GAP + EDGE_LABEL_SIZE * 0.7;
                    String ld = FONT.textPathD(bl, lblX, lblY, EDGE_LABEL_SIZE);
                    if (!ld.isBlank()) {
                        shapes.add(new GlyphRun(ld, textColor));
                    }
                }
                continue;
            }
            double sx = nx[u] + boxW[u];             // source right-middle
            double sy = ny[u] + NODE_H / 2;
            double tx = nx[v];                        // target left-middle
            double ty = ny[v] + NODE_H / 2;
            double dx = tx - sx;
            double dy = ty - sy;
            double len = Math.hypot(dx, dy);
            if (!Double.isFinite(len) || len < 1e-6) {
                continue;   // degenerate anchor pair — skip (never emit NaN geometry)
            }
            double ux = dx / len;
            double uy = dy / len;
            // Arrowhead: tip at the dst anchor, base ARROW_LEN back along the edge, ARROW_HALF_W wide.
            double baseX = tx - ARROW_LEN * ux;
            double baseY = ty - ARROW_LEN * uy;
            double px = -uy;   // unit perpendicular
            double py = ux;
            shapes.add(new Line(sx, sy, baseX, baseY, EDGE_STROKE, EDGE_WIDTH));
            String d = "M " + fmt(tx) + " " + fmt(ty)
                + " L " + fmt(baseX + ARROW_HALF_W * px) + " " + fmt(baseY + ARROW_HALF_W * py)
                + " L " + fmt(baseX - ARROW_HALF_W * px) + " " + fmt(baseY - ARROW_HALF_W * py)
                + " Z";
            shapes.add(new Path(d, ARROW_FILL));
            // Edge label: on the OUTSIDE of the edge at its midpoint (transpose of the TD rule) — an
            // edge going DOWN sits BELOW the midpoint, one going UP or flat sits ABOVE it. Keeps a
            // fan-out's labels ("yes"/"no") from colliding.
            String fl = e.label() == null ? null : boundLabelToCanvas(e.label(), canvasW);
            if (fl != null && !fl.isBlank()) {
                double midX = (sx + baseX) / 2;
                double midY = (sy + baseY) / 2;
                double lblY = dy > 0
                    ? midY + EDGE_LABEL_GAP + EDGE_LABEL_SIZE * 0.7
                    : midY - EDGE_LABEL_GAP - EDGE_LABEL_SIZE * 0.35;
                double lblX = clampLabelX(midX + EDGE_LABEL_GAP,
                    FONT.runWidth(fl, EDGE_LABEL_SIZE), canvasW);
                String ld = FONT.textPathD(fl, lblX, lblY, EDGE_LABEL_SIZE);
                if (!ld.isBlank()) {
                    shapes.add(new GlyphRun(ld, textColor));
                }
            }
        }

        // 2) node boxes via the STYLER seam (default = rect / diamond `<path>`; identical construction
        // to TD, no change needed for LR — the diamond's left/right vertices already land on the LR
        // side anchors). A state diagram reskins pseudostates here without touching the geometry above.
        for (int i = 0; i < n; i++) {
            styler.emitNode(shapes, i, nx[i], ny[i], boxW[i], NODE_H, nodes.get(i).shape(), nodeFill[i]);
        }

        // 3) centered labels (glyph paths — never <text>). Node labels contrast against the box fill
        // (see the TD pass); edge labels above keep textColor (they sit on the page background).
        for (int i = 0; i < n; i++) {
            double cx = nx[i] + boxW[i] / 2;
            double baseline = ny[i] + NODE_H / 2 + LABEL_SIZE * 0.35;
            double w = FONT.runWidth(labels[i], LABEL_SIZE);
            String d = FONT.textPathD(labels[i], cx - w / 2, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, Colors.contrastFill(nodeFill[i])));
            }
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// DFS classification of back-edges: mark any out-edge that targets a GRAY (on-stack) node.
    private static void dfsClassify(int u, List<Edge> edges, List<List<Integer>> adj,
                                    int[] state, boolean[] isBack) {
        state[u] = 1;   // gray
        for (int ei : adj.get(u)) {
            int v = edges.get(ei).v();
            if (state[v] == 1) {
                isBack[ei] = true;         // target is on the recursion stack → back-edge (cycle)
            } else if (state[v] == 0) {
                dfsClassify(v, edges, adj, state, isBack);
            }
            // state[v] == 2 (black): a forward/cross edge — kept for layering, not a back-edge.
        }
        state[u] = 2;   // black
    }

    /// Longest-path layer of `v`: 0 with no forward predecessor, else max(layer(pred)+1). Memoized;
    /// terminates because the forward (back-edge-free) graph is acyclic.
    private static int layerOf(int v, List<List<Integer>> preds, int[] layer) {
        if (layer[v] >= 0) {
            return layer[v];
        }
        // Provisionally 0 before recursing so a (theoretically impossible) forward cycle can't loop.
        layer[v] = 0;
        int best = 0;
        for (int u : preds.get(v)) {
            best = Math.max(best, layerOf(u, preds, layer) + 1);
        }
        layer[v] = best;
        return best;
    }

    /// Deterministic 3-dp number formatting for arrowhead path data (byte-identical bakes, DESIGN §6).
    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            v = 0.0;
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
