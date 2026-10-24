package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.sirentide.api.RenderOptions;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.Sequence;
import com.sirentide.parse.DslParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/// Frozen, complete served SVGs preserve the actual before-repair geometry independently of future
/// layout changes. Exact selectors AND hashes identify the measured collision slices; no colors or
/// path-shape heuristic classify labels/bars. See the adjacent fixture PROVENANCE.txt.
class FrozenLaneLiveOverlapLintTest {
    private static final String BEFORE = "/overlap/lane-live-before/";

    static final String CLEAN_SEQUENCE =
        "sequence\n  Alice ->> Bob : request\n  alt ok\n    Bob -->> Alice : reply\n"
            + "  else no\n    Bob -->> Alice : later\n  end\n";
    private static final String CLEAN_FLOWCHART =
        "flowchart TD\n  A[Start] --> B{Ready?}\n  B -->|yes| C[Build] --> D[Ship]\n"
            + "  B -->|no| E[Fix] --> A\n";

    @Test
    void frozenActualSequenceGeometryFiresOnAllSixMeasuredFrameActivationPairs() throws Exception {
        Document doc = frozen("sequence-served-f568.svg", 685737,
            "7f380fae063c1f7ed7da74c9c6ffca62f212b6aa7d718bb91ee68cc164b48ff1");
        List<Element> root = children(doc.getDocumentElement());
        assertEquals(506, root.size());
        int[] indices = {55, 58, 88, 90};
        List<String> hashes = List.of(
            "efbad5e7e861f994ebcc9cdad537be8a9c585395b34aeddabd5cacdd083316cb",
            "eb6983cf30623e6af51b3befa23e15f2d1378acf4b36572afa9dc123415adb0f",
            "17bdbc68fa4d2f1dc4ca17d0f0388d1c3875de4e1e547f2224a781e099896fce",
            "729d6a636fabfb7d8f3f11ef126978516f3ebf57bc12c284824b836227329f3b");
        List<GlyphRun> labels = new ArrayList<>();
        for (int i = 0; i < indices.length; i++) {
            labels.add(glyph(root.get(indices[i]), hashes.get(i)));
        }
        List<Rect> bars = List.of(
            rectangle(root.get(472), "484.394", "261.75", "8", "520.5"),
            rectangle(root.get(476), "271.431", "445.5", "8", "336.75"),
            rectangle(root.get(479), "275.431", "612.25", "8", "170"));
        List<Shape> slice = new ArrayList<>(labels);
        slice.addAll(bars);
        LaidOut actualSlice = new LaidOut(1189.751, 806.25, slice, labels, bars);
        List<OverlapLint.Finding> found = OverlapLint.findings(actualSlice);
        assertEquals(6, found.size(), "four original frame paths and three bars give six measured pairs");
        assertEquals(List.of("frame-label:0 x activation:1", "frame-label:1 x activation:0",
            "frame-label:2 x activation:0", "frame-label:2 x activation:1",
            "frame-label:2 x activation:2", "frame-label:3 x activation:1"),
            found.stream().map(f -> f.a() + " x " + f.b()).toList());
        double[][] measured = {{4.671, 9.33}, {8, 9.41}, {2.666, 9.33},
            {4.651, 9.33}, {8, 9.33}, {4.621, 7.16}};
        for (int i = 0; i < measured.length; i++) {
            assertEquals(measured[i][0], found.get(i).w(), 0.000001);
            assertEquals(measured[i][1], found.get(i).h(), 0.000001);
        }
        assertTrue(OverlapLint.findings(new LaidOut(1189.751, 806.25, slice)).isEmpty(),
            "the pre-extension text-only rule misses precisely this real frame/activation category");
    }

    @Test
    void frozenActualCancelledEdgeLabelsFireThroughTheExistingTextOwnerRule() throws Exception {
        Document doc = frozen("flowchart-served-f568.svg", 411761,
            "23cf4ad3801017199c83597bfe5f8a052d833bd9bc9b1595b4844d4cc2de2b16");
        List<Element> root = children(doc.getDocumentElement());
        assertEquals(27, root.size());
        Group confirmed = edge(root, "X-CA", 19,
            "b432cac847591ce198cf570e9b51c0ad893c80eea373d7bd909fda2bd182c264");
        Group unknown = edge(root, "X-CU", 21,
            "695f5c004f442046d9aed6ad7ea0e90a6ad2372a25b54b3668b125bdcb511d4e");
        List<OverlapLint.Finding> found = OverlapLint.findings(
            new LaidOut(1206.938, 505.652, List.of(confirmed, unknown)));
        assertEquals(1, found.size());
        assertEquals("edge:X-CA", found.getFirst().a());
        assertEquals("edge:X-CU", found.getFirst().b());
        assertEquals(24.31, found.getFirst().w(), 0.000001);
        assertEquals(4.68, found.getFirst().h(), 0.000001);
    }

    @Test
    void canonicalInputsArePinnedSeparatelyFromTheirFrozenBeforeGeometry() throws Exception {
        assertEquals("dbfab29b7be1775fffd5d0c889d2e758e15c4f6e5ba14d3b89eeedda0c1a0fb6",
            sha256(resource("/sequence/lane-live-acceptance-diagram1.sirentide")));
        assertEquals("3785939d00491b5fb03dfc4119586d1e2a23d72d12203b5a4933d5df9672bcfd",
            sha256(resource("/flowchart-diamond-lane-live.mmd")));
    }

    @Test
    void realCleanSequenceHasFrameLabelsAndActivationBarsAndPasses() {
        Sequence parsed = assertInstanceOf(Sequence.class, DslParser.parse(CLEAN_SEQUENCE));
        LaidOut laid = SequenceLayout.layout(parsed);
        assertFalse(laid.frameLabels().isEmpty(), "actual frame glyph metadata is required");
        assertFalse(laid.activationBars().isEmpty(), "actual activation metadata is required");
        assertTrue(OverlapLint.findings(laid).isEmpty());
        var lint = Sirentide.renderWithDiagnostics(CLEAN_SEQUENCE, null, new RenderOptions(true));
        assertEquals("", lint.diagnostics().detail());
        assertEquals(Sirentide.render(CLEAN_SEQUENCE), lint.svg());
    }

    @Test
    void realCleanFlowchartHasTwoLabeledOutgoingEdgesAndPasses() {
        var parsed = assertInstanceOf(com.sirentide.ir.Flowchart.class, DslParser.parse(CLEAN_FLOWCHART));
        LaidOut laid = FlowchartLayout.layout(parsed);
        List<Group> labeled = laid.shapes().stream().filter(Group.class::isInstance).map(Group.class::cast)
            .filter(g -> g.anchor().role() == SirentideRole.EDGE)
            .filter(g -> g.members().stream().anyMatch(GlyphRun.class::isInstance)).toList();
        assertEquals(2, labeled.size(), "both real branch labels must remain present");
        assertTrue(OverlapLint.findings(laid).isEmpty());
        var lint = Sirentide.renderWithDiagnostics(CLEAN_FLOWCHART, null, new RenderOptions(true));
        assertEquals("", lint.diagnostics().detail());
        assertEquals(Sirentide.render(CLEAN_FLOWCHART), lint.svg());
    }

    private static Group edge(List<Element> root, String id, int seq, String hash) throws Exception {
        List<Element> matching = root.stream()
            .filter(e -> "edge".equals(e.getAttribute("data-sirentide-role")))
            .filter(e -> id.equals(e.getAttribute("data-sirentide-id"))).toList();
        assertEquals(1, matching.size());
        Element group = matching.getFirst();
        assertEquals("g", group.getLocalName());
        assertEquals(Integer.toString(seq), group.getAttribute("data-sirentide-seq"));
        List<Element> members = children(group);
        assertEquals(3, members.size());
        // Child1 is the arrowhead. The exact glyph child and path hash are pinned independently.
        GlyphRun label = glyph(members.get(2), hash);
        return new Group(new Anchor(SirentideRole.EDGE, id, seq), List.of(label));
    }

    private static GlyphRun glyph(Element element, String hash) throws Exception {
        assertEquals("path", element.getLocalName());
        String d = element.getAttribute("d");
        assertEquals(hash, sha256(d.getBytes(StandardCharsets.UTF_8)));
        return new GlyphRun(d, element.getAttribute("fill"));
    }

    private static Rect rectangle(Element element, String x, String y, String w, String h) {
        assertEquals("rect", element.getLocalName());
        assertEquals(x, element.getAttribute("x"));
        assertEquals(y, element.getAttribute("y"));
        assertEquals(w, element.getAttribute("width"));
        assertEquals(h, element.getAttribute("height"));
        return new Rect(Double.parseDouble(x), Double.parseDouble(y), Double.parseDouble(w),
            Double.parseDouble(h), element.getAttribute("fill"));
    }

    private static Document frozen(String name, int size, String hash) throws Exception {
        byte[] bytes = resource(BEFORE + name);
        assertEquals(size, bytes.length);
        assertEquals(hash, sha256(bytes));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = FrozenLaneLiveOverlapLintTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing exact source/served fixture " + name);
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
