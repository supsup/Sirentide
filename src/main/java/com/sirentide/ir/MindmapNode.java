package com.sirentide.ir;

import java.util.List;

/// One node in a {@link Mindmap} tree: a display `text` plus its ordered `children`. The tree is
/// built from the DSL's INDENTATION — the root is the first (shallowest) node, and each deeper line
/// is a child of the nearest shallower line (the parser owns that fold; see
/// {@link com.sirentide.parse.DslParser}). A leaf carries an empty `children` list. The recursion is
/// depth-bounded at parse time (the parser caps depth + node count, §6/§7), so the immutable tree is
/// safe to traverse recursively in layout / a11y.
///
/// `text` may be empty (a bare `root` keyword with no following text still yields a root node); a
/// null text or child list is normalized to empty so the IR invariant always holds.
public record MindmapNode(String text, List<MindmapNode> children) {

    public MindmapNode {
        text = text == null ? "" : text;
        children = children == null ? List.of() : List.copyOf(children);
    }

    /// A leaf node with the given text (no children) — keeps a caller/test that builds a bare node
    /// unchanged.
    public MindmapNode(String text) {
        this(text, List.of());
    }
}
