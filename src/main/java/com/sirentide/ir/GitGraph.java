package com.sirentide.ir;

import java.util.List;

/// Sirentide's twelfth diagram type: a mermaid-style `gitGraph` — a commit history drawn as dots on a
/// horizontal time axis, one parallel LANE per branch (each a distinct palette colour), with
/// branch/merge connectors elbowing between lanes. The IR is the ORDERED {@link GitOp} list exactly as
/// declared; the lane assignment, per-branch colour, and connector geometry are DERIVED at layout time
/// by replaying the ops ({@link com.sirentide.layout.GitGraphLayout}). Keeping the IR as the raw op
/// list (not a pre-resolved node/edge set) makes the malformed decisions — a commit before any branch
/// (implicit `main`), a checkout/merge of an unknown branch (inert), a duplicate branch (inert), a
/// self-merge (inert) — a single replay concern, never fails the bake (docs/DESIGN.md §6).
///
/// `textColor` fills the page-background text (commit-id + branch-name labels); default `currentColor`
/// so it inherits the host page's text colour. Layout dispatch is
/// `case GitGraph gg -> GitGraphLayout.layout(gg, math)`.
public record GitGraph(List<GitOp> ops, String textColor) implements Diagram {

    public GitGraph {
        ops = List.copyOf(ops);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps tests that build a `GitGraph`
    /// from just its ops unchanged.
    public GitGraph(List<GitOp> ops) {
        this(ops, "currentColor");
    }
}
