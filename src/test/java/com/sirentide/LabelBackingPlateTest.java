package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/// Plan 5ce905a8: a backing plate behind labels that cross strokes. The two diagrams are the real
/// ones from the docs page that motivated the plan (the sequence and flowchart test resources). The
/// docs page is dark: page `#0a0a0a`, page text `#e2e8f0`, which is what `currentColor` resolves to
/// there. A light page is checked too, so the fix does not trade one theme for the other.
///
/// The check reads only the baked SVG, in paint order. For every message label (sequence) and edge
/// label (flowchart) glyph path, it takes the glyph's bounding box and collects every rect, line and
/// non-label path painted EARLIER that touches it. An opaque rect covering the whole box hides what
/// is under it; otherwise the page itself also shows through. Every colour left behind the glyph
/// must give at least 4.5 to 1 against the glyph's own fill (WCAG relative luminance).
///
/// That contrast check is about what lies BEHIND a label. What is painted AFTER it, over its
/// glyphs, is checked separately by {@link #nothingIsPaintedOverALabelGlyph} (review 1337 F2), and a
/// glyph read against an arrowhead in a play-through frame by
/// {@link #noLabelGlyphReadsAgainstAnArrowheadInAnyFrame} (review 1337 F1).
///
/// Out of scope here, on purpose: alt/else frame tab and divider labels, which plan 9d56f599 moves
/// into their own bands, and typeset math labels, whose extent is not layout geometry.
class LabelBackingPlateTest {

    private static final String PLATE = "#f5f7ff";
    /// The plate's fill in a future play-through frame (Emphasis dims structural fills).
    private static final String DIMMED_PLATE = "#fbfcff";
    private static final double MIN_CONTRAST = 4.5;
    /// The plate's padding around a label's ink box (LabelPlate.PAD).
    private static final double PAD = 2;
    private static final Page DARK_DOCS = new Page("#0a0a0a", "#e2e8f0");
    private static final Page LIGHT = new Page("#ffffff", "#1a1a1a");
    private static final Pattern NUM = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");

    private static final String SEQUENCE = "/sequence/lane-live-acceptance-diagram1.sirentide";
    private static final String FLOWCHART = "/flowchart-diamond-lane-live.mmd";
    private static final String[] STATES = {"done", "active", "future"};

    /// A surface a diagram is read on: a config header plus the page behind it. The two opaque
    /// themes (`ir.Theme` DARK and NEUTRAL) paint their own background rect, which the check sees.
    private record Surface(String header, Page page) {}

    private static final Surface DOCS_DARK_PAGE = new Surface("", DARK_DOCS);
    private static final Surface DOCS_LIGHT_PAGE = new Surface("", LIGHT);
    private static final Surface THEME_DARK = new Surface("%% theme: dark\n", new Page("#1e1e1e", "#e6e6e6"));
    private static final Surface THEME_NEUTRAL = new Surface("%% theme: neutral\n", new Page("#f5f5f5", "#1a1a1a"));

    private record Page(String background, String text) {}

    private record Painted(Element el, String color, boolean label, double[] box) {}

    @Test
    void sequenceMessageLabelsReadOverBarsAndLifelinesOnTheDarkDocsPage() throws Exception {
        assertEveryLabelGlyphReads(bake(resource("/sequence/lane-live-acceptance-diagram1.sirentide")),
            "message", DARK_DOCS, 18);
    }

    @Test
    void flowchartEdgeLabelsReadOverEdgeLinesOnTheDarkDocsPage() throws Exception {
        assertEveryLabelGlyphReads(bake(resource("/flowchart-diamond-lane-live.mmd")), "edge", DARK_DOCS, 12);
    }

    @Test
    void bothDiagramsStillReadOnALightPage() throws Exception {
        assertEveryLabelGlyphReads(bake(resource("/sequence/lane-live-acceptance-diagram1.sirentide")),
            "message", LIGHT, 18);
        assertEveryLabelGlyphReads(bake(resource("/flowchart-diamond-lane-live.mmd")), "edge", LIGHT, 12);
    }

    /// The motivating crossings are actually plated: 'network none' runs over the Git bar and
    /// 'DELETE the job' over the Launcher bar. Guards against a vacuous pass where the contrast
    /// check finds nothing behind any label.
    @Test
    void theMotivatingLabelsGetAPlate() throws Exception {
        Document svg = bake(resource("/sequence/lane-live-acceptance-diagram1.sirentide"));
        List<Element> messages = groups(svg, "message");
        for (int seq : new int[] {6, 11, 13}) {
            assertTrue(hasPlate(messages.get(seq)), "message " + seq + " crosses a bar and carries a plate");
        }
        assertTrue(groups(bake(resource("/flowchart-diamond-lane-live.mmd")), "edge").stream()
            .anyMatch(LabelBackingPlateTest::hasPlate), "an edge label crossing an edge line carries a plate");
    }

    /// Plan 3e1104cf: a message between adjacent actors crosses nothing, and it is plated anyway,
    /// with the plate's contrast text, so it no longer depends on the host page's colour. (Before
    /// 3e1104cf this test pinned the opposite: no plate, page-inheriting `currentColor`.)
    @Test
    void aLabelThatCrossesNothingIsPlatedToo() throws Exception {
        Document svg = bake("sequence\n  A ->> B : hi\n  B -->> A : ok\n");
        List<Element> messages = groups(svg, "message");
        assertEquals(2, messages.size());
        for (Element g : messages) {
            assertTrue(hasPlate(g), "an uncrossed message label is plated");
            assertTrue(labelPaths(g).stream().noneMatch(p -> "currentColor".equals(p.getAttribute("fill"))),
                "a plated label does not inherit the page colour");
        }
    }

    /// Plan 3e1104cf, the motivating label: in the lane-live-acceptance flowchart (the FLOWCHART
    /// resource, byte-identical to the docs page's source), the decollider moved
    /// `stop or removal unknown` (edge X to CU) clear of every line, so before 3e1104cf it crossed
    /// nothing, got no plate, and drew its page-coloured glyphs straight on the `#0a0a0a` page.
    @Test
    void theStopOrRemovalUnknownLabelIsPlated() throws Exception {
        Element xcu = null;
        for (Element g : groups(bake(resource(FLOWCHART)), "edge")) {
            if ("X-CU".equals(g.getAttribute("data-sirentide-id"))) {
                xcu = g;
            }
        }
        assertNotNull(xcu, "edge X-CU (label 'stop or removal unknown') is baked");
        assertFalse(labelPaths(xcu).isEmpty(), "X-CU has label glyphs");
        assertTrue(hasPlate(xcu), "X-CU 'stop or removal unknown' carries a plate");
    }

    /// Plan 3e1104cf: EVERY edge-label glyph (flowchart) and message-label glyph (sequence), in both
    /// plan fixtures (the flowchart one is the lane-live-acceptance diagram) and every flowchart and
    /// sequence golden, sits DIRECTLY on a plate of its own group: the last shape painted before it
    /// that covers its whole box is a plate (never the bare page, never an edge line, never another
    /// element's fill), and every colour behind it from that plate on reads at least 4.5 to 1
    /// against the glyph's own fill. Checked on the dark docs page (`#0a0a0a`, `currentColor` =
    /// `#e2e8f0`) and on a light page, in the static bake and in every play-through frame.
    ///
    /// Label glyphs are found by STRUCTURE, not colour (an unplated label's colour is whatever the
    /// page says): a `<path>` that is a direct child of an edge or message group and is neither a
    /// triangle arrowhead nor a plate. A typeset math label is a nested `<g>` and out of scope.
    ///
    /// Expected on the code before 3e1104cf: fails, listing every unplated label, among them
    /// `plan-flowchart dark static edge:X-CU glyph #e2e8f0: no plate behind it (on the page
    /// #0a0a0a)` (`stop or removal unknown`), and the goldens' uncrossed labels such as
    /// `sequence ... edge/message ... no plate behind it`. On the light page the same labels' active
    /// frames also read `#e8590c` on `#ffffff`, 3.58 to 1, and their future frames `#cccccc` on
    /// `#ffffff`.
    @Test
    void everyEdgeAndMessageLabelGlyphSitsOnItsOwnPlateInTheBakeAndEveryFrame() throws Exception {
        List<String> found = new ArrayList<>();
        int[] checked = new int[1];
        for (var e : labelFixtures().entrySet()) {
            found.addAll(offPlateEverywhere(e.getKey(), e.getValue(), checked));
        }
        assertTrue(checked[0] > 0, "label glyphs were checked");
        assertEquals("", String.join("\n", new TreeSet<>(found)));
    }

    /// Healthy opposing control for {@link #everyEdgeAndMessageLabelGlyphSitsOnItsOwnPlateInTheBakeAndEveryFrame}:
    /// labels that were ALREADY plated before 3e1104cf (an edge label over an earlier sibling edge,
    /// and the plan sequence's bar-crossing messages 6, 11 and 13) pass the same check, in the bake
    /// and every frame on both pages, so the new rule keeps what the crossing rule gave. Green before
    /// and after the change.
    @Test
    void labelsThatAlreadyHadAPlateStillPass() throws Exception {
        String sibling = "flowchart TD\n  A[a]\n  B[b]\n  C[c]\n  D[d]\n  A --> B\n  A --> D\n"
            + "  A -->|a longer one| C\n";
        assertTrue(hasPlate(groups(bake(sibling), "edge").get(2)), "the A to C label crosses A to D");
        int[] checked = new int[1];
        assertEquals(List.of(), offPlateEverywhere("sibling", sibling, checked));
        assertTrue(checked[0] > 0, "the sibling label's glyphs were checked");

        String seq = resource(SEQUENCE);
        List<Element> messages = groups(bake(seq), "message");
        List<String> seqFound = offPlateEverywhere("plan-sequence", seq, new int[1]);
        for (int k : new int[] {6, 11, 13}) {
            Element g = messages.get(k);
            assertTrue(hasPlate(g), "message " + k + " crosses a bar and carries a plate");
            String id = g.getAttribute("data-sirentide-id");
            List<String> mine = new ArrayList<>();
            for (String f : seqFound) {
                if (f.contains("message:" + id + " ")) {
                    mine.add(f);
                }
            }
            assertEquals(List.of(), mine, "message " + k + " (" + id + ")");
        }
    }

    /// Healthy opposing control: NODE labels are outside the plate rule. A node label sits on its
    /// own node box (the box, not a plate, is directly behind it), no node group gains a plate, and
    /// the structural label finder does not count node glyphs; only the one edge label does.
    @Test
    void aNodeLabelIsUnaffected() throws Exception {
        String dsl = "flowchart\nA[Start] -->|yes| B[End]\n";
        Document svg = bake(dsl);
        List<Element> nodes = groups(svg, "node");
        assertEquals(2, nodes.size());
        for (Element n : nodes) {
            assertFalse(hasPlate(n), "a node group carries no plate");
        }
        List<Painted> order = new ArrayList<>();
        collect(svg.getDocumentElement(), null, DARK_DOCS, order);
        int nodeGlyphs = 0;
        for (int i = 0; i < order.size(); i++) {
            Painted p = order.get(i);
            if (!"node".equals(roleOf(p.el())) || !"path".equals(p.el().getLocalName())
                || isStructuralLabel(p.el())) {
                continue;
            }
            if (!"#000000".equals(p.el().getAttribute("fill"))) {
                continue;   // the box silhouette, if any; node labels contrast-fill dark on #dbe4ff
            }
            nodeGlyphs++;
            Painted back = null;
            for (int j = 0; j < i; j++) {
                if (touches(order.get(j), p.box()) && coversWhole(order.get(j), p.box())) {
                    back = order.get(j);
                }
            }
            assertNotNull(back, "something covers node glyph " + i);
            assertEquals("#dbe4ff", back.el().getAttribute("fill"), "the node box is behind its label");
        }
        assertTrue(nodeGlyphs >= 2, "both node labels were found: " + nodeGlyphs);
        int edgeGlyphs = 0;
        for (Painted p : order) {
            if (isStructuralLabel(p.el())) {
                assertEquals("edge", roleOf(p.el()), "only edge or message glyphs count as labels");
                edgeGlyphs++;
            }
        }
        assertTrue(edgeGlyphs >= 1, "the 'yes' edge label is found");
    }

    /// Healthy control for the checker itself, varied on one axis: the same edge glyph is flagged
    /// when nothing but the page is behind it, and when only its edge line is, and passes once a plate
    /// is laid under it.
    @Test
    void theOnAPlateCheckSeesAMissingPlate() throws Exception {
        String glyph = "<path d=\"M 10 20 L 40 20 L 40 10 L 10 10 Z\" fill=\"currentColor\"/>";
        String line = "<line x1=\"25\" y1=\"0\" x2=\"25\" y2=\"40\" stroke=\"#94a3b8\" stroke-width=\"1.5\"/>";
        String plate = "<rect x=\"8\" y=\"8\" width=\"34\" height=\"14\" fill=\"#f5f7ff\"/>";
        assertEquals(1, offPlate(plateScene(glyph), DARK_DOCS, "synthetic").size(), "bare page");
        assertEquals(1, offPlate(plateScene(line + glyph), DARK_DOCS, "synthetic").size(), "only a line");
        assertEquals(List.of(), offPlate(plateScene(line + plate + glyph.replace("currentColor", "#000000")),
            DARK_DOCS, "synthetic"), "on a plate");
        assertEquals(1, offPlate(plateScene(line + plate + glyph), DARK_DOCS, "synthetic").size(),
            "page-coloured text on a plate is still too faint (#e2e8f0 on #f5f7ff)");
    }

    private static List<Painted> plateScene(String members) throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"60\" height=\"40\">"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"A-B\" data-sirentide-seq=\"1\">"
            + members + "</g></svg>";
        List<Painted> raw = new ArrayList<>();
        collect(parse(svg).getDocumentElement(), null, DARK_DOCS, raw);
        return structural(raw);
    }

    /// {@link #offPlate} over the static bake and every play-through frame of `dsl`, on the dark
    /// docs page and the light page. Labels are found structurally on the static bake and carried by
    /// paint-order index into each frame (the frames API keeps every frame's elements identical).
    private static List<String> offPlateEverywhere(String name, String dsl, int[] checked) throws Exception {
        List<String> found = new ArrayList<>();
        List<String> frames = Sirentide.renderFrames(dsl);
        for (Page page : List.of(DARK_DOCS, LIGHT)) {
            String pageName = page == DARK_DOCS ? "dark" : "light";
            List<Painted> raw = new ArrayList<>();
            collect(bake(dsl).getDocumentElement(), null, page, raw);
            List<Painted> ref = structural(raw);
            for (Painted p : ref) {
                if (p.label()) {
                    checked[0]++;
                }
            }
            found.addAll(offPlate(ref, page, name + " " + pageName + " static"));
            for (int f = 0; f < frames.size(); f++) {
                List<Painted> fr = new ArrayList<>();
                collect(parse(frames.get(f)).getDocumentElement(), null, page, fr);
                if (fr.size() != ref.size()) {
                    found.add(name + " " + pageName + " frame " + f + ": paints " + fr.size()
                        + " elements, the bake " + ref.size());
                    continue;
                }
                List<Painted> order = new ArrayList<>(fr.size());
                for (int i = 0; i < fr.size(); i++) {
                    order.add(new Painted(fr.get(i).el(), fr.get(i).color(), ref.get(i).label(), fr.get(i).box()));
                }
                found.addAll(offPlate(order, page, name + " " + pageName + " frame " + f));
            }
        }
        return found;
    }

    /// Every label glyph in `order` that is not directly on a plate of its own group, or that reads
    /// under 4.5 to 1 against any colour behind it from that plate on.
    private static List<String> offPlate(List<Painted> order, Page page, String context) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Painted p = order.get(i);
            if (!p.label()) {
                continue;
            }
            int back = -1;
            for (int j = 0; j < i; j++) {
                Painted under = order.get(j);
                if (!under.label() && touches(under, p.box()) && coversWhole(under, p.box())) {
                    back = j;
                }
            }
            String who = context + " " + owner(p.el()) + " glyph " + p.color();
            if (back < 0) {
                found.add(who + ": no plate behind it (on the page " + page.background() + ")");
                continue;
            }
            Painted b = order.get(back);
            if (!isPlateFill(b.el().getAttribute("fill")) || b.el().getParentNode() != p.el().getParentNode()) {
                found.add(who + ": directly behind it is " + owner(b.el()) + " " + b.el().getLocalName()
                    + " " + b.color() + ", not its own plate");
                continue;
            }
            for (int j = back; j < i; j++) {
                Painted under = order.get(j);
                if (under.label() || !touches(under, p.box())) {
                    continue;
                }
                double ratio = contrast(p.color(), under.color());
                if (ratio < MIN_CONTRAST) {
                    found.add(String.format(Locale.ROOT, "%s on %s %s: %.2f to 1", who, owner(under.el()),
                        under.color(), ratio));
                }
            }
        }
        return found;
    }

    /// `raw` with each element's label flag replaced by the STRUCTURAL rule
    /// ({@link #isStructuralLabel}).
    private static List<Painted> structural(List<Painted> raw) {
        List<Painted> out = new ArrayList<>(raw.size());
        for (Painted r : raw) {
            out.add(new Painted(r.el(), r.color(), isStructuralLabel(r.el()), r.box()));
        }
        return out;
    }

    /// An edge or message label glyph by structure: a `<path>` whose parent is the edge or message
    /// group itself, that is neither a triangle arrowhead nor a plate.
    private static boolean isStructuralLabel(Element el) {
        if (!"path".equals(el.getLocalName()) || !(el.getParentNode() instanceof Element parent)) {
            return false;
        }
        String role = parent.getAttribute("data-sirentide-role");
        if (!"edge".equals(role) && !"message".equals(role)) {
            return false;
        }
        return !"arrowhead".equals(kind(el)) && !isPlateFill(el.getAttribute("fill"));
    }

    /// A label spanning a middle lifeline (no bar) is plated: lines count, not only bars.
    @Test
    void aLabelOverAPlainLifelineIsPlated() throws Exception {
        Document svg = bake("sequence\n  participant A\n  participant B\n  participant C\n"
            + "  A ->> C : a label long enough to cross the middle lifeline\n");
        Element message = groups(svg, "message").get(0);
        assertTrue(hasPlate(message), "the label crosses B's lifeline");
        assertTrue(labelPaths(message).stream().noneMatch(p -> "currentColor".equals(p.getAttribute("fill"))),
            "a plated label does not inherit the page colour");
    }

    /// An edge label that clears its OWN vertical edge but runs over an EARLIER sibling edge (A to D,
    /// drawn before A to C) is plated too: the check reads everything already painted, not only the
    /// label's own edge.
    @Test
    void anEdgeLabelOverAnEarlierSiblingEdgeIsPlated() throws Exception {
        Document svg = bake("flowchart TD\n  A[a]\n  B[b]\n  C[c]\n  D[d]\n  A --> B\n  A --> D\n"
            + "  A -->|a longer one| C\n");
        List<Element> edges = groups(svg, "edge");
        assertEquals(3, edges.size());
        assertTrue(hasPlate(edges.get(2)), "the A to C label crosses the A to D line");
        assertEveryLabelGlyphReads(svg, "edge", DARK_DOCS, 3);
    }

    /// Plan 5ce905a8, play-through frames: the frames API re-emits the ONE scene once per step, with
    /// the step done, active (accented) or future (dimmed). A plated label must read in all three,
    /// on both docs pages and under both opaque themes, for both diagram kinds. The colours are read
    /// from each emitted frame, never from the emitter's constants. Without the plate-safe recolour
    /// this fails twice per plated label: active `#e8590c` on the plate `#f5f7ff` is 3.35 to 1, and
    /// future `#9e9e9e` on the dimmed plate `#fbfcff` is 2.61 to 1.
    @Test
    void platedLabelsReadInEveryPlayThroughStateOnEveryPageAndTheme() throws Exception {
        for (Surface s : List.of(DOCS_DARK_PAGE, DOCS_LIGHT_PAGE, THEME_DARK, THEME_NEUTRAL)) {
            assertEveryFrameReads(s.header() + resource(SEQUENCE), "message", s.page(), true);
            assertEveryFrameReads(s.header() + resource(FLOWCHART), "edge", s.page(), true);
        }
    }

    /// Every label, plated or not, in every frame state, where the accent and dim are known to fit
    /// the backdrop: the dark docs page that motivated the plan, and the dark theme.
    ///
    /// Before plan 3e1104cf an UNPLATED active label on a LIGHT page or the neutral theme took the
    /// page-level accent `#e8590c` (3.58 to 1 on white, 3.28 on `#f5f5f5`), so this test was limited
    /// to the dark backdrops. Every edge and message label is plated now, and
    /// {@link #everyEdgeAndMessageLabelGlyphSitsOnItsOwnPlateInTheBakeAndEveryFrame} asserts the
    /// light page too.
    @Test
    void everyLabelReadsInEveryPlayThroughStateOnTheDarkDocsPageAndTheDarkTheme() throws Exception {
        for (Surface s : List.of(DOCS_DARK_PAGE, THEME_DARK)) {
            assertEveryFrameReads(s.header() + resource(SEQUENCE), "message", s.page(), false);
            assertEveryFrameReads(s.header() + resource(FLOWCHART), "edge", s.page(), false);
        }
    }

    /// The static bake under the two opaque themes: the theme's background rect is the page.
    @Test
    void bothDiagramsReadUnderTheOpaqueThemes() throws Exception {
        for (Surface s : List.of(THEME_DARK, THEME_NEUTRAL)) {
            assertEveryLabelGlyphReads(bake(s.header() + resource(SEQUENCE)), "message", s.page(), 18);
            assertEveryLabelGlyphReads(bake(s.header() + resource(FLOWCHART)), "edge", s.page(), 12);
        }
    }

    /// Review 1337 F2, the FINAL composition: the contrast checks above only look BEHIND a label,
    /// so this one looks at everything painted AFTER each label glyph. Over both plan fixtures and
    /// every flowchart and sequence golden fixture, no non-label shape painted later may cover any
    /// part of a label glyph's box: a later line by its stroke, a later arrowhead, rect or path by
    /// its box, and a later plate by non-zero winding at points inside the glyph box (so an earlier
    /// label cut out of the plate counts as uncovered). Later labels are text-on-text, OverlapLint's
    /// job, and are not counted.
    ///
    /// Before the fix this failed with nine entries, among them the review's witness
    /// `flowchart-convergent-fan: edge:B-S label (plated) under edge:C-S line` (the C-S line, x 190,
    /// y 60 to 98, crosses B-S's glyphs at about 190,94.5) and
    /// `plan-flowchart: edge:X-CA label (plated) under edge:X-CU plate`.
    @Test
    void nothingIsPaintedOverALabelGlyph() throws Exception {
        List<String> found = new ArrayList<>();
        for (var e : labelFixtures().entrySet()) {
            found.addAll(overPainted(e.getKey(), bake(e.getValue())));
        }
        assertEquals("", String.join("\n", new TreeSet<>(found)));
    }

    /// Healthy control for {@link #nothingIsPaintedOverALabelGlyph}: the check is varied on one axis
    /// at a time over a hand-built scene. A later line across the glyph box and a later uncut plate
    /// reaching into it are both seen; the same line broken around the box, the same plate with the
    /// glyph box cut out of it, and a rect beside the box are not. So a pass of the fixture test is
    /// not the check going blind.
    @Test
    void theOverPaintCheckSeesALaterLineOrPlateButNotAGapOrAHole() throws Exception {
        assertEquals(List.of("synthetic: edge:A-B label (plated) under edge:C-D line"),
            overPainted("synthetic", parse(overScene("<line x1=\"25\" y1=\"0\" x2=\"25\" y2=\"40\" "
                + "stroke=\"#94a3b8\" stroke-width=\"1.5\"/>"))));
        assertEquals(List.of("synthetic: edge:A-B label (plated) under edge:C-D plate"),
            overPainted("synthetic", parse(overScene("<path d=\"M 30 5 L 60 5 L 60 25 L 30 25 Z\" "
                + "fill=\"#f5f7ff\"/>"))));
        assertEquals(List.of(), overPainted("synthetic", parse(overScene(
            "<line x1=\"25\" y1=\"0\" x2=\"25\" y2=\"6\" stroke=\"#94a3b8\" stroke-width=\"1.5\"/>"
                + "<line x1=\"25\" y1=\"24\" x2=\"25\" y2=\"40\" stroke=\"#94a3b8\" stroke-width=\"1.5\"/>"))),
            "a line broken around the glyph box is not over it");
        assertEquals(List.of(), overPainted("synthetic", parse(overScene(
            "<path d=\"M 30 5 L 60 5 L 60 25 L 30 25 Z M 30 9.5 L 30 20.5 L 40.5 20.5 L 40.5 9.5 Z\" "
                + "fill=\"#f5f7ff\"/><rect x=\"45\" y=\"0\" width=\"10\" height=\"40\" fill=\"#c7d2fe\"/>"))),
            "a plate with the glyph box cut out of it, and a rect beside the box, are not over it");
    }

    /// An earlier edge `A-B` with a plated label glyph whose box is [10, 10, 40, 20], then a later
    /// edge `C-D` holding `later`.
    private static String overScene(String later) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"40\">"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"A-B\" data-sirentide-seq=\"1\">"
            + "<rect x=\"8\" y=\"8\" width=\"34\" height=\"14\" fill=\"#f5f7ff\"/>"
            + "<path d=\"M 10 20 L 40 20 L 40 10 L 10 10 Z\" fill=\"#000000\"/></g>"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"C-D\" data-sirentide-seq=\"2\">"
            + later + "</g></svg>";
    }

    /// Every label glyph of `svg` with a non-label shape painted after it over its box (see
    /// {@link #nothingIsPaintedOverALabelGlyph}).
    private static List<String> overPainted(String name, Document svg) {
        List<Painted> order = new ArrayList<>();
        collect(svg.getDocumentElement(), null, LIGHT, order);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Painted p = order.get(i);
            if (!p.label()) {
                continue;
            }
            for (int j = i + 1; j < order.size(); j++) {
                Painted over = order.get(j);
                if (!over.label() && paintsOver(over, p.box())) {
                    found.add(name + ": " + owner(p.el()) + " label" + (onPlate(p) ? " (plated)" : "")
                        + " under " + owner(over.el()) + " " + kind(over.el()));
                }
            }
        }
        return found;
    }

    /// Whether `over` covers some of `box`. A plate path is read by non-zero winding at a grid of
    /// points just inside the box, the way a renderer fills it; anything else by {@link #touches}.
    /// Every sample point counts (review 1341: no exemption for another label's box).
    private static boolean paintsOver(Painted over, double[] box) {
        if (!touches(over, box)) {
            return false;
        }
        if (!"path".equals(over.el().getLocalName()) || !isPlateFill(over.el().getAttribute("fill"))) {
            return true;
        }
        String d = over.el().getAttribute("d");
        int n = 12;
        double in = 0.01;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                double x = box[0] + in + (box[2] - box[0] - 2 * in) * i / n;
                double y = box[1] + in + (box[3] - box[1] - 2 * in) * j / n;
                if (winding(d, x, y) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Non-zero winding number of the M/L/Z rings of `d` at a point.
    private static int winding(String d, double x, double y) {
        int w = 0;
        for (String ring : d.split("(?=M )")) {
            List<Double> v = new ArrayList<>();
            Matcher m = NUM.matcher(ring);
            while (m.find()) {
                v.add(Double.parseDouble(m.group()));
            }
            for (int i = 0; i + 1 < v.size(); i += 2) {
                double ax = v.get(i);
                double ay = v.get(i + 1);
                double bx = v.get((i + 2) % v.size());
                double by = v.get((i + 3) % v.size());
                double c = (bx - ax) * (y - ay) - (x - ax) * (by - ay);
                if (ay <= y) {
                    if (by > y && c > 0) {
                        w++;
                    }
                } else if (by <= y && c < 0) {
                    w--;
                }
            }
        }
        return w;
    }

    /// Review 1341, placement: no two flowchart edge labels come within a plate's padding of each
    /// other, so no label's plate can reach another label's ink. For every pair of edge label
    /// glyphs in different groups, one ink box grown by the 2px plate padding must not overlap the
    /// other, over both plan fixtures and every flowchart golden. On cbb61b57 this failed with
    /// `plan-flowchart: edge:X-CA and edge:X-CU` (ink boxes [616.54, 200.55, 734.68, 209.88] and
    /// [710.37, 195.97, 818.94, 205.23] overlap).
    @Test
    void noTwoFlowchartEdgeLabelsComeWithinAPlateOfEachOther() throws Exception {
        List<String> found = new ArrayList<>();
        for (var e : labelFixtures().entrySet()) {
            if (!e.getKey().contains("flowchart")) {
                continue;
            }
            List<Painted> order = new ArrayList<>();
            collect(bake(e.getValue()).getDocumentElement(), null, LIGHT, order);
            List<Painted> labels = new ArrayList<>();
            for (Painted p : order) {
                if (p.label() && "edge".equals(roleOf(p.el()))) {
                    labels.add(p);
                }
            }
            for (int i = 0; i < labels.size(); i++) {
                for (int j = i + 1; j < labels.size(); j++) {
                    Painted a = labels.get(i);
                    Painted b = labels.get(j);
                    if (a.el().getParentNode() == b.el().getParentNode()) {
                        continue;
                    }
                    double[] g = {a.box()[0] - PAD, a.box()[1] - PAD, a.box()[2] + PAD, a.box()[3] + PAD};
                    double[] h = b.box();
                    if (g[0] < h[2] && h[0] < g[2] && g[1] < h[3] && h[1] < g[3]) {
                        found.add(e.getKey() + ": " + owner(a.el()) + " and " + owner(b.el()));
                    }
                }
            }
        }
        assertEquals("", String.join("\n", new TreeSet<>(found)));
    }

    /// Review 1341, the final composition at GLYPH-OUTLINE level: for every label glyph, at every
    /// point of a 0.5px grid that lies inside the glyph's own outline (non-zero winding of its
    /// M/L/Q/C path, curves flattened), nothing painted later covers it: no later line (by stroke),
    /// rect, plate, arrowhead, node path or other label's glyph outline. Over both plan fixtures
    /// and every flowchart and sequence golden fixture. Unlike the box-level check this sees a
    /// plate that erases ink between another label's glyphs. On cbb61b57 it failed with
    /// `plan-flowchart: edge:X-CA ink under edge:X-CU plate` and
    /// `plan-flowchart: edge:X-CA ink under edge:X-CU label`.
    @Test
    void nothingLaterPaintsOverAnyLabelGlyphInk() throws Exception {
        List<String> found = new ArrayList<>();
        for (var e : labelFixtures().entrySet()) {
            found.addAll(inkOverPainted(e.getKey(), bake(e.getValue())));
        }
        assertEquals("", String.join("\n", new TreeSet<>(found)));
    }

    /// Review 1341's literal points in the static plan flowchart, all inside edge X-CA's glyph
    /// outline: (733.76, 200.65), where X-CU's plate used to erase X-CA's ink between X-CU's
    /// glyphs; (733.82915, 200.6904), where X-CU's glyph and plate both used to cover it; and
    /// (710.97775, 205.2598), the opposing control, where the cut-out already left X-CA's ink
    /// uncovered at cbb61b57. Each must be X-CA ink (checked first) with nothing painted later over
    /// it. On cbb61b57 the first two failed (`covered by [edge:X-CU plate]`,
    /// `[edge:X-CU plate, edge:X-CU label]`) and the control passed.
    @Test
    void review1341PointsOfTheCancelledLabelAreUncovered() throws Exception {
        List<Painted> order = new ArrayList<>();
        collect(bake(resource(FLOWCHART)).getDocumentElement(), null, LIGHT, order);
        int at = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).label() && "edge:X-CA".equals(owner(order.get(i).el()))) {
                at = i;
            }
        }
        assertTrue(at >= 0, "the X-CA edge label");
        List<double[][]> ink = outline(order.get(at).el().getAttribute("d"));
        for (double[] pt : new double[][] {{733.76, 200.65}, {733.82915, 200.6904}, {710.97775, 205.2598}}) {
            assertTrue(winding(ink, pt[0], pt[1]) != 0, "review 1341 point " + Arrays.toString(pt)
                + " is inside X-CA's glyph outline; if X-CA moved, re-derive the points");
            List<String> over = new ArrayList<>();
            for (Painted q : order.subList(at + 1, order.size())) {
                if (coversPoint(q, pt[0], pt[1])) {
                    over.add(owner(q.el()) + " " + (q.label() ? "label" : kind(q.el())));
                }
            }
            assertEquals(List.of(), over, "X-CA ink at " + Arrays.toString(pt) + " covered by");
        }
    }

    /// Healthy opposing control for the outline-level check, varied only in where a later plate
    /// sits: an earlier glyph shaped like an `O` (outer ring, counter cut out) with a later plate
    /// over just its counter is NOT ink under paint (the box-level check would call it covered),
    /// and the same plate moved onto the ring IS.
    @Test
    void theInkCheckReadsTheOutlineNotTheBox() throws Exception {
        String counterOnly = "<path d=\"M 22 13 L 28 13 L 28 17 L 22 17 Z\" fill=\"#f5f7ff\"/>";
        String onTheRing = "<path d=\"M 10.5 11 L 13 11 L 13 19 L 10.5 19 Z\" fill=\"#f5f7ff\"/>";
        assertEquals(List.of(), inkOverPainted("synthetic", parse(ringScene(counterOnly))));
        assertEquals(List.of("synthetic: edge:A-B ink under edge:C-D plate"),
            inkOverPainted("synthetic", parse(ringScene(onTheRing))));
        assertEquals(List.of("synthetic: edge:A-B label (plated) under edge:C-D plate"),
            overPainted("synthetic", parse(ringScene(counterOnly))), "the box-level check sees the box only");
    }

    /// An earlier edge `A-B` whose plated glyph is a square ring (outer [10, 10, 40, 20], counter
    /// [20, 12, 30, 18] wound the other way), then a later edge `C-D` holding `later`.
    private static String ringScene(String later) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"40\">"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"A-B\" data-sirentide-seq=\"1\">"
            + "<rect x=\"8\" y=\"8\" width=\"34\" height=\"14\" fill=\"#f5f7ff\"/>"
            + "<path d=\"M 10 10 L 40 10 L 40 20 L 10 20 Z M 20 12 L 20 18 L 30 18 L 30 12 Z\" "
            + "fill=\"#000000\"/></g>"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"C-D\" data-sirentide-seq=\"2\">"
            + later + "</g></svg>";
    }

    /// Every label glyph of `svg` with some point of its outline's ink covered by a shape painted
    /// later (see {@link #nothingLaterPaintsOverAnyLabelGlyphInk}). Another run of the SAME label
    /// (a wrapped line, same group) is not counted.
    private static List<String> inkOverPainted(String name, Document svg) {
        List<Painted> order = new ArrayList<>();
        collect(svg.getDocumentElement(), null, LIGHT, order);
        TreeSet<String> found = new TreeSet<>();
        double step = 0.5;
        for (int i = 0; i < order.size(); i++) {
            Painted p = order.get(i);
            if (!p.label()) {
                continue;
            }
            List<Painted> later = new ArrayList<>();
            for (Painted q : order.subList(i + 1, order.size())) {
                if (!(q.label() && q.el().getParentNode() == p.el().getParentNode()) && touches(q, p.box())) {
                    later.add(q);
                }
            }
            if (later.isEmpty()) {
                continue;
            }
            List<double[][]> ink = outline(p.el().getAttribute("d"));
            double[] b = p.box();
            for (double x = b[0] + step / 2; x < b[2]; x += step) {
                for (double y = b[1] + step / 2; y < b[3]; y += step) {
                    if (winding(ink, x, y) == 0) {
                        continue;
                    }
                    for (Painted q : later) {
                        if (coversPoint(q, x, y)) {
                            found.add(name + ": " + owner(p.el()) + " ink under " + owner(q.el()) + " "
                                + (q.label() ? "label" : kind(q.el())));
                        }
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    /// Whether `q` paints the point: a line within half its stroke width, a rect strictly inside,
    /// a path by non-zero winding of its outline (a path with commands other than M/L/Q/C/Z, such
    /// as an arc, by its box).
    private static boolean coversPoint(Painted q, double x, double y) {
        String name = q.el().getLocalName();
        if ("line".equals(name)) {
            double[] l = q.box();
            double dx = l[2] - l[0];
            double dy = l[3] - l[1];
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - l[0]) * dx + (y - l[1]) * dy) / len2));
            return Math.hypot(x - l[0] - t * dx, y - l[1] - t * dy) <= l[4];
        }
        double[] b = q.box();
        if ("rect".equals(name)) {
            return x > b[0] && x < b[2] && y > b[1] && y < b[3];
        }
        List<double[][]> rings = outline(q.el().getAttribute("d"));
        if (rings == null) {
            return x > b[0] && x < b[2] && y > b[1] && y < b[3];
        }
        return winding(rings, x, y) != 0;
    }

    private static final Pattern PATH_TOKEN = Pattern.compile("[A-Za-z]|-?[0-9]+(?:\\.[0-9]+)?");

    /// The closed rings of an absolute M/L/Q/C/Z path, each curve flattened into 8 segments, or
    /// `null` for a path with any other command.
    private static List<double[][]> outline(String d) {
        List<String> t = new ArrayList<>();
        Matcher m = PATH_TOKEN.matcher(d);
        while (m.find()) {
            t.add(m.group());
        }
        List<double[][]> rings = new ArrayList<>();
        List<double[]> cur = new ArrayList<>();
        char cmd = 0;
        double px = 0;
        double py = 0;
        int i = 0;
        int n = 8;
        while (i < t.size()) {
            String tok = t.get(i);
            if (Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                i++;
                if (cmd == 'Z') {
                    if (!cur.isEmpty()) {
                        rings.add(cur.toArray(new double[0][]));
                        cur = new ArrayList<>();
                    }
                    continue;
                }
                if ("MLQC".indexOf(cmd) < 0) {
                    return null;
                }
                continue;
            }
            if (cmd == 'M') {
                if (!cur.isEmpty()) {
                    rings.add(cur.toArray(new double[0][]));
                    cur = new ArrayList<>();
                }
                px = Double.parseDouble(t.get(i++));
                py = Double.parseDouble(t.get(i++));
                cur.add(new double[] {px, py});
                cmd = 'L';
            } else if (cmd == 'L') {
                px = Double.parseDouble(t.get(i++));
                py = Double.parseDouble(t.get(i++));
                cur.add(new double[] {px, py});
            } else if (cmd == 'Q') {
                double cx = Double.parseDouble(t.get(i++));
                double cy = Double.parseDouble(t.get(i++));
                double x = Double.parseDouble(t.get(i++));
                double y = Double.parseDouble(t.get(i++));
                for (int k = 1; k <= n; k++) {
                    double s = k / (double) n;
                    double u = 1 - s;
                    cur.add(new double[] {u * u * px + 2 * u * s * cx + s * s * x, u * u * py + 2 * u * s * cy + s * s * y});
                }
                px = x;
                py = y;
            } else if (cmd == 'C') {
                double[] c = new double[6];
                for (int k = 0; k < 6; k++) {
                    c[k] = Double.parseDouble(t.get(i++));
                }
                for (int k = 1; k <= n; k++) {
                    double s = k / (double) n;
                    double u = 1 - s;
                    cur.add(new double[] {
                        u * u * u * px + 3 * u * u * s * c[0] + 3 * u * s * s * c[2] + s * s * s * c[4],
                        u * u * u * py + 3 * u * u * s * c[1] + 3 * u * s * s * c[3] + s * s * s * c[5]});
                }
                px = c[4];
                py = c[5];
            } else {
                return null;
            }
        }
        if (!cur.isEmpty()) {
            rings.add(cur.toArray(new double[0][]));
        }
        return rings;
    }

    /// Non-zero winding number of closed polygon rings at a point.
    private static int winding(List<double[][]> rings, double x, double y) {
        int w = 0;
        for (double[][] r : rings) {
            for (int i = 0; i < r.length; i++) {
                double ax = r[i][0];
                double ay = r[i][1];
                double bx = r[(i + 1) % r.length][0];
                double by = r[(i + 1) % r.length][1];
                double c = (bx - ax) * (y - ay) - (x - ax) * (by - ay);
                if (ay <= y) {
                    if (by > y && c > 0) {
                        w++;
                    }
                } else if (by <= y && c < 0) {
                    w--;
                }
            }
        }
        return w;
    }

    /// Review 1337 F1: in every play-through frame (and the static bake), no label glyph is read
    /// against a filled arrowhead painted before it at under 4.5 to 1. Over both plan fixtures and
    /// every flowchart and sequence golden fixture, on the dark docs page. The colours are read from
    /// each emitted frame.
    ///
    /// Before the fix this failed on `flowchart-convergent`, whose B-X label ink overlapped its own
    /// arrowhead (line, plate, head, glyph in paint order): in B-X's active frame
    /// `edge:B-X glyph #c2410c on edge:B-X arrowhead #e8590c: 1.45 to 1`, and in its future frames
    /// `#6b6b6b on #d6dce4: 3.86 to 1`; the fan fixture's B-S label failed the same way.
    @Test
    void noLabelGlyphReadsAgainstAnArrowheadInAnyFrame() throws Exception {
        List<String> found = new ArrayList<>();
        for (var e : labelFixtures().entrySet()) {
            Document base = bake(e.getValue());
            List<Painted> ref = new ArrayList<>();
            collect(base.getDocumentElement(), null, DARK_DOCS, ref);
            found.addAll(arrowheadClashes(ref, e.getKey() + " static"));
            List<String> frames = Sirentide.renderFrames(e.getValue());
            for (int f = 0; f < frames.size(); f++) {
                List<Painted> raw = new ArrayList<>();
                collect(parse(frames.get(f)).getDocumentElement(), null, DARK_DOCS, raw);
                assertEquals(ref.size(), raw.size(), e.getKey() + " frame " + f + " paints the static scene");
                List<Painted> order = new ArrayList<>(raw.size());
                for (int i = 0; i < raw.size(); i++) {
                    order.add(new Painted(raw.get(i).el(), raw.get(i).color(), ref.get(i).label(), raw.get(i).box()));
                }
                found.addAll(arrowheadClashes(order, e.getKey() + " frame " + f));
            }
        }
        assertEquals("", String.join("\n", new TreeSet<>(found)));
    }

    /// Review 1337 F1, the placement behind the frame check: no flowchart edge label's ink box
    /// touches ANY arrowhead's box, painted before or after it, its own included. Before the fix it
    /// failed on `flowchart-convergent` (B-X on its own head) and `flowchart-convergent-fan` (B-S on
    /// its own and the A-S, C-S, D-S and E-S heads).
    @Test
    void noFlowchartEdgeLabelTouchesAnArrowhead() throws Exception {
        List<String> found = new ArrayList<>();
        for (var e : labelFixtures().entrySet()) {
            if (!e.getKey().contains("flowchart")) {
                continue;
            }
            List<Painted> order = new ArrayList<>();
            collect(bake(e.getValue()).getDocumentElement(), null, LIGHT, order);
            for (Painted p : order) {
                if (!p.label() || !"edge".equals(roleOf(p.el()))) {
                    continue;
                }
                for (Painted head : order) {
                    if ("arrowhead".equals(kind(head.el())) && touches(head, p.box())) {
                        found.add(e.getKey() + ": " + owner(p.el()) + " label on " + owner(head.el()) + " arrowhead");
                    }
                }
            }
        }
        assertEquals("", String.join("\n", new TreeSet<>(found)));
    }

    /// Healthy opposing control for the two F1 tests, varied only in where the arrowhead sits: an
    /// active plated label (`#c2410c`) whose own active arrowhead (`#e8590c`) reaches into its glyph
    /// box is a clash at 1.45 to 1, and the same label with the arrowhead moved just clear of the
    /// glyph box is none, and reads at 4.84 to 1 on its plate by the full behind-the-glyph check.
    @Test
    void anActiveGlyphOnItsOwnArrowheadClashesAndTheSameGlyphOnItsPlateReads() throws Exception {
        List<String> clash = arrowheadClashes(activeScene("M 20 24 L 16 10 L 24 10 Z"), "synthetic");
        assertEquals(1, clash.size(), "the active glyph over its own active arrowhead: " + clash);
        assertTrue(clash.get(0).contains("#c2410c on edge:A-B arrowhead #e8590c: 1.45"), clash.get(0));
        List<Painted> clear = activeScene("M 20 28 L 16 14 L 24 14 Z");
        assertEquals(List.of(), arrowheadClashes(clear, "synthetic"));
        int glyph = clear.size() - 1;
        assertTrue(clear.get(glyph).label(), "the glyph is the last shape");
        assertGlyphReads(clear, glyph, "edge", DARK_DOCS, "synthetic ");
    }

    /// One active edge group: a plate, an accented arrowhead with path `head`, then the plate-accent
    /// glyph whose box is [4, 4, 40, 13]. The glyph is marked as the label by its colour.
    private static List<Painted> activeScene(String head) throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"60\" height=\"40\">"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"A-B\" data-sirentide-seq=\"1\">"
            + "<rect x=\"2\" y=\"2\" width=\"40\" height=\"13\" fill=\"#f5f7ff\"/>"
            + "<path d=\"" + head + "\" fill=\"#e8590c\"/>"
            + "<path d=\"M 4 13 L 40 13 L 40 4 L 4 4 Z\" fill=\"#c2410c\"/></g></svg>";
        List<Painted> raw = new ArrayList<>();
        collect(parse(svg).getDocumentElement(), null, DARK_DOCS, raw);
        List<Painted> order = new ArrayList<>();
        for (Painted r : raw) {
            order.add(new Painted(r.el(), r.color(), "#c2410c".equals(r.color()), r.box()));
        }
        return order;
    }

    /// Every label glyph in `order` with a filled arrowhead painted before it that touches its box
    /// and reads under 4.5 to 1 against the glyph's own fill. An arrowhead painted before an opaque
    /// rect or plate that covers the whole glyph box is hidden by it and not counted, the same rule
    /// as {@link #assertGlyphReads}.
    private static List<String> arrowheadClashes(List<Painted> order, String context) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Painted p = order.get(i);
            if (!p.label()) {
                continue;
            }
            List<Painted> heads = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                Painted under = order.get(j);
                if (under.label() || !touches(under, p.box())) {
                    continue;
                }
                if (coversWhole(under, p.box())) {
                    heads.clear();
                } else if ("arrowhead".equals(kind(under.el()))) {
                    heads.add(under);
                }
            }
            for (Painted head : heads) {
                double ratio = contrast(p.color(), head.color());
                if (ratio < MIN_CONTRAST) {
                    found.add(String.format(Locale.ROOT, "%s %s glyph %s on %s arrowhead %s: %.2f to 1",
                        context, owner(p.el()), p.color(), owner(head.el()), head.color(), ratio));
                }
            }
        }
        return found;
    }

    /// The fixtures the composition checks run over: both plan fixtures and every flowchart and
    /// sequence golden fixture. `plan-flowchart` is the lane-live-acceptance flowchart of plan
    /// 3e1104cf (the docs page's source, byte-identical).
    private static java.util.Map<String, String> labelFixtures() throws Exception {
        java.util.Map<String, String> diagrams = new java.util.LinkedHashMap<>();
        diagrams.put("plan-sequence", resource(SEQUENCE));
        diagrams.put("plan-flowchart", resource(FLOWCHART));
        for (var e : GoldenSvgTest.fixtures().entrySet()) {
            if (e.getKey().startsWith("flowchart") || e.getKey().startsWith("sequence")) {
                diagrams.put(e.getKey(), e.getValue());
            }
        }
        return diagrams;
    }

    /// The flowchart layout's crossing and gap tests reach a stroke at its ACTIVE-frame width
    /// (LabelPlate.ACTIVE_REACH); that must stay the multiplier the frames emitter really applies.
    @Test
    void theActiveReachIsTheFramesStrokeMultiplier() throws Exception {
        var reach = Class.forName("com.sirentide.layout.LabelPlate").getDeclaredField("ACTIVE_REACH");
        var mult = Class.forName("com.sirentide.emit.Emphasis").getDeclaredField("ACTIVE_WIDTH_MULT");
        reach.setAccessible(true);
        mult.setAccessible(true);
        assertEquals(mult.getDouble(null), reach.getDouble(null));
    }

    private static String kind(Element el) {
        String name = el.getLocalName();
        if (PLATE.equals(el.getAttribute("fill"))) {
            return "plate";
        }
        if ("path".equals(name) && el.getAttribute("d").matches("M \\S+ \\S+ L \\S+ \\S+ L \\S+ \\S+ Z")) {
            return "arrowhead";
        }
        return name;
    }

    private static boolean onPlate(Painted p) {
        return "#000000".equals(p.el().getAttribute("fill"));
    }

    private static String owner(Element el) {
        for (Node n = el.getParentNode(); n instanceof Element e; n = n.getParentNode()) {
            if (e.hasAttribute("data-sirentide-role")) {
                return e.getAttribute("data-sirentide-role") + ":" + e.getAttribute("data-sirentide-id");
            }
        }
        return "top";
    }

    /// Bakes the frames and checks each label glyph against what is painted under it IN THAT FRAME.
    /// Which elements are label glyphs is decided once on the static bake (whose label fills are
    /// known), then carried by paint-order index into every frame; the frames API keeps geometry
    /// byte-identical, which this re-asserts element by element. Counts the plated glyphs seen in
    /// each step state so a pass cannot be vacuous.
    private static void assertEveryFrameReads(String dsl, String role, Page page, boolean platedOnly)
        throws Exception {
        Document base = bake(dsl);
        List<Painted> ref = new ArrayList<>();
        collect(base.getDocumentElement(), null, page, ref);
        TreeSet<Integer> seqSet = new TreeSet<>();
        var all = base.getElementsByTagNameNS("*", "g");
        for (int i = 0; i < all.getLength(); i++) {
            Element g = (Element) all.item(i);
            if (g.hasAttribute("data-sirentide-seq")) {
                seqSet.add(Integer.parseInt(g.getAttribute("data-sirentide-seq")));
            }
        }
        List<Integer> seqs = new ArrayList<>(seqSet);
        List<String> frames = Sirentide.renderFrames(dsl);
        assertEquals(seqs.size(), frames.size(), role + ": one frame per step");
        int[] platedByState = new int[3];   // done, active, future
        List<Executable> checks = new ArrayList<>();
        for (int f = 0; f < frames.size(); f++) {
            List<Painted> raw = new ArrayList<>();
            collect(parse(frames.get(f)).getDocumentElement(), null, page, raw);
            assertEquals(ref.size(), raw.size(), role + " frame " + f + " paints the static scene");
            List<Painted> order = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                Painted r = raw.get(i);
                Painted s = ref.get(i);
                assertEquals(s.el().getLocalName(), r.el().getLocalName(), role + " frame " + f + " element " + i);
                assertArrayEquals(Arrays.copyOf(s.box(), 4), Arrays.copyOf(r.box(), 4),
                    role + " frame " + f + " element " + i + " geometry");
                order.add(new Painted(r.el(), r.color(), s.label(), r.box()));
            }
            int k = seqs.get(f);
            for (int i = 0; i < order.size(); i++) {
                Painted p = order.get(i);
                if (!p.label() || !role.equals(roleOf(p.el()))) {
                    continue;
                }
                // Plated is read on the static bake: a future frame dims the plate's own fill.
                boolean plated = hasPlate((Element) ref.get(i).el().getParentNode());
                Element group = (Element) p.el().getParentNode();
                if (platedOnly && !plated) {
                    continue;
                }
                int seq = Integer.parseInt(group.getAttribute("data-sirentide-seq"));
                int state = seq < k ? 0 : seq == k ? 1 : 2;
                if (plated) {
                    platedByState[state]++;
                }
                int at = i;
                String context = "frame " + f + " (" + STATES[state] + ") ";
                checks.add(() -> assertGlyphReads(order, at, role, page, context));
            }
        }
        assertTrue(platedByState[0] > 0 && platedByState[1] > 0 && platedByState[2] > 0,
            role + ": plated glyphs checked in every state (done, active, future): "
                + Arrays.toString(platedByState));
        assertAll(role + " frame label contrast on " + page, checks);
    }

    private static void assertEveryLabelGlyphReads(Document svg, String role, Page page, int groupCount) {
        List<Painted> order = new ArrayList<>();
        collect(svg.getDocumentElement(), null, page, order);
        assertEquals(groupCount, groups(svg, role).size(), role + " groups baked");
        List<Executable> checks = new ArrayList<>();
        int glyphs = 0;
        for (int i = 0; i < order.size(); i++) {
            Painted p = order.get(i);
            if (!p.label() || !role.equals(roleOf(p.el()))) {
                continue;
            }
            glyphs++;
            int at = i;
            checks.add(() -> assertGlyphReads(order, at, role, page, ""));
        }
        assertTrue(glyphs > 0, "found label glyphs to check");
        assertAll(role + " label contrast on " + page, checks);
    }

    /// The contrast check for the label glyph at `order[at]`: every colour painted EARLIER that
    /// touches its box (or the page, when no opaque plate or rect covers the whole box) is at least
    /// 4.5 to 1. Shapes painted LATER are not behind the glyph; see {@link #nothingIsPaintedOverALabelGlyph}.
    private static void assertGlyphReads(List<Painted> order, int at, String role, Page page, String context) {
        Painted p = order.get(at);
        List<String> behind = new ArrayList<>();
        boolean covered = false;
        for (int j = 0; j < at; j++) {
            Painted under = order.get(j);
            if (under.label() || !touches(under, p.box())) {
                continue;
            }
            if (coversWhole(under, p.box())) {
                behind.clear();
                covered = true;
            }
            behind.add(under.color());
        }
        if (!covered) {
            behind.add(page.background());
        }
        for (String color : behind) {
            double ratio = contrast(p.color(), color);
            assertTrue(ratio >= MIN_CONTRAST, String.format(Locale.ROOT,
                "%s%s label glyph at %.1f,%.1f is %s on %s: %.2f to 1", context, role,
                p.box()[0], p.box()[1], p.color(), color, ratio));
        }
    }

    /// Walks the SVG in document (paint) order. Inside a message/edge group, a path filled with the
    /// page colour (or a theme's resolved text colour) or the plate's text colour is a label glyph;
    /// arrowheads use the stroke colours.
    private static void collect(Element el, String role, Page page, List<Painted> out) {
        String r = el.hasAttribute("data-sirentide-role") ? el.getAttribute("data-sirentide-role") : role;
        switch (el.getLocalName()) {
            case "rect" -> {
                double x = num(el, "x");
                double y = num(el, "y");
                out.add(new Painted(el, resolve(el.getAttribute("fill"), page), false,
                    new double[] {x, y, x + num(el, "width"), y + num(el, "height")}));
            }
            case "line" -> out.add(new Painted(el, resolve(el.getAttribute("stroke"), page), false,
                new double[] {num(el, "x1"), num(el, "y1"), num(el, "x2"), num(el, "y2"),
                    num(el, "stroke-width") / 2}));
            case "path" -> {
                String fill = el.getAttribute("fill");
                boolean label = ("message".equals(r) || "edge".equals(r))
                    && ("currentColor".equals(fill) || "#000000".equals(fill) || page.text().equals(fill));
                double[] box = box(el.getAttribute("d"));
                if (box != null) {
                    out.add(new Painted(el, resolve(fill, page), label, box));
                }
            }
            default -> { }
        }
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e) {
                collect(e, r, page, out);
            }
        }
    }

    private static String roleOf(Element el) {
        for (Node n = el; n instanceof Element e; n = n.getParentNode()) {
            if (e.hasAttribute("data-sirentide-role")) {
                return e.getAttribute("data-sirentide-role");
            }
        }
        return null;
    }

    private static boolean touches(Painted p, double[] b) {
        if ("line".equals(p.el().getLocalName())) {
            double[] l = p.box();
            double h = l[4];
            for (int i = 0; i <= 400; i++) {
                double x = l[0] + (l[2] - l[0]) * i / 400.0;
                double y = l[1] + (l[3] - l[1]) * i / 400.0;
                if (x >= b[0] - h && x <= b[2] + h && y >= b[1] - h && y <= b[3] + h) {
                    return true;
                }
            }
            return false;
        }
        double[] a = p.box();
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /// An opaque rect over the whole box, or a plate path (an arrowhead cut out of it, review 1335)
    /// whose outer ring covers the box and none of whose cut-outs reaches into it.
    private static boolean coversWhole(Painted under, double[] box) {
        String name = under.el().getLocalName();
        if ("rect".equals(name)) {
            return contains(under.box(), box);
        }
        if (!"path".equals(name) || !isPlateFill(under.el().getAttribute("fill"))) {
            return false;
        }
        List<double[]> rings = rings(under.el().getAttribute("d"));
        if (!contains(rings.get(0), box)) {
            return false;
        }
        for (double[] hole : rings.subList(1, rings.size())) {
            if (hole[0] < box[2] && box[0] < hole[2] && hole[1] < box[3] && box[1] < hole[3]) {
                return false;
            }
        }
        return true;
    }

    /// The bounding box of each `M ... Z` ring of a path, in order.
    private static List<double[]> rings(String d) {
        List<double[]> out = new ArrayList<>();
        for (String ring : d.split("(?=M )")) {
            double[] b = box(ring);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    private static boolean isPlateFill(String fill) {
        return PLATE.equals(fill) || DIMMED_PLATE.equals(fill);
    }

    private static boolean contains(double[] outer, double[] inner) {
        return outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    /// A plate is a `#f5f7ff` rect, or a `#f5f7ff` path when an earlier arrowhead is cut out of it.
    private static boolean hasPlate(Element group) {
        for (Node c = group.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && ("rect".equals(e.getLocalName()) || "path".equals(e.getLocalName()))
                && PLATE.equals(e.getAttribute("fill"))) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> labelPaths(Element group) {
        List<Element> out = new ArrayList<>();
        for (Node c = group.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && "path".equals(e.getLocalName())
                && !e.getAttribute("fill").equals("#64748b") && !e.getAttribute("fill").equals("#94a3b8")
                && !e.getAttribute("fill").equals(PLATE)) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<Element> groups(Document svg, String role) {
        List<Element> out = new ArrayList<>();
        var all = svg.getElementsByTagNameNS("*", "g");
        for (int i = 0; i < all.getLength(); i++) {
            Element g = (Element) all.item(i);
            if (role.equals(g.getAttribute("data-sirentide-role"))) {
                out.add(g);
            }
        }
        return out;
    }

    private static String resolve(String color, Page page) {
        return "currentColor".equals(color) ? page.text() : color;
    }

    private static double[] box(String d) {
        Matcher m = NUM.matcher(d);
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        while (m.find()) {
            double x = Double.parseDouble(m.group());
            if (!m.find()) {
                break;
            }
            double y = Double.parseDouble(m.group());
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
        }
        return x0 <= x1 ? new double[] {x0, y0, x1, y1} : null;
    }

    private static double num(Element el, String attr) {
        String v = el.getAttribute(attr);
        return v.isEmpty() ? (attr.equals("stroke-width") ? 1 : 0) : Double.parseDouble(v);
    }

    /// WCAG 2 contrast ratio between two `#rrggbb` colours.
    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        assertTrue(hex != null && hex.matches("#[0-9a-fA-F]{6}"), "a resolvable colour: " + hex);
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = LabelBackingPlateTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Document bake(String dsl) throws Exception {
        return parse(Sirentide.render(dsl));
    }

    private static Document parse(String rendered) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)));
    }
}
