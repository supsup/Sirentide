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
        FIXTURES.put("xychart",
            "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n");
        FIXTURES.put("timeline",
            "timeline\n  \"Founded\" : 2020\n  \"Series A\" : 2021\n  \"Launch\" : 2023\n");
        FIXTURES.put("gantt",
            "gantt\n  \"Design\" : 0-3\n  \"Build\" : 3-8\n  \"Test\" : 7-10\n");
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
