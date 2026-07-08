package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.a11y.A11y;
import com.sirentide.a11y.A11yDescriber;
import com.sirentide.ir.ClassBox;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ClassRelation;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.RelationKind;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Class-diagram PARSE + A11Y + malformed-degrade pins (plan sirentide-class-diagram). The MARKER
/// geometry — the fidelity crux — is pinned in {@link com.sirentide.layout.ClassDiagramLayoutTest}.
class ClassDiagramTest {

    private static ClassDiagram parse(String dsl) {
        Diagram ir = DslParser.parse(dsl);
        assertInstanceOf(ClassDiagram.class, ir, "classDiagram parses to a ClassDiagram: " + ir);
        return (ClassDiagram) ir;
    }

    @Test
    void classBlockSplitsAttributesFromMethods() {
        ClassDiagram cd = parse("classDiagram\n  class Animal {\n    +String name\n    +int age\n"
            + "    +eat() void\n    +sleep()\n  }\n");
        assertEquals(1, cd.classes().size());
        ClassBox animal = cd.classes().get(0);
        assertEquals("Animal", animal.name());
        // A member with `(` is a method; else an attribute — in declaration order.
        assertEquals(List.of("+String name", "+int age"), animal.attributes());
        assertEquals(List.of("+eat() void", "+sleep()"), animal.methods());
        assertTrue(animal.hasMembers());
    }

    @Test
    void visibilityPrefixesAreRetainedInMemberText() {
        ClassDiagram cd = parse("classDiagram\n  class C {\n    +pub\n    -priv\n    #prot\n    ~pkg\n"
            + "    +go()\n  }\n");
        ClassBox c = cd.classes().get(0);
        assertEquals(List.of("+pub", "-priv", "#prot", "~pkg"), c.attributes());
        assertEquals(List.of("+go()"), c.methods());
    }

    @Test
    void eachOfTheFiveOperatorsParsesToItsRelationKind() {
        ClassDiagram cd = parse("classDiagram\n  A <|-- B\n  A *-- C\n  A o-- D\n  A --> E\n  A ..> F\n");
        List<ClassRelation> rel = cd.relations();
        assertEquals(5, rel.size());
        assertEquals(RelationKind.INHERITANCE, rel.get(0).kind());
        assertEquals(RelationKind.COMPOSITION, rel.get(1).kind());
        assertEquals(RelationKind.AGGREGATION, rel.get(2).kind());
        assertEquals(RelationKind.ASSOCIATION, rel.get(3).kind());
        assertEquals(RelationKind.DEPENDENCY, rel.get(4).kind());
        // Operands are captured left-to-right as authored; a `: label` peels off.
        assertEquals("A", rel.get(0).left());
        assertEquals("B", rel.get(0).right());
    }

    @Test
    void relationLabelPeelsAtTheColon() {
        ClassDiagram cd = parse("classDiagram\n  Animal <|-- Dog : inherits\n");
        assertEquals("inherits", cd.relations().get(0).label());
        assertEquals("Dog", cd.relations().get(0).right());
    }

    @Test
    void referencedClassesAutoVivifyInFirstSeenOrder() {
        // Collar/Owner are never declared with a `class {}` block — they auto-vivify as empty classes
        // (mermaid semantics) so the relationship renders, in first-seen order after the declared ones.
        ClassDiagram cd = parse("classDiagram\n  class Animal {\n    +name\n  }\n"
            + "  Animal *-- Collar\n  Animal o-- Owner\n");
        assertEquals(List.of("Animal", "Collar", "Owner"),
            cd.classes().stream().map(ClassBox::name).toList());
        assertFalse(cd.classes().get(1).hasMembers(), "auto-vivified Collar is an empty class");
    }

    @Test
    void emptyClassHasNoMembers() {
        ClassDiagram cd = parse("classDiagram\n  class Loner\n");
        assertEquals(1, cd.classes().size());
        assertFalse(cd.classes().get(0).hasMembers());
    }

    @Test
    void unclosedBraceDegradesGracefullyNeverThrows() {
        // An unclosed `{` closes at EOF (keeps the members gathered) — degrade-not-throw (DESIGN §6).
        String dsl = "classDiagram\n  class A {\n    +x\n    +go()\n";
        ClassDiagram cd = parse(dsl);
        assertEquals(1, cd.classes().size());
        assertEquals(List.of("+x"), cd.classes().get(0).attributes());
        assertEquals(List.of("+go()"), cd.classes().get(0).methods());
        // And the full bake never throws, staying a valid SVG.
        assertTrue(Sirentide.render(dsl).startsWith("<svg"), "unclosed brace still bakes an SVG");
    }

    @Test
    void malformedRelationWithEmptyEndpointIsDropped() {
        // A relation operator with a missing right endpoint is malformed → dropped (never throw). The
        // referenced-but-valid left endpoint still auto-vivifies (it was named before the drop check?
        // no — the drop happens BEFORE registration, so nothing registers). And a garbage line drops.
        ClassDiagram cd = parse("classDiagram\n  Animal *-- \n  total garbage line\n  A <|-- B\n");
        assertEquals(1, cd.relations().size(), "only the well-formed relation survives");
        assertEquals("A", cd.relations().get(0).left());
        assertTrue(Sirentide.render("classDiagram\n  Animal *-- \n").startsWith("<svg"));
    }

    @Test
    void emptyBodyRoundTripsAsAClassDiagramNotEmpty() {
        ClassDiagram cd = parse("classDiagram\n");
        assertEquals(0, cd.classes().size());
        assertEquals(0, cd.relations().size());
    }

    @Test
    void a11yDescriptionReflectsClassesAndRelations() {
        Diagram ir = DslParser.parse("classDiagram\n  class Animal {\n    +name\n  }\n"
            + "  class Dog {\n    +bark()\n  }\n  Animal <|-- Dog : inherits\n  Animal *-- Collar\n");
        A11y a = A11yDescriber.describe(ir);
        assertEquals("Class diagram", a.title());
        assertFalse(a.desc().isBlank());
        assertTrue(a.desc().contains("3 classes"), a.desc());
        assertTrue(a.desc().contains("2 relations"), a.desc());
        assertTrue(a.desc().contains("Animal") && a.desc().contains("Dog") && a.desc().contains("Collar"),
            a.desc());
        // The inheritance reads with the correct direction (child inherits from parent).
        assertTrue(a.desc().contains("Dog inherits from Animal"), a.desc());
        assertTrue(a.desc().contains("Animal is composed of Collar"), a.desc());
    }

    @Test
    void oParenAggregationIsNotSpoofedByANameEndingInO() {
        // `o--` aggregation only matches when its `o` is at the start or preceded by whitespace, so a
        // class name glued to `--` does not spoof aggregation (there is no bare `--` operator → drop).
        ClassDiagram cd = parse("classDiagram\n  Zoo-- X\n  Animal o-- Owner\n");
        assertEquals(1, cd.relations().size());
        assertEquals(RelationKind.AGGREGATION, cd.relations().get(0).kind());
        assertEquals("Animal", cd.relations().get(0).left());
    }
}
