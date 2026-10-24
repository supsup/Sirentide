package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.GoldenSvgTest;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.Divider;
import com.sirentide.ir.SeqBlock;
import com.sirentide.ir.SeqLifecycle;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.SeqNote;
import com.sirentide.ir.Sequence;
import com.sirentide.parse.DslParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// Real-frame visibility and the reserved annotation-time contract. The expected activation timing
/// comes from actual message arrows; expected gaps come from the independently emitted tab/divider
/// geometry, never from the production splitting helper or its metadata list.
class SequenceFrameLabelBandsTest {
    private static final String TAB_FILL = "#e7ecff";
    private static final String FRAME_STROKE = "#b8c0cc";
    private static final double EPS = 0.025;
    private static final double CLEARANCE = 2;

    @Test
    void actualDiagramKeepsEveryFrameLabelVisibleAndReportsNoOverlap() throws Exception {
        String dsl;
        try (var in = getClass().getResourceAsStream("/sequence/lane-live-acceptance-diagram1.sirentide")) {
            assertNotNull(in);
            dsl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        LaidOut laid = layout(dsl);
        assertEquals(18, messages(laid).size());
        assertEquals(3, tabs(laid).size());
        assertEquals(11, laid.frameLabels().size(), "three kinds, three guards and five branch labels");
        assertFalse(laid.activationBars().isEmpty(), "dropping the bars cannot clear the collision");
        assertMetadataIsEmitted(laid);
        assertFrameInkClear(laid);
        assertEquals(List.of(), OverlapLint.findings(laid), "same unmodified real DSL must be clean");
    }

    @Test
    void horizontallyUnrelatedActivationRetainsItsOutsideBandUnion() {
        Sequence seq = new Sequence(List.of("A", "B", "C"), List.of(
            call("A", "C", "open remote"), call("A", "B", "open"),
            reply("B", "A", "yes"), call("A", "B", "again"),
            reply("B", "A", "no"), reply("C", "A", "close remote")),
            "currentColor", null, true,
            List.of(new SeqBlock("alt", "yes", 2, 4, List.of(new Divider(3, "no")), 0)));
        LaidOut laid = SequenceLayout.layout(seq);
        double x = center(laid, "C") - 4;
        double rightmostLabel = laid.frameLabels().stream().mapToDouble(g -> bounds(g)[2]).max().orElseThrow();
        assertTrue(x > rightmostLabel, "C is horizontally clear of every frame label");
        List<Gap> gaps = annotationGaps(laid);
        assertEquals(2, gaps.size(), "one header and one else band");
        assertOutsideUnion(messageY(laid, 0), messageY(laid, 5), gaps, barsAt(laid, x));
        assertFrameInkClear(laid);
        assertMetadataIsEmitted(laid);
    }

    @Test
    void sameBoundaryNestedHeadersHaveSeparateTimeBands() {
        LaidOut laid = layout("sequence\nalt outer\nloop inner\npar work\n"
            + "A ->> B : go\nand other\nB -->> A : done\nend\nend\nend\n");
        List<Rect> tabs = new ArrayList<>(tabs(laid));
        tabs.sort(Comparator.comparingDouble(Rect::x));
        assertEquals(3, tabs.size());
        for (int i = 1; i < tabs.size(); i++) {
            assertTrue(tabs.get(i).y() > tabs.get(i - 1).y() + tabs.get(i - 1).height(),
                "inner header follows its ancestor instead of sharing its baseline");
        }
        assertTrue(messageY(laid, 0) > tabs.getLast().y() + tabs.getLast().height());
        assertFrameInkClear(laid);
        assertEquals(List.of(), OverlapLint.findings(laid));
    }

    @Test
    void equalDepthEventsRetainTheirDeclaredOrder() {
        Sequence seq = new Sequence(List.of("A", "B"), List.of(call("A", "B", "go")),
            "currentColor", null, true, List.of(
                new SeqBlock("alt", "first", 0, 0, List.of(), 0),
                new SeqBlock("loop", "second", 0, 0, List.of(), 0)));
        LaidOut laid = SequenceLayout.layout(seq);
        List<Rect> tabs = tabs(laid);
        assertEquals(2, tabs.size());
        assertTrue(tabs.get(0).y() + tabs.get(0).height() < tabs.get(1).y());
        assertTrue(bounds(laid.frameLabels().get(1))[3] < bounds(laid.frameLabels().get(3))[1],
            "declared header text retains its order too");
    }

    @Test
    void noteCreateDestroyAndNestedSelfActivationKeepTheirTiming() {
        Sequence seq = new Sequence(List.of("A", "B", "C"), List.of(
            call("A", "B", "open"), call("B", "C", "create call"),
            call("C", "C", "nested self"), reply("C", "B", "nested return"),
            reply("C", "B", "return")), "currentColor", null, true,
            List.of(new SeqBlock("alt", "created", 1, 4, List.of(new Divider(3, "returning")), 0)),
            List.of(new SeqNote("over", List.of("C"), "before creation", 1)),
            List.of(new SeqLifecycle("C", true, 1), new SeqLifecycle("C", false, 4)));
        LaidOut laid = SequenceLayout.layout(seq);
        Group actor = actor(laid, "C");
        Rect head = actor.members().stream().filter(Rect.class::isInstance).map(Rect.class::cast)
            .findFirst().orElseThrow();
        Group note = laid.shapes().stream().filter(Group.class::isInstance).map(Group.class::cast)
            .filter(g -> g.anchor().role() == SirentideRole.NOTE).findFirst().orElseThrow();
        Rect noteBox = note.members().stream().filter(Rect.class::isInstance).map(Rect.class::cast)
            .findFirst().orElseThrow();
        assertTrue(noteBox.y() + noteBox.height() < head.y());
        assertTrue(head.y() + head.height() < tabs(laid).getFirst().y());
        double cx = center(laid, "C");
        Line lifeline = leaves(laid).stream().filter(Line.class::isInstance).map(Line.class::cast)
            .filter(l -> "#cbd5e1".equals(l.stroke()) && close(l.x1(), cx)).findFirst().orElseThrow();
        assertEquals(head.y() + head.height(), lifeline.y1(), EPS);
        assertEquals(messageY(laid, 4), lifeline.y2(), EPS);
        List<Gap> gaps = annotationGaps(laid);
        assertOutsideUnion(messageY(laid, 1), messageY(laid, 4), gaps, barsAt(laid, cx - 4));
        assertOutsideUnion(messageY(laid, 2), messageY(laid, 3), gaps, barsAt(laid, cx));
        assertFrameInkClear(laid);
    }

    @Test
    void unknownMessagesAndDegenerateBlocksRemainInert() {
        List<String> actors = List.of("A", "B");
        List<SeqMessage> one = List.of(call("A", "B", "go"));
        LaidOut plain = SequenceLayout.layout(new Sequence(actors, one));
        for (SeqBlock block : List.of(new SeqBlock("alt", "empty", 0, -1, List.of(), 0),
                new SeqBlock("alt", "outside", 4, 6, List.of(new Divider(5, "gone")), 0))) {
            LaidOut invalid = SequenceLayout.layout(new Sequence(actors, one, "currentColor", null,
                true, List.of(block)));
            assertEquals(plain.shapes(), invalid.shapes());
            assertTrue(invalid.frameLabels().isEmpty());
        }
        Sequence skipped = new Sequence(actors, List.of(call("X", "Y", "skip"), one.getFirst()),
            "currentColor", null, true,
            List.of(new SeqBlock("alt", "guard", 0, 1, List.of(new Divider(0, "skip")), 0)));
        Sequence compact = new Sequence(actors, one, "currentColor", null, true,
            List.of(new SeqBlock("alt", "guard", 0, 0, List.of(), 0)));
        assertEquals(SequenceLayout.layout(compact).shapes(), SequenceLayout.layout(skipped).shapes());
    }

    @Test
    void blockFreeGoldenBytesStayUnchanged() throws Exception {
        for (String name : List.of("sequence", "sequence-notes")) {
            assertEquals(GoldenSvgTest.golden(name), Sirentide.render(GoldenSvgTest.fixtures().get(name)), name);
        }
    }

    @Test
    void excessiveFrameBandsFailBeforeGlyphOrSegmentExpansion() {
        SeqBlock block = new SeqBlock("alt", "guard", 0, 0, List.of(), 0);
        Sequence seq = new Sequence(List.of("A", "B"), List.of(call("A", "B", "go")),
            "currentColor", null, true, Collections.nCopies(20_001, block));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> SequenceLayout.layout(seq));
        assertTrue(failure.getMessage().contains("MAX_LAYOUT_WORK"));
        assertTrue(failure.getMessage().contains("frame label bands"));
    }

    @Test
    void invalidDividerRepetitionCannotAmplifyEventPreparationWithoutBound() {
        SeqBlock block = new SeqBlock("alt", "guard", 0, 0,
            Collections.nCopies(4_000, new Divider(-1, "inert")), 0);
        Sequence seq = new Sequence(List.of("A", "B"), List.of(call("A", "B", "go")),
            "currentColor", null, true, Collections.nCopies(300, block));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> SequenceLayout.layout(seq));
        assertTrue(failure.getMessage().contains("MAX_LAYOUT_WORK"));
        assertTrue(failure.getMessage().contains("frame event work"));
    }

    @Test
    void crossingActivationsAndHeadersCannotRetainUnlimitedSegments() {
        Sequence seq = new Sequence(List.of("A", "B"), Collections.nCopies(400, call("A", "B", "q")),
            "currentColor", null, true,
            Collections.nCopies(300, new SeqBlock("alt", "guard", 399, 399, List.of(), 0)));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> SequenceLayout.layout(seq));
        assertTrue(failure.getMessage().contains("MAX_LAYOUT_WORK"));
        assertTrue(failure.getMessage().contains("activation segments"));
    }

    private record Gap(double top, double bottom) {}

    private static List<Gap> annotationGaps(LaidOut laid) {
        List<Rect> tabs = tabs(laid);
        assertEquals(1, tabs.size(), "this independent band oracle uses one frame");
        Rect tab = tabs.getFirst();
        List<Gap> out = new ArrayList<>();
        out.add(new Gap(tab.y() - CLEARANCE, tab.y() + tab.height() + CLEARANCE));
        List<Double> dashedRows = leaves(laid).stream().filter(Line.class::isInstance).map(Line.class::cast)
            .filter(l -> FRAME_STROKE.equals(l.stroke()) && close(l.y1(), l.y2())
                && Math.abs(l.x2() - l.x1()) <= 5 + EPS)
            .map(Line::y1).distinct().toList();
        for (double y : dashedRows) {
            out.add(new Gap(y - tab.height() - CLEARANCE, y + CLEARANCE));
        }
        out.sort(Comparator.comparingDouble(Gap::top));
        return out;
    }

    private static void assertOutsideUnion(double start, double end, List<Gap> gaps, List<Rect> bars) {
        List<Gap> expected = new ArrayList<>();
        double cursor = start;
        for (Gap gap : gaps) {
            if (gap.bottom() <= cursor || gap.top() >= end) continue;
            if (gap.top() > cursor) expected.add(new Gap(cursor, gap.top()));
            cursor = Math.max(cursor, gap.bottom());
        }
        if (cursor < end) expected.add(new Gap(cursor, end));
        assertEquals(expected.size(), bars.size(), "complete outside-band union, without extra or lost segments");
        for (int i = 0; i < expected.size(); i++) {
            Rect bar = bars.get(i);
            assertEquals(expected.get(i).top(), bar.y(), EPS);
            assertEquals(expected.get(i).bottom(), bar.y() + bar.height(), EPS);
            assertEquals(8, bar.width(), EPS);
        }
    }

    private static void assertFrameInkClear(LaidOut laid) {
        for (Rect bar : laid.activationBars()) {
            assertTrue(Double.isFinite(bar.x()) && Double.isFinite(bar.y())
                && Double.isFinite(bar.width()) && Double.isFinite(bar.height()));
            assertTrue(bar.width() > 0 && bar.height() > 0);
            for (GlyphRun label : laid.frameLabels()) {
                double[] b = bounds(label);
                double w = Math.min(b[2], bar.x() + bar.width()) - Math.max(b[0], bar.x());
                double h = Math.min(b[3], bar.y() + bar.height()) - Math.max(b[1], bar.y());
                assertTrue(w <= 0 || h <= 0, "actual frame ink must clear every visible activation segment");
            }
        }
    }

    private static void assertMetadataIsEmitted(LaidOut laid) {
        Set<Shape> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
        emitted.addAll(leaves(laid));
        assertTrue(laid.frameLabels().stream().allMatch(emitted::contains));
        assertTrue(laid.activationBars().stream().allMatch(emitted::contains));
    }

    private static double[] bounds(GlyphRun run) {
        double[] b = OverlapLint.bounds(run.pathD());
        assertNotNull(b);
        return b;
    }

    private static List<Rect> barsAt(LaidOut laid, double x) {
        return laid.activationBars().stream().filter(r -> close(r.x(), x))
            .sorted(Comparator.comparingDouble(Rect::y)).toList();
    }

    private static List<Shape> leaves(LaidOut laid) {
        return Group.flatten(laid.shapes());
    }

    private static List<Rect> tabs(LaidOut laid) {
        return leaves(laid).stream().filter(Rect.class::isInstance).map(Rect.class::cast)
            .filter(r -> TAB_FILL.equals(r.fill())).toList();
    }

    private static List<Group> messages(LaidOut laid) {
        return laid.shapes().stream().filter(Group.class::isInstance).map(Group.class::cast)
            .filter(g -> g.anchor().role() == SirentideRole.MESSAGE).toList();
    }

    private static double messageY(LaidOut laid, int index) {
        return messages(laid).get(index).members().stream().filter(Line.class::isInstance).map(Line.class::cast)
            .filter(l -> close(l.y1(), l.y2())).mapToDouble(Line::y1).min().orElseThrow();
    }

    private static Group actor(LaidOut laid, String id) {
        return laid.shapes().stream().filter(Group.class::isInstance).map(Group.class::cast)
            .filter(g -> g.anchor().role() == SirentideRole.ACTOR && id.equals(g.anchor().id()))
            .findFirst().orElseThrow();
    }

    private static double center(LaidOut laid, String id) {
        Rect box = actor(laid, id).members().stream().filter(Rect.class::isInstance).map(Rect.class::cast)
            .findFirst().orElseThrow();
        return box.x() + box.width() / 2;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static LaidOut layout(String dsl) {
        return SequenceLayout.layout((Sequence) DslParser.parse(dsl));
    }

    private static SeqMessage call(String from, String to, String label) {
        return new SeqMessage(from, to, label, false);
    }

    private static SeqMessage reply(String from, String to, String label) {
        return new SeqMessage(from, to, label, true);
    }
}
