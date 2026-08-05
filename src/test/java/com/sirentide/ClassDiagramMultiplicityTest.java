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
import org.junit.jupiter.params.ParameterizedTest;

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

    // ---- Fixpoint's needs-fix, sirentide/845 -------------------------------------------------
    // He found the endpoint I asked him to find, by EXECUTION, and it was a REGRESSION against
    // the parent: the `class` DECLARATION grammar accepts any name, including `Foo"Bar"`, while
    // the REFERENCE grammar peeled any trailing quoted token. Two name productions, and I fenced
    // only one — the same one-arm-of-a-two-arm-condition shape this branch's own commit message
    // claims to eliminate, reproduced one level down.

    @Test
    void aDeclaredNameContainingQuotesIsNotEatenWhenReferenced() {
        ClassDiagram cd = parse("classDiagram\n"
            + "  class Foo\"Bar\" {\n    +int id\n  }\n"
            + "  Foo\"Bar\" --> Baz\n");
        assertEquals(List.of("Foo\"Bar\"", "Baz"), classNames(cd),
            "the declaration grammar accepts Foo\"Bar\" as an identity, so the reference grammar "
                + "must too — three boxes here means the peel ate a declared name");
        ClassRelation r = cd.relations().get(0);
        assertEquals("Foo\"Bar\"", r.left(), "the edge must attach to the DECLARED box");
        assertNull(r.leftMultiplicity(), "Bar is part of an identity, not a cardinality");
        ClassBox foo = cd.classes().stream().filter(c -> c.name().equals("Foo\"Bar\""))
            .findFirst().orElseThrow();
        assertEquals(List.of("+int id"), foo.attributes(),
            "members and the edge must land on the SAME box");
    }

    @Test
    void anEscapedQuoteShapeDoesNotHalfEatTheName() {
        // My anUnterminatedQuoteLeavesTheNameIntact... test is NAMED for this class but only
        // covers the no-trailing-quote shape; the escaped-quote shape walked straight past it.
        ClassDiagram cd = parse("classDiagram\n  User \"\\\"\" --> Order\n");
        ClassRelation r = cd.relations().get(0);
        assertTrue(r.left().startsWith("User"), "left identity half-eaten: " + r.left());
        assertTrue(r.leftMultiplicity() == null || !r.leftMultiplicity().isEmpty(),
            "an EMPTY multiplicity is never a real cardinality: " + r.leftMultiplicity());
    }

    @Test
    void realUmlPropertyStringsAreAcceptedNotRejectedByLength() {
        // The 16-char cap cut a line THROUGH a real UML idiom: `1..* {ordered, unique}` is 22
        // chars. And failing closed there is NOT neutral — it renders the ORIGINAL phantom-class
        // defect, so the true trade was "missed annotation -> PHANTOM CLASS", not "missed
        // annotation vs eaten identity". A shape gate admits it and rejects nothing real.
        ClassDiagram cd = parse("classDiagram\n  User \"1..* {ordered, unique}\" --> \"0..*\" Order\n");
        assertEquals(List.of("User", "Order"), classNames(cd),
            "a long but well-shaped cardinality must not mint a phantom class");
        assertEquals("1..* {ordered, unique}", cd.relations().get(0).leftMultiplicity());
    }

    @Test
    void aQuotedTokenThatIsNotCardinalityShapedIsLeftAsPartOfTheName() {
        // The shape gate is the real filter now: LENGTH alone could never tell a 5-char name
        // from a 5-char cardinality.
        ClassDiagram cd = parse("classDiagram\n  User \"Order\" --> Thing\n");
        ClassRelation r = cd.relations().get(0);
        assertNull(r.leftMultiplicity(),
            "\"Order\" is not cardinality-shaped and must not be peeled: " + r.leftMultiplicity());
    }

    // ---- Fixpoint's second needs-fix, sirentide/851 -------------------------------------------

    @ParameterizedTest(name = "variable bound {0} is a cardinality, not part of a name")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"n", "0..n", "1..n", "2..n"})
    void documentedVariableBoundCardinalitiesAreAccepted(String card) {
        // Mermaid documents n / 0..n / 1..n alongside 1 / 0..1 / 1..* / *. My shape gate admitted
        // digits, '*', '.' and space — so 'n', a LETTER, failed on the first character and the
        // token stayed in the name, minting the phantom class this plan exists to eliminate.
        // Same shape as the length cap it replaced, one notch narrower: the cap cut through
        // "1..* {ordered, unique}", the gate cut through "1..n".
        ClassDiagram cd = parse("classDiagram\n  User \"" + card + "\" --> Order\n");
        assertEquals(List.of("User", "Order"), classNames(cd),
            card + " must not mint a phantom class");
        assertEquals(card, cd.relations().get(0).leftMultiplicity());
    }

    @Test
    void aWhitespaceOnlyTokenIsNotACardinality() {
        // Direction B: a blank multiplicity re-creates the empty-ish value ClassRelation's own
        // javadoc promises never happens (null or a real value, never "").
        ClassDiagram cd = parse("classDiagram\n  User \"   \" --> Order\n");
        ClassRelation r = cd.relations().get(0);
        assertTrue(r.leftMultiplicity() == null || !r.leftMultiplicity().isBlank(),
            "blank multiplicity breaks the never-empty contract: [" + r.leftMultiplicity() + "]");
    }

    @Test
    void aShapeValidTokenInsideADECLAREDNameIsStillNotPeeled() {
        // THE RESIDUAL OF THE ORIGINAL CLASS, narrowed by the shape gate but not closed: "123"
        // IS cardinality-shaped, so the reference peeled it while the declaration kept it — three
        // boxes again, for the same declaration-vs-reference divergence, just through a narrower
        // door. Shape alone can never close this: the two productions must AGREE.
        ClassDiagram cd = parse("classDiagram\n"
            + "  class Foo \"123\" {\n    +int x\n  }\n"
            + "  Foo \"123\" --> Bar\n");
        assertEquals(List.of("Foo \"123\"", "Bar"), classNames(cd),
            "a DECLARED name must be referenceable verbatim, whatever its shape");
        assertEquals("Foo \"123\"", cd.relations().get(0).left());
        assertNull(cd.relations().get(0).leftMultiplicity());
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
