package com.sirentide.ir;

import java.util.List;

/// Sirentide's ninth diagram type: a mermaid-style UML class diagram — a set of {@link ClassBox}es
/// (each a three-compartment box: name / attributes / methods) wired by {@link ClassRelation}s whose
/// UML marker glyph (hollow triangle / filled diamond / hollow diamond / open arrow) is the whole
/// point of the type. Layout places the boxes in a deterministic grid and routes each relation
/// between box borders with its marker at the correct end (docs/DESIGN.md §4/§5). `textColor` is the
/// off-box page text colour (edge labels), same seam every type carries.
///
/// Layout dispatch is `case ClassDiagram cd -> ClassDiagramLayout.layout(cd, math)`.
public record ClassDiagram(List<ClassBox> classes, List<ClassRelation> relations, String textColor)
    implements Diagram {

    public ClassDiagram {
        classes = List.copyOf(classes);
        relations = List.copyOf(relations);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
