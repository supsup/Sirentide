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
/// Out of scope here, on purpose: alt/else frame tab and divider labels, which plan 9d56f599 moves
/// into their own bands, and typeset math labels, whose extent is not layout geometry.
class LabelBackingPlateTest {

    private static final String PLATE = "#f5f7ff";
    private static final double MIN_CONTRAST = 4.5;
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

    /// Healthy positive: a message between adjacent actors crosses nothing, so it keeps its exact
    /// former emission: no plate, page-inheriting `currentColor` text.
    @Test
    void aLabelThatCrossesNothingIsUnchanged() throws Exception {
        Document svg = bake("sequence\n  A ->> B : hi\n  B -->> A : ok\n");
        for (Element g : groups(svg, "message")) {
            assertFalse(hasPlate(g), "no crossing, no plate");
            assertTrue(labelPaths(g).stream().allMatch(p -> "currentColor".equals(p.getAttribute("fill"))),
                "an unplated label keeps currentColor");
        }
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
    /// NOT asserted, and why: an UNPLATED active label on a LIGHT page or the neutral theme takes the
    /// page-level accent `#e8590c` (3.58 to 1 on white, 3.28 on `#f5f5f5`), and an unplated future
    /// label is dimmed by design. That is the frames' page-level accent, older than this plan and
    /// shared by every diagram kind; no single accent clears 4.5 on both `#0a0a0a` and `#ffffff`.
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
    /// touches its box (or the page, when no opaque rect covers the whole box) is at least 4.5 to 1.
    private static void assertGlyphReads(List<Painted> order, int at, String role, Page page, String context) {
        Painted p = order.get(at);
        List<String> behind = new ArrayList<>();
        boolean covered = false;
        for (int j = 0; j < at; j++) {
            Painted under = order.get(j);
            if (under.label() || !touches(under, p.box())) {
                continue;
            }
            if ("rect".equals(under.el().getLocalName()) && contains(under.box(), p.box())) {
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

    private static boolean contains(double[] outer, double[] inner) {
        return outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    private static boolean hasPlate(Element group) {
        for (Node c = group.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && "rect".equals(e.getLocalName()) && PLATE.equals(e.getAttribute("fill"))) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> labelPaths(Element group) {
        List<Element> out = new ArrayList<>();
        for (Node c = group.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && "path".equals(e.getLocalName())
                && !e.getAttribute("fill").equals("#64748b") && !e.getAttribute("fill").equals("#94a3b8")) {
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
