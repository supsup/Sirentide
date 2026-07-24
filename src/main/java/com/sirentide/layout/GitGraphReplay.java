package com.sirentide.layout;

import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The resolved gitGraph model: a SINGLE replay of the raw {@link GitOp} list into lanes, commits,
/// connectors, and merges — the shared source of truth for BOTH the visual layout
/// ({@link GitGraphLayout}) AND the accessibility description
/// ({@link com.sirentide.a11y.A11yDescriber}). One replay guarantees the a11y text can never disagree
/// with the render: the same inert rules (implicit `main`; an unknown-branch checkout/merge, a
/// duplicate branch, a self-merge, an empty-branch merge — all dropped) AND the same
/// {@link #MAX_LANES} lane cap resolve identically for text and geometry.
///
/// SIR-11a: the a11y describer previously ran its OWN second replay with NO lane cap, so a >40-branch
/// graph rendered 40 lanes but the a11y text announced (e.g.) "42 branches". Converging both paths on
/// THIS one capped replay makes the announced branch count equal the rendered lane count by construction.
///
/// Pure, geometry-free (columns + lanes are integer indices; pixels are applied later by the layout)
/// and deterministic — byte-identical replays for byte-identical ops.
public final class GitGraphReplay {

    /// The default active branch — mermaid's default, pre-registered on lane 0 so a commit before any
    /// `branch` (implicit main) has a lane to land on.
    public static final String MAIN = "main";

    /// Lane cap (DESIGN §6/§7): past this a brand-new `branch` is dropped (inert) so a pathological
    /// branch count can't grow the canvas unboundedly. Applied ONCE here so layout + a11y agree on the
    /// drawn lane set.
    public static final int MAX_LANES = 40;

    /// A resolved branch: its registration-order lane + colour (fixed at open time), the column span
    /// its commits cover (`firstCol < 0` when declared but never committed to → no lane is drawn), and
    /// the replay-internal branch-connector origin carried until the first commit anchors it.
    public static final class Branch {
        private final String name;
        private final int lane;
        private final String color;
        int firstCol = -1;
        int lastCol = -1;
        int tipCol = -1;
        int parentTipCol = -1;
        int parentLane = -1;
        boolean pending = false;

        Branch(String name, int lane, String color) {
            this.name = name;
            this.lane = lane;
            this.color = color;
        }

        public String name() {
            return name;
        }

        public int lane() {
            return lane;
        }

        public String color() {
            return color;
        }

        public int firstCol() {
            return firstCol;
        }

        public int lastCol() {
            return lastCol;
        }

        /// True iff this branch received at least one commit (a lane is actually rendered for it).
        public boolean drawn() {
            return firstCol >= 0;
        }
    }

    /// A resolved commit: its column (time), lane + colour (the branch it landed on), optional id
    /// label (null for a merge / unlabeled commit), and the NAME of the branch it landed on.
    public record Commit(int col, int lane, String color, String id, String branch) {}

    /// A resolved connector: a BRANCH connector (`isBranch`) from a parent tip to a child's first
    /// commit, or a MERGE connector from a source tip into a merge commit. Endpoints are (col, lane).
    public record Connector(boolean isBranch, int fromCol, int fromLane, int toCol, int toLane,
                            String color) {}

    /// A resolved merge naming its `source` and `target` branches (for the a11y "Merges:" list).
    public record Merge(String source, String target) {}

    private final Map<String, Branch> branches;
    private final List<Commit> commits;
    private final List<Connector> connectors;
    private final List<Merge> merges;
    private final int totalCols;

    private GitGraphReplay(Map<String, Branch> branches, List<Commit> commits,
                           List<Connector> connectors, List<Merge> merges, int totalCols) {
        this.branches = branches;
        this.commits = commits;
        this.connectors = connectors;
        this.merges = merges;
        this.totalCols = totalCols;
    }

    /// Replay `graph`'s ops into the resolved model — the ONE replay both the layout and the a11y
    /// describer consume.
    public static GitGraphReplay of(GitGraph graph) {
        Map<String, Branch> branches = new LinkedHashMap<>();
        branches.put(MAIN, new Branch(MAIN, 0, Colors.PALETTE[0]));
        String current = MAIN;
        List<Commit> commits = new ArrayList<>();
        List<Connector> connectors = new ArrayList<>();
        List<Merge> merges = new ArrayList<>();
        int col = 0;

        for (GitOp op : graph.ops()) {
            switch (op) {
                case GitOp.Commit c -> {
                    Branch b = branches.get(current);
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
                    commits.add(new Commit(at, b.lane, b.color, c.id(), current));
                }
                case GitOp.Branch br -> {
                    // A duplicate name is inert (no new lane, no switch); past the lane cap it is
                    // dropped. Otherwise open a new lane off the current tip and (mermaid) switch to it.
                    if (!branches.containsKey(br.name()) && branches.size() < MAX_LANES) {
                        Branch parent = branches.get(current);
                        int lane = branches.size();
                        Branch nb = new Branch(br.name(), lane, Colors.PALETTE[lane % Colors.PALETTE.length]);
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
                    Branch src = branches.get(mg.name());
                    // Inert unless the source is a KNOWN, DISTINCT branch with at least one commit.
                    if (src != null && !mg.name().equals(current) && src.tipCol >= 0) {
                        Branch cur = branches.get(current);
                        int at = col++;
                        if (cur.firstCol < 0) {
                            cur.firstCol = at;
                        }
                        cur.lastCol = at;
                        cur.tipCol = at;
                        // The merge commit lands on the ACTIVE lane; the connector runs from the merged
                        // branch's tip (coloured by the SOURCE branch, mermaid convention).
                        commits.add(new Commit(at, cur.lane, cur.color, null, current));
                        connectors.add(new Connector(false, src.tipCol, src.lane, at, cur.lane, src.color));
                        merges.add(new Merge(mg.name(), current));
                    }
                }
            }
        }
        return new GitGraphReplay(branches, commits, connectors, merges, col);
    }

    /// The branches in registration order (main first) — ALL registered, including any declared but
    /// never committed to (which draw no lane).
    public List<Branch> branches() {
        return new ArrayList<>(branches.values());
    }

    /// The DRAWN branches — those that received at least one commit (a lane is actually rendered),
    /// registration order. Its size is the rendered lane count the a11y branch count must match.
    public List<Branch> drawnBranches() {
        List<Branch> out = new ArrayList<>();
        for (Branch b : branches.values()) {
            if (b.drawn()) {
                out.add(b);
            }
        }
        return out;
    }

    public List<Commit> commits() {
        return commits;
    }

    public List<Connector> connectors() {
        return connectors;
    }

    public List<Merge> merges() {
        return merges;
    }

    /// The total number of commit COLUMNS (commits + merge commits) — the time-axis extent.
    public int totalCols() {
        return totalCols;
    }

    /// The branch name owning `lane` (registration-order first match) — the commit-dot anchor-id
    /// fallback when a commit has no explicit id.
    public String branchNameOf(int lane) {
        for (Branch b : branches.values()) {
            if (b.lane == lane) {
                return b.name;
            }
        }
        return MAIN;
    }
}
