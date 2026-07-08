package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Journey;
import com.sirentide.ir.JourneySection;
import com.sirentide.ir.JourneyTask;
import java.util.ArrayList;
import java.util.List;

/// Pure journey layout: a user-journey satisfaction map. Tasks lay out in declaration order along the
/// x-axis (one evenly-spaced COLUMN each, across every section); each task's score maps onto a 1..5
/// SATISFACTION y-axis where a HIGHER score sits HIGHER (score 5 → plot top, score 1 → plot bottom);
/// a single LINE connects the task points in order; each SECTION brackets its contiguous task span
/// with a header above the plot; each task's actors list beneath its point. Deterministic arithmetic,
/// no optimization — byte-identical bakes (docs/DESIGN.md §6).
///
/// COLOUR (documented layout choice): task point discs are coloured by SCORE — a warm→cool ramp,
/// low satisfaction = red, high = green ({@link #SCORE_COLORS}), the map's primary signal. Section
/// headers (bracket + label) take a per-SECTION {@link Colors#PALETTE} tint purely to visually group
/// their columns; the connecting satisfaction line and the axes are neutral.
///
/// Shapes: task points are full-circle {@link Wedge} discs (mirrors the xychart/timeline dot); the
/// satisfaction line, axes, ticks, and section brackets are {@link Line} segments; the title, tick,
/// task-name, actor, and section labels are glyph paths. Each task's disc + name + actor labels wrap
/// in one `<g role="task">` (plan sirentide-semantic-anchor-g); the line/axes/brackets are decorative
/// and un-anchored (a segment spans two tasks and belongs to neither, exactly like the xychart line).
public final class JourneyLayout {

    private JourneyLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    private static final double MARGIN_LEFT = 46;    // y-axis + tick labels
    private static final double MARGIN_RIGHT = 24;
    private static final double SLOT = 96;            // per-task column width (room for name + actors)
    private static final double PLOT_H = 150;         // satisfaction-axis plot height
    private static final double SECTION_BAND = 26;    // section header (bracket + label) band above plot
    private static final double TITLE_BAND = 26;      // title band (when a title is present)
    private static final double TOP_PAD = 8;          // top pad when there is no title
    private static final double TASK_NAME_GAP = 16;   // task-name baseline below the plot bottom
    private static final double ACTOR_TOP_GAP = 30;   // first actor baseline below the plot bottom
    private static final double ACTOR_STEP = 13;      // per-actor line advance
    private static final double BOTTOM_PAD = 8;

    private static final String AXIS_STROKE = "#94a3b8";
    private static final double AXIS_W = 1;
    private static final String LINE_STROKE = "#94a3b8";   // the connecting satisfaction line
    private static final double LINE_W = 2;
    private static final double DOT_R = 5;
    private static final double SECTION_W = 2;             // section bracket stroke width
    private static final double END_TICK = 4;              // section bracket end-tick length

    private static final double TITLE_SIZE = 15;
    private static final double SECTION_SIZE = 12;
    private static final double TICK_SIZE = 10;
    private static final double TASK_SIZE = 11;
    private static final double ACTOR_SIZE = 10;

    /// Score→colour ramp indexed by score (index 0 unused; scores 1..5): low satisfaction is WARM
    /// (red) and high is COOL (green). Contract-clean `#rrggbb` fills. Documented in the class header.
    private static final String[] SCORE_COLORS = {
        "#e15759",   // 0 (unused — scores are clamped to ≥ 1)
        "#e15759",   // 1 — red
        "#f28e2b",   // 2 — orange
        "#edc948",   // 3 — yellow
        "#86bf6b",   // 4 — light green
        "#59a14f"    // 5 — green
    };

    public static LaidOut layout(Journey journey) {
        return layout(journey, null);
    }

    /// The `math` renderer is accepted for dispatch parity with the other label-bearing types, but a
    /// journey's task / actor / section names are plain text (never a `$…$` formula), so they render
    /// as plain glyph paths — a null `math` is byte-identical.
    public static LaidOut layout(Journey journey, MathFragmentRenderer math) {
        String textColor = journey.textColor();
        boolean hasTitle = journey.title() != null && !journey.title().isBlank();

        // FLATTEN tasks in declaration order, recording each NON-EMPTY section's contiguous task span.
        List<JourneyTask> tasks = new ArrayList<>();
        List<int[]> sectionSpans = new ArrayList<>();   // {firstTaskIdx, lastTaskIdx} per drawn section
        List<String> sectionNames = new ArrayList<>();
        for (JourneySection sec : journey.sections()) {
            if (sec.tasks().isEmpty()) {
                continue;   // an empty section contributes no columns + no header
            }
            int first = tasks.size();
            tasks.addAll(sec.tasks());
            sectionSpans.add(new int[] {first, tasks.size() - 1});
            sectionNames.add(sec.name());
        }
        int n = tasks.size();

        double titleTop = hasTitle ? TITLE_BAND : TOP_PAD;
        double plotTop = titleTop + SECTION_BAND;
        double plotBottom = plotTop + PLOT_H;

        // EMPTY journey (no drawn task): a minimal inert canvas with the bare axes — round-trips as a
        // journey (never the 0×0 inert shell), never throws.
        if (n == 0) {
            double w = MARGIN_LEFT + SLOT + MARGIN_RIGHT;
            List<Shape> only = new ArrayList<>();
            only.add(new Line(MARGIN_LEFT, plotTop, MARGIN_LEFT, plotBottom, AXIS_STROKE, AXIS_W));
            only.add(new Line(MARGIN_LEFT, plotBottom, w - MARGIN_RIGHT, plotBottom, AXIS_STROKE, AXIS_W));
            return new LaidOut(w, plotBottom + BOTTOM_PAD, only);
        }

        double plotLeft = MARGIN_LEFT;
        double W = MARGIN_LEFT + n * SLOT + MARGIN_RIGHT;
        double plotRight = W - MARGIN_RIGHT;

        // Column centre per task.
        double[] px = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = plotLeft + SLOT * (i + 0.5);
        }

        // The satisfaction axis: domain 1..5, projected so score 5 → plotTop and score 1 → plotBottom
        // (a HIGHER score sits HIGHER — the delete-mutant pin in JourneyLayoutTest asserts this map).
        AxisScale axis = new AxisScale(1, 5);

        int maxActors = 0;
        for (JourneyTask t : tasks) {
            maxActors = Math.max(maxActors, t.actors().size());
        }
        double H = plotBottom + ACTOR_TOP_GAP + maxActors * ACTOR_STEP + BOTTOM_PAD;

        List<Shape> shapes = new ArrayList<>();

        // 0) TITLE, centred at the top.
        if (hasTitle) {
            String t = FONT.ellipsize(journey.title(), W - 8, TITLE_SIZE);
            emitCentered(shapes, t, W / 2, 18, TITLE_SIZE, textColor);
        }

        // 1) AXES: the y-axis (full plot height) + the x-axis (baseline at the plot bottom).
        shapes.add(new Line(plotLeft, plotTop, plotLeft, plotBottom, AXIS_STROKE, AXIS_W));
        shapes.add(new Line(plotLeft, plotBottom, plotRight, plotBottom, AXIS_STROKE, AXIS_W));

        // 2) y-axis SATISFACTION ticks 1..5 (integer scale), tick marks + numeric labels.
        for (int score = 1; score <= 5; score++) {
            double ty = axis.project(score, plotBottom, plotTop);
            shapes.add(new Line(plotLeft - 4, ty, plotLeft, ty, AXIS_STROKE, AXIS_W));
            String tl = Integer.toString(score);
            double tw = FONT.runWidth(tl, TICK_SIZE);
            String td = FONT.textPathD(tl, plotLeft - 6 - tw, ty + TICK_SIZE * 0.35, TICK_SIZE);
            if (!td.isBlank()) {
                shapes.add(new GlyphRun(td, textColor));
            }
        }

        // 3) SECTION headers: a coloured bracket spanning the section's task columns (with end-ticks)
        //    plus a centred label above it — the visual grouping of the tasks that belong together.
        for (int s = 0; s < sectionSpans.size(); s++) {
            int[] span = sectionSpans.get(s);
            double xL = plotLeft + SLOT * span[0] + 4;
            double xR = plotLeft + SLOT * (span[1] + 1) - 4;
            double by = plotTop - 8;
            String col = Colors.PALETTE[s % Colors.PALETTE.length];
            shapes.add(new Line(xL, by, xR, by, col, SECTION_W));               // bracket span
            shapes.add(new Line(xL, by, xL, by + END_TICK, col, SECTION_W));    // left end-tick (down)
            shapes.add(new Line(xR, by, xR, by + END_TICK, col, SECTION_W));    // right end-tick (down)
            String name = FONT.ellipsize(sectionNames.get(s), (xR - xL) - 2, SECTION_SIZE);
            emitCentered(shapes, name, (xL + xR) / 2, plotTop - 12, SECTION_SIZE, col);
        }

        // 4) The connecting SATISFACTION line across all task points in order (decorative, bare — a
        //    segment spans two tasks and belongs to neither). Drawn before the discs so a disc sits on
        //    top of its segment ends. Deleting this loop is the receipt-#6 mutant (a named test fails).
        for (int i = 0; i + 1 < n; i++) {
            double y0 = axis.project(tasks.get(i).score(), plotBottom, plotTop);
            double y1 = axis.project(tasks.get(i + 1).score(), plotBottom, plotTop);
            shapes.add(new Line(px[i], y0, px[i + 1], y1, LINE_STROKE, LINE_W));
        }

        // 5) TASK points + labels: one `<g role="task">` per task (disc + name + actor labels), seq
        //    0..n-1 in declaration order; the anchor id is the task name.
        AnchorAssigner assigner = new AnchorAssigner();
        for (int i = 0; i < n; i++) {
            JourneyTask t = tasks.get(i);
            double cy = axis.project(t.score(), plotBottom, plotTop);
            List<Shape> members = new ArrayList<>();
            members.add(new Wedge(px[i], cy, DOT_R, 0, 2 * Math.PI, SCORE_COLORS[t.score()]));
            emitCentered(members, FONT.ellipsize(t.name(), SLOT - 6, TASK_SIZE),
                px[i], plotBottom + TASK_NAME_GAP, TASK_SIZE, textColor);
            double ay = plotBottom + ACTOR_TOP_GAP;
            for (String actor : t.actors()) {
                emitCentered(members, FONT.ellipsize(actor, SLOT - 6, ACTOR_SIZE),
                    px[i], ay, ACTOR_SIZE, textColor);
                ay += ACTOR_STEP;
            }
            shapes.add(new Group(assigner.assign(SirentideRole.TASK, t.name()), members));
        }

        return new LaidOut(W, H, shapes);
    }

    /// Emit a glyph run centred at `cx` on `baseline`. A blank path (all-illegal glyphs) is skipped.
    private static void emitCentered(List<Shape> shapes, String text, double cx, double baseline,
                                     double size, String fill) {
        double w = FONT.runWidth(text, size);
        String d = FONT.textPathD(text, cx - w / 2, baseline, size);
        if (!d.isBlank()) {
            shapes.add(new GlyphRun(d, fill));
        }
    }
}
