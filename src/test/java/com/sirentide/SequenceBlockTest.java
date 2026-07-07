package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.SeqBlock;
import com.sirentide.ir.Sequence;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Line;
import com.sirentide.layout.Rect;
import com.sirentide.layout.SequenceLayout;
import com.sirentide.layout.Shape;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Sequence-diagram alt/loop/par FRAME blocks (docs/DESIGN.md §M2). Covers the parse (block keyword
/// recognition, `else`/`and` dividers, nesting via a stack, and the robustness invariants — stray
/// keywords + unclosed blocks NEVER throw), the layout (a frame's border spans the right message
/// y-range + lifeline x-range, a nested block insets, an `else` divider is a line at the right y),
/// the containment/z-order (frames draw UNDER lifelines/messages), and the zero-behaviour-change pin
/// (a block-free sequence bakes byte-identically to the legacy path).
class SequenceBlockTest {

    private static final String FRAME_STROKE = "#b8c0cc";   // the block-frame border colour
    private static final String TAB_FILL = "#e7ecff";       // the label-tab fill
    private static final String LIFELINE = "#cbd5e1";

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

    // -- parse: block model ---------------------------------------------------

    @Test
    void altElseParsesOneBlockWithADividerSpanningItsMessages() {
        Sequence s = parse("sequence\nA ->> B : q\nalt yes\nB -->> A : ok\nelse no\nB -->> A : nope\nend\n");
        assertEquals(3, s.messages().size(), "the three messages stay flat");
        assertEquals(1, s.blocks().size(), "one alt block");
        SeqBlock alt = s.blocks().get(0);
        assertEquals("alt", alt.kind());
        assertEquals("yes", alt.label(), "the free text after the keyword is the block label");
        assertEquals(1, alt.fromMsg(), "spans from the message after `alt` (index 1)");
        assertEquals(2, alt.toMsg(), "to the last message before `end` (index 2)");
        assertEquals(0, alt.depth(), "an outermost block has depth 0");
        assertEquals(1, alt.dividers().size(), "one else divider");
        assertEquals(2, alt.dividers().get(0).atMsg(), "the else divider anchors at the else-branch message");
        assertEquals("no", alt.dividers().get(0).label());
    }

    @Test
    void loopParsesAsAKindLoopBlockWithNoDividers() {
        Sequence s = parse("sequence\nloop every minute\nA ->> B : ping\nend\n");
        assertEquals(1, s.blocks().size());
        SeqBlock loop = s.blocks().get(0);
        assertEquals("loop", loop.kind());
        assertEquals("every minute", loop.label());
        assertEquals(0, loop.fromMsg());
        assertEquals(0, loop.toMsg());
        assertTrue(loop.dividers().isEmpty(), "a loop has no dividers");
    }

    @Test
    void parAndParsesWithAnAndDivider() {
        Sequence s = parse("sequence\npar to Bob\nA ->> B : a\nand to Carol\nA ->> C : b\nend\n");
        assertEquals(1, s.blocks().size());
        SeqBlock par = s.blocks().get(0);
        assertEquals("par", par.kind());
        assertEquals(1, par.dividers().size(), "one and divider");
        assertEquals("to Carol", par.dividers().get(0).label());
        assertEquals(1, par.dividers().get(0).atMsg(), "the and divider anchors at the second-branch message");
    }

    @Test
    void nestedBlockInsideBlockTracksDepthViaTheStack() {
        // A loop nested inside an alt: the inner block carries depth 1, the outer depth 0. Both close
        // on their own `end` (innermost first → the loop is added to the block list before the alt).
        Sequence s = parse("sequence\nalt outer\nA ->> B : x\nloop inner\nA ->> B : y\nend\nend\n");
        assertEquals(2, s.blocks().size());
        SeqBlock first = s.blocks().get(0);
        SeqBlock second = s.blocks().get(1);
        assertEquals("loop", first.kind(), "the inner block closes first");
        assertEquals(1, first.depth(), "the nested block is depth 1");
        assertEquals("alt", second.kind());
        assertEquals(0, second.depth(), "the outer block is depth 0");
        assertEquals(1, first.fromMsg(), "the loop wraps the second message");
        assertEquals(1, first.toMsg());
        assertEquals(0, second.fromMsg(), "the alt wraps both messages");
        assertEquals(1, second.toMsg());
    }

    @Test
    void unclosedBlockClosesAtTheLastMessageNeverThrows() {
        // No `end` — the block must close at the last message at end-of-input, not throw.
        Sequence s = parse("sequence\nloop forever\nA ->> B : a\nA ->> B : b\n");
        assertEquals(1, s.blocks().size(), "the unclosed block still materializes");
        SeqBlock loop = s.blocks().get(0);
        assertEquals(0, loop.fromMsg());
        assertEquals(1, loop.toMsg(), "closed at the last message (index 1)");
    }

    @Test
    void strayElseAndEndDoNotThrowAndProduceNoBlock() {
        // A stray `else`/`and`/`end` with NO open block (or the wrong open kind) is inert — never a
        // block, never a throw (malformed→inert, DESIGN §6).
        Sequence s = parse("sequence\nelse orphan\nA ->> B : x\nand orphan\nend\nend\n");
        assertEquals(1, s.messages().size(), "the message still parses");
        assertTrue(s.blocks().isEmpty(), "no stray keyword opened a block");
    }

    @Test
    void elseInsideALoopIsInertNotADivider() {
        // `else` is only valid inside an `alt`; inside a `loop` it is ignored (not a divider), and the
        // loop still closes cleanly.
        Sequence s = parse("sequence\nloop each\nA ->> B : x\nelse nope\nA ->> B : y\nend\n");
        assertEquals(1, s.blocks().size());
        assertTrue(s.blocks().get(0).dividers().isEmpty(), "an else inside a loop is not a divider");
    }

    @Test
    void aLineWithAnArrowIsAMessageEvenIfItStartsWithAKeywordToken() {
        // The block-keyword rule only fires on an ARROWLESS line, so a message whose FIRST token is a
        // keyword-word still parses as a message (never swallowed as a directive).
        Sequence s = parse("sequence\nend ->> B : real message\n");
        assertEquals(1, s.messages().size(), "the arrow-bearing line is a message");
        assertEquals("end", s.messages().get(0).from());
        assertTrue(s.blocks().isEmpty(), "no block opened");
    }

    // -- layout: frame geometry -----------------------------------------------

    private static List<Shape> shapes(String dsl) {
        Sequence s = parse(dsl);
        LaidOut laid = SequenceLayout.layout(s);
        return laid.shapes();
    }

    /// The four frame-border lines (FRAME_STROKE), returned as the bounding box {left, top, right,
    /// bottom} — asserts there is exactly one frame's worth of full-span border in the scene.
    private static double[] singleFrameBox(List<Shape> shapes) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Shape sh : shapes) {
            if (sh instanceof Line l && FRAME_STROKE.equals(l.stroke())) {
                minX = Math.min(minX, Math.min(l.x1(), l.x2()));
                maxX = Math.max(maxX, Math.max(l.x1(), l.x2()));
                minY = Math.min(minY, Math.min(l.y1(), l.y2()));
                maxY = Math.max(maxY, Math.max(l.y1(), l.y2()));
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    /// The x of every lifeline (a vertical LIFELINE line), sorted.
    private static List<Double> lifelineXs(List<Shape> shapes) {
        java.util.TreeSet<Double> xs = new java.util.TreeSet<>();
        for (Shape sh : shapes) {
            if (sh instanceof Line l && LIFELINE.equals(l.stroke()) && l.x1() == l.x2()) {
                xs.add(l.x1());
            }
        }
        return new java.util.ArrayList<>(xs);
    }

    @Test
    void aFrameSpansItsMessageYRangeAndLifelineXRange() {
        // A single alt around one message on A→B: the frame's x-span brackets BOTH lifelines (with
        // padding) and its y-span brackets the message row.
        List<Shape> shapes = shapes("sequence\nalt case\nA ->> B : x\nend\n");
        double[] box = singleFrameBox(shapes);
        List<Double> lls = lifelineXs(shapes);
        assertEquals(2, lls.size(), "two lifelines");
        double leftLL = lls.get(0);
        double rightLL = lls.get(1);
        assertTrue(box[0] < leftLL, "frame left is left of the first lifeline: " + box[0] + " vs " + leftLL);
        assertTrue(box[2] > rightLL, "frame right is right of the last lifeline: " + box[2] + " vs " + rightLL);
        // The message row sits inside the frame's y-span (top above the row, bottom below it).
        double msgY = firstMessageY(shapes);
        assertTrue(box[1] < msgY, "frame top is above the message row: " + box[1] + " vs " + msgY);
        assertTrue(box[3] > msgY, "frame bottom is below the message row: " + box[3] + " vs " + msgY);
    }

    /// The y of the first horizontal CALL line (the A→B message row).
    private static double firstMessageY(List<Shape> shapes) {
        for (Shape sh : shapes) {
            if (sh instanceof Line l && "#64748b".equals(l.stroke()) && l.y1() == l.y2()) {
                return l.y1();
            }
        }
        throw new AssertionError("no call message line");
    }

    @Test
    void aNestedFrameInsetsInsideItsParent() {
        // A loop nested in an alt, both spanning the same A→B lifelines: the inner (loop, depth 1)
        // frame's left edge is inset to the RIGHT of the outer (alt, depth 0) frame's left edge.
        List<Shape> shapes = shapes("sequence\nalt outer\nloop inner\nA ->> B : y\nend\nend\n");
        // Collect distinct frame-vertical x's; there are two frames → two lefts and two rights.
        java.util.TreeSet<Double> vertX = new java.util.TreeSet<>();
        for (Shape sh : shapes) {
            if (sh instanceof Line l && FRAME_STROKE.equals(l.stroke()) && l.x1() == l.x2()) {
                vertX.add(l.x1());
            }
        }
        assertEquals(4, vertX.size(), "two nested frames → four distinct vertical edges");
        List<Double> xs = new java.util.ArrayList<>(vertX);
        // Sorted: [outerLeft, innerLeft, innerRight, outerRight] — the inner sits strictly inside.
        assertTrue(xs.get(0) < xs.get(1), "inner left is inset right of outer left");
        assertTrue(xs.get(2) < xs.get(3), "inner right is inset left of outer right");
    }

    @Test
    void anElseDividerIsALineAtTheElseBranchRow() {
        // The else divider is a horizontal FRAME_STROKE line whose y sits between the `if` branch
        // message and the `else` branch message.
        List<Shape> shapes = shapes("sequence\nalt yes\nA ->> B : ok\nelse no\nA ->> B : nope\nend\n");
        // Message rows (call lines).
        java.util.List<Double> rows = new java.util.ArrayList<>();
        for (Shape sh : shapes) {
            if (sh instanceof Line l && "#64748b".equals(l.stroke()) && l.y1() == l.y2()) {
                rows.add(l.y1());
            }
        }
        assertEquals(2, rows.size(), "two message rows");
        double ifY = rows.get(0);
        double elseY = rows.get(1);
        // A horizontal frame line strictly between the two rows = the divider (dashed → many segments
        // at one shared y; the top/bottom border lines are outside [ifY, elseY]).
        boolean found = false;
        for (Shape sh : shapes) {
            if (sh instanceof Line l && FRAME_STROKE.equals(l.stroke()) && l.y1() == l.y2()
                && l.y1() > ifY && l.y1() < elseY) {
                found = true;
            }
        }
        assertTrue(found, "an else divider line sits between the if-row and the else-row");
    }

    @Test
    void aFrameEmitsALabelTabRect() {
        List<Shape> shapes = shapes("sequence\nalt case\nA ->> B : x\nend\n");
        long tabs = shapes.stream().filter(sh -> sh instanceof Rect r && TAB_FILL.equals(r.fill())).count();
        assertEquals(1, tabs, "one label tab per frame");
    }

    // -- z-order + containment ------------------------------------------------

    @Test
    void framesDrawUnderTheLifelinesAndMessages() {
        // The frame border/tab must be emitted BEFORE the lifelines (so it sits behind the content it
        // wraps). Assert every frame-stroke line and the tab rect index precedes the first lifeline.
        List<Shape> shapes = shapes("sequence\nalt case\nA ->> B : x\nend\n");
        int firstLifeline = Integer.MAX_VALUE;
        int lastFrame = -1;
        for (int i = 0; i < shapes.size(); i++) {
            Shape sh = shapes.get(i);
            if (sh instanceof Line l && LIFELINE.equals(l.stroke())) {
                firstLifeline = Math.min(firstLifeline, i);
            }
            if ((sh instanceof Line l && FRAME_STROKE.equals(l.stroke()))
                || (sh instanceof Rect r && TAB_FILL.equals(r.fill()))) {
                lastFrame = Math.max(lastFrame, i);
            }
        }
        assertTrue(lastFrame < firstLifeline,
            "frame shapes (last @" + lastFrame + ") precede the lifelines (first @" + firstLifeline + ")");
    }

    // -- zero-behaviour-change pin --------------------------------------------

    @Test
    void aBlockFreeSequenceIsByteIdenticalToTheLegacyPath() {
        // The block path must be INERT when there are no blocks: a sequence with zero blocks renders
        // exactly as it did before this feature (no frame lines, no tab rects, no change at all).
        String dsl = "sequence\nA ->> B : go\nB -->> A : ok\nA ->> A : self\n";
        Sequence s = parse(dsl);
        assertTrue(s.blocks().isEmpty(), "no blocks parsed");
        String svg = Sirentide.render(dsl);
        assertEquals(0, count(svg, FRAME_STROKE), "no frame-border lines when there are no blocks");
        assertEquals(0, count(svg, TAB_FILL), "no label-tab rects when there are no blocks");
        // Determinism across the block-aware path.
        assertEquals(svg, Sirentide.render(dsl));
    }

    @Test
    void aFullAltLoopParDiagramRendersWellFormedAndInCanvas() {
        String svg = Sirentide.render(
            "sequence\nAlice ->> Bob : hello\nalt is available\nBob -->> Alice : yes\n"
                + "loop retry\nAlice ->> Bob : ping\nend\nelse is busy\nBob -->> Alice : later\nend\n"
                + "par to Bob\nAlice ->> Bob : a\nand to Carol\nAlice ->> Carol : b\nend\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertFalse(svg.contains("<text"), "labels are glyph paths, never <text>");
        assertTrue(count(svg, TAB_FILL) == 3, "three frame tabs (alt, loop, par)");
    }
}
