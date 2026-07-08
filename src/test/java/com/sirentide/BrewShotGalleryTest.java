package com.sirentide;

import com.brewshot.BrewShot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sirentide.api.Sirentide;
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

    private record Case(String name, String title, String dsl) {}

    private static final List<Case> GALLERY = List.of(
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
        new Case("flowchart-subgraph", "Flowchart (nested subgraph clusters)",
            "flowchart TD\nA[Start] --> B[Work]\nsubgraph outer [Build Pipeline]\nB --> C[Compile]"
                + "\nsubgraph inner [Test Suite]\nC --> D[Unit]\nD --> F[Integration]\nend"
                + "\nF --> G[Package]\nend\nG --> E[Ship]"),
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
        // GEOMETRY-ESCAPE repros (Lattice's Sirentide review): each once drew a label OUTSIDE the
        // declared canvas — now contained by ellipsize-to-room + an in-frame clamp.
        new Case("pie-thin-labels", "Pie thin-slice outside labels (clipped)",
            "pie\n\"quarter\" : 25\n\"right outside label that should clip\" : 1\n\"rest\" : 74"),
        new Case("timeline-endpoints", "Timeline endpoint labels (clamped)",
            "timeline\n\"very long left endpoint label\" : 0\n\"very long right endpoint label\" : 10"),
        new Case("flowchart-left-label", "Flowchart left-going edge label (clamped)",
            "flowchart\nA --> C\nB -->|this forward label can escape left| C"));

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
                String svg = Sirentide.render(c.dsl());
                shot.html("<!doctype html><html><body style=\"margin:20px;background:#fff\">"
                    + svg + "</body></html>");
                shot.settle(120);

                @SuppressWarnings("unchecked")
                List<Object> escapes = (List<Object>) shot.eval(CONTAINMENT_AUDIT);
                assertEquals(List.of(), escapes,
                    c.name() + ": drawn elements must stay inside the canvas — " + escapes);

                Path png = dir.resolve(c.name() + ".png");
                shot.screenshot(png);
                md.append("## ").append(c.title()).append("\n\n```\n").append(c.dsl())
                    .append("\n```\n\n![").append(c.title()).append("](").append(c.name()).append(".png)\n\n");
            }
        }
        Files.writeString(dir.resolve("GALLERY.md"), md.toString());
    }
}
