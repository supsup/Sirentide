package com.sirentide.parse;

import com.sirentide.ir.ClassBox;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ClassRelation;
import com.sirentide.ir.Matrix;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.DiagramConfig;
import com.sirentide.ir.EdgeStyle;
import com.sirentide.ir.Empty;
import com.sirentide.ir.ErAttribute;
import com.sirentide.ir.ErCardinality;
import com.sirentide.ir.ErDiagram;
import com.sirentide.ir.ErEntity;
import com.sirentide.ir.ErRelation;
import com.sirentide.ir.FlowCluster;
import com.sirentide.ir.RelationKind;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.Flowchart;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import com.sirentide.ir.Journey;
import com.sirentide.ir.JourneySection;
import com.sirentide.ir.JourneyTask;
import com.sirentide.ir.MathBlock;
import com.sirentide.ir.Mindmap;
import com.sirentide.ir.MindmapNode;
import com.sirentide.ir.Pie;
import com.sirentide.ir.Point;
import com.sirentide.ir.QuadrantChart;
import com.sirentide.ir.Sankey;
import com.sirentide.ir.SankeyFlow;
import com.sirentide.ir.Divider;
import com.sirentide.ir.SeqBlock;
import com.sirentide.ir.SeqLifecycle;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.SeqNote;
import com.sirentide.ir.Sequence;
import com.sirentide.ir.Slice;
import com.sirentide.ir.StateDiagram;
import com.sirentide.ir.Task;
import com.sirentide.ir.Theme;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import com.sirentide.contract.SirentideContract;
import com.sirentide.layout.AxisScale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The Sirentide DSL parser (its own language, not a mermaid subset — docs/DESIGN.md §8). M0
/// recognizes the empty diagram and `pie`. Malformed input degrades to the empty diagram or
/// skips the bad row — it never throws (§6: never fail the bake).
///
/// Pie form:
/// ```
/// pie
///   "Reviews" : 40
///   "Builds"  : 30
/// ```
public final class DslParser {

    private DslParser() {}

    /// Input-trust caps (DESIGN §6/§7: malformed/oversized → inert, never throw, never OOM). Past
    /// a cap the parser degrades to an inert/empty diagram rather than allocating unboundedly.
    /// `MAX_SOURCE_BYTES` is also the read bound the CLI enforces on stdin.
    public static final int MAX_SOURCE_BYTES = 1_000_000;   // 1 MB of DSL source
    public static final int MAX_DATA_ROWS = 10_000;         // rows past this are dropped
    public static final int MAX_LABEL_LEN = 512;            // labels are truncated to this
    // Matrix column cap (robustness plan fe8c5bbc #4): rows are bounded by MAX_DATA_ROWS but the
    // `cols:` header token count — and each row's cell count — were uncapped, so `cols: a,a,…×500k`
    // (or a 500k-cell row) forced a cols×rows grid that OOMs in layout before the emit cap can fire.
    // A matrix wider than this is unreadable anyway; extra columns/cells past it are dropped.
    public static final int MAX_COLUMNS = 200;
    // XyChart series cap (robustness plan fe8c5bbc #5): rows are bounded by MAX_DATA_ROWS but the
    // per-row numeric value-token count (each token = one SERIES) and the `series:` name row were
    // uncapped, so `x: 1 1 …×500k` grew maxSeries to 500k → a seriesCount×KEY_ROW_HEIGHT legend +
    // that many bars/points per row. A chart with more series than this is unreadable; extra series
    // per row and extra names are dropped, never laid out.
    public static final int MAX_SERIES = 100;
    // Flowchart graph caps (DESIGN §6/§7): past these, extra nodes/edges are dropped rather than
    // laid out — bounds the layering work + the shape count on a pathological graph, never throws.
    public static final int MAX_NODES = 500;
    public static final int MAX_EDGES = 1000;
    // Sequence-diagram cap (DESIGN §6/§7): a pathological actor count would blow up the lifeline
    // grid; extra first-seen actors past this are dropped (their messages then skip in layout).
    public static final int MAX_ACTORS = 50;
    // Sequence block-nesting cap (M2): a pathological `alt`/`loop`/`par` nesting depth would stack
    // unboundedly; opens past this are swallowed (their `end` hits an empty/other stack, inert).
    public static final int MAX_BLOCK_DEPTH = 64;
    // Mindmap depth cap (DESIGN §6/§7): a pathological indentation chain would nest the tree — and the
    // recursive layout/a11y traversal — unboundedly; a node deeper than this is dropped (its subtree
    // then re-parents to the last valid ancestor). Bounds the recursion depth, never throws/OOMs.
    public static final int MAX_MINDMAP_DEPTH = 64;
    // Mindmap tab-indent width: a leading tab counts as this many indent columns (spaces count 1
    // each). A fixed, documented width keeps depth deterministic when tabs + spaces mix.
    public static final int MINDMAP_TAB_WIDTH = 4;

    public static Diagram parse(String src) {
        if (src == null || src.isBlank()) {
            return new Empty();
        }
        // Oversized source degrades to inert (never parse a runaway input into millions of shapes).
        // MAX_SOURCE_BYTES is a UTF-8 *byte* bound (DESIGN §6/§7 + the CLI stdin read cap). The cheap
        // `length()` guard is a fast reject on UTF-16 code units (always ≤ the UTF-8 byte count for
        // BMP, but multi-byte chars mean length() UNDER-counts bytes — a 600k-char `é` string is
        // 1.2 MB of UTF-8 yet only 600k code units). So ALSO scan the true UTF-8 byte length with a
        // no-alloc code-point walk (never materializes a byte[]) and reject once it exceeds the cap.
        if (src.length() > MAX_SOURCE_BYTES) {
            return new Empty();
        }
        long utf8Bytes = 0;
        for (int i = 0, n = src.length(); i < n; ) {
            int cp = src.codePointAt(i);
            utf8Bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (utf8Bytes > MAX_SOURCE_BYTES) {
                return new Empty();
            }
            i += Character.charCount(cp);
        }
        String[] rawLines = src.strip().split("\\R");
        // Strip the OPTIONAL leading CONFIG BLOCK (an optional `sirentide` marker + `%% key: value`
        // directive lines) so the body parse below is unchanged. With no config block the slice is a
        // no-op (bodyStart == 0 ⇒ lines == rawLines) so a no-config bake is BYTE-IDENTICAL (option A).
        // The directives themselves are read by the sibling {@link #parseConfig} (title/theme/direction).
        int bodyStart = preambleEnd(rawLines);
        if (bodyStart >= rawLines.length) {
            return new Empty();   // a config block with no diagram body → inert (never throws)
        }
        String[] lines = bodyStart == 0
            ? rawLines
            : java.util.Arrays.copyOfRange(rawLines, bodyStart, rawLines.length);
        // The header is a TYPE token plus optional whitespace-split MODIFIER tokens (e.g.
        // `pie legend`). Bare `pie`/`xychart`/… stay exactly as before (a lone type token, no
        // modifiers). Unknown/malformed modifiers are simply ignored — the diagram still bakes
        // (DESIGN §6: never fail the bake).
        String[] header = lines[0].strip().split("\\s+");
        String type = header[0];
        // The off-slice text colour (page-background labels). `color=<value>` overrides the default;
        // an unparseable/illegal colour falls back to the default (never fails the bake).
        String textColor = parseTextColor(header);
        return switch (type) {
            // pie/xychart values are magnitudes → plain numeric parse. `pie legend` (or the `pie
            // key` alias) opts into the left-side colour key; a bare `pie` is legend-off.
            case "pie" -> new Pie(parseData(lines, false), hasLegendModifier(header), textColor);
            case "xychart" -> parseXyChart(lines, header, textColor);
            // timeline values are moments → date-aware parse (bare years stay numeric, ISO dates
            // map to epoch-day) so events place proportionally in time, not evenly by index.
            case "timeline" -> new Timeline(parseData(lines, true), textColor);
            case "gantt" -> parseGantt(lines, textColor);
            case "flowchart" -> parseFlowchart(lines, header, textColor);
            case "sequence" -> parseSequence(lines, header, textColor);
            // A mermaid-style state diagram — reuses the flowchart graph engine (§5); `statediagram`
            // is an accepted alias of `state`.
            case "state", "statediagram" -> parseStateDiagram(lines, header, textColor);
            // A 2×2 positioning matrix — axis-end labels, per-quadrant labels, and `[x,y]` points.
            case "quadrant" -> parseQuadrant(lines, textColor);
            // A categorical comparison / verdict matrix — `cols:` headers + `"row" : v1, v2, …`
            // fixed-vocabulary verdict cells (plan sirentide-comparison-matrix-type). `comparison`
            // is an accepted alias.
            case "matrix", "comparison" -> parseMatrix(lines, textColor);
            // A mermaid-style UML class diagram — `class X { members }` blocks + typed relationships.
            case "classDiagram" -> parseClassDiagram(lines, textColor);
            // A mermaid-style entity-relationship diagram — `ENTITY { rows }` tables + crow-foot
            // cardinality relationships (`A ||--o{ B : label`).
            case "erDiagram" -> parseErDiagram(lines, textColor);
            // A standalone full-size display-math block: the WHOLE body is ONE LaTeX expression
            // (no `$` delimiters), baked centered through the MathFragment seam.
            case "mathblock" -> parseMathBlock(lines);
            // A mermaid-style git commit graph — commit dots on a time axis, one lane per branch, with
            // branch/merge connectors. `gitgraph` is an accepted lowercase alias of `gitGraph`.
            case "gitGraph", "gitgraph" -> parseGitGraph(lines, textColor);
            // A mermaid-style user-journey satisfaction map — an optional `title`, `section` groups,
            // and `<task>: <score 1-5>: <actor>[, …]` rows; tasks plot along x, score on a 1..5 y-axis.
            case "journey" -> parseJourney(lines, textColor);
            // A mermaid-style mindmap — an INDENTATION-defined hierarchy tree. The first (shallowest)
            // body line is the root; each deeper line is a child of the nearest shallower line.
            case "mindmap" -> parseMindmap(lines, textColor);
            // A mermaid-style weighted-flow diagram — CSV-ish `source,target,value` rows; a flow draws
            // as a band whose width tracks its value. `sankey-beta` is mermaid's spelling; `sankey` is
            // the short alias.
            case "sankey", "sankey-beta" -> parseSankey(lines, textColor);
            default -> new Empty();
        };
    }

    /// The optional leading marker line that names the DSL (accepted + ignored, mermaid-`sirentide`
    /// style). It never carries data — it is a human affordance at the top of a config block.
    private static final String CONFIG_MARKER = "sirentide";

    /// The leading token of a config DIRECTIVE line: `%% key: value` (mermaid's comment prefix, reused
    /// as the config channel). A line in the preamble that starts with this + has a `key: value` body
    /// is a directive; anything else in the preamble (a blank line, the marker) is skipped.
    private static final String CONFIG_DIRECTIVE = "%%";

    /// The index where the diagram BODY starts — i.e. the count of leading preamble lines (blank lines,
    /// the optional `sirentide` marker, and `%% …` config directives). Scans from the top and stops at
    /// the FIRST line that is none of those — the diagram's type header. With no preamble this returns 0
    /// (so the body is the whole source, byte-identical to the pre-config parser). Shared by
    /// {@link #parse} (which slices the preamble off) and {@link #parseConfig} (which reads it). A body
    /// line spelled like a directive is NOT at risk: the scan only consumes preamble UP TO the first
    /// non-preamble (type) line, so a `%%` inside the body (after the type line) is left to the type
    /// parser exactly as before.
    private static int preambleEnd(String[] lines) {
        int i = 0;
        while (i < lines.length) {
            String s = lines[i].strip();
            if (s.isEmpty() || s.equals(CONFIG_MARKER) || s.startsWith(CONFIG_DIRECTIVE)) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /// Parses the leading CONFIG BLOCK into a {@link DiagramConfig} — the type-agnostic directive
    /// header (`%% title:` / `%% theme:` / `%% direction:`) that precedes the diagram body, for ANY
    /// type. Read INDEPENDENTLY of {@link #parse} (which strips the same preamble) so the render entry
    /// can thread title/theme/direction into a11y + emit while the body parse stays untouched.
    ///
    /// Robustness (DESIGN §6): a null/blank/oversized source, an unknown key, an unrecognized value, or
    /// a malformed directive (no colon) ALL degrade to the relevant default — this NEVER throws + never
    /// fails the bake. No config block at all → {@link DiagramConfig#DEFAULT}. `%%{init:{…}}%%`
    /// (mermaid's own init syntax) parses to key `{init…` which is unknown → ignored (inert), so a
    /// mermaid-style block is harmless rather than an error.
    public static DiagramConfig parseConfig(String src) {
        if (src == null || src.isBlank() || src.length() > MAX_SOURCE_BYTES) {
            return DiagramConfig.DEFAULT;
        }
        String[] lines = src.strip().split("\\R");
        int bodyStart = preambleEnd(lines);
        String title = null;
        Theme theme = Theme.DEFAULT;
        String direction = null;
        String caption = null;
        for (int i = 0; i < bodyStart; i++) {
            String s = lines[i].strip();
            if (!s.startsWith(CONFIG_DIRECTIVE)) {
                continue;   // a blank line or the `sirentide` marker — not a directive
            }
            String rest = s.substring(CONFIG_DIRECTIVE.length()).strip();
            int colon = rest.indexOf(':');
            if (colon < 0) {
                continue;   // a `%%` with no `key: value` → malformed, ignored (never throws)
            }
            String key = rest.substring(0, colon).strip().toLowerCase(java.util.Locale.ROOT);
            String value = rest.substring(colon + 1).strip();
            switch (key) {
                case "title" -> {
                    if (!value.isEmpty()) {
                        title = cap(value);   // capped like every label (bounded, never blows up output)
                    }
                }
                case "theme" -> theme = Theme.fromToken(value);   // unknown value → DEFAULT (inert)
                case "caption", "note" -> {
                    // A visible annotation rendered below the diagram (plan
                    // sirentide-caption-note-directive). `note` is an alias. Capped like a label.
                    if (!value.isEmpty()) {
                        caption = cap(value);
                    }
                }
                case "direction" -> {
                    String d = value.toUpperCase(java.util.Locale.ROOT);
                    if (d.equals("TD") || d.equals("LR")) {
                        direction = d;
                    }
                    // An unknown direction stays null (ignored) rather than failing the bake.
                }
                default -> {
                    // An unknown key is IGNORED (inert) — a typo/forward key never fails the bake.
                }
            }
        }
        return new DiagramConfig(title, theme, direction, caption);
    }

    /// The off-slice text fill from an optional `color=<value>` header modifier. The value must
    /// match the SirentideContract COLOR grammar (`#rrggbb` | `currentColor` | `none`); an invalid
    /// or unparseable colour degrades to the default `currentColor` rather than failing the bake
    /// (DESIGN §6). Order-independent with `legend`/`key` — it is just another header token.
    private static String parseTextColor(String[] header) {
        for (int i = 1; i < header.length; i++) {
            String tok = header[i];
            if (tok.startsWith("color=")) {
                String value = tok.substring("color=".length());
                if (SirentideContract.isColor(value)) {
                    // Normalize so a 3-digit `color=#333` reaches the emitter as `#333333`.
                    return SirentideContract.normalizeColor(value);
                }
                // Illegal colour → stop looking and use the default (a typo never fails the bake).
                return "currentColor";
            }
        }
        return "currentColor";
    }

    /// The default node/head box fill from an optional `nodecolor=<hex>` header modifier (flowchart
    /// nodes, state boxes, sequence actor heads). HEX-ONLY (like a per-item fill: a box needs a
    /// concrete swatch — `currentColor`/`none` are meaningless and are the H1 contrast footgun), so a
    /// non-hex value degrades to `null` = the layout's built-in default (never fails the bake, DESIGN
    /// §6). Normalized to canonical `#rrggbb`. Order-independent with the other header tokens.
    private static String parseNodeColor(String[] header) {
        for (int i = 1; i < header.length; i++) {
            String tok = header[i];
            if (tok.startsWith("nodecolor=")) {
                String value = tok.substring("nodecolor=".length());
                if (SirentideContract.isHexColor(value)) {
                    return SirentideContract.normalizeColor(value);
                }
                return null;   // invalid → the built-in default fill (a typo never fails the bake)
            }
        }
        return null;
    }

    /// True iff a pie header carries the `legend` modifier (alias `key`). Any other modifier token
    /// is ignored (degrade gracefully → plain pie), so a typo never fails the bake.
    private static boolean hasLegendModifier(String[] header) {
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals("legend") || header[i].equals("key")) {
                return true;
            }
        }
        return false;
    }

    /// The xychart render mode from an optional header modifier: `line` or `scatter`; anything else
    /// (or absent) is the default `bars`. Order-independent with `legend`/`key`/`color=` — just
    /// another header token, so a typo degrades to bars rather than failing the bake (DESIGN §6).
    private static String parseXyMode(String[] header) {
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals("line")) {
                return "line";
            }
            if (header[i].equals("scatter")) {
                return "scatter";
            }
        }
        return "bars";
    }

    /// Parses an xychart: single-series bars (the default, byte-identical to before) OR a
    /// multi-series line/scatter/grouped-bars chart.
    ///
    /// A category row is `"label" : v1 [v2 v3 …] [#hex]`. Whitespace-split numeric tokens after the
    /// colon are the per-series values (series count = the MAX row's length); a shorter row means the
    /// trailing series have NO point at that category (a gap, skipped — never zeroed). A trailing
    /// `#hex` is honoured as the per-item bar colour ONLY on a single-value (single-series) row —
    /// with more than one value there are no per-item colours (series colours come from the palette
    /// by series index). An optional FIRST body line `series: A, B, C` names the legend series
    /// (comma-split, `cap()`'d); otherwise the legend labels default to `Series 1..N`.
    ///
    /// BYTE-COMPAT: a single-series `bars` chart routes through the LEGACY {@link XyChart} shape
    /// (a `Slice` list, `series == null`) so its layout/emit is unchanged.
    private static Diagram parseXyChart(String[] lines, String[] header, String textColor) {
        String mode = parseXyMode(header);
        boolean legend = hasLegendModifier(header);

        List<String> seriesNames = null;
        List<String> labels = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        List<String> singleColors = new ArrayList<>();   // per-row single-series colour (else null)
        int maxSeries = 0;

        for (int i = 1; i < lines.length && rows.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String rest = line.substring(colon + 1).strip();
            // An optional `series: A, B, C` naming row — only when it is the FIRST body row seen.
            if (seriesNames == null && rows.isEmpty() && key.equals("series")) {
                seriesNames = new ArrayList<>();
                for (String name : rest.split(",")) {
                    if (seriesNames.size() >= MAX_SERIES) {
                        break;   // robustness fe8c5bbc #5: bound the series-name row
                    }
                    String s = cap(name.strip());
                    if (!s.isEmpty()) {
                        seriesNames.add(s);
                    }
                }
                continue;
            }
            String label = cap(unquote(key));
            // Walk the whitespace-split value tokens: leading NUMERIC tokens are the series values;
            // the FIRST non-numeric token stops the scan, and if it is the LAST token and a hex
            // colour it becomes the (single-series-only) per-item fill.
            String[] toks = rest.split("\\s+");
            List<Double> vals = new ArrayList<>();
            String color = null;
            for (int t = 0; t < toks.length && vals.size() < MAX_SERIES; t++) {
                if (toks[t].isEmpty()) {
                    continue;
                }
                double v;
                try {
                    v = Double.parseDouble(toks[t]);
                } catch (NumberFormatException e) {
                    // A trailing hex token → candidate per-item colour (kept only if single-series
                    // below); any other non-numeric token just ends the value scan (never throws).
                    if (t == toks.length - 1 && SirentideContract.isHexColor(toks[t])) {
                        color = SirentideContract.normalizeColor(toks[t]);
                    }
                    break;
                }
                if (!Double.isFinite(v)) {
                    break;   // NaN/Infinity (incl. "1e400") ends the scan — never reaches layout
                }
                vals.add(v);
            }
            if (vals.isEmpty()) {
                continue;   // no numeric value → malformed row, skip (never fail the bake)
            }
            double[] arr = new double[vals.size()];
            for (int k = 0; k < arr.length; k++) {
                arr[k] = vals.get(k);
            }
            labels.add(label);
            rows.add(arr);
            singleColors.add(vals.size() == 1 ? color : null);
            maxSeries = Math.max(maxSeries, arr.length);
        }

        // Single-series BARS → the legacy Slice-list shape (byte-identical output). A single-series
        // line/scatter chart still routes through the multi path (it is a NEW render mode, not the
        // pinned bar golden).
        if (mode.equals("bars") && maxSeries <= 1) {
            List<Slice> bars = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                bars.add(new Slice(labels.get(i), rows.get(i)[0], singleColors.get(i), null));
            }
            return new XyChart(bars, textColor);
        }

        // Multi-series (or line/scatter): `bars` carries only the category LABELS (its value slot
        // holds series 0 for convenience, unused by the multi layout); `series` carries the grid.
        List<Slice> bars = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            bars.add(new Slice(labels.get(i), rows.get(i)[0], null, null));
        }
        return new XyChart(bars, rows, seriesNames, mode, legend, textColor);
    }

    /// Parses gantt rows: `"Task" : start-end` (two numbers on a shared time axis). A malformed
    /// row is skipped (never fails the bake).
    private static Diagram parseGantt(String[] lines, String textColor) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i < lines.length && tasks.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = cap(unquote(line.substring(0, colon).strip()));
            // Peel an OPTIONAL trailing `#hex` colour token off the END first, BEFORE the range
            // split — so `"Design" : 0-3 #4e79a7` gives range=`0-3` (the numeric dash still splits
            // cleanly) and colour=`#4e79a7`, and an ISO `2020-01-01 - 2020-06-01 #abcdef` keeps its
            // ` - ` delimiter intact. A non-colour trailing token is left on the range (palette).
            String[] pc = peelColor(line.substring(colon + 1).strip());
            String range = pc[0];
            String color = pc[1];
            // Split start from end. ISO date ranges use a ` - ` delimiter (so the dashes INSIDE a
            // `2020-01-01` date are not mistaken for the range separator — the old first-dash split
            // broke absolute dates); the bare-numeric `start-end` form still splits on its lone dash.
            String startTok;
            String endTok;
            int sep = range.indexOf(" - ");
            if (sep >= 0) {
                startTok = range.substring(0, sep).strip();
                endTok = range.substring(sep + 3).strip();
            } else {
                int dash = range.indexOf('-', 1);   // skip a leading sign; find the numeric separator
                if (dash <= 0) {
                    continue;
                }
                startTok = range.substring(0, dash).strip();
                endTok = range.substring(dash + 1).strip();
            }
            try {
                double start = AxisScale.parseDomainValue(startTok);
                double end = AxisScale.parseDomainValue(endTok);
                // Non-finite (NaN / Infinity, incl. Double.parseDouble("1e400") == Infinity, which
                // throws NO exception) must not reach layout/emit — skip the row (never fail bake).
                if (!Double.isFinite(start) || !Double.isFinite(end)) {
                    continue;
                }
                tasks.add(new Task(label, start, end, color));
            } catch (NumberFormatException e) {
                // Skip a malformed / unparseable-date row (loud-not-silent: dropped, never misplaced).
            }
        }
        return new Gantt(tasks, textColor);
    }

    /// Parses a flowchart: a directed graph, one edge (or one lone node) per body line.
    /// ```
    /// flowchart TD
    ///   A[Start] --> B[Process]
    ///   B --> C[End]
    /// ```
    /// Header: `flowchart` optionally followed by a `TD` (default) or `LR` direction token. Each
    /// body line is a `-->`-separated CHAIN of endpoints where an endpoint is a bare `id`,
    /// `id[Label]`, or `id{Label}`; a line with no top-level `-->` is a lone node declaration. A
    /// chained `A --> B --> C` expands to edges A→B and B→C (any length); an edge label rides its
    /// OWN hop (`A -->|yes| B -->|no| C` = A-yes→B, B-no→C). A node's label is its FIRST decorated
    /// occurrence (a node that is never bracketed uses its id as its label). Nodes register in
    /// first-seen order.
    ///
    /// The `-->` split is OPERATOR-SCANNED, not a blind `indexOf`: it only splits on a `-->` that is
    /// OUTSIDE any `[...]`, `{...}`, or `|...|` span (see {@link #topLevelEdges}). So `A[a-->b] --> C`
    /// is one edge A→C with A labeled `a-->b` (the bracket-embedded arrow is NOT a separator), and a
    /// label-embedded `|a-->b|` is inert. A malformed line drops WHOLE (loud-not-silent, DESIGN §6),
    /// never half-drawn: an unterminated `A[Start` (no `]`), trailing junk after a closed delimiter
    /// (`A[Start] junk`), a nested/unbalanced bracket (`A[Start --> B[End]`), an empty endpoint, or a
    /// missing closing edge-label pipe all drop the line. Caps: {@link #MAX_NODES}/{@link #MAX_EDGES}
    /// bound the graph. Empty body → a Flowchart with no nodes (still a flowchart, so `flowchart`
    /// round-trips — NOT degraded to Empty).
    private static Diagram parseFlowchart(String[] lines, String[] header, String textColor) {
        String direction = "TD";
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals("LR")) {
                direction = "LR";
            } else if (header[i].equals("TD")) {
                direction = "TD";
            }
        }
        // Insertion-ordered id → label map: preserves first-seen node order and lets the first
        // decorated occurrence win the label (a later bare mention never overwrites it). Shapes
        // ride a parallel map (absent = rect).
        // The header `nodecolor=#hex` default box fill (null → the layout's built-in default). A
        // per-node `#hex` always overrides it; an invalid value degrades to the default (never fails).
        String nodeColor = parseNodeColor(header);
        LinkedHashMap<String, String> nodeLabels = new LinkedHashMap<>();
        Map<String, String> nodeShapes = new java.util.HashMap<>();
        Map<String, String> nodeColors = new java.util.HashMap<>();
        // Semantic color classes (plan sirentide-semantic-color-classes + sirentide-node-edge-styling):
        // `classDef <name> fill:#hex,stroke:#hex,stroke-width:Npx,color:#hex` defines a named box style;
        // `class <id>[,<id>…] <name>` assigns nodes to it. A node's fill resolves per-node #hex FIRST,
        // else its class fill, else the header nodecolor=, else the built-in default; its stroke/
        // stroke-width/text-colour come from the class. EVERY colour goes through the SAME isHexColor
        // gate as a per-node colour and each stroke-width through a bounded-numeric gate, so no
        // unvalidated value ever reaches the emitter (the security-first #rrggbb-only invariant holds).
        Map<String, ClassStyle> classStyles = new java.util.HashMap<>();
        Map<String, String> nodeClass = new java.util.HashMap<>();
        // Per-edge styling (plan sirentide-node-edge-styling): a `linkStyle <index[,index…]> …` maps a
        // drawn-edge index to a stroke override; `linkStyle default …` applies to every edge without an
        // index-specific override. Both are resolved AFTER the whole body is parsed (indices reference
        // authoring order, which linkStyle may precede or follow), then stamped onto the FlowEdges.
        Map<Integer, StrokeStyle> linkStyleByIndex = new java.util.HashMap<>();
        StrokeStyle[] linkStyleDefault = new StrokeStyle[1];   // holder so the parse helper can set it
        List<FlowEdge> edges = new ArrayList<>();
        // subgraph/end CLUSTER tracking (mirrors the sequence alt/loop/par block stack): a stack of
        // OPEN clusters (innermost on top) whose member lists grow as nodes are FIRST SEEN inside
        // them, plus the closed clusters ready for the IR. A node newly registered while clusters are
        // open joins EVERY open cluster (transitive membership → the outer box encloses inner members).
        Deque<OpenCluster> clusterStack = new ArrayDeque<>();
        List<FlowCluster> clusters = new ArrayList<>();
        int clusterCounter = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            // Operator-scan for the top-level edge operators (outside every bracket/brace/pipe span).
            // Each carries its byte offset, its length, and its (style, arrow) — the 6 mermaid forms.
            List<EdgeOp> arrows = topLevelEdges(line);
            if (arrows.isEmpty()) {
                // A leading `subgraph`/`end` keyword on an ARROWLESS line is a CLUSTER directive, not a
                // node (the no-arrow guard keeps a real node spelled `end`/`subgraph` — vanishingly
                // rare — parsing normally). `subgraph <id> [title]` opens; `end` closes the innermost.
                String[] kwRest = splitKeyword(line);
                if (kwRest[0].equals(KW_SUBGRAPH)) {
                    if (clusterStack.size() < MAX_BLOCK_DEPTH) {
                        clusterStack.push(parseSubgraphOpen(kwRest[1], clusterStack.size(), ++clusterCounter));
                    }
                    // Past the nesting cap the open is swallowed; its `end` closes an inert/other
                    // cluster or hits an empty stack (never allocate unboundedly, never throw).
                    continue;
                }
                if (kwRest[0].equals(KW_END)) {
                    if (!clusterStack.isEmpty()) {
                        clusters.add(clusterStack.pop().freeze());
                    }
                    // A stray `end` (nothing open) is inert (malformed→inert, DESIGN §6).
                    continue;
                }
                if (kwRest[0].equals(KW_CLASSDEF)) {
                    parseClassDef(kwRest[1], classStyles);   // `classDef <name> fill:#hex,stroke:…,…`
                    continue;
                }
                if (kwRest[0].equals(KW_CLASS)) {
                    parseClassAssign(kwRest[1], nodeClass);   // `class <id>[,<id>…] <name>`
                    continue;
                }
                if (kwRest[0].equals(KW_LINKSTYLE)) {
                    // `linkStyle <index[,index…]|default> stroke:#hex,stroke-width:Npx`
                    parseLinkStyle(kwRest[1], linkStyleByIndex, linkStyleDefault);
                    continue;
                }
                if (isReservedDirectiveLine(kwRest)) {
                    // A directive-SHAPED line whose keyword is recognized mermaid vocabulary this
                    // parser does not implement (`style`, `click`, `direction`, and the colon-form
                    // `accTitle:`/`accDescr:`) is DROPPED inert — never minted as a lone node
                    // wearing its own directive text as a name (the playground silent-mint finding:
                    // `classDef …` on a pre-classDef parser rendered a phantom node named
                    // `classDef critical fill:#fee2e2…`). A deliberate bare node named `style` (no
                    // rest, no colon) still parses as a node — only the directive SHAPE is reserved.
                    continue;
                }
                // No edge operator at top level → the whole line is a lone node declaration. A
                // bracket-swallowed arrow (`A[Start --> B[End]`) lands here too and drops via the
                // endpoint validator (nested `[` → malformed), NOT as a plausible node.
                String[] nd = parseEndpoint(line);
                if (nd != null && registerNode(nodeLabels, nodeShapes, nodeColors, nd)) {
                    joinOpenClusters(clusterStack, nd[0]);
                }
                continue;
            }
            // Bidirectional-operator guard: a `<` immediately preceding any matched operator
            // (`A <--> B`, `A <-.-> B`, `A <==> B`) is mermaid's unsupported both-ends arrow. The
            // old behavior swallowed the `<` into the head endpoint and minted a phantom node
            // named `A <` (the playground silent-mint finding). Until bidirectional edges are a
            // real feature, the whole line DROPS — loud-not-silent, same convention as a
            // malformed endpoint (never half-drawn, never a phantom).
            if (anyArrowPrecededByLt(line, arrows)) {
                continue;
            }
            // Tokenize the chain: endpoints[0..k] separated by k arrows, each arrow carrying an
            // OPTIONAL leading `|label|` that annotates only its hop. The head endpoint precedes the
            // first arrow; each subsequent segment is `[|label|] endpoint`.
            String[] head = parseEndpoint(line.substring(0, arrows.get(0).pos()));
            if (head == null) {
                continue;   // malformed head endpoint → drop the whole line
            }
            List<String[]> endpoints = new ArrayList<>();
            List<String> hopLabels = new ArrayList<>();
            endpoints.add(head);
            boolean dropped = false;
            for (int k = 0; k < arrows.size(); k++) {
                // The segment starts AFTER this operator (its own length, not a fixed 3 — `-.->` is 4).
                int segStart = arrows.get(k).pos() + arrows.get(k).len();
                int segEnd = (k + 1 < arrows.size()) ? arrows.get(k + 1).pos() : line.length();
                String seg = line.substring(segStart, segEnd).strip();
                String label = null;
                if (seg.startsWith("|")) {
                    int close = seg.indexOf('|', 1);
                    if (close < 0) {
                        dropped = true;   // missing closing pipe → drop the whole line
                        break;
                    }
                    String raw = seg.substring(1, close).strip();
                    label = raw.isEmpty() ? null : cap(raw);
                    seg = seg.substring(close + 1).strip();
                }
                String[] ep = parseEndpoint(seg);
                if (ep == null) {
                    dropped = true;   // any malformed endpoint drops the whole line (never half-drawn)
                    break;
                }
                hopLabels.add(label);
                endpoints.add(ep);
            }
            if (dropped) {
                continue;
            }
            // Register every endpoint (only after the whole chain validated — a partial line never
            // half-registers), then wire one edge per hop with that hop's own label. A newly-seen
            // endpoint joins every open cluster (transitive membership).
            for (String[] ep : endpoints) {
                if (registerNode(nodeLabels, nodeShapes, nodeColors, ep)) {
                    joinOpenClusters(clusterStack, ep[0]);
                }
            }
            for (int k = 0; k < hopLabels.size(); k++) {
                String from = endpoints.get(k)[0];
                String to = endpoints.get(k + 1)[0];
                // Only draw an edge whose BOTH endpoints actually registered (a node past MAX_NODES is
                // dropped, so its edges are too) and while under the edge cap.
                if (edges.size() < MAX_EDGES
                    && nodeLabels.containsKey(from) && nodeLabels.containsKey(to)) {
                    EdgeOp op = arrows.get(k);   // hop k's operator carries its style + arrow
                    edges.add(new FlowEdge(from, to, hopLabels.get(k), op.style(), op.arrow()));
                }
            }
        }
        // Any cluster still open at end-of-input closes here (innermost first) — an unclosed
        // `subgraph` degrades gracefully to a cluster spanning to EOF, never throws (DESIGN §6).
        while (!clusterStack.isEmpty()) {
            clusters.add(clusterStack.pop().freeze());
        }
        // Apply per-edge linkStyle overrides by DRAWN-edge index (authoring order of the edges that
        // actually registered). An index-specific override wins over `default`; an edge matched by
        // neither keeps its built-in colour/width. All values were validated at parse time.
        if (!linkStyleByIndex.isEmpty() || linkStyleDefault[0] != null) {
            for (int ei = 0; ei < edges.size(); ei++) {
                StrokeStyle st = linkStyleByIndex.getOrDefault(ei, linkStyleDefault[0]);
                if (st != null) {
                    edges.set(ei, edges.get(ei).withStroke(st.stroke(), st.width()));
                }
            }
        }
        List<FlowNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> e : nodeLabels.entrySet()) {
            // Fill resolution: per-node #hex wins, else the node's class fill, else null (the layout
            // then falls back to the header nodecolor= / built-in default). Stroke / stroke-width /
            // text colour come ONLY from the assigned class (all null when unclassed → the borderless,
            // contrast-labelled box drawn before). Every value was validated when the classDef parsed.
            String explicit = nodeColors.get(e.getKey());
            String cls = nodeClass.get(e.getKey());
            ClassStyle style = cls != null ? classStyles.get(cls) : null;
            String color = explicit != null ? explicit
                : (style != null ? style.fill() : null);
            String stroke = style != null ? style.stroke() : null;
            Double strokeWidth = style != null ? style.strokeWidth() : null;
            String nodeText = style != null ? style.textColor() : null;
            nodes.add(new FlowNode(e.getKey(), e.getValue(),
                nodeShapes.getOrDefault(e.getKey(), "rect"), color, stroke, strokeWidth, nodeText));
        }
        return new Flowchart(nodes, edges, direction, textColor, nodeColor, clusters);
    }

    /// Recognized-but-UNIMPLEMENTED mermaid flowchart directive keywords (the loud-or-dropped
    /// forward-compat rule, plan 66572bcd slice 1). A two-token arrowless line whose first token
    /// is one of these is a DIRECTIVE the author meant for a richer parser — dropping it inert is
    /// honest ("that decoration didn't apply"); minting it as a node is silent corruption. Grow
    /// this set whenever mainline gains a directive keyword, so an OLDER vendored parser fed the
    /// newer syntax degrades to a missing decoration, never a phantom node.
    private static final Set<String> RESERVED_FLOW_DIRECTIVES = Set.of(
        "style", "click", "direction", "accTitle", "accDescr");

    /// True when an arrowless flowchart line is a recognized-but-unimplemented directive that must
    /// drop inert. Two shapes, because {@code splitKeyword} cuts only on whitespace:
    ///   - SPACE form (`style A fill:…`, `direction LR`): first token is the bare keyword and a
    ///     non-empty rest follows — the two-token directive shape. A bare keyword alone stays a node.
    ///   - COLON form (`accTitle: text`, `accTitle:text`): the keyword is glued to `:` (with or
    ///     without a following space), so the first token carries the colon (`accTitle:` /
    ///     `accTitle:text`). Cut at the colon and match the prefix — this covers Mermaid's canonical
    ///     accessibility directives, which the whitespace-only space-form check missed
    ///     (Lattice 7141 finding 1). A colon inside a NON-reserved first token (`A[label:` , `foo:`)
    ///     is not a directive and falls through to the node parse.
    private static boolean isReservedDirectiveLine(String[] kwRest) {
        String kw0 = kwRest[0];
        int colon = kw0.indexOf(':');
        if (colon >= 0) {
            return RESERVED_FLOW_DIRECTIVES.contains(kw0.substring(0, colon));
        }
        return RESERVED_FLOW_DIRECTIVES.contains(kw0) && !kwRest[1].isEmpty();
    }

    /// True when any matched edge operator is immediately preceded (spaces skipped) by a `<` —
    /// the unsupported mermaid bidirectional form (`<-->`/`<-.->`/`<==>`). The caller drops the
    /// whole line. A `<` inside a bracket/brace/pipe span never reaches here (the ops themselves
    /// are top-level, and the char BEFORE a top-level op is by construction outside any span,
    /// where `]`/`}`/`|` — not `<` — would appear for a delimited endpoint).
    private static boolean anyArrowPrecededByLt(String line, List<EdgeOp> arrows) {
        for (EdgeOp op : arrows) {
            int j = op.pos() - 1;
            // Skip ANY parser-recognized whitespace (space, tab, …) — not just ASCII space, or a
            // `A <\t--> B` tab restores the `A <` phantom (Lattice 7141 finding 2).
            while (j >= 0 && Character.isWhitespace(line.charAt(j))) {
                j--;
            }
            if (j >= 0 && line.charAt(j) == '<') {
                return true;
            }
        }
        return false;
    }

    /// The reserved leading COLOR-CLASS keywords (plan sirentide-semantic-color-classes): `classDef`
    /// defines a named box fill, `class` assigns nodes to one. Like `subgraph`/`end`, an arrowless line
    /// whose first token is one of these is a directive, not a node (a node literally named `class` is
    /// vanishingly rare and consistent with the existing subgraph/end treatment).
    private static final String KW_CLASSDEF = "classDef";
    // KW_CLASS ("class") is defined once for the whole parser (reused here + by the class diagram).
    /// The per-edge styling directive (plan sirentide-node-edge-styling): `linkStyle <index|default> …`.
    private static final String KW_LINKSTYLE = "linkStyle";

    /// A resolved `classDef` box style (plan sirentide-node-edge-styling). Every field is already
    /// PARSE-VALIDATED and canonicalized (colours are HEX-ONLY `#rrggbb` — the shared {@link
    /// SirentideContract#isHexColor} guard; `currentColor`/`none` are NOT admitted by classDef;
    /// `strokeWidth` a bounded finite non-negative number) or
    /// `null` when the classDef omitted / malformed that property — so the IR/emitter never sees an
    /// unvalidated value. `textColor` is the label (`color:`) override.
    private record ClassStyle(String fill, String stroke, Double strokeWidth, String textColor) {}

    /// A resolved stroke override for a `linkStyle` edge (plan sirentide-node-edge-styling): a
    /// parse-validated `stroke` colour and/or a bounded `width`; either may be `null` (that facet keeps
    /// its default). Never both-null (the parser drops an all-empty directive).
    private record StrokeStyle(String stroke, Double width) {}

    /// The upper bound on a parsed `stroke-width` (px). A width beyond this is REJECTED (drops to the
    /// default) rather than clamped — a fail-closed guard so no absurd/hostile magnitude reaches an
    /// attribute. 40px is far past any legible diagram border/edge weight.
    private static final double MAX_STROKE_WIDTH = 40.0;

    /// Parse a `classDef <name> <prop>[,<prop>…]` body into `classStyles[name]`. Reads `fill:`,
    /// `stroke:`, `stroke-width:`, and `color:` (mermaid's node-styling props). Colours route through
    /// the SHARED {@link SirentideContract#isHexColor} guard (hex-only) + {@link
    /// SirentideContract#normalizeColor} (the exact path a per-node fill uses) — a non-conforming value
    /// is DROPPED (that property stays null → the default), never forwarded. `stroke-width` routes
    /// through {@link #parseStrokeWidth} (bounded finite non-negative). A classDef with no valid prop
    /// still registers (all-null style) so an assigned node simply falls through to the defaults.
    /// Malformed never throws (DESIGN §6). LAST occurrence of a prop wins (mermaid).
    private static void parseClassDef(String body, Map<String, ClassStyle> classStyles) {
        String[] kv = splitKeyword(body);   // <name> | <props>
        String name = kv[0].strip();
        if (name.isEmpty() || kv[1].isEmpty()) {
            return;
        }
        String fill = null;
        String stroke = null;
        Double strokeWidth = null;
        String textColor = null;
        // Props are comma- or space-separated `key:value`.
        for (String prop : kv[1].split("[,\\s]+")) {
            int colon = prop.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = prop.substring(0, colon).strip();
            String val = prop.substring(colon + 1).strip();
            if (key.equalsIgnoreCase("fill")) {
                if (SirentideContract.isHexColor(val)) {
                    fill = SirentideContract.normalizeColor(val);
                }
            } else if (key.equalsIgnoreCase("stroke")) {
                if (SirentideContract.isHexColor(val)) {
                    stroke = SirentideContract.normalizeColor(val);
                }
            } else if (key.equalsIgnoreCase("stroke-width")) {
                Double w = parseStrokeWidth(val);
                if (w != null) {
                    strokeWidth = w;
                }
            } else if (key.equalsIgnoreCase("color")) {
                if (SirentideContract.isHexColor(val)) {
                    textColor = SirentideContract.normalizeColor(val);
                }
            }
        }
        classStyles.put(name, new ClassStyle(fill, stroke, strokeWidth, textColor));
    }

    /// Parse a `linkStyle <index[,index…]|default> <prop>[,<prop>…]` directive. The first
    /// whitespace-separated token is the target (`default` or a comma-separated list of non-negative
    /// drawn-edge indices); the rest is the stroke props ({@link #parseStrokeProps}). A directive with
    /// no valid prop is inert. Unparseable indices are skipped; `default` sets the fallback. Malformed
    /// never throws (DESIGN §6).
    private static void parseLinkStyle(String body, Map<Integer, StrokeStyle> byIndex,
                                       StrokeStyle[] defaultHolder) {
        String[] kv = splitKeyword(body);   // <target> | <props>
        String target = kv[0].strip();
        if (target.isEmpty() || kv[1].isEmpty()) {
            return;
        }
        StrokeStyle st = parseStrokeProps(kv[1]);
        if (st == null) {
            return;   // no valid stroke/stroke-width → inert
        }
        if (target.equalsIgnoreCase("default")) {
            defaultHolder[0] = st;
            return;
        }
        for (String tok : target.split(",")) {
            String t = tok.strip();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int idx = Integer.parseInt(t);
                if (idx >= 0) {
                    byIndex.put(idx, st);
                }
            } catch (NumberFormatException ignored) {
                // a non-numeric, non-`default` target is inert
            }
        }
    }

    /// Parse the `stroke:`/`stroke-width:` props shared by `linkStyle` (and reused nowhere else) into a
    /// {@link StrokeStyle}, or `null` when neither validated. `stroke` uses the SHARED hex-only colour
    /// guard + normalize (the exact per-node-fill path); `stroke-width` the bounded numeric guard. A
    /// non-conforming value is DROPPED — it never reaches an attribute. LAST occurrence wins.
    private static StrokeStyle parseStrokeProps(String props) {
        String stroke = null;
        Double width = null;
        for (String prop : props.split("[,\\s]+")) {
            int colon = prop.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = prop.substring(0, colon).strip();
            String val = prop.substring(colon + 1).strip();
            if (key.equalsIgnoreCase("stroke")) {
                if (SirentideContract.isHexColor(val)) {
                    stroke = SirentideContract.normalizeColor(val);
                }
            } else if (key.equalsIgnoreCase("stroke-width")) {
                Double w = parseStrokeWidth(val);
                if (w != null) {
                    width = w;
                }
            }
        }
        return (stroke == null && width == null) ? null : new StrokeStyle(stroke, width);
    }

    /// Parse a `stroke-width` value into a bounded, finite, NON-NEGATIVE number of pixels, or `null`
    /// when it does not conform (the caller then drops the override → the default width). A trailing
    /// `px` unit is tolerated and stripped. The finiteness check REUSES {@link
    /// SirentideContract#isFiniteNumber} (so `NaN`/`Infinity`/`1e400`/junk all fail); a negative or
    /// out-of-range magnitude ({@link #MAX_STROKE_WIDTH}) is rejected. This is the parse-boundary guard
    /// that keeps a raw author string from ever being appended as a `stroke-width` attribute.
    private static Double parseStrokeWidth(String raw) {
        String v = raw.strip();
        if (v.length() >= 2 && (v.endsWith("px") || v.endsWith("PX")
                || v.endsWith("Px") || v.endsWith("pX"))) {
            v = v.substring(0, v.length() - 2).strip();
        }
        if (!SirentideContract.isFiniteNumber(v)) {
            return null;
        }
        double w = Double.parseDouble(v.trim());
        if (w < 0 || w > MAX_STROKE_WIDTH) {
            return null;
        }
        return w;
    }

    /// Parse a `class <id>[,<id>…] <name>` assignment into `nodeClass[id] = name` for each id. The LAST
    /// whitespace-separated token is the class name; everything before it is the comma-separated id
    /// list. An empty id list or missing name is inert. A later assignment for an id overrides an
    /// earlier one (last-wins), mirroring mermaid.
    private static void parseClassAssign(String body, Map<String, String> nodeClass) {
        int lastSp = Math.max(body.lastIndexOf(' '), body.lastIndexOf('\t'));
        if (lastSp < 0) {
            return;   // no `<ids> <name>` split → inert
        }
        String name = body.substring(lastSp + 1).strip();
        String ids = body.substring(0, lastSp).strip();
        if (name.isEmpty() || ids.isEmpty()) {
            return;
        }
        for (String id : ids.split("[,\\s]+")) {
            String trimmed = id.strip();
            if (!trimmed.isEmpty()) {
                nodeClass.put(cap(trimmed), name);
            }
        }
    }

    /// The reserved leading CLUSTER keywords: `subgraph <id> [title]` opens a cluster box, `end`
    /// closes the innermost open one (shared with the sequence block `end`). A line whose first token
    /// is one of these AND which carries no top-level arrow is a cluster directive, never a node.
    private static final String KW_SUBGRAPH = "subgraph";

    /// Parses a `subgraph` directive's tail into an {@link OpenCluster}. Accepted forms (the rest is
    /// everything after the `subgraph` keyword): `id`, `id [Free title]`, `id ["Quoted title"]`, a
    /// bare `"Quoted title"` (id == title), or empty (an anonymous cluster, id `__cluster_N__`). The
    /// FIRST `[` (with a matching `]`) delimits an explicit title; before it is the id. Without a
    /// bracket the whole tail is the (unquoted) title and its first whitespace token is the id.
    /// Title/id are `cap()`'d; a missing title defaults to the id (mermaid semantics). Never throws.
    private static OpenCluster parseSubgraphOpen(String rest, int depth, int counter) {
        String id;
        String title;
        int open = rest.indexOf('[');
        int close = open >= 0 ? rest.indexOf(']', open + 1) : -1;
        if (open >= 0 && close > open) {
            title = cap(unquote(rest.substring(open + 1, close).strip()));
            String idPart = rest.substring(0, open).strip();
            id = idPart.isEmpty() ? (title.isEmpty() ? autoClusterId(counter) : title) : cap(idPart);
        } else if (rest.isEmpty()) {
            id = autoClusterId(counter);
            title = "";
        } else {
            title = cap(unquote(rest));
            int sp = rest.indexOf(' ');
            String idPart = (rest.startsWith("\"") || sp < 0) ? title : cap(rest.substring(0, sp).strip());
            id = idPart.isEmpty() ? autoClusterId(counter) : idPart;
        }
        // A blank title falls back to the id so the frame's top band always says something.
        if (title.isEmpty()) {
            title = id;
        }
        return new OpenCluster(id, title, depth);
    }

    private static String autoClusterId(int counter) {
        return "__cluster_" + counter + "__";
    }

    /// Adds a node id to EVERY currently-open cluster (transitive membership: the node belongs to the
    /// innermost open cluster and all its ancestors, so an outer frame encloses inner members). A
    /// bounded per-cluster member list guards against a pathological node count (DESIGN §6/§7).
    private static void joinOpenClusters(Deque<OpenCluster> stack, String nodeId) {
        for (OpenCluster c : stack) {
            if (c.members.size() < MAX_NODES) {
                c.members.add(nodeId);
            }
        }
    }

    /// A mutable open-cluster accumulator on the parse stack: id/title/depth fixed at open time,
    /// `members` grow as nodes are first seen inside; `freeze` snapshots it to an immutable
    /// {@link FlowCluster}. Parse-internal, never surfaced in the IR.
    private static final class OpenCluster {
        final String id;
        final String title;
        final int depth;
        final List<String> members = new ArrayList<>();

        OpenCluster(String id, String title, int depth) {
            this.id = id;
            this.title = title;
            this.depth = depth;
        }

        FlowCluster freeze() {
            return new FlowCluster(id, title, members, depth);
        }
    }

    /// One recognized top-level edge operator: its byte `pos` in the line, its `len` (3 for every
    /// form but `-.->`, which is 4), and its decoded `style` + `arrow` (has-arrowhead). Parse-internal.
    private record EdgeOp(int pos, int len, EdgeStyle style, boolean arrow) {}

    /// Scans a body line for every top-level edge OPERATOR — one that lies OUTSIDE any `[...]` /
    /// `{...}` / `|...|` span — so brackets, braces, and edge-label pipes never poison the split (the
    /// old blind `indexOf("-->")` minted a phantom node from `A[a-->b]`). A tiny state machine walks
    /// the line: a `[`/`{` opens a bracket span until its matching closer; a `|` TOGGLES a pipe span;
    /// while inside either span the scanner ignores everything but the span terminator. Outside a span,
    /// {@link #matchEdgeOp} tries — LONGEST/most-specific first — to read one of the six mermaid forms
    /// at the cursor; a match records the op and skips its whole length so a `-.->` (4 chars) is never
    /// re-scanned as `-->` + a stray `.` (the classic disambiguation bug). An unterminated span at
    /// end-of-line simply yields no further ops (the endpoint validator then drops the malformed line).
    /// Non-nesting by design — a label may contain an operator but not a nested delimiter.
    private static List<EdgeOp> topLevelEdges(String line) {
        List<EdgeOp> arrows = new ArrayList<>();
        char bracketClose = 0;   // 0 = not in a bracket/brace span, else the awaited ']' or '}'
        boolean inPipe = false;
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (bracketClose != 0) {
                if (c == bracketClose) {
                    bracketClose = 0;
                }
                i++;
            } else if (inPipe) {
                if (c == '|') {
                    inPipe = false;
                }
                i++;
            } else if (c == '[') {
                bracketClose = ']';
                i++;
            } else if (c == '{') {
                bracketClose = '}';
                i++;
            } else if (c == '|') {
                inPipe = true;
                i++;
            } else {
                EdgeOp op = matchEdgeOp(line, i);
                if (op != null) {
                    arrows.add(op);
                    i += op.len();   // skip the WHOLE operator (never re-scan a `-.->` tail as `-->`)
                } else {
                    i++;
                }
            }
        }
        return arrows;
    }

    /// Tries to read a mermaid edge operator at offset `i`, LONGEST/most-specific first, returning its
    /// decoded {@link EdgeOp} or `null` when the cursor is not an operator. The six forms, disambiguated
    /// purely by the char pattern (never by re-scanning a suffix):
    ///   `-.->` (len 4) DOTTED + arrow   ·   `-.-`  (len 3) DOTTED + open
    ///   `-->`  (len 3) SOLID  + arrow   ·   `---`  (len 3) SOLID  + open
    ///   `==>`  (len 3) THICK  + arrow   ·   `===`  (len 3) THICK  + open
    /// A dash cursor branches on the SECOND char: `.` → the dotted family (a 4th char `>` ⇒ `-.->`, else
    /// `-.-`); `-` → the solid family (a 3rd char `>` ⇒ `-->`, `-` ⇒ `---`). An `=` cursor is the thick
    /// family (`==>` vs `===`). A two-char run that is neither (a bare `--`, `-.`, `==`, a single `-`
    /// inside a node id like `A-B`) returns `null` so it stays inert — byte-identical to the old scanner
    /// for `-->`, and for `A-B`/`A--B` which never matched an operator before and still do not.
    private static EdgeOp matchEdgeOp(String line, int i) {
        int n = line.length();
        char c = line.charAt(i);
        char c1 = i + 1 < n ? line.charAt(i + 1) : 0;
        char c2 = i + 2 < n ? line.charAt(i + 2) : 0;
        if (c == '-') {
            if (c1 == '.') {
                // Dotted family: `-.->` (arrow) or `-.-` (open); a bare `-.` with no closing dash inert.
                if (c2 == '-') {
                    char c3 = i + 3 < n ? line.charAt(i + 3) : 0;
                    if (c3 == '>') {
                        return new EdgeOp(i, 4, EdgeStyle.DOTTED, true);    // -.->
                    }
                    return new EdgeOp(i, 3, EdgeStyle.DOTTED, false);       // -.-
                }
                return null;
            }
            if (c1 == '-') {
                // Solid family: `-->` (arrow) or `---` (open link); a bare `--` (`A--B`) inert as before.
                if (c2 == '>') {
                    return new EdgeOp(i, 3, EdgeStyle.SOLID, true);         // -->
                }
                if (c2 == '-') {
                    return new EdgeOp(i, 3, EdgeStyle.SOLID, false);        // ---
                }
                return null;
            }
            return null;   // a lone `-` (a node id like `A-B`) is not an operator
        }
        if (c == '=') {
            // Thick family: `==>` (arrow) or `===` (open); a bare `==` inert.
            if (c1 == '=') {
                if (c2 == '>') {
                    return new EdgeOp(i, 3, EdgeStyle.THICK, true);         // ==>
                }
                if (c2 == '=') {
                    return new EdgeOp(i, 3, EdgeStyle.THICK, false);        // ===
                }
            }
            return null;
        }
        return null;
    }

    /// State-diagram transition scan: the byte offsets of every top-level SOLID `-->` (a state diagram
    /// uses ONLY the plain transition arrow — the flowchart edge-variant operators are not state
    /// syntax). Delegates to {@link #topLevelEdges} and keeps only the `-->` (SOLID + arrow, length 3)
    /// ops, so a `---`/`-.->`/`==>` on a state line stays inert exactly as before this feature (its
    /// position was never a `-->`, so the line degrades to a bare state declaration as it always did).
    private static List<Integer> topLevelArrows(String line) {
        List<Integer> out = new ArrayList<>();
        for (EdgeOp op : topLevelEdges(line)) {
            if (op.style() == EdgeStyle.SOLID && op.arrow()) {
                out.add(op.pos());
            }
        }
        return out;
    }

    /// Parses one flowchart endpoint token into `{id, labelOrNull, shapeOrNull, colorOrNull}`. A bare
    /// `id` returns null label/shape/colour (the id becomes its own label, shape defaults to rect);
    /// `id[Label]` claims a rect box, `id{Label}` a DIAMOND decision node (M1.3) — whichever delimiter
    /// appears first wins. A closed delimiter may be followed by exactly ONE trailing `#hex` COLOUR
    /// token (`A[Start] #22c55e`, normalized to `#rrggbb`) that overrides the node's default box fill;
    /// ANY OTHER trailing junk still DROPS the whole line (the hardening pin stays green — the colour
    /// is the sole recognized trailing token, so a typo can't sneak a phantom node through). Id and
    /// label are `cap()`'d. Returns `null` (→ caller drops the whole line, loud-not-silent) for any
    /// malformed endpoint: an empty token or id, an UNTERMINATED delimiter (`A[Start` with no `]`),
    /// NON-COLOUR trailing junk after a closed delimiter (`A[Start] junk`), a NESTED/unbalanced
    /// delimiter inside the label (`A[Start --> B[End]`), or a BARE id with a trailing `#hex`
    /// (`A #22c55e` — ambiguous with a multi-word id, so it drops rather than guessing).
    ///
    /// MATH-SPAN EXCEPTION (the moat, sirentide/39): `{`/`}`/`[`/`]` INSIDE a well-formed `$…$`
    /// inline-math span in the label are LaTeX structure (`\frac{a}{b}`, `\sqrt{2}`, `x^{2n}`,
    /// `\left[…\right]`), NOT DSL delimiters, so a braced math label (`A[$\frac{a}{b}$]`) is NO LONGER
    /// dropped — it bakes. Two seams enforce this with the exact unescaped-`$` notion {@link LabelRuns}
    /// splits on: the closer scan ({@link #shapeCloser}) skips a closer that falls inside a span, and
    /// the nesting check ({@link #hasDslDelimiterOutsideMath}) tests only the TEXT (non-math) runs.
    /// The malformed→inert invariant holds: an UNTERMINATED `$` is NOT a span (its `$` stays literal),
    /// so a stray dollar in prose (`A[Cost $5]`) still finds its real closer exactly as before, and any
    /// stray delimiter OUTSIDE a span still drops the line. An unterminated node delimiter whose closer
    /// only appears inside a span (`A[$x`) still drops (closer scan returns -1).
    /// Peel a trailing `: label` off a diagram line at the FIRST colon (any
    /// surrounding spacing), returning {@code [preColonHead, label]} where label
    /// is null when absent/empty. Only the first colon delimits, so a colon
    /// INSIDE the label survives; an arrow token in the pre-colon head stays
    /// inert (callers scan the head). THE single colon-peel every label-carrying
    /// diagram type uses — so the no-space-colon regression class (sirentide/33
    /// sequence, /35 state) cannot be reintroduced one type at a time. Label is
    /// stripped but NOT capped (callers cap).
    static String[] peelLabel(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return new String[] {line, null};
        }
        String raw = line.substring(colon + 1).strip();
        return new String[] {line.substring(0, colon), raw.isEmpty() ? null : raw};
    }

    private static String[] parseEndpoint(String tok) {
        tok = tok.strip();
        if (tok.isEmpty()) {
            return null;
        }
        // The FAMILY is the earliest opening delimiter char in the token — `[` (rect/subroutine/
        // cylinder), `{` (diamond/hexagon), or `(` (rounded/circle/stadium) — mirroring the old
        // `[` vs `{` earliest-index tie-break (now widened to `(`). Whichever family opens FIRST wins.
        int openB = tok.indexOf('[');
        int openC = tok.indexOf('{');
        int openP = tok.indexOf('(');
        int open = -1;
        if (openB >= 0) {
            open = openB;
        }
        if (openC >= 0 && (open < 0 || openC < open)) {
            open = openC;
        }
        if (openP >= 0 && (open < 0 || openP < open)) {
            open = openP;
        }
        if (open < 0) {
            // Bare id: no delimiter. A trailing `#hex` on a bare id is ambiguous (a colour, or part of
            // a multi-word id?) → DROP rather than guess. Any other bare token stays a plain id.
            if (trailingHex(tok) != null) {
                return null;
            }
            return new String[] {cap(tok), null, null, null};   // bare id, default colour
        }
        // The SHAPE within the family is decided LONGEST-DELIMITER-FIRST off the char that FOLLOWS the
        // opener: `([`→stadium before `[`→rect, `((`→circle before `(`→rounded, `{{`→hexagon before
        // `{`→diamond, `[[`→subroutine and `[(`→cylinder before `[`→rect. Rect + diamond stay EXACTLY
        // as before (a lone `[`/`{`), so their bake is byte-identical. `closeStr` is the matching closer
        // (its length == the opener's length, 1 or 2), so id/label/trailing slicing is uniform.
        char oc = tok.charAt(open);
        char nx = open + 1 < tok.length() ? tok.charAt(open + 1) : 0;
        String shape;
        String closeStr;
        switch (oc) {
            case '(' -> {
                if (nx == '[') {
                    shape = "stadium";
                    closeStr = "])";
                } else if (nx == '(') {
                    shape = "circle";
                    closeStr = "))";
                } else {
                    shape = "rounded";
                    closeStr = ")";
                }
            }
            case '[' -> {
                if (nx == '(') {
                    shape = "cylinder";
                    closeStr = ")]";
                } else if (nx == '[') {
                    shape = "subroutine";
                    closeStr = "]]";
                } else {
                    shape = "rect";
                    closeStr = "]";
                }
            }
            default -> {   // '{'
                if (nx == '{') {
                    shape = "hexagon";
                    closeStr = "}}";
                } else {
                    shape = "diamond";
                    closeStr = "}";
                }
            }
        }
        int openLen = closeStr.length();   // the opener is the same length as its closer (1 or 2)
        String id = tok.substring(0, open).strip();
        if (id.isEmpty()) {
            return null;
        }
        int close = shapeCloser(tok, open + openLen, closeStr);
        if (close < 0) {
            // Unterminated OR MISMATCHED delimiter (`([x` / `([x}` never yield the `])` closer), or the
            // closer only appears inside a $…$ span → drop the whole line (malformed→inert, DESIGN §6).
            return null;
        }
        String color = null;
        String trailing = tok.substring(close + closeStr.length()).strip();
        if (!trailing.isEmpty()) {
            // AFTER the closer, ONLY a single `#hex` colour token is allowed; anything else drops the
            // whole line (never mint a plausible-but-wrong node from `A[Start] junk`).
            if (SirentideContract.isHexColor(trailing)) {
                color = SirentideContract.normalizeColor(trailing);
            } else {
                return null;   // trailing junk after the closer → drop (keeps the hardening pin green)
            }
        }
        String label = tok.substring(open + openLen, close);
        // A nested/unbalanced DSL delimiter inside the label means the operator-scan swallowed an arrow
        // (or the box is unbalanced) — malformed, drop rather than mint a plausible-but-wrong node.
        // EXCEPTION: braces/brackets INSIDE a well-formed `$…$` math span are LaTeX, not DSL structure,
        // so test only the TEXT runs (everything OUTSIDE math) — the moat feature (sirentide/39).
        if (hasDslDelimiterOutsideMath(label)) {
            return null;
        }
        return new String[] {cap(id), cap(label.strip()), shape, color};
    }

    /// Finds the node-shape closer: the first `closeCh` at or after `from` that is NOT inside a
    /// well-formed `$…$` inline-math span — so a `]`/`}` that is really LaTeX (a `{…}` diamond wrapping
    /// `\frac{a}{b}`, or a `$\left]…$`) does not prematurely end the label. Mirrors {@link LabelRuns}
    /// EXACTLY: `\$` is a literal dollar (never a toggle); an unescaped `$` opens a span the next
    /// unescaped `$` closes and its interior is skipped whole; an UNCLOSED or EMPTY `$$` is NOT math
    /// (the `$` is literal, the scan continues), so a stray dollar in prose (`A[Cost $5]`) finds its
    /// real closer exactly as the old `indexOf(closeCh)` did. With no `$` at all the scan IS
    /// `indexOf(closeCh, from)` (byte-parity). Returns -1 when no such closer exists (→ the caller
    /// drops the line: an unterminated delimiter).
    private static int shapeCloser(String tok, int from, String close) {
        int n = tok.length();
        char c0 = close.charAt(0);
        char c1 = close.length() > 1 ? close.charAt(1) : 0;   // 0 = single-char closer
        int i = from;
        while (i < n) {
            char c = tok.charAt(i);
            if (c == '\\' && i + 1 < n && tok.charAt(i + 1) == '$') {
                i += 2;   // \$ is a literal dollar (LabelRuns rule) — neither a span toggle nor a closer
                continue;
            }
            if (c == '$') {
                int mathClose = LabelRuns.indexOfUnescapedDollar(tok, i + 1);
                if (mathClose > i + 1) {
                    i = mathClose + 1;   // skip the whole well-formed span (its closers are LaTeX)
                    continue;
                }
                // Unclosed or empty `$$` → not math; the `$` is literal — fall through and keep scanning.
            }
            // A single-char closer matches on `c0` alone (byte-identical to the old `c == closeCh`); a
            // two-char closer additionally requires the NEXT char to be `c1` (`])`, `))`, `}}`, `]]`,
            // `)]`) — so a lone `]`/`)`/`}` inside the label never prematurely ends a paired shape.
            if (c == c0 && (c1 == 0 || (i + 1 < n && tok.charAt(i + 1) == c1))) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /// True iff the label carries a DSL delimiter (`[`/`]`/`{`/`}`) OUTSIDE every well-formed `$…$`
    /// math span — a nested/unbalanced node delimiter, which drops the line (loud-not-silent). Braces
    /// inside a math span are LaTeX and are IGNORED. Reuses {@link LabelRuns#split} so the notion of
    /// "inside math" is byte-for-byte the moat splitter's: an unterminated `$` is NOT a span (its `$`
    /// stays in a {@link LabelRuns.Text} run), so a stray delimiter next to it is still caught here. A
    /// label with no `$` is one Text run, so this is exactly the old four-char scan (byte-parity).
    private static boolean hasDslDelimiterOutsideMath(String label) {
        for (LabelRuns.Run run : LabelRuns.split(label)) {
            if (run instanceof LabelRuns.Text text) {
                String s = text.s();
                if (s.indexOf('[') >= 0 || s.indexOf(']') >= 0
                    || s.indexOf('{') >= 0 || s.indexOf('}') >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /// If `tok`'s LAST whitespace-separated token is a contract-legal `#hex` colour, returns that
    /// (raw, un-normalized); else `null`. Used to detect a colour suffix on a bare-id / state endpoint.
    private static String trailingHex(String tok) {
        int start = tok.length();
        while (start > 0 && !Character.isWhitespace(tok.charAt(start - 1))) {
            start--;
        }
        if (start > 0) {
            String tail = tok.substring(start).strip();
            if (SirentideContract.isHexColor(tail)) {
                return tail;
            }
        }
        return null;
    }

    /// Registers a node in first-seen order. A brand-new id enters with its decorated label (or the
    /// id itself when bare), subject to {@link #MAX_NODES}. An already-seen id UPGRADES from a
    /// default (label == id) to its first decorated label; the first DECORATED occurrence also sets
    /// the shape AND the colour (a later mention never changes any of them — first decoration wins).
    /// Returns true IFF this call NEWLY registered the id (so the caller assigns cluster membership on
    /// first sight only) — false for an already-seen id or one dropped past the node cap.
    private static boolean registerNode(LinkedHashMap<String, String> map, Map<String, String> shapes,
                                        Map<String, String> colors, String[] nd) {
        String id = nd[0];
        String decoratedLabel = nd[1];   // null when the token was bare
        String shape = nd[2];            // null when the token was bare
        String color = nd[3];            // null when no trailing `#hex` colour
        boolean newlyRegistered = false;
        if (!map.containsKey(id)) {
            if (map.size() >= MAX_NODES) {
                return false;   // drop past the node cap (never throw / never allocate unboundedly)
            }
            map.put(id, decoratedLabel != null ? decoratedLabel : id);
            newlyRegistered = true;
        } else if (decoratedLabel != null && map.get(id).equals(id)) {
            map.put(id, decoratedLabel);   // first decorated occurrence upgrades a bare default
        }
        if (shape != null && map.containsKey(id) && !shapes.containsKey(id)) {
            shapes.put(id, shape);   // first decorated occurrence wins the shape too
        }
        if (color != null && map.containsKey(id) && !colors.containsKey(id)) {
            colors.put(id, color);   // first colour-bearing occurrence wins the colour
        }
        return newlyRegistered;
    }

    /// Parses a sequence diagram: actors across the top and time-ordered messages between them.
    /// ```
    /// sequence
    /// Alice ->> Bob   : Request token
    /// Bob  -->> Alice : Token
    /// Alice ->> Alice : Validate locally
    /// ```
    /// Each body line is `FROM ARROW TO : label`. The ARROW is a CALL — `->>` or its alias `->`
    /// (solid, filled-triangle head) — or a REPLY — `-->>` or its alias `-->` (lighter, open-V head).
    ///
    /// SPLIT DISCIPLINE (mirrors the flowchart operator-scan): split at the FIRST ` : ` first — that
    /// is the OPTIONAL label delimiter, and the label follows the whole arrow region — then scan the
    /// pre-colon HEAD for the arrow token. So an arrow INSIDE the label (`A ->> B : retry -> escalate`)
    /// is inert (post-colon), never a mis-split. The head scan is LEFTMOST + LONGEST-at-position
    /// ({@link #scanSeqArrow}): at each offset it tries `-->>` (len 4) before `->>`/`-->` (len 3)
    /// before `->` (len 2), so a longer form never mis-splits (`->>` is a substring of `-->>`; `->` of
    /// all of them). CONSEQUENCE: actor names cannot contain an arrow token or ` : ` (documented).
    ///
    /// Both endpoints auto-register in first-seen order (a self-message `A ->> A` registers `A` once).
    /// A malformed line — no arrow token in the head, or an empty endpoint — is DROPPED whole (never
    /// throws, DESIGN §6). Caps: {@link #MAX_ACTORS} actors, {@link #MAX_DATA_ROWS} messages;
    /// ids/labels `cap()`'d. A bare `sequence` body (no non-blank lines) → a Sequence with no actors
    /// and `bodyHadContent=false` (an intentional blank canvas). A NON-EMPTY body that parses to zero
    /// actors (every line malformed) sets `bodyHadContent=true` so layout degrades VISIBLY.
    ///
    /// BLOCK KEYWORDS (M2 — alt/loop/par frames). A line whose FIRST token is `alt`/`loop`/`par`,
    /// `else`/`and`, or `end` AND which carries NO arrow token is a BLOCK DIRECTIVE, not a message
    /// (the no-arrow guard keeps a real message whose sender is spelled like a keyword — vanishingly
    /// rare — parsing as a message, and keeps the split robust). We track a STACK of open blocks:
    ///   • `alt`/`loop`/`par <label>` opens a block at the next message index, `depth` = the current
    ///     open count (so nesting insets in layout).
    ///   • `else <label>` (only when the innermost open block is an `alt`) / `and <label>` (only for
    ///     `par`) records a {@link Divider} at the next message index. A stray `else`/`and` — none
    ///     open, or the innermost block is the wrong kind — is IGNORED (malformed→inert, never throws).
    ///   • `end` closes the innermost open block, spanning to the last message so far. A stray `end`
    ///     (no open block) is IGNORED.
    /// Any block still open at end-of-input is closed at the last message (never throws). `else`/`and`
    /// on a block that ends up EMPTY, or a divider past the last message, is skipped in layout.
    private static Diagram parseSequence(String[] lines, String[] header, String textColor) {
        // Header `nodecolor=#hex` colours ALL actor heads (per-actor colours are a follow-up — no
        // actor-decl syntax yet). null → the layout's built-in head fill.
        String nodeColor = parseNodeColor(header);
        LinkedHashSet<String> actors = new LinkedHashSet<>();
        List<SeqMessage> messages = new ArrayList<>();
        List<SeqBlock> blocks = new ArrayList<>();
        List<SeqNote> notes = new ArrayList<>();
        List<SeqLifecycle> lifecycles = new ArrayList<>();
        Deque<OpenBlock> stack = new ArrayDeque<>();   // innermost open block on top
        boolean bodyHadContent = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            bodyHadContent = true;   // a non-blank body line existed (even if it turns out malformed)
            // A leading block / note / lifecycle keyword on an ARROWLESS line is a directive, not a
            // message (the no-arrow guard keeps a real message whose sender is spelled like a keyword
            // parsing as a message). Try the block keywords first, then note/create/destroy.
            if (scanSeqArrow(line) == null) {
                if (handleBlockKeyword(line, messages, blocks, stack)) {
                    continue;
                }
                if (handleNoteOrLifecycle(line, actors, notes, lifecycles, messages.size())) {
                    continue;
                }
            }
            // Peel the OPTIONAL label at the FIRST colon, ANY spacing (`B : hi`, `B: hi`, `B:hi` —
            // requiring " : " silently broke the common no-space form: Lattice, sirentide/33). The
            // arrow lives in the pre-colon HEAD, so an arrow token in the label is inert
            // (operator-scan discipline, mirrors the flowchart); a colon INSIDE the label survives
            // because only the FIRST colon delimits. Actor names cannot contain ':' (as before).
            String[] peeled = peelLabel(line);
            String head = peeled[0];
            String label = peeled[1] == null ? null : cap(peeled[1]);
            // Scan the head for the arrow token (leftmost, longest-at-position). null → no arrow → drop.
            SeqArrow arrow = scanSeqArrow(head);
            if (arrow == null) {
                continue;   // no arrow token in the head → malformed, drop the line
            }
            String from = cap(head.substring(0, arrow.pos()).strip());
            String toRaw = head.substring(arrow.pos() + arrow.len()).strip();
            // Activation-bar sigil (mermaid `->>+ B` / `-->>- B`): a `+`/`-` immediately after the
            // arrow is activation SYNTAX, not part of the actor name. Bars themselves are
            // unsupported (dropped decoration, like an `opt` frame) — but the sigil must be
            // consumed, or it silently mints a WRONG actor literally named `+ B` (the playground
            // silent-mint finding). Actor names may not begin with `+`/`-` as a result — reserved.
            // A well-formed activation carries EXACTLY ONE sigil: after consuming it the target
            // must be a real actor name, so a STILL-sigil-prefixed or empty remainder (`->>++C`,
            // `-->>--C`, `->>+ ` with no target) is malformed activation grammar and DROPS the whole
            // line rather than minting a `+C` phantom (Lattice 7141 finding 3).
            if (!toRaw.isEmpty() && (toRaw.charAt(0) == '+' || toRaw.charAt(0) == '-')) {
                toRaw = toRaw.substring(1).strip();
                if (toRaw.isEmpty() || toRaw.charAt(0) == '+' || toRaw.charAt(0) == '-') {
                    continue;   // malformed activation (empty or repeated sigil) → drop the line
                }
            }
            String to = cap(toRaw);
            if (from.isEmpty() || to.isEmpty()) {
                continue;   // empty endpoint → malformed, drop the line
            }
            // Register both endpoints (first-seen order), each subject to the actor cap. A message is
            // kept only if BOTH endpoints registered (so an over-cap actor drops its messages too).
            registerActor(actors, from);
            registerActor(actors, to);
            if (messages.size() < MAX_DATA_ROWS && actors.contains(from) && actors.contains(to)) {
                messages.add(new SeqMessage(from, to, label, arrow.reply()));
            }
        }
        // Unclosed blocks at end-of-input close at the last message (innermost first). Never throw.
        while (!stack.isEmpty()) {
            blocks.add(stack.pop().close(messages.size() - 1));
        }
        return new Sequence(new ArrayList<>(actors), messages, textColor, nodeColor, bodyHadContent,
            blocks, notes, lifecycles);
    }

    /// The reserved leading NOTE / LIFECYCLE keywords (M2 enrichment). A line whose first token is one
    /// of these AND which has no arrow token is a note/lifecycle directive (never an actor name).
    private static final String KW_NOTE = "note";
    private static final String KW_CREATE = "create";
    private static final String KW_DESTROY = "destroy";
    /// An optional `participant` filler after `create`/`destroy` (mermaid `create participant X`).
    private static final String KW_PARTICIPANT = "participant";

    /// Handles a note / create / destroy directive line (already known to be arrowless). Returns true
    /// when the first token WAS one of those keywords (consumed — added a note / lifecycle event, or
    /// was an inert malformed directive), false when it is none (so the caller falls through to the
    /// normal message parse). `atMsg` is `messages.size()` — the index the NEXT message will take, so a
    /// note/create/destroy anchors between the surrounding messages (the same index convention the
    /// block keywords use). Robustness (DESIGN §6): a malformed note (bad position / unknown actor / no
    /// text) or an unknown-actor create/destroy is swallowed inert, never throws.
    private static boolean handleNoteOrLifecycle(String line, LinkedHashSet<String> actors,
                                                 List<SeqNote> notes, List<SeqLifecycle> lifecycles,
                                                 int atMsg) {
        String[] kwRest = splitKeyword(line);
        switch (kwRest[0]) {
            case KW_NOTE -> {
                SeqNote note = parseNote(kwRest[1], actors, atMsg);
                if (note != null) {
                    notes.add(note);
                }
                return true;   // a malformed note is consumed but inert (never a stray message)
            }
            case KW_CREATE -> {
                // `create [participant] X` REGISTERS the actor first-seen (create introduces it) and
                // starts its lifeline mid-diagram. An empty/over-cap actor is inert.
                String actor = cap(stripParticipant(kwRest[1]).strip());
                if (!actor.isEmpty()) {
                    registerActor(actors, actor);
                    if (actors.contains(actor)) {
                        lifecycles.add(new SeqLifecycle(actor, true, atMsg));
                    }
                }
                return true;
            }
            case KW_DESTROY -> {
                // `destroy [participant] X` ends an ALREADY-REGISTERED actor's lifeline. An unknown /
                // empty actor is inert (destroying a non-existent participant is meaningless).
                String actor = cap(stripParticipant(kwRest[1]).strip());
                if (!actor.isEmpty() && actors.contains(actor)) {
                    lifecycles.add(new SeqLifecycle(actor, false, atMsg));
                }
                return true;
            }
            default -> {
                return false;   // not a note/lifecycle keyword → fall through to the message parse
            }
        }
    }

    /// Strips an optional leading `participant ` filler token (mermaid `create participant X` ==
    /// `create X`). A bare id (no filler) is returned unchanged.
    private static String stripParticipant(String rest) {
        String s = rest.strip();
        if (s.equals(KW_PARTICIPANT)) {
            return "";
        }
        if (s.startsWith(KW_PARTICIPANT + " ") || s.startsWith(KW_PARTICIPANT + "\t")) {
            return s.substring(KW_PARTICIPANT.length()).strip();
        }
        return s;
    }

    /// Parses a `note` directive tail (everything after the `note` keyword) into a {@link SeqNote}, or
    /// null when malformed (→ the caller drops it, inert). Form:
    /// `(over|left of|right of) <actor>[,<actor2>] : text`. The label is peeled at the FIRST colon
    /// (the text); the pre-colon head names the position + actor(s). `over` takes 1 OR 2 actors (a
    /// two-actor note SPANS them); `left of`/`right of` take a SINGLE actor (a second is ignored). Every
    /// referenced actor must be KNOWN (first-seen already) — an unknown actor, a missing/empty text, an
    /// unrecognized position, or no resolvable actor all yield null (malformed→inert, DESIGN §6).
    private static SeqNote parseNote(String rest, LinkedHashSet<String> actors, int atMsg) {
        String[] peeled = peelLabel(rest);
        String head = peeled[0].strip();
        String text = peeled[1] == null ? null : cap(peeled[1]);
        if (text == null) {
            return null;   // a textless note is dropped
        }
        String position;
        String actorsPart;
        if (head.startsWith("over ")) {
            position = "over";
            actorsPart = head.substring("over ".length()).strip();
        } else if (head.startsWith("left of ")) {
            position = "left";
            actorsPart = head.substring("left of ".length()).strip();
        } else if (head.startsWith("right of ")) {
            position = "right";
            actorsPart = head.substring("right of ".length()).strip();
        } else {
            return null;   // unrecognized position keyword → inert
        }
        // Resolve the referenced actors: every non-empty token must be a KNOWN actor (a note does not
        // register new actors — it annotates existing ones). Any unknown token drops the whole note.
        List<String> resolved = new ArrayList<>();
        for (String tok : actorsPart.split(",")) {
            String a = cap(tok.strip());
            if (a.isEmpty()) {
                continue;
            }
            if (!actors.contains(a)) {
                return null;   // an unknown referenced actor → malformed, drop
            }
            resolved.add(a);
        }
        if (resolved.isEmpty()) {
            return null;   // no resolvable actor → inert
        }
        // left/right annotate a SINGLE actor; over spans up to 2 (extra tokens are trimmed).
        if (!position.equals("over")) {
            resolved = List.of(resolved.get(0));
        } else if (resolved.size() > 2) {
            resolved = resolved.subList(0, 2);
        }
        return new SeqNote(position, resolved, text, atMsg);
    }

    /// The reserved leading block keywords (M2). A line whose first whitespace-delimited token is one
    /// of these AND which has no arrow token is a block directive (never an actor name).
    private static final String KW_ALT = "alt";
    private static final String KW_LOOP = "loop";
    private static final String KW_PAR = "par";
    private static final String KW_ELSE = "else";
    private static final String KW_AND = "and";
    private static final String KW_END = "end";

    /// Handles a block-directive line (already known to be arrowless). Returns true when the line WAS
    /// a recognized block keyword (and was consumed — opened/divided/closed a block, or was an inert
    /// stray), false when the first token is not a block keyword (so the caller falls through to the
    /// normal message parse). `messages.size()` is the index the NEXT message will take — an opening
    /// keyword anchors `fromMsg` there, a divider anchors `atMsg` there. Robustness (DESIGN §6): a
    /// stray `else`/`and`/`end` — nothing open, or the wrong open kind — is swallowed, never throws.
    private static boolean handleBlockKeyword(String line, List<SeqMessage> messages,
                                              List<SeqBlock> blocks, Deque<OpenBlock> stack) {
        String[] kwRest = splitKeyword(line);
        String kw = kwRest[0];
        String rest = kwRest[1];   // the free-text label after the keyword ("" when bare)
        switch (kw) {
            case KW_ALT, KW_LOOP, KW_PAR -> {
                if (stack.size() < MAX_BLOCK_DEPTH) {
                    stack.push(new OpenBlock(kw, cap(rest), messages.size(), stack.size()));
                }
                // Past the nesting cap the keyword is swallowed (its `end` will hit an empty/other
                // stack and be inert) — never allocate unboundedly, never throw.
                return true;
            }
            case KW_ELSE -> {
                OpenBlock top = stack.peek();
                if (top != null && top.kind.equals(KW_ALT) && top.dividers.size() < MAX_DATA_ROWS) {
                    top.dividers.add(new Divider(messages.size(), cap(rest)));
                }
                return true;   // a stray `else` (no alt open) is inert
            }
            case KW_AND -> {
                OpenBlock top = stack.peek();
                if (top != null && top.kind.equals(KW_PAR) && top.dividers.size() < MAX_DATA_ROWS) {
                    top.dividers.add(new Divider(messages.size(), cap(rest)));
                }
                return true;   // a stray `and` (no par open) is inert
            }
            case KW_END -> {
                if (!stack.isEmpty()) {
                    blocks.add(stack.pop().close(messages.size() - 1));
                }
                return true;   // a stray `end` (nothing open) is inert
            }
            default -> {
                return false;   // not a block keyword → fall through to the message parse
            }
        }
    }

    /// Splits a directive line into `[keyword, rest]` — the first whitespace-delimited token and the
    /// remaining free-text label (stripped; "" when the keyword stands alone, e.g. a bare `end`).
    private static String[] splitKeyword(String line) {
        int sp = line.indexOf(' ');
        int tab = line.indexOf('\t');
        int cut = sp < 0 ? tab : (tab < 0 ? sp : Math.min(sp, tab));
        if (cut < 0) {
            return new String[] {line, ""};
        }
        return new String[] {line.substring(0, cut), line.substring(cut + 1).strip()};
    }

    /// A mutable open-block accumulator on the parse stack: its kind/label/`fromMsg`/`depth` are fixed
    /// at open time; `dividers` grow as `else`/`and` lines arrive; `close` freezes it to an immutable
    /// {@link SeqBlock} spanning `[fromMsg, toMsg]`. Parse-internal, never surfaced in the IR.
    private static final class OpenBlock {
        final String kind;
        final String label;
        final int fromMsg;
        final int depth;
        final List<Divider> dividers = new ArrayList<>();

        OpenBlock(String kind, String label, int fromMsg, int depth) {
            this.kind = kind;
            this.label = label;
            this.fromMsg = fromMsg;
            this.depth = depth;
        }

        SeqBlock close(int toMsg) {
            return new SeqBlock(kind, label, fromMsg, toMsg, dividers, depth);
        }
    }

    /// A located sequence-message arrow: byte `pos` in the head, token `len`, and whether it is a
    /// REPLY (`-->>` / `-->`) rather than a CALL (`->>` / `->`).
    private record SeqArrow(int pos, int len, boolean reply) {}

    /// Scans a head segment for the arrow token — LEFTMOST match, LONGEST token at that position, so a
    /// longer form never mis-splits (`->>` is a substring of `-->>`; `->` a substring of all). The
    /// left-to-right walk guarantees we never start MID-token: a `-->>` at offset j is caught at j, so
    /// the loop never reaches j+1 to spuriously match the embedded `->>`. Returns null when the head
    /// carries no arrow token (→ the caller drops the line, loud-not-silent). `-->>`/`-->` are replies;
    /// `->>`/`->` are calls.
    private static SeqArrow scanSeqArrow(String head) {
        int n = head.length();
        for (int i = 0; i < n; i++) {
            if (head.startsWith("-->>", i)) {
                return new SeqArrow(i, 4, true);    // reply
            }
            if (head.startsWith("->>", i)) {
                return new SeqArrow(i, 3, false);   // call
            }
            if (head.startsWith("-->", i)) {
                return new SeqArrow(i, 3, true);    // reply alias
            }
            if (head.startsWith("->", i)) {
                return new SeqArrow(i, 2, false);   // call alias
            }
        }
        return null;
    }

    /// Registers an actor in first-seen order, up to {@link #MAX_ACTORS}; past the cap a brand-new
    /// actor is dropped (never throws / never allocates unboundedly). An already-seen actor is a no-op.
    private static void registerActor(LinkedHashSet<String> actors, String id) {
        if (!actors.contains(id) && actors.size() < MAX_ACTORS) {
            actors.add(id);
        }
    }

    /// The reserved pseudostate node ids — mermaid's `[*]` maps to `__start__` when it is a
    /// transition SOURCE and `__end__` when it is a TARGET, so a single `[*]` token can resolve to
    /// EITHER depending on its role in a given hop (they are registered as two DISTINCT nodes).
    private static final String START_ID = "__start__";
    private static final String END_ID = "__end__";
    private static final String STATE_TOKEN = "[*]";

    /// Parses a mermaid-style state diagram — a directed graph of states + transitions, layered by the
    /// SAME engine flowcharts use (it wraps a {@link Flowchart} in a {@link StateDiagram}).
    /// ```
    /// state
    /// [*] --> Idle
    /// Idle --> Running : start
    /// Running --> Idle : stop
    /// Running --> [*]
    /// ```
    /// Each body line is either a TRANSITION (`SRC --> DST [ : label]`, chainable `A --> B --> C`) or
    /// a bare STATE declaration (`S`, or `S : display name`). The transition label is mermaid-style —
    /// it follows a `:` AFTER the destination, NOT `|pipes|`. So the line is split at its FIRST
    /// top-level ` : ` into (edges-part, tail); the tail is a TRANSITION label when the edges-part has
    /// arrows, or a state DISPLAY NAME when it does not. On a chained transition the label applies to
    /// the LAST hop only (mermaid semantics). `[*]` is the START pseudostate (id `__start__`) when it
    /// is a hop SOURCE and the END pseudostate (id `__end__`) when it is a hop TARGET; both carry an
    /// EMPTY label so layout draws a disc/bullseye with no text. Arrow splitting reuses
    /// {@link #topLevelArrows}. Malformed rows (empty endpoint, bare `[*]`) drop, never throw
    /// (DESIGN §6). Caps {@link #MAX_NODES}/{@link #MAX_EDGES} bound the graph. Empty body → a state
    /// diagram with no states (round-trips, NOT degraded to Empty).
    private static Diagram parseStateDiagram(String[] lines, String[] header, String textColor) {
        // Header `nodecolor=#hex` colours every state box (null → the built-in default); a per-state
        // trailing `#hex` (`Idle #22c55e`) overrides it, riding the same endpoint parse as flowcharts.
        String nodeColor = parseNodeColor(header);
        LinkedHashMap<String, String> nodeLabels = new LinkedHashMap<>();
        Map<String, String> nodeShapes = new java.util.HashMap<>();
        Map<String, String> nodeColors = new java.util.HashMap<>();
        List<FlowEdge> edges = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            // Split off the FIRST ` : ` tail. The colon FOLLOWS the destination, so scan arrows on the
            // pre-colon segment: the tail is a transition label (arrows present) or a display name (not).
            String[] peeled = peelLabel(line);
            String edgesPart = peeled[0].strip();
            String tail = peeled[1] == null ? null : cap(peeled[1]);
            List<Integer> arrows = topLevelArrows(edgesPart);
            if (arrows.isEmpty()) {
                // Bare state declaration: `S`, `S : display name`, or `S #hex` (per-state colour). A
                // bare `[*]` has no role → drop.
                String[] pc = peelStateEndpoint(edgesPart);
                String id = cap(pc[0]);
                if (id.isEmpty() || id.equals(STATE_TOKEN)) {
                    continue;
                }
                registerState(nodeLabels, nodeShapes, nodeColors, id, tail, pc[1]);
                continue;
            }
            // Tokenize the chain into endpoints separated by top-level `-->`, peel each endpoint's
            // optional trailing `#hex`, and validate the id is non-empty (drop the WHOLE line
            // otherwise — never half-wire a chain, DESIGN §6).
            List<String> ids = new ArrayList<>();
            List<String> colors = new ArrayList<>();
            List<String> raw = new ArrayList<>();
            raw.add(edgesPart.substring(0, arrows.get(0)).strip());
            for (int k = 0; k < arrows.size(); k++) {
                int segStart = arrows.get(k) + 3;
                int segEnd = (k + 1 < arrows.size()) ? arrows.get(k + 1) : edgesPart.length();
                raw.add(edgesPart.substring(segStart, segEnd).strip());
            }
            boolean dropped = false;
            for (String ep : raw) {
                String[] pc = peelStateEndpoint(ep);
                if (pc[0].isEmpty()) {
                    dropped = true;
                    break;
                }
                ids.add(pc[0]);
                colors.add(pc[1]);
            }
            if (dropped) {
                continue;
            }
            // Wire one edge per hop. `[*]` resolves per ROLE: as a hop's SOURCE it is __start__, as its
            // TARGET it is __end__ (so the same token in `A --> [*] --> B` is END then START). A
            // pseudostate never takes a colour (its disc fill is fixed). The label rides the LAST hop.
            int lastHop = ids.size() - 2;
            for (int k = 0; k < ids.size() - 1; k++) {
                boolean fromStar = ids.get(k).equals(STATE_TOKEN);
                boolean toStar = ids.get(k + 1).equals(STATE_TOKEN);
                String from = fromStar ? START_ID : cap(ids.get(k));
                String to = toStar ? END_ID : cap(ids.get(k + 1));
                registerState(nodeLabels, nodeShapes, nodeColors, from, null, fromStar ? null : colors.get(k));
                registerState(nodeLabels, nodeShapes, nodeColors, to, null, toStar ? null : colors.get(k + 1));
                String hopLabel = (k == lastHop) ? tail : null;
                if (edges.size() < MAX_EDGES
                    && nodeLabels.containsKey(from) && nodeLabels.containsKey(to)) {
                    edges.add(new FlowEdge(from, to, hopLabel));
                }
            }
        }
        List<FlowNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> e : nodeLabels.entrySet()) {
            nodes.add(new FlowNode(e.getKey(), e.getValue(),
                nodeShapes.get(e.getKey()), nodeColors.get(e.getKey())));
        }
        return new StateDiagram(new Flowchart(nodes, edges, "TD", textColor, nodeColor));
    }

    /// Peels an OPTIONAL trailing `#hex` colour off a state endpoint token, returning
    /// `{idPart, colorOrNull}` (colour normalized to canonical `#rrggbb`). Mirrors the flowchart
    /// endpoint's trailing-colour rule for state lines (`Idle #22c55e`). A token with no
    /// whitespace-separated trailing hex is returned unchanged with a `null` colour.
    private static String[] peelStateEndpoint(String tok) {
        String hex = trailingHex(tok);
        if (hex != null) {
            int cut = tok.length() - hex.length();
            return new String[] {tok.substring(0, cut).strip(), SirentideContract.normalizeColor(hex)};
        }
        return new String[] {tok, null};
    }

    /// Registers a state (or pseudostate) in first-seen order, up to {@link #MAX_NODES}. The
    /// pseudostates `__start__`/`__end__` get shape `"start"`/`"end"` and an EMPTY label (a disc, no
    /// text); a normal state gets shape `"state"` and defaults its label to its id. A `displayName`
    /// (from a bare `S : name` decl) UPGRADES a state whose label is still the default id — the first
    /// display name wins, a later bare mention never overwrites it. A per-state `color` (from a
    /// trailing `#hex`) sets the box fill on first colour-bearing occurrence; pseudostates never carry
    /// a colour (the caller passes `null` for `[*]`).
    private static void registerState(LinkedHashMap<String, String> map, Map<String, String> shapes,
                                      Map<String, String> colors, String id, String displayName,
                                      String color) {
        String shape;
        String defaultLabel;
        if (id.equals(START_ID)) {
            shape = "start";
            defaultLabel = "";
        } else if (id.equals(END_ID)) {
            shape = "end";
            defaultLabel = "";
        } else {
            shape = "state";
            defaultLabel = id;
        }
        if (!map.containsKey(id)) {
            if (map.size() >= MAX_NODES) {
                return;   // drop past the node cap (never throw / allocate unboundedly)
            }
            map.put(id, displayName != null ? displayName : defaultLabel);
            shapes.put(id, shape);
        } else if (displayName != null && map.get(id).equals(defaultLabel)) {
            map.put(id, displayName);   // first display name upgrades the default id label
        }
        if (color != null && map.containsKey(id) && !colors.containsKey(id)) {
            colors.put(id, color);   // first colour-bearing occurrence wins the box fill
        }
    }

    /// Parses a quadrant chart — a 2×2 positioning matrix.
    /// ```
    /// quadrant
    ///   x-axis "Low Reach" --> "High Reach"
    ///   y-axis "Low Impact" --> "High Impact"
    ///   quadrant-1 "Major project"
    ///   quadrant-2 "Quick win"
    ///   quadrant-3 "Deprioritize"
    ///   quadrant-4 "Fill-in"
    ///   "Feature A" : [0.3, 0.6]
    ///   "Feature B" : [0.75, 0.8]
    /// ```
    /// DIRECTIVE rows: `x-axis`/`y-axis` carry the axis-END labels split on `-->` (the `Low` end
    /// LEFT of the arrow, the `High` end right; a lone value with no `-->` sets only the Low end).
    /// `quadrant-1`..`quadrant-4` carry a quadrant label — Mermaid numbering: Q1 top-right, Q2
    /// top-left, Q3 bottom-left, Q4 bottom-right. POINT rows are `"label" : [x, y]` with `x,y` in
    /// `[0,1]` (out-of-range CLAMPED into the unit square, never thrown). Every part is OPTIONAL —
    /// a bare `quadrant` still bakes a valid empty 2×2 grid. A malformed row is skipped (DESIGN §6:
    /// never fail the bake); the type ALWAYS round-trips to a QuadrantChart, never degrades to Empty.
    private static Diagram parseQuadrant(String[] lines, String textColor) {
        String[] xEnds = {null, null};    // {lo, hi}
        String[] yEnds = {null, null};    // {lo, hi}
        String[] quadrantLabels = new String[4];
        List<Point> points = new ArrayList<>();
        for (int i = 1; i < lines.length && points.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("x-axis")) {
                axisEnds(xEnds, line.substring("x-axis".length()));
            } else if (line.startsWith("y-axis")) {
                axisEnds(yEnds, line.substring("y-axis".length()));
            } else if (line.startsWith("quadrant-1")) {
                quadrantLabels[0] = quadrantLabel(line.substring("quadrant-1".length()));
            } else if (line.startsWith("quadrant-2")) {
                quadrantLabels[1] = quadrantLabel(line.substring("quadrant-2".length()));
            } else if (line.startsWith("quadrant-3")) {
                quadrantLabels[2] = quadrantLabel(line.substring("quadrant-3".length()));
            } else if (line.startsWith("quadrant-4")) {
                quadrantLabels[3] = quadrantLabel(line.substring("quadrant-4".length()));
            } else {
                // A point row `"label" : [x, y]`. No colon → not a point → skip (never fail the bake).
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String label = cap(unquote(line.substring(0, colon).strip()));
                Point p = parsePoint(label, line.substring(colon + 1).strip());
                if (p != null) {
                    points.add(p);
                }
            }
        }
        return new QuadrantChart(xEnds[0], xEnds[1], yEnds[0], yEnds[1],
            quadrantLabels, points, textColor);
    }

    /// Parse a comparison / verdict matrix (plan sirentide-comparison-matrix-type). A `cols:` (alias
    /// `columns:`) line names the M column headers; every other `"label" : v1, v2, …` line is a row
    /// whose comma-separated tokens become verdict cells. Every row is padded/truncated to exactly M
    /// cells (M = the header count, or the widest row when there's no header) so the grid is always
    /// rectangular. A line with no colon (and not the `cols:` header) is skipped — never fails the bake.
    private static Diagram parseMatrix(String[] lines, String textColor) {
        List<String> columns = new ArrayList<>();
        List<Matrix.Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length && rows.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.regionMatches(true, 0, "cols:", 0, 5)
                || line.regionMatches(true, 0, "columns:", 0, 8)) {
                for (String tok : line.substring(line.indexOf(':') + 1).split(",")) {
                    if (columns.size() >= MAX_COLUMNS) {
                        break;   // robustness fe8c5bbc #4: drop columns past the cap, never OOM
                    }
                    String header = cap(unquote(tok.strip()));
                    if (!header.isEmpty()) {
                        columns.add(header);
                    }
                }
                continue;
            }
            // Split label : cells. A quoted label may itself contain a colon, so find the closing
            // quote first, then the separator colon after it.
            int sep;
            if (line.startsWith("\"")) {
                int close = line.indexOf('"', 1);
                sep = close < 0 ? -1 : line.indexOf(':', close + 1);
            } else {
                sep = line.indexOf(':');
            }
            if (sep < 0) {
                continue;   // not a row (no verdicts) → skip, never throw
            }
            String label = cap(unquote(line.substring(0, sep).strip()));
            List<Matrix.Cell> cells = new ArrayList<>();
            for (String tok : line.substring(sep + 1).split(",", -1)) {
                if (cells.size() >= MAX_COLUMNS) {
                    break;   // robustness fe8c5bbc #4: a 500k-cell row is bounded too, not just the header
                }
                cells.add(parseCell(tok));
            }
            rows.add(new Matrix.Row(label, cells));
        }
        int m = columns.isEmpty()
            ? rows.stream().mapToInt(r -> r.cells().size()).max().orElse(0)
            : columns.size();
        List<Matrix.Row> normalized = new ArrayList<>();
        for (Matrix.Row r : rows) {
            List<Matrix.Cell> cs = new ArrayList<>(r.cells());
            while (cs.size() < m) {
                cs.add(new Matrix.Cell("", Matrix.Verdict.NA));
            }
            normalized.add(new Matrix.Row(r.label(), cs.size() > m ? new ArrayList<>(cs.subList(0, m)) : cs));
        }
        return new Matrix(columns, normalized, textColor);
    }

    /// Parse one matrix cell. Two shapes: a bare verdict token (`pass`, `match`) where the token is
    /// BOTH the shown text and the colour; or `display text:verdict` where the part before the last
    /// colon is shown verbatim and the part after picks the fill (e.g. `HELD:pass` → the word "HELD"
    /// on a green cell). The `text:verdict` shape lets a descriptive matrix (11-operate-clone-replay's
    /// "HELD (principle)" / "hold + offer" cells) still carry the match/diverge colour. A blank cell is NA.
    private static Matrix.Cell parseCell(String token) {
        String tok = token.strip();
        int c = tok.lastIndexOf(':');
        if (c >= 0) {
            String text = tok.substring(0, c).strip();
            return new Matrix.Cell(cap(text), verdictOf(tok.substring(c + 1)));
        }
        return new Matrix.Cell(cap(tok), verdictOf(tok));
    }

    /// Map a cell token to the closed verdict vocabulary (the only values that reach the palette).
    /// Blank / `-` / `na` / anything unrecognized → NA (neutral), so an unknown token never introduces
    /// a colour and never throws.
    private static Matrix.Verdict verdictOf(String token) {
        String t = token.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "pass", "match", "yes", "ok", "y", "true", "✓", "✔" -> Matrix.Verdict.PASS;
            case "fail", "diverge", "no", "n", "false", "✗", "✘", "x" -> Matrix.Verdict.FAIL;
            case "partial", "part", "mixed", "~", "◑" -> Matrix.Verdict.PARTIAL;
            default -> Matrix.Verdict.NA;
        };
    }

    /// Fills `{lo, hi}` from an axis-end directive's tail (`"Low" --> "High"`). The `-->` splits the
    /// two ends; with no arrow the whole tail is the LOW end and the high end stays null. Each end is
    /// unquoted, `cap()`'d, and a blank end becomes null (an absent end is simply not drawn).
    private static void axisEnds(String[] ends, String tail) {
        tail = tail.strip();
        int arrow = tail.indexOf("-->");
        if (arrow >= 0) {
            ends[0] = axisEnd(tail.substring(0, arrow));
            ends[1] = axisEnd(tail.substring(arrow + 3));
        } else {
            ends[0] = axisEnd(tail);
        }
    }

    /// One axis-end label: unquoted, `cap()`'d, blank → null.
    private static String axisEnd(String s) {
        String v = cap(unquote(s.strip()));
        return v.isEmpty() ? null : v;
    }

    /// A quadrant label from a `quadrant-N` directive tail: unquoted, `cap()`'d, blank → null.
    private static String quadrantLabel(String tail) {
        String v = cap(unquote(tail.strip()));
        return v.isEmpty() ? null : v;
    }

    /// Parses a point's `[x, y]` coordinate tail into a {@link Point}, or null when malformed (which
    /// the caller skips). Surrounding `[ ]` are optional; the two comma-split tokens parse as doubles
    /// and CLAMP into the unit square `[0,1]` (out-of-range never throws — the inert-shell invariant,
    /// DESIGN §6). A non-numeric or non-finite coordinate, or the wrong token count, drops the point.
    private static Point parsePoint(String label, String coords) {
        String c = coords.strip();
        if (c.startsWith("[")) {
            c = c.substring(1);
        }
        if (c.endsWith("]")) {
            c = c.substring(0, c.length() - 1);
        }
        String[] toks = c.split(",");
        if (toks.length != 2) {
            return null;   // a point needs exactly an x and a y
        }
        try {
            double x = Double.parseDouble(toks[0].strip());
            double y = Double.parseDouble(toks[1].strip());
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return null;   // NaN/Infinity (incl. "1e400") never reaches layout
            }
            return new Point(label, clampUnit(x), clampUnit(y));
        } catch (NumberFormatException e) {
            return null;   // unparseable coordinate → drop the point (never fail the bake)
        }
    }

    /// Clamps a coordinate into the unit interval `[0,1]` (defensive against an out-of-range author
    /// value — it stays inside the plot square rather than escaping the canvas or throwing).
    private static double clampUnit(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /// The leading keyword that opens a class BLOCK: `class <Name> { … members … }`.
    private static final String KW_CLASS = "class";

    /// Parses a mermaid-style UML class diagram — `class <Name> { members }` blocks plus typed
    /// relationships between classes.
    /// ```
    /// classDiagram
    ///   class Animal {
    ///     +String name
    ///     +eat() void
    ///   }
    ///   Animal <|-- Dog : inherits
    ///   Animal *-- Collar : composition
    /// ```
    /// A `class <Name> {` line OPENS a member block; every subsequent line until a lone `}` is a
    /// MEMBER (classified as a METHOD when it carries `(`, else an ATTRIBUTE — the `+`/`-`/`#`/`~`
    /// visibility marker stays part of the display text). A bare `class <Name>` (no brace) declares an
    /// empty class; `class <Name> {}` / `{ }` opens-and-closes an empty one. OUTSIDE a block a line is
    /// a RELATIONSHIP `LEFT OP RIGHT [: label]` where OP is one of the five UML operators
    /// ({@link #scanRelationOp}); each operand references a class, auto-vivified as an empty class when
    /// never declared (mermaid semantics — so `Animal *-- Collar` renders Collar without its own
    /// block). Classes register in FIRST-SEEN order (declared or referenced).
    ///
    /// Robustness (DESIGN §6, never throw): a line that is neither a class directive, a member (inside
    /// a block), nor a well-formed relationship is DROPPED. A relationship with an EMPTY endpoint drops.
    /// An UNCLOSED `{` at end-of-input CLOSES gracefully at EOF (the class keeps the members gathered so
    /// far) rather than throwing — degrade-not-throw. Caps: {@link #MAX_NODES} classes,
    /// {@link #MAX_EDGES} relationships; names/members `cap()`'d. Empty body → a ClassDiagram with no
    /// classes (round-trips, NOT degraded to Empty).
    private static Diagram parseClassDiagram(String[] lines, String textColor) {
        // Insertion-ordered name → member accumulator: preserves first-seen class order (declared or
        // relationship-referenced). A relationship-referenced name auto-vivifies an empty accumulator.
        LinkedHashMap<String, ClassAcc> classes = new LinkedHashMap<>();
        List<ClassRelation> relations = new ArrayList<>();
        ClassAcc open = null;   // the currently-open `class { … }` block, or null when outside one
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (open != null) {
                // Inside a member block: a lone `}` closes it; anything else is a member line.
                if (line.equals("}")) {
                    open = null;
                    continue;
                }
                // A trailing `}` glued to the last member (`+bark() void }`) also closes the block.
                String member = line;
                if (member.endsWith("}")) {
                    member = member.substring(0, member.length() - 1).strip();
                    addMember(open, member);
                    open = null;
                    continue;
                }
                addMember(open, member);
                continue;
            }
            // Outside a block. A `class <Name> [{]` directive opens a block or declares a bare class.
            String[] kwRest = splitKeyword(line);
            if (kwRest[0].equals(KW_CLASS) && !kwRest[1].isEmpty()) {
                String rest = kwRest[1];
                int brace = rest.indexOf('{');
                if (brace < 0) {
                    // Bare `class Name` — an empty class declaration (no members).
                    registerClass(classes, cap(rest.strip()));
                    continue;
                }
                String name = cap(rest.substring(0, brace).strip());
                if (name.isEmpty()) {
                    continue;   // `class {` with no name → malformed, drop (never throw)
                }
                ClassAcc acc = registerClass(classes, name);
                // `class Name {}` / `{ }` on one line opens-and-closes an empty block; otherwise the
                // block stays open for the following member lines. Inline members after `{` on the
                // SAME line are not supported (mermaid puts members on their own lines) — ignored.
                String afterBrace = rest.substring(brace + 1).strip();
                open = afterBrace.startsWith("}") ? null : acc;
                continue;
            }
            // Otherwise a relationship line `LEFT OP RIGHT [: label]`. Peel the optional `: label` at
            // the first colon, then scan the pre-colon head for a UML relation operator.
            String[] peeled = peelLabel(line);
            String head = peeled[0];
            String label = peeled[1] == null ? null : cap(peeled[1]);
            RelationScan op = scanRelationOp(head);
            if (op == null) {
                continue;   // no operator (and not a class directive) → malformed, drop (never throw)
            }
            String left = cap(head.substring(0, op.pos()).strip());
            String right = cap(head.substring(op.pos() + op.len()).strip());
            if (left.isEmpty() || right.isEmpty()) {
                continue;   // an empty endpoint → malformed relation, drop
            }
            // Auto-vivify both operands as classes (first-seen order) and record the relation.
            registerClass(classes, left);
            registerClass(classes, right);
            if (relations.size() < MAX_EDGES
                && classes.containsKey(left) && classes.containsKey(right)) {
                relations.add(new ClassRelation(left, right, op.kind(), label));
            }
        }
        List<ClassBox> boxes = new ArrayList<>();
        for (Map.Entry<String, ClassAcc> e : classes.entrySet()) {
            boxes.add(new ClassBox(e.getKey(), e.getValue().attributes, e.getValue().methods));
        }
        return new ClassDiagram(boxes, relations, textColor);
    }

    /// Registers a class by name in first-seen order (up to {@link #MAX_NODES}), returning its member
    /// accumulator. An already-seen name returns the existing accumulator (a later `class {}` block
    /// adds to it); past the cap a brand-new class is dropped and a THROWAWAY accumulator is returned
    /// so the caller never NPEs (its members simply never reach the IR — never throw, never unbounded).
    private static ClassAcc registerClass(LinkedHashMap<String, ClassAcc> classes, String name) {
        ClassAcc acc = classes.get(name);
        if (acc != null) {
            return acc;
        }
        if (classes.size() >= MAX_NODES) {
            return new ClassAcc();   // over-cap: a detached accumulator (its members are dropped)
        }
        ClassAcc fresh = new ClassAcc();
        classes.put(name, fresh);
        return fresh;
    }

    /// Adds one member line to a class accumulator, classified as a METHOD (carries `(`) or an
    /// ATTRIBUTE (does not). The full display text — including any `+`/`-`/`#`/`~` visibility marker —
    /// is kept and `cap()`'d; a blank member is ignored. Member lists are bounded by
    /// {@link #MAX_DATA_ROWS} so a pathological block can't allocate unboundedly (never throw).
    private static void addMember(ClassAcc acc, String member) {
        String m = member.strip();
        if (m.isEmpty()) {
            return;
        }
        List<String> target = m.indexOf('(') >= 0 ? acc.methods : acc.attributes;
        if (target.size() < MAX_DATA_ROWS) {
            target.add(cap(m));
        }
    }

    /// A located class-relation operator: byte `pos` in the head, token `len`, and the resolved
    /// {@link RelationKind}.
    private record RelationScan(int pos, int len, RelationKind kind) {}

    /// Scans a relationship head for the FIRST (leftmost) UML relation operator, trying the LONGEST
    /// token at each offset so a longer form never mis-splits. The five operators:
    /// `<|--` (inheritance), `..>` (dependency), `-->` (association), `*--` (composition),
    /// `o--` (aggregation). The `o--` aggregation token only matches when its `o` is at the start or
    /// preceded by whitespace, so a class name ending in `o` glued to `--` (`Zoo--`) does NOT spoof an
    /// aggregation. Returns null when the head carries no operator (→ the caller drops the line).
    private static RelationScan scanRelationOp(String head) {
        int n = head.length();
        for (int i = 0; i < n; i++) {
            if (head.startsWith("<|--", i)) {
                return new RelationScan(i, 4, RelationKind.INHERITANCE);
            }
            if (head.startsWith("..>", i)) {
                return new RelationScan(i, 3, RelationKind.DEPENDENCY);
            }
            if (head.startsWith("-->", i)) {
                return new RelationScan(i, 3, RelationKind.ASSOCIATION);
            }
            if (head.startsWith("*--", i)) {
                return new RelationScan(i, 3, RelationKind.COMPOSITION);
            }
            if (head.startsWith("o--", i) && (i == 0 || Character.isWhitespace(head.charAt(i - 1)))) {
                return new RelationScan(i, 3, RelationKind.AGGREGATION);
            }
        }
        return null;
    }

    /// A mutable member accumulator on the parse map: attributes + methods grow as member lines
    /// arrive inside a `class { … }` block; frozen into a {@link ClassBox} at end-of-parse.
    /// Parse-internal, never surfaced in the IR.
    private static final class ClassAcc {
        final List<String> attributes = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
    }

    /// The reserved key markers on an ER attribute row (`PK` primary / `FK` foreign / `UK` unique).
    /// Matched case-insensitively; any other trailing token is not a key (ignored).
    private static String erKeyMarker(String tok) {
        String u = tok.toUpperCase(java.util.Locale.ROOT);
        return (u.equals("PK") || u.equals("FK") || u.equals("UK")) ? u : null;
    }

    /// Parses a mermaid-style entity-relationship diagram — entity TABLES + crow-foot relationships.
    /// ```
    /// erDiagram
    ///   CUSTOMER ||--o{ ORDER : places
    ///   ORDER ||--|{ LINE-ITEM : contains
    ///   CUSTOMER {
    ///     string name PK
    ///     string email
    ///   }
    /// ```
    /// An `ENTITY {` line OPENS an attribute block (mirrors the class-diagram `class X { }` block);
    /// every line until a lone `}` is an attribute row `type name [PK|FK|UK]` (whitespace-split: the
    /// first token is the type, the second the name, and any later `PK`/`FK`/`UK` token the key — a
    /// single-token row has an EMPTY type and the token as its name). A bare `ENTITY` (no brace) — or
    /// an entity named only in a relationship — is an attribute-less name box.
    ///
    /// OUTSIDE a block a line is a RELATIONSHIP `LEFT <leftCard>OP<rightCard> RIGHT [: label]` where
    /// OP is `--` (identifying, solid) or `..` (non-identifying, dashed) and each two-char cardinality
    /// is one of the crow-foot tokens ({@link #scanErOperator}). Each operand references an entity,
    /// auto-vivified as an attribute-less entity when never given a block (mermaid semantics — so a
    /// relationship to an unblocked `ADDRESS` still renders it). Entities register in FIRST-SEEN order
    /// (declared or referenced).
    ///
    /// Robustness (DESIGN §6, never throw): a line that is neither an entity directive, an attribute
    /// (inside a block), nor a well-formed relationship is DROPPED. A relationship with an empty
    /// endpoint or an unrecognized cardinality drops. An UNCLOSED `{` at end-of-input CLOSES gracefully
    /// at EOF (the entity keeps the rows gathered so far) rather than throwing. Caps: {@link #MAX_NODES}
    /// entities, {@link #MAX_EDGES} relationships, {@link #MAX_DATA_ROWS} rows per entity; names/rows
    /// `cap()`'d. Empty body → an ErDiagram with no entities (round-trips, NOT degraded to Empty).
    private static Diagram parseErDiagram(String[] lines, String textColor) {
        // Insertion-ordered name → attribute accumulator: preserves first-seen entity order (declared
        // or relationship-referenced). A relationship-referenced name auto-vivifies an empty one.
        LinkedHashMap<String, List<ErAttribute>> entities = new LinkedHashMap<>();
        List<ErRelation> relations = new ArrayList<>();
        List<ErAttribute> open = null;   // the currently-open `ENTITY { … }` block, or null when outside
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (open != null) {
                // Inside an attribute block: a lone `}` closes it; anything else is an attribute row.
                if (line.equals("}")) {
                    open = null;
                    continue;
                }
                // A trailing `}` glued to the last row (`int age }`) also closes the block.
                String row = line;
                boolean closes = false;
                if (row.endsWith("}")) {
                    row = row.substring(0, row.length() - 1).strip();
                    closes = true;
                }
                addErAttribute(open, row);
                if (closes) {
                    open = null;
                }
                continue;
            }
            // Outside a block. Try a relationship first (its operator is unambiguous); otherwise the
            // line is an entity directive (`ENTITY` or `ENTITY {`).
            String[] peeled = peelLabel(line);
            String head = peeled[0];
            String label = peeled[1] == null ? null : cap(peeled[1]);
            ErOpScan op = scanErOperator(head);
            if (op != null) {
                String left = cap(head.substring(0, op.pos()).strip());
                String right = cap(head.substring(op.pos() + op.len()).strip());
                if (left.isEmpty() || right.isEmpty()) {
                    continue;   // an empty endpoint → malformed relation, drop (never throw)
                }
                registerEntity(entities, left);
                registerEntity(entities, right);
                if (relations.size() < MAX_EDGES
                    && entities.containsKey(left) && entities.containsKey(right)) {
                    relations.add(new ErRelation(left, right, op.leftCard(), op.rightCard(),
                        op.identifying(), label));
                }
                continue;
            }
            // Not a relationship → an entity directive: `ENTITY {` opens a block, a bare `ENTITY`
            // declares a name box. In BOTH forms the entity name must be a SINGLE whitespace-free token
            // (a valid identifier). This drops the malformed-relation residue whose stray `{` (a
            // cardinality char like `o{`, e.g. `X |bad--o{ Y`) would otherwise spoof a block-open — the
            // pre-brace `X |bad--o` has spaces, so it is not a name. A quoted multi-word entity name is
            // not supported in v1 (RESIDUAL). No colon-peel here: a bare entity carries no label.
            int brace = line.indexOf('{');
            if (brace < 0) {
                // Bare `ENTITY` — an attribute-less entity, only when the whole line is one clean token.
                String name = cap(line.strip());
                if (isSingleToken(name)) {
                    registerEntity(entities, name);
                }
                continue;   // multi-token / empty → garbage line, drop (never throw)
            }
            String name = cap(line.substring(0, brace).strip());
            if (!isSingleToken(name)) {
                continue;   // no clean name before `{` → malformed, drop (and do NOT open a block)
            }
            List<ErAttribute> acc = registerEntity(entities, name);
            // `ENTITY {}` / `{ }` on one line opens-and-closes empty; otherwise the block stays open
            // for the following rows. Inline rows after `{` on the SAME line are not supported (ignored).
            String afterBrace = line.substring(brace + 1).strip();
            open = afterBrace.startsWith("}") ? null : acc;
        }
        List<ErEntity> table = new ArrayList<>();
        for (Map.Entry<String, List<ErAttribute>> e : entities.entrySet()) {
            table.add(new ErEntity(e.getKey(), e.getValue()));
        }
        return new ErDiagram(table, relations, textColor);
    }

    /// Registers an ER entity by name in first-seen order (up to {@link #MAX_NODES}), returning its
    /// attribute accumulator. An already-seen name returns the existing list (a later `{}` block adds
    /// to it); past the cap a brand-new entity is dropped and a THROWAWAY list is returned so the
    /// caller never NPEs (its rows simply never reach the IR — never throw, never unbounded).
    private static List<ErAttribute> registerEntity(LinkedHashMap<String, List<ErAttribute>> entities,
                                                    String name) {
        List<ErAttribute> acc = entities.get(name);
        if (acc != null) {
            return acc;
        }
        if (entities.size() >= MAX_NODES) {
            return new ArrayList<>();   // over-cap: a detached list (its rows are dropped)
        }
        List<ErAttribute> fresh = new ArrayList<>();
        entities.put(name, fresh);
        return fresh;
    }

    /// Adds one attribute row `type name [PK|FK|UK]` to an entity accumulator. Whitespace-split: the
    /// first token is the type, the second the name; a single-token row has an EMPTY type and the token
    /// as its name (a bare `id` still renders). The FIRST later `PK`/`FK`/`UK` token (any case) becomes
    /// the key. A blank row is ignored. Rows are bounded by {@link #MAX_DATA_ROWS} so a pathological
    /// block can't allocate unboundedly (never throw). Type/name/key are `cap()`'d.
    private static void addErAttribute(List<ErAttribute> acc, String row) {
        String r = row.strip();
        if (r.isEmpty() || acc.size() >= MAX_DATA_ROWS) {
            return;
        }
        String[] toks = r.split("\\s+");
        String type;
        String name;
        int keyFrom;
        if (toks.length == 1) {
            type = "";
            name = toks[0];
            keyFrom = 1;
        } else {
            type = toks[0];
            name = toks[1];
            keyFrom = 2;
        }
        String key = null;
        for (int i = keyFrom; i < toks.length; i++) {
            String k = erKeyMarker(toks[i]);
            if (k != null) {
                key = k;
                break;   // first key marker wins
            }
        }
        acc.add(new ErAttribute(cap(type), cap(name), key));
    }

    /// True iff `s` is a single non-empty token with no interior whitespace — the shape a bare/blocked
    /// ER entity name must take (a `LINE-ITEM` passes; a `X |bad--o` with spaces does not). Guards the
    /// entity-directive path from adopting malformed-relation residue as a spurious entity.
    private static boolean isSingleToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /// A located ER relationship operator: byte `pos` in the head (where the LEFT cardinality begins),
    /// token `len` (leftCard + `--`/`..` + rightCard, always 6), the two resolved cardinalities, and
    /// whether the operator is identifying (solid `--`) vs non-identifying (dashed `..`).
    private record ErOpScan(int pos, int len, ErCardinality leftCard, ErCardinality rightCard,
                            boolean identifying) {}

    /// Scans a relationship head for the FIRST (leftmost) crow-foot operator: a two-char LEFT
    /// cardinality, then `--` (identifying) or `..` (non-identifying), then a two-char RIGHT
    /// cardinality — e.g. `||--o{`, `}o..o|`. The two-char cardinalities are validated against the
    /// crow-foot token tables ({@link #leftCardinality}/{@link #rightCardinality}); a `--`/`..` whose
    /// flanking chars are not valid cardinalities is NOT an operator (so a hyphen inside an entity name
    /// like `LINE-ITEM` never spoofs one — its `-` is single, and `LINE--ITEM` would need cardinality
    /// chars around it). Returns null when the head carries no well-formed operator (→ the caller treats
    /// the line as an entity directive, then drops it if that fails too — never throws).
    private static ErOpScan scanErOperator(String head) {
        int n = head.length();
        for (int i = 2; i + 4 <= n; i++) {
            boolean solid = head.charAt(i) == '-' && head.charAt(i + 1) == '-';
            boolean dashed = head.charAt(i) == '.' && head.charAt(i + 1) == '.';
            if (!solid && !dashed) {
                continue;
            }
            ErCardinality left = leftCardinality(head.substring(i - 2, i));
            ErCardinality right = rightCardinality(head.substring(i + 2, i + 4));
            if (left != null && right != null) {
                return new ErOpScan(i - 2, 6, left, right, solid);
            }
        }
        return null;
    }

    /// The LEFT-end crow-foot cardinality token (the entity is to the LEFT, so the outer symbol is the
    /// SECOND char): `|o`→zero-or-one, `||`→exactly-one, `}o`→zero-or-many, `}|`→one-or-many. Any other
    /// two-char string is not a cardinality (returns null).
    private static ErCardinality leftCardinality(String tok) {
        return switch (tok) {
            case "|o" -> ErCardinality.ZERO_OR_ONE;
            case "||" -> ErCardinality.EXACTLY_ONE;
            case "}o" -> ErCardinality.ZERO_OR_MANY;
            case "}|" -> ErCardinality.ONE_OR_MANY;
            default -> null;
        };
    }

    /// The RIGHT-end crow-foot cardinality token (the entity is to the RIGHT, so the tokens mirror the
    /// left ones): `o|`→zero-or-one, `||`→exactly-one, `o{`→zero-or-many, `|{`→one-or-many. Any other
    /// two-char string is not a cardinality (returns null).
    private static ErCardinality rightCardinality(String tok) {
        return switch (tok) {
            case "o|" -> ErCardinality.ZERO_OR_ONE;
            case "||" -> ErCardinality.EXACTLY_ONE;
            case "o{" -> ErCardinality.ZERO_OR_MANY;
            case "|{" -> ErCardinality.ONE_OR_MANY;
            default -> null;
        };
    }

    /// Parses the shared `"label" : value` rows (used by both pie and xychart). A malformed row
    /// is skipped rather than failing the whole diagram (docs/DESIGN.md §6: never fail the bake).
    private static List<Slice> parseData(String[] lines, boolean dateAware) {
        List<Slice> data = new ArrayList<>();
        for (int i = 1; i < lines.length && data.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = cap(unquote(line.substring(0, colon).strip()));
            // A data value is a SINGLE token, optionally followed by a trailing colour token:
            // `"Reviews" : 40 #4e79a7` → value=`40`, colour=`#4e79a7`. Split off the first token as
            // the value; if a trailing token follows AND matches the COLOR grammar it becomes the
            // per-item fill (normalized to canonical `#rrggbb`). A trailing token that is NOT a colour
            // (a typo) is simply IGNORED — the value still parses → palette fallback, never fails bake.
            String[] tok = line.substring(colon + 1).strip().split("\\s+", 2);
            String valueTok = tok[0];
            String color = null;
            if (tok.length == 2) {
                String tail = tok[1].strip();
                if (SirentideContract.isHexColor(tail)) {   // per-item fill is hex-only (H1): currentColor/none fall through to palette
                    color = SirentideContract.normalizeColor(tail);
                }
            }
            try {
                // Date-aware rows (timeline) map ISO dates to epoch-day for proportional placement;
                // a bare year / plain number parses as its numeric value either way.
                double value = dateAware
                    ? AxisScale.parseDomainValue(valueTok)
                    : Double.parseDouble(valueTok);
                // Non-finite (NaN / Infinity, incl. "1e400" == Infinity which throws NO exception)
                // must not reach layout/emit — skip the row (never fail the bake).
                if (!Double.isFinite(value)) {
                    continue;
                }
                // An ISO-date token became an opaque epoch-day for placement; remember the original
                // so the timeline shows `2020-01-15`, not the epoch number `18276` (A2). A bare
                // year / plain number keeps a null valueLabel and formats numerically as before.
                String valueLabel = (dateAware && AxisScale.isDateToken(valueTok)) ? valueTok : null;
                data.add(new Slice(label, value, color, valueLabel));
            } catch (NumberFormatException e) {
                // Skip a malformed row.
            }
        }
        return data;
    }

    /// Peels an OPTIONAL trailing colour token off a stripped data value (the text after the `:`).
    /// Returns `{valuePart, colorOrNull}`: if the LAST whitespace-separated token matches the
    /// SirentideContract COLOR grammar it is removed and returned (normalized to canonical `#rrggbb`,
    /// so a 3-digit `#333` becomes `#333333` on the IR); the leading value part is returned stripped.
    /// If there is no trailing colour token (or the input is a single token), the value is returned
    /// unchanged with a `null` colour — an invalid/unparseable trailing token is NEVER treated as a
    /// colour (it stays on the value, whose numeric parse then rejects it → palette fallback, no throw).
    private static String[] peelColor(String tok) {
        int start = tok.length();
        while (start > 0 && !Character.isWhitespace(tok.charAt(start - 1))) {
            start--;
        }
        // start > 0 means there is at least one whitespace char before the final token.
        if (start > 0) {
            String tail = tok.substring(start);
            if (SirentideContract.isHexColor(tail)) {   // per-item fill is hex-only (H1): currentColor/none fall through to palette
                return new String[] {tok.substring(0, start).strip(), SirentideContract.normalizeColor(tail)};
            }
        }
        return new String[] {tok, null};
    }

    /// The reserved leading journey keywords: `title <text>` sets the (single) diagram title, `section
    /// <name>` opens a new task group. Any other leading token is a TASK row (`<name>: <score>: <actor>…`).
    private static final String KW_TITLE = "title";
    private static final String KW_SECTION = "section";

    /// Parses a mermaid-style `journey` — a user-journey satisfaction map.
    /// ```
    /// journey
    ///   title My working day
    ///   section Go to work
    ///     Make tea: 5: Me
    ///     Commute: 3: Me, Cat
    ///   section Do work
    ///     Code: 5: Me
    ///     Meetings: 2: Me, Boss
    /// ```
    /// An optional `title` line names the diagram; a `section <name>` line opens a group; every other
    /// non-blank line is a task `<name>: <score 1-5>: <actor>[, <actor2>…]`. Robustness (DESIGN §6,
    /// never fails the bake): a task with NO numeric score is DROPPED; a task appearing BEFORE any
    /// `section` is DROPPED (it has no group to join); an out-of-range score is CLAMPED into 1..5 (in
    /// {@link JourneyTask}); the actor list is optional (a scoreless-but-actorless task keeps an empty
    /// actor list). A bare `journey` body (no sections/tasks) round-trips as a Journey (never Empty).
    private static Diagram parseJourney(String[] lines, String textColor) {
        String title = null;
        List<JourneySection> sections = new ArrayList<>();
        String sectionName = null;
        List<JourneyTask> current = null;   // null until the first `section` opens one
        int taskCount = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] kw = splitKeyword(line);
            if (kw[0].equals(KW_TITLE)) {
                String t = cap(kw[1].strip());
                title = t.isEmpty() ? null : t;
                continue;
            }
            if (kw[0].equals(KW_SECTION)) {
                if (current != null) {
                    sections.add(new JourneySection(sectionName, current));
                }
                sectionName = cap(kw[1].strip());
                current = new ArrayList<>();
                continue;
            }
            // A task row (`<name>: <score>: <actor>…`). Dropped if malformed OR outside any section.
            JourneyTask task = parseJourneyTask(line);
            if (task == null || current == null || taskCount >= MAX_DATA_ROWS) {
                continue;
            }
            current.add(task);
            taskCount++;
        }
        if (current != null) {
            sections.add(new JourneySection(sectionName, current));
        }
        return new Journey(title, sections, textColor);
    }

    /// Parses a mindmap: an INDENTATION-defined hierarchy tree.
    /// ```
    /// mindmap
    ///   root Root idea
    ///     Origins
    ///       Long history
    ///     Tools
    ///       Mermaid
    /// ```
    /// The FIRST non-blank body line is the ROOT (its indent is the baseline); each later line's
    /// LEADING-SPACE depth ({@link #leadingIndent}, a tab = {@link #MINDMAP_TAB_WIDTH} columns) folds
    /// it under the nearest OPEN ancestor with a STRICTLY-SMALLER indent — a deeper line is that
    /// ancestor's child, a line at the same-or-shallower indent pops back to it. A node's text is the
    /// stripped line; the root strips an OPTIONAL leading `root ` keyword (mermaid convention) so
    /// `root Root idea` reads "Root idea" (a bare `root` yields an empty-text root, still a node).
    ///
    /// Malformed → inert, never throws (DESIGN §6): a line indented at-or-shallower than the root
    /// attaches to the ROOT (the stack never pops below it — mermaid allows one root, so extra
    /// top-level lines become root children); inconsistent indentation SNAPS to the nearest shallower
    /// open ancestor (no exact-multiple requirement); a bare `mindmap` body (no non-blank line) →
    /// a `null`-root Mindmap (an empty tree, round-trips as a mindmap). Caps (never OOM):
    /// {@link #MAX_DATA_ROWS} total nodes, {@link #MAX_MINDMAP_DEPTH} depth (a deeper node is dropped,
    /// its subtree re-parenting to the last valid ancestor); text `cap()`'d.
    private static Diagram parseMindmap(String[] lines, String textColor) {
        MindmapBuilder root = null;
        // The stack of OPEN ancestors (innermost on top). The root stays at the bottom and is never
        // popped, so a line at ≤ the root indent lands on it. `push`/`pop`/`peek` is a LIFO stack.
        Deque<MindmapBuilder> stack = new ArrayDeque<>();
        int nodeCount = 0;
        for (int i = 1; i < lines.length; i++) {
            String rawLine = lines[i];   // RAW — indentation is measured before stripping
            String text = rawLine.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (nodeCount >= MAX_DATA_ROWS) {
                break;   // node cap — stop folding (never allocate unboundedly / never throw)
            }
            int indent = leadingIndent(rawLine);
            if (root == null) {
                // The first non-blank body line is the root; strip an optional leading `root` keyword.
                root = new MindmapBuilder(cap(stripRootKeyword(text)), indent, 0);
                stack.push(root);
                nodeCount++;
                continue;
            }
            // Pop every open ancestor whose indent is ≥ this line's — the survivor (never below the
            // root) is the nearest STRICTLY-shallower open node, i.e. this line's parent.
            while (stack.size() > 1 && stack.peek().indent >= indent) {
                stack.pop();
            }
            MindmapBuilder parent = stack.peek();
            if (parent.depth + 1 > MAX_MINDMAP_DEPTH) {
                continue;   // over-deep → drop (its subtree re-parents to the last valid ancestor)
            }
            MindmapBuilder node = new MindmapBuilder(cap(text), indent, parent.depth + 1);
            parent.children.add(node);
            stack.push(node);
            nodeCount++;
        }
        return new Mindmap(root == null ? null : root.freeze(), textColor);
    }

    /// Parses a mermaid-style `sankey-beta` — CSV-ish `source,target,value` rows into a
    /// {@link Sankey}. Each body line is split on COMMAS into exactly three fields; each is stripped.
    /// ```
    /// sankey
    ///   Coal,Electricity,25
    ///   Gas,Electricity,15
    ///   Electricity,Homes,20
    /// ```
    /// A row is DROPPED WHOLE (loud-not-silent, never throws — DESIGN §6) when it is malformed: not
    /// exactly three comma-fields, an empty source or target, a non-numeric / non-finite / non-positive
    /// value (zero or negative — a band needs a positive width), or a SELF-flow (`A,A` — a node flowing
    /// to itself has no column separation to draw a band across, so it is dropped, documented). Fields
    /// are `cap()`'d. Flows past {@link #MAX_DATA_ROWS} are dropped (bounds the band + node count). An
    /// empty body (no valid rows) → a Sankey with no flows — still a sankey (round-trips), laid out to a
    /// minimal inert canvas, NOT degraded to Empty.
    private static Diagram parseSankey(String[] lines, String textColor) {
        List<SankeyFlow> flows = new ArrayList<>();
        for (int i = 1; i < lines.length && flows.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            // Split into at most 4 so a 4th comma-field is detected as malformed (not silently joined).
            String[] parts = line.split(",", -1);
            if (parts.length != 3) {
                continue;   // not exactly source,target,value → malformed, drop
            }
            String source = cap(parts[0].strip());
            String target = cap(parts[1].strip());
            String valueTok = parts[2].strip();
            if (source.isEmpty() || target.isEmpty()) {
                continue;   // a missing endpoint → malformed, drop
            }
            if (source.equals(target)) {
                continue;   // a self-flow (A,A) → dropped (no column separation to draw a band); documented
            }
            double value;
            try {
                value = Double.parseDouble(valueTok);
            } catch (NumberFormatException e) {
                continue;   // non-numeric value → malformed, drop (never fail the bake)
            }
            // Non-finite (NaN / Infinity, incl. "1e400" == Infinity which throws NO exception) or a
            // non-positive value (zero/negative — a band must have a positive width) → drop the row.
            if (!Double.isFinite(value) || value <= 0) {
                continue;
            }
            flows.add(new SankeyFlow(source, target, value));
        }
        return new Sankey(flows, textColor);
    }

    /// The leading-INDENT column count of a raw line: a space is 1 column, a tab is
    /// {@link #MINDMAP_TAB_WIDTH} columns; the scan stops at the first non-whitespace char. Fixed,
    /// deterministic — mixing tabs + spaces yields a stable depth (never throws).
    private static int leadingIndent(String line) {
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                col++;
            } else if (c == '\t') {
                col += MINDMAP_TAB_WIDTH;
            } else {
                break;
            }
        }
        return col;
    }

    /// Strips an OPTIONAL leading `root` keyword from the ROOT line's text (mermaid convention): a
    /// bare `root` → "" (an empty-text root), `root Root idea` → "Root idea"; any other first token
    /// leaves the whole text as the root label. Only ever applied to the first node.
    private static String stripRootKeyword(String text) {
        if (text.equals("root")) {
            return "";
        }
        if (text.startsWith("root ") || text.startsWith("root\t")) {
            return text.substring("root".length()).strip();
        }
        return text;
    }

    /// A mutable mindmap-tree accumulator on the parse stack: text/indent/depth fixed at creation,
    /// `children` grow as deeper lines fold under it; `freeze` snapshots it (recursively) to an
    /// immutable {@link MindmapNode}. Parse-internal, never surfaced in the IR. The recursion is
    /// bounded by {@link #MAX_MINDMAP_DEPTH} (the parser drops deeper nodes), so `freeze` is safe.
    private static final class MindmapBuilder {
        final String text;
        final int indent;
        final int depth;
        final List<MindmapBuilder> children = new ArrayList<>();

        MindmapBuilder(String text, int indent, int depth) {
            this.text = text;
            this.indent = indent;
            this.depth = depth;
        }

        MindmapNode freeze() {
            List<MindmapNode> kids = new ArrayList<>();
            for (MindmapBuilder c : children) {
                kids.add(c.freeze());
            }
            return new MindmapNode(text, kids);
        }
    }

    /// Parses one journey task row `<name>: <score>: <actor>[, <actor2>…]` into a {@link JourneyTask},
    /// or null when malformed (→ the caller drops it, inert). The FIRST colon peels the task name; the
    /// NEXT colon peels the score from the actor list (a row with only one colon has a score but no
    /// actors). The score must parse as a finite number (else the row is dropped) and is clamped into
    /// 1..5 by {@link JourneyTask}. Actors are comma-split, `cap()`'d, empties dropped. Never throws.
    private static JourneyTask parseJourneyTask(String line) {
        int c1 = line.indexOf(':');
        if (c1 < 0) {
            return null;   // no colon → not a task row, drop
        }
        String name = cap(line.substring(0, c1).strip());
        if (name.isEmpty()) {
            return null;   // an empty task name → malformed, drop
        }
        String rest = line.substring(c1 + 1).strip();
        int c2 = rest.indexOf(':');
        String scoreTok = (c2 < 0 ? rest : rest.substring(0, c2)).strip();
        String actorsPart = c2 < 0 ? "" : rest.substring(c2 + 1).strip();
        double raw;
        try {
            raw = Double.parseDouble(scoreTok);
        } catch (NumberFormatException e) {
            return null;   // no numeric score → malformed, drop (never fail the bake)
        }
        if (!Double.isFinite(raw)) {
            return null;   // NaN/Infinity score → drop
        }
        int score = (int) Math.max(1, Math.min(5, Math.round(raw)));   // clamp into 1..5 (documented)
        List<String> actors = new ArrayList<>();
        for (String a : actorsPart.split(",")) {
            String s = cap(a.strip());
            if (!s.isEmpty()) {
                actors.add(s);
            }
        }
        return new JourneyTask(name, score, actors);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /// Truncates an over-long label to the trust cap so a pathological label can't blow up the
    /// glyph run / output size (DESIGN §6/§7: bounded, inert degrade — never throw).
    private static String cap(String label) {
        return label.length() > MAX_LABEL_LEN ? label.substring(0, MAX_LABEL_LEN) : label;
    }

    /// The reserved leading gitGraph keywords. A body line's FIRST whitespace-delimited token selects
    /// the op; anything else is dropped (malformed→inert, DESIGN §6).
    private static final String KW_COMMIT = "commit";
    private static final String KW_BRANCH = "branch";
    private static final String KW_CHECKOUT = "checkout";
    private static final String KW_MERGE = "merge";

    /// Parses a mermaid-style `gitGraph` — a commit history as an ORDERED op list. The IR carries the
    /// ops verbatim; the LANE/colour/connector geometry is derived at layout time by replaying them
    /// ({@link com.sirentide.ir.GitGraph}), so the parser is a thin tokenizer.
    /// ```
    /// gitGraph
    ///   commit
    ///   commit id: "fix"
    ///   branch develop
    ///   checkout develop
    ///   commit
    ///   checkout main
    ///   merge develop
    ///   commit
    /// ```
    /// Each body line's first token selects the op: `commit [id: "x"]` (an optional quoted/bare id
    /// label), `branch <name>`, `checkout <name>`, `merge <name>`. Names/ids are `cap()`'d.
    ///
    /// Robustness (DESIGN §6, never throw): a line whose first token is none of the four keywords is
    /// DROPPED (inert). A `branch`/`checkout`/`merge` with an EMPTY name is dropped (there is nothing to
    /// name). The SEMANTIC malformed cases — a commit before any branch (→ implicit `main`), a
    /// checkout/merge of an UNKNOWN branch, a DUPLICATE branch, a SELF-merge — are NOT dropped here: they
    /// stay as ops and are resolved INERTLY at replay (layout), so the parse stays a pure tokenize.
    /// Ops past {@link #MAX_DATA_ROWS} are dropped (bounded, never allocates unboundedly). Empty body →
    /// a GitGraph with no ops (round-trips, NOT degraded to Empty).
    private static Diagram parseGitGraph(String[] lines, String textColor) {
        List<GitOp> ops = new ArrayList<>();
        for (int i = 1; i < lines.length && ops.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] kwRest = splitKeyword(line);
            String kw = kwRest[0];
            String rest = kwRest[1];   // "" when the keyword stands alone (a bare `commit`)
            switch (kw) {
                case KW_COMMIT -> ops.add(new GitOp.Commit(parseCommitId(rest)));
                case KW_BRANCH -> {
                    String name = cap(gitName(rest));
                    if (!name.isEmpty()) {
                        ops.add(new GitOp.Branch(name));
                    }
                    // an empty branch name (`branch` alone) has nothing to name → drop (inert)
                }
                case KW_CHECKOUT -> {
                    String name = cap(gitName(rest));
                    if (!name.isEmpty()) {
                        ops.add(new GitOp.Checkout(name));
                    }
                }
                case KW_MERGE -> {
                    String name = cap(gitName(rest));
                    if (!name.isEmpty()) {
                        ops.add(new GitOp.Merge(name));
                    }
                }
                default -> {
                    // not a gitGraph keyword → drop the line (malformed→inert, never throws)
                }
            }
        }
        return new GitGraph(ops, textColor);
    }

    /// The optional id label off a `commit` tail: `id: "fix"`, `id:fix`, or `id "fix"` all yield
    /// `fix`; a bare `commit` (empty tail) yields null (an unlabeled dot). A tail that does NOT start
    /// with the `id` marker is ALSO taken as the id (mermaid tolerates `commit "fix"`), unquoted +
    /// `cap()`'d. A blank result is null. Never throws.
    private static String parseCommitId(String rest) {
        String s = rest.strip();
        if (s.isEmpty()) {
            return null;
        }
        // Strip an optional leading `id` marker, then an optional `:` — so `id:`, `id :`, `id`, and a
        // bare quoted string all reduce to the id token.
        if (s.equals("id")) {
            return null;
        }
        if (s.startsWith("id:")) {
            s = s.substring(3).strip();
        } else if (s.startsWith("id ") || s.startsWith("id\t")) {
            s = s.substring(2).strip();
            if (s.startsWith(":")) {
                s = s.substring(1).strip();
            }
        }
        String id = cap(unquote(s));
        return id.isEmpty() ? null : id;
    }

    /// A gitGraph branch/merge/checkout name: the FIRST whitespace-delimited token of the tail,
    /// unquoted (a quoted `"feature x"` keeps its interior as one name). Trailing tokens (a mermaid
    /// `merge develop id: "m1"`) are IGNORED in v1 (RESIDUAL). Blank → "".
    private static String gitName(String rest) {
        String s = rest.strip();
        if (s.isEmpty()) {
            return "";
        }
        if (s.startsWith("\"")) {
            return unquote(s);   // a quoted name may contain spaces; take it whole
        }
        int sp = s.indexOf(' ');
        int tab = s.indexOf('\t');
        int cut = sp < 0 ? tab : (tab < 0 ? sp : Math.min(sp, tab));
        return cut < 0 ? s : s.substring(0, cut);
    }

    /// Parses a `mathblock`: the WHOLE body (every line after the header) is ONE raw LaTeX
    /// expression. It is NOT `$…$`-split — a mathblock body is all math, so there are no delimiters;
    /// the joined body IS the expression (plan sirentide-mathblock). Body lines are stripped and
    /// joined with a single space (LaTeX treats a newline as ordinary whitespace, so a multi-line
    /// expression joins cleanly), blank lines dropped. The result is `cap()`'d to {@link #MAX_LABEL_LEN}
    /// (the same length bound every author string obeys — a demo equation fits comfortably); an empty
    /// body yields a `MathBlock` with empty latex, which layout renders as the inert empty canvas.
    /// Never throws (DESIGN §6).
    private static Diagram parseMathBlock(String[] lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(line);
        }
        return new MathBlock(cap(sb.toString()));
    }
}
