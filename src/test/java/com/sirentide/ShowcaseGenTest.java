package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Outcome;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.XyChart;
import com.sirentide.math.LatteXMathFragmentRenderer;
import com.sirentide.parse.DslParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
/// Without the flag the test renders every card, checks its semantics, and BYTE-ASSERTS the generated
/// page against the tracked artifact. A deliberate layout/content change therefore uses the explicit
/// update switch and commits the resulting page; stale showcase HTML cannot silently remain green.
class ShowcaseGenTest {

    private static final boolean UPDATE = Boolean.getBoolean("sirentide.updateShowcase");
    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();
    private static final String PLAY_ACCENT = "#e8590c";
    private static final int EXPECTED_PLAY_FRAMES = 5;
    private static final int DISPLAYED_PLAY_FRAMES = 3;

    private static final String BARS_DSL =
        "xychart\n\"Reviews\" : 8\n\"Builds\" : 5\n\"Docs\" : 3";
    private static final String LINE_DSL =
        "xychart line legend\nseries: Revenue, Cost\n\"Mon\" : 5 3\n\"Tue\" : 8 6\n"
            + "\"Wed\" : 6\n\"Thu\" : 9 4\n\"Fri\" : 12 7";
    private static final String SCATTER_DSL =
        "xychart scatter legend\nseries: Latency, Throughput\n\"Mon\" : 4 -2\n"
            + "\"Tue\" : 8 3\n\"Wed\" : 5 7\n\"Thu\" : 11 4\n\"Fri\" : 7 9";

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
        new Card("Bar chart", "xychart · default bars",
            "The default <code>xychart</code> mode: categorical values rise from a signed zero "
                + "baseline with proportional y-axis ticks.",
            BARS_DSL),
        new Card("Multi-series line chart", "xychart line",
            "Connected points, multi-series with a legend — and a missing value is an honest "
                + "<em>gap</em>, never a fake bridge. Bars are the default <code>xychart</code> mode.",
            LINE_DSL),
        new Card("Scatter chart", "xychart scatter",
            "The same categorical axes and multi-series palette, rendered as independent point "
                + "discs with no connecting segments; negative values remain below zero.",
            SCATTER_DSL),
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
        new Card("Heatmap", "heatmap",
            "A continuous-score grid: the comparison matrix's frame, but each cell carries a 0..1 "
                + "magnitude (decimal, <code>NN%</code>, or <code>text:value</code>) filled from a "
                + "single-hue sequential ramp — light→dark blue, so magnitude reads as depth, never "
                + "as a verdict colour. Dark cells flip their label to white, a non-numeric cell "
                + "stays neutral, and a sampled ramp legend (<code>scale:</code> names its ends) "
                + "sits under the grid.",
            "heatmap\ncols: bare, snapshot, card\nscale: \"diverged\" --> \"reproduced\"\n"
                + "\"values-boundary\" : 0.60, 0.72, 0.95\n\"card-discriminators\" : 1.00, 1.00, 1.00\n"
                + "\"decision-replay v2\" : 0.86, 0.90, 0.93\n\"technique naming\" : -, 40%, 100%"),
        new Card("Snake graph — √2", "snake · cf:",
            "The canonical Çanakçı–Schiffler square snake graph for a positive continued fraction. "
                + "The <code>cf:</code> partial quotients determine a connected strip of unit tiles; "
                + "each block length controls how long the strip runs before its next turn.",
            "snake\ncf: 1, 2, 2, 2"),
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
        new Card("Tensor network — MPS chain", "tensornetwork",
            "Penrose graphical notation: a matrix-product state as a horizontal chain of tensor "
                + "<em>cores</em> (discs), a <em>bond</em> (the contracted virtual index) between each "
                + "adjacent pair, and one dangling <em>physical leg</em> per core. Each core anchors as a "
                + "<code>node</code>, each bond as an <code>edge</code>, so the chain plays through frame "
                + "by frame like every other diagram.",
            "tensornetwork\nmps A B C D"),
        new Card("Tensor network — MPO operator", "tensornetwork mpo",
            "An <code>mpo</code> chain is a matrix-product <em>operator</em>: every core carries a "
                + "SECOND vertical leg (the operator's row index goes up, its column index down), so the "
                + "chain reads as an operator acting on a state. Same core/bond geometry, two legs per "
                + "core instead of one.",
            "tensornetwork\nmpo A B C D"),
        new Card("Young diagram", "young · rows:",
            "An integer partition as left-justified rows of unit boxes in English convention. "
                + "The positive values after <code>rows:</code> are the non-increasing row lengths, "
                + "so the partition's shape is visible directly in the baked geometry.",
            "young\nrows: 8, 6, 4, 3, 1"),
        new Card("Knot — figure-eight (4₁)", "knot · figure8",
            "A classical knot projection rendered as smooth closed strands with explicit over/under "
                + "crossing gaps. The built-in vocabulary covers the unknot, trefoil (3₁), and this "
                + "reduced alternating four-crossing figure-eight knot.",
            "knot\ntype: figure8"),
        new Card("Dynkin — B₃", "dynkin · B3",
            "A finite Dynkin diagram for semisimple Lie-algebra classification. Sirentide supports "
                + "the classical A/B/C/D families plus E6/E7/E8/F4/G2; bond multiplicity and the "
                + "arrow toward the shorter root distinguish this B₃ example from a simple chain.",
            "dynkin\ntype: B3"),
        new Card("Root system — E₈ Coxeter plane", "rootsystem · edges:minimal",
            "All 240 E₈ roots, generated by bounded Weyl-reflection closure and projected into the "
                + "deterministic exponent-1 Coxeter (Petrie) plane: eight concentric 30-point orbits. "
                + "The complete 6,720-edge minimal root-polytope graph is below the 10,000-line cap; "
                + "larger classical cases degrade all-or-none to points and rings.",
            "rootsystem\ntype: E8\nedges: minimal"),
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
    /// sequence whose 3 messages + 2 actor anchors become 5 static frames, the active step advancing.
    /// The showcase displays the first 3 message frames from that full deck. Structurally different
    /// from a Card (many frames, not one render), so it is generated on its own.
    private static final String PLAY_DSL =
        "sequence\nClient ->> Server : request\nServer ->> Server : process\n"
            + "Server -->> Client : response";

    /// A recognized-but-unsupported Mermaid construct. The ordinary render remains safely inert;
    /// `renderWithDiagnostics` supplies the author-facing reason without changing that SVG.
    private static final String DIAGNOSTIC_DSL =
        "flowchart TD\nA[Open docs] --> B[Read guide]\nclick A callback \"tooltip\"";

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
                + "Electricity,Industry,20\nSolar,Homes,10\nSolar,Industry,5", false),
        new TypePage("tensornetwork.html", "Tensor network", "Tensor-network diagram",
            "A tensor-network diagram in Penrose graphical notation — the notation of "
                + "quantum-information / DMRG / tensor-network-ML papers. <code>mps A B C D</code> lays "
                + "out a matrix-product STATE: an ordered row of tensor <code>cores</code> (discs), a "
                + "<code>bond</code> (the contracted virtual index) drawn between every adjacent pair, and "
                + "one dangling <code>physical leg</code> per core. <code>mpo A B C D</code> makes it a "
                + "matrix-product OPERATOR — each core gains a second vertical leg (one physical index "
                + "up, one down). Each core anchors as a <code>node</code> and each bond as an "
                + "<code>edge</code>, so the chain is queryable and plays through frame-by-frame like "
                + "every other type; pure line/disc geometry, no runtime dependency.",
            "tensornetwork\nmps A B C D", false),
        new TypePage("rootsystem-a2.html", "A₂ root-system Coxeter plane",
            "A₂ root-system Coxeter plane",
            "The smallest rank-two simply-laced example is a readable hexagon: six roots on one "
                + "distinct-radius guide ring and the complete six-link minimal graph. Every link "
                + "retains its semantic <code>edge</code> anchor and ≥3:1 non-text contrast.",
            "rootsystem\ntype: A2\nedges: minimal", false),
        new TypePage("rootsystem-g2.html", "G₂ root-system Coxeter plane",
            "G₂ root-system Coxeter plane",
            "The exceptional rank-two non-simply-laced example has six short and six long roots on "
                + "two distinct-radius guide rings. <code>edges:none</code> keeps the small-case "
                + "receipt focused on its two root lengths and alternating Coxeter-plane geometry.",
            "rootsystem\ntype: G2\nedges: none", false),
        new TypePage("rootsystem.html", "Root-system Coxeter plane", "E₈ root-system Coxeter plane",
            "A finite crystallographic root system. <code>type:</code> accepts the classical "
                + "<code>A&lt;n&gt;/B&lt;n&gt;/C&lt;n&gt;/D&lt;n&gt;</code> families through the "
                + "explicit rendering cap <code>n ≤ 24</code>, and "
                + "<code>E6/E7/E8/F4/G2</code>. Roots are closed under the shared Cartan matrix's "
                + "simple reflections, then projected into a deterministic Coxeter/Petrie plane. "
                + "<code>edges: minimal</code> (the default) draws the complete minimal-distance "
                + "root links when they fit the 10,000-line cap; <code>edges: none</code> "
                + "keeps just the points and distinct-radius guide rings. Separate Coxeter orbits "
                + "can share a guide radius; E8 is the exceptional clean eight-rings-of-30 case. "
                + "This complete 6,960-anchor figure is static-only because it exceeds the "
                + "512-frame play-through cap.",
            "rootsystem\ntype: E8\nedges: minimal", false));

    @Test
    void showcaseRendersEveryTypeAndFeatureAndMatchesTrackedArtifact() throws Exception {
        Set<Class<?>> shippedTypes = new LinkedHashSet<>(
            Arrays.asList(Diagram.class.getPermittedSubclasses()));
        shippedTypes.remove(Empty.class);
        Set<Class<?>> showcasedTypes = new LinkedHashSet<>();
        for (Card card : CARDS) {
            showcasedTypes.add(DslParser.parse(card.dsl()).getClass());
        }
        assertEquals(shippedTypes, showcasedTypes,
            "every shipped sealed-IR diagram type must have a showcase card");
        assertXyModeAndSignedScatterContract();

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

        // Structured diagnostics card (plan sirentide-render-diagnostics): the side-channel names
        // an unsupported construct while preserving the ordinary render's exact safe SVG.
        var diagnosticBake = Sirentide.renderWithDiagnostics(DIAGNOSTIC_DSL);
        assertEquals(Sirentide.render(DIAGNOSTIC_DSL), diagnosticBake.svg(),
            "renderWithDiagnostics must preserve render(dsl) byte-for-byte");
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, diagnosticBake.diagnostics().outcome());
        assertEquals("parse", diagnosticBake.diagnostics().stage());
        assertEquals(3, diagnosticBake.diagnostics().line());
        assertTrue(diagnosticBake.diagnostics().message().contains("click"),
            "the diagnostics demo must name its unsupported construct");
        assertEquals(Sirentide.render(""), diagnosticBake.svg(),
            "the unsupported diagnostics example must preserve the exact inert shell");
        assertFalse(diagnosticBake.diagnostics().message().isBlank(),
            "the unsupported diagnostics example must carry an author-facing message");
        assertFalse(diagnosticBake.diagnostics().detail().isBlank(),
            "the unsupported diagnostics example must carry construct-specific detail");
        assertTrue(diagnosticBake.diagnostics().detail().contains("click"),
            "the diagnostics detail must identify the unsupported click construct");
        String diagnosticReport =
            "outcome: " + diagnosticBake.diagnostics().outcome() + "\n"
                + "stage:   " + diagnosticBake.diagnostics().stage() + "\n"
                + "line:    " + diagnosticBake.diagnostics().line() + "\n"
                + "message: " + diagnosticBake.diagnostics().message() + "\n"
                + "detail:  " + diagnosticBake.diagnostics().detail();
        body.append("<section class=\"card\">\n")
            .append("  <h2>Structured diagnostics<code>renderWithDiagnostics</code></h2>\n")
            .append("  <p class=\"desc\"><code>Sirentide.renderWithDiagnostics(dsl)</code> returns "
                + "the exact SVG from <code>render(dsl)</code> plus a structured "
                + "<code>outcome</code>, <code>stage</code>, author-facing <code>message</code>, "
                + "source <code>line</code>, and lower-level <code>detail</code>. Here an unsupported "
                + "Mermaid <code>click</code> directive still fails closed to the inert SVG shell, "
                + "but the side channel says why.</p>\n")
            .append("  <div class=\"duo\"><pre>").append(escape(DIAGNOSTIC_DSL))
            .append("</pre><pre>").append(escape(diagnosticReport)).append("</pre></div>\n")
            .append("</section>\n");

        // Play-through card (plan sirentide-play-through-frames): the FIRST consumer of the semantic
        // seq anchors. `renderFrames` turns the already-assigned `data-sirentide-seq` step-ordering
        // into N STATIC SVG frames — a slideshow a doc flips through, zero JS. Frame k accents the
        // active step (thick orange arrow), shows earlier steps done/normal, and dims later ones
        // (cumulative "playing forward"). Every frame is a standalone CSP-clean bake — same alphabet,
        // no script/animation/:target — and shares the ONE layout's geometry byte-for-byte. Here: 3
        // consecutive message frames of a request/response sequence, the active step advancing.
        List<String> playFrames = Sirentide.renderFrames(PLAY_DSL);
        var diagnosedFrames = Sirentide.renderFramesWithDiagnostics(PLAY_DSL);
        assertEquals(playFrames, diagnosedFrames.frames(),
            "renderFramesWithDiagnostics must preserve every frame byte-for-byte");
        assertEquals(Outcome.OK, diagnosedFrames.diagnostics().outcome());
        assertEquals(EXPECTED_PLAY_FRAMES, playFrames.size(),
            "three message groups plus two actor anchors must produce exactly five frames");
        assertEquals(3, DISPLAYED_PLAY_FRAMES,
            "the showcase must display exactly the first three message frames");
        String staticPlayGeometry = stripPresentation(Sirentide.render(PLAY_DSL));
        for (int i = 0; i < DISPLAYED_PLAY_FRAMES; i++) {
            assertEquals(1, anchoredGroupsContaining(playFrames.get(i), PLAY_ACCENT),
                "displayed frame " + i + " must accent exactly one active semantic group");
            assertTrue(groupBySeq(playFrames.get(i), i).contains(PLAY_ACCENT),
                "displayed frame " + i + " must advance the accent to seq " + i);
            assertEquals(staticPlayGeometry, stripPresentation(playFrames.get(i)),
                "displayed frame " + i + " must preserve the static render's geometry");
            if (i > 0) {
                assertFalse(playFrames.get(i - 1).equals(playFrames.get(i)),
                    "displayed play-through frames must progress to a different active step");
            }
        }
        body.append("<section class=\"card\">\n")
            .append("  <h2>Play-through frames<code>renderFrames · renderFramesWithDiagnostics</code></h2>\n")
            .append("  <p class=\"desc\">The <em>flow you play</em>: the semantic <code>data-sirentide-seq"
                + "</code> step-ordering baked into every diagram is now <em>consumed</em> — "
                + "<code>Sirentide.renderFrames(dsl)</code> returns one static SVG per step, each "
                + "accenting the active step (thick accent arrow), showing earlier steps done and "
                + "dimming later ones. No new syntax, no runtime JS, same "
                + "<code>svg/path/rect/line</code> alphabet — a slideshow a doc flips through. "
                + "<code>renderFramesWithDiagnostics(dsl)</code> returns those same frames "
                + "byte-for-byte plus the structured diagnostic side channel. Three "
                + "consecutive frames below, the active message advancing.</p>\n")
            .append("  <pre>").append(escape(PLAY_DSL)).append("</pre>\n")
            .append("  <div class=\"frames\">\n");
        for (int i = 0; i < DISPLAYED_PLAY_FRAMES; i++) {
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

        String generatedShowcase = page(body.toString());
        assertEquals(DISPLAYED_PLAY_FRAMES, count(generatedShowcase, "<div class=\"frame\">"),
            "generated showcase must contain exactly the checked first three message frames");
        assertStrictHtmlComments(generatedShowcase);
        Path trackedShowcase = Path.of("examples", "showcase.html").toAbsolutePath();
        if (UPDATE) {
            Files.writeString(trackedShowcase, generatedShowcase);
        }
        assertEquals(generatedShowcase, Files.readString(trackedShowcase),
            "tracked showcase drifted from ShowcaseGenTest; regenerate with "
                + "-Dsirentide.updateShowcase=true and commit the deliberate artifact change");
    }

    /// Showcase-specific XyChart contract. The broad sealed-type census sees all three modes as the
    /// same class, so these assertions pin the visible examples to bars/line/scatter semantics rather
    /// than merely proving that some XyChart card exists.
    private static void assertXyModeAndSignedScatterContract() {
        XyChart bars = (XyChart) DslParser.parse(BARS_DSL);
        assertEquals("bars", bars.mode(),
            "the explicit bar card must exercise default mode selection");
        String barsSvg = Sirentide.render(BARS_DSL);
        assertEquals(3, count(barsSvg, "<g data-sirentide-role=\"bar\""),
            "the three-category default-bars card must emit exactly three semantic bar marks");
        assertEquals(3, count(barsSvg, "<rect"),
            "the default-bars card must emit exactly one rectangle per bar mark");
        assertEquals("line", ((XyChart) DslParser.parse(LINE_DSL)).mode(),
            "the line card must exercise line mode");
        XyChart scatter = (XyChart) DslParser.parse(SCATTER_DSL);
        assertEquals("scatter", scatter.mode(), "the scatter card must exercise scatter mode");

        String scatterSvg = Sirentide.render(SCATTER_DSL);
        String lineTwin = Sirentide.render(SCATTER_DSL.replaceFirst("xychart scatter", "xychart line"));
        assertEquals(8, count(lineTwin, "<line") - count(scatterSvg, "<line"),
            "five categories across two complete series add eight connectors only in line mode");
        assertEquals(0, count(scatterSvg, "stroke-width=\"1.5\""),
            "scatter must emit zero series connector marks");
        assertEquals(8, count(lineTwin, "stroke-width=\"1.5\""),
            "the line twin must emit exactly eight series connector marks");

        // The current line/scatter contract deliberately keeps the full x-axis at plot-bottom. Its
        // signed y-scale still emits a zero TICK. Read the +4/-2 point centres from their emitted
        // full-circle paths, interpolate zero, then require an emitted horizontal tick at that y and
        // the negative point lower in SVG's y-down coordinate space.
        double positiveY = emittedPointY(scatterSvg, "Mon");
        double negativeY = emittedPointY(scatterSvg, "Mon-1");
        double zeroTickY = negativeY + (positiveY - negativeY) / 3.0;
        assertTrue(negativeY > zeroTickY,
            "the signed scatter's negative point must sit below the zero-tick projection");
        assertTrue(hasEmittedHorizontalTick(scatterSvg, zeroTickY),
            "the interpolated zero projection must be present as the emitted four-pixel y-axis tick");
    }

    private static double emittedPointY(String svg, String id) {
        String group = groupById(svg, id);
        int move = group.indexOf("<path d=\"M ");
        int arc = group.indexOf(" A ", move);
        assertTrue(move >= 0 && arc > move, "point group " + id + " must emit a full-circle path");
        String[] coordinates = group.substring(move + "<path d=\"M ".length(), arc).split("\\s+");
        assertEquals(2, coordinates.length, "point path must begin with emitted x/y coordinates");
        return Double.parseDouble(coordinates[1]);
    }

    private static boolean hasEmittedHorizontalTick(String svg, double expectedY) {
        int cursor = 0;
        while (true) {
            int open = svg.indexOf("<line ", cursor);
            if (open < 0) {
                return false;
            }
            int close = svg.indexOf("/>", open);
            assertTrue(close > open, "emitted line must close");
            String line = svg.substring(open, close);
            double x1 = numericAttribute(line, "x1");
            double x2 = numericAttribute(line, "x2");
            double y1 = numericAttribute(line, "y1");
            double y2 = numericAttribute(line, "y2");
            if (Math.abs(y1 - expectedY) < 0.002 && Math.abs(y2 - expectedY) < 0.002
                && Math.abs(Math.abs(x2 - x1) - 4.0) < 0.002) {
                return true;
            }
            cursor = close + 2;
        }
    }

    private static double numericAttribute(String tag, String name) {
        String prefix = name + "=\"";
        int start = tag.indexOf(prefix);
        assertTrue(start >= 0, "emitted tag must carry " + name + ": " + tag);
        start += prefix.length();
        int end = tag.indexOf('"', start);
        assertTrue(end > start, "emitted " + name + " must have a numeric value");
        return Double.parseDouble(tag.substring(start, end));
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            total++;
        }
        return total;
    }

    private static int anchoredGroupsContaining(String svg, String token) {
        int total = 0;
        int cursor = 0;
        while (true) {
            int open = svg.indexOf("<g data-sirentide-role=", cursor);
            if (open < 0) {
                return total;
            }
            int close = svg.indexOf("</g>", open);
            assertTrue(close > open, "semantic group must close");
            if (svg.substring(open, close).contains(token)) {
                total++;
            }
            cursor = close + 4;
        }
    }

    private static String groupBySeq(String svg, int seq) {
        int tag = svg.indexOf("data-sirentide-seq=\"" + seq + "\"");
        assertTrue(tag >= 0, "semantic group for seq " + seq + " must be present");
        int open = svg.lastIndexOf("<g data-sirentide-role=", tag);
        int close = svg.indexOf("</g>", tag);
        assertTrue(open >= 0 && close > open, "semantic group for seq " + seq + " must close");
        return svg.substring(open, close + 4);
    }

    private static String groupById(String svg, String id) {
        int tag = svg.indexOf("data-sirentide-id=\"" + id + "\"");
        assertTrue(tag >= 0, "semantic group for id " + id + " must be present");
        int open = svg.lastIndexOf("<g data-sirentide-role=", tag);
        int close = svg.indexOf("</g>", tag);
        assertTrue(open >= 0 && close > open, "semantic group for id " + id + " must close");
        return svg.substring(open, close + 4);
    }

    private static String stripPresentation(String svg) {
        return svg
            .replaceAll(" fill=\"[^\"]*\"", "")
            .replaceAll(" stroke-width=\"[^\"]*\"", "")
            .replaceAll(" stroke=\"[^\"]*\"", "");
    }

    private static void assertStrictHtmlComments(String html) {
        int cursor = 0;
        while (true) {
            int open = html.indexOf("<!--", cursor);
            if (open < 0) {
                assertTrue(html.indexOf("-->", cursor) < 0, "generated HTML has an orphan comment close");
                return;
            }
            assertTrue(html.indexOf("-->", cursor) < 0 || html.indexOf("-->", cursor) >= open,
                "generated HTML has a comment close before its next open");
            int close = html.indexOf("-->", open + 4);
            assertTrue(close >= 0, "generated HTML comment must close");
            assertFalse(html.substring(open + 4, close).contains("--"),
                "HTML comment bodies must not contain an internal double hyphen");
            cursor = close + 3;
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
              .duo pre { flex: 0 1 380px; margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
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
            <p class="note">All twenty-three types on one page: <a href="showcase.html">showcase.html</a> · browser-audited renders: <a href="gallery/GALLERY.md">gallery</a></p>
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
                 Regen: run ShowcaseGenTest with -Dsirentide.updateShowcase=true; exact command is in test source. -->
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
            .duo pre { flex: 0 1 380px; margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
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
              <p class="sub">Twenty-three diagram types · pure-Java bake · inert <code>svg/path/rect/line</code> output · every label a real glyph path · real LaTeX in any label.<br>
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
