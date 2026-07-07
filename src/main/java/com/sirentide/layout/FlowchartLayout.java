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

    public static LaidOut layout(Flowchart fc) {
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

        // Edge endpoints as indices; drop any edge whose endpoint isn't a known node (defensive —
        // the parser already guards this, but layout must tolerate a stray reference, never throw).
        List<int[]> edges = new ArrayList<>();
        for (FlowEdge e : fc.edges()) {
            Integer u = index.get(e.from());
            Integer v = index.get(e.to());
            if (u != null && v != null) {
                edges.add(new int[] {u, v});
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
            adj.get(edges.get(ei)[0]).add(ei);
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
                preds.get(edges.get(ei)[1]).add(edges.get(ei)[0]);
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
            boxW[i] = Math.max(MIN_BOX_W, FONT.runWidth(label, LABEL_SIZE) + 2 * PAD_X);
            byLayer.get(layer[i]).add(i);   // first-seen order preserved (i ascends)
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
        for (boolean b : isBack) {
            if (b) {
                backCount++;
            }
        }
        double canvasW = contentW + backCount * BACK_LANE_GAP;
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
            int[] e = edges.get(ei);
            int u = e[0];
            int v = e[1];
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
        }

        // 2) node boxes.
        for (int i = 0; i < n; i++) {
            shapes.add(new Rect(nx[i], ny[i], boxW[i], NODE_H, NODE_FILL));
        }

        // 3) centered labels (glyph paths — never <text>).
        String textColor = fc.textColor();
        for (int i = 0; i < n; i++) {
            double cx = nx[i] + boxW[i] / 2;
            double baseline = ny[i] + NODE_H / 2 + LABEL_SIZE * 0.35;
            double w = FONT.runWidth(labels[i], LABEL_SIZE);
            String d = FONT.textPathD(labels[i], cx - w / 2, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, textColor));
            }
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// DFS classification of back-edges: mark any out-edge that targets a GRAY (on-stack) node.
    private static void dfsClassify(int u, List<int[]> edges, List<List<Integer>> adj,
                                    int[] state, boolean[] isBack) {
        state[u] = 1;   // gray
        for (int ei : adj.get(u)) {
            int v = edges.get(ei)[1];
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
