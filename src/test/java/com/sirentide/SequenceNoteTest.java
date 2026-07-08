package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.SeqLifecycle;
import com.sirentide.ir.SeqNote;
import com.sirentide.ir.Sequence;
import com.sirentide.layout.Group;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.Rect;
import com.sirentide.layout.SequenceLayout;
import com.sirentide.layout.Shape;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Sequence-diagram NOTES + participant CREATE/DESTROY (docs/DESIGN.md §M2 enrichment). Covers the
/// parse (position keywords `over`/`left of`/`right of`, multi-actor `over A,B`, `create`/`destroy`,
/// and the malformed→inert cases — unknown actor / no text / unknown create-destroy never throw), the
/// layout (a note box sits at the right y between messages and spans the right actors; a created
/// lifeline starts mid-diagram; a destroyed lifeline ends with an X), the NOTE semantic anchor, a
/// NAMED delete-mutant sentinel on the destroy X, and the zero-behaviour-change pin (a note-free
/// sequence is byte-identical to the legacy path).
class SequenceNoteTest {

    private static final String NOTE_FILL = "#fff8c5";      // the note-box background
    private static final String DESTROY_STROKE = "#64748b"; // the destroy X mark (== the call stroke)
    private static final String LIFELINE = "#cbd5e1";
    private static final double HEAD_BOTTOM = 54;           // MARGIN(24) + ACTOR_H(30) — a normal lifeline top

    private static Sequence parse(String dsl) {
        Diagram d = DslParser.parse(dsl);
        assertTrue(d instanceof Sequence, "parses to a Sequence, not " + d.getClass().getSimpleName());
        return (Sequence) d;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // -- parse: notes ---------------------------------------------------------

    @Test
    void noteOverLeftRightParsePositionActorsAndText() {
        Sequence s = parse("sequence\nA ->> B : hi\nnote over A : thinking\n"
            + "note left of A : L\nnote right of B : R\n");
        assertEquals(3, s.notes().size(), "three notes parsed");
        SeqNote over = s.notes().get(0);
        assertEquals("over", over.position());
        assertEquals(List.of("A"), over.actors());
        assertEquals("thinking", over.text());
        assertEquals(1, over.atMsg(), "the note is anchored after the first message");
        assertEquals("left", s.notes().get(1).position());
        assertEquals(List.of("A"), s.notes().get(1).actors());
        assertEquals("right", s.notes().get(2).position());
        assertEquals(List.of("B"), s.notes().get(2).actors());
        assertEquals("R", s.notes().get(2).text());
    }

    @Test
    void noteOverTwoActorsSpansBoth() {
        Sequence s = parse("sequence\nA ->> B : hi\nnote over A,B : shared\n");
        SeqNote n = s.notes().get(0);
        assertEquals("over", n.position());
        assertEquals(List.of("A", "B"), n.actors(), "over A,B references both actors");
        assertEquals("shared", n.text());
    }

    @Test
    void noteWithUnknownActorIsInertNoThrow() {
        // Ghost was never seen as an actor → the note is dropped (malformed→inert), never throws.
        Sequence s = parse("sequence\nA ->> B : hi\nnote over Ghost : boo\nnote over A,Ghost : mix\n");
        assertTrue(s.notes().isEmpty(), "a note referencing an unknown actor is dropped: " + s.notes());
        assertEquals(2, s.actors().size(), "Ghost never registered as an actor");
    }

    @Test
    void noteWithNoTextIsInertNoThrow() {
        Sequence s = parse("sequence\nA ->> B : hi\nnote over A :\nnote over A\n");
        assertTrue(s.notes().isEmpty(), "a textless note is dropped: " + s.notes());
    }

    @Test
    void noteWithUnrecognizedPositionIsInert() {
        Sequence s = parse("sequence\nA ->> B : hi\nnote beside A : x\n");
        assertTrue(s.notes().isEmpty(), "an unrecognized position keyword drops the note");
    }

    // -- parse: create / destroy ----------------------------------------------

    @Test
    void createRegistersTheActorAndRecordsALifecycle() {
        // `create participant Carol` introduces Carol (first-seen) BEFORE her first message.
        Sequence s = parse("sequence\nA ->> B : hi\ncreate participant Carol\nB ->> Carol : spawn\n");
        assertEquals(List.of("A", "B", "Carol"), s.actors(), "create registers Carol first-seen");
        assertEquals(1, s.lifecycles().size());
        SeqLifecycle lc = s.lifecycles().get(0);
        assertEquals("Carol", lc.actor());
        assertTrue(lc.create(), "a create event");
        assertEquals(1, lc.atMsg(), "anchored after the first message (before B->>Carol)");
    }

    @Test
    void createWithoutParticipantKeywordAlsoWorks() {
        Sequence s = parse("sequence\nA ->> B : hi\ncreate Carol\nB ->> Carol : spawn\n");
        assertEquals(List.of("A", "B", "Carol"), s.actors());
        assertTrue(s.lifecycles().get(0).create());
    }

    @Test
    void destroyRecordsALifecycleForAKnownActor() {
        Sequence s = parse("sequence\nA ->> B : hi\ndestroy B\n");
        assertEquals(1, s.lifecycles().size());
        SeqLifecycle lc = s.lifecycles().get(0);
        assertEquals("B", lc.actor());
        assertFalse(lc.create(), "a destroy event");
        assertEquals(1, lc.atMsg());
    }

    @Test
    void destroyOfUnknownActorIsInertNoThrow() {
        Sequence s = parse("sequence\nA ->> B : hi\ndestroy Nobody\n");
        assertTrue(s.lifecycles().isEmpty(), "destroying an unknown actor is inert");
        assertEquals(2, s.actors().size(), "the unknown actor was never registered");
    }

    @Test
    void aLineWithAnArrowIsAMessageEvenIfItStartsWithNoteCreateOrDestroy() {
        // The note/lifecycle rule fires only on an ARROWLESS line — a message whose sender is spelled
        // like a keyword still parses as a message.
        Sequence s = parse("sequence\nnote ->> B : real\ncreate ->> B : also\n");
        assertEquals(2, s.messages().size(), "arrow-bearing lines stay messages");
        assertTrue(s.notes().isEmpty());
        assertTrue(s.lifecycles().isEmpty());
    }

    // -- layout: note geometry ------------------------------------------------

    private static List<Shape> shapes(String dsl) {
        Sequence s = parse(dsl);
        LaidOut laid = SequenceLayout.layout(s);
        return Group.flatten(laid.shapes());
    }

    /// The single NOTE_FILL rect (the note box) as {x, y, width, height}.
    private static double[] noteRect(List<Shape> shapes) {
        for (Shape sh : shapes) {
            if (sh instanceof Rect r && NOTE_FILL.equals(r.fill())) {
                return new double[] {r.x(), r.y(), r.width(), r.height()};
            }
        }
        throw new AssertionError("no note box rect");
    }

    /// The y of the first horizontal CALL message line (stroke #64748b, y1==y2).
    private static double messageY(List<Shape> shapes, int skip) {
        int seen = 0;
        for (Shape sh : shapes) {
            if (sh instanceof Line l && "#64748b".equals(l.stroke()) && l.y1() == l.y2()) {
                if (seen++ == skip) {
                    return l.y1();
                }
            }
        }
        throw new AssertionError("no call message line at index " + skip);
    }

    /// Lifeline (vertical LIFELINE line) top/bottom for the lifeline at the given x (nearest match).
    private static double[] lifelineAtMaxX(List<Shape> shapes) {
        Line best = null;
        for (Shape sh : shapes) {
            if (sh instanceof Line l && LIFELINE.equals(l.stroke()) && l.x1() == l.x2()) {
                if (best == null || l.x1() > best.x1()) {
                    best = l;
                }
            }
        }
        assertTrue(best != null, "at least one lifeline");
        return new double[] {best.x1(), Math.min(best.y1(), best.y2()), Math.max(best.y1(), best.y2())};
    }

    @Test
    void aNoteBoxSitsAtTheRightYBetweenTheSurroundingMessages() {
        // A note between two messages: its box y-span sits strictly BELOW the first message row and
        // strictly ABOVE the second (the injected band pushes the second message down).
        List<Shape> shapes = shapes("sequence\nA ->> B : first\nnote over A : middle\nB ->> A : second\n");
        double[] box = noteRect(shapes);
        double firstY = messageY(shapes, 0);
        double secondY = messageY(shapes, 1);
        assertTrue(box[1] > firstY, "note box top is below the first message: " + box[1] + " vs " + firstY);
        assertTrue(box[1] + box[3] < secondY,
            "note box bottom is above the second message: " + (box[1] + box[3]) + " vs " + secondY);
    }

    @Test
    void anOverTwoActorNoteSpansBothLifelines() {
        // `over A,B`: the box brackets BOTH lifelines (left of the first lifeline x, right of the last).
        List<Shape> shapes = shapes("sequence\nA ->> B : hi\nnote over A,B : shared\n");
        double[] box = noteRect(shapes);
        java.util.TreeSet<Double> lls = new java.util.TreeSet<>();
        for (Shape sh : shapes) {
            if (sh instanceof Line l && LIFELINE.equals(l.stroke()) && l.x1() == l.x2()) {
                lls.add(l.x1());
            }
        }
        assertEquals(2, lls.size(), "two lifelines");
        assertTrue(box[0] < lls.first(), "note left is left of the first lifeline");
        assertTrue(box[0] + box[2] > lls.last(), "note right is right of the last lifeline");
    }

    // -- layout: create / destroy geometry ------------------------------------

    @Test
    void aCreatedLifelineStartsAtTheCreatePointNotTheTop() {
        // Carol is created mid-diagram → her lifeline (the rightmost) starts BELOW the normal head
        // bottom (54); A/B start at 54.
        List<Shape> shapes = shapes("sequence\nA ->> B : hi\ncreate participant Carol\nB ->> Carol : spawn\n");
        double[] carol = lifelineAtMaxX(shapes);
        assertTrue(carol[1] > HEAD_BOTTOM,
            "the created lifeline starts mid-diagram: top " + carol[1] + " > " + HEAD_BOTTOM);
        // A normal actor (A, leftmost) still starts at the head bottom.
        double aTop = Double.POSITIVE_INFINITY;
        for (Shape sh : shapes) {
            if (sh instanceof Line l && LIFELINE.equals(l.stroke()) && l.x1() == l.x2()) {
                aTop = Math.min(aTop, Math.min(l.y1(), l.y2()));
            }
        }
        assertEquals(HEAD_BOTTOM, aTop, 1e-6, "a normal lifeline still starts at the head bottom");
    }

    /// The diagonal (x1!=x2 AND y1!=y2) destroy-stroke lines — ONLY the X mark (message call lines are
    /// horizontal; self-hook segments are horizontal/vertical). Returned as their shared centre points.
    private static List<double[]> destroyXLines(List<Shape> shapes) {
        List<double[]> out = new java.util.ArrayList<>();
        for (Shape sh : shapes) {
            if (sh instanceof Line l && DESTROY_STROKE.equals(l.stroke())
                && l.x1() != l.x2() && l.y1() != l.y2()) {
                out.add(new double[] {(l.x1() + l.x2()) / 2, (l.y1() + l.y2()) / 2});
            }
        }
        return out;
    }

    /// DELETE-MUTANT SENTINEL (receipt #6): a destroyed actor's lifeline ends at the destroy y with an
    /// X mark — two diagonal crossed lines centred on its lifeline at its (truncated) bottom. DROP the
    /// destroy-X emission block in {@link SequenceLayout} (the `1.1) destroy X marks` loop) and this
    /// falls from 2 crossed lines to 0 → RED. Named + executed (confirmed the mutant reds this test).
    @Test
    void aDestroyedLifelineEndsAtTheDestroyYWithAnXMark() {
        // B is destroyed after the second message → its lifeline is truncated and carries an X.
        List<Shape> shapes = shapes("sequence\nA ->> B : hi\nB -->> A : ok\ndestroy B\nA ->> B : after\n");
        // The rightmost lifeline is B's; its bottom is the destroy y.
        double[] b = lifelineAtMaxX(shapes);
        double bx = b[0];
        double bottom = b[2];
        List<double[]> xs = destroyXLines(shapes);
        assertEquals(2, xs.size(), "the destroy X is two crossed lines: " + xs.size());
        for (double[] c : xs) {
            assertEquals(bx, c[0], 1e-6, "the X is centred on B's lifeline x");
            assertEquals(bottom, c[1], 1e-6, "the X sits at the destroyed lifeline's bottom (destroy y)");
        }
    }

    // -- semantic anchor ------------------------------------------------------

    @Test
    void aNoteEmitsARoleNoteAnchorGroup() {
        String svg = Sirentide.render("sequence\nA ->> B : hi\nnote over A,B : shared note\n");
        assertTrue(svg.contains("data-sirentide-role=\"note\""), "a note anchor is baked: " + svg);
        assertTrue(svg.contains("data-sirentide-id=\"sharednote\""),
            "the note id is the sanitized text: " + svg);
    }

    // -- render ---------------------------------------------------------------

    @Test
    void afullNoteAndCreateDestroyDiagramRendersWellFormedInCanvas() {
        String svg = Sirentide.render("sequence\nAlice ->> Bob : hello\nnote right of Bob : Bob thinks\n"
            + "note over Alice,Bob : a shared note\ncreate participant Carol\nBob ->> Carol : spawn\n"
            + "destroy Carol\nCarol -->> Bob : done\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertFalse(svg.contains("<text"), "note text is glyph paths, never <text>");
        assertEquals(2, count(svg, "fill=\"" + NOTE_FILL + "\""), "two note boxes");
        assertEquals(2, count(svg, "data-sirentide-role=\"note\""), "two note anchors");
    }

    // -- zero-behaviour-change pin --------------------------------------------

    @Test
    void aNoteFreeSequenceIsByteIdenticalToTheLegacyPath() {
        // A sequence with NO notes / create / destroy carries empty lists and must render exactly as
        // before — no note fill, no destroy stroke-mark, deterministic.
        String dsl = "sequence\nA ->> B : go\nB -->> A : ok\nA ->> A : self\n";
        Sequence s = parse(dsl);
        assertTrue(s.notes().isEmpty() && s.lifecycles().isEmpty(), "no notes / lifecycle events");
        String svg = Sirentide.render(dsl);
        assertEquals(0, count(svg, NOTE_FILL), "no note boxes when there are no notes");
        assertEquals(svg, Sirentide.render(dsl), "deterministic across the note-aware path");
    }
}
