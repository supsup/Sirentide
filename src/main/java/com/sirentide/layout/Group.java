package com.sirentide.layout;

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
}
