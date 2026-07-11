package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Source-of-truth generator for `examples/showcase.html` — the hand-authored gallery kept drifting
/// behind the diagram types as they landed (it was stuck at `quadrant` while class/ER/mathblock +
/// the math moat shipped). This test IS the showcase now: it renders every demo through the real
/// bake pipeline ({@link Sirentide#render}) and, under `-Dsirentide.updateShowcase=true`, writes the
/// whole page. The math demos render through the REAL {@link LatteXMathFragmentRenderer} so the
/// baked LaTeX (STIX glyph paths + fraction-bar rects) shows for real, not degraded to raw source.
///
/// Regen mechanism (mirrors {@link GoldenSvgTest}'s golden regen):
///   ./gradlew test --tests com.sirentide.ShowcaseGenTest -Dsirentide.updateShowcase=true
/// Without the flag the test still renders every card and SMOKE-CHECKS it (non-empty, non-inert,
/// math actually baked) so the generator can't silently rot — but it does NOT byte-assert the
/// committed HTML, so a benign layout tweak doesn't red this test; you regen + commit the new page.
class ShowcaseGenTest {

    private static final boolean UPDATE = Boolean.getBoolean("sirentide.updateShowcase");
    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    /// One card per demo. `typeTag` is the little `<code>` chip after the title; `math` routes the
    /// bake through the real LatteX renderer (the moat) instead of the null renderer.
    private record Card(String title, String typeTag, String desc, String dsl, boolean math) {
        Card(String title, String typeTag, String desc, String dsl) {
            this(title, typeTag, desc, dsl, false);
        }
    }

    private static final List<Card> CARDS = List.of(
        new Card("Pie", "pie legend",
            "Proportional wedges, on-slice contrast labels, an optional left color key.",
            "pie legend\n\"Reviews\" : 40\n\"Builds\" : 25\n\"Docs\" : 20\n\"Design\" : 15"),
        new Card("Bars · Lines · Scatter", "xychart line",
            "One type, three render modes, multi-series with a legend — and a missing value is an "
                + "honest <em>gap</em>, never a fake bridge.",
            "xychart line legend\nseries: Revenue, Cost\n\"Mon\" : 5 3\n\"Tue\" : 8 6\n\"Wed\" : 6\n"
                + "\"Thu\" : 9 4\n\"Fri\" : 12 7"),
        new Card("Timeline", "timeline",
            "Events placed <em>proportionally</em> in time; ISO dates render as dates.",
            "timeline\n\"Founded\" : 2019-06-01\n\"Series A\" : 2021-03-15\n\"Launch\" : 2024-11-08"),
        new Card("Gantt", "gantt",
            "Min-normalized shared time axis; per-task colors.",
            "gantt\n\"Design\" : 0-3\n\"Build\" : 3-8 #22c55e\n\"Test\" : 7-11\n\"Ship\" : 11-13"),
        new Card("Flowchart", "flowchart",
            "Layered graph, TD or LR — diamond decisions, per-hop edge labels, and a cycle-tolerant "
                + "layout: the <code>retry</code> loop rides a visible side lane. Node labels "
                + "auto-contrast with any fill you pick.",
            "flowchart\nStart[Commit] --> Test{Tests green?}\nTest -->|yes| Ship[Ship it] #22c55e\n"
                + "Test -->|no| Fix[Fix] #ef4444\nFix -->|retry| Test"),
        new Card("Flowchart — node shapes", "flowchart",
            "The mermaid node vocabulary, keyed by delimiter: <code>[rect]</code>, "
                + "<code>(rounded)</code>, <code>([stadium])</code>, <code>((circle))</code>, "
                + "<code>{{hexagon}}</code>, <code>[(database)]</code>, <code>[[subroutine]]</code>, and "
                + "the <code>{diamond}</code> decision — each baked to pure path/line geometry, labels "
                + "auto-fitted inside.",
            "flowchart TD\nA[Process] --> B(Rounded)\nB --> C([Stadium])\nC --> D((Go))\n"
                + "D --> E{{Prepare}}\nE --> F[(Store)]\nF --> G[[Validate]]\nG --> H{OK?}"),
        new Card("Flowchart — edge types", "flowchart",
            "The mermaid edge vocabulary, keyed by operator: <code>--&gt;</code> solid arrow, "
                + "<code>---</code> open link (no head), <code>-.-&gt;</code> dotted arrow, "
                + "<code>-.-</code> dotted open, <code>==&gt;</code> thick arrow, <code>===</code> thick "
                + "open — each still carries an optional <code>|label|</code>. Dotted lines are baked as "
                + "deterministic short segments (the output has no stroke-dasharray); thick lines draw a "
                + "heavier stroke; open links drop the arrowhead.",
            "flowchart TD\nA[Start] --- B[Link]\nB -.->|retry| C[Retry]\nC -.- D[Idle]\n"
                + "D ==>|ship| E[Ship]\nE === F[Done]"),
        new Card("Sequence", "sequence",
            "Actors, lifelines, calls (filled heads), replies (open-V), self-message hooks, and "
                + "activation bars that grow while an actor is busy.",
            "sequence\nClient ->> Gateway : GET /token\nGateway ->> Auth : validate\n"
                + "Auth -->> Gateway : ok\nGateway ->> Gateway : sign JWT\n"
                + "Gateway -->> Client : 200 token"),
        new Card("State", "state",
            "Mermaid-style lifecycles: <code>[*]</code> start disc and end bullseye, labeled "
                + "transitions, cycles inherited from the flowchart engine.",
            "state\n[*] --> Idle\nIdle --> Running : start\nRunning --> Idle : stop\nRunning --> [*]"),
        new Card("Quadrant", "quadrant",
            "A 2×2 positioning matrix: axis-end labels, four quadrant labels (Mermaid numbering — "
                + "Q1 top-right…Q4 bottom-right), and <code>[x,y]</code> points in the unit square. "
                + "Soft quadrant tints with contrast-derived labels.",
            "quadrant\nx-axis \"Low Reach\" --> \"High Reach\"\ny-axis \"Low Impact\" --> \"High Impact\"\n"
                + "quadrant-1 \"Major project\"\nquadrant-2 \"Quick win\"\nquadrant-3 \"Deprioritize\"\n"
                + "quadrant-4 \"Fill-in\"\n\"Feature A\" : [0.3, 0.6]\n\"Feature B\" : [0.75, 0.8]\n"
                + "\"Feature C\" : [0.5, 0.2]\n\"Feature D\" : [0.85, 0.35]"),
        new Card("Comparison matrix", "matrix",
            "A categorical verdict matrix: <code>cols:</code> headers over <code>\"row\" : v1, v2</code> "
                + "cells, each filled from a CLOSED palette — pass/fail/partial/na (aliases "
                + "match→pass, diverge→fail). Rows rectangularize to the header width; an unknown token "
                + "falls to the neutral fill, so no free-form colour is ever introduced.",
            "matrix\ncols: snapshot, bare\n\"ID1 claim-on-no-signal\" : match, match\n"
                + "\"PC2 peer-over-flagship\" : match, match\n\"PC1 soft-intent threshold\" : partial, diverge\n"
                + "\"PC5 boundary-holds-vs-Charles\" : match, diverge"),
        new Card("Class diagram", "classDiagram",
            "UML classes with stacked attribute + method compartments, grid-placed, wired by all "
                + "five relationship markers — inheritance (hollow triangle), composition (filled "
                + "diamond), aggregation (hollow diamond), association (arrow), dependency (dashed).",
            "classDiagram\nclass Animal {\n+String name\n+int age\n+eat() void\n+sleep()\n}\n"
                + "class Dog {\n+bark() void\n}\nAnimal <|-- Dog : inherits\n"
                + "Animal *-- Collar : composition\nAnimal o-- Owner : aggregation\n"
                + "Dog --> Bone : association\nDog ..> Vet : dependency"),
        new Card("ER diagram", "erDiagram",
            "Entity tables with typed attributes and PK markers, joined by crow-foot cardinality "
                + "glyphs — exactly-one (bar), zero-or-many (circle + foot), one-or-many (bar + foot).",
            "erDiagram\nCUSTOMER ||--o{ ORDER : places\nORDER ||--|{ LINE-ITEM : contains\n"
                + "CUSTOMER }o--o| ADDRESS : has\nCUSTOMER {\nstring name PK\nstring email\nint age\n}\n"
                + "ORDER {\nint id PK\ndate created\n}"),
        new Card("Class diagram — tall math", "classDiagram",
            "A compartment row GROWS to contain a tall multi-row fragment — a matrix, cases, or a "
                + "stacked fraction — reusing the same math seam the flowchart nodes use. The rows "
                + "below it shift down, the box grows, and the relationship anchor tracks the taller "
                + "border. Short / plain labels stay byte-identical (they never grow).",
            "classDiagram\nclass Matrix {\n+grid $\\begin{matrix} a & b \\\\ c & d \\end{matrix}$\n"
                + "+int rank\n+det() double\n}\nMatrix --> Scalar : maps",
            true),
        new Card("Display math", "mathblock",
            "A standalone full-size equation — the whole body is one LaTeX expression, baked "
                + "centered to real glyph paths and a fraction-bar <code>&lt;rect&gt;</code>. Paste an "
                + "equation, get an SVG.",
            "mathblock\n\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", true),
        new Card("Git graph", "gitGraph",
            "A commit history: dots on a shared time axis, one color-coded <em>lane</em> per branch. "
                + "<code>branch</code> forks a new lane off the current tip, <code>merge</code> elbows "
                + "back in with a merge commit, and <code>commit id: \"x\"</code> labels a dot.",
            "gitGraph\ncommit\ncommit id: \"init\"\nbranch develop\ncheckout develop\ncommit\n"
                + "commit id: \"feature\"\ncheckout main\nmerge develop\ncommit id: \"release\""),
        new Card("User journey", "journey",
            "A satisfaction map: tasks in order along the x-axis, each scored 1–5 on the y-axis "
                + "(higher = happier), connected by a line. <code>section</code> brackets group the "
                + "steps; each task lists its <em>actors</em>. Points are colored by score — warm red "
                + "for a low, cool green for a high.",
            "journey\ntitle My working day\nsection Go to work\nMake tea: 5: Me\n"
                + "Commute: 3: Me, Cat\nArrive: 4: Me\nsection Do work\nCode: 5: Me\n"
                + "Meetings: 2: Me, Boss\nLunch: 4: Me, Team"),
        new Card("Mindmap", "mindmap",
            "An <em>indentation</em>-defined hierarchy — the first line is the root, each deeper "
                + "line a child of the nearest shallower one. Laid out as a left-to-right tree: depth "
                + "is a column, siblings stack down the y-axis, each parent centered on its children, "
                + "elbow connectors wiring the branches. Boxes band by depth.",
            "mindmap\n  root Mindmaps\n    Origins\n      Long history\n      Popular\n    Research\n"
                + "      On effectiveness\n    Tools\n      Pen and paper\n      Mermaid"),
        new Card("Sankey", "sankey",
            "Weighted flows between nodes: each row is <code>source,target,value</code>, and a "
                + "<em>band</em>'s width is proportional to its value. Nodes fall into "
                + "<em>columns</em> by depth (sources left, sinks right); a node's height is the larger "
                + "of its in- or out-flow. Bands are a lighter tint of their source's color.",
            "sankey\nCoal,Electricity,25\nGas,Electricity,15\nElectricity,Homes,20\n"
                + "Electricity,Industry,20\nSolar,Homes,10\nSolar,Industry,5"),
        new Card("Flowchart — nested subgraphs", "subgraph",
            "Cluster containers group nodes inside a titled frame; nest them for pipelines "
                + "within pipelines. The frame border, title tab, and canvas grow-to-fit are all baked.",
            "flowchart TD\nA[Start] --> B[Work]\nsubgraph outer [Build Pipeline]\nB --> C[Compile]\n"
                + "subgraph inner [Test Suite]\nC --> D[Unit]\nD --> F[Integration]\nend\n"
                + "F --> G[Package]\nend\nG --> E[Ship]"),
        new Card("Flowchart — semantic colour classes", "classDef · class",
            "Define a reusable fill with <code>classDef &lt;name&gt; fill:#rrggbb</code>, then assign it "
                + "with <code>class &lt;id&gt; &lt;name&gt;</code> — the green=allow / red=deny palette the "
                + "security diagrams need. Same <code>#rrggbb</code>-only hex gate as a per-node colour; "
                + "a per-node <code>#hex</code> still wins over its class.",
            "flowchart LR\nclassDef deny fill:#fecaca\nclassDef ok fill:#bbf7d0\n"
                + "A[Request] --> B{Authorized?}\nB -->|yes| C[Serve]\nB -->|no| D[Deny]\n"
                + "class C ok\nclass D deny"),
        new Card("Caption / note directive", "%% caption · %% note",
            "A <code>%% caption: &lt;text&gt;</code> directive (alias <code>%% note:</code>) in the "
                + "preamble renders a centered, word-wrapped annotation band below <em>any</em> diagram "
                + "type. It bakes to <code>currentColor</code> glyph paths like every label — inert by "
                + "construction, no sanitizer change.",
            "%% caption: A merge lands only after both peers approve and no conflicts remain.\n"
                + "flowchart LR\nA[Author] --> B[Review]\nB --> C[Merge]"),
        new Card("Sequence — alt / loop / par frames", "alt · loop · par",
            "Combined-fragment frames: an <code>alt</code>/<code>else</code> branch, a "
                + "<code>loop</code> nested inside it, and a <code>par</code>/<code>and</code> across a "
                + "third actor — each a bordered frame with a label tab and dashed dividers.",
            "sequence\nAlice ->> Bob : hello\nalt is available\nBob -->> Alice : yes\n"
                + "loop every retry\nAlice ->> Bob : ping\nend\nelse is busy\nBob -->> Alice : later\nend\n"
                + "par to Bob\nAlice ->> Bob : a\nand to Carol\nAlice ->> Carol : b\nend"),
        new Card("Sequence — notes & create / destroy", "note · create · destroy",
            "Annotate a lifeline with a <code>note</code> box — <code>over</code> one actor, spanning "
                + "<code>over A,B</code>, or to the <code>left of</code>/<code>right of</code> a "
                + "lifeline — and spin a participant up <em>mid-diagram</em> with "
                + "<code>create</code>, then end its lifeline with an <code>X</code> via "
                + "<code>destroy</code>.",
            "sequence\nAlice ->> Bob : hello\nnote right of Bob : Bob validates\n"
                + "note over Alice,Bob : a shared checkpoint\ncreate participant Worker\n"
                + "Bob ->> Worker : spawn job\nWorker -->> Bob : started\ndestroy Worker\n"
                + "Bob -->> Alice : done"),
        new Card("Math in any label", "$…$",
            "The moat: real LaTeX inside a diagram label. A <code>$…$</code> run in any node/edge/"
                + "message label bakes to STIX glyph paths (not raw source) via an injected math "
                + "renderer — here <code>$E=mc^2$</code> and a real fraction <code>$\\frac{v^2}{r}$</code>.",
            "flowchart TD\nA[Energy $E=mc^2$] --> B[$\\frac{v^2}{r}$]", true),
        new Card("Multi-row math in labels", "matrix · cases",
            "TALL-fragment box growth: a <em>multi-row</em> construct — a "
                + "<code>$\\begin{matrix}…$</code>, <code>$\\begin{cases}…$</code>, a stacked fraction — "
                + "grows its node box VERTICALLY to fit, keeping the fragment centered on the baseline "
                + "and fully contained. A single-line label (the <code>Vector</code> and "
                + "<code>Solve</code> text, the inline <code>$x$</code>) keeps the fixed height, so only "
                + "the genuinely tall labels grow.",
            "flowchart TD\nA[Vector $\\begin{matrix} a \\\\ b \\\\ c \\end{matrix}$] --> B[Scale by $x$]\n"
                + "A --> C[Solve $\\begin{cases} x & a \\\\ y & b \\\\ z & c \\end{cases}$]", true));

    /// The play-through demo body (plan sirentide-play-through-frames): a small request/response
    /// sequence whose 3 messages become 3 static frames, the active step advancing. Structurally
    /// different from a Card (many frames, not one render), so it is generated on its own.
    private static final String PLAY_DSL =
        "sequence\nClient ->> Server : request\nServer ->> Server : process\n"
            + "Server -->> Client : response";

    /// The theme card is structurally different (one bake, shown in a light + a dark pane), so it is
    /// generated on its own after the grid of cards.
    private static final String THEME_DSL =
        "flowchart nodecolor=#1e293b\nA[Author] --> B{Bake}\nB -->|svg| C[Docs] #22c55e";

    /// The theme-config demo: one diagram body, baked default (transparent) vs with a leading
    /// `%% theme: dark` config block (self-contained dark background + light structural text).
    private static final String THEME_CONFIG_BODY =
        "pie legend\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 30\n";
    private static final String THEME_CONFIG_DARK =
        "%% title: Where the time goes\n%% theme: dark\n" + THEME_CONFIG_BODY;

    /// The three per-type demo pages that were missing (class / ER / mathblock) — same generated
    /// bake, in the hand-authored per-type page template (a single card + notes).
    private record TypePage(String file, String title, String heading, String note, String dsl,
        boolean math) {}

    private static final List<TypePage> TYPE_PAGES = List.of(
        new TypePage("class.html", "Class diagram", "Class diagram",
            "A UML class diagram. <code>class Name { +field type; +method() ret }</code> declares a "
                + "three-compartment box (name / attributes / methods); an undeclared class on an edge "
                + "auto-vivifies as an empty box. All five relationship markers render: "
                + "<code>&lt;|--</code> inheritance (hollow triangle), <code>*--</code> composition "
                + "(filled diamond), <code>o--</code> aggregation (hollow diamond), <code>--&gt;</code> "
                + "association (arrow), <code>..&gt;</code> dependency (dashed).",
            "classDiagram\nclass Animal {\n+String name\n+int age\n+eat() void\n+sleep()\n}\n"
                + "class Dog {\n+bark() void\n}\nAnimal <|-- Dog : inherits\n"
                + "Animal *-- Collar : composition\nAnimal o-- Owner : aggregation\n"
                + "Dog --> Bone : association\nDog ..> Vet : dependency", false),
        new TypePage("er.html", "ER diagram", "Entity-relationship diagram",
            "An entity-relationship diagram. <code>NAME { type field PK }</code> declares an entity "
                + "table with typed attributes (<code>PK</code> marks a key); "
                + "<code>A ||--o{ B : verb</code> draws a relationship with crow-foot cardinality at "
                + "each end — <code>||</code> exactly-one (bar), <code>o{</code> zero-or-many "
                + "(circle + foot), <code>|{</code> one-or-many (bar + foot), <code>o|</code> "
                + "zero-or-one.",
            "erDiagram\nCUSTOMER ||--o{ ORDER : places\nORDER ||--|{ LINE-ITEM : contains\n"
                + "CUSTOMER }o--o| ADDRESS : has\nCUSTOMER {\nstring name PK\nstring email\nint age\n}\n"
                + "ORDER {\nint id PK\ndate created\n}", false),
        new TypePage("mathblock.html", "Display math", "Display math block",
            "A standalone full-size display equation: the whole body after <code>mathblock</code> is "
                + "one LaTeX expression, baked centered. With an injected math renderer it typesets to "
                + "real glyph paths plus a fraction-bar <code>&lt;rect&gt;</code> (shown here); with "
                + "the default null renderer it degrades to the raw source as plain-text glyphs — "
                + "loud, never blank. The math backend is the consumer's choice; the core ships "
                + "zero runtime dependencies.",
            "mathblock\n\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", true),
        new TypePage("gitGraph.html", "Git graph", "Git graph",
            "A commit graph. <code>commit</code> adds a node to the current branch's lane; "
                + "<code>branch name</code> / <code>checkout name</code> open and switch lanes "
                + "(each a distinct palette colour); <code>merge name</code> draws an elbow "
                + "connector from that branch's tip into a merge commit on the active lane. "
                + "Commits advance in declaration order; <code>commit id: \"x\"</code> labels a node.",
            "gitGraph\ncommit\ncommit id: \"init\"\nbranch develop\ncheckout develop\ncommit\n"
                + "commit id: \"feature\"\ncheckout main\nmerge develop\ncommit id: \"release\"", false),
        new TypePage("journey.html", "User journey", "User-journey map",
            "A satisfaction map. <code>section Name</code> groups tasks; each "
                + "<code>Task: score: Actor[, Actor]</code> plots a point at its 1-5 satisfaction "
                + "score (higher sits higher), coloured on a red-to-green ramp, with the actors "
                + "listed beneath. A line connects consecutive tasks; a per-section bracket spans "
                + "its columns.",
            "journey\ntitle My working day\nsection Go to work\nMake tea: 5: Me\n"
                + "Commute: 3: Me, Cat\nArrive: 4: Me\nsection Do work\nCode: 5: Me\n"
                + "Meetings: 2: Me, Boss\nLunch: 4: Me, Team", false),
        new TypePage("mindmap.html", "Mindmap", "Mind map",
            "An indentation-defined tree. The first line is the root; each deeper indentation "
                + "level is a child of the nearest shallower line. Rendered as a left-to-right "
                + "layered tree — depth sets the column, each parent centred on its children's "
                + "span, elbow connectors linking parent to child, depth-banded node colours.",
            "mindmap\n  root Root idea\n    Origins\n      Long history\n      Popular\n"
                + "    Research\n      On effect\n    Tools\n      Pen and paper\n      Mermaid", false),
        new TypePage("sankey.html", "Sankey", "Sankey flow diagram",
            "A weighted-flow diagram. Each <code>source,target,value</code> row is a flow; nodes "
                + "sit in depth columns (source-only leftmost), and a node's height is the greater "
                + "of its in- and out-flow totals. Every flow draws as a band whose width is "
                + "proportional to its value, tinted from its source node's colour.",
            "sankey\nCoal,Electricity,25\nGas,Electricity,15\nElectricity,Homes,20\n"
                + "Electricity,Industry,20\nSolar,Homes,10\nSolar,Industry,5", false));

    @Test
    void showcaseRendersEveryTypeAndFeature() throws Exception {
        StringBuilder body = new StringBuilder();
        for (Card c : CARDS) {
            String svg = c.math() ? Sirentide.render(c.dsl(), REAL) : Sirentide.render(c.dsl());
            // Smoke check: real output, never the inert 0×0 degrade shell.
            assertTrue(svg.contains("<svg") && !svg.contains("width=\"0\" height=\"0\""),
                c.title() + ": expected a real render, got an inert/empty SVG");
            if (c.math()) {
                // The moat proof: a MathBox actually baked (a translated fill group), not degraded
                // to raw $...$ source text.
                assertTrue(svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
                    c.title() + ": math did not bake through the real renderer — degraded to source?");
                assertFalse(svg.contains("\\frac") || svg.contains("mc^2"),
                    c.title() + ": raw LaTeX leaked into the render instead of baking");
            }
            body.append("<section class=\"card\">\n")
                .append("  <h2>").append(c.title()).append("<code>").append(escape(c.typeTag()))
                .append("</code></h2>\n")
                .append("  <p class=\"desc\">").append(c.desc()).append("</p>\n")
                .append("  <div class=\"duo\"><pre>").append(escape(c.dsl()))
                .append("</pre><div class=\"render\">").append(svg).append("</div></div>\n")
                .append("</section>\n");
        }
        // Theme card: one bake, embedded twice.
        String themeSvg = Sirentide.render(THEME_DSL);
        body.append("<section class=\"card\">\n")
            .append("  <h2>One bake, any theme</h2>\n")
            .append("  <p class=\"desc\">The <em>same</em> SVG, twice — no media queries, no second "
                + "render. Page-background text is <code>currentColor</code> (inherits the theme); "
                + "labels on filled shapes auto-contrast with the fill; author colors "
                + "(<code>#hex</code> per node, <code>nodecolor=</code> for all) stay legible "
                + "anywhere.</p>\n")
            .append("  <pre>").append(escape(THEME_DSL)).append("</pre>\n")
            .append("  <div class=\"split\">\n")
            .append("    <div class=\"pane light\"><div class=\"lbl\">light page</div>")
            .append(themeSvg).append("</div>\n")
            .append("    <div class=\"pane dark\"><div class=\"lbl\">dark page</div>")
            .append(themeSvg).append("</div>\n")
            .append("  </div>\n")
            .append("</section>\n");

        // Theme-config card (plan sirentide-theming-config): the SAME diagram baked default (transparent,
        // for a light page) vs with a leading `%% theme: dark` config block (a SELF-CONTAINED dark
        // background rect + light-adjusted structural text). Both panes sit on a DARK surface to show
        // the default needs the page to be light while `theme: dark` reads on dark standalone.
        String defaultBake = Sirentide.render(THEME_CONFIG_BODY);
        String darkBake = Sirentide.render(THEME_CONFIG_DARK);
        assertTrue(darkBake.contains("fill=\"#1e1e1e\""),
            "the theme:dark demo must carry its self-contained dark background rect");
        body.append("<section class=\"card\">\n")
            .append("  <h2>Config block<code>%% theme: dark</code></h2>\n")
            .append("  <p class=\"desc\">A leading <code>%% key: value</code> config block (read before "
                + "any diagram type) sets <code>title</code>, <code>theme</code> "
                + "(<code>default</code>·<code>dark</code>·<code>neutral</code>) and "
                + "<code>direction</code>. <code>theme: dark</code> bakes a <em>self-contained</em> dark "
                + "background rect + light text, so the SVG reads on a dark page with no media query or "
                + "second render. Both panes below sit on a dark surface — the default (transparent) "
                + "needs a light page; <code>theme: dark</code> carries its own.</p>\n")
            .append("  <pre>").append(escape(THEME_CONFIG_DARK)).append("</pre>\n")
            .append("  <div class=\"split\">\n")
            .append("    <div class=\"pane dark\"><div class=\"lbl\">default theme · on a dark page</div>")
            .append(defaultBake).append("</div>\n")
            .append("    <div class=\"pane dark\"><div class=\"lbl\">%% theme: dark · self-contained</div>")
            .append(darkBake).append("</div>\n")
            .append("  </div>\n")
            .append("</section>\n");

        // Play-through card (plan sirentide-play-through-frames): the FIRST consumer of the semantic
        // seq anchors. `renderFrames` turns the already-assigned `data-sirentide-seq` step-ordering
        // into N STATIC SVG frames — a slideshow a doc flips through, zero JS. Frame k accents the
        // active step (thick orange arrow), shows earlier steps done/normal, and dims later ones
        // (cumulative "playing forward"). Every frame is a standalone CSP-clean bake — same alphabet,
        // no script/animation/:target — and shares the ONE layout's geometry byte-for-byte. Here: 3
        // consecutive message frames of a request/response sequence, the active step advancing.
        List<String> playFrames = Sirentide.renderFrames(PLAY_DSL);
        assertTrue(playFrames.size() >= 3, "the play-through demo must have at least 3 frames");
        assertFalse(playFrames.get(0).equals(playFrames.get(1)),
            "play-through frames must differ (a different active step per frame)");
        body.append("<section class=\"card\">\n")
            .append("  <h2>Play-through frames<code>renderFrames · seq → N static SVGs</code></h2>\n")
            .append("  <p class=\"desc\">The <em>flow you play</em>: the semantic <code>data-sirentide-seq"
                + "</code> step-ordering baked into every diagram is now <em>consumed</em> — "
                + "<code>Sirentide.renderFrames(dsl)</code> returns one static SVG per step, each "
                + "accenting the active step (thick accent arrow), showing earlier steps done and "
                + "dimming later ones. No new syntax, no runtime JS, same "
                + "<code>svg/path/rect/line</code> alphabet — a slideshow a doc flips through. Three "
                + "consecutive frames below, the active message advancing.</p>\n")
            .append("  <pre>").append(escape(PLAY_DSL)).append("</pre>\n")
            .append("  <div class=\"frames\">\n");
        for (int i = 0; i < 3; i++) {
            body.append("    <div class=\"frame\"><div class=\"lbl\">frame ").append(i + 1)
                .append(" · step ").append(i + 1).append("</div>").append(playFrames.get(i))
                .append("</div>\n");
        }
        body.append("  </div>\n")
            .append("</section>\n");

        // The three per-type demo pages.
        for (TypePage tp : TYPE_PAGES) {
            String svg = tp.math() ? Sirentide.render(tp.dsl(), REAL) : Sirentide.render(tp.dsl());
            assertTrue(svg.contains("<svg") && !svg.contains("width=\"0\" height=\"0\""),
                tp.file() + ": expected a real render, got an inert/empty SVG");
            if (tp.math()) {
                assertTrue(svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
                    tp.file() + ": math did not bake through the real renderer");
            }
            if (UPDATE) {
                Files.writeString(Path.of("examples", tp.file()).toAbsolutePath(), typePage(tp, svg));
            }
        }

        if (UPDATE) {
            Files.writeString(Path.of("examples", "showcase.html").toAbsolutePath(), page(body.toString()));
        }
    }

    /// The hand-authored per-type page template (mirrors examples/state.html): a single demo card
    /// plus two note lines.
    private static String typePage(TypePage tp, String svg) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sirentide — %TITLE% example</title>
            <!-- GENERATED by com.sirentide.ShowcaseGenTest — do not hand-edit.
                 Regen: ./gradlew test --tests com.sirentide.ShowcaseGenTest -Dsirentide.updateShowcase=true -->
            <style>
              body { font-family: system-ui, sans-serif; max-width: 860px; margin: 3rem auto; padding: 0 1rem;
                     color: #1e293b; line-height: 1.55; }
              h1 { margin-bottom: .2rem; } .sub { color: #64748b; margin-top: 0; }
              .card { border: 1px solid #e2e8f0; border-radius: 12px; padding: 1.5rem; margin: 1.5rem 0; }
              .duo { display: flex; gap: 2rem; align-items: center; flex-wrap: wrap; }
              .duo pre { flex: 0 1 340px; margin: 0; }
              .duo .render { flex: 1 1 300px; text-align: center; overflow-x: auto; }
              .render svg { max-width: 100%; height: auto; }
              pre { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 8px; overflow-x: auto; font-size: .85rem; }
              code { background: #f1f5f9; padding: .1rem .3rem; border-radius: 4px; }
              .note { color: #475569; font-size: .95rem; }
              a { color: #6366f1; }
            </style>
            </head>
            <body>
            <h1>Sirentide — %HEADING%</h1>
            <p class="sub">Live renderer output — the SVG below was produced by Sirentide from the DSL beside it.</p>
            <div class="card"><div class="duo"><pre>%DSL%</pre><div class="render">%SVG%</div></div></div>
            <p class="note">%NOTE%</p>
            <p class="note">All sixteen types on one page: <a href="showcase.html">showcase.html</a> · browser-audited renders: <a href="gallery/GALLERY.md">gallery</a></p>
            </body>
            </html>
            """
            .replace("%TITLE%", tp.title())
            .replace("%HEADING%", tp.heading())
            .replace("%NOTE%", tp.note())
            .replace("%DSL%", escape(tp.dsl()))
            .replace("%SVG%", svg);
    }

    /// The full HTML document. The `<style>` block and header/footer are the hand-authored chrome,
    /// preserved verbatim; only the count in the sub-line moved (8 → eleven) and the body grid is
    /// generated from {@link #CARDS}.
    private static String page(String cards) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sirentide — showcase</title>
            <!-- GENERATED by com.sirentide.ShowcaseGenTest — do not hand-edit.
                 Regen: ./gradlew test --tests com.sirentide.ShowcaseGenTest -Dsirentide.updateShowcase=true -->
            <style>
            :root { --ink:#0f172a; --sub:#475569; --line:#e2e8f0; --card:#ffffff; }
            * { box-sizing: border-box; }
            body { font-family: system-ui, -apple-system, sans-serif; margin: 0; color: var(--ink);
                   background: linear-gradient(180deg,#f8fafc,#eef2f7); line-height: 1.55; }
            .wrap { max-width: 1000px; margin: 0 auto; padding: 0 1.25rem 4rem; }
            header { text-align: center; padding: 3.5rem 1rem 2rem; }
            header h1 { font-size: 2.6rem; margin: 0; letter-spacing: -.02em; }
            header .tag { color: var(--sub); font-size: 1.15rem; margin: .4rem 0 0; }
            header .sub { color: #64748b; font-size: .95rem; margin-top: .6rem; }
            .grid { display: grid; gap: 1.4rem; }
            section.card { background: var(--card); border: 1px solid var(--line); border-radius: 14px;
              padding: 1.4rem 1.6rem; box-shadow: 0 1px 3px rgba(15,23,42,.05); }
            section.card h2 { margin: 0 0 .15rem; font-size: 1.25rem; }
            section.card h2 code { font-size: .8em; color: #6366f1; background: #eef2ff; padding: .1rem .45rem; border-radius: 6px; margin-left: .5rem; }
            .desc { color: var(--sub); font-size: .95rem; margin: .2rem 0 1rem; }
            .duo { display: flex; gap: 1.5rem; align-items: center; flex-wrap: wrap; }
            .duo pre { flex: 0 1 340px; margin: 0; }
            .duo .render { flex: 1 1 320px; text-align: center; overflow-x: auto; }
            .render svg { max-width: 100%; height: auto; }
            pre { background: #0f172a; color: #e2e8f0; padding: .9rem 1.1rem; border-radius: 10px;
                  font-size: .82rem; overflow-x: auto; }
            .split { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-top: 1rem; }
            .pane { border-radius: 12px; padding: 1.2rem; text-align: center; }
            .pane.light { background: #ffffff; border: 1px solid var(--line); color: #0f172a; }
            .pane.dark  { background: #0b1220; border: 1px solid #1f2a44; color: #e2e8f0; }
            .pane .lbl { font-size: .8rem; opacity: .65; margin-bottom: .6rem; }
            .frames { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; margin-top: 1rem; }
            .frame { border: 1px solid var(--line); border-radius: 12px; padding: 1rem; text-align: center;
              background: #ffffff; overflow-x: auto; }
            .frame .lbl { font-size: .78rem; color: #6366f1; margin-bottom: .55rem; font-weight: 600; }
            .frame svg { max-width: 100%; height: auto; }
            @media (max-width: 720px){ .frames { grid-template-columns: 1fr; } }
            footer { text-align: center; color: #94a3b8; font-size: .85rem; padding: 2rem 0 0; }
            footer a { color: #6366f1; }
            @media (max-width: 720px){ .split { grid-template-columns: 1fr; } }
            </style>
            </head>
            <body>
            <header>
              <h1>Sirentide 🌊</h1>
              <p class="tag"><strong>Living, narratable diagrams — baked to static SVG, no runtime JS.</strong></p>
              <p class="sub">Sixteen diagram types · pure-Java bake · inert <code>svg/path/rect/line</code> output · every label a real glyph path · real LaTeX in any label.<br>
              Every image below is live renderer output, baked by Sirentide from the DSL beside it.</p>
            </header>
            <div class="wrap"><div class="grid">
            """
            + cards
            + """
            </div>
            <footer>Clean-room, zero-dependency, Apache-2.0 · sibling of <a href="https://github.com/supsup/LatteX">LatteX</a> · gallery of browser-audited renders in <a href="gallery/GALLERY.md">examples/gallery</a></footer>
            </div>
            </body>
            </html>
            """;
    }

    /// HTML-escape for `<pre>`/`<code>` text: `&`, `<`, `>` only (mirrors the hand-authored page, which
    /// left quotes literal inside `<pre>`).
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
