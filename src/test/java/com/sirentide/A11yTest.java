package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.a11y.A11y;
import com.sirentide.api.Sirentide;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.layout.LaidOut;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// Deterministic-accessibility guard for the baked SVG (plan sirentide-svg-accessibility). Pins
/// that every baked diagram carries a `role="img"` + a `<title>` + a reading-order `<desc>` built
/// PURELY from the IR: non-empty, reflecting the diagram's own labels, byte-stable across renders,
/// and XML-escaped so a label with markup metacharacters can never break the SVG.
class A11yTest {

    /// RECEIPT 3 + DELETE-MUTANT ANCHOR. The `<desc>` is non-empty and reflects the diagram — for a
    /// flowchart it must name a node's label text and describe the edges. If the emitter's `<desc>`
    /// emission is dropped or blanked (the delete-mutant), this test FAILS on the non-empty /
    /// contains-label assertions. This is the NAMED mutant-catcher for the desc mechanism.
    @Test
    void descIsNonEmptyAndReflectsTheDiagram() {
        String svg = Sirentide.render(
            "flowchart TD\n  A[Start] --> B[Ready]\n  B --> C[End]\n");
        String desc = between(svg, "<desc>", "</desc>");
        assertFalse(desc.isBlank(), "the <desc> must be non-empty: " + svg);
        assertTrue(desc.contains("Start") && desc.contains("Ready") && desc.contains("End"),
            "the <desc> reflects the flowchart's node labels: " + desc);
        assertTrue(desc.contains("leads to"),
            "the <desc> describes the edges in reading order: " + desc);
        String title = between(svg, "<title>", "</title>");
        assertEquals("Flowchart", title, "the <title> is the short type name");
    }

    /// RECEIPT 3 (determinism). The a11y text is a pure function of the diagram: two renders of the
    /// same DSL produce byte-identical SVG (title + desc included). No timestamps, no randomness.
    @Test
    void a11yIsDeterministicAcrossRenders() {
        String dsl = "sequence\n  Alice ->> Bob : Request\n  Bob -->> Alice : Reply\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl),
            "the same DSL renders byte-identically (deterministic a11y)");
    }

    /// RECEIPT 4 (XML escaping, render level). A label containing `<`, `&`, and `"` must be escaped
    /// in the `<desc>` so the SVG stays well-formed. Proven two ways: the raw output carries the
    /// entity forms (never a raw `<` inside the desc text), and the whole SVG parses as valid XML
    /// whose desc text round-trips back to the original metacharacters.
    @Test
    void descXmlEscapesMarkupMetacharacters() throws Exception {
        String svg = Sirentide.render("flowchart TD\n  A[a < b & \"c\"] --> B[End]\n");
        String desc = between(svg, "<desc>", "</desc>");
        assertTrue(desc.contains("&lt;"), "'<' is escaped in the desc: " + desc);
        assertTrue(desc.contains("&amp;"), "'&' is escaped in the desc: " + desc);
        assertTrue(desc.contains("&quot;"), "'\"' is escaped in the desc: " + desc);
        assertFalse(desc.contains("a < b"), "no RAW '<' survives in the desc text: " + desc);

        // The escaping is real: the whole document parses, and the desc's text content round-trips
        // back to the source metacharacters (the parser un-escapes the entities).
        Document doc = parse(svg);
        String descText = firstText(doc, "desc");
        assertTrue(descText.contains("a < b & \"c\""),
            "the escaped desc round-trips to the original label text: " + descText);
    }

    /// RECEIPT 4 (XML escaping, emitter level). A directly-crafted a11y payload with every markup
    /// metacharacter is escaped at the sink — the single containment guarantee for the one
    /// text-as-text seam in Sirentide's output. `&` is escaped without double-escaping the others.
    @Test
    void emitterEscapesAllMetacharactersInTitleAndDesc() throws Exception {
        A11y a11y = new A11y("t<&>\"", "d < e & f > g \"h\"");
        String svg = SvgEmitter.emit(LaidOut.of(10, 10), a11y);
        assertTrue(svg.contains("<title>t&lt;&amp;&gt;&quot;</title>"),
            "title fully escaped: " + svg);
        assertTrue(svg.contains("&lt;") && svg.contains("&amp;") && svg.contains("&gt;")
            && svg.contains("&quot;"), "every metacharacter escaped in desc: " + svg);
        assertFalse(svg.contains("&amp;amp;"), "no double-escaping of '&': " + svg);
        parse(svg);   // must be well-formed XML
    }

    /// The BLANK payload ({@link A11y#NONE}) emits NO role/title/desc — the inert/empty shell stays
    /// byte-identical to the pre-a11y output, so a bare `Empty` diagram never grows an a11y block.
    @Test
    void blankPayloadEmitsNoA11yBlock() {
        String bare = SvgEmitter.emit(LaidOut.of(0, 0), A11y.NONE);
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" "
            + "viewBox=\"0 0 0 0\"></svg>", bare, "blank a11y → unchanged inert shell");
        assertEquals(bare, Sirentide.render(""),
            "an empty diagram bakes the inert shell, no a11y block");
    }

    /// Pie and sequence get RICH per-type descriptions too (not just flowchart): a pie names its
    /// slices + values, a sequence names its actors + messages.
    @Test
    void pieAndSequenceGetRichDescriptions() {
        String pie = between(Sirentide.render("pie\n  \"Reviews\" : 40\n  \"Builds\" : 30\n"),
            "<desc>", "</desc>");
        assertTrue(pie.contains("Reviews 40") && pie.contains("Builds 30"),
            "pie desc names slices + values: " + pie);
        String seq = between(Sirentide.render(
            "sequence\n  Alice ->> Bob : Ping\n  Bob -->> Alice : Pong\n"), "<desc>", "</desc>");
        assertTrue(seq.contains("Alice") && seq.contains("Bob") && seq.contains("Ping")
            && seq.contains("(reply)"), "sequence desc names actors + messages + reply: " + seq);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static String between(String s, String open, String close) {
        int i = s.indexOf(open);
        if (i < 0) {
            return "";
        }
        int j = s.indexOf(close, i + open.length());
        return j < 0 ? "" : s.substring(i + open.length(), j);
    }

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private static String firstText(Document doc, String tag) {
        NodeList nl = doc.getElementsByTagName(tag);
        if (nl.getLength() == 0) {
            return "";
        }
        Node n = nl.item(0);
        return n.getTextContent();
    }
}
