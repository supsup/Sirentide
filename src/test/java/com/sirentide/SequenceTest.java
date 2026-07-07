package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.Sequence;
import com.sirentide.parse.DslParser;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Sirentide's sixth diagram type: a sequence diagram — actor heads + lifelines + arrowed labeled
/// messages, laid out by pure grid arithmetic (docs/DESIGN.md §M1, the founding go/no-go
/// demonstrator). Tests parse (first-seen actors, the reply arrow, optional labels, malformed drops,
/// self-messages), the contract-clean render (rects + lifelines + message lines/arrowheads + glyph
/// labels, no <text>), and the containment invariant (no x escapes the declared canvas width).
class SequenceTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static Sequence parse(String dsl) {
        Diagram d = DslParser.parse(dsl);
        assertTrue(d instanceof Sequence, "parses to a Sequence, not " + d.getClass().getSimpleName());
        return (Sequence) d;
    }

    // -- parse ----------------------------------------------------------------

    @Test
    void noSpaceColonLabelParses() {
        // REGRESSION (Lattice, sirentide/33): the verdict-fix batch split labels at " : "
        // (space-colon-space), silently breaking the common no-space form — `A ->> B: hi`
        // parsed as a bogus actor "B: hi" with NO label. The delimiter is the FIRST colon,
        // any spacing; the label is stripped BEFORE the arrow scan so arrows-in-labels stay inert.
        Sequence s = (Sequence) DslParser.parse("sequence\nA ->> B: hi\n");
        assertEquals(java.util.List.of("A", "B"), s.actors(), "B, not 'B: hi'");
        assertEquals("hi", s.messages().get(0).label());
        Sequence tight = (Sequence) DslParser.parse("sequence\nA ->> B:hi\nB -->> A : ok: sure\n");
        assertEquals("hi", tight.messages().get(0).label(), "tight colon form");
        assertEquals("ok: sure", tight.messages().get(1).label(), "a colon INSIDE the label survives");
    }

    @Test
    void parsesActorsFirstSeenIncludingBothEndpoints() {
        Sequence s = parse("sequence\nAlice ->> Bob : Request token\nBob -->> Alice : Token\n"
            + "Carol ->> Alice : Ping\n");
        // First-seen order across BOTH endpoints: Alice, Bob (from msg1), then Carol (from msg3).
        assertEquals(List.of("Alice", "Bob", "Carol"), s.actors());
    }

    @Test
    void replyArrowSetsReplyAndCallDoesNot() {
        Sequence s = parse("sequence\nAlice ->> Bob : Request token\nBob -->> Alice : Token\n");
        assertEquals(2, s.messages().size());
        assertFalse(s.messages().get(0).reply(), "->> is a call");
        assertEquals("Request token", s.messages().get(0).label());
        assertTrue(s.messages().get(1).reply(), "-->> is a reply");
        assertEquals("Token", s.messages().get(1).label());
        // The longer -->> wins even though it contains ->> as a substring.
        assertEquals("Bob", s.messages().get(1).from());
        assertEquals("Alice", s.messages().get(1).to());
    }

    @Test
    void labelIsOptional() {
        Sequence s = parse("sequence\nAlice ->> Bob\n");
        assertEquals(1, s.messages().size());
        assertNull(s.messages().get(0).label(), "no colon → no label");
        assertEquals("Alice", s.messages().get(0).from());
        assertEquals("Bob", s.messages().get(0).to());
    }

    @Test
    void malformedLinesAreDroppedNotThrown() {
        Sequence s = parse("sequence\nA ->> B : x\nno arrow here\n ->> B\nA ->> \nC ->> D : y\n");
        // Dropped: no-arrow line, empty-from " ->> B", empty-to "A ->> ". Kept: A→B and C→D.
        assertEquals(2, s.messages().size(), "only the two well-formed messages");
        assertEquals(List.of("A", "B", "C", "D"), s.actors());
    }

    @Test
    void selfMessageParses() {
        Sequence s = parse("sequence\nAlice ->> Alice : Validate locally\n");
        assertEquals(List.of("Alice"), s.actors(), "a self-message registers the actor once");
        SeqMessage m = s.messages().get(0);
        assertEquals(m.from(), m.to(), "a self-message has from == to");
        assertEquals("Validate locally", m.label());
    }

    @Test
    void emptyBodyIsASequenceNotEmpty() {
        Sequence s = parse("sequence\n");
        assertEquals(0, s.actors().size());
        assertEquals(0, s.messages().size());
    }

    // -- FIX 1: arrow aliases `->` / `-->` ------------------------------------

    @Test
    void bareArrowAliasesAreAcceptedAsCallAndReply() {
        // `->` == `->>` (call), `-->` == `-->>` (reply). Both used to render SILENTLY EMPTY.
        Sequence s = parse("sequence\nA -> B : hi\nA --> B : ok\n");
        assertEquals(2, s.messages().size(), "both alias forms parse to a message");
        assertFalse(s.messages().get(0).reply(), "-> is a call");
        assertEquals("hi", s.messages().get(0).label());
        assertTrue(s.messages().get(1).reply(), "--> is a reply");
        assertEquals("ok", s.messages().get(1).label());
    }

    @Test
    void aliasScanIsLongestFirstSoLongerFormsNeverMisSplit() {
        // `->>` contains `->`; `-->>` contains `-->`/`->>`/`->`. The scan must not mis-split them.
        Sequence s = parse("sequence\nAlice -->> Bob : reply\nBob ->> Alice : call\n");
        assertEquals("Alice", s.messages().get(0).from());
        assertEquals("Bob", s.messages().get(0).to());
        assertTrue(s.messages().get(0).reply(), "-->> stays a reply, not mis-split to ->>");
        assertFalse(s.messages().get(1).reply(), "->> stays a call, not mis-split to ->");
    }

    // -- FIX 4: operator-scan (arrow in the label is inert) -------------------

    @Test
    void arrowInsideTheLabelIsInertOneMessageLabelIntact() {
        // Split at the FIRST " : " first, THEN scan the HEAD — so the `->` inside the post-colon
        // label is never treated as the message arrow (a blind indexOf would mis-split here).
        Sequence s = parse("sequence\nA ->> B : retry -> escalate\n");
        assertEquals(1, s.messages().size(), "exactly ONE message");
        SeqMessage m = s.messages().get(0);
        assertEquals("A", m.from());
        assertEquals("B", m.to());
        assertFalse(m.reply(), "the head arrow ->> is a call");
        assertEquals("retry -> escalate", m.label(), "the label (with its arrow) is intact");
    }

    // -- FIX 2: non-empty malformed body degrades VISIBLY --------------------

    @Test
    void allMalformedBodyDegradesVisiblyNotToInertShell() {
        // Every line malformed → zero actors, but the body was NON-EMPTY: render a visible message,
        // never the inert blank shell (width 0).
        String svg = Sirentide.render("sequence\ngarbage line\nmore garbage\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertTrue(svg.contains("<path"), "the degrade message renders as glyph paths");
        assertFalse(svg.contains("width=\"0\""), "NOT the inert width-0 shell");
    }

    @Test
    void emptyBodyStaysAMinimalBlankCanvasNotADegradeMessage() {
        // A bare `sequence` header (no body) is an intentional empty diagram → no degrade message.
        String svg = Sirentide.render("sequence\n");
        assertFalse(svg.contains("<path"), "no degrade message for an intentionally empty body");
    }

    // -- render ---------------------------------------------------------------

    @Test
    void nActorsRenderNRectsAndNLifelines() {
        String svg = Sirentide.render("sequence\nA ->> B : x\nB ->> C : y\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertEquals(3, count(svg, "<rect"), "one head box per actor");
        // Lifelines are the ONLY light-stroke lines; 3 actors → 3 lifelines (message lines add more).
        assertEquals(3, count(svg, "stroke=\"#cbd5e1\""), "one lifeline per actor");
        assertFalse(svg.contains("<text"), "labels are glyph paths, never <text>");
    }

    @Test
    void callAndReplyRenderDistinctArrowheads() {
        // A call adds a FILLED-triangle <path>; a reply adds an OPEN V (two extra <line>s, no path).
        String call = Sirentide.render("sequence\nA ->> B\n");
        String reply = Sirentide.render("sequence\nA -->> B\n");
        // Same actors → same 2 head-label glyph paths + 2 lifelines. The call's message is 1 line +
        // 1 arrow path; the reply's is 1 line + 2 V lines + 0 path.
        assertEquals(3, count(call, "<line"), "2 lifelines + 1 call line");
        assertEquals(5, count(reply, "<line"), "2 lifelines + 1 reply line + 2 open-V lines");
        assertEquals(count(call, "<line") + 2, count(reply, "<line"), "the open V is two extra lines");
        assertEquals(count(reply, "<path") + 1, count(call, "<path"), "only the call has a filled head");
    }

    @Test
    void messageLabelAddsGlyphPaths() {
        String labeled = Sirentide.render("sequence\nA ->> B : hello there\n");
        String plain = Sirentide.render("sequence\nA ->> B\n");
        assertTrue(count(labeled, "<path") > count(plain, "<path"), "the message label adds glyph paths");
    }

    @Test
    void selfMessageRendersAHookAndArrowhead() {
        String svg = Sirentide.render("sequence\nAlice ->> Alice : loop\n");
        assertTrue(svg.startsWith("<svg"), "well-formed");
        assertEquals(1, count(svg, "<rect"), "one actor head");
        // The self-hook is 3 segments (out, down, back-to-base) + 1 lifeline = 4 call-coloured/light
        // lines; plus the filled-triangle arrowhead path.
        assertTrue(count(svg, "<line") >= 4, "hook segments + lifeline");
        assertTrue(count(svg, "<path") >= 1, "a filled arrowhead + label glyphs");
    }

    @Test
    void aliasFormsRenderDistinctArrowheadsLikeTheirDoubleForms() {
        // `->` renders a call (filled-triangle <path>); `-->` a reply (open-V, two extra <line>s, no
        // extra path) — the same heads that `->>` / `-->>` produce. Both label-free so the ONLY delta
        // is the arrowhead geometry (matching callAndReplyRenderDistinctArrowheads for the alias forms).
        String call = Sirentide.render("sequence\nA -> B\n");
        String reply = Sirentide.render("sequence\nA --> B\n");
        assertEquals(count(call, "<line") + 2, count(reply, "<line"), "the open V is two extra lines");
        assertEquals(count(reply, "<path") + 1, count(call, "<path"), "only the call has a filled head");
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "sequence\nA ->> B : go\nB -->> A : ok\nA ->> A : self\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    // -- geometry -------------------------------------------------------------

    private static double svgWidth(String svg) {
        Matcher w = Pattern.compile("<svg[^>]*\\swidth=\"([0-9.]+)\"").matcher(svg);
        assertTrue(w.find(), "svg root has a width");
        return Double.parseDouble(w.group(1));
    }

    private static double svgHeight(String svg) {
        Matcher h = Pattern.compile("<svg[^>]*\\sheight=\"([0-9.]+)\"").matcher(svg);
        assertTrue(h.find(), "svg root has a height");
        return Double.parseDouble(h.group(1));
    }

    @Test
    void moreMessagesGrowTheCanvasTaller() {
        double one = svgHeight(Sirentide.render("sequence\nA ->> B : x\n"));
        double three = svgHeight(Sirentide.render("sequence\nA ->> B : x\nB ->> A : y\nA ->> B : z\n"));
        assertTrue(three > one, "3 messages are taller than 1: " + three + " vs " + one);
    }

    @Test
    void everyEmittedXStaysInsideTheCanvasWidth() {
        // A long label (must ellipsize + clamp) plus a trailing-actor SELF-message (must widen the
        // canvas) — the geometry-escape class. Scan every emitted x (line x1/x2, rect x/x+width,
        // and the x of each coordinate PAIR in a path d) and assert it lands in [0, width].
        String svg = Sirentide.render(
            "sequence\nAlice ->> Bob : A very very long message label that would overrun the canvas edge\n"
                + "Bob ->> Bob : self loop with its own long trailing label to force a widen\n");
        double w = svgWidth(svg);
        // <line> x1/x2
        Matcher line = Pattern.compile("<line x1=\"([\\-0-9.]+)\" y1=\"[\\-0-9.]+\" x2=\"([\\-0-9.]+)\"")
            .matcher(svg);
        while (line.find()) {
            assertInRange(Double.parseDouble(line.group(1)), w, "line x1");
            assertInRange(Double.parseDouble(line.group(2)), w, "line x2");
        }
        // <rect> x and x+width
        Matcher rect = Pattern.compile("<rect x=\"([\\-0-9.]+)\" y=\"[\\-0-9.]+\" width=\"([\\-0-9.]+)\"")
            .matcher(svg);
        while (rect.find()) {
            double x = Double.parseDouble(rect.group(1));
            assertInRange(x, w, "rect x");
            assertInRange(x + Double.parseDouble(rect.group(2)), w, "rect x+width");
        }
        // <path d="..."> — every command in this layout takes coordinate PAIRS, so the numbers come
        // in (x, y) order: even-indexed numeric tokens are x's.
        Matcher path = Pattern.compile("<path d=\"([^\"]*)\"").matcher(svg);
        Pattern num = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
        while (path.find()) {
            Matcher nums = num.matcher(path.group(1));
            int idx = 0;
            while (nums.find()) {
                if (idx % 2 == 0) {
                    assertInRange(Double.parseDouble(nums.group()), w, "path x");
                }
                idx++;
            }
        }
    }

    private static void assertInRange(double x, double width, String what) {
        assertTrue(x >= 0 && x <= width, what + " " + x + " escapes canvas width " + width);
    }
}
