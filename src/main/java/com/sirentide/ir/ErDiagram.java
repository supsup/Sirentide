package com.sirentide.ir;

import java.util.List;

/// Sirentide's tenth diagram type: a mermaid-style entity-relationship diagram — a set of
/// {@link ErEntity} tables (each a name header over typed attribute rows) wired by
/// {@link ErRelation}s whose CROW-FOOT cardinality marker at EACH end (bar / circle / three-prong
/// fork combos) is the whole point of the type. Layout places the entity tables in a deterministic
/// grid and routes each relation between table borders with the correct cardinality glyph combo at
/// each end (docs/DESIGN.md §4/§5). `textColor` is the off-box page text colour (edge labels), the
/// same seam every type carries.
///
/// Layout dispatch is `case ErDiagram er -> ErDiagramLayout.layout(er, math)`.
public record ErDiagram(List<ErEntity> entities, List<ErRelation> relations, String textColor)
    implements Diagram {

    public ErDiagram {
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
