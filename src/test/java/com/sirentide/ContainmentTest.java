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
        // flowchart SUBGRAPH clusters: an outer `subgraph … end`, a NESTED subgraph inside it, an
        // EMPTY subgraph (no members → no frame), and a STRAY `end` (malformed→inert) — exercises the
        // new cluster frame border lines, the title-band rect, the title glyph run, and the nesting
        // inset through the allowlist (rect/line already in-set; proves the frame obeys the grammar).
        "flowchart TD\n  A[Start] --> B[Work]\n  subgraph outer [Outer]\n    B --> C[Compile]\n"
            + "    subgraph inner [Inner]\n      C --> D[Test]\n    end\n  end\n"
            + "  subgraph empty [Nothing declared here]\n  end\n  end\n  D --> E[Ship]\n",
        // LR subgraph cluster: a subgraph in the left→right geometry — the frame must contain its
        // members and stay in-set after the LR coordinate pass + any grow-to-fit shift.
        "flowchart LR\n  A --> B\n  subgraph grp [Group]\n    B --> C\n    C --> D\n  end\n  D --> E\n",
        // sequence (6th type): a call `->>` + a reply `-->>` + a SELF-message + an UNLABELED message
        // — exercises the filled-triangle arrowhead, the open-V (line-pair) reply head, the self-hook,
        // and the label-clamp, all through the allowlist.
        "sequence\n  Alice ->> Bob : Request a really long token label that must clamp in-canvas\n"
            + "  Bob -->> Alice : Token\n  Alice ->> Alice : Validate locally\n  Bob ->> Carol\n",
        // sequence arrow ALIASES (fix 1): bare `->` (call) + `-->` (reply) + an arrow INSIDE the label
        // (fix 4, operator-scan: post-colon `->` is inert) — all through the allowlist.
        "sequence\n  A -> B : call via alias\n  B --> A : reply via alias\n"
            + "  A ->> B : retry -> escalate\n",
        // sequence ZERO-ACTOR degrade (fix 2): a NON-EMPTY body where every line is malformed renders a
        // visible `sequence: no messages parsed` glyph-run canvas — must stay in-set, not the inert shell.
        "sequence\n  garbage line\n  more garbage\n",
        // sequence ACTIVATION bars (M2): a call `->>` opens an activation on its callee, a reply `-->>`
        // closes it, a NESTED concurrent call stacks an offset bar, and a trailing UNBALANCED call
        // (no reply) closes at the diagram bottom — exercises the new ACT_FILL <rect>s through the
        // allowlist (rect is already in-set; this proves the activation rects obey the value grammar).
        "sequence\n  A ->> B : c1\n  A ->> B : c2\n  B -->> A : r2\n  B -->> A : r1\n  A ->> B : dangling\n",
        // sequence alt/loop/par FRAME blocks (M2): an `alt`+`else` with a NESTED `loop`, a `par`+`and`
        // across a third actor, a self-message inside a loop (frame must contain the hook), and a STRAY
        // `end`/`else` + an UNCLOSED block (malformed→inert) — exercises the new frame border lines,
        // the label-tab rect, the dashed divider segments, and the nesting inset through the allowlist.
        "sequence\n  Alice ->> Bob : hello\n  alt is available\n    Bob -->> Alice : yes\n"
            + "    loop every retry\n      Alice ->> Alice : think\n    end\n"
            + "  else is busy\n    Bob -->> Alice : later\n  end\n"
            + "  par to Bob\n    Alice ->> Bob : a\n  and to Carol\n    Alice ->> Carol : b\n  end\n"
            + "  else stray divider\n  end\n  loop unclosed at eof\n    Alice ->> Bob : trailing\n",
        // state diagram (7th type): `[*]` start + end pseudostates (disc + bullseye Wedge paths), a
        // labeled transition, and a CYCLE (Idle↔Running) — exercises the reused flowchart engine's
        // cycle handling plus the new disc geometry, all through the allowlist.
        "state\n  [*] --> Idle\n  Idle --> Running : start\n  Running --> Idle : stop\n  Running --> [*]\n",
        // quadrant chart (8th type): both axis ends, all four quadrant labels, points one-per-quadrant
        // plus an OUT-OF-RANGE point (clamped into the unit square) and a LONG label that ellipsizes —
        // exercises the tints (rect), border+crossing axes (line), quadrant/point/axis glyph runs, the
        // palette discs (wedge), and the clamp/ellipsize containment, all through the allowlist.
        "quadrant\n  x-axis \"Low Reach\" --> \"High Reach\"\n  y-axis \"Low Impact\" --> \"High Impact\"\n"
            + "  quadrant-1 \"A really long quadrant label that must ellipsize inside its cell\"\n"
            + "  quadrant-2 \"Quick win\"\n  quadrant-3 \"Deprioritize\"\n  quadrant-4 \"Fill-in\"\n"
            + "  \"Feature A\" : [0.3, 0.6]\n  \"Out of range\" : [1.8, -0.5]\n  \"Malformed\" : nope\n",
        // bare quadrant: no axis/quadrant labels, no points → a valid EMPTY 2×2 grid (still in-set,
        // never the inert shell — the type round-trips even with an empty body).
        "quadrant\n",
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

    /// The a11y widen (plan sirentide-svg-accessibility): the emitter now bakes a root `role="img"`
    /// + `<title>` + `<desc>`. This pins that (a) a non-empty diagram ACTUALLY emits title/desc with
    /// `role="img"`, and (b) `title`/`desc` are the ONLY elements the corpus adds beyond the
    /// pre-a11y geometry alphabet — no other element crept in on the back of the widen. The
    /// per-element allowlist walk in {@link #everyEmittedSvgStaysInsideTheAllowlist} already proves
    /// every attribute stays bounded (role == "img", title/desc attribute-free).
    @Test
    void a11yTitleDescAndRoleAreEmittedAndAreTheOnlyNewElements() throws Exception {
        String flow = Sirentide.render(
            "flowchart TD\n  A[Start] --> B[Process]\n  B --> C[End]\n");
        assertTrue(flow.contains("role=\"img\""), "root carries role=img: " + flow);
        assertTrue(flow.contains("<title>") && flow.contains("</title>"), "a <title> is baked: " + flow);
        assertTrue(flow.contains("<desc>") && flow.contains("</desc>"), "a <desc> is baked: " + flow);

        // Across the whole corpus, the set of emitted element tags must be a subset of the
        // GEOMETRY alphabet plus exactly {title, desc}. If a stray element slipped in, this catches
        // it; if title/desc silently stopped emitting, the presence checks above catch that.
        java.util.Set<String> geometry = java.util.Set.of("svg", "path", "rect", "line", "g");
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (String dsl : CORPUS) {
            collectTags(parse(Sirentide.render(dsl)).getDocumentElement(), seen);
        }
        for (String tag : seen) {
            assertTrue(geometry.contains(tag) || tag.equals("title") || tag.equals("desc"),
                "unexpected element <" + tag + "> appeared — the a11y widen must add ONLY title/desc");
        }
        assertTrue(seen.contains("title") && seen.contains("desc"),
            "title AND desc must actually appear across the corpus, saw: " + seen);
    }

    /// The allowlist guard stays NON-VACUOUS after the widen: a hand-crafted SVG carrying a FOREIGN
    /// element (`<foreignObject>`) must still fail the containment walk. Proves the a11y widen did
    /// not accidentally open the allowlist to arbitrary elements.
    @Test
    void foreignElementStillRejectedAfterTheWiden() throws Exception {
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\" "
            + "viewBox=\"0 0 1 1\" role=\"img\"><title>x</title>"
            + "<foreignObject width=\"1\" height=\"1\"></foreignObject></svg>";
        Document doc = parse(hostile);
        try {
            checkElement(doc.getDocumentElement(), "hostile-foreignObject");
            fail("a <foreignObject> must fail the allowlist — the guard is not vacuous");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("foreignObject"),
                "the failure names the foreign element: " + expected.getMessage());
        }
    }

    private void collectTags(Element el, java.util.Set<String> into) {
        into.add(el.getTagName());
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                collectTags((Element) k, into);
            }
        }
    }

    /// The math-in-labels widening (RFC sirentide/39): a flowchart node whose `$…$` label renders
    /// through a fake fragment must bake a `<g transform>` that STILL stays inside the allowlist
    /// (g + numeric transform, path + d/fill). Proves the g/transform contract widening is
    /// producer ⊆ contract, same guard as every other element.
    @Test
    void mathFragmentLabelStaysInsideTheAllowlist() throws Exception {
        com.sirentide.api.MathFragmentRenderer fake = (latex, size) ->
            java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<g transform=\"scale(0.5 0.5)\"><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>", 40, 12, 4));
        String svg = Sirentide.render("flowchart TD\n  A[Energy $E=mc^2$] --> B[Done]\n", fake);
        assertTrue(svg.contains("<g transform="), "a MathBox was actually emitted: " + svg);
        Document doc = parse(svg);
        checkElement(doc.getDocumentElement(), "math-in-labels");
    }

    /// Conf F1 pin (RFC sirentide/49): the RECONCILED contract. FragmentGuard already permits
    /// `fill` on an inner `<g>` and the SirentideContract doc-comment intends it, but
    /// ALLOWED_ATTRS.g == {transform} — so a real (S2 LatteX) renderer emitting
    /// `<g fill="currentColor">…</g>` passes the guard yet violates the containment allowlist.
    /// This pins the reconciled rule: an inner `<g fill=…>` fragment STAYS inside the allowlist.
    /// RED until F1 adds `fill` to ALLOWED_ATTRS.g (drift-guard-as-a-test: the failing test IS
    /// the contract; F1's one-line change makes it green).
    @Test
    void mathFragmentInnerGWithFillStaysInsideTheAllowlist() throws Exception {
        com.sirentide.api.MathFragmentRenderer fake = (latex, size) ->
            java.util.Optional.of(new com.sirentide.api.MathFragment(
                "<g fill=\"currentColor\" transform=\"scale(0.5 0.5)\">"
                    + "<path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>", 40, 12, 4));
        String svg = Sirentide.render("flowchart TD\n  A[Energy $E=mc^2$] --> B[Done]\n", fake);
        assertTrue(svg.contains("<g fill="), "the inner g-with-fill fragment was emitted: " + svg);
        Document doc = parse(svg);
        checkElement(doc.getDocumentElement(), "math-in-labels-inner-g-fill");
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
