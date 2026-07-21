package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.contract.SirentideContract;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
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

/// FUZZ / PROPERTY-INVARIANT pass over ALL 21 diagram types (cycle-2 roadmap #4 — the trust floor).
///
/// "malformed → inert, never throws, never escapes the alphabet" was, before this pass, an
/// ASSUMPTION backed by happy-path goldens + a curated containment corpus. This test turns it into a
/// FUZZED contract: it generates a deterministic, adversarial corpus (a few thousand cases — type
/// headers + every truncated prefix, random ASCII/bytes/Unicode incl. control chars/surrogates/NUL,
/// injection payloads in every label position, structural abuse, and bit-flip/char-drop/delimiter-
/// swap mutations of the golden seeds) and asserts four UNIVERSAL invariants on each case:
///
///   - INV-1 (never throws): `render` AND `renderWithDiagnostics` return normally on ANY input. The
///     harness catches Throwable and FAILS with the offending input if anything escapes.
///   - INV-2 (never escapes the alphabet / never injects): the emitted SVG is well-formed XML (parsed
///     with the JDK parser, NOT a regex) AND every element/attribute/value is inside the
///     {@link SirentideContract} allowlist (the same source of truth {@link ContainmentTest} pins).
///     A hostile label (`<script>`, `]]>`, `&`, quotes, a `</svg>`/`</title>` breakout, a raw NUL)
///     must be escaped/neutralized — no raw `<script`, no non-well-formed breakout.
///   - INV-3 (diagnostics explain empty): `renderWithDiagnostics` never throws, its `svg` is
///     byte-identical to `render`, its {@link Diagnostics} is well-formed (non-null outcome/stage,
///     non-blank message), and when a NON-BLANK input degrades to the inert shell the diagnostics
///     carry a REASON (outcome != OK).
///   - INV-4 (geometry containment): EVERY emitted geometry element (`<path>` per command, `<rect>`,
///     `<line>`) sits — on BOTH the x AND the y axis — inside the declared canvas (parsed off the
///     root `<svg>` `viewBox`) ± a small ink tolerance ({@link #CONTAINMENT_TOL}, matched to
///     {@link GeometryEscapeTest}). This generalizes {@link GeometryEscapeTest} (X-axis only, 3
///     curated DSLs) to both axes across the whole ~3000-case adversarial corpus. Types that are
///     KNOWN to overflow today are listed in {@link #INV4_KNOWN_OVERFLOW} — an explicit, documented
///     allowlist so the invariant lands green now and TIGHTENS (delete an entry) as each is fixed;
///     the assertion is NOT weakened and still BITES on every non-allowlisted type.
///
/// Determinism: the corpus is built purely from fixed seeds indexed into `java.util.Random` with
/// FIXED longs + a fixed seed list — NO `Math.random`, NO clock — so the same run reproduces the
/// same cases ({@link #corpusIsDeterministic}).
///
/// FUZZ-FOUND + FIXED (this pass): a control character (NUL/SOH/… — the XML-1.0-illegal C0 class) or
/// an unpaired surrogate embedded in a label reached the one text-as-text sink (the a11y
/// `<title>`/`<desc>`) unescaped, making the emitted SVG NON-WELL-FORMED (INV-2) even though nothing
/// threw. Fixed in `SvgEmitter.xmlEscape` (drops XML-illegal chars at the sink); regressions
/// {@link #controlCharInLabel_staysWellFormedXml} + {@link #loneSurrogateInLabel_staysWellFormed}.
/// Goldens stayed byte-identical (well-formed labels are untouched).
class FuzzInvariantTest {

    /// The inert empty shell — the universal degrade target (kept in sync with
    /// {@link Sirentide}'s package-private constant, which this test cannot see across packages).
    private static final String INERT_SHELL =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"></svg>";

    /// The diagram-type header keywords — EVERY type the parser accepts (kept exhaustive by the
    /// {@link #everyParserTypeIsCoveredByTheFuzzCorpus} census, which fails if a new
    /// {@link com.sirentide.ir.Diagram} permits-type is added without a fuzz seed here).
    private static final String[] TYPES = {
        "pie", "xychart", "timeline", "gantt", "flowchart TD", "sequence", "state", "quadrant",
        "classDiagram", "erDiagram", "mathblock", "gitGraph", "journey", "mindmap", "sankey", "matrix",
        "snake", "tensornetwork", "young", "dynkin", "knot"
    };

    /// One representative, well-formed body per type — the fuzz SEEDS. Prefix-truncation, mutation,
    /// and (where a `%s` placeholder is present) injection all derive from these.
    private static final String[] SEEDS = {
        "pie\n  \"Reviews\" : 40\n  \"Builds\" : 30\n  \"Docs\" : 30\n",
        "pie legend color=#334155\n  \"A\" : 60 #22c55e\n  \"B\" : 40\n",
        "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n",
        "xychart line legend\n  series: Revenue, Cost\n  \"Q1\" : 5 -3\n  \"Q2\" : 8\n",
        "timeline\n  \"Founded\" : 2020\n  \"Series A\" : 2021\n  \"Launch\" : 2023\n",
        "gantt\n  \"Design\" : 0-3\n  \"Build\" : 3-8\n  \"Test\" : 7-10\n",
        "flowchart TD\n  A[Start] --> B{Ready?}\n  B -->|yes| C[Build] --> D[Ship]\n  B -->|no| E[Fix] --> A\n",
        "flowchart LR\n  A --> B\n  subgraph grp [Group]\n    B --> C\n    C --> D\n  end\n  D --> E\n",
        "sequence\n  Alice ->> Bob : hello\n  alt is available\n    Bob -->> Alice : yes\n  else is busy\n    Bob -->> Alice : later\n  end\n",
        "sequence\n  Alice ->> Bob : hi\n  note over Alice,Bob : shared\n  create participant Carol\n  destroy Carol\n",
        "state\n  [*] --> Idle\n  Idle --> Running : start\n  Running --> Idle : stop\n  Running --> [*]\n",
        "quadrant\n  x-axis \"Low\" --> \"High\"\n  quadrant-1 \"A\"\n  \"Feature A\" : [0.3, 0.6]\n",
        "classDiagram\n  class Animal {\n    +String name\n    +eat() void\n  }\n  Animal <|-- Dog : inherits\n",
        "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  CUSTOMER {\n    string name PK\n  }\n",
        "mathblock\n  \\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n",
        "gitGraph\n  commit\n  commit id: \"fix\"\n  branch develop\n  checkout develop\n  commit\n  checkout main\n  merge develop\n",
        "journey\n  title My day\n  section Go to work\n    Make tea: 5: Me\n    Commute: 3: Me, Cat\n",
        "mindmap\n  root Root idea\n    Origins\n      Long history\n    Research\n    Tools\n",
        "sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n  Electricity,Homes,20\n",
        "matrix\n  cols: snapshot, bare\n  \"ID1 claim-on-no-signal\" : match, match\n"
            + "  \"PC1 soft-intent\" : partial, diverge\n",
        // the four post-2026-07-17 types (snake, tensornetwork, young, dynkin) — added so INV-4
        // actually runs across EVERY parser type (review sir400 finding 1).
        "snake\n  cf: 1, 2, 2, 2\n",
        "tensornetwork\n  mps A B C D\n",
        "young\n  rows: 3, 2, 1\n",
        "dynkin\n  type: B4\n",
        // knot — the twenty-first type (landed after sir400; census kept us honest, review sir403 freshen).
        "knot\n  type: trefoil\n"
    };

    /// Per-type templates with a single `%LBL%` slot into which a hostile label is spliced. Exercises
    /// the label→glyph-path AND the label→a11y-text paths for every type.
    private static final String[] LABEL_TEMPLATES = {
        "pie\n  \"%LBL%\" : 10\n  \"Ok\" : 5\n",
        "xychart\n  \"%LBL%\" : 5\n  \"B\" : 8\n",
        "timeline\n  \"%LBL%\" : 2020\n",
        "gantt\n  \"%LBL%\" : 0-3\n",
        "flowchart TD\n  A[%LBL%] --> B[End]\n",
        "sequence\n  Alice ->> Bob : %LBL%\n",
        "state\n  [*] --> S1 : %LBL%\n",
        "quadrant\n  quadrant-1 \"%LBL%\"\n  \"P\" : [0.3, 0.6]\n",
        "classDiagram\n  class A {\n    +%LBL%\n  }\n",
        "erDiagram\n  A ||--o{ B : %LBL%\n",
        "mathblock\n  %LBL%\n",
        "gitGraph\n  commit id: \"%LBL%\"\n",
        "journey\n  title T\n  section S\n    %LBL%: 3: Me\n",
        "mindmap\n  root %LBL%\n",
        "sankey\n  %LBL%,B,10\n",
        "snake\n  cf: %LBL%\n",
        "tensornetwork\n  mps %LBL% B\n",
        "young\n  rows: %LBL%\n",
        "dynkin\n  type: %LBL%\n",
        "knot\n  type: %LBL%\n",
        // config-block title override — the OTHER a11y-text seam.
        "%% title: %LBL%\npie\n  \"A\" : 10\n"
    };

    // ---- the four invariant checks, run over the whole corpus ---------------------------------

    @Test
    void allFourInvariantsHoldOnTheAdversarialCorpus() throws Exception {
        List<String> corpus = generateCorpus();
        // a reused, hardened XML parser (created once — thousands of parses otherwise dominate runtime).
        DocumentBuilder xml = newHardenedParser();

        long t0 = System.nanoTime();
        int cases = 0;
        for (String dsl : corpus) {
            checkAllInvariants(dsl, xml);
            cases++;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // Non-vacuity: the corpus must actually be large (guards against a generation regression that
        // silently empties it and makes the whole pass trivially "green").
        assertTrue(cases >= 3000, "fuzz corpus must exercise a few thousand cases, ran only " + cases);
        System.out.println("[FuzzInvariantTest] " + cases + " adversarial cases across 21 types in " + ms + " ms");
    }

    /// The single-case invariant harness: INV-1 (no throw), INV-2 (well-formed + in-alphabet),
    /// INV-3 (diagnostics explain empty).
    private void checkAllInvariants(String dsl, DocumentBuilder xml) throws Exception {
        // INV-1: render must never throw on ANY input.
        String svg;
        try {
            svg = Sirentide.render(dsl);
        } catch (Throwable t) {
            fail("INV-1 render threw on " + describe(dsl) + " : " + t);
            return; // unreachable
        }
        // INV-1: renderWithDiagnostics must never throw either.
        RenderResult rr;
        try {
            rr = Sirentide.renderWithDiagnostics(dsl);
        } catch (Throwable t) {
            fail("INV-1 renderWithDiagnostics threw on " + describe(dsl) + " : " + t);
            return; // unreachable
        }

        // INV-2: the emitted SVG is well-formed XML (JDK parser, not a regex) ...
        Document doc;
        try {
            xml.reset();
            doc = xml.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            fail("INV-2 emitted SVG is not well-formed XML for " + describe(dsl) + " : " + t
                + " | svg=" + describe(svg));
            return; // unreachable
        }
        // ... and every element/attribute/value is inside the contract allowlist.
        checkElement(doc.getDocumentElement(), dsl);
        // ... and no LIVE <script> element markup survived. NOTE: a raw `<` only ever appears in
        // valid output as element markup, and a hostile label's `<`/`>` are ALWAYS escaped to
        // `&lt;`/`&gt;` in the a11y text seam — so a raw `<script` substring can appear ONLY if a real
        // <script> element (or malformed markup) leaked, which the XML parse + allowlist walk above
        // would also reject. We do NOT string-match `onload=`/`onerror=`: those carry no metachar, so
        // the escaped-but-inert TEXT of a hostile label (`&lt;svg onload=x&gt;` inside <desc>) legally
        // contains that substring — the injection guarantee is "not a LIVE attribute" (allowlist walk),
        // not "the bytes never appear as safe text".
        assertFalse(svg.toLowerCase(Locale.ROOT).contains("<script"),
            "INV-2 a live <script tag survived for " + describe(dsl));

        // INV-3: the diagnostic twin is consistent + explanatory.
        assertEquals(svg, rr.svg(),
            "INV-3 renderWithDiagnostics().svg() must be byte-identical to render() for " + describe(dsl));
        Diagnostics d = rr.diagnostics();
        assertNotNull(d, "INV-3 diagnostics must be non-null for " + describe(dsl));
        assertNotNull(d.outcome(), "INV-3 outcome must be non-null for " + describe(dsl));
        assertNotNull(d.stage(), "INV-3 stage must be non-null for " + describe(dsl));
        assertTrue(d.message() != null && !d.message().isBlank(),
            "INV-3 message must be non-blank for " + describe(dsl));
        // The explanatory floor: a NON-blank input that degraded to the inert shell must carry a
        // reason (not the OK verdict). Blank/whitespace inputs legitimately render the shell with OK.
        if (dsl != null && !dsl.isBlank() && svg.equals(INERT_SHELL)) {
            assertFalse(d.outcome() == Outcome.OK,
                "INV-3 inert shell on non-blank input must carry a reason (outcome != OK) for "
                    + describe(dsl) + " : outcome=" + d.outcome());
        }

        // INV-4: every emitted geometry element sits inside the declared canvas on BOTH axes.
        // Types on the KNOWN-overflow allowlist are exempt (see INV4_KNOWN_OVERFLOW) — a documented,
        // explicit escape hatch that keeps the invariant green today and biting on everything else.
        List<String> escapes = containmentEscapes(svg);
        if (!escapes.isEmpty() && !INV4_KNOWN_OVERFLOW.contains(diagramType(dsl))) {
            fail("INV-4 geometry escapes the canvas for " + describe(dsl)
                + " [type=" + diagramType(dsl) + "] : " + escapes);
        }
    }

    // ---- deterministic corpus generation -------------------------------------------------------

    /// The corpus is reproducible: same seeds → same cases, byte-for-byte, in the same order.
    @Test
    void corpusIsDeterministic() {
        List<String> a = generateCorpus();
        List<String> b = generateCorpus();
        assertEquals(a, b, "the fuzz corpus must be deterministic (seeded, no Math.random/time)");
        assertTrue(a.size() >= 3000, "corpus size floor");
    }

    /// CENSUS / non-vacuity (review sir400 finding 1): INV-4 claims to run across EVERY diagram type,
    /// so the corpus must actually EXERCISE every type the parser accepts. The authoritative type set is
    /// the {@link com.sirentide.ir.Diagram} sealed `permits` list minus the inert {@code Empty} shell —
    /// read reflectively, so a NEW parser type (a new permits record) makes this FAIL until a fuzz seed
    /// is added here. Guards against the exact gap Lattice found: snake/tensornetwork/young/dynkin
    /// shipped in the parser but were absent from TYPES/SEEDS, so INV-4 silently never ran on them.
    @Test
    void everyParserTypeIsCoveredByTheFuzzCorpus() {
        // The parser's real diagram types = the Diagram permits minus the Empty inert shell.
        long realTypes = Arrays.stream(com.sirentide.ir.Diagram.class.getPermittedSubclasses())
            .filter(c -> !c.getSimpleName().equals("Empty"))
            .count();

        // The distinct keywords this test declares it covers (the head token of each TYPES entry).
        Set<String> declared = new LinkedHashSet<>();
        for (String type : TYPES) {
            declared.add(type.split(" ")[0]);
        }

        // Which of those keywords actually render a NON-inert diagram somewhere in the corpus — the
        // proof INV-4's geometry walk has real geometry to check for that type, not just an inert shell.
        Set<String> coveredNonInert = new HashSet<>();
        for (String dsl : generateCorpus()) {
            String type = diagramType(dsl);
            if (type != null && !coveredNonInert.contains(type)
                    && !Sirentide.render(dsl).equals(INERT_SHELL)) {
                coveredNonInert.add(type);
            }
        }

        // (a) the declared keyword set must exactly match the parser's real-type count — adding a new
        //     Diagram permits record without a fuzz seed here fails HERE.
        assertEquals(realTypes, (long) declared.size(),
            "the fuzz TYPES list (" + declared.size() + " distinct keywords) must cover EVERY Diagram "
            + "permits type (" + realTypes + " minus Empty); a new parser type must add a seed here. "
            + "declared=" + declared);
        // (b) every declared type must actually be exercised NON-inert (INV-4 really runs on it).
        for (String kw : declared) {
            assertTrue(coveredNonInert.contains(kw),
                "the corpus never renders a NON-inert " + kw + " — INV-4 does not actually run on it");
        }
        assertEquals((long) declared.size(), (long) coveredNonInert.size(),
            "every declared type must be exercised non-inert; covered=" + coveredNonInert);
    }

    /// Builds the full adversarial corpus. PURE + deterministic: a `java.util.Random` seeded with
    /// FIXED longs and fixed input seeds — no `Math.random`, no clock. Kept bounded (~a few thousand)
    /// and cheap so it rides the normal `./gradlew test`.
    private static List<String> generateCorpus() {
        List<String> out = new ArrayList<>(6000);

        // (0) explicit edge cases: null, blank, whitespace, only-delimiters, mismatched delimiters.
        out.add(null);
        out.add("");
        out.add("   ");
        out.add("\n\n\n");
        out.add("\t\t");
        out.add("{}[]()<>\"':,");
        out.add("[[[[((((");
        out.add("]]]]))))");
        out.add("::::");
        out.add("-->-->-->");
        out.add("%%");
        out.add("%% title:");
        out.add("%% theme: dark");

        // (1) each type's header + EVERY truncated prefix of its representative body. Cutting at every
        //     prefix length exercises the parser mid-token / mid-line / mid-delimiter everywhere.
        for (String seed : SEEDS) {
            for (int k = 0; k <= seed.length(); k++) {
                out.add(seed.substring(0, k));
            }
        }
        // bare type headers, plus a header with trailing junk modifiers.
        for (String type : TYPES) {
            out.add(type + "\n");
            out.add(type);
            out.add(type + " legend key color=#zzz nonsense\n");
            out.add(type + " \t \n   \n");
        }

        // (2) injection payloads in every label position (both the glyph-path AND a11y-text seams).
        for (String payload : injectionPayloads()) {
            for (String tmpl : LABEL_TEMPLATES) {
                out.add(tmpl.replace("%LBL%", payload));
            }
        }

        // (3) random ASCII / random bytes / random BMP (incl. control chars) / random full-Unicode
        //     (incl. astral + lone surrogates), each optionally under a random type header.
        randomAscii(out, 900_1L, 500);
        randomBytes(out, 900_2L, 500);
        randomBmp(out, 900_3L, 500);
        randomUnicode(out, 900_4L, 500);
        randomUnderHeader(out, 900_5L, 500);

        // (4) structural abuse: deep nesting + huge (but bounded) counts.
        structuralAbuse(out);

        // (5) mutations of the seeds: bit-flips, char drops, delimiter swaps.
        mutate(out, 900_6L, 40);

        return out;
    }

    /// The injection payloads placed into label positions. Control-char payloads are built with
    /// `(char)` casts (never as source literals) so the test file itself carries no control chars.
    private static List<String> injectionPayloads() {
        List<String> p = new ArrayList<>();
        p.add("<script>alert(1)</script>");
        p.add("\"><svg onload=x>");
        p.add("</svg><script>x</script>");
        p.add("</title><script>x</script>");
        p.add("]]>");
        p.add("<![CDATA[x]]>");
        p.add("&entity;");
        p.add("&amp;");
        p.add("&#0;");
        p.add("${x}");
        p.add("`backtick`");
        p.add("a\"b'c");
        p.add("a < b > c & d");
        p.add("line1\nline2\nline3");
        p.add("tab\there");
        p.add("x".repeat(4000));
        // control / surrogate payloads — the class the fuzz FOUND (built, not literal).
        p.add("nul" + (char) 0x00 + "end");
        p.add("soh" + (char) 0x01 + "end");
        p.add("bell" + (char) 0x07 + "end");
        p.add("vt" + (char) 0x0B + "end");
        p.add("esc" + (char) 0x1B + "end");
        p.add("del" + (char) 0x7F + "end");
        p.add("lonehi" + (char) 0xD800 + "end");
        p.add("lonelo" + (char) 0xDC00 + "end");
        p.add("nonchar" + (char) 0xFFFE + "end");
        p.add("rtl" + (char) 0x202E + "flip");
        p.add("astral😀smile");
        return p;
    }

    private static void randomAscii(List<String> out, long seed, int n) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            int len = r.nextInt(200);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                sb.append((char) (0x20 + r.nextInt(0x5F)));   // printable ASCII
            }
            out.add(sb.toString());
        }
    }

    private static void randomBytes(List<String> out, long seed, int n) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            int len = r.nextInt(200);
            byte[] b = new byte[len];
            r.nextBytes(b);
            out.add(new String(b, StandardCharsets.ISO_8859_1));   // arbitrary byte values 0x00..0xFF
        }
    }

    private static void randomBmp(List<String> out, long seed, int n) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            int len = r.nextInt(150);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                sb.append((char) r.nextInt(0x10000));   // any BMP unit incl. controls + lone surrogates
            }
            out.add(sb.toString());
        }
    }

    private static void randomUnicode(List<String> out, long seed, int n) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            int len = r.nextInt(120);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                int cp = r.nextInt(0x110000);   // full range incl. astral + surrogate gap
                if (Character.isValidCodePoint(cp) && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                    sb.appendCodePoint(cp);
                } else {
                    sb.append((char) (cp & 0xFFFF));   // deliberately emit lone surrogates too
                }
            }
            out.add(sb.toString());
        }
    }

    private static void randomUnderHeader(List<String> out, long seed, int n) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            String type = TYPES[r.nextInt(TYPES.length)];
            int rows = r.nextInt(6);
            StringBuilder sb = new StringBuilder(type).append('\n');
            for (int j = 0; j < rows; j++) {
                int len = r.nextInt(40);
                sb.append("  ");
                for (int k = 0; k < len; k++) {
                    sb.append((char) r.nextInt(0x100));   // latin-1-ish row bodies incl. controls
                }
                sb.append('\n');
            }
            out.add(sb.toString());
        }
    }

    private static void structuralAbuse(List<String> out) {
        // deep nesting of bracket/brace/paren/angle across a flowchart node + a bare blob.
        for (int depth : new int[] {1, 2, 8, 64, 200, 2000}) {
            out.add("flowchart TD\n  A[" + "[".repeat(depth) + "x" + "]".repeat(depth) + "] --> B[E]\n");
            out.add("flowchart TD\n  A{" + "{".repeat(depth) + "x" + "}".repeat(depth) + "} --> B[E]\n");
            out.add("mindmap\n" + "  ".repeat(1) + "root R\n"
                + repeatIndentChain(depth));   // deep indentation → depth cap
            out.add("(".repeat(depth) + ")".repeat(depth));
            out.add("sequence\n" + "  alt x\n".repeat(depth));   // deep block nesting
        }
        // huge (bounded) counts on the cheap linear types — near/under MAX_DATA_ROWS, kept fast.
        for (int count : new int[] {100, 400, 1200}) {
            out.add(buildRows("pie\n", count, i -> "  \"S" + i + "\" : " + (i + 1) + "\n"));
            out.add(buildRows("timeline\n", count, i -> "  \"E" + i + "\" : " + (2000 + i) + "\n"));
            out.add(buildRows("journey\n  section S\n", count, i -> "  T" + i + ": 3: Me\n"));
            out.add(buildRows("sankey\n", count, i -> "  N" + i + ",N" + (i + 1) + "," + (i + 1) + "\n"));
            out.add(buildRows("gantt\n", count, i -> "  \"T" + i + "\" : " + i + "-" + (i + 1) + "\n"));
        }
        // huge (bounded) graph counts on the heavier types — kept small enough to stay fast.
        for (int count : new int[] {50, 150}) {
            out.add(buildRows("flowchart TD\n", count, i -> "  N" + i + " --> N" + (i + 1) + "\n"));
            out.add(buildRows("sequence\n", count, i -> "  A ->> B : m" + i + "\n"));
            out.add(buildRows("classDiagram\n", count, i -> "  class C" + i + "\n"));
            out.add(buildRows("erDiagram\n", count, i -> "  E" + i + " ||--o{ E" + (i + 1) + " : r\n"));
        }
        // only-delimiter / mismatched bodies under each header.
        for (String type : TYPES) {
            out.add(type + "\n  \"\" : \n  : : :\n  ]]>\n  <>&\"'\n");
            out.add(type + "\n" + "  ,,,,\n".repeat(5));
        }
    }

    private static String repeatIndentChain(int depth) {
        StringBuilder sb = new StringBuilder();
        int d = Math.min(depth, 300);   // bound the built string; the parser cap does the rest
        for (int i = 0; i < d; i++) {
            sb.append("  ".repeat(i + 1)).append("n").append(i).append('\n');
        }
        return sb.toString();
    }

    private interface RowFn {
        String row(int i);
    }

    private static String buildRows(String header, int count, RowFn fn) {
        StringBuilder sb = new StringBuilder(header);
        for (int i = 0; i < count; i++) {
            sb.append(fn.row(i));
        }
        return sb.toString();
    }

    private static void mutate(List<String> out, long seed, int mutationsPerSeed) {
        Random r = new Random(seed);
        for (String seedDsl : SEEDS) {
            char[] base = seedDsl.toCharArray();
            for (int m = 0; m < mutationsPerSeed; m++) {
                char[] c = base.clone();
                int kind = r.nextInt(3);
                if (c.length == 0) {
                    continue;
                }
                switch (kind) {
                    case 0 -> {   // bit-flip a char
                        int idx = r.nextInt(c.length);
                        c[idx] = (char) (c[idx] ^ (1 << r.nextInt(16)));
                        out.add(new String(c));
                    }
                    case 1 -> {   // drop a char
                        int idx = r.nextInt(c.length);
                        StringBuilder sb = new StringBuilder(seedDsl);
                        sb.deleteCharAt(idx);
                        out.add(sb.toString());
                    }
                    default -> {  // delimiter swap
                        int idx = r.nextInt(c.length);
                        String delims = "[](){}<>:,-|\">";
                        c[idx] = delims.charAt(r.nextInt(delims.length()));
                        out.add(new String(c));
                    }
                }
            }
        }
    }

    // ---- named minimized regressions for the fuzz-FOUND + FIXED defect --------------------------

    /// FUZZ-FOUND BUG (now FIXED): a NUL (or any XML-1.0-illegal C0 control char) in a label reached
    /// the a11y `<title>`/`<desc>` text sink unescaped, making the emitted SVG non-well-formed XML —
    /// `render` did not throw (INV-1 held) but the output could not be parsed (INV-2 was violated).
    /// The fix drops XML-illegal chars in `SvgEmitter.xmlEscape`; this pins that every C0 control
    /// character in a label now yields well-formed, allowlist-clean output.
    @Test
    void controlCharInLabel_staysWellFormedXml() throws Exception {
        DocumentBuilder xml = newHardenedParser();
        for (int cp = 0; cp <= 0x1F; cp++) {
            String ch = new String(Character.toChars(cp));
            // the minimal reproducer: one control char in a pie slice label.
            String svg = Sirentide.render("pie\n  \"Bad" + ch + "Label\" : 10\n");
            xml.reset();
            xml.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));   // must not throw
            checkElement(parseFresh(svg), "ctrl-" + cp);
        }
        // DEL (0x7F) + the C1 range too.
        for (int cp : new int[] {0x7F, 0x80, 0x9F, 0xFFFE, 0xFFFF}) {
            String ch = new String(Character.toChars(cp));
            String svg = Sirentide.render("flowchart TD\n  A[N" + ch + "] --> B[E]\n");
            xml.reset();
            xml.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /// FUZZ-FOUND companion (now FIXED): an UNPAIRED surrogate in a label likewise must not corrupt
    /// the a11y text seam — the emitter drops it, keeping the SVG well-formed and in-alphabet.
    @Test
    void loneSurrogateInLabel_staysWellFormed() throws Exception {
        for (char sur : new char[] {(char) 0xD800, (char) 0xDBFF, (char) 0xDC00, (char) 0xDFFF}) {
            String svg = Sirentide.render("pie\n  \"L" + sur + "R\" : 10\n  \"Ok\" : 5\n");
            checkElement(parseFresh(svg), "lone-surrogate");
        }
        // a VALID astral pair must survive (it is a legal XML char) — the drop is surgical.
        String svg = Sirentide.render("pie\n  \"emoji😀here\" : 10\n");
        checkElement(parseFresh(svg), "astral-pair");
    }

    /// A markup-metachar / breakout label must be neutralized (escaped or baked to glyph paths),
    /// never surfaced as a live element/attribute — the classic injection floor.
    @Test
    void escapesMarkupInLabel() throws Exception {
        String[] hostile = {
            "<script>alert(1)</script>",
            "</svg><script>x</script>",
            "</title><script>x</script>",
            "\"><svg onload=x>",
            "]]>",
            "a & b < c > d \" e"
        };
        for (String h : hostile) {
            for (String tmpl : LABEL_TEMPLATES) {
                String svg = Sirentide.render(tmpl.replace("%LBL%", h));
                // well-formed XML + the allowlist walk is the injection floor: a live <script>/onload
                // attribute would be a foreign element or a non-allowlisted attribute (both rejected).
                checkElement(parseFresh(svg), "hostile:" + h);
                assertFalse(svg.toLowerCase(Locale.ROOT).contains("<script"),
                    "no live <script> tag for label " + h + " : " + svg);
            }
        }
    }

    /// REVIEW sir400 finding 2 (red-on-old / green-on-new): an SVG arc whose ENDPOINTS sit inside the
    /// canvas but whose elliptical INTERIOR bulges outside must be caught by INV-4. Lattice's Chrome-
    /// confirmed discriminator — `M 10 50 A 200 200 0 1 0 90 50` in a 100×100 box — has both endpoints
    /// at y=50 (inside) yet Chrome's path bbox escapes the root. The OLD pathPoints (endpoint-only for
    /// A) returned NO escape (false-green); {@link #arcExtrema} now reaches the interior extrema.
    @Test
    void arcInteriorEscapeIsCaught() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" "
            + "viewBox=\"0 0 100 100\"><path d=\"M 10 50 A 200 200 0 1 0 90 50\"/></svg>";
        List<String> escapes = containmentEscapes(svg);
        assertFalse(escapes.isEmpty(),
            "INV-4 must catch an arc whose interior bulges outside the canvas though its endpoints are "
            + "inside (both at y=50): " + escapes);

        // The endpoint-only extraction (the retired behaviour) sees only in-canvas points — the proof
        // this discriminator is RED-ON-OLD, not a tautology.
        List<double[]> endpointOnly = List.of(new double[] {10, 50}, new double[] {90, 50});
        for (double[] p : endpointOnly) {
            assertTrue(p[0] >= 0 && p[0] <= 100 && p[1] >= 0 && p[1] <= 100,
                "the arc endpoints are inside the canvas, so endpoint-only checking false-greens");
        }

        // A small, wholly-contained arc must NOT be flagged (the check is not trivially always-true).
        String contained = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" "
            + "viewBox=\"0 0 100 100\"><path d=\"M 40 50 A 10 10 0 0 0 60 50\"/></svg>";
        assertTrue(containmentEscapes(contained).isEmpty(),
            "a small contained arc must not be reported as an escape: " + containmentEscapes(contained));
    }

    /// REVIEW sir430: `state` emits rounded-rect node paths with H (horizontal) / V (vertical) lineto
    /// commands (`M x y H x2 Q … V y2 …`). The retired pathPoints `default -> i+=1` SILENTLY SKIPPED
    /// H/V, so a coordinate escaping via one false-greened INV-4 (Lattice's 100×100 probe escaping to
    /// 200,200 returned no escapes). H/V are now modelled against the tracked current point, and any
    /// UNMODELLED command fails CLOSED (recorded as an escape) so INV-4 can never silently skip again.
    @Test
    void horizontalVerticalAndUnknownPathCommandsAreNotSilentlySkipped() {
        // H escaping right (x=200 in a 100-wide canvas) — the y rides the current point (50, inside).
        String hEsc = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" "
            + "viewBox=\"0 0 100 100\"><path d=\"M 10 50 H 200\"/></svg>";
        assertFalse(containmentEscapes(hEsc).isEmpty(),
            "an H lineto escaping the canvas must be caught (was silently skipped): " + containmentEscapes(hEsc));
        // V escaping down (y=200), x rides the current point (50, inside).
        String vEsc = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" "
            + "viewBox=\"0 0 100 100\"><path d=\"M 50 10 V 200\"/></svg>";
        assertFalse(containmentEscapes(vEsc).isEmpty(),
            "a V lineto escaping the canvas must be caught (was silently skipped): " + containmentEscapes(vEsc));
        // A real state-shaped H/V/Q rounded rect wholly inside must NOT be flagged (not trivially true).
        String contained = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" "
            + "viewBox=\"0 0 100 100\"><path d=\"M 20 20 H 80 Q 84 20 84 24 V 76 Q 84 80 80 80 "
            + "H 20 Q 16 80 16 76 V 24 Q 16 20 20 20 Z\"/></svg>";
        assertTrue(containmentEscapes(contained).isEmpty(),
            "a contained H/V/Q rounded rect must not be flagged: " + containmentEscapes(contained));
        // An UNMODELLED command (C cubic bézier) must FAIL CLOSED, not be silently skipped.
        String unknown = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" "
            + "viewBox=\"0 0 100 100\"><path d=\"M 10 10 C 20 20 30 30 200 200\"/></svg>";
        List<String> unkEsc = containmentEscapes(unknown);
        assertFalse(unkEsc.isEmpty(),
            "an unmodelled path command must fail closed (recorded as an escape), never silently skip");
        assertTrue(unkEsc.stream().anyMatch(s -> s.contains("does not model")),
            "the fail-closed escape must name the unmodelled command: " + unkEsc);

        // review sir434: a RELATIVE (lowercase) command must FAIL CLOSED, not be case-folded to absolute.
        // `M 90 50 h 20` reaches x=110 (escapes a 100-wide canvas); the retired Character.toUpperCase read
        // `h` as absolute `H 20` (x=20, inside) — the false-green. Case-sensitive parse now fails it closed.
        String relative = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" "
            + "viewBox=\"0 0 100 100\"><path d=\"M 90 50 h 20\"/></svg>";
        List<String> relEsc = containmentEscapes(relative);
        assertFalse(relEsc.isEmpty(),
            "a relative (lowercase) command a browser reads as an offset must fail closed, not be read as "
            + "absolute: " + relEsc);
        assertTrue(relEsc.stream().anyMatch(s -> s.contains("does not model")),
            "the relative command must fail closed by name (not silently case-folded): " + relEsc);
    }

    // ---- INV-4 geometry containment (generalizes GeometryEscapeTest to both axes, whole corpus) --

    /// Glyph ink can overhang the advance box by a hair — matched to {@link GeometryEscapeTest#TOL}
    /// so the two containment audits share one tolerance. The real escapes overran by &gt;10px, so
    /// this cannot mask a genuine overflow (the invariant still bites — see the prove-it probe in the
    /// review notes / commit message).
    private static final double CONTAINMENT_TOL = 3.0;

    /// Diagram TYPES that are KNOWN to overflow their declared canvas TODAY. Each entry would be an
    /// explicit, documented admission that INV-4 fails for that type on the current corpus — NOT a way
    /// to make the assertion vacuous — and the list would TIGHTEN as each is fixed (delete its entry).
    ///
    /// EMPTY today: the first-run census of INV-4 over the whole corpus (5525 canvases, ~4.18M path
    /// points checked) found ZERO escapes at {@link #CONTAINMENT_TOL}=3.0 — the recent geometry work
    /// (canvas-relative ellipsize, the flowchart/pie/timeline clamp fixes, tensornetwork + dynkin
    /// grow-to-fit) already contains every corpus case on BOTH axes. INV-4 therefore guards ALL types
    /// with no exemptions; the assertion was proven to still BITE via a forced-wide probe (see commit
    /// message). Add a type here only if a future corpus/type surfaces a genuine, documented overflow.
    private static final Set<String> INV4_KNOWN_OVERFLOW = Set.of();

    // root <svg viewBox="minX minY W H"> — the declared canvas; minX/minY are 0 in practice but we
    // read them so the bound is correct if that ever changes.
    private static final Pattern SVG_VIEWBOX = Pattern.compile(
        "<svg\\b[^>]*\\bviewBox=\"\\s*([0-9.eE+-]+)\\s+([0-9.eE+-]+)\\s+([0-9.eE+-]+)\\s+([0-9.eE+-]+)\\s*\"");
    private static final Pattern PATH_D = Pattern.compile("<path\\b[^>]*\\bd=\"([^\"]*)\"");
    private static final Pattern RECT_TAG = Pattern.compile("<rect\\b[^>]*?/?>");
    private static final Pattern LINE_TAG = Pattern.compile("<line\\b[^>]*?/?>");

    /// Every geometry escape (element + axis + coordinate + violated bound) in the emitted SVG. Empty
    /// list ⇒ fully contained. Walks `<path>` per-command (mirrors {@link GeometryEscapeTest} and
    /// extends it to Y), plus `<rect>` (both corners) and `<line>` (both endpoints), and checks BOTH
    /// the x and y extents of each against the canvas ± {@link #CONTAINMENT_TOL}.
    private static List<String> containmentEscapes(String svg) {
        Matcher vb = SVG_VIEWBOX.matcher(svg);
        if (!vb.find()) {
            return List.of();   // no declared canvas (e.g. the inert shell has no geometry to escape).
        }
        double minX = Double.parseDouble(vb.group(1));
        double minY = Double.parseDouble(vb.group(2));
        double w = Double.parseDouble(vb.group(3));
        double h = Double.parseDouble(vb.group(4));
        double loX = minX - CONTAINMENT_TOL;
        double hiX = minX + w + CONTAINMENT_TOL;
        double loY = minY - CONTAINMENT_TOL;
        double hiY = minY + h + CONTAINMENT_TOL;
        List<String> esc = new ArrayList<>();

        // <path d="..."> — absolute M/L/Q/A/Z, space-separated. Per-command point extraction keeps an
        // arc's radii/flags and every command's paired coordinate correctly assigned to x vs y.
        Matcher pm = PATH_D.matcher(svg);
        while (pm.find()) {
            try {
                for (double[] pt : pathPoints(pm.group(1))) {
                    checkPoint(esc, "path", pt[0], pt[1], loX, hiX, loY, hiY);
                }
            } catch (UnknownPathCommand u) {
                esc.add("path uses command '" + u.cmd + "' that INV-4 does not model — failing closed "
                    + "(add its handling to pathPoints before trusting containment): " + pm.group(1));
            }
        }
        // <rect x y width height> — check both corners (covers the x- and y-extents).
        Matcher rm = RECT_TAG.matcher(svg);
        while (rm.find()) {
            String t = rm.group();
            Double x = attr(t, "x");
            Double y = attr(t, "y");
            Double rw = attr(t, "width");
            Double rh = attr(t, "height");
            if (x != null && y != null && rw != null && rh != null) {
                checkPoint(esc, "rect", x, y, loX, hiX, loY, hiY);
                checkPoint(esc, "rect", x + rw, y + rh, loX, hiX, loY, hiY);
            }
        }
        // <line x1 y1 x2 y2> — check both endpoints.
        Matcher lm = LINE_TAG.matcher(svg);
        while (lm.find()) {
            String t = lm.group();
            Double x1 = attr(t, "x1");
            Double y1 = attr(t, "y1");
            Double x2 = attr(t, "x2");
            Double y2 = attr(t, "y2");
            if (x1 != null && y1 != null && x2 != null && y2 != null) {
                checkPoint(esc, "line", x1, y1, loX, hiX, loY, hiY);
                checkPoint(esc, "line", x2, y2, loX, hiX, loY, hiY);
            }
        }
        return esc;
    }

    private static void checkPoint(List<String> esc, String kind, double x, double y,
            double loX, double hiX, double loY, double hiY) {
        if (x < loX || x > hiX) {
            esc.add(kind + " x=" + fmt(x) + " outside x-canvas [" + fmt(loX) + ", " + fmt(hiX) + "]");
        }
        if (y < loY || y > hiY) {
            esc.add(kind + " y=" + fmt(y) + " outside y-canvas [" + fmt(loY) + ", " + fmt(hiY) + "]");
        }
    }

    /// Per-command containment points from an absolute-only path `d` (M/L/Q/A/Z), tracking the current
    /// point so an arc's TRUE extent is checked. Mirrors {@link GeometryEscapeTest#pathXs} and extends
    /// it to Y and to arc interiors:
    ///   * M/L `x y` → (x,y).
    ///   * Q `cx cy x y` → control + endpoint; the quadratic lies inside the convex hull of those two
    ///     plus the start point, so checking the endpoints + control over-approximates it (conservative).
    ///   * A `rx ry rot large sweep x y` → the endpoint AND the arc's bounding-box extrema (review
    ///     sir400 finding 2). An endpoint inside the canvas does NOT imply the arc is inside: the
    ///     elliptical interior can bulge out (Lattice's `M 10 50 A 200 200 0 1 0 90 50` in a 100×100
    ///     box). {@link #arcExtrema} computes the min/max x and y the curve actually reaches.
    private static List<double[]> pathPoints(String d) {
        List<double[]> pts = new ArrayList<>();
        String[] tok = d.trim().split("\\s+");
        int i = 0;
        double curX = 0;
        double curY = 0;                     // the current point (start of the next command's segment)
        while (i < tok.length) {
            String t = tok[i];
            if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                // CASE-SENSITIVE (review sir434): the emitter is absolute-only (uppercase M/L/Q/A/Z/H/V).
                // A lowercase letter is a RELATIVE command (h/l/v/… take offsets from the current point),
                // which SVG permits but the emitter never emits — Character.toUpperCase would misread
                // `M 90 50 h 20` as absolute (x=20, inside) while a browser reaches x=110 (escaping). So
                // do NOT fold case: an unmodelled lowercase (or any non-uppercase-alphabet) command falls
                // to the default and FAILS CLOSED.
                char cmd = t.charAt(0);
                switch (cmd) {
                    case 'M', 'L' -> {                     // x y
                        double[] p = numAt(tok, i + 1, i + 2);
                        if (p != null) {
                            pts.add(p);
                            curX = p[0];
                            curY = p[1];
                        }
                        i += 3;
                    }
                    case 'Q' -> {                          // cx cy x y  → control + endpoint
                        double[] ctrl = numAt(tok, i + 1, i + 2);
                        double[] end = numAt(tok, i + 3, i + 4);
                        if (ctrl != null) {
                            pts.add(ctrl);
                        }
                        if (end != null) {
                            pts.add(end);
                            curX = end[0];
                            curY = end[1];
                        }
                        i += 5;
                    }
                    case 'A' -> {                          // rx ry rot large sweep x y
                        Double rx = num(tok, i + 1);
                        Double ry = num(tok, i + 2);
                        Double rot = num(tok, i + 3);
                        Double fa = num(tok, i + 4);
                        Double fs = num(tok, i + 5);
                        double[] end = numAt(tok, i + 6, i + 7);
                        if (rx != null && ry != null && rot != null && fa != null && fs != null
                                && end != null) {
                            pts.add(end);
                            // the arc's true bounding-box extrema between the current point and end.
                            pts.addAll(arcExtrema(curX, curY, rx, ry, rot, fa != 0, fs != 0,
                                end[0], end[1]));
                            curX = end[0];
                            curY = end[1];
                        }
                        i += 8;
                    }
                    case 'H' -> {                          // H x  → horizontal lineto (y unchanged)
                        Double x = num(tok, i + 1);
                        if (x != null) {
                            pts.add(new double[] {x, curY});
                            curX = x;
                        }
                        i += 2;
                    }
                    case 'V' -> {                          // V y  → vertical lineto (x unchanged)
                        Double y = num(tok, i + 1);
                        if (y != null) {
                            pts.add(new double[] {curX, y});
                            curY = y;
                        }
                        i += 2;
                    }
                    case 'Z' -> i += 1;
                    // FAIL CLOSED (review sir430): the emitter's alphabet is M/L/Q/A/Z/H/V — `state`
                    // emits H/V rounded-rect edges. A silent skip of an unmodelled command let INV-4
                    // false-green a path escaping via that command. Any command not modelled above
                    // throws, and containmentEscapes records it as an escape — INV-4 can never again
                    // silently skip a coordinate-bearing command.
                    default -> throw new UnknownPathCommand(cmd);
                }
            } else {
                i += 1;   // stray token (shouldn't happen with this emitter) — skip
            }
        }
        return pts;
    }

    /// A path command INV-4 does not model — thrown so {@link #containmentEscapes} fails CLOSED rather
    /// than silently skipping a coordinate-bearing command (review sir430).
    private static final class UnknownPathCommand extends RuntimeException {
        final char cmd;
        UnknownPathCommand(char cmd) {
            super(null, null, false, false);
            this.cmd = cmd;
        }
    }

    /// The bounding-box extrema POINTS of an SVG elliptical-arc command (endpoint parameterization,
    /// SVG 1.1 §F.6.5). Converts (x0,y0)→(x1,y1) with (rx,ry,phi,largeArc,sweep) to centre form, then
    /// evaluates the arc at the (up to four) angles where the ellipse's x or y is extremal AND that
    /// angle lies within the swept range — those interior points, not just the endpoints, are where an
    /// arc escapes its canvas. Degenerate arcs (zero radius, coincident endpoints) reduce to a line and
    /// contribute no interior point. A pure oracle: no browser, deterministic. (review sir400 finding 2)
    private static List<double[]> arcExtrema(double x0, double y0, double rx, double ry, double phiDeg,
            boolean largeArc, boolean sweep, double x1, double y1) {
        List<double[]> out = new ArrayList<>();
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0 || (x0 == x1 && y0 == y1)) {
            return out;   // SVG: degenerate → straight line; endpoints already checked.
        }
        double phi = Math.toRadians(phiDeg % 360.0);
        double cosP = Math.cos(phi);
        double sinP = Math.sin(phi);

        // Step 1: (x0,y0)/(x1,y1) → primed coords.
        double dx = (x0 - x1) / 2.0;
        double dy = (y0 - y1) / 2.0;
        double x0p = cosP * dx + sinP * dy;
        double y0p = -sinP * dx + cosP * dy;

        // Step 2: correct out-of-range radii (SVG F.6.6).
        double lambda = (x0p * x0p) / (rx * rx) + (y0p * y0p) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }

        // Step 3: centre (cx',cy') then (cx,cy).
        double rx2 = rx * rx;
        double ry2 = ry * ry;
        double num = rx2 * ry2 - rx2 * y0p * y0p - ry2 * x0p * x0p;
        double den = rx2 * y0p * y0p + ry2 * x0p * x0p;
        double coef = (largeArc != sweep ? 1.0 : -1.0) * Math.sqrt(Math.max(0.0, num / den));
        double cxp = coef * (rx * y0p / ry);
        double cyp = coef * (-ry * x0p / rx);
        double cx = cosP * cxp - sinP * cyp + (x0 + x1) / 2.0;
        double cy = sinP * cxp + cosP * cyp + (y0 + y1) / 2.0;

        // Step 4: start angle theta1 and sweep delta.
        double ux = (x0p - cxp) / rx;
        double uy = (y0p - cyp) / ry;
        double vx = (-x0p - cxp) / rx;
        double vy = (-y0p - cyp) / ry;
        double theta1 = Math.atan2(uy, ux);
        double delta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && delta > 0) {
            delta -= 2 * Math.PI;
        } else if (sweep && delta < 0) {
            delta += 2 * Math.PI;
        }

        // Step 5: the ellipse's x- and y-extremal angles; add each that lies within the arc.
        double tx = Math.atan2(-ry * sinP, rx * cosP);   // dx/dt = 0
        double ty = Math.atan2(ry * cosP, rx * sinP);    // dy/dt = 0
        for (double base : new double[] {tx, tx + Math.PI, ty, ty + Math.PI}) {
            if (angleInArc(base, theta1, delta)) {
                double ex = cx + rx * Math.cos(base) * cosP - ry * Math.sin(base) * sinP;
                double ey = cy + rx * Math.cos(base) * sinP + ry * Math.sin(base) * cosP;
                out.add(new double[] {ex, ey});
            }
        }
        return out;
    }

    /// Whether angle {@code t} lies on the arc swept from {@code theta1} by {@code delta} (signed).
    private static boolean angleInArc(double t, double theta1, double delta) {
        double rel = (t - theta1) % (2 * Math.PI);
        if (delta >= 0) {
            if (rel < 0) {
                rel += 2 * Math.PI;
            }
            return rel <= delta + 1e-9;
        }
        if (rel > 0) {
            rel -= 2 * Math.PI;
        }
        return rel >= delta - 1e-9;
    }

    /// A single numeric token by index, or null if absent/non-numeric.
    private static Double num(String[] tok, int idx) {
        if (idx >= tok.length) {
            return null;
        }
        try {
            return Double.parseDouble(tok[idx]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// A numeric (x,y) pair from two token indices, or null if either is absent/non-numeric.
    private static double[] numAt(String[] tok, int xi, int yi) {
        Double x = num(tok, xi);
        Double y = num(tok, yi);
        return (x == null || y == null) ? null : new double[] {x, y};
    }

    /// Reads a single numeric attribute by NAME out of an already-isolated element tag (order-
    /// independent), or null if absent/non-numeric.
    private static Double attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([0-9.eE+-]+)\"").matcher(tag);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// The diagram type of a DSL = the keyword of the FIRST non-blank, non-comment line (exactly how
    /// the parser decides type), or null when that line is not a known header (garbage → inert shell,
    /// no geometry). Used only to consult {@link #INV4_KNOWN_OVERFLOW}.
    private static String diagramType(String dsl) {
        if (dsl == null) {
            return null;
        }
        for (String raw : dsl.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("%%")) {
                continue;
            }
            for (String type : TYPES) {
                String kw = type.split(" ")[0];
                if (line.equals(kw) || line.startsWith(kw + " ") || line.startsWith(kw + "\t")) {
                    return kw;
                }
            }
            return null;   // first content line isn't a known header → untyped
        }
        return null;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // ---- shared allowlist walk (mirrors ContainmentTest, over the SirentideContract source) ------

    private void checkElement(Element el, String dsl) {
        String tag = el.getTagName();
        if (!SirentideContract.ALLOWED_ELEMENTS.contains(tag)) {
            fail("INV-2 element <" + tag + "> is outside the allowlist for " + describe(dsl));
        }
        var allowedAttrs = SirentideContract.ALLOWED_ATTRS.get(tag);
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            String value = a.getNodeValue();
            if (!allowedAttrs.contains(name)) {
                fail("INV-2 attribute " + name + "=\"" + value + "\" on <" + tag
                    + "> is outside the allowlist for " + describe(dsl));
            }
            if (!SirentideContract.attributeValueValid(name, value)) {
                fail("INV-2 value " + name + "=\"" + value + "\" on <" + tag
                    + "> violates its constraint for " + describe(dsl));
            }
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                checkElement((Element) k, dsl);
            }
        }
    }

    private static DocumentBuilder newHardenedParser() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);   // xmlns surfaces as a regular attribute we allow explicitly
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static Element parseFresh(String svg) throws Exception {
        return newHardenedParser().parse(
            new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    /// A safe, control-char-free rendering of an input for failure messages: length + a
    /// backslash-escaped preview (so the assertion text itself never carries a raw control char).
    private static String describe(String dsl) {
        if (dsl == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder("len=").append(dsl.length()).append(" \"");
        int limit = Math.min(dsl.length(), 80);
        for (int i = 0; i < limit; i++) {
            char c = dsl.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        if (dsl.length() > limit) {
            sb.append("…");
        }
        return sb.append('"').toString();
    }
}
