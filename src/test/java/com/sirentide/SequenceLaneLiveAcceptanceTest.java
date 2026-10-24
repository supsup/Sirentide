package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Divider;
import com.sirentide.ir.SeqBlock;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.Sequence;
import com.sirentide.parse.DslParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// Plan 9d1a2cdc: real diagram 1, copied verbatim from stafficy-docs
/// f917e2f9856dc32ba4ca01b947006dd1a26d9493:architecture/lane-live-acceptance.md.
/// The expected labels below are literal acceptance data, not output of the layout's clipping
/// code. Only actual baked path geometry counts: title/desc text is deliberately never read.
/// Expected word outlines must occur in order across at most four baked lines. Line breaks are
/// deliberately independent of the production wrapping policy; punctuation and every word must
/// survive, with neither an ellipsis nor a missing tail. No layout display plan is consulted.
class SequenceLaneLiveAcceptanceTest {
    private static final String RESOURCE = "/sequence/lane-live-acceptance-diagram1.sirentide";
    private static final String FIXTURE_SHA256 =
        "dbfab29b7be1775fffd5d0c889d2e758e15c4f6e5ba14d3b89eeedda0c1a0fb6";
    private static final FontMetrics FONT = FontMetrics.bundled();
    // FontMetrics rounds coordinates to 2 dp. Subtracting a translation recovered from its
    // first point can combine two rounding errors; this tolerance permits that, never scaling.
    private static final double PATH_TOLERANCE = 0.025;
    private static final List<String> ACTORS = List.of("Caller", "App", "Git", "Launcher", "Job", "Store");
    private static final List<SeqMessage> MESSAGES = List.of(
        new SeqMessage("Caller", "App", "POST lane-runs with lane and sha", false),
        new SeqMessage("App", "Caller", "403, nothing cloned", true),
        new SeqMessage("App", "Caller", "400 naming the rule, no runId", true),
        new SeqMessage("App", "Caller", "429 naming the limit", true),
        new SeqMessage("App", "Caller", "202 with runId, status running", true),
        new SeqMessage("App", "Git", "clone the project's own source, checkout the exact sha", false),
        new SeqMessage("App", "Launcher", "post lane job, network none, allowlisted digest, command verbatim", false),
        new SeqMessage("Launcher", "Job", "start container with checkout at /w", false),
        new SeqMessage("Job", "Launcher", "exit code", true),
        new SeqMessage("App", "Launcher", "poll every 2 s until terminal", false),
        new SeqMessage("App", "App", "skip DELETE on purpose, job not confirmed gone", false),
        new SeqMessage("App", "Launcher", "DELETE the job", false),
        new SeqMessage("Launcher", "App", "confirmed gone (2xx or 404), or not confirmed", true),
        new SeqMessage("App", "Store", "write the receipt, with checkoutRetained when the job is not confirmed gone", false),
        new SeqMessage("App", "App", "remove the checkout", false),
        new SeqMessage("App", "App", "keep the checkout for a sweep not yet built", false),
        new SeqMessage("Caller", "App", "GET lane-runs by runId or by sha", false),
        new SeqMessage("App", "Caller", "the receipt, or none for a refused request", true));
    private static final List<SeqBlock> BLOCKS = List.of(
        new SeqBlock("alt", "seat credential", 1, 15, List.of(
            new Divider(2, "sha not 40 hex, lane not declared, external lane, image not allowlisted"),
            new Divider(3, "every worker busy"), new Divider(4, "accepted")), 0),
        new SeqBlock("alt", "start-unknown, or a cancel whose custody is kept", 10, 12,
            List.of(new Divider(11, "completed, failed, timed out, or the poll failed")), 1),
        new SeqBlock("alt", "job confirmed gone", 14, 15,
            List.of(new Divider(15, "DELETE skipped, or attempted and not confirmed")), 1));

    @Test
    void frozenDiagramRetainsAllEighteenMessagesAndEveryBranch() throws Exception {
        Sequence seq = assertInstanceOf(Sequence.class, DslParser.parse(fixture()));
        assertEquals(ACTORS, seq.actors());
        assertEquals(MESSAGES, seq.messages(), "full literal labels, endpoints, reply kind and order");
        assertEquals(BLOCKS, seq.blocks().stream().sorted(Comparator.comparingInt(SeqBlock::fromMsg)).toList(),
            "three blocks, five dividers, exact ranges and nesting");
        assertTrue(seq.notes().isEmpty());
        assertTrue(seq.lifecycles().isEmpty());
    }

    @Test
    void allEighteenBakedMessageLabelsHaveTheirFullGlyphOutlines() throws Exception {
        Document svg = bake();
        List<Element> groups = groups(svg, "message");
        assertEquals(18, groups.size(), "one actual baked message group per source message");
        Map<String, Double> centers = actorCenters(svg);
        List<Executable> checks = new ArrayList<>();
        for (int i = 0; i < MESSAGES.size(); i++) {
            int at = i;
            checks.add(() -> {
                SeqMessage expected = MESSAGES.get(at);
                Element group = groups.get(at);
                assertEquals(Integer.toString(at), group.getAttribute("data-sirentide-seq"));
                List<Element> actualLabelPaths = paths(group).stream()
                    .filter(e -> !arrowhead(outline(e.getAttribute("d")))).toList();
                LabelMatch glyphs = requireOneFullLabel(actualLabelPaths, expected.label(), 11,
                    "message " + at + " " + expected.from() + " -> " + expected.to());
                assertEquals(new HashSet<>(actualLabelPaths), new HashSet<>(glyphs.runs()),
                    "every message glyph path is accounted for, including any detached ellipsis");
                assertLineGeometry(glyphs, expected.from().equals(expected.to()) ? 140 : 220);
                Box label = labelBox(glyphs);
                List<Element> horizontal = elements(group, "line").stream()
                    .filter(e -> near(number(e, "y1"), number(e, "y2"))).toList();
                assertFalse(horizontal.isEmpty(), "message has real horizontal arrow geometry");
                double row = horizontal.stream().mapToDouble(e -> number(e, "y1")).min().orElseThrow();
                assertTrue(horizontal.stream().anyMatch(e -> near(number(e, "x1"), centers.get(expected.from()))),
                    "message is attached to its actual sender column");
                if (expected.from().equals(expected.to())) {
                    double hookRight = horizontal.stream()
                        .mapToDouble(e -> Math.max(number(e, "x1"), number(e, "x2"))).max().orElseThrow();
                    assertTrue(label.left() >= hookRight + 4,
                        "self-message label clears the actual hook: " + expected.label());
                    int senderIndex = ACTORS.indexOf(expected.from());
                    if (senderIndex + 1 < ACTORS.size()) {
                        double nextColumn = centers.get(ACTORS.get(senderIndex + 1));
                        assertTrue(label.right() + 4 <= nextColumn + PATH_TOLERANCE,
                            "complete self-message label clears the following lifeline: " + expected.label());
                    }
                } else {
                    double left = Math.min(centers.get(expected.from()), centers.get(expected.to()));
                    double right = Math.max(centers.get(expected.from()), centers.get(expected.to()));
                    assertTrue(label.left() >= left - PATH_TOLERANCE && label.right() <= right + PATH_TOLERANCE,
                        "complete label fits its real actor span: " + expected.label());
                    assertTrue(label.bottom() < row, "complete label sits above its own arrow");
                }
            });
        }
        assertAll("all 18 message glyphs, including three self-messages", checks);
        for (int i = 1; i < groups.size(); i++) {
            Box previous = messageBox(groups.get(i - 1));
            Box current = messageBox(groups.get(i));
            assertTrue(current.top() >= previous.bottom() + 1,
                "wrapped message rows " + (i - 1) + " and " + i + " must not overprint");
        }
    }

    @Test
    void bakedFramesRetainAllThreeBlockAndFiveDividerLabels() throws Exception {
        Document svg = bake();
        List<Element> framePaths = paths(svg.getDocumentElement()).stream()
            .filter(e -> !withinRole(e, "message") && !withinRole(e, "actor")).toList();
        HashSet<Element> consumed = new HashSet<>();
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> {
            List<LabelMatch> tabs = matching(framePaths, "alt", 10);
            assertEquals(3, tabs.size(), "all three kind tabs bake");
            tabs.forEach(tab -> consumed.addAll(tab.runs()));
        });
        for (SeqBlock block : BLOCKS) {
            checks.add(() -> consumed.addAll(requireOneFullLabel(
                framePaths, block.label(), 10, "block " + block.fromMsg()).runs()));
            for (Divider divider : block.dividers()) {
                checks.add(() -> consumed.addAll(requireOneFullLabel(
                    framePaths, divider.label(), 10, "divider " + divider.atMsg()).runs()));
            }
        }
        assertAll("full frame and branch glyph paths, never title/desc echoes", checks);
        assertEquals(new HashSet<>(framePaths), consumed,
            "no extra unaccounted frame path, including a detached ellipsis");
    }

    @Test
    void everyBakedPathLineAndRectangleStaysInsideTheRealViewBox() throws Exception {
        Document svg = bake();
        String[] viewBox = svg.getDocumentElement().getAttribute("viewBox").trim().split("\\s+");
        assertEquals(4, viewBox.length);
        assertEquals(0, Double.parseDouble(viewBox[0]), PATH_TOLERANCE);
        assertEquals(0, Double.parseDouble(viewBox[1]), PATH_TOLERANCE);
        double width = Double.parseDouble(viewBox[2]);
        double height = Double.parseDouble(viewBox[3]);
        assertTrue(Double.isFinite(width) && width > 0 && Double.isFinite(height) && height > 0);
        assertEquals(18, groups(svg, "message").size(), "an inert shell must not pass containment");
        for (Element path : paths(svg.getDocumentElement())) {
            Outline o = outline(path.getAttribute("d"));
            for (int i = 0; i < o.coordinates().size(); i += 2) {
                inside(o.coordinates().get(i), o.coordinates().get(i + 1), width, height);
            }
        }
        for (Element line : elements(svg.getDocumentElement(), "line")) {
            inside(number(line, "x1"), number(line, "y1"), width, height);
            inside(number(line, "x2"), number(line, "y2"), width, height);
        }
        for (Element rect : elements(svg.getDocumentElement(), "rect")) {
            double w = number(rect, "width");
            double h = number(rect, "height");
            assertTrue(w >= 0 && h >= 0);
            inside(number(rect, "x"), number(rect, "y"), width, height);
            inside(number(rect, "x") + w, number(rect, "y") + h, width, height);
        }
    }

    @Test
    void glyphOracleAcceptsWrappingButRejectsEllipsisOrMissingTail() throws Exception {
        String full = MESSAGES.get(13).label();
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        assertEquals(1, matching(List.of(labelPath(doc, full, 91.375)), full, 11).size());
        List<Element> wrapped = List.of(labelPath(doc, "write the receipt, with", 91.375),
            labelPath(doc, "checkoutRetained when the job", 111.375),
            labelPath(doc, "is not confirmed gone", 131.375));
        assertEquals(1, matching(wrapped, full, 11).size(), "complete words survive legal soft line breaks");
        assertTrue(matching(List.of(labelPath(doc, "write the receipt, with checkoutRetained…", 91.375)),
            full, 11).isEmpty(),
            "a prefix and ellipsis cannot masquerade as the full outline");
        assertTrue(matching(List.of(wrapped.get(0), wrapped.get(1),
            labelPath(doc, "is not confirmed", 131.375)), full, 11).isEmpty(),
            "the final word must actually become glyphs");
    }

    private static Element labelPath(Document doc, String text, double baseline) {
        Element path = doc.createElement("path");
        path.setAttribute("d", FONT.textPathD(text, 37.125, baseline, 11));
        return path;
    }

    @Test
    void extremePlainLabelsKeepTheExistingMessageAndSelfWidthBounds() throws Exception {
        // The acceptance fix must not simply delete the old caps. This headless counterpart to
        // BrewShotGalleryTest's wide-span guard measures the actually baked outlines, including
        // the separate self-message path. Existing SequenceTest keeps the wide-math guards.
        String huge = "x".repeat(512);
        Document svg = bake("sequence\nA ->> B : x\nB ->> C : x\nC ->> D : x\nD ->> E : x\n"
            + "A ->> E : " + huge + "\nE ->> E : " + huge + "\n");
        List<Element> messages = groups(svg, "message");
        assertEquals(6, messages.size());
        assertBoundedPlainLabel(messages.get(4), 220);
        assertBoundedPlainLabel(messages.get(5), 140);
    }

    private static void assertBoundedPlainLabel(Element message, double cap) {
        List<Element> actual = paths(message).stream().filter(e -> !arrowhead(outline(e.getAttribute("d")))).toList();
        assertTrue(!actual.isEmpty() && actual.size() <= 4, "one to four nonempty bounded text runs");
        double widest = actual.stream().map(e -> outline(e.getAttribute("d")).box())
            .mapToDouble(box -> box.right() - box.left()).max().orElseThrow();
        assertTrue(widest > 50, "a real visible label must not be replaced by an empty run");
        assertTrue(widest <= cap + PATH_TOLERANCE, "extreme plain label remains bounded at " + cap);
    }

    private static String fixture() throws Exception {
        try (InputStream in = SequenceLaneLiveAcceptanceTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "frozen Docs diagram is packaged as a test resource");
            byte[] bytes = in.readAllBytes();
            assertEquals(FIXTURE_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "the acceptance source must not shrink to fit the implementation");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Document bake() throws Exception {
        return bake(fixture());
    }

    private static Document bake(String dsl) throws Exception {
        String rendered = Sirentide.render(dsl);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Element> elements(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static List<Element> paths(Element parent) {
        return elements(parent, "path");
    }

    private static List<Element> groups(Document svg, String role) {
        return elements(svg.getDocumentElement(), "g").stream()
            .filter(e -> role.equals(e.getAttribute("data-sirentide-role"))).toList();
    }

    private static Map<String, Double> actorCenters(Document svg) {
        Map<String, Double> centers = new LinkedHashMap<>();
        for (Element group : groups(svg, "actor")) {
            List<Element> boxes = elements(group, "rect");
            assertEquals(1, boxes.size(), "one real head box per actor");
            Element box = boxes.getFirst();
            centers.put(group.getAttribute("data-sirentide-id"), number(box, "x") + number(box, "width") / 2);
        }
        assertEquals(ACTORS, new ArrayList<>(centers.keySet()));
        return centers;
    }

    private static boolean withinRole(Element e, String role) {
        for (Node n = e.getParentNode(); n instanceof Element parent; n = n.getParentNode()) {
            if (role.equals(parent.getAttribute("data-sirentide-role"))) {
                return true;
            }
        }
        return false;
    }

    private record LabelMatch(List<Element> runs) {}

    private static LabelMatch requireOneFullLabel(List<Element> paths, String full, double size, String context) {
        List<LabelMatch> found = matching(paths, full, size);
        assertEquals(1, found.size(), context + " must bake every glyph of: " + full);
        return found.getFirst();
    }

    private static List<LabelMatch> matching(List<Element> paths, String full, double size) {
        List<String> words = List.of(full.split(" "));
        List<Outline> expected = words.stream().map(w -> outline(FONT.textPathD(w, 0, 0, size))).toList();
        List<LabelMatch> matches = new ArrayList<>();
        for (int start = 0; start < paths.size(); start++) {
            for (int end = start + 1; end <= Math.min(paths.size(), start + 4); end++) {
                List<Element> runs = paths.subList(start, end);
                if (conservesWords(runs, words, expected, size)) {
                    matches.add(new LabelMatch(List.copyOf(runs)));
                }
            }
        }
        return matches;
    }

    /// The frozen source has ASCII-space-separated words, none needing an internal word break.
    /// Compare each word's COMPLETE commands/coordinates modulo translation, consume every actual
    /// coordinate, and require source order. This accepts different legal word-wrap decisions.
    private static boolean conservesWords(List<Element> runs, List<String> words, List<Outline> expected, double size) {
        int word = 0;
        double previousBaseline = Double.NEGATIVE_INFINITY;
        for (Element run : runs) {
            Outline actual = outline(run.getAttribute("d"));
            int command = 0;
            int coordinate = 0;
            double baseline = Double.NaN;
            double nextX = Double.NaN;
            while (command < actual.commands().size()) {
                if (word >= expected.size()) return false;
                Outline wanted = expected.get(word);
                int commandEnd = command + wanted.commands().size();
                int coordinateEnd = coordinate + wanted.coordinates().size();
                if (commandEnd > actual.commands().size() || coordinateEnd > actual.coordinates().size()) return false;
                Outline piece = new Outline(actual.commands().subList(command, commandEnd),
                    actual.coordinates().subList(coordinate, coordinateEnd));
                if (!translated(wanted, piece)) return false;
                double x = piece.coordinates().getFirst() - wanted.coordinates().getFirst();
                double y = piece.coordinates().get(1) - wanted.coordinates().get(1);
                if (Double.isNaN(baseline)) {
                    baseline = y;
                    if (baseline <= previousBaseline + PATH_TOLERANCE) return false;
                } else if (!near(baseline, y) || !near(nextX, x)) {
                    return false; // no collapsed/interleaved words or missing in-line space
                }
                nextX = x + FONT.runWidth(words.get(word), size) + FONT.advance(' ', size);
                word++;
                command = commandEnd;
                coordinate = coordinateEnd;
            }
            if (coordinate != actual.coordinates().size()) return false;
            previousBaseline = baseline;
        }
        return word == expected.size();
    }

    private static boolean arrowhead(Outline path) {
        return path.commands().equals(List.of("M", "L", "L", "Z")) && path.coordinates().size() == 6;
    }

    private static Box labelBox(LabelMatch label) {
        return union(label.runs().stream().map(e -> outline(e.getAttribute("d")).box()).toList());
    }

    private static void assertLineGeometry(LabelMatch label, double cap) {
        Box previous = null;
        for (Element run : label.runs()) {
            Box box = outline(run.getAttribute("d")).box();
            // Up to 1 px permits glyph ink overhang; actual advance spacing is independently
            // conserved word-by-word above. The extreme-x guard below has no such overhang.
            assertTrue(box.right() - box.left() <= cap + 1, "every wrapped glyph run stays bounded");
            if (previous != null) {
                assertTrue(box.top() >= previous.bottom() + 1, "wrapped lines must not overprint");
            }
            previous = box;
        }
    }

    private static Box messageBox(Element message) {
        List<Box> boxes = new ArrayList<>();
        for (Element path : paths(message)) boxes.add(outline(path.getAttribute("d")).box());
        for (Element line : elements(message, "line")) {
            double x1 = number(line, "x1"), x2 = number(line, "x2");
            double y1 = number(line, "y1"), y2 = number(line, "y2");
            boxes.add(new Box(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)));
        }
        assertFalse(boxes.isEmpty(), "message has geometry");
        return union(boxes);
    }

    private static Box union(List<Box> boxes) {
        return new Box(boxes.stream().mapToDouble(Box::left).min().orElseThrow(),
            boxes.stream().mapToDouble(Box::top).min().orElseThrow(),
            boxes.stream().mapToDouble(Box::right).max().orElseThrow(),
            boxes.stream().mapToDouble(Box::bottom).max().orElseThrow());
    }

    private record Box(double left, double top, double right, double bottom) {}

    private record Outline(List<String> commands, List<Double> coordinates) {
        Box box() {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < coordinates.size(); i += 2) {
                minX = Math.min(minX, coordinates.get(i));
                maxX = Math.max(maxX, coordinates.get(i));
                minY = Math.min(minY, coordinates.get(i + 1));
                maxY = Math.max(maxY, coordinates.get(i + 1));
            }
            return new Box(minX, minY, maxX, maxY);
        }
    }

    private static Outline outline(String d) {
        List<String> commands = new ArrayList<>();
        List<Double> coordinates = new ArrayList<>();
        for (String token : d.trim().split("[\\s,]+")) {
            if (List.of("M", "L", "Q", "Z").contains(token)) {
                commands.add(token);
            } else {
                double value = Double.parseDouble(token); // unexpected commands fail loudly
                assertTrue(Double.isFinite(value), "all actual path coordinates are finite");
                coordinates.add(value);
            }
        }
        assertFalse(coordinates.isEmpty(), "a label/marker must have real outline coordinates");
        assertEquals(0, coordinates.size() % 2, "absolute M/L/Q path coordinate pairs");
        return new Outline(commands, coordinates);
    }

    private static boolean translated(Outline expected, Outline actual) {
        if (!expected.commands().equals(actual.commands())
                || expected.coordinates().size() != actual.coordinates().size()) {
            return false;
        }
        double dx = actual.coordinates().get(0) - expected.coordinates().get(0);
        double dy = actual.coordinates().get(1) - expected.coordinates().get(1);
        for (int i = 0; i < expected.coordinates().size(); i++) {
            double offset = (i % 2 == 0) ? dx : dy;
            if (Math.abs(actual.coordinates().get(i) - expected.coordinates().get(i) - offset) > PATH_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    private static double number(Element e, String name) {
        double value = Double.parseDouble(e.getAttribute(name));
        assertTrue(Double.isFinite(value), name + " is finite");
        return value;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= PATH_TOLERANCE;
    }

    private static void inside(double x, double y, double width, double height) {
        assertTrue(x >= -PATH_TOLERANCE && x <= width + PATH_TOLERANCE
                && y >= -PATH_TOLERANCE && y <= height + PATH_TOLERANCE,
            "actual geometry " + x + "," + y + " escapes viewBox " + width + "x" + height);
    }
}
