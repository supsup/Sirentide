package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.a11y.A11y;
import com.sirentide.a11y.A11yDescriber;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.ErAttribute;
import com.sirentide.ir.ErCardinality;
import com.sirentide.ir.ErDiagram;
import com.sirentide.ir.ErEntity;
import com.sirentide.ir.ErRelation;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// ER-diagram PARSE + A11Y + malformed-degrade pins (plan sirentide-er-diagram). The CROW-FOOT marker
/// geometry — the fidelity crux — is pinned in {@link com.sirentide.layout.ErDiagramLayoutTest}.
class ErDiagramTest {

    private static ErDiagram parse(String dsl) {
        Diagram ir = DslParser.parse(dsl);
        assertInstanceOf(ErDiagram.class, ir, "erDiagram parses to an ErDiagram: " + ir);
        return (ErDiagram) ir;
    }

    @Test
    void entityBlockParsesTypedAttributesWithKeyMarkers() {
        ErDiagram er = parse("erDiagram\n  CUSTOMER {\n    string name PK\n    string email\n"
            + "    int age\n  }\n");
        assertEquals(1, er.entities().size());
        ErEntity c = er.entities().get(0);
        assertEquals("CUSTOMER", c.name());
        assertTrue(c.hasAttributes());
        assertEquals(3, c.attributes().size());
        ErAttribute pk = c.attributes().get(0);
        assertEquals("string", pk.type());
        assertEquals("name", pk.name());
        assertEquals("PK", pk.key());
        ErAttribute email = c.attributes().get(1);
        assertEquals("string", email.type());
        assertEquals("email", email.name());
        assertNull(email.key(), "a keyless row carries a null key");
        assertEquals("int", c.attributes().get(2).type());
        assertEquals("age", c.attributes().get(2).name());
    }

    @Test
    void fkAndUkKeyMarkersAreRecognizedCaseInsensitively() {
        ErDiagram er = parse("erDiagram\n  ORDER {\n    int id PK\n    int customer_id fk\n"
            + "    string sku Uk\n  }\n");
        List<ErAttribute> a = er.entities().get(0).attributes();
        assertEquals("PK", a.get(0).key());
        assertEquals("FK", a.get(1).key(), "a lowercase fk normalizes to FK");
        assertEquals("UK", a.get(2).key(), "a mixed-case Uk normalizes to UK");
    }

    @Test
    void eachOfTheFourCardinalitiesParsesAtEachEnd() {
        // Left ends: |o ||  }o }| ; right ends: o| || o{ |{ — one relation per pairing.
        ErDiagram er = parse("erDiagram\n"
            + "  A |o--o| B\n"      // zero-or-one both ends
            + "  C ||--|| D\n"      // exactly-one both ends
            + "  E }o--o{ F\n"      // zero-or-many both ends
            + "  G }|--|{ H\n");    // one-or-many both ends
        List<ErRelation> r = er.relations();
        assertEquals(4, r.size());
        assertEquals(ErCardinality.ZERO_OR_ONE, r.get(0).leftCard());
        assertEquals(ErCardinality.ZERO_OR_ONE, r.get(0).rightCard());
        assertEquals(ErCardinality.EXACTLY_ONE, r.get(1).leftCard());
        assertEquals(ErCardinality.EXACTLY_ONE, r.get(1).rightCard());
        assertEquals(ErCardinality.ZERO_OR_MANY, r.get(2).leftCard());
        assertEquals(ErCardinality.ZERO_OR_MANY, r.get(2).rightCard());
        assertEquals(ErCardinality.ONE_OR_MANY, r.get(3).leftCard());
        assertEquals(ErCardinality.ONE_OR_MANY, r.get(3).rightCard());
    }

    @Test
    void asymmetricCardinalityKeepsEachEndDistinct() {
        // The canonical example: a customer (exactly one) places zero-or-many orders.
        ErDiagram er = parse("erDiagram\n  CUSTOMER ||--o{ ORDER : places\n");
        ErRelation r = er.relations().get(0);
        assertEquals("CUSTOMER", r.left());
        assertEquals("ORDER", r.right());
        assertEquals(ErCardinality.EXACTLY_ONE, r.leftCard(), "the CUSTOMER end is exactly-one");
        assertEquals(ErCardinality.ZERO_OR_MANY, r.rightCard(), "the ORDER end is zero-or-many");
        assertEquals("places", r.label());
    }

    @Test
    void identifyingVsNonIdentifyingFromSolidVsDashedOperator() {
        ErDiagram er = parse("erDiagram\n  A ||--o{ B\n  C ||..o{ D\n");
        assertTrue(er.relations().get(0).identifying(), "-- is identifying (solid)");
        assertFalse(er.relations().get(1).identifying(), ".. is non-identifying (dashed)");
    }

    @Test
    void entityWithNoBlockIsAnAttributelessNameBox() {
        ErDiagram er = parse("erDiagram\n  LONER\n");
        assertEquals(1, er.entities().size());
        assertFalse(er.entities().get(0).hasAttributes());
        assertEquals("LONER", er.entities().get(0).name());
    }

    @Test
    void relationToUnknownEntityAutoVivifiesInFirstSeenOrder() {
        // ADDRESS is never given a `{ }` block — it auto-vivifies as an attribute-less entity (mermaid
        // semantics) so the relationship renders, in first-seen order after the declared CUSTOMER.
        ErDiagram er = parse("erDiagram\n  CUSTOMER {\n    string name\n  }\n"
            + "  CUSTOMER }o--o| ADDRESS : has\n");
        assertEquals(List.of("CUSTOMER", "ADDRESS"),
            er.entities().stream().map(ErEntity::name).toList());
        assertFalse(er.entities().get(1).hasAttributes(), "auto-vivified ADDRESS has no attributes");
    }

    @Test
    void hyphenInEntityNameDoesNotSpoofAnOperator() {
        // LINE-ITEM carries a single `-`, not a `--`, and has no flanking cardinality chars, so the
        // operator scan does not mis-split it (the crow-foot operator needs cardinality chars around --).
        ErDiagram er = parse("erDiagram\n  ORDER ||--|{ LINE-ITEM : contains\n");
        assertEquals(1, er.relations().size());
        assertEquals("ORDER", er.relations().get(0).left());
        assertEquals("LINE-ITEM", er.relations().get(0).right());
        assertEquals(ErCardinality.ONE_OR_MANY, er.relations().get(0).rightCard());
    }

    @Test
    void unclosedBraceDegradesGracefullyNeverThrows() {
        // An unclosed `{` closes at EOF (keeps the rows gathered) — degrade-not-throw (DESIGN §6).
        String dsl = "erDiagram\n  CUSTOMER {\n    string name PK\n    string email\n";
        ErDiagram er = parse(dsl);
        assertEquals(1, er.entities().size());
        assertEquals(2, er.entities().get(0).attributes().size());
        assertTrue(Sirentide.render(dsl).startsWith("<svg"), "unclosed brace still bakes an SVG");
    }

    @Test
    void malformedRelationAndUnknownCardinalityAreDroppedNeverThrow() {
        // A missing right endpoint, a garbage line, and an INVALID cardinality (`|x`) all drop; only the
        // well-formed relation survives. Nothing throws.
        ErDiagram er = parse("erDiagram\n  A ||-- \n  total garbage line\n"
            + "  X |x--o{ Y\n  P ||--o{ Q\n");
        assertEquals(1, er.relations().size(), "only the well-formed relation survives");
        assertEquals("P", er.relations().get(0).left());
        assertTrue(Sirentide.render("erDiagram\n  A ||-- \n  X |x--o{ Y\n").startsWith("<svg"));
    }

    @Test
    void emptyBodyRoundTripsAsAnErDiagramNotEmpty() {
        ErDiagram er = parse("erDiagram\n");
        assertEquals(0, er.entities().size());
        assertEquals(0, er.relations().size());
    }

    @Test
    void singleTokenAttributeRowKeepsTheTokenAsItsName() {
        ErDiagram er = parse("erDiagram\n  E {\n    id\n  }\n");
        ErAttribute a = er.entities().get(0).attributes().get(0);
        assertEquals("", a.type(), "a single-token row has an empty type");
        assertEquals("id", a.name());
        assertNull(a.key());
    }

    @Test
    void a11yDescriptionReflectsEntitiesAndCardinalities() {
        Diagram ir = DslParser.parse("erDiagram\n  CUSTOMER {\n    string name PK\n    string email\n  }\n"
            + "  ORDER {\n    int id PK\n  }\n  CUSTOMER ||--o{ ORDER : places\n"
            + "  CUSTOMER }o--o| ADDRESS : has\n");
        A11y a = A11yDescriber.describe(ir);
        assertEquals("Entity-relationship diagram", a.title());
        assertFalse(a.desc().isBlank());
        assertTrue(a.desc().contains("3 entities"), a.desc());
        assertTrue(a.desc().contains("2 relationships"), a.desc());
        assertTrue(a.desc().contains("CUSTOMER") && a.desc().contains("ORDER")
            && a.desc().contains("ADDRESS"), a.desc());
        // BOTH ends' cardinalities are spoken (the marker's meaning).
        assertTrue(a.desc().contains("CUSTOMER (exactly one) to ORDER (zero or many)"), a.desc());
        assertTrue(a.desc().contains("labeled \"places\""), a.desc());
        assertTrue(a.desc().contains("identifying"), a.desc());
    }

    @Test
    void anEntityWithManyAttributesIsDisplayRowCapped() {
        // Robustness plan fe8c5bbc #2 (ER twin of the class cap): an entity's attribute-row COUNT was
        // unbounded, growing a canvas-blowing table. The DISPLAY cap shows at most MAX_DISPLAYED_ROWS
        // rows (+ a synthesized "… N more" row), bounding the table height. N=300 with the cap yields
        // ~30 rows; uncapped it would be ~300, so the bounded root-SVG height is the mutation proof.
        StringBuilder src = new StringBuilder("erDiagram\n  BIG {\n");
        for (int i = 0; i < 300; i++) {
            src.append("    int a").append(i).append("\n");
        }
        String svg = Sirentide.render(src.append("  }\n").toString());
        double h = Double.parseDouble(svg.replaceFirst("(?s).*?<svg[^>]*height=\"([0-9.]+)\".*", "$1"));
        assertTrue(h < 1200, "the many-attribute entity table is display-row-capped, height=" + h);
    }
}
