package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sirentide.api.Sirentide;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Golden-SVG regression guard: renders a FIXED DSL per diagram type and asserts the emitted SVG
/// is BYTE-IDENTICAL to a committed golden under `src/test/resources/golden/`. This converts every
/// layout-arithmetic bug from silent (wrong geometry, no alarm) to loud (a diff on the golden).
///
/// Regen mechanism: run with `-Dsirentide.updateGolden=true` to REWRITE the goldens from current
/// output instead of asserting, e.g.
///   ./gradlew test --tests com.sirentide.GoldenSvgTest -Dsirentide.updateGolden=true
/// Review the resulting diff before committing — an unexpected golden change is the signal.
class GoldenSvgTest {

    private static final boolean UPDATE = Boolean.getBoolean("sirentide.updateGolden");

    /// Fixed inputs — one per diagram type. Deterministic (finite, positive) so the golden is
    /// stable across runs and machines.
    private static final Map<String, String> FIXTURES = new LinkedHashMap<>();

    static {
        FIXTURES.put("pie",
            "pie\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 30\n");
        FIXTURES.put("pie-legend",
            "pie legend\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 20\n  \"Ops\" : 10\n");
        FIXTURES.put("xychart",
            "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n");
        FIXTURES.put("timeline",
            "timeline\n  \"Founded\" : 2020\n  \"Series A\" : 2021\n  \"Launch\" : 2023\n");
        FIXTURES.put("gantt",
            "gantt\n  \"Design\" : 0-3\n  \"Build\" : 3-8\n  \"Test\" : 7-10\n");
        // A TD decision flow: a diamond ({Ready?}), labeled edges (yes/no), a CHAINED hop
        // (C[Build] --> D[Ship], E[Fix] --> A), and a CYCLE (E→A→…→E). Pins the parser-hardening
        // behaviour (operator-scan split + per-hop labels + chain expansion) byte-for-byte.
        FIXTURES.put("flowchart",
            "flowchart TD\n  A[Start] --> B{Ready?}\n  B -->|yes| C[Build] --> D[Ship]\n"
                + "  B -->|no| E[Fix] --> A\n");
        // A flowchart exercising ALL the node SHAPES in one chain: a rect (byte-identical default), a
        // rounded box `(…)`, a stadium `([…])`, a circle `((…))`, a hexagon `{{…}}`, a cylinder/database
        // `[(…)]`, a subroutine `[[…]]`, and a diamond `{…}`. Pins each shape's path/line geometry AND
        // the longest-delimiter-first delimiter mapping byte-for-byte (the plain `[Rect]`/`{Decision?}`
        // must stay identical to the flowchart golden's rect/diamond).
        FIXTURES.put("flowchart-shapes",
            "flowchart TD\n  A[Rect] --> B(Rounded)\n  B --> C([Stadium])\n  C --> D((Circle))\n"
                + "  D --> E{{Hexagon}}\n  E --> F[(Database)]\n  F --> G[[Subroutine]]\n"
                + "  G --> H{Decision?}\n");
        // A flowchart exercising ALL 5 edge VARIANTS in one chart (plan flowchart-edge-types): an open
        // link `---` (line, no head), a dotted arrow `-.->`, a dotted open `-.-`, a thick arrow `==>`, a
        // thick open `===`, PLUS a LABELED dotted (`-.->|maybe|`) and a LABELED thick (`==>|force|`).
        // Pins the per-style emission byte-for-byte: dotted = deterministic short segments, thick =
        // wider stroke-width, open = the arrowhead <path> omitted, labels drawn on the variant edges.
        FIXTURES.put("flowchart-edges",
            "flowchart TD\n  A[Start] --- B[Link]\n  B -.-> C[Retry]\n  C -.- D[Idle]\n"
                + "  D ==> E[Ship]\n  E === F[Done]\n  A -.->|maybe| D\n  C ==>|force| F\n");
        // A flowchart with a subgraph AND a NESTED subgraph: `outer` wraps C/D/F/G, `inner` (nested)
        // wraps D/F. Pins the cluster frame border geometry (four lines), the title band + glyph tab,
        // the nesting inset (inner padding tightens by depth), and the canvas grow-to-fit byte-for-byte.
        FIXTURES.put("flowchart-subgraph",
            "flowchart TD\n  A[Start] --> B[Work]\n  subgraph outer [Outer]\n    B --> C[Compile]\n"
                + "    subgraph inner [Inner]\n      C --> D[Test]\n      D --> F[Lint]\n    end\n"
                + "    F --> G[Package]\n  end\n  G --> E[Ship]\n");
        // A 3-actor sequence with ACTIVATION bars (M2): Client→Gateway opens Gateway's bar,
        // Gateway→Auth opens Auth's, Auth-->>Gateway closes Auth's, a NESTED self-call on Gateway
        // stacks an offset bar, and the final reply closes it — pinning the activation-rect geometry
        // (start/end y, nesting x-offset, unbalanced close) byte-for-byte.
        FIXTURES.put("sequence",
            "sequence\n  Client ->> Gateway : GET /token\n  Gateway ->> Auth : validate\n"
                + "  Auth -->> Gateway : ok\n  Gateway ->> Gateway : sign JWT\n"
                + "  Gateway -->> Client : 200 token\n");
        // A sequence with alt/loop/par FRAME blocks (M2): an `alt`+`else` whose "available" branch
        // NESTS a `loop`, then a `par`+`and` across a third actor — pins the frame border geometry,
        // the label tabs, the dashed dividers, and the nesting inset byte-for-byte.
        FIXTURES.put("sequence-blocks",
            "sequence\n  Alice ->> Bob : hello\n  alt is available\n    Bob -->> Alice : yes\n"
                + "    loop every retry\n      Alice ->> Bob : ping\n    end\n"
                + "  else is busy\n    Bob -->> Alice : later\n  end\n"
                + "  par to Bob\n    Alice ->> Bob : a\n  and to Carol\n    Alice ->> Carol : b\n  end\n");
        // A sequence with NOTES + CREATE/DESTROY (M2 enrichment): a `note right of` a single actor, a
        // `note over A,B` spanning two, a `create participant` starting a lifeline MID-DIAGRAM, and a
        // `destroy` ending one with an X mark (plus a message FROM the destroyed actor — the documented
        // draw-anyway degrade). Pins the note-box geometry (bordered box + centred glyph text), the
        // injected note bands, the created head/lifeline start y, and the destroy-X byte-for-byte.
        FIXTURES.put("sequence-notes",
            "sequence\n  Alice ->> Bob : hello\n  note right of Bob : Bob thinks\n"
                + "  note over Alice,Bob : a shared note\n  create participant Carol\n"
                + "  Bob ->> Carol : spawn\n  destroy Carol\n  Carol -->> Bob : done\n");
        // A quadrant chart: both axis ends, all four quadrant labels, and points landing one per
        // quadrant plus one dead-centre — pins the affine unit-square mapping, the y-flip, the tints,
        // and the contrast-derived labels byte-for-byte.
        FIXTURES.put("quadrant",
            "quadrant\n  x-axis \"Low Reach\" --> \"High Reach\"\n"
                + "  y-axis \"Low Impact\" --> \"High Impact\"\n"
                + "  quadrant-1 \"Major project\"\n  quadrant-2 \"Quick win\"\n"
                + "  quadrant-3 \"Deprioritize\"\n  quadrant-4 \"Fill-in\"\n"
                + "  \"Feature A\" : [0.3, 0.6]\n  \"Feature B\" : [0.75, 0.8]\n"
                + "  \"Feature C\" : [0.5, 0.2]\n");
        // A UML class diagram (9th type): two POPULATED three-compartment classes (Animal with attrs +
        // methods, Dog with a method), an INHERITANCE edge (hollow-triangle marker) AND a COMPOSITION
        // edge (filled-diamond marker), the latter to an auto-vivified empty class (Collar). Pins the
        // compartment stacking, the grid placement, and the two distinct marker glyphs byte-for-byte.
        FIXTURES.put("classDiagram",
            "classDiagram\n  class Animal {\n    +String name\n    +int age\n    +eat() void\n"
                + "    +sleep()\n  }\n  class Dog {\n    +bark() void\n  }\n"
                + "  Animal <|-- Dog : inherits\n  Animal *-- Collar : composition\n");
        // An entity-relationship diagram (10th type): two POPULATED entity tables (CUSTOMER + ORDER,
        // each with a PK) plus an auto-vivified LINE-ITEM, a ZERO-OR-MANY relation (CUSTOMER ||--o{
        // ORDER — a bar-combo at the one end, a crow's-foot+circle at the many end) AND a ONE-OR-MANY
        // relation (ORDER ||--|{ LINE-ITEM — a crow's-foot+bar at the many end). Pins the header/rows
        // table stacking, the grid placement, and the crow-foot cardinality glyph combos byte-for-byte.
        FIXTURES.put("erDiagram",
            "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  ORDER ||--|{ LINE-ITEM : contains\n"
                + "  CUSTOMER {\n    string name PK\n    string email\n  }\n"
                + "  ORDER {\n    int id PK\n    date created\n  }\n");
        // A display-math block (11th type): a sum-with-limits equals a fraction. GoldenSvgTest renders
        // with the DEFAULT (null) renderer, so THIS golden pins the NULL-RENDERER DEGRADE — the raw
        // LaTeX source baked as plain-text glyph paths, centered with padding (deterministic). The REAL
        // typeset bake (glyph paths + a fraction-bar <rect>) is proven separately in
        // MathBlockRealRenderTest, which injects the LatteX renderer GoldenSvgTest deliberately lacks.
        FIXTURES.put("mathblock",
            "mathblock\n  \\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n");
        // A git commit graph (12th type): a main lane with two commits (one id-labeled), a `develop`
        // branch off the second, two commits on develop, then a merge back into main + a final commit —
        // pins the per-lane y offset, the two distinct branch colours, the branch-point elbow connector
        // (drop + run), the merge elbow connector (run + rise), the full-circle commit discs, and the
        // commit/branch anchor groups byte-for-byte.
        FIXTURES.put("gitgraph",
            "gitGraph\n  commit\n  commit id: \"fix\"\n  branch develop\n  checkout develop\n"
                + "  commit\n  commit\n  checkout main\n  merge develop\n  commit\n");
        // A user-journey satisfaction map (13th type): a title, two sections, several scored tasks, a
        // MULTI-ACTOR task (Commute: Me, Cat), and an out-of-range score CLAMPED to 5 (Arrive: 9) —
        // pins the score→y map (higher sits higher), the per-task point discs coloured by score, the
        // connecting satisfaction line, the section-header brackets + labels, and the stacked actor
        // labels byte-for-byte.
        FIXTURES.put("journey",
            "journey\n  title My working day\n  section Go to work\n    Make tea: 5: Me\n"
                + "    Commute: 3: Me, Cat\n    Arrive: 9: Me\n  section Do work\n    Code: 5: Me\n"
                + "    Meetings: 2: Me, Boss\n");
        // A mindmap (14th type): an indentation-defined tree — a `root Root idea` node with three
        // branches (Origins, Research, Tools), each with leaf children of its own — pins the LR
        // layered layout (depth→x column, left-aligned boxes), the leaf-order y + parent-centering
        // (each branch node sits between its leaves), the depth-banded box fills, and the elbow
        // parent→child connectors byte-for-byte.
        FIXTURES.put("mindmap",
            "mindmap\n  root Root idea\n    Origins\n      Long history\n      Popular\n"
                + "    Research\n      On effect\n    Tools\n      Pen and paper\n      Mermaid\n");
        // A sankey (15th type): a 3-column weighted-flow graph — two sources (Coal, Gas) fan INTO
        // Electricity, which fans OUT to two sinks (Homes, Industry). Electricity is a MIDDLE node with
        // multiple in- AND out-flows (in-sum 40 == out-sum 40), so it exercises the max(in,out) node
        // height, the column-by-depth placement, and the cumulative inflow/outflow slot offsets. Pins
        // the band quadrilateral geometry (width ∝ value), the node bar stacking, the depth columns,
        // the per-source lighter-tint band fills, and the beside-bar labels byte-for-byte.
        FIXTURES.put("sankey",
            "sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n"
                + "  Electricity,Homes,20\n  Electricity,Industry,20\n");
    }

    @Test
    void everyDiagramTypeMatchesItsGolden() throws Exception {
        for (Map.Entry<String, String> e : FIXTURES.entrySet()) {
            String name = e.getKey();
            String actual = Sirentide.render(e.getValue());
            if (UPDATE) {
                writeGolden(name, actual);
            } else {
                assertEquals(readGolden(name), actual,
                    name + ".svg drifted — a layout change? Regen with -Dsirentide.updateGolden=true "
                        + "and review the diff.");
            }
        }
    }

    private static String readGolden(String name) throws Exception {
        try (InputStream in = GoldenSvgTest.class.getResourceAsStream("/golden/" + name + ".svg")) {
            assertNotNull(in, "missing golden /golden/" + name + ".svg — regen with "
                + "-Dsirentide.updateGolden=true");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeGolden(String name, String svg) throws Exception {
        Path dir = Path.of("src/test/resources/golden");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".svg"), svg, StandardCharsets.UTF_8);
    }
}
