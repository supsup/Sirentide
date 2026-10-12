package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.SeqNote;
import com.sirentide.ir.Sequence;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// An arrow token inside a note's TEXT no longer makes the note vanish (audit 2026-10-12
/// finding 4, the note half).
///
/// THIS ONE IS A FIX, NOT A REFUSAL, and it is in its own commit so it can be dropped on its own.
/// The note grammar is `note (over|left of|right of) <actor> : text`, the text is free text, and
/// parseSequence's own comment already says an arrow after the colon is inert in a message label.
/// The keyword guard did not follow that rule: it scanned the WHOLE line, so
///
///     note over A : retry -> escalate
///
/// read as arrow-bearing, skipped the note branch, found no arrow in the head `note over A`, and
/// was dropped as a malformed message. No note, no caveat, outcome OK. Refusing it instead would
/// refuse a form the grammar documents as legal.
///
/// THE BLOCK-LABEL HALF OF THE FINDING IS NOT ADDRESSED. `alt cache -> miss` has no colon, so its
/// arrow is in the head, and it is the same shape as a legal message from a multi-word actor.
class SequenceNoteArrowTextTest {

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

    @Test
    void aNoteWhoseTextHoldsAnArrowIsStillANote() {
        // The audit's own input.
        Sequence s = sequence("sequence\nA ->> B : hi\nnote over A : retry -> escalate\n");
        assertEquals(1, s.notes().size(), "the note must survive an arrow in its text: " + s.notes());
        SeqNote note = s.notes().get(0);
        assertEquals("over", note.position());
        assertEquals(List.of("A"), note.actors());
        assertEquals("retry -> escalate", note.text(), "the arrow is text, kept verbatim");
        assertEquals(1, note.atMsg(), "anchored after the one message before it");
        // And it minted nothing else: the line is a note, not a message between phantom actors.
        assertEquals(List.of("A", "B"), s.actors());
        assertEquals(List.of("A->B[hi]"), messages(s));
    }

    @Test
    void everyArrowSpellingInANotesTextIsContent() {
        // One spelling per iteration; scanSeqArrow knows four tokens and each must be inert here.
        for (String arrow : new String[] {"->", "->>", "-->", "-->>"}) {
            String text = "x " + arrow + " y";
            Sequence s = sequence("sequence\nA ->> B : hi\nnote right of B : " + text + "\n");
            assertEquals(1, s.notes().size(), "`" + arrow + "` in note text must not drop the note");
            assertEquals(text, s.notes().get(0).text());
            assertEquals("right", s.notes().get(0).position());
            assertEquals(List.of("A->B[hi]"), messages(s), "`" + arrow + "` minted no message");
        }
    }

    @Test
    void aNoteWithoutAnArrowParsesExactlyAsBefore() {
        // THE POSITIVE CONTROL: the form that always worked, same assertions.
        Sequence s = sequence("sequence\nA ->> B : hi\nnote over A,B : plain text\n");
        assertEquals(1, s.notes().size());
        assertEquals(List.of("A", "B"), s.notes().get(0).actors());
        assertEquals("plain text", s.notes().get(0).text());
        assertEquals(List.of("A", "B"), s.actors());
        assertEquals(List.of("A->B[hi]"), messages(s));
    }

    @Test
    void anArrowInTheHeadStillMakesTheLineAMessage() {
        // The widening reads the pre-colon HEAD, so it cannot swallow a real message. Two cases:
        // a sender spelled `note` (SequenceNoteTest pins this too), and an ordinary message whose
        // LABEL holds an arrow, which was already inert and must stay a message, not become a note.
        Sequence s = sequence("sequence\nnote ->> B : real\nA ->> B : retry -> escalate\n");
        assertTrue(s.notes().isEmpty(), "neither line is a note: " + s.notes());
        assertEquals(List.of("note", "B", "A"), s.actors());
        assertEquals(List.of("note->B[real]", "A->B[retry -> escalate]"), messages(s));
    }

    @Test
    void theWideningIsForNoteOnly() {
        // `create Carol : a -> b` is not a form with text after a colon. If the guard were widened
        // for every keyword, this line would reach the create branch and register an actor
        // literally named `Carol : a -> b`. It stays what it was: a dropped line.
        Sequence s = sequence("sequence\nA ->> B : hi\ncreate Carol : a -> b\n");
        assertEquals(List.of("A", "B"), s.actors());
        assertTrue(s.lifecycles().isEmpty(), "no create event: " + s.lifecycles());
    }
}
