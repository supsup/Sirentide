package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// The build-failing ALLOWLIST containment guard (replaces the old denylist spot-checks). Renders
/// a corpus of diagrams (one per type + edge cases), XML-parses each emitted SVG with a JDK parser
/// (zero new deps), and asserts every element ∈ the allowed set, every attribute ∈ that element's
/// allowed set, and every value obeys its constraint (finite-numeric / valid path-data / colour
/// grammar). Any violation fails the build. This is the LatteX-S8 mechanism the output contract
/// calls for: producer ⊆ contract.
class ContainmentTest {

    /// One DSL per diagram type, plus edge cases (empty, unknown head, non-finite value, oversized
    /// label). Every one must bake to something inside the allowlist.
    private static final List<String> CORPUS = List.of(
        // pie
        "pie\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 30\n",
        "pie\n  \"All\" : 5\n",                                    // single full-disc slice
        "pie\n  \"good\" : 10\n  no colon here\n  \"bad\" : nope\n",  // malformed rows
        // xychart
        "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n",
        "xychart\n",                                              // axes only
        "xychart\n  \"A\" : 5\n  \"B\" : -3\n",                    // negative magnitude
        // timeline
        "timeline\n  \"Founded\" : 2020\n  \"Series A\" : 2021\n  \"Launch\" : 2023\n",
        "timeline\n",                                             // axis only
        // gantt
        "gantt\n  \"Design\" : 0-3\n  \"Build\" : 3-8\n  \"Test\" : 7-10\n",
        "gantt\n  \"ok\" : 0-2\n  \"bad\" : notarange\n",          // malformed range
        // edge cases
        "",                                                       // empty diagram
        "anything",                                               // unknown → empty shell
        "flowchart TD; A-->B",                                    // unknown head
        "pie\n  \"Overflow\" : 1e400\n  \"Real\" : 10\n",          // non-finite value (must not leak)
        "pie\n  \"" + "x".repeat(4000) + "\" : 10\n");            // oversized label (capped)

    @Test
    void everyEmittedSvgStaysInsideTheAllowlist() throws Exception {
        for (String dsl : CORPUS) {
            String svg = Sirentide.render(dsl);
            Document doc = parse(svg);
            checkElement(doc.getDocumentElement(), dsl);
        }
    }

    /// Direct proof the non-finite leak is closed: `1e400` parses to Infinity in Java WITHOUT
    /// throwing; the emitter must never surface the literal string "Infinity" as a coordinate.
    @Test
    void nonFiniteValueNeverEmitsInfinityLiteral() {
        String svg = Sirentide.render("pie\n  \"Overflow\" : 1e400\n");
        assertTrue(!svg.contains("Infinity") && !svg.contains("NaN"),
            "no non-finite literal in output: " + svg);
    }

    private void checkElement(Element el, String dsl) {
        String tag = el.getTagName();
        if (!SirentideContract.ALLOWED_ELEMENTS.contains(tag)) {
            fail("element <" + tag + "> is outside the allowlist (dsl: " + preview(dsl) + ")");
        }
        var allowedAttrs = SirentideContract.ALLOWED_ATTRS.get(tag);
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            String value = a.getNodeValue();
            if (!allowedAttrs.contains(name)) {
                fail("attribute " + name + "=\"" + value + "\" on <" + tag
                    + "> is outside the allowlist (dsl: " + preview(dsl) + ")");
            }
            if (!SirentideContract.attributeValueValid(name, value)) {
                fail("value " + name + "=\"" + value + "\" on <" + tag
                    + "> violates its constraint (dsl: " + preview(dsl) + ")");
            }
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                checkElement((Element) k, dsl);
            }
        }
    }

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);   // xmlns surfaces as a regular attribute we allow explicitly
        // Hardening: no external entity resolution (defense-in-depth; our own output anyway).
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private static String preview(String dsl) {
        String oneLine = dsl.replace("\n", "\\n");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}
