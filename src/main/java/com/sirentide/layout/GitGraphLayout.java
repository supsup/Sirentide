package com.sirentide.layout;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.contract.SirentideRole;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /// The default active branch name — mermaid's default. Pre-registered on lane 0 so a commit before
    /// any `branch` (implicit main) has a lane to land on.
    private static final String MAIN = "main";

    /// Lane cap (DESIGN §6/§7): past this a brand-new `branch` is dropped (inert) so a pathological
    /// branch count can't grow the canvas unboundedly — never throws, never allocates without bound.
    private static final int MAX_LANES = 40;

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
        Map<String, BranchState> branches = new LinkedHashMap<>();
        branches.put(MAIN, new BranchState(0, Colors.PALETTE[0]));
        String current = MAIN;
        List<CommitDraw> commits = new ArrayList<>();
        List<Connector> connectors = new ArrayList<>();
        int col = 0;

        for (GitOp op : graph.ops()) {
            switch (op) {
                case GitOp.Commit c -> {
                    BranchState b = branches.get(current);
                    int at = col++;
                    if (b.firstCol < 0) {
                        b.firstCol = at;
                        // The branch's FIRST commit anchors its branch connector (from the parent tip
                        // recorded at `branch` time). A branch off a parent with no commits yet has no
                        // connector (parentTipCol < 0) — the lane simply starts fresh.
                        if (b.pending && b.parentTipCol >= 0) {
                            connectors.add(new Connector(true, b.parentTipCol, b.parentLane,
                                at, b.lane, b.color));
                        }
                        b.pending = false;
                    }
                    b.lastCol = at;
                    b.tipCol = at;
                    commits.add(new CommitDraw(at, b.lane, b.color, c.id()));
                }
                case GitOp.Branch br -> {
                    // A duplicate name is inert (no new lane, no switch); past the lane cap it is
                    // dropped. Otherwise open a new lane off the current tip and (mermaid) switch to it.
                    if (!branches.containsKey(br.name()) && branches.size() < MAX_LANES) {
                        BranchState parent = branches.get(current);
                        int lane = branches.size();
                        BranchState nb = new BranchState(lane, Colors.PALETTE[lane % Colors.PALETTE.length]);
                        nb.parentTipCol = parent.tipCol;   // may be -1 (parent has no commit yet)
                        nb.parentLane = parent.lane;
                        nb.pending = true;
                        branches.put(br.name(), nb);
                        current = br.name();
                    }
                }
                case GitOp.Checkout co -> {
                    if (branches.containsKey(co.name())) {
                        current = co.name();
                    }
                    // an unknown branch checkout is inert (the active branch is unchanged)
                }
                case GitOp.Merge mg -> {
                    BranchState src = branches.get(mg.name());
                    // Inert unless the source is a KNOWN, DISTINCT branch with at least one commit.
                    if (src != null && !mg.name().equals(current) && src.tipCol >= 0) {
                        BranchState cur = branches.get(current);
                        int at = col++;
                        if (cur.firstCol < 0) {
                            cur.firstCol = at;
                        }
                        cur.lastCol = at;
                        cur.tipCol = at;
                        // The merge commit lands on the ACTIVE lane; the connector runs from the merged
                        // branch's tip (coloured by the SOURCE branch, mermaid convention).
                        commits.add(new CommitDraw(at, cur.lane, cur.color, null));
                        connectors.add(new Connector(false, src.tipCol, src.lane, at, cur.lane, src.color));
                    }
                }
            }
        }

        // -- CANVAS size (grow to fit the columns + lanes) ---------------------------------------
        int totalCols = col;
        if (totalCols == 0) {
            // No commit landed anywhere (e.g. a bare `gitGraph`, or only inert ops) — a minimal empty
            // canvas, round-trips as a gitGraph (never the inert shell).
            return LaidOut.of(MARGIN_LEFT + MARGIN_RIGHT, TOP + MARGIN_BOTTOM);
        }
        int maxLane = 0;
        for (CommitDraw cd : commits) {
            maxLane = Math.max(maxLane, cd.lane);
        }
        double width = MARGIN_LEFT + (totalCols - 1) * STEP_X + MARGIN_RIGHT;
        double height = TOP + maxLane * LANE_GAP + MARGIN_BOTTOM;

        List<Shape> shapes = new ArrayList<>();

        // 1) CONNECTORS first (background, decorative, un-anchored). Branch: drop vertically at the
        //    parent tip then run horizontally along the child lane. Merge: run horizontally along the
        //    source lane then rise into the merge commit. Two straight segments = a clean elbow.
        for (Connector cn : connectors) {
            double fromX = x(cn.fromCol);
            double fromY = y(cn.fromLane);
            double toX = x(cn.toCol);
            double toY = y(cn.toLane);
            if (cn.isBranch) {
                shapes.add(new Line(fromX, fromY, fromX, toY, cn.color, SPINE_W));   // vertical at branch point
                shapes.add(new Line(fromX, toY, toX, toY, cn.color, SPINE_W));       // along the child lane
            } else {
                shapes.add(new Line(fromX, fromY, toX, fromY, cn.color, SPINE_W));   // along the source lane
                shapes.add(new Line(toX, fromY, toX, toY, cn.color, SPINE_W));       // rise into the merge
            }
        }

        AnchorAssigner assigner = new AnchorAssigner();

        // 2) BRANCH lane spines + name labels (one `<g role="branch">` per DRAWN branch, registration
        //    order = main first). A single-commit lane has a zero-length spine (skipped); the group
        //    still carries the branch-name label, so it is always non-empty + anchored.
        for (Map.Entry<String, BranchState> e : branches.entrySet()) {
            BranchState b = e.getValue();
            if (b.firstCol < 0) {
                continue;   // a branch declared but never committed to draws no lane
            }
            List<Shape> members = new ArrayList<>();
            double laneY = y(b.lane);
            if (b.lastCol > b.firstCol) {
                members.add(new Line(x(b.firstCol), laneY, x(b.lastCol), laneY, b.color, SPINE_W));
            }
            // Branch name in the left margin, colour-matched to the lane, ellipsized to fit the margin.
            String name = FONT.ellipsize(e.getKey(), MARGIN_LEFT - 12, BRANCH_SIZE);
            String nameD = FONT.textPathD(name, 6, laneY + 3.5, BRANCH_SIZE);
            if (!nameD.isBlank()) {
                members.add(new GlyphRun(nameD, b.color));
            }
            shapes.add(new Group(assigner.assign(SirentideRole.BRANCH, e.getKey()), members));
        }

        // 3) COMMIT dots (one `<g role="commit">` per commit, declaration order). The dot rides in the
        //    group; an explicit id gets a centred label BELOW the dot (clamped in-frame). A merge/unlabeled
        //    commit's group carries just the dot. The anchor id is the commit id, else its branch name.
        for (CommitDraw cd : commits) {
            double cx = x(cd.col);
            double cy = y(cd.lane);
            List<Shape> members = new ArrayList<>();
            members.add(new Wedge(cx, cy, DOT_R, 0, 2 * Math.PI, cd.color));
            if (cd.id != null && !cd.id.isBlank()) {
                String label = FONT.ellipsize(cd.id, STEP_X * 1.6, ID_SIZE);
                double w = FONT.runWidth(label, ID_SIZE);
                double originX = Math.max(CLAMP_MARGIN, Math.min(cx - w / 2, width - CLAMP_MARGIN - w));
                String d = FONT.textPathD(label, originX, cy + DOT_R + 11, ID_SIZE);
                if (!d.isBlank()) {
                    members.add(new GlyphRun(d, textColor));
                }
            }
            String anchorBase = cd.id != null && !cd.id.isBlank() ? cd.id : branchNameOf(branches, cd.lane);
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

    /// The branch name owning a given lane (for a commit-dot anchor id fallback when the commit has no
    /// explicit id). Deterministic first match in registration order.
    private static String branchNameOf(Map<String, BranchState> branches, int lane) {
        for (Map.Entry<String, BranchState> e : branches.entrySet()) {
            if (e.getValue().lane == lane) {
                return e.getKey();
            }
        }
        return MAIN;
    }

    /// A mutable per-branch accumulator on the replay: its lane index + colour are fixed at open time;
    /// `firstCol`/`lastCol`/`tipCol` grow as commits land; `parentTipCol`/`parentLane`/`pending` carry
    /// the branch-connector origin until the first commit anchors it. Replay-internal, never in the IR.
    private static final class BranchState {
        final int lane;
        final String color;
        int firstCol = -1;
        int lastCol = -1;
        int tipCol = -1;
        int parentTipCol = -1;
        int parentLane = -1;
        boolean pending = false;

        BranchState(int lane, String color) {
            this.lane = lane;
            this.color = color;
        }
    }

    /// A resolved commit to draw: its column (time), lane (branch), colour (branch), and optional id
    /// label. Replay-internal.
    private record CommitDraw(int col, int lane, String color, String id) {}

    /// A resolved connector to draw: a BRANCH connector (`isBranch`) from a parent tip to a child's
    /// first commit, or a MERGE connector from a source tip into a merge commit. Carries endpoints as
    /// (col, lane) pairs + the stroke colour. Replay-internal.
    private record Connector(boolean isBranch, int fromCol, int fromLane, int toCol, int toLane,
                             String color) {}
}
