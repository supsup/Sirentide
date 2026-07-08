package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

/// TALL-FRAGMENT box growth adopted in the CLASS + ER structural layouts (plan sirentide-tall-math-
/// labels, cycle-2 follow-up). Where flowchart TD/LR + state already grow a fixed-height NODE box to a
/// tall multi-row fragment, the class-compartment ROWS and the ER attribute ROWS now do the same: an
/// attribute / method / entity-row whose `$…$` carries a matrix / cases / stacked fraction grows THAT
/// row (and, cumulatively, the box + the rows below it), reusing the SAME {@link
/// com.sirentide.layout.MathLabel} seam helpers (`isTall`/`boxHeight`/`baselineInBox`) — no new growth
/// policy.
///
/// The correctness crux is the byte-identical-for-short-labels guarantee: growth is gated on
/// `ascent+descent > lineHeight` (the same gate flowchart uses), so a plain-text / short inline-`$x$`
/// class or ER diagram renders EXACTLY as before (its rows keep the fixed pitch). {@link GoldenSvgTest}
/// pins that for the NULL renderer (existing class/ER goldens, no regen); this class pins it for the
/// REAL LatteX renderer AND pins the GROWN geometry with a byte golden.
class TallMathClassErTest {

    private static final boolean UPDATE = Boolean.getBoolean("sirentide.updateGolden");
    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();
    private static final String BORDER = "#475569";   // ClassDiagramLayout.BORDER (box border + dividers)

    /// The GOLDEN fixture (receipt 1): a class with a 2×2 MATRIX attribute (a genuinely tall multi-row
    /// fragment), a plain `+int rank` attribute below it, and a `+det()` method — plus a related plain
    /// class. The matrix row grows, the `rank` row and the method compartment shift down, the box grows,
    /// and the association edge anchors to the (now taller) box border. Baked through the REAL LatteX
    /// renderer, byte-pinned so any drift in the grown geometry is loud.
    private static final String GOLDEN_DSL =
        "classDiagram\n"
            + "  class Matrix {\n"
            + "    +grid $\\begin{matrix} a & b \\\\ c & d \\end{matrix}$\n"
            + "    +int rank\n"
            + "    +det() double\n"
            + "  }\n"
            + "  Matrix --> Scalar : maps\n";

    /// The 4-ROW matrix (ascent+descent ≈ 48 px, genuinely TALLER than one 15 px line) — used for the
    /// containment + delete-mutant assertions, where the fragment must OVERFLOW its compartment if
    /// growth is removed. Rendered as a SINGLE class so the compartments are unambiguous.
    private static final String TALL_LATEX =
        "\\begin{matrix} a & b \\\\ c & d \\\\ e & f \\\\ g & h \\end{matrix}";
    private static final String TALL_CLASS_DSL =
        "classDiagram\n  class M {\n    +g $" + TALL_LATEX + "$\n    +run() void\n  }\n";

    // -- receipt 1: the byte-pinned golden ----------------------------------------

    @Test
    void tallMatrixClassMatchesGolden() throws Exception {
        String actual = Sirentide.render(GOLDEN_DSL, REAL);
        if (UPDATE) {
            Path dir = Path.of("src/test/resources/golden");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("class-tallmath.svg"), actual, StandardCharsets.UTF_8);
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/golden/class-tallmath.svg")) {
            assertNotNull(in, "missing golden /golden/class-tallmath.svg — regen with "
                + "-Dsirentide.updateGolden=true");
            String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected, actual, "class-tallmath.svg drifted — a tall-math layout change? "
                + "Regen with -Dsirentide.updateGolden=true and review the diff.");
        }
    }

    // -- receipt 2: ZERO-CHANGE pin for short / plain labels ----------------------

    @Test
    void plainClassAndErByteIdenticalAcrossRendererPresence() {
        // No `$…$` at all → the renderer is never consulted → the growth path is never entered → the
        // real-renderer bake is byte-for-byte the null-renderer bake (the strongest zero-change pin).
        String classDsl = "classDiagram\n  class Animal {\n    +String name\n    +int age\n"
            + "    +eat() void\n  }\n  class Dog\n  Animal <|-- Dog : is-a\n";
        assertEquals(Sirentide.render(classDsl), Sirentide.render(classDsl, REAL),
            "a plain class diagram is byte-identical with vs without the renderer");
        String erDsl = "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n"
            + "  CUSTOMER {\n    string name PK\n    string email\n    int age\n  }\n";
        assertEquals(Sirentide.render(erDsl), Sirentide.render(erDsl, REAL),
            "a plain ER diagram is byte-identical with vs without the renderer");
    }

    @Test
    void shortMathRowDoesNotGrowTheClassBox_realRenderer() {
        // A single-baseline `$E=mc^2$` attribute (ascent+descent well under one line) must keep the same
        // box height as the same class with a plain attribute — byte-identical canvas, no vertical creep.
        String mathDsl = "classDiagram\n  class C {\n    +energy $E=mc^2$\n    +go() void\n  }\n";
        String textDsl = "classDiagram\n  class C {\n    +energy here now\n    +go() void\n  }\n";
        assertEquals(canvasHeight(Sirentide.render(textDsl, REAL)),
            canvasHeight(Sirentide.render(mathDsl, REAL)), 1e-9,
            "a short $E=mc^2$ attribute must not grow the class box — same canvas height as plain text");
    }

    @Test
    void shortMathRowDoesNotGrowTheErTable_realRenderer() {
        // The math must be the 2nd token (the row `name`) so ErAttribute.display() keeps it.
        String mathDsl = "erDiagram\n  T {\n    cost $x$\n    int id PK\n  }\n";
        String textDsl = "erDiagram\n  T {\n    cost here\n    int id PK\n  }\n";
        assertEquals(canvasHeight(Sirentide.render(textDsl, REAL)),
            canvasHeight(Sirentide.render(mathDsl, REAL)), 1e-9,
            "a short $O(n)$ ER row must not grow the entity table — same canvas height as plain text");
    }

    // -- receipt 3: the box + subsequent rows grow, anchors track the taller box ---

    @Test
    void tallMatrixGrowsTheClassBoxAndPushesLaterRowsDown() {
        // The Matrix class (matrix attr + a plain attr + a method) is taller than the SAME class with a
        // short attribute in place of the matrix; the growth equals the matrix's over-one-line extent.
        String tallDsl = GOLDEN_DSL;
        String shortDsl = GOLDEN_DSL.replace("$" + matrixLatex2x2() + "$", "small");
        double tallH = canvasHeight(Sirentide.render(tallDsl, REAL));
        double shortH = canvasHeight(Sirentide.render(shortDsl, REAL));
        Optional<MathFragment> frag = REAL.render(matrixLatex2x2(), 12.0);
        assertTrue(frag.isPresent(), "the 2×2 matrix renders");
        double ext = frag.get().heightPx() + frag.get().depthPx();
        double lineH = 15.0;   // FONT.lineHeight(12) — the member pitch
        assertTrue(tallH > shortH + 1e-6, "the matrix class canvas grew: tall=" + tallH + " short=" + shortH);
        assertEquals(ext - lineH, tallH - shortH, 1.0,
            "the box grew by the matrix's over-one-line extent (ext-lineH): grew=" + (tallH - shortH));

        // The association edge anchors to the GROWN Matrix box: its start endpoint lies on the Matrix
        // box's border and within its (now taller) vertical extent — the anchor tracks the grown box.
        String svg = Sirentide.render(tallDsl, REAL);
        double[] mBox = tallestRect(svg);   // the Matrix box is the tallest rect (Scalar is memberless)
        double top = mBox[1];
        double bottom = mBox[1] + mBox[3];
        double[] edge = firstEdgeLineEndpoint(svg);   // an EDGE_STROKE (#94a3b8) segment endpoint
        boolean touchesMatrix =
            onBorder(edge, mBox) || onBorder(new double[] {edge[2], edge[3]}, mBox);
        assertTrue(touchesMatrix,
            "the relation edge meets the grown Matrix box border (top=" + top + " bottom=" + bottom
                + " edge=" + java.util.Arrays.toString(edge) + ")");
    }

    // -- receipt 3 + 5: the matrix stays WITHIN its grown compartment (mutant witness) --

    /// DELETE-MUTANT WITNESS. The 4-row matrix (ascent+descent ≈ 48 px) must sit fully inside its grown
    /// ATTRIBUTE compartment — between the name/attr divider above and the attr/method divider below.
    /// Forcing the attribute row back to the fixed 15 px pitch (the mutation that removes box growth —
    /// e.g. `double rowH = memberPitch;` in {@link com.sirentide.layout.ClassDiagramLayout}'s attr loop)
    /// leaves the compartment 15 px tall, so the 48 px matrix OVERFLOWS past BOTH dividers and these two
    /// containment assertions go RED. Named so receipt 5's mutant has a witness that lives in the suite.
    @Test
    void matrixStaysWithinItsGrownAttributeCompartment() {
        String svg = Sirentide.render(TALL_CLASS_DSL, REAL);
        Optional<MathFragment> frag = REAL.render(TALL_LATEX, 12.0);
        assertTrue(frag.isPresent(), "the 4-row matrix renders");
        double ascent = frag.get().heightPx();
        double descent = frag.get().depthPx();

        // The single class has 4 horizontal border lines: top, name|attr divider, attr|method divider,
        // bottom. The matrix attribute lives in the [div1, div2] compartment.
        List<Double> hy = horizontalBorderYs(svg);
        assertEquals(4, hy.size(),
            "a populated single class draws 4 horizontal border lines (top, 2 dividers, bottom): " + hy);
        double div1 = hy.get(1);   // name | attributes
        double div2 = hy.get(2);   // attributes | methods

        double baseline = firstMathBoxBaseline(svg);
        double fragTop = baseline - ascent;
        double fragBottom = baseline + descent;

        // The attribute compartment GREW to contain the 48 px matrix (a fixed 15 px pitch could not).
        assertTrue(div2 - div1 >= ascent + descent - 1e-6,
            "the attribute compartment grew to hold the matrix: gap=" + (div2 - div1)
                + " ext=" + (ascent + descent));
        // CONTAINED — the matrix ink stays inside its own compartment. THESE are the assertions the
        // delete-mutant (force the attr row to memberPitch) breaks: a 48 px matrix in a 15 px row
        // overflows past both dividers.
        assertTrue(fragTop >= div1 - 1e-6,
            "matrix top stays below the name divider (not in the name band): fragTop=" + fragTop
                + " div1=" + div1);
        assertTrue(fragBottom <= div2 + 1e-6,
            "matrix bottom stays above the method divider (not in the method band): fragBottom="
                + fragBottom + " div2=" + div2);
    }

    // -- receipt 4: the grown class + ER math bakes stay inside the containment allowlist --

    @Test
    void grownClassAndErMathStayInsideTheContainmentAllowlist() throws Exception {
        checkElement(parse(Sirentide.render(GOLDEN_DSL, REAL)).getDocumentElement(), "class-tallmath");
        checkElement(parse(Sirentide.render(TALL_CLASS_DSL, REAL)).getDocumentElement(), "class-4row");
        // ER via a SPACE-FREE tall fraction as the row NAME (the ER attribute tokenizer splits on
        // whitespace and `display()` keeps only `type name key`, so the math must be the 2nd token; a
        // multi-token matrix can't ride a DSL attribute — see the layout test for direct-IR matrix growth).
        String erDsl = "erDiagram\n  T {\n    ratio $\\frac{a}{b}$\n    int id PK\n  }\n";
        String erSvg = Sirentide.render(erDsl, REAL);
        assertTrue(erSvg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
            "the ER fraction actually reaches the MathBox seam: "
                + erSvg.substring(0, Math.min(200, erSvg.length())));
        checkElement(parse(erSvg).getDocumentElement(), "er-tallmath");
    }

    // -- helpers (SVG scraping) ---------------------------------------------------

    private static String matrixLatex2x2() {
        return "\\begin{matrix} a & b \\\\ c & d \\end{matrix}";
    }

    private static double canvasHeight(String svg) {
        Matcher m = Pattern.compile("<svg[^>]*\\sheight=\"([-0-9.]+)\"").matcher(svg);
        assertTrue(m.find(), "svg height present");
        return Double.parseDouble(m.group(1));
    }

    private static double[] tallestRect(String svg) {
        Matcher m = Pattern.compile(
            "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"")
            .matcher(svg);
        double[] best = null;
        while (m.find()) {
            double h = Double.parseDouble(m.group(4));
            if (best == null || h > best[3]) {
                best = new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3)), h};
            }
        }
        assertNotNull(best, "at least one box rect present");
        return best;
    }

    /// The y of every HORIZONTAL border line (the class box border + compartment dividers), sorted. Filters
    /// on the BORDER stroke so the fragment's internal glyph strokes (a different colour) never leak in.
    private static List<Double> horizontalBorderYs(String svg) {
        List<Double> ys = new ArrayList<>();
        Matcher m = Pattern.compile("<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" "
            + "y2=\"([-0-9.]+)\" stroke=\"" + Pattern.quote(BORDER) + "\"").matcher(svg);
        while (m.find()) {
            double y1 = Double.parseDouble(m.group(2));
            double y2 = Double.parseDouble(m.group(4));
            if (Math.abs(y1 - y2) < 1e-6) {
                ys.add(y1);
            }
        }
        ys.sort(Double::compareTo);
        return ys;
    }

    /// The first relationship EDGE segment's endpoints {x1,y1,x2,y2} (stroke = the class EDGE_STROKE).
    private static double[] firstEdgeLineEndpoint(String svg) {
        Matcher m = Pattern.compile("<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" "
            + "y2=\"([-0-9.]+)\" stroke=\"#94a3b8\"").matcher(svg);
        assertTrue(m.find(), "a relationship edge line is present");
        return new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
            Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))};
    }

    /// True iff point {x,y} lies on the border of box {x,y,w,h} (within a small tolerance), i.e. it sits
    /// on one of the four edges and inside the perpendicular span.
    private static boolean onBorder(double[] pt, double[] box) {
        double x = pt[0];
        double y = pt[1];
        double left = box[0];
        double top = box[1];
        double right = box[0] + box[2];
        double bottom = box[1] + box[3];
        double eps = 0.75;
        boolean inX = x >= left - eps && x <= right + eps;
        boolean inY = y >= top - eps && y <= bottom + eps;
        boolean onVert = (Math.abs(x - left) < eps || Math.abs(x - right) < eps) && inY;
        boolean onHoriz = (Math.abs(y - top) < eps || Math.abs(y - bottom) < eps) && inX;
        return onVert || onHoriz;
    }

    private static double firstMathBoxBaseline(String svg) {
        Matcher m = Pattern.compile(
            "<g fill=\"[^\"]+\" transform=\"translate\\(([-0-9.]+) ([-0-9.]+)\\)\"").matcher(svg);
        assertTrue(m.find(), "a MathBox <g fill translate> is present");
        return Double.parseDouble(m.group(2));
    }

    // -- containment walk (mirrors ContainmentTest) -------------------------------

    private void checkElement(Element el, String dsl) {
        String tag = el.getTagName();
        if (!SirentideContract.ALLOWED_ELEMENTS.contains(tag)) {
            fail("element <" + tag + "> is outside the allowlist (dsl: " + dsl + ")");
        }
        var allowedAttrs = SirentideContract.ALLOWED_ATTRS.get(tag);
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            String value = a.getNodeValue();
            assertFalse(!allowedAttrs.contains(name),
                "attribute " + name + "=\"" + value + "\" on <" + tag + "> is outside the allowlist");
            assertTrue(SirentideContract.attributeValueValid(name, value),
                "value " + name + "=\"" + value + "\" on <" + tag + "> violates its constraint");
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                checkElement((Element) k, dsl);
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
}
