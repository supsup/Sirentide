package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.FragmentGuard;
import com.sirentide.contract.SirentideContract;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// The moat proof (math-in-labels S2, RFC sirentide/39): REAL baked LaTeX rendered inside a
/// Sirentide flowchart label through the REAL {@link LatteXMathFragmentRenderer} (LatteX 0.5.0),
/// not the fake stand-in of {@link MathInLabelsTest}. Where the fake proves the WIRING, this proves
/// the THESIS — a `$E=mc^2$` label bakes to STIX glyph paths and a fraction bakes to a real bar
/// `<rect>`, the whole fragment staying inside the emitter contract.
///
/// DSL NOTE: the fraction is now written `$\frac{v^2}{r}$` — the BRACED common form. It used to be
/// `$\frac vr$` (single-token args) because DslParser.parseEndpoint treated `{`/`}` inside a `[...]`
/// label as a nested delimiter and DROPPED the line; the math-span-aware brace handling
/// (sirentide-flowchart-label-brace-math, {@link com.sirentide.parse.DslParser#shapeCloser}) lifted
/// that limit, so braced LaTeX in a label now reaches the renderer and bakes its fraction bar.
///
/// DELETE-MUTANT WITNESS: {@link #realRenderBakesTheFractionBarRect} — it asserts the real render
/// emits STRICTLY MORE `<rect>`s than the same DSL with the feature off (the extra rect IS the
/// fraction bar). Break the field mapping in {@link LatteXMathFragmentRenderer#render} (return
/// `Optional.empty()` always, or drop the innerSvg) and this test goes RED: with no baked fragment
/// the fraction degrades to text and the rect count collapses to the node-box baseline. Proves the
/// suite exercises the real bake, not a vacuous path.
class MathInLabelsRealRenderTest {

    /// The moat DSL: a text+math label (`Energy $E=mc^2$`) and an all-math fraction label
    /// (`$\frac vr$`), wired by an edge so the flowchart lays out normally.
    private static final String DSL =
        "flowchart TD\n  A[Energy $E=mc^2$] --> B[$\\frac{v^2}{r}$]\n";

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    // -- 1. real baked math renders (not the literal source) ----------------------

    @Test
    void realRenderProducesBakedMathNotInertShell() {
        String svg = Sirentide.render(DSL, REAL);
        // Not the inert degrade shell — a real, sized canvas with drawn content.
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "not the inert shell: " + preview(svg));
        assertTrue(svg.contains("<path"), "baked glyph paths present: " + preview(svg));
        // A MathBox wrapper was emitted -> the math did NOT degrade to raw text.
        assertTrue(svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
            "a MathBox <g fill … translate> wraps the baked fragment: " + preview(svg));
    }

    @Test
    void bakedMathDoesNotLeakTheLiteralLatexSource() {
        // The math is baked to glyph paths, not passed through as text: neither the E=mc^2 source nor
        // the \frac command survives as a literal run anywhere in the output.
        String svg = Sirentide.render(DSL, REAL);
        assertFalse(svg.contains("E=mc^2"), "E=mc^2 source is baked away, not passed through: " + preview(svg));
        assertFalse(svg.contains("\\frac"), "\\frac command is baked away, not passed through: " + preview(svg));
    }

    // -- 2. the fraction bar (the rect-widen load-bearing case + DELETE-MUTANT witness) --

    @Test
    void realRenderBakesTheFractionBarRect() {
        // Feature OFF (null renderer): the two node boxes are the only <rect>s; `$\frac vr$` renders as
        // literal text glyphs (no bar). Feature ON (real renderer): LatteX bakes the fraction, adding
        // its bar as a <rect> ON TOP of the node boxes. So the real render must have STRICTLY MORE
        // rects. This is the mutant witness: break the mapping and the extra rect vanishes.
        int rectsOff = count(Sirentide.render(DSL, null), "<rect");
        int rectsOn = count(Sirentide.render(DSL, REAL), "<rect");
        assertTrue(rectsOn > rectsOff,
            "the real fraction bake adds a bar <rect> over the node-box baseline: off=" + rectsOff
                + " on=" + rectsOn);
    }

    // -- 3. the real innerSvg passes the fragment guard (the {g,path,rect} widen holds) --

    @Test
    void realLatteXFragmentsAreContractCleanFragments() {
        // Directly ask the renderer for the two fragments and prove each real innerSvg is
        // FragmentGuard-clean — the S2 rect-widen ({g,path,rect}) holds against genuine LatteX output,
        // not just the hand-written fixtures. This is the contract closing: producer ⊆ contract.
        Optional<MathFragment> emc = REAL.render("E=mc^2", 16.0);
        Optional<MathFragment> frac = REAL.render("\\frac vr", 16.0);
        assertTrue(emc.isPresent(), "E=mc^2 renders");
        assertTrue(frac.isPresent(), "the fraction renders");
        assertTrue(FragmentGuard.isClean(emc.get().innerSvg()),
            "real E=mc^2 fragment is contract-clean: " + preview(emc.get().innerSvg()));
        assertTrue(FragmentGuard.isClean(frac.get().innerSvg()),
            "real fraction fragment is contract-clean (the rect-widen holds): "
                + preview(frac.get().innerSvg()));
        // The fraction genuinely carries a <rect> bar (else the widen would be untested here).
        assertTrue(frac.get().innerSvg().contains("<rect"),
            "the real fraction fragment carries a bar <rect>: " + preview(frac.get().innerSvg()));
    }

    // -- 4. containment: the whole baked SVG stays inside the Sirentide alphabet ---

    @Test
    void realRenderStaysInsideTheContainmentAllowlist() throws Exception {
        String svg = Sirentide.render(DSL, REAL);
        Document doc = parse(svg);
        checkElement(doc.getDocumentElement());
    }

    // -- 5. malformed -> inert (per fragment): the diagram still renders, no throw ---

    @Test
    void malformedLatexDegradesGracefullyWithoutThrowing() {
        // An unknown command and an unbalanced-brace fraction both make LatteX throw
        // MathSyntaxException; the renderer maps that to empty, so the label degrades to its raw
        // source text and the flowchart still bakes a well-formed, non-inert SVG. (Both labels are
        // brace-free at the DSL level so they reach the renderer at all.)
        for (String dsl : new String[] {
            "flowchart TD\n  A[$\\undefinedcmd$] --> B[ok]\n",
            "flowchart TD\n  A[bad $\\sqrt$ math] --> B[ok]\n"}) {
            String svg = Sirentide.render(dsl, REAL);   // must not throw
            assertFalse(svg.contains("width=\"0\" height=\"0\""),
                "malformed math still renders a real diagram, not the inert shell: " + preview(svg));
            assertTrue(svg.contains("<rect"), "the flowchart still draws its node boxes: " + preview(svg));
        }
    }

    // -- helpers (XML allowlist walk, mirrors ContainmentTest) --------------------

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
        return svg.length() > 200 ? svg.substring(0, 200) + "…" : svg;
    }
}
