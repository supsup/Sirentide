package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// The BROADENED moat proof (plan sirentide-math-in-all-label-types): `$…$` math now bakes in EVERY
/// label-bearing diagram type, not just flowchart node labels. Where {@link MathInLabelsRealRenderTest}
/// proves the flowchart thesis, this proves the SAME shared {@link com.sirentide.layout.MathLabel}
/// seam reaches a sequence MESSAGE label, a state TRANSITION label, a quadrant POINT label — richly
/// (real baked glyph `<path>`s + a fraction `<rect>` bar) — and at least reaches the seam (a MathBox
/// `<g fill … translate>` wrapper, so the math did NOT degrade to raw text) for timeline / gantt / pie
/// / xychart labels.
///
/// DELETE-MUTANT WITNESS: {@link #sequenceMessageMathBakesThroughTheSeam}. Break SequenceLayout so a
/// message label ignores `math` again (route `m.label()` straight to `FONT.textPathD`, never the
/// MathLabel branch) and this test goes RED — the MathBox `<g … translate>` wrapper vanishes and the
/// `$\frac{a}{b}$` degrades to literal `\frac` text. Restore it and it goes green. Named, executed.
class MathInAllLabelsRealRenderTest {

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    /// The MathBox wrapper the emitter bakes for a rendered fragment: `<g fill="…" transform="translate(…`.
    /// Its presence means the label routed through MathLabel and the fragment did NOT degrade to text.
    private static final String MATHBOX_RE = "(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*";

    // -- 1. RICH: sequence message label bakes real math (a fraction bar rect + no literal source) --

    /// The delete-mutant witness (see class doc). A sequence MESSAGE label with a fraction bakes a
    /// MathBox wrapper and a bar `<rect>` — strictly more rects than the feature-off render.
    @Test
    void sequenceMessageMathBakesThroughTheSeam() {
        String dsl = "sequence\n  Alice ->> Bob : cost $\\frac{a}{b}$\n  Bob -->> Alice : $O(n)$ time\n";
        String svg = Sirentide.render(dsl, REAL);
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "not the inert shell: " + preview(svg));
        assertTrue(svg.matches(MATHBOX_RE),
            "a MathBox wraps the baked message math (did NOT degrade to text): " + preview(svg));
        assertFalse(svg.contains("\\frac"), "the \\frac source is baked away, not passed through: " + preview(svg));
        // The fraction bar is an extra <rect> over the feature-off baseline (mutant witness).
        int rectsOff = count(Sirentide.render(dsl, null), "<rect");
        int rectsOn = count(svg, "<rect");
        assertTrue(rectsOn > rectsOff,
            "the real fraction bake adds a bar <rect>: off=" + rectsOff + " on=" + rectsOn);
    }

    // -- 2. RICH: state TRANSITION label bakes real math --------------------------

    @Test
    void stateTransitionLabelMathBakesThroughTheSeam() {
        String dsl = "state\n  [*] --> Idle\n  Idle --> Done : takes $\\frac{n}{2}$\n";
        String svg = Sirentide.render(dsl, REAL);
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "not the inert shell: " + preview(svg));
        assertTrue(svg.matches(MATHBOX_RE),
            "a MathBox wraps the baked transition-label math: " + preview(svg));
        assertFalse(svg.contains("\\frac"), "the \\frac source is baked away: " + preview(svg));
        int rectsOff = count(Sirentide.render(dsl, null), "<rect");
        int rectsOn = count(svg, "<rect");
        assertTrue(rectsOn > rectsOff,
            "the fraction bar adds a <rect> over the feature-off baseline: off=" + rectsOff + " on=" + rectsOn);
    }

    // -- 3. RICH: quadrant POINT label bakes real math ----------------------------

    @Test
    void quadrantPointLabelMathBakesThroughTheSeam() {
        String dsl = "quadrant\n  x-axis \"Low\" --> \"High\"\n  y-axis \"Low\" --> \"High\"\n"
            + "  \"cost $\\frac{a}{b}$\" : [0.6, 0.7]\n";
        String svg = Sirentide.render(dsl, REAL);
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "not the inert shell: " + preview(svg));
        assertTrue(svg.matches(MATHBOX_RE),
            "a MathBox wraps the baked point-label math: " + preview(svg));
        assertFalse(svg.contains("\\frac"), "the \\frac source is baked away: " + preview(svg));
        int rectsOff = count(Sirentide.render(dsl, null), "<rect");
        int rectsOn = count(svg, "<rect");
        assertTrue(rectsOn > rectsOff,
            "the fraction bar adds a <rect>: off=" + rectsOff + " on=" + rectsOn);
    }

    // -- 4. SEAM-PROVEN: timeline / gantt / pie / xychart labels reach the math seam --

    @Test
    void timelineEventLabelReachesTheMathSeam() {
        assertMathBox("timeline\n  \"$E=mc^2$\" : 2020\n  \"Launch\" : 2023\n", "timeline event");
    }

    @Test
    void ganttTaskLabelReachesTheMathSeam() {
        assertMathBox("gantt\n  \"$E=mc^2$\" : 0-3\n  \"Build\" : 3-8\n", "gantt task");
    }

    @Test
    void pieSliceLabelReachesTheMathSeam() {
        assertMathBox("pie\n  \"$E=mc^2$\" : 60\n  \"Rest\" : 40\n", "pie slice");
    }

    @Test
    void xychartCategoryLabelReachesTheMathSeam() {
        assertMathBox("xychart\n  \"$E=mc^2$\" : 5\n  \"Tue\" : 8\n", "xychart category");
    }

    private void assertMathBox(String dsl, String where) {
        String svg = Sirentide.render(dsl, REAL);
        assertFalse(svg.contains("width=\"0\" height=\"0\""), where + ": not the inert shell: " + preview(svg));
        assertTrue(svg.matches(MATHBOX_RE),
            where + ": a MathBox wraps the baked label math (reaches the seam): " + preview(svg));
        assertFalse(svg.contains("E=mc^2"),
            where + ": the E=mc^2 source is baked away, not passed through: " + preview(svg));
    }

    // -- 5. CONTAINMENT: a math-in-sequence-label bake stays inside the alphabet ---

    @Test
    void sequenceMathLabelStaysInsideTheContainmentAllowlist() throws Exception {
        String svg = Sirentide.render(
            "sequence\n  Alice ->> Bob : cost $\\frac{a}{b}$ per $O(n)$ step\n", REAL);
        Document doc = parse(svg);
        checkElement(doc.getDocumentElement());
    }

    // -- 6. null-math degrade: a $…$ label with NO renderer renders plain, never throws --

    @Test
    void nullMathRendersEveryTypePlainWithoutThrowing() {
        for (String dsl : new String[] {
            "sequence\n  Alice ->> Bob : cost $\\frac{a}{b}$\n",
            "state\n  [*] --> Idle\n  Idle --> Done : $O(n)$\n",
            "quadrant\n  \"$x^2$\" : [0.5, 0.5]\n",
            "timeline\n  \"$E=mc^2$\" : 2020\n",
            "gantt\n  \"$E=mc^2$\" : 0-3\n",
            "pie\n  \"$E=mc^2$\" : 60\n  \"Rest\" : 40\n",
            "xychart\n  \"$E=mc^2$\" : 5\n"}) {
            String svg = Sirentide.render(dsl, null);   // must not throw, no MathBox emitted
            assertFalse(svg.contains("width=\"0\" height=\"0\""),
                "null-math still renders a real diagram: " + preview(svg));
            assertFalse(svg.matches(MATHBOX_RE),
                "null-math emits NO MathBox (the plain-text degrade path): " + preview(svg));
        }
    }

    // -- helpers (mirror MathInLabelsRealRenderTest) ------------------------------

    private void checkElement(Element el) {
        String tag = el.getTagName();
        if (!SirentideContract.ALLOWED_ELEMENTS.contains(tag)) {
            fail("element <" + tag + "> is outside the Sirentide allowlist");
        }
        var allowedAttrs = SirentideContract.ALLOWED_ATTRS.get(tag);
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            String value = a.getNodeValue();
            if (!allowedAttrs.contains(name)) {
                fail("attribute " + name + "=\"" + value + "\" on <" + tag + "> is outside the allowlist");
            }
            if (!SirentideContract.attributeValueValid(name, value)) {
                fail("value " + name + "=\"" + value + "\" on <" + tag + "> violates its constraint");
            }
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                checkElement((Element) k);
            }
        }
    }

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private static int count(String s, String sub) {
        int c = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }

    private static String preview(String svg) {
        return svg.length() > 220 ? svg.substring(0, 220) + "…" : svg;
    }
}
