package com.sirentide.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/// Plan 43fd0785 (Pip's audit ST-02): "the guard accepts it" must imply "it is well-formed XML
/// where the emitter embeds it".
///
/// At origin/main `FragmentGuard.isClean` returned TRUE for four ill-formed fragments: a duplicate
/// attribute, an undefined entity reference, a case-mismatched open/close pair (the name was
/// lowercased before the stack check), and a control character between tags. `SvgEmitter` embeds
/// the fragment verbatim inside the MathBox `<g>`, so each made the WHOLE emitted SVG unparseable.
/// The repair keeps the allowlist tokenizer and adds a hardened JDK XML parse of the fragment in
/// its embedding context; element and attribute names are now matched case-sensitively.
///
/// Every RED test below names, in a one-line comment, the mutant that turns it red again.
class FragmentGuardWellFormedTest {

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    private static final String DSL = "flowchart TD\n  A[$x$]\n";

    // ---- the four reported witnesses (each RED at origin/main) -----------------

    private static final String DUPLICATE_ATTRIBUTE = "<g><path d=\"M0 0\" d=\"M1 1\"/></g>";
    private static final String UNDEFINED_ENTITY = "<g>&undefined;<path d=\"M0 0\"/></g>";
    private static final String CASE_MISMATCH = "<G><path d=\"M0 0\"/></g>";
    private static final String CONTROL_CHAR = "<g>\u0001<path d=\"M0 0\"/></g>";

    @Test
    void duplicateAttribute_isRejected() {
        // Mutant: drop the wellFormedWhenEmbedded call from isClean -> this returns true again.
        assertFalse(FragmentGuard.isClean(DUPLICATE_ATTRIBUTE), "a duplicate d= attribute is not well-formed");
    }

    @Test
    void undefinedEntity_isRejected() {
        // Mutant: drop the wellFormedWhenEmbedded call from isClean -> this returns true again.
        assertFalse(FragmentGuard.isClean(UNDEFINED_ENTITY), "&undefined; is an undeclared entity");
    }

    @Test
    void caseMismatchedTagPair_isRejected() {
        // Mutant: restore the toLowerCase on the element name AND drop the parse -> true again
        // (either layer alone refuses it; the two tests below each pin one layer).
        assertFalse(FragmentGuard.isClean(CASE_MISMATCH), "<G>...</g> is a mismatched pair in XML");
    }

    @Test
    void controlCharacter_isRejected() {
        // Mutant: drop the wellFormedWhenEmbedded call from isClean -> this returns true again.
        assertFalse(FragmentGuard.isClean(CONTROL_CHAR), "U+0001 is not a legal XML 1.0 character");
    }

    // ---- each layer pinned on its own ------------------------------------------

    @Test
    void caseMismatch_isRefusedByTheParseLayerAlone() {
        // Mutant: make wellFormedWhenEmbedded return true -> this assertion fails.
        assertFalse(FragmentGuard.wellFormedWhenEmbedded(CASE_MISMATCH), "the parser alone refuses <G>...</g>");
    }

    @Test
    void uppercaseNames_areRefusedByTheTokenLayer() {
        // Mutant: restore toLowerCase on element names -> the <G>...</G> case returns true (it is
        // well-formed XML, so only the allowlist can refuse it).
        assertFalse(FragmentGuard.isClean("<G><path d=\"M0 0\"/></G>"), "<G> is not the SVG <g> element");
        // Mutant: restore toLowerCase on attribute names -> true (D is not the d attribute).
        assertFalse(FragmentGuard.isClean("<path D=\"M0 0\"/>"), "D is not the SVG d attribute");
        assertTrue(FragmentGuard.wellFormedWhenEmbedded("<G><path d=\"M0 0\"/></G>"),
            "control: the uppercase pair IS well-formed, so this test pins the token layer");
    }

    // ---- further ill-formedness the tokenizer accepted at origin/main ----------

    @Test
    void otherIllFormedFragments_areRejected() {
        // Mutant: drop the wellFormedWhenEmbedded call from isClean -> every one returns true.
        List<String> illFormed = List.of(
            "<path d=\"M0 0\"fill=\"none\"/>",                 // no whitespace between attributes
            "<path\fd=\"M0 0\"/>",                             // form feed as an attribute separator
            "<g><path d=\"M0 0\"/></g\u000B>",                 // vertical tab inside a close tag
            "<g>a & b<path d=\"M0 0\"/></g>",                  // stray ampersand
            "<g>&#1;<path d=\"M0 0\"/></g>",                   // character reference to an illegal char
            "<g>\uFFFF<path d=\"M0 0\"/></g>",                 // a noncharacter XML forbids
            "<g>\uD800<path d=\"M0 0\"/></g>");                // an unpaired surrogate
        for (String f : illFormed) {
            assertFalse(FragmentGuard.isClean(f), "ill-formed fragment must be refused: " + visible(f));
        }
    }

    // ---- controls: fragments accepted today stay accepted ----------------------

    @Test
    void wellFormedFragments_acceptedBefore_stayAccepted() {
        // Mutant: make wellFormedWhenEmbedded return false (or break the embedding wrapper) -> fails.
        List<String> clean = List.of(
            "<g><path d=\"M0 0\"/></g>",                                            // the plan's positive
            "<g><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>",
            "<path d=\"M0 0L10 0\" fill=\"currentColor\"/>",
            "<g transform=\"scale(0.5 0.5)\"><path d=\"M0 0L10 0\" fill=\"currentColor\"/></g>",
            "<rect x=\"1\" y=\"2\" width=\"3\" height=\"4\"/>",
            "<path d=\"M0 -18 L20 -18 L20 2 L0 2 Z\" fill=\"currentColor\"/>",
            "<g/>",
            "  <g fill=\"#000000\">\n    <path d=\"M 1 2 L 3 4 Z\"/>\n    <rect x=\"0\" y=\"-1\" width=\"5\" height=\"0.5\"/>\n  </g>\n",
            "<g >\n</g >");
        for (String f : clean) {
            assertTrue(FragmentGuard.isClean(f), "a well-formed, contract-clean fragment must stay clean: " + visible(f));
        }
    }

    @Test
    void realLatteXFragments_stayAccepted() {
        // Mutant: any parse-layer over-refusal (e.g. namespace or wrapper drift) -> real math degrades.
        for (String latex : List.of("E=mc^2", "\\frac{v^2}{r}", "\\frac{a}{b}", "O(n)", "x^2",
                "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
                "\\begin{matrix} a & b \\\\ c & d \\end{matrix}",
                "\\begin{cases} x & a \\\\ y & b \\end{cases}")) {
            MathFragment f = REAL.render(latex, 16).orElseThrow(
                () -> new AssertionError("LatteX must render " + latex));
            assertTrue(FragmentGuard.isClean(f.innerSvg()), "real LatteX output for " + latex + " must stay clean");
        }
    }

    // ---- end to end: a witness degrades to the literal $x$ text ----------------

    @Test
    void eachWitness_degradesToTheLiteralSourceText_andTheSvgParses() throws Exception {
        String literal = Sirentide.render(DSL, (latex, size) -> Optional.empty());
        String clean = Sirentide.render(DSL, fixed("<g><path d=\"M0 0\"/></g>"));
        assertNotEquals(literal, clean, "control: a clean fragment reaches the math seam (non-vacuous)");
        parseHardened(clean);
        for (String witness : List.of(DUPLICATE_ATTRIBUTE, UNDEFINED_ENTITY, CASE_MISMATCH, CONTROL_CHAR)) {
            String svg = Sirentide.render(DSL, fixed(witness));
            // Mutant: drop the parse (and the case fix) -> the witness is embedded and the SVG differs.
            assertEquals(literal, svg, "a refused fragment bakes exactly the raw $x$ fallback: " + visible(witness));
            parseHardened(svg);
        }
    }

    // ---- acceptance 3: every math fixture's final SVG parses -------------------

    @Test
    void everyMathFixture_emitsAnSvgAHardenedParserAccepts() throws Exception {
        // Control, GREEN at origin/main too: it guards that the repair keeps real math reaching the
        // seam. Mutant: an over-refusing guard -> the MathBox assertion fails for that fixture.
        String tallMatrix = "\\begin{matrix} a & b \\\\ c & d \\\\ e & f \\\\ g & h \\end{matrix}";
        List<String> fixtures = List.of(
            "flowchart TD\n  A[Energy $E=mc^2$] --> B[$\\frac{v^2}{r}$]\n",
            "flowchart TD\n"
                + "  A[Matrix $\\begin{matrix} a & b \\\\ c & d \\end{matrix}$] --> B[Plain node]\n"
                + "  A --> C[$\\begin{cases} x & a \\\\ y & b \\end{cases}$]\n",
            "flowchart TD\n  A[$" + tallMatrix + "$] --> B[Plain]\n",
            "sequence\n  Alice ->> Bob : cost $\\frac{a}{b}$\n  Bob -->> Alice : $O(n)$ time\n",
            "state\n  [*] --> Idle\n  Idle --> Done : takes $\\frac{n}{2}$\n",
            "quadrant\n  x-axis \"Low\" --> \"High\"\n  y-axis \"Low\" --> \"High\"\n"
                + "  \"cost $\\frac{a}{b}$\" : [0.6, 0.7]\n",
            "timeline\n  \"$E=mc^2$\" : 2020\n  \"Launch\" : 2023\n",
            "gantt\n  \"$E=mc^2$\" : 0-3\n  \"Build\" : 3-8\n",
            "pie\n  \"$E=mc^2$\" : 60\n  \"Rest\" : 40\n",
            "xychart\n  \"$E=mc^2$\" : 5\n  \"Tue\" : 8\n",
            "classDiagram\n"
                + "  class Matrix {\n"
                + "    +grid $\\begin{matrix} a & b \\\\ c & d \\end{matrix}$\n"
                + "    +int rank\n"
                + "    +det() double\n"
                + "  }\n"
                + "  Matrix --> Scalar : maps\n",
            "classDiagram\n  class M {\n    +g $" + tallMatrix + "$\n    +run() void\n  }\n",
            "erDiagram\n  T {\n    ratio $\\frac{a}{b}$\n    int id PK\n  }\n",
            "mathblock\n  \\frac{a}{b}\n",
            "mathblock\n  \\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n");
        for (String dsl : fixtures) {
            String svg = Sirentide.render(dsl, REAL);
            assertTrue(svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
                "fixture must reach the math seam (MathBox present): " + dsl);
            Document d = parseHardened(svg);
            assertEquals("svg", d.getDocumentElement().getLocalName(), "root is <svg> for: " + dsl);
        }
        for (String golden : List.of("mathblock", "flowchart-tallmath", "class-tallmath")) {
            try (InputStream in = getClass().getResourceAsStream("/golden/" + golden + ".svg")) {
                assertNotNull(in, "missing golden " + golden);
                parseHardened(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    // ---- the parser is hardened: no DTD, no entity, nothing fetched ------------

    @Test
    void hardenedParser_acceptsAPlainDocument() {
        // Control for the refusals below: without it a parser that refused everything would pass them.
        assertTrue(FragmentGuard.parsesHardened("<svg xmlns=\"http://www.w3.org/2000/svg\"><g/></svg>"));
    }

    @Test
    void hardenedParser_refusesAnyDoctype() {
        // Mutant: remove the disallow-doctype-decl feature -> the internal entity expands, true.
        assertFalse(FragmentGuard.parsesHardened("<!DOCTYPE svg [<!ENTITY x \"y\">]><svg>&x;</svg>"));
    }

    @Test
    void hardenedParser_neverReadsAnExternalEntity(@TempDir Path dir) throws Exception {
        // Mutant: remove the doctype ban AND set external-general-entities true -> the file's
        // well-formed text is pulled in and the parse succeeds.
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "ok", StandardCharsets.UTF_8);
        String doc = "<!DOCTYPE svg [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><svg>&x;</svg>";
        assertFalse(FragmentGuard.parsesHardened(doc), "an external entity must never be resolved");
    }

    @Test
    @Timeout(20)
    void hardenedParser_neverFetchesAnExternalDtd() throws Exception {
        // Mutant: remove the doctype ban AND set load-external-dtd true -> the parser connects to
        // the listener, so accept() returns instead of timing out.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(500);
            String doc = "<!DOCTYPE svg SYSTEM \"http://127.0.0.1:" + server.getLocalPort()
                + "/x.dtd\"><svg/>";
            assertFalse(FragmentGuard.parsesHardened(doc), "a document with a DOCTYPE is refused");
            assertThrows(SocketTimeoutException.class, server::accept, "the DTD URL was never contacted");
        }
    }

    @Test
    void hardenedParser_refusesAProcessingInstruction() {
        // Mutant: remove Refusing.processingInstruction -> true.
        assertFalse(FragmentGuard.parsesHardened("<svg><?x y?></svg>"));
    }

    @Test
    void entityFragment_degradesEndToEnd() {
        // Control, GREEN at origin/main too (the tokenizer refuses "<!"); kept so a future
        // tokenizer relaxation cannot let a DOCTYPE through without the parse layer catching it.
        String literal = Sirentide.render(DSL, (latex, size) -> Optional.empty());
        assertEquals(literal, Sirentide.render(DSL,
            fixed("<!DOCTYPE g [<!ENTITY x \"y\">]><g>&x;<path d=\"M0 0\"/></g>")));
    }

    // ---- helpers ----------------------------------------------------------------

    private static MathFragmentRenderer fixed(String innerSvg) {
        return (latex, size) -> Optional.of(new MathFragment(innerSvg, 40, 12, 4));
    }

    /// A hardened, quiet DOM parse of a whole emitted SVG; throws on any well-formedness error.
    private static Document parseHardened(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(new DefaultHandler());   // fatalError throws; nothing printed to stderr
        return b.parse(new InputSource(new StringReader(svg)));
    }

    /// Escape non-printable characters so a failure message is readable.
    private static String visible(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7E) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
