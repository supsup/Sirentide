package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Sankey;
import com.sirentide.ir.SankeyFlow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Pure sankey layout: WEIGHTED FLOWS between nodes placed in depth COLUMNS. The deterministic choice
/// over an iterative energy-minimizing node order — a fixed first-seen stack per column, so bakes are
/// byte-identical (docs/DESIGN.md §6).
///
/// LAYOUT (the documented approach):
///   - NODES are DERIVED from the flows in FIRST-SEEN order (each flow contributes its source then its
///     target). A node's COLUMN is its longest path from a source: column 0 for a source-only node,
///     else 1 + the max column of its in-neighbours. Computed by a BOUNDED RELAXATION — `n` passes of
///     `col[target] = max(col[target], col[source]+1)` over the flows in declaration order, then each
///     column CLAMPED to `[0, n-1]`. On a DAG that is the exact longest path; on a CYCLE the relaxation
///     would grow without bound, so the `n`-pass + clamp bound it deterministically (the cycle's
///     back-edge just draws a band from a higher column to a lower one — a leftward band, still a valid
///     quad, never a throw). Documented cycle-break.
///   - COLUMNS march left→right at a fixed pitch ({@code NODE_W + COL_GAP}); every node in a column
///     shares one left x.
///   - A node's VALUE is max(sum of its inflows, sum of its outflows); its HEIGHT is that value · a
///     shared {@code scale} chosen so the heaviest column's bars fill {@code BAR_BUDGET} px (so the
///     tallest column fits the canvas). Nodes STACK top→down within a column in first-seen order with a
///     fixed gap.
///   - Each FLOW takes a vertical slot on its source's RIGHT edge (cumulative outflow offset) and on
///     its target's LEFT edge (cumulative inflow offset), assigned in DECLARATION order. Because a
///     node's height is max(in,out)·scale ≥ (out or in)·scale, every node's bands FIT on its edge.
///   - A BAND draws as a filled straight-sided QUADRILATERAL `<path>`: from the source slot (height
///     value·scale) across to the target slot. Coloured by a LIGHTER TINT of the SOURCE node's palette
///     colour (the contract fill is opaque `#rrggbb` — a tint signals "same source, softer", not alpha).
///
/// SHAPES: each node is a filled {@link Rect} bar (its palette colour) with a label beside it (a
/// contrast-filled glyph run, to the RIGHT for every column except the rightmost, which labels LEFT so
/// the text stays on-canvas); each band is a filled {@link Path}. Each band wraps in one
/// `<g data-sirentide-role="flow">` and each node in one `<g data-sirentide-role="node">` (plan
/// sirentide-semantic-anchor-g). Bands emit FIRST (under the nodes; lower seq). The canvas grows to
/// contain every bar + band + label; nothing escapes.
public final class SankeyLayout {

    private SankeyLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    private static final double MARGIN = 24;
    private static final double NODE_W = 18;         // a thin node bar (the classic sankey node)
    private static final double COL_GAP = 150;       // horizontal gap between depth columns (band span)
    private static final double NODE_GAP = 18;       // vertical gap between stacked nodes in a column
    private static final double BAR_BUDGET = 340;    // px the heaviest column's bars fill (sets scale)
    private static final double MIN_NODE_H = 3;      // a tiny-value node still draws a visible sliver
    private static final double BAND_TINT = 0.55;    // blend the source colour 55% toward white
    private static final double LABEL_SIZE = 12;
    private static final double LABEL_PAD = 8;        // gap between a node bar and its label
    private static final double MAX_LABEL_W = 130;    // labels ellipsize past this
    private static final double MIN_W = 140;          // empty-sankey blank canvas
    private static final double MIN_H = 70;

    public static LaidOut layout(Sankey sankey) {
        return layout(sankey, null);
    }

    /// The `math` renderer is accepted for dispatch parity with the other label-bearing types, but a
    /// sankey's node labels are plain (never a `$…$` formula), so labels render as plain glyph paths —
    /// a null `math` is byte-identical.
    public static LaidOut layout(Sankey sankey, MathFragmentRenderer math) {
        List<SankeyFlow> flows = sankey.flows();
        // EMPTY sankey (no valid flows): a minimal inert canvas — round-trips as a sankey (never the
        // 0×0 shell), never throws.
        if (flows.isEmpty()) {
            return LaidOut.of(MIN_W, MIN_H);
        }

        // 1) DERIVE nodes in first-seen order (each flow's source then target). `index` maps a node
        // name → its stable id; `names` is the id→name array.
        Map<String, Integer> index = new LinkedHashMap<>();
        for (SankeyFlow f : flows) {
            index.putIfAbsent(f.source(), index.size());
            index.putIfAbsent(f.target(), index.size());
        }
        int n = index.size();
        String[] names = new String[n];
        for (Map.Entry<String, Integer> e : index.entrySet()) {
            names[e.getValue()] = e.getKey();
        }

        // 2) In/out value sums per node → each node's VALUE = max(inflow-sum, outflow-sum).
        double[] inSum = new double[n];
        double[] outSum = new double[n];
        for (SankeyFlow f : flows) {
            outSum[index.get(f.source())] += f.value();
            inSum[index.get(f.target())] += f.value();
        }
        double[] value = new double[n];
        for (int i = 0; i < n; i++) {
            value[i] = Math.max(inSum[i], outSum[i]);
        }

        // 3) COLUMN by longest-path-from-a-source, via bounded relaxation (cycle-safe — see class doc).
        int[] col = new int[n];
        for (int pass = 0; pass < n; pass++) {
            boolean changed = false;
            for (SankeyFlow f : flows) {
                int s = index.get(f.source());
                int t = index.get(f.target());
                if (col[t] < col[s] + 1) {
                    col[t] = col[s] + 1;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        int maxCol = 0;
        for (int i = 0; i < n; i++) {
            col[i] = Math.min(col[i], n - 1);   // clamp: bounds a cyclic back-edge deterministically
            maxCol = Math.max(maxCol, col[i]);
        }

        // 4) SCALE: the heaviest column's node-values fill BAR_BUDGET px. `colValueSum[c]` sums the
        // node values in column c; the scale is BAR_BUDGET / the max of those (guarded > 0).
        double[] colValueSum = new double[maxCol + 1];
        for (int i = 0; i < n; i++) {
            colValueSum[col[i]] += value[i];
        }
        double maxColValueSum = 0;
        for (double v : colValueSum) {
            maxColValueSum = Math.max(maxColValueSum, v);
        }
        double scale = maxColValueSum > 0 ? BAR_BUDGET / maxColValueSum : 1;

        // 5) NODE geometry: column x (fixed pitch) + a top→down stack per column (first-seen order).
        double[] colX = new double[maxCol + 1];
        for (int c = 0; c <= maxCol; c++) {
            colX[c] = MARGIN + c * (NODE_W + COL_GAP);
        }
        double[] nodeTop = new double[n];
        double[] nodeH = new double[n];
        double[] runningY = new double[maxCol + 1];
        for (int c = 0; c <= maxCol; c++) {
            runningY[c] = MARGIN;
        }
        for (int i = 0; i < n; i++) {
            int c = col[i];
            nodeH[i] = Math.max(MIN_NODE_H, value[i] * scale);
            nodeTop[i] = runningY[c];
            runningY[c] += nodeH[i] + NODE_GAP;
        }
        double contentBottom = MARGIN;
        for (int c = 0; c <= maxCol; c++) {
            contentBottom = Math.max(contentBottom, runningY[c] - NODE_GAP);
        }
        double canvasH = contentBottom + MARGIN;

        // Node palette colour by first-seen index (the band is a lighter tint of its SOURCE's colour).
        String[] nodeColor = new String[n];
        for (int i = 0; i < n; i++) {
            nodeColor[i] = Colors.PALETTE[i % Colors.PALETTE.length];
        }

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();

        // 6) FLOW BANDS first (drawn UNDER the nodes; anchored so seq 0..F-1 precedes the nodes). Each
        // flow gets the next slot on its source's right edge + its target's left edge, in declaration
        // order. The band width IS value·scale — deleting/fixing that is the receipt-#6 mutant target
        // (SankeyLayoutTest#aBandWidthIsProportionalToItsValue fails).
        double[] outOff = new double[n];
        double[] inOff = new double[n];
        for (SankeyFlow f : flows) {
            int s = index.get(f.source());
            int t = index.get(f.target());
            double w = f.value() * scale;
            double srcX = colX[col[s]] + NODE_W;   // source RIGHT edge
            double tgtX = colX[col[t]];             // target LEFT edge
            double srcY0 = nodeTop[s] + outOff[s];
            double tgtY0 = nodeTop[t] + inOff[t];
            outOff[s] += w;
            inOff[t] += w;
            // A straight-sided quad: source slot (top-left, bottom-left) across to the target slot
            // (top-right, bottom-right). Deterministic + never self-intersecting for a forward band.
            String d = "M " + fmt(srcX) + " " + fmt(srcY0)
                + " L " + fmt(tgtX) + " " + fmt(tgtY0)
                + " L " + fmt(tgtX) + " " + fmt(tgtY0 + w)
                + " L " + fmt(srcX) + " " + fmt(srcY0 + w)
                + " Z";
            String fill = Colors.lighten(nodeColor[s], BAND_TINT);
            shapes.add(new Group(assigner.assign(SirentideRole.FLOW, f.source() + "-" + f.target()),
                List.of(new Path(d, fill))));
        }

        // 7) NODE bars + labels: one `<g role="node">` per node, seq F..F+N-1 in first-seen order. The
        // label sits to the RIGHT of the bar (in the column gap), except the rightmost column labels to
        // the LEFT so its text stays on-canvas.
        double canvasW = colX[maxCol] + NODE_W + MARGIN;
        for (int i = 0; i < n; i++) {
            int c = col[i];
            double left = colX[c];
            String fill = nodeColor[i];
            List<Shape> ng = new ArrayList<>();
            ng.add(new Rect(left, nodeTop[i], NODE_W, nodeH[i], fill));
            String label = FONT.ellipsize(names[i], MAX_LABEL_W, LABEL_SIZE);
            double lw = FONT.runWidth(label, LABEL_SIZE);
            double baseline = nodeTop[i] + nodeH[i] / 2 + LABEL_SIZE * 0.35;
            double labelX;
            if (c == maxCol) {
                labelX = left - LABEL_PAD - lw;    // rightmost column → label to the LEFT
            } else {
                labelX = left + NODE_W + LABEL_PAD;  // otherwise → label to the RIGHT
                canvasW = Math.max(canvasW, labelX + lw + MARGIN);   // grow to contain a right label
            }
            String pd = FONT.textPathD(label, labelX, baseline, LABEL_SIZE);
            if (!pd.isBlank()) {
                // The label sits BESIDE the bar on the PAGE background (not on the fill), so it uses the
                // page text colour (`currentColor` by default) — it must read against the theme, not the
                // bar. (Contrast-on-fill is for labels drawn INSIDE a box, like the flowchart nodes.)
                ng.add(new GlyphRun(pd, sankey.textColor()));
            }
            shapes.add(new Group(assigner.assign(SirentideRole.NODE, names[i]), ng));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Numbers with the emitter's rounding (integer when whole, else ≤ 3 decimals) so the laid-out `d`
    /// strings are compact + deterministic; a non-finite value degrades to 0 (never leaks Infinity/NaN).
    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            v = 0.0;
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
