package com.sirentide.layout;

import java.util.ArrayList;
import java.util.List;

/// A semantic GROUP: the shapes that together draw ONE logical diagram element (a flowchart node or
/// edge, a pie slice), tagged with the {@link Anchor} the emitter writes onto their `<g>` wrapper
/// (plan sirentide-semantic-anchor-g). Grouping is PURELY additive — the member shapes keep their
/// exact geometry; the `<g>` carries ONLY the closed data-sirentide-role/id/seq attribute set (no
/// transform, no fill, no style, no coordinate change), so a grouped diagram is geometry-identical to
/// its ungrouped self. Members are leaf shapes (a Group never nests another Group).
public record Group(Anchor anchor, List<Shape> members) implements Shape {

    public Group {
        if (anchor == null) {
            throw new IllegalArgumentException("group anchor must not be null");
        }
        members = List.copyOf(members);
    }

    /// Expand a shape list to its DRAWABLE LEAVES: every {@link Group} is replaced (recursively) by its
    /// members; every other shape passes through unchanged. The additive `<g>` anchor wrappers are thus
    /// transparent to a pure-geometry consumer. Used by the per-type layout tests, which assert on the
    /// leaf rect/line/path/wedge/glyph geometry that anchoring is required to leave byte-for-byte intact.
    public static List<Shape> flatten(List<Shape> shapes) {
        List<Shape> out = new ArrayList<>();
        for (Shape s : shapes) {
            if (s instanceof Group g) {
                out.addAll(flatten(g.members()));
            } else {
                out.add(s);
            }
        }
        return out;
    }
}
