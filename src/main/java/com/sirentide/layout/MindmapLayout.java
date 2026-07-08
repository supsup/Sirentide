package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Mindmap;
import com.sirentide.ir.MindmapNode;
import java.util.ArrayList;
import java.util.List;

/// Pure mindmap layout: a LEFT-TO-RIGHT layered tree (root at the left, depth grows rightward). The
/// deterministic choice over a radial "organic" look — a tidy-ish tree that never optimizes, so
/// bakes are byte-identical (docs/DESIGN.md §6).
///
/// LAYOUT (the documented approach):
///   - DEPTH → X COLUMN. Each depth d gets an x-column at {@code colX[d]}; a column's width is its
///     widest node box, and columns march left→right with a fixed gap. Every box in a column is
///     LEFT-ALIGNED at {@code colX[d]}, so a whole depth shares one left edge.
///   - LEAF-ORDER Y + PARENT-CENTERING. A depth-first walk (in child order) assigns each LEAF the
///     next evenly-spaced y-slot; each INTERNAL node's y CENTRES on its children — the midpoint of
///     its first and last child's y (the standard tidy-tree y-centering). So a parent always sits
///     vertically between its children.
///   - EDGES. One elbow connector per parent→child: out the parent's RIGHT edge, across to a mid-x,
///     down/up to the child's row, into the child's LEFT edge (three {@link Line} segments).
///
/// SHAPES: each node is a filled {@link Rect} box (coloured by DEPTH from {@link Colors#PALETTE}, so
/// the tree reads in depth bands) with a centred, contrast-filled glyph label (dark on a light box,
/// white on a dark one — {@link Colors#contrastFill}); connectors are neutral {@link Line}s. Each
/// node's box + label wrap in one `<g data-sirentide-role="node">` and each connector in one
/// `<g data-sirentide-role="edge">` (plan sirentide-semantic-anchor-g). The canvas grows to contain
/// every box + connector; nothing escapes.
public final class MindmapLayout {

    private MindmapLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    private static final double MARGIN = 20;
    private static final double COL_GAP = 46;      // horizontal gap between depth columns
    private static final double ROW_GAP = 40;      // vertical pitch between leaf rows
    private static final double NODE_H = 30;
    private static final double PAD_X = 12;         // horizontal padding inside a node box
    private static final double MIN_BOX_W = 40;
    private static final double MAX_LABEL_W = 160;  // labels ellipsize past this
    private static final double LABEL_SIZE = 12;
    private static final double MIN_W = 120;        // empty-mindmap blank canvas
    private static final double MIN_H = 60;

    private static final String EDGE_STROKE = "#94a3b8";
    private static final double EDGE_WIDTH = 1.5;

    public static LaidOut layout(Mindmap mm) {
        return layout(mm, null);
    }

    /// The `math` renderer is accepted for dispatch parity with the other label-bearing types, but a
    /// mindmap's node text is plain (never a `$…$` formula), so labels render as plain glyph paths —
    /// a null `math` is byte-identical.
    public static LaidOut layout(Mindmap mm, MathFragmentRenderer math) {
        MindmapNode root = mm.root();
        // EMPTY mindmap (no root): a minimal inert canvas — round-trips as a mindmap (never the 0×0
        // shell), never throws.
        if (root == null) {
            return LaidOut.of(MIN_W, MIN_H);
        }

        // FLATTEN the tree to preorder-indexed arrays (id 0 = root). The parser depth-caps the tree,
        // so this recursion is bounded (never a stack blow-up on adversarial input).
        List<MindmapNode> order = new ArrayList<>();
        List<Integer> depthOf = new ArrayList<>();
        List<Integer> parentOf = new ArrayList<>();
        List<List<Integer>> kids = new ArrayList<>();
        collect(root, 0, -1, order, depthOf, parentOf, kids);
        int n = order.size();

        // Node box widths (ellipsized label + padding) + the per-column width (widest box in a depth).
        String[] labels = new String[n];
        double[] boxW = new double[n];
        int maxDepth = 0;
        for (int i = 0; i < n; i++) {
            String label = FONT.ellipsize(order.get(i).text(), MAX_LABEL_W, LABEL_SIZE);
            labels[i] = label;
            boxW[i] = Math.max(MIN_BOX_W, FONT.runWidth(label, LABEL_SIZE) + 2 * PAD_X);
            maxDepth = Math.max(maxDepth, depthOf.get(i));
        }
        double[] colW = new double[maxDepth + 1];
        for (int i = 0; i < n; i++) {
            colW[depthOf.get(i)] = Math.max(colW[depthOf.get(i)], boxW[i]);
        }
        double[] colX = new double[maxDepth + 1];
        colX[0] = MARGIN;
        for (int d = 1; d <= maxDepth; d++) {
            colX[d] = colX[d - 1] + colW[d - 1] + COL_GAP;
        }

        // Y assignment: leaf-order slots + parent-centering. `leaf` is a 1-element mutable counter so
        // the recursion advances a shared leaf index; each internal node centres on its child range.
        double[] cy = new double[n];
        int[] leaf = {0};
        assignY(0, kids, cy, leaf);
        int leafCount = leaf[0];

        double canvasW = colX[maxDepth] + colW[maxDepth] + MARGIN;
        double canvasH = 2 * MARGIN + NODE_H + Math.max(0, leafCount - 1) * ROW_GAP;

        List<Shape> shapes = new ArrayList<>();
        AnchorAssigner assigner = new AnchorAssigner();

        // 1) EDGES first (drawn UNDER the boxes; anchored in id order so seq 0..E-1 precedes the
        // nodes). Every non-root node has exactly one incoming edge from its parent. Elbow: parent
        // right-middle → mid-x → child row → child left-middle. Deleting this loop is a receipt-#6
        // mutant (MindmapLayoutTest#anEdgeConnectsEachParentToChild fails).
        for (int i = 1; i < n; i++) {
            int p = parentOf.get(i);
            double parentRight = colX[depthOf.get(p)] + boxW[p];
            double childLeft = colX[depthOf.get(i)];
            double py = cy[p];
            double ky = cy[i];
            double midX = (parentRight + childLeft) / 2;
            List<Shape> tgt = new ArrayList<>();
            tgt.add(new Line(parentRight, py, midX, py, EDGE_STROKE, EDGE_WIDTH));   // out the parent
            tgt.add(new Line(midX, py, midX, ky, EDGE_STROKE, EDGE_WIDTH));          // across to the row
            tgt.add(new Line(midX, ky, childLeft, ky, EDGE_STROKE, EDGE_WIDTH));     // into the child
            shapes.add(new Group(assigner.assign(SirentideRole.EDGE, edgeBaseId(order, p, i)), tgt));
        }

        // 2) NODE boxes + centred labels: one `<g role="node">` per node, seq E..E+N-1 in preorder.
        for (int i = 0; i < n; i++) {
            double left = colX[depthOf.get(i)];
            double top = cy[i] - NODE_H / 2;
            String fill = Colors.PALETTE[depthOf.get(i) % Colors.PALETTE.length];
            List<Shape> ng = new ArrayList<>();
            ng.add(new Rect(left, top, boxW[i], NODE_H, fill));
            double w = FONT.runWidth(labels[i], LABEL_SIZE);
            String d = FONT.textPathD(labels[i], left + boxW[i] / 2 - w / 2,
                cy[i] + LABEL_SIZE * 0.35, LABEL_SIZE);
            if (!d.isBlank()) {
                ng.add(new GlyphRun(d, Colors.contrastFill(fill)));
            }
            shapes.add(new Group(assigner.assign(SirentideRole.NODE, nodeBaseId(order.get(i))), ng));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Preorder-collect the tree into parallel arrays (id assigned in visit order, id 0 = root): the
    /// node, its depth, its parent id (-1 for the root), and each node's child-id list. Bounded
    /// recursion (the parser depth-caps the tree).
    private static void collect(MindmapNode node, int depth, int parent, List<MindmapNode> order,
                                List<Integer> depthOf, List<Integer> parentOf, List<List<Integer>> kids) {
        int id = order.size();
        order.add(node);
        depthOf.add(depth);
        parentOf.add(parent);
        kids.add(new ArrayList<>());
        if (parent >= 0) {
            kids.get(parent).add(id);   // the parent was visited first (preorder), so its list exists
        }
        for (MindmapNode c : node.children()) {
            collect(c, depth + 1, id, order, depthOf, parentOf, kids);
        }
    }

    /// Assigns `cy[id]` and returns it: a LEAF takes the next evenly-spaced y-slot (advancing the
    /// shared `leaf` counter); an INTERNAL node centres on the midpoint of its FIRST and LAST child's
    /// y (the tidy-tree y-centering — the receipt-#6 delete mutant target). Bounded recursion.
    private static double assignY(int id, List<List<Integer>> kids, double[] cy, int[] leaf) {
        List<Integer> ch = kids.get(id);
        if (ch.isEmpty()) {
            cy[id] = MARGIN + NODE_H / 2 + leaf[0] * ROW_GAP;
            leaf[0]++;
            return cy[id];
        }
        double first = assignY(ch.get(0), kids, cy, leaf);
        double last = first;
        for (int k = 1; k < ch.size(); k++) {
            last = assignY(ch.get(k), kids, cy, leaf);
        }
        cy[id] = (first + last) / 2;   // centre on the child y-range (a parent sits between its kids)
        return cy[id];
    }

    /// The `data-sirentide-id` base for a NODE: its display text (SANITIZED downstream so a text with
    /// spaces/symbols yields a legal id, and same-text siblings uniquify). Falls back to the role name
    /// downstream when the text is blank (an empty-text root).
    private static String nodeBaseId(MindmapNode node) {
        return node.text();
    }

    /// The `data-sirentide-id` base for an EDGE: `<parentText>-<childText>` — narratable ("Root-Origins")
    /// and uniquified downstream for repeats.
    private static String edgeBaseId(List<MindmapNode> order, int parent, int child) {
        return order.get(parent).text() + "-" + order.get(child).text();
    }
}
