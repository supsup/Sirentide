package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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

/// The `mathblock` moat proof (plan sirentide-mathblock): a standalone display-math block baked
/// FULL-SIZE through the REAL {@link LatteXMathFragmentRenderer} — the whole body is ONE LaTeX
/// expression (no `$` delimiters), typeset to STIX glyph paths + a fraction-bar `<rect>`, centered
/// on the canvas. Where {@link GoldenSvgTest} pins the NULL-renderer degrade (source-as-text), this
/// proves the real typeset bake that GoldenSvgTest structurally cannot reach.
///
/// DELETE-MUTANT WITNESS: {@link #blockEmitsAMathBoxForRealMath}. Break {@code MathBlockLayout} so it
/// never emits the fragment (e.g. make {@code placeFragment} fall through to the text degrade, or drop
/// the {@code shapes.add(new MathBox(...))} line) and this test goes RED — with no MathBox the block
/// degrades to raw-source text and the `<g fill … transform=translate>` wrapper vanishes. Proves the
/// suite exercises the real full-size bake, not a vacuous path.
class MathBlockRealRenderTest {

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    private static final String FRAC = "mathblock\n  \\frac{a}{b}\n";
    private static final String SUM =
        "mathblock\n  \\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n";

    /// The MathBox wrapper the emitter puts around a baked fragment (`<g fill=… transform=translate(`).
    private static final Pattern MATHBOX = Pattern.compile("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*");

    // -- 1. real baked display math (a fraction bar rect + glyph paths) ------------

    @Test
    void realRenderBakesTheFractionBarAndGlyphPaths() {
        String svg = Sirentide.render(FRAC, REAL);
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "not the inert shell: " + preview(svg));
        assertTrue(svg.contains("<path"), "baked glyph paths present: " + preview(svg));
        // The fraction bakes its bar as a <rect> — the display-math structure survived to the canvas.
        assertTrue(svg.contains("<rect"), "the fraction bar <rect> baked: " + preview(svg));
        // No LaTeX source leaked as text (it is baked to glyphs, not passed through).
        assertFalse(svg.contains("\\frac"), "\\frac is baked away, not passed through: " + preview(svg));
    }

    @Test
    void sumWithLimitsRenders() {
        // The showcase equation: a big-operator sum WITH limits equals a fraction. It bakes (a real,
        // non-inert canvas with a MathBox and a fraction bar), proving big operators + sub/superscript
        // limits reach the canvas through the block.
        String svg = Sirentide.render(SUM, REAL);
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "not the inert shell: " + preview(svg));
        assertTrue(MATHBOX.matcher(svg).matches(), "a MathBox baked the sum equation: " + preview(svg));
        assertTrue(svg.contains("<rect"), "the fraction bar <rect> baked: " + preview(svg));
    }

    // -- 2. the DELETE-MUTANT witness: a MathBox is actually emitted ---------------

    @Test
    void blockEmitsAMathBoxForRealMath() {
        String svg = Sirentide.render(FRAC, REAL);
        assertTrue(MATHBOX.matcher(svg).matches(),
            "the block bakes a MathBox <g fill … translate> around the real fragment (mutant witness): "
                + preview(svg));
    }

    // -- 3. FULL-SIZE: the block is bigger than the same latex at label size -------

    @Test
    void displayMathIsBiggerThanLabelSize() {
        // The block bakes at DISPLAY size (28px), strictly bigger than an inline label run (16px). Prove
        // it two ways: the renderer's fragment width grows with size, AND the block's own canvas width
        // exceeds the label-size fragment (so the block is genuinely full-size, not label-inline-sized).
        Optional<MathFragment> display = REAL.render("\\frac{a}{b}", 28.0);
        Optional<MathFragment> label = REAL.render("\\frac{a}{b}", 16.0);
        assertTrue(display.isPresent() && label.isPresent(), "both sizes render");
        assertTrue(display.get().widthPx() > label.get().widthPx(),
            "the display-size fragment is wider than the label-size one: display="
                + display.get().widthPx() + " label=" + label.get().widthPx());
        double blockWidth = svgWidth(Sirentide.render(FRAC, REAL));
        assertTrue(blockWidth > label.get().widthPx(),
            "the block canvas is wider than a label-size fragment (it is full-size display math): block="
                + blockWidth + " label=" + label.get().widthPx());
    }

    // -- 4. containment: the whole baked block stays inside the alphabet -----------

    @Test
    void realRenderStaysInsideTheContainmentAllowlist() throws Exception {
        checkElement(parse(Sirentide.render(SUM, REAL)).getDocumentElement());
    }

    // -- 5. malformed -> text degrade (per block): still renders, no throw ---------

    @Test
    void malformedLatexDegradesGracefullyWithoutThrowing() {
        // Unbalanced braces / an unknown command make LatteX throw; the renderer maps that to empty, so
        // the block degrades to its raw source baked as text — a well-formed, non-inert SVG, no throw.
        for (String dsl : new String[] {
            "mathblock\n  \\frac{a\n",
            "mathblock\n  \\undefinedcmd{x}\n"}) {
            String svg = Sirentide.render(dsl, REAL);   // must not throw
            assertFalse(svg.contains("width=\"0\" height=\"0\""),
                "malformed math still bakes its source as text, not inert: " + preview(svg));
            assertTrue(svg.contains("<path"), "the source baked to glyph paths: " + preview(svg));
        }
    }

    // -- helpers ------------------------------------------------------------------

    private static double svgWidth(String svg) {
        Matcher m = Pattern.compile("<svg[^>]*\\bwidth=\"([0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg has a numeric width: " + preview(svg));
        return Double.parseDouble(m.group(1));
    }

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

    private static String preview(String svg) {
        return svg.length() > 200 ? svg.substring(0, 200) + "…" : svg;
    }
}
