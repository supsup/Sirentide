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
