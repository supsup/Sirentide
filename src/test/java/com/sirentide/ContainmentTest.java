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
        "pie legend\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 30\n",   // left colour key
        "pie key\n  \"A really long legend label that overruns\" : 5\n  \"B\" : 5\n", // alias + ellipsize
        "pie\n  \"All\" : 5\n",                                    // single full-disc slice
        "pie\n  \"good\" : 10\n  no colon here\n  \"bad\" : nope\n",  // malformed rows
        // xychart
        "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n",
        "xychart\n",                                              // axes only
        "xychart\n  \"A\" : 5\n  \"B\" : -3\n",                    // negative magnitude
        // multi-series LINE with a legend + NEGATIVE values + a missing (gap) point — exercises the
        // disc/segment path, the left colour key, the signed domain, and the label clamp in-set.
        "xychart line legend\n  series: Revenue, Cost\n  \"Q1\" : 5 -3\n  \"Q2\" : 8\n  \"Q3\" : -2 6\n",
        "xychart scatter\n  \"A\" : 5 8\n  \"B\" : -3 2\n",       // scatter, multi-series, negative
        // timeline
        "timeline\n  \"Founded\" : 2020\n  \"Series A\" : 2021\n  \"Launch\" : 2023\n",
        "timeline\n",                                             // axis only
        // gantt
        "gantt\n  \"Design\" : 0-3\n  \"Build\" : 3-8\n  \"Test\" : 7-10\n",
        "gantt\n  \"ok\" : 0-2\n  \"bad\" : notarange\n",          // malformed range
        // flowchart (5th type): a chain + a diamond + a CYCLE + a lone node — exercises the new
        // <path> arrowhead shape, the layered layout, and cycle-termination through the allowlist.
        "flowchart TD\n  A[Start] --> B[Process]\n  B --> C[End]\n"
            + "  A --> D[Side]\n  D --> C\n  C --> A\n  E[Lone]\n",
        // flowchart edge labels (M1.2) incl. a LABELED BACK-edge (its label rides the lane; the
        // canvas widens for it — author text through the glyph-path pipeline, never <text>).
        "flowchart\n  A{Ship?} -->|yes| B[Deploy]\n  A -->|no| C[Fix]\n  C -->|retry| A\n",   // + a DIAMOND decision node (M1.3)
        // LR geometry (M1.4): columns flow left→right, back-edges lane BELOW the content, labeled —
        // exercises the diamond, forward + back edge labels, and the cycle through the LR path.
        "flowchart LR\n  A{Ship?} -->|yes| B[Deploy]\n  A -->|no| C[Fix]\n  C -->|retry| A\n  D[Lone]\n",
        // operator-scan hardening: a bracket-EMBEDDED arrow (label "a-->b", NOT an edge split) plus a
        // CHAINED multi-hop line (A→B→C) — bake-through safety that the parse-fix output stays in-set.
        "flowchart TD\n  A[a-->b] --> C\n  A --> B --> C\n",
        // sequence (6th type): a call `->>` + a reply `-->>` + a SELF-message + an UNLABELED message
        // — exercises the filled-triangle arrowhead, the open-V (line-pair) reply head, the self-hook,
        // and the label-clamp, all through the allowlist.
        "sequence\n  Alice ->> Bob : Request a really long token label that must clamp in-canvas\n"
            + "  Bob -->> Alice : Token\n  Alice ->> Alice : Validate locally\n  Bob ->> Carol\n",
        // state diagram (7th type): `[*]` start + end pseudostates (disc + bullseye Wedge paths), a
        // labeled transition, and a CYCLE (Idle↔Running) — exercises the reused flowchart engine's
        // cycle handling plus the new disc geometry, all through the allowlist.
        "state\n  [*] --> Idle\n  Idle --> Running : start\n  Running --> Idle : stop\n  Running --> [*]\n",
        // edge cases
        "",                                                       // empty diagram
        "anything",                                               // unknown → empty shell
        "flowchart TD; A-->B",                                    // unknown head
        "pie\n  \"Overflow\" : 1e400\n  \"Real\" : 10\n",          // non-finite value (must not leak)
        // AUTHOR COLOUR flows (T3, deep-review sirentide/14): the color merges added the highest-risk
        // author-string→attribute paths but touched zero containment corpus. Exercise them all so the
        // producer⊆contract guard covers author-supplied colour, not just the built-in palette.
        "pie\n  \"A\" : 60 #22c55e\n  \"B\" : 40 #f80\n",          // per-item 6-digit + 3-digit hex
        "pie\n  \"A\" : 60 currentColor\n  \"B\" : 40 none\n",     // per-item currentColor/none (H1 — palette fallback)
        "pie legend color=#334155\n  \"A\" : 60 #123456\n  \"B\" : 40\n", // color= header + per-item + legend
        "xychart color=#ffffff\n  \"Mon\" : 5 #abcdef\n  \"Tue\" : -3\n", // header + per-bar + negative
        // NODE COLOUR flows: a per-node trailing #hex + a header nodecolor= default + contrast-derived
        // node labels (author-string→fill/label paths) across flowchart, sequence heads, and state.
        "flowchart nodecolor=#334155\n  A[Start] #22c55e --> B{Q?}\n  B -->|yes| C[End]\n",
        "sequence nodecolor=#1a2233\n  Alice ->> Bob : hi\n  Bob -->> Alice : ok\n",
        "state nodecolor=#334155\n  [*] --> Idle #22c55e\n  Idle --> Running : go\n  Running --> [*]\n",
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
