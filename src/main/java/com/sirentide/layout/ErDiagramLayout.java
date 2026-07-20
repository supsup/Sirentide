package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.ErAttribute;
import com.sirentide.ir.ErCardinality;
import com.sirentide.ir.ErDiagram;
import com.sirentide.ir.ErEntity;
import com.sirentide.ir.ErRelation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Pure entity-relationship diagram layout: entity TABLES (a name header band over typed attribute
/// rows) placed in a deterministic grid, wired by relationship edges whose CROW-FOOT cardinality
/// marker combo sits at EACH end (docs/DESIGN.md §4/§5). The cardinality glyphs are the fidelity crux
/// — a wrong combo at a wrong end is a broken ER diagram — so each end's {@link ErCardinality} maps to
/// one exact two-symbol combo via {@link #cardinalityMarker}.
///
/// MARKER-GLYPH APPROACH (within the svg/path/rect/line + glyph-path alphabet, no new element/attr).
/// Each cardinality draws TWO stacked symbols at the entity end, from a menu of three primitives —
/// each built ONLY from {@link Line} segments so it is genuinely visible on any background (the
/// contract's `<path>` carries no `stroke`, so a hollow outline MUST be lines, not a `fill="none"`
/// path — the same rule the class-diagram markers follow):
///   - a BAR is one perpendicular tick {@link Line} across the edge.
///   - a CROW'S-FOOT is three {@link Line} prongs fanning from one convergence point (on the edge)
///     out to three points on the entity border — the three-prong fork of a "many" side.
///   - a CIRCLE is a small HOLLOW ring approximated by a {@link #CIRCLE_SIDES}-segment {@link Line}
///     polygon centred on the edge (the contract has no `<circle>`, and a filled `<path>` disc would
///     read as "one", not "zero" — a hollow ring is the faithful, containment-legal approximation).
/// The INNER symbol (nearest the entity) is a crow's-foot when the cardinality is "many" else a bar;
/// the OUTER symbol (just beyond) is a circle when the cardinality is optional ("zero-or-…") else a
/// bar. So: zero-or-one = bar+circle, exactly-one = bar+bar (double tick), zero-or-many =
/// crow's-foot+circle, one-or-many = crow's-foot+bar. The DASHED (`..`, non-identifying) relationship
/// bakes its edge line as a run of short {@link Line} segments (the contract has no
/// `stroke-dasharray`), deterministic.
///
/// PLACEMENT: a row-major GRID (`ceil(sqrt(n))` columns), tables sized to their widest row. The slot
/// ORDER is relationship-aware (via {@link GridOrder} — related entities land in adjacent slots) so
/// edges stay short and a straight centre-to-centre edge is far less likely to cross a third table.
/// Edges route straight between table BORDERS (clipped to the rectangle) with each end's cardinality
/// combo at that border. The canvas grows to fit the grid + margin so nothing escapes (containment).
/// Deterministic; text baked to glyph paths, markers to lines. RESIDUAL: crossing REDUCTION not
/// MINIMIZATION (DESIGN §7) — a long diagonal edge can still clip a table; single-line attribute rows,
/// column alignment, and orthogonal routing are follow-ups.
public final class ErDiagramLayout {

    private ErDiagramLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    private static final double MARGIN = 24;
    private static final double NAME_SIZE = 13;      // the entity name header (slightly larger = emphasis)
    private static final double ROW_SIZE = 12;       // attribute rows
    private static final double PAD_X = 12;          // horizontal padding inside a table
    private static final double PAD_Y = 6;           // vertical padding per band
    private static final double COL_GAP = 100;       // horizontal gap between grid columns (room for markers)
    private static final double ROW_GAP = 90;        // vertical gap between grid rows (room for markers)
    private static final double MIN_BOX_W = 96;
    private static final double MIN_W = 120;         // blank-canvas width (0 entities)
    private static final double MIN_H = 60;
    private static final double MAX_LABEL_W = 260;   // rows ellipsize past this
    // Displayed-attribute cap per entity (robustness plan fe8c5bbc #2, ER twin of the class cap): the
    // row WIDTH ellipsizes but the row COUNT was unbounded. Show at most this many attribute rows; a
    // longer entity ends with one synthesized "… (N more)" row (legibility + a layout bound).
    static final int MAX_DISPLAYED_ROWS = 30;

    private static final String BOX_FILL = "#ecfdf5";    // attribute-rows background (pale emerald)
    private static final String NAME_FILL = "#a7f3d0";   // name header band (a shade darker)
    private static final String BORDER = "#0f766e";      // table border + row dividers (teal)
    private static final String MARKER = "#0f766e";      // cardinality marker glyph colour
    private static final String EDGE_STROKE = "#5eead4"; // relationship edge line
    private static final double BORDER_W = 1;
    private static final double EDGE_WIDTH = 1.5;
    private static final double EDGE_LABEL_SIZE = 10;

    // Crow-foot marker geometry (px). CROW_LEN = how far the fork convergence sits from the entity
    // border; CROW_HALF = half the fork's spread at the border. BAR_D = the inner-bar distance for a
    // "one" side; BAR_HALF = a bar tick's perpendicular half-extent. GAP separates the inner and outer
    // symbols. CIRCLE_R/SIDES draw the hollow "zero" ring. Distinct enough that the combos read clearly.
    private static final double CROW_LEN = 16;
    private static final double CROW_HALF = 9;
    private static final double BAR_D = 9;
    private static final double BAR_HALF = 7;
    private static final double GAP = 8;
    private static final double CIRCLE_R = 5;
    private static final int CIRCLE_SIDES = 12;

    /// Largest perpendicular marker half-extent over this diagram's cardinality menu (crow-foot 9, bar 7,
    /// hollow ring radius 5). The self-loop attach pitch and the border inset derive from it so adjacent
    /// same-side markers can never overprint (sirentide 275); the geometry oracle imports this SAME value
    /// so the pitch and its test can never drift apart.
    static final double MAX_MARKER_HALF = Math.max(CROW_HALF, Math.max(BAR_HALF, CIRCLE_R));

    private static final double DASH_ON = 6;   // non-identifying (dashed) edge dash segment length
    private static final double DASH_OFF = 4;  // dashed edge gap length

    // A self-relation (`A ||--o{ A`) routes a rectilinear loop off the table's RIGHT edge: out this far
    // past the border, down, and back — entirely to the right of the table, so it never re-enters the
    // interior. SELF_LOOP_OUT clears the deepest cardinality combo (zero-or-many's ring reaches
    // CROW_LEN + GAP + 2·CIRCLE_R = 34 out) so the outer leg sits beyond every marker. The row cursor
    // reserves the whole loop LANE (legs + widest label), so neither the next table in the row nor
    // the viewBox edge can collide with it (Lattice re-review, seq 217).
    private static final double SELF_LOOP_OUT = 44;
    // Each ADDITIONAL self-relation on the same table nests one lane further out (distinct vertical
    // legs) …
    private static final double SELF_LOOP_LANE = 14;
    // … and nudges its attach points apart (top attach up, bottom attach down, clamped inside the
    // border span) so the horizontal legs never overpaint either. Labels stack separately (see
    // loopLabelBaseline), so an attach-clamp collapse can never merge them.
    // DERIVED from the marker footprint, NOT a flat constant (sirentide 275): two adjacent same-side
    // markers sit one step apart perpendicular to their horizontal legs, each extending ±MAX_MARKER_HALF,
    // so the pitch must clear 2·MAX_MARKER_HALF + stroke clearance or the glyphs overprint AND a later
    // FUTURE relation repaints part of an earlier ACTIVE one under play-through. The old flat 12 was under
    // the 18px crow-foot / 14px bar footprints. CLEARANCE = the marker stroke + an anti-alias epsilon.
    private static final double SELF_LOOP_MARKER_CLEARANCE = 2;
    private static final double SELF_LOOP_ATTACH_STEP = 2 * MAX_MARKER_HALF + SELF_LOOP_MARKER_CLEARANCE;
    // Gap between the outermost loop leg and its label's left edge.
    private static final double SELF_LOOP_LABEL_GAP = 4;

    /// One placed entity table: its grid rectangle plus the pre-measured header height and the
    /// (ellipsized) display lines, so the emit pass draws band/rows/text without re-measuring.
    /// `nameMeasure`/`rowMeasures` are the composite measures for lines carrying `$…$` math (null for
    /// plain text); `nameRowH`/`rowRowH` are the per-row heights — the fixed pitch for a plain or
    /// short-math row, GROWN (via {@link MathLabel#boxHeight}) for a row whose fragment is TALLER than
    /// one line (a matrix / cases / stacked fraction). A row grows iff its height differs from the fixed
    /// pitch (plan sirentide-tall-math-labels — the attribute row now consumes the fragment HEIGHT).
    private record Placed(ErEntity entity, double x, double y, double w, double h,
                          double headerH, String name, List<String> rows,
                          MathLabel.Measured nameMeasure, List<MathLabel.Measured> rowMeasures,
                          double nameRowH, List<Double> rowRowH) {
        double centerX() {
            return x + w / 2;
        }

        double centerY() {
            return y + h / 2;
        }
    }

    public static LaidOut layout(ErDiagram er) {
        return layout(er, null);
    }

    /// The pure layout. `math` (nullable) renders `$…$` runs in the entity-name / attribute-row /
    /// edge-label text through the shared {@link MathLabel} seam; a null renderer is the plain-text
    /// path (byte-identical bake).
    public static LaidOut layout(ErDiagram er, MathFragmentRenderer math) {
        List<ErEntity> entities = er.entities();
        int n = entities.size();
        if (n == 0) {
            return LaidOut.of(MIN_W, MIN_H);   // a bare `erDiagram` still round-trips as one
        }

        // -- 1) size every table (widest of the header and its rows + padding), first-seen order.
        double[] boxW = new double[n];
        double[] boxH = new double[n];
        double[] headerH = new double[n];
        String[] names = new String[n];
        List<List<String>> rowLines = new ArrayList<>();
        // Composite measures (null for plain lines) + per-row heights (pitch for plain/short math, GROWN
        // for a tall multi-row fragment). Parallel to the string lists; consumed by the emit pass.
        MathLabel.Measured[] nameMeasures = new MathLabel.Measured[n];
        List<List<MathLabel.Measured>> rowMeasures = new ArrayList<>();
        double[] nameRowH = new double[n];
        List<List<Double>> rowRowHs = new ArrayList<>();
        double rowPitch = FONT.lineHeight(ROW_SIZE);
        double namePitch = FONT.lineHeight(NAME_SIZE);
        for (int i = 0; i < n; i++) {
            ErEntity e = entities.get(i);
            Disp nd = disp(e.name(), NAME_SIZE, math);
            names[i] = nd.display();
            nameMeasures[i] = nd.measure();
            nameRowH[i] = rowHeight(nd.measure(), namePitch, NAME_SIZE);
            double widest = widthOf(nd.display(), nd.measure(), NAME_SIZE);
            List<String> rows = new ArrayList<>();
            List<MathLabel.Measured> rm = new ArrayList<>();
            List<Double> rrh = new ArrayList<>();
            double rowsSum = 0;
            for (String src : capAttributeDisplays(e.attributes())) {
                Disp d = disp(src, ROW_SIZE, math);
                rows.add(d.display());
                rm.add(d.measure());
                double rh = rowHeight(d.measure(), rowPitch, ROW_SIZE);
                rrh.add(rh);
                rowsSum += rh;
                widest = Math.max(widest, widthOf(d.display(), d.measure(), ROW_SIZE));
            }
            rowLines.add(rows);
            rowMeasures.add(rm);
            rowRowHs.add(rrh);
            boxW[i] = Math.max(MIN_BOX_W, widest + 2 * PAD_X);
            headerH[i] = nameRowH[i] + 2 * PAD_Y;
            // An entity with rows shows the header band + the rows compartment; an attribute-less
            // entity collapses to a single header box (no rows band, no dividers). The rows compartment
            // is the SUM of its (possibly grown) row heights + padding — for an all-plain / short-math
            // entity every row is one pitch, so this reduces to the pre-growth `count · pitch + 2·PAD_Y`
            // and the table is byte-identical.
            double rowsH = e.hasAttributes() ? rowsSum + 2 * PAD_Y : 0;
            boxH[i] = headerH[i] + rowsH;
        }

        // -- 2) grid placement: ceil(sqrt(n)) columns, row-major. The slot ORDER is relationship-aware
        // (related entities land in adjacent slots via {@link GridOrder}) so edges are short and a
        // straight edge is far less likely to cross a third table — quality over the v1 first-seen
        // order, still fully deterministic. Each row's height is its tallest table; tables march
        // left→right with COL_GAP, rows down with ROW_GAP. Canvas grows to fit (containment).
        Map<String, Integer> index = new HashMap<>();
        for (int k = 0; k < n; k++) {
            index.put(entities.get(k).name(), k);
        }
        List<int[]> edgeList = new ArrayList<>();
        for (ErRelation r : er.relations()) {
            Integer a = index.get(r.left());
            Integer b = index.get(r.right());
            if (a != null && b != null) {
                edgeList.add(new int[] {a, b});
            }
        }
        int[] perm = GridOrder.order(n, edgeList.toArray(new int[0][]));

        // Self-loop lane bookkeeping (Lattice re-review, seq 217): every self-relation on a table
        // occupies a LANE off its right edge — lane k's vertical leg sits k·SELF_LOOP_LANE further
        // out — and each loop's (already-ellipsized) label rides beside the table's OUTERMOST leg.
        // The row cursor below reserves the table's full lane extent, which is what keeps a loop
        // label from escaping the viewBox (the old grow-pass reserved only the legs) and from
        // running through the next table in the row.
        int[] selfLoops = new int[n];                       // lanes per table
        int[] selfLane = new int[er.relations().size()];    // this relation's lane on its table
        double[] selfLabelW = new double[n];                // widest label in the table's lane
        for (int e = 0; e < er.relations().size(); e++) {
            ErRelation r = er.relations().get(e);
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
        // A table with MULTIPLE self-loop lanes must be TALL enough that the per-lane attach nudges
        // never clamp two lanes onto the same y — clamp-collapsed lanes run COLLINEAR horizontal
        // legs that partially overpaint, so a later FUTURE group paints over an earlier ACTIVE one
        // in play-through, and the stacked labels collapse at the ascent floor (Lattice r3,
        // seq 227, JShell-probed at 3 class / 3 ER lanes). Growing the table keeps every authored
        // relation rendered (rejecting the "unsupported" count would erase valid relations — the
        // original bug class): 0.3·h must clear the border inset plus one ATTACH_STEP per extra
        // lane, which by the 0.3/0.7 symmetry bounds the bottom nudges too.
        for (int i = 0; i < n; i++) {
            if (selfLoops[i] > 1) {
                boxH[i] = Math.max(boxH[i],
                    (MAX_MARKER_HALF + SELF_LOOP_ATTACH_STEP * (selfLoops[i] - 1)) / 0.3);
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
                // Reserve the table's self-loop LANE (legs + widest label) inside the row, so the
                // next table starts past it AND canvasW (derived from the cursor) contains it.
                cursor += boxW[node] + selfLaneExtent(selfLoops[node], selfLabelW[node]) + COL_GAP;
            }
            canvasW = Math.max(canvasW, cursor - COL_GAP + MARGIN);
            rowTop += rowH + ROW_GAP;
            slot = rowEnd;
        }
        double canvasH = Math.max(MIN_H, rowTop - ROW_GAP + MARGIN);

        // The row cursor above reserved every self-loop lane HORIZONTALLY. What can still poke past
        // the canvas is a MATH label's ascent/descent (a tall fraction at EDGE_LABEL_SIZE): the label
        // baseline is stacked above the lane-0 exit leg (the same {@link #loopLabelBaseline} +
        // ascent-floor formula emitSelfLoop uses), so grow the BOTTOM when a bottom-row label's
        // descent reaches past the margin.
        for (int e = 0; e < er.relations().size(); e++) {
            ErRelation r = er.relations().get(e);
            Integer li = index.get(r.left());
            if (li == null || !li.equals(index.get(r.right()))
                || r.label() == null || r.label().isBlank()) {
                continue;
            }
            String lbl = FONT.ellipsize(r.label(), MAX_LABEL_W, EDGE_LABEL_SIZE);
            double asc;
            double desc;
            if (math != null && MathLabel.hasMath(lbl)) {
                MathLabel.Measured m = MathLabel.measure(lbl, EDGE_LABEL_SIZE, FONT, math);
                asc = m.ascent();
                desc = m.descent();
            } else {
                asc = FONT.ascent(EDGE_LABEL_SIZE);
                desc = FONT.descent(EDGE_LABEL_SIZE);
            }
            double baseline = Math.max(asc + 2, loopLabelBaseline(py[li], boxH[li], selfLane[e]));
            canvasH = Math.max(canvasH, baseline + desc + 2);
        }

        Placed[] placed = new Placed[n];
        for (int k = 0; k < n; k++) {
            placed[k] = new Placed(entities.get(k), px[k], py[k], boxW[k], boxH[k],
                headerH[k], names[k], rowLines.get(k),
                nameMeasures[k], rowMeasures.get(k), nameRowH[k], rowRowHs.get(k));
        }

        List<Shape> shapes = new ArrayList<>();

        // Per-diagram anchor factory (plan sirentide-semantic-anchor-g): each relation → ONE
        // `<g role="edge">` (id = the left-right entity-name pair), each entity → ONE `<g role="entity">`
        // (id = the entity name). Relations emit first, so seq runs 0..R-1 over relations then R..R+n-1
        // over entities — the deterministic emit-order index. Grouping is additive: geometry unchanged.
        AnchorAssigner assigner = new AnchorAssigner();

        // -- 3) relationship edges + cardinality markers FIRST (under the tables, so a table border
        // cleanly caps the line while the markers — in the gap between tables — stay visible on top).
        // Each relation's edge line + both cardinality combos + label collect into ONE `<g role="edge">`.
        for (int e = 0; e < er.relations().size(); e++) {
            ErRelation r = er.relations().get(e);
            Integer li = index.get(r.left());
            Integer ri = index.get(r.right());
            if (li == null || ri == null) {
                continue;   // a relation to an unplaced entity (defensive) — skip, never throw
            }
            List<Shape> eg = new ArrayList<>();
            emitRelation(eg, placed, li, ri, r, er.textColor(), canvasW, canvasH, math,
                selfLane[e], selfLoops[li]);
            shapes.add(new Group(assigner.assign(SirentideRole.EDGE, r.left() + "-" + r.right()), eg));
        }

        // -- 4) + 5) each entity → ONE `<g role="entity">` folding its table geometry (background rect,
        // name band, border, header/rows divider) AND its text (name centered, rows left-aligned). The
        // grid places tables in disjoint slots and a table's text never leaves its table, so folding the
        // table and text per entity (vs the old two passes) introduces no cross-entity z-order change:
        // geometry byte-identical, visually identical (the same fold the class boxes use).
        for (int k = 0; k < n; k++) {
            List<Shape> tg = new ArrayList<>();
            emitTable(tg, placed[k]);
            emitTableText(tg, placed[k], math);
            shapes.add(new Group(assigner.assign(SirentideRole.ENTITY, placed[k].entity().name()), tg));
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }

    /// Measures a display line's width, routing `$…$` through {@link MathLabel} when a renderer is
    /// present (so a math-bearing row sizes its table correctly), else plain glyph advance.
    private static double measure(String line, double size, MathFragmentRenderer math) {
        if (math != null && MathLabel.hasMath(line)) {
            return MathLabel.measure(line, size, FONT, math).width();
        }
        return FONT.runWidth(line, size);
    }

    /// The DISPLAY form of a raw table line plus its composite measure. A `$…$` line (with a renderer)
    /// SKIPS ellipsization — a formula must never be cut mid-run, which would break the `$…$` delimiters
    /// and silently drop the math (the reason the class/ER inline-math previously only worked for short
    /// fragments) — and is measured as a composite; a plain line is ellipsized to MAX_LABEL_W and carries
    /// Attribute display sources for one entity, bounded to {@link #MAX_DISPLAYED_ROWS} rows
    /// (robustness fe8c5bbc #2). Within the cap, every attribute's display string is returned; past
    /// it, the first {@code cap-1} plus one synthesized "… (N more)" row — an ordinary display string
    /// that flows through {@link #disp} + measurement like any attribute.
    private static List<String> capAttributeDisplays(List<ErAttribute> attrs) {
        List<String> out = new ArrayList<>();
        if (attrs.size() <= MAX_DISPLAYED_ROWS) {
            for (ErAttribute a : attrs) {
                out.add(a.display());
            }
            return out;
        }
        for (int i = 0; i < MAX_DISPLAYED_ROWS - 1; i++) {
            out.add(attrs.get(i).display());
        }
        out.add("… (" + (attrs.size() - (MAX_DISPLAYED_ROWS - 1)) + " more)");
        return out;
    }

    /// a `null` measure (the byte-identical fixed-pitch text path). Mirrors the flowchart engine's
    /// math-skips-ellipsize rule.
    private record Disp(String display, MathLabel.Measured measure) {}

    private static Disp disp(String raw, double size, MathFragmentRenderer math) {
        if (math != null && MathLabel.hasMath(raw)) {
            return new Disp(raw, MathLabel.measure(raw, size, FONT, math));
        }
        return new Disp(FONT.ellipsize(raw, MAX_LABEL_W, size), null);
    }

    /// A line's advance width from its (possibly null) composite measure — the fragment's composite
    /// width when it carries math, else the plain glyph advance. Keeps table-sizing byte-identical for
    /// plain text (same value the old {@link #measure} returned).
    private static double widthOf(String line, MathLabel.Measured m, double size) {
        return m != null ? m.width() : FONT.runWidth(line, size);
    }

    /// The height ONE table row should occupy: the fixed `pitch` for a plain / short-math row
    /// (byte-identical), else the fragment's grown height via {@link MathLabel#boxHeight} when it is
    /// TALLER than one line (a matrix / cases / stacked fraction). The seam owns the growth policy; a
    /// `null` measure (plain row) always yields `pitch`. `rowH != pitch` iff the row grew.
    private static double rowHeight(MathLabel.Measured m, double pitch, double size) {
        return m != null ? MathLabel.boxHeight(m, pitch, size, FONT) : pitch;
    }

    /// Emits one entity table's geometry: the rows background rect, the name-band rect, the four border
    /// lines, and (for a populated entity) the header/rows divider. An attribute-less entity is a single
    /// name-filled box with no divider.
    private static void emitTable(List<Shape> shapes, Placed p) {
        boolean populated = p.entity().hasAttributes();
        shapes.add(new Rect(p.x(), p.y(), p.w(), p.h(), BOX_FILL));
        // Name band across the top (covers the whole box for an attribute-less entity, since headerH == h).
        shapes.add(new Rect(p.x(), p.y(), p.w(), p.headerH(), NAME_FILL));
        // Border: four lines (a Rect carries fill only — no stroke — so the frame is drawn as lines).
        double x0 = p.x();
        double y0 = p.y();
        double x1 = p.x() + p.w();
        double y1 = p.y() + p.h();
        shapes.add(new Line(x0, y0, x1, y0, BORDER, BORDER_W));
        shapes.add(new Line(x0, y1, x1, y1, BORDER, BORDER_W));
        shapes.add(new Line(x0, y0, x0, y1, BORDER, BORDER_W));
        shapes.add(new Line(x1, y0, x1, y1, BORDER, BORDER_W));
        if (populated) {
            double div = p.y() + p.headerH();   // header | rows
            shapes.add(new Line(x0, div, x1, div, BORDER, BORDER_W));
        }
    }

    /// Emits a table's text: the name centered in its header band, then the attribute rows left-aligned
    /// in the rows compartment, top-to-bottom (glyph paths / MathBoxes via {@link MathLabel}).
    private static void emitTableText(List<Shape> shapes, Placed p, MathFragmentRenderer math) {
        double cx = p.centerX();
        double namePitch = FONT.lineHeight(NAME_SIZE);
        // Name — centered in the header band. A GROWN name row (a tall fragment) centers the fragment
        // ink in the whole band via {@link MathLabel#baselineInBox}; a plain / short-math name keeps the
        // EXACT legacy baseline (band midpoint), so its bytes are unchanged.
        double nameBaseline = p.nameRowH() != namePitch
            ? MathLabel.baselineInBox(p.nameMeasure(), p.y(), p.headerH())
            : p.y() + p.headerH() / 2 + NAME_SIZE * 0.35;
        emitLine(shapes, p.name(), cx, nameBaseline, NAME_SIZE, true, Colors.contrastFill(NAME_FILL), math);
        if (!p.entity().hasAttributes()) {
            return;
        }
        double rowPitch = FONT.lineHeight(ROW_SIZE);
        double ascent = FONT.ascent(ROW_SIZE);
        double leftX = p.x() + PAD_X;
        // Rows — march a cursor by each row's (possibly grown) height. A plain / short-math row takes one
        // `rowPitch` and lands at the EXACT legacy baseline (`rowTop + ascent`); a tall fragment grows its
        // row and centers its ink via {@link MathLabel#baselineInBox}, pushing the rows below it down.
        // All-plain entities march by rowPitch → byte-identical.
        double y = p.y() + p.headerH() + PAD_Y;
        for (int k = 0; k < p.rows().size(); k++) {
            double rowH = p.rowRowH().get(k);
            double baseline = rowH != rowPitch
                ? MathLabel.baselineInBox(p.rowMeasures().get(k), y, rowH)
                : y + ascent;
            emitLine(shapes, p.rows().get(k), leftX, baseline, ROW_SIZE, false,
                Colors.contrastFill(BOX_FILL), math);
            y += rowH;
        }
    }

    /// Emits one text line as glyph paths (or a MathBox composite when it carries `$…$` and a renderer
    /// is present). `centered` places it around `anchorX`; otherwise `anchorX` is the LEFT origin.
    private static void emitLine(List<Shape> shapes, String text, double anchorX, double baselineY,
                                 double size, boolean centered, String fill, MathFragmentRenderer math) {
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

    /// Routes one relationship between two placed tables: the edge line between table borders (dashed
    /// for a non-identifying `..` relation) + the crow-foot cardinality combo at EACH end + an optional
    /// `: label` at the edge midpoint. The edge line runs from each end's INNER-symbol attach point (the
    /// crow-foot convergence for a "many" end, the border for a "one" end) so the fork completes cleanly
    /// to the border while the bars/circles sit on the line.
    private static void emitRelation(List<Shape> shapes, Placed[] placed, int li, int ri, ErRelation r,
                                     String textColor, double canvasW, double canvasH,
                                     MathFragmentRenderer math, int lane, int laneCount) {
        // Self-relation (`A ||--o{ A`): both endpoints are the same table. A zero-length straight edge
        // would put clipToRect at the table center for BOTH ends and draw the cardinality combos INSIDE
        // the table — and merely SKIPPING it erases a semantically-valid recursive relationship AND
        // leaves a phantom empty edge group owning an anchor. Instead route a deterministic on-canvas
        // self-LOOP off the right edge, with each end's cardinality combo on the loop, in this
        // relation's own lane (`lane` of the table's `laneCount`; layout reserved the extent).
        if (li == ri) {
            emitSelfLoop(shapes, placed[li], r, textColor, canvasW, canvasH, math, lane, laneCount);
            return;
        }
        Placed left = placed[li];
        Placed right = placed[ri];
        double lcx = left.centerX();
        double lcy = left.centerY();
        double rcx = right.centerX();
        double rcy = right.centerY();
        double[] lb = clipToRect(lcx, lcy, left.w(), left.h(), rcx, rcy);    // left table border point
        double[] rb = clipToRect(rcx, rcy, right.w(), right.h(), lcx, lcy);  // right table border point

        // Route the border-to-border span around any third table (placement removes most crossings;
        // this bends the residual hub-skip through a single waypoint).
        List<double[]> others = new ArrayList<>();
        for (int k = 0; k < placed.length; k++) {
            if (k == li || k == ri) {
                continue;
            }
            Placed p = placed[k];
            others.add(new double[] {p.x(), p.y(), p.w(), p.h()});
        }
        EdgeRouter.Route route = EdgeRouter.route(lb[0], lb[1], rb[0], rb[1], others, canvasW, canvasH);

        // Each end's dir points from its border ALONG the edge — toward the waypoint when bent, else
        // toward the other table. The cardinality markers and inner attach follow that dir.
        double[] lDir = route.hasBend()
            ? unit(route.wx() - lb[0], route.wy() - lb[1]) : unit(rb[0] - lb[0], rb[1] - lb[1]);
        double[] rDir = route.hasBend()
            ? unit(route.wx() - rb[0], route.wy() - rb[1]) : unit(lb[0] - rb[0], lb[1] - rb[1]);

        // Edge line: from each end's inner-symbol attach point (the fork convergence for a many end),
        // through the waypoint when routed.
        double lInner = innerExtent(r.leftCard());
        double rInner = innerExtent(r.rightCard());
        double sx = lb[0] + lDir[0] * lInner;
        double sy = lb[1] + lDir[1] * lInner;
        double ex = rb[0] + rDir[0] * rInner;
        double ey = rb[1] + rDir[1] * rInner;
        emitEdgeChain(shapes, sx, sy, ex, ey, route, !r.identifying());

        // Cardinality markers at each end (each end draws its own combo).
        shapes.addAll(cardinalityMarker(r.leftCard(), lb[0], lb[1], lDir[0], lDir[1], MARKER));
        shapes.addAll(cardinalityMarker(r.rightCard(), rb[0], rb[1], rDir[0], rDir[1], MARKER));

        // Optional `: label` — at the bend (when routed) else the border midpoint, clamped in-canvas.
        if (r.label() != null && !r.label().isBlank()) {
            double midX = route.hasBend() ? route.wx() : (lb[0] + rb[0]) / 2;
            double midY = (route.hasBend() ? route.wy() : (lb[1] + rb[1]) / 2) - 3;
            String lbl = FONT.ellipsize(r.label(), MAX_LABEL_W, EDGE_LABEL_SIZE);
            double w = (math != null && MathLabel.hasMath(lbl))
                ? MathLabel.measure(lbl, EDGE_LABEL_SIZE, FONT, math).width()
                : FONT.runWidth(lbl, EDGE_LABEL_SIZE);
            double originX = Math.max(2, Math.min(midX - w / 2, canvasW - 2 - w));
            double clampedY = Math.max(EDGE_LABEL_SIZE, Math.min(midY, canvasH - 2));
            emitLine(shapes, lbl, originX, clampedY, EDGE_LABEL_SIZE, false, textColor, math);
        }
    }

    /// How far a cardinality's INNER symbol reaches from the entity border: the crow-foot convergence
    /// distance for a "many" side (the edge line meets the fork tip there), else 0 for a "one" side (the
    /// edge line runs to the border and the bar tick crosses it).
    private static double innerExtent(ErCardinality card) {
        return card.many() ? CROW_LEN : 0;
    }

    /// Routes a SELF-relation (`A ||--o{ A`) as a deterministic rectilinear LOOP off the table's RIGHT
    /// edge, so a recursive relationship renders on-canvas instead of being erased. Geometry, all derived
    /// from the table rectangle and the relation's LANE (Lattice re-review, seq 217 — multiple
    /// self-relations each take their own lane instead of overpainting): two attach points on the right
    /// border (0.3·h and 0.7·h, nudged apart per lane via {@link #loopExitY}/{@link #loopReturnY}), a
    /// leg out to {@code x+w+SELF_LOOP_OUT+lane·SELF_LOOP_LANE}, a leg down, and a leg back. Every point
    /// has x ≥ the right border, so the loop NEVER crosses the table interior, and the layout reserved
    /// the whole lane extent (legs + widest label) in the row cursor, so nothing here can escape the
    /// viewBox or run through a neighbor.
    /// BOTH cardinality combos render — {@code leftCard} at the top attach, {@code rightCard} at the
    /// bottom — each on the border pointing outward (exactly like a normal edge to a table on the right),
    /// and the edge line runs from each end's inner-symbol attach (the fork convergence for a "many" end),
    /// reusing the SAME {@link #emitEdgeLine} primitive (honouring the identifying/dashed flag).
    private static void emitSelfLoop(List<Shape> shapes, Placed table, ErRelation r, String textColor,
                                     double canvasW, double canvasH, MathFragmentRenderer math,
                                     int lane, int laneCount) {
        double x1 = table.x() + table.w();                  // right border
        double ay = loopExitY(table.y(), table.h(), lane);  // left-operand end attach (top)
        double by = loopReturnY(table.y(), table.h(), lane); // right-operand end attach (bottom)
        double out = x1 + SELF_LOOP_OUT + lane * SELF_LOOP_LANE;   // this lane's vertical leg
        boolean dashed = !r.identifying();
        // Edge line from each end's inner-symbol attach (fork convergence for a many end, border for a
        // one end), out and around — the SAME inner-attach rule the straight edge uses.
        double lInner = innerExtent(r.leftCard());
        double rInner = innerExtent(r.rightCard());
        emitEdgeLine(shapes, x1 + lInner, ay, out, ay, dashed);
        emitEdgeLine(shapes, out, ay, out, by, dashed);
        emitEdgeLine(shapes, out, by, x1 + rInner, by, dashed);
        // Both cardinality combos, each at its own border attach, pointing OUTWARD (+x, away from table).
        shapes.addAll(cardinalityMarker(r.leftCard(), x1, ay, 1, 0, MARKER));
        shapes.addAll(cardinalityMarker(r.rightCard(), x1, by, 1, 0, MARKER));
        // Optional `: label` — beside the table's OUTERMOST lane leg (never crossing an outer lane's
        // vertical leg: every leg ends at x ≤ that leg, and the label starts past it), lane-STACKED
        // upward from just above the lane-0 exit leg via {@link #loopLabelBaseline}. Above-the-legs
        // keeps the label out of the TABLE-CENTER band, where a straight edge to a right neighbor
        // (and that edge's own midpoint label) lives — at center height a loop label runs into the
        // neighbor edge's label (caught by eye on the BrewShot capture). Attach-independent, so a
        // short table clamping the attach nudges together can never collapse stacked labels. The
        // canvas clamps are belts: layout already reserved the width and grew the height with the
        // same baseline formula.
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
            double labelX = x1 + SELF_LOOP_OUT + (laneCount - 1) * SELF_LOOP_LANE + SELF_LOOP_LABEL_GAP;
            double originX = Math.max(2, Math.min(labelX, canvasW - 2 - w));
            double baseline = Math.max(asc + 2, loopLabelBaseline(table.y(), table.h(), lane));
            emitLine(shapes, lbl, originX, baseline, EDGE_LABEL_SIZE, false, textColor, math);
        }
    }

    /// Lane k's label BASELINE: just above the lane-0 EXIT leg (0.3·h), one line-slot further up per
    /// lane. Above-the-legs keeps every label clear of the table-center band (a crossing edge to a
    /// right neighbor + its midpoint label live there), and the formula is deliberately independent
    /// of the attach-point CLAMPS so stacked labels stay ≥ one line apart on ANY table height.
    private static double loopLabelBaseline(double boxY, double boxH, int lane) {
        return boxY + boxH * 0.3 - 3 - lane * (EDGE_LABEL_SIZE + 2);
    }

    /// Lane k's EXIT attach y (the top attach): 0.3·h nudged UP one {@link #SELF_LOOP_ATTACH_STEP} per
    /// lane so stacked loops' horizontal legs never overpaint, clamped just inside the border span — a BELT: the sizing pass grows a multi-lane table so the nudges
    /// never actually clamp two lanes together (collinear legs overpaint; Lattice r3 seq 227).
    private static double loopExitY(double boxY, double boxH, int lane) {
        // Clamp at MAX_MARKER_HALF (not a flat 4) so the outermost lane's marker top edge (attach −
        // MAX_MARKER_HALF) stays at/inside the table border instead of poking above it (sirentide 275).
        return Math.max(boxY + MAX_MARKER_HALF, boxY + boxH * 0.3 - lane * SELF_LOOP_ATTACH_STEP);
    }

    /// Lane k's RETURN attach y (the bottom attach): 0.7·h nudged DOWN per lane, clamped in-span.
    private static double loopReturnY(double boxY, double boxH, int lane) {
        return Math.min(boxY + boxH - MAX_MARKER_HALF, boxY + boxH * 0.7 + lane * SELF_LOOP_ATTACH_STEP);
    }

    /// Horizontal extent a table's self-loop lane adds past its right border: the outermost vertical
    /// leg plus (when any of its loops is labeled) the label gap + the widest label. Zero without
    /// self-loops. The row cursor reserves exactly this, and emitSelfLoop stays within it.
    private static double selfLaneExtent(int loops, double labelW) {
        if (loops == 0) {
            return 0;
        }
        return SELF_LOOP_OUT + (loops - 1) * SELF_LOOP_LANE
            + (labelW > 0 ? SELF_LOOP_LABEL_GAP + labelW : 0);
    }

    /// Emits the edge core from `(x1,y1)` to `(x2,y2)`: a single straight run when the route is direct,
    /// else a two-leg polyline through the detour waypoint (`x1,y1 → W → x2,y2`) so the edge bends
    /// around a third table. Each leg honours the dashed flag independently.
    private static void emitEdgeChain(List<Shape> shapes, double x1, double y1, double x2, double y2,
                                      EdgeRouter.Route route, boolean dashed) {
        if (route.hasBend()) {
            emitEdgeLine(shapes, x1, y1, route.wx(), route.wy(), dashed);
            emitEdgeLine(shapes, route.wx(), route.wy(), x2, y2, dashed);
        } else {
            emitEdgeLine(shapes, x1, y1, x2, y2, dashed);
        }
    }

    /// Emits the relationship edge line — a single {@link Line} (identifying `--`) or a run of short
    /// dash segments (non-identifying `..`). Never emits NaN geometry (degenerate → skipped).
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

    /// Builds the crow-foot cardinality marker for one relationship end at `tip` (a point on the entity
    /// border), with `dir` the unit vector pointing FROM the tip AWAY from the entity (along the edge
    /// toward the other entity). Package-private so the geometry can be pinned directly by the layout
    /// tests — the cardinality combo is the fidelity crux (a wrong combo at a wrong end is a broken ER
    /// diagram). Draws TWO stacked symbols:
    ///   - INNER (nearest the entity): a {@link #crowFoot} (3 lines, a "many" fork) when
    ///     {@link ErCardinality#many()}, else a {@link #barTick} (1 line, a "one" tick) at {@link #BAR_D}.
    ///   - OUTER (just beyond): a {@link #hollowCircle} ({@link #CIRCLE_SIDES} lines, a "zero" ring) when
    ///     {@link ErCardinality#optional()}, else a {@link #barTick} (1 line, the mandatory second tick).
    /// The line-count / structure difference (crow's-foot 3 converging lines vs a 1-line bar vs a
    /// many-line circle ring) is what the delete-mutant test asserts to catch a swapped/dropped marker.
    static List<Shape> cardinalityMarker(ErCardinality card, double tipX, double tipY,
                                         double dirX, double dirY, String color) {
        List<Shape> out = new ArrayList<>();
        // Inner symbol.
        double innerReach;
        if (card.many()) {
            out.addAll(crowFoot(tipX, tipY, dirX, dirY, color));
            innerReach = CROW_LEN;
        } else {
            double bx = tipX + dirX * BAR_D;
            double by = tipY + dirY * BAR_D;
            out.addAll(barTick(bx, by, dirX, dirY, color));
            innerReach = BAR_D;
        }
        // Outer symbol at innerReach + GAP.
        double od = innerReach + GAP;
        if (card.optional()) {
            double ccx = tipX + dirX * (od + CIRCLE_R);
            double ccy = tipY + dirY * (od + CIRCLE_R);
            out.addAll(hollowCircle(ccx, ccy, color));
        } else {
            double bx = tipX + dirX * od;
            double by = tipY + dirY * od;
            out.addAll(barTick(bx, by, dirX, dirY, color));
        }
        return out;
    }

    /// A crow's-foot fork: three {@link Line} prongs fanning from ONE convergence point (at
    /// {@link #CROW_LEN} out along `dir`) back to three points on the entity border — the middle prong
    /// to the tip, the outer two to `tip ± perp·CROW_HALF`. The three lines SHARE the convergence point
    /// (the fork's meeting point), which is exactly what distinguishes it from a single bar tick.
    static List<Shape> crowFoot(double tipX, double tipY, double dirX, double dirY, String color) {
        double px = -dirY;   // unit perpendicular
        double py = dirX;
        double cxp = tipX + dirX * CROW_LEN;   // convergence point
        double cyp = tipY + dirY * CROW_LEN;
        List<Shape> out = new ArrayList<>();
        out.add(new Line(cxp, cyp, tipX, tipY, color, BORDER_W));
        out.add(new Line(cxp, cyp, tipX + px * CROW_HALF, tipY + py * CROW_HALF, color, BORDER_W));
        out.add(new Line(cxp, cyp, tipX - px * CROW_HALF, tipY - py * CROW_HALF, color, BORDER_W));
        return out;
    }

    /// A single bar tick: one {@link Line} perpendicular to the edge, centred at `(cx, cy)`, spanning
    /// `± perp·BAR_HALF`. A "one"/mandatory cardinality symbol (a "double tick" = two of these).
    static List<Shape> barTick(double cx, double cy, double dirX, double dirY, String color) {
        double px = -dirY;
        double py = dirX;
        List<Shape> out = new ArrayList<>();
        out.add(new Line(cx + px * BAR_HALF, cy + py * BAR_HALF,
            cx - px * BAR_HALF, cy - py * BAR_HALF, color, BORDER_W));
        return out;
    }

    /// A hollow "zero" ring: a {@link #CIRCLE_SIDES}-segment {@link Line} polygon approximating a small
    /// circle of radius {@link #CIRCLE_R} centred at `(cx, cy)`. A closed loop (the last vertex meets the
    /// first). Hollow because the contract's `<path>` has no `stroke` — a filled disc would read as a
    /// mandatory "one", so the optional "zero" must be an open ring drawn from lines.
    static List<Shape> hollowCircle(double cx, double cy, String color) {
        List<Shape> out = new ArrayList<>();
        double prevX = cx + CIRCLE_R;
        double prevY = cy;
        for (int s = 1; s <= CIRCLE_SIDES; s++) {
            // StrictMath (not Math) so the trig is bit-for-bit reproducible across platforms/JVMs — the
            // byte-identical-bake invariant (DESIGN §6) must not hinge on a platform's cos/sin ULP.
            double a = 2 * StrictMath.PI * s / CIRCLE_SIDES;
            double nx = cx + CIRCLE_R * StrictMath.cos(a);
            double ny = cy + CIRCLE_R * StrictMath.sin(a);
            out.add(new Line(prevX, prevY, nx, ny, color, BORDER_W));
            prevX = nx;
            prevY = ny;
        }
        return out;
    }

    /// The point on a table's border along the ray from its centre `(cx, cy)` toward `(tx, ty)`. Used to
    /// anchor an edge/marker to the table edge instead of its centre. A coincident target returns the
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
}
