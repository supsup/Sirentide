package com.sirentide;

import static org.junit.jupiter.api.Assertions.*;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.font.EmittedText;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Flowchart;
import com.sirentide.layout.FlowchartLayout;
import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.Group;
import com.sirentide.layout.MathBox;
import com.sirentide.layout.Rect;
import com.sirentide.layout.Shape;
import com.sirentide.parse.DslParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Causal diamond guards: actual emitted polygon/curves AND full text, independently.
 * No sizing-formula oracle, screenshots, golden regeneration or browser is needed.
 * Real LatteX outline integration is not claimed: the strict SVG adapter rejects unsupported
 * paths/transforms. These math fixtures have honest nonzero ink and matching metrics. */
class FlowchartDiamondTextFitTest {
    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final String CONDITION = "Completed with exit 0, the verified digest equals "
        + "the allowlisted one, and a valid docker-wall start time?";
    private static final String LONG_WORD =
        "ThisDiamondConditionMustBeRenderedCompletelyWithoutAnyEllipsisOrMissingSuffix0123456789";
    private static final String LONG_PROSE =
        "Completed with exit zero and the verified digest equals the allowlisted one and the "
        + "docker wall start time is valid and every original condition word must remain visible "
        + "including this final suffix";
    private static final List<String> DIRECTIONS = List.of("TD", "LR");
    private static final double EPS = 1e-6;
    // FontMetrics serializes at 2dp: translating before rounding can put corresponding
    // coordinate deltas in adjacent .01px bins. Relative to the first delta, allow .01
    // plus .001 numerical margin. Command/count/order stay exact. This tolerance is
    // ONLY glyph identity; actual curve-in-polygon distances still use EPS=1e-6.
    private static final double GLYPH_IDENTITY_EPS = .011;

    private record Snapshot(Flowchart parsed, List<Group> groups,
                            List<SvgTestInk.NodeInk> nodes, List<List<String>> text,
                            String corpus, String publicSvg) {}

    @Test
    void canonicalDiagramPreservesTheFullConditionInEveryDiamond() throws Exception {
        String original;
        try (InputStream in = getClass().getResourceAsStream("/flowchart-diamond-lane-live.mmd")) {
            assertNotNull(in, "missing exact canonical fixture");
            original = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(106, CONDITION.length(), "pin the complete condition, not its quoted tail");
        assertTrue(original.contains("C{" + CONDITION + "}"));
        for (String direction : DIRECTIONS) {
            Snapshot s = snapshot(original.replaceFirst("flowchart TD", "flowchart " + direction), null, 0);
            assertEquals(13, s.parsed.nodes().size(), "canonical parsed source order/count");
            assertEquals(13, s.nodes.size(), "actual node-role groups, not inferred anchor IDs");
            List<String> decisions = new ArrayList<>();
            for (int i = 0; i < s.parsed.nodes().size(); i++) {
                var source = s.parsed.nodes().get(i);
                String shown = wrappedText(s.text.get(i));
                if (source.shape().equals("diamond")) {
                    decisions.add(source.id());
                    assertEquals(source.label(), shown, "complete original decision at source index " + i);
                    contained(s, i);
                } else {
                    // Existing non-diamond truncation stays permitted, but order must still match.
                    String prefix = shown.endsWith("…") ? shown.substring(0, shown.length() - 1) : shown;
                    assertFalse(prefix.isEmpty(), "nonblank canonical node cannot disappear");
                    assertTrue(source.label().startsWith(prefix), "actual group must match source index " + i);
                    if (!shown.endsWith("…")) assertEquals(source.label(), shown);
                }
            }
            assertEquals(List.of("C", "X", "D"), decisions,
                "use parsed source order; emitted anchor IDs are sanitized LABELS, not DSL IDs");
        }
    }

    @Test
    void fullPlainLabelsAndEveryGlyphFitInTdAndLr() {
        for (String direction : DIRECTIONS) {
            for (String label : List.of("Ready?", CONDITION, LONG_WORD, LONG_PROSE,
                    "this is a long label that should wrap onto several lines", "ffff jjjj gyppy?")) {
                Snapshot s = snapshot(single(direction, label), null, 0);
                assertEquals(label, wrappedText(s.text.get(0)), "lost/truncated original text: " + label);
                assertFalse(s.corpus.contains("…"), "no newly substituted ellipsis");
                contained(s, 0);
            }
        }
    }

    @Test
    void truthfulShortTallDeepAndMixedMathInkFitsWithOneCallbackPerRender() {
        // These closed filled rectangles actually occupy ALL their declared metric extents.
        // The old wiring fake M0 0L10 0 has zero fill area and cannot witness containment.
        for (String direction : DIRECTIONS) {
            for (double[] metrics : List.of(new double[]{80, 8, 2}, new double[]{80, 48, 8},
                    new double[]{80, 8, 48})) {
                for (boolean mixed : List.of(false, true)) {
                    MathFragmentRenderer math = rectangle(metrics[0], metrics[1], metrics[2]);
                    String label = mixed ? "Before $M$ after" : "$M$";
                    Snapshot s = snapshot(single(direction, label), math, 1);
                    long boxes = Group.flatten(s.groups.get(0).members()).stream()
                        .filter(MathBox.class::isInstance).count();
                    assertEquals(1, boxes, "one accepted full fragment, not a text fallback");
                    assertEquals(mixed ? List.of("Before ", " after") : List.of(), s.text.get(0));
                    assertEquals(mixed ? 3 : 1, s.nodes.get(0).ink().size(), "every emitted run retained");
                    assertTrue(s.nodes.get(0).ink().stream().allMatch(k -> k.sx() == 1 && k.sy() == 1),
                        "these truthful fixtures must not be shrunk to disguise overflow");
                    int mathIndex = mixed ? 1 : 0;
                    DiamondInkOracle.assertTranslatedPath(rectanglePath(metrics[0], metrics[1], metrics[2]),
                        s.nodes.get(0).ink().get(mathIndex).d(), .003);
                    contained(s, 0);
                }
            }
        }
    }

    @Test
    void failedMathRemainsLiteralAndPlainTextDoesNotConsultTheRenderer() {
        for (String direction : DIRECTIONS) {
            Snapshot fallback = snapshot(single(direction, "$FAIL$"), (latex, size) -> Optional.empty(), 1);
            assertEquals("$FAIL$", wrappedText(fallback.text.get(0)));
            assertTrue(Group.flatten(fallback.groups.get(0).members()).stream()
                .noneMatch(MathBox.class::isInstance));
            contained(fallback, 0);
            String dsl = single(direction, "Ready?");
            Snapshot plain = snapshot(dsl, rectangle(80, 48, 8), 0);
            assertEquals("Ready?\n", plain.corpus, "measurement must not duplicate actual emission");
            assertEquals(Sirentide.render(dsl), plain.publicSvg, "unused renderer is inert");
        }
    }

    @Test
    void blankLabelsHaveFiniteDiamondsAndRectangularTruncationIsUnchanged() {
        for (String direction : DIRECTIONS) {
            for (String label : List.of("", "   ")) {
                Snapshot s = snapshot(single(direction, label), null, 0);
                assertEquals("", s.parsed.nodes().get(0).label(), "parser's existing strip policy");
                assertEquals("", s.corpus);
                assertTrue(s.nodes.get(0).ink().isEmpty());
                DiamondInkOracle.diamond(s.nodes.get(0).outline());
            }
            Snapshot rect = snapshot("flowchart " + direction + "\n A[" + LONG_WORD + "]\n", null, 0);
            assertEquals("rect", rect.nodes.get(0).primaryTag());
            Rect box = Group.flatten(rect.groups.get(0).members()).stream()
                .filter(Rect.class::isInstance).map(Rect.class::cast).findFirst().orElseThrow();
            assertTrue(box.width() <= 208.0 + .5, "existing non-diamond 180+padding bound");
            assertTrue(rect.corpus.contains("…"), "diamond completeness does not change rectangle policy");
        }
    }

    @Test
    void captureContainsActualRunsExactlyOnceAndRestoresAnOuterCapture() {
        EmittedText.arm();
        try {
            FONT.textPathD("outer-before", 0, 0, 12);
            Snapshot s = snapshot(single("TD", "Ready?"), null, 0);
            assertEquals("Ready?\n", s.corpus,
                "a missing measurement-suspension mutant must fail this exact comparison");
            FONT.textPathD("outer-after", 0, 0, 12);
            assertEquals("outer-before\nouter-after\n", EmittedText.collected(),
                "nested capture and oracle measurement must not pollute the outer sink");
        } finally {
            EmittedText.disarm();
        }
    }

    @Test
    void analyticOracleRejectsOutsideCurvesBoundingBoxTrapsAndMissingInk() {
        String diamond = "M0 -10 L10 0 L0 10 L-10 0 Z";
        DiamondInkOracle.assertInside(diamond, List.of(new DiamondInkOracle.Ink("M-1 0 L1 0 Z")), EPS);
        // Control point outside, actual quadratic inside: a control-point-only rejection is too strong.
        DiamondInkOracle.assertInside(diamond, List.of(new DiamondInkOracle.Ink("M-1 0 Q0 15 1 0 Z")), EPS);
        for (String ink : List.of("M0 0 L11 0 Z", "M8 8 L9 8 L9 9 Z", "M-1 0 Q0 25 1 0 Z")) {
            assertThrows(AssertionError.class,
                () -> DiamondInkOracle.assertInside(diamond, List.of(new DiamondInkOracle.Ink(ink)), EPS));
        }
        assertThrows(AssertionError.class, () -> DiamondInkOracle.assertInside(diamond, List.of(), EPS));
        assertThrows(AssertionError.class, () -> DiamondInkOracle.diamond("M0 0 L10 0 L10 10 L0 10 Z"));
        assertThrows(AssertionError.class, () -> DiamondInkOracle.assertInside(diamond,
            List.of(new DiamondInkOracle.Ink("M0 0 C0 0 1 1 2 2 Z")), EPS));
    }

    @Test
    void glyphCorrespondenceRejectsOmissionAndAcceptsOnlyRigidTranslation() {
        String expected = FONT.textPathD("AB", 0, 0, 12);
        DiamondInkOracle.assertTranslatedPath(expected, FONT.textPathD("AB", 42.123, 70.456, 12), GLYPH_IDENTITY_EPS);
        assertThrows(AssertionError.class, () -> DiamondInkOracle.assertTranslatedPath(
            expected, FONT.textPathD("A", 42, 70, 12), GLYPH_IDENTITY_EPS));
        assertThrows(AssertionError.class,
            () -> DiamondInkOracle.assertTranslatedPath(expected, "", GLYPH_IDENTITY_EPS));
        assertThrows(AssertionError.class, () -> DiamondInkOracle.assertTranslatedPath(
            expected, FONT.textPathD("AB", 42, 70, 11), GLYPH_IDENTITY_EPS));
    }

    private static Snapshot snapshot(String dsl, MathFragmentRenderer math, int expectedMathCalls) {
        assertTrue(DslParser.parse(dsl) instanceof Flowchart, "fixture parser setup");
        Flowchart parsed = (Flowchart) DslParser.parse(dsl);
        int[] calls = {0};
        MathFragmentRenderer counted = math == null ? null : (latex, size) -> {
            calls[0]++;
            return math.render(latex, size);
        };
        List<Group> groups;
        List<List<String>> nodeText = new ArrayList<>();
        String corpus, emitted;
        EmittedText.arm();
        try {
            var laid = FlowchartLayout.layout(parsed, counted);
            assertEquals(expectedMathCalls, calls[0], "one callback per original math run in layout");
            corpus = EmittedText.collected();
            List<GlyphRun> glyphs = Group.flatten(laid.shapes()).stream()
                .filter(GlyphRun.class::isInstance).map(GlyphRun.class::cast).toList();
            List<String> actualRuns = corpus.lines().filter(r -> !r.isBlank()).toList();
            assertEquals(actualRuns.size(), glyphs.size(),
                "tap-only text or duplicate measurement cannot masquerade as emitted glyphs");
            IdentityHashMap<GlyphRun,String> textByGlyph = new IdentityHashMap<>();
            for (int i = 0; i < glyphs.size(); i++) {
                // Edge labels use 10px, nodes use 12px; compare NODE contours below only.
                textByGlyph.put(glyphs.get(i), actualRuns.get(i));
            }
            assertEquals(corpus, EmittedText.collected(), "test oracle must suppress its own text tap");
            groups = laid.shapes().stream().filter(Group.class::isInstance).map(Group.class::cast)
                .filter(g -> g.anchor().role() == SirentideRole.NODE).toList();
            assertEquals(parsed.nodes().size(), groups.size(), "parsed source -> node group order/count");
            for (Group group : groups) {
                List<String> texts = new ArrayList<>();
                for (Shape shape : Group.flatten(group.members())) {
                    if (shape instanceof GlyphRun glyph) {
                        String text = textByGlyph.get(glyph);
                        assertNotNull(text, "actual glyph has no emission record");
                        String expected;
                        boolean suspended = EmittedText.enterPlainRender();
                        try { expected = FONT.textPathD(text, 0, 0, 12); }
                        finally { EmittedText.exitPlainRender(suspended); }
                        DiamondInkOracle.assertTranslatedPath(expected, glyph.pathD(), GLYPH_IDENTITY_EPS);
                        texts.add(text);
                    }
                }
                nodeText.add(List.copyOf(texts));
            }
            assertEquals(corpus, EmittedText.collected(), "node shape comparison must not emit twice");
            emitted = SvgEmitter.emit(laid, null);
        } finally {
            EmittedText.disarm();
        }
        List<SvgTestInk.NodeInk> nodes = SvgTestInk.nodes(emitted);
        assertEquals(parsed.nodes().size(), nodes.size(), "emitter must retain actual nodes");
        for (int i = 0; i < groups.size(); i++) {
            int k = 0;
            for (Shape shape : Group.flatten(groups.get(i).members())) {
                if (shape instanceof GlyphRun glyph) {
                    assertTrue(k < nodes.get(i).ink().size(), "emitter dropped a glyph run");
                    var ink = nodes.get(i).ink().get(k++);
                    assertEquals(glyph.pathD(), ink.d(), "actual SVG must retain every layout glyph contour");
                    assertEquals(1.0, ink.sx()); assertEquals(1.0, ink.sy());
                    assertEquals(0.0, ink.tx()); assertEquals(0.0, ink.ty());
                } else if (shape instanceof MathBox) {
                    // These controlled fragments contain exactly one path. No real-ink coverage is inferred.
                    k++;
                }
            }
            assertEquals(k, nodes.get(i).ink().size(), "no omitted or extra emitted ink paths");
        }
        calls[0] = 0;
        String publicSvg = Sirentide.render(dsl, counted);
        assertEquals(expectedMathCalls, calls[0], "one callback per original math run in public render");
        assertEquals(nodes, SvgTestInk.nodes(publicSvg),
            "public API must retain the same actual paths, not an inert shell or different geometry");
        return new Snapshot(parsed, groups, nodes, List.copyOf(nodeText), corpus, publicSvg);
    }

    private static void contained(Snapshot s, int index) {
        assertEquals("path", s.nodes.get(index).primaryTag(), "diamond outline must be a path");
        DiamondInkOracle.assertInside(s.nodes.get(index).outline(), s.nodes.get(index).ink(), EPS);
    }
    private static String wrappedText(List<String> runs) { return String.join(" ", runs); }
    private static String single(String direction, String label) {
        return "flowchart " + direction + "\n A{" + label + "}\n";
    }
    private static MathFragmentRenderer rectangle(double width, double ascent, double depth) {
        return (latex, size) -> Optional.of(new MathFragment(
            "<path d=\"" + rectanglePath(width, ascent, depth) + "\" fill=\"currentColor\"/>",
            width, ascent, depth));
    }
    private static String rectanglePath(double width, double ascent, double depth) {
        return "M0 " + (-ascent) + " L" + width + " " + (-ascent)
            + " L" + width + " " + depth + " L0 " + depth + " Z";
    }
}
