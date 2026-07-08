package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Sankey;
import com.sirentide.ir.SankeyFlow;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Sirentide's fifteenth diagram type: a `sankey` parses CSV-ish `source,target,value` rows into a
/// weighted-flow graph, and NEVER throws on malformed input (a row without three fields, a
/// non-numeric / zero / negative value, a missing endpoint, a self-flow, an empty body). The band /
/// node / column GEOMETRY is proven separately in {@link com.sirentide.layout.SankeyLayoutTest}.
class SankeyTest {

    private static Sankey parse(String dsl) {
        Diagram d = DslParser.parse(dsl);
        return assertInstanceOf(Sankey.class, d);
    }

    private static List<String> triples(Sankey s) {
        return s.flows().stream()
            .map(f -> f.source() + "," + f.target() + "," + (long) f.value()).toList();
    }

    @Test
    void validRowsParseIntoFlowsInDeclarationOrder() {
        Sankey s = parse("sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n"
            + "  Electricity,Homes,20\n  Electricity,Industry,20\n");
        assertEquals(List.of("Coal,Electricity,25", "Gas,Electricity,15",
            "Electricity,Homes,20", "Electricity,Industry,20"), triples(s),
            "four flows in declaration order");
    }

    @Test
    void fieldsAreTrimmed() {
        Sankey s = parse("sankey\n   Coal , Electricity , 25 \n");
        SankeyFlow f = s.flows().get(0);
        assertEquals("Coal", f.source(), "source is trimmed");
        assertEquals("Electricity", f.target(), "target is trimmed");
        assertEquals(25.0, f.value(), 1e-9);
    }

    @Test
    void sankeyBetaIsAnAlias() {
        Sankey s = parse("sankey-beta\n  A,B,10\n");
        assertEquals(1, s.flows().size(), "sankey-beta parses the same as sankey");
        assertEquals("A", s.flows().get(0).source());
    }

    @Test
    void aNonNumericValueRowIsDropped() {
        Sankey s = parse("sankey\n  Coal,Electricity,25\n  Gas,Homes,notanumber\n");
        assertEquals(1, s.flows().size(), "the non-numeric-value row is dropped, no throw");
        assertEquals("Coal", s.flows().get(0).source());
    }

    @Test
    void zeroAndNegativeValueRowsAreDropped() {
        Sankey s = parse("sankey\n  A,B,10\n  C,D,0\n  E,F,-5\n");
        assertEquals(List.of("A,B,10"), triples(s),
            "a zero and a negative value row are dropped (a band needs a positive width): " + triples(s));
    }

    @Test
    void aRowWithoutThreeFieldsIsDropped() {
        Sankey s = parse("sankey\n  A,B,10\n  Bad,Row\n  Too,Many,Fields,5\n");
        assertEquals(List.of("A,B,10"), triples(s),
            "a 2-field and a 4-field row are dropped: " + triples(s));
    }

    @Test
    void aMissingEndpointRowIsDropped() {
        Sankey s = parse("sankey\n  A,B,10\n  ,Homes,10\n  Coal,,5\n");
        assertEquals(List.of("A,B,10"), triples(s),
            "an empty source and an empty target row are dropped: " + triples(s));
    }

    @Test
    void aSelfFlowRowIsDropped() {
        Sankey s = parse("sankey\n  A,B,10\n  Loop,Loop,5\n");
        assertEquals(List.of("A,B,10"), triples(s),
            "a self-flow (A,A) is dropped (no column separation to draw a band): " + triples(s));
    }

    @Test
    void aNonFiniteValueRowIsDropped() {
        // "1e400" parses to Infinity in Java WITHOUT throwing — it must never reach the IR.
        Sankey s = parse("sankey\n  A,B,10\n  C,D,1e400\n");
        assertEquals(List.of("A,B,10"), triples(s), "a non-finite value row is dropped: " + triples(s));
    }

    @Test
    void anEmptyBodyRoundTripsAsASankeyWithNoFlows() {
        Sankey s = parse("sankey\n");
        assertTrue(s.flows().isEmpty(), "no body line → no flows (still a sankey, not Empty)");
        String svg = Sirentide.render("sankey\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertFalse(svg.contains("width=\"0\" height=\"0\""),
            "a real inert canvas, not the 0x0 shell: " + svg);
    }

    @Test
    void anAllMalformedBodyNeverThrowsAndBakesReal() {
        String dsl = "sankey\n  garbage line\n  A,B,nope\n  ,,,\n  X,X,5\n";
        String svg = Sirentide.render(dsl);
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed, no throw");
        // Every row is malformed → no flows → the inert canvas (never the 0x0 shell).
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "a real render, not the shell: " + svg);
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n  Electricity,Homes,40\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void a11yDescReflectsTheFlowsAndValues() {
        String svg = Sirentide.render("sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n"
            + "  Electricity,Homes,20\n  Electricity,Industry,20\n");
        int t = svg.indexOf("<desc>");
        int e = svg.indexOf("</desc>");
        assertTrue(t >= 0 && e > t, "a <desc> is baked: " + svg);
        String desc = svg.substring(t + 6, e);
        assertFalse(desc.isBlank(), "the desc is non-empty");
        assertTrue(desc.contains("Sankey") && desc.contains("5 nodes") && desc.contains("4 flows"),
            "the desc names the type + derived node count + flow count: " + desc);
        assertTrue(desc.contains("Coal to Electricity 25") && desc.contains("Gas to Electricity 15"),
            "the desc reads flows with their values: " + desc);
    }
}
