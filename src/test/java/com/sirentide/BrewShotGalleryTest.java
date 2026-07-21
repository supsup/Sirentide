package com.sirentide;

import com.brewshot.BrewShot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real-browser "eyes" for Sirentide (BrewShot, PROJECT/brewshot). The byte-pinned
 * {@code .svg} goldens pin the EMITTER output; this pins what XML cannot see — the
 * RENDERED geometry in a live browser.
 *
 * <p>THE AUDIT: every drawn {@code <path>}/{@code <rect>}/{@code <line>} must have a
 * finite bounding box that sits INSIDE the root {@code <svg>}'s box. A label pushed
 * off-canvas (a negative-x category label, an over-max axis tick, a de-collision row
 * stacked below the viewport) has a client bbox outside the svg box and fails here —
 * mechanically, the exact class of visual bug (negative-x clipped labels, y=226
 * stacking) that the deep review (sirentide/14) could only find by eye.
 *
 * <p>THE SCREENSHOTS: a reference PNG per diagram type written under
 * {@code examples/gallery/}, linked from {@code examples/gallery/GALLERY.md}. References,
 * not byte-goldens — regenerate freely. Skips clean (never fails) when no local Chrome.
 */
class BrewShotGalleryTest {

    /** JS audit: collect every drawn element whose client bbox escapes the root svg box. */
    private static final String CONTAINMENT_AUDIT = """
        (function () {
          var bad = [];
          var svg = document.querySelector('svg');
          if (!svg) { return ['no svg element']; }
          var s = svg.getBoundingClientRect();
          if (!s.width || !s.height) { return ['zero-size svg']; }
          var tol = 1.5; // sub-pixel antialias tolerance
          svg.querySelectorAll('path, rect, line').forEach(function (el, i) {
            var r = el.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) { return; } // degenerate/hidden
            if (!isFinite(r.left) || !isFinite(r.top) || !isFinite(r.right) || !isFinite(r.bottom)) {
              bad.push(el.tagName + '#' + i + ' non-finite bbox'); return;
            }
            if (r.left < s.left - tol || r.top < s.top - tol
                || r.right > s.right + tol || r.bottom > s.bottom + tol) {
              bad.push(el.tagName + '#' + i + ' escapes canvas ['
                + Math.round(r.left - s.left) + ',' + Math.round(r.top - s.top) + ','
                + Math.round(r.right - s.left) + ',' + Math.round(r.bottom - s.top)
                + '] vs [0,0,' + Math.round(s.width) + ',' + Math.round(s.height) + ']');
            }
          });
          return bad;
        })()
        """;

    /// A `renderer` of {@code null} bakes with the default (null) math renderer; a non-null one (the
    /// real {@link LatteXMathFragmentRenderer}) bakes the `$…$` math for real — the moat cases.
    private record Case(String name, String title, String dsl, MathFragmentRenderer renderer) {
        Case(String name, String title, String dsl) { this(name, title, dsl, null); }
    }

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    /// A class with more members than the display cap, so the box shows MAX_DISPLAYED_ROWS-1 rows
    /// plus a synthesized "… (N more)" row instead of an unreadable, canvas-blowing tower
    /// (robustness plan fe8c5bbc #2). The BrewShot containment audit proves the capped box + the
    /// "… more" row all stay inside the declared canvas.
    private static String cappedMemberClass() {
        StringBuilder s = new StringBuilder("classDiagram\nclass BigConfig {\n");
        for (int i = 0; i < 45; i++) {
            s.append("+flag").append(i).append(" bool\n");
        }
        return s.append("}\n").toString();
    }

    private static final List<Case> GALLERY = List.of(
        new Case("class-member-cap", "Class member-row cap (… N more)", cappedMemberClass()),
        new Case("pie", "Pie", "pie\n\"Reviews\" : 40\n\"Builds\" : 25\n\"Docs\" : 20\n\"Design\" : 15"),
        new Case("pie-legend", "Pie with a legend",
            "pie legend\n\"Reviews\" : 40\n\"Builds\" : 25\n\"Docs\" : 20\n\"Design\" : 15"),
        new Case("pie-custom", "Custom per-item colours",
            "pie legend\n\"Ship\" : 40 #22c55e\n\"WIP\" : 25 #eab308\n\"Blocked\" : 20 #ef4444\n\"Backlog\" : 15 #64748b"),
        new Case("xychart", "Bar chart (signed axis)",
            "xychart\n\"Mon\" : 5\n\"Tue\" : -3\n\"Wed\" : 8\n\"Thu\" : 6"),
        new Case("xychart-line", "Line chart (two series + legend)",
            "xychart line legend\nseries: Revenue, Cost\n\"Q1\" : 5 8\n\"Q2\" : 8 4\n"
                + "\"Q3\" : 3 6\n\"Q4\" : 9 2"),
        new Case("timeline", "Timeline (proportional)",
            "timeline\n\"Founded\" : 2000\n\"Series A\" : 2005\n\"Launch\" : 2020"),
        new Case("gantt", "Gantt",
            "gantt\n\"Design\" : 0-3\n\"Build\" : 3-8\n\"Test\" : 7-11\n\"Ship\" : 11-13"),
        new Case("flowchart", "Flowchart (layered, custom node colour)",
            "flowchart\nA[Open PR] --> B{Approve?}\nB -->|yes| C[Merge] #22c55e"
                + "\nB -->|no| D[Revise]\nD -->|re-review| B"),
        new Case("flowchart-shapes", "Flowchart node shapes (stadium · circle · hexagon · cylinder · subroutine)",
            "flowchart TD\nA[Process] --> B(Rounded)\nB --> C([Stadium])\nC --> D((Go))\n"
                + "D --> E{{Prepare}}\nE --> F[(Store)]\nF --> G[[Validate]]\nG --> H{OK?}"),
        new Case("flowchart-edges", "Flowchart edge types (open · dotted · thick)",
            "flowchart TD\nA[Solid] --> B[Open]\nB --- C[Dotted]\nC -.-> D[DotOpen]\n"
                + "D -.- E[Thick]\nE ==> F[ThickOpen]\nF === G[End]"),
        new Case("flowchart-subgraph", "Flowchart (nested subgraph clusters)",
            "flowchart TD\nA[Start] --> B[Work]\nsubgraph outer [Build Pipeline]\nB --> C[Compile]"
                + "\nsubgraph inner [Test Suite]\nC --> D[Unit]\nD --> F[Integration]\nend"
                + "\nF --> G[Package]\nend\nG --> E[Ship]"),
        new Case("flowchart-classdef", "Flowchart semantic colour classes (classDef · class)",
            "flowchart LR\nclassDef deny fill:#fecaca\nclassDef ok fill:#bbf7d0\n"
                + "A[Request] --> B{Authorized?}\nB -->|yes| C[Serve]\nB -->|no| D[Deny]\n"
                + "class C ok\nclass D deny"),
        new Case("flowchart-caption", "Caption / note directive (annotation band below any diagram)",
            "%% caption: A merge lands only after both peers approve and no conflicts remain.\n"
                + "flowchart LR\nA[Author] --> B[Review]\nB --> C[Merge]"),
        new Case("flowchart-edge-to-subgraph", "Edge to a subgraph id (routes into the cluster, no phantom node)",
            "flowchart TD\nEPR[Scaffold] --> PROJ\nsubgraph PROJ [Project]\n"
                + "PP[Package] --> QQ[Queue]\nend"),
        new Case("flowchart-config-direction", "Config direction directive (%% direction: LR drives a bare header)",
            "%% direction: LR\nflowchart\nA[Parse] --> B[Layout] --> C[Emit]"),
        new Case("sequence", "Sequence (API token flow)",
            "sequence\nClient ->> Gateway : GET /token\nGateway ->> Auth : validate"
                + "\nAuth -->> Gateway : ok\nGateway ->> Gateway : sign JWT"
                + "\nGateway -->> Client : 200 token"),
        new Case("sequence-blocks", "Sequence (alt / loop / par frames)",
            "sequence\nAlice ->> Bob : hello\nalt is available\nBob -->> Alice : yes"
                + "\nloop every retry\nAlice ->> Bob : ping\nend\nelse is busy"
                + "\nBob -->> Alice : later\nend\npar to Bob\nAlice ->> Bob : a"
                + "\nand to Carol\nAlice ->> Carol : b\nend"),
        new Case("state", "State diagram (lifecycle)",
            "state\n[*] --> Idle\nIdle --> Running : start\nRunning --> Idle : stop\nRunning --> [*]"),
        new Case("quadrant", "Quadrant chart (2×2 prioritization matrix)",
            "quadrant\nx-axis \"Low Reach\" --> \"High Reach\"\ny-axis \"Low Impact\" --> \"High Impact\""
                + "\nquadrant-1 \"Major project\"\nquadrant-2 \"Quick win\"\nquadrant-3 \"Deprioritize\""
                + "\nquadrant-4 \"Fill-in\"\n\"Feature A\" : [0.3, 0.6]\n\"Feature B\" : [0.75, 0.8]"
                + "\n\"Feature C\" : [0.5, 0.2]\n\"Feature D\" : [0.85, 0.35]"),
        new Case("classDiagram", "Class diagram (UML — all five relationship markers)",
            "classDiagram\nclass Animal {\n+String name\n+int age\n+eat() void\n+sleep()\n}\n"
                + "class Dog {\n+bark() void\n}\nAnimal <|-- Dog : inherits\n"
                + "Animal *-- Collar : composition\nAnimal o-- Owner : aggregation\n"
                + "Dog --> Bone : association\nDog ..> Vet : dependency"),
        new Case("erDiagram", "ER diagram (crow-foot cardinalities)",
            "erDiagram\nCUSTOMER ||--o{ ORDER : places\nORDER ||--|{ LINE-ITEM : contains\n"
                + "CUSTOMER }o--o| ADDRESS : has\nCUSTOMER {\nstring name PK\nstring email\nint age\n}\n"
                + "ORDER {\nint id PK\ndate created\n}"),
        new Case("gitGraph", "Git graph (branch lanes + merge)",
            "gitGraph\ncommit\ncommit id: \"init\"\nbranch develop\ncheckout develop\ncommit\n"
                + "commit id: \"feature\"\ncheckout main\nmerge develop\ncommit id: \"release\""),
        new Case("journey", "User journey (satisfaction map)",
            "journey\ntitle My working day\nsection Go to work\nMake tea: 5: Me\n"
                + "Commute: 3: Me, Cat\nArrive: 4: Me\nsection Do work\nCode: 5: Me\n"
                + "Meetings: 2: Me, Boss\nLunch: 4: Me, Team"),
        new Case("mindmap", "Mind map (indentation-defined tree)",
            "mindmap\n  root Root idea\n    Origins\n      Long history\n      Popular\n"
                + "    Research\n      On effect\n    Tools\n      Pen and paper\n      Mermaid"),
        new Case("matrix", "Comparison / verdict matrix (rows × columns × verdict palette)",
            "matrix\ncols: snapshot, bare\n\"ID1 claim-on-no-signal\" : match, match\n"
                + "\"PC2 peer-over-flagship\" : match, match\n\"PC1 soft-intent threshold\" : partial, diverge\n"
                + "\"PC5 boundary-holds-vs-Charles\" : match, diverge"),
        new Case("sankey", "Sankey (weighted flows in depth columns)",
            "sankey\nCoal,Electricity,25\nGas,Electricity,15\nElectricity,Homes,20\n"
                + "Electricity,Industry,20\nSolar,Homes,10\nSolar,Industry,5"),
        // Continued-fraction snake graphs (canonical Çanakçı–Schiffler square snakes, plan
        // sirentide-snake-graph-primitive). Bare tinted tile strips — NO labels (review sir344); the
        // per-run tint delineates the segments, the CF lives in the a11y desc. The containment audit
        // proves the whole strip stays inside its grow-to-fit canvas.
        new Case("snake-phi", "Snake graph — φ = [1,1,1,1,1] (a straight strip)",
            "snake\ncf: 1, 1, 1, 1, 1\n"),
        new Case("snake-sqrt2", "Snake graph — √2 = [1,2,2,2] (the golden)",
            "snake\ncf: 1, 2, 2, 2\n"),
        new Case("snake-e", "Snake graph — e-start = [2,1,2,1,1,4]",
            "snake\ncf: 2, 1, 2, 1, 1, 4\n"),
        // Classical knot diagrams (plan sirentide-knot-diagram-primitive): one closed smooth curve per
        // knot with over/under crossings (the under strand broken by a gap). All three are alternating;
        // their emitted geometry reconstructs the known Gauss code (KnotGaussCodeOracleTest). The
        // containment audit proves every strand arc stays inside its grow-to-fit canvas.
        new Case("knot-unknot", "Knot — unknot (trivial closed loop, 0 crossings)",
            "knot\ntype: unknot"),
        new Case("knot-trefoil", "Knot — trefoil (3₁, 3 crossings, alternating)",
            "knot\ntype: trefoil"),
        new Case("knot-figure8", "Knot — figure-eight (4₁, 4 crossings, alternating)",
            "knot\ntype: figure8"),
        new Case("tensornetwork", "Tensor network (MPS chain — cores, bonds, physical legs)",
            "tensornetwork\nmps A B C D"),
        new Case("tensornetwork-mpo", "Tensor network (MPO — second operator leg per core)",
            "tensornetwork\nmpo A B C D"),
        // GEOMETRY-ESCAPE repros (Lattice's Sirentide review): each once drew a label OUTSIDE the
        // declared canvas — now contained by ellipsize-to-room + an in-frame clamp.
        new Case("pie-thin-labels", "Pie thin-slice outside labels (clipped)",
            "pie\n\"quarter\" : 25\n\"right outside label that should clip\" : 1\n\"rest\" : 74"),
        new Case("timeline-endpoints", "Timeline endpoint labels (clamped)",
            "timeline\n\"very long left endpoint label\" : 0\n\"very long right endpoint label\" : 10"),
        new Case("flowchart-left-label", "Flowchart left-going edge label (clamped)",
            "flowchart\nA --> C\nB -->|this forward label can escape left| C"),
        // SELF-LOOP geometry repros (Lattice re-review, sirentide seq 217): a long loop label once
        // escaped the viewBox and ran through the neighbor box, stacked self-relations overpainted
        // one geometry, and the class marker ignored its authored operand. Now the row cursor
        // reserves the whole loop lane, lanes nest, and the marker follows markerAtLeft().
        new Case("class-self-loop", "Class self-relation (long label, reserved lane)",
            "classDiagram\nclass Node\nNode --> Node : recursive relationship with retry and backoff"),
        new Case("class-self-loops-stacked", "Stacked class self-relations (lanes + marker sides + neighbor)",
            "classDiagram\nclass A\nclass B\nA <|-- A : refines itself\nA --> A : delegates\nA --> B"),
        new Case("er-self-loop", "ER self-relation (crow-foot both ends + neighbor)",
            "erDiagram\nEMPLOYEE ||--o{ EMPLOYEE : manages\nEMPLOYEE ||--|| DESK : uses"),
        new Case("class-self-loops-three", "Three self-relation lanes (box grows; no collinear legs)",
            "classDiagram\nclass A\nA --> A : first\nA --> A : second\nA --> A : third"),
        // sirentide 275: stack SAME-SIDE markers whose footprint exceeds the old 12px pitch (inheritance
        // triangle 16, composition diamond 14, ER crow-foot 18) so the capture actually exercises marker
        // disjointness — the three-arrow case above uses 10px arrows that fit the old pitch and hid it.
        new Case("class-self-loops-marker-stack", "Stacked over-footprint markers (triangles + diamond)",
            "classDiagram\nclass A\nA <|-- A : inherits\nA <|-- A : also\nA *-- A : owns"),
        new Case("er-self-loops-stacked", "Stacked ER self-relations (crow-feet + bars, same side)",
            "erDiagram\nA ||--o{ A : first\nA ||--o{ A : second"),
        // THE MOAT — real baked LaTeX (via the injected LatteX renderer), audited to stay in-canvas.
        new Case("mathblock", "Display math (standalone, baked LaTeX)",
            "mathblock\n\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", REAL),
        new Case("math-in-labels", "Math baked inside flowchart labels",
            "flowchart TD\nA[Energy $E=mc^2$] --> B[$\\frac{v^2}{r}$]", REAL));

    private static Path galleryDir() {
        return Path.of("examples", "gallery").toAbsolutePath();
    }

    @Test
    void everyDiagramStaysInsideItsCanvasAndWritesAReferenceScreenshot() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping the browser eyes");
        Path dir = galleryDir();
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder("# Sirentide gallery — real-browser renders\n\n"
            + "Captured by [BrewShot](https://github.com/supsup/BrewShot) in the test suite. "
            + "Each diagram is audited so no drawn element "
            + "escapes its canvas (the visual class the byte-pinned SVG goldens can't see).\n\n");

        try (BrewShot shot = BrewShot.launch(520, 320)) {
            for (Case c : GALLERY) {
                String svg = c.renderer() == null
                    ? Sirentide.render(c.dsl())
                    : Sirentide.render(c.dsl(), c.renderer());
                shot.html("<!doctype html><html><body style=\"margin:20px;background:#fff\">"
                    + svg + "</body></html>");
                shot.settle(120);

                @SuppressWarnings("unchecked")
                List<Object> escapes = (List<Object>) shot.eval(CONTAINMENT_AUDIT);
                assertEquals(List.of(), escapes,
                    c.name() + ": drawn elements must stay inside the canvas — " + escapes);

                // Crisp, tightly-cropped capture (BrewShot 0.6.0): the svg element re-rastered
                // at 3x device pixels with a 16px breathing frame — the two-line swap the CSS-
                // transform workaround (sirentide #119) was HELD for (#120/#121: native beats
                // hand-rolled; brewshot #21/#22). clip.scale re-renders vectors, not upscales.
                Path png = dir.resolve(c.name() + ".png");
                Files.write(png, shot.screenshotElement("svg", 3.0, 16));
                md.append("## ").append(c.title()).append("\n\n```\n").append(c.dsl())
                    .append("\n```\n\n![").append(c.title()).append("](").append(c.name()).append(".png)\n\n");
            }
        }
        Files.writeString(dir.resolve("GALLERY.md"), md.toString());
    }

    @Test
    void aWideSpanSequenceMessageLabelIsWidthCapped() throws Exception {
        // Robustness plan fe8c5bbc #3: a message label ellipsized to the SPAN between its two actors,
        // so a message across DISTANT actors (a wide span) admitted a full-length, enormously wide
        // label run. The hard cap min(span-8, MAX_MSG_LABEL_W) truncates it. Measured in a real
        // browser: the label is ONE glyph <path>, so its bbox width is the label width — with the cap
        // the widest glyph path stays bounded; without it, the A→E label would be many times wider.
        assumeTrue(BrewShot.available(), "no local Chrome; skipping the browser eyes");
        String longLabel = "this is a deliberately very long message label that without the hard "
            + "width cap would render as an enormous run spanning the whole distance between the "
            + "far-apart actors A and E and blow well past any readable width";
        String dsl = "sequence\nA ->> B : x\nB ->> C : x\nC ->> D : x\nD ->> E : x\nA ->> E : " + longLabel;
        try (BrewShot shot = BrewShot.launch(1600, 400)) {
            shot.html("<!doctype html><html><body style=\"margin:20px;background:#fff\">"
                + Sirentide.render(dsl) + "</body></html>");
            shot.settle(120);
            Object maxW = shot.eval("(function(){var m=0;document.querySelectorAll('svg path')"
                + ".forEach(function(p){var w=p.getBoundingClientRect().width;if(isFinite(w)&&w>m)m=w;});"
                + "return m;})()");
            double widest = ((Number) maxW).doubleValue();
            assertTrue(widest < 300,
                "the wide-span message label is width-capped; widest glyph path=" + widest);
        }
    }
}
