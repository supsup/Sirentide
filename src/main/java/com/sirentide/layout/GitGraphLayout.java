package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.GitGraph;
import java.util.ArrayList;
import java.util.List;

/// Pure gitGraph layout: a commit history drawn as dots on a horizontal TIME axis (x advances one
/// COLUMN per commit in declaration order), one parallel LANE per branch (offset perpendicular in y),
/// each branch a distinct palette colour. A `branch` opens a lane off the active branch's tip; a
/// `merge` draws an elbow connector from the merged branch's tip into a merge commit on the active
/// lane. Deterministic arithmetic — no optimization, byte-identical bakes (docs/DESIGN.md §6).
///
/// The IR is the raw {@link GitOp} list; this layout REPLAYS it to resolve lanes/colours/connectors,
/// and the malformed cases degrade INERTLY (never throw): a commit before any branch lands on an
/// implicit `main` (lane 0, pre-registered); a `checkout`/`merge` of an unknown branch is dropped; a
/// DUPLICATE `branch` name is dropped (no new lane, no switch); a SELF-merge (merge of the active
/// branch) is dropped; a merge of a branch with no commits yet is dropped.
///
/// Shapes: commit dots are full-circle {@link Wedge} discs (mirrors the timeline dot); the lane spine
/// + branch/merge connectors are {@link Line} segments; commit-id + branch-name labels are glyph
/// paths. Each commit dot is wrapped in a `<g role="commit">`; each drawn branch's spine + name label
/// in a `<g role="branch">` (plan sirentide-semantic-anchor-g). Connectors are decorative + un-anchored.
public final class GitGraphLayout {

    private GitGraphLayout() {}

    private static final double MARGIN_LEFT = 74;    // room for the left-side branch-name labels
    private static final double MARGIN_RIGHT = 40;
    private static final double MARGIN_BOTTOM = 30;   // room for the below-dot commit-id labels
    private static final double TOP = 42;             // y of lane 0 (main)
    private static final double STEP_X = 46;          // horizontal advance per commit column
    private static final double LANE_GAP = 48;        // perpendicular offset between branch lanes
    private static final double DOT_R = 6;            // commit-dot radius
    private static final double SPINE_W = 3;          // lane-spine + connector stroke width
    private static final double ID_SIZE = 10;         // commit-id label font size
    private static final double BRANCH_SIZE = 11;     // branch-name label font size
    private static final double CLAMP_MARGIN = 2;     // min gap kept from the canvas edge

    private static final FontMetrics FONT = FontMetrics.bundled();

    public static LaidOut layout(GitGraph graph) {
        return layout(graph, null);
    }

    /// The `math` renderer is accepted for dispatch parity with the other label-bearing types, but a
    /// git commit id / branch name is a plain identifier (never a `$…$` formula), so it is rendered as
    /// plain glyph text — a null `math` is byte-identical.
    public static LaidOut layout(GitGraph graph, MathFragmentRenderer math) {
        String textColor = graph.textColor();

        // -- REPLAY the ops into lanes + commits + connectors ------------------------------------
        // The SHARED resolved model (GitGraphReplay): the SAME replay the a11y describer reads, so the
        // spoken branch/commit/merge counts can never disagree with what is drawn (SIR-11a). Layout
        // owns only the pixels — the model is geometry-free (col/lane indices).
        GitGraphReplay replay = GitGraphReplay.of(graph);
        List<GitGraphReplay.Commit> commits = replay.commits();
        List<GitGraphReplay.Connector> connectors = replay.connectors();

        // -- CANVAS size (grow to fit the columns + lanes) ---------------------------------------
        int totalCols = replay.totalCols();
        if (totalCols == 0) {
            // No commit landed anywhere (e.g. a bare `gitGraph`, or only inert ops) — a minimal empty
            // canvas, round-trips as a gitGraph (never the inert shell).
            return LaidOut.of(MARGIN_LEFT + MARGIN_RIGHT, TOP + MARGIN_BOTTOM);
        }
        int maxLane = 0;
        for (GitGraphReplay.Commit cd : commits) {
            maxLane = Math.max(maxLane, cd.lane());
        }
        double width = MARGIN_LEFT + (totalCols - 1) * STEP_X + MARGIN_RIGHT;
        double height = TOP + maxLane * LANE_GAP + MARGIN_BOTTOM;

        List<Shape> shapes = new ArrayList<>();

        // 1) CONNECTORS first (background, decorative, un-anchored). Branch: drop vertically at the
        //    parent tip then run horizontally along the child lane. Merge: run horizontally along the
        //    source lane then rise into the merge commit. Two straight segments = a clean elbow.
        for (GitGraphReplay.Connector cn : connectors) {
            double fromX = x(cn.fromCol());
            double fromY = y(cn.fromLane());
            double toX = x(cn.toCol());
            double toY = y(cn.toLane());
            if (cn.isBranch()) {
                shapes.add(new Line(fromX, fromY, fromX, toY, cn.color(), SPINE_W));   // vertical at branch point
                shapes.add(new Line(fromX, toY, toX, toY, cn.color(), SPINE_W));       // along the child lane
            } else {
                shapes.add(new Line(fromX, fromY, toX, fromY, cn.color(), SPINE_W));   // along the source lane
                shapes.add(new Line(toX, fromY, toX, toY, cn.color(), SPINE_W));       // rise into the merge
            }
        }

        AnchorAssigner assigner = new AnchorAssigner();

        // 2) BRANCH lane spines + name labels (one `<g role="branch">` per DRAWN branch, registration
        //    order = main first). A single-commit lane has a zero-length spine (skipped); the group
        //    still carries the branch-name label, so it is always non-empty + anchored.
        for (GitGraphReplay.Branch b : replay.branches()) {
            if (!b.drawn()) {
                continue;   // a branch declared but never committed to draws no lane
            }
            List<Shape> members = new ArrayList<>();
            double laneY = y(b.lane());
            if (b.lastCol() > b.firstCol()) {
                members.add(new Line(x(b.firstCol()), laneY, x(b.lastCol()), laneY, b.color(), SPINE_W));
            }
            // Branch name in the left margin, colour-matched to the lane, ellipsized to fit the margin.
            String name = FONT.ellipsize(b.name(), MARGIN_LEFT - 12, BRANCH_SIZE);
            String nameD = FONT.textPathD(name, 6, laneY + 3.5, BRANCH_SIZE);
            if (!nameD.isBlank()) {
                members.add(new GlyphRun(nameD, b.color()));
            }
            shapes.add(new Group(assigner.assign(SirentideRole.BRANCH, b.name()), members));
        }

        // 3) COMMIT dots (one `<g role="commit">` per commit, declaration order). The dot rides in the
        //    group; an explicit id gets a centred label BELOW the dot (clamped in-frame). A merge/unlabeled
        //    commit's group carries just the dot. The anchor id is the commit id, else its branch name.
        for (GitGraphReplay.Commit cd : commits) {
            double cx = x(cd.col());
            double cy = y(cd.lane());
            List<Shape> members = new ArrayList<>();
            members.add(new Wedge(cx, cy, DOT_R, 0, 2 * Math.PI, cd.color()));
            if (cd.id() != null && !cd.id().isBlank()) {
                String label = FONT.ellipsize(cd.id(), STEP_X * 1.6, ID_SIZE);
                double w = FONT.runWidth(label, ID_SIZE);
                double originX = Math.max(CLAMP_MARGIN, Math.min(cx - w / 2, width - CLAMP_MARGIN - w));
                String d = FONT.textPathD(label, originX, cy + DOT_R + 11, ID_SIZE);
                if (!d.isBlank()) {
                    members.add(new GlyphRun(d, textColor));
                }
            }
            String anchorBase = cd.id() != null && !cd.id().isBlank()
                ? cd.id() : replay.branchNameOf(cd.lane());
            shapes.add(new Group(assigner.assign(SirentideRole.COMMIT, anchorBase), members));
        }

        return new LaidOut(width, height, shapes);
    }

    private static double x(int col) {
        return MARGIN_LEFT + col * STEP_X;
    }

    private static double y(int lane) {
        return TOP + lane * LANE_GAP;
    }
}
