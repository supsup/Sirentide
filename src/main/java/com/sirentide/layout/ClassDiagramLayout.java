package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.ClassBox;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ClassRelation;
import com.sirentide.ir.RelationKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Pure UML class-diagram layout: three-compartment class boxes placed in a deterministic grid, wired
/// by relationship edges whose UML marker GLYPH (hollow triangle / filled diamond / hollow diamond /
/// open arrow) sits at the correct end (docs/DESIGN.md §4/§5). The markers are the fidelity crux — a
/// wrong shape at a wrong end reads as "broken" — so each relation kind maps to one exact glyph via
/// {@link #marker}.
///
/// MARKER-GLYPH APPROACH (within the svg/path/rect/line + glyph-path alphabet, no new element/attr):
///   - a FILLED diamond (composition) is one {@link Path} with a solid `fill` — a 4-vertex polygon.
///   - a HOLLOW triangle (inheritance) / HOLLOW diamond (aggregation) is a stroked OUTLINE built from
///     {@link Line} segments (3 for the triangle, 4 for the diamond). Outlines MUST be lines, not a
///     `fill="none"` path: the contract's `<path>` carries no `stroke`, so a hollow path would be
///     INVISIBLE — a stroked line outline is genuinely hollow on ANY background (docs/DESIGN.md §6).
///   - an OPEN arrow (association / dependency) is two {@link Line} barbs meeting at the tip.
///   - a DEPENDENCY additionally draws its edge line DASHED (a run of short {@link Line} segments —
///     the contract has no `stroke-dasharray`, so the dash is baked as segments; deterministic).
///
/// PLACEMENT: a row-major GRID (`ceil(sqrt(n))` columns), boxes sized to their widest compartment
/// line. The slot ORDER is relationship-aware (via {@link GridOrder} — related classes land in
/// adjacent slots) so edges stay short and a straight centre-to-centre edge is far less likely to
/// cross a third box. Edges route straight between box BORDERS (clipped to the rectangle) with the
/// marker at the marked end. The canvas grows to fit the grid + margin so nothing escapes
/// (containment). Deterministic; text baked to glyph paths, markers to paths/lines. RESIDUAL:
/// crossing REDUCTION not MINIMIZATION (DESIGN §7) — a long diagonal edge can still clip a box; a
/// layered "inheritance flows down" pass and orthogonal routing are follow-ups.
public final class ClassDiagramLayout {

    private ClassDiagramLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    private static final double MARGIN = 24;
    private static final double NAME_SIZE = 13;      // the name compartment (slightly larger = emphasis)
    private static final double MEMBER_SIZE = 12;    // attribute / method lines
    private static final double PAD_X = 12;          // horizontal padding inside a box
    private static final double PAD_Y = 6;           // vertical padding per compartment
    private static final double COL_GAP = 52;        // horizontal gap between grid columns
    private static final double ROW_GAP = 52;        // vertical gap between grid rows
    private static final double MIN_BOX_W = 84;
    private static final double MIN_W = 120;         // blank-canvas width (0 classes)
    private static final double MIN_H = 60;
    private static final double MAX_LABEL_W = 240;   // compartment lines ellipsize past this
    // Displayed-member cap per compartment (robustness plan fe8c5bbc #2): disp() ellipsizes each row's
    // WIDTH but nothing bounded the row COUNT, so a class with hundreds of members (up to the parser's
    // MAX_NODES per box) grew an unreadable, canvas-blowing box. Show at most this many rows; a
    // truncated compartment ends with one synthesized "… (N more)" row (legibility + a layout bound).
    static final int MAX_DISPLAYED_ROWS = 30;

    private static final String BOX_FILL = "#eef2ff";    // member-compartment background (pale indigo)
    private static final String NAME_FILL = "#c7d2fe";   // name-compartment band (a shade darker)
    private static final String BORDER = "#475569";      // box border + compartment dividers (slate)
    private static final String MARKER = "#475569";      // relationship marker glyph colour
    private static final String EDGE_STROKE = "#94a3b8"; // relationship edge line
    private static final double BORDER_W = 1;
    private static final double EDGE_WIDTH = 1.5;
    /// Edge/self-loop label type size. Package-private because the self-loop geometry oracle derives the
    /// label metric band (and the baseline it expects on each loop's top leg) from this SAME value, so the
    /// placement and its receipts can never drift apart.
    static final double EDGE_LABEL_SIZE = 10;

    /// Endpoint cardinality type size. Deliberately BELOW {@link #EDGE_LABEL_SIZE}: the relation's
    /// `: label` names the association and the cardinality qualifies it, and UML convention sets the
    /// multiplicity subordinate. Equal sizes read as two competing labels on one edge.
    private static final double MULT_SIZE = 8.5;

    /// Distance stepped ALONG the edge from the border point before drawing a cardinality. Must clear
    /// the longest marker glyph, since a marker caps the border end of the leg and the cardinality
    /// would otherwise sit inside it.
    private static final double MULT_ALONG = 15;

    /// Distance pushed PERPENDICULAR to the edge, so the cardinality clears the stroke it labels.
    private static final double MULT_PERP = 6;

    /// Ceiling on {@link #MULT_ALONG} as a fraction of the leg being annotated, so that on a SHORT
    /// edge the cardinality stays in its own end-third instead of walking onto the midpoint label.
    private static final double MULT_ALONG_FRACTION = 0.3;

    /// Ellipsize ceiling for a cardinality. The parser already bounds one at 64 code points and
    /// shape-filters it, and the longest real UML idiom (`0..* {ordered, nonunique}`) is 25 — so this
    /// is a drawing belt, not the semantic gate.
    private static final double MAX_MULT_W = 72;

    // Marker geometry (px). Length = how far the marker extends back from the box border along the
    // edge; half-width = its perpendicular half-extent. Distinct per family so the shapes read clearly.
    private static final double TRI_LEN = 14;
    private static final double TRI_HALF = 8;
    private static final double DIA_LEN = 16;
    private static final double DIA_HALF = 7;
    private static final double ARR_LEN = 11;
    private static final double ARR_HALF = 5;

    /// Largest perpendicular marker half-extent over this diagram's marker menu (inheritance triangle 8,
    /// composition/aggregation diamond 7, association/dependency arrow 5). The self-loop attach pitch and
    /// the border inset derive from it so adjacent same-side markers can never overprint (sirentide 275);
    /// the geometry oracle imports this SAME value so the pitch and its test can never drift apart.
    static final double MAX_MARKER_HALF = Math.max(TRI_HALF, Math.max(DIA_HALF, ARR_HALF));

    private static final double DASH_ON = 6;   // dependency dash segment length
    private static final double DASH_OFF = 4;  // dependency dash gap length

    // A self-relation (`A <|-- A`) routes a rectilinear loop off the box's RIGHT edge: out this far
    // past the border, down, and back — entirely to the right of the box, so it never re-enters the
    // interior. SELF_LOOP_OUT > every marker length (max DIA_LEN = 16) so the outer leg clears the
    // marker. The row cursor reserves the whole loop LANE (legs + widest label), so neither the
    // next box in the row nor the viewBox edge can collide with it (Lattice re-review, seq 217).
    private static final double SELF_LOOP_OUT = 30;
    // Each ADDITIONAL self-relation on the same box nests one lane further out (distinct vertical
    // legs) …
    private static final double SELF_LOOP_LANE = 14;
    // … and nudges its attach points apart (top attach up, bottom attach down, clamped inside the
    // border span) so the horizontal legs never overpaint either. Each lane's LABEL rides its own
    // top leg (see loopLabelBaselines), with a METRIC floor so an attach-clamp collapse — or a label
    // taller than the lane pitch — can never merge two labels.
    // DERIVED from the marker footprint, NOT a flat constant (sirentide 275): two adjacent same-side
    // markers sit one step apart perpendicular to their horizontal legs, each extending ±MAX_MARKER_HALF,
    // so the pitch must clear 2·MAX_MARKER_HALF + stroke clearance or the glyphs overprint AND a later
    // FUTURE relation repaints part of an earlier ACTIVE one under play-through. The old flat 12 was under
    // the 16px triangle / 14px diamond footprints. CLEARANCE = the marker stroke + an anti-alias epsilon.
    private static final double SELF_LOOP_MARKER_CLEARANCE = 2;
    private static final double SELF_LOOP_ATTACH_STEP = 2 * MAX_MARKER_HALF + SELF_LOOP_MARKER_CLEARANCE;
    // Gap between the outermost loop leg and its label's left edge.
    private static final double SELF_LOOP_LABEL_GAP = 4;
    // Clear vertical gap the METRIC FLOOR leaves between two adjacent loop labels' OCCUPIED BANDS
    // (Marlow sirentide/768 F2): the floor separates consecutive baselines by the upper label's
    // DESCENT + the lower label's ASCENT + this gap, so the bands `[baseline−ascent, baseline+descent]`
    // are disjoint BY CONSTRUCTION whatever the labels measure. The retired fixed EDGE_LABEL_SIZE+2
    // slot ignored per-label metrics, so two tall math fragments emitted overlapping bands.
    private static final double SELF_LOOP_LABEL_BAND_GAP = 2;

    /// Minimum clear corridor between a self-loop label's text box and any NON-LOOP edge segment
    /// crossing the label's x-band (eye-pass finding, plan 64cf1bae): half a label line-height plus a
    /// small gap, so a loop label can never READ as a label ON a neighbour edge — misattribution,
    /// worse than crowding, and invisible to every pure-disjointness receipt (non-overlap is not
    /// unambiguity). {@link SelfLoopLabelColumn} moves each CONFLICTED label — and only those — the
    /// minimum that honours it; the geometry oracle imports this SAME value so the corridor and its
    /// test can never drift apart.
    static final double SELF_LOOP_EDGE_CLEARANCE = FONT.lineHeight(EDGE_LABEL_SIZE) / 2 + 4;

    /// One placed class box: its grid rectangle plus the pre-measured compartment heights and the
    /// (ellipsized) display lines, so the emit pass draws bands/dividers/text without re-measuring.
    /// `nameMeasure`/`attrMeasures`/`methodMeasures` are the composite measures for lines carrying
    /// `$…$` math (null for plain text); `nameRowH`/`attrRowH`/`methodRowH` are the per-row heights —
    /// {@code namePitch}/{@code memberPitch} for a plain or short-math row, GROWN (via
    /// {@link MathLabel#boxHeight}) for a row whose fragment is TALLER than one line (a matrix / cases /
    /// stacked fraction). A row grows iff its height differs from the fixed pitch (plan
    /// sirentide-tall-math-labels — the compartment now consumes the fragment HEIGHT, not just the width).
    private record Placed(ClassBox box, double x, double y, double w, double h,
                          double nameH, double attrH, double methodH,
                          String name, List<String> attrs, List<String> methods,
                          MathLabel.Measured nameMeasure, List<MathLabel.Measured> attrMeasures,
                          List<MathLabel.Measured> methodMeasures,
                          double nameRowH, List<Double> attrRowH, List<Double> methodRowH) {
        double centerX() {
            return x + w / 2;
        }

        double centerY() {
            return y + h / 2;
        }
    }

    public static LaidOut layout(ClassDiagram cd) {
        return layout(cd, null);
    }

    /// The pure layout. `math` (nullable) renders `$…$` runs in the name/member/edge-label text through
    /// the shared {@link MathLabel} seam; a null renderer is the plain-text path (byte-identical bake).
    public static LaidOut layout(ClassDiagram cd, MathFragmentRenderer math) {
        List<ClassBox> classes = cd.classes();
        int n = classes.size();
        if (n == 0) {
            return LaidOut.of(MIN_W, MIN_H);   // a bare `classDiagram` still round-trips as one
        }

        // -- 1) size every box (widest compartment line + padding), first-seen order.
        double[] boxW = new double[n];
        double[] boxH = new double[n];
        double[] nameH = new double[n];
        double[] attrH = new double[n];
        double[] methodH = new double[n];
        String[] names = new String[n];
        List<List<String>> attrLines = new ArrayList<>();
        List<List<String>> methodLines = new ArrayList<>();
        // Composite measures (null for plain lines) + per-row heights (pitch for plain/short math, GROWN
        // for a tall multi-row fragment). Parallel to the string lists; consumed by the emit pass.
        MathLabel.Measured[] nameMeasures = new MathLabel.Measured[n];
        List<List<MathLabel.Measured>> attrMeasures = new ArrayList<>();
        List<List<MathLabel.Measured>> methodMeasures = new ArrayList<>();
        double[] nameRowH = new double[n];
        List<List<Double>> attrRowH = new ArrayList<>();
        List<List<Double>> methodRowH = new ArrayList<>();
        double memberPitch = FONT.lineHeight(MEMBER_SIZE);
        double namePitch = FONT.lineHeight(NAME_SIZE);
        for (int i = 0; i < n; i++) {
            ClassBox b = classes.get(i);
            Disp nd = disp(b.name(), NAME_SIZE, math);
            names[i] = nd.display();
            nameMeasures[i] = nd.measure();
            nameRowH[i] = rowHeight(nd.measure(), namePitch, NAME_SIZE);
            double widest = widthOf(nd.display(), nd.measure(), NAME_SIZE);
            List<String> attrs = new ArrayList<>();
            List<MathLabel.Measured> am = new ArrayList<>();
            List<Double> arh = new ArrayList<>();
            double attrSum = 0;
            for (String a : capMembers(b.attributes())) {
                Disp d = disp(a, MEMBER_SIZE, math);
                attrs.add(d.display());
                am.add(d.measure());
                double rh = rowHeight(d.measure(), memberPitch, MEMBER_SIZE);
                arh.add(rh);
                attrSum += rh;
                widest = Math.max(widest, widthOf(d.display(), d.measure(), MEMBER_SIZE));
            }
            List<String> methods = new ArrayList<>();
            List<MathLabel.Measured> mm = new ArrayList<>();
            List<Double> mrh = new ArrayList<>();
            double methodSum = 0;
            for (String m : capMembers(b.methods())) {
                Disp d = disp(m, MEMBER_SIZE, math);
                methods.add(d.display());
                mm.add(d.measure());
                double rh = rowHeight(d.measure(), memberPitch, MEMBER_SIZE);
                mrh.add(rh);
                methodSum += rh;
                widest = Math.max(widest, widthOf(d.display(), d.measure(), MEMBER_SIZE));
            }
            attrLines.add(attrs);
            methodLines.add(methods);
            attrMeasures.add(am);
            methodMeasures.add(mm);
            attrRowH.add(arh);
            methodRowH.add(mrh);
            boxW[i] = Math.max(MIN_BOX_W, widest + 2 * PAD_X);
            nameH[i] = nameRowH[i] + 2 * PAD_Y;
            // A class with ANY member shows all three compartments; an empty compartment collapses to a
            // thin band (2·PAD_Y). A memberless class collapses to a single name box (no divider bands).
            // Each compartment's height is the SUM of its (possibly grown) row heights + padding — for an
            // all-plain / short-math class every row is one pitch, so this reduces to the pre-growth
            // `count · pitch + 2·PAD_Y` and the box is byte-identical.
            if (b.hasMembers()) {
                attrH[i] = attrSum + 2 * PAD_Y;
                methodH[i] = methodSum + 2 * PAD_Y;
            } else {
                attrH[i] = 0;
                methodH[i] = 0;
            }
            boxH[i] = nameH[i] + attrH[i] + methodH[i];
        }

        // -- 2) grid placement: ceil(sqrt(n)) columns, row-major. The slot ORDER is relationship-aware
        // (related classes land in adjacent slots via {@link GridOrder}) so edges are short and a
        // straight edge is far less likely to cross a third box — quality over the v1 first-seen order,
        // still fully deterministic. Each row's height is its tallest box; boxes march left→right with
        // COL_GAP, rows down with ROW_GAP. Canvas grows to fit (containment: nothing escapes the margin).
        Map<String, Integer> index = new HashMap<>();
        for (int k = 0; k < n; k++) {
            index.put(classes.get(k).name(), k);
        }
        List<int[]> edgeList = new ArrayList<>();
        for (ClassRelation r : cd.relations()) {
            Integer a = index.get(r.left());
            Integer b = index.get(r.right());
            if (a != null && b != null) {
                edgeList.add(new int[] {a, b});
            }
        }
        int[] perm = GridOrder.order(n, edgeList.toArray(new int[0][]));

        // Self-loop lane bookkeeping (Lattice re-review, seq 217): every self-relation on a box
        // occupies a LANE off its right edge — lane k's vertical leg sits k·SELF_LOOP_LANE further
        // out — and each loop's (already-ellipsized) label rides ONE shared column past the node's
        // OUTERMOST leg, at the height of its OWN loop's top leg (Marlow sirentide/761).
        // The row cursor below reserves the node's full lane extent, which is what keeps a loop
        // label from escaping the viewBox (the old grow-pass reserved only the legs) and from
        // running through the next box in the row.
        int[] selfLoops = new int[n];                       // lanes per node
        int[] selfLane = new int[cd.relations().size()];    // this relation's lane on its node
        double[] selfLabelW = new double[n];                // widest label in the node's lane
        for (int e = 0; e < cd.relations().size(); e++) {
            ClassRelation r = cd.relations().get(e);
            Integer li = index.get(r.left());
            if (li == null || !li.equals(index.get(r.right()))) {
                continue;
            }
            selfLane[e] = selfLoops[li]++;
            if (r.label() != null && !r.label().isBlank()) {
                String lbl = FONT.ellipsize(r.label(), MAX_LABEL_W, EDGE_LABEL_SIZE);
                double w = (math != null && MathLabel.hasMath(lbl))
                    ? MathLabel.measure(lbl, EDGE_LABEL_SIZE, FONT, math).width()
                    : FONT.runWidth(lbl, EDGE_LABEL_SIZE);
                selfLabelW[li] = Math.max(selfLabelW[li], w);
            }
        }
        // A box with MULTIPLE self-loop lanes must be TALL enough that the per-lane attach nudges
        // never clamp two lanes onto the same y — clamp-collapsed lanes run COLLINEAR horizontal
        // legs that partially overpaint, so a later FUTURE group paints over an earlier ACTIVE one
        // in play-through, and the stacked labels collapse at the ascent floor (Lattice r3,
        // seq 227, JShell-probed at 3 class / 3 ER lanes). Growing the box keeps every authored
        // relation rendered (rejecting the "unsupported" count would erase valid relations — the
        // original bug class): 0.3·h must clear the border inset plus one ATTACH_STEP per extra
        // lane, which by the 0.3/0.7 symmetry bounds the bottom nudges too.
        for (int i = 0; i < n; i++) {
            if (selfLoops[i] > 1) {
                boxH[i] = Math.max(boxH[i],
                    (MAX_MARKER_HALF + SELF_LOOP_ATTACH_STEP * (selfLoops[i] - 1)) / 0.3);
            }
            // sir288 F1 (class analog of the ER phantom-compartment fix): a memberless class is a
            // SINGLE name-filled band — emitBox's documented invariant is nameH == h — and the
            // multi-lane growth above raised only boxH, leaving a phantom body below the name fill.
            // The grown height belongs to the only real compartment the class has.
            if (attrH[i] == 0 && methodH[i] == 0) {
                nameH[i] = boxH[i];
            }
        }

        int cols = (int) Math.ceil(Math.sqrt(n));
        if (cols < 1) {
            cols = 1;
        }
        double[] px = new double[n];
        double[] py = new double[n];
        double rowTop = MARGIN;
        double canvasW = MIN_W;
        int slot = 0;
        while (slot < n) {
            int rowEnd = Math.min(slot + cols, n);
            double rowH = 0;
            for (int s = slot; s < rowEnd; s++) {
                rowH = Math.max(rowH, boxH[perm[s]]);
            }
            double cursor = MARGIN;
            for (int s = slot; s < rowEnd; s++) {
                int node = perm[s];
                px[node] = cursor;
                py[node] = rowTop;
                // Reserve the node's self-loop LANE (legs + widest label) inside the row, so the
                // next box starts past it AND canvasW (derived from the cursor) contains it.
                cursor += boxW[node] + selfLaneExtent(selfLoops[node], selfLabelW[node]) + COL_GAP;
            }
            canvasW = Math.max(canvasW, cursor - COL_GAP + MARGIN);
            rowTop += rowH + ROW_GAP;
            slot = rowEnd;
        }
        double canvasH = Math.max(MIN_H, rowTop - ROW_GAP + MARGIN);

        // -- neighbour-edge corridor avoidance (eye-pass finding, plan 64cf1bae). A straight (or
        // bent) neighbour edge can cross the x-band where a node's self-loop label fan rides — the
        // reserved lane extent bounds boxes, not edges — and the labels then READ as labels ON that
        // edge (g5, class-self-loops-stacked: A→B threaded between "refines itself" and "delegates").
        // Every non-self route is computed ONCE here and handed to the emit pass, so the corridor the
        // corridor a label avoids can never drift from the edge actually drawn;
        // {@link SelfLoopLabelColumn} then places each node's labels INDIVIDUALLY — every label keeps
        // {@link #SELF_LOOP_EDGE_CLEARANCE} from every segment and box crossing ITS OWN x-band, and a
        // label nothing crosses does not move at all (Marlow sirentide/770). A label with no feasible
        // position above its corridor drops below it, and the growth pass below grows the canvas.
        EdgeRouter.Route[] routes = new EdgeRouter.Route[cd.relations().size()];
        List<double[]> edgeSegments = new ArrayList<>();
        List<double[]> boxRects = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            boxRects.add(new double[] {px[k], py[k], boxW[k], boxH[k]});
        }
        for (int e = 0; e < cd.relations().size(); e++) {
            ClassRelation r = cd.relations().get(e);
            Integer li = index.get(r.left());
            Integer ri = index.get(r.right());
            if (li == null || ri == null || li.equals(ri)) {
                continue;
            }
            double[] lb = clipToRect(px[li] + boxW[li] / 2, py[li] + boxH[li] / 2,
                boxW[li], boxH[li], px[ri] + boxW[ri] / 2, py[ri] + boxH[ri] / 2);
            double[] rb = clipToRect(px[ri] + boxW[ri] / 2, py[ri] + boxH[ri] / 2,
                boxW[ri], boxH[ri], px[li] + boxW[li] / 2, py[li] + boxH[li] / 2);
            List<double[]> others = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                if (k != li && k != ri) {
                    others.add(boxRects.get(k));
                }
            }
            routes[e] = EdgeRouter.route(lb[0], lb[1], rb[0], rb[1], others, canvasW, canvasH);
            if (routes[e].hasBend()) {
                edgeSegments.add(new double[] {lb[0], lb[1], routes[e].wx(), routes[e].wy()});
                edgeSegments.add(new double[] {routes[e].wx(), routes[e].wy(), rb[0], rb[1]});
            } else {
                edgeSegments.add(new double[] {lb[0], lb[1], rb[0], rb[1]});
            }
        }
        // -- the ONE self-loop label-baseline computation (Marlow sirentide/768 F1+F2). Every loop
        // label is measured ONCE here — width + ACTUAL ascent/descent, math fragments included — and
        // the per-lane FINAL baselines are solved from that full set in ONE pass:
        //
        //   1. the METRIC FLOOR ({@link #loopLabelBaselines}) walks the lanes outermost-first (their
        //      ideals descend that way) and separates consecutive baselines by the upper label's
        //      descent + the lower label's ascent + SELF_LOOP_LABEL_BAND_GAP, so the occupied bands
        //      are disjoint BY CONSTRUCTION — the retired fixed slot ignored metrics and let two tall
        //      math labels emit OVERLAPPING bands (F2);
        //   2. the CORRIDOR SOLVE ({@link SelfLoopLabelColumn}) then runs on the metric-floored
        //      stack and returns a FINAL BASELINE PER LABEL. It is PER-LABEL, not one shift for the
        //      set (Marlow sirentide/770): the labels share an x ORIGIN, not an x EXTENT, so a
        //      crossing edge can conflict with a wide label and miss a narrow one entirely, and a
        //      whole-fan dy dragged the unconflicted one off the leg it is supposed to ride. A
        //      conflicted label now moves the MINIMUM that clears its own corridor, in whichever
        //      direction is nearer and feasible; an unconflicted label whose ideal survives its
        //      neighbours' final positions does not move at all. The move is a NAMED degradation of
        //      the association contract, not a silent exception (F1) — a crossing neighbour edge
        //      outranks exact leg-alignment for THAT label, and where clearing it downward forces
        //      the labels below it down too (disjointness), that cascade is contract as well.
        //      Solving over the FLOORED stack is what makes the two degradations compose in one
        //      place — the retired shape re-derived a metrics-blind ideal at three separate sites.
        //
        // The result lands in `selfLabelBaseline[e]`, indexed by relation, and BOTH the canvas-growth
        // reservation below and emitSelfLoop CONSUME it — emission recomputes nothing, so a
        // reservation can never be smaller than the emission it covers.
        int relCount = cd.relations().size();
        boolean[] selfLabeled = new boolean[relCount];
        double[] selfLabelAsc = new double[relCount];
        double[] selfLabelDesc = new double[relCount];
        double[] selfLabelX0 = new double[relCount];
        double[] selfLabelX1 = new double[relCount];
        double[] selfLabelBaseline = new double[relCount];
        double[][][] laneMetrics = new double[n][][];   // [node][lane] = {ascent, descent}, null = unlabeled
        for (int k = 0; k < n; k++) {
            laneMetrics[k] = new double[Math.max(selfLoops[k], 1)][];
        }
        for (int e = 0; e < relCount; e++) {
            ClassRelation r = cd.relations().get(e);
            Integer li = index.get(r.left());
            if (li == null || !li.equals(index.get(r.right()))
                || r.label() == null || r.label().isBlank()) {
                continue;
            }
            String lbl = FONT.ellipsize(r.label(), MAX_LABEL_W, EDGE_LABEL_SIZE);
            double w;
            double asc;
            double desc;
            if (math != null && MathLabel.hasMath(lbl)) {
                MathLabel.Measured m = MathLabel.measure(lbl, EDGE_LABEL_SIZE, FONT, math);
                w = m.width();
                asc = m.ascent();
                desc = m.descent();
            } else {
                w = FONT.runWidth(lbl, EDGE_LABEL_SIZE);
                asc = FONT.ascent(EDGE_LABEL_SIZE);
                desc = FONT.descent(EDGE_LABEL_SIZE);
            }
            double labelX = px[li] + boxW[li] + SELF_LOOP_OUT + (selfLoops[li] - 1) * SELF_LOOP_LANE
                + SELF_LOOP_LABEL_GAP;
            double originX = Math.max(2, Math.min(labelX, canvasW - 2 - w));
            selfLabeled[e] = true;
            selfLabelAsc[e] = asc;
            selfLabelDesc[e] = desc;
            selfLabelX0[e] = originX;
            selfLabelX1[e] = originX + w;
            laneMetrics[li][selfLane[e]] = new double[] {asc, desc};
        }
        double[][] laneBaseline = new double[n][];      // step 1: the metric-floored stack per node
        for (int k = 0; k < n; k++) {
            laneBaseline[k] = loopLabelBaselines(py[k], boxH[k], laneMetrics[k]);
        }
        List<List<SelfLoopLabelColumn.LoopLabel>> columns = new ArrayList<>();
        List<List<Integer>> columnRel = new ArrayList<>();   // column entry → its relation index
        for (int k = 0; k < n; k++) {
            columns.add(new ArrayList<>());
            columnRel.add(new ArrayList<>());
        }
        for (int e = 0; e < relCount; e++) {
            if (selfLabeled[e]) {
                int li = index.get(cd.relations().get(e).left());
                columns.get(li).add(new SelfLoopLabelColumn.LoopLabel(selfLane[e],
                    selfLabelX0[e], selfLabelX1[e], selfLabelAsc[e], selfLabelDesc[e],
                    laneBaseline[li][selfLane[e]]));
                columnRel.get(li).add(e);
            }
        }
        for (int i = 0; i < n; i++) {           // step 2: the per-label corridor solve on that stack
            if (columns.get(i).isEmpty()) {
                continue;
            }
            double[] solved = SelfLoopLabelColumn.place(columns.get(i), edgeSegments, boxRects,
                SELF_LOOP_EDGE_CLEARANCE, SELF_LOOP_LABEL_BAND_GAP);
            for (int j = 0; j < solved.length; j++) {
                int e = columnRel.get(i).get(j);
                selfLabelBaseline[e] = Math.max(selfLabelAsc[e] + 2, solved[j]);
            }
        }

        // The row cursor above reserved every self-loop lane HORIZONTALLY. What can still poke past
        // the canvas is a MATH label's ascent/descent (a tall fraction at EDGE_LABEL_SIZE), a
        // metric-floored lane pushed below its ideal, or a corridor-shifted fan dropped below the
        // bottom row — so the BOTTOM grows from the SAME per-lane baselines emitSelfLoop draws with
        // (one carrier, no second copy) plus each label's own measured descent. Every labeled lane is
        // visited, so the whole floored + shifted stack is covered.
        for (int e = 0; e < relCount; e++) {
            if (selfLabeled[e]) {
                canvasH = Math.max(canvasH, selfLabelBaseline[e] + selfLabelDesc[e] + 2);
            }
        }

        Placed[] placed = new Placed[n];
        for (int k = 0; k < n; k++) {
            placed[k] = new Placed(classes.get(k), px[k], py[k], boxW[k], boxH[k],
                nameH[k], attrH[k], methodH[k], names[k], attrLines.get(k), methodLines.get(k),
                nameMeasures[k], attrMeasures.get(k), methodMeasures.get(k),
                nameRowH[k], attrRowH.get(k), methodRowH.get(k));
        }

        List<Shape> shapes = new ArrayList<>();

        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): each relation → ONE
        // `<g role="edge">` (id = the left-right class-name pair), each class → ONE `<g role="class">`
        // (id = the class name). Relations emit first, so seq runs 0..R-1 over relations then R..R+n-1
        // over classes — the deterministic emit-order index. Grouping is additive: geometry unchanged.
        AnchorAssigner assigner = new AnchorAssigner();

        // -- 3) relationship edges + markers FIRST (under the boxes, so a box border cleanly caps the
        // line while the marker — which sits in the gap between boxes — stays visible on top). Each
        // relation's edge line + marker + label collect into ONE `<g role="edge">`.
        for (int e = 0; e < cd.relations().size(); e++) {
            ClassRelation r = cd.relations().get(e);
            Integer li = index.get(r.left());
            Integer ri = index.get(r.right());
            if (li == null || ri == null) {
                continue;   // a relation to an unplaced class (defensive) — skip, never throw
            }
            List<Shape> eg = new ArrayList<>();
            emitRelation(eg, placed, li, ri, r, cd.textColor(), canvasW, canvasH, math,
                selfLane[e], selfLoops[li], routes[e], selfLabelBaseline[e]);
            shapes.add(new Group(assigner.assign(SirentideRole.EDGE, r.left() + "-" + r.right()), eg));
        }

        // -- 4) + 5) each class → ONE `<g role="class">` folding its box geometry (background rect, name
        // band, border, compartment dividers) AND its text (name centered, members left-aligned). The
        // grid places boxes in disjoint slots and a box's text never leaves its box, so folding the box
        // and text per class (vs the old two passes) introduces no cross-class z-order change: geometry
        // byte-identical, visually identical (the same fold the flowchart nodes use).
        for (int k = 0; k < n; k++) {
            List<Shape> cg = new ArrayList<>();
            emitBox(cg, placed[k]);
            emitBoxText(cg, placed[k], math);
            shapes.add(new Group(assigner.assign(SirentideRole.CLASS, placed[k].box().name()), cg));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Measures a display line's width, routing `$…$` through {@link MathLabel} when a renderer is
    /// present (so a math-bearing member sizes its box correctly), else plain glyph advance.
    private static double measure(String line, double size, MathFragmentRenderer math) {
        if (math != null && MathLabel.hasMath(line)) {
            return MathLabel.measure(line, size, FONT, math).width();
        }
        return FONT.runWidth(line, size);
    }

    /// The DISPLAY form of a raw compartment line plus its composite measure. A `$…$` line (with a
    /// renderer) SKIPS ellipsization — a formula must never be cut mid-run, which would break the `$…$`
    /// delimiters and silently drop the math (the reason the class/ER inline-math previously only worked
    /// for short fragments) — and is measured as a composite; a plain line is ellipsized to MAX_LABEL_W
    /// and carries a `null` measure (the byte-identical fixed-pitch text path). Mirrors the flowchart
    /// Bound a compartment to {@link #MAX_DISPLAYED_ROWS} rows (robustness fe8c5bbc #2). A compartment
    /// within the cap is returned unchanged; a longer one keeps its first {@code cap-1} members and
    /// ends with a single synthesized "… (N more)" row — so the box stays readable and bounded even
    /// for a class near the parser's per-box member ceiling. The synthesized row is an ordinary
    /// display string (it flows through {@link #disp} + measurement like any member).
    private static List<String> capMembers(List<String> members) {
        if (members.size() <= MAX_DISPLAYED_ROWS) {
            return members;
        }
        List<String> out = new ArrayList<>(members.subList(0, MAX_DISPLAYED_ROWS - 1));
        out.add("… (" + (members.size() - (MAX_DISPLAYED_ROWS - 1)) + " more)");
        return out;
    }

    /// engine's math-skips-ellipsize rule.
    private record Disp(String display, MathLabel.Measured measure) {}

    private static Disp disp(String raw, double size, MathFragmentRenderer math) {
        if (math != null && MathLabel.hasMath(raw)) {
            return new Disp(raw, MathLabel.measure(raw, size, FONT, math));
        }
        return new Disp(FONT.ellipsize(raw, MAX_LABEL_W, size), null);
    }

    /// A line's advance width from its (possibly null) composite measure — the fragment's composite
    /// width when it carries math, else the plain glyph advance. Keeps the box-sizing byte-identical for
    /// plain text (same value the old {@link #measure} returned).
    private static double widthOf(String line, MathLabel.Measured m, double size) {
        return m != null ? m.width() : FONT.runWidth(line, size);
    }

    /// The height ONE compartment row should occupy: the fixed `pitch` for a plain / short-math row
    /// (byte-identical), else the fragment's grown height via {@link MathLabel#boxHeight} when it is
    /// TALLER than one line (a matrix / cases / stacked fraction). The seam owns the growth policy; a
    /// `null` measure (plain row) always yields `pitch`. `rowH != pitch` iff the row grew.
    private static double rowHeight(MathLabel.Measured m, double pitch, double size) {
        return m != null ? MathLabel.boxHeight(m, pitch, size, FONT) : pitch;
    }

    /// Emits one class box's geometry: the member-compartment background rect, the name-band rect, the
    /// four border lines, and (for a populated class) the two compartment dividers (name/attributes and
    /// attributes/methods). A memberless class is a single name-filled box with no dividers.
    private static void emitBox(List<Shape> shapes, Placed p) {
        boolean populated = p.box().hasMembers();
        // Background for the member compartments (only meaningful when populated; harmless otherwise).
        shapes.add(new Rect(p.x(), p.y(), p.w(), p.h(), BOX_FILL));
        // Name band across the top (covers the whole box for a memberless class, since nameH == h).
        shapes.add(new Rect(p.x(), p.y(), p.w(), p.nameH(), NAME_FILL));
        // Border: four lines (a Rect carries fill only — no stroke — so the frame is drawn as lines,
        // exactly like the flowchart cluster frames).
        double x0 = p.x();
        double y0 = p.y();
        double x1 = p.x() + p.w();
        double y1 = p.y() + p.h();
        shapes.add(new Line(x0, y0, x1, y0, BORDER, BORDER_W));
        shapes.add(new Line(x0, y1, x1, y1, BORDER, BORDER_W));
        shapes.add(new Line(x0, y0, x0, y1, BORDER, BORDER_W));
        shapes.add(new Line(x1, y0, x1, y1, BORDER, BORDER_W));
        if (populated) {
            double divA = p.y() + p.nameH();                    // name | attributes
            double divB = p.y() + p.nameH() + p.attrH();        // attributes | methods
            shapes.add(new Line(x0, divA, x1, divA, BORDER, BORDER_W));
            shapes.add(new Line(x0, divB, x1, divB, BORDER, BORDER_W));
        }
    }

    /// Emits a box's text: the name centered in its band, then the attribute lines and method lines
    /// left-aligned in their compartments, top-to-bottom (glyph paths / MathBoxes via {@link MathLabel}).
    private static void emitBoxText(List<Shape> shapes, Placed p, MathFragmentRenderer math) {
        double cx = p.centerX();
        double namePitch = FONT.lineHeight(NAME_SIZE);
        // Name — centered in the name band. A GROWN name row (a tall fragment) centers the fragment ink
        // in the whole band via {@link MathLabel#baselineInBox}; a plain / short-math name keeps the
        // EXACT legacy baseline (band midpoint), so its bytes are unchanged.
        double nameBaseline = p.nameRowH() != namePitch
            ? MathLabel.baselineInBox(p.nameMeasure(), p.y(), p.nameH())
            : p.y() + p.nameH() / 2 + NAME_SIZE * 0.35;
        emitLine(shapes, p.name(), cx, nameBaseline, NAME_SIZE, true, p.w(),
            Colors.contrastFill(NAME_FILL), math);
        if (!p.box().hasMembers()) {
            return;
        }
        double memberPitch = FONT.lineHeight(MEMBER_SIZE);
        double ascent = FONT.ascent(MEMBER_SIZE);
        double leftX = p.x() + PAD_X;
        // Attributes — march a cursor by each row's (possibly grown) height. A plain / short-math row
        // takes one `memberPitch` and lands at the EXACT legacy baseline (`rowTop + ascent`); a tall
        // fragment grows its row and centers its ink via {@link MathLabel#baselineInBox}, pushing the
        // rows below it down. All-plain classes march by memberPitch → byte-identical.
        double y = p.y() + p.nameH() + PAD_Y;
        for (int k = 0; k < p.attrs().size(); k++) {
            double rowH = p.attrRowH().get(k);
            double baseline = rowH != memberPitch
                ? MathLabel.baselineInBox(p.attrMeasures().get(k), y, rowH)
                : y + ascent;
            emitLine(shapes, p.attrs().get(k), leftX, baseline, MEMBER_SIZE, false, p.w(),
                Colors.contrastFill(BOX_FILL), math);
            y += rowH;
        }
        // Methods — same row-marching in the methods compartment.
        y = p.y() + p.nameH() + p.attrH() + PAD_Y;
        for (int k = 0; k < p.methods().size(); k++) {
            double rowH = p.methodRowH().get(k);
            double baseline = rowH != memberPitch
                ? MathLabel.baselineInBox(p.methodMeasures().get(k), y, rowH)
                : y + ascent;
            emitLine(shapes, p.methods().get(k), leftX, baseline, MEMBER_SIZE, false, p.w(),
                Colors.contrastFill(BOX_FILL), math);
            y += rowH;
        }
    }

    /// Emits one text line as glyph paths (or a MathBox composite when it carries `$…$` and a renderer
    /// is present). `centered` places it around `anchorX`; otherwise `anchorX` is the LEFT origin.
    private static void emitLine(List<Shape> shapes, String text, double anchorX, double baselineY,
                                 double size, boolean centered, double boxW, String fill,
                                 MathFragmentRenderer math) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (math != null && MathLabel.hasMath(text)) {
            MathLabel.Measured m = MathLabel.measure(text, size, FONT, math);
            double originX = centered ? anchorX - m.width() / 2 : anchorX;
            MathLabel.emit(m, originX, baselineY, fill, size, FONT, shapes);
            return;
        }
        double w = FONT.runWidth(text, size);
        double originX = centered ? anchorX - w / 2 : anchorX;
        String d = FONT.textPathD(text, originX, baselineY, size);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }

    /// Routes one relationship between two placed boxes: the edge line between box borders (dashed for a
    /// dependency) + its UML marker at the marked end + an optional `: label` at the edge midpoint. The
    /// `route` was computed ONCE in the layout pre-pass ({@link EdgeRouter} bends around a third box via
    /// one waypoint; the marker then points along the first/last leg) — the SAME segments the label-fan
    /// corridor solver treated as obstacles, so avoided and drawn geometry can never drift apart.
    private static void emitRelation(List<Shape> shapes, Placed[] placed, int li, int ri,
                                     ClassRelation r, String textColor, double canvasW, double canvasH,
                                     MathFragmentRenderer math, int lane, int laneCount,
                                     EdgeRouter.Route route, double labelBaseline) {
        // Self-relation (`A <|-- A`): both endpoints are the same box. A zero-length straight edge would
        // put clipToRect at the box center for BOTH ends and draw the marker INSIDE the box — and merely
        // SKIPPING it erases a semantically-valid recursive relationship AND leaves a phantom empty edge
        // group owning an anchor. Instead route a deterministic on-canvas self-LOOP off the right edge,
        // in this relation's own lane (`lane` of the node's `laneCount`; layout reserved the extent).
        if (li == ri) {
            emitSelfLoop(shapes, placed[li], r, textColor, canvasW, canvasH, math, lane, laneCount,
                labelBaseline);
            return;
        }
        double[] lb = {route.sx(), route.sy()};   // left box border point
        double[] rb = {route.ex(), route.ey()};   // right box border point

        // The first onward point from the left border and the previous point into the right border are
        // the waypoint when bent, else the opposite border — so the marker points ALONG the leg it caps.
        double lNextX = route.hasBend() ? route.wx() : rb[0];
        double lNextY = route.hasBend() ? route.wy() : rb[1];
        double rPrevX = route.hasBend() ? route.wx() : lb[0];
        double rPrevY = route.hasBend() ? route.wy() : lb[1];

        double edgeStartX;
        double edgeStartY;
        double edgeEndX;
        double edgeEndY;
        if (r.kind().markerAtLeft()) {
            // Marker at the LEFT operand (whole/parent). dir points from the left border along the edge.
            double[] dir = unit(lNextX - lb[0], lNextY - lb[1]);
            List<Shape> mk = marker(r.kind(), lb[0], lb[1], dir[0], dir[1], MARKER);
            double markLen = markerLength(r.kind());
            edgeStartX = lb[0] + dir[0] * markLen;
            edgeStartY = lb[1] + dir[1] * markLen;
            edgeEndX = rb[0];
            edgeEndY = rb[1];
            emitEdgeChain(shapes, edgeStartX, edgeStartY, edgeEndX, edgeEndY, route, r.kind().dashed());
            shapes.addAll(mk);
        } else {
            // Marker at the RIGHT operand (arrow target). dir points from the right border along the edge.
            double[] dir = unit(rPrevX - rb[0], rPrevY - rb[1]);
            List<Shape> mk = marker(r.kind(), rb[0], rb[1], dir[0], dir[1], MARKER);
            double markLen = markerLength(r.kind());
            edgeStartX = lb[0];
            edgeStartY = lb[1];
            edgeEndX = rb[0] + dir[0] * markLen;
            edgeEndY = rb[1] + dir[1] * markLen;
            emitEdgeChain(shapes, edgeStartX, edgeStartY, edgeEndX, edgeEndY, route, r.kind().dashed());
            shapes.addAll(mk);
        }

        // Optional `: label` — at the bend (when routed) else the straight midpoint, clamped in-canvas.
        if (r.label() != null && !r.label().isBlank()) {
            double midX = route.hasBend() ? route.wx() : (edgeStartX + edgeEndX) / 2;
            double midY = (route.hasBend() ? route.wy() : (edgeStartY + edgeEndY) / 2) - 3;
            String lbl = FONT.ellipsize(r.label(), MAX_LABEL_W, EDGE_LABEL_SIZE);
            double w = (math != null && MathLabel.hasMath(lbl))
                ? MathLabel.measure(lbl, EDGE_LABEL_SIZE, FONT, math).width()
                : FONT.runWidth(lbl, EDGE_LABEL_SIZE);
            double originX = Math.max(2, Math.min(midX - w / 2, canvasW - 2 - w));
            emitLine(shapes, lbl, originX, midY, EDGE_LABEL_SIZE, false, canvasW, textColor, math);
        }
        // UML multiplicities, at the ENDPOINT each one annotates (plan 35bccb97). Placed from the
        // BORDER POINT and the same onward point the marker already derived its direction from, so
        // the cardinality rides the leg it belongs to whether or not the route bends — no second
        // derivation of the geometry, which is the failure this file already paid for once
        // (Marlow sirentide/768 F1).
        // The FORWARD direction of each leg, both in left→right traversal order, so the one
        // perpendicular derived inside names the same side of the stroke at both ends.
        double[] fwdL = unit(lNextX - lb[0], lNextY - lb[1]);
        double[] fwdR = unit(rb[0] - rPrevX, rb[1] - rPrevY);
        emitMultiplicity(shapes, r.leftMultiplicity(), lb[0], lb[1], lNextX, lNextY,
            fwdL[0], fwdL[1], 1, canvasW, textColor);
        emitMultiplicity(shapes, r.rightMultiplicity(), rb[0], rb[1], rPrevX, rPrevY,
            fwdR[0], fwdR[1], -1, canvasW, textColor);
    }

    /// How far along its own leg an endpoint cardinality is drawn, given that leg's length.
    ///
    /// THE INVARIANT, and it is the reason this is a named function rather than a `Math.min` inline:
    /// a cardinality must NEVER REACH THE MIDPOINT OF ITS OWN LEG, because the midpoint is exactly
    /// where the relation's `: label` is drawn. A flat step satisfies that on a long edge and fails
    /// on a short one — the first cut of this feature rendered a literal `0..*places` on a pair of
    /// adjacent boxes — so the step is capped as a FRACTION of the leg and the guarantee holds at
    /// every length rather than at the lengths that happened to be on screen.
    ///
    /// With `MULT_ALONG_FRACTION < 0.5` the invariant is arithmetic, not a coincidence of constants:
    /// below the knee `along = fraction·len < len/2`, and above it `along = MULT_ALONG`, which is
    /// smaller still because the knee is where the two are equal.
    static double multiplicityAlong(double legLen) {
        return Math.min(MULT_ALONG, legLen * MULT_ALONG_FRACTION);
    }

    /// The UNIT NORMAL naming which side of a stroke an endpoint cardinality sits on, given that
    /// leg's direction `(fx, fy)`. Extracted and package-private because it is the part of this
    /// feature that was wrong TWICE, in opposite directions, with both cuts looking reasonable in
    /// the source — so it is pinned directly rather than only through a render.
    ///
    /// The contract is stated in SCREEN space, deliberately, because the thing it must avoid is
    /// stated in screen space: the relation's `: label` offsets by a flat `-3` in y, so it is above
    /// the stroke whichever way the edge runs. Hence:
    ///
    ///   - the normal always points DOWN-screen (`y > 0`), putting the cardinality opposite the label;
    ///   - for a VERTICAL leg the normal is horizontal, so `y == 0` and the tie is broken RIGHTWARD
    ///     — on a vertical edge the label sits ON the stroke, and either side clears it, so the only
    ///     requirement is that the choice be deterministic and the same at both ends.
    ///
    /// Both endpoints of one relation call this with their own leg direction and therefore agree by
    /// RULE. They do not agree by shared derivation, which is what the first cut assumed and what
    /// made its failure invisible on a symmetric edge.
    static double[] multiplicitySide(double fx, double fy) {
        double px = -fy;
        double py = fx;
        if (py < 0 || (py == 0 && px < 0)) {
            px = -px;
            py = -py;
        }
        return new double[] {px, py};
    }

    /// Draw ONE endpoint cardinality near the border point it annotates, stepped {@link #MULT_ALONG}
    /// along the edge (clear of the box border and of any marker glyph, which caps at
    /// {@code markerLength} ≤ {@link #MULT_ALONG}) and pushed {@link #MULT_PERP} off the line so the
    /// text never sits ON the stroke it labels.
    ///
    /// THE OFFSET IS PERPENDICULAR, NOT VERTICAL, and that is the whole reason this is a method
    /// rather than two inline y-nudges. The midpoint `: label` above lifts by a flat `-3` in y, which
    /// is right for a horizontal edge and puts the text straight through a VERTICAL one. Endpoint
    /// cardinalities land on both, so the offset rotates with the edge: `perp = (dy, −dx)` reads
    /// ABOVE a rightward edge and to the RIGHT of a downward edge.
    ///
    /// No reservation pass: this matches the midpoint label's own discipline on non-self edges, which
    /// clamps into the canvas rather than growing it. A cardinality is bounded at parse — 64 code
    /// points ceiling, shape-filtered to `term` or `term..term` — so it cannot be the thing that
    /// overruns a canvas the way an arbitrary MATH label can.
    private static void emitMultiplicity(List<Shape> shapes, String raw, double bx, double by,
                                         double towardX, double towardY, double fx, double fy,
                                         double sign, double canvasW, String textColor) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String lbl = FONT.ellipsize(raw, MAX_MULT_W, MULT_SIZE);
        double w = FONT.runWidth(lbl, MULT_SIZE);
        double legLen = Math.hypot(towardX - bx, towardY - by);
        double along = multiplicityAlong(legLen);
        // WHICH SIDE OF THE STROKE, AS A RULE RATHER THAN A DERIVED VALUE. Two earlier cuts got this
        // wrong in opposite directions and both were geometrically defensible, which is the tell
        // that the side is not derivable from the edge at all:
        //
        //   cut 1 — perp from each endpoint's own OUTWARD direction. The two ends face opposite
        //           ways, so perp flipped sign between them and one relation's cardinalities
        //           straddled the line; the left one landed back on the `: label`.
        //   cut 2 — perp from ONE forward direction for both ends. Consistent, and consistently
        //           WRONG on this diagram: forward here points leftward, so both went ABOVE — which
        //           is exactly where the label lives.
        //
        // The label's own offset is a flat `-3` in SCREEN y, not in edge space: it is above the
        // stroke whichever way the edge runs. So the cardinality's side must be stated in screen
        // space too. Take `perp = (−fy, fx)` and orient it DOWNWARD; for a vertical edge (perp
        // horizontal, and the label sits ON the stroke) orient it RIGHTWARD instead. Both ends then
        // agree because they follow the same rule, not because their inputs happened to match.
        double[] side = multiplicitySide(fx, fy);
        double ax = bx + fx * along * sign + side[0] * MULT_PERP;
        double ay = by + fy * along * sign + side[1] * MULT_PERP;
        double originX = Math.max(2, Math.min(ax - w / 2, canvasW - 2 - w));
        double baseline = Math.max(FONT.ascent(MULT_SIZE) + 2, ay + FONT.ascent(MULT_SIZE) * 0.35);
        emitLine(shapes, lbl, originX, baseline, MULT_SIZE, false, canvasW, textColor, null);
    }

    /// Routes a SELF-relation (`A <|-- A`) as a deterministic rectilinear LOOP off the box's RIGHT edge,
    /// so a recursive relationship renders on-canvas instead of being erased. Geometry, all derived from
    /// the box rectangle and the relation's LANE (Lattice re-review, seq 217 — multiple self-relations
    /// each take their own lane instead of overpainting): two attach points on the right border (0.3·h
    /// and 0.7·h, nudged apart per lane via {@link #loopExitY}/{@link #loopReturnY}), a leg out to
    /// {@code x+w+SELF_LOOP_OUT+lane·SELF_LOOP_LANE}, a leg down, and a leg back to the marked border
    /// point. Every point has x ≥ the right border, so the loop NEVER crosses the box interior, and the
    /// layout reserved the whole lane extent (legs + widest label) in the row cursor, so nothing here
    /// can escape the viewBox or run through a neighbor. The UML {@link #marker} honours the kind's
    /// {@link RelationKind#markerAtLeft()} operand exactly like a straight edge: the LEFT operand maps
    /// to the TOP attach (mirroring the ER twin's left-cardinality-at-top), the RIGHT to the BOTTOM —
    /// tip on the border, pointing outward. The LABEL is placed by Y-ASSOCIATION (Marlow
    /// sirentide/761): x in ONE constant column {@code SELF_LOOP_LABEL_GAP} past the node's OUTERMOST
    /// leg — clear of every lane line by construction, whatever the label's width — and its baseline
    /// the CARRIED `labelBaseline`: the layout pre-pass solved the node's whole label column ONCE from
    /// the full label set (metric floor then the PER-LABEL corridor solve, {@link #loopLabelBaselines}
    /// + {@link SelfLoopLabelColumn}) and this pass CONSUMES that value, recomputing nothing — the single
    /// carrier the growth reservation also read, so avoided, reserved and drawn geometry cannot drift
    /// — and so the corridor degradation is stated ONCE, as contract, instead of composing the
    /// association away unnoticed (Marlow sirentide/768 F1). The ascent floor and canvas clamps are
    /// re-applied here as belts, identically to the reservation.
    private static void emitSelfLoop(List<Shape> shapes, Placed box, ClassRelation r, String textColor,
                                     double canvasW, double canvasH, MathFragmentRenderer math,
                                     int lane, int laneCount, double labelBaseline) {
        double x1 = box.x() + box.w();                    // right border
        double ay = loopExitY(box.y(), box.h(), lane);    // top attach (LEFT operand's end)
        double by = loopReturnY(box.y(), box.h(), lane);  // bottom attach (RIGHT operand's end)
        double out = x1 + SELF_LOOP_OUT + lane * SELF_LOOP_LANE;   // this lane's vertical leg
        boolean dashed = r.kind().dashed();
        // Marker ownership follows the authored operand (seq 217 finding 4): a whole/parent kind
        // (markerAtLeft) caps the TOP attach; an arrow/plain kind caps the BOTTOM. Tip on the border,
        // pointing OUTWARD (+x, away from the box) either way; the capped leg starts past the marker.
        boolean markTop = r.kind().markerAtLeft();
        double markY = markTop ? ay : by;
        List<Shape> mk = marker(r.kind(), x1, markY, 1, 0, MARKER);
        double markLen = markerLength(r.kind());
        // Three rectilinear legs: exit → out, down, back — the marked end's leg leaves markLen free.
        emitEdgeLine(shapes, markTop ? x1 + markLen : x1, ay, out, ay, dashed);
        emitEdgeLine(shapes, out, ay, out, by, dashed);
        emitEdgeLine(shapes, out, by, markTop ? x1 : x1 + markLen, by, dashed);
        shapes.addAll(mk);
        // Optional `: label` — Y-ASSOCIATION (Marlow sirentide/761; the old x-staircase put every label
        // beyond the OUTERMOST leg in a detached block with no geometric tie to its own loop). X: ONE
        // constant column for all of the node's loop labels, SELF_LOOP_LABEL_GAP past the outermost
        // vertical leg — an x-band INSIDE a lane is impossible (a label runs up to MAX_LABEL_W, far
        // wider than the lane pitch, so it would cross the outer legs), and out here every label clears
        // every lane line AND every border marker (markers cap at x1 + markerLength ≤ x1 +
        // SELF_LOOP_OUT) by construction. Y is what associates, and it is NOT computed here: the
        // layout pre-pass solved the node's whole fan in one place — each lane's ideal (its own top
        // leg, optically centred), the METRIC FLOOR that keeps adjacent occupied bands disjoint from
        // the labels' actual ascent/descent, then the PER-LABEL corridor solve over that floored stack —
        // and handed the answer down as `labelBaseline`. Re-deriving any part of it here is what let
        // the contract composed away unnoticed (Marlow sirentide/768 F1): with the ideal recomputed
        // at three sites, "rides its own leg" and "clears the corridor" could not both be stated
        // about the same number. The ascent floor and canvas clamps below are belts, applied
        // identically at reservation.
        if (r.label() != null && !r.label().isBlank()) {
            String lbl = FONT.ellipsize(r.label(), MAX_LABEL_W, EDGE_LABEL_SIZE);
            double w;
            double asc;
            if (math != null && MathLabel.hasMath(lbl)) {
                MathLabel.Measured m = MathLabel.measure(lbl, EDGE_LABEL_SIZE, FONT, math);
                w = m.width();
                asc = m.ascent();
            } else {
                w = FONT.runWidth(lbl, EDGE_LABEL_SIZE);
                asc = FONT.ascent(EDGE_LABEL_SIZE);
            }
            double labelX = x1 + SELF_LOOP_OUT + (laneCount - 1) * SELF_LOOP_LANE
                + SELF_LOOP_LABEL_GAP;
            double originX = Math.max(2, Math.min(labelX, canvasW - 2 - w));
            double baseline = Math.max(asc + 2, labelBaseline);
            emitLine(shapes, lbl, originX, baseline, EDGE_LABEL_SIZE, false, canvasW, textColor, math);
        }
        // Cardinalities on a SELF-relation, at the two attach points this method already named:
        // the top attach is the LEFT operand's end and the bottom is the RIGHT's. Without this a
        // self-relation would draw its `: label` and silently drop its multiplicities — support
        // that is real on four edge shapes and absent on the fifth, which is the partial-coverage
        // failure this codebase keeps finding in other people's work.
        //
        // The two ends take OPPOSITE vertical offsets rather than the shared side the straight-edge
        // path uses. Both loop legs run rightward out of the same border, so a shared side would put
        // one cardinality on top of a stroke; lifting the top attach and dropping the bottom one
        // clears both legs and separates the pair from each other.
        emitLoopMultiplicity(shapes, r.leftMultiplicity(), x1 + MULT_ALONG,
            ay - MULT_PERP, canvasW, textColor);
        emitLoopMultiplicity(shapes, r.rightMultiplicity(), x1 + MULT_ALONG,
            by + MULT_PERP + FONT.ascent(MULT_SIZE), canvasW, textColor);
    }

    /// One cardinality on a self-loop attach point, clamped in-canvas. Separate from
    /// {@link #emitMultiplicity} because a loop's geometry is rectilinear and already known here —
    /// there is no leg direction to project onto, and the side rule that method encodes would put
    /// both ends on the same side of two parallel legs.
    private static void emitLoopMultiplicity(List<Shape> shapes, String raw, double x,
                                             double baseline, double canvasW, String textColor) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String lbl = FONT.ellipsize(raw, MAX_MULT_W, MULT_SIZE);
        double w = FONT.runWidth(lbl, MULT_SIZE);
        double originX = Math.max(2, Math.min(x, canvasW - 2 - w));
        emitLine(shapes, lbl, originX, Math.max(FONT.ascent(MULT_SIZE) + 2, baseline),
            MULT_SIZE, false, canvasW, textColor, null);
    }

    /// The label BASELINES of a box's self-loop lanes, indexed by lane — computed from the FULL
    /// label set in the layout pre-pass and consumed unchanged by both the emit pass and the
    /// reservation pass (Marlow sirentide/761: the old placement stacked every label in a detached
    /// block with no geometric tie to its own loop, so a reader could not tell which label belonged
    /// to which loop).
    ///
    /// Y-ASSOCIATION. Lane k's label rides ITS OWN loop's TOP HORIZONTAL LEG: that leg leaves the
    /// border at {@link #loopExitY}(k) and runs out to lane k's vertical leg, so a baseline at
    /// {@code ay_k + ascent·0.35} optically centres the text on that line and the label reads as
    /// riding its own loop exactly like an edge label rides its edge. (The x-band cannot do the
    /// associating — a label runs up to MAX_LABEL_W, many times the lane pitch, so an "inside its own
    /// lane" column would cross every outer leg; emitSelfLoop therefore puts ALL of them in one
    /// column past the outermost leg and lets Y carry the association.)
    ///
    /// METRIC FLOOR (Marlow sirentide/768 F2 — this REPLACES the retired fixed-slot floor, which
    /// budgeted a flat {@code EDGE_LABEL_SIZE + 2} per lane and so could not see a label taller than
    /// that: two math fragments with a 20px ascent and a 20px descent emitted OVERLAPPING occupied
    /// bands while every fixed-slot receipt stayed green). Lane k's top leg sits one
    /// {@link #SELF_LOOP_ATTACH_STEP} ABOVE lane k−1's, so the ideals run downward from the OUTERMOST
    /// lane to lane 0; walking that order, each next label is pushed down until its band
    /// {@code [baseline − ascent, baseline + descent]} clears the previous label's band by
    /// {@link #SELF_LOOP_LABEL_BAND_GAP} — i.e. {@code baseline_k ≥ baseline_{k+1} + descent_{k+1} +
    /// ascent_k + gap}, from each label's OWN measured metrics (math included). Disjointness is
    /// therefore by construction at any label size, and order is preserved (the floor only ever
    /// pushes a lower-ideal lane FURTHER down). A SHORT box whose attach nudges CLAMP together (all
    /// ideals equal) degrades the same way instead of overprinting. `labelMetrics[lane]` is
    /// {@code {ascent, descent}}, or null for a lane whose relation carries no label — an unlabeled
    /// lane occupies no band, so it neither floors nor is floored.
    ///
    /// This is the IDEAL + DEGRADATION-2 half of the contract; {@link SelfLoopLabelColumn} composes
    /// DEGRADATION 1 (the corridor) on top of the stack this returns, in the layout pre-pass — PER
    /// LABEL, so what this method returns is both the starting point AND the target the corridor
    /// solve stays as close to as the hard constraints allow.
    static double[] loopLabelBaselines(double boxY, double boxH, double[][] labelMetrics) {
        double[] baselines = new double[Math.max(labelMetrics.length, 1)];
        double lift = FONT.ascent(EDGE_LABEL_SIZE) * 0.35;
        double prev = Double.NEGATIVE_INFINITY;   // previous (higher) lane's baseline …
        double prevDesc = 0;                      // … and the descent of the label riding it
        for (int lane = baselines.length - 1; lane >= 0; lane--) {
            double ideal = loopExitY(boxY, boxH, lane) + lift;
            double[] m = lane < labelMetrics.length ? labelMetrics[lane] : null;
            if (m == null) {
                baselines[lane] = ideal;
                continue;
            }
            baselines[lane] = Math.max(ideal, prev + prevDesc + m[0] + SELF_LOOP_LABEL_BAND_GAP);
            prev = baselines[lane];
            prevDesc = m[1];
        }
        return baselines;
    }

    /// Lane k's EXIT attach y (the top attach): 0.3·h nudged UP one {@link #SELF_LOOP_ATTACH_STEP} per
    /// lane so stacked loops' horizontal legs never overpaint, clamped just inside the border span — a BELT: the sizing pass grows a multi-lane box so the nudges
    /// never actually clamp two lanes together (collinear legs overpaint; Lattice r3 seq 227).
    private static double loopExitY(double boxY, double boxH, int lane) {
        // Clamp at MAX_MARKER_HALF (not a flat 4) so the outermost lane's marker top edge (attach −
        // MAX_MARKER_HALF) stays at/inside the box border instead of poking above it (sirentide 275).
        return Math.max(boxY + MAX_MARKER_HALF, boxY + boxH * 0.3 - lane * SELF_LOOP_ATTACH_STEP);
    }

    /// Lane k's RETURN attach y (the bottom attach): 0.7·h nudged DOWN per lane, clamped in-span.
    private static double loopReturnY(double boxY, double boxH, int lane) {
        return Math.min(boxY + boxH - MAX_MARKER_HALF, boxY + boxH * 0.7 + lane * SELF_LOOP_ATTACH_STEP);
    }

    /// Horizontal extent a node's self-loop lane adds past its right border: the outermost vertical
    /// leg plus (when any of its loops is labeled) the label gap + the widest label. Zero without
    /// self-loops. Every loop label now shares ONE column at that offset (Marlow sirentide/761 — the
    /// per-lane x-staircase is gone, and with it the extra {@code (loops-1)·SELF_LOOP_LANE} this used
    /// to reserve for it), so the row cursor reserves exactly the band emitSelfLoop writes into.
    private static double selfLaneExtent(int loops, double labelW) {
        if (loops == 0) {
            return 0;
        }
        return SELF_LOOP_OUT + (loops - 1) * SELF_LOOP_LANE
            + (labelW > 0 ? SELF_LOOP_LABEL_GAP + labelW : 0);
    }

    /// Emits the edge core from `(x1,y1)` to `(x2,y2)`: a single straight run when the route is direct,
    /// else a two-leg polyline through the detour waypoint (`x1,y1 → W → x2,y2`) so the edge bends
    /// around a third box. Each leg honours the dashed flag independently.
    private static void emitEdgeChain(List<Shape> shapes, double x1, double y1, double x2, double y2,
                                      EdgeRouter.Route route, boolean dashed) {
        if (route.hasBend()) {
            emitEdgeLine(shapes, x1, y1, route.wx(), route.wy(), dashed);
            emitEdgeLine(shapes, route.wx(), route.wy(), x2, y2, dashed);
        } else {
            emitEdgeLine(shapes, x1, y1, x2, y2, dashed);
        }
    }

    /// Emits the relationship edge line — a single {@link Line} (association / whole-side kinds) or a
    /// run of short dash segments (dependency). Never emits NaN geometry (degenerate → skipped).
    private static void emitEdgeLine(List<Shape> shapes, double x1, double y1, double x2, double y2,
                                     boolean dashed) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (!Double.isFinite(len) || len < 1e-6) {
            return;
        }
        if (!dashed) {
            shapes.add(new Line(x1, y1, x2, y2, EDGE_STROKE, EDGE_WIDTH));
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double pos = 0;
        while (pos < len) {
            double end = Math.min(pos + DASH_ON, len);
            shapes.add(new Line(x1 + ux * pos, y1 + uy * pos, x1 + ux * end, y1 + uy * end,
                EDGE_STROKE, EDGE_WIDTH));
            pos += DASH_ON + DASH_OFF;
        }
    }

    /// How far a kind's marker extends back from the box border (so the edge line starts past it).
    private static double markerLength(RelationKind kind) {
        return switch (kind) {
            case INHERITANCE -> TRI_LEN;
            case COMPOSITION, AGGREGATION -> DIA_LEN;
            case ASSOCIATION, DEPENDENCY -> ARR_LEN;
        };
    }

    /// Builds the UML marker glyph for a relation kind at `tip` (a point on the box border), with
    /// `dir` the unit vector pointing FROM the tip AWAY from the box (along the edge toward the other
    /// class). Package-private so the geometry can be pinned directly by the layout tests — the marker
    /// shapes are the fidelity crux (a wrong shape at a wrong end reads as "broken"):
    ///   - INHERITANCE → a HOLLOW triangle: 3 {@link Line} outline segments (tip + 2 base corners).
    ///   - COMPOSITION → a FILLED diamond: 1 {@link Path} (4 vertices, solid `fill`).
    ///   - AGGREGATION → a HOLLOW diamond: 4 {@link Line} outline segments.
    ///   - ASSOCIATION / DEPENDENCY → an OPEN arrow: 2 {@link Line} barbs meeting at the tip.
    /// The point-count / shape-type difference (triangle 3 lines, diamond 4 lines, filled diamond a
    /// 4-vertex path, arrow 2 lines) is what the delete-mutant test asserts to catch a swapped marker.
    static List<Shape> marker(RelationKind kind, double tipX, double tipY,
                              double dirX, double dirY, String color) {
        double px = -dirY;   // unit perpendicular
        double py = dirX;
        List<Shape> out = new ArrayList<>();
        switch (kind) {
            case INHERITANCE -> {
                double bx = tipX + dirX * TRI_LEN;
                double by = tipY + dirY * TRI_LEN;
                double cLx = bx + px * TRI_HALF;
                double cLy = by + py * TRI_HALF;
                double cRx = bx - px * TRI_HALF;
                double cRy = by - py * TRI_HALF;
                out.add(new Line(tipX, tipY, cLx, cLy, color, BORDER_W));
                out.add(new Line(cLx, cLy, cRx, cRy, color, BORDER_W));
                out.add(new Line(cRx, cRy, tipX, tipY, color, BORDER_W));
            }
            case COMPOSITION -> {
                double mx = tipX + dirX * (DIA_LEN / 2);
                double my = tipY + dirY * (DIA_LEN / 2);
                double backX = tipX + dirX * DIA_LEN;
                double backY = tipY + dirY * DIA_LEN;
                double s1x = mx + px * DIA_HALF;
                double s1y = my + py * DIA_HALF;
                double s2x = mx - px * DIA_HALF;
                double s2y = my - py * DIA_HALF;
                String d = "M " + fmt(tipX) + " " + fmt(tipY)
                    + " L " + fmt(s1x) + " " + fmt(s1y)
                    + " L " + fmt(backX) + " " + fmt(backY)
                    + " L " + fmt(s2x) + " " + fmt(s2y)
                    + " Z";
                out.add(new Path(d, color));
            }
            case AGGREGATION -> {
                double mx = tipX + dirX * (DIA_LEN / 2);
                double my = tipY + dirY * (DIA_LEN / 2);
                double backX = tipX + dirX * DIA_LEN;
                double backY = tipY + dirY * DIA_LEN;
                double s1x = mx + px * DIA_HALF;
                double s1y = my + py * DIA_HALF;
                double s2x = mx - px * DIA_HALF;
                double s2y = my - py * DIA_HALF;
                out.add(new Line(tipX, tipY, s1x, s1y, color, BORDER_W));
                out.add(new Line(s1x, s1y, backX, backY, color, BORDER_W));
                out.add(new Line(backX, backY, s2x, s2y, color, BORDER_W));
                out.add(new Line(s2x, s2y, tipX, tipY, color, BORDER_W));
            }
            case ASSOCIATION, DEPENDENCY -> {
                double bx = tipX + dirX * ARR_LEN;
                double by = tipY + dirY * ARR_LEN;
                double b1x = bx + px * ARR_HALF;
                double b1y = by + py * ARR_HALF;
                double b2x = bx - px * ARR_HALF;
                double b2y = by - py * ARR_HALF;
                out.add(new Line(b1x, b1y, tipX, tipY, color, EDGE_WIDTH));
                out.add(new Line(b2x, b2y, tipX, tipY, color, EDGE_WIDTH));
            }
        }
        return out;
    }

    /// The point on a box's border along the ray from its centre `(cx, cy)` toward `(tx, ty)`. Used to
    /// anchor an edge/marker to the box edge instead of its centre. A coincident target returns the
    /// centre (degenerate → no NaN).
    static double[] clipToRect(double cx, double cy, double w, double h, double tx, double ty) {
        double dx = tx - cx;
        double dy = ty - cy;
        if (dx == 0 && dy == 0) {
            return new double[] {cx, cy};
        }
        double scale = Double.MAX_VALUE;
        if (dx != 0) {
            scale = Math.min(scale, (w / 2) / Math.abs(dx));
        }
        if (dy != 0) {
            scale = Math.min(scale, (h / 2) / Math.abs(dy));
        }
        return new double[] {cx + dx * scale, cy + dy * scale};
    }

    /// A unit vector in the direction `(dx, dy)`, or `(1, 0)` for a degenerate zero vector (never NaN).
    private static double[] unit(double dx, double dy) {
        double len = Math.hypot(dx, dy);
        if (!Double.isFinite(len) || len < 1e-9) {
            return new double[] {1, 0};
        }
        return new double[] {dx / len, dy / len};
    }

    /// Deterministic 3-dp number formatting for path data (byte-identical bakes, DESIGN §6).
    private static String fmt(double v) {
        if (!Double.isFinite(v)) {
            v = 0.0;
        }
        double r = Math.round(v * 1000.0) / 1000.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
