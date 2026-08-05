package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// Header dispatch accepts the REAL Mermaid spellings (plan 8a991947, slice 1).
///
/// MEASURED BEFORE THE FIX, against the shipped 0.6.0 jar — each of these rendered a 0x0 SVG at
/// EXIT 0, i.e. a silent blank file:
///
///   stateDiagram -> BLANK      state          -> renders
///   stateDiagram-v2 -> BLANK   statediagram   -> renders
///   sequenceDiagram -> BLANK   sequence       -> renders
///   quadrantChart -> BLANK     quadrant       -> renders
///
/// The README invites Mermaid as a design reference and several keywords (`classDiagram`,
/// `erDiagram`, `gitGraph`) already match Mermaid 1:1 — so an author pattern-matching from real
/// Mermaid hits the inconsistent ones and gets a blank file with no diagnostic.
///
/// Case-insensitivity ALONE was measured insufficient: `sequencediagram` and `quadrantchart` were
/// blank too, so genuine aliases are required, not just a `toLowerCase`.
class HeaderAliasDispatchTest {

    private static Diagram parse(String dsl) {
        return DslParser.parse(dsl);
    }

    private static final String STATE_BODY = "\n[*] --> Idle\nIdle --> Running\n";

    @ParameterizedTest(name = "{0} dispatches to a state diagram")
    @CsvSource({"state", "statediagram", "stateDiagram", "STATEDIAGRAM", "stateDiagram-v2",
                "statediagram-v2"})
    void everyStateSpellingDispatches(String header) {
        Diagram d = parse(header + STATE_BODY);
        assertFalse(d instanceof Empty,
            header + " must not degrade to the inert shell: " + d);
    }

    @ParameterizedTest(name = "{0} dispatches to a sequence diagram")
    @CsvSource({"sequence", "sequenceDiagram", "sequencediagram", "SequenceDiagram"})
    void everySequenceSpellingDispatches(String header) {
        Diagram d = parse(header + "\nA ->> B: hi\n");
        assertFalse(d instanceof Empty, header + " must not degrade to the inert shell: " + d);
    }

    @ParameterizedTest(name = "{0} dispatches to a quadrant chart")
    @CsvSource({"quadrant", "quadrantChart", "quadrantchart"})
    void everyQuadrantSpellingDispatches(String header) {
        Diagram d = parse(header + "\nx-axis Low --> High\n\"A\": [0.3, 0.6]\n");
        assertFalse(d instanceof Empty, header + " must not degrade to the inert shell: " + d);
    }

    @ParameterizedTest(name = "{0} keeps working (already Mermaid-exact, must not regress)")
    @CsvSource({"classDiagram", "classdiagram", "erDiagram", "erdiagram", "gitGraph", "gitgraph",
                "flowchart", "FLOWCHART", "mindmap", "journey", "pie", "timeline"})
    void spellingsThatAlreadyWorkedStillWork(String header) {
        // Case-insensitivity must be uniform, not bolted onto the three types that were broken.
        Diagram d = parse(header + "\nA --> B\n");
        assertFalse(d instanceof Empty, header + " regressed to the inert shell: " + d);
    }

    // ---- negative controls: normalization must not invent diagram types ----------------------

    @ParameterizedTest(name = "{0} is NOT a diagram type and must stay Empty")
    @CsvSource({"notadiagram", "stateDiagrammm", "sequenceDiagra", "quadrant-chart-v9", "flowchar"})
    void unknownHeadersStillDegradeToTheInertShell(String header) {
        // Slice 1 does NOT change what an unknown header PRODUCES — that is slice 2 (a loud
        // refusal instead of a silent blank). This control exists so normalization cannot
        // accidentally widen the accepted set: a near-miss must not be fuzzily matched onto a
        // real type, which would be a far worse defect than the blank it replaced.
        assertInstanceOf(Empty.class, parse(header + STATE_BODY),
            header + " must not be fuzzy-matched to a real diagram type");
    }

    @Test
    void anAliasResolvesToTheSameIrAsItsCanonicalSpelling() {
        // Not merely "not Empty" — the alias must land on the SAME parser, so the alias cannot
        // silently route to a different (but non-empty) diagram type.
        assertEquals(parse("state" + STATE_BODY).getClass(),
            parse("stateDiagram" + STATE_BODY).getClass());
        assertEquals(parse("sequence\nA ->> B: hi\n").getClass(),
            parse("sequenceDiagram\nA ->> B: hi\n").getClass());
        assertEquals(parse("quadrant\nx-axis Low --> High\n").getClass(),
            parse("quadrantChart\nx-axis Low --> High\n").getClass());
    }
}
