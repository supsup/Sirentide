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
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/// FUZZ / PROPERTY-INVARIANT pass over ALL 15 diagram types (cycle-2 roadmap #4 — the trust floor).
///
/// "malformed → inert, never throws, never escapes the alphabet" was, before this pass, an
/// ASSUMPTION backed by happy-path goldens + a curated containment corpus. This test turns it into a
/// FUZZED contract: it generates a deterministic, adversarial corpus (a few thousand cases — type
/// headers + every truncated prefix, random ASCII/bytes/Unicode incl. control chars/surrogates/NUL,
/// injection payloads in every label position, structural abuse, and bit-flip/char-drop/delimiter-
/// swap mutations of the golden seeds) and asserts three UNIVERSAL invariants on each case:
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

    /// The 15 diagram-type header keywords.
    private static final String[] TYPES = {
        "pie", "xychart", "timeline", "gantt", "flowchart TD", "sequence", "state", "quadrant",
        "classDiagram", "erDiagram", "mathblock", "gitGraph", "journey", "mindmap", "sankey", "matrix"
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
            + "  \"PC1 soft-intent\" : partial, diverge\n"
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
        // config-block title override — the OTHER a11y-text seam.
        "%% title: %LBL%\npie\n  \"A\" : 10\n"
    };

    // ---- the three invariant checks, run over the whole corpus ---------------------------------

    @Test
    void allThreeInvariantsHoldOnTheAdversarialCorpus() throws Exception {
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
        System.out.println("[FuzzInvariantTest] " + cases + " adversarial cases across 15 types in " + ms + " ms");
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
