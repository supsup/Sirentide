package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.a11y.A11y;
import com.sirentide.a11y.A11yDescriber;
import com.sirentide.ir.ClassBox;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ClassRelation;
import com.sirentide.ir.Diagram;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// UML multiplicity annotations on relation endpoints (plan 24d6b22f).
///
/// THE DEFECT THIS PINS, reproduced from the shipped 0.6.0 jar before the fix existed:
/// `User "1" --> "*" Order` had its multiplicity tokens absorbed INTO the endpoint names, so the
/// parser minted classes literally named `User "1"` and `"*" Order`. The corruption reached the
/// ACCESSIBLE DESCRIPTION too ("Classes: User &quot;1&quot;, &quot;*&quot; Order"), so it shipped
/// to assistive tech as well as to the eye — which is why the a11y leg below is a real assertion
/// and not decoration.
///
/// The identity leg ({@link #declaredClassAndAnnotatedReferenceAreOneBoxNotTwo}) is the one that
/// matters most: when the class is ALSO declared properly — the normal case — the declared box and
/// the annotated reference were DIFFERENT boxes, so members attached to one and edges to the other
/// and a realistic model rendered as a disconnected mess.
class ClassDiagramMultiplicityTest {

    private static ClassDiagram parse(String dsl) {
        Diagram ir = DslParser.parse(dsl);
        assertInstanceOf(ClassDiagram.class, ir, "classDiagram parses to a ClassDiagram: " + ir);
        return (ClassDiagram) ir;
    }

    private static List<String> classNames(ClassDiagram cd) {
        return cd.classes().stream().map(ClassBox::name).toList();
    }

    @Test
    void multiplicityIsStrippedFromBothEndpointNames() {
        ClassDiagram cd = parse("classDiagram\n  User \"1\" --> \"*\" Order : places\n");
        assertEquals(List.of("User", "Order"), classNames(cd),
            "multiplicity tokens must not become part of the class identity");
        assertEquals(1, cd.relations().size());
        ClassRelation r = cd.relations().get(0);
        assertEquals("User", r.left());
        assertEquals("Order", r.right());
        assertEquals("places", r.label(), "the : label is unaffected by multiplicity peeling");
    }

    @Test
    void declaredClassAndAnnotatedReferenceAreOneBoxNotTwo() {
        // The leg that matters: declare the classes, THEN reference them with multiplicities.
        ClassDiagram cd = parse("classDiagram\n"
            + "  class User {\n    +String name\n  }\n"
            + "  class Order {\n    +int id\n  }\n"
            + "  User \"1\" --> \"*\" Order : places\n");
        assertEquals(List.of("User", "Order"), classNames(cd),
            "an annotated reference must resolve to the DECLARED box, never mint a phantom sibling");
        // The declared members must still be on the box the relation points at.
        ClassBox user = cd.classes().stream().filter(c -> c.name().equals("User")).findFirst().orElseThrow();
        assertEquals(List.of("+String name"), user.attributes(),
            "members and edges must land on the SAME box");
    }

    @Test
    void multiplicityIsCarriedOnTheRelationNotDiscarded() {
        ClassDiagram cd = parse("classDiagram\n  User \"1\" --> \"0..*\" Order\n");
        ClassRelation r = cd.relations().get(0);
        assertEquals("1", r.leftMultiplicity());
        assertEquals("0..*", r.rightMultiplicity());
    }

    @Test
    void multiplicityReachesTheAccessibleDescription() {
        // The pre-fix desc read: Classes: User "1", "*" Order.  It must now name the clean
        // identities AND still carry the cardinality, since dropping it silently would be a
        // milder version of the same information defect.
        ClassDiagram cd = parse("classDiagram\n  User \"1\" --> \"*\" Order : places\n");
        A11y a = A11yDescriber.describe(cd);
        assertTrue(a.desc().contains("User") && a.desc().contains("Order"), a.desc());
        assertTrue(a.desc().contains("\"1\"") || a.desc().contains("(1)") || a.desc().contains(" 1 "),
            "the left cardinality survives into the description: " + a.desc());
        assertTrue(a.desc().contains("*"), "the right cardinality survives: " + a.desc());
        assertTrue(!a.desc().contains("User \"1\""),
            "the corrupted composite name must be gone: " + a.desc());
    }

    // ---- negative controls: the peeling must not fire where there is no multiplicity ----------

    @Test
    void relationsWithoutMultiplicityAreUnchanged() {
        ClassDiagram cd = parse("classDiagram\n  A <|-- B\n  A *-- C\n  A --> D : uses\n");
        assertEquals(List.of("A", "B", "C", "D"), classNames(cd));
        for (ClassRelation r : cd.relations()) {
            assertNull(r.leftMultiplicity(), "no annotation → no multiplicity, not an empty string");
            assertNull(r.rightMultiplicity());
        }
        assertEquals("uses", cd.relations().get(2).label());
    }

    @Test
    void anUnterminatedQuoteLeavesTheNameIntactRatherThanHalfEatingIt() {
        // A malformed annotation must not silently consume part of the identity — the failure a
        // naive "strip from the first quote" implementation would introduce.
        ClassDiagram cd = parse("classDiagram\n  User \"1 --> Order\n");
        assertTrue(classNames(cd).contains("Order"),
            "the right endpoint survives a malformed left annotation: " + classNames(cd));
        assertTrue(classNames(cd).stream().anyMatch(n -> n.startsWith("User")),
            "the left identity is not half-eaten: " + classNames(cd));
    }

    @Test
    void aQuotedNameWithNoOperatorSideEffectsIsNotTreatedAsMultiplicity() {
        // Multiplicity is positional: adjacent to the operator. A quoted token that is the WHOLE
        // endpoint is a (degenerate) name, not a cardinality to peel into nothing.
        ClassDiagram cd = parse("classDiagram\n  \"1\" --> Order\n");
        assertEquals(1, cd.relations().size());
        ClassRelation r = cd.relations().get(0);
        assertTrue(!r.left().isEmpty(), "peeling must never empty an endpoint: left=" + r.left());
    }
}
