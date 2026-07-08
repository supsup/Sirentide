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
        // flowchart EDGE VARIANTS (plan flowchart-edge-types): an open link `---`, a dotted arrow
        // `-.->`, a dotted open `-.-`, a thick arrow `==>`, a thick open `===`, plus a LABELED dotted +
        // a LABELED thick — so the dashed short-segment lines, the wider-stroke lines, the omitted
        // arrowheads, and the variant-edge labels all stay inside the svg/path/rect/line allowlist.
        "flowchart TD\n  A[Start] --- B[Link]\n  B -.-> C[Retry]\n  C -.- D[Idle]\n"
            + "  D ==> E[Ship]\n  E === F[Done]\n  A -.->|maybe| D\n  C ==>|force| F\n",
        // operator-scan hardening: a bracket-EMBEDDED arrow (label "a-->b", NOT an edge split) plus a
        // CHAINED multi-hop line (A→B→C) — bake-through safety that the parse-fix output stays in-set.
        "flowchart TD\n  A[a-->b] --> C\n  A --> B --> C\n",
        // flowchart NODE SHAPES: every shape delimiter in one chain — a rounded `(…)`, a stadium
        // `([…])`, a circle `((…))`, a hexagon `{{…}}`, a cylinder/database `[(…)]` (arc silhouette +
        // line-segment lid rim), a subroutine `[[…]]` (rect + inner bars), plus a plain rect + a diamond
        // — so the new arc/curve/polygon paths + the cylinder rim lines stay inside svg/path/rect/line.
        "flowchart TD\n  A[Rect] --> B(Rounded)\n  B --> C([Stadium])\n  C --> D((Circle))\n"
            + "  D --> E{{Hexagon}}\n  E --> F[(Database)]\n  F --> G[[Subroutine]]\n  G --> H{Decision?}\n",
        // malformed shape delimiters (unclosed / mismatched pairs) must degrade to inert (drop the
        // whole line, never throw): `([x` unclosed, `((y)` one-short, `{{z}` one-short, `[(w]` mismatched.
        "flowchart TD\n  A([Good]) --> B[Fine]\n  C([unclosed\n  D((one)\n  E{{one}\n  F[(bad]\n",
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
        // sequence NOTES + CREATE/DESTROY (M2 enrichment): a `note right of` single actor, a `note
        // over A,B` span, a `create participant` (mid-diagram lifeline start), a `destroy` (lifeline
        // end + X mark), a message FROM a destroyed actor (draw-anyway degrade), plus MALFORMED cases —
        // a note with an UNKNOWN actor, a note with NO text, and a `destroy` of an unknown actor (all
        // inert) — exercises the note-box rect/line/glyph + the destroy-X lines through the allowlist.
        "sequence\n  Alice ->> Bob : hi\n  note right of Bob : a note that is fairly long and must clamp\n"
            + "  note over Alice,Bob : shared\n  note left of Alice : side\n  note over Ghost : unknown\n"
            + "  note over Alice :\n  create participant Carol\n  Bob ->> Carol : spawn\n"
            + "  destroy Carol\n  destroy Nobody\n  Carol -->> Bob : done\n",
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
        // class diagram (9th type): a POPULATED three-compartment class + all FIVE relationship markers
        // (hollow-triangle inheritance, filled-diamond composition, hollow-diamond aggregation, open-arrow
        // association, dashed-open-arrow dependency), an auto-vivified empty class, an UNCLOSED block, and
        // a MALFORMED relation (empty endpoint) — exercises the marker paths/lines + the dashed-segment
        // edge + the compartment bands/dividers through the allowlist (all in svg/path/rect/line/glyph).
        "classDiagram\n  class Animal {\n    +String name\n    +eat() void\n  }\n  class Dog {\n"
            + "    +bark() void\n  }\n  Animal <|-- Dog : inherits\n  Animal *-- Collar : composition\n"
            + "  Animal o-- Owner : aggregation\n  Dog --> Bone : association\n  Dog ..> Vet : dependency\n"
            + "  Animal *-- \n  garbage line here\n",
        // bare class diagram: no classes → a valid empty canvas (round-trips, never the inert shell).
        "classDiagram\n",
        // class diagram unclosed brace: degrade-not-throw (closes at EOF) — must stay in-set.
        "classDiagram\n  class A {\n    +x\n    +go()\n",
        // ER diagram (10th type): two populated entity tables (typed rows + PK/FK/UK keys), ALL FOUR
        // crow-foot cardinalities across the ends (zero-or-one, exactly-one, zero-or-many, one-or-many),
        // an identifying `--` AND a non-identifying `..` (dashed) relation, an auto-vivified entity, an
        // UNCLOSED block, and a MALFORMED relation (bad cardinality) — exercises the crow-foot / bar /
        // hollow-ring marker lines + the dashed-segment edge + the header/rows table bands through the
        // allowlist (all in svg/path/rect/line/glyph — the ring is a many-segment LINE polygon).
        "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  ORDER ||--|{ LINE-ITEM : contains\n"
            + "  CUSTOMER }o--o| ADDRESS : has\n  ORDER ||..|| INVOICE : bills\n"
            + "  A |o--o| B\n  X |bad--o{ Y\n  Z ||-- \n  garbage row here\n"
            + "  CUSTOMER {\n    string name PK\n    string email\n    int customer_id FK\n  }\n"
            + "  ORDER {\n    int id PK\n    date created\n    string sku UK\n",   // unclosed block (EOF)
        // bare ER diagram: no entities → a valid empty canvas (round-trips, never the inert shell).
        "erDiagram\n",
        // mathblock (11th type): a full-size display-math block. The CORPUS renders with the null
        // renderer, so this exercises the DEGRADE — the raw LaTeX source baked as plain-text glyph
        // paths — which must stay in-set (the real typeset bake's containment is proven in
        // MathBlockRealRenderTest). Includes a multi-line body (joined) with braces + a sum.
        "mathblock\n  \\sum_{i=1}^{n} i\n  = \\frac{n(n+1)}{2}\n",
        "mathblock\n",   // empty body → inert empty canvas (round-trips, never a throw)
        // gitGraph (12th type): a main lane with an id-labeled commit, a `develop` branch + commits, a
        // merge back into main, plus MALFORMED cases — a commit BEFORE any branch (implicit main), a
        // checkout of an UNKNOWN branch (inert), a DUPLICATE branch (inert), and a SELF-merge (inert) —
        // exercises the commit discs (wedge), the lane spines + elbow branch/merge connectors (line),
        // and the commit-id/branch-name glyph labels through the allowlist (all in svg/path/line/g).
        "gitGraph\n  commit\n  commit id: \"fix\"\n  checkout ghost\n  branch develop\n"
            + "  branch develop\n  checkout develop\n  commit\n  commit id: \"wip\"\n  checkout main\n"
            + "  merge develop\n  merge main\n  commit\n",
        "gitGraph\n",   // empty body → minimal empty canvas (round-trips, never the inert shell)
        // journey (13th type): a title, two sections, several scored tasks, a MULTI-ACTOR task, plus
        // MALFORMED cases — an OUT-OF-RANGE score (clamped 1..5), a NON-NUMERIC score (dropped), a task
        // BEFORE any section (dropped), and an actorless task — exercises the point discs (wedge), the
        // connecting satisfaction line + axes + section brackets (line), and the title/tick/task/actor
        // glyph labels through the allowlist (all in svg/path/line/g).
        "journey\n  title My working day\n  Orphan: 4: Nobody\n  section Go to work\n"
            + "    Make tea: 5: Me\n    Commute: 3: Me, Cat\n    Arrive: 9: Me\n    Bad: nope: Me\n"
            + "    Solo: 2\n  section Do work\n    Code: 5: Me\n    Meetings: 2: Me, Boss\n",
        "journey\n",   // empty body → minimal inert canvas (round-trips, never the inert shell)
        // mindmap (14th type): a root + 3 branches with leaf children, plus MALFORMED cases — an
        // OVER-INDENTED first child (attaches to the root), a line at ≤ the root indent (a second
        // top-level line → a root child), INCONSISTENT indentation (snaps to the nearest shallower
        // ancestor), and a tab-indented line (fixed-width columns) — exercises the node boxes (rect),
        // the elbow parent→child connectors (line), and the node-label glyph runs through the
        // allowlist (all in svg/rect/line/g).
        "mindmap\n  root Root idea\n        Deep first child\n    Origins\n      Long history\n"
            + "      Popular\n    Research\n       Odd indent leaf\n\tTab branch\n    Tools\n"
            + "      Mermaid\n  Second top level\n",
        "mindmap\n",              // empty body → minimal inert canvas (round-trips, never the shell)
        "mindmap\n  Solo root\n", // a single root-only mindmap (just one box, no edges)
        // sankey (15th type): a 3-column weighted-flow graph (Coal/Gas → Electricity → Homes/Industry —
        // a middle node with multiple in AND out flows), plus MALFORMED cases — a row without exactly
        // three comma-fields, a NON-NUMERIC value, a ZERO and a NEGATIVE value, a missing endpoint, and
        // a SELF-flow (all dropped) — exercises the flow-band quadrilaterals (path), the node bars
        // (rect), and the beside-bar node-label glyph runs through the allowlist (all in svg/path/rect/g).
        "sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n  Electricity,Homes,20\n"
            + "  Electricity,Industry,20\n  Bad,Row\n  Gas,Homes,notanumber\n  A,B,0\n  C,D,-5\n"
            + "  ,Homes,10\n  Loop,Loop,5\n",
        "sankey-beta\n  A,B,10\n  B,C,10\n",   // the `sankey-beta` alias + a 3-node chain
        "sankey\n",                            // empty body → minimal inert canvas (round-trips, never the shell)
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

    /// The semantic-anchor widening (plan sirentide-semantic-anchor-g, contract sirentide/67): a real
    /// flowchart now bakes `<g data-sirentide-role/-id/-seq>` around each node/edge, and those must
    /// stay INSIDE the allowlist (the per-element walk checks role ∈ enum, id ∈ charset, seq ∈ digits).
    /// Proves producer ⊆ contract for the anchor attrs.
    @Test
    void anchorGroupAttrsStayInsideTheAllowlist() throws Exception {
        String svg = Sirentide.render("flowchart TD\n  A[Start] --> B[End]\n");
        assertTrue(svg.contains("data-sirentide-role=\"node\""), "a node anchor was baked: " + svg);
        assertTrue(svg.contains("data-sirentide-role=\"edge\""), "an edge anchor was baked: " + svg);
        assertTrue(svg.contains("data-sirentide-id=") && svg.contains("data-sirentide-seq="),
            "anchor id + seq were baked: " + svg);
        Document doc = parse(svg);
        checkElement(doc.getDocumentElement(), "flowchart-anchors");

        // A pie bakes slice anchors too.
        String pie = Sirentide.render("pie\n  \"Reviews\" : 40\n  \"Builds\" : 60\n");
        assertTrue(pie.contains("data-sirentide-role=\"slice\""), "a slice anchor was baked: " + pie);
        checkElement(parse(pie).getDocumentElement(), "pie-anchors");
    }

    /// The allowlist stays NON-VACUOUS for the anchor widen: a `<g>` carrying a FOREIGN data-* (the
    /// separately-gated `data-sirentide-fx`), an `onclick` handler, a bad ROLE (not in the closed
    /// enum), or a bad ID (a char outside the `[A-Za-z0-9_-]` charset) must ALL still fail the
    /// containment walk. Proves the widen admitted ONLY the three closed anchor attrs with their
    /// value grammars — not arbitrary data-*/on*/values.
    @Test
    void anchorGuardIsNotVacuous() throws Exception {
        String head = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\" "
            + "viewBox=\"0 0 1 1\">";
        // (a) a foreign data-* attribute on <g> — data-sirentide-fx is a later, separately-gated ask.
        assertGRejected(head + "<g data-sirentide-fx=\"x\"></g></svg>", "data-sirentide-fx");
        // (b) an event handler on <g>.
        assertGRejected(head + "<g onclick=\"alert(1)\"></g></svg>", "onclick");
        // (c) a role OUTSIDE the closed enum (value violation, attr name is allowed).
        assertGRejected(head + "<g data-sirentide-role=\"script\" data-sirentide-id=\"a\" "
            + "data-sirentide-seq=\"0\"></g></svg>", "data-sirentide-role");
        // (d) an id carrying an illegal char (a quote/space/angle-bracket the sanitizer would strip).
        assertGRejected(head + "<g data-sirentide-role=\"node\" data-sirentide-id=\"a b\" "
            + "data-sirentide-seq=\"0\"></g></svg>", "data-sirentide-id");
        // (e) a non-numeric seq.
        assertGRejected(head + "<g data-sirentide-role=\"node\" data-sirentide-id=\"a\" "
            + "data-sirentide-seq=\"1x\"></g></svg>", "data-sirentide-seq");
    }

    /// Assert a hand-crafted SVG fails the containment walk with a message naming the offending token.
    private void assertGRejected(String hostile, String needle) throws Exception {
        Document doc = parse(hostile);
        try {
            checkElement(doc.getDocumentElement(), "hostile-g:" + needle);
            fail("a <g> carrying " + needle + " must fail the allowlist — the anchor guard is vacuous");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains(needle),
                "the failure names " + needle + ": " + expected.getMessage());
        }
    }

    /// The theming widen (plan sirentide-theming-config): a `theme: dark`/`neutral` diagram carries a
    /// SELF-CONTAINED background `<rect>` covering the viewBox, and its page-level `currentColor` text
    /// resolves to an explicit foreground hex. Both must stay INSIDE the allowlist — the bg rect is a
    /// plain in-alphabet `<rect>` (numeric geometry + contract-clean hex fill), no new element/attr. A
    /// themed bake across the corpus is proven producer ⊆ contract exactly like the default bake.
    @Test
    void themedBackgroundAndForegroundStayInsideTheAllowlist() throws Exception {
        for (String theme : new String[] {"dark", "neutral"}) {
            for (String body : new String[] {
                "pie\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 30\n",
                "flowchart TD\n  A[Start] --> B{Ready?}\n  B -->|yes| C[Ship]\n",
                "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n"}) {
                String svg = Sirentide.render("%% theme: " + theme + "\n" + body);
                assertTrue(svg.contains("<rect x=\"0\" y=\"0\""),
                    theme + ": a self-contained background rect was baked: " + svg);
                checkElement(parse(svg).getDocumentElement(), "themed-" + theme);
            }
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
