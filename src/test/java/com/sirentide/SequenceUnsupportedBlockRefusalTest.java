package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.SeqBlock;
import com.sirentide.ir.Sequence;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A Mermaid sequence block opener this parser has no frame for (`opt`, `critical`, `break`,
/// `rect`) is REFUSED and NAMED when it sits INSIDE ANOTHER BLOCK (audit 2026-10-12 finding 5;
/// lead ruling "refuse first, fail closed").
///
/// NARROWED IN THE REPAIR ROUND. The first version refused the opener wherever it stood. The
/// reason given for refusing (below) needs an enclosing block, so at top level the refusal
/// blanked a diagram whose only fault was a missing frame. At top level the opener is now what it
/// was before this branch: a dropped line, with every message drawn and its `end` inert.
///
/// WHAT IT DID BEFORE, read off the parser by trace. The opener has no arrow and is not one of
/// `alt`/`loop`/`par`, so it was dropped as a malformed message. Its `end` was not dropped: `end`
/// closes the innermost OPEN block, which was the enclosing one. On the audit's input
///
///     alt ok / A ->> B : x / opt maybe / B ->> A : y / end / else bad / A ->> B : z / end
///
/// the `alt` frame closed after messages 0..1 instead of 0..2, `else bad` was a stray with no
/// divider, and the last `end` closed nothing. All at outcome OK, and the frame that was drawn
/// looks exactly like one the author wrote.
///
/// THE CONTROL ASSERTS THE FRAME'S SPAN AND DIVIDER, not that a Sequence came back: the defect
/// never changed the diagram type, the actors, or the message count. Only the block geometry.
class SequenceUnsupportedBlockRefusalTest {

    private static Sequence sequence(String dsl) {
        Diagram d = DslParser.parse(dsl);
        return assertInstanceOf(Sequence.class, d,
            "expected a Sequence to inspect, got " + d.getClass().getSimpleName() + " for: " + dsl);
    }

    private static List<String> messages(Sequence s) {
        return s.messages().stream()
            .map(m -> m.from() + "->" + m.to() + (m.label() == null ? "" : "[" + m.label() + "]"))
            .toList();
    }

    private static void assertRefusedNaming(String dsl, String keyword, int line) {
        assertInstanceOf(Empty.class, DslParser.parse(dsl),
            "an unsupported block opener must degrade the diagram, never mis-frame it: " + dsl);
        DslParser.UnsupportedConstruct u = DslParser.detectUnsupportedConstruct(dsl);
        assertTrue(u != null, "the opener must be NAMED, not silently degraded: " + dsl);
        assertEquals(keyword, u.token(), "the detector's token for: " + dsl);
        assertEquals(line, u.line(), "the 1-based physical line of the opener in: " + dsl);

        RenderResult r = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(Outcome.UNSUPPORTED_CONSTRUCT, r.diagnostics().outcome(),
            "a refusal must not read as success: " + r.diagnostics().message());
        assertEquals("parse", r.diagnostics().stage());
        assertEquals(line, r.diagnostics().line());
        assertTrue(r.diagnostics().message().contains("`" + keyword + "`"),
            "the author-facing message must name the `" + keyword + "` block: "
                + r.diagnostics().message());
    }

    // -- the refusal ------------------------------------------------------------------------------

    @Test
    void anOptNestedInAnAltIsRefusedRatherThanClosingTheAlt() {
        // The audit's own input. `opt maybe` is physical line 4.
        assertRefusedNaming(
            "sequence\nalt ok\nA ->> B : x\nopt maybe\nB ->> A : y\nend\nelse bad\nA ->> B : z\nend\n",
            "opt", 4);
    }

    @Test
    void eachUnsupportedOpenerNestedInABlockIsRefusedOnItsOwn() {
        // One keyword per assertion, under both header spellings and inside each supported frame.
        // Every one of these has an enclosing block for the opener's `end` to close.
        assertRefusedNaming(
            "sequenceDiagram\nloop each\nA ->> B : x\ncritical connect\nA ->> B : y\nend\nend\n",
            "critical", 4);
        assertRefusedNaming("sequence\npar one\nA ->> B : x\nbreak when down\nend\nend\n", "break", 4);
        assertRefusedNaming("sequence\nalt ok\nrect rgb(0, 255, 0)\nA ->> B : x\nend\nend\n", "rect", 3);
        assertRefusedNaming("sequence\nloop each\nopt\nA ->> B : x\nend\nend\n", "opt", 3);
    }

    @Test
    void anArrowInsideTheOpenersLabelAfterAColonDoesNotHideIt() {
        // The scanner reads the pre-colon HEAD, the same place the message parse looks for its
        // arrow. A scanner reading the whole line takes the label's `->` for a message arrow,
        // skips the opener, and the `end` below closes the enclosing loop.
        assertRefusedNaming(
            "sequence\nloop each\nA ->> B : x\nopt retry : a -> b\nA ->> B : y\nend\nend\n", "opt", 4);
    }

    // -- the narrowed case: an opener with no block open ------------------------------------------

    @Test
    void aTopLevelOpenerIsADroppedLineAndTheMessagesRender() {
        // The adversarial review's input. Nothing is open when `rect` arrives, so its `end` has
        // nothing to close: the refusal's own reason does not apply.
        String dsl = "sequenceDiagram\nA ->> B : x\nrect rgb(0, 255, 0)\nB -->> A : y\nend\n";
        Sequence s = sequence(dsl);
        assertEquals(List.of("A", "B"), s.actors());
        assertEquals(List.of("A->B[x]", "B->A[y]"), messages(s),
            "OLD BEHAVIOUR RESTORED: before this branch a top-level `rect` line was dropped and "
                + "both messages rendered; the first version of the refusal blanked the diagram");
        assertTrue(s.blocks().isEmpty(),
            "the stray `end` meets an empty stack and closes nothing: " + s.blocks());
        assertNull(DslParser.detectUnsupportedConstruct(dsl),
            "one unreadable line is not an unsupported construct: " + dsl);
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome());
    }

    @Test
    void eachTopLevelOpenerIsDroppedOnItsOwn() {
        // One keyword per assertion. OLD BEHAVIOUR RESTORED for all four: the opener line is
        // dropped, the message inside it is drawn, no frame is opened.
        for (String opener : new String[] {"opt maybe", "critical connect", "break when down",
                                           "rect rgb(0, 255, 0)", "opt"}) {
            String dsl = "sequence\n" + opener + "\nA ->> B : x\nend\n";
            Sequence s = sequence(dsl);
            assertEquals(List.of("A->B[x]"), messages(s),
                "OLD BEHAVIOUR RESTORED: a top-level `" + opener + "` costs only its frame: " + dsl);
            assertTrue(s.blocks().isEmpty(), "no block opened by `" + opener + "`: " + s.blocks());
            assertNull(DslParser.detectUnsupportedConstruct(dsl), "not refused: " + dsl);
        }
    }

    @Test
    void anOpenerAfterAClosedBlockIsTopLevelAgain() {
        // "A block is open AT THAT LINE", not "a block was opened earlier". A guard that only
        // remembers having seen an `alt` refuses this; the alt here is closed before `opt`.
        String dsl = "sequence\nalt ok\nA ->> B : x\nend\nopt maybe\nB ->> A : y\nend\n";
        Sequence s = sequence(dsl);
        assertEquals(List.of("A->B[x]", "B->A[y]"), messages(s),
            "OLD BEHAVIOUR RESTORED: the `opt` line is dropped and its message still renders");
        assertEquals(1, s.blocks().size(), "only the alt frame: " + s.blocks());
        assertEquals("alt", s.blocks().get(0).kind());
        assertEquals(0, s.blocks().get(0).fromMsg());
        assertEquals(0, s.blocks().get(0).toMsg(), "and it still spans only its own message");
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }

    @Test
    void aSupportedBlockInsideATopLevelOpenerKeepsItsOwnFrame() {
        // The other direction of nesting: the UNSUPPORTED opener is outermost. Its line is
        // dropped while nothing is open, the `alt` inside it opens and closes normally, and the
        // outer `end` is the stray. Nothing is framed wrongly, so nothing is refused.
        String dsl = "sequence\nopt maybe\nalt ok\nA ->> B : x\nend\nB ->> A : y\nend\n";
        Sequence s = sequence(dsl);
        assertEquals(List.of("A->B[x]", "B->A[y]"), messages(s),
            "OLD BEHAVIOUR RESTORED: a top-level `opt` costs only its own frame");
        assertEquals(1, s.blocks().size(), "only the alt frame: " + s.blocks());
        assertEquals(0, s.blocks().get(0).toMsg(), "closed by its OWN end, after one message");
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }

    // -- the controls -----------------------------------------------------------------------------

    @Test
    void theSameAltWithoutTheOptParsesToTheSameFrameAsBefore() {
        // THE POSITIVE CONTROL: the audit's input with the `opt` ... `end` pair removed. This is
        // the frame the author meant, and the one the unguarded parser failed to draw.
        String dsl = "sequence\nalt ok\nA ->> B : x\nB ->> A : y\nelse bad\nA ->> B : z\nend\n";
        Sequence s = sequence(dsl);
        assertEquals(List.of("A", "B"), s.actors());
        assertEquals(List.of("A->B[x]", "B->A[y]", "A->B[z]"), messages(s));
        assertEquals(1, s.blocks().size(), "one alt frame: " + s.blocks());
        SeqBlock alt = s.blocks().get(0);
        assertEquals("alt", alt.kind());
        assertEquals("ok", alt.label());
        assertEquals(0, alt.fromMsg(), "the frame opens at the first message");
        assertEquals(2, alt.toMsg(), "and spans all THREE messages, not two");
        assertEquals(1, alt.dividers().size(), "the else divider is kept: " + alt.dividers());
        assertEquals(2, alt.dividers().get(0).atMsg());
        assertEquals("bad", alt.dividers().get(0).label());
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
        assertEquals(Outcome.OK, Sirentide.renderWithDiagnostics(dsl).diagnostics().outcome());
    }

    @Test
    void aMessageWhoseSenderIsSpelledLikeAnUnsupportedOpenerIsStillAMessage() {
        // The exemption the supported block keywords already have (SequenceBlockTest pins
        // `end ->> B` as a message): an arrow in the head makes the line a message. A refusal
        // keyed on the first word alone refuses both of these.
        String dsl = "sequence\nopt ->> B : real\nbreak ->> B : also\n";
        Sequence s = sequence(dsl);
        assertEquals(List.of("opt", "B", "break"), s.actors());
        assertEquals(List.of("opt->B[real]", "break->B[also]"), messages(s));
        assertTrue(s.blocks().isEmpty(), "no block opened: " + s.blocks());
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }

    @Test
    void theSupportedFramesAreNotRefused() {
        // Under-triggering is caught above; this is the over-triggering axis. All three supported
        // openers, nested, with both divider kinds.
        String dsl = "sequence\nloop each\nalt ok\nA ->> B : x\nelse bad\nA ->> B : y\nend\n"
            + "par one\nA ->> B : p\nand two\nB ->> A : q\nend\nend\n";
        Sequence s = sequence(dsl);
        assertEquals(List.of("A->B[x]", "A->B[y]", "A->B[p]", "B->A[q]"), messages(s));
        assertEquals(List.of("alt", "par", "loop"), s.blocks().stream().map(b -> b.kind()).toList(),
            "blocks are recorded as they CLOSE, innermost first");
        assertNull(DslParser.detectUnsupportedConstruct(dsl));
    }
}
