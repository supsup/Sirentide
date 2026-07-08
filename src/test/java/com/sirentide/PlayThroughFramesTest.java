package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// Receipts for the PLAY-THROUGH FRAMES API (plan sirentide-play-through-frames): the first CONSUMER
/// of the `data-sirentide-seq` semantic anchors. `Sirentide.renderFrames(dsl)` turns the already-
/// assigned seq step-ordering into N STATIC SVG frames, frame k emphasizing the active step, each a
/// standalone CSP-clean bake (zero JS). These pin: N == distinct seq count, frames DIFFER, geometry is
/// IDENTICAL across frames (only fills/strokes change), the static `render` stays byte-identical, a
/// no-seq/malformed diagram degrades to a single frame == render, every frame is containment-clean +
/// well-formed, and the DELETE-MUTANT (make the emphasis a no-op → {@link #framesDiffer} fails).
class PlayThroughFramesTest {

    /// A sequence diagram: 4 messages + 3 actors → 7 anchored groups, each with a distinct seq, so the
    /// play-through has 7 frames. seq maps to steps (messages first, then actor heads) — the canonical
    /// "flow you play". The accent (thick orange arrow) reads cleanly on the message lines.
    private static final String SEQ_DSL =
        "sequence\nClient ->> Gateway : GET /token\nGateway ->> Auth : validate\n"
            + "Auth -->> Gateway : ok\nGateway -->> Client : 200 token";

    /// The play-through accent hex + a dimmed-tint probe — mirrors the emit scheme (Emphasis.ACCENT /
    /// the sink-package constants are package-private, so the values are restated here to pin them).
    private static final String ACCENT = "#e8590c";
    private static final double DIM_T = 0.62;

    // ---- Receipt 1: N frames, N == distinct seq count -----------------------------------------

    @Test
    void frameCountEqualsDistinctSeqCount() {
        List<String> frames = Sirentide.renderFrames(SEQ_DSL);
        int distinct = distinctSeqCount(Sirentide.render(SEQ_DSL));
        assertEquals(7, distinct, "the demo sequence has 4 messages + 3 actors = 7 seq steps");
        assertEquals(distinct, frames.size(),
            "renderFrames must return exactly one frame per distinct seq value");
    }

    // ---- Receipt 2: frames DIFFER; active group's fill/stroke differs from its dimmed form -----

    @Test
    void framesDiffer() {
        List<String> frames = Sirentide.renderFrames(SEQ_DSL);
        // Consecutive frames are never byte-identical — each emphasizes a different step. (DELETE-
        // MUTANT: neuter Emphasis.accent/box/strokeWidth to return their input and this fails — every
        // frame collapses to the same bake.)
        for (int i = 1; i < frames.size(); i++) {
            assertNotEquals(frames.get(i - 1), frames.get(i),
                "frame " + (i - 1) + " and " + i + " must differ (different active step)");
        }
        // The active step is really accented: the accent colour appears in a frame but NEVER in the
        // plain static render (the no-emphasis path is clean).
        assertTrue(frames.get(0).contains(ACCENT), "frame 0 accents its active step: " + frames.get(0));
        assertFalse(Sirentide.render(SEQ_DSL).contains(ACCENT),
            "the plain render must carry no play-through accent");

        // The active group's stroke DIFFERS from its dimmed form. Message 0 (a call, stroke #64748b)
        // is ACTIVE in frame 0 (→ accent) and FUTURE in NO frame (seq 0 is lowest); message 3 (the
        // last, highest seq among messages) is FUTURE in frame 0 (dimmed) and ACTIVE in its own frame.
        int lastMsgSeq = 3;                       // messages emit first: seq 0..3
        String activeG = groupBySeq(frames.get(lastMsgSeq), lastMsgSeq);   // its own frame → ACTIVE
        String dimmedG = groupBySeq(frames.get(0), lastMsgSeq);            // frame 0 → FUTURE (dimmed)
        assertNotEquals(activeG, dimmedG, "a group's active form must differ from its dimmed form");
        assertTrue(activeG.contains(ACCENT), "the active group carries the accent: " + activeG);
        // The dimmed form carries the lightened tint of the reply stroke (#94a3b8), not the accent.
        String dimReply = com.sirentide.layout.Colors.lighten("#94a3b8", DIM_T);
        assertTrue(dimmedG.contains(dimReply),
            "the dimmed group is tinted toward white (" + dimReply + "): " + dimmedG);
        assertFalse(dimmedG.contains(ACCENT), "a future/dimmed group must not carry the accent");
    }

    // ---- Receipt 3: geometry IDENTICAL across frames (only fill/stroke change) -----------------

    @Test
    void geometryIsIdenticalAcrossFrames() {
        List<String> frames = Sirentide.renderFrames(SEQ_DSL);
        String baseGeom = stripPresentation(Sirentide.render(SEQ_DSL));
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(baseGeom, stripPresentation(frames.get(i)),
                "frame " + i + ": stripping fills/strokes must leave the SAME geometry as the static "
                    + "render — layout runs once, only presentation changes");
        }
    }

    // ---- Receipt 4: render(dsl) BYTE-IDENTICAL (no-emphasis path untouched) --------------------

    @Test
    void staticRenderStaysByteIdentical() {
        // The frame API must not perturb the static render (no shared-state mutation; deterministic).
        String before = Sirentide.render(SEQ_DSL);
        Sirentide.renderFrames(SEQ_DSL);
        String after = Sirentide.render(SEQ_DSL);
        assertEquals(before, after, "renderFrames must not change what render() produces");
        // And the static render is the un-emphasized bake: no accent, no injected emphasis artefact.
        assertFalse(after.contains(ACCENT), "the static render carries no emphasis accent");
        // (The true byte-identity-to-committed guarantee is enforced by GoldenSvgTest's byte pins,
        // which would red if the null-emphasis emit path shifted a single byte.)
    }

    // ---- Receipt 5: no-seq / malformed → single frame == render, never throws ------------------

    @Test
    void noSeqOrMalformedDegradesToASingleRenderFrame() {
        // A malformed sequence (no anchored groups → the visible degrade), an unknown type (inert
        // shell), an empty source (inert shell), and a SINGLE-slice pie (one seq → nothing to play).
        for (String dsl : List.of(
            "sequence\ngarbage line\nmore garbage",
            "anything",
            "",
            "pie\n\"All\" : 5")) {
            List<String> frames = Sirentide.renderFrames(dsl);
            assertEquals(1, frames.size(), "no-seq/single-seq must yield exactly one frame: " + dsl);
            assertEquals(Sirentide.render(dsl), frames.get(0),
                "the single frame must be byte-identical to render(dsl): " + dsl);
        }
    }

    // ---- Receipt 6: each frame is containment-clean + well-formed XML --------------------------

    @Test
    void everyFrameIsContainmentCleanAndWellFormed() throws Exception {
        for (String dsl : List.of(SEQ_DSL,
            "flowchart TD\nA[Start] --> B{Tests green?}\nB -->|yes| C[Ship] #22c55e\nB -->|no| D[Fix]")) {
            List<String> frames = Sirentide.renderFrames(dsl);
            for (int i = 0; i < frames.size(); i++) {
                // Well-formed XML (a strict JDK parse throws on any malformation).
                Document doc = parse(frames.get(i));
                // Every element/attr/value inside the allowlist — the ContainmentTest guard, per frame.
                checkElement(doc.getDocumentElement(), dsl + " frame " + i);
            }
        }
    }

    // ---- Receipt 8-adjacent: determinism ------------------------------------------------------

    @Test
    void framesAreDeterministic() {
        assertEquals(Sirentide.renderFrames(SEQ_DSL), Sirentide.renderFrames(SEQ_DSL),
            "same dsl → same List<String> (byte-stable)");
    }

    // ==== helpers ==============================================================================

    private static int distinctSeqCount(String svg) {
        Matcher m = Pattern.compile("data-sirentide-seq=\"(\\d+)\"").matcher(svg);
        Set<Integer> seqs = new TreeSet<>();
        while (m.find()) {
            seqs.add(Integer.parseInt(m.group(1)));
        }
        return seqs.size();
    }

    /// Strip every PRESENTATION attribute (fill / stroke / stroke-width) so what remains is pure
    /// GEOMETRY (path `d`, rect/line coordinates, the anchor attrs). Two frames that agree here differ
    /// only in colour — the "layout runs once" invariant.
    private static String stripPresentation(String svg) {
        return svg
            .replaceAll(" fill=\"[^\"]*\"", "")
            .replaceAll(" stroke-width=\"[^\"]*\"", "")
            .replaceAll(" stroke=\"[^\"]*\"", "");
    }

    /// Extract the `<g …data-sirentide-seq="k"…>…</g>` substring (groups never nest, so the first
    /// `</g>` closes it). Used to compare one element's presentation across frames.
    private static String groupBySeq(String svg, int seq) {
        int tag = svg.indexOf("data-sirentide-seq=\"" + seq + "\"");
        assertTrue(tag >= 0, "seq " + seq + " group present");
        int open = svg.lastIndexOf("<g ", tag);
        int close = svg.indexOf("</g>", tag);
        assertTrue(open >= 0 && close > open, "well-formed group for seq " + seq);
        return svg.substring(open, close + 4);
    }

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    /// The ContainmentTest allowlist walk, inlined: every element ∈ allowed set, every attr ∈ that
    /// element's set, every value obeys its constraint. Proves a play-through frame stays producer ⊆
    /// contract exactly like the static render (no new element/attr — emphasis is fill/stroke only).
    private static void checkElement(Element el, String ctx) {
        String tag = el.getTagName();
        if (!SirentideContract.ALLOWED_ELEMENTS.contains(tag)) {
            fail("element <" + tag + "> outside allowlist (" + ctx + ")");
        }
        Set<String> allowedAttrs = SirentideContract.ALLOWED_ATTRS.get(tag);
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            String value = a.getNodeValue();
            if (!allowedAttrs.contains(name)) {
                fail("attribute " + name + "=\"" + value + "\" on <" + tag + "> outside allowlist (" + ctx + ")");
            }
            if (!SirentideContract.attributeValueValid(name, value)) {
                fail("value " + name + "=\"" + value + "\" on <" + tag + "> violates its constraint (" + ctx + ")");
            }
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                checkElement((Element) k, ctx);
            }
        }
    }
}
