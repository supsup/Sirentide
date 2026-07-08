package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.FlowCluster;
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
/// Flowchart-quality routing (M2, Sugiyama): a FORWARD edge spanning more than one layer is split
/// into a chain through one VIRTUAL waypoint per crossed layer (no box/label drawn, a ~12px slot).
/// The waypoints (a) give the long edge bend points so it routes AROUND the intermediate boxes
/// instead of grazing them, and (b) participate — as first-class vertices alongside the real nodes —
/// in BARYCENTER crossing-minimization, which reorders each layer by the mean position of its
/// neighbours in the adjacent layer (down/up sweeps, stable first-seen tiebreak). Both are computed
/// direction-independently, so TD and LR benefit identically; back-edges keep their lanes unchanged.
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

    // -- subgraph CLUSTER frames. A titled bounding box (a stroke-only rectangle = four Lines, so no
    // new emitter surface; a filled top BAND holds the title) enclosing a cluster's member node rects
    // with padding. Drawn UNDER the edges/nodes so a frame never occludes the content it wraps. Nested
    // clusters TIGHTEN their padding by depth·CLUSTER_INSET so the inner frame insets inside its
    // parent (whose transitive members it encloses). A frame that escapes above/left of the canvas is
    // caught by the shift+grow-to-fit pass (a top/left-escaping frame shifts every vertex and the
    // canvas grows), so CLUSTER_PAD is free to give the members real breathing room past the tight
    // ≤ MARGIN bound — the widest member no longer hugs the border.
    private static final String CLUSTER_STROKE = "#94a3b8";  // frame border (in-palette slate)
    private static final String CLUSTER_BAND_FILL = "#eef2ff"; // pale-indigo title-band background
    private static final double CLUSTER_STROKE_W = 1;
    private static final double CLUSTER_PAD = 14;      // padding beyond the member node bbox (depth 0)
    private static final double CLUSTER_INSET = 4;     // each nesting depth tightens the padding this far
    private static final double CLUSTER_MIN_PAD = 4;   // padding never drops below this, however deep
    private static final double CLUSTER_BAND_H = 14;   // title-band height above the member nodes
    private static final double CLUSTER_TITLE_SIZE = 10;   // title glyph size
    private static final double CLUSTER_TITLE_PAD = 5;      // horizontal padding inside the band
    private static final double CLUSTER_MAX_TITLE_W = 220;  // titles ellipsize past this (before band-fit)

    /// A Sugiyama VIRTUAL waypoint's slot width — a thin, invisible placeholder (no box, no label)
    /// that a long edge routes THROUGH and that occupies an ordering slot in barycenter sweeps.
    private static final double VIRTUAL_W = 12;
    /// Barycenter crossing-minimization sweeps: down, up, down, up (4). Each sweep re-orders a layer
    /// by the mean position of its neighbours in the just-fixed adjacent layer; a STABLE sort keeps
    /// first-seen order on ties (deterministic bakes, DESIGN §6).
    private static final int SWEEP_PASSES = 4;

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

    /// Emit an edge/transition label, routing any `$…$` run through the shared {@link MathLabel} seam
    /// when a renderer is present (plan sirentide-math-in-all-label-types). `anchorX`/`gap` +
    /// `subtractWidth` reproduce each call site's outside-of-edge origin rule (a left-going edge's
    /// label right-aligns: its RIGHT edge sits `gap` left of the anchor; every other case left-aligns
    /// `gap` right of it). `baselineY` is the pre-computed label baseline. A `null`/`hasMath`-false
    /// label takes the EXACT legacy path — canvas-relative ellipsize, `runWidth`, `clampLabelX`,
    /// `textPathD` — so a plain-text edge label is byte-identical to the inline emission it replaced.
    /// A math label SKIPS the canvas ellipsize (a formula must not be cut mid-run) and is measured on
    /// its composite width, then clamped in-frame the same way.
    private static void emitEdgeLabel(List<Shape> shapes, String raw, double anchorX, double baselineY,
                                      boolean subtractWidth, double gap, double canvasW, String color,
                                      MathFragmentRenderer math) {
        if (raw == null) {
            return;
        }
        if (math != null && MathLabel.hasMath(raw)) {
            MathLabel.Measured mm = MathLabel.measure(raw, EDGE_LABEL_SIZE, FONT, math);
            double w = mm.width();
            double lblX = subtractWidth ? anchorX - gap - w : anchorX + gap;
            lblX = clampLabelX(lblX, w, canvasW);
            MathLabel.emit(mm, lblX, baselineY, color, EDGE_LABEL_SIZE, FONT, shapes);
            return;
        }
        String fl = boundLabelToCanvas(raw, canvasW);
        if (fl.isBlank()) {
            return;
        }
        double w = FONT.runWidth(fl, EDGE_LABEL_SIZE);
        double lblX = subtractWidth ? anchorX - gap - w : anchorX + gap;
        lblX = clampLabelX(lblX, w, canvasW);
        String ld = FONT.textPathD(fl, lblX, baselineY, EDGE_LABEL_SIZE);
        if (!ld.isBlank()) {
            shapes.add(new GlyphRun(ld, color));
        }
    }

    /// The default node styler: a rect box, or a diamond `<path>` for a `{Label}` decision node —
    /// byte-for-byte the emission that used to be inline in both the TD and LR passes, so every
    /// flowchart golden is unchanged by the styler seam. State diagrams pass a different styler
    /// ({@link StateDiagramLayout}) to reskin the SAME boxes (discs for pseudostates) without forking
    /// this engine.
    /// Inner-bar inset for a subroutine (`[[Label]]`) box — the vertical bar sits this far in from each
    /// short side (inside the PAD_X padding, so it never overlaps the centered label).
    private static final double SUBROUTINE_BAR = 6;
    /// Lid depth (vertical semi-axis) of a cylinder's (`[(Label)]`) top/bottom ellipse rims.
    private static final double CYL_LID = 6;
    /// Line-segment count approximating a cylinder's visible front lid rim (a smooth-enough arc).
    private static final int CYL_LID_SEGS = 10;

    static final NodeStyler DEFAULT_STYLER = (shapes, i, x, y, w, h, shape, fill) -> {
        switch (shape == null ? "rect" : shape) {
            case "diamond" -> {
                double cx = x + w / 2;
                double cy = y + h / 2;
                String d = "M " + fmt(cx) + " " + fmt(y)
                    + " L " + fmt(x + w) + " " + fmt(cy)
                    + " L " + fmt(cx) + " " + fmt(y + h)
                    + " L " + fmt(x) + " " + fmt(cy)
                    + " Z";
                shapes.add(new Path(d, fill));
            }
            // Fully-rounded-ends pill: straight top/bottom + a semicircle cap (r = h/2) on each side.
            case "stadium" -> shapes.add(new Path(stadiumPath(x, y, w, h), fill));
            // A ring — a full ellipse traced as two arcs (a true circle when the box is square).
            case "circle" -> shapes.add(new Path(ellipsePath(x, y, w, h), fill));
            // A 6-point hexagon: flat top/bottom, a point on each side (all straight L segments).
            case "hexagon" -> shapes.add(new Path(hexagonPath(x, y, w, h), fill));
            // A soft-cornered box (Q-quadratic corners, no arcs) — distinct from the sharp default rect.
            case "rounded" -> shapes.add(new Path(roundedRectPath(x, y, w, h), fill));
            // Subroutine: the plain box PLUS an inner vertical bar just in from each short side.
            case "subroutine" -> {
                shapes.add(new Rect(x, y, w, h, fill));
                String bar = Colors.contrastFill(fill);
                shapes.add(new Line(x + SUBROUTINE_BAR, y, x + SUBROUTINE_BAR, y + h, bar, 1));
                shapes.add(new Line(x + w - SUBROUTINE_BAR, y, x + w - SUBROUTINE_BAR, y + h, bar, 1));
            }
            // Database can: a filled silhouette (elliptical top + bottom) + a lid rim front-arc.
            case "cylinder" -> emitCylinder(shapes, x, y, w, h, fill);
            // Default is the sharp RECT (`[Label]` + every bare node) — byte-identical to before.
            default -> shapes.add(new Rect(x, y, w, h, fill));
        }
    };

    /// A pill/stadium (`([Label])`) outline: straight top and bottom edges joined by a semicircular cap
    /// (radius h/2) at each short end. The caps are SVG elliptical arcs — `A r r 0 0 1 …` bulging out —
    /// so the ends are fully round; the left/right vertices still sit on the box's mid-height edge
    /// anchors, so edge routing is unchanged. Requires w >= h (always true: MIN_BOX_W 44 > NODE_H 36).
    private static String stadiumPath(double x, double y, double w, double h) {
        double r = h / 2;
        return "M " + fmt(x + r) + " " + fmt(y)
            + " L " + fmt(x + w - r) + " " + fmt(y)
            + " A " + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(x + w - r) + " " + fmt(y + h)
            + " L " + fmt(x + r) + " " + fmt(y + h)
            + " A " + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(x + r) + " " + fmt(y)
            + " Z";
    }

    /// A full ellipse (`((Label))` circle node) traced as two half-arcs — bottom half left→right, top
    /// half right→left — filling the box [x,x+w]×[y,y+h]. rx = w/2, ry = h/2, so a SQUARE box (the
    /// short-label case, w == h == NODE_H) bakes a true circle; a wider box is a horizontal ellipse.
    /// The left/right/top/bottom extremes sit on the box edges, so edge anchors are unchanged.
    private static String ellipsePath(double x, double y, double w, double h) {
        double rx = w / 2;
        double ry = h / 2;
        double cy = y + ry;
        return "M " + fmt(x) + " " + fmt(cy)
            + " A " + fmt(rx) + " " + fmt(ry) + " 0 0 1 " + fmt(x + w) + " " + fmt(cy)
            + " A " + fmt(rx) + " " + fmt(ry) + " 0 0 1 " + fmt(x) + " " + fmt(cy)
            + " Z";
    }

    /// A 6-point hexagon (`{{Label}}`): a flat top and bottom edge, a single point on each side. The
    /// side notch = min(h/2, w/4), so the left/right points sit on the box's mid-height edge anchors
    /// (edge routing unchanged) and the top/bottom edges stay wide enough for the label. All straight
    /// L segments (no curves), which is exactly what distinguishes it from the arc/curve shapes.
    private static String hexagonPath(double x, double y, double w, double h) {
        double notch = Math.min(h / 2, w / 4);
        double cy = y + h / 2;
        return "M " + fmt(x + notch) + " " + fmt(y)
            + " L " + fmt(x + w - notch) + " " + fmt(y)
            + " L " + fmt(x + w) + " " + fmt(cy)
            + " L " + fmt(x + w - notch) + " " + fmt(y + h)
            + " L " + fmt(x + notch) + " " + fmt(y + h)
            + " L " + fmt(x) + " " + fmt(cy)
            + " Z";
    }

    /// A soft-cornered rectangle (`(Label)` rounded node): straight H/V edges with a Q quadratic at
    /// each corner (radius min(8, w/2, h/2)). Q corners (no arcs, no `<rect>`) distinguish it from both
    /// the sharp default rect and the arc-based stadium/circle.
    private static String roundedRectPath(double x, double y, double w, double h) {
        double r = Math.min(8, Math.min(w, h) / 2);
        return "M " + fmt(x + r) + " " + fmt(y)
            + " L " + fmt(x + w - r) + " " + fmt(y)
            + " Q " + fmt(x + w) + " " + fmt(y) + " " + fmt(x + w) + " " + fmt(y + r)
            + " L " + fmt(x + w) + " " + fmt(y + h - r)
            + " Q " + fmt(x + w) + " " + fmt(y + h) + " " + fmt(x + w - r) + " " + fmt(y + h)
            + " L " + fmt(x + r) + " " + fmt(y + h)
            + " Q " + fmt(x) + " " + fmt(y + h) + " " + fmt(x) + " " + fmt(y + h - r)
            + " L " + fmt(x) + " " + fmt(y + r)
            + " Q " + fmt(x) + " " + fmt(y) + " " + fmt(x + r) + " " + fmt(y)
            + " Z";
    }

    /// A database CYLINDER (`[(Label)]`): a filled silhouette (top back-arc bulging up, straight sides,
    /// bottom front-arc bulging down) plus the visible FRONT rim of the top lid drawn as a short
    /// line-segment polyline (a Path fills but can't stroke a hairline arc, so the rim rides {@link Line}
    /// segments — the same many-segment-line approximation ER's rings use). Both rims are shallow (semi-
    /// axis {@link #CYL_LID}); the left/right of the body sit on the box edges so edge anchors hold.
    private static void emitCylinder(List<Shape> shapes, double x, double y, double w, double h,
                                     String fill) {
        double rx = w / 2;
        double ry = Math.min(CYL_LID, h / 4);
        double cx = x + rx;
        // Silhouette (filled): top back-arc up, right side down, bottom front-arc down, left side up.
        String d = "M " + fmt(x) + " " + fmt(y + ry)
            + " A " + fmt(rx) + " " + fmt(ry) + " 0 0 0 " + fmt(x + w) + " " + fmt(y + ry)
            + " L " + fmt(x + w) + " " + fmt(y + h - ry)
            + " A " + fmt(rx) + " " + fmt(ry) + " 0 0 0 " + fmt(x) + " " + fmt(y + h - ry)
            + " Z";
        shapes.add(new Path(d, fill));
        // The lid rim: the front half of the top ellipse (dips to y+2·ry), as CYL_LID_SEGS line segments.
        String rim = Colors.contrastFill(fill);
        double px = x;
        double py = y + ry;
        for (int k = 1; k <= CYL_LID_SEGS; k++) {
            double t = Math.PI * k / CYL_LID_SEGS;         // 0..π sweeps the front rim left→right
            double nx = cx - rx * Math.cos(t);             // x (k=0) → x+w (k=CYL_LID_SEGS)
            double ny = (y + ry) + ry * Math.sin(t);       // dips down to y + 2·ry at the middle
            shapes.add(new Line(px, py, nx, ny, rim, 1));
            px = nx;
            py = ny;
        }
    }

    public static LaidOut layout(Flowchart fc) {
        return layout(fc, DEFAULT_STYLER, null);
    }

    /// Inline-math entry: node labels containing `$…$` render through `math` (RFC sirentide/39).
    /// A null `math` is identical to {@link #layout(Flowchart)}.
    public static LaidOut layout(Flowchart fc, MathFragmentRenderer math) {
        return layout(fc, DEFAULT_STYLER, math);
    }

    /// Styler entry with no math (the state-diagram driver's path). Byte-identical to before.
    static LaidOut layout(Flowchart fc, NodeStyler styler) {
        return layout(fc, styler, null);
    }

    /// The parameterized engine: identical layered-graph layout for every caller, with only the
    /// final node-drawing step delegated to `styler` (DESIGN §5 — one graph engine, two presentations).
    /// `math` (nullable) renders `$…$` runs in node labels; when null every label is plain text and
    /// the output is byte-identical to the pre-feature engine.
    static LaidOut layout(Flowchart fc, NodeStyler styler, MathFragmentRenderer math) {
        List<FlowNode> nodes = fc.nodes();
        int n = nodes.size();
        // 0 nodes → a small blank-but-valid canvas (a bare `flowchart` still round-trips as one).
        if (n == 0) {
            return LaidOut.of(MIN_W, MIN_H);
        }

        // Semantic-anchor gate (plan sirentide-semantic-anchor-g, slice 2): EVERY caller of this engine
        // now wraps each node/edge in a `<g data-sirentide-*>`. The real flowchart (DEFAULT_STYLER) and
        // the STATE diagram (its own styler, states→node / transitions→edge) both anchor — the node
        // base-id (label else DSL id) and the from-to edge id are styler-independent, so a state's
        // `[*]` pseudostate (empty label) falls back to its `__start__`/`__end__` id. Unconditional now
        // (slice 1 gated on the styler to keep state byte-identical; slice 2 anchors it too).
        boolean anchored = true;

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
        // Composite measures for nodes whose label carries `$…$` math AND a renderer was provided;
        // null for every plain-text node, which keeps the existing text path (byte-identical) below.
        MathLabel.Measured[] measures = new MathLabel.Measured[n];
        List<List<Integer>> byLayer = new ArrayList<>();
        for (int i = 0; i < layerCount; i++) {
            byLayer.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            String raw = nodes.get(i).label();
            double lw;
            if (math != null && MathLabel.hasMath(raw)) {
                // Math labels SKIP ellipsization — a formula must not be cut mid-run (parse-side
                // label length caps still bound the input). Size the box on the composite width.
                MathLabel.Measured m = MathLabel.measure(raw, LABEL_SIZE, FONT, math);
                measures[i] = m;
                labels[i] = raw;
                lw = m.width();
            } else {
                String label = FONT.ellipsize(raw, MAX_LABEL_W, LABEL_SIZE);
                labels[i] = label;
                lw = FONT.runWidth(label, LABEL_SIZE);
            }
            // Per-shape box width. RECT + DIAMOND stay EXACTLY as before (byte-identical): rect is
            // label+padding; a DIAMOND (M1.3) is 1.5× the text so the rhombus CONTAINS its centered
            // label ((w/2)/a + 6/b <= 1 with b=NODE_H/2 needs a >= 0.75·labelW). New shapes: a HEXAGON
            // widens (1.4×) so its left/right side-points don't clip the inscribed label; a CIRCLE is
            // sized SQUARISH (floored at NODE_H, no MIN_BOX_W floor) so a short label bakes a true
            // 36×36 ring (a wide label degrades to an ellipse — the label still fits the inscribed box).
            // STADIUM/ROUNDED/SUBROUTINE/CYLINDER fit the label the same as a rect (their extra chrome —
            // pill caps, corners, inner bars, the DB lid — lives in the padding, not the text column).
            String sh = nodes.get(i).shape();
            double w;
            switch (sh) {
                case "diamond" -> w = Math.max(MIN_BOX_W, 1.5 * lw + 2 * PAD_X);
                case "hexagon" -> w = Math.max(MIN_BOX_W, 1.4 * lw + 2 * PAD_X);
                case "circle" -> w = Math.max(NODE_H, lw + 2 * PAD_X);   // square for a short label
                default -> w = Math.max(MIN_BOX_W, lw + 2 * PAD_X);      // rect + stadium/rounded/sub/cyl
            }
            boxW[i] = w;
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

        // -- Sugiyama routing (M2): split each FORWARD edge spanning >1 layer into a chain through one
        // VIRTUAL waypoint per crossed layer, then barycenter-order every layer (real + virtual). Pure
        // combinatorics over `layer`/`byLayer` — direction-independent, so TD and LR share it. When no
        // forward edge is long AND no layer reorders (single-node or stable-tie layers), `order` equals
        // `byLayer` and no virtual exists → every existing bake is byte-identical.
        Routing rt = route(n, edges, isBack, layer, layerCount, boxW, byLayer);

        // Layering/box-sizing above is direction-INDEPENDENT. TD (below) draws layers as ROWS flowing
        // top→down; LR draws them as COLUMNS flowing left→right — a genuinely different coordinate +
        // emission pass (glyph paths can't be transposed after the fact), so it forks here. The TD
        // path stays byte-identical (all existing goldens unchanged).
        if ("LR".equals(fc.direction())) {
            return layoutLr(fc, nodes, n, layerCount, rt, boxW, labels, nodeFill, edges, isBack,
                styler, measures, math, anchored);
        }

        // Canvas width = widest layer (real + virtual slots) + margins.
        double maxLayerWidth = 0;
        for (List<Integer> row : rt.order) {
            maxLayerWidth = Math.max(maxLayerWidth, rowWidth(row, rt.vWidth));
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
        // lanes extend the canvas to the right without shifting the graph), laid left→right. Real and
        // virtual vertices share the vx/vy arrays; a virtual's waypoint POINT is its slot centre.
        double[] vx = new double[rt.vTotal];
        double[] vy = new double[rt.vTotal];
        for (int L = 0; L < layerCount; L++) {
            List<Integer> row = rt.order.get(L);
            double lw = rowWidth(row, rt.vWidth);
            double startX = (contentW - lw) / 2;
            double y = MARGIN + L * (NODE_H + LAYER_GAP);
            double cursor = startX;
            for (int idx : row) {
                vx[idx] = cursor;
                vy[idx] = y;
                cursor += rt.vWidth[idx] + NODE_GAP;
            }
        }

        // -- subgraph cluster frames (drawn UNDER everything). Empty for a cluster-free chart, so the
        // shift is 0 and the canvas is unchanged (byte-identical bake). A frame that would escape ABOVE
        // or LEFT of the canvas shifts EVERY vertex (real + virtual) right/down by the deficit so the
        // containment invariant holds; the canvas then GROWS to fit the shifted content + frame extent.
        List<ClusterFrame> frames = buildClusterFrames(fc.clusters(), index, vx, vy, boxW);
        double offX = 0;
        double offY = 0;
        for (ClusterFrame f : frames) {
            offX = Math.max(offX, MARGIN - f.left());
            offY = Math.max(offY, MARGIN - f.top());
        }
        if (offX > 0 || offY > 0) {
            for (int i = 0; i < rt.vTotal; i++) {
                vx[i] += offX;
                vy[i] += offY;
            }
            frames = buildClusterFrames(fc.clusters(), index, vx, vy, boxW);
        }
        double maxFrameRight = 0;
        double maxFrameBottom = 0;
        for (ClusterFrame f : frames) {
            maxFrameRight = Math.max(maxFrameRight, f.right());
            maxFrameBottom = Math.max(maxFrameBottom, f.bottom());
        }
        canvasW = Math.max(canvasW + offX, maxFrameRight + MARGIN);
        canvasH = Math.max(canvasH + offY, maxFrameBottom + MARGIN);

        // -- emit order matters (readability + the containment audit): cluster frames UNDER edges,
        // edges UNDER nodes, then boxes, then labels on top.
        List<Shape> shapes = new ArrayList<>();
        for (ClusterFrame f : frames) {
            emitClusterFrame(shapes, f, canvasW);
        }

        // 1) edges: forward = a straight line (or, when routed through waypoints, a POLYLINE) from the
        // src bottom-center to the dst top-center + a triangle arrowhead on the FINAL segment; BACK-
        // edges = an orthogonal detour through a right-side lane (M1.1) — out the source's right side,
        // up the lane, back into the target's right side.
        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): assigns each edge/node its
        // role+unique-id+emit-order-seq. Edges are assigned FIRST (emit before nodes), so seq runs
        // 0..E-1 over edges then E..E+N-1 over nodes — the element's emit-order index. Unused when
        // !anchored (state diagram): the shapes go straight onto the flat list, byte-identical.
        AnchorAssigner assigner = new AnchorAssigner();
        int laneIdx = 0;
        for (int ei = 0; ei < edges.size(); ei++) {
            Edge e = edges.get(ei);
            int u = e.u();
            int v = e.v();
            // When anchored, an edge's shapes collect into `tgt` and are wrapped in ONE `<g>`; when
            // not, `tgt` IS the flat list, so the emission order/bytes are exactly the pre-anchor path.
            List<Shape> tgt = anchored ? new ArrayList<>() : shapes;
            if (isBack[ei]) {
                // The lane base tracks the (possibly cluster-shifted) content, so a back-edge lane
                // still clears the nodes after a subgraph shift (offX is 0 for a cluster-free chart).
                double laneX = contentW - MARGIN + offX + BACK_LANE_GAP * (++laneIdx);
                double sy = vy[u] + NODE_H / 2;        // source right-middle
                double sx = vx[u] + boxW[u];
                double ty = vy[v] + NODE_H / 2;        // target right-middle
                double tx = vx[v] + boxW[v];
                tgt.add(new Line(sx, sy, laneX, sy, EDGE_STROKE, EDGE_WIDTH));      // out right
                tgt.add(new Line(laneX, sy, laneX, ty, EDGE_STROKE, EDGE_WIDTH));   // up the lane
                tgt.add(new Line(laneX, ty, tx + ARROW_LEN, ty, EDGE_STROKE, EDGE_WIDTH)); // back in
                // Left-pointing arrowhead, tip on the target's right edge.
                String bd = "M " + fmt(tx) + " " + fmt(ty)
                    + " L " + fmt(tx + ARROW_LEN) + " " + fmt(ty - ARROW_HALF_W)
                    + " L " + fmt(tx + ARROW_LEN) + " " + fmt(ty + ARROW_HALF_W)
                    + " Z";
                tgt.add(new Path(bd, ARROW_FILL));
                // Edge label (M1.2): beside the lane's vertical run (the canvas was widened for it).
                emitEdgeLabel(tgt, e.label(), laneX, (sy + ty) / 2 + EDGE_LABEL_SIZE * 0.35,
                    false, EDGE_LABEL_GAP, canvasW, fc.textColor(), math);
            } else {
                int[] chain = rt.chain.get(ei);
                double scx = vx[u] + boxW[u] / 2;
                double sBottom = vy[u] + NODE_H;
                double dcx = vx[v] + boxW[v] / 2;
                double dTop = vy[v];
                if (chain.length == 2) {
                    // Straight span-1 edge — byte-for-byte the original emission (all existing goldens).
                    double dx = dcx - scx;
                    double dy = dTop - sBottom;
                    double len = Math.hypot(dx, dy);
                    if (Double.isFinite(len) && len >= 1e-6) {
                        double ux = dx / len;
                        double uy = dy / len;
                        // Arrowhead: tip at the dst anchor, base ARROW_LEN back along the edge.
                        double baseCx = dcx - ARROW_LEN * ux;
                        double baseCy = dTop - ARROW_LEN * uy;
                        double px = -uy;   // unit perpendicular
                        double py = ux;
                        // The line stops at the arrow base so it doesn't overshoot the filled triangle.
                        tgt.add(new Line(scx, sBottom, baseCx, baseCy, EDGE_STROKE, EDGE_WIDTH));
                        String d = "M " + fmt(dcx) + " " + fmt(dTop)
                            + " L " + fmt(baseCx + ARROW_HALF_W * px) + " " + fmt(baseCy + ARROW_HALF_W * py)
                            + " L " + fmt(baseCx - ARROW_HALF_W * px) + " " + fmt(baseCy - ARROW_HALF_W * py)
                            + " Z";
                        tgt.add(new Path(d, ARROW_FILL));
                        // Edge label (M1.2): on the OUTSIDE of the edge at its midpoint — a right-going
                        // edge's label sits right of the line, a left-going one's left of it.
                        emitEdgeLabel(tgt, e.label(), (scx + baseCx) / 2,
                            (sBottom + baseCy) / 2 + EDGE_LABEL_SIZE * 0.35,
                            dx < 0, EDGE_LABEL_GAP, canvasW, fc.textColor(), math);
                    }
                    // else: degenerate anchor pair — skip (never emit NaN geometry). The (possibly
                    // empty) edge group is still emitted below so seq stays aligned with the edge set.
                } else {
                    // LONG edge: route as a POLYLINE src-anchor → waypoint centres → dst-anchor. Each
                    // virtual slot sits BESIDE the intermediate boxes so the polyline bends AROUND
                    // them. Arrowhead ONLY on the final segment.
                    int k = chain.length;
                    double[] xs = new double[k];
                    double[] ys = new double[k];
                    xs[0] = scx;
                    ys[0] = sBottom;
                    for (int j = 1; j < k - 1; j++) {
                        int w = chain[j];
                        // Use the routed virtual width (matches the LR path) so a future variable-width
                        // virtual can't make TD and LR waypoints diverge (Conf sirentide/44 #5).
                        xs[j] = vx[w] + rt.vWidth[w] / 2;
                        ys[j] = vy[w] + NODE_H / 2;
                    }
                    xs[k - 1] = dcx;
                    ys[k - 1] = dTop;
                    emitPolyline(tgt, xs, ys);
                    // Edge label anchors on the FIRST segment's midpoint (outside rule + clamp, as today).
                    emitEdgeLabel(tgt, e.label(), (xs[0] + xs[1]) / 2,
                        (ys[0] + ys[1]) / 2 + EDGE_LABEL_SIZE * 0.35,
                        (xs[1] - xs[0]) < 0, EDGE_LABEL_GAP, canvasW, fc.textColor(), math);
                }
            }
            if (anchored) {
                shapes.add(new Group(assigner.assign(SirentideRole.EDGE, edgeBaseId(nodes, e)), tgt));
            }
        }

        // 2) node boxes (STYLER seam) + 3) centered labels. Each node's box AND label are collected
        // into ONE `<g data-sirentide-role="node">` (emit-order per node index) — the label rides inside
        // its node's group. (The `else` two-pass path is now a never-taken legacy fallback — `anchored`
        // is unconditionally true; kept only so the geometry is trivially auditable side by side.) A
        // node label never extends beyond its own box (box width = label + padding), so the box/label
        // interleave introduces no cross-node z-order change: visually identical, geometry byte-identical.
        if (anchored) {
            for (int i = 0; i < n; i++) {
                List<Shape> ng = new ArrayList<>();
                styler.emitNode(ng, i, vx[i], vy[i], boxW[i], NODE_H, nodes.get(i).shape(), nodeFill[i]);
                emitNodeLabel(ng, vx[i] + boxW[i] / 2, vy[i] + NODE_H / 2 + LABEL_SIZE * 0.35,
                    measures[i], labels[i], nodeFill[i]);
                shapes.add(new Group(assigner.assign(SirentideRole.NODE, nodeBaseId(nodes.get(i))), ng));
            }
        } else {
            for (int i = 0; i < n; i++) {
                styler.emitNode(shapes, i, vx[i], vy[i], boxW[i], NODE_H, nodes.get(i).shape(), nodeFill[i]);
            }
            for (int i = 0; i < n; i++) {
                emitNodeLabel(shapes, vx[i] + boxW[i] / 2, vy[i] + NODE_H / 2 + LABEL_SIZE * 0.35,
                    measures[i], labels[i], nodeFill[i]);
            }
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Emit one node's centered label (glyph paths — never `<text>`) into `tgt`. A node label sits ON
    /// its box, so it fills with the CONTRAST of the box colour (dark on a light box, white on a dark
    /// one). Extracted from the label pass so the anchored (per-node group) and unanchored (flat two-
    /// pass) paths share EXACTLY the same label bytes.
    private static void emitNodeLabel(List<Shape> tgt, double cx, double baseline,
                                      MathLabel.Measured measure, String label, String nodeFill) {
        if (measure != null) {
            MathLabel.emit(measure, cx - measure.width() / 2, baseline,
                Colors.contrastFill(nodeFill), LABEL_SIZE, FONT, tgt);
        } else {
            double w = FONT.runWidth(label, LABEL_SIZE);
            String d = FONT.textPathD(label, cx - w / 2, baseline, LABEL_SIZE);
            if (!d.isBlank()) {
                tgt.add(new GlyphRun(d, Colors.contrastFill(nodeFill)));
            }
        }
    }

    /// The `data-sirentide-id` base for a NODE: its human label (SANITIZED downstream), so a label
    /// with spaces/symbols yields a legal id and two same-label nodes get uniquified. Falls back to the
    /// DSL id when the label is blank (never the case for a real flowchart node; defensive).
    private static String nodeBaseId(FlowNode node) {
        String label = node.label();
        return label != null && !label.isBlank() ? label : node.id();
    }

    /// The `data-sirentide-id` base for an EDGE: `<fromId>-<toId>` from the endpoint node DSL ids —
    /// stable, always present, and narratable ("A-B"). Uniquified downstream for parallel edges.
    private static String edgeBaseId(List<FlowNode> nodes, Edge e) {
        return nodes.get(e.u()).id() + "-" + nodes.get(e.v()).id();
    }

    /// LEFT-RIGHT geometry: layers become COLUMNS, flow runs left→right. A full mirror of the TD
    /// pass with the axes swapped — columns (not rows), vertical centering within the tallest column,
    /// forward edges out the RIGHT side into the next column's LEFT side, and back-edges detouring
    /// through horizontal lanes BELOW the content (mirror of TD's right-side vertical lanes). The
    /// diamond path is untouched: its left/right vertices already land on the LR side anchors. Long
    /// forward edges route through the SAME virtual waypoints (computed direction-independently); only
    /// the coordinates differ.
    private static LaidOut layoutLr(Flowchart fc, List<FlowNode> nodes, int n, int layerCount,
                                    Routing rt, double[] boxW, String[] labels,
                                    String[] nodeFill, List<Edge> edges, boolean[] isBack,
                                    NodeStyler styler, MathLabel.Measured[] measures,
                                    MathFragmentRenderer math, boolean anchored) {
        // -- columns: colW[L] = widest slot (real box or virtual) in layer L; colX marches left→right.
        double[] colW = new double[layerCount];
        for (int L = 0; L < layerCount; L++) {
            double w = 0;
            for (int idx : rt.order.get(L)) {
                w = Math.max(w, rt.vWidth[idx]);
            }
            colW[L] = w;
        }
        double[] colX = new double[layerCount];
        double maxColumnStackH = 0;   // tallest column's stacked height → the vertical-centering datum
        for (int L = 0; L < layerCount; L++) {
            colX[L] = L == 0 ? MARGIN : colX[L - 1] + colW[L - 1] + LAYER_GAP;
            int cnt = rt.order.get(L).size();
            double stackH = cnt == 0 ? 0 : cnt * NODE_H + (cnt - 1) * NODE_GAP;
            maxColumnStackH = Math.max(maxColumnStackH, stackH);
        }
        double contentW = (layerCount == 0 ? MARGIN : colX[layerCount - 1] + colW[layerCount - 1]) + MARGIN;
        double contentH = maxColumnStackH + 2 * MARGIN;

        // Assign coordinates: within a column, vertices stack top→down in barycenter order, and the
        // whole stack is CENTERED VERTICALLY on the tallest column (mirror of TD's per-row horizontal
        // centering). Each vertex is centered horizontally within its column's width. Real + virtual
        // share vx/vy; a virtual's waypoint POINT is its slot centre.
        double[] vx = new double[rt.vTotal];
        double[] vy = new double[rt.vTotal];
        for (int L = 0; L < layerCount; L++) {
            List<Integer> col = rt.order.get(L);
            int cnt = col.size();
            double stackH = cnt == 0 ? 0 : cnt * NODE_H + (cnt - 1) * NODE_GAP;
            double startY = (contentH - stackH) / 2;
            double cursor = startY;
            for (int idx : col) {
                vx[idx] = colX[L] + (colW[L] - rt.vWidth[idx]) / 2;
                vy[idx] = cursor;
                cursor += NODE_H + NODE_GAP;
            }
        }

        // -- subgraph cluster frames (drawn UNDER everything). Same shift/grow discipline as the TD
        // path: empty for a cluster-free chart (offX/offY = 0 → byte-identical); a top/left-escaping
        // frame shifts every vertex right/down and the canvas grows to fit.
        // id → index for member lookup (LR builds no `index` map of its own — rebuild the small one).
        Map<String, Integer> lrIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            lrIndex.put(nodes.get(i).id(), i);
        }
        List<ClusterFrame> frames = buildClusterFrames(fc.clusters(), lrIndex, vx, vy, boxW);
        double offX = 0;
        double offY = 0;
        for (ClusterFrame f : frames) {
            offX = Math.max(offX, MARGIN - f.left());
            offY = Math.max(offY, MARGIN - f.top());
        }
        if (offX > 0 || offY > 0) {
            for (int i = 0; i < rt.vTotal; i++) {
                vx[i] += offX;
                vy[i] += offY;
            }
            frames = buildClusterFrames(fc.clusters(), lrIndex, vx, vy, boxW);
        }
        double maxFrameRight = 0;
        double maxFrameBottom = 0;
        for (ClusterFrame f : frames) {
            maxFrameRight = Math.max(maxFrameRight, f.right());
            maxFrameBottom = Math.max(maxFrameBottom, f.bottom());
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
        canvasW = Math.max(canvasW + offX, maxFrameRight + MARGIN);
        canvasH = Math.max(canvasH + offY, maxFrameBottom + MARGIN);

        // -- emit order (readability + containment audit): cluster frames under edges, edges under
        // nodes, then boxes, then labels.
        List<Shape> shapes = new ArrayList<>();
        for (ClusterFrame f : frames) {
            emitClusterFrame(shapes, f, canvasW);
        }
        String textColor = fc.textColor();

        // 1) edges. forward = right-middle → left-middle straight line (or a POLYLINE through waypoint
        // centres) + a triangle arrowhead on the FINAL segment; back = a detour DOWN out the source's
        // bottom, along a lane below the content, UP into the target's bottom with an up arrowhead.
        // Per-diagram anchor factory (mirror of the TD path): edges assigned first, then nodes.
        AnchorAssigner assigner = new AnchorAssigner();
        int laneIdx = 0;
        for (int ei = 0; ei < edges.size(); ei++) {
            Edge e = edges.get(ei);
            int u = e.u();
            int v = e.v();
            // Anchored → collect into `tgt`, wrap in one `<g>`; else `tgt` IS the flat list (byte-identical).
            List<Shape> tgt = anchored ? new ArrayList<>() : shapes;
            if (isBack[ei]) {
                // The lane base tracks the (possibly cluster-shifted) content (offY is 0 for a
                // cluster-free chart, so the byte-identical bake holds).
                double laneY = contentH - MARGIN + offY + BACK_LANE_GAP * (++laneIdx);
                double sx = vx[u] + boxW[u] / 2;       // source bottom-middle
                double sy = vy[u] + NODE_H;
                double tx = vx[v] + boxW[v] / 2;        // target bottom-middle
                double ty = vy[v] + NODE_H;
                tgt.add(new Line(sx, sy, sx, laneY, EDGE_STROKE, EDGE_WIDTH));      // down out
                tgt.add(new Line(sx, laneY, tx, laneY, EDGE_STROKE, EDGE_WIDTH));   // along the lane
                tgt.add(new Line(tx, laneY, tx, ty + ARROW_LEN, EDGE_STROKE, EDGE_WIDTH)); // up in
                // Up-pointing arrowhead, tip on the target's bottom edge.
                String bd = "M " + fmt(tx) + " " + fmt(ty)
                    + " L " + fmt(tx - ARROW_HALF_W) + " " + fmt(ty + ARROW_LEN)
                    + " L " + fmt(tx + ARROW_HALF_W) + " " + fmt(ty + ARROW_LEN)
                    + " Z";
                tgt.add(new Path(bd, ARROW_FILL));
                // Edge label: just below the lane's horizontal run (the canvas was grown for it).
                emitEdgeLabel(tgt, e.label(), (sx + tx) / 2,
                    laneY + EDGE_LABEL_GAP + EDGE_LABEL_SIZE * 0.7,
                    false, EDGE_LABEL_GAP, canvasW, textColor, math);
            } else {
                int[] chain = rt.chain.get(ei);
                double sx = vx[u] + boxW[u];             // source right-middle
                double sy = vy[u] + NODE_H / 2;
                double tx = vx[v];                        // target left-middle
                double ty = vy[v] + NODE_H / 2;
                if (chain.length == 2) {
                    // Straight span-1 edge — byte-for-byte the original LR emission.
                    double dx = tx - sx;
                    double dy = ty - sy;
                    double len = Math.hypot(dx, dy);
                    if (Double.isFinite(len) && len >= 1e-6) {
                        double ux = dx / len;
                        double uy = dy / len;
                        // Arrowhead: tip at the dst anchor, base ARROW_LEN back along the edge.
                        double baseX = tx - ARROW_LEN * ux;
                        double baseY = ty - ARROW_LEN * uy;
                        double px = -uy;   // unit perpendicular
                        double py = ux;
                        tgt.add(new Line(sx, sy, baseX, baseY, EDGE_STROKE, EDGE_WIDTH));
                        String d = "M " + fmt(tx) + " " + fmt(ty)
                            + " L " + fmt(baseX + ARROW_HALF_W * px) + " " + fmt(baseY + ARROW_HALF_W * py)
                            + " L " + fmt(baseX - ARROW_HALF_W * px) + " " + fmt(baseY - ARROW_HALF_W * py)
                            + " Z";
                        tgt.add(new Path(d, ARROW_FILL));
                        // Edge label: on the OUTSIDE of the edge at its midpoint (transpose of TD) — an
                        // edge going DOWN sits BELOW the midpoint, one going UP or flat sits ABOVE it.
                        double lblY = dy > 0
                            ? (sy + baseY) / 2 + EDGE_LABEL_GAP + EDGE_LABEL_SIZE * 0.7
                            : (sy + baseY) / 2 - EDGE_LABEL_GAP - EDGE_LABEL_SIZE * 0.35;
                        emitEdgeLabel(tgt, e.label(), (sx + baseX) / 2, lblY,
                            false, EDGE_LABEL_GAP, canvasW, textColor, math);
                    }
                    // else degenerate — skip; the (possibly empty) group still emits so seq stays aligned.
                } else {
                    // LONG edge: POLYLINE right-anchor → waypoint centres → left-anchor, head on the last.
                    int k = chain.length;
                    double[] xs = new double[k];
                    double[] ys = new double[k];
                    xs[0] = sx;
                    ys[0] = sy;
                    for (int j = 1; j < k - 1; j++) {
                        int w = chain[j];
                        xs[j] = vx[w] + rt.vWidth[w] / 2;
                        ys[j] = vy[w] + NODE_H / 2;
                    }
                    xs[k - 1] = tx;
                    ys[k - 1] = ty;
                    emitPolyline(tgt, xs, ys);
                    // Edge label anchors on the FIRST segment's midpoint (LR outside rule + clamp).
                    double lblY = (ys[1] - ys[0]) > 0
                        ? (ys[0] + ys[1]) / 2 + EDGE_LABEL_GAP + EDGE_LABEL_SIZE * 0.7
                        : (ys[0] + ys[1]) / 2 - EDGE_LABEL_GAP - EDGE_LABEL_SIZE * 0.35;
                    emitEdgeLabel(tgt, e.label(), (xs[0] + xs[1]) / 2, lblY,
                        false, EDGE_LABEL_GAP, canvasW, textColor, math);
                }
            }
            if (anchored) {
                shapes.add(new Group(assigner.assign(SirentideRole.EDGE, edgeBaseId(nodes, e)), tgt));
            }
        }

        // 2) node boxes (STYLER seam) + 3) centered labels. One `<g role="node">` per node (box + label
        // together); the `else` two-pass path is the never-taken legacy fallback (anchored is always
        // true now). See the TD path for the geometry-unchanged rationale.
        if (anchored) {
            for (int i = 0; i < n; i++) {
                List<Shape> ng = new ArrayList<>();
                styler.emitNode(ng, i, vx[i], vy[i], boxW[i], NODE_H, nodes.get(i).shape(), nodeFill[i]);
                emitNodeLabel(ng, vx[i] + boxW[i] / 2, vy[i] + NODE_H / 2 + LABEL_SIZE * 0.35,
                    measures[i], labels[i], nodeFill[i]);
                shapes.add(new Group(assigner.assign(SirentideRole.NODE, nodeBaseId(nodes.get(i))), ng));
            }
        } else {
            for (int i = 0; i < n; i++) {
                styler.emitNode(shapes, i, vx[i], vy[i], boxW[i], NODE_H, nodes.get(i).shape(), nodeFill[i]);
            }
            for (int i = 0; i < n; i++) {
                emitNodeLabel(shapes, vx[i] + boxW[i] / 2, vy[i] + NODE_H / 2 + LABEL_SIZE * 0.35,
                    measures[i], labels[i], nodeFill[i]);
            }
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// The Sugiyama result shared by TD + LR: the barycenter-ordered layers of ALL vertices (real +
    /// virtual), a per-vertex slot width, the total vertex count, and each edge's routing chain
    /// (`[u, w1, …, wk, v]`; length 2 for a straight span-1 edge; unused/`[u,v]` for a back-edge).
    private record Routing(List<List<Integer>> order, double[] vWidth, int vTotal, List<int[]> chain) {}

    /// Split long forward edges into virtual-waypoint chains, then barycenter-order every layer.
    private static Routing route(int n, List<Edge> edges, boolean[] isBack, int[] layer,
                                 int layerCount, double[] boxW, List<List<Integer>> byLayer) {
        // 1) build chains + mint virtual vertices (ids ≥ n), one per layer a forward edge SKIPS.
        List<int[]> chain = new ArrayList<>(edges.size());
        List<Integer> virtualLayer = new ArrayList<>();   // layer of virtual (n + k)
        int vNext = n;
        for (int ei = 0; ei < edges.size(); ei++) {
            Edge e = edges.get(ei);
            if (isBack[ei]) {
                chain.add(new int[] {e.u(), e.v()});   // back-edge: not routed through waypoints
                continue;
            }
            int lu = layer[e.u()];
            int lv = layer[e.v()];
            if (lv - lu <= 1) {
                chain.add(new int[] {e.u(), e.v()});
                continue;
            }
            int[] c = new int[lv - lu + 1];
            c[0] = e.u();
            for (int L = lu + 1; L < lv; L++) {
                int vid = vNext++;
                virtualLayer.add(L);
                c[L - lu] = vid;
            }
            c[lv - lu] = e.v();
            chain.add(c);
        }
        int vTotal = vNext;

        double[] vWidth = new double[vTotal];
        int[] vLayer = new int[vTotal];
        for (int i = 0; i < n; i++) {
            vWidth[i] = boxW[i];
            vLayer[i] = layer[i];
        }
        for (int k = 0; k < virtualLayer.size(); k++) {
            int vid = n + k;
            vWidth[vid] = VIRTUAL_W;
            vLayer[vid] = virtualLayer.get(k);
        }

        // 2) initial order = real nodes (first-seen, from byLayer) then virtuals appended in mint order.
        List<List<Integer>> order = new ArrayList<>();
        for (int L = 0; L < layerCount; L++) {
            order.add(new ArrayList<>(byLayer.get(L)));
        }
        for (int vid = n; vid < vTotal; vid++) {
            order.get(vLayer[vid]).add(vid);
        }

        // 3) adjacency between consecutive-layer vertices, from chain links only (back-edges excluded
        // from the sweeps — they keep their lanes). up[b] = neighbours a layer ABOVE; down[a] = below.
        List<List<Integer>> up = new ArrayList<>();
        List<List<Integer>> down = new ArrayList<>();
        for (int i = 0; i < vTotal; i++) {
            up.add(new ArrayList<>());
            down.add(new ArrayList<>());
        }
        for (int ei = 0; ei < edges.size(); ei++) {
            if (isBack[ei]) {
                continue;
            }
            int[] c = chain.get(ei);
            for (int j = 0; j + 1 < c.length; j++) {
                down.get(c[j]).add(c[j + 1]);
                up.get(c[j + 1]).add(c[j]);
            }
        }

        // 4) barycenter sweeps (down, up, down, up). A STABLE sort keeps first-seen order on ties.
        int[] pos = new int[vTotal];
        for (List<Integer> row : order) {
            for (int p = 0; p < row.size(); p++) {
                pos[row.get(p)] = p;
            }
        }
        for (int sweep = 0; sweep < SWEEP_PASSES; sweep++) {
            if (sweep % 2 == 0) {
                for (int L = 1; L < layerCount; L++) {
                    sortLayerByBarycenter(order.get(L), up, pos);
                }
            } else {
                for (int L = layerCount - 2; L >= 0; L--) {
                    sortLayerByBarycenter(order.get(L), down, pos);
                }
            }
        }

        return new Routing(order, vWidth, vTotal, chain);
    }

    /// Re-order one layer by the barycenter (mean neighbour position) of each vertex against the
    /// just-fixed adjacent layer. A vertex with no neighbour keeps its current slot (key = current
    /// index), so it never jumps. A STABLE sort resolves equal barycenters to the prior (ultimately
    /// first-seen) order — the determinism the byte-goldens require. `pos` is refreshed for this layer.
    private static void sortLayerByBarycenter(List<Integer> row, List<List<Integer>> neighbors,
                                              int[] pos) {
        if (row.size() > 1) {
            Map<Integer, Double> key = new HashMap<>();
            for (int p = 0; p < row.size(); p++) {
                int v = row.get(p);
                List<Integer> nb = neighbors.get(v);
                double k;
                if (nb.isEmpty()) {
                    k = p;                       // no neighbour → keep the current slot
                } else {
                    double sum = 0;
                    for (int u : nb) {
                        sum += pos[u];
                    }
                    k = sum / nb.size();
                }
                key.put(v, k);
            }
            row.sort((a, b) -> Double.compare(key.get(a), key.get(b)));   // stable (TimSort)
        }
        for (int p = 0; p < row.size(); p++) {
            pos[row.get(p)] = p;
        }
    }

    /// Emit a routed polyline: every segment but the last as a full {@link Line}, then the final
    /// segment truncated at the arrow base with a triangle arrowhead at its tip (reuses the straight-
    /// edge arrowhead math). Points are (xs[i], ys[i]); the arrow points into (xs[last], ys[last]).
    private static void emitPolyline(List<Shape> shapes, double[] xs, double[] ys) {
        int last = xs.length - 1;
        for (int j = 0; j < last - 1; j++) {
            shapes.add(new Line(xs[j], ys[j], xs[j + 1], ys[j + 1], EDGE_STROKE, EDGE_WIDTH));
        }
        double px0 = xs[last - 1];
        double py0 = ys[last - 1];
        double tx = xs[last];
        double ty = ys[last];
        double dx = tx - px0;
        double dy = ty - py0;
        double len = Math.hypot(dx, dy);
        if (!Double.isFinite(len) || len < 1e-6) {
            shapes.add(new Line(px0, py0, tx, ty, EDGE_STROKE, EDGE_WIDTH));   // degenerate — no head
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double baseX = tx - ARROW_LEN * ux;
        double baseY = ty - ARROW_LEN * uy;
        double px = -uy;   // unit perpendicular
        double py = ux;
        shapes.add(new Line(px0, py0, baseX, baseY, EDGE_STROKE, EDGE_WIDTH));
        String d = "M " + fmt(tx) + " " + fmt(ty)
            + " L " + fmt(baseX + ARROW_HALF_W * px) + " " + fmt(baseY + ARROW_HALF_W * py)
            + " L " + fmt(baseX - ARROW_HALF_W * px) + " " + fmt(baseY - ARROW_HALF_W * py)
            + " Z";
        shapes.add(new Path(d, ARROW_FILL));
    }

    /// A layer's laid width: sum of its slot widths + NODE_GAP between consecutive slots.
    private static double rowWidth(List<Integer> row, double[] vWidth) {
        double lw = 0;
        for (int idx : row) {
            lw += vWidth[idx];
        }
        if (row.size() > 1) {
            lw += (row.size() - 1) * NODE_GAP;
        }
        return lw;
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

    /// One resolved subgraph cluster frame: the (ellipsized) `title` plus the border rectangle
    /// `[left,right]×[top,bottom]` (the top band occupies `[top, top+CLUSTER_BAND_H]`). Layout-internal
    /// — {@link #emitClusterFrame} turns it into four border lines + a title band + a title glyph run.
    private record ClusterFrame(String title, double left, double top, double right, double bottom) {}

    /// Resolves each {@link FlowCluster} to a pixel {@link ClusterFrame}: the union of its member node
    /// rects (from `vx`/`vy`/`boxW`), grown by a depth-tightened padding, with a title band reserved
    /// ABOVE the members. A cluster with NO placed member (empty subgraph, or every member filtered)
    /// yields no frame — degenerate → inert (DESIGN §6). Frames are emitted OUTERMOST-first (sorted by
    /// depth) so a nested frame's border/band draws ON TOP of its parent's, never occluded. Returns an
    /// EMPTY list for a cluster-free flowchart, so the emit path adds nothing (byte-identical bake).
    private static List<ClusterFrame> buildClusterFrames(List<FlowCluster> clusters,
                                                         Map<String, Integer> index,
                                                         double[] vx, double[] vy, double[] boxW) {
        if (clusters.isEmpty()) {
            return List.of();   // no subgraphs → no frames, byte-identical to the pre-cluster bake
        }
        List<FlowCluster> ordered = new ArrayList<>(clusters);
        ordered.sort((a, b) -> Integer.compare(a.depth(), b.depth()));   // stable: outer under inner
        List<ClusterFrame> out = new ArrayList<>();
        for (FlowCluster c : ordered) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            boolean any = false;
            for (String id : c.memberNodeIds()) {
                Integer i = index.get(id);
                if (i == null) {
                    continue;   // a member that never placed (defensive) — never throw
                }
                any = true;
                minX = Math.min(minX, vx[i]);
                minY = Math.min(minY, vy[i]);
                maxX = Math.max(maxX, vx[i] + boxW[i]);
                maxY = Math.max(maxY, vy[i] + NODE_H);
            }
            if (!any) {
                continue;   // empty cluster → no frame (inert)
            }
            double pad = Math.max(CLUSTER_MIN_PAD, CLUSTER_PAD - c.depth() * CLUSTER_INSET);
            double left = minX - pad;
            double right = maxX + pad;
            double bottom = maxY + pad;
            double top = minY - pad - CLUSTER_BAND_H;
            String title = FONT.ellipsize(c.title(),
                Math.min(CLUSTER_MAX_TITLE_W, right - left - 2 * CLUSTER_TITLE_PAD), CLUSTER_TITLE_SIZE);
            out.add(new ClusterFrame(title, left, top, right, bottom));
        }
        return out;
    }

    /// Emits one cluster {@link ClusterFrame}: a STROKE-ONLY border (four {@link Line}s — a
    /// {@link Rect} carries fill only), a filled top BAND {@link Rect} holding the (contrast-filled)
    /// title glyph run, all inside the svg/rect/line/path alphabet and clamped in-canvas. Emitted
    /// BEFORE the edges/nodes so the frame sits behind the content it wraps.
    private static void emitClusterFrame(List<Shape> shapes, ClusterFrame f, double canvasW) {
        shapes.add(new Line(f.left(), f.top(), f.right(), f.top(), CLUSTER_STROKE, CLUSTER_STROKE_W));
        shapes.add(new Line(f.left(), f.bottom(), f.right(), f.bottom(), CLUSTER_STROKE, CLUSTER_STROKE_W));
        shapes.add(new Line(f.left(), f.top(), f.left(), f.bottom(), CLUSTER_STROKE, CLUSTER_STROKE_W));
        shapes.add(new Line(f.right(), f.top(), f.right(), f.bottom(), CLUSTER_STROKE, CLUSTER_STROKE_W));
        // Title band — a filled rect across the frame top holding the cluster title.
        shapes.add(new Rect(f.left(), f.top(), f.right() - f.left(), CLUSTER_BAND_H, CLUSTER_BAND_FILL));
        if (f.title() != null && !f.title().isBlank()) {
            double tw = FONT.runWidth(f.title(), CLUSTER_TITLE_SIZE);
            double x = clampLabelX(f.left() + CLUSTER_TITLE_PAD, tw, canvasW);
            double baseline = f.top() + CLUSTER_BAND_H / 2 + CLUSTER_TITLE_SIZE * 0.35;
            String d = FONT.textPathD(f.title(), x, baseline, CLUSTER_TITLE_SIZE);
            if (!d.isBlank()) {
                shapes.add(new GlyphRun(d, Colors.contrastFill(CLUSTER_BAND_FILL)));
            }
        }
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
