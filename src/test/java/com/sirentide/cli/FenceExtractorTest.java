package com.sirentide.cli;

import static com.sirentide.cli.FenceExtractor.extractFirstSirentideFence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/// CROSS-REPOSITORY CONTRACT FIXTURE (review sirentide/471 B2). `FenceExtractor` must behave
/// exactly like the fence-scanning state machine in Stafficy's `SirentideDiagramConverter`
/// (Stafficy main `37f1e8a47c011b7ad9c5a9405d27311155521cc8`, converter lines 81–149) — the
/// pre-flexmark pass that decides which fences the `/docs` bake captures. Every case below is
/// either a shared-grammar pin or a named parity discriminator against that converter; several
/// mirror its own focused tests (noted inline). If a case here ever needs to change, first check
/// whether the CONVERTER changed — parity is the contract, not this file's history.
class FenceExtractorTest {

    // --- shared-grammar pins (behavior identical before and after the parity rewrite) ----------

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
            "only the FIRST captured fence is returned (the bake renders all; this verb checks one)");
    }

    @Test
    void unclosedFenceIsTreatedAsNotFound() {
        String md = """
            ```sirentide
            pie
              "A" : 1
            """; // no closing ``` before EOF
        assertNull(extractFirstSirentideFence(md),
            "the converter flushes an unclosed capture VERBATIM (renders nothing) — so the "
            + "render-check answer is 'no fence', never a guessed body");
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
        // Marker lines strip leading whitespace before the token test (converter's
        // fenceTokenIfFenceLine uses stripLeading); BODY lines are never stripped.
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

    // --- converter-parity discriminators (review sirentide/471 B2) ------------------------------

    @Test
    void sirentideNestedInsideATildeOuterFenceIsNotCaptured() {
        // Mirrors the converter's own sirentideNestedInsideAnOuterFenceIsNotCaptured test: a
        // writer documenting the syntax inside a ~~~ fence gets verbatim passthrough from the
        // bake, so the render-check must answer "no fence" — this exact input previously
        // extracted (and rendered) here, the divergence review 471 reproduced.
        String nested = "~~~\n```sirentide\nflowchart TD\n  A --> B\n```\n~~~";
        assertNull(extractFirstSirentideFence(nested),
            "/docs leaves a nested fence literal; the render-check must not capture it");
    }

    @Test
    void fourBacktickSirentideOpenerIsCaptured() {
        // The converter's fenceTokenIfFenceLine matches "starts with ```" and backtickFenceInfo
        // skips the WHOLE backtick run — so ````sirentide is captured by the bake. Review 471
        // found this exact-tip CLI rejected it (opposite-direction divergence).
        String md = "````sirentide\npie\n  \"A\" : 1\n```\n";
        assertEquals("pie\n  \"A\" : 1", extractFirstSirentideFence(md),
            "a 4+-backtick sirentide opener is a diagram to the bake, so it is one here");
    }

    @Test
    void infoStringWithTrailingTextIsNotAnExactMatch() {
        // Mirrors the converter's aSirentideInfoStringWithTrailingTextIsNotAnExactMatch test.
        String md = "```sirentide extra\npie\n  \"A\" : 1\n```\n";
        assertNull(extractFirstSirentideFence(md),
            "the info-string must be EXACTLY 'sirentide' post-trim, matching the converter");
    }

    @Test
    void tildeSirentideFenceIsNotADiagram() {
        String md = "~~~sirentide\npie\n  \"A\" : 1\n~~~\n";
        assertNull(extractFirstSirentideFence(md),
            "the converter captures backtick fences only; a ~~~sirentide fence passes through");
    }

    @Test
    void captureIsClosedByAnyBacktickFenceLineEvenWithAnInfoString() {
        // Converter parity: while capturing, the close test is on the fence TOKEN only — a
        // ```java line closes a sirentide capture (its info string is ignored).
        String md = "```sirentide\npie\n  \"A\" : 1\n```java\nSystem.out.println();\n```\n";
        assertEquals("pie\n  \"A\" : 1", extractFirstSirentideFence(md),
            "any backtick fence line closes the capture, exactly as in the converter");
    }

    @Test
    void tildeLinesInsideACaptureAreBody() {
        // Converter parity: while capturing, only backtick fence lines close — a ~~~ line is DSL.
        String md = "```sirentide\npie\n~~~\n  \"A\" : 1\n```\n";
        assertEquals("pie\n~~~\n  \"A\" : 1", extractFirstSirentideFence(md));
    }

    @Test
    void insideABareBacktickOuterFenceASirentideLineClosesTheOuterFence() {
        // Converter parity (deliberately not CommonMark): inside a bare ``` outer fence, a
        // ```sirentide line bears the outer fence's closing token and is consumed as its CLOSER —
        // it does not open a capture, and the lines after it are top level again.
        String md = "```\ntext\n```sirentide\npie\n  \"A\" : 1\n```\n";
        assertNull(extractFirstSirentideFence(md),
            "the ```sirentide line closes the outer fence (converter parity); 'pie' is prose and "
            + "the final ``` opens a new pass-over fence — nothing is captured");
    }

    @Test
    void afterAnOuterFenceClosesASubsequentSirentideFenceIsCaptured() {
        String md = "~~~\nnot captured\n~~~\n```sirentide\npie\n  \"A\" : 1\n```\n";
        assertEquals("pie\n  \"A\" : 1", extractFirstSirentideFence(md),
            "the pass-over region ends at its matching marker; later fences are top level again");
    }

    @Test
    void crlfBodyLinesKeepTheirCarriageReturnsByteForByteLikeTheBake() {
        // The converter splits on \n ONLY (split("\n", -1)) — a CRLF file's \r stays on each line
        // and inside the captured DSL. The render-check must hand the renderer the SAME bytes the
        // bake would, whatever the renderer then does with them.
        String md = "```sirentide\r\npie\r\n  \"A\" : 1\r\n```\r\n";
        assertEquals("pie\r\n  \"A\" : 1\r", extractFirstSirentideFence(md),
            "\\r is body content to the bake's scanner (the opener/closer lines still match "
            + "because backtickFenceInfo trims and the closer test is startsWith)");
    }
}
