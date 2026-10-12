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
/// `rect`) is REFUSED and NAMED (audit 2026-10-12 finding 5; lead ruling "refuse first, fail
/// closed").
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
    void eachUnsupportedOpenerIsRefusedOnItsOwn() {
        // One keyword per assertion, under both header spellings.
        assertRefusedNaming("sequenceDiagram\nA ->> B : x\ncritical connect\nA ->> B : y\nend\n",
            "critical", 3);
        assertRefusedNaming("sequence\nA ->> B : x\nbreak when down\nend\n", "break", 3);
        assertRefusedNaming("sequence\nrect rgb(0, 255, 0)\nA ->> B : x\nend\n", "rect", 2);
        assertRefusedNaming("sequence\nopt\nA ->> B : x\nend\n", "opt", 2);
    }

    @Test
    void anArrowInsideTheOpenersLabelAfterAColonDoesNotHideIt() {
        // The scanner reads the pre-colon HEAD, the same place the message parse looks for its
        // arrow. A scanner reading the whole line takes the label's `->` for a message arrow,
        // skips the opener, and the `end` below closes whatever encloses it.
        assertRefusedNaming("sequence\nA ->> B : x\nopt retry : a -> b\nA ->> B : y\nend\n", "opt", 3);
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
