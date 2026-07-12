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

    private static final String BOX_FILL = "#eef2ff";    // member-compartment background (pale indigo)
    private static final String NAME_FILL = "#c7d2fe";   // name-compartment band (a shade darker)
    private static final String BORDER = "#475569";      // box border + compartment dividers (slate)
    private static final String MARKER = "#475569";      // relationship marker glyph colour
    private static final String EDGE_STROKE = "#94a3b8"; // relationship edge line
    private static final double BORDER_W = 1;
    private static final double EDGE_WIDTH = 1.5;
    private static final double EDGE_LABEL_SIZE = 10;

    // Marker geometry (px). Length = how far the marker extends back from the box border along the
    // edge; half-width = its perpendicular half-extent. Distinct per family so the shapes read clearly.
    private static final double TRI_LEN = 14;
    private static final double TRI_HALF = 8;
    private static final double DIA_LEN = 16;
    private static final double DIA_HALF = 7;
    private static final double ARR_LEN = 11;
    private static final double ARR_HALF = 5;

    private static final double DASH_ON = 6;   // dependency dash segment length
    private static final double DASH_OFF = 4;  // dependency dash gap length

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
            for (String a : b.attributes()) {
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
            for (String m : b.methods()) {
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
                cursor += boxW[node] + COL_GAP;
            }
            canvasW = Math.max(canvasW, cursor - COL_GAP + MARGIN);
            rowTop += rowH + ROW_GAP;
            slot = rowEnd;
        }
        double canvasH = Math.max(MIN_H, rowTop - ROW_GAP + MARGIN);

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
        for (ClassRelation r : cd.relations()) {
            Integer li = index.get(r.left());
            Integer ri = index.get(r.right());
            if (li == null || ri == null) {
                continue;   // a relation to an unplaced class (defensive) — skip, never throw
            }
            List<Shape> eg = new ArrayList<>();
            emitRelation(eg, placed, li, ri, r, cd.textColor(), canvasW, canvasH, math);
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
    /// dependency) + its UML marker at the marked end + an optional `: label` at the edge midpoint. When
    /// the straight edge would cross a THIRD box, {@link EdgeRouter} bends it around via one waypoint
    /// (the marker then points along the first/last leg), so an edge never runs over a non-endpoint box.
    private static void emitRelation(List<Shape> shapes, Placed[] placed, int li, int ri,
                                     ClassRelation r, String textColor, double canvasW, double canvasH,
                                     MathFragmentRenderer math) {
        // Self-relation (`A --|> A`): both endpoints are the same box, so the edge is zero-length —
        // clipToRect returns the box center for BOTH ends and unit(0,0) degenerates to (1,0), which
        // would draw the UML marker INSIDE the box pointing sideways. A self-loop needs arc routing
        // the straight-edge/single-waypoint EdgeRouter and marker primitives don't provide, so we
        // skip the relation (draw nothing) rather than emit a wrong marker.
        if (li == ri) {
            return;
        }
        Placed left = placed[li];
        Placed right = placed[ri];
        double lcx = left.centerX();
        double lcy = left.centerY();
        double rcx = right.centerX();
        double rcy = right.centerY();
        double[] lb = clipToRect(lcx, lcy, left.w(), left.h(), rcx, rcy);    // left box border point
        double[] rb = clipToRect(rcx, rcy, right.w(), right.h(), lcx, lcy);  // right box border point

        // Route around any third box (placement removes most crossings; this bends the residual).
        List<double[]> others = new ArrayList<>();
        for (int k = 0; k < placed.length; k++) {
            if (k == li || k == ri) {
                continue;
            }
            Placed p = placed[k];
            others.add(new double[] {p.x(), p.y(), p.w(), p.h()});
        }
        EdgeRouter.Route route = EdgeRouter.route(lb[0], lb[1], rb[0], rb[1], others, canvasW, canvasH);

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
