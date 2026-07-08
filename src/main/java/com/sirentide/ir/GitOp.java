package com.sirentide.ir;

/// One declaration-order OPERATION in a {@link GitGraph} — the ordered op list IS the diagram (a git
/// history is inherently a sequence of actions, so an op list projects it more faithfully than a
/// pre-resolved node/edge set). Layout ({@link com.sirentide.layout.GitGraphLayout}) replays these
/// ops to assign lanes, colours, and connectors; the malformed cases (an unknown-branch
/// checkout/merge, a commit before any branch, a duplicate branch, a self-merge) are resolved there,
/// gracefully + inertly (docs/DESIGN.md §6). Sealed so the replay switch is exhaustive.
public sealed interface GitOp permits GitOp.Commit, GitOp.Branch, GitOp.Checkout, GitOp.Merge {

    /// A `commit [id: "x"]` — adds a commit dot to the active branch's lane. `id` is the optional
    /// author label (null when absent → the dot is unlabeled). The lane it lands on is decided at
    /// replay time (the active branch), not here.
    record Commit(String id) implements GitOp {}

    /// A `branch <name>` — opens a NEW lane branching off the active branch at its current tip, and
    /// (mermaid semantics) switches the active branch to it. A duplicate name is inert at replay.
    record Branch(String name) implements GitOp {
        public Branch {
            name = name == null ? "" : name;
        }
    }

    /// A `checkout <name>` — switches the active branch to `name`. An unknown branch is inert at
    /// replay (the switch is dropped; following commits stay on the current branch).
    record Checkout(String name) implements GitOp {
        public Checkout {
            name = name == null ? "" : name;
        }
    }

    /// A `merge <name>` — merges `name`'s tip into the active branch: a merge commit on the active
    /// lane plus a connector from `name`'s tip. An unknown branch, or a merge of the active branch
    /// into itself, is inert at replay.
    record Merge(String name) implements GitOp {
        public Merge {
            name = name == null ? "" : name;
        }
    }
}
