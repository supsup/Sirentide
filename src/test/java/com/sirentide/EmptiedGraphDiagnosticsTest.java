package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.FramesResult;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;

/// Receipts for the EMPTIED-DIAGRAM degrade (plan 650d6425, "an emptied diagram reports success").
///
/// THE MEASURED DEFECT, at sirentide main: `flowchart TD` + `A <--> B` renders an empty picture and
/// `renderWithDiagnostics` returned
/// `Diagnostics[outcome=OK, stage=emit, message="Rendered successfully.", line=-1]`. The DROP is
/// correct and deliberate — a bidirectional arrow is unsupported, and dropping the line is what kills
/// the phantom `A <` node the parser used to mint. The VERDICT on top of it was not: the author wrote
/// a two-node diagram, watched it vanish, and was told it succeeded.
///
/// THE CONTROL IS THE LOAD-BEARING HALF, and it is why {@link #genuinelyEmptyBodiesKeepReportingOk}
/// sits at the top of this file. A genuinely empty body (`flowchart TD` alone, blank, whitespace) is
/// LEGITIMATELY empty and must keep its OK. The trigger is NON-EMPTY-BODY-YIELDS-EMPTY-GRAPH, never
/// merely empty-graph — a change that failed every empty diagram would be a WRONG fix and worse than
/// the bug, and it would pass every other test in this file.
///
/// The two arms are provably distinct in exactly ONE place — the body line. Everything downstream is
/// identical: {@link #theEmptiedAndTheEmptyBakeTheSameBytes} pins that the defect input and the
/// control input produce byte-identical SVG, so neither the IR nor the picture can be the
/// discriminator. Only the SOURCE can, which is what makes
/// {@link DslParser#flowchartBodyCensus} necessary rather than ornamental.
class EmptiedGraphDiagnosticsTest {

    /// `flowchart TD` + one dropped bidirectional edge. The subject of the whole plan.
    private static final String EMPTIED = "flowchart TD\n    A <--> B";
    /// The same diagram type with NO body at all — legitimately empty.
    private static final String EMPTY_BODY = "flowchart TD";

    // ---- THE CONTROL ---------------------------------------------------------------------------

    /// A genuinely empty body is legitimately empty: it renders the empty canvas ON PURPOSE and must
    /// still report success. Five spellings of "nothing was written", including the null and blank
    /// sources {@code RenderDiagnosticsTest} already pins as "a blank canvas, not a parse error".
    @Test
    void genuinelyEmptyBodiesKeepReportingOk() {
        for (String dsl : new String[] {
                EMPTY_BODY,             // the header alone
                "flowchart",            // a bare, direction-less header
                "flowchart TD\n\n\n",   // a header plus nothing but blank lines
                "flowchart LR\n   \n\t\n",
                "",                     // an empty source
                "   \n  \n "}) {        // whitespace only
            Diagnostics d = Sirentide.renderWithDiagnostics(dsl).diagnostics();
            assertEquals(Outcome.OK, d.outcome(),
                "an EMPTY body is legitimately empty and must keep its OK verdict: "
                    + dsl.replace("\n", "\\n") + " → " + d.message());
            assertEquals("Rendered successfully.", d.message(),
                "and its message must be untouched: " + dsl.replace("\n", "\\n"));
        }
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(null).diagnostics().outcome(),
            "a null source is a blank canvas, not an emptied diagram");
    }

    /// The discriminator receipt: the emptied input and the empty-body control bake IDENTICAL bytes,
    /// so no property of the IR or the SVG can tell them apart. If a future "simplification" keys the
    /// degrade on the rendered graph alone, this test still passes — and
    /// {@link #genuinelyEmptyBodiesKeepReportingOk} is what fails. They are a pair.
    @Test
    void theEmptiedAndTheEmptyBakeTheSameBytes() {
        assertEquals(Sirentide.render(EMPTY_BODY), Sirentide.render(EMPTIED),
            "an emptied flowchart and an empty one render the same canvas — only the SOURCE differs");
        assertNotEquals(Sirentide.renderWithDiagnostics(EMPTY_BODY).diagnostics().outcome(),
            Sirentide.renderWithDiagnostics(EMPTIED).diagnostics().outcome(),
            "…and yet their VERDICTS must differ, which is the whole point of the plan");
    }

    // ---- THE DEFECT ----------------------------------------------------------------------------

    /// THE repro. Not OK; classified on the existing UNSUPPORTED_CONSTRUCT channel (the arrow is
    /// recognized-but-unsupported, exactly what that channel is for), at stage `parse`, naming the
    /// author's 1-based physical line, and NAMING the dropped construct in the message.
    @Test
    void emptiedByABidirectionalArrowDegradesAndNamesIt() {
        RenderResult r = Sirentide.renderWithDiagnostics(EMPTIED);
        Diagnostics d = r.diagnostics();
        assertNotEquals(Outcome.OK, d.outcome(),
            "an emptied diagram must not report success: " + d.message());
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, d.outcome(),
            "a recognized-but-unsupported construct reuses the existing channel");
        assertEquals("parse", d.stage(), "the content was lost at parse");
        assertEquals(2, d.line(), "the offending statement's 1-based physical line");
        assertTrue(d.message().contains("<-->"),
            "the message must NAME the dropped construct: " + d.message());
        assertTrue(d.message().contains("no nodes and no edges"),
            "…and say what happened to the diagram: " + d.message());
        assertTrue(d.detail().contains("A <--> B"),
            "the log-facing detail carries the dropped source line: " + d.detail());
        assertEquals(Sirentide.render(EMPTIED), r.svg(),
            "the SACRED bake is untouched — only the verdict on top of it changed");
    }

    /// All three spellings of the bidirectional form the parser drops. `<-.->` and `<==>` are not
    /// separate code paths in the parser, but they ARE separate author spellings, and a fix that
    /// covered only `<-->` would look identical in every other test here.
    @Test
    void everyBidirectionalSpellingDegrades() {
        for (String arrow : new String[] {"<-->", "<-.->", "<==>"}) {
            String dsl = "flowchart TD\n    A " + arrow + " B";
            Diagnostics d = Sirentide.renderWithDiagnostics(dsl).diagnostics();
            assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, d.outcome(), "for " + arrow);
            assertEquals(2, d.line(), "for " + arrow);
        }
    }

    // ---- ONE CASE PER DROP PATH THAT CAN EMPTY A NON-EMPTY BODY ---------------------------------

    /// The census of drop paths, each of which can empty a body on its own. The bidirectional arrow
    /// is only ONE of them — a fix that special-cased `<-->` would pass the test above and fail here.
    ///
    /// Every one of these degrades on the PARSE_ERROR channel: the statement is unparseable, not a
    /// recognized construct, so the "we know what you meant and cannot draw it" vocabulary would be
    /// a lie. All carry the real line, because the parse knows exactly which statement it dropped.
    @Test
    void everyUnparseableDropPathDegradesWithItsLine() {
        for (String body : new String[] {
                "A[Start",                // an unterminated bracket on a lone node
                "A[Start] junk",          // trailing text after a closed label
                "A[Start --> B[End]",     // a nested bracket swallows the arrow → lone-node path
                "A -->|yes B",            // an edge label with no closing pipe
                " --> B",                 // an empty head endpoint
                "A --> B[End"}) {         // an unterminated bracket on a chain endpoint
            String dsl = "flowchart TD\n    " + body;
            Diagnostics d = Sirentide.renderWithDiagnostics(dsl).diagnostics();
            assertEquals(Outcome.PARSE_ERROR, d.outcome(),
                "an unparseable statement that empties the body must degrade: " + body);
            assertEquals("parse", d.stage(), "for " + body);
            assertEquals(2, d.line(), "the dropped statement's physical line, for " + body);
            assertTrue(d.message().contains("could not read")
                    || d.message().contains("no closing `|`"),
                "the message must say what could not be read: " + d.message());
            assertEquals(Sirentide.render(dsl), Sirentide.renderWithDiagnostics(dsl).svg(),
                "svg identity holds on every degrade path, for " + body);
        }
    }

    /// The OTHER way a written body ends up empty: nothing was DROPPED, but nothing renderable was
    /// DECLARED either — a body of directives, an empty `subgraph`, a stray `end`, or an edge whose
    /// only endpoint is an empty cluster (that last one is dropped AFTER the body walk, so the
    /// statement census sees no drop at all and this branch is the one that must catch it).
    @Test
    void bodiesThatDeclareNothingRenderableDegradeToo() {
        for (String body : new String[] {
                "direction LR",                      // a reserved directive, dropped inert
                "accTitle: My chart",                // the colon-form directive
                "classDef foo fill:#ff0000",         // a style definition with nothing to style
                "class A foo",                       // an assignment to a node that was never declared
                "linkStyle default stroke:#ff0000",  // an edge style with no edges
                "end",                               // a stray block close
                "subgraph S [Group]\n    end",       // a group with no members (renders nothing)
                "subgraph S\n    end\n    S --> S"}) {   // an edge into an EMPTY cluster
            String dsl = "flowchart TD\n    " + body;
            Diagnostics d = Sirentide.renderWithDiagnostics(dsl).diagnostics();
            assertEquals(Outcome.PARSE_ERROR, d.outcome(),
                "a written body that declares nothing must not report success: " + body);
            assertTrue(d.message().contains("declared no nodes and no edges"),
                "the message must distinguish 'declared nothing' from 'was dropped': " + d.message());
            assertEquals(2, d.line(), "the first statement's physical line, for " + body);
        }
    }

    /// The BOUNDARY of the trigger, from the other side: a body that drops a statement but still
    /// produces a node (or an edge) is NOT an emptied diagram — the author sees a picture, and this
    /// slice deliberately does not change that verdict. Stated as a receipt so the limitation is a
    /// decision on the record rather than an accident.
    @Test
    void aPartiallyDroppedBodyThatStillRendersStaysOk() {
        for (String dsl : new String[] {
                "flowchart TD\n    A[ok]\n    B <--> C",      // one surviving node
                "flowchart TD\n    A --> B\n    C <--> D",    // one surviving edge
                "flowchart TD\n    A[ok]\n    B[broken"}) {   // a surviving node + a malformed line
            assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome(),
                "a diagram that still renders content is not an EMPTIED diagram: "
                    + dsl.replace("\n", "\\n"));
        }
    }

    // ---- ATTRIBUTION ---------------------------------------------------------------------------

    /// The reported line is the author's PHYSICAL line, counted from the top of their raw source —
    /// leading blank lines and a `%%` config preamble keep their numbers (the same rule
    /// {@code detectUnsupportedConstruct} learned at Marlow sirentide/706 Finding 3, which a
    /// `src.strip()` before the split would silently break).
    @Test
    void theReportedLineIsThePhysicalSourceLine() {
        assertEquals(5, Sirentide.renderWithDiagnostics("\n\n\nflowchart TD\n    A <--> B")
            .diagnostics().line(), "three leading blank lines keep their numbering");
        assertEquals(5, Sirentide.renderWithDiagnostics(
                "sirentide\n%% title: T\n%% theme: dark\nflowchart TD\n    A <--> B")
            .diagnostics().line(), "a marker + config preamble keeps its numbering");
        assertEquals(4, Sirentide.renderWithDiagnostics("flowchart TD\n\n\n    A <--> B")
            .diagnostics().line(), "blank lines INSIDE the body keep their numbering");
    }

    /// The FIRST dropped statement decides the channel, and the count is exact while the echoed
    /// sample is capped. Two bodies with the same two statements in opposite orders classify
    /// differently — which is the point: the diagnostic reports what the author hits first.
    @Test
    void theFirstDropDecidesTheChannelAndTheCountStaysExact() {
        assertEquals(Outcome.PARSE_ERROR,
            Sirentide.renderWithDiagnostics("flowchart TD\n    A[Start\n    B <--> C")
                .diagnostics().outcome(), "malformed first → PARSE_ERROR");
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT,
            Sirentide.renderWithDiagnostics("flowchart TD\n    B <--> C\n    A[Start")
                .diagnostics().outcome(), "unsupported first → UNSUPPORTED_CONSTRUCT");

        StringBuilder many = new StringBuilder("flowchart TD\n");
        for (int i = 0; i < 9; i++) {
            many.append("    N").append(i).append(" <--> M").append(i).append("\n");
        }
        Diagnostics d = Sirentide.renderWithDiagnostics(many.toString()).diagnostics();
        assertTrue(d.message().contains("9 statements"), "the statement count is exact: " + d.message());
        assertTrue(d.message().contains("9 of this body's statements were dropped"),
            "the drop count is exact: " + d.message());
        assertTrue(d.detail().contains("(4 more not listed)"),
            "…while the echoed sample stays bounded: " + d.detail());
    }

    /// A pathological source line cannot become a pathological diagnostic: the echoed text is capped,
    /// and it lands in `detail` (log-facing) rather than in the author-facing `message`.
    @Test
    void aHugeDroppedLineIsTruncatedAndNeverReachesTheMessage() {
        String junk = "X".repeat(5_000);
        Diagnostics d = Sirentide.renderWithDiagnostics("flowchart TD\n    " + junk + " <--> Y")
            .diagnostics();
        assertTrue(d.detail().length() < 400, "the detail stays bounded: " + d.detail().length());
        assertTrue(d.detail().contains("..."), "…and says it truncated: " + d.detail());
        assertTrue(!d.message().contains("XXXX"), "author text never lands in the message");
    }

    // ---- THE FRAMES TWIN -----------------------------------------------------------------------

    /// The play-through twin classifies IDENTICALLY (the two entry points are documented to agree)
    /// while its deck stays byte-identical to what `renderFrames` returns for the same source — the
    /// invariant every other degrade in that method holds.
    @Test
    void theFramesTwinAgreesAndItsDeckIsUnchanged() {
        FramesResult fr = Sirentide.renderFramesWithDiagnostics(EMPTIED);
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, fr.diagnostics().outcome(),
            "the frames twin must not report success either");
        assertEquals(Sirentide.renderWithDiagnostics(EMPTIED).diagnostics().message(),
            fr.diagnostics().message(), "both entry points classify the same way");
        assertEquals(Sirentide.renderFrames(EMPTIED), fr.frames(),
            "the deck is byte-identical to renderFrames");
        assertEquals(Outcome.OK, Sirentide.renderFramesWithDiagnostics(EMPTY_BODY)
            .diagnostics().outcome(), "and the empty-body control holds on this path too");
    }

    // ---- THE CENSUS ITSELF ---------------------------------------------------------------------

    /// The parser-side census is honest about what it does and does not know: `null` for anything
    /// that is not a flowchart, for a blank source, and for an over-cap source (a size rejection is
    /// never a construct claim — 706 Finding 3), and a statement count that reflects the body the
    /// author wrote rather than the graph that survived.
    @Test
    void theCensusIsNullForEverythingItCannotSpeakFor() {
        assertNull(DslParser.flowchartBodyCensus(null), "null source");
        assertNull(DslParser.flowchartBodyCensus("   \n  "), "blank source");
        assertNull(DslParser.flowchartBodyCensus("pie\n  \"A\" : 1"), "not a flowchart");
        assertNull(DslParser.flowchartBodyCensus("stateDiagram-v2\n  A --> B"), "not a flowchart");
        assertNull(DslParser.flowchartBodyCensus("flowchart TD\n  A <--> B\n%% " + "e".repeat(1_100_000)),
            "an over-cap source is a cap rejection, not a census");

        DslParser.FlowchartBodyCensus empty = DslParser.flowchartBodyCensus(EMPTY_BODY);
        assertEquals(0, empty.statements(), "the control's body has no statements");
        assertEquals(0, empty.droppedTotal(), "…and nothing was dropped");

        DslParser.FlowchartBodyCensus emptied = DslParser.flowchartBodyCensus(EMPTIED);
        assertEquals(1, emptied.statements(), "the author wrote one statement");
        assertEquals(1, emptied.droppedTotal(), "…and it was dropped whole");
        assertEquals(2, emptied.dropped().get(0).line(), "at physical line 2");
        assertTrue(emptied.dropped().get(0).unsupported(),
            "a bidirectional arrow is recognized-but-unsupported, not unparseable");

        // A HEALTHY flowchart still censuses truthfully — the census is not a failure detector, it
        // reports what the walk saw, and the emptiness decision belongs to the caller.
        DslParser.FlowchartBodyCensus healthy = DslParser.flowchartBodyCensus("flowchart TD\n  A --> B");
        assertEquals(1, healthy.statements());
        assertEquals(0, healthy.droppedTotal(), "nothing was dropped in a healthy body");
    }

    /// Uppercase/mixed-case header spellings census too: the alias table is the only thing that knows
    /// `FLOWCHART` and `flowchart` are one type, and matching the raw spelling here would have left a
    /// SHOUTED header silently reporting success.
    @Test
    void headerSpellingGoesThroughTheAliasTable() {
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT,
            Sirentide.renderWithDiagnostics("FLOWCHART TD\n    A <--> B").diagnostics().outcome());
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT,
            Sirentide.renderWithDiagnostics("Flowchart LR\n    A <--> B").diagnostics().outcome());
    }

    // ---- THE SAFETY INVARIANT ------------------------------------------------------------------

    /// The never-throw contract is sacred, and the SVG is never touched. Both halves, across every
    /// input this file exercises plus the degenerate ones.
    @Test
    void neverThrowsAndNeverAltersTheBake() {
        for (String dsl : new String[] {
                EMPTIED, EMPTY_BODY, "", "   ", "flowchart TD\n    A[Start",
                "flowchart TD\n    subgraph S\n    end", "flowchart TD\n    A --> B",
                "flowchart\n    <-->", "flowchart TD\n" + "    A <--> B\n".repeat(200)}) {
            RenderResult r = Sirentide.renderWithDiagnostics(dsl);
            assertEquals(Sirentide.render(dsl), r.svg(),
                "svg identity for " + dsl.replace("\n", "\\n"));
            assertTrue(r.diagnostics().message() != null && !r.diagnostics().message().isBlank(),
                "a non-blank message for " + dsl.replace("\n", "\\n"));
        }
    }
}
