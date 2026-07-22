package com.sirentide.cli;

import static com.sirentide.cli.FenceExtractor.extractFirstSirentideFence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/// Pins the exact fence grammar `FenceExtractor` supports (plan
/// 6eb098d6-sirentide-local-render-check-cli slice A). This is a REVERT-PROOF test on the class's
/// documented divergence risk against the real Stafficy-side `SirentideDiagramConverter`: these
/// cases are the class's whole contract, not incidental coverage.
class FenceExtractorTest {

    @Test
    void findsTheFenceBody() {
        String md = """
            # Heading

            some prose

            ```sirentide
            pie
              "A" : 60
              "B" : 40
            ```

            more prose
            """;
        assertEquals("pie\n  \"A\" : 60\n  \"B\" : 40", extractFirstSirentideFence(md));
    }

    @Test
    void noFenceReturnsNull() {
        String md = """
            # Heading

            ```java
            System.out.println("not sirentide");
            ```
            """;
        assertNull(extractFirstSirentideFence(md), "a fence of a different info string is not a match");
        assertNull(extractFirstSirentideFence("just plain text, no fences at all"));
        assertNull(extractFirstSirentideFence(""));
    }

    @Test
    void multipleFencesTakesFirst() {
        String md = """
            ```sirentide
            pie
              "First" : 1
            ```

            ```sirentide
            pie
              "Second" : 1
            ```
            """;
        assertEquals("pie\n  \"First\" : 1", extractFirstSirentideFence(md),
            "only the FIRST top-level fence is returned; the second is ignored");
    }

    @Test
    void unclosedFenceIsTreatedAsNotFound() {
        String md = """
            ```sirentide
            pie
              "A" : 1
            """; // no closing ``` before EOF
        assertNull(extractFirstSirentideFence(md),
            "an opening fence with no closing line is NOT guessed at — same as no fence found");
    }

    @Test
    void emptyFenceBodyIsTheEmptyString() {
        String md = """
            ```sirentide
            ```
            """;
        assertEquals("", extractFirstSirentideFence(md), "a fence with nothing between the markers");
    }

    @Test
    void fenceMarkerLinesMayBeIndentedButBodyIsPreservedVerbatim() {
        // The opening/closing MARKER lines are matched after stripping surrounding whitespace; the
        // BODY lines are not stripped, so indentation inside the fence survives untouched.
        String md = "  ```sirentide  \n  pie\n    \"A\" : 1\n  ```\n";
        assertEquals("  pie\n    \"A\" : 1", extractFirstSirentideFence(md));
    }

    @Test
    void aBareTripleBacktickIsNotAnOpeningMarker() {
        String md = """
            ```
            pie
              "A" : 1
            ```
            """;
        assertNull(extractFirstSirentideFence(md), "a fence with no info string at all is not sirentide");
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(extractFirstSirentideFence(null));
    }
}
