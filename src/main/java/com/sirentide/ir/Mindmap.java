package com.sirentide.ir;

/// Sirentide's fourteenth diagram type: a mermaid-style `mindmap` — an INDENTATION-defined hierarchy
/// tree. The body's leading-space depth defines the structure: the first (shallowest) line is the
/// root, and each deeper line is a child of the nearest shallower line. Layout
/// ({@link com.sirentide.layout.MindmapLayout}) draws a LEFT-TO-RIGHT layered tree — depth maps to an
/// x-column, sibling leaves stack down the y-axis, and each internal node's y CENTRES on its
/// children's y-range (a tidy-ish tree) — wiring each parent to its children with an elbow connector.
///
/// The IR is a single (possibly null) `root` {@link MindmapNode} holding the recursive child tree.
/// A `null` root is an EMPTY mindmap (a bare `mindmap` body) — it still round-trips as a mindmap and
/// lays out to a minimal inert canvas, never the 0×0 shell. The malformed decisions live in the
/// parser/layout and never fail the bake (docs/DESIGN.md §6): an over-indented / shallow-of-root line
/// attaches to the root; inconsistent indentation snaps to the nearest shallower open ancestor; a
/// node past the depth/count cap is dropped.
///
/// `textColor` fills the page-background text (currently unused — node labels contrast against their
/// box fill, like the flowchart — but carried for dispatch parity + a future off-box label). Default
/// `currentColor`. Layout dispatch is `case Mindmap m -> MindmapLayout.layout(m, math)`.
public record Mindmap(MindmapNode root, String textColor) implements Diagram {

    public Mindmap {
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps a caller that builds a mindmap
    /// from just its root unchanged.
    public Mindmap(MindmapNode root) {
        this(root, "currentColor");
    }
}
